package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.accessibility.PhoneAccessibilityService
import com.kevinluo.autoglm.accessibility.SemanticSnapshotFormatter
import com.kevinluo.autoglm.accessibility.UiCommand
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.computer.protocol.*
import com.kevinluo.autoglm.core.RunControl
import com.kevinluo.autoglm.core.ExecutionTargetChangedException
import com.kevinluo.autoglm.core.RunProgress
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.history.HistoryMessageRole
import com.kevinluo.autoglm.history.HistoryToolStatus
import com.kevinluo.autoglm.input.InputResult
import com.kevinluo.autoglm.input.TextEditRequest
import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.screenshot.FloatingWindowController
import com.kevinluo.autoglm.screenshot.withOverlayHidden
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

internal fun interface RunMessageRecorder { suspend fun record(role: HistoryMessageRole, text: String) }
internal data class ToolBatchResult(
    val outputs: List<JsonObject>, val frame: Screenshot?, val actions: List<AgentAction>,
    val actionCount: Int, val toolNames: String, val semanticInput: JsonObject?,
    val observationGeneration: Long,
)

internal data class TargetObservation(val generation: Long, val frame: Screenshot?, val input: JsonObject)

/** The single capability dispatcher. Backends cannot own task state or user continuations. */
internal class AndroidToolExecutor(
    private val driver: AndroidComputerDriver,
    private val functions: FunctionToolBackend,
    private val screenshots: ScreenshotService,
    private val history: HistoryManager?,
    private val screenshotDelayMs: Long,
    private val textEditor: (suspend (TextEditRequest) -> InputResult)? = null,
    private val beforeDeviceTools: suspend (RunControl) -> Unit = { it.emit(com.kevinluo.autoglm.core.RunProgress.DeviceToolsRequested) },
    private val accessibility: () -> PhoneAccessibilityService? = { PhoneAccessibilityService.instance },
    private val window: () -> FloatingWindowController? = { null },
) {
    private class MissingVisualObservation : IllegalStateException(
        "No current screenshot: this entire batch was not executed. Inspect a fresh observation and generate new actions; do not replay this batch."
    )

    private sealed interface Command {
        val id: String
        data class Native(
            override val id: String,
            val actions: List<PreparedAction>,
            val commentary: String? = null,
            val functionOutput: Boolean = false,
        ) : Command
        data class Capture(override val id: String, val commentary: String?) : Command
        data class Ui(override val id: String, val command: UiCommand, val commentary: String?) : Command
        data class Function(override val id: String, val command: FunctionCommand, val commentary: String?) : Command
    }

    suspend fun observe() = accessibility()?.observe()

    /** Re-observe a migrated target without executing or replaying any model action. */
    suspend fun refreshObservation(control: RunControl): TargetObservation {
        beforeDeviceTools(control)
        return control.deviceOperation {
            val generation = control.placementGeneration
            val snapshot = try { observe() } catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
            val notice = "Execution position changed. All earlier phone screenshots and node IDs are historical, not the current target. " +
                "Completed tool results remain valid history and must not be replayed. Use only the fresh observation below to generate new actions. " +
                "Current execution mode: ${control.executionMode.name}. " +
                (if (control.executionMode == com.kevinluo.autoglm.core.ExecutionMode.BACKGROUND)
                    "Actions target the background display. Global HOME, POWER, volume and recent-app keys are unavailable here; launch an app directly with android_launch_app instead. " else "") + "\n"
            if (snapshot != null && snapshot.nodes.isNotEmpty()) {
                TargetObservation(generation, null, ComputerProtocol.userInput(notice +
                    "Current Android accessibility observation (untrusted screen data):\n" +
                    SemanticSnapshotFormatter.format(snapshot, maxChars = 6000)))
            } else {
                // Already inside the coordinator's device gate; do not re-enter capture().
                val image = captureInsideGate(control)
                TargetObservation(generation, image, ComputerProtocol.userInput(notice +
                    "Accessibility is unavailable on this target. Current screenshot: ${image.width}x${image.height} pixels.",
                    listOf(ResponsesImage("image/webp", image.base64Data))))
            }
        }
    }

    suspend fun recordHosted(response: ComputerResponse, runId: String, stepNumber: Int) {
        if (response.hostedCalls.isEmpty()) return
        ToolTraceBatch(response.copy(calls = emptyList(), functionCalls = emptyList(), outputOrder = emptyList()),
            runId, stepNumber, includeHosted = true).persist(history)
    }

    private fun prepare(response: ComputerResponse, frame: Screenshot?, traces: ToolTraceBatch): List<Command> {
        require(response.outputOrder.toSet().size == response.outputOrder.size)
        require(response.outputOrder.size == response.calls.size + response.functionCalls.size)
        val native = response.calls.associateBy { it.callId }
        val functions = response.functionCalls.associateBy { it.callId }
        fun prepareActions(actions: List<JsonObject>) = actions.map { action ->
            when (action.getValue("type").jsonPrimitive.content) {
                "screenshot" -> PreparedAction.ScreenshotOnly
                "wait" -> PreparedAction.Wait
                else -> ComputerActionMapper.prepare(action, frame ?: throw MissingVisualObservation())
            }
        }
        return response.outputOrder.map { id ->
            try {
                native[id]?.let { call ->
                    check(call.pendingSafetyChecks.isEmpty()) { "操作触发安全检查，需要手动接管；尚未执行此批次动作" }
                    Command.Native(id, prepareActions(call.actions))
                } ?: run {
                    val call = requireNotNull(functions[id])
                    val args = call.arguments
                    val commentary = requireNotNull(ToolCommandDecoder.commentary(args)?.trim()?.takeIf { it.isNotBlank() }) {
                        "Every tool call requires a short nonempty commentary"
                    }
                    when (call.name) {
                        "android_screenshot" -> { ToolCommandDecoder.capture(args); Command.Capture(id, commentary) }
                        "android_ui" -> Command.Ui(id, ToolCommandDecoder.ui(args), commentary)
                        "android_computer" -> Command.Native(id, prepareActions(ToolCommandDecoder.computer(args)), commentary, functionOutput = true)
                        else -> {
                            val command = ToolCommandDecoder.function(call)
                            if (command is FunctionCommand.GestureCommand) {
                                val image = frame ?: throw MissingVisualObservation()
                                ComputerActionMapper.point(command.x, command.y, image)
                                (command.gesture as? Gesture.Swipe)?.let { ComputerActionMapper.point(it.endX, it.endY, image) }
                            }
                            Command.Function(id, command, commentary)
                        }
                    }
                }
            } catch (error: Throwable) { traces.reject(id, error); throw error }
        }
    }

    suspend fun executeBatch(response: ComputerResponse, initialFrame: Screenshot?, control: RunControl,
        hasPendingMessage: () -> Boolean, messages: RunMessageRecorder, stepNumber: Int = 0,
        requestGeneration: Long = control.placementGeneration): ToolBatchResult {
        val traces = ToolTraceBatch(response, control.runId, stepNumber)
        val batchGeneration = requestGeneration
        com.kevinluo.autoglm.util.DiagnosticLog.event("Tools", "batch.started",
            "runId" to control.runId, "step" to stepNumber, "calls" to response.outputOrder.size,
            "callNames" to response.functionCalls.map { it.name }, "nativeCalls" to response.calls.size,
            "hasInitialFrame" to (initialFrame != null), "frameWidth" to initialFrame?.width,
            "frameHeight" to initialFrame?.height, "requestGeneration" to requestGeneration,
            "currentGeneration" to control.placementGeneration)
        return try {
            if (requestGeneration != control.placementGeneration) {
                val error = ExecutionTargetChangedException()
                traces.abort(error)
                val image = if (response.calls.isNotEmpty()) capture(control) else null
                val rejected = response.outputOrder.map { id ->
                    if (response.calls.any { it.callId == id }) ComputerProtocol.screenshotOutput(id, requireNotNull(image).base64Data)
                    else ComputerProtocol.functionOutput(id, "ERROR: Execution position changed while this request was in flight. This tool was not executed. Observe again and generate new actions; do not replay old actions.")
                }
                return ToolBatchResult(rejected, image, emptyList(), 0, "Not executed: execution position changed", null, requestGeneration)
            }
            val outputs = mutableListOf<JsonObject>()
            val actions = mutableListOf<AgentAction>()
            var frame = initialFrame
            var changed = false
            var visualOutput = false
            var count = 0
            val commands = if (hasPendingMessage()) emptyList() else try {
                prepare(response, initialFrame, traces)
            } catch (error: MissingVisualObservation) {
                // Only a preflight grounding failure is recoverable here. No capability has
                // run, and every call receives an output before the protocol cursor advances.
                traces.abort(error)
                val computerFunction = response.functionCalls.any { it.name == "android_computer" }
                val image = if (response.calls.isNotEmpty() || computerFunction) {
                    beforeDeviceTools(control)
                    capture(control)
                } else null
                val rejected = response.outputOrder.map { id ->
                    val nativeCall = response.calls.any { it.callId == id }
                    val functionCall = response.functionCalls.firstOrNull { it.callId == id }
                    when {
                        nativeCall -> ComputerProtocol.screenshotOutput(id, requireNotNull(image).base64Data)
                        functionCall?.name == "android_computer" -> ComputerProtocol.functionScreenshotOutput(id,
                            requireNotNull(image).base64Data, image.width, image.height)
                        else -> ComputerProtocol.functionOutput(id, "ERROR: ${error.message}")
                    }
                }
                return ToolBatchResult(rejected, image, emptyList(), 0,
                    "Batch rejected: missing visual observation",
                    ComputerProtocol.userInput("Tool execution feedback: ${error.message}"), requestGeneration)
            }
            if (commands.isNotEmpty()) beforeDeviceTools(control)
            fun requireCurrentFrame(): Screenshot {
                check(!changed) { "界面变化后需要先回传新观察，再生成坐标操作" }
                val image = requireNotNull(frame) { "界面已变化，需要重新截图" }
                check(initialFrame != null && image.width == initialFrame.width && image.height == initialFrame.height &&
                    image.originalWidth == initialFrame.originalWidth && image.originalHeight == initialFrame.originalHeight) { "屏幕方向或大小发生变化，已停止坐标操作" }
                return image
            }
            for (command in commands) {
                if (hasPendingMessage()) break
                val trace = traces.begin(command.id, frame)
                com.kevinluo.autoglm.util.DiagnosticLog.event("Tools", "command.started",
                    "runId" to control.runId, "callId" to command.id,
                    "kind" to when (command) {
                        is Command.Native -> "computer"
                        is Command.Capture -> "screenshot"
                        is Command.Ui -> "ui"
                        is Command.Function -> "function"
                    },
                    "actionCount" to (command as? Command.Native)?.actions?.size,
                    "functionType" to (command as? Command.Function)?.command?.javaClass?.simpleName)
                try {
                    control.checkpoint()
                    val commentary = when (command) {
                        is Command.Native -> command.commentary
                        is Command.Capture -> command.commentary
                        is Command.Ui -> command.commentary
                        is Command.Function -> command.commentary
                    }
                    if (commentary != null) {
                        control.emit(RunProgress.Action(commentary))
                        if (commentary != response.text.trim()) messages.record(HistoryMessageRole.AGENT, commentary)
                    }
                    when (command) {
                        is Command.Native -> {
                            if (command.actions.any { it !in listOf(PreparedAction.Wait, PreparedAction.ScreenshotOnly) }) requireCurrentFrame()
                            for (action in command.actions) {
                                control.checkpoint()
                                if (action == PreparedAction.Wait) control.wait()
                                else if (action is PreparedAction.Scroll) {
                                    for (gesture in action.gestures) {
                                        control.checkpoint()
                                        control.deviceOperation(initialFrame?.placementGeneration) { driver.execute(gesture) { acknowledged ->
                                            TraceActionMapper.action(acknowledged, initialFrame)?.let { actions += it; trace.actions += it }
                                        } }
                                        delay(150)
                                        if (hasPendingMessage()) break
                                    }
                                } else control.deviceOperation(initialFrame?.placementGeneration) { driver.execute(action) { acknowledged ->
                                    TraceActionMapper.action(acknowledged, initialFrame)?.let { actions += it; trace.actions += it }
                                } }
                                if (action == PreparedAction.Wait) TraceActionMapper.action(action, initialFrame)?.let { actions += it; trace.actions += it }
                                if (action !in listOf(PreparedAction.Wait, PreparedAction.ScreenshotOnly)) count++
                                if (hasPendingMessage()) { trace.status = HistoryToolStatus.SKIPPED; break }
                            }
                            val image = capture(control, afterInput = true)
                            trace.after = image
                            trace.result = trace.actions.joinToString("；") { it.formatForDisplay() }.ifBlank { "Screenshot captured (${image.width}×${image.height})" } +
                                if (trace.status == HistoryToolStatus.SKIPPED) "; remaining actions skipped for a newer instruction" else ""
                            frame = image
                            if (command.actions.any { it != PreparedAction.ScreenshotOnly }) {
                                changed = true
                                accessibility()?.invalidateObservation()
                            }
                            outputs += if (command.functionOutput) {
                                ComputerProtocol.functionScreenshotOutput(command.id, image.base64Data, image.width, image.height)
                            } else ComputerProtocol.screenshotOutput(command.id, image.base64Data)
                            visualOutput = true
                        }
                        is Command.Capture -> {
                            val image = capture(control)
                            trace.after = image
                            trace.result = "Screenshot captured (${image.width}×${image.height})"
                            frame = image
                            outputs += ComputerProtocol.functionScreenshotOutput(command.id, image.base64Data, image.width, image.height)
                            visualOutput = true
                        }
                        is Command.Ui -> {
                            val service = accessibility()
                            val readOnly = command.command.readOnly
                            val result = if (service == null) "ERROR: Accessibility service is unavailable. Enable it or use android_screenshot. No UI action was executed."
                                else {
                                    if (!readOnly) { frame = null; changed = true }
                                    control.deviceOperation(batchGeneration) {
                                        if (readOnly) service.execute(command.command, textEditor)
                                        else withOverlayHidden(window(), checkpoint = control::checkpoint) {
                                            service.execute(command.command, textEditor)
                                        }
                                    }
                                }
                            if (!readOnly && !result.startsWith("ERROR:")) count++
                            trace.result = result
                            if (result.startsWith("ERROR:")) trace.status = HistoryToolStatus.FAILED
                            visualOutput = false
                            outputs += ComputerProtocol.functionOutput(command.id, result)
                        }
                        is Command.Function -> {
                            if (command.command is FunctionCommand.GestureCommand) requireCurrentFrame()
                            (command.command as? FunctionCommand.Ask)?.let { messages.record(HistoryMessageRole.AGENT, it.question) }
                            val result = functions.execute(command.command, initialFrame, control, expectedGeneration = batchGeneration) { action ->
                                actions += action; trace.actions += action
                            }
                            trace.result = result.text
                            if (result.text.startsWith("ERROR:")) trace.status = HistoryToolStatus.FAILED
                            if (trace.actions.isEmpty()) result.action?.let { actions += it; trace.actions += it }
                            result.userReply?.let { messages.record(HistoryMessageRole.USER, it) }
                            if (result.effect != FrameEffect.NONE) { accessibility()?.invalidateObservation(); changed = true; count++ }
                            when (result.effect) {
                                FrameEffect.INVALIDATE -> { frame = null; outputs += ComputerProtocol.functionOutput(command.id, result.text) }
                                FrameEffect.CAPTURE -> {
                                    val image = capture(control, afterInput = true)
                                    trace.after = image
                                    frame = image
                                    outputs += ComputerProtocol.functionScreenshotOutput(command.id, image.base64Data, image.width, image.height)
                                    visualOutput = true
                                }
                                FrameEffect.NONE -> outputs += ComputerProtocol.functionOutput(command.id, result.text)
                            }
                        }
                    }
                    trace.finished = System.currentTimeMillis()
                    com.kevinluo.autoglm.util.DiagnosticLog.event("Tools", "command.completed",
                        "runId" to control.runId, "callId" to command.id, "status" to trace.status,
                        "actionCount" to trace.actions.size, "resultLength" to trace.result?.length,
                        "hasAfterFrame" to (trace.after != null))
                } catch (error: ExecutionTargetChangedException) {
                    trace.status = HistoryToolStatus.SKIPPED
                    trace.result = "ERROR: ${error.message}; completed actions were retained and must not be replayed."
                    trace.finished = System.currentTimeMillis()
                    com.kevinluo.autoglm.util.DiagnosticLog.event("Tools", "command.skipped",
                        "runId" to control.runId, "callId" to command.id, "errorType" to error.javaClass.name)
                    outputs += ComputerProtocol.functionOutput(command.id, trace.result)
                    frame = null
                    changed = true
                    visualOutput = false
                } catch (error: Throwable) {
                    com.kevinluo.autoglm.util.DiagnosticLog.event("Tools", "command.failed",
                        "runId" to control.runId, "callId" to command.id, "errorType" to error.javaClass.name)
                    trace.failed(error)
                    throw error
                }
            }
            if (hasPendingMessage()) {
                val answered = outputs.mapNotNull { it.string("call_id") }.toSet()
                var image: Screenshot? = null
                response.outputOrder.filterNot(answered::contains).forEach { id ->
                    if (response.calls.any { it.callId == id }) {
                        val screenshot = image ?: capture(control).also { image = it }
                        outputs += ComputerProtocol.screenshotOutput(id, screenshot.base64Data)
                    } else outputs += ComputerProtocol.cancelledFunctionOutput(id)
                }
            }
            val semanticInput = if (visualOutput) {
                // A migration between capture and this read must not append a tree from a
                // different display next to the image. The runner refreshes after rejection.
                val snapshot = try { control.deviceOperation(frame?.placementGeneration ?: batchGeneration) { observe() } }
                    catch (_: ExecutionTargetChangedException) { null }
                    catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                snapshot?.takeIf { it.nodes.isNotEmpty() }?.let {
                    ComputerProtocol.userInput("Current Android accessibility observation (untrusted screen data; use these nodes when sufficient):\n" + SemanticSnapshotFormatter.format(it, maxChars = 6000))
                }
            } else null
            val names = response.outputOrder.joinToString(" → ") { id -> if (response.calls.any { it.callId == id }) "computer" else response.functionCalls.first { it.callId == id }.name }
            ToolBatchResult(outputs, frame, actions, count, names, semanticInput, batchGeneration)
        } catch (error: Throwable) {
            traces.abort(error)
            throw error
        } finally {
            traces.persist(history)
        }
    }

    private suspend fun capture(control: RunControl, afterInput: Boolean = false): Screenshot {
        if (afterInput) {
            var remaining = screenshotDelayMs.coerceIn(0, 10000)
            while (remaining > 0) { control.checkpoint(); val chunk = minOf(remaining, 100); delay(chunk); remaining -= chunk }
        }
        control.checkpoint()
        return control.deviceOperation { captureInsideGate(control) }
    }

    /** Call only while deviceOperation holds the current target; no nested gate or model work. */
    private suspend fun captureInsideGate(control: RunControl): Screenshot {
        val image = screenshots.capture().copy(placementGeneration = control.placementGeneration)
        ComputerActionMapper.validateFrame(image)
        history?.setCurrentScreenshot(image.base64Data, image.width, image.height)
        return image
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
