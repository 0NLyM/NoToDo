package app.notodo.data

import app.notodo.parse.Kind
import app.notodo.parse.Precision
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class View(val label: String) {
    INBOX("Inbox"), TODAY("Oggi"), UPCOMING("Prossimi"), PENDING("In sospeso"),
    KNOWLEDGE("Conoscenza"), IDEAS("Idee"), ALL("Tutto")
}

enum class Due(val label: String) { ANY("Qualsiasi"), OVERDUE("Scadute"), TODAY("Oggi"), WEEK("7 giorni"), NONE("Senza data") }
enum class Status(val label: String) { ANY("Tutti"), OPEN("Aperti"), DONE("Completati") }
enum class Created(val label: String, val days: Long?) { ANY("Sempre", null), TODAY("Oggi", 0), WEEK("7 giorni", 7), MONTH("30 giorni", 30) }

data class Filter(
    val kind: Kind? = null,
    val tag: String? = null,
    val person: String? = null,
    val due: Due = Due.ANY,
    val status: Status = Status.ANY,
    val created: Created = Created.ANY,
) {
    val active get() = this != Filter()
}

/** Giorno dell'elemento: per i «solo giorno» vale il fuso dell'elemento, altrimenti quello di chi guarda. */
fun Item.day(zone: ZoneId): LocalDate? = whenAt?.let { if (precision == Precision.DAY) it.toLocalDate() else it.withZoneSameInstant(zone).toLocalDate() }

fun Item.overdue(now: ZonedDateTime): Boolean {
    if (done || kind == Kind.EVENT || at == null) return false
    return if (precision == Precision.DAY) day(now.zone)!! < now.toLocalDate() else at < now.toInstant().toEpochMilli()
}

fun View.matches(i: Item, now: ZonedDateTime): Boolean {
    val today = now.toLocalDate()
    val d = i.day(now.zone)
    return when (this) {
        View.INBOX -> false
        View.TODAY -> !i.done && d != null && (d == today || d < today && i.kind != Kind.EVENT)
        View.UPCOMING -> !i.done && d != null && d > today
        View.PENDING -> !i.done && d == null && i.kind in setOf(Kind.TASK, Kind.EVENT, Kind.VERIFY)
        View.KNOWLEDGE -> i.kind == Kind.NOTE || i.kind == Kind.REFERENCE
        View.IDEAS -> i.kind == Kind.IDEA
        View.ALL -> true
    }
}

/** Vista + ricerca + filtri. Con una ricerca attiva si cerca ovunque. */
fun select(items: List<Item>, view: View, query: String, f: Filter, now: ZonedDateTime): List<Item> {
    val terms = normalize(query).split(' ').filter(String::isNotBlank)
    val v = if (terms.isEmpty()) view else View.ALL
    val today = now.toLocalDate()
    return items.filter { i ->
        val d = i.day(now.zone)
        v.matches(i, now) &&
            terms.all { i.search.contains(it) } &&
            (f.kind == null || i.kind == f.kind) &&
            (f.tag == null || f.tag in i.tags) &&
            (f.person == null || f.person in i.people) &&
            when (f.due) {
                Due.ANY -> true
                Due.OVERDUE -> i.overdue(now)
                Due.TODAY -> d == today
                Due.WEEK -> d != null && d >= today && d <= today.plusDays(7)
                Due.NONE -> d == null
            } &&
            when (f.status) {
                Status.ANY -> true
                Status.OPEN -> !i.done
                Status.DONE -> i.done
            } &&
            (f.created.days == null || i.createdAt >= today.minusDays(f.created.days).atStartOfDay(now.zone).toInstant().toEpochMilli())
    }.sortedWith(
        when (v) {
            View.TODAY, View.UPCOMING -> compareBy<Item>({ it.at }, { it.createdAt })
            View.PENDING -> compareByDescending<Item> { it.createdAt }
            View.KNOWLEDGE, View.IDEAS -> compareByDescending<Item> { it.updatedAt }
            else -> compareBy<Item>({ it.done }, { it.at == null }, { it.at }).thenByDescending { it.createdAt }
        }
    )
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)
private val DAY_Y = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ITALIAN)

fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "Oggi"
    today.plusDays(1) -> "Domani"
    today.minusDays(1) -> "Ieri"
    else -> (if (d.year == today.year) DAY else DAY_Y).format(d)
}

fun whenLabel(at: ZonedDateTime, precision: Precision?, now: ZonedDateTime): String {
    val local = if (precision == Precision.DAY) at else at.withZoneSameInstant(now.zone)
    val day = dayLabel(local.toLocalDate(), now.toLocalDate())
    return when (precision) {
        Precision.DAY -> day
        Precision.APPROX -> "$day ≈ ${TIME.format(local)}"
        else -> "$day ${TIME.format(local)}"
    }
}

fun Item.whenLabel(now: ZonedDateTime): String? = whenAt?.let { whenLabel(it, precision, now) }
