@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)

package com.example.notes

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val ALL = -1L
const val TRASH = -2L
private const val DAY = 86_400_000L
private val Yellow = Color(0xFFE5A400)

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    val scheme = if (androidx.compose.foundation.isSystemInDarkTheme())
        darkColorScheme(primary = Color(0xFFFFD60A), background = Color.Black, surface = Color(0xFF1C1C1E),
            onBackground = Color.White, onSurface = Color.White)
    else
        lightColorScheme(primary = Yellow, background = Color(0xFFF2F2F7), surface = Color.White,
            onBackground = Color.Black, onSurface = Color.Black)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable private fun muted() = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f)

// ---------------------------------------------------------------- navigation

@Composable
fun NotesApp(vm: NotesViewModel = viewModel()) {
    val notes by vm.notes.collectAsState()
    val folders by vm.folders.collectAsState()
    val loaded = notes
    if (loaded == null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    else AppContent(vm, loaded, folders)
}

@Composable
private fun AppContent(vm: NotesViewModel, notes: List<Note>, folders: List<Folder>) {
    var sel by rememberSaveable { mutableStateOf<Long?>(null) }       // null = folder list
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }   // null = none, 0 = new note
    val e = editing
    val s = sel
    // Never open a note that is missing, or a locked note that has not been unlocked.
    val target = if (e != null && e > 0) notes.firstOrNull { it.id == e } else null
    val blocked = e != null && e > 0 && (target == null || (target.locked && e !in vm.unlocked))
    LaunchedEffect(blocked) { if (blocked) editing = null }
    when {
        blocked -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        e != null -> key(e) {
            EditorScreen(vm, e, if (s != null && s > 0) s else 1L, folders, notes) { editing = null }
        }
        s != null -> NoteListScreen(vm, s, notes, folders, { sel = null }, { editing = it }, { editing = 0L })
        else -> FolderScreen(vm, notes, folders, { sel = it }, { editing = 0L })
    }
}

@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding(),
        content = content
    )
}

// ---------------------------------------------------------------- folders

@Composable
fun FolderScreen(vm: NotesViewModel, notes: List<Note>, folders: List<Folder>, onSelect: (Long) -> Unit, onNew: () -> Unit) {
    var newFolder by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<Folder?>(null) }
    var rename by remember { mutableStateOf<Folder?>(null) }
    val active = notes.filter { it.deletedAt == null }
    val trash = notes.count { it.deletedAt != null }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val msg = try {
                val (n, locked) = vm.backupTo(uri)
                "Backed up $n notes" + if (locked > 0) ". $locked locked notes were not included." else ""
            } catch (ex: Exception) { "Backup failed" }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = try {
                val (added, skipped) = vm.restoreFrom(uri)
                "Restored $added notes" + if (skipped > 0) " ($skipped were already on this phone)" else ""
            } catch (ex: Exception) { "That file isn't a valid backup" }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    Screen {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
            Box {
                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = MaterialTheme.colorScheme.primary) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Back up all notes") }, onClick = {
                        menu = false
                        backupLauncher.launch("Notes-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json")
                    })
                    DropdownMenuItem(text = { Text("Restore from backup") }, onClick = {
                        menu = false
                        restoreLauncher.launch(arrayOf("*/*"))
                    })
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            item { Text("Folders", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 24.dp, 16.dp, 12.dp)) }
            item { GroupRow(Icons.Outlined.Description, "All Notes", active.size, true, true, { onSelect(ALL) }) }
            item { Text("On My Phone", color = muted(), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(20.dp, 20.dp, 16.dp, 6.dp)) }
            itemsIndexed(folders, key = { _, f -> f.id }) { i, f ->
                GroupRow(Icons.Outlined.Folder, f.name, active.count { it.folderId == f.id }, i == 0, false,
                    { onSelect(f.id) }, if (f.id != 1L) ({ action = f }) else null)
            }
            item { GroupRow(Icons.Outlined.Delete, "Recently Deleted", trash, folders.isEmpty(), true, { onSelect(TRASH) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ newFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, "New Folder", tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.weight(1f))
            IconButton(onNew) { Icon(Icons.Outlined.Edit, "New Note", tint = MaterialTheme.colorScheme.primary) }
        }
    }

    if (newFolder) NameDialog("New Folder", "", { vm.addFolder(it); newFolder = false }, { newFolder = false })
    rename?.let { f -> NameDialog("Rename Folder", f.name, { vm.renameFolder(f, it); rename = null }, { rename = null }) }
    action?.let { f ->
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(f.name) },
            text = { Text("Deleting a folder moves its notes to Recently Deleted.") },
            confirmButton = { TextButton(onClick = { rename = f; action = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { vm.deleteFolder(f); action = null }) { Text("Delete", color = Color(0xFFFF3B30)) } }
        )
    }
}

