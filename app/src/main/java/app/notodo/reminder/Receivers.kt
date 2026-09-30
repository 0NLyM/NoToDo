package app.notodo.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.notodo.app
import app.notodo.widget.Widget

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = work {
        if (intent.action == Alarms.DIGEST) {
            Notifications.digest(ctx)
            Alarms.scheduleDigest(ctx)
        } else {
            fire(ctx)
        }
    }

    companion object {
        suspend fun fire(ctx: Context) {
            ctx.app.repo.fireDue().forEach { Notifications.alert(ctx, it) }
            Alarms.schedule(ctx)
        }
    }
}

/** Azioni della notifica: «Fatto» e «+15 min» (snooze, la scadenza non cambia). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = work {
        val id = intent.data?.lastPathSegment ?: return@work
        val repo = ctx.app.repo
        when (intent.action) {
            DONE -> repo.setDone(id, true)
            SNOOZE -> repo.snooze(id, repo.clock() + 15 * 60_000L)
        }
        Notifications.cancel(ctx, id)
    }

    companion object {
        const val DONE = "app.notodo.action.DONE"
        const val SNOOZE = "app.notodo.action.SNOOZE"
    }
}

/** Riavvio, cambio ora/fuso, aggiornamento app, permesso sveglie concesso: si ripianifica tutto. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        work {
            AlarmReceiver.fire(ctx)
            Alarms.scheduleDigest(ctx)
            Widget.refresh(ctx)
        }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
