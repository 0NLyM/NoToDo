package app.notodo.parse

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/**
 * Parser deterministico per l'italiano, interamente offline.
 * Ogni decisione produce un motivo leggibile; ciò che non è certo diventa un [Doubt].
 */
object ItalianParser : CaptureParser {

    override fun parse(text: String, ctx: ParseContext): List<Draft> =
        Analysis(text, ctx).run { segments().map { draft(it) } }

    override fun parseSpan(text: String, start: Int, end: Int, ctx: ParseContext): Draft =
        Analysis(text, ctx).draft(start until end)

    override fun splitSpan(text: String, start: Int, end: Int): List<IntRange> {
        val lower = norm(text)
        val cuts = SECONDARY.findAll(lower.substring(start, end)).map { it.range.first + start..it.range.last + start }
            .filter { it.first > start && it.last + 1 < end }.toList()
        val cut = cuts.firstOrNull { c -> WORD.find(lower, c.last + 1)?.value?.let(::isVerb) == true } ?: cuts.firstOrNull()
            ?: return listOf(start until end)
        return listOf(start until cut.first, cut.last + 1 until end).map { trim(text, it) }.filter { !it.isEmpty() }
    }
}

private val IT = Locale.ITALIAN
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", IT)
private fun fmt(d: LocalDate) = DAY_FMT.format(d)

/** Minuscole a lunghezza invariata: gli indici restano validi sul testo originale. */
internal fun norm(s: String) = buildString(s.length) {
    for (c in s) append(if (c == '’' || c == '`') '\'' else c.lowercaseChar())
}

private fun trim(text: String, r: IntRange): IntRange {
    var s = r.first
    var e = r.last + 1
    while (s < e && (text[s].isWhitespace() || text[s] in ",;")) s++
    while (e > s && (text[e - 1].isWhitespace() || text[e - 1] in ",;")) e--
    return s until e
}

/**
 * Classi \w e \b Unicode (lettere accentate). Su Android il motore ICU lo fa già e non accetta il flag
 * `(?U)`, che farebbe fallire ogni analisi; sulla JVM dei test serve.
 */
private val U = if (System.getProperty("java.vm.name") == "Dalvik") "" else "(?U)"
private val NUMW = mapOf(
    "un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5, "sei" to 6,
    "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11, "dodici" to 12, "quindici" to 15,
    "venti" to 20, "trenta" to 30, "quaranta" to 40, "quarantacinque" to 45, "cinquanta" to 50,
)
private val NUMW_RE = NUMW.keys.sortedByDescending { it.length }.joinToString("|")
private val HOURW = NUMW.filterValues { it <= 12 } - "un" - "uno"
private val HOURW_RE = HOURW.keys.sortedByDescending { it.length }.joinToString("|")
private const val WD_RE = "luned[iìí]|marted[iìí]|mercoled[iìí]|gioved[iìí]|venerd[iìí]|sabato|domenica"
private const val MONTH_RE =
    "gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre|gen|feb|mar|apr|mag|giu|lug|ago|set|ott|nov|dic"
private val MONTHS = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
private val WEEKDAYS = listOf("luned", "marted", "mercoled", "gioved", "venerd", "sabato", "domenica")
private val WEEKDAY_NAMES = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
private fun dayName(d: DayOfWeek) = WEEKDAY_NAMES[d.value - 1]

