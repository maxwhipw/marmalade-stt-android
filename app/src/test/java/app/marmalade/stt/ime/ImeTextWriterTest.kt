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

    private class FakeEditor(text: String = "", private val maxLength: Int = Int.MAX_VALUE) : EditorConnection {
        private val buf = StringBuilder(text)
        var cursor = text.length
        var composing: IntRange? = null
        var batchDepth = 0
        var nullTextBefore = false
        var acceptsComposing = true
        val calls = mutableListOf<String>()

        val text: String get() = buf.toString()
        val composingText: String? get() = composing?.let { buf.substring(it.first, it.last + 1) }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            if (nullTextBefore) null else buf.substring(maxOf(0, cursor - n), cursor)

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "setComposingText($text)"
            if (!acceptsComposing) return false
            val replacedLength = composing?.let { it.last + 1 - it.first } ?: 0
            val available = (maxLength - (buf.length - replacedLength)).coerceAtLeast(0)
            val accepted = text.take(available)
            val start = replaceComposingOrInsert(accepted)
            composing = start until start + accepted.length
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

    /** Queues batched edits; observing a blank value invalidates this simulated connection. */
    private class ReactiveEditor(private val delegate: FakeEditor) : EditorConnection by delegate {
        private var batchDepth = 0
        private val pending = mutableListOf<() -> Unit>()
        val publishedTexts = mutableListOf<String>()
        var active = true
            private set

        private fun publish() {
            publishedTexts += delegate.text
            if (delegate.text.isBlank()) active = false
        }

        private fun edit(operation: () -> Unit): Boolean {
            if (!active) return false
            if (batchDepth > 0) {
                pending += operation
            } else {
                operation()
                publish()
            }
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
            edit { delegate.setComposingText(text, newCursorPosition) }
        override fun finishComposingText() = edit { delegate.finishComposingText() }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
            edit { delegate.deleteSurroundingText(beforeLength, afterLength) }
        override fun commitText(text: CharSequence, newCursorPosition: Int) =
            edit { delegate.commitText(text, newCursorPosition) }

        override fun beginBatchEdit(): Boolean {
            if (!active) return false
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            batchDepth--
            if (batchDepth == 0 && pending.isNotEmpty()) {
                pending.forEach { it() }
                pending.clear()
                publish()
            }
            return batchDepth > 0
        }

        fun closeConnection() {
            pending.clear()
            active = false
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
    fun `scratch that uses committed context when the editor truncates partials`() {
        val editor = FakeEditor("One. Two.", maxLength = 13)
        val writer = writerFor(editor)
        writer.showPartial("scratch")
        writer.showPartial("scratch that")
        assertEquals("One. Two. scr", editor.text)

        assertTrue(writer.commitFinal("Scratch that.", nearSilent = false, pipeline = pipeline))
        assertEquals("One.", editor.text)
        assertNull(editor.composing)
        assertEquals(0, editor.batchDepth)
    }

    @Test
    fun `a rejected composing request does not freeze context or remove a nonexistent partial`() {
        val editor = FakeEditor("One.").apply { acceptsComposing = false }
        val writer = writerFor(editor)
        writer.showPartial("hello")
        editor.commitText(" Two.", 1)
        editor.calls.clear()

        assertTrue(writer.commitFinal("Scratch that.", nearSilent = false, pipeline = pipeline))
        assertEquals("One.", editor.text)
        assertFalse(editor.calls.any { it.startsWith("setComposingText") || it == "finishComposingText" })
    }

    @Test
    fun `reactive editor rejects commits after an unbatched blank value`() {
        val editor = ReactiveEditor(FakeEditor())
        editor.setComposingText("hello", 1)
        editor.setComposingText("", 1)

        assertFalse(editor.active)
        assertFalse(editor.commitText("Hello.", 1))
        assertEquals(listOf("hello", ""), editor.publishedTexts)
    }

    @Test
    fun `reactive editor observes the final before connection teardown without an intermediate blank`() {
        for (initial in listOf("", "   ", "a", ".")) {
            val buffer = FakeEditor(initial)
            val editor = ReactiveEditor(buffer)
            val writer = ImeTextWriter { editor }
            writer.showPartial("hello")
            val partial = buffer.text

            assertTrue(writer.commitFinal("Hello.", nearSilent = false, pipeline = pipeline))
            writer.clearPartial() // Session cleanup before switching keyboards.
            assertTrue(editor.active)
            editor.closeConnection()

            val expected = initial + (if (initial.isNotEmpty() && !initial.last().isWhitespace()) " " else "") + "Hello."
            assertEquals(expected, buffer.text)
            assertEquals(listOf(partial, expected), editor.publishedTexts)
            assertNull(buffer.composing)
        }
    }

    @Test
    fun `reactive editor applies scratch that with deferred composing removal`() {
        val buffer = FakeEditor("One. Two.")
        val editor = ReactiveEditor(buffer)
        val writer = ImeTextWriter { editor }
        writer.showPartial("scratch that")

        assertTrue(writer.commitFinal("Scratch that.", nearSilent = false, pipeline = pipeline))
        assertEquals("One.", buffer.text)
        assertEquals(listOf("One. Two. scratch that", "One."), editor.publishedTexts)
        assertNull(buffer.composing)
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
