package com.example.applestylenotes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ====================================================================== shared pieces

class ListCtx(
    val store: NotesStore,
    val nav: NavVM,
    val actions: AppActions,
    val askMove: (Note) -> Unit,
    val askForever: (Note) -> Unit,
)

fun openNote(n: Note, nav: NavVM, actions: AppActions) {
    if (n.locked && n.id !in nav.unlocked) {
        actions.authenticate("Unlock note") {
            nav.unlock(n.id)
            nav.push(Screen.Editor(n.id))
        }
    } else {
        nav.push(Screen.Editor(n.id))
    }
}

fun groupShape(first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) 12.dp else 0.dp,
    topEnd = if (first) 12.dp else 0.dp,
    bottomStart = if (last) 12.dp else 0.dp,
    bottomEnd = if (last) 12.dp else 0.dp,
)

@Composable
fun LargeTitle(text: String) {
    Text(
        text, fontSize = 34.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit) {
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp)
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, null, tint = gray, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search", color = gray, fontSize = 17.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, null, tint = gray, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupRow(
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Surface(shape = groupShape(first, last), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
                if (!last) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
fun BottomBar(left: @Composable () -> Unit, center: String, right: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { left() }
        Text(
            center, Modifier.weight(1f), textAlign = TextAlign.Center,
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { right() }
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                singleLine = true, placeholder = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onDone(text.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun MoveDialog(note: Note, folders: List<Folder>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to Folder") },
        text = {
            Column {
                (listOf(Folder(DEFAULT_FOLDER, "Notes")) + folders).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(f.id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (f.id == note.folderId) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun Thumb(store: NotesStore, name: String, size: Dp, modifier: Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) {
            decodeSampled(File(store.attachDir, name), px * 2)?.asImageBitmap()
        }
    }
    val b = bmp
    if (b != null) {
        Image(b, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
fun NoteMenu(note: Note, expanded: Boolean, dismiss: () -> Unit, c: ListCtx) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        if (note.isDeleted) {
            DropdownMenuItem(text = { Text("Recover") }, onClick = { dismiss(); c.store.restore(note.id) })
            DropdownMenuItem(text = { Text("Delete Permanently") }, onClick = { dismiss(); c.askForever(note) })
        } else {
            val open = !note.locked || note.id in c.nav.unlocked
            DropdownMenuItem(
                text = { Text(if (note.pinned) "Unpin Note" else "Pin Note") },
                onClick = { dismiss(); c.store.setPinned(note.id, !note.pinned) }
            )
            DropdownMenuItem(text = { Text("Move to Folder…") }, onClick = { dismiss(); c.askMove(note) })
            if (open) {
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = { dismiss(); c.actions.shareText(note.title, note.text) }
                )
                DropdownMenuItem(
                    text = { Text("Export as PDF") },
                    onClick = { dismiss(); c.actions.exportPdf(note.title, listOf(note)) }
                )
            }
            DropdownMenuItem(text = { Text("Delete") }, onClick = { dismiss(); c.store.trash(note.id) })
        }
    }
}

@Composable
fun NoteRow(
    note: Note,
    first: Boolean,
    last: Boolean,
    folderLabel: String?,
    store: NotesStore,
    onClick: () -> Unit,
    menu: (@Composable (Boolean, () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    GroupRow(first, last, onClick, if (menu != null) ({ open = true }) else null) {
        Column(Modifier.weight(1f)) {
            Text(
                note.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rowDate(if (note.isDeleted) note.deletedAt else note.updatedAt), color = gray, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (note.locked) "Locked" else note.preview,
                    color = gray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (folderLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, null, tint = gray, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(folderLabel, color = gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (note.pinned) {
            Icon(Icons.Outlined.PushPin, null, tint = gray, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        if (note.locked) {
            Icon(Icons.Outlined.Lock, null, tint = gray, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        } else {
            note.attachments.firstOrNull()?.let {
                Spacer(Modifier.width(8.dp))
                Thumb(store, it, 44.dp, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
            }
        }
        menu?.invoke(open) { open = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    store: NotesStore,
    onClick: () -> Unit,
    menu: @Composable (Boolean, () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .padding(6.dp)
            .height(170.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = { open = true })
    ) {
        Column(Modifier.padding(12.dp)) {
            val img = if (note.locked) null else note.attachments.firstOrNull()
            if (img != null) {
                Thumb(store, img, 80.dp, Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(6.dp))
            }
            Text(
                note.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (note.locked) "Locked" else note.preview,
                color = gray, fontSize = 13.sp, maxLines = if (img != null) 1 else 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rowDate(if (note.isDeleted) note.deletedAt else note.updatedAt),
                    color = gray, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
                if (note.pinned) Icon(Icons.Outlined.PushPin, null, tint = gray, modifier = Modifier.size(14.dp))
                if (note.locked) Icon(Icons.Outlined.Lock, null, tint = gray, modifier = Modifier.size(14.dp))
            }
            menu(open) { open = false }
        }
    }
}

// ====================================================================== folders screen

private class FolderRowData(val id: String, val name: String, val count: Int, val custom: Boolean)

@Composable
fun FoldersScreen(nav: NavVM, store: NotesStore, actions: AppActions) {
    val data by store.data.collectAsState()
    var query by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<Folder?>(null) }
    var deleteFor by remember { mutableStateOf<Folder?>(null) }

    val live = data.notes.filter { !it.isDeleted }
    val trashCount = data.notes.count { it.isDeleted }
    val tags = remember(data) {
        live.filter { !it.locked }
            .flatMap { tagsOf(it.text) }
            .groupingBy { it }.eachCount()
            .toSortedMap().toList()
    }
    val rows = listOf(
        FolderRowData(ALL_NOTES, "All Notes", live.size, false),
        FolderRowData(DEFAULT_FOLDER, "Notes", live.count { it.folderId == DEFAULT_FOLDER }, false),
    ) + data.folders.map { f -> FolderRowData(f.id, f.name, live.count { it.folderId == f.id }, true) }

    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "title") { Spacer(Modifier.height(48.dp)); LargeTitle("Folders") }
            item(key = "search") { SearchField(query) { query = it } }

            if (query.isNotBlank()) {
                val results = live.filter { matches(it, query) }.sortedByDescending { it.updatedAt }
                if (results.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "No Results", color = gray, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(32.dp)
                        )
                    }
                } else {
                    item(key = "rh") { SectionHeader("Notes") }
                    itemsIndexed(results, key = { _, n -> "r" + n.id }) { i, n ->
                        NoteRow(
                            n, i == 0, i == results.lastIndex,
                            if (n.folderId == DEFAULT_FOLDER) "Notes"
                            else data.folders.firstOrNull { it.id == n.folderId }?.name,
                            store, { openNote(n, nav, actions) }
                        )
                    }
                }
            } else {
                item(key = "h1") { SectionHeader("On My Device") }
                itemsIndexed(rows, key = { _, r -> "f" + r.id }) { i, r ->
                    var open by remember { mutableStateOf(false) }
                    GroupRow(
                        i == 0, i == rows.lastIndex,
                        { nav.push(Screen.NoteList(r.id)) },
                        if (r.custom) ({ open = true }) else null
                    ) {
                        Icon(Icons.Outlined.Folder, null, tint = primary)
                        Spacer(Modifier.width(12.dp))
                        Text(r.name, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.count.toString(), color = gray, fontSize = 17.sp)
                        Icon(Icons.Default.ChevronRight, null, tint = gray)
                        if (r.custom) {
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                    open = false; renameFor = Folder(r.id, r.name)
                                })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                    open = false; deleteFor = Folder(r.id, r.name)
                                })
                            }
                        }
                    }
                }
                if (tags.isNotEmpty()) {
                    item(key = "h2") { SectionHeader("Tags") }
                    itemsIndexed(tags, key = { _, t -> "t" + t.first }) { i, t ->
                        GroupRow(i == 0, i == tags.lastIndex, { nav.push(Screen.NoteList(TAG_PREFIX + t.first)) }, null) {
                            Icon(Icons.Outlined.Tag, null, tint = primary)
                            Spacer(Modifier.width(12.dp))
                            Text("#" + t.first, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.second.toString(), color = gray, fontSize = 17.sp)
                            Icon(Icons.Default.ChevronRight, null, tint = gray)
                        }
                    }
                }
                item(key = "sp") { Spacer(Modifier.height(24.dp)) }
                item(key = "trash") {
                    GroupRow(true, true, { nav.push(Screen.NoteList(TRASH)) }, null) {
                        Icon(Icons.Outlined.Delete, null, tint = primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Recently Deleted", Modifier.weight(1f), fontSize = 17.sp)
                        Text(trashCount.toString(), color = gray, fontSize = 17.sp)
                        Icon(Icons.Default.ChevronRight, null, tint = gray)
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
        BottomBar(
            left = {
                IconButton(onClick = { showNew = true }) {
                    Icon(Icons.Outlined.CreateNewFolder, "New Folder", tint = primary)
                }
            },
            center = "",
            right = {
                IconButton(onClick = {
                    val n = store.newNote(DEFAULT_FOLDER)
                    nav.push(Screen.Editor(n.id))
                }) { Icon(Icons.Outlined.EditNote, "New Note", tint = primary) }
            }
        )
    }

    if (showNew) {
        NameDialog("New Folder", "", "Save", { store.addFolder(it); showNew = false }, { showNew = false })
    }
    renameFor?.let { f ->
        NameDialog("Rename Folder", f.name, "Save", { store.renameFolder(f.id, it); renameFor = null }, { renameFor = null })
    }
    deleteFor?.let { f ->
        ConfirmDialog(
            "Delete \"${f.name}\"?",
            "Notes in this folder will be moved to Recently Deleted, where you can recover them for $TRASH_DAYS days.",
            "Delete",
            { store.deleteFolder(f.id); deleteFor = null },
            { deleteFor = null }
        )
    }
}

// ====================================================================== note list screen

@Composable
fun NoteListScreen(folderId: String, nav: NavVM, store: NotesStore, actions: AppActions) {
    val data by store.data.collectAsState()
    val inTrash = folderId == TRASH
    val isCustom = data.folders.any { it.id == folderId }
    val folderName = when {
        folderId == ALL_NOTES -> "All Notes"
        inTrash -> "Recently Deleted"
        folderId == DEFAULT_FOLDER -> "Notes"
        folderId.startsWith(TAG_PREFIX) -> "#" + folderId.removePrefix(TAG_PREFIX)
        else -> data.folders.firstOrNull { it.id == folderId }?.name ?: "Notes"
    }
    val base = remember(data, folderId) { notesFor(data, folderId) }
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) base else base.filter { matches(it, query) }
    val sort = store.sort
    val gallery = store.gallery
    val sections = remember(shown, sort, inTrash) { groupNotes(shown, sort, inTrash) }

    var menuOpen by remember { mutableStateOf(false) }
    var moveFor by remember { mutableStateOf<Note?>(null) }
    var foreverFor by remember { mutableStateOf<Note?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }

    val ctx = ListCtx(store, nav, actions, { moveFor = it }, { foreverFor = it })
    val showFolder = folderId == ALL_NOTES || query.isNotBlank()
    fun folderLabel(n: Note): String? =
        if (!showFolder) null
        else if (n.folderId == DEFAULT_FOLDER) "Notes"
        else data.folders.firstOrNull { it.id == n.folderId }?.name

    val primary = MaterialTheme.colorScheme.primary
    val gray = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // top bar
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.pop() }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ChevronLeft, null, tint = primary, modifier = Modifier.size(28.dp))
                Text("Folders", color = primary, fontSize = 17.sp)
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreHoriz, "More", tint = primary) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (inTrash) {
                        DropdownMenuItem(text = { Text("Recover All") }, onClick = { menuOpen = false; store.restoreAll() })
                        DropdownMenuItem(text = { Text("Delete All") }, onClick = { menuOpen = false; confirmAll = true })
                    } else {
                        DropdownMenuItem(
                            text = { Text(if (gallery) "View as List" else "View as Gallery") },
                            onClick = { menuOpen = false; store.setGallery(!gallery) }
                        )
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text((if (sort == m) "✓  " else "     ") + "Sort by " + m.label) },
                                onClick = { menuOpen = false; store.setSort(m) }
                            )
                        }
                        DropdownMenuItem(text = { Text("Export Folder as PDF") }, onClick = {
                            menuOpen = false
                            val exportable = base.filter { !it.locked }.sortedByDescending { it.updatedAt }
                            actions.exportPdf(folderName.removePrefix("#"), exportable, base.size - exportable.size)
                        })
                        if (isCustom) {
                            DropdownMenuItem(text = { Text("Rename Folder") }, onClick = { menuOpen = false; rename = true })
                        }
                    }
                }
            }
        }

        val header: @Composable () -> Unit = {
            LargeTitle(folderName)
            SearchField(query) { query = it }
            if (inTrash) {
                Text(
                    "Notes are kept here for $TRASH_DAYS days, then deleted permanently. Tap and hold a note to recover it.",
                    color = gray, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
        }

        if (!gallery) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item(key = "header") { Column { header() } }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (query.isBlank()) "No Notes" else "No Results",
                            color = gray, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(48.dp)
                        )
                    }
                }
                sections.forEach { sec ->
                    if (sec.header != null) item(key = "s:" + sec.header) { SectionHeader(sec.header) }
                    itemsIndexed(sec.notes, key = { _, n -> n.id }) { i, n ->
                        NoteRow(
                            n, i == 0, i == sec.notes.lastIndex, folderLabel(n), store,
                            { openNote(n, nav, actions) },
                            { exp, dis -> NoteMenu(n, exp, dis, ctx) }
                        )
                    }
                    item(key = "gap:" + sec.header) { Spacer(Modifier.height(4.dp)) }
                }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Column { header() } }
                if (shown.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (query.isBlank()) "No Notes" else "No Results",
                            color = gray, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(48.dp)
                        )
                    }
                }
                sections.forEach { sec ->
                    if (sec.header != null) {
                        item(key = "s:" + sec.header, span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                sec.header, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 6.dp)
                            )
                        }
                    }
                    items(sec.notes, key = { it.id }) { n ->
                        NoteCard(n, store, { openNote(n, nav, actions) }) { exp, dis -> NoteMenu(n, exp, dis, ctx) }
                    }
                }
                item(key = "end", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
            }
        }

        BottomBar(
            left = {},
            center = if (shown.size == 1) "1 Note" else "${shown.size} Notes",
            right = {
                if (!inTrash) {
                    IconButton(onClick = {
                        val n = store.newNote(folderId)
                        nav.push(Screen.Editor(n.id))
                    }) { Icon(Icons.Outlined.EditNote, "New Note", tint = primary) }
                }
            }
        )
    }

    moveFor?.let { n ->
        MoveDialog(n, data.folders, { fid -> store.moveNote(n.id, fid); moveFor = null }, { moveFor = null })
    }
    foreverFor?.let { n ->
        ConfirmDialog(
            "Delete permanently?", "\"${n.title}\" will be deleted for good and can't be recovered.",
            "Delete", { store.deleteForever(n.id); foreverFor = null }, { foreverFor = null }
        )
    }
    if (confirmAll) {
        ConfirmDialog(
            "Delete all?", "Every note in Recently Deleted will be deleted permanently.",
            "Delete All", { store.emptyTrash(); confirmAll = false }, { confirmAll = false }
        )
    }
    if (rename) {
        NameDialog("Rename Folder", folderName, "Save", { store.renameFolder(folderId, it); rename = false }, { rename = false })
    }
}
