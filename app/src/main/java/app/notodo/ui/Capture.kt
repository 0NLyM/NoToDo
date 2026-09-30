package app.notodo.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.notodo.App
import app.notodo.data.Capture
import app.notodo.data.Settings
import app.notodo.data.dayLabel
import app.notodo.data.whenLabel
import app.notodo.parse.Alerts
import app.notodo.parse.Doubt
import app.notodo.parse.Draft
import app.notodo.parse.Kind
import app.notodo.reminder.Alarms
import app.notodo.reminder.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.UUID

class CaptureActivity : ComponentActivity() {
    private val vm: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.start(intent.getStringExtra(EXTRA_CAPTURE), prefill(intent), intent.getStringExtra(EXTRA_SOURCE) ?: sourceOf(intent))
        setContent { NoToDoTheme { CaptureScreen(vm, ::finish) } }
    }

    /** Riaprire la cattura riprende quella in corso; un testo condiviso nel frattempo viene accodato, mai perso. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getStringExtra(EXTRA_CAPTURE)
        if (id != null) vm.start(id, null, "inbox") else prefill(intent)?.let(vm::append)
    }

    companion object {
        const val ACTION = "app.notodo.CAPTURE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CAPTURE = "capture"

        fun intent(ctx: Context, source: String): Intent = Intent(ctx, CaptureActivity::class.java).setAction(ACTION).putExtra(EXTRA_SOURCE, source)
        fun process(ctx: Context, captureId: String): Intent = Intent(ctx, CaptureActivity::class.java).setAction(ACTION).putExtra(EXTRA_CAPTURE, captureId)

        fun prefill(i: Intent): String? =
            (i.getStringExtra(Intent.EXTRA_TEXT) ?: i.getStringExtra(EXTRA_TEXT) ?: i.data?.takeIf { it.scheme == "notodo" }?.getQueryParameter("text"))
                ?.takeIf { it.isNotBlank() }

        private fun sourceOf(i: Intent) = when (i.action) {
            Intent.ACTION_SEND -> "condivisione"
            Intent.ACTION_VIEW -> "deep link"
            ACTION -> "intent"
            else -> "app"
        }
    }
}

class CaptureViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    private val a = app as App
    var id: String = handle[KEY_ID] ?: newId()
        private set
    var source by mutableStateOf("app")
        private set
    var text by mutableStateOf("")
        private set
    var drafts by mutableStateOf(emptyList<Draft>())
        private set
    /** Dopo Unisci/Separa l'analisi automatica si ferma per non perdere la struttura scelta. */
    var frozen by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var resumable by mutableStateOf<Capture?>(null)
        private set
    var settings by mutableStateOf(Settings())
        private set
    var message by mutableStateOf<String?>(null)
    private val edited = mutableMapOf<String, Draft>()
    private val discarded = mutableSetOf<String>()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            settings = a.settings.get()
            // Processo ricreato dal sistema: si riparte dalla bozza già salvata.
            handle.get<String>(KEY_ID)?.let { a.repo.findCapture(it) }?.takeIf { !it.processed }?.let { load(it) }
        }
    }

    private fun newId() = UUID.randomUUID().toString().also { handle[KEY_ID] = it }

    /** Chiusura entro il debounce: l'ultimo testo si salva comunque, fuori dal ciclo di vita della schermata. */
    override fun onCleared() {
        val (i, t, src, z) = listOf(id, text, source, settings.zone)
        a.scope.launch { a.repo.saveDraft(i, t, src, java.time.ZoneId.of(z)) }
    }

    fun start(captureId: String?, prefill: String?, source: String) {
        viewModelScope.launch {
            settings = a.settings.get()
            val c = captureId?.let { a.repo.findCapture(it) }?.takeIf { !it.processed }
            if (c != null) return@launch load(c)
            id = newId()
            this@CaptureViewModel.source = source
            reset()
            text = ""
            drafts = emptyList()
            if (prefill != null) onText(prefill, immediate = true)
            else resumable = a.repo.inbox.first().firstOrNull { a.repo.clock() - it.updatedAt < 30 * 60_000L }
        }
    }

    private suspend fun load(c: Capture) {
        id = c.id
        handle[KEY_ID] = c.id
        source = c.source
        text = c.text
        resumable = null
        reset()
        analyze()
    }

    private fun reset() {
        frozen = false
        edited.clear()
        discarded.clear()
    }

    fun resume() = resumable?.let { c -> viewModelScope.launch { load(c) } }

    fun append(t: String) = onText(if (text.isBlank()) t else "$text\n$t", immediate = true)

    fun onText(t: String, immediate: Boolean = false) {
        text = t
        if (t.isNotBlank()) resumable = null
        job?.cancel()
        job = viewModelScope.launch {
            if (!immediate) delay(250)
            analyze()
            a.repo.saveDraft(id, text, source, settings.zoneId)
        }
    }

    private suspend fun analyze() {
        if (frozen) return
        val ctx = settings.parseContext(a.repo.clock())
        val t = text
        runCatching { withContext(Dispatchers.Default) { a.parser.parse(t, ctx) } }
            .onSuccess { parsed ->
                failed = false
                failure = null
                drafts = parsed.filter { it.source !in discarded }.map { d -> edited[d.source]?.copy(start = d.start, end = d.end) ?: d }
            }
            .onFailure {
                failed = true
                failure = it.toString().take(160)
                drafts = emptyList()
            }
    }

    private fun replace(i: Int, d: Draft) {
        edited[d.source] = d
        drafts = drafts.toMutableList().also { it[i] = d }
    }

    fun edit(i: Int, f: Fields) = replace(i, drafts[i].with(f))

    fun setKind(i: Int, k: Kind) {
        val d = drafts[i]
        val alerts = if (d.alerts.isNotEmpty() && (k == Kind.TASK || k == Kind.EVENT)) d.alerts else defaultAlerts(k, d.precision, settings.defaultAlerts, settings.day)
        replace(i, d.copy(kind = k, alerts = alerts, recognized = true))
    }

    fun useAlternative(i: Int, doubt: Doubt) {
        val d = drafts[i]
        replace(i, d.copy(at = doubt.alternative, doubts = d.doubts - doubt))
    }

    fun discard(i: Int) {
        discarded += drafts[i].source
        drafts = drafts.filterIndexed { j, _ -> j != i }
    }

    fun merge(i: Int) {
        val ctx = settings.parseContext(a.repo.clock())
        frozen = true
        drafts = drafts.take(i) + a.parser.parseSpan(text, drafts[i].start, drafts[i + 1].end, ctx) + drafts.drop(i + 2)
    }

    fun split(i: Int) {
        val d = drafts[i]
        val parts = a.parser.splitSpan(text, d.start, d.end)
        if (parts.size < 2) {
            message = "Nessun punto di separazione: vai a capo o usa «;» nel testo"
            return
        }
        val ctx = settings.parseContext(a.repo.clock())
        frozen = true
        drafts = drafts.take(i) + parts.map { a.parser.parseSpan(text, it.first, it.last + 1, ctx) } + drafts.drop(i + 1)
    }

    fun reanalyze() = viewModelScope.launch {
        reset()
        analyze()
    }

    /** Salvataggio atomico; se l'analisi fallisce o nulla è riconosciuto, il testo resta integro in Inbox. */
    suspend fun save(): String {
        job?.cancel()
        a.repo.saveDraft(id, text, source, settings.zoneId)
        if (text.isBlank()) return "Niente da salvare"
        analyze()
        return when {
            failed -> "Analisi non riuscita: testo salvato in Inbox"
            drafts.isNotEmpty() && drafts.none { it.recognized } -> "Non riconosciuto: testo salvato in Inbox"
            !a.repo.confirm(id, text, source, settings.zoneId, drafts) -> "Già salvato"
            drafts.isEmpty() -> "Tutto scartato"
            drafts.size == 1 -> "Salvato 1 elemento"
            else -> "Salvati ${drafts.size} elementi"
        }
    }

    companion object {
        private const val KEY_ID = "notodo.capture.id"
    }
}