@Composable
private fun GroupRow(icon: ImageVector, label: String, count: Int, first: Boolean, last: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(
        topStart = if (first) 12.dp else 0.dp, topEnd = if (first) 12.dp else 0.dp,
        bottomStart = if (last) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp)
    Column(Modifier.padding(horizontal = 16.dp).clip(shape).background(MaterialTheme.colorScheme.surface)
        .combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text("$count", color = muted(), fontSize = 17.sp)
            Icon(Icons.Outlined.ChevronRight, null, tint = muted())
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 54.dp), color = muted().copy(alpha = .2f))
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { if (t.isNotBlank()) onDone(t.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- note list

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun bucket(ts: Long): String {
    val t = startOfToday()
    return when {
        ts >= t -> "Today"
        ts >= t - DAY -> "Yesterday"
        ts >= t - 7 * DAY -> "Previous 7 Days"
        ts >= t - 30 * DAY -> "Previous 30 Days"
        else -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ts))
    }
}

private fun dateLabel(ts: Long): String {
    val t = startOfToday()
    val f = when {
        ts >= t -> "h:mm a"
        ts >= t - 6 * DAY -> "EEEE"
        else -> "dd/MM/yy"
    }
    return SimpleDateFormat(f, Locale.getDefault()).format(Date(ts))
}

@Composable
fun NoteListScreen(
    vm: NotesViewModel, sel: Long, notes: List<Note>, folders: List<Folder>,
    onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var trashNote by remember { mutableStateOf<Note?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    val inTrash = sel == TRASH
    val title = when (sel) { ALL -> "All Notes"; TRASH -> "Recently Deleted"; else -> folders.firstOrNull { it.id == sel }?.name ?: "Notes" }
    val inFolder = notes.filter { if (inTrash) it.deletedAt != null else it.deletedAt == null && (sel == ALL || it.folderId == sel) }
    val shown = (if (inTrash) inFolder.sortedByDescending { it.deletedAt } else inFolder)
        .filter { query.isBlank() || (!it.locked && plain(it.text).contains(query, ignoreCase = true)) }
    val sections: List<Pair<String, List<Note>>> =
        if (inTrash) (if (shown.isEmpty()) emptyList() else listOf("Notes are deleted after 30 days" to shown))
        else buildList {
            val pinned = shown.filter { it.pinned }
            if (pinned.isNotEmpty()) add("Pinned" to pinned)
            shown.filter { !it.pinned }.groupBy { bucket(it.updatedAt) }.forEach { (k, v) -> add(k to v) }
        }

    fun openNote(n: Note) {
        if (!n.locked || n.id in vm.unlocked) { onOpen(n.id); return }
        val act = ctx.findActivity() ?: return
        authenticate(act, "Unlock note", {
            if (Crypto.decrypt(n.text) == null)
                Toast.makeText(ctx, "This locked note can't be opened on this device.", Toast.LENGTH_LONG).show()
            else { vm.unlocked.add(n.id); onOpen(n.id) }
        }, { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() })
    }

    Screen {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBackIosNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Folders")
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = MaterialTheme.colorScheme.primary) }
                DropdownMenu(menu, { menu = false }) {
                    if (!inTrash) DropdownMenuItem(
                        text = { Text("Export as PDF") },
                        leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                        onClick = {
                            menu = false
                            val export = inFolder.filter { !it.locked }.sortedByDescending { it.updatedAt }
                            val leftOut = inFolder.count { it.locked }
                            if (export.isEmpty()) Toast.makeText(ctx, "No notes to export", Toast.LENGTH_SHORT).show()
                            else scope.launch {
                                val uri = withContext(Dispatchers.IO) { exportPdf(ctx, title, export) }
                                Toast.makeText(ctx, if (uri != null) "PDF saved to Downloads" + (if (leftOut > 0) ". Locked notes were left out." else "") else "Export failed", Toast.LENGTH_LONG).show()
                            }
                        })
                    else DropdownMenuItem(text = { Text("Delete All") }, onClick = { menu = false; confirmEmpty = true })
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 10.dp))
                SearchField(query) { query = it }
            }
            if (shown.isEmpty()) item {
                Text(if (query.isBlank()) "No Notes" else "No Results", color = muted(), modifier = Modifier.padding(32.dp))
            }
            sections.forEach { (header, list) ->
                item(key = "h-$header") {
                    Text(header, color = muted(), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(20.dp, 20.dp, 16.dp, 6.dp))
                }
                itemsIndexed(list, key = { _, n -> n.id }) { i, n ->
                    NoteRow(n, inTrash, i == 0, i == list.lastIndex, vm) { if (inTrash) trashNote = n else openNote(n) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(48.dp))
            Text("${inFolder.size} Notes", color = muted(), fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (!inTrash) IconButton(onNew) { Icon(Icons.Outlined.Edit, "New Note", tint = MaterialTheme.colorScheme.primary) }
            else Spacer(Modifier.size(48.dp))
        }
    }

    trashNote?.let { n ->
        AlertDialog(
            onDismissRequest = { trashNote = null },
            title = { Text(noteTitle(n)) },
            text = { Text("Recover this note, or delete it permanently?") },
            confirmButton = { TextButton(onClick = { vm.restore(n.id); trashNote = null }) { Text("Recover") } },
            dismissButton = { TextButton(onClick = { vm.deleteForever(n.id); trashNote = null }) { Text("Delete Permanently", color = Color(0xFFFF3B30)) } }
        )
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("Delete all notes permanently?") },
        text = { Text("This can't be undone.") },
        confirmButton = { TextButton(onClick = { vm.emptyTrash(); confirmEmpty = false }) { Text("Delete All", color = Color(0xFFFF3B30)) } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
    )
}

