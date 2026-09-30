package app.notodo.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.notodo.App
import app.notodo.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class WidgetTest {
    private val app = ApplicationProvider.getApplicationContext<App>()

    private fun render(name: String): View = runBlocking {
        val s = app.settings.get()
        listOf("tra 2 ore controlla il firewall", "domani alle 9 chiama Luca", "venerdì prossimo alle 16 verifica backup", "il router usa VLAN 40")
            .forEachIndexed { i, t -> app.repo.confirm("w$i", t, "test", s.zoneId, app.parser.parse(t, s.parseContext(app.repo.clock()))) }
        val root = FrameLayout(app)
        val v = Widget.views(app, app.repo.items.first(), ZonedDateTime.now(s.zoneId)).apply(app, root)
        val w = (250 * app.resources.displayMetrics.density).toInt()
        val h = (180 * app.resources.displayMetrics.density).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        v
    }

    @Test fun `prossime scadenze in ordine, note escluse`() {
        val v = render("widget-chiaro")
        val rows = v.findViewById<android.widget.LinearLayout>(R.id.rows)
        val titles = (0 until rows.childCount).map { rows.getChildAt(it).findViewById<TextView>(R.id.text).text.toString() }
        assertEquals(listOf("Controlla il firewall", "Chiama Luca", "Verifica backup"), titles)
    }

    @Config(qualifiers = "+night")
    @Test fun scuro() {
        render("widget-scuro")
    }
}
