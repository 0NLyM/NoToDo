package app.notodo.assist

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.service.voice.VoiceInteractionService
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.notodo.App
import app.notodo.ui.CaptureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica il manifest con il parser del framework usato da Impostazioni › Assistente digitale.
 * Non sostituisce la prova sul Nothing Phone (3): dimostra solo che la dichiarazione è valida.
 */
@RunWith(AndroidJUnit4::class)
class IntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val pm = app.packageManager

    @Test fun `servizio assistente accettato dal parser di sistema`() {
        val services = pm.queryIntentServices(Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(app.packageName), PackageManager.GET_META_DATA)
        val si = services.single().serviceInfo
        assertEquals(AssistService::class.java.name, si.name)
        val cls = Class.forName("android.service.voice.VoiceInteractionServiceInfo")
        val info = cls.getConstructor(PackageManager::class.java, ServiceInfo::class.java).newInstance(pm, si)
        assertNull(cls.getMethod("getParseError").invoke(info))
        assertEquals(true, cls.getMethod("getSupportsAssist").invoke(info))
        assertEquals(AssistSessionService::class.java.name, cls.getMethod("getSessionService").invoke(info))
        assertEquals("android.permission.BIND_VOICE_INTERACTION", pm.getServiceInfo(ComponentName(app, AssistSessionService::class.java), 0).permission)
    }

    @Test fun `punti di ingresso per MacroDroid, condivisione e deep link`() {
        fun target(i: Intent) = pm.queryIntentActivities(i.setPackage(app.packageName), 0).single().activityInfo.name
        assertEquals(CaptureActivity::class.java.name, target(Intent(CaptureActivity.ACTION)))
        assertEquals(CaptureActivity::class.java.name, target(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertEquals(CaptureActivity::class.java.name, target(Intent(Intent.ACTION_VIEW, "notodo://capture?text=ciao".toUri())))
        assertEquals("domani chiama Luca", CaptureActivity.prefill(Intent(CaptureActivity.ACTION).putExtra("text", "domani chiama Luca")))
        assertEquals("chiama Luca", CaptureActivity.prefill(Intent(Intent.ACTION_VIEW, "notodo://capture?text=chiama%20Luca".toUri())))
        assertNull(CaptureActivity.prefill(Intent(Intent.ACTION_VIEW, "https://example.com/?text=x".toUri())))
    }

    @Test fun `nessun permesso di rete, microfono o accessibilita`() {
        val requested = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertEquals(
            setOf("android.permission.POST_NOTIFICATIONS", "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.RECEIVE_BOOT_COMPLETED"),
            requested.filter { it.startsWith("android.permission.") }.toSet(),
        )
        assertTrue("android.permission.INTERNET" !in requested && "android.permission.RECORD_AUDIO" !in requested)
    }
}
