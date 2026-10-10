package com.example.notes

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders")
data class Folder(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val folderId: Long = 1,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val locked: Boolean = false
)

private val markerRegex = Regex("^(#{1,3} |` |• |– |☐ |☑ |\\d+\\. )")
fun prefixOf(line: String): String = markerRegex.find(line)?.value ?: ""
fun stripMarkers(line: String): String = plain(line.removePrefix(prefixOf(line)))

fun noteTitle(n: Note): String = if (n.locked) "Locked Note" else
    n.text.lineSequence().firstOrNull { plain(it).isNotBlank() }?.let { stripMarkers(it).trim() }?.ifBlank { null } ?: "New Note"

fun notePreview(n: Note): String = if (n.locked) "This note is locked" else
    n.text.lines().filter { plain(it).isNotBlank() }.getOrNull(1)?.let { stripMarkers(it).trim() } ?: "No additional text"

@Dao
interface NotesDao {
    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC") fun allNotes(): Flow<List<Note>>
    @Query("SELECT * FROM folders ORDER BY id") fun folders(): Flow<List<Folder>>
    @Query("SELECT * FROM notes") suspend fun allOnce(): List<Note>
    @Query("SELECT * FROM folders") suspend fun foldersOnce(): List<Folder>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun get(id: Long): Note?
    @Insert suspend fun insert(n: Note): Long
    @Update suspend fun update(n: Note)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteForever(id: Long)
    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff") suspend fun purge(cutoff: Long)
    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL") suspend fun emptyTrash()
    @Insert suspend fun insertFolder(f: Folder): Long
    @Update suspend fun updateFolder(f: Folder)
    @Query("DELETE FROM folders WHERE id = :id") suspend fun deleteFolder(id: Long)
    @Query("UPDATE notes SET deletedAt = COALESCE(deletedAt, :now), folderId = 1, pinned = 0 WHERE folderId = :fid")
    suspend fun trashFolderNotes(fid: Long, now: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [Folder::class, Note::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): NotesDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "notes.db")
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT INTO folders (id, name) VALUES (1, 'Notes')")
                    }
                }).build().also { inst = it }
        }
    }
}
