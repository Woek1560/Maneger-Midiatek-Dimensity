package dev.leonardo.mt6899

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import dev.leonardo.mt6899.core.Shell
import dev.leonardo.mt6899.core.ShellResult
import dev.leonardo.mt6899.core.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.topjohnwu.superuser.Shell.setDefaultBuilder(
            com.topjohnwu.superuser.Shell.Builder.create().setTimeout(20L)
        )
        LogBuffer.init(this)
    }
}

object RootShell : Shell {
    override fun exec(cmd: String): ShellResult {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val r = com.topjohnwu.superuser.Shell.cmd(cmd).to(out, err).exec()
        return ShellResult(out.joinToString("\n"), err.joinToString("\n"), r.code)
    }

    fun hasRoot(): Boolean = com.topjohnwu.superuser.Shell.getShell().isRoot
}

class PrefsStore(ctx: Context) : Store {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("mt6899", Context.MODE_PRIVATE)
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
    override fun remove(key: String) { sp.edit().remove(key).apply() }
    override fun keys(): Set<String> = sp.all.keys.toSet()
}

/**
 * Log em memória (últimas 600 linhas) + arquivo em filesDir (sobrevive a reinício do app).
 * Tags usadas: SERVICO, TELA, TOQUE, APP, REDE, PERFIL, GUARDA, MODULO, SISTEMA.
 */
object LogBuffer {
    data class Entry(val ts: Long, val tag: String, val level: Char, val msg: String) {
        fun text(): String =
            fmt.format(Instant.ofEpochMilli(ts)) + " [" + tag + "] " + (if (level == 'I') "" else "$level ") + msg
    }

    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val _flow = MutableStateFlow<List<Entry>>(emptyList())
    val flow: StateFlow<List<Entry>> = _flow
    private const val MAX_MEM = 600
    private const val MAX_FILE_BYTES = 400_000L
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "mt6899.log")
        file = f
        try {
            if (f.exists()) {
                val restored = f.readLines().takeLast(MAX_MEM).mapNotNull { parse(it) }
                _flow.value = restored
            }
        } catch (_: Throwable) {
        }
    }

    private fun parse(line: String): Entry? {
        val p = line.split('\t', limit = 4)
        if (p.size < 4) return null
        val ts = p[0].toLongOrNull() ?: return null
        return Entry(ts, p[1], p[2].firstOrNull() ?: 'I', p[3])
    }

    fun add(s: String) = add("SISTEMA", s, if (s.startsWith("FALHA")) 'W' else 'I')

    fun add(tag: String, msg: String, level: Char = 'I') {
        val e = Entry(System.currentTimeMillis(), tag, level, msg.replace('\n', ' ').replace('\t', ' '))
        _flow.update { (it + e).takeLast(MAX_MEM) }
        val f = file ?: return
        io.execute {
            try {
                f.appendText("${e.ts}\t${e.tag}\t${e.level}\t${e.msg}\n")
                if (f.length() > MAX_FILE_BYTES) {
                    val keep = f.readLines().takeLast(1500)
                    f.writeText(keep.joinToString("\n") + "\n")
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun clear() {
        _flow.value = emptyList()
        val f = file ?: return
        io.execute { try { f.writeText("") } catch (_: Throwable) { } }
    }

    fun dump(entries: List<Entry> = _flow.value): String = entries.joinToString("\n") { it.text() }
}

/** Quadro de estado em tempo real mostrado na aba Logs (chave -> valor). */
object LiveStatus {
    private val _f = MutableStateFlow<Map<String, String>>(emptyMap())
    val flow: StateFlow<Map<String, String>> = _f
    val beat = MutableStateFlow(0L)

    fun set(k: String, v: String) {
        _f.update { if (it[k] == v) it else it + (k to v) }
    }
}
