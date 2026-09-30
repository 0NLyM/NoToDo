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
import app.notodo.data.View
import app.notodo.data.overdue
import app.notodo.data.select
import app.notodo.data.whenLabel
import app.notodo.reminder.PI_FLAGS
import app.notodo.reminder.work
import app.notodo.ui.CaptureActivity
import app.notodo.ui.MainActivity
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = work { Widget.refresh(ctx) }
}

/** Prossime scadenze + pulsante cattura. Aggiornato a ogni modifica dei dati e ogni ora. */
object Widget {
    suspend fun refresh(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
        if (ids.isEmpty()) return
        val now = ZonedDateTime.now(ctx.app.settings.get().zoneId)
        val items = ctx.app.repo.items.first()
        val next = (select(items, View.TODAY, "", Filter(), now) + select(items, View.UPCOMING, "", Filter(), now)).take(4)
        val views = RemoteViews(ctx.packageName, R.layout.widget).apply {
            setOnClickPendingIntent(R.id.capture, PendingIntent.getActivity(ctx, 1, CaptureActivity.intent(ctx, "widget"), PI_FLAGS))
            setOnClickPendingIntent(R.id.header, PendingIntent.getActivity(ctx, 2, MainActivity.openView(ctx, View.TODAY), PI_FLAGS))
            removeAllViews(R.id.rows)
            next.forEach { i ->
                addView(R.id.rows, RemoteViews(ctx.packageName, R.layout.widget_row).apply {
                    setTextViewText(R.id.time, i.whenLabel(now))
                    setTextViewText(R.id.text, i.title)
                    setViewVisibility(R.id.late, if (i.overdue(now)) AndroidView.VISIBLE else AndroidView.GONE)
                    setOnClickPendingIntent(R.id.row, PendingIntent.getActivity(ctx, 0, MainActivity.open(ctx, i.id), PI_FLAGS))
                })
            }
            setViewVisibility(R.id.empty, if (next.isEmpty()) AndroidView.VISIBLE else AndroidView.GONE)
        }
        mgr.updateAppWidget(ids, views)
    }
}
