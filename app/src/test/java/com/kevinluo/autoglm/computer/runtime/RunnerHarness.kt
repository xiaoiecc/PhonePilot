package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.accessibility.PhoneAccessibilityService
import com.kevinluo.autoglm.agent.AgentConfig
import com.kevinluo.autoglm.agent.PhoneAgent
import com.kevinluo.autoglm.app.AppInfo
import com.kevinluo.autoglm.app.AppResolver
import com.kevinluo.autoglm.computer.protocol.ComputerCall
import com.kevinluo.autoglm.computer.protocol.ComputerFunctionCall
import com.kevinluo.autoglm.computer.protocol.ComputerResponse
import com.kevinluo.autoglm.computer.protocol.ResponsesComputerClient
import com.kevinluo.autoglm.core.*
import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.input.InputResult
import com.kevinluo.autoglm.input.TextInputManager
import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.util.HumanizedSwipeGenerator
import com.kevinluo.autoglm.util.Point
import com.kevinluo.autoglm.util.SwipePath
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*

/** Real coordinator, Responses runner, dispatcher, function backend and input driver. Only IO is mocked. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RunnerHarness(val scope: TestScope, maxSteps: Int = 20, service: PhoneAccessibilityService? = null,
    beforeDeviceTools: suspend (RunControl) -> Unit = { it.emit(RunProgress.DeviceToolsRequested) },
    window: com.kevinluo.autoglm.screenshot.FloatingWindowController? = null) {
    val client = mockk<ResponsesComputerClient>(relaxed = true)
    val device = mockk<DeviceExecutor>()
    val input = mockk<TextInputManager>()
    val apps = mockk<AppResolver>()
    val swipes = mockk<HumanizedSwipeGenerator>()
    val screenshots = mockk<ScreenshotService>()
    val history = mockk<HistoryManager>(relaxed = true)
    val frame = Screenshot("UklGR-example", 720, 1280, 1080, 1920)
    val completions = mutableListOf<RunCompletion>()
    val runner: PhoneAgent
    val coordinator: RunCoordinator

    init {
        coEvery { device.tap(any(), any()) } returns CommandResult(0)
        coEvery { device.doubleTap(any(), any()) } returns CommandResult(0)
        coEvery { device.longPress(any(), any(), any()) } returns CommandResult(0)
        coEvery { device.straightSwipe(any(), any(), any(), any(), any()) } returns CommandResult(0)
        coEvery { device.swipe(any(), any()) } returns CommandResult(0)
        coEvery { device.pressKey(any()) } returns CommandResult(0)
        coEvery { device.launchApp(any()) } returns CommandResult(0)
        coEvery { input.typeText(any()) } returns InputResult.accepted("replace accepted")
        coEvery { input.appendText(any()) } returns InputResult.accepted("append accepted")
        every { apps.resolvePackageName(any()) } returns "com.example"
        every { apps.getAllLaunchableApps() } returns listOf(AppInfo("Example", "com.example"))
        every { swipes.generatePath(any(), any(), any(), any(), any(), any()) } answers {
            SwipePath(listOf(Point(firstArg(), secondArg()), Point(thirdArg(), arg(3))), 400)
        }
        every { swipes.generateLinearPath(any(), any(), any(), any(), any(), any()) } answers {
            SwipePath(listOf(Point(firstArg(), secondArg()), Point(thirdArg(), arg(3))), 400)
        }
        coEvery { screenshots.capture() } returns frame
        val driver = AndroidComputerDriver(device, input)
        val functions = FunctionToolBackend(device, input, apps, swipes) { null }
        val dispatcher = AndroidToolExecutor(driver, functions, screenshots, history, 0,
            beforeDeviceTools = beforeDeviceTools, accessibility = { service }, window = { window })
        runner = PhoneAgent(dispatcher, AgentConfig(maxSteps = maxSteps, screenshotDelayMs = 0), history, client,
            imageLoader = { com.kevinluo.autoglm.computer.protocol.ResponsesImage(it.mimeType,
                "attachment-${java.io.File(it.path).name}") })
        coordinator = RunCoordinator(scope, { runner }, onFinalize = { _, outcome ->
            outcome.session?.let(history::updateConversationSession)
            history.completeTask(outcome.success, outcome.message)
        }, clockMs = { scope.testScheduler.currentTime })
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            coordinator.completions.collect { completions += it }
        }
    }

    fun respond(vararg responses: ComputerResponse) { coEvery { client.request(any(), any()) } returnsMany responses.toList() }
    fun begin(text: String = "task", forceNew: Boolean = false) = begin(ChatInput(text), forceNew)
    fun begin(input: ChatInput, forceNew: Boolean = false) {
        assertTrue(coordinator.submit(input.text, forceNew, input.images))
        scope.advanceTimeBy(300)
        scope.runCurrent()
    }
    fun settle(): RunOutcome {
        scope.advanceUntilIdle()
        assertFalse(coordinator.isBusy, "A test must resolve its interaction/request before settling")
        return completions.last().outcome
    }
    fun run(text: String = "task", forceNew: Boolean = false): RunOutcome = run(ChatInput(text), forceNew)
    fun run(input: ChatInput, forceNew: Boolean = false): RunOutcome { begin(input, forceNew); return settle() }
}

internal fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
internal fun function(id: String, name: String, arguments: String) = ComputerFunctionCall(id, name,
    JsonObject(mapOf("commentary" to JsonPrimitive("正在执行 $name")) + obj(arguments)))
internal fun functionResponse(id: String, vararg calls: ComputerFunctionCall, text: String = "") = ComputerResponse(id, emptyList(), text, calls.toList())
internal fun screenshotRequest(id: String = "r1", callId: String = "shot") = functionResponse(id, function(callId, "android_screenshot", "{}"))
internal fun finalResponse(id: String = "final", text: String = "done") = ComputerResponse(id, emptyList(), text)
internal fun nativeResponse(id: String, callId: String, vararg actions: String) =
    ComputerResponse(id, listOf(ComputerCall(callId, actions.map(::obj), emptyList())), "")
internal fun tapAction(x: Int = 10, y: Int = 10) = """{"type":"click","button":"left","x":$x,"y":$y}"""
internal fun gesture(id: String = "gesture", operation: String = "long_press", x: Int = 10, y: Int = 20,
    endX: Int? = null, endY: Int? = null, confirmation: String? = null) = ComputerFunctionCall(id, "android_gesture", buildJsonObject {
    put("operation", operation); put("x", x); put("y", y)
    put("end_x", endX?.let(::JsonPrimitive) ?: JsonNull); put("end_y", endY?.let(::JsonPrimitive) ?: JsonNull)
    put("duration_ms", 400); put("humanized", operation == "swipe")
    put("confirmation_message", confirmation?.let(::JsonPrimitive) ?: JsonNull)
    put("commentary", "正在执行手势")
})
