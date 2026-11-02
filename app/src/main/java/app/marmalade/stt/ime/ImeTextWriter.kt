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

    /**
     * Whether partials go into the editor at all. False for editors without
     * text support (`InputType.TYPE_NULL`), where a composing region makes no
     * sense; the panel still shows the partial.
     */
    var usesComposing: Boolean = true

    /** A partial is in the editor's composing region. */
    private var composing = false

    /** The exact text currently placed in the composing region (separator + partial). */
    private var currentComposingText = ""

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
            separator = Dictation.partialSeparator(ed.getTextBeforeCursor(1, 0)?.toString().orEmpty())
            composing = true
        }
        currentComposingText = separator + text
        ed.setComposingText(currentComposingText, 1)
    }

    /**
     * Replace the partial with the cleaned-up [text] of a finished utterance.
     * Returns whether it changed the editor (false when the cleanup left
     * nothing, e.g. a filtered hallucination, or there is no editor).
     */
    fun commitFinal(text: String, nearSilent: Boolean, pipeline: TextPipeline, silent: Boolean = false): Boolean {
        val ed = editor()
        if (ed == null) {
            composing = false
            currentComposingText = ""
            return false
        }
        val rawBefore = ed.getTextBeforeCursor(BEFORE_CURSOR_CHARS + currentComposingText.length, 0)?.toString().orEmpty()
        val before = if (composing && rawBefore.endsWith(currentComposingText)) {
            rawBefore.dropLast(currentComposingText.length)
        } else if (composing && rawBefore.length >= currentComposingText.length) {
            rawBefore.dropLast(currentComposingText.length)
        } else {
            rawBefore
        }
        val edit = pipeline.process(text, before = before, nearSilent = nearSilent, silent = silent)
        ed.beginBatchEdit()
        try {
            removeComposing(ed)
            if (edit.isEmpty) return false
            if (edit.deleteBefore > 0) ed.deleteSurroundingText(edit.deleteBefore, 0)
            if (edit.insert.isNotEmpty()) ed.commitText(edit.insert, 1)
        } finally {
            ed.endBatchEdit()
        }
        return true
    }

    /** Remove the partial from the editor (cancel, abort, keyboard dismissed). */
    fun clearPartial() {
        val ed = editor()
        if (ed == null) {
            composing = false
            currentComposingText = ""
            return
        }
        if (!composing) return
        ed.beginBatchEdit()
        try {
            removeComposing(ed)
        } finally {
            ed.endBatchEdit()
        }
    }

    private fun removeComposing(ed: EditorConnection) {
        if (!composing) return
        // Empty it first: finishComposingText alone would keep the partial as text.
        ed.setComposingText("", 1)
        ed.finishComposingText()
        composing = false
        currentComposingText = ""
    }

    private companion object {
        /** How much context `:textproc` gets for spacing, capitalisation and "scratch that". */
        const val BEFORE_CURSOR_CHARS = 1000
    }
}
