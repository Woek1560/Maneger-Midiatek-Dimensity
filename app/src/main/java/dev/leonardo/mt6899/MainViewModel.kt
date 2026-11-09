package dev.leonardo.mt6899

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.leonardo.mt6899.core.Engine
import dev.leonardo.mt6899.core.GpuLimit
import dev.leonardo.mt6899.core.Live
import dev.leonardo.mt6899.core.NodeSpec
import dev.leonardo.mt6899.core.Profile
import dev.leonardo.mt6899.core.SocInfo
import dev.leonardo.mt6899.core.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class Phase { Loading, NoRoot, Ready }

enum class AppTab(val title: String) {
    Home("Painel"),
    Cpu("CPU"),
    Gpu("GPU"),
    Thermal("Térmica"),
    System("Sistema"),
    Profiles("Perfis"),
    Display("Tela"),
    Net("Rede"),
    Logs("Logs")
}

data class AppEntry(val label: String, val pkg: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    val store = PrefsStore(app)
    val engine = Engine(RootShell, store) { LogBuffer.add("PERFIL", it, if (it.startsWith("FALHA")) 'W' else 'I') }

    var phase by mutableStateOf(Phase.Loading)
    var info by mutableStateOf<SocInfo?>(null)
    var live by mutableStateOf<Live?>(null)
    var nodeVals by mutableStateOf<Map<String, String?>>(emptyMap())
    var extras by mutableStateOf<Map<String, String>>(emptyMap())
    var profiles by mutableStateOf<List<Profile>>(emptyList())
    var displayLine by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var supportedHz by mutableStateOf(listOf(60, 90, 120))
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var hasSnapshot by mutableStateOf(false)
    var moduleInfo by mutableStateOf("")
    var usageGranted by mutableStateOf(false)
    val prefs = mutableStateMapOf<String, String>()

    init {
        io { bootstrap() }
    }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (t: Throwable) {
                LogBuffer.add("SISTEMA", "erro: ${t.message}", 'E')
                message = "Erro: ${t.message}"
            }
        }
    }

    // ───────────────────────── preferências ─────────────────────────

    private fun loadPrefs() {
        val cur = HashMap<String, String>()
        for (k in store.keys()) {
            if (k.startsWith("snap:") || k.startsWith("mode:")) continue
            store.get(k)?.let { cur[k] = it }
        }
        prefs.keys.toList().filter { it !in cur }.forEach { prefs.remove(it) }
        prefs.putAll(cur)
    }

    fun pref(k: String): String? = prefs[k]

    fun setPref(k: String, v: String) {
        store.put(k, v)
        prefs[k] = v
        ServiceControl.sync(ctx, store)
    }

    fun delPref(k: String) {
        store.remove(k)
        prefs.remove(k)
        ServiceControl.sync(ctx, store)
    }

    // ───────────────────────── leitura ─────────────────────────

    private fun bootstrap() {
        loadPrefs()
        if (!RootShell.hasRoot()) {
            phase = Phase.NoRoot
            LogBuffer.add("SISTEMA", "root não disponível", 'E')
            return
        }
        info = engine.detect()
        detectRates()
        refreshProfilesNow()
        refreshNodesNow()
        live = engine.readLive()
        hasSnapshot = engine.hasSnapshot()
        phase = Phase.Ready
        LogBuffer.add("SISTEMA", "app aberto · root ok · ${info?.socModel}")
        ServiceControl.sync(ctx, store)
    }

    fun retry() {
        phase = Phase.Loading
        io { bootstrap() }
    }

    fun refreshLive() = io { live = engine.readLive() }

    private fun refreshNodesNow() {
        val si = info ?: return
        nodeVals = engine.readNodes(si.nodes)
    }

    fun refreshNodes() = io { refreshNodesNow() }

    private fun refreshProfilesNow() {
        profiles = engine.allProfiles()
        prefs["active"] = store.get("active") ?: ""
    }

    fun refreshExtras() = io { extras = engine.readExtras() }

    fun refreshDisplay() = io {
        displayLine = engine.readDisplay()
        usageGranted = hasUsageAccess()
    }

    @Suppress("DEPRECATION")
    private fun detectRates() {
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val r = wm.defaultDisplay.supportedModes.map { Math.round(it.refreshRate) }.filter { it >= 30 }.distinct().sorted()
            if (r.isNotEmpty()) supportedHz = r
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────── ações genéricas ─────────────────────────

    private fun report(label: String, res: List<WriteResult>) {
        val ok = res.count { it.ok }
        val bad = res.filter { !it.ok }
        message = when {
            res.isEmpty() -> "$label: nada a fazer"
            bad.isEmpty() -> "$label: $ok OK"
            else -> "$label: $ok OK, ${bad.size} falha(s) — ${bad.first().note.ifEmpty { bad.first().path.substringAfterLast('/') }}"
        }
    }

    private fun refreshAfterWrite() {
        live = engine.readLive()
        refreshNodesNow()
    }

    fun setCpu(policy: Int, min: Long?, max: Long?, gov: String?) = io {
        report("CPU$policy", engine.setCpu(policy, min, max, gov))
        refreshAfterWrite()
    }

    fun setGpuCeil(kHz: Long) = io { report("GPU teto", listOf(engine.setGpuCeilingKHz(kHz))); refreshAfterWrite() }

    fun setGpuFloor(kHz: Long) = io { report("GPU piso", listOf(engine.setGpuFloorKHz(kHz))); refreshAfterWrite() }

    fun fixOpp(idx: Int?) = io { report("GPU OPP", listOf(engine.fixGpuOpp(idx))); refreshAfterWrite() }

    fun setLimit(l: GpuLimit, enabled: Boolean) = io {
        report("Limitador ${l.name}", listOf(engine.setLimit(l.id, enabled)))
        refreshAfterWrite()
        ServiceControl.sync(ctx, store)
    }

    fun writeNode(spec: NodeSpec, value: String) = io {
        report(spec.label, listOf(engine.writeNode(spec, value)))
        refreshNodesNow()
    }

    fun pauseThermal(pause: Boolean) = io {
        report("Térmica", engine.pauseThermal(pause))
        loadPrefs()
        ServiceControl.sync(ctx, store)
    }

    fun safeNow() = io {
        report("Modo seguro", engine.safeMode("acionado manualmente"))
        refreshProfilesNow()
        refreshAfterWrite()
    }

    fun setLock(on: Boolean) = io {
        store.put("lock", if (on) "1" else "0")
        prefs["lock"] = if (on) "1" else "0"
        if (!on) engine.restoreModes()
        message = if (on) "Travar valores ligado" else "Permissões restauradas"
    }

    // ───────────────────────── perfis ─────────────────────────

    fun applyProfile(p: Profile) = io {
        message = "Aplicando '${p.name}'…"
        val res = engine.applyProfile(p)
        report("Perfil '${p.name}'", res)
        LogBuffer.add("PERFIL", "'${p.name}' aplicado: ${res.count { it.ok }}/${res.size} escritas OK")
        loadPrefs()
        refreshProfilesNow()
        refreshAfterWrite()
        ServiceControl.sync(ctx, store)
    }

    fun saveCurrentAs(name: String) = io {
        val n = name.trim()
        if (n.isEmpty()) return@io
        engine.saveProfile(engine.captureCurrent(n))
        refreshProfilesNow()
        message = "Perfil '$n' salvo"
    }

    fun deleteProfile(name: String) = io {
        engine.deleteProfile(name)
        refreshProfilesNow()
        message = "Perfil '$name' excluído"
    }

    fun captureSnapshot() = io {
        val n = engine.captureSnapshot()
        hasSnapshot = true
        message = "Snapshot salvo ($n nós)"
    }

    fun restoreSnapshot() = io {
        val res = engine.restoreSnapshot()
        report("Restaurar padrão", res)
        loadPrefs()
        refreshProfilesNow()
        refreshAfterWrite()
    }

    // ───────────────────────── tela ─────────────────────────

    fun loadApps() {
        if (apps.isNotEmpty()) return
        io {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val list = pm.queryIntentActivities(intent, 0)
            apps = list.map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun setAppRate(pkg: String, value: String?) {
        if (value.isNullOrEmpty()) delPref("disp.app:$pkg") else setPref("disp.app:$pkg", value)
    }

    private fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        return ops.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun grantUsageAccess() = io {
        val r = RootShell.exec("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        usageGranted = hasUsageAccess()
        LogBuffer.add("APP", "conceder acesso ao uso de apps via root: code=${r.code} → ${if (usageGranted) "ok" else "negado"}", if (usageGranted) 'I' else 'W')
        message = if (usageGranted) "Acesso concedido" else "Não consegui conceder (veja Logs)"
    }

    // ───────────────────────── rede ─────────────────────────

    fun netDiagnose() = io {
        LogBuffer.add("REDE", "── diagnóstico ──")
        NetworkController(ctx, RootShell, store).diagnose().forEach { LogBuffer.add("REDE", it) }
        message = "Diagnóstico gravado nos Logs"
    }

    fun netTest(allow5g: Boolean) = io {
        val ok = NetworkController(ctx, RootShell, store).setAllowed(allow5g, "teste manual")
        message = if (ok) "Comando aceito — veja os Logs" else "Falhou — veja os Logs"
    }

    fun netPrivateDns(): String = NetworkController(ctx, RootShell, store).privateDnsMode()

    // ───────────────────────── auto-teste ─────────────────────────

    fun selfTest() = io {
        LogBuffer.add("SISTEMA", "── auto-teste ──")
        LogBuffer.add("SISTEMA", "root: ${RootShell.hasRoot()}")
        val beat = LiveStatus.beat.value
        val age = (System.currentTimeMillis() - beat) / 1000L
        LogBuffer.add(
            "SERVICO",
            if (beat == 0L) "serviço não iniciou nesta sessão" else "último batimento há ${age}s",
            if (beat != 0L && age < 10L) 'I' else 'W'
        )
        val st = LiveStatus.flow.value
        LogBuffer.add("TELA", "estado: ${st["tela"] ?: "—"} · toque: ${st["toque"] ?: "—"}")
        LogBuffer.add("APP", "uso de apps: ${if (hasUsageAccess()) "ok" else "sem acesso"} · app atual: ${st["fg"] ?: "—"}")
        LogBuffer.add("REDE", "estado: ${st["rede"] ?: "—"} · DNS: ${st["dns"] ?: "—"}")
        LogBuffer.add("TELA", "dumpsys: ${engine.readDisplay()}")
        usageGranted = hasUsageAccess()
        refreshModule()
        message = "Auto-teste gravado nos Logs"
    }

    // ───────────────────────── módulo ─────────────────────────

    fun refreshModule() = io {
        val present = RootShell.exec("[ -d /data/adb/modules/mt6899_helper ] && echo sim || echo nao").out.trim()
        val tail = RootShell.exec("tail -n 25 /data/adb/mt6899/module.log 2>/dev/null").out.trim()
        moduleInfo = (if (present == "sim") "Módulo instalado" else "Módulo NÃO instalado (opcional)") +
            (if (tail.isNotEmpty()) "\n$tail" else "")
    }
}
