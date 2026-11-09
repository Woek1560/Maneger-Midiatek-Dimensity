package dev.leonardo.mt6899.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leonardo.mt6899.LiveStatus
import dev.leonardo.mt6899.MainViewModel
import dev.leonardo.mt6899.core.GpuLimit
import dev.leonardo.mt6899.core.Profile
import java.util.Locale

// ───────────────────────── Painel ─────────────────────────

@Composable
fun HomeScreen(vm: MainViewModel) {
    val si = vm.info ?: return
    val live = vm.live
    val status by LiveStatus.flow.collectAsState()
    val beat by LiveStatus.beat.collectAsState()
    val alive = System.currentTimeMillis() - beat < 10_000L
    ScreenColumn {
        Section("Aparelho") {
            KV("Modelo", "${si.marketName} (${si.device})")
            KV("SoC", si.socModel)
            KV("GPU", si.gpuName)
            KV("Kernel", si.kernel)
        }
        Section("Estado") {
            val active = vm.pref("active")
            KV("Perfil ativo", if (active.isNullOrBlank()) "—" else active)
            KV("Tela", status["tela"] ?: vm.displayLine.ifEmpty { "—" })
            KV("Rede", status["rede"] ?: "—")
            KV("Serviço", if (alive) "ativo" else "parado")
        }
        Section("CPU") {
            for (p in si.policies) {
                val c = live?.cpu?.get(p.id)
                if (c == null) {
                    KV(p.label, "lendo…")
                } else {
                    KV(p.label, "${mhz(c.cur)} · ${c.gov}")
                    Muted("faixa ${mhz(c.min)} – ${mhz(c.max)}  (hw ${mhz(p.maxHw)})")
                }
            }
        }
        Section("GPU") {
            KV("Frequência", live?.gpuKHz?.let { mhz(it) } ?: "—")
            KV("Utilização", live?.gpuUtil?.let { "$it%" } ?: "—")
        }
        Section("Temperaturas") {
            val temps = live?.temps.orEmpty()
            if (temps.isEmpty()) Muted("sem leitura")
            for ((k, v) in temps) KV(k, String.format(Locale.US, "%.1f °C", v))
            live?.battTempC?.let { KV("bateria (power_supply)", String.format(Locale.US, "%.1f °C", it)) }
            live?.battPct?.let { KV("capacidade", "$it%") }
        }
    }
}

// ───────────────────────── CPU ─────────────────────────

@Composable
fun CpuScreen(vm: MainViewModel) {
    val si = vm.info ?: return
    ScreenColumn {
        for (p in si.policies) {
            val c = vm.live?.cpu?.get(p.id)
            Section(p.label + (p.capacity?.let { " · cap $it" } ?: "")) {
                KV("Atual", c?.let { mhz(it.cur) } ?: "…")
                if (p.governors.isNotEmpty()) {
                    Muted("Governor")
                    Chips(
                        p.governors,
                        c?.gov,
                        onSelect = { vm.setCpu(p.id, null, null, it) }
                    )
                }
                FreqSlider("Mínimo", p.freqs, c?.min) { vm.setCpu(p.id, it, null, null) }
                FreqSlider("Máximo", p.freqs, c?.max) { vm.setCpu(p.id, null, it, null) }
                TextButton(onClick = { vm.setCpu(p.id, p.minHw, p.maxHw, null) }) { Text("Restaurar faixa de fábrica") }
            }
        }
        NodeGroups(vm) { it.group.startsWith("CPU") || it.group == "Scheduler" }
    }
}

// ───────────────────────── GPU ─────────────────────────

