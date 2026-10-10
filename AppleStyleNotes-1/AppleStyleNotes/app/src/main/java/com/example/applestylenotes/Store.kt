package com.example.applestylenotes

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * All notes live in one JSON file inside the app's private storage
 * (files/notes.json). Photos live in files/attachments/.
 * Deleting a note only moves it to "Recently Deleted" (kept 30 days).
 */
class NotesStore(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "notes.json")
    val attachDir = File(app.filesDir, "attachments").also { it.mkdirs() }
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val _data = MutableStateFlow(NotesData(emptyList(), emptyList()))
    val data: StateFlow<NotesData> = _data
    private var seq = 0L
    private var written = 0L

    var sort by mutableStateOf(
        runCatching { SortMode.valueOf(prefs.getString("sort", "EDITED") ?: "EDITED") }
            .getOrDefault(SortMode.EDITED)
    )
        private set

    var gallery by mutableStateOf(prefs.getBoolean("gallery", false))
        private set

    private val saveTask = Runnable {
        val s = snapshot()
        io.execute { write(s.first, s.second) }
    }

    init {
        load()
        purge()
    }

    fun setSort(m: SortMode) {
        sort = m
        prefs.edit().putString("sort", m.name).apply()
    }

    fun setGallery(g: Boolean) {
        gallery = g
        prefs.edit().putBoolean("gallery", g).apply()
    }

    // ---------- mutations (call on main thread) ----------

    private fun update(block: (NotesData) -> NotesData) {
        _data.value = block(_data.value)
        handler.removeCallbacks(saveTask)
        handler.postDelayed(saveTask, 500)
    }

    private fun mapNote(id: String, f: (Note) -> Note) = update { d ->
        d.copy(notes = d.notes.map { if (it.id == id) f(it) else it })
    }

    fun newNote(folderId: String): Note {
        val fid = if (folderId == DEFAULT_FOLDER || _data.value.folders.any { it.id == folderId }) folderId
        else DEFAULT_FOLDER
        val now = System.currentTimeMillis()
        val n = Note(id = UUID.randomUUID().toString(), folderId = fid, createdAt = now, updatedAt = now)
        update { it.copy(notes = it.notes + n) }
        return n
    }

    fun updateContent(id: String, text: String, spans: String) {
        val cur = _data.value.notes.firstOrNull { it.id == id } ?: return
        if (cur.text == text && cur.spans == spans) return
        mapNote(id) { it.copy(text = text, spans = spans, updatedAt = System.currentTimeMillis()) }
    }

    fun setPinned(id: String, v: Boolean) = mapNote(id) { it.copy(pinned = v) }
    fun setLocked(id: String, v: Boolean) = mapNote(id) { it.copy(locked = v) }
    fun moveNote(id: String, folderId: String) = mapNote(id) { it.copy(folderId = folderId) }

    fun trash(id: String) = mapNote(id) { it.copy(deletedAt = System.currentTimeMillis(), pinned = false) }

    fun restore(id: String) {
        val folders = _data.value.folders
        mapNote(id) {
            val fid = if (it.folderId == DEFAULT_FOLDER || folders.any { f -> f.id == it.folderId }) it.folderId
            else DEFAULT_FOLDER
            it.copy(deletedAt = 0L, folderId = fid)
        }
    }

    fun restoreAll() {
        _data.value.notes.filter { it.isDeleted }.forEach { restore(it.id) }
    }

    fun deleteForever(id: String) {
        val n = _data.value.notes.firstOrNull { it.id == id } ?: return
        n.attachments.forEach { File(attachDir, it).delete() }
        update { it.copy(notes = it.notes.filter { x -> x.id != id }) }
    }

    fun emptyTrash() {
        _data.value.notes.filter { it.isDeleted }.forEach { deleteForever(it.id) }
    }

    /** Apple-style: a note you opened and left empty disappears instead of cluttering the list. */
    fun discardIfEmpty(id: String) {
        val n = _data.value.notes.firstOrNull { it.id == id } ?: return
        if (n.text.isBlank() && n.attachments.isEmpty()) deleteForever(id)
    }

    fun addAttachment(id: String, name: String) =
        mapNote(id) { it.copy(attachments = it.attachments + name, updatedAt = System.currentTimeMillis()) }

    fun removeAttachment(id: String, name: String) {
        mapNote(id) { it.copy(attachments = it.attachments - name, updatedAt = System.currentTimeMillis()) }
        File(attachDir, name).delete()
    }

    fun addFolder(name: String): Folder {
        val f = Folder(UUID.randomUUID().toString(), name)
        update { it.copy(folders = it.folders + f) }
        return f
    }

    fun renameFolder(id: String, name: String) = update { d ->
        d.copy(folders = d.folders.map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Notes inside go to Recently Deleted (recoverable), like Apple Notes. */
    fun deleteFolder(id: String) {
        val now = System.currentTimeMillis()
        update { d ->
            d.copy(
                folders = d.folders.filter { it.id != id },
                notes = d.notes.map {
                    if (it.folderId == id && !it.isDeleted) it.copy(deletedAt = now, pinned = false) else it
                }
            )
        }
    }

    // ---------- persistence ----------

    private fun purge() {
        val now = System.currentTimeMillis()
        val limit = TRASH_DAYS * 86_400_000L
        val all = _data.value.notes
        val keep = all.filter { n ->
            val expired = n.isDeleted && now - n.deletedAt > limit
            val empty = !n.isDeleted && n.text.isBlank() && n.attachments.isEmpty()
            !(expired || empty)
        }
        if (keep.size != all.size) {
            val keepIds = keep.map { it.id }.toSet()
            all.filter { it.id !in keepIds }.forEach { n -> n.attachments.forEach { File(attachDir, it).delete() } }
            update { it.copy(notes = keep) }
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            val fs = o.optJSONArray("folders") ?: JSONArray()
            val ns = o.optJSONArray("notes") ?: JSONArray()
            val folders = (0 until fs.length()).map {
                val f = fs.getJSONObject(it)
                Folder(f.getString("id"), f.getString("name"))
            }
            val notes = (0 until ns.length()).map {
                val n = ns.getJSONObject(it)
                val att = n.optJSONArray("attachments") ?: JSONArray()
                Note(
                    id = n.getString("id"),
                    folderId = n.optString("folderId", DEFAULT_FOLDER),
                    text = n.optString("text", ""),
                    spans = n.optString("spans", "[]"),
                    attachments = (0 until att.length()).map { i -> att.getString(i) },
                    pinned = n.optBoolean("pinned", false),
                    locked = n.optBoolean("locked", false),
                    createdAt = n.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = n.optLong("updatedAt", System.currentTimeMillis()),
                    deletedAt = n.optLong("deletedAt", 0L),
                )
            }
            _data.value = NotesData(folders, notes)
        } catch (e: Exception) {
            // Never lose data: keep a copy of the unreadable file.
            try {
                file.copyTo(File(app.filesDir, "notes.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            } catch (e2: Exception) {
            }
        }
    }

    private fun toJson(d: NotesData): String {
        val o = JSONObject()
        o.put("folders", JSONArray().apply {
            d.folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        })
        o.put("notes", JSONArray().apply {
            d.notes.forEach { n ->
                put(
                    JSONObject()
                        .put("id", n.id).put("folderId", n.folderId)
                        .put("text", n.text).put("spans", n.spans)
                        .put("attachments", JSONArray(n.attachments))
                        .put("pinned", n.pinned).put("locked", n.locked)
                        .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt)
                        .put("deletedAt", n.deletedAt)
                )
            }
        })
        return o.toString()
    }

    private fun snapshot(): Pair<Long, String> {
        seq++
        return seq to toJson(_data.value)
    }

    @Synchronized
    private fun write(n: Long, json: String) {
        if (n <= written) return
        try {
            val tmp = File(app.filesDir, "notes.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            written = n
        } catch (e: Exception) {
        }
    }

    /** Write to disk right now (called when the app goes to the background). */
    fun flushSync() {
        handler.removeCallbacks(saveTask)
        val s = snapshot()
        write(s.first, s.second)
    }
}
