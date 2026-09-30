package app.notodo.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.notodo.App
import app.notodo.data.Item
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.util.TimeZone
import java.util.UUID

/** Pianificazione reale su AlarmManager/NotificationManager (Robolectric), con l'App vera. */
@RunWith(AndroidJUnit4::class)
class RemindersTest {
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val am = app.getSystemService(AlarmManager::class.java)
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val originalZone = TimeZone.getDefault()

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun tearDown() {
        TimeZone.setDefault(originalZone)
        app.db.close()
    }

    private fun fireAlarms() = shadowOf(am).scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == Alarms.FIRE }

    private fun save(text: String): Item = runBlocking {
        val s = app.settings.get()
        val id = UUID.randomUUID().toString()
        app.repo.confirm(id, text, "test", s.zoneId, app.parser.parse(text, s.parseContext(app.repo.clock())))
        app.repo.items.first().first { it.captureId == id }
    }

    /** Attende il lavoro asincrono (goAsync) di un receiver. */
    private fun until(what: String, cond: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timeout: $what")
    }

    @Test fun `un solo allarme esatto, ripianificare non duplica`() = runBlocking {
        val a = save("tra 3 ore chiama Luca")
        save("tra 5 ore controlla il backup")
        Alarms.schedule(app)
        Alarms.schedule(app)
        val alarm = fireAlarms().single()
        assertEquals(a.nextAlertAt, alarm.triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
    }

    @Test fun `senza permesso sveglie si ripiega su allarme non esatto`() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val a = save("tra 3 ore chiama Luca")
        Alarms.schedule(app)
        val alarm = fireAlarms().single()
        assertEquals(a.nextAlertAt, alarm.triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarm.windowLengthMs)
    }

    @Test fun `riavvio e cambio fuso ripianificano lo stesso istante`() = runBlocking {
        val a = save("tra 3 ore chiama Luca")
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation!!) }
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setClass(app, SystemReceiver::class.java))
        until("allarme dopo il riavvio") { fireAlarms().size == 1 }
        assertEquals(a.nextAlertAt, fireAlarms().single().triggerAtMs)

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation!!) }
        app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED).setClass(app, SystemReceiver::class.java))
        until("allarme dopo il cambio fuso") { fireAlarms().size == 1 }
        assertEquals(a.nextAlertAt, fireAlarms().single().triggerAtMs)
    }

    @Test fun `allarme scaduto notifica e passa al prossimo avviso`() = runBlocking {
        val a = save("tra 3 ore chiama Luca")
        app.repo.snooze(a.id, app.repo.clock() - 1) // forza un avviso già dovuto
        AlarmReceiver.fire(app)
        val n = shadowOf(nm).getNotification(Notifications.id(a.id))
        assertNotNull(n)
        assertEquals("Chiama Luca", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(listOf("Fatto", "+15 min", "Rimanda…"), n.actions.map { it.title.toString() })
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Promemoria NoToDo", n.publicVersion.extras.getString(android.app.Notification.EXTRA_TITLE))
        val next = app.repo.findItem(a.id)!!.nextAlertAt!!
        assertEquals(next, fireAlarms().single().triggerAtMs)
        assertTrue(next > app.repo.clock())
    }

    @Test fun `azione +15 min dalla notifica rimanda senza toccare la scadenza`() = runBlocking {
        val a = save("tra 3 ore chiama Luca")
        app.sendBroadcast(Intent(ActionReceiver.SNOOZE, Notifications.itemUri(a.id)).setClass(app, ActionReceiver::class.java))
        until("snooze applicato") { runBlocking { app.repo.findItem(a.id)!!.snoozeUntil != null } }
        val i = app.repo.findItem(a.id)!!
        assertEquals(a.at, i.at)
        assertEquals(i.snoozeUntil, i.nextAlertAt)
        until("allarme ripianificato") { fireAlarms().singleOrNull()?.triggerAtMs == i.snoozeUntil }
    }

    @Test fun `azione Fatto completa e annulla l'allarme`() = runBlocking {
        val a = save("tra 3 ore chiama Luca")
        Alarms.schedule(app)
        app.sendBroadcast(Intent(ActionReceiver.DONE, Notifications.itemUri(a.id)).setClass(app, ActionReceiver::class.java))
        until("completato") { runBlocking { app.repo.findItem(a.id)!!.done } }
        until("allarme annullato") { fireAlarms().isEmpty() }
    }
}
