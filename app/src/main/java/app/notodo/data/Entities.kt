package app.notodo.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import app.notodo.parse.Alerts
import app.notodo.parse.Draft
import app.notodo.parse.Kind
import app.notodo.parse.Precision
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.Transient as NotExported

/** Testo originale così come è stato detto/digitato. Non elaborata = Inbox. */
@Serializable
@Entity(tableName = "capture")
data class Capture(
    @PrimaryKey val id: String,
    val text: String,
    val source: String,
    val zone: String,
    val createdAt: Long,
    val updatedAt: Long,
    val processed: Boolean = false,
)

@Serializable
@Entity(tableName = "item", indices = [Index("captureId"), Index("nextAlertAt")])
data class Item(
    @PrimaryKey val id: String,
    val captureId: String?,
    val spanStart: Int = 0,
    val spanEnd: Int = 0,
    val kind: Kind,
    val title: String,
    val body: String = "",
    val done: Boolean = false,
    /** Scadenza (task) o inizio (appuntamento); per [Precision.DAY] è la mezzanotte locale. */
    val at: Long? = null,
    val zone: String,
    val precision: Precision? = null,
    val dateText: String? = null,
    val alerts: List<String> = emptyList(),
    val mentions: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val confidence: Float = 1f,
    val reasons: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val doneAt: Long? = null,
    /** Avvisi fino a questo istante già gestiti (inviati, saltati o silenziati da uno snooze). */
    val firedUpTo: Long = 0,
    val snoozeUntil: Long? = null,
    val seenAt: Long? = null,
    @NotExported val nextAlertAt: Long? = null,
    @NotExported val search: String = "",
) {
    val whenAt: ZonedDateTime? get() = at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of(zone)) }

    /** Campi derivati, ricalcolati a ogni scrittura. */
    fun derived() = copy(
        nextAlertAt = if (done) null else Alerts.next(whenAt, alerts, firedUpTo, snoozeUntil),
        search = normalize(listOf(title, body, people.joinToString(" "), tags.joinToString(" "), dateText.orEmpty(), kind.label).joinToString(" ")),
    )
}

@Serializable
@Entity(tableName = "audit", indices = [Index("itemId")])
data class Audit(
    @PrimaryKey val id: String,
    val itemId: String,
    val at: Long,
    val action: String,
    val detail: String = "",
)

class Converters {
    @TypeConverter fun fromList(v: List<String>): String = v.joinToString("\u001F")
    @TypeConverter fun toList(v: String): List<String> = if (v.isEmpty()) emptyList() else v.split('\u001F')
}

/** Minuscole senza accenti: «Venerdì» e «venerdi» si trovano a vicenda. */
fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

fun Draft.toItem(id: String, captureId: String?, zone: ZoneId, now: Long) = Item(
    id = id, captureId = captureId, spanStart = start, spanEnd = end, kind = kind, title = title, body = body,
    at = at?.toInstant()?.toEpochMilli(), zone = (at?.zone ?: zone).id, precision = precision, dateText = dateText,
    alerts = alerts, mentions = mentions.map { it.toString() }, people = people, tags = tags, confidence = confidence,
    reasons = reasons + doubts.map { "Da confermare: ${it.text}" },
    createdAt = now, updatedAt = now, firedUpTo = now,
)
