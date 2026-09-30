package app.notodo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notodo.data.Item
import app.notodo.data.Repo
import app.notodo.data.Settings
import app.notodo.data.dayLabel
import app.notodo.data.overdue
import app.notodo.data.whenLabel
import app.notodo.parse.Alerts
import app.notodo.parse.Kind
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.ZonedDateTime

@Composable
fun DetailScreen(vm: MainViewModel, id: String, settings: Settings, now: ZonedDateTime) {
    val item by remember(id) { vm.repo.item(id) }.collectAsStateWithLifecycle(null)
    val all by vm.items.collectAsStateWithLifecycle()
    val audit by remember(id) { vm.repo.audit(id) }.collectAsStateWithLifecycle(emptyList())
    val i = item
    val capture by remember(i?.captureId) { i?.captureId?.let { vm.repo.capture(it) } ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    var editing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Spacer(Modifier.weight(1f))
            if (i != null) {
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Modifica") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Elimina") }
            }
        }
        if (i == null) return@Column Empty("Elemento non trovato")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("${i.kind.label} · ${if (i.done) "completato" else "aperto"}")
            Text(i.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(i.whenLabel(now) ?: "Nessuna data", fontFamily = Mono, color = if (i.overdue(now)) scheme.primary else scheme.onSurface)
                if (i.overdue(now)) Dot(Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { moving = true }) { Text(if (i.at == null) "Aggiungi data" else "Sposta scadenza") }
            }
            if (i.alerts.isNotEmpty()) Text("Avvisi: " + i.alerts.joinToString(" · ") { Alerts.label(it) }, style = small)
            i.nextAlertAt?.let { Text("Prossimo avviso: ${Repo.stamp(it, now.zone)}", fontFamily = Mono, fontSize = 12.sp, color = scheme.onSurfaceVariant) }
            i.snoozeUntil?.takeIf { it > now.toInstant().toEpochMilli() }?.let { Text("Rimandato a ${Repo.stamp(it, now.zone)} (la scadenza resta invariata)", style = small) }
            val meta = (i.people.map { "@$it" } + i.tags.map { "#$it" }).joinToString("   ")
            if (meta.isNotEmpty()) Text(meta, color = scheme.onSurfaceVariant)
            if (i.body.isNotBlank()) Text(i.body)
            if (i.kind != Kind.NOTE && i.kind != Kind.REFERENCE && i.kind != Kind.IDEA) {
                if (i.done) OutlinedButton(onClick = { vm.act { setDone(i.id, false) } }) { Text("Riapri") }
                else Button(onClick = { vm.act { setDone(i.id, true) } }) { Text("Completa") }
            }

            Section("Testo originale")
            capture?.let { c ->
                val s = i.spanStart.coerceIn(0, c.text.length)
                val e = i.spanEnd.coerceIn(s, c.text.length)
                Text(buildAnnotatedString {
                    append(c.text.substring(0, s))
                    withStyle(SpanStyle(background = scheme.primary.copy(alpha = .14f), fontWeight = FontWeight.Medium)) { append(c.text.substring(s, e)) }
                    append(c.text.substring(e))
                })
                Label("Provenienza: ${c.source} · ${Repo.stamp(c.createdAt, now.zone)} · fuso ${c.zone}")
            } ?: Text("Non disponibile", style = small)
            if (i.mentions.isNotEmpty()) Text("Date menzionate: " + i.mentions.joinToString(", ") { dayLabel(LocalDate.parse(it), now.toLocalDate()) }, style = small)
            i.dateText?.let { Text("Data letta da: «$it»${i.precision?.let { p -> " · precisione ${p.label}" } ?: ""}", style = small) }

            Section("Motivi dell'analisi · confidenza ${(i.confidence * 100).toInt()}%")
            i.reasons.forEach { Text("· $it", style = small) }

            val related = remember(all, i) {
                all.filter { o -> o.id != i.id && (i.captureId != null && o.captureId == i.captureId || o.people.any(i.people::contains) || o.tags.any(i.tags::contains)) }.take(8)
            }
            if (related.isNotEmpty()) {
                Section("Collegati")
                related.forEach { o -> ItemRow(o, now, onOpen = { vm.open(o.id) }, onToggle = { vm.act { setDone(o.id, !o.done) } }) }
            }

            Section("Cronologia")
            audit.forEach { a ->
                Column {
                    Text("${Repo.stamp(a.at, now.zone)} · ${a.action}", fontFamily = Mono, fontSize = 12.sp)
                    if (a.detail.isNotBlank()) Text(a.detail, style = small, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (i == null) return
    if (editing) EditDialog(i.fields(), settings.zoneId, settings.defaultAlerts, settings.day, onDismiss = { editing = false }) { f ->
        vm.act { update(i.with(f)) }
        editing = false
    }
    if (moving) DateTimeDialog(i.whenAt, settings.zoneId, onDismiss = { moving = false }) { at, p ->
        val alerts = if (p == i.precision && i.at != null) i.alerts else defaultAlerts(if (i.alerts.isNotEmpty()) Kind.TASK else i.kind, p, settings.defaultAlerts, settings.day)
        vm.act { update(i.with(i.fields().copy(at = at, precision = p, alerts = alerts)), "scadenza spostata") }
        moving = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Eliminare l'elemento?") },
        text = { Text("Il testo originale della cattura resta nell'archivio e negli export.") },
        confirmButton = { TextButton(onClick = { vm.act { delete(i.id) }; deleting = false; vm.close() }) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annulla") } },
    )
}

@Composable
private fun Section(title: String) = Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(12.dp))
    Label(title)
}
