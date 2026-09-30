package app.notodo.parse

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Avvisi relativi a `at`:
 * - `m60`      = 60 minuti prima (durata reale, corretta anche a cavallo del cambio d'ora)
 * - `d1`       = il giorno prima alla stessa ora locale
 * - `d0@09:00` = il giorno stesso alle 9 locali
 */
object Alerts {
    fun time(at: ZonedDateTime, spec: String): ZonedDateTime {
        val n = spec.substring(1).substringBefore('@').toLong()
        return when (spec[0]) {
            'm' -> at.minusMinutes(n)
            'd' -> at.minusDays(n).let { d -> spec.substringAfter('@', "").ifEmpty { null }?.let { d.with(LocalTime.parse(it)) } ?: d }
            else -> throw IllegalArgumentException(spec)
        }
    }

    /** Prossimo istante di avviso dopo [after]; lo snooze è un avviso in più. */
    fun next(at: ZonedDateTime?, specs: List<String>, after: Long, snoozeUntil: Long?): Long? =
        (specs.mapNotNull { s -> at?.let { time(it, s).toInstant().toEpochMilli() } } + listOfNotNull(snoozeUntil))
            .filter { it > after }
            .minOrNull()

    fun label(spec: String): String {
        val n = spec.substring(1).substringBefore('@').toInt()
        val t = spec.substringAfter('@', "")
        return when {
            spec[0] == 'm' && n == 0 -> "All'ora"
            spec[0] == 'm' && n % 60 == 0 -> if (n == 60) "1 ora prima" else "${n / 60} ore prima"
            spec[0] == 'm' -> "$n min prima"
            n == 0 -> "Alle $t del giorno"
            else -> (if (n == 1) "Il giorno prima" else "$n giorni prima") + if (t.isEmpty()) "" else " alle $t"
        }
    }
}
