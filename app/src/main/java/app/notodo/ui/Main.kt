package app.notodo.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.notodo.App
import app.notodo.data.Capture
import app.notodo.data.Created
import app.notodo.data.Due
import app.notodo.data.Filter
import app.notodo.data.Item
import app.notodo.data.Repo
import app.notodo.data.Settings
import app.notodo.data.Status
import app.notodo.data.View
import app.notodo.data.matches
import app.notodo.data.overdue
import app.notodo.data.select
import app.notodo.data.whenLabel
import app.notodo.parse.Kind
import app.notodo.reminder.Alarms
import app.notodo.reminder.Notifications
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { NoToDoTheme { MainScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.tick()
    }

    private fun handle(i: Intent) {
        i.data?.takeIf { it.scheme == "notodo" && it.host == "item" }?.lastPathSegment?.let(vm::open)
        i.getStringExtra(EXTRA_VIEW)?.let { name -> View.entries.firstOrNull { it.name == name } }?.let {
            vm.view = it
            vm.close()
        }
    }

    companion object {
        private const val EXTRA_VIEW = "view"
        private const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        fun open(ctx: Context, itemId: String): Intent =
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Notifications.itemUri(itemId)).addFlags(FLAGS)

        fun openView(ctx: Context, view: View): Intent =
            Intent(ctx, MainActivity::class.java).setAction("app.notodo.VIEW.${view.name}").putExtra(EXTRA_VIEW, view.name).addFlags(FLAGS)
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as App
    val repo: Repo get() = a.repo
    val settings = a.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val items = a.repo.items.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val inbox = a.repo.inbox.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var view by mutableStateOf(View.TODAY)
    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter())
    var detail by mutableStateOf<String?>(null)
        private set
    var showSettings by mutableStateOf(false)
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    fun tick() {
        now = a.repo.clock()
    }

    fun open(id: String) {
        detail = id
        viewModelScope.launch { a.repo.markSeen(id) }
    }

    fun close() {
        detail = null
    }

    fun act(block: suspend Repo.() -> Unit) = viewModelScope.launch { a.repo.block() }

    fun updateSettings(change: (Settings) -> Settings) = viewModelScope.launch {
        a.settings.update(change)
        Alarms.scheduleDigest(a)
    }
}

@Composable
fun MainScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = Instant.ofEpochMilli(vm.now).atZone(settings.zoneId)
    BackHandler(vm.detail != null || vm.showSettings) {
        if (vm.showSettings) vm.showSettings = false else vm.close()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val id = vm.detail
        when {
            vm.showSettings -> SettingsScreen(vm, settings)
            id != null -> DetailScreen(vm, id, settings, now)
            else -> ListScreen(vm, settings, now)
        }
    }
}

@Composable
private fun ListScreen(vm: MainViewModel, settings: Settings, now: ZonedDateTime) {
    val ctx = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val visible = remember(items, vm.view, vm.query, vm.filter, now) { select(items, vm.view, vm.query, vm.filter, now) }
    val searching = vm.query.isNotBlank()
    var filters by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(if (searching) "Ricerca in tutto" else "NoToDo")
                Text(
                    if (searching) "«${vm.query}»" else vm.view.label,
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(if (vm.view == View.INBOX && !searching) "${inbox.size}" else "${visible.size}", fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { vm.showSettings = true }) { Icon(Icons.Default.Settings, "Impostazioni") }
        }
        Box(Modifier.weight(1f)) {
            if (vm.view == View.INBOX && !searching) InboxList(inbox, now) { vm.act { deleteDraft(it) } }
            else if (visible.isEmpty()) Empty(if (searching || vm.filter.active) "Nessun risultato" else "Niente qui. Tocca + per catturare.")
            else key(vm.view) { // cambiando vista si riparte dal primo elemento, in basso
                LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
                    itemsIndexed(visible, key = { _, i -> i.id }) { n, i ->
                        ItemRow(i, now, onOpen = { vm.open(i.id) }, onToggle = { vm.act { setDone(i.id, !i.done) } }, first = n == 0)
                        // a lista rovesciata il divisore finisce sopra la riga: niente accanto alla pillola né in cima
                        if (n > 0 && n < visible.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        if (filters) FilterBar(vm, items)
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(View.entries) { v ->
                val count = if (v == View.INBOX) inbox.size else items.count { v.matches(it, now) }
                FilterChip(selected = vm.view == v && !searching, onClick = { vm.view = v; vm.query = "" }, label = {
                    Text(buildAnnotatedString {
                        append(v.label)
                        if (count > 0) withStyle(SpanStyle(fontFamily = Mono, fontSize = 12.sp)) { append("  $count") }
                    })
                })
            }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                vm.query, { vm.query = it }, Modifier.weight(1f),
                placeholder = { Text("Cerca") }, singleLine = true, shape = CircleShape,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searching) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Close, "Cancella ricerca") } },
            )
            IconButton(onClick = { filters = !filters }) {
                Icon(Icons.AutoMirrored.Filled.List, if (filters) "Nascondi filtri" else "Filtri", tint = if (vm.filter.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            FilledIconButton(
                onClick = { ctx.startActivity(CaptureActivity.intent(ctx, "app")) },
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Icon(Icons.Default.Add, "Nuova cattura") }
        }
    }
}

