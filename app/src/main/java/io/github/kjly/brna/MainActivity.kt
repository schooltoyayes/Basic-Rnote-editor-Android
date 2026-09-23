package io.github.kjly.brna

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.kjly.brna.export.DocumentExporter
import io.github.kjly.brna.export.ExportFormat
import io.github.kjly.brna.export.ExportPrefs
import io.github.kjly.brna.export.ExportScope
import io.github.kjly.brna.model.BrushStyle
import io.github.kjly.brna.model.NativeBrushStroke
import io.github.kjly.brna.model.NativeCanvasElement
import io.github.kjly.brna.model.NativeVectorImageElement
import io.github.kjly.brna.model.NoteDocument
import io.github.kjly.brna.model.PaperStyle
import io.github.kjly.brna.model.Stroke
import io.github.kjly.brna.model.StrokePoint
import io.github.kjly.brna.model.ToolConfig
import io.github.kjly.brna.model.ToolType
import io.github.kjly.brna.model.ViewportState
import io.github.kjly.brna.storage.DocumentUri
import io.github.kjly.brna.storage.FileManager
import io.github.kjly.brna.storage.PdfImporter
import io.github.kjly.brna.storage.Recovery
import io.github.kjly.brna.storage.SettingsManager
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.kjly.brna.ui.canvas.DrawingCanvas
import io.github.kjly.brna.ui.components.ColorPicker
import io.github.kjly.brna.ui.components.ExportSheet
import io.github.kjly.brna.ui.components.PageSettingsSheet
import io.github.kjly.brna.ui.components.PenConfigStrip
import io.github.kjly.brna.ui.components.PenPicker
import io.github.kjly.brna.ui.components.RnoteTopBar
import io.github.kjly.brna.ui.theme.BabyRnoteTheme

class MainActivity : ComponentActivity() {

    private var performUndoAction: (() -> Unit)? = null
    private var performRedoAction: (() -> Unit)? = null

    // Storage Activity Launchers
    private var pendingDocumentToSave: NoteDocument? = null
    // Defaults to true since the app's whole purpose is desktop Rnote interop —
    // a brand-new note should save as .rnote, not fall back to our internal .json format.
    private var saveAsRnote: Boolean = true

    /**
     * The file this note came from and saves back to; null until it has been written once.
     *
     * Save used to launch the create-document picker every single time, so a note opened
     * from disk was saved as a *new* file — under whatever name the picker proposed, in
     * whatever folder it happened to open on — instead of back over itself.
     */
    private var currentDocumentUri: Uri? = null

    /**
     * Where the SAF picker should open. Without `EXTRA_INITIAL_URI` it starts wherever it
     * was last left, which for a first save is rarely anywhere near the note.
     */
    private var pickerStartUri: Uri? = null

    // Called after a save with what was written, so the UI can clear isModified — but only
    // if nothing changed while the write was running.
    private var onSaveSucceeded: ((NoteDocument) -> Unit)? = null

    /**
     * The open note when it has unsaved changes, null when there is nothing to protect.
     * Installed by the UI, read by autosave.
     */
    private var unsavedDocument: (() -> NoteDocument?)? = null

    /**
     * The file's last-modified time as of our own last open or save of it. A different
     * value at save time means somebody else wrote it in between — Toni on the laptop,
     * through a synced Drive folder — and saving over it would silently lose their work.
     */
    private var knownLastModified: Long? = null

    /** A save that found the file changed elsewhere, waiting for the user to decide. */
    private var pendingConflict by mutableStateOf<NoteDocument?>(null)

    /** A note recovered from the last session, waiting to be offered back. */
    private var pendingRecovery by mutableStateOf<Recovery.Pending?>(null)

    /** Set when [incomingDocument] is a recovered note, which is unsaved by definition. */
    private var incomingIsRecovered = false

    /** Set when the file's own name becomes the note's title (on open, and on save-as). */
    private var onTitleAdopted: ((String) -> Unit)? = null

    /**
     * Non-null while a file is being read or written off the main thread; shown as a
     * progress card. Reading a large .rnote (imported PDF pages are megabytes of SVG) on
     * the main thread froze the whole UI and could trip "app isn't responding".
     */
    private var busyMessage by mutableStateOf<String?>(null)

    /**
     * A note that has finished loading and waits to be handed to the UI. Going through
     * state rather than calling [onDocumentLoaded] directly matters for "Open with": that
     * load can finish before the first composition has installed the real handler.
     */
    private var incomingDocument by mutableStateOf<NoteDocument?>(null)

