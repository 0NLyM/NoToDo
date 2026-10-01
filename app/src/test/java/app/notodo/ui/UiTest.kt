package app.notodo.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

/** Colore con cui è davvero disegnato il testo (quello risolto dal tema, non quello richiesto). */
private fun ComposeTestRule.textColor(text: String): Color {
    val out = mutableListOf<TextLayoutResult>()
    onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(out)
    return out.single().layoutInput.style.color
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

    @Test fun `unisci, rianalizza e separa dalle card`() {
        type("domani chiama Luca; porta il cavo")
        assertEquals(2, rule.onAllNodesWithContentDescription("Scarta").fetchSemanticsNodes().size)
        rule.onAllNodesWithContentDescription("Azioni")[0].performClick()
        rule.onNodeWithText("Unisci con il successivo").performClick()
        rule.settle()
        assertEquals(1, rule.onAllNodesWithContentDescription("Scarta").fetchSemanticsNodes().size)
        rule.onNodeWithText("Chiama Luca; porta il cavo").assertExists()
        rule.onNodeWithText("Rianalizza").performClick()
        rule.settle()
        assertEquals(2, rule.onAllNodesWithContentDescription("Scarta").fetchSemanticsNodes().size)
    }

    @Test fun `separa una frase con due azioni`() {
        type("chiama Luca e manda la mail a Rossi")
        rule.onNodeWithContentDescription("Azioni").performClick()
        rule.onNodeWithText("Separa").performClick()
        rule.settle()
        rule.onNodeWithText("Chiama Luca").assertExists()
        rule.onNodeWithText("Manda la mail a Rossi").assertExists()
    }

    @Test fun `chiusura immediata dopo la digitazione non perde il testo`() {
        rule.onNode(hasSetTextAction()).performTextInput("chiama il tecnico della caldaia")
        rule.activityRule.scenario.close() // prima che scada il debounce della bozza
        rule.until { runBlocking { app.repo.inbox.first().any { it.text == "chiama il tecnico della caldaia" } } }
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

/**
 * Configurazione schermo di default: con w412dp-xxhdpi Robolectric non raggiunge mai l'idle
 * se un qualsiasi campo di testo (anche BasicTextField) sta in un Dialog. Limite dell'ambiente di test.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorUiTest {
    @get:Rule val rule = createAndroidComposeRule<CaptureActivity>()

    @Test fun `correggi apre l'editor della card`() {
        rule.onNode(hasSetTextAction()).performTextInput("alle 3 riavvia il NAS")
        rule.settle()
        rule.until { rule.onAllNodesWithText("Riavvia il NAS").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Riavvia il NAS").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Correggi").assertExists()
        rule.shot("correggi")
        rule.onNodeWithText("Idea").performClick()
        rule.onNodeWithText("Applica").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("IDEA").assertExists()
        rule.onNodeWithText("Ora ambigua", substring = true).assertDoesNotExist() // correzione manuale = confermato
    }
}

/** Schermate principali renderizzate (chiaro e scuro) per la revisione visiva. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
abstract class ScreensTest(private val prefix: String, private val dark: Boolean) {
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
            assertEquals("titolo della card ($prefix)", dark, rule.textColor("Chiamare Rossi").luminance() > .5f)
        }
        val id = runBlocking { app.repo.items.first().first { it.title == "Chiamare Rossi" }.id }
        ActivityScenario.launch<MainActivity>(MainActivity.open(app, id)).use {
            rule.until { rule.onAllNodesWithText("Testo originale", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Chiamare Rossi").assertExists()
        }
        ActivityScenario.launch<MainActivity>(MainActivity.openView(app, View.TODAY)).use {
            rule.settle()
            rule.onNodeWithContentDescription("Impostazioni").performClick()
            rule.settle()
            rule.shot("$prefix-impostazioni")
        }
        ActivityScenario.launch<SnoozeActivity>(SnoozeActivity.intent(app, id)).use {
            rule.until { rule.onAllNodesWithText("Chiamare Rossi").fetchSemanticsNodes().isNotEmpty() }
            rule.settle()
            rule.shot("$prefix-rimanda")
        }
    }
}

class LightScreensTest : ScreensTest("chiaro", dark = false)

@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class DarkScreensTest : ScreensTest("scuro", dark = true)

/** Lista dal basso verso l'alto: il primo elemento sta in basso, in una pillola rossa; la casella lo completa. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class ListUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun top(title: String) = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top

    /** Il bordo destro della riga è vuoto: lì si vede solo lo sfondo, rosso (primary chiaro) se è la pillola. */
    private fun pill(title: String): Boolean {
        val r = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        return bmp.getPixel((r.right - 8).toInt(), r.center.y.toInt()) == 0xFFC8102E.toInt()
    }

    @Test fun `il primo e' in basso nella pillola rossa e la casella lo completa`() {
        runBlocking {
            val s = app.settings.get()
            val t = "Domani alle 09:00 chiama Luca; domani alle 10:00 chiama Anna"
            app.repo.confirm("lista", t, "app", s.zoneId, app.parser.parse(t, s.parseContext(app.repo.clock())))
        }
        ActivityScenario.launch<MainActivity>(MainActivity.openView(app, View.ALL)).use {
            rule.until { rule.onAllNodesWithText("Chiama Anna").fetchSemanticsNodes().isNotEmpty() }
            rule.settle()
            assertTrue("il primo elemento è il più in basso", top("Chiama Luca") > top("Chiama Anna"))
            assertTrue(pill("Chiama Luca"))
            assertFalse(pill("Chiama Anna"))
            rule.shot("lista")

            val boxes = rule.onAllNodes(isToggleable())
            assertEquals(2, boxes.fetchSemanticsNodes().size)
            boxes[boxes.fetchSemanticsNodes().withIndex().maxBy { it.value.boundsInRoot.top }.index].performClick()
            rule.until { runBlocking { app.repo.items.first().first { it.title == "Chiama Luca" }.done } }
            rule.settle()
            assertTrue("la pillola passa al successivo", pill("Chiama Anna"))
            assertFalse(pill("Chiama Luca"))
            rule.shot("lista-fatto")
        }
    }
}
