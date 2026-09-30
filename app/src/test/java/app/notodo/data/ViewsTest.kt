package app.notodo.data

import app.notodo.parse.Kind
import app.notodo.parse.Precision
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ViewsTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val now: ZonedDateTime = LocalDateTime.parse("2026-09-29T10:00").atZone(rome)
    private fun ms(iso: String) = LocalDateTime.parse(iso).atZone(rome).toInstant().toEpochMilli()

    private fun item(
        id: String, kind: Kind = Kind.TASK, at: String? = null, day: Boolean = false, done: Boolean = false,
        title: String = id, tags: List<String> = emptyList(), people: List<String> = emptyList(), created: String = "2026-09-29T09:00",
    ) = Item(
        id = id, captureId = null, kind = kind, title = title, done = done, at = at?.let(::ms), zone = rome.id,
        precision = at?.let { if (day) Precision.DAY else Precision.EXACT }, tags = tags, people = people,
        createdAt = ms(created), updatedAt = ms(created),
    ).derived()

    private val items = listOf(
        item("scaduto", at = "2026-09-28T09:00"),
        item("oggi-giorno", at = "2026-09-29T00:00", day = true),
        item("oggi", at = "2026-09-29T15:00", tags = listOf("lavoro"), people = listOf("Rossi")),
        item("domani", at = "2026-09-30T09:00", title = "Venerdì chiama il fornitore"),
        item("senza-data"),
        item("evento-ieri", kind = Kind.EVENT, at = "2026-09-28T09:00"),
        item("nota", kind = Kind.NOTE, title = "Il router usa VLAN 40", created = "2026-09-01T09:00"),
        item("rif", kind = Kind.REFERENCE),
        item("idea", kind = Kind.IDEA),
        item("fatto", at = "2026-09-29T11:00", done = true),
    )

    private fun ids(view: View, query: String = "", f: Filter = Filter()) = select(items, view, query, f, now).map { it.id }

    @Test fun viste() {
        assertEquals(listOf("scaduto", "oggi-giorno", "oggi"), ids(View.TODAY))
        assertEquals(listOf("domani"), ids(View.UPCOMING))
        assertEquals(listOf("senza-data"), ids(View.PENDING))
        assertEquals(setOf("nota", "rif"), ids(View.KNOWLEDGE).toSet())
        assertEquals(listOf("idea"), ids(View.IDEAS))
        assertEquals(items.size, ids(View.ALL).size)
    }

    @Test fun `ricerca ovunque, senza accenti, tutti i termini`() {
        assertEquals(listOf("nota"), ids(View.TODAY, "vlan 40"))
        assertEquals(listOf("domani"), ids(View.TODAY, "venerdi fornitore"))
        assertEquals(emptyList<String>(), ids(View.ALL, "vlan 50"))
    }

    @Test fun filtri() {
        assertEquals(listOf("oggi"), ids(View.ALL, f = Filter(tag = "lavoro")))
        assertEquals(listOf("oggi"), ids(View.ALL, f = Filter(person = "Rossi")))
        assertEquals(listOf("scaduto"), ids(View.ALL, f = Filter(due = Due.OVERDUE)))
        assertEquals(setOf("oggi-giorno", "oggi", "fatto"), ids(View.ALL, f = Filter(due = Due.TODAY)).toSet())
        assertEquals(listOf("fatto"), ids(View.ALL, f = Filter(status = Status.DONE)))
        assertEquals(listOf("idea"), ids(View.ALL, f = Filter(kind = Kind.IDEA)))
        assertEquals(false, "nota" in ids(View.ALL, f = Filter(created = Created.WEEK)))
    }

    @Test fun etichette() {
        assertEquals("Oggi 15:00", whenLabel(LocalDateTime.parse("2026-09-29T15:00").atZone(rome), Precision.EXACT, now))
        assertEquals("Domani ≈ 16:00", whenLabel(LocalDateTime.parse("2026-09-30T16:00").atZone(rome), Precision.APPROX, now))
        assertEquals("ven 9 ott", whenLabel(LocalDateTime.parse("2026-10-09T00:00").atZone(rome), Precision.DAY, now))
        assertEquals("mar 5 gen 2027", whenLabel(LocalDateTime.parse("2027-01-05T00:00").atZone(rome), Precision.DAY, now))
    }
}
