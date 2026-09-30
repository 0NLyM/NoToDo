package app.notodo.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime

class AlertsTest {
    private fun z(s: String) = ZonedDateTime.parse(s)
    private fun ms(s: String) = z(s).toInstant().toEpochMilli()

    @Test fun `giorno prima mantiene l'ora locale attraverso il cambio d'ora`() {
        val at = z("2026-10-25T15:00+01:00[Europe/Rome]")
        assertEquals(z("2026-10-24T15:00+02:00[Europe/Rome]"), Alerts.time(at, "d1"))
        assertEquals(z("2026-10-24T16:00+02:00[Europe/Rome]"), Alerts.time(at, "m1440"))
    }

    @Test fun `minuti prima sono durata reale`() {
        val at = z("2026-03-29T03:30+02:00[Europe/Rome]")
        assertEquals(z("2026-03-29T01:30+01:00[Europe/Rome]"), Alerts.time(at, "m60"))
    }

    @Test fun `ora fissa del giorno`() {
        val day = z("2026-10-02T00:00+02:00[Europe/Rome]")
        assertEquals(z("2026-10-02T09:00+02:00[Europe/Rome]"), Alerts.time(day, "d0@09:00"))
        assertEquals(z("2026-10-01T20:00+02:00[Europe/Rome]"), Alerts.time(day, "d1@20:00"))
    }

    @Test fun `prossimo avviso e snooze`() {
        val at = z("2026-10-02T15:00+02:00[Europe/Rome]")
        val specs = listOf("m60", "m0")
        assertEquals(ms("2026-10-02T14:00+02:00"), Alerts.next(at, specs, ms("2026-10-02T13:59+02:00"), null))
        assertEquals(ms("2026-10-02T15:00+02:00"), Alerts.next(at, specs, ms("2026-10-02T14:00+02:00"), null))
        assertNull(Alerts.next(at, specs, ms("2026-10-02T15:00+02:00"), null))
        assertEquals(ms("2026-10-02T16:00+02:00"), Alerts.next(at, specs, ms("2026-10-02T15:59+02:00"), ms("2026-10-02T16:00+02:00")))
    }

    @Test fun etichette() {
        assertEquals("All'ora", Alerts.label("m0"))
        assertEquals("1 ora prima", Alerts.label("m60"))
        assertEquals("15 min prima", Alerts.label("m15"))
        assertEquals("Il giorno prima", Alerts.label("d1"))
        assertEquals("Alle 09:00 del giorno", Alerts.label("d0@09:00"))
        assertEquals("2 giorni prima alle 09:00", Alerts.label("d2@09:00"))
    }
}
