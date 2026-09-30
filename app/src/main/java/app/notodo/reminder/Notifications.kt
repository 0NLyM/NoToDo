package app.notodo.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import app.notodo.R
import app.notodo.app
import app.notodo.data.Filter
import app.notodo.data.Item
import app.notodo.data.View
import app.notodo.data.overdue
import app.notodo.data.select
import app.notodo.data.whenLabel
import app.notodo.parse.Kind
import app.notodo.ui.MainActivity
import app.notodo.ui.SnoozeActivity
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime

object Notifications {
    const val ALERTS = "promemoria"
    const val DIGEST = "riepilogo"
    private const val DIGEST_ID = 1
    private const val RESURFACE_ID = 2
    private const val RED = 0xFFC8102E.toInt()

    fun channels(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(ALERTS, "Promemoria", NotificationManager.IMPORTANCE_HIGH).apply { description = "Scadenze e appuntamenti" },
                NotificationChannel(DIGEST, "Riepilogo e riscoperta", NotificationManager.IMPORTANCE_LOW).apply { description = "Riepilogo giornaliero e note da rivedere" },
            )
        )
    }

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun id(itemId: String) = itemId.hashCode()

    fun cancel(ctx: Context, itemId: String) = ctx.getSystemService(NotificationManager::class.java).cancel(id(itemId))

    fun itemUri(id: String) = "notodo://item/$id".toUri()

    private fun action(ctx: Context, action: String, id: String) =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ActionReceiver::class.java).setAction(action).setData(itemUri(id)), PI_FLAGS)

    /** Stesso id per elemento: un nuovo avviso sostituisce il precedente, mai duplicati. */
    suspend fun alert(ctx: Context, item: Item) {
        if (!allowed(ctx)) return
        val s = ctx.app.settings.get()
        val now = ZonedDateTime.now(s.zoneId)
        val text = listOfNotNull(
            item.whenLabel(now)?.let { (if (item.kind == Kind.EVENT) "Inizio: " else "Scadenza: ") + it },
            item.people.joinToString(", ").ifEmpty { null },
        ).joinToString(" · ")
        val n = NotificationCompat.Builder(ctx, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(RED)
            .setContentTitle(item.title)
            .setContentText(text)
            .setSubText(item.kind.label)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, MainActivity.open(ctx, item.id), PI_FLAGS))
            .setAutoCancel(true)
            .setVisibility(if (s.privateLockscreen) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(
                NotificationCompat.Builder(ctx, ALERTS).setSmallIcon(R.drawable.ic_notify).setColor(RED).setContentTitle("Promemoria NoToDo").build()
            )
            .addAction(0, "Fatto", action(ctx, ActionReceiver.DONE, item.id))
            .addAction(0, "+15 min", action(ctx, ActionReceiver.SNOOZE, item.id))
            .addAction(0, "Rimanda…", PendingIntent.getActivity(ctx, 0, SnoozeActivity.intent(ctx, item.id), PI_FLAGS))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id(item.id), n)
    }

    /** Riepilogo solo se c'è qualcosa da dire; riscoperta al massimo una nota per periodo. */
    suspend fun digest(ctx: Context) {
        if (!allowed(ctx)) return
        val s = ctx.app.settings.get()
        val now = ZonedDateTime.now(s.zoneId)
        val nowMs = now.toInstant().toEpochMilli()
        val items = ctx.app.repo.items.first()
        if (s.digestTime.isNotEmpty()) {
            val today = select(items, View.TODAY, "", Filter(), now)
            if (today.isNotEmpty()) {
                val late = today.count { it.overdue(now) }
                val title = "Oggi: ${today.size} " + (if (today.size == 1) "elemento" else "elementi") + if (late > 0) " · $late in ritardo" else ""
                post(ctx, DIGEST_ID, title, today.take(6).map { "${it.whenLabel(now)} · ${it.title}" }, MainActivity.openView(ctx, View.TODAY))
            }
        }
        if (s.resurfaceDays > 0 && nowMs - s.lastResurface >= s.resurfaceDays * 86_400_000L) {
            val stale = now.minusDays(30).toInstant().toEpochMilli()
            val pick = items.filter { !it.done && it.kind in setOf(Kind.NOTE, Kind.REFERENCE, Kind.IDEA) && maxOf(it.seenAt ?: 0, it.updatedAt) < stale }
                .minByOrNull { maxOf(it.seenAt ?: 0, it.updatedAt) }
            if (pick != null) {
                post(ctx, RESURFACE_ID, "Da rivedere: ${pick.title}", listOf(pick.kind.label + " non aperta da oltre 30 giorni"), MainActivity.open(ctx, pick.id))
                ctx.app.settings.update { it.copy(lastResurface = nowMs) }
            }
        }
    }

    private fun post(ctx: Context, id: Int, title: String, lines: List<String>, open: Intent) {
        val style = NotificationCompat.InboxStyle().also { st -> lines.forEach { st.addLine(it) } }
        val n = NotificationCompat.Builder(ctx, DIGEST)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(RED)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull())
            .setStyle(style)
            .setContentIntent(PendingIntent.getActivity(ctx, id, open, PI_FLAGS))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