@Composable
fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** [first]: il primo della lista (in basso sullo schermo) è in evidenza, in una pillola rossa larga quanto la riga. */
@Composable
fun ItemRow(i: Item, now: ZonedDateTime, onOpen: () -> Unit, onToggle: () -> Unit, first: Boolean = false) {
    val late = i.overdue(now)
    val scheme = MaterialTheme.colorScheme
    val text = if (first) scheme.onPrimary else scheme.onSurface
    val quiet = if (first) scheme.onPrimary.copy(alpha = .9f) else scheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth()
            .then(if (first) Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clip(CircleShape).background(scheme.primary) else Modifier)
            .clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (i.kind == Kind.TASK || i.kind == Kind.VERIFY) RoundCheck(i.done, { onToggle() }, color = if (i.done) quiet else text, hole = if (first) scheme.primary else scheme.background)
        else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { KindGlyph(i.kind, color = quiet) }
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Text(
                i.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (i.done) quiet else text,
                textDecoration = if (i.done) TextDecoration.LineThrough else null,
            )
            val meta = listOfNotNull(
                i.whenLabel(now),
                "avviso".takeIf { i.nextAlertAt != null },
                i.people.joinToString(", ").ifEmpty { null },
                i.tags.joinToString(" ") { "#$it" }.ifEmpty { null },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontFamily = Mono, fontSize = 12.sp, color = if (late && !first) scheme.primary else quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (late) Dot(Modifier.padding(end = 8.dp), color = if (first) text else scheme.primary)
    }
}

@Composable
private fun InboxList(inbox: List<Capture>, now: ZonedDateTime, onDelete: (String) -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<Capture?>(null) }
    if (inbox.isEmpty()) return Empty("Inbox vuota: ogni cattura non elaborata finisce qui, integra.")
    LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
        items(inbox, key = { it.id }) { c ->
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp)) {
                Text(c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("${c.source} · ${whenLabel(Instant.ofEpochMilli(c.updatedAt).atZone(now.zone), null, now)}", Modifier.weight(1f))
                    TextButton(onClick = { confirm = c }) { Text("Elimina") }
                    TextButton(onClick = { ctx.startActivity(CaptureActivity.process(ctx, c.id)) }) { Text("Elabora") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Eliminare la bozza?") },
            text = { Text("«${c.text.take(120)}» verrà eliminata definitivamente.") },
            confirmButton = { TextButton(onClick = { onDelete(c.id); confirm = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun FilterBar(vm: MainViewModel, items: List<Item>) {
    val f = vm.filter
    LazyRow(Modifier.background(MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Choice("Tipo", f.kind, listOf(null) + Kind.entries, { it?.label ?: "Tutti" }) { vm.filter = f.copy(kind = it) } }
        item { Choice("Tag", f.tag, listOf(null) + items.flatMap { it.tags }.distinct().sorted(), { it?.let { t -> "#$t" } ?: "Tutti" }) { vm.filter = f.copy(tag = it) } }
        item { Choice("Persona", f.person, listOf(null) + items.flatMap { it.people }.distinct().sorted(), { it ?: "Tutte" }) { vm.filter = f.copy(person = it) } }
        item { Choice("Scadenza", f.due, Due.entries, { it.label }) { vm.filter = f.copy(due = it) } }
        item { Choice("Stato", f.status, Status.entries, { it.label }) { vm.filter = f.copy(status = it) } }
        item { Choice("Creato", f.created, Created.entries, { it.label }) { vm.filter = f.copy(created = it) } }
        if (f.active) item { TextButton(onClick = { vm.filter = Filter() }) { Text("Azzera") } }
    }
}

@Composable
private fun <T> Choice(label: String, value: T, options: List<T>, text: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = value != options.first(), onClick = { open = true }, label = { Text("$label: ${text(value)}") })
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onPick(o); open = false }) }
        }
    }
}
