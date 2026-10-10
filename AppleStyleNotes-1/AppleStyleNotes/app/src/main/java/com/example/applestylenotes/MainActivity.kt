package com.example.applestylenotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Folders : Screen
    data class NoteList(val folderId: String) : Screen
    data class Editor(val noteId: String) : Screen
}

class NavVM : ViewModel() {
    var stack by mutableStateOf<List<Screen>>(listOf(Screen.Folders))
        private set
    var unlocked by mutableStateOf<Set<String>>(emptySet())
        private set

    var pendingExport: List<Note> = emptyList()
    var pendingSkipped: Int = 0

    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun unlock(id: String) { unlocked = unlocked + id }
    fun relock(id: String) { unlocked = unlocked - id }
    fun lockAll() { unlocked = emptySet() }
}

interface AppActions {
    fun authenticate(title: String, onSuccess: () -> Unit)
    fun exportPdf(name: String, notes: List<Note>, skippedLocked: Int = 0)
    fun shareText(subject: String, text: String)
    fun toast(msg: String)
}

class MainActivity : FragmentActivity(), AppActions {
    private val vm: NavVM by viewModels()
    private lateinit var store: NotesStore

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) writePdf(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = (application as NotesApp).store
        setContent {
            NotesTheme {
                NotesRoot(vm, store, this@MainActivity)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        store.flushSync()
        if (!isChangingConfigurations) vm.lockAll()
    }

    // ---------- AppActions ----------

    override fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun authenticate(title: String, onSuccess: () -> Unit) {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            toast("Set up a screen lock (PIN, pattern, password or fingerprint) on this phone to use locked notes.")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(auth)
            .build()
        prompt.authenticate(info)
    }

    override fun exportPdf(name: String, notes: List<Note>, skippedLocked: Int) {
        if (notes.isEmpty()) {
            toast("There are no notes to export.")
            return
        }
        vm.pendingExport = notes
        vm.pendingSkipped = skippedLocked
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Notes" }
        exportLauncher.launch("$safe.pdf")
    }

    private fun writePdf(uri: Uri) {
        val notes = vm.pendingExport
        val skipped = vm.pendingSkipped
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        PdfExporter.write(out, notes, store.attachDir)
                    } != null
                } catch (e: Throwable) {
                    false
                }
            }
            if (ok) {
                val extra = if (skipped > 0) " ($skipped locked note${if (skipped == 1) "" else "s"} skipped)" else ""
                toast("PDF saved$extra")
            } else {
                toast("Couldn't save the PDF.")
            }
        }
    }

    override fun shareText(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, null))
    }
}

@Composable
fun NotesRoot(nav: NavVM, store: NotesStore, actions: AppActions) {
    val data by store.data.collectAsState()
    val top = nav.stack.last()

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    // If the app was backgrounded, a locked note that is open must be unlocked again.
    LaunchedEffect(top, nav.unlocked, data) {
        if (top is Screen.Editor) {
            val n = data.notes.firstOrNull { it.id == top.noteId }
            if (n != null && n.locked && n.id !in nav.unlocked) nav.pop()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = nav.stack,
            contentKey = { it.last() },
            transitionSpec = {
                if (targetState.size > initialState.size) {
                    slideInHorizontally(tween(260)) { it } togetherWith
                        slideOutHorizontally(tween(260)) { -it / 3 }
                } else {
                    slideInHorizontally(tween(260)) { -it / 3 } togetherWith
                        slideOutHorizontally(tween(260)) { it }
                }
            },
            label = "nav"
        ) { stack ->
            when (val s = stack.last()) {
                is Screen.Folders -> FoldersScreen(nav, store, actions)
                is Screen.NoteList -> NoteListScreen(s.folderId, nav, store, actions)
                is Screen.Editor -> EditorScreen(s.noteId, nav, store, actions)
            }
        }
    }
}
