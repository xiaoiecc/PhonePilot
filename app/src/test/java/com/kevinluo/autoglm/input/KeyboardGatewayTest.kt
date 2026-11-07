package com.kevinluo.autoglm.input

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class FakeKeyboardEditor : KeyboardEditor {
    var selectionAccepted = true
    var empty: Boolean? = false
    var clearAccepted = true
    var textAccepted = true
    var endCalls = 0
    val commits = mutableListOf<String>()
    var onSelect: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null
    override fun beginBatchEdit() = true
    override fun endBatchEdit(): Boolean { endCalls++; return true }
    override fun selectAll(): Boolean { onSelect?.invoke(); return selectionAccepted }
    override fun isEmpty() = empty
    override fun commitText(text: String, cursorPosition: Int): Boolean {
        commits += text
        if (text.isEmpty()) onClear?.invoke()
        return if (text.isEmpty()) clearAccepted else textAccepted
    }
}

class KeyboardGatewayTest {
    @Test fun `replace aborts when selection or clear was not accepted`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val editor = FakeKeyboardEditor().apply { selectionAccepted = false }
        val token = gateway.activate(this, editor)
        assertFalse(gateway.input(token, "replacement", replace = true).success)
        assertTrue(editor.commits.isEmpty())
        editor.selectionAccepted = true
        editor.clearAccepted = false
        assertFalse(gateway.input(token, "replacement", replace = true).success)
        assertEquals(listOf(""), editor.commits)
    }

    @Test fun `empty field can be cleared when select all is unavailable`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val editor = FakeKeyboardEditor().apply { selectionAccepted = false; empty = true }
        val result = gateway.input(gateway.activate(this, editor), "text", replace = true)
        assertTrue(result.success)
        assertFalse(result.verified)
        assertEquals(InputDisposition.ACCEPTED, result.disposition)
        assertEquals(listOf("", "text"), editor.commits)
    }

    @Test fun `append preserves text and a stale editor token cannot target a new field`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val first = FakeKeyboardEditor()
        val old = gateway.activate(this, first)
        val second = FakeKeyboardEditor()
        val current = gateway.activate(this, second)
        gateway.deactivate(this, old)
        assertFalse(gateway.input(old, "wrong field", replace = false).success)
        assertTrue(gateway.input(current, " appended", replace = false).success)
        assertTrue(first.commits.isEmpty())
        assertEquals(listOf(" appended"), second.commits)
    }

    @Test fun `editor change during clearing prevents subsequent replacement commit`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val first = FakeKeyboardEditor()
        val next = FakeKeyboardEditor()
        val token = gateway.activate(this, first)
        first.onClear = { gateway.activate(this, next) }
        assertFalse(gateway.input(token, "replacement", replace = true).success)
        assertEquals(listOf(""), first.commits)
        assertTrue(next.commits.isEmpty())
    }

    @Test fun `cancellation between select and clear stops further input and closes the batch`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val editor = FakeKeyboardEditor()
        val token = gateway.activate(this, editor)
        lateinit var operation: Job
        editor.onSelect = { operation.cancel() }
        operation = launch { gateway.input(token, "cancelled", replace = true) }
        operation.join()
        assertTrue(operation.isCancelled)
        assertTrue(editor.commits.isEmpty())
        assertEquals(1, editor.endCalls)
    }

    @Test fun `missing endpoint and rejected commit never imply verified text`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        assertNull(gateway.awaitEditor(20))
        val editor = FakeKeyboardEditor().apply { textAccepted = false }
        val token = gateway.activate(this, editor)
        assertNull(gateway.awaitEditor(20, token))
        assertFalse(gateway.input(token, "text", replace = false).success)
        gateway.deactivate(this, token)
        assertFalse(gateway.input(token, "text", replace = false).success)
    }

    @Test fun `target guard failure after selection stops clear and after clear stops commit`() = runBlocking<Unit> {
        for (failureStage in listOf(TextEditStage.BEFORE_CLEAR, TextEditStage.BEFORE_COMMIT)) {
            val gateway = KeyboardGateway(Dispatchers.Unconfined)
            val editor = FakeKeyboardEditor()
            val seen = mutableListOf<TextEditStage>()
            val result = gateway.input(gateway.activate(this, editor), "replacement", true) { stage ->
                seen += stage
                stage != failureStage
            }
            assertFalse(result.success)
            assertEquals(if (failureStage == TextEditStage.BEFORE_CLEAR) emptyList<String>() else listOf(""), editor.commits)
            assertEquals(failureStage, seen.last())
            assertEquals(1, editor.endCalls)
        }
    }

    @Test fun `whole field append uses acknowledged replacement independent of editor caret`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        var field = "original"
        var selectionStart = 2
        var selectionEnd = 2
        val editor = object : KeyboardEditor {
            override fun beginBatchEdit() = true
            override fun endBatchEdit() = true
            override fun selectAll(): Boolean {
                selectionStart = 0
                selectionEnd = field.length
                return true
            }
            override fun isEmpty() = field.isEmpty()
            override fun commitText(text: String, cursorPosition: Int): Boolean {
                field = field.substring(0, selectionStart) + text + field.substring(selectionEnd)
                selectionStart += text.length
                selectionEnd = selectionStart
                return true
            }
        }
        val request = TextEditRequest("original" + " appended")
        val stages = mutableListOf<TextEditStage>()
        val result = gateway.input(gateway.activate(this, editor), request.text, replace = true) { stage ->
            stages += stage
            true
        }
        assertTrue(result.success)
        assertFalse(result.verified)
        assertEquals("original appended", field)
        assertEquals(field.length, selectionStart)
        assertEquals(field.length, selectionEnd)
        assertEquals(TextEditStage.values().toList(), stages)
    }
}
