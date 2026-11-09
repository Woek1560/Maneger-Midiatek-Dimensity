package dev.leonardo.mt6899.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.mt6899.AppEntry
import dev.leonardo.mt6899.LiveStatus
import dev.leonardo.mt6899.LogBuffer
import dev.leonardo.mt6899.MainViewModel
import dev.leonardo.mt6899.Phase
import kotlinx.coroutines.delay

// ───────────────────────── Tela (taxa de atualização) ─────────────────────────

@Composable
fun DisplayScreen(vm: MainViewModel) {
    val status by LiveStatus.flow.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadApps() }

    val rates = vm.supportedHz
    val rateStrs = rates.map { it.toString() }
    val mode = vm.pref("disp.mode") ?: "off"

    val q = query.trim().lowercase()
    val filtered = vm.apps
        .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        .sortedWith(compareBy({ vm.pref("disp.app:${it.pkg}") == null }, { it.label.lowercase() }))

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Section("Estado") {
                KV("Taxas suportadas", rates.joinToString(" / ") { "$it Hz" })
                KV("Decisão atual", status["tela"] ?: "—")
                KV("Sistema reporta", vm.displayLine.ifEmpty { "…" })
                KV("Leitor de toque", status["toque"] ?: "—")
                KV("App em primeiro plano", status["fg"] ?: "—")
                KV("Acesso ao uso de apps", if (vm.usageGranted) "concedido" else "não concedido (usa dumpsys)")
                if (!vm.usageGranted) {
                    OutlinedButton(onClick = { vm.grantUsageAccess() }) { Text("Conceder via root") }
                }
            }
        }
        item {
            Section("Modo global") {
                Muted("Prioridade: 1) regra do app em primeiro plano → 2) este modo → 3) Sistema (o app não mexe).")
                Chips(
                    listOf("off", "fixed", "auto"), mode,
                    { vm.setPref("disp.mode", it) },
                    { when (it) { "off" -> "Sistema"; "fixed" -> "Fixo"; else -> "Auto (toque/ocioso)" } }
                )
                if (mode == "fixed") {
                    Muted("Taxa fixa")
                    Chips(rateStrs, vm.pref("disp.fixed") ?: "120", { vm.setPref("disp.fixed", it) }, { "$it Hz" })
                }
                if (mode == "auto") {
                    Muted("Ao tocar na tela")
                    Chips(rateStrs, vm.pref("disp.hi") ?: "120", { vm.setPref("disp.hi", it) }, { "$it Hz" })
                    Muted("Ocioso")
                    Chips(rateStrs, vm.pref("disp.lo") ?: "60", { vm.setPref("disp.lo", it) }, { "$it Hz" })
                    ValueSlider(
                        "Considerar ocioso após",
                        ((vm.pref("disp.idle")?.toLongOrNull() ?: 2500L) / 1000L).toInt().coerceIn(1, 10),
                        1, 10, "s"
                    ) { vm.setPref("disp.idle", (it * 1000).toString()) }
                    Muted("Cada toque/rolagem leva a tela ao valor 'ao tocar'; sem toque pelo tempo acima ela cai para 'ocioso'.")
                }
            }
        }
        item {
            Section("Por app (maior prioridade)") {
                Muted("Escolha a taxa de um app. \"Padrão\" segue o modo global. \"Auto\" usa a lógica toque/ocioso só neste app.")
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Buscar app") }, modifier = Modifier.fillMaxWidth()
                )
                if (vm.apps.isEmpty()) Muted("Carregando apps…")
            }
        }
        items(filtered, key = { it.pkg }) { app ->
            AppRateRow(app, vm.pref("disp.app:${app.pkg}"), rateStrs) { vm.setAppRate(app.pkg, it) }
        }
    }
}

@Composable
private fun AppRateRow(app: AppEntry, current: String?, rates: List<String>, onSet: (String?) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (current != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Muted(app.pkg)
            Chips(
                listOf("default") + rates + "auto", current ?: "default",
                { onSet(if (it == "default") null else it) },
                { when (it) { "default" -> "Padrão"; "auto" -> "Auto"; else -> "$it Hz" } }
            )
        }
    }
}

// ───────────────────────── Rede ─────────────────────────

