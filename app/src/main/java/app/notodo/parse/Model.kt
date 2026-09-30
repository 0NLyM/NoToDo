package app.notodo.parse

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

enum class Kind(val label: String) {
    TASK("Task"), EVENT("Appuntamento"), NOTE("Nota"), IDEA("Idea"),
    REFERENCE("Riferimento"), VERIFY("Da verificare")
}

/** EXACT = ora esplicita, APPROX = «verso le 16» / «stasera», DAY = solo giorno. */
enum class Precision { EXACT, APPROX, DAY }

/** Campo da confermare; [alternative] è l'altra lettura plausibile, se esiste. */
data class Doubt(val text: String, val alternative: ZonedDateTime? = null)

/** Elemento proposto in anteprima: nulla viene salvato finché l'utente non conferma. */
data class Draft(
    val start: Int,
    val end: Int,
    val source: String,
    val kind: Kind,
    val title: String,
    val body: String = "",
    val at: ZonedDateTime? = null,
    val precision: Precision? = null,
    val dateText: String? = null,
    val alerts: List<String> = emptyList(),
    val mentions: List<LocalDate> = emptyList(),
    val people: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val confidence: Float = 0f,
    val reasons: List<String> = emptyList(),
    val doubts: List<Doubt> = emptyList(),
    val recognized: Boolean = true,
)

data class ParseContext(
    val now: ZonedDateTime,
    val defaultAlerts: List<String> = listOf("m60", "m0"),
    val dayTime: LocalTime = LocalTime.of(9, 0),
    val tagRules: Map<String, List<String>> = emptyMap(),
)

/** Punto di sostituzione per un futuro parser avanzato (es. modello locale). */
interface CaptureParser {
    fun parse(text: String, ctx: ParseContext): List<Draft>

    /** Analizza [start, end) come un solo elemento (Unisci). */
    fun parseSpan(text: String, start: Int, end: Int, ctx: ParseContext): Draft

    /** Divide [start, end) nel punto secondario più plausibile (Separa); un solo elemento se impossibile. */
    fun splitSpan(text: String, start: Int, end: Int): List<IntRange>
}
