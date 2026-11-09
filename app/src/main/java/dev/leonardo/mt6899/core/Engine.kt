package dev.leonardo.mt6899.core

/**
 * Núcleo do gerenciador (MT6899). Todas as chamadas são bloqueantes: use em thread de IO.
 *
 * Chaves do Store usadas aqui:
 *  active, lock, mode:<path>, profiles, disabled_limits, thermal_paused, snap:*,
 *  guard, g_soc, g_skin, g_batt, prof_ctrl (perfis controlam tela/rede)
 */
class Engine(
    private val sh: Shell,
    val store: Store,
    private val logger: (String) -> Unit = {}
) {
    companion object {
        const val BATT = "/sys/class/power_supply/battery"
        const val GED = "/sys/kernel/ged/hal"
        const val GED_BOOST = "/sys/kernel/ged/hal/custom_boost_gpu_freq"
        const val GED_CUR = "/sys/kernel/ged/hal/current_freqency"
        const val GED_UP = "/sys/kernel/ged/hal/custom_upbound_gpu_freq"
        const val GED_UTIL = "/sys/kernel/ged/hal/gpu_utilization"
        const val GPU_FIX = "/proc/gpufreqv2/fix_target_opp_index"
        const val GPU_LIMIT_TABLE = "/proc/gpufreqv2/limit_table"
        const val GPU_OPP_TABLE = "/proc/gpufreqv2/gpu_working_opp_table"
        const val GPU_STATUS = "/proc/gpufreqv2/gpufreq_status"
        const val KTHERMAL = "/sys/kernel/thermal"

        val TEMP_ZONES = listOf(
            "soc_max", "cpu-big-core7-0", "cpu-medium-core4-0", "cpu-little-core0", "gpu0",
            "quiet_therm", "display_therm", "battery", "wifi_therm", "flash_therm"
        )
    }

    @Volatile
    var info: SocInfo? = null
        private set

    private fun log(s: String) = logger(s)

    private fun pol(id: Int) = "/sys/devices/system/cpu/cpufreq/policy$id"

    // ───────────────────────── leitura básica ─────────────────────────

    fun readMany(paths: List<String>): Map<String, String?> {
        if (paths.isEmpty()) return emptyMap()
        val sb = StringBuilder("for p in ")
        for (p in paths) sb.append(q(p)).append(' ')
        sb.append("; do if [ -e \"\$p\" ]; then echo \"@@E\$p\"; head -c 4000 \"\$p\" 2>/dev/null; echo; else echo \"@@N\$p\"; fi; done")
        val out = sh.exec(sb.toString()).out
        val map = LinkedHashMap<String, String?>()
        var cur: String? = null
        var exists = false
        val buf = StringBuilder()
        fun flush() {
            val c = cur ?: return
            map[c] = if (exists) buf.toString().trimEnd() else null
        }
        for (line in out.lines()) {
            if (line.startsWith("@@E") || line.startsWith("@@N")) {
                flush()
                buf.setLength(0)
                exists = line.startsWith("@@E")
                cur = line.substring(3)
            } else {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(line)
            }
        }
        flush()
        return paths.associateWith { map[it] }
    }

    fun readOne(path: String): String? = readMany(listOf(path))[path]

    private fun prop(k: String): String = sh.exec("getprop $k").out.trim()

    // ───────────────────────── detecção ─────────────────────────

    fun detect(): SocInfo {
        val polIds = sh.exec("ls -d /sys/devices/system/cpu/cpufreq/policy* 2>/dev/null").out.lines()
            .mapNotNull { it.trim().substringAfterLast("policy").toIntOrNull() }
            .sorted()
        val polFiles = listOf(
            "related_cpus", "scaling_available_frequencies", "scaling_available_governors",
            "cpuinfo_min_freq", "cpuinfo_max_freq"
        )
        val m = readMany(polIds.flatMap { id -> polFiles.map { pol(id) + "/" + it } })
        val firstCpu = polIds.associateWith { Parse.words(m[pol(it) + "/related_cpus"]).firstOrNull() }
        val capMap = readMany(firstCpu.values.filterNotNull().map { "/sys/devices/system/cpu/cpu$it/cpu_capacity" })
        val policies = polIds.map { id ->
            val freqs = Parse.longs(m[pol(id) + "/scaling_available_frequencies"]).sorted()
            val minHw = Parse.intOf(m[pol(id) + "/cpuinfo_min_freq"]) ?: freqs.firstOrNull() ?: 0L
            val maxHw = Parse.intOf(m[pol(id) + "/cpuinfo_max_freq"]) ?: freqs.lastOrNull() ?: 0L
            val cpus = (m[pol(id) + "/related_cpus"] ?: "").trim()
            val cap = firstCpu[id]?.let {
                Parse.intOf(capMap["/sys/devices/system/cpu/cpu$it/cpu_capacity"])
            }?.toInt()
            CpuPolicy(id, cpus, cap, freqs, Parse.words(m[pol(id) + "/scaling_available_governors"]), minHw, maxHw)
        }

        val gpuInfoPath = "/sys/class/misc/mali0/device/gpuinfo"
        val g = readMany(listOf(GPU_OPP_TABLE, gpuInfoPath))
        var opps = Parse.oppTable(g[GPU_OPP_TABLE])
        val devs = sh.exec("ls /sys/class/devfreq 2>/dev/null").out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val gpuDev = detectDevfreq(devs, "mali")
        val ddr = detectDevfreq(devs, "dvfsrc")
        val ufs = detectDevfreq(devs, "ufs")
        if (opps.isEmpty() && gpuDev != null) {
            opps = gpuDev.freqs.sortedDescending().mapIndexed { i, hz -> GpuOpp(i, hz / 1000, 0L) }
        }

        val zoneOut = sh.exec(
            "for z in /sys/class/thermal/thermal_zone*; do echo \"\${z##*thermal_zone} \$(cat \"\$z/type\" 2>/dev/null)\"; done"
        ).out
        val zones = LinkedHashMap<String, Int>()
        for (line in zoneOut.lines()) {
            val parts = line.trim().split(" ", limit = 2)
            val idx = parts.getOrNull(0)?.toIntOrNull()
            val type = parts.getOrNull(1)?.trim()
            if (idx != null && !type.isNullOrEmpty() && !zones.containsKey(type)) zones[type] = idx
        }

        val blocks = sh.exec("ls /sys/block 2>/dev/null").out.lines().map { it.trim() }
            .filter { Regex("^(sd[a-z]|mmcblk\\d+|nvme\\d+n\\d+)$").matches(it) }

        val cand = candidates(policies, ddr, ufs, gpuDev, blocks)
        val raw = readMany(cand.map { it.path })
        val nodes = cand.filter { raw[it.path] != null }.map { n ->
            if (n.id.endsWith(".sched")) {
                val (_, all) = Parse.scheduler(raw[n.path])
                n.copy(kind = Kind.CHOICE, choices = all)
            } else n
        }

        var market = prop("ro.product.marketname")
        if (market.isEmpty()) market = prop("ro.product.model")
        var soc = prop("ro.soc.model")
        if (soc.isEmpty()) soc = prop("ro.board.platform")
        val device = prop("ro.product.device").ifEmpty { "?" }
        val kernel = sh.exec("uname -r").out.trim()
        val gpuName = Parse.firstLine(g[gpuInfoPath]).ifEmpty { "GPU" }

        val si = SocInfo(
            market.ifEmpty { "?" }, device, soc.ifEmpty { "?" }, kernel, gpuName,
            policies, opps, ddr, ufs, gpuDev, zones, blocks, nodes
        )
        info = si
        log("SoC ${si.socModel} · ${si.marketName} · ${policies.size} clusters · ${opps.size} OPPs GPU · ${nodes.size} nós ajustáveis")
        return si
    }

    private fun detectDevfreq(devs: List<String>, key: String): DevfreqInfo? {
        val name = devs.firstOrNull { it.contains(key, ignoreCase = true) } ?: return null
        val path = "/sys/class/devfreq/$name"
        val m = readMany(listOf("$path/available_frequencies", "$path/available_governors"))
        val f = m["$path/available_frequencies"] ?: return null
        return DevfreqInfo(path, Parse.longs(f).sorted(), Parse.words(m["$path/available_governors"]))
    }

    private fun candidates(
        policies: List<CpuPolicy>,
        ddr: DevfreqInfo?,
        ufs: DevfreqInfo?,
        gpuDev: DevfreqInfo?,
        blocks: List<String>
    ): List<NodeSpec> {
        val out = ArrayList<NodeSpec>()

        for (p in policies) {
            val first = Parse.words(p.cpus).firstOrNull() ?: continue
            val dir = "/sys/devices/system/cpu/cpu$first/core_ctl"
            val grp = "CPU · core_ctl · cluster ${p.id}"
            fun cc(file: String, label: String, hint: String = "", kind: Kind = Kind.INT, choices: List<String> = emptyList()) {
                out.add(NodeSpec("cpu${p.id}.cc.$file", label, "$dir/$file", grp, kind, Risk.CAUTION, hint, choices))
            }
            cc("enable", "core_ctl ativo", "0 = desliga o controle de núcleos deste cluster", Kind.CHOICE, listOf("0", "1"))
            cc("min_cpus", "Mínimo de CPUs online")
            cc("max_cpus", "Máximo de CPUs online")
            cc("up_thres", "Limiar para ligar núcleo (up_thres)")
            cc("cpu_busy_up_thres", "Busy up threshold")
            cc("cpu_busy_down_thres", "Busy down threshold")
        }

        out.add(NodeSpec("sched.pelt", "PELT multiplier", "/proc/sys/kernel/sched_pelt_multiplier", "Scheduler", Kind.CHOICE, Risk.CAUTION,
            "Maior = carga reage mais rápido (mais consumo). Visto: 4", listOf("1", "2", "4")))
        out.add(NodeSpec("sched.eas", "Energy Aware Scheduling", "/proc/sys/kernel/sched_energy_aware", "Scheduler", Kind.CHOICE, Risk.CAUTION,
            "", listOf("0", "1")))

        fun ged(id: String, label: String, file: String, hint: String) {
            out.add(NodeSpec(id, label, "$GED/$file", "GPU · GED", Kind.INT, Risk.CAUTION, hint))
        }
        ged("gpu.margin", "DVFS margin", "dvfs_margin_value", "Margem do DVFS da GPU. Visto: 530")
        ged("gpu.window", "Janela de carga", "loading_window_size", "Visto: 8")
        ged("gpu.stride", "Stride de carga", "loading_stride_size", "Visto: 2")
        ged("gpu.timer_margin", "Margem do timer", "timer_base_dvfs_margin", "Visto: 30")
        ged("gpu.loadmode", "Modo de loading", "dvfs_loading_mode", "Visto: 6")
        ged("gpu.boostlevel", "Boost level", "gpu_boost_level", "-1 = sem boost forçado")
        ged("gpu.fwidle", "Fallback window", "fallback_window_size", "Visto: 1")

        if (gpuDev != null) {
            val ch = gpuDev.freqs.map { it.toString() }
            out.add(NodeSpec("gpu.dev.max", "devfreq max_freq (Hz)", gpuDev.path + "/max_freq", "GPU · devfreq", Kind.CHOICE, Risk.CAUTION,
                "Teto do devfreq; o limitador térmico AP também o altera", ch))
            out.add(NodeSpec("gpu.dev.min", "devfreq min_freq (Hz)", gpuDev.path + "/min_freq", "GPU · devfreq", Kind.CHOICE, Risk.CAUTION, "", ch))
        }
        if (ddr != null) {
            val ch = ddr.freqs.map { it.toString() }
            out.add(NodeSpec("ddr.gov", "DDR governor", ddr.path + "/governor", "DDR / UFS", Kind.CHOICE, Risk.CAUTION, "", ddr.governors))
            out.add(NodeSpec("ddr.min", "DDR min (B/s)", ddr.path + "/min_freq", "DDR / UFS", Kind.CHOICE, Risk.CAUTION,
                "Subir o piso mantém a DRAM mais rápida (mais consumo, menos latência)", ch))
            out.add(NodeSpec("ddr.max", "DDR max (B/s)", ddr.path + "/max_freq", "DDR / UFS", Kind.CHOICE, Risk.CAUTION, "", ch))
        }
        if (ufs != null) {
            val ch = ufs.freqs.map { it.toString() }
            out.add(NodeSpec("ufs.gov", "UFS governor", ufs.path + "/governor", "DDR / UFS", Kind.CHOICE, Risk.CAUTION, "", ufs.governors))
            out.add(NodeSpec("ufs.min", "UFS clock mín (Hz)", ufs.path + "/min_freq", "DDR / UFS", Kind.CHOICE, Risk.CAUTION,
                "Fixar no máximo evita queda de clock do armazenamento", ch))
            out.add(NodeSpec("ufs.max", "UFS clock máx (Hz)", ufs.path + "/max_freq", "DDR / UFS", Kind.CHOICE, Risk.CAUTION, "", ch))
        }

        fun vm(id: String, label: String, file: String, hint: String = "") {
            out.add(NodeSpec(id, label, "/proc/sys/vm/$file", "Memória", Kind.INT, Risk.SAFE, hint))
        }
        vm("vm.swappiness", "swappiness", "swappiness", "0–200")
        vm("vm.vfs", "vfs_cache_pressure", "vfs_cache_pressure", "Padrão 100")
        vm("vm.dirty", "dirty_ratio", "dirty_ratio")
        vm("vm.dirty_bg", "dirty_background_ratio", "dirty_background_ratio")
        vm("vm.pagecluster", "page-cluster", "page-cluster", "0 costuma ser melhor com zRAM")
        vm("vm.wmark", "watermark_scale_factor", "watermark_scale_factor")

        for (b in blocks) {
            out.add(NodeSpec("io.$b.sched", "$b · scheduler", "/sys/block/$b/queue/scheduler", "I/O", Kind.CHOICE, Risk.CAUTION))
            out.add(NodeSpec("io.$b.ra", "$b · read_ahead_kb", "/sys/block/$b/queue/read_ahead_kb", "I/O", Kind.INT, Risk.SAFE))
        }

        out.add(NodeSpec("thermal.sports", "sports_mode", "$KTHERMAL/sports_mode", "Térmica", Kind.CHOICE, Risk.DANGER,
            "Modo esportivo do driver térmico MTK. Reduz a contenção térmica.", listOf("0", "1")))
        out.add(NodeSpec("fpsgo.enable", "FPSGO", "/sys/kernel/fpsgo/common/fpsgo_enable", "Térmica", Kind.CHOICE, Risk.CAUTION,
            "Controle de desempenho por frame do MediaTek", listOf("0", "1")))
        return out
    }

    // ───────────────────────── leitura ao vivo ─────────────────────────

    fun readLive(): Live {
        val si = info ?: detect()
        val paths = ArrayList<String>()
        for (p in si.policies) {
            paths.add(pol(p.id) + "/scaling_cur_freq")
            paths.add(pol(p.id) + "/scaling_min_freq")
            paths.add(pol(p.id) + "/scaling_max_freq")
            paths.add(pol(p.id) + "/scaling_governor")
        }
        paths.addAll(listOf(GED_CUR, GED_UTIL, GED_UP, GED_BOOST, GPU_STATUS, GPU_LIMIT_TABLE,
            "$BATT/capacity", "$BATT/temp"))
        val zonePaths = TEMP_ZONES.mapNotNull { name ->
            si.zones[name]?.let { Pair(name, "/sys/class/thermal/thermal_zone$it/temp") }
        }
        paths.addAll(zonePaths.map { it.second })
        val flagNames = listOf("is_cpu_limit", "is_gpu_limit", "is_apu_limit")
        paths.addAll(flagNames.map { "$KTHERMAL/$it" })

        val m = readMany(paths)

        val cpu = LinkedHashMap<Int, CpuLive>()
        for (p in si.policies) {
            cpu[p.id] = CpuLive(
                Parse.intOf(m[pol(p.id) + "/scaling_cur_freq"]) ?: 0L,
                Parse.intOf(m[pol(p.id) + "/scaling_min_freq"]) ?: 0L,
                Parse.intOf(m[pol(p.id) + "/scaling_max_freq"]) ?: 0L,
                Parse.firstLine(m[pol(p.id) + "/scaling_governor"])
            )
        }
        val ged = Parse.gedCurrent(m[GED_CUR])
        val status = m[GPU_STATUS]
        val ceil = Parse.ppmCeiling(status)
        val floor = Parse.ppmFloor(status)
        val temps = LinkedHashMap<String, Float>()
        for ((name, path) in zonePaths) {
            val v = Parse.intOf(m[path])
            if (v != null && v > -100000) temps[name] = v / 1000.0f
        }
        val flags = LinkedHashMap<String, String>()
        for (f in flagNames) {
            val v = m["$KTHERMAL/$f"]
            if (v != null) flags[f] = Parse.firstLine(v)
        }
        return Live(
            System.currentTimeMillis(), cpu,
            ged?.first, ged?.second,
            Parse.intOf(m[GED_UTIL])?.toInt(),
            Parse.intOf(m[GED_UP])?.toInt(),
            Parse.intOf(m[GED_BOOST])?.toInt(),
            ceil?.first, ceil?.second, floor?.first, floor?.second,
            Parse.limitTable(m[GPU_LIMIT_TABLE]),
            temps,
            Parse.intOf(m["$BATT/capacity"])?.toInt(),
            Parse.intOf(m["$BATT/temp"])?.let { it / 10.0f },
            flags
        )
    }

    fun readNodes(nodes: List<NodeSpec>): Map<String, String?> {
        val m = readMany(nodes.map { it.path })
        val out = LinkedHashMap<String, String?>()
        for (n in nodes) {
            val raw = m[n.path]
            out[n.id] = when {
                raw == null -> null
                n.id.endsWith(".sched") -> Parse.scheduler(raw).first
                n.id.startsWith("gpu.") && n.path.startsWith(GED) ->
                    Parse.intOf(raw)?.toString() ?: Parse.firstLine(raw)
                else -> Parse.firstLine(raw)
            }
        }
        return out
    }

    fun readExtras(): Map<String, String> {
        val names = listOf("boost", "balance_mode", "temp_state", "foreground_app_name", "cloud_game", "video_mode", "screen_state")
        val m = readMany(names.map { "/sys/class/thermal/thermal_message/$it" })
        val out = LinkedHashMap<String, String>()
        for (n in names) {
            val v = m["/sys/class/thermal/thermal_message/$n"]
            if (v != null) out[n] = Parse.firstLine(v)
        }
        out.putAll(readCooling())
        return out
    }

    private fun readCooling(): Map<String, String> {
        val out = sh.exec(
            "for c in /sys/class/thermal/cooling_device*; do echo \"\$(cat \"\$c/type\" 2>/dev/null)|\$(cat \"\$c/cur_state\" 2>/dev/null)|\$(cat \"\$c/max_state\" 2>/dev/null)\"; done"
        ).out
        val map = LinkedHashMap<String, String>()
        for (line in out.lines()) {
            val p = line.split("|")
            if (p.size == 3 && p[0].isNotBlank() && !p[0].startsWith("cpu-pause")) {
                map["cool:" + p[0]] = p[1] + "/" + p[2]
            }
        }
        return map
    }

    fun readDisplay(): String {
        val active = Regex("=\\s*([0-9.]+)")
            .find(sh.exec("dumpsys display 2>/dev/null | grep -m1 mActiveRenderFrameRate").out.trim())
            ?.groupValues?.get(1)
        val peak = sh.exec("settings get system peak_refresh_rate 2>/dev/null").out.trim()
        val min = sh.exec("settings get system min_refresh_rate 2>/dev/null").out.trim()
        return "ativo=${active ?: "?"} Hz · min=$min · peak=$peak"
    }

    // ───────────────────────── escrita ─────────────────────────

    private fun execWrite(path: String, value: String, lock: Boolean): ShellResult {
        var cmd = "[ -e ${q(path)} ] || exit 3; printf '%s\\n' ${q(value)} > ${q(path)}"
        if (lock) cmd += "; rc=\$?; chmod 0444 ${q(path)} 2>/dev/null; exit \$rc"
        return sh.exec(cmd)
    }

    fun write(path: String, value: String, lock: Boolean = false, verify: Boolean = true): WriteResult {
        if (lock && store.get("mode:$path") == null) {
            val mode = sh.exec("stat -c %a ${q(path)} 2>/dev/null").out.trim()
            if (mode.isNotEmpty()) store.put("mode:$path", mode)
        }
        val r = execWrite(path, value, lock)
        if (r.code == 3) {
            val wr = WriteResult(path, value, "", false, "nó inexistente")
            log(wr.line())
            return wr
        }
        val after = if (verify) (readOne(path) ?: "") else ""
        val ok = r.ok && (!verify || Parse.same(value, after))
        val errLine = r.err.lineSequence().firstOrNull { it.isNotBlank() }?.take(90) ?: ""
        val got = if (verify) Parse.firstLine(after) else (if (r.ok) value else "")
        val wr = WriteResult(path, value, got, ok, errLine)
        log(wr.line())
        return wr
    }

    fun writeNode(spec: NodeSpec, value: String): WriteResult = write(spec.path, value)

    fun lockEnabled(): Boolean = store.get("lock") == "1"

    fun resolveMax(pc: CpuPolicy, v: Long): Long {
        if (v > 0) {
            val f = pc.freqs.lastOrNull { it <= v }
            if (f != null) return f
            pc.freqs.firstOrNull()?.let { return it }
        }
        return pc.maxHw
    }

    fun resolveMin(pc: CpuPolicy, v: Long): Long {
        if (v <= 0) return pc.minHw
        val f = pc.freqs.firstOrNull { it >= v }
        if (f != null) return f
        return pc.freqs.lastOrNull() ?: pc.maxHw
    }

    fun setCpu(policy: Int, minK: Long?, maxK: Long?, gov: String?): List<WriteResult> {
        val pp = pol(policy)
        val lock = lockEnabled()
        val res = ArrayList<WriteResult>()
        if (gov != null) res.add(write("$pp/scaling_governor", gov))
        if (minK == null && maxK == null) return res
        val m = readMany(listOf("$pp/scaling_min_freq", "$pp/scaling_max_freq"))
        val curMin = Parse.intOf(m["$pp/scaling_min_freq"]) ?: 0L
        val curMax = Parse.intOf(m["$pp/scaling_max_freq"]) ?: Long.MAX_VALUE
        var newMin = minK ?: curMin
        var newMax = maxK ?: curMax
        if (maxK != null && minK == null && newMin > newMax) newMin = newMax
        if (minK != null && maxK == null && newMin > newMax) newMax = newMin
        val writeMax = !(maxK == null && newMax == curMax)
        val writeMin = minK != null || newMin != curMin
        val pMin = "$pp/scaling_min_freq"
        val pMax = "$pp/scaling_max_freq"
        if (newMax < curMin) {
            // baixar o teto abaixo do piso atual: piso primeiro
            if (writeMin) res.add(write(pMin, newMin.toString(), lock))
            if (writeMax) res.add(write(pMax, newMax.toString(), lock))
        } else {
            if (writeMax) res.add(write(pMax, newMax.toString(), lock))
            if (writeMin) res.add(write(pMin, newMin.toString(), lock))
        }
        return res
    }

    // ───────────────────────── GPU ─────────────────────────

    private fun gpuIdxForCeiling(kHz: Long): Int {
        val opps = info?.gpuOpps ?: return 0
        if (kHz <= 0 || opps.isEmpty() || kHz >= opps.first().kHz) return 0
        val o = opps.firstOrNull { it.kHz <= kHz }
        return o?.idx ?: opps.last().idx
    }

    private fun gpuIdxForFloor(kHz: Long): Int {
        val opps = info?.gpuOpps ?: return 0
        if (opps.isEmpty()) return 0
        if (kHz <= opps.last().kHz) return opps.last().idx
        val o = opps.lastOrNull { it.kHz >= kHz }
        return o?.idx ?: 0
    }

    fun setGpuCeilingKHz(kHz: Long): WriteResult = write(GED_UP, gpuIdxForCeiling(kHz).toString())

    fun setGpuFloorKHz(kHz: Long): WriteResult = write(GED_BOOST, gpuIdxForFloor(kHz).toString())

    fun fixGpuOpp(idx: Int?): WriteResult {
        val want = (idx ?: -1).toString()
        val w = write(GPU_FIX, want, verify = false)
        val cur = Parse.gpuOppIndex(readOne(GPU_STATUS))
        val ok = w.ok && (idx == null || cur == idx)
        val note = if (w.ok) {
            if (idx == null || cur == idx) "" else "GPU ociosa/limitada? confira o PPM"
        } else w.note
        val wr = WriteResult(GPU_FIX, want, "OPP atual=${cur ?: "?"}", ok, note)
        log(wr.line())
        return wr
    }

    fun setLimit(id: Int, enabled: Boolean): WriteResult {
        val value = if (enabled) "$id 1 1" else "$id 0 0"
        val w = write(GPU_LIMIT_TABLE, value, verify = false)
        val lim = Parse.limitTable(readOne(GPU_LIMIT_TABLE)).firstOrNull { it.id == id }
        val ok = w.ok && lim != null && lim.cEnable == enabled
        if (ok) {
            val set = (store.get("disabled_limits") ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
            if (enabled) set.remove(id.toString()) else set.add(id.toString())
            store.put("disabled_limits", set.joinToString(","))
        }
        val got = lim?.let { "${it.name}: c=${if (it.cEnable) 1 else 0} f=${if (it.fEnable) 1 else 0}" } ?: "?"
        val note = if (w.ok) (if (ok) "" else "o driver ignorou o comando (formato id c f)") else w.note
        val wr = WriteResult(GPU_LIMIT_TABLE, value, got, ok, note)
        log(wr.line())
        return wr
    }

    // ───────────────────────── tela / térmica ─────────────────────────

    /** hz == null devolve o controle ao sistema. */
    fun setRefresh(hz: Int?): WriteResult {
        if (hz == null) {
            sh.exec("settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings delete system user_refresh_rate")
            val wr = WriteResult("display", "auto", "auto", true)
            log(wr.line())
            return wr
        }
        val s = "$hz.0"
        val r = sh.exec("settings put system min_refresh_rate $s; settings put system peak_refresh_rate $s; settings put system user_refresh_rate $hz")
        val peak = sh.exec("settings get system peak_refresh_rate").out.trim()
        val wr = WriteResult("display", s, peak, r.ok && peak.startsWith(hz.toString()), "HyperOS pode sobrescrever")
        log(wr.line())
        return wr
    }

    fun pauseThermal(pause: Boolean): List<WriteResult> {
        val res = listOf("mi_thermald", "thermald").map { svc ->
            val r = sh.exec((if (pause) "stop " else "start ") + svc)
            val wr = WriteResult("svc:$svc", if (pause) "stop" else "start", "code=${r.code}", r.ok, r.err.take(80))
            log(wr.line())
            wr
        }
        store.put("thermal_paused", if (pause) "1" else "0")
        return res
    }

    // ───────────────────────── perfis ─────────────────────────

    /**
     * Perfis internos. Melhorias em relação à v0.1:
     *  - Economia: tira mais do cluster prime (o mais caro em energia) e preserva o little; PELT 2.
     *  - Equilibrado: limita só o topo do prime (90%), pois o último degrau custa muito e rende pouco.
     *  - Desempenho: piso moderado de CPU/GPU, UFS fixado no clock máximo, tela fixa em 120 Hz, 5G sempre liberado.
     *  - Todos (exceto Máximo) religam o limitador térmico THERMAL_AP e normalizam PELT/UFS ao trocar de perfil.
     *  - Os valores de tela/rede só são aplicados se "Perfis controlam tela e rede" estiver ligado.
     */
    fun builtins(): List<Profile> {
        val si = info ?: return emptyList()
        val ufsMin = si.ufs?.freqs?.minOrNull()?.toString()
        val ufsMax = si.ufs?.freqs?.maxOrNull()?.toString()

        fun base(caps: List<Int>, floors: List<Long>, gpuMax: Long, gpuMin: Long): LinkedHashMap<String, String> {
            val v = LinkedHashMap<String, String>()
            si.policies.forEachIndexed { i, p ->
                val gov = when {
                    p.governors.contains("sugov_ext") -> "sugov_ext"
                    p.governors.contains("schedutil") -> "schedutil"
                    else -> p.governors.firstOrNull() ?: ""
                }
                if (gov.isNotEmpty()) v["cpu${p.id}.gov"] = gov
                val cap = if (i in caps.indices) caps[i] else caps.last()
                v["cpu${p.id}.max"] = if (cap >= 100) "0" else (p.maxHw * cap / 100).toString()
                v["cpu${p.id}.min"] = (if (i in floors.indices) floors[i] else 0L).toString()
            }
            v["gpu.max_khz"] = gpuMax.toString()
            v["gpu.min_khz"] = gpuMin.toString()
            return v
        }

        fun extras(v: LinkedHashMap<String, String>, pelt: String, ufsFixed: Boolean, thermalAp: Boolean,
                   dispMode: String, hi: Int, lo: Int, net: String): LinkedHashMap<String, String> {
            if (si.node("sched.pelt") != null) v["node:sched.pelt"] = pelt
            if (si.node("ufs.min") != null) {
                val t = if (ufsFixed) ufsMax else ufsMin
                if (t != null) v["node:ufs.min"] = t
            }
            v["gpu.limit.THERMAL_AP"] = if (thermalAp) "on" else "off"
            v["display.mode"] = dispMode
            v["display.hi"] = hi.toString()
            v["display.lo"] = lo.toString()
            v["display.fixed"] = hi.toString()
            v["net.mode"] = net
            return v
        }

        // Economia não impõe teto/piso artificial: mantém DVFS e OPPs da ROM/kernel.
        // PELT, UFS, tela e modem ficam sob controle do sistema para evitar trabalho
        // extra e políticas concorrentes. THERMAL_AP permanece habilitado.
        val eco = extras(base(listOf(100, 100, 100), listOf(0, 0, 0), 0L, 0L), "4", false, true, "off", 120, 60, "off").apply {
            remove("node:sched.pelt")
            remove("node:ufs.min")
        }
        val bal = extras(base(listOf(100, 100, 90), listOf(0, 0, 0), 0L, 0L), "4", false, true, "auto", 120, 60, "dynamic")
        val perf = extras(base(listOf(100, 100, 100), listOf(1000000L, 1200000L, 1200000L), 0L, 520000L), "4", true, true, "fixed", 120, 120, "5g")
        val max = extras(base(listOf(100, 100, 100), listOf(1300000L, 1500000L, 1500000L), 0L, 702000L), "4", true, false, "fixed", 120, 120, "5g")

        return listOf(
            Profile("Economia", eco, true),
            Profile("Equilibrado", bal, true),
            Profile("Desempenho", perf, true),
            Profile("Máximo ⚠", max, true)
        )
    }

    fun allProfiles(): List<Profile> = builtins() + Profile.decode(store.get("profiles"))

    fun saveProfile(p: Profile) {
        val cur = Profile.decode(store.get("profiles")).filter { it.name != p.name }
        store.put("profiles", Profile.encode(cur + p.copy(builtin = false)))
    }

    fun deleteProfile(name: String) {
        val cur = Profile.decode(store.get("profiles")).filter { it.name != name }
        store.put("profiles", Profile.encode(cur))
    }

    fun captureCurrent(name: String): Profile {
        val si = info ?: detect()
        val live = readLive()
        val v = LinkedHashMap<String, String>()
        for (p in si.policies) {
            val c = live.cpu[p.id] ?: continue
            if (c.gov.isNotEmpty()) v["cpu${p.id}.gov"] = c.gov
            v["cpu${p.id}.max"] = if (c.max >= p.maxHw) "0" else c.max.toString()
            v["cpu${p.id}.min"] = if (c.min > p.minHw) c.min.toString() else "0"
        }
        live.gpuUpIdx?.let { up ->
            v["gpu.max_khz"] = if (up <= 0) "0" else (si.gpuOpps.firstOrNull { it.idx == up }?.kHz ?: 0L).toString()
        }
        live.gpuBoostIdx?.let { b ->
            v["gpu.min_khz"] = if (b < si.gpuOpps.size - 1) (si.gpuOpps.firstOrNull { it.idx == b }?.kHz ?: 0L).toString() else "0"
        }
        return Profile(name, v, false)
    }

    fun applyProfile(pr: Profile): List<WriteResult> {
        val si = info ?: detect()
        val res = ArrayList<WriteResult>()
        log("── Perfil '${pr.name}' ──")
        for (p in si.policies) {
            val gov = pr.values["cpu${p.id}.gov"]?.takeIf { p.governors.contains(it) }
            val max = pr.values["cpu${p.id}.max"]?.toLongOrNull()?.let { resolveMax(p, it) }
            val min = pr.values["cpu${p.id}.min"]?.toLongOrNull()?.let { resolveMin(p, it) }
            if (gov != null || max != null || min != null) res.addAll(setCpu(p.id, min, max, gov))
        }
        pr.values["gpu.max_khz"]?.toLongOrNull()?.let { res.add(setGpuCeilingKHz(it)) }
        pr.values["gpu.min_khz"]?.toLongOrNull()?.let { res.add(setGpuFloorKHz(it)) }

        var limits: List<GpuLimit>? = null
        for ((key, value) in pr.values) {
            when {
                key.startsWith("gpu.limit.") -> {
                    val name = key.removePrefix("gpu.limit.")
                    if (limits == null) limits = Parse.limitTable(readOne(GPU_LIMIT_TABLE))
                    val lim = limits.firstOrNull { it.name == name }
                    if (lim != null) {
                        val want = value != "off"
                        // não mexe se já está no estado pedido (evita escrita à toa a cada troca de perfil)
                        if (lim.cEnable != want) res.add(setLimit(lim.id, want))
                    }
                }
                key.startsWith("node:") -> {
                    val spec = si.node(key.removePrefix("node:"))
                    if (spec != null) res.add(writeNode(spec, value))
                }
                key == "display.hz" -> {
                    // legado (v0.1): só vale se a aba Tela não estiver controlando
                    if ((store.get("disp.mode") ?: "off") == "off") res.add(setRefresh(value.toIntOrNull()))
                }
                key == "thermal.pause" -> res.addAll(pauseThermal(value == "1"))
            }
        }
        applyAutomation(pr)
        store.put("active", pr.name)
        return res
    }

    /** Copia as preferências de tela/rede do perfil para o Store (se "Perfis controlam tela e rede" estiver ligado). */
    private fun applyAutomation(pr: Profile) {
        if (store.get("prof_ctrl") != "1") return
        val map = listOf(
            "display.mode" to "disp.mode", "display.hi" to "disp.hi", "display.lo" to "disp.lo",
            "display.fixed" to "disp.fixed", "net.mode" to "net.mode"
        )
        var changed = false
        for ((k, dst) in map) {
            val v = pr.values[k] ?: continue
            if (store.get(dst) != v) { store.put(dst, v); changed = true }
        }
        if (changed) log("Perfil '${pr.name}' ajustou tela/rede (modo tela=${store.get("disp.mode")}, rede=${store.get("net.mode")})")
    }

    fun applyByName(name: String): List<WriteResult> {
        val p = allProfiles().firstOrNull { it.name == name } ?: return emptyList()
        return applyProfile(p)
    }

    private fun expected(si: SocInfo, pr: Profile): Map<String, String> {
        val e = LinkedHashMap<String, String>()
        for (p in si.policies) {
            pr.values["cpu${p.id}.gov"]?.takeIf { p.governors.contains(it) }?.let { e[pol(p.id) + "/scaling_governor"] = it }
            pr.values["cpu${p.id}.max"]?.toLongOrNull()?.let { e[pol(p.id) + "/scaling_max_freq"] = resolveMax(p, it).toString() }
            pr.values["cpu${p.id}.min"]?.toLongOrNull()?.let { e[pol(p.id) + "/scaling_min_freq"] = resolveMin(p, it).toString() }
        }
        pr.values["gpu.max_khz"]?.toLongOrNull()?.let { e[GED_UP] = gpuIdxForCeiling(it).toString() }
        pr.values["gpu.min_khz"]?.toLongOrNull()?.let { e[GED_BOOST] = gpuIdxForFloor(it).toString() }
        return e
    }

    /** Reaplica o perfil ativo se algum nó derivou. Retorna true se reaplicou. */
    fun enforce(): Boolean {
        val si = info ?: return false
        val name = store.get("active") ?: return false
        val prof = allProfiles().firstOrNull { it.name == name } ?: return false
        val exp = expected(si, prof)
        val now = readMany(exp.keys.toList())
        val drift = exp.filter { (k, v) ->
            val got = now[k]
            got != null && !Parse.same(v, got)
        }
        if (drift.isEmpty()) return false
        log("Deriva em ${drift.size} nó(s): ${drift.keys.joinToString { it.substringAfterLast('/') }} → reaplicando '${prof.name}'")
        applyProfile(prof)
        return true
    }

    // ───────────────────────── snapshot ─────────────────────────

    private fun snapPaths(si: SocInfo): List<String> {
        val l = ArrayList<String>()
        for (p in si.policies) {
            for (f in listOf("scaling_governor", "scaling_min_freq", "scaling_max_freq")) l.add(pol(p.id) + "/" + f)
        }
        l.addAll(si.nodes.map { it.path })
        l.addAll(listOf(GED_UP, GED_BOOST))
        return l.distinct()
    }

    fun hasSnapshot(): Boolean = store.get("snap:done") == "1"

    fun captureSnapshot(): Int {
        val si = info ?: detect()
        for (k in store.keys().filter { it.startsWith("snap:") }) store.remove(k)
        var n = 0
        for ((key, value) in readMany(snapPaths(si))) {
            if (value == null) continue
            var v = if (key.endsWith("/scheduler")) Parse.scheduler(value).first else Parse.firstLine(value)
            if (key.startsWith(GED)) Parse.intOf(value)?.let { v = it.toString() }
            store.put("snap:$key", v)
            n++
        }
        store.put("snap:done", "1")
        log("Snapshot salvo ($n nós)")
        return n
    }

    fun restoreSnapshot(): List<WriteResult> {
        val si = info ?: detect()
        val res = ArrayList<WriteResult>()
        for (p in si.policies) {
            val gov = store.get("snap:" + pol(p.id) + "/scaling_governor")
            val mn = store.get("snap:" + pol(p.id) + "/scaling_min_freq")?.toLongOrNull()
            val mx = store.get("snap:" + pol(p.id) + "/scaling_max_freq")?.toLongOrNull()
            res.addAll(setCpu(p.id, mn, mx, gov))
        }
        for (path in snapPaths(si)) {
            if (path.contains("/cpufreq/policy")) continue
            val v = store.get("snap:$path") ?: continue
            res.add(write(path, v))
        }
        restoreModes()
        store.remove("active")
        return res
    }

    fun restoreModes() {
        for (k in store.keys().filter { it.startsWith("mode:") }) {
            val path = k.removePrefix("mode:")
            sh.exec("chmod ${store.get(k)} ${q(path)} 2>/dev/null")
            store.remove(k)
        }
    }

    // ───────────────────────── guarda térmica ─────────────────────────

    data class GuardCfg(val enabled: Boolean, val soc: Float, val skin: Float, val batt: Float)

    fun guardCfg(): GuardCfg = GuardCfg(
        store.get("guard") != "0",
        store.get("g_soc")?.toFloatOrNull() ?: 80f,
        store.get("g_skin")?.toFloatOrNull() ?: 44f,
        store.get("g_batt")?.toFloatOrNull() ?: 42f
    )

    fun guardReason(live: Live, g: GuardCfg = guardCfg()): String? {
        if (!g.enabled) return null
        live.temps["soc_max"]?.let { if (it >= g.soc) return "SoC ${"%.1f".format(it)}°C ≥ ${g.soc.toInt()}°C" }
        live.temps["quiet_therm"]?.let { if (it >= g.skin) return "Superfície ${"%.1f".format(it)}°C ≥ ${g.skin.toInt()}°C" }
        live.battTempC?.let { if (it >= g.batt) return "Bateria ${"%.1f".format(it)}°C ≥ ${g.batt.toInt()}°C" }
        return null
    }

    /** true se o perfil atual relaxou proteções térmicas (thermald pausado ou limitadores desligados). */
    fun isRisky(): Boolean {
        if (store.get("thermal_paused") == "1") return true
        return (store.get("disabled_limits") ?: "").isNotBlank()
    }

    fun guardTrip(live: Live): String? {
        val g = guardCfg()
        val reason = guardReason(live, g) ?: return null
        if (isRisky()) return reason
        if ((live.temps["soc_max"] ?: 0f) >= g.soc + 10f) return reason
        if ((live.battTempC ?: 0f) >= g.batt + 3f) return reason
        return null
    }

    fun safeMode(reason: String): List<WriteResult> {
        log("⚠ MODO SEGURO: $reason")
        val res = ArrayList<WriteResult>()
        if (store.get("thermal_paused") == "1") res.addAll(pauseThermal(false))
        for (id in (store.get("disabled_limits") ?: "").split(",").mapNotNull { it.toIntOrNull() }) {
            res.add(setLimit(id, true))
        }
        res.addAll(applyByName("Equilibrado"))
        return res
    }
}
