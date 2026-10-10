package com.example.applestylenotes

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val DEFAULT_FOLDER = "default"
const val ALL_NOTES = "all"
const val TRASH = "trash"
const val TAG_PREFIX = "tag:"
const val TRASH_DAYS = 30

enum class SortMode(val label: String) {
    EDITED("Date Edited"),
    CREATED("Date Created"),
    TITLE("Title")
}

data class Folder(val id: String, val name: String)

data class Note(
    val id: String,
    val folderId: String = DEFAULT_FOLDER,
    val text: String = "",
    val spans: String = "[]",
    val attachments: List<String> = emptyList(),
    val pinned: Boolean = false,
    val locked: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long = 0L,
) {
    val isDeleted: Boolean get() = deletedAt != 0L

    private fun contentLines() = text.lineSequence()
        .map { LIST_RE.replace(it, "").trim() }
        .filter { it.isNotEmpty() }

    val title: String
        get() = contentLines().firstOrNull()
            ?: if (attachments.isNotEmpty()) "Photo" else "New Note"

    val preview: String
        get() = contentLines().drop(1).firstOrNull()
            ?: if (attachments.isNotEmpty()) "Photo" else "No additional text"
}

data class NotesData(val folders: List<Folder>, val notes: List<Note>)

data class Section(val header: String?, val notes: List<Note>)

private val TAG_RE = Regex("(?<![\\p{L}\\p{N}_#])#([\\p{L}\\p{N}_]+)")

fun tagsOf(text: String): List<String> =
    TAG_RE.findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()

fun notesFor(data: NotesData, folderId: String): List<Note> = when {
    folderId == TRASH -> data.notes.filter { it.isDeleted }
    folderId == ALL_NOTES -> data.notes.filter { !it.isDeleted }
    folderId.startsWith(TAG_PREFIX) -> {
        val tag = folderId.removePrefix(TAG_PREFIX)
        data.notes.filter { !it.isDeleted && !it.locked && tag in tagsOf(it.text) }
    }
    else -> data.notes.filter { !it.isDeleted && it.folderId == folderId }
}

fun matches(note: Note, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (note.title.lowercase().contains(q)) return true
    return !note.locked && note.text.lowercase().contains(q)
}

fun sectionFor(ts: Long, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val startToday = c.timeInMillis
    if (ts >= startToday) return "Today"
    c.timeInMillis = startToday; c.add(Calendar.DAY_OF_YEAR, -1)
    if (ts >= c.timeInMillis) return "Yesterday"
    c.timeInMillis = startToday; c.add(Calendar.DAY_OF_YEAR, -7)
    if (ts >= c.timeInMillis) return "Previous 7 Days"
    c.timeInMillis = startToday; c.add(Calendar.DAY_OF_YEAR, -30)
    if (ts >= c.timeInMillis) return "Previous 30 Days"
    val thisYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
    val noteYear = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)
    return if (noteYear == thisYear) SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(ts))
    else noteYear.toString()
}

fun rowDate(ts: Long): String {
    val cat = sectionFor(ts)
    return when (cat) {
        "Today" -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
        "Yesterday" -> "Yesterday"
        "Previous 7 Days" -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ts))
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
    }
}

fun groupNotes(notes: List<Note>, sort: SortMode, inTrash: Boolean): List<Section> {
    if (notes.isEmpty()) return emptyList()
    fun key(n: Note) = if (sort == SortMode.CREATED) n.createdAt else n.updatedAt
    val comparator: Comparator<Note> = when {
        inTrash -> compareByDescending<Note> { it.deletedAt }
        sort == SortMode.TITLE -> compareBy<Note> { it.title.lowercase() }
        else -> compareByDescending<Note> { key(it) }
    }
    val sorted = notes.sortedWith(comparator)
    val out = ArrayList<Section>()
    val pinned = if (inTrash) emptyList() else sorted.filter { it.pinned }
    if (pinned.isNotEmpty()) out.add(Section("Pinned", pinned))
    val rest = if (inTrash) sorted else sorted.filter { !it.pinned }
    if (sort == SortMode.TITLE && !inTrash) {
        if (rest.isNotEmpty()) out.add(Section(if (pinned.isNotEmpty()) "Notes" else null, rest))
    } else {
        val groups = LinkedHashMap<String, MutableList<Note>>()
        for (n in rest) {
            val ts = if (inTrash) n.deletedAt else key(n)
            groups.getOrPut(sectionFor(ts)) { mutableListOf() }.add(n)
        }
        groups.forEach { (h, l) -> out.add(Section(h, l)) }
    }
    return out
}