private val REL_DAY = Regex("$U\\b(?:(entro|per)\\s+)?(dopodomani|domani|oggi|ieri|l'altro\\s?ieri|altroieri|stamattina|stamani|stamane|stasera|domattina)\\b")
private val WEEKDAY = Regex("$U\\b(?:(ogni|entro|per)\\s+)?(?:(questo|questa|quest')\\s*)?(?:(prossim[oa]|scors[oa])\\s+)?($WD_RE)(?:\\s+(prossim[oa]|scors[oa]))?\\b")
private val DAY_MONTH = Regex("$U\\b(?:(entro|per)\\s+)?(?:(il|l'|dal|del|dell'|al|all'|nel)\\s*)?(\\d{1,2}|primo)\\s*°?\\s*($MONTH_RE)(?!\\w)\\.?(?:\\s+(\\d{4})(?!\\w))?")
private val NUMERIC = Regex("$U(?<![\\w.:/-])(?:(entro|per)\\s+)?(?:(il|l'|del|dal|al)\\s*)?(\\d{1,2})([/-])(\\d{1,2})(?:\\4(\\d{4}|\\d{2}))?(?![\\w/:-])")
private val IN_OFFSET = Regex("$U\\b(?:tra|fra)\\s+(un\\s+paio\\s+di|un\\s+quarto\\s+d'|un'|mezz'|mezza\\s+|\\d{1,3}|$NUMW_RE)\\s*(minuti|minuto|min|ore|ora|oretta|h|giorni|giorno|gg|settimane|settimana|mesi|mese)\\b")
private val TIME = Regex(
    "$U\\b(alle\\s+ore|alle|all'|ore|h|verso\\s+le|verso\\s+l'|intorno\\s+alle|intorno\\s+all'|circa\\s+alle|circa\\s+all'|sulle|sull'|entro\\s+le|entro\\s+l'|per\\s+le|per\\s+l'|dalle|dall')" +
        "\\s*(\\d{1,2}|$HOURW_RE)(?:[:.](\\d{2}))?(?:\\s+(e\\s+mezz[ao]|e\\s+un\\s+quarto|e\\s+tre\\s+quarti|meno\\s+un\\s+quarto))?" +
        "(?:\\s+(di\\s+mattina|del\\s+mattino|della\\s+mattina|di\\s+pomeriggio|del\\s+pomeriggio|di\\s+sera|della\\s+sera|di\\s+notte|della\\s+notte))?(?![\\w:])"
)
private val BARE_TIME = Regex("(?<![\\w:./])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\w:])")
private val NOON = Regex("$U\\b(?:a\\s+)?mezzogiorno\\b")
private val PART = Regex("$U[ \\t,]*(?:di\\s+|in\\s+|nel\\s+|nella\\s+|della\\s+)?(mattina|mattinata|mattino|pomeriggio|sera|serata|pranzo)\\b")
private val PARTS = mapOf("matt" to LocalTime.of(9, 0), "pomeriggio" to LocalTime.of(15, 0), "ser" to LocalTime.of(20, 0), "pranzo" to LocalTime.of(13, 0))
private val GAP = Regex("[ \\t,]*(?:(?:di|del|della|il|la)[ \\t]+)?")
private val DIRECTIVE = Regex(
    "$U(?:\\b(?:e\\s+)?(?:ricordamelo|ricordamela|ricordameli|ricordatemelo|ricordami|ricordalo|avvisami|avvisamelo|notificamelo|notificami|mandami\\s+un\\s+promemoria)\\s+)?" +
        "\\b(il\\s+giorno\\s+prima|la\\s+sera\\s+prima|la\\s+mattina\\s+prima|la\\s+mattina\\s+stessa|(\\d{1,3}|$NUMW_RE|un'|mezz')\\s*(minuti|minuto|min|ore|ora|giorni|giorno|settimane|settimana)\\s+prima)\\b"
)
private val ASK = Regex("$U\\b(?:ricordamelo|ricordamela|ricordameli|ricordatemelo|avvisami|avvisamelo|notificamelo|notificami|ricordami)\\b(?!\\s+(?:di|che)\\b)")
private val PAST = Regex(
    "$U\\b(?:ho|hai|ha|abbiamo|avete|hanno)\\s+(?:gi[àa]\\s+|appena\\s+|poi\\s+)?(?!quest[oaie]\\b|tutt[oaie]\\b)" +
        "(?:\\w+(?:ato|ata|ati|ate|uto|uta|uti|ute|ito|ita|iti|ite|tto|tta|tti|tte|sto|sta|sti|ste)|preso|messo|acceso|chiuso|deciso|perso|speso|sceso|spento|vinto|aggiunto)\\b" +
        "|\\b(?:sono|sei|è|e'|siamo|siete)\\s+(?:gi[àa]\\s+|appena\\s+)?(?:stat|andat|venut|uscit|partit|arrivat|tornat|rimast|successo|finit|scadut)\\w*\\b" +
        "|\\b(?:giorni|settimane|mesi|anni|ore|minuti|tempo)\\s+fa\\b"
)
private val M_IDEA = Regex("$U^idea\\b[ \\t]*[:\\-–—,.]?[ \\t]*")
private val M_NOTE = Regex("$U^(?:nota|appunto|annotazione|memo)[ \\t]*[:\\-–—][ \\t]*")
private val M_REF = Regex("$U^(?:(?:ricordati|ricorda|ricordiamoci|tieni\\s+(?:a\\s+mente|presente)|nota|annota|segnati|segna)\\s+che\\b|(?:info|informazione|riferimento|dato)[ \\t]*[:\\-–—])[ \\t]*")
private val M_TASK = Regex("$U^(?:(?:ricordami|ricordatemi|ricordati|ricorda|ricordarsi)\\s+di|devo|dobbiamo|devi|bisogna|occorre|todo|to\\s+do|task|da\\s+fare)\\b[ \\t]*[:\\-–—]?[ \\t]*")
private val M_VERIFY = Regex("$U\\b(?:da\\s+verificare|da\\s+confermare|da\\s+controllare|non\\s+sono\\s+sicur[oa]|non\\s+so\\s+se|forse|credo\\s+che|mi\\s+sembra\\s+che|mi\\s+pare\\s+che|penso\\s+che|dovrebbe\\s+essere)\\b|\\?[ \\t]*$")
private val EVENT_VERBS = setOf("incontra", "incontrare", "vedi", "vedere")
private val EVENT_WORDS = Regex("$U\\b(?:incontr[aoi]|incontrare|riunione|meeting|appuntamento|call|videochiamata|visita|dentista|medico|colloquio|cena|pranzo|aperitivo|compleanno|evento|webinar|conferenza|esame|volo|treno|concerto|partita|intervento)\\b")
private val TECH = Regex("$U\\b(?:[A-Z]{2,}[A-Z0-9-]*|\\d+(?:[.,:/-]\\d+)*|\\p{L}+\\d+\\w*|\\d+\\p{L}+\\w*)\\b|\\S+@\\S+\\.\\w+|https?://\\S+|www\\.\\S+")
private val HASHTAG = Regex("$U#(\\w[\\w-]*)")
private val MENTION = Regex("$U(?<![\\w.])@(\\w+)")
private val AT_SIGN = Regex("$U(?<![\\w.])@(?=\\w)")
private val PERSON = Regex(
    "$U(?i:\\b(?:chiama(?:re)?|richiama(?:re)?|telefona(?:re)?|scrivi|scrivere|manda(?:re)?|invia(?:re)?|incontra(?:re)?|vedi|vedere|senti|sentire|avvisa(?:re)?|chiedi|chiedere|contatta(?:re)?|sollecita(?:re)?|ricorda(?:re)?|dire|con|a|ad|da|per|parlato\\s+con|detto\\s+a|dott|dottor|dottoressa|dr|ing|sig|signor|signora|avv|prof|geom)\\.?\\s+(?:a\\s+|ad\\s+)?)" +
        "(\\p{Lu}\\p{Ll}+(?:\\s+\\p{Lu}\\p{Ll}+)?)"
)
private val WORD = Regex("$U\\p{L}+")
private val SECONDARY = Regex("$U\\s*(?:,|\\.(?=\\s)|\\s-\\s|\\b(?:e\\s+poi|ed|e|poi|quindi|inoltre|anche)\\b)\\s*")
private val CONJ_END = Regex("$U(?:^|\\s)(?:e|ed|poi|quindi|inoltre|anche|infine|dopo)$")
private val EDGE_START = Regex("$U^(?:[\\s,;:.\\-–—]+|(?:e|ed|poi|quindi|anche)\\s+)+")
private val EDGE_END = Regex("$U(?:[\\s,;:.\\-–—]+|\\s+(?:e|ed|di|del|della|a|al|alla|per|entro|il|la|lo|con|da|ore))+$")
private val ABBREV = setOf("sig", "sigg", "dott", "dr", "ing", "avv", "prof", "geom", "rag", "arch", "es", "ecc", "pag", "n", "nr", "tel", "cell", "ca", "v", "fig", "art", "vol")
private val NOT_NAMES = (WEEKDAY_NAMES + WEEKDAY_NAMES.map { it.replace('ì', 'i') } + MONTH_RE.split('|') +
    listOf("oggi", "domani", "dopodomani", "ieri", "stasera")).toSet()

