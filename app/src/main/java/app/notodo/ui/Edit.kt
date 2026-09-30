package app.notodo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.notodo.data.Item
import app.notodo.data.whenLabel
import app.notodo.parse.Alerts
import app.notodo.parse.Draft
import app.notodo.parse.Kind
import app.notodo.parse.Precision
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Campi modificabili, comuni ad anteprima ed elementi salvati. */
data class Fields(
    val kind: Kind,
    val title: String,
    val body: String,
    val at: ZonedDateTime?,
    val precision: Precision?,
    val alerts: List<String>,
    val tags: List<String>,
    val people: List<String>,
)

fun Draft.fields() = Fields(kind, title, body, at, precision, alerts, tags, people)

/** Una correzione manuale conferma l'elemento: i dubbi decadono. */
fun Draft.with(f: Fields) = copy(
    kind = f.kind, title = f.title, body = f.body, at = f.at, precision = f.precision, alerts = f.alerts,
    tags = f.tags, people = f.people, recognized = true, doubts = emptyList(), confidence = 1f,
    reasons = reasons + "Corretto a mano",
)

fun Item.fields() = Fields(kind, title, body, whenAt, precision, alerts, tags, people)

fun Item.with(f: Fields) = copy(
    kind = f.kind, title = f.title, body = f.body, at = f.at?.toInstant()?.toEpochMilli(), zone = (f.at?.zone ?: ZoneId.of(zone)).id,
    precision = f.precision, alerts = f.alerts, tags = f.tags, people = f.people,
)

/** Avvisi proposti per un nuovo tipo/data: solo task e appuntamenti notificano di default. */
fun defaultAlerts(kind: Kind, precision: Precision?, timed: List<String>, day: LocalTime): List<String> = when {
    precision == null || kind != Kind.TASK && kind != Kind.EVENT -> emptyList()
    precision == Precision.DAY -> listOf("d0@%02d:%02d".format(day.hour, day.minute))
    else -> timed
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditDialog(
    initial: Fields,
    zone: ZoneId,
    timedDefaults: List<String>,
    day: LocalTime,
    onDismiss: () -> Unit,
    onSave: (Fields) -> Unit,
) {
    var f by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    val now = ZonedDateTime.now(zone)
    val dayTime = "%02d:%02d".format(day.hour, day.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correggi") },
        confirmButton = { TextButton(onClick = { onSave(f.copy(title = f.title.trim().ifEmpty { initial.title })) }) { Text("Applica") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Tipo")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Kind.entries.forEach { k ->
                        FilterChip(selected = f.kind == k, onClick = {
                            f = f.copy(kind = k, alerts = if (f.alerts.isEmpty() || k != Kind.TASK && k != Kind.EVENT) defaultAlerts(k, f.precision, timedDefaults, day) else f.alerts)
                        }, label = { Text(k.label) })
                    }
                }
                OutlinedTextField(f.title, { f = f.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Titolo") })
                OutlinedTextField(f.body, { f = f.copy(body = it) }, Modifier.fillMaxWidth(), label = { Text("Dettagli") }, minLines = 2)
                Label("Quando")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = true }) { Text(f.at?.let { whenLabel(it, f.precision, now) } ?: "Nessuna data") }
                    if (f.at != null) TextButton(onClick = { f = f.copy(at = null, precision = null, alerts = emptyList()) }) { Text("Rimuovi") }
                }
                if (f.at != null) {
                    Label("Avvisi")
                    val presets = if (f.precision == Precision.DAY) listOf("d0@$dayTime", "d1@$dayTime", "d1@20:00") else listOf("m0", "m10", "m30", "m60", "d1")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (presets + f.alerts).distinct().forEach { spec ->
                            FilterChip(selected = spec in f.alerts, onClick = {
                                f = f.copy(alerts = if (spec in f.alerts) f.alerts - spec else f.alerts + spec)
                            }, label = { Text(Alerts.label(spec)) })
                        }
                    }
                }
                OutlinedTextField(f.tags.joinToString(", "), { f = f.copy(tags = split(it)) }, Modifier.fillMaxWidth(), label = { Text("Tag (separati da virgola)") })
                OutlinedTextField(f.people.joinToString(", "), { f = f.copy(people = split(it)) }, Modifier.fillMaxWidth(), label = { Text("Persone") })
            }
        },
    )
    if (picking) DateTimeDialog(f.at, zone, onDismiss = { picking = false }) { at, p ->
        // Stessa precisione: gli avvisi restano; altrimenti si ricalcolano (una nota con avvisi espliciti li mantiene).
        val alerts = if (p == f.precision && f.at != null) f.alerts
        else defaultAlerts(if (f.alerts.isNotEmpty()) Kind.TASK else f.kind, p, timedDefaults, day)
        f = f.copy(at = at, precision = p, alerts = alerts)
        picking = false
    }
}

private fun split(s: String) = s.split(',').map { it.trim().removePrefix("#").removePrefix("@") }.filter { it.isNotEmpty() }

/** Prima il giorno, poi l'ora; «Solo giorno» non inventa un orario. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeDialog(initial: ZonedDateTime?, zone: ZoneId, onDismiss: () -> Unit, onPick: (ZonedDateTime, Precision) -> Unit) {
    var timeStep by remember { mutableStateOf(false) }
    val start = initial?.withZoneSameInstant(zone) ?: ZonedDateTime.now(zone)
    val date = rememberDatePickerState(initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val time = rememberTimePickerState(if (initial == null) 9 else start.hour, if (initial == null) 0 else start.minute, is24Hour = true)
    fun day(): LocalDate = Instant.ofEpochMilli(date.selectedDateMillis ?: 0).atZone(ZoneOffset.UTC).toLocalDate()
    if (!timeStep) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { timeStep = true }, enabled = date.selectedDateMillis != null) { Text("Avanti") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
        ) { DatePicker(date) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Ora") },
            text = { TimePicker(time) },
            confirmButton = { TextButton(onClick = { onPick(ZonedDateTime.of(day(), LocalTime.of(time.hour, time.minute), zone), Precision.EXACT) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { onPick(day().atStartOfDay(zone), Precision.DAY) }) { Text("Solo giorno") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
