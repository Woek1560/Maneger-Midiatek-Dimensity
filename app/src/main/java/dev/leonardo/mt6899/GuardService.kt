package dev.leonardo.mt6899

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.leonardo.mt6899.core.Engine
import dev.leonardo.mt6899.core.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Volatile
    private var bootPending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val fromBoot = intent?.getBooleanExtra("from_boot", false) ?: false
        if (!started) {
            started = true
            bootPending = fromBoot
            scope.launch {
                try {
                    supervise(fromBoot)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    LogBuffer.add("SERVICO", "erro fatal: ${t.message}", 'E')
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LogBuffer.add("SERVICO", "encerrado")
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("guard", "Serviço do gerenciador", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "guard")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("MT6899 Manager")
            .setContentText("Mantendo perfil, tela e rede")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private suspend fun supervise(fromBoot: Boolean) = coroutineScope {
        val store = PrefsStore(applicationContext)
        LogBuffer.add("SERVICO", "iniciado" + if (fromBoot) " (boot)" else "")
        if (!RootShell.hasRoot()) {
            LogBuffer.add("SERVICO", "sem root, encerrando", 'E')
            stopSelf()
            return@coroutineScope
        }
        val engine = Engine(RootShell, store) { LogBuffer.add("PERFIL", it, if (it.startsWith("FALHA")) 'W' else 'I') }
        val needsGuard = store.get("watchdog") == "1" || engine.isRisky() || fromBoot
        if (needsGuard) launch { guardLoop(engine, store, fromBoot) }
        if (ServiceControl.dispNeeded(store)) launch { DisplayController(this@GuardService, RootShell, store).loop() }
        if (ServiceControl.netNeeded(store)) launch { netLoop(NetworkController(this@GuardService, RootShell, store)) }

        var idleSince = 0L
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            LiveStatus.beat.value = now
            if (!ServiceControl.shouldRun(store) && !bootPending) {
                if (idleSince == 0L) idleSince = now
                else if (now - idleSince > 5000L) {
                    LogBuffer.add("SERVICO", "nada a fazer, encerrando")
                    stopSelf()
                    return@coroutineScope
                }
            } else {
                idleSince = 0L
            }
            delay(3000)
        }
    }

    private suspend fun guardLoop(engine: Engine, store: Store, fromBoot: Boolean) {
        if (fromBoot) {
            delay(20_000) // deixa o sistema assentar depois do boot
            val name = store.get("active")
            if (name != null && store.get("boot") != "0") {
                LogBuffer.add("SERVICO", "aplicando '$name' no boot")
                try {
                    engine.detect()
                    engine.applyByName(name)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    LogBuffer.add("SERVICO", "falha ao aplicar no boot: ${t.message}", 'E')
                }
            }
            bootPending = false
        }
        var lastSafe = 0L
        var lastGameState: Boolean? = null
        val foreground = ForegroundWatcher(this, RootShell)
        while (currentCoroutineContext().isActive) {
            val watch = store.get("watchdog") == "1"
            if (watch || engine.isRisky()) {
                try {
                    if (engine.info == null) engine.detect()
                    val trip = engine.guardTrip(engine.readLive())
                    val now = System.currentTimeMillis()
                    if (trip != null) {
                        if (now - lastSafe > 60_000L) {
                            lastSafe = now
                            LogBuffer.add("GUARDA", "disparou: $trip", 'W')
                            engine.safeMode(trip)
                        }
                    } else if (watch) {
                        // Durante jogos, deixa o GameTime/Power HAL gerenciar DVFS.
                        // A guarda térmica acima continua ativa normalmente.
                        val inGame = foreground.isGameForeground()
                        if (lastGameState != inGame) {
                            lastGameState = inGame
                            LogBuffer.add("GUARDA", if (inGame) "jogo em primeiro plano: watchdog não disputa CPU/GPU com GameTime" else "fora de jogo: watchdog normal")
                        }
                        if (!inGame) engine.enforce()
                    }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    LogBuffer.add("GUARDA", "erro: ${t.message}", 'E')
                }
            }
            val iv = (store.get("interval")?.toLongOrNull() ?: 30L).coerceIn(10L, 120L)
            delay(iv * 1000L)
        }
    }

    private suspend fun netLoop(net: NetworkController) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                wake.trySend(Unit)
            }
        }
        contentResolver.registerContentObserver(Settings.Global.getUriFor("private_dns_mode"), false, obs)
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    net.step()
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    LogBuffer.add("REDE", "erro: ${t.message}", 'E')
                }
                withTimeoutOrNull(15_000L) { wake.receive() }
            }
        } finally {
            contentResolver.unregisterContentObserver(obs)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = PrefsStore(context)
        if (!ServiceControl.shouldRunAtBoot(store)) return
        LogBuffer.add("SERVICO", "BOOT_COMPLETED recebido; iniciando serviço")
        ContextCompat.startForegroundService(
            context,
            Intent(context, GuardService::class.java).putExtra("from_boot", true)
        )
    }
}