@Composable
fun CaptureScreen(vm: CaptureViewModel, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var editing by remember { mutableStateOf<Int?>(null) }
    val now = ZonedDateTime.now(vm.settings.zoneId)
    fun done(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        close()
    }
    fun save() = scope.launch {
        val msg = vm.save()
        withContext(Dispatchers.Main) { done(msg) }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { save() }
    val withAlerts = vm.drafts.any { it.alerts.isNotEmpty() && it.at != null }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))
            .pointerInput(Unit) { detectTapGestures { if (vm.text.isBlank()) close() } },
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().statusBarsPadding().imePadding().navigationBarsPadding()
                .padding(8.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(16.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot()
                Spacer(Modifier.width(8.dp))
                Label("Cattura · ${vm.source}", Modifier.weight(1f))
                IconButton(onClick = { if (vm.text.isNotBlank()) done("Bozza in Inbox") else close() }) { Icon(Icons.Default.Close, "Chiudi") }
            }
            vm.resumable?.let { c ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Bozza non salvata: «${c.text.take(60)}»", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { vm.resume() }) { Text("Riprendi") }
                }
            }
            OutlinedTextField(
                value = vm.text,
                onValueChange = { vm.onText(it) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Scrivi o detta con il microfono della tastiera…") },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Status(vm, withAlerts)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(vm.drafts, key = { i, d -> "$i:${d.start}" }) { i, d ->
                    DraftCard(
                        d, now, canMerge = i < vm.drafts.lastIndex,
                        onEdit = { editing = i }, onKind = { vm.setKind(i, it) }, onAlternative = { vm.useAlternative(i, it) },
                        onMerge = { vm.merge(i) }, onSplit = { vm.split(i) }, onDiscard = { vm.discard(i) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { done(if (vm.text.isBlank()) "Niente da salvare" else "Lasciato in Inbox") }, enabled = vm.text.isNotBlank()) { Text("Inbox") }
                Button(
                    onClick = { if (withAlerts && !Notifications.allowed(ctx)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else save() },
                    modifier = Modifier.weight(1f),
                    enabled = vm.text.isNotBlank(),
                ) { Text(if (vm.drafts.size > 1) "Salva tutto (${vm.drafts.size})" else "Salva") }
            }
        }
    }
    editing?.let { i ->
        vm.drafts.getOrNull(i)?.let { d ->
            EditDialog(d.fields(), vm.settings.zoneId, vm.settings.defaultAlerts, vm.settings.day, onDismiss = { editing = null }) {
                vm.edit(i, it)
                editing = null
            }
        }
    }
    vm.message?.let {
        LaunchedEffect(it) {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}

@Composable
private fun Status(vm: CaptureViewModel, withAlerts: Boolean) {
    val ctx = LocalContext.current
    val red = MaterialTheme.colorScheme.primary
    val small = MaterialTheme.typography.bodySmall
    when {
        vm.failed -> Text("Analisi non riuscita: il testo verrà salvato integro in Inbox\n${vm.failure.orEmpty()}", color = red, style = small)
        vm.text.isBlank() -> Text("Esempio: «domani alle 9 chiama Luca; idea: supporto 3D per la scrivania»", color = MaterialTheme.colorScheme.onSurfaceVariant, style = small)
        vm.drafts.isNotEmpty() && vm.drafts.none { it.recognized } -> Text("Non riconosciuto: resterà in Inbox da elaborare", color = red, style = small)
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Label("${vm.drafts.size} ${if (vm.drafts.size == 1) "elemento" else "elementi"} · da confermare", Modifier.weight(1f))
            if (vm.frozen) TextButton(onClick = { vm.reanalyze() }) { Text("Rianalizza") }
        }
    }
    if (withAlerts && !Alarms.exactAllowed(ctx)) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Avvisi non precisi: manca il permesso «Sveglie e promemoria»", Modifier.weight(1f), color = red, style = small)
        TextButton(onClick = { ctx.startActivity(Intent(SystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri())) }) { Text("Consenti") }
    }
}

