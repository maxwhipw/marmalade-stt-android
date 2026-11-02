package app.marmalade.stt.ime

import app.marmalade.stt.textproc.TextPipeline
import app.marmalade.stt.textproc.TextProcConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImeTextWriter] against a fake editor that behaves like a text field: a
 * buffer, a cursor at the end of what was written, and a composing region.
 * Runs the real default `:textproc` pipeline.
 */
class ImeTextWriterTest {

    private class FakeEditor(text: String = "") : EditorConnection {
        private val buf = StringBuilder(text)
        var cursor = text.length
        var composing: IntRange? = null
        var batchDepth = 0
        var nullTextBefore = false
        val calls = mutableListOf<String>()

        val text: String get() = buf.toString()
        val composingText: String? get() = composing?.let { buf.substring(it.first, it.last + 1) }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            if (nullTextBefore) null else buf.substring(maxOf(0, cursor - n), cursor)

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "setComposingText($text)"
            val start = replaceComposingOrInsert(text)
            composing = start until start + text.length
            return true
        }

        override fun finishComposingText(): Boolean {
            calls += "finishComposingText"
            composing = null
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            calls += "deleteSurroundingText($beforeLength)"
            val start = maxOf(0, cursor - beforeLength)
            buf.delete(start, cursor)
            cursor = start
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "commitText($text)"
            replaceComposingOrInsert(text)
            composing = null
            return true
        }

        override fun beginBatchEdit(): Boolean {
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            batchDepth--
            return true
        }

