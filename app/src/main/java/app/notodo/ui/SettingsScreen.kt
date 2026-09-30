package app.notodo.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.notodo.data.Settings
import app.notodo.parse.Alerts
import app.notodo.reminder.Alarms
import app.notodo.reminder.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, s: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val small = MaterialTheme.typography.bodySmall
    // Stati ricalcolati al ritorno nell'app (MainActivity.onResume → tick).
    val tick = vm.now
    val notifications = remember(tick) { Notifications.allowed(ctx) }
    val exact = remember(tick) { Alarms.exactAllowed(ctx) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val assistant = remember(tick) { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }
    var zone by remember { mutableStateOf(s.zone) }
    var rules by remember { mutableStateOf(s.tagRules) }
    var timeDialog by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) = ContextCompat.getMainExecutor(ctx).execute { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    fun write(uri: Uri?, content: suspend () -> String) = uri?.let {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(it, "wt")!!.use { o -> o.write(content().toByteArray()) } } }
                .fold({ toast("Esportato") }, { e -> toast("Export non riuscito: ${e.message}") })
        }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.tick() }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { write(it) { vm.repo.exportJson() } }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { write(it) { vm.repo.exportMarkdown() } }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                runCatching {
                    val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(it)!!.use { i -> i.readBytes().decodeToString() } }
                    vm.repo.importJson(text)
                }.fold(::toast) { e -> toast("Import non riuscito: ${e.message}") }
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.showSettings = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Text("Impostazioni", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Group("Assistente digitale")
            Check(assistant, if (assistant) "NoToDo è l'assistente predefinito" else "NoToDo non è l'assistente predefinito")
            Text(
                "Android consente un solo assistente: se scegli NoToDo, la pressione lunga del tasto power apre la cattura " +
                    "al posto di Gemini/Google o di MacroDroid. NoToDo non cambia nulla da solo.",
                style = small,
            )
            OutlinedButton(onClick = { ctx.startActivity(Intent(SystemSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) { Text("Apri «App predefinite»") }
            Text(
                "Per tenere MacroDroid come assistente: azione «Avvia attività» → NoToDo › Cattura rapida, oppure «Invia intent» " +
                    "(target Attività) con azione ${CaptureActivity.ACTION} e pacchetto ${ctx.packageName}; extra facoltativo «text».",
                style = small,
            )
            TextButton(onClick = { copy(ctx, CaptureActivity.ACTION); toast("Copiato") }) { Text("Copia l'azione dell'intent") }

            Group("Affidabilità dei promemoria")
            Permission("Notifiche", notifications) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            Permission("Sveglie e promemoria (orario esatto)", exact) {
                ctx.startActivity(Intent(SystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri()))
            }
            if (!exact) Text("Senza questo permesso Android può ritardare gli avvisi anche di molti minuti.", style = small, color = MaterialTheme.colorScheme.primary)
            Permission("Batteria senza restrizioni", battery) { ctx.startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

            Group("Avvisi")
            Label("Predefiniti per task e appuntamenti con orario")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("m0", "m10", "m30", "m60", "d1").forEach { spec ->
                    FilterChip(selected = spec in s.defaultAlerts, onClick = {
                        vm.updateSettings { it.copy(defaultAlerts = if (spec in it.defaultAlerts) it.defaultAlerts - spec else it.defaultAlerts + spec) }
                    }, label = { Text(Alerts.label(spec)) })
                }
            }
            Line("Avviso per elementi con solo il giorno", s.dayTime) { timeDialog = "day" }
            Toggle("Nascondi il contenuto sulla schermata di blocco", s.privateLockscreen) { v -> vm.updateSettings { it.copy(privateLockscreen = v) } }

            Group("Riepilogo e riscoperta")
            Toggle("Riepilogo giornaliero", s.digestTime.isNotEmpty()) { v -> vm.updateSettings { it.copy(digestTime = if (v) "08:00" else "") } }
            if (s.digestTime.isNotEmpty()) Line("Ora del riepilogo", s.digestTime) { timeDialog = "digest" }
            Label("Riproponi una nota trascurata")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Mai", 1 to "Ogni giorno", 7 to "Ogni settimana").forEach { (d, l) ->
                    FilterChip(selected = s.resurfaceDays == d, onClick = { vm.updateSettings { it.copy(resurfaceDays = d) } }, label = { Text(l) })
                }
            }
            Text("Niente notifiche casuali: il riepilogo solo se oggi c'è qualcosa, e al massimo una nota non aperta da 30 giorni per periodo.", style = small)

            Group("Analisi")
            val zoneOk = runCatching { ZoneId.of(zone.trim()) }.isSuccess
            OutlinedTextField(
                zone, { zone = it }, Modifier.fillMaxWidth(), singleLine = true, isError = !zoneOk,
                label = { Text("Fuso orario") }, supportingText = { Text("Dispositivo: ${ZoneId.systemDefault().id}") },
            )
            OutlinedTextField(rules, { rules = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("Tag automatici (tag: parola, parola)") })
            OutlinedButton(onClick = { vm.updateSettings { it.copy(zone = zone.trim(), tagRules = rules) }; toast("Salvato") }, enabled = zoneOk) { Text("Salva analisi") }

            Group("Backup")
            Text("File leggibili (JSON versionato, Markdown) salvati dove scegli tu. L'import unisce per id: nessun duplicato.", style = small)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportJson.launch("notodo-backup.json") }) { Text("Esporta JSON") }
                OutlinedButton(onClick = { importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Importa JSON") }
                OutlinedButton(onClick = { exportMd.launch("notodo.md") }) { Text("Esporta Markdown") }
            }

            Group("Privacy")
            Text(
                "Nessun account e nessun permesso Internet: l'app non può inviare dati. Niente microfono, niente lettura dello schermo. " +
                    "Il backup automatico di Android è disattivato: usa l'export.",
                style = small,
            )
            Label("Versione ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}")
            Spacer(Modifier.height(24.dp))
        }
    }
    timeDialog?.let { key ->
        val initial = runCatching { LocalTime.parse(if (key == "day") s.dayTime else s.digestTime) }.getOrDefault(LocalTime.of(8, 0))
        TimeDialog(initial, onDismiss = { timeDialog = null }) { t ->
            val v = "%02d:%02d".format(t.hour, t.minute)
            vm.updateSettings { if (key == "day") it.copy(dayTime = v) else it.copy(digestTime = v) }
            timeDialog = null
        }
    }
}

private fun copy(ctx: Context, text: String) =
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NoToDo", text))

@Composable
private fun Group(title: String) = Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(12.dp))
    Label(title, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Check(ok: Boolean, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Dot(color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
    Text(text, Modifier.padding(start = 8.dp))
}

@Composable
private fun Permission(title: String, ok: Boolean, fix: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { Check(ok, title) }
    if (!ok) TextButton(onClick = fix) { Text("Consenti") } else Label("ok")
}

@Composable
private fun Line(title: String, value: String, onClick: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(title, Modifier.weight(1f))
    TextButton(onClick = onClick) { Text(value, fontFamily = Mono) }
}

@Composable
private fun Toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(title, Modifier.weight(1f))
    Switch(value, onChange)
}
