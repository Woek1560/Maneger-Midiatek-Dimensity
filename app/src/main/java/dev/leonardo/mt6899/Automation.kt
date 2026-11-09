package dev.leonardo.mt6899

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.leonardo.mt6899.core.Shell
import dev.leonardo.mt6899.core.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chaves do Store (prefixos):
 *   disp.mode  = off | fixed | auto          (tela)
 *   disp.fixed = Hz do modo fixo (120)
 *   disp.hi / disp.lo = Hz com toque / ocioso no modo auto (120 / 60)
 *   disp.idle  = ms sem toque para considerar ocioso (2500)
 *   disp.app:<pacote> = "60" | "90" | "120" | ... | "auto"   (prioridade MAIOR que disp.mode)
 *   net.mode   = off | dynamic | 4g | 5g
 *   net.dns_auto = 1 -> DNS privado "Automático" conta como ligado
 *   log.verbose = 1 -> loga cada troca toque/ocioso
 */
object ServiceControl {
    const val PKG = "dev.leonardo.mt6899"

    fun dispNeeded(s: Store): Boolean =
        (s.get("disp.mode") ?: "off") != "off" || s.keys().any { it.startsWith("disp.app:") }

    fun netNeeded(s: Store): Boolean = (s.get("net.mode") ?: "off") != "off"

    fun risky(s: Store): Boolean =
        s.get("thermal_paused") == "1" || !(s.get("disabled_limits") ?: "").isBlank()

    fun shouldRun(s: Store): Boolean =
        s.get("watchdog") == "1" || dispNeeded(s) || netNeeded(s) || risky(s)

    fun shouldRunAtBoot(s: Store): Boolean =
        shouldRun(s) || (s.get("active") != null && s.get("boot") != "0")

    /** Inicia o serviço se algo precisa dele. Quem para o serviço é o próprio serviço (quando não há mais nada a fazer). */
    fun sync(ctx: Context, store: Store) {
        val app = ctx.applicationContext
        val want = shouldRun(store)
        if (want) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, GuardService::class.java))
            } catch (t: Throwable) {
                LogBuffer.add("SERVICO", "não consegui iniciar o serviço: ${t.message}", 'E')
            }
        }
        val boot = shouldRunAtBoot(store)
        // sinais para o módulo Magisk/KernelSU (opcional): ele inicia/revive o serviço
        Thread {
            try {
                RootShell.exec(
                    "mkdir -p /data/adb/mt6899; echo ${if (want) 1 else 0} > /data/adb/mt6899/want_service; " +
                        "echo ${if (boot) 1 else 0} > /data/adb/mt6899/want_boot"
                )
            } catch (_: Throwable) {
            }
        }.start()
    }
}

// ───────────────────────── app em primeiro plano ─────────────────────────

class ForegroundWatcher(private val ctx: Context, private val sh: Shell) {
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var cur: String? = null
    private var lastQuery = 0L

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Retorna true quando o app em primeiro plano está marcado pelo Android como jogo. */
    fun isGameForeground(): Boolean {
        val pkg = current() ?: return false
        return try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_GAME
        } catch (_: Throwable) {
            false
        }
    }

    fun current(): String? {
        if (hasUsageAccess()) {
            val now = System.currentTimeMillis()
            val from = if (lastQuery == 0L) now - 10 * 60_000L else lastQuery - 1000L
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) cur = e.packageName
            }
            lastQuery = now
            if (cur != null) return cur
        }
        // fallback: root + dumpsys
        val out = sh.exec("dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'").out
        val pkg = Regex("""u\d+\s+([\w.]+)/""").find(out)?.groupValues?.get(1)
        if (pkg != null) cur = pkg
        return cur
    }
}

// ───────────────────────── toque ─────────────────────────

/**
 * Detecta atividade na tela de toque lendo o nó /dev/input/eventN com `su -c cat`
 * (cat não tem buffer de stdio, então cada leitura chega na hora).
 */
