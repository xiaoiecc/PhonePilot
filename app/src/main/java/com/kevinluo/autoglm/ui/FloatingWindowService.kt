package com.kevinluo.autoglm.ui

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.kevinluo.autoglm.MainActivity
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.core.ExecutionMode
import com.kevinluo.autoglm.core.InteractionKind
import com.kevinluo.autoglm.core.InteractionReply
import com.kevinluo.autoglm.core.PendingInteraction
import com.kevinluo.autoglm.home.ChatMessageAdapter
import com.kevinluo.autoglm.screenshot.ScopedFloatingWindowController
import com.kevinluo.autoglm.task.TaskExecutionManager
import com.kevinluo.autoglm.ui.capsule.AutoGrowingInput
import com.kevinluo.autoglm.ui.capsule.CapsulePresentation
import com.kevinluo.autoglm.ui.capsule.CapsuleDragLayout
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

enum class TaskStatus {
    IDLE, RUNNING, PAUSED, CANCELLING, COMPLETED, FAILED,
    WAITING_CONFIRMATION, WAITING_TAKEOVER, WAITING_FOR_USER,
}

/** Window host and read-only presentation. No device capability or independent task lifecycle. */
class FloatingWindowService : Service(), ScopedFloatingWindowController {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val isAttached = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val chatAdapter = ChatMessageAdapter()
    private var lastInteractionKey: Pair<String, Long>? = null
    private var detailsExpanded = false
    private var sizeAnimator: ValueAnimator? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var layoutPosted = false
    private var bodyExpanded = false
    private var bodyFadeGeneration = 0L
    private var rebindingDraft = false
    private var draftConversationId: String? = null
    private var draftBound = false
    private val drafts = ComposerDraftStore()
    private var keyboardRequestGeneration = 0L
    private var keyboardRequestCleanup: (() -> Unit)? = null

    companion object {
        private const val TAG = "FloatingWindow"
        @Volatile private var instance: FloatingWindowService? = null
        fun getInstance(): FloatingWindowService? = instance
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
        fun requestOverlayPermission(context: Context) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        FloatingWindowStateManager.initialize(applicationContext)
        observeTaskState()
    }