@Composable
fun GpuScreen(vm: MainViewModel) {
    val si = vm.info ?: return
    val live = vm.live
    val opps = si.gpuOpps
    val asc = opps.map { it.kHz }.sorted()
    var confirmLimit by remember { mutableStateOf<GpuLimit?>(null) }
    ScreenColumn {
        Section("Estado") {
            KV("Frequência", live?.gpuKHz?.let { mhz(it) } ?: "…")
            KV("Utilização", live?.gpuUtil?.let { "$it%" } ?: "…")
            KV("OPP atual", live?.gpuIdx?.let { "idx $it" } ?: "…")
            val ceilTxt = live?.let { l -> l.ppmCeilIdx?.let { "idx $it · limiter ${l.ppmCeilLimiter}" } }
            val floorTxt = live?.let { l -> l.ppmFloorIdx?.let { "idx $it · limiter ${l.ppmFloorLimiter}" } }
            KV("Teto efetivo (PPM)", ceilTxt ?: "…")
            KV("Piso efetivo (PPM)", floorTxt ?: "…")
        }
        if (asc.size >= 2) {
            Section("Limites deste app (GED)") {
                Muted("Teto no máximo e piso no mínimo = sem limite deste app.")
                val up = live?.gpuUpIdx?.let { i -> opps.firstOrNull { it.idx == i }?.kHz }
                val boost = live?.gpuBoostIdx?.let { i -> opps.firstOrNull { it.idx == i }?.kHz }
                FreqSlider("Teto (custom_upbound)", asc, up ?: asc.last()) { vm.setGpuCeil(it) }
                FreqSlider("Piso (custom_boost)", asc, boost ?: asc.first()) { vm.setGpuFloor(it) }
            }
        }
        if (opps.isNotEmpty()) {
            Section("Fixar frequência (OPP)") {
                Muted("Útil para benchmark. A GPU volta ao DVFS normal ao liberar.")
                Chips(
                    listOf("auto") + opps.map { it.idx.toString() },
                    null,
                    { vm.fixOpp(it.toIntOrNull()) },
                    { s -> if (s == "auto") "Auto" else "$s · ${mhz(opps.firstOrNull { it.idx.toString() == s }?.kHz ?: 0L)}" }
                )
            }
        }
        val limits = live?.limits.orEmpty()
        if (limits.isNotEmpty()) {
            Section("Limitadores do driver (limit_table)") {
                limits.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider()
                    SwitchRow(l.name, "prioridade ${l.priority} · teto ${l.ceiling} · piso ${l.floor}", l.cEnable) { on ->
                        if (on) vm.setLimit(l, true) else confirmLimit = l
                    }
                }
            }
        }
        NodeGroups(vm) { it.group.startsWith("GPU") }
    }
    confirmLimit?.let { l ->
        Confirm(
            "Desligar ${l.name}?",
            "Esse limitador protege contra superaquecimento/consumo. A guarda térmica do serviço volta a ligá-lo se a temperatura subir.",
            onYes = { confirmLimit = null; vm.setLimit(l, false) },
            onNo = { confirmLimit = null }
        )
    }
}

// ───────────────────────── Térmica ─────────────────────────

@Composable
fun ThermalScreen(vm: MainViewModel) {
    val live = vm.live
    var confirmPause by remember { mutableStateOf(false) }
    val paused = vm.pref("thermal_paused") == "1"
    ScreenColumn {
        Section("Temperaturas") {
            val temps = live?.temps.orEmpty()
            if (temps.isEmpty()) Muted("sem leitura")
            for ((k, v) in temps) KV(k, String.format(Locale.US, "%.1f °C", v))
            live?.battTempC?.let { KV("bateria", String.format(Locale.US, "%.1f °C", it)) }
            for ((k, v) in live?.flags.orEmpty()) KV(k, v)
        }
        Section("Guarda térmica") {
            SwitchRow(
                "Guarda ligada",
                "Se um perfil relaxou proteções e a temperatura subir, volta ao 'Equilibrado' e religa os limitadores. Precisa do serviço ativo.",
                vm.pref("guard") != "0"
            ) { vm.setPref("guard", if (it) "1" else "0") }
            ValueSlider("SoC", vm.pref("g_soc")?.toFloatOrNull()?.toInt() ?: 80, 60, 95, "°C") { vm.setPref("g_soc", it.toString()) }
            ValueSlider("Superfície", vm.pref("g_skin")?.toFloatOrNull()?.toInt() ?: 44, 36, 55, "°C") { vm.setPref("g_skin", it.toString()) }
            ValueSlider("Bateria", vm.pref("g_batt")?.toFloatOrNull()?.toInt() ?: 42, 36, 50, "°C") { vm.setPref("g_batt", it.toString()) }
            OutlinedButton(onClick = { vm.safeNow() }) { Text("Modo seguro agora") }
        }
        Section("Serviços térmicos") {
            SwitchRow(
                "Pausar mi_thermald / thermald",
                if (paused) "mi_thermald/thermald pausados" else "ligados (padrão)",
                paused
            ) { on -> if (on) confirmPause = true else vm.pauseThermal(false) }
        }
        val extras = vm.extras
        val msg = extras.filterKeys { !it.startsWith("cool:") }
        if (msg.isNotEmpty()) Section("Estado do thermal_message (HyperOS)") {
            for ((k, v) in msg) KV(k, v)
        }
        val cool = extras.filterKeys { it.startsWith("cool:") }
        if (cool.isNotEmpty()) Section("Cooling devices (atual/máx)") {
            Muted("Os de cpu-pause ficam ocultos.")
            for ((k, v) in cool) KV(k.removePrefix("cool:"), v)
        }
        NodeGroups(vm) { it.group == "Térmica" }
    }
    if (confirmPause) {
        Confirm(
            "Pausar serviços térmicos?",
            "Sem o mi_thermald o aparelho pode esquentar bastante sem reduzir clock. Só para benchmark/testes.",
            onYes = { confirmPause = false; vm.pauseThermal(true) },
            onNo = { confirmPause = false }
        )
    }
}

// ───────────────────────── Sistema ─────────────────────────