class TouchMonitor(private val sh: Shell) {
    @Volatile var lastTouch = 0L
    @Volatile var active = false
    @Volatile var device: String? = null
    @Volatile var startedAt = 0L
    @Volatile var events = 0L
    @Volatile private var running = false
    @Volatile private var proc: Process? = null
    private var thread: Thread? = null
    var onActive: (() -> Unit)? = null

    fun isRunning(): Boolean = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.elapsedRealtime()
        thread = Thread { runLoop() }.also { it.isDaemon = true; it.name = "touch-monitor"; it.start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { proc?.destroy() } catch (_: Throwable) { }
        thread?.interrupt()
        thread = null
        LogBuffer.add("TOQUE", "monitor parado")
    }

    private fun findDevice(): String? {
        val out = sh.exec("getevent -pl 2>/dev/null").out
        var best: String? = null
        var bestScore = -1
        for (blk in out.split("add device")) {
            val path = Regex("/dev/input/event\\d+").find(blk)?.value ?: continue
            if (!blk.contains("ABS_MT_POSITION_X")) continue
            val score = if (blk.contains("INPUT_PROP_DIRECT")) 2 else 1
            if (score > bestScore) { best = path; bestScore = score }
        }
        return best
    }

    private fun nap(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    private fun onEvent() {
        events++
        lastTouch = SystemClock.elapsedRealtime()
        if (!active) {
            active = true
            onActive?.invoke()
        }
    }

    private fun runLoop() {
        while (running) {
            try {
                val dev = device ?: findDevice().also { device = it }
                if (dev == null) {
                    LogBuffer.add("TOQUE", "nenhum touchscreen encontrado (getevent -pl sem ABS_MT_POSITION_X)", 'E')
                    nap(15000)
                    continue
                }
                val p = ProcessBuilder("su", "-c", "cat $dev").redirectErrorStream(true).start()
                proc = p
                LogBuffer.add("TOQUE", "monitorando $dev")
                val buf = ByteArray(512)
                val ins = p.inputStream
                while (running) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    onEvent()
                }
                try { p.destroy() } catch (_: Throwable) { }
                proc = null
                if (running) {
                    LogBuffer.add("TOQUE", "leitura de $dev terminou; reiniciando em 3 s", 'W')
                    nap(3000)
                }
            } catch (t: Throwable) {
                if (!running) break
                LogBuffer.add("TOQUE", "erro: ${t.message}", 'E')
                nap(5000)
            }
        }
    }
}

// ───────────────────────── taxa de atualização ─────────────────────────

/**
 * Prioridade das regras (maior -> menor):
 *   1) regra do app em primeiro plano (disp.app:<pkg>)   -> Hz fixo ou "auto"
 *   2) modo global: fixed -> Hz fixo | auto -> toque = hi, ocioso = lo
 *   3) off -> o app não mexe (devolve ao sistema)
 */