    private fun observeTaskState() {
        serviceScope.launch {
            TaskExecutionManager.messages.collect { messages ->
                val list = floatingView?.findViewById<RecyclerView>(R.id.steps_recycler_view)
                val follow = list?.canScrollVertically(1) != true
                chatAdapter.submitMessages(messages)
                if (follow && messages.isNotEmpty()) list?.scrollToPosition(messages.lastIndex)
                render()
            }
        }
        serviceScope.launch {
            var previouslyPausedRun: String? = null
            TaskExecutionManager.taskState.collect { state ->
                if (state.status == TaskStatus.PAUSED && previouslyPausedRun != state.runId) {
                    FloatingWindowStateManager.setMinimized(false)
                }
                previouslyPausedRun = state.runId.takeIf { state.status == TaskStatus.PAUSED }
                render()
            }
        }
        serviceScope.launch { TaskExecutionManager.pendingInteraction.collect { renderInteraction(it); render() } }
        serviceScope.launch {
            var lastWait: Pair<String, Long>? = null
            TaskExecutionManager.activeWait.collect { wait ->
                val key = wait?.let { it.runId to it.id }
                if (key != null && key != lastWait) FloatingWindowStateManager.setMinimized(false)
                lastWait = key
                render()
            }
        }
        serviceScope.launch { TaskExecutionManager.executionMode.collect { render() } }
        serviceScope.launch { TaskExecutionManager.placementSwitching.collect { render() } }
        serviceScope.launch { TaskExecutionManager.placementError.collect { render() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startOverlayForeground()
        if (floatingView == null && canDrawOverlays(this)) createWindowView()
        if (FloatingWindowStateManager.shouldBeVisible()) show()
        return START_STICKY
    }

    private fun startOverlayForeground() {
        val channel = "automation_overlay"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, getString(R.string.overlay_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_layers)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_body)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        ServiceCompat.startForeground(this, 2003, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        sizeAnimator?.cancel()
        clearInputFocus()
        removeWindowInternal()
        floatingView = null
        layoutParams = null
        super.onDestroy()
    }

    override fun acquireHidden(): AutoCloseable = FloatingWindowStateManager.acquireHide(applicationContext)
    override fun acquirePauseVisibility(): AutoCloseable = FloatingWindowStateManager.acquirePauseVisibility(applicationContext)
    override fun isVisible(): Boolean = isAttached.get()
    override fun hide() {
        serviceScope.launch {
            sizeAnimator?.cancel()
            sizeAnimator = null
            clearInputFocus()
            removeWindowInternal()
        }
    }
    override fun show() {
        serviceScope.launch {
            if (!FloatingWindowStateManager.shouldBeVisible()) return@launch
            if (floatingView == null) createWindowView()
            if (!isAttached.get()) addWindowInternal()
            render()
        }
    }
    override fun showAndBringToFront() {
        serviceScope.launch {
            if (!FloatingWindowStateManager.shouldBeVisible()) return@launch
            if (floatingView == null) createWindowView()
            if (isAttached.get()) removeWindowInternal()
            addWindowInternal()
            render()
        }
    }

    private fun createWindowView() {
        if (floatingView != null) return
        val themed = android.view.ContextThemeWrapper(this, R.style.Theme_AutoGLM)
        val view = LayoutInflater.from(themed).inflate(R.layout.layout_floating_window, null)
        floatingView = view
        layoutParams = createLayoutParams()
        view.findViewById<RecyclerView>(R.id.steps_recycler_view).apply {
            layoutManager = LinearLayoutManager(this@FloatingWindowService).apply { stackFromEnd = true }
            adapter = chatAdapter
            isNestedScrollingEnabled = false
        }
        chatAdapter.submitMessages(TaskExecutionManager.messages.value)
        setupButtons(view)
        setupInput(view.findViewById(R.id.task_input))
        setupInput(view.findViewById(R.id.et_interaction_answer))
        view.findViewById<EditText>(R.id.task_input).doAfterTextChanged { text ->
            if (!rebindingDraft) {
                drafts.save(draftConversationId, text?.toString().orEmpty(), emptyList())
                if (text?.isNotBlank() == true) TaskExecutionManager.pauseForDraft()
                else TaskExecutionManager.onEditorBlur(false)
                render()
            }
        }
        view.findViewById<EditText>(R.id.task_input).setOnFocusChangeListener { editor, focused ->
            if (!focused) TaskExecutionManager.onEditorBlur((editor as EditText).text.isNotBlank())
        }
        view.findViewById<View>(R.id.capsule_body).addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) scheduleResize()
        }
        view.findViewById<EditText>(R.id.task_input).addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) scheduleResize()
        }
        view.findViewById<EditText>(R.id.et_interaction_answer).addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) scheduleResize()
        }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) clearInputFocus()
            false
        }
        setupDragBehavior(view.findViewById(R.id.header))
        renderInteraction(TaskExecutionManager.pendingInteraction.value)
        render()
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(cardWidth(), dp(56), type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - width) / 2
            y = dp(12)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    /** This method reads current facts on every call; no cached task state can win a race. */
    private fun render() {
        val view = floatingView ?: return
        val state = TaskExecutionManager.taskState.value
        rebindDraft(state.conversationId)
        val minimized = FloatingWindowStateManager.snapshot.value.minimized
        val input = view.findViewById<EditText>(R.id.task_input)
        val presentation = CapsulePresentation.from(state.run, minimized, detailsExpanded, input.text.isNotBlank())
        val switching = TaskExecutionManager.placementSwitching.value
        val inBackground = TaskExecutionManager.executionMode.value == ExecutionMode.BACKGROUND
        val statusText = when {
            switching -> getString(R.string.capsule_switching)
            state.status == TaskStatus.RUNNING && state.currentAction.isNotBlank() -> state.currentAction
            else -> getString(when (state.status) {
                TaskStatus.IDLE -> R.string.task_status_idle
                TaskStatus.RUNNING -> R.string.task_status_running
                TaskStatus.PAUSED -> R.string.task_status_paused
                TaskStatus.CANCELLING -> R.string.task_status_cancelling
                TaskStatus.COMPLETED -> R.string.task_status_completed
                TaskStatus.FAILED -> R.string.task_status_failed
                TaskStatus.WAITING_CONFIRMATION -> R.string.floating_waiting_confirm
                TaskStatus.WAITING_TAKEOVER -> R.string.takeover_title
                TaskStatus.WAITING_FOR_USER -> R.string.reply_hint
            })
        }
        view.findViewById<TextView>(R.id.tv_status).apply {
            text = statusText
            contentDescription = if (inBackground && state.isExecuting) getString(R.string.capsule_foreground) + ": " + statusText
                else getString(R.string.capsule_details) + ": " + statusText
            visibility = if (minimized) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.status_indicator).apply {
            visibility = if (minimized) View.GONE else View.VISIBLE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@FloatingWindowService,
                    if (state.status == TaskStatus.FAILED) R.color.capsule_danger else R.color.capsule_accent))
            }
        }
        view.findViewById<ImageButton>(R.id.btn_minimize).contentDescription = getString(
            if (minimized) R.string.capsule_ball else R.string.capsule_fold)
        view.findViewById<ImageButton>(R.id.btn_progress_details).apply {
            visibility = if (minimized) View.GONE else View.VISIBLE
            contentDescription = getString(if (detailsExpanded) R.string.capsule_hide_progress else R.string.capsule_show_progress)
            rotation = if (detailsExpanded) 180f else 0f
        }
        visible(view, R.id.control_buttons_container, !minimized && state.isExecuting)
        visible(view, R.id.btn_pause, presentation.pauseVisible)
        visible(view, R.id.btn_resume, presentation.resumeVisible)
        visible(view, R.id.btn_stop, presentation.stopVisible)
        view.findViewById<View>(R.id.btn_stop).isEnabled = state.status != TaskStatus.CANCELLING
        state.run.activeWait?.let { shownWait ->
            view.findViewById<View>(R.id.btn_finish_wait).setOnClickListener {
                val current = TaskExecutionManager.activeWait.value
                if (current?.runId == shownWait.runId && current.id == shownWait.id) TaskExecutionManager.finishWaiting()
            }
        }
        setBodyExpanded(view, presentation.bodyVisible)
        // Preserve the outgoing body's content while its surface shrinks; all commands still
        // validate current interaction/run IDs if tapped during the short outgoing fade.
        if (presentation.bodyVisible) {
            visible(view, R.id.input_area, presentation.inputVisible)
            visible(view, R.id.interaction_panel, presentation.interactionVisible)
            visible(view, R.id.btn_finish_wait, presentation.waitVisible)
            visible(view, R.id.steps_recycler_view, presentation.messagesVisible && chatAdapter.itemCount > 0)
            visible(view, R.id.btn_new_task, presentation.newConversationVisible)
            visible(view, R.id.btn_select_template, presentation.inputVisible)
            val description = CapsulePresentation.standaloneDescription(state.currentAction,
                TaskExecutionManager.messages.value.asSequence()
                    .filter { it.role == com.kevinluo.autoglm.history.HistoryMessageRole.AGENT }.map { it.text })
            view.findViewById<TextView>(R.id.capsule_current_description).text = description.orEmpty()
            visible(view, R.id.capsule_current_description, detailsExpanded && description != null)
        }
        input.setHint(when (state.status) {
            TaskStatus.PAUSED -> R.string.capsule_pause_hint
            TaskStatus.COMPLETED, TaskStatus.FAILED -> R.string.continue_conversation_hint
            else -> R.string.task_input_hint
        })
        view.findViewById<View>(R.id.btn_start).isEnabled = state.status != TaskStatus.CANCELLING && !switching
        view.findViewById<TextView>(R.id.tv_step_counter).text = getString(
            if (inBackground) R.string.capsule_mode_back else R.string.capsule_mode_front) +
            if (state.stepNumber > 0) " · " + getString(R.string.step_counter_format, state.stepNumber) else ""
        view.findViewById<MaterialButton>(R.id.btn_execution_mode).apply {
            setText(if (switching) R.string.capsule_switching else if (inBackground) R.string.capsule_foreground else R.string.capsule_background)
            isEnabled = !switching && state.status != TaskStatus.CANCELLING
            visibility = if (state.isExecuting) View.VISIBLE else View.GONE
        }
        val error = TaskExecutionManager.placementError.value
        view.findViewById<TextView>(R.id.capsule_error).apply {
            text = error.orEmpty()
            visibility = if (!error.isNullOrBlank()) View.VISIBLE else View.GONE
        }
        view.findViewById<TextView>(R.id.tv_result).apply {
            text = state.resultMessage
            visibility = if (state.status == TaskStatus.FAILED && text.isNotBlank()) View.VISIBLE else View.GONE
        }
        view.findViewById<TextView>(R.id.token_usage).text = getString(R.string.overlay_token_usage,
            formatTokenCount(state.totalInputTokens), formatTokenCount(state.totalOutputTokens))
        if (!presentation.bodyVisible) clearInputFocus()
        scheduleResize()
    }

    private fun rebindDraft(conversationId: String?) {
        if (draftBound && draftConversationId == conversationId) return
        cancelKeyboardRequest()
        val input = floatingView?.findViewById<EditText>(R.id.task_input) ?: return
        if (draftBound) drafts.save(draftConversationId, input.text.toString(), emptyList())
        draftConversationId = conversationId
        draftBound = true
        rebindingDraft = true
        try { input.setText(drafts.get(conversationId).text) } finally { rebindingDraft = false }
    }

    private fun renderInteraction(pending: PendingInteraction?) {
        val view = floatingView ?: return
        if (pending == null) {
            if (lastInteractionKey != null) clearInputFocus()
            lastInteractionKey = null
            return
        }
        val key = pending.runId to pending.id
        if (lastInteractionKey == key) return
        clearInputFocus()
        lastInteractionKey = key
        FloatingWindowStateManager.setMinimized(false)
        view.findViewById<TextView>(R.id.tv_interaction_message).text = pending.prompt
        val options = view.findViewById<LinearLayout>(R.id.interaction_options)
        options.removeAllViews()
        val answer = view.findViewById<EditText>(R.id.et_interaction_answer)
        answer.visibility = if (pending.kind == InteractionKind.TEXT) View.VISIBLE else View.GONE
        answer.text.clear()
        val confirm = view.findViewById<MaterialButton>(R.id.btn_interaction_confirm)
        confirm.visibility = if (pending.kind == InteractionKind.CHOICE) View.GONE else View.VISIBLE
        confirm.setText(when (pending.kind) {
            InteractionKind.TEXT -> R.string.send_reply
            InteractionKind.TAKEOVER -> R.string.takeover_completed
            else -> R.string.dialog_confirm
        })
        confirm.setOnClickListener {
            val reply = if (pending.kind == InteractionKind.TEXT) {
                val text = answer.text.toString().trim()
                if (text.isBlank()) return@setOnClickListener
                InteractionReply.Text(text)
            } else InteractionReply.Accepted(true)
            if (TaskExecutionManager.replyInteraction(pending.runId, pending.id, reply)) clearInputFocus()
        }
        view.findViewById<View>(R.id.btn_interaction_cancel).setOnClickListener {
            TaskExecutionManager.replyInteraction(pending.runId, pending.id, InteractionReply.Cancelled)
        }
        pending.options.forEachIndexed { index, option ->
            options.addView(MaterialButton(options.context).apply {
                text = option
                textSize = 14f
                minHeight = dp(48)
                setTextColor(ContextCompat.getColor(context, R.color.capsule_accent))
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.capsule_accent_soft)
                cornerRadius = dp(16)
                setOnClickListener { TaskExecutionManager.replyInteraction(pending.runId, pending.id, InteractionReply.Choice(index)) }
            })
        }
        // Revealing a question is not permission to steal the main display's keyboard focus.
    }

    private fun setupButtons(view: View) {
        view.findViewById<View>(R.id.btn_open_app).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        view.findViewById<View>(R.id.btn_minimize).apply {
            setOnClickListener { FloatingWindowStateManager.setMinimized(!FloatingWindowStateManager.snapshot.value.minimized) }
            setOnLongClickListener { TaskExecutionManager.cancelTask(); true }
        }
        view.findViewById<View>(R.id.btn_close).setOnClickListener { FloatingWindowStateManager.close(this) }
        view.findViewById<View>(R.id.btn_stop).setOnClickListener { TaskExecutionManager.cancelTask() }
        view.findViewById<View>(R.id.btn_pause).setOnClickListener { TaskExecutionManager.pauseTask() }
        view.findViewById<View>(R.id.btn_resume).setOnClickListener { TaskExecutionManager.resumeTask() }
        view.findViewById<View>(R.id.btn_new_task).setOnClickListener {
            if (!TaskExecutionManager.isTaskRunning()) {
                TaskExecutionManager.resetTask()
                drafts.clear(draftConversationId)
                view.findViewById<EditText>(R.id.task_input).text.clear()
                detailsExpanded = false
                render()
            }
        }
        view.findViewById<View>(R.id.btn_execution_mode).setOnClickListener { switchMode() }
        view.findViewById<View>(R.id.btn_progress_details).setOnClickListener {
            detailsExpanded = !detailsExpanded
            render()
        }
        view.findViewById<View>(R.id.tv_status).setOnClickListener {
            if (TaskExecutionManager.executionMode.value == ExecutionMode.BACKGROUND && TaskExecutionManager.isTaskRunning()) {
                requestMode(ExecutionMode.FOREGROUND)
            } else { detailsExpanded = !detailsExpanded; render() }
        }
        view.findViewById<View>(R.id.btn_select_template).setOnClickListener { showTemplateSelection(it) }
        view.findViewById<View>(R.id.btn_start).setOnClickListener {
            val editor = view.findViewById<EditText>(R.id.task_input)
            val text = editor.text.toString().trim()
            if (text.isBlank()) return@setOnClickListener
            val accepted = if (TaskExecutionManager.isTaskRunning()) TaskExecutionManager.sendAndResume(text)
                else TaskExecutionManager.sendMessage(text)
            if (accepted) {
                drafts.clear(draftConversationId)
                editor.text.clear()
                clearInputFocus()
                detailsExpanded = false
                render()
            } else Toast.makeText(this, R.string.capsule_send_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchMode() = requestMode(if (TaskExecutionManager.executionMode.value == ExecutionMode.BACKGROUND)
        ExecutionMode.FOREGROUND else ExecutionMode.BACKGROUND)
    private fun requestMode(mode: ExecutionMode) {
        clearInputFocus()
        if (!TaskExecutionManager.requestExecutionMode(mode)) Toast.makeText(this, R.string.capsule_mode_rejected, Toast.LENGTH_SHORT).show()
    }

    private fun showTemplateSelection(anchor: View) {
        val templates = com.kevinluo.autoglm.settings.SettingsManager.getInstance(this).getTaskTemplates()
        if (templates.isEmpty()) { Toast.makeText(this, R.string.settings_no_templates, Toast.LENGTH_SHORT).show(); return }
        android.widget.PopupMenu(anchor.context, anchor).apply {
            templates.forEachIndexed { index, template -> menu.add(0, index, index, template.name) }
            setOnMenuItemClickListener { item -> floatingView?.findViewById<EditText>(R.id.task_input)?.setText(templates[item.itemId].description); true }
            show()
        }
    }

    private fun setupInput(input: AutoGrowingInput) {
        input.onDismissKeyboard = ::clearInputFocus
        input.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) requestKeyboard(input)
            false
        }
    }

    /** Register before relayout: posting after updateViewLayout alone does not acknowledge window focus. */
    private fun requestKeyboard(input: AutoGrowingInput) {
        cancelKeyboardRequest()
        val root = floatingView ?: return
        val params = layoutParams ?: return
        if (!isAttached.get() || !input.isShown || !FloatingWindowStateManager.shouldBeVisible()) return
        val generation = keyboardRequestGeneration
        val state = TaskExecutionManager.taskState.value
        val runId = state.runId
        val conversationId = state.conversationId
        val interactionKey = TaskExecutionManager.pendingInteraction.value?.let { it.runId to it.id }
        val observer = root.viewTreeObserver
        var scheduled = false
        var postedShow: Runnable? = null

        fun current(): Boolean {
            if (generation != keyboardRequestGeneration || floatingView !== root || !isAttached.get() ||
                !root.isAttachedToWindow || !input.isShown || !bodyExpanded || !FloatingWindowStateManager.shouldBeVisible()) return false
            val current = TaskExecutionManager.taskState.value
            if (current.runId != runId || current.conversationId != conversationId) return false
            val pending = TaskExecutionManager.pendingInteraction.value
            return when (input.id) {
                R.id.et_interaction_answer -> pending?.kind == InteractionKind.TEXT &&
                    interactionKey == (pending.runId to pending.id)
                R.id.task_input -> pending == null && CapsulePresentation.from(current.run,
                    FloatingWindowStateManager.snapshot.value.minimized, detailsExpanded, input.text?.isNotBlank() == true).inputVisible
                else -> false
            }
        }

        fun focusReady() {
            if (!current()) { cancelKeyboardRequest(); return }
            if (scheduled || !root.hasWindowFocus()) return
            if (!input.requestFocus()) { cancelKeyboardRequest(); return }
            scheduled = true
            // This post follows actual window focus and editor focus, allowing InputMethodManager's
            // focus transaction to complete. It is not a timer or a retry of a failed show request.
            postedShow = Runnable {
                if (current() && root.hasWindowFocus() && input.hasFocus()) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
                if (generation == keyboardRequestGeneration) cancelKeyboardRequest()
            }.also { root.post(it) }
        }

        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) focusReady() }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (generation == keyboardRequestGeneration) cancelKeyboardRequest()
            }
        }
        keyboardRequestCleanup = {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
            root.removeOnAttachStateChangeListener(detachListener)
            postedShow?.let(root::removeCallbacks)
        }
        observer.addOnWindowFocusChangeListener(focusListener)
        root.addOnAttachStateChangeListener(detachListener)
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        updateWindow()
        focusReady() // Already-focused overlays need no new focus event.
    }

    private fun cancelKeyboardRequest() {
        keyboardRequestGeneration++
        val cleanup = keyboardRequestCleanup
        keyboardRequestCleanup = null
        cleanup?.invoke()
    }

    private fun clearInputFocus() {
        cancelKeyboardRequest()
        val view = floatingView ?: return
        val input = view.findViewById<EditText>(R.id.task_input).takeIf { it.hasFocus() }
            ?: view.findViewById<EditText>(R.id.et_interaction_answer).takeIf { it.hasFocus() }
        if (input != null) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        layoutParams?.let { params ->
            val flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (flags != params.flags) { params.flags = flags; updateWindow() }
        }
    }

    internal fun applyMinimizedPresentation(minimized: Boolean) { render() }

    private fun scheduleResize() {
        val view = floatingView ?: return
        if (layoutPosted) return
        layoutPosted = true
        view.post {
            layoutPosted = false
            if (floatingView === view) resizeToContent(view)
        }
    }

    private fun setBodyExpanded(view: View, expanded: Boolean) {
        if (bodyExpanded == expanded) return
        bodyExpanded = expanded
        val generation = ++bodyFadeGeneration
        val body = view.findViewById<View>(R.id.capsule_body)
        body.animate().cancel()
        val animate = isAttached.get() && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        if (expanded) {
            body.visibility = View.VISIBLE
            body.alpha = if (animate) 0f else 1f
            body.translationY = if (animate) dp(5).toFloat() else 0f
            if (animate) body.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(180).start()
        } else if (animate) {
            body.animate().alpha(0f).translationY(dp(4).toFloat()).setStartDelay(0).setDuration(110).withEndAction {
                if (floatingView === view && bodyFadeGeneration == generation && !bodyExpanded) body.visibility = View.GONE
            }.start()
        } else { body.visibility = View.GONE; body.alpha = 1f; body.translationY = 0f }
    }

    /** Animate the existing window; stale animators cannot attach or resurrect a window. */
    private fun resizeToContent(view: View) {
        val params = layoutParams ?: return
        val minimized = FloatingWindowStateManager.snapshot.value.minimized
        val width = if (minimized) dp(56) else cardWidth()
        val header = view.findViewById<View>(R.id.header)
        header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(88), View.MeasureSpec.AT_MOST))
        val headerHeight = if (minimized) dp(56) else header.measuredHeight.coerceIn(dp(56), dp(88))
        if (header.layoutParams.height != headerHeight) {
            header.layoutParams = header.layoutParams.apply { height = headerHeight }
        }
        val body = view.findViewById<View>(R.id.capsule_body)
        val visibleFrame = Rect().also(view::getWindowVisibleDisplayFrame)
        val maxHeight = minOf((resources.displayMetrics.heightPixels * .76f).toInt(),
            (visibleFrame.bottom - params.y - dp(12)).takeIf { it > dp(100) } ?: resources.displayMetrics.heightPixels)
        val height = if (minimized || !bodyExpanded) headerHeight else {
            body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            (headerHeight + body.measuredHeight).coerceAtMost(maxHeight.coerceAtLeast(headerHeight + dp(48)))
        }
        if (targetWidth == width && targetHeight == height && (sizeAnimator?.isRunning == true ||
                    (params.width == width && params.height == height))) return
        targetWidth = width
        targetHeight = height
        sizeAnimator?.cancel()
        val animate = isAttached.get() && FloatingWindowStateManager.shouldBeVisible() &&
            (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        if (!animate) { params.width = width; params.height = height; updateWindow(); return }
        val fromWidth = params.width
        val fromHeight = params.height
        sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (abs(height - fromHeight) < dp(50) && width == fromWidth) 150L else 300L
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { animator ->
                if (floatingView !== view || !isAttached.get() || !FloatingWindowStateManager.shouldBeVisible()) return@addUpdateListener
                val fraction = animator.animatedValue as Float
                params.width = (fromWidth + (width - fromWidth) * fraction).roundToInt()
                params.height = (fromHeight + (height - fromHeight) * fraction).roundToInt()
                updateWindow()
            }
            start()
        }
    }

    private fun setupDragBehavior(header: CapsuleDragLayout) {
        var initialX = 0
        var initialY = 0
        header.onDragStart = { layoutParams?.let { initialX = it.x; initialY = it.y } }
        header.onDrag = { dx, dy ->
            layoutParams?.let { params ->
                params.x = (initialX + dx).roundToInt().coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                params.y = (initialY + dy).roundToInt().coerceIn(0, (resources.displayMetrics.heightPixels - dp(56)).coerceAtLeast(0))
                updateWindow()
            }
        }
        header.onDragEnd = {
            layoutParams?.takeIf { FloatingWindowStateManager.snapshot.value.minimized }?.let { params ->
                params.x = if (params.x + params.width / 2 < resources.displayMetrics.widthPixels / 2) dp(8)
                    else resources.displayMetrics.widthPixels - params.width - dp(8)
                updateWindow()
            }
        }
    }

    private fun addWindowInternal() {
        val view = floatingView ?: return
        if (isAttached.get()) return
        try { windowManager?.addView(view, layoutParams); isAttached.set(true) }
        catch (error: Exception) { Logger.e(TAG, "Could not attach overlay", error) }
    }
    private fun removeWindowInternal() {
        cancelKeyboardRequest()
        if (!isAttached.get()) return
        try { windowManager?.removeView(floatingView) }
        catch (error: Exception) { Logger.e(TAG, "Could not detach overlay", error) }
        finally { isAttached.set(false) }
    }
    private fun updateWindow() {
        if (!isAttached.get()) return
        val params = layoutParams ?: return
        params.x = params.x.coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
        try { windowManager?.updateViewLayout(floatingView, params) }
        catch (error: Exception) { Logger.e(TAG, "Could not resize overlay", error) }
    }
    private fun cardWidth() = minOf(resources.displayMetrics.widthPixels - dp(24), dp(400)).coerceAtLeast(dp(200))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun visible(view: View, id: Int, visible: Boolean) {
        val child = view.findViewById<View>(id)
        val next = if (visible) View.VISIBLE else View.GONE
        if (child.visibility == next) return
        if (!visible && (id == R.id.input_area || id == R.id.interaction_panel)) clearInputFocus()
        child.animate().cancel()
        child.visibility = next
        if (visible && isAttached.get() && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
            child.alpha = 0f
            child.translationY = dp(5).toFloat()
            child.animate().alpha(1f).translationY(0f).setStartDelay(40).setDuration(180).start()
        } else { child.alpha = 1f; child.translationY = 0f }
    }
}