@Composable
fun SystemScreen(vm: MainViewModel) {
    val si = vm.info ?: return
    var confirmRestore by remember { mutableStateOf(false) }
    ScreenColumn {
        Section("Serviço em segundo plano") {
            SwitchRow(
                "Aplicar perfil no boot",
                "Aguarda ~20 s depois do boot para o sistema assentar. O app não solicita isenção permanente do Doze.",
                vm.pref("boot") != "0"
            ) { vm.setPref("boot", if (it) "1" else "0") }
            ValueSlider("Intervalo do watchdog", vm.pref("interval")?.toIntOrNull() ?: 30, 10, 120, "s") {
                vm.setPref("interval", it.toString())
            }
            SwitchRow(
                "Travar valores",
                "Trava a escrita de min/max da CPU (chmod 0444) para o HyperOS não sobrescrever.",
                vm.pref("lock") == "1"
            ) { vm.setLock(it) }
        }
        Section("Snapshot e padrão") {
            Muted(if (vm.hasSnapshot) "Há um snapshot salvo." else "Nenhum snapshot ainda. Salve antes de experimentar.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.captureSnapshot() }) { Text("Salvar estado atual") }
                OutlinedButton(onClick = { confirmRestore = true }, enabled = vm.hasSnapshot) { Text("Voltar ao padrão seguro") }
            }
        }
        NodeGroups(vm) { it.group in setOf("Memória", "I/O", "DDR / UFS") }
        Section("Dispositivo") {
            KV("Nós ajustáveis", si.nodes.size.toString())
            KV("Block devices", si.blockDevs.joinToString().ifEmpty { "—" })
        }
    }
    if (confirmRestore) {
        Confirm(
            "Voltar ao padrão seguro?",
            "Restaura o snapshot salvo (governors, faixas, nós) e remove o perfil ativo.",
            onYes = { confirmRestore = false; vm.restoreSnapshot() },
            onNo = { confirmRestore = false }
        )
    }
}

// ───────────────────────── Perfis ─────────────────────────

private fun profileSummary(p: Profile): String {
    val parts = ArrayList<String>()
    p.values.entries.filter { it.key.endsWith(".max") && it.key.startsWith("cpu") }.forEach {
        parts.add("${it.key.substringBefore('.')} máx " + (if (it.value == "0") "livre" else mhz(it.value.toLongOrNull() ?: 0L)))
    }
    p.values["gpu.max_khz"]?.let { if (it != "0") parts.add("GPU ≤ " + mhz(it.toLongOrNull() ?: 0L)) }
    p.values["gpu.min_khz"]?.let { if (it != "0") parts.add("GPU ≥ " + mhz(it.toLongOrNull() ?: 0L)) }
    p.values["display.mode"]?.let { m ->
        parts.add("tela " + when (m) {
            "off" -> "Sistema"
            "fixed" -> "fixa ${p.values["display.fixed"]} Hz"
            else -> "auto ${p.values["display.hi"]}/${p.values["display.lo"]} Hz"
        })
    }
    p.values["net.mode"]?.let {
        parts.add("rede " + when (it) {
            "off" -> "Sistema"
            "dynamic" -> "4G + 5G c/ DNS"
            else -> it
        })
    }
    return parts.joinToString(" · ")
}

@Composable
fun ProfilesScreen(vm: MainViewModel) {
    var saveDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val active = vm.pref("active")
    ScreenColumn {
        Muted("Toque para aplicar. O perfil ativo é mantido pelo serviço, se ligado.")
        for (p in vm.profiles) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (p.name == active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                )
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name + if (p.name == active) "  ● ativo" else "",
                            Modifier.weight(1f), style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Muted(profileSummary(p))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.applyProfile(p) }) { Text("Aplicar '${p.name}'") }
                        if (!p.builtin) TextButton(onClick = { vm.deleteProfile(p.name) }) { Text("Excluir") }
                    }
                }
            }
        }
        Section("Salvar estado atual como perfil") {
            Muted("Captura governors, min/max de cada cluster e teto/piso da GPU como estão agora.")
            Button(onClick = { newName = ""; saveDialog = true }) { Text("Salvar estado atual") }
        }
        Section("Automação") {
            SwitchRow(
                "Perfis controlam tela e rede",
                "Ao aplicar um perfil, ele também define o modo da aba Tela e da aba Rede (sobrescreve o que estiver lá).",
                vm.pref("prof_ctrl") == "1"
            ) { vm.setPref("prof_ctrl", if (it) "1" else "0") }
            SwitchRow(
                "Reaplicar perfil (watchdog)",
                "Reaplica o perfil quando mi_thermald/perf HAL mudarem os nós e vigia a temperatura.",
                vm.pref("watchdog") == "1"
            ) { vm.setPref("watchdog", if (it) "1" else "0") }
            SwitchRow(
                "Aplicar perfil no boot", null,
                vm.pref("boot") != "0"
            ) { vm.setPref("boot", if (it) "1" else "0") }
        }
    }
    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Nome do perfil") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { saveDialog = false; vm.saveCurrentAs(newName) }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancelar") } }
        )
    }
}