class DisplayController(
    private val ctx: Context,
    private val sh: Shell,
    private val store: Store
) {
    data class Cfg(
        val mode: String, val fixed: Int, val hi: Int, val lo: Int, val idleMs: Long,
        val apps: Map<String, String>, val verbose: Boolean
    )

    val touch = TouchMonitor(sh)
    private val fg = ForegroundWatcher(ctx, sh)
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var applied: Int? = null
    private var lastAssert = 0L
    private var lastFgPoll = 0L
    private var screenOn = true
    private var currentFg: String? = null
    private var switches = 0
    private var cfgCache: Cfg? = null
    private var cfgAt = 0L

    private fun cfg(): Cfg {
        val now = SystemClock.elapsedRealtime()
        cfgCache?.let { if (now - cfgAt < 700) return it }
        val apps = LinkedHashMap<String, String>()
        for (k in store.keys()) {
            if (k.startsWith("disp.app:")) {
                val v = store.get(k)
                if (!v.isNullOrBlank()) apps[k.removePrefix("disp.app:")] = v
            }
        }
        val c = Cfg(
            store.get("disp.mode") ?: "off",
            store.get("disp.fixed")?.toIntOrNull() ?: 120,
            store.get("disp.hi")?.toIntOrNull() ?: 120,
            store.get("disp.lo")?.toIntOrNull() ?: 60,
            (store.get("disp.idle")?.toLongOrNull() ?: 2500L).coerceIn(500L, 15000L),
            apps,
            store.get("log.verbose") == "1"
        )
        cfgCache = c
        cfgAt = now
        return c
    }

    private fun ensureUsageAccess() {
        if (fg.hasUsageAccess()) {
            LiveStatus.set("usage", "ok")
            return
        }
        val r = sh.exec("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        val ok = fg.hasUsageAccess()
        LiveStatus.set("usage", if (ok) "ok" else "negado")
        LogBuffer.add(
            "APP",
            if (ok) "acesso ao uso de apps concedido via root"
            else "sem acesso ao uso de apps; usando dumpsys. ${r.err.take(80)}",
            if (ok) 'I' else 'W'
        )
    }

    suspend fun loop() {
        LogBuffer.add("TELA", "controlador iniciado")
        ensureUsageAccess()
        touch.onActive = { wake.trySend(Unit) }
        try {
            while (currentCoroutineContext().isActive) {
                val c = cfg()
                val needTouch = c.mode == "auto" || c.apps.values.any { it == "auto" }
                if (needTouch) touch.start() else touch.stop()
                try {
                    step(c)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    LogBuffer.add("TELA", "erro: ${t.message}", 'E')
                }
                LiveStatus.set(
                    "toque",
                    if (!touch.isRunning()) "parado"
                    else (touch.device ?: "procurando…") + " · ${touch.events} leituras"
                )
                withTimeoutOrNull(1000L) { wake.receive() }
            }
        } finally {
            touch.stop()
            release("controlador encerrado")
        }
    }

    private fun step(c: Cfg) {
        if (!pm.isInteractive) {
            screenOn = false
            LiveStatus.set("tela", "tela apagada")
            return
        }
        if (!screenOn) {
            screenOn = true
            applied = null // reaplica ao acordar
        }
        if (c.mode == "off" && c.apps.isEmpty()) {
            release("modo Sistema")
            LiveStatus.set("tela", "sistema (não controlado)")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (c.apps.isNotEmpty() && now - lastFgPoll >= 1000) {
            lastFgPoll = now
            val p = fg.current()
            if (p != null && p != currentFg) {
                currentFg = p
                LiveStatus.set("fg", p)
                if (c.verbose) LogBuffer.add("APP", "em primeiro plano: $p")
            }
        }
        val rule = if (c.apps.isEmpty()) null else currentFg?.let { c.apps[it] }
        val (hz, why) = decide(c, rule, now)
        applyTarget(hz, why, c.verbose)
    }

    private fun decide(c: Cfg, rule: String?, now: Long): Pair<Int?, String> {
        if (rule != null) {
            val r = rule.toIntOrNull()
            if (r != null) return Pair(r, "app $currentFg")
            if (rule == "auto") return auto(c, now)
        }
        return when (c.mode) {
            "fixed" -> Pair(c.fixed, "fixo")
            "auto" -> auto(c, now)
            else -> Pair(null, "sistema")
        }
    }

    private fun auto(c: Cfg, now: Long): Pair<Int?, String> {
        if (touch.device == null) {
            return if (now - touch.startedAt < 5000L) Pair(applied, "iniciando toque")
            else Pair(null, "auto indisponível (sem touchscreen)")
        }
        val activeNow = now - touch.lastTouch < c.idleMs
        if (!activeNow) touch.active = false
        return if (activeNow) Pair(c.hi, "toque") else Pair(c.lo, "ocioso")
    }

    private fun force(hz: Int): Boolean {
        val r = sh.exec(
            "settings put system min_refresh_rate ${hz}.0; settings put system peak_refresh_rate ${hz}.0; settings put system user_refresh_rate $hz"
        )
        return r.ok
    }

    private fun release(why: String) {
        if (applied == null) return
        sh.exec("settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings delete system user_refresh_rate")
        applied = null
        LogBuffer.add("TELA", "controle devolvido ao sistema ($why)")
    }

    private fun applyTarget(hz: Int?, why: String, verbose: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (hz == null) {
            release(why)
            LiveStatus.set("tela", why)
            return
        }
        val noisy = why == "toque" || why == "ocioso"
        if (hz != applied) {
            val ok = force(hz)
            applied = hz
            lastAssert = now
            switches++
            if (!noisy || verbose) {
                LogBuffer.add("TELA", "→ $hz Hz ($why)" + (if (ok) "" else " · FALHOU ao escrever"), if (ok) 'I' else 'W')
            }
        } else if (now - lastAssert > 20_000L) {
            lastAssert = now
            val peak = sh.exec("settings get system peak_refresh_rate").out.trim()
            if (!peak.startsWith(hz.toString())) {
                force(hz)
                LogBuffer.add("TELA", "sistema sobrescreveu (peak=$peak); reaplicado $hz Hz", 'W')
            }
        }
        LiveStatus.set("tela", "$hz Hz · $why · $switches trocas")
    }
}

// ───────────────────────── rede 4G / 5G ─────────────────────────

/**
 * Mantém a rede majoritariamente em 4G e libera 5G quando o DNS privado (modo "hostname") está ligado.
 * Usa `cmd phone set-allowed-network-types-for-users` (bitmask de TelephonyManager.NETWORK_TYPE_BITMASK_*).
 * Se o ROM não tiver esse comando, tenta um fallback via settings e registra tudo na aba Logs.
 */
class NetworkController(
    private val ctx: Context,
    private val sh: Shell,
    private val store: Store
) {
    companion object {
        private fun bit(t: Int): Long = 1L shl (t - 1)

        // GPRS=1 EDGE=2 UMTS=3 HSDPA=8 HSUPA=9 HSPA=10 LTE=13 HSPAP=15 GSM=16 TD_SCDMA=17 LTE_CA=19 NR=20
        val MASK_4G: Long = listOf(1, 2, 3, 8, 9, 10, 13, 15, 16, 17, 19).fold(0L) { a, t -> a or bit(t) }
        val MASK_5G: Long = MASK_4G or bit(20)
    }

    private var applied5g: Boolean? = null
    private var candidate: Boolean? = null
    private var candSince = 0L
    private var lastAssert = 0L
    private var nextTry = 0L

    fun privateDnsMode(): String = Settings.Global.getString(ctx.contentResolver, "private_dns_mode") ?: "off"

    private fun dnsOn(): Boolean {
        val m = privateDnsMode()
        return m == "hostname" || (store.get("net.dns_auto") == "1" && m == "opportunistic")
    }

    /** Chamado periodicamente e quando private_dns_mode muda. */
    fun step(forceApply: Boolean = false) {
        val mode = store.get("net.mode") ?: "off"
        val now = SystemClock.elapsedRealtime()
        if (mode == "off") {
            if (applied5g == false) {
                setAllowed(true, "modo desligado: restaurando 5G")
            }
            applied5g = null
            candidate = null
            LiveStatus.set("rede", "não controlada")
            return
        }
        val dns = privateDnsMode()
        val on = dnsOn()
        val want5g = when (mode) {
            "5g" -> true
            "4g" -> false
            else -> on
        }
        LiveStatus.set("dns", dns + if (on) " (ligado)" else "")

        if (want5g != applied5g || forceApply) {
            if (!forceApply && applied5g != null) {
                if (candidate != want5g) {
                    candidate = want5g
                    candSince = now
                    return
                }
                if (now - candSince < 3000L) return // debounce do toggle do DNS
            }
            if (!forceApply && now < nextTry) return
            val ok = setAllowed(want5g, "modo=$mode, DNS privado=$dns")
            candidate = null
            if (ok) {
                applied5g = want5g
                lastAssert = now
            } else {
                nextTry = now + 60_000L
            }
        } else if (now - lastAssert > 60_000L) {
            lastAssert = now
            val cur = readAllowed(0)
            val mask = if (want5g) MASK_5G else MASK_4G
            if (cur != null && cur != mask) {
                LogBuffer.add("REDE", "tipos permitidos mudaram ($cur); reaplicando", 'W')
                setAllowed(want5g, "reafirmando")
            }
        }
        LiveStatus.set("rede", (if (applied5g == true) "4G+5G liberado" else if (applied5g == false) "4G (5G bloqueado)" else "pendente") + " · DNS=$dns")
    }

    private fun readAllowed(slot: Int): Long? {
        val g = sh.exec("cmd phone get-allowed-network-types-for-users -s $slot 2>&1").out
        return Regex("(\\d{3,})").find(g)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun looksLikeError(s: String): Boolean {
        val t = s.lowercase()
        return t.contains("unknown command") || t.contains("exception") || t.contains("error") ||
            t.contains("invalid") || t.contains("usage:") || t.contains("not found")
    }

    fun setAllowed(allow5g: Boolean, why: String): Boolean {
        val mask = if (allow5g) MASK_5G else MASK_4G
        val label = if (allow5g) "4G+5G" else "só 4G (sem NR)"
        LogBuffer.add("REDE", "→ $label [$why] máscara=$mask")
        var ok = false
        for (slot in 0..1) {
            val r = sh.exec("cmd phone set-allowed-network-types-for-users -s $slot $mask 2>&1")
            val text = (r.out + " " + r.err).trim()
            val failed = !r.ok || looksLikeError(text)
            if (!failed) {
                val got = readAllowed(slot)
                ok = true
                LogBuffer.add(
                    "REDE",
                    "slot $slot: ok" + (if (got != null) " · lido=$got" + (if (got == mask) " ✓" else " (≠ pedido)") else " · sem leitura de volta"),
                    if (got == null || got == mask) 'I' else 'W'
                )
            } else {
                LogBuffer.add("REDE", "slot $slot: ${text.take(120).ifEmpty { "falhou (code=${r.code})" }}", if (slot == 0) 'W' else 'I')
            }
        }
        if (!ok) {
            // fallback: settings (efeito depende do ROM; pode exigir reabrir o rádio)
            val mode = if (allow5g) 33 else 9
            val r = sh.exec(
                "settings put global preferred_network_mode $mode; settings put global preferred_network_mode1 $mode; settings put global preferred_network_mode2 $mode"
            )
            LogBuffer.add(
                "REDE",
                "cmd phone indisponível; fallback settings preferred_network_mode=$mode (code=${r.code}) — sem garantia de efeito, rode o Diagnóstico",
                'W'
            )
            ok = r.ok
        }
        return ok
    }

    fun diagnose(): List<String> {
        val l = ArrayList<String>()
        fun run(title: String, cmd: String) {
            val r = sh.exec(cmd)
            val t = (r.out + r.err).trim().lines().joinToString(" | ").take(300)
            l.add("$title: ${t.ifEmpty { "(vazio)" }}")
        }
        run("gsm.network.type", "getprop gsm.network.type")
        run("private_dns_mode", "settings get global private_dns_mode")
        run("private_dns_specifier", "settings get global private_dns_specifier")
        run("cmd phone (comandos de rede)", "cmd phone help 2>&1 | grep -i -E 'network|allowed|nr' | head -6")
        run("allowed slot 0", "cmd phone get-allowed-network-types-for-users -s 0 2>&1")
        run("allowed slot 1", "cmd phone get-allowed-network-types-for-users -s 1 2>&1")
        run("preferred_network_mode*", "settings list global 2>/dev/null | grep -i preferred_network_mode")
        run("telephony.registry", "dumpsys telephony.registry 2>/dev/null | grep -m3 -o -E 'nrState=[A-Z_]+|mDataConnectionNetworkType=[0-9]+|accessNetworkTechnology=[A-Z_]+'")
        run("máscaras usadas", "echo 4G=$MASK_4G 5G=$MASK_5G")
        return l
    }
}
