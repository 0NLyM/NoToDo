package app.notodo.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ItalianParserTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun ctx(iso: String) = ParseContext(
        now = LocalDateTime.parse(iso).atZone(rome),
        tagRules = mapOf("lavoro" to listOf("firewall", "backup")),
    )
    private val tuesday = ctx("2026-09-29T10:00") // martedì
    private fun parse(t: String, c: ParseContext = tuesday) = ItalianParser.parse(t, c)
    private fun one(t: String, c: ParseContext = tuesday) = parse(t, c).single()
    private fun at(iso: String): ZonedDateTime = LocalDateTime.parse(iso).atZone(rome)
    private fun Draft.doubt(s: String) = doubts.any { it.text.contains(s) }

    // ---------- casi di accettazione ----------

    @Test fun `due task distinti con data e ora in Europe Rome`() {
        val d = parse("Domani alle 09:00 chiama Luca; venerdì prossimo alle 16 verifica backup")
        assertEquals(2, d.size)
        assertEquals(Kind.TASK, d[0].kind)
        assertEquals("Chiama Luca", d[0].title)
        assertEquals(at("2026-09-30T09:00"), d[0].at)
        assertEquals(Precision.EXACT, d[0].precision)
        assertEquals(listOf("Luca"), d[0].people)
        assertEquals(Kind.TASK, d[1].kind)
        assertEquals("Verifica backup", d[1].title)
        assertEquals(at("2026-10-09T16:00"), d[1].at)
        assertEquals(listOf("m60", "m0"), d[1].alerts)
        assertTrue(d.all { it.doubts.isEmpty() })
    }

    @Test fun `informazione tecnica senza scadenza ne avvisi`() {
        val d = one("Il router usa VLAN 40")
        assertEquals(Kind.REFERENCE, d.kind)
        assertEquals("Il router usa VLAN 40", d.title)
        assertNull(d.at)
        assertTrue(d.alerts.isEmpty())
    }

    @Test fun `giorno senza ora non inventa l'ora e chiede conferma`() {
        val d = one("Venerdì ricordami di chiamare Rossi")
        assertEquals(Kind.TASK, d.kind)
        assertEquals("Chiamare Rossi", d.title)
        assertEquals(at("2026-10-02T00:00"), d.at)
        assertEquals(Precision.DAY, d.precision)
        assertEquals(listOf("d0@09:00"), d.alerts)
        assertTrue(d.doubt("Ora non indicata"))
        assertEquals(listOf("Rossi"), d.people)
    }

    @Test fun `evento con avviso il giorno prima resta un solo elemento`() {
        val d = one("Incontra Marco il 3 ottobre alle 15; ricordami il giorno prima")
        assertEquals(Kind.EVENT, d.kind)
        assertEquals("Incontra Marco", d.title)
        assertEquals(at("2026-10-03T15:00"), d.at)
        assertEquals(listOf("d1", "m0"), d.alerts)
        assertEquals(at("2026-10-02T15:00"), Alerts.time(d.at!!, d.alerts[0]))
        assertEquals(listOf("Marco"), d.people)
    }

    @Test fun `input incomprensibile non riconosciuto`() {
        val d = one("asdf qwer zxcv")
        assertFalse(d.recognized)
        assertEquals("asdf qwer zxcv", d.source)
    }

    @Test fun `esempio multi elemento del brief`() {
        val d = parse(
            "Venerdì prossimo alle 15:30 chiama Rossi per il firewall; lunedì alle 9 controlla il backup; " +
                "ricordati che il router di sede B usa VLAN 40; idea: stampare un supporto 3D per la scrivania"
        )
        assertEquals(listOf(Kind.TASK, Kind.TASK, Kind.REFERENCE, Kind.IDEA), d.map { it.kind })
        assertEquals("Chiama Rossi per il firewall", d[0].title)
        assertEquals(at("2026-10-09T15:30"), d[0].at)
        assertEquals(listOf("lavoro"), d[0].tags)
        assertEquals(listOf("Rossi"), d[0].people)
        assertEquals("Controlla il backup", d[1].title)
        assertEquals(at("2026-10-05T09:00"), d[1].at)
        assertEquals("Il router di sede B usa VLAN 40", d[2].title)
        assertNull(d[2].at)
        assertTrue(d[2].alerts.isEmpty())
        assertEquals("Stampare un supporto 3D per la scrivania", d[3].title)
        assertNull(d[3].at)
    }

    @Test fun `data menzionata al passato non diventa scadenza`() {
        val d = one("Ho parlato con Rossi il 12 settembre")
        assertEquals(Kind.NOTE, d.kind)
        assertNull(d.at)
        assertEquals(listOf(LocalDate.of(2026, 9, 12)), d.mentions)
        assertTrue(d.alerts.isEmpty())
        assertEquals("Ho parlato con Rossi il 12 settembre", d.title)
    }

    // ---------- giorni della settimana ----------

    @Test fun `questo prossimo e venerdi semplice di martedi`() {
        assertEquals(at("2026-10-02T00:00"), one("questo venerdì paga").at)
        assertEquals(at("2026-10-02T00:00"), one("venerdì paga").at)
        assertEquals(at("2026-10-09T00:00"), one("venerdì prossimo paga").at)
        assertEquals(at("2026-10-09T00:00"), one("prossimo venerdì paga").at)
    }

    @Test fun `venerdi detto di venerdi`() {
        val friday = ctx("2026-10-02T10:00")
        val bare = one("venerdì chiama Rossi", friday)
        assertEquals(at("2026-10-09T00:00"), bare.at)
        assertEquals(at("2026-10-02T00:00"), bare.doubts.first { it.alternative != null }.alternative)
        assertEquals(at("2026-10-02T00:00"), one("questo venerdì chiama Rossi", friday).at)
        assertEquals(at("2026-10-09T00:00"), one("venerdì prossimo chiama Rossi", friday).at)
    }

    @Test fun `questo venerdi gia passato e domenica verso lunedi prossimo`() {
        val saturday = ctx("2026-10-03T10:00")
        val d = one("questo venerdì chiama Rossi", saturday)
        assertEquals(at("2026-10-09T00:00"), d.at)
        assertTrue(d.doubt("già passato"))
        val sunday = ctx("2026-10-04T10:00")
        assertEquals(at("2026-10-05T00:00"), one("lunedì chiama Rossi", sunday).at)
        assertEquals(at("2026-10-05T00:00"), one("lunedì prossimo chiama Rossi", sunday).at)
    }

    // ---------- relative, ore, precisione ----------

    @Test fun `espressioni relative`() {
        assertEquals(at("2026-09-29T12:00"), one("tra 2 ore riavvia il server").at)
        assertEquals(at("2026-09-29T10:30"), one("tra mezz'ora chiama Luca").at)
        assertEquals(at("2026-09-29T11:00"), one("fra un'ora chiama Luca").at)
        assertEquals(at("2026-10-02T00:00"), one("tra tre giorni chiama Luca").at)
        assertEquals(Precision.DAY, one("tra tre giorni chiama Luca").precision)
        assertEquals(at("2026-10-01T00:00"), one("dopodomani chiama Luca").at)
        assertEquals(at("2026-09-29T00:00"), one("oggi chiama Luca").at)
    }

    @Test fun `ora senza data oggi o domani se gia passata`() {
        assertEquals(at("2026-09-29T15:00"), one("alle 15 chiama Luca").at)
        val late = one("alle 15 chiama Luca", ctx("2026-09-29T16:00"))
        assertEquals(at("2026-09-30T15:00"), late.at)
        assertTrue(late.doubt("già passate"))
    }

    @Test fun `orario approssimativo e parti del giorno`() {
        val d = one("verso le 16 chiama il fornitore")
        assertEquals(at("2026-09-29T16:00"), d.at)
        assertEquals(Precision.APPROX, d.precision)
        val sera = one("domani sera chiama mamma")
        assertEquals(at("2026-09-30T20:00"), sera.at)
        assertEquals(Precision.APPROX, sera.precision)
        assertTrue(sera.doubt("sera"))
        assertEquals(at("2026-09-30T20:00"), one("domani sera alle 8 chiama mamma").at)
    }

    @Test fun `ora ambigua marcata con alternativa`() {
        val d = one("domani alle 3 chiama Luca")
        assertEquals(at("2026-09-30T15:00"), d.at)
        assertEquals(at("2026-09-30T03:00"), d.doubts.single { it.text.startsWith("Ora ambigua") }.alternative)
        assertEquals(at("2026-09-30T07:30"), one("domani alle 07:30 chiama Luca").at)
        assertEquals(at("2026-09-30T15:30"), one("domani alle 3 e mezza del pomeriggio chiama Luca").at)
    }

    @Test fun `entro venerdi e scadenza di giorno`() {
        val d = one("entro venerdì manda il preventivo a Bianchi")
        assertEquals(Kind.TASK, d.kind)
        assertEquals("Manda il preventivo a Bianchi", d.title)
        assertEquals(at("2026-10-02T00:00"), d.at)
        assertEquals(Precision.DAY, d.precision)
        assertEquals(listOf("Bianchi"), d.people)
    }

    @Test fun `il 5 ottobre e date numeriche`() {
        assertEquals(at("2026-10-05T00:00"), one("il 5 ottobre paga l'affitto").at)
        assertEquals(at("2026-10-05T10:00"), one("5/10 alle 10 paga l'affitto").at)
        assertEquals(at("2027-10-05T00:00"), one("il 5 ottobre 2027 rinnova il dominio").at)
        assertEquals(at("2026-10-09T15:00"), one("venerdì 9 ottobre alle 15 riunione").at)
        assertTrue(one("giovedì 9 ottobre alle 15 riunione").doubt("non corrisponde"))
    }

    // ---------- DST e cambio anno ----------

    @Test fun `fine ora legale 25 ottobre 2026`() {
        val sat = ctx("2026-10-24T20:00")
        assertEquals(ZonedDateTime.parse("2026-10-25T07:00+01:00[Europe/Rome]"), one("tra 12 ore controlla il backup", sat).at)
        assertEquals(ZonedDateTime.parse("2026-10-25T09:00+01:00[Europe/Rome]"), one("domani alle 9 controlla il backup", sat).at)
        assertTrue(one("domani alle 02:30 controlla il backup", sat).doubt("si ripetono"))
    }

    @Test fun `inizio ora legale 29 marzo 2026`() {
        val d = one("il 29 marzo alle 02:30 aggiorna il firmware", ctx("2026-03-20T10:00"))
        assertEquals(ZonedDateTime.parse("2026-03-29T03:30+02:00[Europe/Rome]"), d.at)
        assertTrue(d.doubt("non esistono"))
    }

    @Test fun `cambio anno`() {
        val dec = ctx("2026-12-20T10:00")
        val jan = one("il 5 gennaio rinnova il certificato", dec)
        assertEquals(at("2027-01-05T00:00"), jan.at)
        assertFalse(jan.doubt("già passato"))
        assertEquals(at("2027-01-01T00:00"), one("domani chiama Luca", ctx("2026-12-31T10:00")).at)
        assertEquals(at("2026-12-31T23:00"), one("31 dicembre alle 23 brinda", ctx("2026-12-31T22:00")).at)
        val sep = one("il 5 settembre paga la bolletta")
        assertEquals(at("2027-09-05T00:00"), sep.at)
        assertEquals(at("2026-09-05T00:00"), sep.doubts.first { it.alternative != null }.alternative)
    }

    // ---------- dettatura, segmentazione, casi vari ----------

    @Test fun `dettatura senza punteggiatura con due date`() {
        val a = parse("domani alle 9 chiama Luca venerdì prossimo alle 16 verifica backup")
        assertEquals(listOf("Chiama Luca", "Verifica backup"), a.map { it.title })
        assertEquals(listOf(at("2026-09-30T09:00"), at("2026-10-09T16:00")), a.map { it.at })
        val b = parse("chiama Luca domani alle 9 e verifica il backup venerdì prossimo alle 16")
        assertEquals(listOf("Chiama Luca", "Verifica il backup"), b.map { it.title })
        assertEquals(listOf(at("2026-09-30T09:00"), at("2026-10-09T16:00")), b.map { it.at })
    }

    @Test fun `una sola data non divide la frase`() {
        val d = one("domani chiama Rossi e chiedi del firewall")
        assertEquals("Chiama Rossi e chiedi del firewall", d.title)
    }

    @Test fun `ricordati che e ricordati di`() {
        assertEquals(Kind.TASK, one("ricordati di comprare il latte").kind)
        assertEquals("Comprare il latte", one("ricordati di comprare il latte").title)
        assertEquals(Kind.REFERENCE, one("ricordati che la password del wifi ospiti è sul frigo").kind)
    }

    @Test fun `ora e giorno separati nella stessa frase`() {
        val d = one("dalle 15 alle 17 riunione con Marco domani")
        assertEquals(Kind.EVENT, d.kind)
        assertEquals(at("2026-09-30T15:00"), d.at)
        assertEquals("Dalle 15 alle 17 riunione con Marco", d.title)
    }

    @Test fun `richiesta esplicita di avviso e dati preservati`() {
        val d = one("Il certificato SSL di mail.example.com scade il 5 novembre, ricordamelo")
        assertEquals(at("2026-11-05T00:00"), d.at)
        assertTrue(d.alerts.isNotEmpty())
        assertEquals("Il certificato SSL di mail.example.com scade", d.title)
        val mail = one("scrivi a mario.rossi@example.com per il preventivo di https://example.com/a.b?x=1")
        assertTrue(mail.title.contains("mario.rossi@example.com"))
        assertTrue(mail.title.contains("https://example.com/a.b?x=1"))
    }

    @Test fun `data al genitivo e menzione, scadenza separata`() {
        val d = one("Paga la fattura del 5 ottobre entro venerdì")
        assertEquals(at("2026-10-02T00:00"), d.at)
        assertEquals(listOf(LocalDate.of(2026, 10, 5)), d.mentions)
        assertEquals("Paga la fattura del 5 ottobre", d.title)
    }

    @Test fun `ricorrenza segnalata come non supportata`() {
        val d = one("ogni lunedì controlla il backup")
        assertEquals(at("2026-10-05T00:00"), d.at)
        assertTrue(d.doubt("Ricorrenza"))
    }

    @Test fun `hashtag menzioni e idee non notificano`() {
        val d = one("#casa compra il detersivo per @Anna")
        assertEquals(listOf("casa"), d.tags)
        assertEquals(listOf("Anna"), d.people)
        assertEquals("Compra il detersivo per Anna", d.title)
        assertTrue(one("idea: domani provare il nuovo firewall").alerts.isEmpty())
        assertEquals(Kind.VERIFY, one("forse la password del NAS è cambiata").kind)
    }

    @Test fun `separa e unisci`() {
        val t = "chiama Luca e manda la mail a Rossi"
        val parts = ItalianParser.splitSpan(t, 0, t.length)
        assertEquals(listOf("chiama Luca", "manda la mail a Rossi"), parts.map { t.substring(it.first, it.last + 1) })
        val two = "domani chiama Luca; porta il cavo"
        val drafts = parse(two)
        assertEquals(2, drafts.size)
        val merged = ItalianParser.parseSpan(two, drafts[0].start, drafts[1].end, tuesday)
        assertEquals(at("2026-09-30T00:00"), merged.at)
        assertEquals("Chiama Luca; porta il cavo", merged.title)
    }

    @Test fun `motivi coerenti con l'ora finale e marcatori spezzati dalla data`() {
        val sveglia = one("Domani mattina alle 7 sveglia")
        assertEquals(at("2026-09-30T07:00"), sveglia.at)
        assertTrue(sveglia.reasons.contains("«alle 7» = 07:00"))
        val pizza = one("ricordami tra 20 minuti di togliere la pizza")
        assertEquals(Kind.TASK, pizza.kind)
        assertEquals("Togliere la pizza", pizza.title)
        assertEquals(at("2026-09-29T10:20"), pizza.at)
        assertEquals(Kind.TASK, one("Lunedì e martedì controlla i log").kind)
        assertEquals(listOf("Rossi"), one("Il dott. Rossi arriva giovedì alle 11").people)
    }

    @Test fun `testo vuoto o solo punteggiatura`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse(" ;;\n. ").isEmpty())
    }
}
