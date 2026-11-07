package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.app.AppResolver

import com.kevinluo.autoglm.core.InteractionKind
import com.kevinluo.autoglm.core.InteractionReply
import com.kevinluo.autoglm.core.RunControl
import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.device.requireSuccessful
import com.kevinluo.autoglm.input.TextInputManager
import com.kevinluo.autoglm.screenshot.FloatingWindowController
import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.screenshot.withOverlayHidden
import com.kevinluo.autoglm.util.HumanizedSwipeGenerator


internal enum class FrameEffect { NONE, INVALIDATE, CAPTURE }
internal data class FunctionToolResult(
    val text: String,
    val action: AgentAction? = null,
    val effect: FrameEffect = FrameEffect.NONE,
    val userReply: String? = null,
)

/** Only Android leaf operations; all waits and user continuations belong to RunControl. */
internal class FunctionToolBackend(
    private val device: DeviceExecutor,
    private val textInput: TextInputManager,
    private val apps: AppResolver,
    private val swipes: HumanizedSwipeGenerator,
    private val window: () -> FloatingWindowController?,
) {
    suspend fun execute(command: FunctionCommand, frame: Screenshot?, control: RunControl,
        onAcknowledged: (AgentAction) -> Unit = {}): FunctionToolResult {
        control.checkpoint()
        return when (command) {
            is FunctionCommand.Launch -> {
                val target = apps.resolvePackageName(command.app)
                    ?: return FunctionToolResult("ERROR: No installed launchable app matched; use android_list_apps to inspect available apps.")
                val result = device.launchApp(target)
                result.requireSuccessful("launch_app")
                FunctionToolResult("Launched $target", AgentAction.Launch(target), FrameEffect.INVALIDATE)
            }
            FunctionCommand.ListApps -> FunctionToolResult(apps.getAllLaunchableApps().joinToString("\n") { "${it.displayName}: ${it.packageName}" }, AgentAction.ListApps)
            is FunctionCommand.Key -> {
                val (code, action) = when (command.key) {
                    SystemKey.BACK -> 4 to AgentAction.Back
                    SystemKey.HOME -> 3 to AgentAction.Home
                    SystemKey.VOLUME_UP -> 24 to AgentAction.VolumeUp
                    SystemKey.VOLUME_DOWN -> 25 to AgentAction.VolumeDown
                    SystemKey.POWER -> 26 to AgentAction.Power
                }
                withOverlayHidden(window(), control::checkpoint) {
                    device.pressKey(code).requireSuccessful("system_key")
                    onAcknowledged(action)
                }
                FunctionToolResult("Key accepted", action, FrameEffect.INVALIDATE)
            }
            is FunctionCommand.Input -> {
                val text = command.text
                val mode = command.mode
                val result = withOverlayHidden(window(), control::checkpoint) {
                    (if (mode == InputMode.REPLACE) textInput.typeText(text) else textInput.appendText(text)).also {
                        if (it.success) onAcknowledged(AgentAction.Type(text))
                    }
                }
                check(result.success) { result.message }
                FunctionToolResult("${result.message}; verify the final field in a fresh observation", AgentAction.Type(text), FrameEffect.INVALIDATE)
            }
            is FunctionCommand.GestureCommand -> gesture(command, requireNotNull(frame) { "A current screenshot is required" }, control, onAcknowledged)
            is FunctionCommand.Takeover -> {
                val message = command.message
                val reply = control.interact(InteractionKind.TAKEOVER, message)
                val accepted = (reply as? InteractionReply.Accepted)?.accepted == true
                FunctionToolResult(if (accepted) "User completed the manual step" else "User cancelled the manual step", AgentAction.TakeOver(message), FrameEffect.INVALIDATE)
            }
            is FunctionCommand.Choice -> {
                val options = command.options
                val reply = control.interact(InteractionKind.CHOICE, command.question, options)
                val index = (reply as? InteractionReply.Choice)?.index
                FunctionToolResult(index?.let { "User selected: ${options.getOrNull(it) ?: error("Invalid selection")}" }
                    ?: "User cancelled the interaction", AgentAction.Interact(options))
            }
            is FunctionCommand.Ask -> {
                val answer = (control.interact(InteractionKind.TEXT, command.question) as? InteractionReply.Text)?.text
                FunctionToolResult(answer?.let { "User replied: $it" } ?: "User cancelled the question",
                    AgentAction.Note(if (answer == null) "用户取消回复" else "用户回复：$answer"), userReply = answer)
            }
            is FunctionCommand.Wait -> {
                val duration = command.durationMs
                val reason = command.reason
                val waited = control.wait(duration, reason)
                FunctionToolResult("Waited ${waited.actualMs}ms" + if (waited.skipped) "; finished early by user" else "",
                    AgentAction.Wait((duration ?: 2000) / 1000f), FrameEffect.INVALIDATE)
            }
            is FunctionCommand.Note -> FunctionToolResult("Note recorded: ${command.message}", AgentAction.Note(command.message))
        }
    }

    private suspend fun gesture(command: FunctionCommand.GestureCommand, frame: Screenshot, control: RunControl,
        onAcknowledged: (AgentAction) -> Unit): FunctionToolResult {
        val duration = command.durationMs
        require(duration in 1..5000)
        val start = ComputerActionMapper.point(command.x, command.y, frame)
        val confirmation = command.confirmation
        if (confirmation != null && (control.interact(InteractionKind.CONFIRM, confirmation) as? InteractionReply.Accepted)?.accepted != true) {
            return FunctionToolResult("User declined the gesture")
        }
        return withOverlayHidden(window(), control::checkpoint) {
            when (val gesture = command.gesture) {
                Gesture.LongPress -> {
                    device.longPress(start.x, start.y, duration).requireSuccessful("long_press")
                    FunctionToolResult("Long press completed", AgentAction.LongPress(TraceActionMapper.normalized(start.x, frame.originalWidth), TraceActionMapper.normalized(start.y, frame.originalHeight), duration), FrameEffect.CAPTURE)
                }
                is Gesture.Swipe -> {
                    val end = ComputerActionMapper.point(gesture.endX, gesture.endY, frame)
                    val path = if (gesture.humanized) swipes.generatePath(start.x, start.y, end.x, end.y, frame.originalWidth, frame.originalHeight)
                        else swipes.generateLinearPath(start.x, start.y, end.x, end.y, frame.originalWidth, frame.originalHeight)
                    device.swipe(path.points, duration).requireSuccessful("swipe")
                    FunctionToolResult("Swipe completed", AgentAction.Swipe(TraceActionMapper.normalized(start.x, frame.originalWidth), TraceActionMapper.normalized(start.y, frame.originalHeight),
                        TraceActionMapper.normalized(end.x, frame.originalWidth), TraceActionMapper.normalized(end.y, frame.originalHeight), gesture.humanized), FrameEffect.CAPTURE)
                }
            }.also { it.action?.let(onAcknowledged) }
        }
    }

}
