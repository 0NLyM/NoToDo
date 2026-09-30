package app.notodo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notodo.app
import app.notodo.data.Settings
import app.notodo.data.whenLabel
import app.notodo.parse.Kind
import app.notodo.parse.Precision
import app.notodo.reminder.Notifications
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** «Rimanda…» dalla notifica: lo snooze sposta solo l'avviso; la scadenza si sposta con un'azione a parte. */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.data?.lastPathSegment ?: return finish()
        setContent { NoToDoTheme { SnoozeScreen(id, ::finish) } }
    }

    companion object {
        fun intent(ctx: Context, itemId: String): Intent =
            Intent(ctx, SnoozeActivity::class.java).setData(Notifications.itemUri(itemId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun SnoozeScreen(id: String, close: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val item by remember(id) { app.repo.item(id) }.collectAsStateWithLifecycle(null)
    val s by app.settings.flow.collectAsStateWithLifecycle(Settings())
    var pick by remember { mutableStateOf<String?>(null) }
    val now = ZonedDateTime.now(s.zoneId)
    fun act(block: suspend () -> Unit) = scope.launch {
        block()
        Notifications.cancel(ctx, id)
        close()
    }
    fun snooze(at: ZonedDateTime) = act { app.repo.snooze(id, at.toInstant().toEpochMilli()) }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(item?.title ?: "Rimanda") },
        text = {
            Column {
                Label("Rimanda l'avviso")
                TextButton(onClick = { snooze(now.plusMinutes(15)) }) { Text("Tra 15 minuti") }
                TextButton(onClick = { snooze(now.plusHours(1)) }) { Text("Tra 1 ora") }
                TextButton(onClick = { snooze(now.toLocalDate().plusDays(1).atTime(s.day).atZone(s.zoneId)) }) { Text("Domani alle ${s.dayTime}") }
                TextButton(onClick = { pick = "snooze" }) { Text("Scegli data e ora…") }
                HorizontalDivider()
                Label("Scadenza: ${item?.whenLabel(now) ?: "nessuna"}")
                TextButton(onClick = { pick = "move" }) { Text("Sposta la scadenza…") }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Chiudi") } },
    )
    pick?.let { mode ->
        DateTimeDialog(if (mode == "move") item?.whenAt else null, s.zoneId, onDismiss = { pick = null }) { at, p ->
            val i = item
            when {
                mode == "snooze" -> snooze(if (p == Precision.DAY) at.with(s.day) else at)
                i != null -> {
                    val alerts = if (p == i.precision && i.at != null) i.alerts else defaultAlerts(if (i.alerts.isNotEmpty()) Kind.TASK else i.kind, p, s.defaultAlerts, s.day)
                    act { app.repo.update(i.with(i.fields().copy(at = at, precision = p, alerts = alerts)), "scadenza spostata") }
                }
            }
            pick = null
        }
    }
}
