package app.notodo.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.notodo.parse.ItalianParser
import app.notodo.parse.ParseContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RepoTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun ms(iso: String) = LocalDateTime.parse(iso).atZone(rome).toInstant().toEpochMilli()
    private var now = ms("2026-09-29T10:00")
    private val dbs = mutableListOf<Db>()
    private lateinit var repo: Repo

    private fun newRepo() = Repo(
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), Db::class.java).build().also(dbs::add),
        clock = { now },
    )

    @Before fun setUp() {
        repo = newRepo()
    }

    @After fun tearDown() = dbs.forEach { it.close() }

    private fun drafts(t: String) = ItalianParser.parse(t, ParseContext(Instant.ofEpochMilli(now).atZone(rome)))
    private suspend fun save(id: String, t: String) = repo.confirm(id, t, "test", rome, drafts(t))

    @Test fun `bozza salvata a ogni modifica e rimossa se svuotata`() = runTest {
        repo.saveDraft("c1", "domani chiama", "app", rome)
        repo.saveDraft("c1", "domani chiama Luca", "app", rome)
        assertEquals(listOf("domani chiama Luca"), repo.inbox.first().map { it.text })
        repo.saveDraft("c1", "  ", "app", rome)
        assertTrue(repo.inbox.first().isEmpty())
    }

    @Test fun `conferma atomica e idempotente, la bozza esce dall'Inbox`() = runTest {
        val text = "Domani alle 09:00 chiama Luca; venerdì prossimo alle 16 verifica backup"
        repo.saveDraft("c1", text, "assistente", rome)
        assertTrue(repo.confirm("c1", text, "assistente", rome, drafts(text)))
        assertFalse(repo.confirm("c1", text, "assistente", rome, drafts(text))) // doppio tocco o retry
        val items = repo.items.first()
        assertEquals(2, items.size)
        assertTrue(repo.inbox.first().isEmpty())
        assertEquals(text, repo.findCapture("c1")!!.text)
        val luca = items.single { it.title == "Chiama Luca" }
        assertEquals(ms("2026-09-30T08:00"), luca.nextAlertAt) // 1 ora prima
        assertEquals("creato", repo.audit(luca.id).first().single().action)
    }

    @Test fun `snooze sposta solo l'avviso e ne resta uno solo`() = runTest {
        save("c1", "domani alle 15 chiama Luca")
        val id = repo.items.first().single().id
        now = ms("2026-09-30T14:00")
        val fired = repo.fireDue()
        assertEquals(listOf(id), fired.map { it.id })
        assertEquals(ms("2026-09-30T15:00"), fired.single().nextAlertAt)

        repo.snooze(id, ms("2026-09-30T16:00"))
        val i = repo.findItem(id)!!
        assertEquals(ms("2026-09-30T15:00"), i.at) // scadenza invariata
        assertEquals(ms("2026-09-30T16:00"), repo.nextAlert()) // l'avviso delle 15 è silenziato
        now = ms("2026-09-30T15:00")
        assertTrue(repo.fireDue().isEmpty())
        now = ms("2026-09-30T16:00")
        assertEquals(1, repo.fireDue().size)
        assertNull(repo.nextAlert())
        assertEquals(listOf("avviso inviato", "avviso rimandato", "avviso inviato", "creato"), repo.audit(id).first().map { it.action })
    }

    @Test fun `avvisi persi a telefono spento arrivano una volta sola`() = runTest {
        save("c1", "domani alle 15 chiama Luca")
        now = ms("2026-09-30T18:00")
        assertEquals(1, repo.fireDue().size)
        assertTrue(repo.fireDue().isEmpty())
        assertNull(repo.nextAlert())
    }

    @Test fun `avvisi gia passati alla creazione non partono`() = runTest {
        save("c1", "tra 30 minuti chiama Luca") // «1 ora prima» è già nel passato
        assertEquals(ms("2026-09-29T10:30"), repo.nextAlert())
    }

    @Test fun `completare annulla gli avvisi, riaprire non recupera quelli passati`() = runTest {
        save("c1", "domani alle 15 chiama Luca")
        val id = repo.items.first().single().id
        repo.setDone(id, true)
        assertNull(repo.nextAlert())
        now = ms("2026-09-30T14:30")
        repo.setDone(id, false)
        assertEquals(ms("2026-09-30T15:00"), repo.nextAlert())
    }

    @Test fun `spostare la scadenza ricalcola gli avvisi e resta in cronologia`() = runTest {
        save("c1", "domani alle 15 chiama Luca")
        val i = repo.items.first().single()
        repo.update(i.copy(at = ms("2026-10-01T11:00")), "scadenza spostata")
        assertEquals(ms("2026-10-01T10:00"), repo.nextAlert())
        val log = repo.audit(i.id).first().first()
        assertEquals("scadenza spostata", log.action)
        assertTrue(log.detail.startsWith("data "))
    }

    @Test fun `export e import doppio senza duplicati`() = runTest {
        save("c1", "Domani alle 09:00 chiama Luca #lavoro; il router usa VLAN 40")
        save("c2", "Venerdì ricordami di chiamare Rossi")
        repo.saveDraft("c3", "asdf qwer", "app", rome)
        val id = repo.items.first().first { it.title == "Chiama Luca" }.id
        repo.snooze(id, ms("2026-09-29T12:00"))
        val json = repo.exportJson()

        val other = newRepo()
        other.importJson(json)
        other.importJson(json)
        assertEquals(repo.items.first().sortedBy { it.id }, other.items.first().sortedBy { it.id })
        assertEquals(repo.inbox.first(), other.inbox.first())
        assertEquals(
            repo.items.first().flatMap { repo.audit(it.id).first() }.toSet(),
            other.items.first().flatMap { other.audit(it.id).first() }.toSet(),
        )

        // una modifica più recente non viene sovrascritta da un backup vecchio
        val luca = other.findItem(id)!!
        now += 60_000
        other.update(luca.copy(title = "Chiama Luca per il firewall"))
        other.importJson(json)
        assertEquals("Chiama Luca per il firewall", other.findItem(id)!!.title)

        val md = repo.exportMarkdown()
        assertTrue(md.contains("**Chiama Luca**"))
        assertTrue(md.contains("asdf qwer"))
        assertTrue(md.contains("#lavoro"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rifiuta file non NoToDo`() = runTest {
        repo.importJson("""{"format":"altro","version":1,"exportedAt":"x","captures":[],"items":[],"audit":[]}""")
    }
}
