package app.notodo.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.notodo.App
import app.notodo.data.View
import app.notodo.parse.CaptureParser
import app.notodo.parse.Draft
import app.notodo.parse.ParseContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration

private val app get() = ApplicationProvider.getApplicationContext<App>()

private fun idleMain() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

private fun ComposeTestRule.settle() {
    idleMain()
    waitForIdle()
}

private fun ComposeTestRule.until(cond: () -> Boolean) = waitUntil(5_000) {
    idleMain()
    cond()
}

private fun ComposeTestRule.shot(name: String) {
    val bmp = onRoot().captureToImage().asAndroidBitmap()
    File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class CaptureUiTest {
    @get:Rule val rule = createAndroidComposeRule<CaptureActivity>()

    @Before fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun type(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.settle()
        rule.until { runBlocking { app.repo.inbox.first().any { it.text == text } } }
        rule.settle()
    }

    private fun toast(): String? = ShadowToast.getTextOfLatestToast()

    @Test fun `tre elementi in anteprima, salvati insieme`() {
        type("Domani alle 09:00 chiama Luca; venerdì prossimo alle 16 verifica backup; il router usa VLAN 40")
        rule.onNodeWithText("Chiama Luca").assertExists()
        rule.onNodeWithText("Verifica backup").assertExists()
        rule.onNodeWithText("Il router usa VLAN 40").assertExists()
        rule.shot("cattura")
        rule.onNodeWithText("Salva tutto (3)").performClick()
        rule.until { toast() != null }
        assertEquals("Salvati 3 elementi", toast())
        assertEquals(3, runBlocking { app.repo.items.first().size })
        assertEquals(0, runBlocking { app.repo.inbox.first().size })
    }

    @Test fun `testo non riconosciuto resta integro in Inbox`() {
        type("asdf qwer zxcv")
        rule.onNodeWithText("Salva").performClick()
        rule.until { toast() != null }
        assertEquals("Non riconosciuto: testo salvato in Inbox", toast())
        assertEquals(listOf("asdf qwer zxcv"), runBlocking { app.repo.inbox.first().map { it.text } })
        assertEquals(0, runBlocking { app.repo.items.first().size })
    }

    @Test fun `parser guasto non perde il testo`() {
        app.parser = object : CaptureParser {
            override fun parse(text: String, ctx: ParseContext): List<Draft> = error("guasto simulato")
            override fun parseSpan(text: String, start: Int, end: Int, ctx: ParseContext): Draft = error("guasto simulato")
            override fun splitSpan(text: String, start: Int, end: Int): List<IntRange> = error("guasto simulato")
        }
        type("domani alle 9 chiama Luca")
        rule.onNodeWithText("Analisi non riuscita", substring = true).assertExists()
        rule.onNodeWithText("Salva").performClick()
        rule.until { toast() != null }
        assertEquals("Analisi non riuscita: testo salvato in Inbox", toast())
        assertEquals(listOf("domani alle 9 chiama Luca"), runBlocking { app.repo.inbox.first().map { it.text } })
    }
}

/** Schermate principali renderizzate (chiaro e scuro) per la revisione visiva. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
abstract class ScreensTest(private val prefix: String) {
    @get:Rule val rule = createEmptyComposeRule()

    private fun seed() = runBlocking {
        val s = app.settings.get()
        listOf(
            "Domani alle 09:00 chiama Luca; venerdì prossimo alle 16 verifica backup #lavoro",
            "tra 2 ore controlla il firewall di sede B; ricordati che il router di sede B usa VLAN 40",
            "Venerdì ricordami di chiamare Rossi; idea: stampare un supporto 3D per la scrivania",
            "Incontra Marco il 3 ottobre alle 15; ricordami il giorno prima",
            "Ho parlato con Rossi il 12 settembre del rinnovo licenze",
        ).forEachIndexed { i, t -> app.repo.confirm("seed$i", t, "assistente", s.zoneId, app.parser.parse(t, s.parseContext(app.repo.clock()))) }
        app.repo.saveDraft("draft", "asdf qwer", "widget", s.zoneId)
    }

    @Test fun schermate() {
        seed()
        ActivityScenario.launch<MainActivity>(MainActivity.openView(app, View.TODAY)).use {
            rule.until { rule.onAllNodesWithText("Controlla il firewall di sede B").fetchSemanticsNodes().isNotEmpty() }
            rule.settle()
            rule.shot("$prefix-oggi")
        }
        ActivityScenario.launch<MainActivity>(MainActivity.openView(app, View.ALL)).use {
            rule.until { rule.onAllNodesWithText("Chiama Luca").fetchSemanticsNodes().isNotEmpty() }
            rule.settle()
            rule.shot("$prefix-tutto")
            rule.onNodeWithText("Chiama Luca").performClick()
            rule.settle()
            rule.shot("$prefix-dettaglio")
        }
        ActivityScenario.launch<CaptureActivity>(
            CaptureActivity.intent(app, "assistente").putExtra(CaptureActivity.EXTRA_TEXT, "Venerdì ricordami di chiamare Rossi; alle 3 riavvia il NAS"),
        ).use {
            rule.settle()
            rule.until { runBlocking { app.repo.inbox.first().any { it.text.startsWith("Venerdì") } } }
            rule.settle()
            rule.shot("$prefix-cattura-dubbi")
        }
    }
}

class LightScreensTest : ScreensTest("chiaro")

@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class DarkScreensTest : ScreensTest("scuro")