    /** Opens the create-document picker in the folder the note already lives in. */
    private inner class CreateDocumentNear(mimeType: String) :
        ActivityResultContracts.CreateDocument(mimeType) {
        override fun createIntent(context: Context, input: String): Intent =
            super.createIntent(context, input).apply {
                pickerStartUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
    }

    /** The same for opening, plus the write grant that saving back over the file needs. */
    private inner class OpenDocumentNear : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).apply {
                pickerStartUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
                addFlags(
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
    }

    /** Launcher for saving as our JSON format. */
    private val createDocumentLauncher = registerForActivityResult(
        CreateDocumentNear("application/json")
    ) { uri -> uri?.let { finishSaveAs(it, asRnote = false) } }

    /** Launcher for saving as native .rnote (GZIP+JSON). */
    private val createRnoteLauncher = registerForActivityResult(
        CreateDocumentNear("application/octet-stream")
    ) { uri -> uri?.let { finishSaveAs(it, asRnote = true) } }

    /** Where imported PDF pages go: page width, format height, and the top of the first. */
    private class PdfImportTarget(val pageWidth: Float, val formatHeight: Float, val startY: Float)

    /** Installed by the UI, which knows the note's format and where its content ends. */
    private var pdfImportTarget: (() -> PdfImportTarget)? = null

    /** Installed by the UI: adds imported pages to the open note. */
    private var onPdfImported: ((List<NativeVectorImageElement>) -> Unit)? = null

    private val importPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importPdf(it) } }

    /** Renders the PDF's pages off the main thread and adds them to the open note. */
    private fun importPdf(uri: Uri) {
        val target = pdfImportTarget?.invoke() ?: return
        if (busyMessage != null) return
        busyMessage = "Importing PDF…"
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) {
                try {
                    PdfImporter.import(
                        this@MainActivity, uri, target.pageWidth, target.formatHeight, target.startY
                    )
                } catch (e: Throwable) {
                    // A password-protected or broken PDF, or one too big to render.
                    e.printStackTrace()
                    null
                }
            }
            busyMessage = null
            if (pages.isNullOrEmpty()) {
                Toast.makeText(this@MainActivity, "Could not import the PDF", Toast.LENGTH_LONG).show()
            } else {
                onPdfImported?.invoke(pages)
                Toast.makeText(
                    this@MainActivity,
                    if (pages.size == 1) "Imported 1 page" else "Imported ${pages.size} pages",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        OpenDocumentNear()
    ) { uri -> uri?.let { openDocument(it) } }

    /**
     * Reads [uri] off the main thread and hands the note to the UI. Shared by the Open
     * picker and by "Open with" from other apps.
     */
    private fun openDocument(uri: Uri) {
        if (busyMessage != null) return
        busyMessage = "Opening…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    FileManager.loadDocumentFromUri(this@MainActivity, uri)
                        ?.let {
                            knownLastModified = DocumentUri.lastModified(this@MainActivity, uri)
                            it to DocumentUri.displayName(this@MainActivity, uri)
                        }
                } catch (e: Throwable) {
                    // OutOfMemoryError included: a file too big to hold is a failed open,
                    // not a crash.
                    e.printStackTrace()
                    null
                }
            }
            busyMessage = null
            if (result != null) {
                val (loaded, fileName) = result
                // The file's own name wins over the title inside it: a .rnote carries no
                // title at all, and our JSON's title is only what it was last renamed to.
                val title = fileName?.let(DocumentUri::titleFrom) ?: loaded.document.title
                // The bytes decide the format it saves back as. The old test was the
                // "Imported Note" placeholder title, which said nothing about the file.
                saveAsRnote = loaded.isNativeRnote
                adoptDocumentUri(uri)
                incomingDocument = loaded.document.copy(title = title)
                Toast.makeText(this@MainActivity, "Opened: $title", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Could not open file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** "Open with" from a file manager, Drive, a mail or chat app. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let { openDocument(it) }
    }

    /** This uri is now the note's home: save over it, and start the picker beside it. */
    private fun adoptDocumentUri(uri: Uri) {
        DocumentUri.takePersistablePermission(this, uri)
        currentDocumentUri = uri
        pickerStartUri = uri
    }

    /** Writes back over the file the note came from. False when it has no file yet. */
    private fun saveInPlace(document: NoteDocument, overwriteChanges: Boolean = false): Boolean {
        val target = currentDocumentUri ?: return false
        if (busyMessage != null) return true
        busyMessage = "Saving…"
        val asRnote = saveAsRnote
        lifecycleScope.launch {
            if (!overwriteChanges && withContext(Dispatchers.IO) { changedElsewhere(target) }) {
                busyMessage = null
                pendingConflict = document
                return@launch
            }
            val success = writeLock.withLock { withContext(Dispatchers.IO) { writeDocument(target, document, asRnote) } }
            busyMessage = null
            if (success) {
                afterSave(target, document)
                Toast.makeText(this@MainActivity, "Saved ${document.title}", Toast.LENGTH_SHORT).show()
            } else {
                // The grant can be gone (file deleted, card pulled, permission revoked, or
                // a read-only "Open with" grant), so fall back to asking for a destination
                // rather than losing the edits.
                currentDocumentUri = null
                Toast.makeText(
                    this@MainActivity, "Could not save over the file — choose a location", Toast.LENGTH_LONG
                ).show()
                launchSavePicker(document)
            }
        }
        return true
    }

    /** Blocking; call from [Dispatchers.IO]. */
    private fun changedElsewhere(uri: Uri): Boolean {
        val known = knownLastModified ?: return false
        val now = DocumentUri.lastModified(this, uri) ?: return false
        return now != known
    }

    /** Bookkeeping after any successful write of [document] to [uri]. */
    private suspend fun afterSave(uri: Uri, document: NoteDocument) {
        knownLastModified = withContext(Dispatchers.IO) {
            Recovery.clear(this@MainActivity)
            DocumentUri.lastModified(this@MainActivity, uri)
        }
        onSaveSucceeded?.invoke(document)
    }

    /**
     * Rnote saves on its own every couple of minutes; this does the same. The unsaved note
     * always goes to the private recovery copy first (fast, and it can't fail on a lost
     * file grant), then over its own file if it has one that nobody else has changed. It
     * never asks anything: a conflict or a failure just leaves the recovery copy and the
     * question for the next manual save.
     */
    private fun autosave() {
        val document = unsavedDocument?.invoke() ?: return
        val target = currentDocumentUri
        val asRnote = saveAsRnote
        lifecycleScope.launch {
            val wrote = withContext(Dispatchers.IO) {
                try {
                    Recovery.write(this@MainActivity, document, target, asRnote)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
                target != null && busyMessage == null && !changedElsewhere(target) &&
                    writeLock.withLock { writeDocument(target, document, asRnote) }
            }
            if (wrote && target != null) afterSave(target, document)
        }
    }

    /** One write to a file at a time: autosave and a manual save can otherwise overlap. */
    private val writeLock = Mutex()

    /** Blocking write; call from [Dispatchers.IO]. False on any failure, OOM included. */
    private fun writeDocument(uri: Uri, document: NoteDocument, asRnote: Boolean): Boolean = try {
        FileManager.saveDocumentToUri(this, uri, document, asRnote)
    } catch (e: Throwable) {
        e.printStackTrace()
        false
    }

    /** Asks for a destination, then saves there and adopts it. */
    private fun launchSavePicker(document: NoteDocument) {
        pendingDocumentToSave = document
        val safeTitle = document.title.ifBlank { "MyNote" }
        if (saveAsRnote) {
            createRnoteLauncher.launch("$safeTitle.rnote")
        } else {
            createDocumentLauncher.launch("$safeTitle.json")
        }
    }

    private fun finishSaveAs(uri: Uri, asRnote: Boolean) {
        val document = pendingDocumentToSave ?: return
        pendingDocumentToSave = null
        busyMessage = "Saving…"
        lifecycleScope.launch {
            val success = writeLock.withLock { withContext(Dispatchers.IO) { writeDocument(uri, document, asRnote) } }
            busyMessage = null
            if (!success) {
                Toast.makeText(this@MainActivity, "Save failed", Toast.LENGTH_SHORT).show()
                return@launch
            }
            saveAsRnote = asRnote
            adoptDocumentUri(uri)
            // The picker lets the name be edited, so the note takes the name it was actually
            // saved under — otherwise the title in the bar and the file on disk disagree.
            DocumentUri.displayName(this@MainActivity, uri)
                ?.let { onTitleAdopted?.invoke(DocumentUri.titleFrom(it)) }
            afterSave(uri, document)
            Toast.makeText(
                this@MainActivity,
                if (asRnote) "Saved as .rnote" else "Saved as .json",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    /**
     * One export request, parked between the sheet's Export button and the SAF result.
     * Rnote's export dialogs collect every answer and then pick a destination; SAF runs
     * the other way round, so the answers wait here until the destination comes back.
     */
    private data class PendingExport(
        val document: NoteDocument,
        val selection: List<Stroke>,
        val prefs: ExportPrefs,
        val baseName: String
    )

    private var pendingExport: PendingExport? = null

    // A CreateDocument contract is bound to one MIME type at registration, so there is a
    // launcher per format rather than one launcher with a variable type. They all start in
    // the note's own folder, like the save picker.
    private val exportSvgLauncher = registerForActivityResult(
        CreateDocumentNear(ExportFormat.SVG.mimeType)
    ) { uri -> uri?.let { finishSingleExport(it) } }

    private val exportPngLauncher = registerForActivityResult(
        CreateDocumentNear(ExportFormat.PNG.mimeType)
    ) { uri -> uri?.let { finishSingleExport(it) } }

    private val exportJpegLauncher = registerForActivityResult(
        CreateDocumentNear(ExportFormat.JPEG.mimeType)
    ) { uri -> uri?.let { finishSingleExport(it) } }

    private val exportPdfLauncher = registerForActivityResult(
        CreateDocumentNear(ExportFormat.PDF.mimeType)
    ) { uri -> uri?.let { finishSingleExport(it) } }

    /** Page export writes a file per page, so it asks for a folder, not a file name. */
    private val exportFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { finishPagesExport(it) } }

    private fun startExport(request: PendingExport) {
        pendingExport = request
        if (request.prefs.scope == ExportScope.PAGES) {
            // OpenDocumentTree takes the folder to start in as its input.
            exportFolderLauncher.launch(pickerStartUri)
        } else {
            val fileName = DocumentExporter.fileNameFor(request.baseName, request.prefs)
            when (request.prefs.format) {
                ExportFormat.SVG -> exportSvgLauncher.launch(fileName)
                ExportFormat.PNG -> exportPngLauncher.launch(fileName)
                ExportFormat.JPEG -> exportJpegLauncher.launch(fileName)
                ExportFormat.PDF -> exportPdfLauncher.launch(fileName)
            }
        }
    }

    private fun finishSingleExport(uri: Uri) {
        val request = pendingExport ?: return
        pendingExport = null
        runExport {
            FileManager.exportToUri(this, uri, request.document, request.selection, request.prefs)
        }
    }

    private fun finishPagesExport(treeUri: Uri) {
        val request = pendingExport ?: return
        pendingExport = null
        runExport {
            FileManager.exportPagesToTree(
                this, treeUri, request.document, request.prefs, request.baseName
            )
        }
    }

    /**
     * Exports off the main thread: a document with imported PDF pages renders each of
     * them in full, which is far too slow to do while the UI waits.
     */
    private fun runExport(export: () -> DocumentExporter.Result) {
        busyMessage = "Exporting…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    export()
                } catch (e: Throwable) {
                    e.printStackTrace()
                    DocumentExporter.Result.Failure("Export failed")
                }
            }
            busyMessage = null
            reportExport(result)
        }
    }

    private fun reportExport(result: DocumentExporter.Result) {
        val message = when (result) {
            is DocumentExporter.Result.Success ->
                if (result.fileCount == 1) "Exported" else "Exported ${result.fileCount} pages"
            is DocumentExporter.Result.Failure -> result.message
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private var onDocumentLoaded: (NoteDocument) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Rnote's own breakpoint collapses its sidebar under 1250sp on a desktop window;
            // here, below Material's compact/medium 600dp boundary, the floating PenConfigStrip
            // would overlap most of the drawing area on a phone-width screen, so it's hidden
            // rather than degraded in place, and Page Settings falls back to a modal sheet
            // instead of a docked side panel.
            val isCompactWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600

            // ── Persistent settings — loaded once from SharedPreferences ──────────
            var paperStyle by remember {
                mutableStateOf(SettingsManager.loadPaperStyle(this))
            }
            var toolConfig by remember {
                mutableStateOf(ToolConfig(allowFingerDrawing = SettingsManager.loadAllowFingerDrawing(this)))
            }

            // ── Document state ────────────────────────────────────────────────────
            var documentTitle by remember { mutableStateOf("My Note") }
            var isModified by remember { mutableStateOf(false) }
            var showRenameDialog by remember { mutableStateOf(false) }
            var showNewDocumentDialog by remember { mutableStateOf(false) }
            var renameFieldValue by remember { mutableStateOf("") }

            // ── UI sheet state ────────────────────────────────────────────────────
            // One canvas unit must be a constant physical size, so the viewport carries the
            // display's real scale (see ViewportState.displayScale) rather than drawing 1:1.
            val displayMetrics = LocalContext.current.resources.displayMetrics
            val displayScale = remember(displayMetrics) {
                ViewportState.displayScaleFor(
                    displayMetrics.xdpi, displayMetrics.ydpi, displayMetrics.densityDpi
                )
            }
            var viewportState by remember { mutableStateOf(ViewportState(displayScale = displayScale)) }
            // The drawing area's own size, which the pan offset is measured against.
            var canvasSize by remember { mutableStateOf(IntSize.Zero) }
            var showPageSettings by remember { mutableStateOf(false) }
            var showExportSheet by remember { mutableStateOf(false) }
            // Kept across openings so a second export doesn't start from the defaults again.
            var exportPrefs by remember { mutableStateOf(ExportPrefs()) }

            // ── Stroke stacks ─────────────────────────────────────────────────────
            val strokes = remember { mutableStateListOf<Stroke>() }
            val undoStack = remember { mutableStateListOf<List<Stroke>>() }
            val redoStack = remember { mutableStateListOf<List<Stroke>>() }
            val selectedStrokes = remember { mutableStateListOf<Stroke>() }
            // Non-stroke elements (text/shapes/images) preserved from an imported native file.
            // Carried through save/export so they aren't silently dropped from opened .rnote files.
            var documentNativeElements by remember { mutableStateOf<List<NativeCanvasElement>>(emptyList()) }

            // ── Page indicator (2D grid position) ────────────────────────────────
            val currentPage: Int? = if (paperStyle.pageSize.isInfinite) null else {
                // Just pass a non-null sentinel — actual col/row shown in top bar title
                1
            }
            val pageGridLabel: String? = if (paperStyle.pageSize.isInfinite) null else {
                val pageW = paperStyle.effectivePageWidthPx
                val pageH = paperStyle.effectivePageHeightPx
                val col = floor(-viewportState.panOffset.x / viewportState.effectiveScale / (pageW + 40f)).toInt()
                val row = floor(-viewportState.panOffset.y / viewportState.effectiveScale / (pageH + 40f)).toInt()
                "${col + 1}, ${row + 1}"  // 1-indexed display
            }

            // ── Persist settings ──────────────────────────────────────────────────
            // Only ever from an explicit choice (the Page Settings sheet, the finger-draw
            // toggle). This used to be a SideEffect that mirrored every paperStyle change
            // into SharedPreferences, which meant opening any .rnote silently made that
            // file's format the app-wide default: open a desktop fixed-size note, start a
            // new note, and it was still Fixed Size — that is how a continuous-vertical
            // note got written to disk as `fixed_size`.
            val persistSettings = { style: PaperStyle ->
                SettingsManager.save(this, style, toolConfig.allowFingerDrawing)
            }

            // ── Document load handler ─────────────────────────────────────────────
            onDocumentLoaded = { doc ->
                strokes.clear()
                undoStack.clear()
                redoStack.clear()
                selectedStrokes.clear()
                strokes.addAll(doc.strokes)
                // The file's format is the document's, not the user's default — so it is
                // deliberately not persisted; the next new note starts from preferences.
                paperStyle = doc.paperStyle
                documentTitle = doc.title
                documentNativeElements = doc.nativeElements
                viewportState = ViewportState(displayScale = displayScale)
                isModified = false
            }

            // A finished load waits in incomingDocument; hand it over once this handler
            // exists (see incomingDocument for why it isn't called directly).
            val pendingDocument = incomingDocument
            LaunchedEffect(pendingDocument) {
                if (pendingDocument != null) {
                    onDocumentLoaded(pendingDocument)
                    incomingDocument = null
                    if (incomingIsRecovered) {
                        incomingIsRecovered = false
                        isModified = true
                    }
                }
            }

            // ── New document handler ──────────────────────────────────────────────
            // A harder reset than Clear Canvas: the title and the undo history go too,
            // so there's no way back — hence the confirmation when edits are unsaved.
            // The paper style goes back to the stored preference rather than surviving:
            // it belongs to the document now, so whatever an opened file brought with it
            // must not follow the user into their next note.
            val startNewDocument = {
                paperStyle = SettingsManager.loadPaperStyle(this)
                strokes.clear()
                undoStack.clear()
                redoStack.clear()
                selectedStrokes.clear()
                documentNativeElements = emptyList()
                documentTitle = "My Note"
                viewportState = ViewportState(displayScale = displayScale)
                isModified = false
                // Fresh notes save as .rnote — see saveAsRnote's declaration.
                saveAsRnote = true
                // No file yet, so the next Save has to ask for one.
                currentDocumentUri = null
                knownLastModified = null
                // Starting over discards the old note on purpose; don't offer it back.
                Recovery.clear(this)
            }

            // ── Save succeeded handler ────────────────────────────────────────────
            onSaveSucceeded = { saved ->
                // A save runs in the background; ink added while it ran is not in the file.
                val unchanged = saved.strokes.size == strokes.size &&
                    saved.strokes.indices.all { saved.strokes[it] === strokes[it] } &&
                    saved.nativeElements === documentNativeElements &&
                    saved.paperStyle == paperStyle && saved.title == documentTitle
                if (unchanged) isModified = false
            }
            unsavedDocument = {
                if (!isModified) null else NoteDocument(
                    title = documentTitle,
                    paperStyle = paperStyle,
                    strokes = strokes.toList(),
                    nativeElements = documentNativeElements
                )
            }
            // A minute after the first unsaved change, and again after every save.
            LaunchedEffect(isModified) {
                if (isModified) {
                    delay(AUTOSAVE_DELAY_MS)
                    autosave()
                }
            }
            onTitleAdopted = { name -> documentTitle = name }

            // ── PDF import ─────────────────────────────────────────────────────────
            pdfImportTarget = {
                val pageW = paperStyle.effectivePageWidthPx.takeIf { it > 0f } ?: 793.7f
                val pageH = paperStyle.effectivePageHeightPx.takeIf { it > 0f } ?: 1122.5f
                val others = documentNativeElements.filter { it !is NativeBrushStroke }
                val hasContent = strokes.isNotEmpty() || others.isNotEmpty()
                // Below everything already in the note, starting on the next whole page.
                val bottom = maxOf(
                    strokes.maxOfOrNull { s -> s.points.maxOfOrNull { it.y } ?: 0f } ?: 0f,
                    others.maxOfOrNull { it.maxY } ?: 0f
                )
                val startY = if (hasContent) (floor(bottom / pageH) + 1f) * pageH else 0f
                PdfImportTarget(pageW, pageH, startY)
            }
            onPdfImported = { pages ->
                // Ahead of the rest: the document layer is drawn first, under everything.
                documentNativeElements = pages + documentNativeElements
                isModified = true
                // Bring the first imported page into view at the current zoom.
                viewportState = viewportState.copy(
                    panOffset = androidx.compose.ui.geometry.Offset(
                        ViewportState.ORIGIN_MARGIN_PX,
                        ViewportState.ORIGIN_MARGIN_PX - pages.first().minY * viewportState.effectiveScale
                    )
                )
            }

            // ── S-Pen Air Action remote shortcuts ─────────────────────────────────
            performUndoAction = {
                // Gated on undoStack, not `strokes` — an empty canvas can still have undo
                // history (e.g. right after Clear Canvas), and that must stay undoable.
                if (undoStack.isNotEmpty()) {
                    redoStack.add(strokes.toList())
                    val previousState = undoStack.removeAt(undoStack.lastIndex)
                    strokes.clear()
                    strokes.addAll(previousState)
                    isModified = true
                }
            }

            performRedoAction = {
                if (redoStack.isNotEmpty()) {
                    undoStack.add(strokes.toList())
                    val nextState = redoStack.removeAt(redoStack.lastIndex)
                    strokes.clear()
                    strokes.addAll(nextState)
                    isModified = true
                }
            }

            BabyRnoteTheme(darkTheme = paperStyle.isDarkMode) {
                Scaffold(
                    topBar = {
                        RnoteTopBar(
                            paperStyle = paperStyle,
                            zoomScale = viewportState.zoomScale,
                            allowFingerDrawing = toolConfig.allowFingerDrawing,
                            isModified = isModified,
                            documentTitle = documentTitle,
                            currentPage = pageGridLabel,
                            onResetZoom = { viewportState = ViewportState(displayScale = displayScale) },
                            // Unlike the zoom reset next to it, this keeps the zoom and
                            // only moves the view — see ViewportState.returnedToOrigin.
                            onReturnToOrigin = {
                                viewportState = viewportState.returnedToOrigin(
                                    viewportWidthPx = canvasSize.width.toFloat(),
                                    // Nothing to centre on when the document has no pages.
                                    pageWidthPx = if (paperStyle.pageSize.isInfinite) 0f
                                                  else paperStyle.effectivePageWidthPx
                                )
                            },
                            onTitleTap = {
                                renameFieldValue = documentTitle
                                showRenameDialog = true
                            },
                            onToggleFingerDrawing = {
                                toolConfig = toolConfig.copy(allowFingerDrawing = !toolConfig.allowFingerDrawing)
                                SettingsManager.save(
                                    this, paperStyle, toolConfig.allowFingerDrawing
                                )
                            },
                            onSaveDocument = {
                                val currentDoc = NoteDocument(
                                    title = documentTitle,
                                    paperStyle = paperStyle,
                                    strokes = strokes.toList(),
                                    nativeElements = documentNativeElements
                                )
                                // Straight back over the file it came from; only a note
                                // that has never been written asks where to go.
                                if (!saveInPlace(currentDoc)) launchSavePicker(currentDoc)
                            },
                            onSaveDocumentAs = {
                                launchSavePicker(
                                    NoteDocument(
                                        title = documentTitle,
                                        paperStyle = paperStyle,
                                        strokes = strokes.toList(),
                                        nativeElements = documentNativeElements
                                    )
                                )
                            },
                            onOpenDocument = {
                                openDocumentLauncher.launch(arrayOf("*/*", "application/json"))
                            },
                            onImportPdf = { importPdfLauncher.launch(arrayOf("application/pdf")) },
                            onNewDocument = {
                                if (isModified) {
                                    showNewDocumentDialog = true
                                } else {
                                    startNewDocument()
                                }
                            },
                            onExport = { showExportSheet = true },
                            onClearCanvas = {
                                if (strokes.isNotEmpty() || documentNativeElements.isNotEmpty()) {
                                    undoStack.add(strokes.toList())
                                    redoStack.clear()
                                    strokes.clear()
                                    selectedStrokes.clear()
                                    documentNativeElements = emptyList()
                                    isModified = true
                                }
                            },
                            onOpenPageSettings = { showPageSettings = true }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .onSizeChanged { canvasSize = it }
                    ) {
                        DrawingCanvas(
                            toolConfig = toolConfig,
                            paperStyle = paperStyle,
                            viewportState = viewportState,
                            strokes = strokes,
                            selectedStrokes = selectedStrokes,
                            onViewportChanged = { newViewport ->
                                viewportState = newViewport
                            },
                            onAddStroke = { newStroke ->
                                undoStack.add(strokes.toList())
                                redoStack.clear()
                                strokes.add(newStroke)
                                isModified = true
                            },
                            onEraseStart = {
                                // One snapshot for the whole eraser drag. This used to sit
                                // in onEraseStrokes, which fires per motion event, so
                                // rubbing out five strokes cost five undos to put back.
                                undoStack.add(strokes.toList())
                                redoStack.clear()
                            },
                            onEraseStrokes = { erased ->
                                val erasedIds = erased.map { it.id }.toSet()
                                strokes.removeAll { it.id in erasedIds }
                                isModified = true
                            },
                            onSelectionDragStart = {
                                undoStack.add(strokes.toList())
                                redoStack.clear()
                            },
                            onStrokesModified = { updatedStrokes ->
                                val updatedById = updatedStrokes.associateBy { it.id }
                                for (i in strokes.indices) {
                                    updatedById[strokes[i].id]?.let { strokes[i] = it }
                                }
                                isModified = true
                            },
                            nativeElements = documentNativeElements,
                        )

                        // Top-center: stroke color + palette (matches Rnote's colorpicker.ui)
                        ColorPicker(
                            activeColor = toolConfig.currentActiveColor,
                            onColorSelected = { newColor ->
                                toolConfig = if (toolConfig.activeTool == ToolType.BRUSH && toolConfig.brushStyle == BrushStyle.MARKER) {
                                    toolConfig.copy(highlighterColor = newColor)
                                } else {
                                    toolConfig.copy(penColor = newColor)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 18.dp)
                        )

                        // Left edge, vertically centered: per-pen config (matches RnPensSideBar).
                        // Hidden below the width breakpoint (see isCompactWidth, top of file).
                        if (!isCompactWidth) PenConfigStrip(
                            toolConfig = toolConfig,
                            hasActiveSelection = selectedStrokes.isNotEmpty(),
                            onBrushStyleSelected = { style -> toolConfig = toolConfig.copy(brushStyle = style) },
                            onSizeChanged = { newSize -> toolConfig = toolConfig.updateActiveSize(newSize) },
                            onDeleteSelection = {
                                if (selectedStrokes.isNotEmpty()) {
                                    undoStack.add(strokes.toList())
                                    redoStack.clear()
                                    val ids = selectedStrokes.map { it.id }.toSet()
                                    strokes.removeAll { it.id in ids }
                                    selectedStrokes.clear()
                                    isModified = true
                                }
                            },
                            onDuplicateSelection = {
                                if (selectedStrokes.isNotEmpty()) {
                                    undoStack.add(strokes.toList())
                                    redoStack.clear()
                                    val offset = 20f
                                    val duplicates = selectedStrokes.map { s ->
                                        s.copy(
                                            id = java.util.UUID.randomUUID().toString(),
                                            points = s.points.map { p -> StrokePoint(p.x + offset, p.y + offset, p.pressure) }
                                        )
                                    }
                                    strokes.addAll(duplicates)
                                    selectedStrokes.clear()
                                    selectedStrokes.addAll(duplicates)
                                    isModified = true
                                }
                            },
                            onSelectAll = {
                                selectedStrokes.clear()
                                selectedStrokes.addAll(strokes)
                            },
                            onDeselectAll = { selectedStrokes.clear() },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 18.dp)
                        )

                        // Bottom-center: pen switcher + undo/redo (matches Rnote's penpicker.ui)
                        PenPicker(
                            toolConfig = toolConfig,
                            canUndo = undoStack.isNotEmpty(),
                            canRedo = redoStack.isNotEmpty(),
                            onToolSelected = { newTool ->
                                toolConfig = toolConfig.copy(activeTool = newTool)
                                if (newTool != ToolType.SELECTOR) selectedStrokes.clear()
                            },
                            onUndo = { performUndoAction?.invoke() },
                            onRedo = { performRedoAction?.invoke() },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 28.dp)
                        )

                        // Docked at tablet width (right edge, opposite PenConfigStrip); falls
                        // back to a modal sheet below the breakpoint.
                        if (showPageSettings) {
                            PageSettingsSheet(
                                paperStyle = paperStyle,
                                onPaperStyleChanged = {
                                    paperStyle = it
                                    // An explicit choice is both an edit to this document
                                    // and the default the next new note should start from.
                                    persistSettings(it)
                                    isModified = true
                                },
                                onDismiss = { showPageSettings = false },
                                dockedAsSidePanel = !isCompactWidth,
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }

                        // ── Progress card while a file is read or written ─────────────
                        busyMessage?.let { message ->
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .background(Color(0xE61E1E2E), RoundedCornerShape(16.dp))
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text(message, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }

                    // ── Export Sheet ──────────────────────────────────────────────
                    if (showExportSheet) {
                        val exportDocument = NoteDocument(
                            title = documentTitle,
                            paperStyle = paperStyle,
                            strokes = strokes.toList(),
                            nativeElements = documentNativeElements
                        )
                        ExportSheet(
                            paperStyle = paperStyle,
                            prefs = exportPrefs,
                            pageCount = DocumentExporter.pagesFor(exportDocument, exportPrefs).size,
                            hasSelection = selectedStrokes.isNotEmpty(),
                            hasImportedPages = DocumentExporter.hasImportedPages(exportDocument),
                            onPrefsChanged = { exportPrefs = it },
                            onDismiss = { showExportSheet = false },
                            onExport = {
                                showExportSheet = false
                                startExport(
                                    PendingExport(
                                        document = exportDocument,
                                        selection = selectedStrokes.toList(),
                                        prefs = exportPrefs,
                                        baseName = documentTitle
                                    )
                                )
                            }
                        )
                    }

                    // ── Changed elsewhere since it was opened ─────────────────────
                    pendingConflict?.let { conflicted ->
                        AlertDialog(
                            onDismissRequest = { pendingConflict = null },
                            title = { Text("File changed elsewhere") },
                            text = {
                                Text(
                                    "\"${conflicted.title}\" was changed since you opened it — " +
                                        "on another device, for example. Saving over it would " +
                                        "lose those changes."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    pendingConflict = null
                                    launchSavePicker(conflicted)
                                }) { Text("Save as copy") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    pendingConflict = null
                                    saveInPlace(conflicted, overwriteChanges = true)
                                }) { Text("Overwrite") }
                            }
                        )
                    }

                    // ── Recovered from the last session ───────────────────────────
                    pendingRecovery?.let { recovered ->
                        AlertDialog(
                            onDismissRequest = { },
                            title = { Text("Restore unsaved note?") },
                            text = {
                                Text(
                                    "\"${recovered.document.title}\" had changes that were not " +
                                        "saved when the app was last closed."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    pendingRecovery = null
                                    restoreRecovered(recovered)
                                }) { Text("Restore") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    pendingRecovery = null
                                    Recovery.clear(this@MainActivity)
                                }) { Text("Discard") }
                            }
                        )
                    }

                    // ── New Document Confirmation ─────────────────────────────────
                    if (showNewDocumentDialog) {
                        AlertDialog(
                            onDismissRequest = { showNewDocumentDialog = false },
                            title = { Text("Discard unsaved changes?") },
                            text = { Text("\"$documentTitle\" has unsaved changes. Starting a new note will discard them.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showNewDocumentDialog = false
                                    startNewDocument()
                                }) { Text("Discard") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNewDocumentDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    // ── Rename Dialog ─────────────────────────────────────────────
                    if (showRenameDialog) {
                        AlertDialog(
                            onDismissRequest = { showRenameDialog = false },
                            title = { Text("Rename Note") },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = renameFieldValue,
                                        onValueChange = { renameFieldValue = it },
                                        label = { Text("Title") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    // Renaming the file itself isn't ours to do: SAF hands
                                    // back a new uri for a renamed document, and a
                                    // single-document grant doesn't extend to it, so the
                                    // note would lose the file it saves to. Save As is the
                                    // way to put the note in a file of the new name.
                                    if (currentDocumentUri != null) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "The file keeps its name — use Save As to " +
                                                "write this note to a new one.",
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val trimmed = renameFieldValue.trim()
                                    if (trimmed.isNotBlank()) {
                                        documentTitle = trimmed
                                        isModified = true
                                    }
                                    showRenameDialog = false
                                }) { Text("Rename") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }
        }
        // "Open with" — only for the launch that brought the file, not when the activity
        // is recreated with the same intent after the process was reclaimed.
        if (savedInstanceState == null) {
            if (intent?.action == Intent.ACTION_VIEW) handleViewIntent(intent) else offerRecovery()
        }
    }

    /** Looks for a note the last session didn't get to save, and offers it back. */
    private fun offerRecovery() {
        lifecycleScope.launch {
            val recovered = withContext(Dispatchers.IO) { Recovery.read(this@MainActivity) }
            if (recovered != null) pendingRecovery = recovered
        }
    }

    private fun restoreRecovered(recovered: Recovery.Pending) {
        saveAsRnote = recovered.saveAsRnote
        currentDocumentUri = recovered.uri
        recovered.uri?.let { pickerStartUri = it }
        // Unknown: the file may have changed while the app was gone, and the recovered
        // note would then be the one to overwrite it — so no conflict baseline is set and
        // the next save asks nothing. Recovered work is the user's most recent anyway.
        knownLastModified = null
        incomingIsRecovered = true
        incomingDocument = recovered.document
    }

    override fun onStop() {
        super.onStop()
        // Leaving the foreground is when Android may reclaim the app without asking, so
        // unsaved work is secured now rather than at the next timer tick. Preferences need
        // nothing here: they are written the moment they are chosen (see persistSettings).
        autosave()
    }

    private companion object {
        /** How long after the first unsaved change autosave runs. Rnote's default is 120 s. */
        const val AUTOSAVE_DELAY_MS = 60_000L
    }

    /**
     * Samsung S-Pen Air Action Remote Button Key Event Handler:
     * - Single Press / Page Down: Undo
     * - Page Up: Redo
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_PAGE_DOWN -> {
                performUndoAction?.invoke()
                true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                performRedoAction?.invoke()
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }
}
