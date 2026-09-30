package app.notodo.data

import androidx.room.withTransaction
import app.notodo.parse.Draft
import app.notodo.parse.Kind
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class Repo(
    private val db: Db,
    val clock: () -> Long = System::currentTimeMillis,
    private val onChange: suspend () -> Unit = {},
) {
    private val dao = db.dao()
    val items: Flow<List<Item>> = dao.items()
    val inbox: Flow<List<Capture>> = dao.inbox()
    fun item(id: String) = dao.itemFlow(id)
    fun capture(id: String) = dao.captureFlow(id)
    fun audit(itemId: String) = dao.audit(itemId)
    suspend fun findCapture(id: String) = dao.capture(id)
    suspend fun findItem(id: String) = dao.item(id)
    suspend fun nextAlert() = dao.nextAlert()

    /** Bozza scritta a ogni modifica: sopravvive a chiusura dell'overlay e kill del processo. */
    suspend fun saveDraft(id: String, text: String, source: String, zone: ZoneId) {
        val old = dao.capture(id)
        when {
            old?.processed == true || old?.text == text -> Unit
            text.isBlank() -> dao.deleteDraft(id)
            else -> clock().let { t -> dao.upsert(Capture(id, text, source, zone.id, old?.createdAt ?: t, t)) }
        }
    }

    suspend fun deleteDraft(id: String) = dao.deleteDraft(id)

    /** Conferma dell'anteprima: tutto o niente; una seconda chiamata (doppio tocco, retry) non duplica. */
    suspend fun confirm(captureId: String, text: String, source: String, zone: ZoneId, drafts: List<Draft>): Boolean {
        val saved = db.withTransaction {
            val old = dao.capture(captureId)
            if (old?.processed == true) return@withTransaction false
            val t = clock()
            dao.upsert(Capture(captureId, text, source, zone.id, old?.createdAt ?: t, t, processed = true))
            drafts.forEachIndexed { i, d ->
                val item = d.toItem("$captureId-$i", captureId, zone, t).derived()
                dao.upsert(item)
                log(item.id, "creato", (listOf("Fonte: $source") + item.reasons).joinToString("\n"))
            }
            true
        }
        if (saved) onChange()
        return saved
    }

    private suspend fun edit(id: String, action: String, detail: (Item, Item) -> String = ::diff, change: (Item) -> Item) {
        db.withTransaction {
            val old = dao.item(id) ?: return@withTransaction
            val new = change(old).copy(updatedAt = clock()).derived()
            dao.upsert(new)
            log(id, action, detail(old, new))
        }
        onChange()
    }

    /** Modifica dall'editor: se cambiano data o avvisi, il calcolo degli avvisi riparte da adesso. */
    suspend fun update(new: Item, action: String = "modificato") = edit(new.id, action) { old ->
        val timing = old.at != new.at || old.alerts != new.alerts
        new.copy(firedUpTo = if (timing) clock() else old.firedUpTo, snoozeUntil = if (timing) null else old.snoozeUntil)
    }

    suspend fun setDone(id: String, done: Boolean) = edit(id, if (done) "completato" else "riaperto", { _, _ -> "" }) {
        it.copy(done = done, doneAt = if (done) clock() else null, firedUpTo = maxOf(it.firedUpTo, clock()), snoozeUntil = null)
    }

    /** Snooze: silenzia gli avvisi fino a [until] e ne manda uno solo allora. La scadenza non cambia. */
    suspend fun snooze(id: String, until: Long) = edit(id, "avviso rimandato", { _, _ -> "a ${stamp(until)}" }) {
        it.copy(snoozeUntil = until, firedUpTo = until - 1)
    }

    suspend fun markSeen(id: String) = dao.item(id)?.let { dao.upsert(it.copy(seenAt = clock())) }

    suspend fun delete(id: String) {
        db.withTransaction { dao.deleteItem(id); dao.deleteAudit(id) }
        onChange()
    }

    /** Avvisi dovuti: uno per elemento anche se, a telefono spento, ne sono stati persi più d'uno. */
    suspend fun fireDue(): List<Item> {
        val t = clock()
        val fired = db.withTransaction {
            dao.due(t).map { old ->
                old.copy(firedUpTo = t).derived().also {
                    dao.upsert(it)
                    log(old.id, "avviso inviato", "previsto ${stamp(old.nextAlertAt!!)}, inviato ${stamp(t)}")
                }
            }
        }
        if (fired.isNotEmpty()) onChange()
        return fired
    }

    private suspend fun log(itemId: String, action: String, detail: String) =
        dao.insert(Audit(UUID.randomUUID().toString(), itemId, clock(), action, detail))

    // ---------- backup ----------

    suspend fun exportJson(): String = json.encodeToString(
        Backup.serializer(),
        Backup(exportedAt = Instant.ofEpochMilli(clock()).toString(), captures = dao.allCaptures(), items = dao.allItems(), audit = dao.allAudit()),
    )

    /** Unione per id: niente duplicati, vince la versione modificata più di recente; gli avvisi passati non ripartono. */
    suspend fun importJson(text: String): String {
        val b = json.decodeFromString(Backup.serializer(), text)
        require(b.format == Backup.FORMAT) { "Non è un backup NoToDo" }
        require(b.version <= Backup.VERSION) { "Backup di una versione più recente (v${b.version})" }
        var added = 0
        var updated = 0
        db.withTransaction {
            val t = clock()
            b.captures.forEach { c ->
                val old = dao.capture(c.id)
                if (old == null || c.updatedAt > old.updatedAt) dao.upsert(c.copy(processed = c.processed || old?.processed == true))
            }
            b.items.forEach { i ->
                val old = dao.item(i.id)
                if (old == null || i.updatedAt > old.updatedAt) {
                    if (old == null) added++ else updated++
                    dao.upsert(i.copy(firedUpTo = maxOf(i.firedUpTo, old?.firedUpTo ?: 0, t)).derived())
                }
            }
            b.audit.forEach { dao.insert(it) }
        }
        onChange()
        return "Nuovi: $added · aggiornati: $updated · invariati: ${b.items.size - added - updated}"
    }

    suspend fun exportMarkdown(): String = buildString {
        val items = dao.allItems().sortedWith(compareBy({ it.done }, { it.at ?: Long.MAX_VALUE }, { it.createdAt }))
        val captures = dao.allCaptures().associateBy { it.id }
        appendLine("# NoToDo — export ${stamp(clock())}")
        val inbox = captures.values.filter { !it.processed }
        if (inbox.isNotEmpty()) {
            appendLine("\n## Inbox da elaborare\n")
            inbox.forEach { appendLine("- ${it.text.replace("\n", " ")}") }
        }
        Kind.entries.forEach { k ->
            val list = items.filter { it.kind == k }
            if (list.isEmpty()) return@forEach
            appendLine("\n## ${k.label}\n")
            list.forEach { i ->
                val meta = listOfNotNull(
                    i.at?.let { stamp(it, i.precision == app.notodo.parse.Precision.DAY) },
                    i.alerts.takeIf { it.isNotEmpty() }?.joinToString(", ") { app.notodo.parse.Alerts.label(it) }?.let { "avvisi: $it" },
                    i.people.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    i.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                ).joinToString(" · ")
                appendLine("- [${if (i.done) "x" else " "}] **${i.title}**" + if (meta.isEmpty()) "" else " — $meta")
                if (i.body.isNotBlank()) appendLine("  ${i.body}")
                captures[i.captureId]?.let { c -> appendLine("  > ${c.text.substring(i.spanStart.coerceIn(0, c.text.length), i.spanEnd.coerceIn(0, c.text.length)).replace("\n", " ")}") }
            }
        }
    }

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        private val STAMP = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ITALIAN)
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ITALIAN)
        fun stamp(ms: Long, day: Boolean = false): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { (if (day) DAY else STAMP).format(it) }

        fun diff(a: Item, b: Item): String = listOfNotNull(
            "tipo ${a.kind.label} → ${b.kind.label}".takeIf { a.kind != b.kind },
            "titolo «${a.title}» → «${b.title}»".takeIf { a.title != b.title },
            "dettagli modificati".takeIf { a.body != b.body },
            "data ${a.at?.let { stamp(it) } ?: "nessuna"} → ${b.at?.let { stamp(it) } ?: "nessuna"}".takeIf { a.at != b.at || a.precision != b.precision },
            "avvisi ${a.alerts} → ${b.alerts}".takeIf { a.alerts != b.alerts },
            "tag ${a.tags} → ${b.tags}".takeIf { a.tags != b.tags },
            "persone ${a.people} → ${b.people}".takeIf { a.people != b.people },
        ).joinToString("\n")
    }
}

@Serializable
data class Backup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: String,
    val captures: List<Capture>,
    val items: List<Item>,
    val audit: List<Audit>,
) {
    companion object {
        const val FORMAT = "notodo-backup"
        const val VERSION = 1
    }
}