private val VERBS = setOf(
    "chiama", "richiama", "telefona", "scrivi", "rispondi", "manda", "invia", "inoltra", "controlla", "ricontrolla", "verifica",
    "testa", "prova", "riprova", "aggiorna", "installa", "disinstalla", "configura", "riavvia", "spegni", "accendi", "sostituisci",
    "cambia", "sistema", "ripara", "pulisci", "compra", "ordina", "prenota", "paga", "ritira", "porta", "prendi", "consegna",
    "prepara", "stampa", "scarica", "carica", "salva", "copia", "sposta", "elimina", "cancella", "rinnova", "disdici", "fai",
    "vai", "passa", "chiedi", "avvisa", "segnala", "apri", "chiudi", "leggi", "studia", "finisci", "completa", "inizia",
    "organizza", "pianifica", "fissa", "annulla", "documenta", "esegui", "lancia", "monitora", "migra", "crea", "aggiungi",
    "rimuovi", "blocca", "sblocca", "resetta", "formatta", "collega", "scollega", "cerca", "trova", "contatta", "sollecita",
    "archivia", "butta", "restituisci", "incontra", "vedi", "senti", "rivedi", "correggi", "attiva", "disattiva", "abilita",
    "disabilita", "registra", "compila", "firma", "spedisci", "risolvi", "ripristina", "esporta", "importa", "misura", "ricorda",
    "ricordami", "ricordati", "devo", "dobbiamo", "bisogna", "manda", "metti", "togli", "sentire", "fissa",
)
private val NOT_INFINITIVE = setOf("essere", "avere", "dovere", "potere", "volere", "sapere", "piacere", "mare", "cantiere", "ingegnere", "carattere")
private val CLITICS = listOf("glielo", "gliela", "glieli", "gliene", "melo", "mela", "telo", "gli", "lo", "la", "li", "le", "mi", "ti", "ci", "vi", "ne")
private val FUNCTION_WORDS = setOf(
    "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "di", "del", "della", "dei", "delle", "che", "e", "è", "per", "con",
    "a", "al", "alla", "da", "dal", "in", "nel", "nella", "su", "sul", "non", "mi", "ti", "si", "ci", "ma", "o", "se", "come",
    "anche", "più", "sono", "ha", "ho", "hanno",
)

private fun isVerb(w: String): Boolean =
    w in VERBS ||
        (w.length > 5 && w !in NOT_INFINITIVE && (w.endsWith("are") || w.endsWith("ere") || w.endsWith("ire") || w.endsWith("arsi") || w.endsWith("ersi") || w.endsWith("irsi"))) ||
        CLITICS.any { w.length > it.length + 2 && w.endsWith(it) && w.dropLast(it.length) in VERBS }

private class Res(val date: LocalDate, val note: String, val doubt: String? = null, val alt: LocalDate? = null)

private class When(
    var start: Int,
    var end: Int,
    val resolve: ((scheduling: Boolean) -> Res)?,
    val dow: DayOfWeek? = null,
    val instant: ZonedDateTime? = null,
    val past: Boolean = false,
    val genitive: Boolean = false,
    var deadline: Boolean = false,
    val recurring: Boolean = false,
    var approx: Boolean = false,
    val plain: Boolean = false,
) {
    var time: LocalTime? = null
    var alt: LocalTime? = null
    var part: String? = null
    var timeSaid: String? = null
    val notes = mutableListOf<String>()
    val keep = mutableListOf<IntRange>()
    val extra = mutableListOf<IntRange>()
}

private class TimeHit(val start: Int, var end: Int, val time: LocalTime, val approx: Boolean, val alt: LocalTime?, val deadline: Boolean, val from: Boolean, var said: String) {
    var keep = false
}

private class Analysis(val text: String, val ctx: ParseContext) {
    val lower = norm(text)
    val zone = ctx.now.zone
    val today: LocalDate = ctx.now.toLocalDate()
    val directives = DIRECTIVE.findAll(lower).toList()
    val masked = buildString(lower.length) {
        append(lower)
        directives.forEach { d -> for (i in d.range) setCharAt(i, ' ') }
    }
    val whens: List<When> = findWhens()

    // ---------- segmentazione ----------