@Composable
fun Confidence(value: Float) {
    val n = when {
        value >= .75f -> 3
        value >= .45f -> 2
        else -> 1
    }
    val label = listOf("bassa", "media", "alta")[n - 1]
    Row(Modifier.semantics { contentDescription = "Confidenza $label" }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { Dot(color = if (it < n) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, size = 5.dp) }
    }
}

@Composable
fun DraftCard(
    d: Draft,
    now: ZonedDateTime,
    canMerge: Boolean,
    onEdit: () -> Unit,
    onKind: (Kind) -> Unit,
    onAlternative: (Doubt) -> Unit,
    onMerge: () -> Unit,
    onSplit: () -> Unit,
    onDiscard: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var kinds by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .border(1.dp, if (d.recognized) scheme.outlineVariant else scheme.primary, MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Correggi", onClick = onEdit).padding(start = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "Cambia tipo") { kinds = true }, contentAlignment = Alignment.CenterStart) {
                Label(d.kind.label, color = scheme.onSurface)
                DropdownMenu(kinds, { kinds = false }) {
                    Kind.entries.forEach { k -> DropdownMenuItem(text = { Text(k.label) }, onClick = { onKind(k); kinds = false }) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Confidence(d.confidence)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Azioni") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Correggi") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Unisci con il successivo") }, onClick = { menu = false; onMerge() }, enabled = canMerge)
                    DropdownMenuItem(text = { Text("Separa") }, onClick = { menu = false; onSplit() })
                }
            }
            IconButton(onClick = onDiscard) { Icon(Icons.Default.Close, "Scarta") }
        }
        Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.title, style = MaterialTheme.typography.titleMedium)
            d.at?.let { Text(whenLabel(it, d.precision, now), fontFamily = Mono, color = if (d.doubts.isEmpty()) scheme.onSurface else scheme.primary) }
            if (d.alerts.isNotEmpty()) Text("Avvisi: " + d.alerts.joinToString(" · ") { Alerts.label(it) }, style = small, color = scheme.onSurfaceVariant)
            d.doubts.forEach { doubt ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(doubt.text, Modifier.weight(1f), style = small)
                    doubt.alternative?.let { alt -> TextButton(onClick = { onAlternative(doubt) }) { Text("Usa ${whenLabel(alt, d.precision, now)}") } }
                }
            }
            if (!d.recognized) Text("Non riconosciuto: correggilo o resterà in Inbox", color = scheme.primary, style = small)
            val meta = (d.people.map { "@$it" } + d.tags.map { "#$it" } + d.mentions.map { "↳ ${dayLabel(it, now.toLocalDate())}" }).joinToString("   ")
            if (meta.isNotEmpty()) Text(meta, style = small, color = scheme.onSurfaceVariant)
            Text("«${d.source}»", style = small, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                if (why) "Nascondi motivi" else "Perché?",
                Modifier.clickable { why = !why }.padding(vertical = 4.dp),
                style = small, color = scheme.primary,
            )
            AnimatedVisibility(why) { Column { d.reasons.forEach { Text("· $it", style = small) } } }
        }
    }
}
