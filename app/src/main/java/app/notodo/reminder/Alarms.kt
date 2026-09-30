package app.notodo.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.notodo.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

const val PI_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

object Alarms {
    const val FIRE = "app.notodo.action.FIRE"
    const val DIGEST = "app.notodo.action.DIGEST"

    private fun pending(ctx: Context, action: String) =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, AlarmReceiver::class.java).setAction(action), PI_FLAGS)

    fun exactAllowed(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /**
     * Un solo allarme di sistema: il prossimo avviso fra tutti gli elementi.
     * Stesso PendingIntent = sostituzione, quindi richiamarlo è sempre idempotente.
     */
    suspend fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, FIRE)
        val next = ctx.app.repo.nextAlert()
        when {
            next == null -> am.cancel(pi)
            am.canScheduleExactAlarms() -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) // inexact: segnalato in Impostazioni
        }
    }

    /** Riepilogo e riscoperta non devono essere puntuali: allarme inexact giornaliero. */
    suspend fun scheduleDigest(ctx: Context) {
        val s = ctx.app.settings.get()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, DIGEST)
        val time = runCatching { LocalTime.parse(s.digestTime) }.getOrNull()
        if (time == null && s.resurfaceDays == 0) return am.cancel(pi)
        val now = ZonedDateTime.now(s.zoneId)
        var at = now.with(time ?: s.day).truncatedTo(ChronoUnit.MINUTES)
        if (!at.isAfter(now)) at = at.plusDays(1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
    }
}

/** Lavoro asincrono in un receiver senza bloccare il main thread. */
fun BroadcastReceiver.work(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