    fun segments(): List<IntRange> {
        val clauses = strongSplit().flatMap(::clauseSplit)
        val out = mutableListOf<IntRange>()
        var carry: Int? = null
        for (c in clauses) {
            val r = (carry ?: c.first)..c.last
            carry = null
            when {
                !residualEmpty(c) -> out += r
                out.isNotEmpty() -> out[out.lastIndex] = out.last().first..r.last
                else -> carry = r.first
            }
        }
        if (carry != null) out += carry until text.length
        return out.map { trim(text, it) }.filter { !it.isEmpty() }
    }

    private fun strongSplit(): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        fun add(e: Int) {
            val r = trim(text, start until e)
            if (!r.isEmpty() && text.substring(r.first, r.last + 1).any { it.isLetterOrDigit() }) out += r
        }
        for (i in text.indices) {
            val c = text[i]
            if (c == ';' || c == '\n') {
                add(i); start = i + 1
            } else if (c in ".!?" && (i + 1 == text.length || text[i + 1].isWhitespace()) && !abbreviation(i)) {
                add(i + 1); start = i + 1
            }
        }
        add(text.length)
        return out
    }

    private fun abbreviation(dot: Int): Boolean {
        if (text[dot] != '.') return false
        val w = WORD.findAll(lower.substring(0, dot)).lastOrNull() ?: return false
        return w.range.last == dot - 1 && w.value in ABBREV
    }

    /** Dettatura senza punteggiatura: divide solo se ogni frase ha un verbo d'azione e una propria data. */
    private fun clauseSplit(seg: IntRange): List<IntRange> {
        val ws = whens.filter { it.start >= seg.first && it.end <= seg.last + 1 && !it.past && !it.genitive }
        if (ws.size < 2) return listOf(seg)
        val heads = WORD.findAll(lower.substring(0, seg.last + 1), seg.first)
            .filter { isVerb(it.value) && atHead(it.range.first, seg.first) }.toList()
        if (heads.size < 2) return listOf(seg)
        val bounds = heads.zipWithNext { a, b ->
            val between = lower.substring(a.range.last + 1, b.range.first)
            val conj = Regex("$U(?:,|\\s(?:e\\s+poi|ed|e|poi|quindi|inoltre|infine))\\s*$").find(between)
            val w = ws.lastOrNull { it.start > a.range.last && it.end <= b.range.first && lower.substring(it.end, b.range.first).isBlank() }
            when {
                conj != null -> (a.range.last + 1 + conj.range.first) to b.range.first
                w != null -> w.start to w.start
                else -> b.range.first to b.range.first
            }
        }
        val pieces = mutableListOf<IntRange>()
        var s = seg.first
        for ((cutEnd, nextStart) in bounds) {
            pieces += s until cutEnd; s = nextStart
        }
        pieces += s..seg.last
        return if (pieces.all { p -> ws.count { it.start >= p.first && it.end <= p.last + 1 } == 1 }) pieces else listOf(seg)
    }

    private fun atHead(p: Int, segStart: Int): Boolean {
        val before = lower.substring(segStart, p).trimEnd()
        return before.isEmpty() || before.endsWith(",") || CONJ_END.containsMatchIn(before) ||
            before.endsWith("di") && Regex("$U\\bricorda(?:mi|ti)?\\s+di$").containsMatchIn(before) ||
            whens.any { it.end in segStart..p && lower.substring(it.end, p).isBlank() }
    }

    private fun residualEmpty(r: IntRange): Boolean {
        val cut = whens.filter { it.start >= r.first && it.end <= r.last + 1 }.map { it.start until it.end } +
            directives.filter { it.range.first >= r.first && it.range.last <= r.last }.map { it.range } +
            ASK.findAll(lower.substring(0, r.last + 1), r.first).map { it.range }
        val rest = residual(r, cut).lowercase()
        return rest.isBlank() || WORD.findAll(rest).all { it.value in setOf("e", "ed", "anche", "poi", "mi", "per", "favore", "grazie") }
    }

    private fun residual(r: IntRange, cut: List<IntRange>): String {
        val sb = StringBuilder()
        for (i in r) sb.append(if (cut.any { i in it }) ' ' else text[i])
        return sb.toString()
    }

    // ---------- elemento ----------

    fun draft(r: IntRange): Draft {
        val s = r.first
        val e = r.last + 1
        val src = text.substring(s, e)
        val segLower = lower.substring(s, e)
        val ws = combine(whens.filter { it.start >= s && it.end <= e })
        val dirs = directives.filter { it.range.first >= s && it.range.last < e }
        val tagCut = HASHTAG.findAll(text.substring(0, e), s).map { it.range }.toList()
        val whenCut = ws.flatMap { it.extra + listOf(it.start until it.end) }
        // «ricordami tra 20 minuti di …»: «ricordami» fa parte del marcatore task, non è una richiesta di avviso
        val asks = ASK.findAll(lower.substring(0, e), s).toList()
            .takeUnless { M_TASK.containsMatchIn(norm(clean(residual(r, dirs.map { d -> d.range } + tagCut + whenCut)))) } ?: emptyList()
        val baseCut = dirs.map { it.range } + asks.map { it.range } + tagCut
        // testo senza date: base per classificare
        val bareOrig = clean(residual(r, baseCut + whenCut))
        val bare = norm(bareOrig)
        val first = WORD.find(bare.replace(M_TASK, "").replace(EDGE_START, ""))?.value
        val imperative = M_TASK.containsMatchIn(bare) || first != null && isVerb(first)
        val past = if (imperative) null else PAST.find(segLower)
        val reasons = mutableListOf<String>()
        val doubts = mutableListOf<Doubt>()

        val sched = if (past != null) null else ws.firstOrNull { !it.past && !it.genitive }
        var at: ZonedDateTime? = null
        var precision: Precision? = null
        if (sched != null) {
            val res = sched.resolve?.invoke(true)
            res?.let { reasons += it.note; it.doubt?.let { d -> doubts += Doubt(d, it.alt?.let { a -> zdt(a, sched.time) }) } }
            reasons += sched.notes
            val time = sched.time
            if (time != null && sched.timeSaid != null) reasons += "«${sched.timeSaid}» = ${hm(time)}"
            var rolled = false
            when {
                sched.instant != null -> at = sched.instant
                res != null -> at = zdt(res.date, time)
                time != null -> {
                    val todayAt = zdt(today, time)
                    rolled = !todayAt.isAfter(ctx.now)
                    at = if (rolled) zdt(today.plusDays(1), time) else todayAt
                    if (rolled) doubts += Doubt("Le ${hm(time)} di oggi sono già passate: impostato domani", todayAt)
                }
            }
            precision = when {
                sched.instant == null && time == null -> Precision.DAY
                sched.approx -> Precision.APPROX
                else -> Precision.EXACT
            }
            val a = at!!
            if (time != null && res != null) {
                if (a.toLocalTime() != time) doubts += Doubt("Le ${hm(time)} non esistono quel giorno (cambio d'ora): ${hm(a.toLocalTime())}")
                else if (zone.rules.getValidOffsets(LocalDateTime.of(res.date, time)).size > 1) doubts += Doubt("Le ${hm(time)} si ripetono (cambio d'ora): usata la prima")
            }
            sched.alt?.let { alt ->
                val other = zdt(a.toLocalDate(), alt).let { if (res == null && !it.isAfter(ctx.now)) it.plusDays(1) else it }
                doubts += Doubt("Ora ambigua: ${hm(a.toLocalTime())} o ${hm(alt)}?", other)
            }
            if (sched.part != null && sched.approx && time != null) doubts += Doubt("Orario dedotto da «${sched.part}»: ≈ ${hm(time)}")
            if (sched.recurring) doubts += Doubt("Ricorrenza non supportata: impostata solo la prossima data")
            val gone = if (precision == Precision.DAY) a.toLocalDate() < today else a.isBefore(ctx.now)
            if (gone && !rolled) doubts += Doubt("Data già passata")
            if (sched.deadline) reasons += "«entro/per» = scadenza"
        }
        val others = ws.filter { it !== sched }
        val mentions = others.filter { it.resolve != null }.map { w ->
            w.resolve!!.invoke(past == null && !w.past && !w.genitive).also {
                reasons += "Data menzionata «${text.substring(w.start, w.end).trim()}» = ${fmt(it.date)} (non è una scadenza)"
            }.date
        }
        if (sched != null && others.any { !it.past && !it.genitive })
            doubts += Doubt("Più date/orari: usato «${text.substring(sched.start, sched.end).trim()}». Usa Separa se sono attività diverse")

        val rest = clean(residual(r, baseCut + (sched?.let { w -> (w.extra + listOf(w.start until w.end)).flatMap { minus(it, w.keep) } } ?: emptyList())))
        val restLower = norm(rest)
        val hasDate = at != null
        var marker: Regex? = null
        var recognized = true
        fun why(k: Kind, c: Float, reason: String): Pair<Kind, Float> { reasons.add(0, reason); return k to c }
        val (kind, conf) = when {
            M_IDEA.containsMatchIn(bare) -> { marker = M_IDEA; why(Kind.IDEA, .9f, "Idea: prefisso «idea»") }
            M_NOTE.containsMatchIn(bare) -> { marker = M_NOTE; why(Kind.NOTE, .9f, "Nota: prefisso esplicito") }
            M_REF.containsMatchIn(bare) -> { marker = M_REF; why(Kind.REFERENCE, .9f, "Riferimento: «${M_REF.find(bare)!!.value.trim()}»") }
            M_TASK.containsMatchIn(bare) -> { marker = M_TASK; why(Kind.TASK, .9f, "Task: «${M_TASK.find(bare)!!.value.trim()}»") }
            imperative && hasDate && first in EVENT_VERBS -> why(Kind.EVENT, .85f, "Appuntamento: «$first» con data")
            imperative -> why(Kind.TASK, if (hasDate) .9f else .75f, "Task: verbo d'azione «$first»")
            hasDate && EVENT_WORDS.containsMatchIn(bare) -> why(Kind.EVENT, .85f, "Appuntamento: «${EVENT_WORDS.find(bare)!!.value}» con data")
            M_VERIFY.containsMatchIn(bare) -> why(Kind.VERIFY, .7f, "Da verificare: «${M_VERIFY.find(bare)!!.value.trim()}»")
            past != null -> why(Kind.NOTE, .7f, "Nota: frase al passato («${past.value}»)")
            hasDate -> why(Kind.TASK, .55f, "Task: data senza verbo riconosciuto")
            TECH.containsMatchIn(bareOrig) -> why(Kind.REFERENCE, .65f, "Riferimento: dato tecnico «${TECH.find(bareOrig)!!.value}»")
            WORD.findAll(bare).count() >= 2 && WORD.findAll(bare).any { it.value in FUNCTION_WORDS } -> why(Kind.NOTE, .45f, "Nota: nessun segnale specifico")
            else -> { recognized = false; why(Kind.NOTE, .1f, "Non riconosciuto") }
        }

        var title = marker?.find(restLower)?.let { rest.substring(it.range.last + 1) } ?: rest
        repeat(3) { title = title.replace(EDGE_START, "").replace(EDGE_END, "") }
        title = title.ifBlank { src.trim() }.replaceFirstChar { it.titlecase(IT) }

        val explicit = asks.isNotEmpty() || dirs.isNotEmpty()
        val specs = dirs.map { spec(it, precision) }
        dirs.forEach { reasons += "«${text.substring(it.range.first, it.range.last + 1).trim()}» → ${Alerts.label(spec(it, precision))}" }
        val alerts = when {
            at == null -> emptyList<String>().also { if (explicit) doubts += Doubt("Avviso richiesto ma nessuna data") }
            kind != Kind.TASK && kind != Kind.EVENT && !explicit -> emptyList()
            specs.isNotEmpty() -> (specs + if (precision == Precision.DAY) "d0@${hm(ctx.dayTime)}" else "m0").distinct()
            precision == Precision.DAY -> listOf("d0@${hm(ctx.dayTime)}")
            else -> ctx.defaultAlerts
        }
        if (precision == Precision.DAY && alerts.isNotEmpty() && specs.isEmpty())
            doubts += Doubt("Ora non indicata: avviso alle ${hm(ctx.dayTime)} del giorno")

        val people = (MENTION.findAll(src).map { it.groupValues[1] } + PERSON.findAll(src).map { it.groupValues[1] })
            .map { n -> n.split(' ').filter { norm(it) !in NOT_NAMES }.joinToString(" ") }
            .filter { it.isNotBlank() }.distinct().toList()
        people.forEach { reasons += "Persona: «$it»" }
        val tags = (HASHTAG.findAll(src).map { norm(it.groupValues[1]) } +
            ctx.tagRules.filter { (_, kws) -> kws.any { kw -> Regex("$U\\b${Regex.escape(norm(kw.trim()))}\\b").containsMatchIn(segLower) } }.keys)
            .distinct().toList()
        tags.forEach { reasons += "Tag #$it" }

        return Draft(
            start = s, end = e, source = src, kind = kind, title = title,
            at = at, precision = precision, dateText = sched?.let { text.substring(it.start, it.end).trim() },
            alerts = alerts, mentions = mentions, people = people, tags = tags,
            confidence = (conf - .1f * doubts.size).coerceIn(.05f, .99f),
            reasons = reasons, doubts = doubts, recognized = recognized,
        )
    }

    /** Nella stessa frase un'ora e un giorno non adiacenti («dalle 15 riunione domani») formano un solo riferimento. */
    private fun combine(ws: List<When>): List<When> {
        val lone = ws.firstOrNull { it.resolve == null && it.instant == null && it.time != null } ?: return ws
        val day = ws.firstOrNull { it.resolve != null && it.time == null && it.instant == null && !it.past && !it.genitive } ?: return ws
        val c = When(day.start, day.end, day.resolve, dow = day.dow, deadline = day.deadline || lone.deadline, recurring = day.recurring, approx = lone.approx)
        c.time = lone.time; c.alt = lone.alt; c.timeSaid = lone.timeSaid
        c.notes += day.notes + lone.notes
        c.keep += day.keep + lone.keep
        c.extra += lone.start until lone.end
        return (ws - lone - day + c).sortedBy { it.start }
    }

    private fun clean(s: String) = s.replace(AT_SIGN, "").replace(Regex("\\s+"), " ").trim()

    private fun minus(r: IntRange, keep: List<IntRange>): List<IntRange> =
        if (keep.isEmpty()) listOf(r) else r.filter { i -> keep.none { i in it } }.map { it..it }

    private fun spec(d: MatchResult, precision: Precision?): String {
        val g = d.groupValues
        val day = hm(ctx.dayTime)
        return when {
            g[1].startsWith("il giorno") -> if (precision == Precision.DAY) "d1@$day" else "d1"
            g[1].startsWith("la sera") -> "d1@20:00"
            g[1].startsWith("la mattina prima") -> "d1@$day"
            g[1].startsWith("la mattina") -> "d0@$day"
            else -> {
                val n = when (g[2]) { "un'" -> 1.0; "mezz'" -> .5; else -> (g[2].toIntOrNull() ?: NUMW[g[2]] ?: 1).toDouble() }
                val unit = g[3]
                when {
                    unit.startsWith("min") -> "m${n.toInt()}"
                    unit.startsWith("or") -> if (precision == Precision.DAY) "d0@$day" else "m${(n * 60).toInt()}"
                    unit.startsWith("giorn") -> if (precision == Precision.DAY) "d${n.toInt()}@$day" else "d${n.toInt()}"
                    else -> if (precision == Precision.DAY) "d${n.toInt() * 7}@$day" else "d${n.toInt() * 7}"
                }
            }
        }
    }

    private fun zdt(d: LocalDate, t: LocalTime?) = if (t == null) d.atStartOfDay(zone) else ZonedDateTime.of(d, t, zone)
    private fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    // ---------- date e ore ----------

    private fun findWhens(): List<When> {
        val dates = mutableListOf<When>()
        REL_DAY.findAll(masked).forEach { dates += relDay(it) }
        WEEKDAY.findAll(masked).forEach { dates += weekday(it) }
        DAY_MONTH.findAll(masked).forEach { m ->
            val g = m.groupValues
            dated(m, if (g[3] == "primo") 1 else g[3].toInt(), MONTHS.indexOfFirst { g[4].startsWith(it) } + 1, g[5].toIntOrNull(), g[2], g[1])?.let(dates::add)
        }
        NUMERIC.findAll(masked).forEach { m ->
            val g = m.groupValues
            val y = g[6].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            dated(m, g[3].toInt(), g[5].toInt(), y, g[2], g[1])?.let(dates::add)
        }
        IN_OFFSET.findAll(masked).forEach { dates += inOffset(it) }
        val ds = noOverlap(dates.sortedBy { it.start }).toMutableList()

        // «venerdì 9 ottobre», «domani 30 settembre»: un solo riferimento, con verifica di coerenza
        var i = 0
        while (i < ds.size - 1) {
            val a = ds[i]
            val b = ds[i + 1]
            val (named, exact) = if (a.dow != null) a to b else b to a
            if (a.instant == null && b.instant == null && GAP.matches(masked.substring(a.end, b.start)) && (a.dow != null) != (b.dow != null) &&
                (named === a && !exact.genitive || named.plain)
            ) {
                val merged = When(a.start, b.end, { sch ->
                    val r = exact.resolve!!(sch)
                    val ok = named.dow == r.date.dayOfWeek
                    Res(r.date, r.note, if (ok) r.doubt else "«${dayName(named.dow!!)}» non corrisponde a ${fmt(r.date)}", r.alt)
                }, deadline = a.deadline || b.deadline, genitive = exact.genitive)
                ds[i] = merged; ds.removeAt(i + 1)
            } else i++
        }

        // parte del giorno subito dopo la data: «domani sera», «venerdì mattina»
        for (d in ds) {
            if (d.instant != null) continue
            val m = PART.toPattern().matcher(masked).region(d.end, masked.length)
            if (m.lookingAt()) {
                d.part = m.group(1); d.end = m.end()
            }
        }

        val times = mutableListOf<TimeHit>()
        TIME.findAll(masked).forEach { m -> time(m)?.let(times::add) }
        BARE_TIME.findAll(masked).forEach { m ->
            val h = m.groupValues[1].toInt()
            val t = LocalTime.of(h, m.groupValues[2].toInt())
            val amb = h in 1..7 && !m.groupValues[1].startsWith("0")
            val v = if (amb) t.plusHours(12) else t
            times += TimeHit(m.range.first, m.range.last + 1, v, false, if (amb) t else null, false, false, m.value)
        }
        NOON.findAll(masked).forEach { m -> times += TimeHit(m.range.first, m.range.last + 1, LocalTime.NOON, false, null, false, false, "mezzogiorno") }
        val ts = times.sortedBy { it.start }.filter { t -> ds.none { t.start < it.end && it.start < t.end } }
            .fold(mutableListOf<TimeHit>()) { acc, t -> if (acc.isEmpty() || acc.last().end <= t.start) acc += t; acc }
        // «dalle 15 alle 17»: si usa l'inizio, il testo resta nel titolo
        val merged = mutableListOf<TimeHit>()
        for (t in ts) {
            val prev = merged.lastOrNull()
            if (prev != null && prev.from && masked.substring(prev.end, t.start).isBlank() && masked.startsWith("all", t.start)) {
                prev.end = t.end; prev.keep = true; prev.said = text.substring(prev.start, t.end)
            } else merged += t
        }

        val out = ds.toMutableList()
        for (t in merged) {
            val d = ds.firstOrNull {
                it.time == null && it.instant == null &&
                    (it.end <= t.start && t.start - it.end <= 12 && GAP.matches(masked.substring(it.end, t.start)) ||
                        t.end <= it.start && it.start - t.end <= 12 && GAP.matches(masked.substring(t.end, it.start)))
            }
            val w = d ?: When(t.start, t.end, null).also(out::add)
            w.time = t.time; w.alt = t.alt; w.approx = t.approx; w.deadline = w.deadline || t.deadline
            w.timeSaid = t.said
            if (t.keep) w.keep += t.start until t.end
            w.start = minOf(w.start, t.start); w.end = maxOf(w.end, t.end)
        }
        for (d in out) {
            val p = d.part ?: continue
            val pt = PARTS.entries.first { p.startsWith(it.key) }.value
            val t = d.time
            when {
                t == null -> {
                    d.time = pt; d.approx = true; d.notes += "«$p» ≈ ${hm(pt)}"
                }
                pt.hour >= 15 && t.hour < 12 -> {
                    d.time = t.plusHours(12); d.alt = null
                }
                pt.hour < 12 && d.alt != null -> {
                    d.time = d.alt; d.alt = null
                }
                else -> d.alt = null
            }
        }
        return noOverlap(out.sortedBy { it.start })
    }

    private fun noOverlap(ws: List<When>): List<When> =
        ws.fold(mutableListOf<When>()) { acc, w ->
            val last = acc.lastOrNull()
            if (last == null || last.end <= w.start) acc += w
            else if (w.end - w.start > last.end - last.start) acc[acc.lastIndex] = w
            acc
        }

    private fun relDay(m: MatchResult): When {
        val w = m.groupValues[2]
        val (days, part) = when {
            w == "dopodomani" -> 2 to null
            w == "domani" -> 1 to null
            w == "oggi" -> 0 to null
            w == "ieri" -> -1 to null
            w.startsWith("l'altro") || w == "altroieri" -> -2 to null
            w == "stasera" -> 0 to "sera"
            w == "domattina" -> 1 to "mattina"
            else -> 0 to "mattina"
        }
        val d = today.plusDays(days.toLong())
        return When(m.range.first, m.range.last + 1, { Res(d, "«${m.value.trim()}» = ${fmt(d)}") }, past = days < 0, deadline = m.groupValues[1].isNotEmpty())
            .also { it.part = part }
    }

    private fun weekday(m: MatchResult): When {
        val g = m.groupValues
        val name = g[4]
        val dow = DayOfWeek.of(WEEKDAYS.indexOfFirst { name.startsWith(it) } + 1)
        val mod = g[3].ifEmpty { g[5] }
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val said = m.value.trim()
        val resolve: (Boolean) -> Res = { scheduling ->
            when {
                mod.startsWith("prossim") -> monday.plusWeeks(1).with(TemporalAdjusters.nextOrSame(dow))
                    .let { Res(it, "«$said» = ${dayName(dow)} della settimana prossima: ${fmt(it)}") }
                mod.startsWith("scors") -> monday.minusWeeks(1).with(TemporalAdjusters.nextOrSame(dow))
                    .let { Res(it, "«$said» = ${fmt(it)}") }
                g[2].isNotEmpty() -> monday.with(TemporalAdjusters.nextOrSame(dow)).let {
                    if (it >= today) Res(it, "«$said» = ${dayName(dow)} di questa settimana: ${fmt(it)}")
                    else today.with(TemporalAdjusters.next(dow)).let { n -> Res(n, "«$said» già passato: ${fmt(n)}", "«$said» è già passato: inteso ${fmt(n)}") }
                }
                !scheduling -> today.with(TemporalAdjusters.previousOrSame(dow)).let { Res(it, "«$said» (passato) = ${fmt(it)}") }
                today.dayOfWeek == dow -> today.plusWeeks(1).let {
                    Res(it, "Oggi è ${dayName(dow)}: «$said» = tra 7 giorni, ${fmt(it)}", "Oggi è ${dayName(dow)}: inteso ${fmt(it)}", today)
                }
                else -> today.with(TemporalAdjusters.next(dow)).let { Res(it, "«$said» = prossimo ${dayName(dow)}: ${fmt(it)}") }
            }
        }
        return When(
            m.range.first, m.range.last + 1, resolve, dow = dow, past = mod.startsWith("scors"),
            deadline = g[1] == "entro" || g[1] == "per", recurring = g[1] == "ogni", plain = g[1].isEmpty() && g[2].isEmpty() && mod.isEmpty(),
        )
    }

    private fun dated(m: MatchResult, day: Int, month: Int, year: Int?, prep: String, pre: String): When? {
        fun at(y: Int) = runCatching { LocalDate.of(y, month, day) }.getOrNull()
        if (month !in 1..12 || (year != null && at(year) == null) || (1..4).none { at(today.year + it - 1) != null }) return null
        val said = m.value.trim()
        val resolve: (Boolean) -> Res = { scheduling ->
            when {
                year != null -> at(year)!!.let { Res(it, "«$said» = ${fmt(it)}") }
                scheduling -> {
                    val next = (0..4).firstNotNullOf { at(today.year + it)?.takeIf { d -> d >= today } }
                    val prev = at(today.year)?.takeIf { it < today }
                    if (prev != null && ChronoUnit.DAYS.between(prev, today) <= 60)
                        Res(next, "«$said» = ${fmt(next)} (già passato quest'anno)", "Il ${fmt(prev)} è già passato: inteso ${fmt(next)}", prev)
                    else Res(next, "«$said» = ${fmt(next)}")
                }
                else -> ((-1..1).mapNotNull { at(today.year + it) }.minByOrNull { abs(ChronoUnit.DAYS.between(today, it)) }
                    ?: (0..4).firstNotNullOf { at(today.year + it) }).let { Res(it, "«$said» = ${fmt(it)}") }
            }
        }
        return When(m.range.first, m.range.last + 1, resolve, genitive = prep == "del" || prep == "dell'", deadline = pre.isNotEmpty())
    }

    private fun inOffset(m: MatchResult): When {
        val q = m.groupValues[1].trim()
        val unit = m.groupValues[2]
        val approx = q.startsWith("un paio") || unit == "oretta"
        val n = when {
            q.startsWith("un paio") -> 2.0
            q.startsWith("un quarto") -> .25
            q == "un'" -> 1.0
            q.startsWith("mezz") -> .5
            else -> (q.toIntOrNull() ?: NUMW[q] ?: 1).toDouble()
        }
        val r = m.range.first until m.range.last + 1
        fun instant(minutes: Long): When {
            val t = ctx.now.plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES)
            return When(r.first, r.last + 1, null, instant = t, approx = approx).also { it.notes += "«${m.value}» = ${fmt(t.toLocalDate())} ${hm(t.toLocalTime())}" }
        }
        fun day(d: LocalDate) = When(r.first, r.last + 1, { Res(d, "«${m.value}» = ${fmt(d)}") })
        return when {
            unit.startsWith("min") -> instant(n.toLong())
            unit.startsWith("or") || unit == "h" -> instant((n * 60).toLong())
            unit.startsWith("giorn") || unit == "gg" -> day(today.plusDays(n.toLong()))
            unit.startsWith("settiman") -> day(today.plusWeeks(n.toLong()))
            else -> day(today.plusMonths(n.toLong()))
        }
    }

    private fun time(m: MatchResult): TimeHit? {
        val g = m.groupValues
        val prefix = g[1].replace(Regex("\\s+"), " ")
        var h = g[2].toIntOrNull() ?: HOURW[g[2]] ?: return null
        var min = g[3].toIntOrNull() ?: 0
        when {
            g[4].startsWith("e mezz") -> min += 30
            g[4] == "e un quarto" -> min += 15
            g[4] == "e tre quarti" -> min += 45
            g[4].startsWith("meno") -> { h -= 1; min += 45 }
        }
        if (h !in 0..24 || min !in 0..59) return null
        val q = g[5]
        var alt: LocalTime? = null
        when {
            q.contains("pomeriggio") || q.contains("sera") -> if (h in 1..11) h += 12
            q.contains("notte") -> if (h in 6..11) h += 12
            q.isEmpty() && h in 1..7 && !g[2].startsWith("0") -> { alt = LocalTime.of(h, min); h += 12 }
        }
        val t = LocalTime.of(h % 24, min)
        return TimeHit(
            m.range.first, m.range.last + 1, t,
            approx = prefix.startsWith("verso") || prefix.startsWith("intorno") || prefix.startsWith("circa") || prefix.startsWith("sul"),
            alt = alt, deadline = prefix.startsWith("entro") || prefix.startsWith("per"), from = prefix.startsWith("dal"),
            said = text.substring(m.range.first, m.range.last + 1),
        )
    }
}
