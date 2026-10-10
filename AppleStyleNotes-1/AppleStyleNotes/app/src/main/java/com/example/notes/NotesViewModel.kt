package com.example.notes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One open editor. Holds the DB id once the note exists and what was last written. */
class EditSession(var id: Long, var lastText: String?, var locked: Boolean) { var closed = false }

class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    private val mutex = Mutex()

    /** null until the first load from the database has finished. */
    val notes: StateFlow<List<Note>?> = dao.allNotes().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val folders: StateFlow<List<Folder>> = dao.folders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Locked notes the user has unlocked in this session (cleared when the editor closes). */
    val unlocked = mutableSetOf<Long>()
    fun relock(id: Long) { unlocked.remove(id) }

    init {
        // Recently Deleted keeps notes for 30 days, then they are removed for good.
        viewModelScope.launch { dao.purge(System.currentTimeMillis() - 30L * 86_400_000L) }
    }

    private suspend fun persist(s: EditSession, text: String, folderId: Long) {
        if (s.closed) return
        val stored = { if (s.locked) Crypto.encrypt(text) else text }
        val now = System.currentTimeMillis()
        if (s.id == 0L) {
            if (text.isBlank()) return
            s.id = dao.insert(Note(text = stored(), folderId = folderId, createdAt = now, updatedAt = now, locked = s.locked))
            s.lastText = text
        } else {
            if (text == s.lastText) return
            val cur = dao.get(s.id) ?: return
            dao.update(cur.copy(text = stored(), locked = s.locked, updatedAt = now))
            s.lastText = text
        }
    }

    suspend fun save(s: EditSession, text: String, folderId: Long) =
        mutex.withLock { withContext(NonCancellable) { persist(s, text, folderId) } }

    fun saveAsync(s: EditSession, text: String, folderId: Long) {
        viewModelScope.launch { save(s, text, folderId) }
    }

    suspend fun setLocked(s: EditSession, text: String, folderId: Long, lock: Boolean) =
        mutex.withLock {
            withContext(NonCancellable) {
                s.locked = lock
                s.lastText = null   // force a rewrite in the new form
                persist(s, text, folderId)
            }
        }

    /** Called when the editor closes. Empty notes are removed, like Apple Notes. */
    fun finish(s: EditSession, text: String, folderId: Long) {
        viewModelScope.launch {
            mutex.withLock {
                if (text.isBlank()) { if (s.id != 0L) dao.deleteForever(s.id) } else persist(s, text, folderId)
                s.closed = true
            }
        }
    }

    /** Delete from inside the editor: note goes to Recently Deleted. */
    suspend fun discard(s: EditSession) = mutex.withLock {
        s.closed = true
        if (s.id != 0L) dao.get(s.id)?.let { dao.update(it.copy(deletedAt = System.currentTimeMillis(), pinned = false)) }
    }

    fun trash(id: Long) = viewModelScope.launch {
        dao.get(id)?.let { dao.update(it.copy(deletedAt = System.currentTimeMillis(), pinned = false)) }
    }
    fun restore(id: Long) = viewModelScope.launch { dao.get(id)?.let { dao.update(it.copy(deletedAt = null)) } }
    fun deleteForever(id: Long) = viewModelScope.launch { dao.deleteForever(id) }
    fun emptyTrash() = viewModelScope.launch { dao.emptyTrash() }
    fun togglePin(id: Long) = viewModelScope.launch { dao.get(id)?.let { dao.update(it.copy(pinned = !it.pinned)) } }
    fun move(id: Long, folderId: Long) = viewModelScope.launch { dao.get(id)?.let { dao.update(it.copy(folderId = folderId)) } }

    fun addFolder(name: String) = viewModelScope.launch { dao.insertFolder(Folder(name = name)) }
    fun renameFolder(f: Folder, name: String) = viewModelScope.launch { dao.updateFolder(f.copy(name = name)) }
    fun deleteFolder(f: Folder) = viewModelScope.launch {
        if (f.id == 1L) return@launch
        dao.trashFolderNotes(f.id, System.currentTimeMillis())
        dao.deleteFolder(f.id)
    }

    // ------------------------------------------------------------ backup / restore

    /** Writes every note except locked ones to [uri]. Returns (notes written, locked notes left out). */
    suspend fun backupTo(uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val all = dao.allOnce()
        val keep = all.filter { !it.locked }
        val root = JSONObject().put("app", "AppleStyleNotes").put("version", 1).put("exportedAt", System.currentTimeMillis())
        val fa = JSONArray()
        dao.foldersOnce().forEach { fa.put(JSONObject().put("id", it.id).put("name", it.name)) }
        root.put("folders", fa)
        val na = JSONArray()
        keep.forEach { n ->
            na.put(JSONObject()
                .put("text", n.text).put("folderId", n.folderId).put("pinned", n.pinned)
                .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt)
                .put("deletedAt", n.deletedAt ?: JSONObject.NULL))
        }
        root.put("notes", na)
        val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
            ?: error("Can't write to that location")
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        keep.size to (all.size - keep.size)
    }

    /** Adds notes from a backup file. Notes already on the phone are skipped. Returns (added, skipped). */
    suspend fun restoreFrom(uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: error("Can't read that file")
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        require(root.optString("app") == "AppleStyleNotes") { "Not a backup from this app" }

        val existing = dao.foldersOnce().toMutableList()
        val folderMap = HashMap<Long, Long>()
        val fa = root.getJSONArray("folders")
        for (i in 0 until fa.length()) {
            val o = fa.getJSONObject(i)
            val name = o.getString("name")
            val match = existing.firstOrNull { it.name == name }
            val id = match?.id ?: dao.insertFolder(Folder(name = name)).also { existing.add(Folder(it, name)) }
            folderMap[o.getLong("id")] = id
        }

        val have = dao.allOnce().filter { !it.locked }.map { it.createdAt to it.text }.toHashSet()
        var added = 0
        var skipped = 0
        val na = root.getJSONArray("notes")
        for (i in 0 until na.length()) {
            val o = na.getJSONObject(i)
            val t = o.getString("text")
            val created = o.getLong("createdAt")
            if ((created to t) in have) { skipped++; continue }
            dao.insert(Note(
                text = t,
                folderId = folderMap[o.getLong("folderId")] ?: 1L,
                pinned = o.optBoolean("pinned"),
                createdAt = created,
                updatedAt = o.getLong("updatedAt"),
                deletedAt = if (o.isNull("deletedAt")) null else o.getLong("deletedAt")
            ))
            added++
        }
        added to skipped
    }
}
