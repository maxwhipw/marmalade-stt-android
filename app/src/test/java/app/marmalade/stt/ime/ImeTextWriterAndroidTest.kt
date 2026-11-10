package app.marmalade.stt.ime

import android.app.Application
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import app.marmalade.stt.textproc.TextPipeline
import app.marmalade.stt.textproc.TextProcConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the Android InputConnection adapter against a framework EditText. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImeTextWriterAndroidTest {
    private val pipeline = TextPipeline(TextProcConfig())

    private fun field(initial: String) = EditText(RuntimeEnvironment.getApplication()).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        setText(initial)
        setSelection(text.length)
    }

    private fun writer(field: EditText): ImeTextWriter {
        val connection = requireNotNull(field.onCreateInputConnection(EditorInfo())).asEditorConnection()
        return ImeTextWriter { connection }
    }

    @Test
    fun `framework editor watchers see a direct replacement with no intermediate blank`() {
        for (initial in listOf("", " ", "   ", "a", ".")) {
            val field = field(initial)
            val writer = writer(field)
            writer.showPartial("hello")
            val observed = mutableListOf<String>()
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    observed += s.toString()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })

            assertTrue(writer.commitFinal("Hello.", nearSilent = false, pipeline = pipeline))
            writer.clearPartial()

            val expected = initial + (if (initial.isNotEmpty() && !initial.last().isWhitespace()) " " else "") + "Hello."
            assertEquals(listOf(expected), observed)
            assertEquals(expected, field.text.toString())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(field.text))
            assertEquals(expected.length, field.selectionStart)
        }
    }

    @Test
    fun `batching alone does not hide explicit removal from framework text watchers`() {
        val field = field("")
        val connection = requireNotNull(field.onCreateInputConnection(EditorInfo()))
        connection.setComposingText("hello", 1)
        val observed = mutableListOf<String>()
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                observed += s.toString()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        connection.beginBatchEdit()
        try {
            connection.setComposingText("", 1)
            connection.finishComposingText()
            connection.commitText("Hello.", 1)
        } finally {
            connection.endBatchEdit()
        }

        assertEquals(listOf("", "Hello."), observed)
    }

    @Test
    fun `framework editor applies commands against committed context`() {
        val field = field("One. Two.")
        val writer = writer(field)
        writer.showPartial("scratch that")

        assertTrue(writer.commitFinal("Scratch that.", nearSilent = false, pipeline = pipeline))
        writer.clearPartial()
        assertEquals("One.", field.text.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(field.text))
    }

    @Test
    fun `framework editor cancellation removes only the partial`() {
        val field = field("Keep this.")
        val writer = writer(field)
        writer.showPartial("unfinished")
        writer.clearPartial()

        assertEquals("Keep this.", field.text.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(field.text))
    }
}
