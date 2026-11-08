package com.kevinluo.autoglm.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.kevinluo.autoglm.input.InputResult
import com.kevinluo.autoglm.input.KeyboardInputGateway
import com.kevinluo.autoglm.input.TextEditRequest
import com.kevinluo.autoglm.input.TextEditStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext


import java.util.UUID

/** Explicit node actions only. Never guesses a nearby node, parent click target, or coordinate. */
class PhoneAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set
        // Traversal/search and wire output are separate budgets. A short observation must
        // not make later nodes impossible to find on the very same screen.
        internal const val MAX_NODES = 1024
        private const val MAX_DEPTH = 18
        private const val MAX_TEXT_LENGTH = 18000
        private const val TRAVERSAL_NOTICE = "[Traversal budget reached; find and scoped observe search captured nodes only. Scroll or use a screenshot for remaining content.]\n"
    }

    private data class Target(val path: List<Int>, val fingerprint: String, val windowId: Int, val displayId: Int)
    private data class CapturedObservation(
        val snapshot: UiSnapshot,
        val targets: Map<String, Target> = emptyMap(),
        val complete: Boolean = false,
    )
    private val targets = mutableMapOf<String, Target>()
    private val lease = SnapshotLease()
    private var lastObservation: CapturedObservation? = null
    private val lastSnapshot: UiSnapshot? get() = lastObservation?.snapshot
    private var selectedDisplayId = 0

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Animations, clocks and unrelated windows emit events while the model is thinking.
        // A dirty lease must be freshly compared, not rejected before inspecting its target.
        if (event?.packageName?.toString() != packageName) lease.markDirty()
    }

    override fun onInterrupt() {
        lease.invalidate()
        targets.clear()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        onInterrupt()
        super.onDestroy()
    }

    suspend fun observe(): UiSnapshot = withContext(Dispatchers.Main.immediate) { observeNow() }

    /** The composition root selects a verified display lease, never a model-supplied display ID. */
    suspend fun selectDisplay(displayId: Int) = withContext(Dispatchers.Main.immediate) {
        require(displayId >= 0)
        selectedDisplayId = displayId
        // Even a recycled numeric display ID must not renew an old observation's authority.
        lease.invalidate()
        targets.clear()
        lastObservation = null
    }

    fun invalidateObservationForConnection() {
        lease.invalidate()
        targets.clear()
        lastObservation = null
    }

    suspend fun invalidateObservation() = withContext(Dispatchers.Main.immediate) {
        invalidateObservationForConnection()
    }

    suspend fun execute(
        command: UiCommand,
        textEditor: (suspend (TextEditRequest) -> InputResult)? = null,
    ): String = withContext(Dispatchers.Main.immediate) {
        if (command is UiCommand.Observe) {
            val scope = command.scopeId
            if (scope == null) return@withContext observeNow().text
            validateScope(command.snapshotId, scope)?.let { return@withContext it }
            return@withContext SemanticSnapshotFormatter.format(checkNotNull(lastSnapshot), scopeId = scope, maxChars = MAX_TEXT_LENGTH - 128)
        }
        if (command is UiCommand.Find) return@withContext findNodes(command)
        if (command is UiCommand.ClickMatching) return@withContext clickMatching(command, textEditor)
        require(command is UiCommand.Target)
        val snapshotId = command.snapshotId
        val nodeId = command.nodeId
        val before = lastSnapshot?.takeIf { it.id == snapshotId }
        validateObservation(snapshotId)?.let { return@withContext it }
        val target = targets[nodeId] ?: return@withContext "ERROR: unknown node ID; observe again."
        val root = rootForWindow(target.windowId) ?: return@withContext "ERROR: no accessible target window; request user takeover or a screenshot."
        var node: AccessibilityNodeInfo? = root
        try {
            if (root.packageName?.toString() == packageName || root.windowId != target.windowId) {
                return@withContext "ERROR: active window changed; observe again."
            }
            for (index in target.path) {
                val parent = node ?: return@withContext "ERROR: node disappeared; observe again."
                if (isSensitive(parent)) return@withContext "ERROR: protected content requires user takeover."
                node = parent.getChild(index)
                if (parent !== root) recycle(parent)
            }
            val current = node ?: return@withContext "ERROR: node disappeared; observe again."
            if (!current.refresh() || !current.isVisibleToUser || !current.isEnabled ||
                current.windowId != target.windowId || fingerprint(current) != target.fingerprint
            ) {
                lease.invalidate()
                return@withContext "ERROR: node changed; observe again."
            }
            if (isSensitive(current)) return@withContext "ERROR: protected content requires user takeover."
            if (command.action is NodeAction.Text) {
                return@withContext executeText(command.action, current, target, before, textEditor)
            }
            val action = when (val request = command.action) {
                NodeAction.Simple.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
                NodeAction.Simple.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                NodeAction.Simple.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                NodeAction.Simple.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                NodeAction.Simple.SCROLL_UP -> android.R.id.accessibilityActionScrollUp
                NodeAction.Simple.SCROLL_DOWN -> android.R.id.accessibilityActionScrollDown
                NodeAction.Simple.SCROLL_LEFT -> android.R.id.accessibilityActionScrollLeft
                NodeAction.Simple.SCROLL_RIGHT -> android.R.id.accessibilityActionScrollRight
                NodeAction.Simple.SHOW -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                is NodeAction.Selection -> AccessibilityNodeInfo.ACTION_SET_SELECTION
                is NodeAction.Progress -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
                is NodeAction.Custom -> request.id
                is NodeAction.Text -> error("Text handled by keyboard gateway")
            }
            if (current.actionList.none { it.id == action }) {
                return@withContext "ERROR: the selected node does not support this action; observe or use a screenshot."
            }
            val actionArguments = when (val request = command.action) {
                is NodeAction.Selection -> {
                    val start = request.start
                    val end = request.end
                    if (!current.isEditable || !SemanticActionValidator.isValidSelection(start, end, current.text?.length ?: 0)) {
                        return@withContext "ERROR: invalid text selection."
                    }
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                    }
                }
                is NodeAction.Progress -> {
                    val progress = request.value
                    val range = current.rangeInfo ?: return@withContext "ERROR: node range is unavailable."
                    if (!SemanticActionValidator.isValidProgress(progress, range.min, range.max)) {
                        return@withContext "ERROR: progress is outside the node range."
                    }
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, progress) }
                }
                else -> null
            }
            val performed = current.performAction(action, actionArguments)
            lease.invalidate()
            if (!performed) return@withContext "ERROR: Android rejected the action; do not assume it succeeded."
            awaitSemanticStability()
        } catch (error: CancellationException) {
            lease.invalidate()
            throw error
        } catch (_: Exception) {
            lease.invalidate()
            return@withContext "ERROR: accessibility action failed; observe again before retrying."
        } finally {
            node?.takeIf { it !== root }?.let(::recycle)
            recycle(root)
        }
        val after = observeNow()
        SemanticActionFeedback.format(before, after, MAX_TEXT_LENGTH)
    }

    private suspend fun clickMatching(
        command: UiCommand.ClickMatching,
        textEditor: (suspend (TextEditRequest) -> InputResult)?,
    ): String {
        val source = lastObservation
        if (source == null || source.snapshot.id != command.snapshotId || !lease.canRevalidate(command.snapshotId)) {
            return "ERROR: stale source snapshot; call android_ui observe again."
        }
        SemanticClickMatcher.queryError(command.query, command.matchMode)?.let { return "ERROR: $it" }
        val fresh = collectObservation(UUID.randomUUID().toString())
        val resolution = SemanticClickMatcher.resolve(source.snapshot, fresh.snapshot, command.nodeId,
            command.query, command.matchMode, source.complete, fresh.complete)
        if (resolution is SemanticClickResolution.Rejected) return "ERROR: ${resolution.reason}"
        require(resolution is SemanticClickResolution.Matched)
        // No suspension or device action occurred before binding. The old ID is not rebound:
        // publish this explicit fresh observation, then retain normal exact-node validation.
        if (lastObservation !== source || !lease.canRevalidate(command.snapshotId)) {
            return "ERROR: source snapshot was invalidated; observe again."
        }
        bindObservation(fresh)
        return execute(UiCommand.Target(fresh.snapshot.id, resolution.nodeId, NodeAction.Simple.CLICK), textEditor)
    }

    private suspend fun executeText(
        action: NodeAction.Text,
        current: AccessibilityNodeInfo,
        target: Target,
        before: UiSnapshot?,
        textEditor: (suspend (TextEditRequest) -> InputResult)?,
    ): String {
        if (textEditor == null) return "ERROR: keyboard input gateway is unavailable; no text was sent."
        if (!current.isEditable || current.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            return "ERROR: text input needs an editable node supporting text changes."
        }
        val original = current.text?.toString().orEmpty()
        // The prior AX append also replaced the whole value: retain end-append semantics,
        // independent of the current caret, through the same acknowledged keyboard gateway.
        val updated = if (action.append) original + action.value else action.value
        if (updated.length > 10000) return "ERROR: text is limited to 10000 characters."
        val staleEditor = if (!current.isFocused) KeyboardInputGateway.gateway.currentToken()?.generation else null
        if (!current.isFocused && !current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            return "ERROR: Android rejected input focus; no text was sent."
        }
        lease.invalidate()
        val result = textEditor(TextEditRequest(updated, guard = { stage ->
            // Focus/IME changes legitimately reflow bounds and invalidate the observation.
            // Re-check the exact live node and input focus instead of reusing that old lease.
            val focused = inputFocusForSelectedDisplay()
            try {
                selectedDisplayId == target.displayId && current.refresh() && current == focused && current.isFocused &&
                    current.windowId == target.windowId && current.isEditable &&
                    current.isVisibleToUser && current.isEnabled && !isSensitive(current) &&
                    (stage == TextEditStage.BEFORE_COMMIT || current.text?.toString().orEmpty() == original)
            } finally {
                focused?.takeIf { it !== current }?.let(::recycle)
            }
        }, staleEditorGeneration = staleEditor))
        lease.invalidate()
        if (!result.success) return "ERROR: ${result.message}; observe again before retrying."
        awaitSemanticStability()
        val matches = current.refresh() && current.text?.toString().orEmpty() == updated
        val after = observeNow()
        val verification = if (matches) "Text value verified by a fresh accessibility read." else
            "Input accepted; final field value is unverified. Inspect the fresh observation before any retry."
        return "$verification ${result.message}\n${SemanticActionFeedback.format(before, after, MAX_TEXT_LENGTH)}"
    }

    private suspend fun awaitSemanticStability() {
        var elapsed = 0
        var quiet = 0
        var generation = lease.generation
        while (elapsed < 2000) {
            delay(50)
            elapsed += 50
            if (lease.generation != generation) {
                generation = lease.generation
                quiet = 0
            } else {
                quiet += 50
                if (quiet >= 250) return
            }
        }
    }

    private suspend fun findNodes(command: UiCommand.Find): String {
        val query = command.query
        val scope = command.scopeId
        if (scope != null) validateScope(command.snapshotId, scope)?.let { return it }
        val scopeAnchor = scope?.let(targets::get)
        val searchScroll = command.searchScroll
        var snapshot = if (scope == null) observeNow() else checkNotNull(lastSnapshot)
        var screens = 1
        while (true) {
            if (scope != null && (snapshot.windowId != scopeAnchor?.windowId || targets[scope] != scopeAnchor)) {
                return "ERROR: search scope changed; observe again before continuing the search."
            }
            val matches = SemanticFinder.find(snapshot, query, scope)
            if (matches.isNotEmpty()) return SemanticFinder.formatMatches(snapshot, matches)
            val scrollers = snapshot.nodes.filter { node ->
                node.actions.any { it.id in setOf("scroll_forward", "scroll_down") } && SemanticFinder.inScope(node, snapshot, scope)
            }
            if (searchScroll && scrollers.size > 1 && scope == null) {
                return "ERROR: multiple scroll containers; provide scope_id or use a screenshot."
            }
            val scroller = scrollers.firstOrNull()
            if (!searchScroll || scroller == null || screens >= 4) {
                return SemanticFinder.formatMatches(snapshot, matches) +
                    "No match after searching $screens screen(s); do not assume it is absent from the app.\n"
            }
            val scrollAction = if (scroller.actions.any { it.id == "scroll_forward" }) NodeAction.Simple.SCROLL_FORWARD
                else NodeAction.Simple.SCROLL_DOWN
            val scrollResult = execute(UiCommand.Target(snapshot.id, scroller.id, scrollAction))
            if (scrollResult.startsWith("ERROR:")) return scrollResult
            val next = lastSnapshot ?: observeNow()
            val diff = SnapshotDiffer.diff(snapshot, next)
            if (!diff.windowChanged && diff.changes.isEmpty()) {
                return SemanticFinder.formatMatches(next, emptyList()) + "No further semantic content was revealed.\n"
            }
            snapshot = next
            screens++
        }
    }

    /** Scoped reads cannot silently reinterpret a node path from an old window/snapshot. */
    private fun validateScope(snapshotId: String?, scopeId: String): String? {
        validateObservation(snapshotId)?.let { return it }
        SemanticSnapshotAccess.scopeError(lastSnapshot, snapshotId, scopeId, lease.isCurrent(snapshotId))?.let { return it }
        val target = targets[scopeId] ?: return "ERROR: unknown or protected scope; observe again."
        val root = rootForWindow(target.windowId) ?: return "ERROR: scope window disappeared; observe again."
        var node: AccessibilityNodeInfo? = root
        try {
            if (root.windowId != target.windowId || root.packageName?.toString() == packageName) {
                return "ERROR: scope window changed; observe again."
            }
            for (index in target.path) {
                val parent = node ?: return "ERROR: scope disappeared; observe again."
                if (isSensitive(parent)) return "ERROR: protected scope requires user takeover."
                node = parent.getChild(index)
                if (parent !== root) recycle(parent)
            }
            val current = node ?: return "ERROR: scope disappeared; observe again."
            if (!current.refresh() || !current.isVisibleToUser || isSensitive(current) ||
                current.windowId != target.windowId || fingerprint(current) != target.fingerprint) {
                lease.invalidate()
                return "ERROR: scope changed; observe again."
            }
            return null
        } catch (_: Exception) {
            lease.invalidate()
            return "ERROR: scope could not be validated; observe again."
        } finally {
            node?.takeIf { it !== root }?.let(::recycle)
            recycle(root)
        }
    }

    /** Revalidates the same identity without publishing new node IDs behind the model's back. */
    private fun validateObservation(snapshotId: String?): String? {
        val before = lastObservation
        if (before == null || before.snapshot.id != snapshotId || !lease.canRevalidate(snapshotId)) {
            return "ERROR: stale snapshot; call android_ui observe again."
        }
        val selected = try { selectObservationRoot() } catch (_: Exception) { null }
        val selectedWindow = try {
            selected?.takeIf { it.packageName?.toString() != packageName }?.windowId
        } finally {
            selected?.let(::recycle)
        }
        if (selectedWindow != before.snapshot.windowId) {
            lease.invalidate()
            return "ERROR: active window changed; observe again."
        }
        if (lease.isCurrent(snapshotId)) return null
        val fresh = collectObservation(checkNotNull(snapshotId))
        if (!before.complete || !fresh.complete || before.targets != fresh.targets ||
            !SemanticSnapshotIdentity.unchanged(before.snapshot, fresh.snapshot)) {
            lease.invalidate()
            return "ERROR: observed UI changed; call android_ui observe again."
        }
        return if (lease.revalidate(snapshotId)) null else "ERROR: stale snapshot; call android_ui observe again."
    }

    private fun observeNow(): UiSnapshot {
        val observation = collectObservation(UUID.randomUUID().toString())
        bindObservation(observation)
        return observation.snapshot
    }

    private fun bindObservation(observation: CapturedObservation) {
        targets.clear()
        targets.putAll(observation.targets)
        lastObservation = observation
        if (observation.snapshot.nodes.isNotEmpty()) lease.capture(observation.snapshot.id) else lease.invalidate()
    }

    /** Read-only collection used by observation and dirty-lease validation. */
    private fun collectObservation(id: String): CapturedObservation {
        val observedTargets = mutableMapOf<String, Target>()
        val root = try { selectObservationRoot() } catch (_: Exception) { null }
        if (root == null) {
            return CapturedObservation(UiSnapshot(id, "Accessibility tree unavailable on the selected display. Use android_screenshot for this display or request user takeover; no other display was read."))
        }
        val nodes = mutableListOf<SemanticNode>()
        var visited = 0
        var truncated = false
        fun visit(node: AccessibilityNodeInfo, path: List<Int>, parentId: String?, depth: Int) {
            if (visited >= MAX_NODES || depth > MAX_DEPTH) {
                truncated = true
                return
            }
            visited++
            val protected = isSensitive(node)
            val actions = semanticActions(node)
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val description = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.takeIf { it.isNotBlank() } else null
            val informative = protected || actions.isNotEmpty() || text != null || description != null || hint != null ||
                node.collectionInfo != null || node.collectionItemInfo != null
            var emittedId: String? = parentId
            var emittedDepth = depth
            if (node.isVisibleToUser && informative) {
                val nodeId = "n" + path.joinToString("_").ifEmpty { "root" }
                val bounds = Rect().also(node::getBoundsInScreen)
                val states = mutableSetOf<String>()
                if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString()?.takeIf { it.isNotBlank() }?.let(states::add)
                if (node.isCheckable) states += if (node.isChecked) "checked" else "unchecked"
                if (node.isSelected) states += "selected"
                if (node.isFocused) states += "focused"
                if (!node.isEnabled) states += "disabled"
                val item = node.collectionItemInfo
                val collection = node.collectionInfo
                nodes += SemanticNode(
                    id = nodeId,
                    kind = semanticKind(node),
                    label = if (protected) null else description ?: if (node.isEditable) hint else text,
                    value = if (protected) null else if (node.isEditable) text else if (description != null) text else null,
                    description = if (protected) null else description,
                    hint = if (protected) null else hint,
                    viewId = if (protected) null else node.viewIdResourceName,
                    states = if (protected) emptySet() else states,
                    collectionPosition = when {
                        item != null -> "row ${item.rowIndex + 1}, column ${item.columnIndex + 1}"
                        collection != null && collection.rowCount >= 0 -> "items=${collection.rowCount}"
                        else -> null
                    },
                    actions = if (protected) emptyList() else actions,
                    parentId = parentId,
                    childIds = emptyList(),
                    depth = depth,
                    bounds = intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    sensitive = protected,
                )
                if (!protected) observedTargets[nodeId] = Target(path, fingerprint(node), node.windowId, selectedDisplayId)
                emittedId = nodeId
                emittedDepth = depth + 1
            }
            if (protected) return
            for (index in 0 until node.childCount.coerceAtMost(MAX_NODES)) {
                if (visited >= MAX_NODES) {
                    truncated = true
                    break
                }
                val child = node.getChild(index) ?: continue
                try {
                    visit(child, path + index, emittedId, emittedDepth)
                } finally {
                    recycle(child)
                }
            }
        }
        return try {
            val snapshot = if (root.packageName?.toString() == packageName) {
                UiSnapshot(id, "Agent controls are focused. Open the target app or use Android HOME through the computer tool.\n", windowId = root.windowId)
            } else {
                visit(root, emptyList(), null, 0)
                val merged = SemanticNodeMerger.merge(nodes)
                var text = SemanticSnapshotFormatter.format(UiSnapshot(id, "", merged, root.windowId),
                    maxChars = MAX_TEXT_LENGTH - TRAVERSAL_NOTICE.length - 128)
                if (truncated) text += TRAVERSAL_NOTICE
                UiSnapshot(id, text, merged, root.windowId)
            }
            CapturedObservation(snapshot, observedTargets.toMap(), !truncated && snapshot.nodes.isNotEmpty())
        } catch (_: Exception) {
            CapturedObservation(UiSnapshot(id, "Accessibility tree changed while observing. Call observe again or request a screenshot."))
        } finally {
            recycle(root)
        }
    }

    private fun selectObservationRoot(): AccessibilityNodeInfo? {
        val displayWindows = windowsForDisplay(selectedDisplayId)
        // Our full-screen Activity is not a transparent floating control. Do not act on
        // another application's still-listed background window while it is in front.
        displayWindows.firstOrNull { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive) false
            else {
                val root = window.root
                try { root?.packageName?.toString() == packageName }
                finally { root?.let(::recycle) }
            }
        }?.let { return it.root }
        val candidates = displayWindows.filter { window ->
            runCatching {
                val root = window.root
                try { root != null && root.packageName?.toString() != packageName }
                finally { root?.let(::recycle) }
            }.getOrDefault(false)
        }
        val selected = candidates.firstOrNull { it.isActive && it.isFocused }
            ?: candidates.firstOrNull { it.isActive }
            ?: candidates.firstOrNull { it.isFocused }
            ?: candidates.firstOrNull()
        return selected?.root ?: if (selectedDisplayId == 0) rootInActiveWindow else null
    }

    /** Non-primary displays never borrow the active-window convenience API (which targets display 0). */
    internal fun windowsForDisplay(displayId: Int): List<AccessibilityWindowInfo> = when {
        displayId == 0 -> windows
        Build.VERSION.SDK_INT >= 30 -> windowsOnAllDisplays?.get(displayId).orEmpty()
        else -> emptyList()
    }

    private fun rootForWindow(windowId: Int): AccessibilityNodeInfo? {
        windowsForDisplay(selectedDisplayId).firstOrNull { it.id == windowId }?.root?.let { return it }
        if (selectedDisplayId != 0) return null
        val fallback = rootInActiveWindow ?: return null
        if (fallback.windowId == windowId) return fallback
        recycle(fallback)
        return null
    }

    private fun inputFocusForSelectedDisplay(): AccessibilityNodeInfo? {
        val root = selectObservationRoot() ?: return null
        val focused = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        catch (_: Exception) { null }
        if (root !== focused) recycle(root)
        return focused
    }

    private fun semanticKind(node: AccessibilityNodeInfo): SemanticKind = when {
        node.isEditable -> SemanticKind.INPUT
        node.isCheckable && node.className?.contains("Switch", ignoreCase = true) == true -> SemanticKind.SWITCH
        node.isCheckable -> SemanticKind.CHECKBOX
        node.isScrollable -> SemanticKind.SCROLLER
        node.isClickable || node.isLongClickable -> SemanticKind.BUTTON
        node.className?.contains("Text", ignoreCase = true) == true -> SemanticKind.TEXT
        else -> SemanticKind.GROUP
    }

    private fun semanticActions(node: AccessibilityNodeInfo): List<SemanticAction> = node.actionList.mapNotNull { action ->
        val id = when (action.id) {
            AccessibilityNodeInfo.ACTION_CLICK -> "click"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
            android.R.id.accessibilityActionScrollUp -> "scroll_up"
            android.R.id.accessibilityActionScrollDown -> "scroll_down"
            android.R.id.accessibilityActionScrollLeft -> "scroll_left"
            android.R.id.accessibilityActionScrollRight -> "scroll_right"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id -> "show"
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> "set_selection"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> "set_progress"
            AccessibilityNodeInfo.ACTION_SELECT -> "select"
            else -> "custom_${action.id}"
        }
        if (id.startsWith("custom_") && action.label.isNullOrBlank()) null else SemanticAction(id, action.label?.toString())
    }

    private fun isSensitive(node: AccessibilityNodeInfo): Boolean =
        node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)

    private fun fingerprint(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return listOf(
            node.windowId, node.packageName, node.className, node.viewIdResourceName,
            bounds.toShortString(), node.isEnabled, node.isClickable, node.isEditable, node.isScrollable,
            if (isSensitive(node)) "[protected]" else node.text,
            if (isSensitive(node)) "[protected]" else node.contentDescription,
        ).joinToString("\u0000")
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        node.recycle()
    }
}