@Composable
private fun SearchField(q: String, onChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .08f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp)
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = muted(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search", color = muted(), fontSize = 17.sp)
                BasicTextField(
                    q, onChange, singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (q.isNotEmpty()) IconButton({ onChange("") }, Modifier.size(24.dp)) { Icon(Icons.Outlined.Close, "Clear", tint = muted()) }
        }
    }
}

@Composable
private fun NoteRow(n: Note, inTrash: Boolean, first: Boolean, last: Boolean, vm: NotesViewModel, onClick: () -> Unit) {
    val shape = RoundedCornerShape(
        topStart = if (first) 12.dp else 0.dp, topEnd = if (first) 12.dp else 0.dp,
        bottomStart = if (last) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp)
    val id = n.id
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> if (inTrash) vm.restore(id) else vm.togglePin(id)
            SwipeToDismissBoxValue.EndToStart -> if (inTrash) vm.deleteForever(id) else vm.trash(id)
            else -> {}
        }
        false
    })
    Box(Modifier.padding(horizontal = 16.dp).clip(shape)) {
        SwipeToDismissBox(
            state = state,
            backgroundContent = {
                val d = state.dismissDirection
                val color = when (d) {
                    SwipeToDismissBoxValue.StartToEnd -> Yellow
                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFFF3B30)
                    else -> Color.Transparent
                }
                val icon = if (d == SwipeToDismissBoxValue.StartToEnd) (if (inTrash) Icons.Outlined.Restore else Icons.Outlined.PushPin) else Icons.Outlined.Delete
                Box(
                    Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp),
                    contentAlignment = if (d == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                ) { if (d != SwipeToDismissBoxValue.Settled) Icon(icon, null, tint = Color.White) }
            }
        ) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(noteTitle(n), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (n.locked) Icon(Icons.Outlined.Lock, null, tint = muted(), modifier = Modifier.size(14.dp))
                        if (n.pinned) Icon(Icons.Outlined.PushPin, null, tint = Yellow, modifier = Modifier.size(14.dp))
                    }
                    Row {
                        Text(dateLabel(if (inTrash) n.deletedAt ?: n.updatedAt else n.updatedAt), color = muted(), fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(notePreview(n), color = muted(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), color = muted().copy(alpha = .2f))
            }
        }
    }
}

// ---------------------------------------------------------------- editor

