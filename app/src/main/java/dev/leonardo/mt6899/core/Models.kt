package dev.leonardo.mt6899.core

enum class Kind { INT, CHOICE, TEXT }

enum class Risk { SAFE, CAUTION, DANGER }

interface Shell {
    fun exec(cmd: String): ShellResult
}

interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

data class ShellResult(val out: String, val err: String, val code: Int) {
    val ok: Boolean get() = code == 0
}

data class WriteResult(
    val path: String,
    val wanted: String,
    val got: String,
    val ok: Boolean,
    val note: String = ""
) {
    fun line(): String {
        val p = path.removePrefix("/sys/").removePrefix("/proc/")
        return if (ok) "OK   $p = $got"
        else "FALHA $p (pedido=$wanted, lido=$got) $note".trim()
    }
}

data class NodeSpec(
    val id: String,
    val label: String,
    val path: String,
    val group: String,
    val kind: Kind = Kind.INT,
    val risk: Risk = Risk.SAFE,
    val hint: String = "",
    val choices: List<String> = emptyList()
)

data class CpuPolicy(
    val id: Int,
    val cpus: String,
    val capacity: Int?,
    val freqs: List<Long>,
    val governors: List<String>,
    val minHw: Long,
    val maxHw: Long
) {
    val label: String get() = "Cluster $id · CPU ${cpuRange(cpus)}"
}

data class GpuOpp(val idx: Int, val kHz: Long, val volt: Long)

data class GpuLimit(
    val id: Int,
    val name: String,
    val priority: Int,
    val ceiling: Int,
    val floor: Int,
    val cEnable: Boolean,
    val fEnable: Boolean
)

data class DevfreqInfo(val path: String, val freqs: List<Long>, val governors: List<String>)

data class CpuLive(val cur: Long, val min: Long, val max: Long, val gov: String)

data class Live(
    val ts: Long,
    val cpu: Map<Int, CpuLive>,
    val gpuIdx: Int?,
    val gpuKHz: Long?,
    val gpuUtil: Int?,
    val gpuUpIdx: Int?,
    val gpuBoostIdx: Int?,
    val ppmCeilIdx: Int?,
    val ppmCeilLimiter: Int?,
    val ppmFloorIdx: Int?,
    val ppmFloorLimiter: Int?,
    val limits: List<GpuLimit>,
    val temps: Map<String, Float>,
    val battPct: Int?,
    val battTempC: Float?,
    val flags: Map<String, String>
)

data class SocInfo(
    val marketName: String,
    val device: String,
    val socModel: String,
    val kernel: String,
    val gpuName: String,
    val policies: List<CpuPolicy>,
    val gpuOpps: List<GpuOpp>,
    val ddr: DevfreqInfo?,
    val ufs: DevfreqInfo?,
    val gpuDevfreq: DevfreqInfo?,
    val zones: Map<String, Int>,
    val blockDevs: List<String>,
    val nodes: List<NodeSpec>
) {
    val gpuMaxKHz: Long get() = gpuOpps.firstOrNull()?.kHz ?: 0L
    val gpuMinKHz: Long get() = gpuOpps.lastOrNull()?.kHz ?: 0L
    fun policy(id: Int): CpuPolicy? = policies.firstOrNull { it.id == id }
    fun node(id: String): NodeSpec? = nodes.firstOrNull { it.id == id }
}

data class Profile(
    val name: String,
    val values: LinkedHashMap<String, String>,
    val builtin: Boolean = false
) {
    companion object {
        fun encode(list: List<Profile>): String =
            list.filter { !it.builtin }.joinToString("\n") { p ->
                "P|" + clean(p.name) + "|" +
                    p.values.entries.joinToString(",") { clean(it.key) + "=" + clean(it.value) }
            }

        fun decode(text: String?): List<Profile> {
            if (text.isNullOrBlank()) return emptyList()
            val out = ArrayList<Profile>()
            for (line in text.lines()) {
                val parts = line.split("|")
                if (parts.size < 3 || parts[0] != "P") continue
                val map = LinkedHashMap<String, String>()
                for (kv in parts[2].split(",")) {
                    val i = kv.indexOf('=')
                    if (i > 0) map[kv.substring(0, i)] = kv.substring(i + 1)
                }
                out.add(Profile(parts[1], map, false))
            }
            return out
        }

        private fun clean(s: String): String =
            s.replace("|", "/").replace(",", ";").replace("\n", " ")
    }
}

/** Aspas simples seguras para shell. */
fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun cpuRange(cpus: String): String {
    val nums = cpus.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
    return when {
        nums.isEmpty() -> "?"
        nums.size == 1 -> nums[0].toString()
        else -> "${nums.first()}-${nums.last()}"
    }
}