@Composable
fun NetScreen(vm: MainViewModel) {
    val status by LiveStatus.flow.collectAsState()
    var dns by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        while (true) {
            dns = vm.netPrivateDns()
            delay(2000)
        }
    }
    val mode = vm.pref("net.mode") ?: "off"
    ScreenColumn {
        Section("Como funciona") {
            Muted("Base em 4G para economizar bateria. Quando você liga o DNS Privado (com um provedor definido), o app libera 4G + 5G. Ao desligar o DNS Privado, volta para 4G após ~3 s.")
        }
        Section("Modo") {
            Chips(
                listOf("off", "dynamic", "4g", "5g"), mode,
                { vm.setPref("net.mode", it) },
                { when (it) { "off" -> "Sistema"; "dynamic" -> "Dinâmico (DNS)"; "4g" -> "Sempre 4G"; else -> "Sempre 5G" } }
            )
        }
        Section("Estado") {
            KV("DNS privado (Settings)", dns)
            KV("Rede", status["rede"] ?: "—")
            SwitchRow(
                "Contar DNS \"Automático\" como ligado",
                "Desligado: só conta quando há provedor definido (modo hostname).",
                vm.pref("net.dns_auto") == "1"
            ) { vm.setPref("net.dns_auto", if (it) "1" else "0") }
        }
        Section("Testes") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.netTest(false) }) { Text("Forçar 4G") }
                OutlinedButton(onClick = { vm.netTest(true) }) { Text("Liberar 5G") }
            }
            Button(onClick = { vm.netDiagnose() }) { Text("Diagnóstico (grava nos Logs)") }
            Muted("Se o modo não for \"Sistema\", o serviço reaplica o estado configurado depois do teste.")
        }
        Section("Observação") {
            Muted("O app usa `cmd phone set-allowed-network-types-for-users` (bitmask de tipos de rede). Isso depende do ROM. Se não funcionar, rode o Diagnóstico e veja a aba Logs: ela mostra exatamente o que o ROM respondeu.")
        }
    }
}

// ───────────────────────── Logs ─────────────────────────

@Composable
private fun CheckRow(ok: Boolean?, label: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            when (ok) { true -> "✓"; false -> "✗"; null -> "•" },
            color = when (ok) { true -> Color(0xFF7DE3C8); false -> Color(0xFFFF6B6B); null -> Color(0xFF9AA4B2) },
            modifier = Modifier.padding(end = 8.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Muted(detail)
        }
    }
}

@Composable
fun LogsScreen(vm: MainViewModel) {
    val entries by LogBuffer.flow.collectAsState()
    val status by LiveStatus.flow.collectAsState()
    val beat by LiveStatus.beat.collectAsState()
    var tag by remember { mutableStateOf("Todos") }
    var onlyProblems by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        vm.refreshModule()
        vm.refreshDisplay()
    }

    val tags = listOf("Todos", "SERVICO", "TELA", "TOQUE", "APP", "REDE", "PERFIL", "GUARDA", "MODULO", "SISTEMA")
    val shown = entries.asReversed().filter { (tag == "Todos" || it.tag == tag) && (!onlyProblems || it.level != 'I') }
    val age = (System.currentTimeMillis() - beat) / 1000L
    val serviceOk = beat != 0L && age < 10L

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Section("Está tudo funcionando?") {
                CheckRow(vm.phase == Phase.Ready, "Root", if (vm.phase == Phase.Ready) "ok" else "indisponível")
                CheckRow(
                    serviceOk, "Serviço em segundo plano",
                    if (beat == 0L) "ainda não iniciou (ligue algo em Tela, Rede, Perfis ou Watchdog)" else "batimento há ${age}s"
                )
                CheckRow(
                    status["tela"]?.let { !it.contains("indispon") }, "Controle de tela",
                    status["tela"] ?: "sem dados (modo Sistema ou serviço parado)"
                )
                CheckRow(
                    status["toque"]?.let { it.startsWith("/dev/input") }, "Leitor de toque",
                    status["toque"] ?: "só é usado no modo Auto"
                )
                CheckRow(vm.usageGranted, "Acesso ao uso de apps (regras por app)", if (vm.usageGranted) "concedido" else "não concedido")
                CheckRow(
                    status["rede"]?.let { !it.startsWith("pendente") }, "Controle de rede",
                    (status["rede"] ?: "modo Sistema ou serviço parado") + (status["dns"]?.let { " · DNS=$it" } ?: "")
                )
                CheckRow(
                    if (vm.moduleInfo.isEmpty()) null else vm.moduleInfo.startsWith("Módulo instalado"),
                    "Módulo Magisk/KernelSU (opcional)", vm.moduleInfo.lines().firstOrNull() ?: "…"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.selfTest() }) { Text("Auto-teste") }
                    OutlinedButton(onClick = { vm.refreshModule() }) { Text("Reler módulo") }
                }
                val modLog = vm.moduleInfo.lines().drop(1).joinToString("\n")
                if (modLog.isNotBlank()) Muted("log do módulo:\n$modLog")
            }
        }
        item {
            Section("Logs") {
                Chips(tags, tag, { tag = it })
                SwitchRow("Só avisos e erros", null, onlyProblems) { onlyProblems = it }
                SwitchRow(
                    "Log detalhado de toque/ocioso",
                    "Registra cada troca de taxa do modo Auto (pode encher o log).",
                    vm.pref("log.verbose") == "1"
                ) { vm.setPref("log.verbose", if (it) "1" else "0") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(LogBuffer.dump(shown.asReversed()))) }) { Text("Copiar") }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, LogBuffer.dump(shown.asReversed()))
                        }
                        ctx.startActivity(Intent.createChooser(send, "Compartilhar logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Compartilhar") }
                    OutlinedButton(onClick = { LogBuffer.clear() }) { Text("Limpar") }
                }
                Muted("${shown.size} linha(s) · mais recentes primeiro")
            }
        }
        items(shown) { e ->
            Text(
                e.text(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = when (e.level) {
                    'E' -> Color(0xFFFF6B6B)
                    'W' -> Color(0xFFFFC857)
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