        /** Replaces the composing region (or inserts at the cursor); the cursor ends after [text]. */
        private fun replaceComposingOrInsert(text: CharSequence): Int {
            val region = composing
            val start = region?.first ?: cursor
            val end = region?.let { it.last + 1 } ?: cursor
            buf.replace(start, end, text.toString())
            cursor = start + text.length
            return start
        }
    }

    private val pipeline = TextPipeline(TextProcConfig())

    private fun writerFor(editor: FakeEditor?) = ImeTextWriter { editor }

    @Test
    fun `a partial is composed after a separating space`() {
        val editor = FakeEditor("Hello.")
        val writer = writerFor(editor)
        writer.showPartial("how are")
        writer.showPartial("how are you")
        assertEquals("Hello. how are you", editor.text)
        assertEquals(" how are you", editor.composingText)
    }

    @Test
    fun `no separator after whitespace or in an empty field`() {
        val spaced = FakeEditor("Hello. ")
        writerFor(spaced).showPartial("hi")
        assertEquals("Hello. hi", spaced.text)

        val empty = FakeEditor()
        writerFor(empty).showPartial("hi")
        assertEquals("hi", empty.text)
    }

    @Test
    fun `a final replaces the partial with the pipeline's text`() {
        val editor = FakeEditor("Hello.")
        val writer = writerFor(editor)
        writer.showPartial("how are you")
        writer.commitFinal("how are you?", nearSilent = false, pipeline = pipeline)
        assertEquals("Hello. How are you?", editor.text)
        assertNull(editor.composing)
        assertEquals(editor.text.length, editor.cursor)
        assertEquals(0, editor.batchDepth)

        // The next utterance composes and commits after it.
        writer.showPartial("fine")
        assertEquals("Hello. How are you? fine", editor.text)
        writer.commitFinal("Fine.", nearSilent = false, pipeline = pipeline)
        assertEquals("Hello. How are you? Fine.", editor.text)
    }

    @Test
    fun `scratch that deletes the previous sentence`() {
        val editor = FakeEditor("One. Two.")
        val writer = writerFor(editor)
        writer.showPartial("scratch that")
        writer.commitFinal("Scratch that.", nearSilent = false, pipeline = pipeline)
        assertEquals("One.", editor.text)
        assertTrue("deleteSurroundingText(5)" in editor.calls)
    }

    @Test
    fun `a dropped phantom leaves the editor as it was`() {
        val editor = FakeEditor("Hello.")
        val writer = writerFor(editor)
        writer.showPartial("Thank you")
        editor.calls.clear()
        writer.commitFinal("Thank you.", nearSilent = true, pipeline = pipeline, silent = true)
        assertEquals("Hello.", editor.text)
        assertEquals(listOf("setComposingText()", "finishComposingText"), editor.calls)
    }

    @Test
    fun `without composing support partials stay out of the editor`() {
        val editor = FakeEditor("Hi.")
        val writer = writerFor(editor).apply { usesComposing = false }
        writer.showPartial("there")
        assertEquals("Hi.", editor.text)
        writer.clearPartial()
        assertFalse(editor.calls.any { it.startsWith("setComposingText") || it == "finishComposingText" })

        writer.commitFinal("there", nearSilent = false, pipeline = pipeline)
        assertEquals("Hi. There", editor.text)
        assertFalse(editor.calls.any { it.startsWith("setComposingText") })
    }

    @Test
    fun `clearPartial removes the partial and never commits it`() {
        val editor = FakeEditor("Hi")
        val writer = writerFor(editor)
        writer.showPartial("half a sen")
        writer.clearPartial()
        assertEquals("Hi", editor.text)
        assertNull(editor.composing)
        assertEquals(2, editor.cursor)

        // Nothing left to clear: no further editor calls.
        editor.calls.clear()
        writer.clearPartial()
        assertEquals(emptyList<String>(), editor.calls)
    }

    @Test
    fun `an empty partial clears the composing region`() {
        val editor = FakeEditor("Hi")
        val writer = writerFor(editor)
        writer.showPartial("there")
        writer.showPartial("")
        assertEquals("Hi", editor.text)
        assertNull(editor.composing)
    }

    @Test
    fun `unreadable text before the cursor counts as empty`() {
        val editor = FakeEditor().apply { nullTextBefore = true }
        val writer = writerFor(editor)
        writer.showPartial("hello")
        assertEquals("hello", editor.composingText)
        writer.commitFinal("hello there", nearSilent = false, pipeline = pipeline)
        assertEquals("Hello there", editor.text)
    }

    @Test
    fun `no editor is a no-op`() {
        val writer = writerFor(null)
        writer.showPartial("hello")
        writer.commitFinal("hello", nearSilent = false, pipeline = pipeline)
        writer.clearPartial()
    }

    @Test
    fun `composing removal and commit occur inside a batch edit`() {
        var batchDepthDuringRemove = -1
        var batchDepthDuringCommit = -1
        val editor = object : EditorConnection {
            private val delegate = FakeEditor()
            override fun getTextBeforeCursor(n: Int, flags: Int) = delegate.getTextBeforeCursor(n, flags)
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (text.isEmpty()) batchDepthDuringRemove = delegate.batchDepth
                return delegate.setComposingText(text, newCursorPosition)
            }
            override fun finishComposingText() = delegate.finishComposingText()
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
                delegate.deleteSurroundingText(beforeLength, afterLength)
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                batchDepthDuringCommit = delegate.batchDepth
                return delegate.commitText(text, newCursorPosition)
            }
            override fun beginBatchEdit() = delegate.beginBatchEdit()
            override fun endBatchEdit() = delegate.endBatchEdit()
        }
        val writer = ImeTextWriter { editor }
        writer.showPartial("hello")
        writer.commitFinal("hello world", nearSilent = false, pipeline = pipeline)
        assertEquals(1, batchDepthDuringRemove)
        assertEquals(1, batchDepthDuringCommit)
    }

    @Test
    fun `commitFinal in an empty field commits cleanly without leaving composing spans`() {
        val editor = FakeEditor("")
        val writer = writerFor(editor)
        writer.showPartial("hello")
        assertEquals("hello", editor.text)
        assertEquals("hello", editor.composingText)

        writer.commitFinal("Hello.", nearSilent = false, pipeline = pipeline)
        assertEquals("Hello.", editor.text)
        assertNull(editor.composing)
        assertEquals(0, editor.batchDepth)
    }

    @Test
    fun `commitFinal in a whitespace field commits cleanly`() {
        val editor = FakeEditor("   ")
        val writer = writerFor(editor)
        writer.showPartial("hello")
        assertEquals("   hello", editor.text)
        assertEquals("hello", editor.composingText)

        writer.commitFinal("Hello.", nearSilent = false, pipeline = pipeline)
        assertEquals("   Hello.", editor.text)
        assertNull(editor.composing)
        assertEquals(0, editor.batchDepth)
    }
}
