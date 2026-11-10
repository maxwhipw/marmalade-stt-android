package app.marmalade.stt.ime

import android.view.inputmethod.InputConnection
import app.marmalade.stt.textproc.TextPipeline
import app.marmalade.stt.ui.screen.Dictation

/**
 * The part of [InputConnection] the voice keyboard uses. Same signatures, so
 * tests can stand in a fake editor.
 */
interface EditorConnection {
    fun getTextBeforeCursor(n: Int, flags: Int): CharSequence?
    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean
    fun finishComposingText(): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean
    fun beginBatchEdit(): Boolean
    fun endBatchEdit(): Boolean
}

/** Adapts a real [InputConnection]. */
fun InputConnection.asEditorConnection(): EditorConnection {
    val ic = this
    return object : EditorConnection {
        override fun getTextBeforeCursor(n: Int, flags: Int) = ic.getTextBeforeCursor(n, flags)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
            ic.setComposingText(text, newCursorPosition)
        override fun finishComposingText() = ic.finishComposingText()
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
            ic.deleteSurroundingText(beforeLength, afterLength)
        override fun commitText(text: CharSequence, newCursorPosition: Int) = ic.commitText(text, newCursorPosition)
        override fun beginBatchEdit() = ic.beginBatchEdit()
        override fun endBatchEdit() = ic.endBatchEdit()
    }
}

/**
 * Writes one listening session's text into the editor the keyboard is
 * attached to.
 *
 * Partials go into the composing region (underlined, not yet committed) so
 * the user sees them in place; a Final replaces the partial with the
 * `:textproc` output, applied as a [app.marmalade.stt.textproc.TextEdit]
 * against the text before the cursor. An aborted partial is removed, never
 * committed.
 *
 * @param editor the current connection, resolved at each call (the service
 *   passes `currentInputConnection`). Null means no editor: every call is a
 *   no-op.
 */
class ImeTextWriter(private val editor: () -> EditorConnection?) {

    /** Diagnostics contain operation names only, never dictated text. */
    var onEditorFailure: (String) -> Unit = {}

    /**
     * Whether partials go into the editor at all. False for editors without
     * text support (`InputType.TYPE_NULL`), where a composing region makes no
     * sense; the panel still shows the partial.
     */
    var usesComposing: Boolean = true

    /** A partial is in the editor's composing region. */
    private var composing = false

    /** Context at the composing region's start, before the editor can filter or truncate a partial. */
    private var beforeComposing = ""

    /** Goes before the partial ("" or " "), fixed when composing starts: afterwards the text before the cursor includes the partial. */
    private var separator = ""

    /** Show [text] as the utterance in progress. */
    fun showPartial(text: String) {
        if (!usesComposing) return
        if (text.isEmpty()) {
            clearPartial()
            return
        }
        val ed = editor() ?: return
        if (!composing) {
            beforeComposing = ed.getTextBeforeCursor(BEFORE_CURSOR_CHARS, 0)?.toString().orEmpty()
            separator = Dictation.partialSeparator(beforeComposing)
        }
        if (ed.setComposingText(separator + text, 1)) composing = true
    }

    /**
     * Replace the partial with the cleaned-up [text] of a finished utterance.
     * Returns whether the editor accepted the final edit requests. This is
     * not an acknowledgment that the host app persisted the text.
     */
    fun commitFinal(text: String, nearSilent: Boolean, pipeline: TextPipeline, silent: Boolean = false): Boolean {
        val ed = editor()
        if (ed == null) {
            onEditorFailure("no input connection for final")
            resetComposition()
            return false
        }
        // Do not infer the composing region's length from the last partial: an
        // editor may have filtered it. Reading after removal inside the batch
        // is also unsafe: reactive editors can defer those edits until endBatchEdit.
        val before = if (composing) {
            beforeComposing
        } else {
            ed.getTextBeforeCursor(BEFORE_CURSOR_CHARS, 0)?.toString().orEmpty()
        }
        val edit = pipeline.process(text, before = before, nearSilent = nearSilent, silent = silent)
        if (!edit.isEmpty && edit.deleteBefore == 0) {
            // commitText replaces the composing span itself. This single edit
            // needs no batching and never exposes an intermediate blank value.
            if (!ed.commitText(edit.insert, 1)) return rejectedFinal(ed, "commitText")
            resetComposition()
            return true
        }
        // Commands can reach into text preceding the composing span. Batching
        // is advisory: BaseInputConnection can return false even when usable.
        ed.beginBatchEdit()
        try {
            if (!removeComposing(ed)) return rejectedFinal(ed, "remove composing text")
            if (edit.isEmpty) return false
            if (!ed.deleteSurroundingText(edit.deleteBefore, 0)) {
                return rejectedFinal(ed, "deleteSurroundingText")
            }
            if (edit.insert.isNotEmpty() && !ed.commitText(edit.insert, 1)) {
                return rejectedFinal(ed, "commitText after deletion")
            }
        } finally {
            ed.endBatchEdit()
        }
        return true
    }

    /** Remove the partial from the editor (cancel, abort, keyboard dismissed). */
    fun clearPartial() {
        val ed = editor()
        if (ed == null) {
            resetComposition()
            return
        }
        if (!composing) return
        ed.beginBatchEdit()
        try {
            if (!removeComposing(ed)) rejectedFinal(ed, "clearPartial")
        } finally {
            ed.endBatchEdit()
        }
    }

    private fun removeComposing(ed: EditorConnection): Boolean {
        if (!composing) return true
        // Empty it first: finishComposingText alone would keep the partial as text.
        if (!ed.setComposingText("", 1)) return false
        val finished = ed.finishComposingText()
        resetComposition()
        return finished
    }

    private fun rejectedFinal(ed: EditorConnection, operation: String): Boolean {
        onEditorFailure("$operation rejected")
        // Keep whatever partial is still visible. Session cleanup must not
        // turn a failed final replacement into another destructive removal.
        if (composing) ed.finishComposingText()
        resetComposition()
        return false
    }

    private fun resetComposition() {
        composing = false
        beforeComposing = ""
    }

    private companion object {
        /** How much context `:textproc` gets for spacing, capitalisation and "scratch that". */
        const val BEFORE_CURSOR_CHARS = 1000
    }
}
