package io.github.kjly.brna.storage

import android.content.Context
import android.net.Uri
import io.github.kjly.brna.model.NoteDocument
import java.io.File
import java.util.Properties

/**
 * A private copy of the open note's unsaved state, so work survives Android reclaiming
 * the app in the background — which it does without warning when memory runs short.
 *
 * Written by autosave, cleared by every successful save, and offered back on the next
 * launch if it is still there. It lives in the app's own storage, so writing it is fast
 * and never touches the user's file.
 */
object Recovery {

    private const val NOTE_FILE = "recovery.rnote"
    private const val META_FILE = "recovery.properties"

    private const val KEY_TITLE = "title"
    private const val KEY_URI = "uri"
    private const val KEY_AS_RNOTE = "asRnote"
    private const val KEY_SAVED_AT = "savedAt"

    /** A recovered note and where it came from. */
    class Pending(
        val document: NoteDocument,
        /** The file the note belongs to; null for a note that had never been saved. */
        val uri: Uri?,
        val saveAsRnote: Boolean,
        val savedAt: Long
    )

    /** Blocking; call off the main thread. */
    fun write(context: Context, document: NoteDocument, uri: Uri?, saveAsRnote: Boolean) {
        val dir = context.filesDir
        // Written aside and renamed into place, so a write cut short by the process being
        // killed leaves the previous copy intact rather than half a file.
        val noteTmp = File(dir, "$NOTE_FILE.tmp")
        noteTmp.outputStream().use {
            RnoteNativeSerializer.serialize(it, RnoteNativeSerializer.bridgeToNative(document))
        }
        val meta = Properties().apply {
            setProperty(KEY_TITLE, document.title)
            uri?.let { setProperty(KEY_URI, it.toString()) }
            setProperty(KEY_AS_RNOTE, saveAsRnote.toString())
            setProperty(KEY_SAVED_AT, System.currentTimeMillis().toString())
        }
        val metaTmp = File(dir, "$META_FILE.tmp")
        metaTmp.outputStream().use { meta.store(it, null) }
        noteTmp.renameTo(File(dir, NOTE_FILE))
        metaTmp.renameTo(File(dir, META_FILE))
    }

    /** The recovered note, or null when there is none (or it can't be read). Blocking. */
    fun read(context: Context): Pending? = try {
        val noteFile = File(context.filesDir, NOTE_FILE)
        val metaFile = File(context.filesDir, META_FILE)
        if (!noteFile.exists() || !metaFile.exists()) {
            null
        } else {
            val meta = Properties().apply { metaFile.inputStream().use { load(it) } }
            val native = noteFile.inputStream().use { RnoteNativeParser.parse(it) }
            val document = FileManager.bridgeNativeToNoteDocument(native)
                .copy(title = meta.getProperty(KEY_TITLE) ?: "Recovered Note")
            Pending(
                document = document,
                uri = meta.getProperty(KEY_URI)?.let(Uri::parse),
                saveAsRnote = meta.getProperty(KEY_AS_RNOTE)?.toBoolean() ?: true,
                savedAt = meta.getProperty(KEY_SAVED_AT)?.toLongOrNull() ?: noteFile.lastModified()
            )
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        null
    }

    /** Forgets the recovered note: it was saved, restored, or thrown away on purpose. */
    fun clear(context: Context) {
        File(context.filesDir, NOTE_FILE).delete()
        File(context.filesDir, META_FILE).delete()
    }
}
