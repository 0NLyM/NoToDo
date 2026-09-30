package app.notodo.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View as AndroidView
import android.widget.RemoteViews
import android.app.PendingIntent
import app.notodo.R
import app.notodo.app
import app.notodo.data.Filter
import app.notodo.data.Item
import app.notodo.data.View
import app.notodo.data.overdue
import app.notodo.data.select
import app.notodo.reminder.PI_FLAGS
import app.notodo.reminder.work
import app.notodo.ui.CaptureActivity
import app.notodo.ui.MainActivity
import kotlinx.coroutines.flow.first
import app.notodo.parse.Precision
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = work { Widget.refresh(ctx) }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ITALIAN)
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)

/** Etichetta corta per una riga sola: «14:30», «gio 09:00», «9 ott». */
fun shortLabel(i: Item, now: ZonedDateTime): String {
    val at = i.whenAt?.let { if (i.precision == Precision.DAY) it else it.withZoneSameInstant(now.zone) } ?: return ""
    val d = at.toLocalDate()
    val today = now.toLocalDate()
    val time = if (i.precision == Precision.DAY) null else TIME.format(at)
    return when {
        d == today -> time ?: "oggi"
        d == today.minusDays(1) -> "ieri"
        d < today || d > today.plusDays(6) -> DAY.format(d)
        else -> WEEKDAY.format(d) + (time?.let { " $it" } ?: "")
    }
}

/** Prossime scadenze + pulsante cattura. Aggiornato a ogni modifica dei dati e ogni ora. */
object Widget {
    suspend fun refresh(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
        if (ids.isEmpty()) return
        mgr.updateAppWidget(ids, views(ctx, ctx.app.repo.items.first(), ZonedDateTime.now(ctx.app.settings.get().zoneId)))
    }

    fun views(ctx: Context, items: List<Item>, now: ZonedDateTime): RemoteViews {
        val next = (select(items, View.TODAY, "", Filter(), now) + select(items, View.UPCOMING, "", Filter(), now)).take(4)
        return RemoteViews(ctx.packageName, R.layout.widget).apply {
            setOnClickPendingIntent(R.id.capture, PendingIntent.getActivity(ctx, 1, CaptureActivity.intent(ctx, "widget"), PI_FLAGS))
            setOnClickPendingIntent(R.id.header, PendingIntent.getActivity(ctx, 2, MainActivity.openView(ctx, View.TODAY), PI_FLAGS))
            removeAllViews(R.id.rows)
            next.forEach { i ->
                addView(R.id.rows, RemoteViews(ctx.packageName, R.layout.widget_row).apply {
                    setTextViewText(R.id.time, shortLabel(i, now))
                    setTextViewText(R.id.text, i.title)
                    setViewVisibility(R.id.late, if (i.overdue(now)) AndroidView.VISIBLE else AndroidView.GONE)
                    setOnClickPendingIntent(R.id.row, PendingIntent.getActivity(ctx, 0, MainActivity.open(ctx, i.id), PI_FLAGS))
                })
            }
            setViewVisibility(R.id.empty, if (next.isEmpty()) AndroidView.VISIBLE else AndroidView.GONE)
        }
    }
}
