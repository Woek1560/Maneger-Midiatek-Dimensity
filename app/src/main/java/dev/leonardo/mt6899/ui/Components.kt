package dev.leonardo.mt6899.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.mt6899.MainViewModel
import dev.leonardo.mt6899.core.Kind
import dev.leonardo.mt6899.core.NodeSpec
import dev.leonardo.mt6899.core.Risk
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8AB4FF),
            onPrimary = Color(0xFF0E1116),
            secondary = Color(0xFF7DE3C8),
            background = Color(0xFF0E1116),
            surface = Color(0xFF151A22),
            surfaceVariant = Color(0xFF1D2430),
            onSurface = Color(0xFFE6EAF0),
            onBackground = Color(0xFFE6EAF0),
            error = Color(0xFFFF6B6B)
        ),
        content = content
    )
}

fun mhz(k: Long): String =
    if (k >= 1_000_000L) String.format(Locale.US, "%.2f GHz", k / 1_000_000.0) else "${k / 1000} MHz"

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            v, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End
        )
    }
}

@Composable
fun Chips(options: List<String>, selected: String?, onSelect: (String) -> Unit, label: (String) -> String = { it }) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) {
            FilterChip(selected = (o == selected), onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Muted(sub)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Confirm(title: String, text: String, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancelar") } }
    )
}

private fun nearestIdx(freqs: List<Long>, v: Long?): Int {
    if (v == null || freqs.isEmpty()) return 0
    var best = 0
    for (i in freqs.indices) if (abs(freqs[i] - v) < abs(freqs[best] - v)) best = i
    return best
}

/** Slider sobre uma lista de frequências (kHz). Só aplica ao soltar. */
@Composable
fun FreqSlider(label: String, freqs: List<Long>, valueKHz: Long?, onCommit: (Long) -> Unit) {
    if (freqs.size < 2) {
        KV(label, valueKHz?.let { mhz(it) } ?: "?")
        return
    }
    val idxNow = nearestIdx(freqs, valueKHz)
    var pos by remember(idxNow, freqs.size) { mutableStateOf(idxNow.toFloat()) }
    val shown = freqs[pos.roundToInt().coerceIn(0, freqs.lastIndex)]
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(mhz(shown), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onCommit(freqs[pos.roundToInt().coerceIn(0, freqs.lastIndex)]) },
            valueRange = 0f..(freqs.size - 1).toFloat(),
            steps = freqs.size - 2
        )
    }
}

/** Slider numérico com passo inteiro. Só aplica ao soltar. */
@Composable
fun ValueSlider(label: String, value: Int, min: Int, max: Int, unit: String, onCommit: (Int) -> Unit) {
    var pos by remember(value) { mutableStateOf(value.coerceIn(min, max).toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${pos.roundToInt()} $unit", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onCommit(pos.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0)
        )
    }
}

@Composable
fun NodeEditor(spec: NodeSpec, value: String?, onApply: (String) -> Unit) {
    var pending by remember { mutableStateOf<String?>(null) }
    val apply: (String) -> Unit = { v ->
        if (spec.risk == Risk.DANGER) {
            pending = v
        } else {
            onApply(v)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            when (spec.risk) {
                Risk.DANGER -> Text("⚠ perigo", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Risk.CAUTION -> Text("cuidado", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                Risk.SAFE -> {}
            }
        }
        if (spec.hint.isNotBlank()) Muted(spec.hint)
        if (spec.kind == Kind.CHOICE && spec.choices.isNotEmpty()) {
            Chips(spec.choices, value, apply)
        } else {
            var text by remember(value) { mutableStateOf(value ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { apply(text.trim()) }) { Text("Aplicar") }
            }
        }
    }
    pending?.let { v ->
        Confirm(
            "Nó perigoso",
            "Escrever '$v' em ${spec.label}? ${spec.hint}",
            onYes = { pending = null; onApply(v) },
            onNo = { pending = null }
        )
    }
}

/** Lista nós ajustáveis agrupados por NodeSpec.group, filtrando por [filter]. */
@Composable
fun NodeGroups(vm: MainViewModel, filter: (NodeSpec) -> Boolean) {
    val si = vm.info ?: return
    val groups = si.nodes.filter(filter).groupBy { it.group }
    for ((g, nodes) in groups) {
        Section(g) {
            nodes.forEachIndexed { i, n ->
                if (i > 0) HorizontalDivider()
                NodeEditor(n, vm.nodeVals[n.id]) { vm.writeNode(n, it) }
            }
        }
    }
}
