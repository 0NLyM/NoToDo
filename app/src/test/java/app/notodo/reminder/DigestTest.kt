package app.notodo.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.notodo.App
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class DigestTest {
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val nm = app.getSystemService(NotificationManager::class.java)

    @Before fun setUp() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun save(id: String, text: String) = runBlocking {
        val s = app.settings.get()
        app.repo.confirm(id, text, "test", s.zoneId, app.parser.parse(text, s.parseContext(app.repo.clock())))
    }

    private fun title(id: Int) = shadowOf(nm).getNotification(id)?.extras?.getString(Notification.EXTRA_TITLE)

    @Test fun `nessun riepilogo se oggi non c'e nulla`() = runBlocking {
        save("a", "il router usa VLAN 40")
        Notifications.digest(app)
        assertNull(title(1))
        assertNull(title(2)) // la nota è recente: niente riscoperta
    }

    @Test fun `riepilogo di oggi e una sola nota trascurata per periodo`() = runBlocking {
        save("a", "oggi alle 23:59 controlla il backup")
        save("b", "il router usa VLAN 40; idea: supporto 3D per la scrivania")
        val old = app.repo.clock() - 40L * 86_400_000
        app.repo.items.first().filter { it.captureId == "b" }.forEachIndexed { i, it -> app.db.dao().upsert(it.copy(updatedAt = old + i)) }

        Notifications.digest(app)
        assertTrue(title(1)!!.startsWith("Oggi: 1 elemento"))
        assertEquals("Da rivedere: Il router usa VLAN 40", title(2)) // la più trascurata
        assertTrue(app.settings.get().lastResurface > 0)

        nm.cancelAll()
        Notifications.digest(app)
        assertNull(title(2)) // stesso periodo: nessuna seconda proposta
    }
}