@Composable
fun EditorScreen(
    vm: NotesViewModel, noteId: Long, defaultFolder: Long,
    folders: List<Folder>, notes: List<Note>, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    val initial = remember { notes.firstOrNull { it.id == noteId } }
    val initialText = remember { initial?.let { if (it.locked) Crypto.decrypt(it.text) ?: "" else it.text } ?: "" }
    val session = remember { EditSession(noteId, initialText, initial?.locked == true) }
    var isLocked by remember { mutableStateOf(initial?.locked == true) }
    var moreMenu by remember { mutableStateOf(false) }
    val layoutHolder = remember { arrayOfNulls<TextLayoutResult>(1) }
    var folderId by remember { mutableLongStateOf(initial?.folderId ?: defaultFolder) }
    var value by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    var shownId by remember { mutableLongStateOf(noteId) }
    var styleMenu by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    val pinned = notes.firstOrNull { it.id == shownId }?.pinned == true
    val latest by rememberUpdatedState(value)
    val latestFolder by rememberUpdatedState(folderId)

    LaunchedEffect(Unit) { if (noteId == 0L) focus.requestFocus() }
    // Auto-save shortly after every change.
    LaunchedEffect(Unit) {
        snapshotFlow { value.text }.debounce(300).collect {
            vm.save(session, it, latestFolder); shownId = session.id
        }
    }
    // Save immediately when the app goes to the background.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) vm.saveAsync(session, latest.text, latestFolder)
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    fun close() { vm.finish(session, latest.text, latestFolder); vm.relock(session.id); onClose() }
    BackHandler { close() }

    val styler = NoteStyler(muted().copy(alpha = .5f), MaterialTheme.colorScheme.primary, muted())

    Screen {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { close() }) {
                Icon(Icons.Outlined.ArrowBackIosNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(folders.firstOrNull { it.id == folderId }?.name ?: "Notes")
            }
            Spacer(Modifier.weight(1f))
            val tint = MaterialTheme.colorScheme.primary
            IconButton({
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, plain(latest.text))
                }, null))
            }) { Icon(Icons.Outlined.Share, "Share", tint = tint) }
            IconButton({
                scope.launch {
                    vm.save(session, latest.text, latestFolder); shownId = session.id
                    if (session.id != 0L) vm.togglePin(session.id)
                }
            }) { Icon(Icons.Outlined.PushPin, "Pin", tint = if (pinned) tint else muted()) }
            IconButton({ scope.launch { vm.discard(session); vm.relock(session.id); onClose() } }) { Icon(Icons.Outlined.Delete, "Delete", tint = tint) }
            Box {
                IconButton({ moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = tint) }
                DropdownMenu(moreMenu, { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("Move to folder") }, leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                        onClick = { moreMenu = false; showMove = true })
                    DropdownMenuItem(
                        text = { Text(if (isLocked) "Remove Lock" else "Lock Note") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        onClick = {
                            moreMenu = false
                            val act = ctx.findActivity()
                            if (act != null) authenticate(act, if (isLocked) "Remove lock" else "Lock note", {
                                scope.launch {
                                    val lock = !isLocked
                                    vm.setLocked(session, latest.text, latestFolder, lock)
                                    shownId = session.id
                                    if (session.id != 0L) vm.unlocked.add(session.id)
                                    isLocked = lock
                                }
                            }, { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() })
                        })
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            BasicTextField(
                value = value,
                onValueChange = { value = autoContinue(value, it) },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = styler,
                onTextLayout = { layoutHolder[0] = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp).padding(16.dp).focusRequester(focus)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val up = waitForUpOrCancellation(PointerEventPass.Initial)
                            if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                                val layout = layoutHolder[0]
                                val toggled = if (layout != null) toggleCheckAt(latest, layout, down.position) else null
                                if (toggled != null) { value = toggled; up.consume() }
                            }
                        }
                    }
            )
        }

        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val tint = MaterialTheme.colorScheme.primary
                Box {
                    IconButton({ styleMenu = true }) { Icon(Icons.Outlined.TextFields, "Format", tint = tint) }
                    DropdownMenu(styleMenu, { styleMenu = false }) {
                        listOf("Title" to "# ", "Heading" to "## ", "Subheading" to "### ", "Body" to "", "Monospaced" to "` ").forEach { (label, p) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { value = applyPrefix(value, p); styleMenu = false })
                        }
                    }
                }
                StyleButton("B", bold = true, italic = false, underline = false, active = activeStyle(value, BOLD)) { value = toggleStyle(value, BOLD) }
                StyleButton("I", bold = false, italic = true, underline = false, active = activeStyle(value, ITALIC)) { value = toggleStyle(value, ITALIC) }
                StyleButton("U", bold = false, italic = false, underline = true, active = activeStyle(value, UNDERLINE)) { value = toggleStyle(value, UNDERLINE) }
                IconButton({ value = toggleCheck(value) }) { Icon(Icons.Outlined.CheckCircle, "Checklist", tint = tint) }
                IconButton({ value = applyPrefix(value, "• ") }) { Icon(Icons.Outlined.FormatListBulleted, "Bulleted list", tint = tint) }
                TextButton(onClick = { value = applyPrefix(value, "– ") }) { Text("–", fontSize = 22.sp) }
                IconButton({ value = applyPrefix(value, "1. ") }) { Icon(Icons.Outlined.FormatListNumbered, "Numbered list", tint = tint) }
                Spacer(Modifier.width(24.dp))
                TextButton(onClick = { fm.clearFocus() }) { Text("Done", fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (showMove) AlertDialog(
        onDismissRequest = { showMove = false },
        title = { Text("Move to folder") },
        text = {
            Column {
                folders.forEach { f ->
                    TextButton(onClick = {
                        showMove = false
                        scope.launch {
                            vm.save(session, latest.text, latestFolder)
                            folderId = f.id
                            if (session.id != 0L) vm.move(session.id, f.id)
                        }
                    }) { Text(f.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showMove = false }) { Text("Cancel") } }
    )
}

@Composable
private fun StyleButton(label: String, bold: Boolean, italic: Boolean, underline: Boolean, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).size(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .22f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (underline) TextDecoration.Underline else null
        )
    }
}
