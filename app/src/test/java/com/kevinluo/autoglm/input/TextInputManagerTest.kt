package com.kevinluo.autoglm.input

import com.kevinluo.autoglm.core.CommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TextInputManagerTest {
    private val originalIme = "com.example.input/.Keyboard"

    @Test fun `IME commands use Android registered short ID rather than equivalent full component`() = runBlocking<Unit> {
        // Match Android's InputMethodInfo map behavior: only its registered ID is accepted.
        // Do not use KeyboardHelper.IME_ID as the oracle, otherwise the original bug passes.
        val registeredId = "com.kevinluo.autoglm/.input.AutoGLMKeyboardService"
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val editor = FakeKeyboardEditor()
        val commands = mutableListOf<List<String>>()
        var selectedIme = originalIme
        val manager = TextInputManager({ argv, _ ->
            commands += argv
            when {
                argv.first() == "settings" -> CommandResult(0, selectedIme, "")
                argv.take(2) == listOf("ime", "enable") ->
                    if (argv.last() == registeredId) CommandResult(0, "", "")
                    else CommandResult(255, "", "Unknown input method")
                argv.take(2) == listOf("ime", "set") -> {
                    if (argv.last() !in listOf(registeredId, originalIme)) {
                        CommandResult(255, "", "Unknown input method")
                    } else {
                        selectedIme = argv.last()
                        if (selectedIme == registeredId) gateway.activate(this, editor)
                        CommandResult(0, "", "")
                    }
                }
                else -> error("Unexpected command: $argv")
            }
        }, gateway, Dispatchers.Unconfined)

        val result = manager.appendText("accepted once")

        assertTrue(result.success, result.message)
        assertEquals(listOf("accepted once"), editor.commits)
        assertEquals(originalIme, selectedIme)
        assertEquals(listOf("ime", "enable", registeredId), commands[1])
        assertEquals(listOf("ime", "set", registeredId), commands[2])
        assertEquals(listOf("ime", "set", originalIme), commands[4])
    }

    @Test fun `keyboard identity comparisons accept short and full component formats`() {
        val shortId = "com.kevinluo.autoglm/.input.AutoGLMKeyboardService"
        val fullId = "com.kevinluo.autoglm/com.kevinluo.autoglm.input.AutoGLMKeyboardService"
        assertEquals(shortId, KeyboardHelper.IME_ID)
        assertTrue(KeyboardHelper.isAutoGLMKeyboard(shortId))
        assertTrue(KeyboardHelper.isAutoGLMKeyboard(fullId))
        assertTrue(KeyboardHelper.sameIme(shortId, fullId))
        assertTrue(KeyboardHelper.sameIme(fullId, shortId))
        assertFalse(KeyboardHelper.sameIme(shortId, "com.other/.input.AutoGLMKeyboardService"))
        assertFalse(KeyboardHelper.isAutoGLMKeyboard("com.kevinluo.autoglm/.OtherService"))
    }

    @Test fun `replace clear failure prevents text commit and restores original IME`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val editor = FakeKeyboardEditor().apply { clearAccepted = false }
        var selectedIme = originalIme
        val commands = mutableListOf<List<String>>()
        val manager = TextInputManager({ argv, _ ->
            commands += argv
            when {
                argv.first() == "settings" -> CommandResult(0, selectedIme, "")
                argv.take(2) == listOf("ime", "set") -> {
                    selectedIme = argv.last()
                    if (selectedIme == KeyboardHelper.IME_ID) gateway.activate(this, editor)
                    CommandResult(0, "", "")
                }
                else -> CommandResult(0, "", "")
            }
        }, gateway, Dispatchers.Unconfined)
        val result = manager.typeText("replacement")
        assertFalse(result.success)
        assertEquals(listOf(""), editor.commits)
        assertEquals(originalIme, selectedIme)
        assertEquals(listOf(listOf("ime", "set", originalIme), listOf("settings", "get", "secure", "default_input_method")), commands.takeLast(2))
    }

    @Test fun `nonzero IME switch result is failure even with success looking stdout`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val commands = mutableListOf<List<String>>()
        var selectedIme = originalIme
        val manager = TextInputManager({ argv, _ ->
            commands += argv
            when {
                argv.first() == "settings" -> CommandResult(0, selectedIme, "")
                argv.last() == KeyboardHelper.IME_ID && argv[1] == "set" -> CommandResult(1, "normal-looking text", "")
                else -> {
                    if (argv.take(2) == listOf("ime", "set")) selectedIme = argv.last()
                    CommandResult(0, "", "")
                }
            }
        }, gateway, Dispatchers.Unconfined)
        assertFalse(manager.appendText("text").success)
        assertEquals(listOf(listOf("ime", "set", originalIme), listOf("settings", "get", "secure", "default_input_method")), commands.takeLast(2))
        assertEquals(originalIme, selectedIme)
        assertNull(gateway.currentToken())
    }

    @Test fun `cancelling readiness wait still restores IME in non cancellable context`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val selected = CompletableDeferred<Unit>()
        var ime = originalIme
        var restoreWasActive = false
        val manager = TextInputManager({ argv, _ ->
            if (argv.first() == "settings") CommandResult(0, ime, "") else {
                if (argv.take(2) == listOf("ime", "set")) {
                    ime = argv.last()
                    if (ime == KeyboardHelper.IME_ID) selected.complete(Unit)
                    else restoreWasActive = currentCoroutineContext().isActive
                }
                CommandResult(0, "", "")
            }
        }, gateway, Dispatchers.Unconfined)
        val operation = launch { manager.appendText("cancelled") }
        withTimeout(2000) { selected.await() }
        operation.cancelAndJoin()
        assertEquals(originalIme, ime)
        assertTrue(restoreWasActive)
        assertNull(gateway.currentToken())
    }

    @Test fun `accepted input remains acknowledged if restoration fails and must not be retried blindly`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val editor = FakeKeyboardEditor()
        var ime = originalIme
        val manager = TextInputManager({ argv, _ ->
            if (argv.first() == "settings") CommandResult(0, ime, "") else {
                if (argv.take(2) == listOf("ime", "set")) {
                    if (argv.last() == originalIme) return@TextInputManager CommandResult(1, "", "restore failed")
                    ime = argv.last()
                    gateway.activate(this, editor)
                }
                CommandResult(0, "", "")
            }
        }, gateway, Dispatchers.Unconfined)
        val result = manager.appendText("accepted once")
        assertTrue(result.success)
        assertFalse(result.verified)
        assertFalse(result.restorationSucceeded)
        assertEquals(listOf("accepted once"), editor.commits)
    }

    @Test fun `invalid IME identifier never becomes a command argument`() = runBlocking<Unit> {
        val commands = mutableListOf<List<String>>()
        val manager = TextInputManager({ argv, _ -> commands += argv; CommandResult(0, "com.example/.Keyboard; other-command", "") },
            KeyboardGateway(Dispatchers.Unconfined), Dispatchers.Unconfined)
        assertFalse(manager.typeText("never typed").success)
        assertEquals(1, commands.size)
        assertFalse(KeyboardHelper.isAutoGLMKeyboard("com.kevinluo.autoglm/OtherService"))
        assertTrue(KeyboardHelper.isAutoGLMKeyboard(KeyboardHelper.IME_ID))
    }

    @Test fun `post readiness target guard rejects IME induced target changes and restores original IME`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val editor = FakeKeyboardEditor()
        var ime = originalIme
        var targetValid = true
        var guardCalls = 0
        val manager = TextInputManager({ argv, _ ->
            if (argv.first() == "settings") CommandResult(0, ime, "") else {
                if (argv.take(2) == listOf("ime", "set")) {
                    ime = argv.last()
                    if (ime == KeyboardHelper.IME_ID) {
                        targetValid = false
                        gateway.activate(this, editor)
                    }
                }
                CommandResult(0, "", "")
            }
        }, gateway, Dispatchers.Unconfined)
        val result = manager.editText(TextEditRequest("must not overwrite", guard = {
            guardCalls++
            assertEquals(KeyboardHelper.IME_ID, ime)
            targetValid
        }))
        assertFalse(result.success)
        assertEquals(1, guardCalls)
        assertTrue(editor.commits.isEmpty())
        assertEquals(originalIme, ime)
    }

    @Test fun `newly focused target cannot reuse previous editor when our IME is already selected`() = runBlocking<Unit> {
        val gateway = KeyboardGateway(Dispatchers.Unconfined)
        val oldEditor = FakeKeyboardEditor()
        val stale = gateway.activate(this, oldEditor)
        val readIme = CompletableDeferred<Unit>()
        val manager = TextInputManager({ _, _ ->
            readIme.complete(Unit)
            CommandResult(0, KeyboardHelper.IME_ID, "")
        }, gateway, Dispatchers.Unconfined)
        var result: InputResult? = null
        val operation = launch {
            result = manager.editText(TextEditRequest("new target", staleEditorGeneration = stale.generation))
        }
        withTimeout(2000) { readIme.await() }
        assertTrue(oldEditor.commits.isEmpty())
        assertNull(result)
        val newEditor = FakeKeyboardEditor()
        gateway.activate(this, newEditor)
        withTimeout(2000) { operation.join() }
        assertTrue(result?.success == true)
        assertTrue(oldEditor.commits.isEmpty())
        assertEquals(listOf("", "new target"), newEditor.commits)
    }
}
