package com.kevinluo.autoglm.ui

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.kevinluo.autoglm.MainActivity
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.home.ChatMessageAdapter
import com.kevinluo.autoglm.core.PendingInteraction
import com.kevinluo.autoglm.core.InteractionKind
import com.kevinluo.autoglm.core.InteractionReply
import com.kevinluo.autoglm.task.TaskExecutionManager
import com.kevinluo.autoglm.screenshot.ScopedFloatingWindowController
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enum representing the current status of a task in the floating window.
 *
 * Used to track and display the execution state of agent tasks.
 *
 */
enum class TaskStatus {
    /** No task is currently running. */
    IDLE,

    /** A task is actively being executed. */
    RUNNING,

    /** Task execution has been paused by the user. */
    PAUSED,

    /** Stop has been requested; the prior run still owns cleanup. */
    CANCELLING,

    /** Task has completed successfully. */
    COMPLETED,

    /** Task has failed due to an error. */
    FAILED,

    /** Waiting for user confirmation to proceed. */
    WAITING_CONFIRMATION,

    /** Waiting for user to take over control. */
    WAITING_TAKEOVER,

    /** Waiting for a free-form user reply. */
    WAITING_FOR_USER,
}

/** Read-only task/chat renderer and Android window host; user gestures submit facade commands. */
class FloatingWindowService :
    Service(),
    ScopedFloatingWindowController {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isAttached = AtomicBoolean(false)
    private var lastInteractionKey: Pair<String, Long>? = null

    // Coroutine scope for UI operations - uses SupervisorJob so child failures don't cancel siblings
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Conversation presentation only; task execution belongs to the coordinator.
    private val chatAdapter = ChatMessageAdapter()

    companion object {
        private const val TAG = "FloatingWindow"

        // Window size as percentage of screen
        private const val WIDTH_PERCENT = 0.80f
        private const val HEIGHT_PERCENT = 0.70f

        @Volatile
        private var instance: FloatingWindowService? = null

        fun getInstance(): FloatingWindowService? = instance

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun requestOverlayPermission(context: Context) {
            val intent =
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        FloatingWindowStateManager.initialize(applicationContext)
        Logger.d(TAG, "Service created")
        observeTaskState()
    }

    /**
     * Observes TaskExecutionManager state flows and updates UI accordingly.
     *
     * Starts coroutines to observe taskState and steps StateFlows.
     * These coroutines are cancelled in onDestroy when serviceScope is cancelled.
     */
    private fun observeTaskState() {
        serviceScope.launch {
            TaskExecutionManager.messages.collect { messages ->
                val list = floatingView?.findViewById<RecyclerView>(R.id.steps_recycler_view)
                val follow = list?.canScrollVertically(1) != true
                chatAdapter.submitMessages(messages)
                if (messages.isNotEmpty() && !FloatingWindowStateManager.snapshot.value.minimized) list?.visibility = View.VISIBLE
                if (follow && messages.isNotEmpty()) list?.scrollToPosition(messages.lastIndex)
            }
        }
        serviceScope.launch { TaskExecutionManager.taskState.collect { updateUIFromTaskState(it) } }
        serviceScope.launch { TaskExecutionManager.pendingInteraction.collect { renderInteraction(it) } }
        serviceScope.launch {
            TaskExecutionManager.activeWait.collect { active ->
                floatingView?.findViewById<MaterialButton>(R.id.btn_finish_wait)?.visibility =
                    if (active == null || FloatingWindowStateManager.snapshot.value.minimized) View.GONE else View.VISIBLE
            }
        }
    }
    /**
     * Updates the UI based on TaskExecutionState from TaskExecutionManager.
     *
     * @param state The current task execution state
     */
    private fun updateUIFromTaskState(state: com.kevinluo.autoglm.task.TaskExecutionState) {
        val view = floatingView ?: return
        view.findViewById<MaterialButton>(R.id.btn_new_task)?.isEnabled = !state.isExecuting
        view.findViewById<MaterialButton>(R.id.btn_start)?.isEnabled = state.status != TaskStatus.CANCELLING
        view.findViewById<MaterialButton>(R.id.btn_stop)?.isEnabled = state.status != TaskStatus.CANCELLING
        view.findViewById<TextView>(R.id.token_usage)?.text = getString(
            R.string.overlay_token_usage, formatTokenCount(state.totalInputTokens), formatTokenCount(state.totalOutputTokens),
        )
        updateStatusIndicator(state.status)
        updateUIForStatus(state.status)
        view.findViewById<TextView>(R.id.tv_step_counter)?.text = getString(R.string.step_counter_format, state.stepNumber)
        showResult(state.resultMessage, state.status)
        applyMinimizedPresentation(FloatingWindowStateManager.snapshot.value.minimized)
    }
    /**
     * Updates the status indicator UI elements.
     *
     * @param status The current task status
     */
    private fun updateStatusIndicator(status: TaskStatus) {
        floatingView?.let { view ->
            val statusText = view.findViewById<TextView>(R.id.tv_status)
            val indicator = view.findViewById<View>(R.id.status_indicator)

            val (textRes, colorRes) =
                when (status) {
                    TaskStatus.IDLE -> R.string.task_status_idle to R.color.status_idle
                    TaskStatus.RUNNING -> R.string.task_status_running to R.color.status_running
                    TaskStatus.PAUSED -> R.string.task_status_paused to R.color.status_paused
                    TaskStatus.CANCELLING -> R.string.task_status_cancelling to R.color.status_waiting
                    TaskStatus.COMPLETED -> R.string.task_status_completed to R.color.status_completed
                    TaskStatus.FAILED -> R.string.task_status_failed to R.color.status_failed
                    TaskStatus.WAITING_CONFIRMATION -> R.string.floating_waiting_confirm to R.color.status_waiting
                    TaskStatus.WAITING_TAKEOVER, TaskStatus.WAITING_FOR_USER -> R.string.takeover_title to R.color.status_waiting
                }

            statusText?.text = getString(textRes)
            indicator?.let {
                val drawable =
                    (it.background as? GradientDrawable)
                        ?: GradientDrawable().also { d -> it.background = d }
                drawable.shape = GradientDrawable.OVAL
                drawable.setColor(ContextCompat.getColor(this@FloatingWindowService, colorRes))
            }
        }
    }

    /**
     * Updates the steps list from TaskExecutionManager.
     *
     * @param steps The list of task steps from TaskExecutionManager
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startOverlayForeground()
        if (floatingView == null && canDrawOverlays(this)) {
            createWindowView()
        }
        // START_STICKY does not replay the old show() call after service recreation.
        if (FloatingWindowStateManager.shouldBeVisible()) show()
        return START_STICKY
    }

    private fun startOverlayForeground() {
        val channel = "automation_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, getString(R.string.overlay_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_layers)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_body))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
        ServiceCompat.startForeground(this, 2003, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Logger.d(TAG, "Service destroying")
        instance = null
        // Cancel all coroutines
        serviceScope.cancel()
        removeWindow()
        super.onDestroy()
    }

    // ==================== FloatingWindowController ====================

    /**
     * Hides the floating window from the screen.
     *
     * Clears input focus and hides the keyboard before removing the window.
     *
     */
    override fun hide() {
        serviceScope.launch {
            Logger.d(TAG, "hide() called, isAttached=${isAttached.get()}")
            if (isAttached.get()) {
                // Clear focus and hide keyboard before hiding window
                clearInputFocus()
                removeWindowInternal()
            }
        }
    }

    override fun acquireHidden(): AutoCloseable = FloatingWindowStateManager.acquireHide(applicationContext)
    override fun acquirePauseVisibility(): AutoCloseable = FloatingWindowStateManager.acquirePauseVisibility(applicationContext)

    /**
     * Clears input focus and hides keyboard.
     *
     * After clearing focus, adds FLAG_NOT_FOCUSABLE so back key works in other apps.
     */
    private fun clearInputFocus() {
        val taskInput = floatingView?.findViewById<EditText>(R.id.task_input)?.takeIf { it.hasFocus() }
            ?: floatingView?.findViewById<EditText>(R.id.et_interaction_answer)?.takeIf { it.hasFocus() }
            ?: return

        Logger.d(TAG, "clearInputFocus: clearing focus and hiding keyboard")

        // Hide keyboard first
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(taskInput.windowToken, 0)

        // Clear focus
        taskInput.clearFocus()

        // Add FLAG_NOT_FOCUSABLE so back key works in other apps
        layoutParams?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (isAttached.get()) {
                try {
                    windowManager?.updateViewLayout(floatingView, params)
                    Logger.d(TAG, "clearInputFocus: added FLAG_NOT_FOCUSABLE")
                } catch (e: Exception) {
                    Logger.e(TAG, "Error updating layout after clearing focus", e)
                }
            }
        }
    }

    /**
     * Shows the floating window on the screen.
     *
     * Creates the window view if not already created, then adds it to the window manager.
     *
     */
    override fun show() {
        serviceScope.launch {
            if (!FloatingWindowStateManager.shouldBeVisible()) return@launch
            Logger.d(TAG, "show() called, isAttached=${isAttached.get()}, floatingView=${floatingView != null}")

            // Create window view if not created yet
            if (floatingView == null) {
                createWindowView()
            }

            if (!isAttached.get() && floatingView != null) {
                addWindowInternal()
            }
        }
    }

    /**
     * Shows the floating window and brings it to the front.
     *
     * If the window is already attached, removes and re-adds it to ensure it's on top.
     *
     */
    override fun showAndBringToFront() {
        serviceScope.launch {
            if (!FloatingWindowStateManager.shouldBeVisible()) return@launch
            val attached = isAttached.get()
            val hasView = floatingView != null
            Logger.d(TAG, "showAndBringToFront() called, isAttached=$attached, floatingView=$hasView")

            // Create window view if not created yet
            if (floatingView == null) {
                createWindowView()
            }

            if (floatingView != null) {
                if (isAttached.get()) {
                    removeWindowInternal()
                }
                addWindowInternal()
            }
        }
    }

    /**
     * Checks if the floating window is currently visible.
     *
     * @return true if the window is attached and visible, false otherwise
     *
     */
    override fun isVisible(): Boolean = isAttached.get()

    /** Result text is presentation only; successful answers already appear as chat bubbles. */
    private fun showResult(message: String, status: TaskStatus) {
        floatingView?.findViewById<TextView>(R.id.tv_result)?.apply {
            val failed = status == TaskStatus.FAILED
            visibility = if (failed && message.isNotBlank()) View.VISIBLE else View.GONE
            text = if (failed) message else ""
            setTextColor(ContextCompat.getColor(this@FloatingWindowService, if (status == TaskStatus.COMPLETED) R.color.status_completed else R.color.status_failed))
        }
    }

    /** UI owns only listeners and editor contents. The coordinator owns the pending operation. */
    private fun renderInteraction(pending: PendingInteraction?) {
        val view = floatingView ?: return
        val panel = view.findViewById<View>(R.id.interaction_panel)
        if (pending == null) {
            if (lastInteractionKey != null) clearInputFocus()
            lastInteractionKey = null
            panel.visibility = View.GONE
            return
        }
        val key = pending.runId to pending.id
        if (lastInteractionKey == key) {
            panel.visibility = if (FloatingWindowStateManager.snapshot.value.minimized) View.GONE else View.VISIBLE
            return
        }
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
            TaskExecutionManager.replyInteraction(pending.runId, pending.id, reply)
        }
        view.findViewById<MaterialButton>(R.id.btn_interaction_cancel).setOnClickListener {
            TaskExecutionManager.replyInteraction(pending.runId, pending.id, InteractionReply.Cancelled)
        }
        pending.options.forEachIndexed { index, option ->
            // Match the theme used to inflate the overlay; Service context lacks it.
            options.addView(MaterialButton(options.context).apply {
                text = option
                textSize = 13f
                setOnClickListener {
                    TaskExecutionManager.replyInteraction(pending.runId, pending.id, InteractionReply.Choice(index))
                }
            })
        }
        panel.visibility = View.VISIBLE
        if (pending.kind == InteractionKind.TEXT) {
            answer.requestFocus()
            layoutParams?.let { params ->
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                if (isAttached.get()) windowManager?.updateViewLayout(floatingView, params)
            }
            answer.post {
                val current = TaskExecutionManager.pendingInteraction.value
                if (current?.runId == pending.runId && current.id == pending.id && answer.isAttachedToWindow) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(answer, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }
    // ==================== Private Methods ====================

    /**
     * Updates the UI elements based on the current task status.
     *
     * @param status The current task status
     */
    private fun updateUIForStatus(status: TaskStatus) {
        Logger.d(TAG, "updateUIForStatus called with status: $status")
        floatingView?.let { view ->
            val inputArea = view.findViewById<LinearLayout>(R.id.input_area)
            val stepsRecycler = view.findViewById<RecyclerView>(R.id.steps_recycler_view)
            val controlButtonsContainer = view.findViewById<LinearLayout>(R.id.control_buttons_container)
            val stopBtn = view.findViewById<MaterialButton>(R.id.btn_stop)
            val pauseBtn = view.findViewById<MaterialButton>(R.id.btn_pause)
            val resumeBtn = view.findViewById<MaterialButton>(R.id.btn_resume)
            val newTaskBtn = view.findViewById<MaterialButton>(R.id.btn_new_task)

            Logger.d(TAG, "updateUIForStatus: inputArea=$inputArea, stepsRecycler=$stepsRecycler, stopBtn=$stopBtn")

            when (status) {
                TaskStatus.CANCELLING -> {
                    inputArea?.visibility = View.VISIBLE
                    stepsRecycler?.visibility = View.VISIBLE
                    controlButtonsContainer?.visibility = View.VISIBLE
                    pauseBtn?.visibility = View.GONE
                    resumeBtn?.visibility = View.GONE
                    stopBtn?.visibility = View.VISIBLE
                    stopBtn?.isEnabled = false
                    newTaskBtn?.visibility = View.GONE
                    view.findViewById<MaterialButton>(R.id.btn_start)?.isEnabled = false
                }
                TaskStatus.IDLE -> {
                    // Show input area, hide steps and control buttons
                    Logger.d(TAG, "updateUIForStatus: Setting IDLE state - show input, hide steps")
                    inputArea?.visibility = View.VISIBLE
                    stepsRecycler?.visibility = if (chatAdapter.itemCount > 0) View.VISIBLE else View.GONE
                    controlButtonsContainer?.visibility = View.GONE
                    newTaskBtn?.visibility = View.GONE
                    view.findViewById<EditText>(R.id.task_input)?.setHint(R.string.task_input_hint)
                    view.findViewById<MaterialButton>(R.id.btn_start)?.setText(R.string.send_message)
                }

                TaskStatus.RUNNING, TaskStatus.WAITING_CONFIRMATION, TaskStatus.WAITING_TAKEOVER, TaskStatus.WAITING_FOR_USER -> {
                    inputArea?.visibility = View.VISIBLE
                    stepsRecycler?.visibility = View.VISIBLE
                    controlButtonsContainer?.visibility = View.VISIBLE
                    pauseBtn?.visibility = View.VISIBLE
                    resumeBtn?.visibility = View.GONE
                    stopBtn?.visibility = View.VISIBLE
                    newTaskBtn?.visibility = View.GONE
                    view.findViewById<EditText>(R.id.task_input)?.setHint(R.string.running_message_hint)
                    view.findViewById<MaterialButton>(R.id.btn_start)?.setText(R.string.send_message)
                }

                TaskStatus.PAUSED -> {
                    inputArea?.visibility = View.VISIBLE
                    stepsRecycler?.visibility = View.VISIBLE
                    controlButtonsContainer?.visibility = View.VISIBLE
                    pauseBtn?.visibility = View.GONE
                    resumeBtn?.visibility = View.VISIBLE
                    stopBtn?.visibility = View.VISIBLE
                    newTaskBtn?.visibility = View.GONE
                    view.findViewById<EditText>(R.id.task_input)?.setHint(R.string.running_message_hint)
                    view.findViewById<MaterialButton>(R.id.btn_start)?.setText(R.string.send_message)
                }

                TaskStatus.COMPLETED, TaskStatus.FAILED -> {
                    // Keep conversation input available and offer a separate new-session action.
                    inputArea?.visibility = View.VISIBLE
                    stepsRecycler?.visibility = View.VISIBLE
                    controlButtonsContainer?.visibility = View.GONE
                    newTaskBtn?.visibility = View.VISIBLE
                    newTaskBtn?.setText(R.string.new_conversation)
                    view.findViewById<EditText>(R.id.task_input)?.setHint(R.string.continue_conversation_hint)
                    view.findViewById<MaterialButton>(R.id.btn_start)?.setText(R.string.send_message)
                }
            }
            val inputVis = inputArea?.visibility
            val stepsVis = stepsRecycler?.visibility
            Logger.d(TAG, "updateUIForStatus: After update - inputArea.vis=$inputVis, stepsRecycler.vis=$stepsVis")
        } ?: Logger.w(TAG, "updateUIForStatus: floatingView is null!")
    }

    /**
     * Creates and shows the floating window.
     */
    /**
     * Creates the window view without showing it.
     */
    private fun createWindowView() {
        if (floatingView != null) {
            Logger.d(TAG, "Window view already created")
            return
        }

        Logger.d(TAG, "Creating floating window view")

        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_AutoGLM)
        floatingView =
            LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_window, null)

        layoutParams = createLayoutParams()

        setupRecyclerView()
        setupDragBehavior()
        setupButtons()
        setupTaskInput()

        // Render the latest read-only projection when this platform view is recreated.
        val currentState = TaskExecutionManager.taskState.value
        updateUIForStatus(currentState.status)
        updateStatusIndicator(currentState.status)
        updateUIFromTaskState(currentState)
        renderInteraction(TaskExecutionManager.pendingInteraction.value)
        if (currentState.stepNumber > 0) {
            floatingView?.findViewById<TextView>(R.id.tv_step_counter)?.text =
                getString(R.string.step_counter_format, currentState.stepNumber)
        }

        // Setup touch listener to clear focus when tapping outside input
        setupTouchToClearFocus()

        Logger.d(TAG, "Floating window view created")
    }

    /**
     * Sets up touch listener on the floating view to clear input focus
     * when user taps outside the input field or outside the window.
     */
    private fun setupTouchToClearFocus() {
        floatingView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) {
                val taskInput = floatingView?.findViewById<EditText>(R.id.task_input)
                if (taskInput?.hasFocus() == true) {
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        // Touch outside window, clear focus
                        Logger.d(TAG, "Touch outside window, clearing focus")
                        clearInputFocus()
                    } else {
                        // Check if touch is outside the input field
                        val inputLocation = IntArray(2)
                        taskInput.getLocationOnScreen(inputLocation)
                        val inputRect =
                            android.graphics.Rect(
                                inputLocation[0],
                                inputLocation[1],
                                inputLocation[0] + taskInput.width,
                                inputLocation[1] + taskInput.height,
                            )

                        // Get touch position relative to screen
                        val touchX = event.rawX.toInt()
                        val touchY = event.rawY.toInt()

                        if (!inputRect.contains(touchX, touchY)) {
                            // Touch is outside input, clear focus
                            Logger.d(TAG, "Touch outside input, clearing focus")
                            clearInputFocus()
                        }
                    }
                }
            }
            false // Don't consume the event, let it propagate
        }
    }

    /**
     * Creates the window layout parameters.
     *
     * @return WindowManager.LayoutParams configured for the floating window
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val widthPx = (screenWidth * WIDTH_PERCENT).toInt()
        val heightPx = (screenHeight * HEIGHT_PERCENT).toInt()

        return WindowManager
            .LayoutParams(
                widthPx,
                heightPx,
                type,
                // FLAG_NOT_TOUCH_MODAL: allow touches outside window to pass through
                // FLAG_WATCH_OUTSIDE_TOUCH: receive ACTION_OUTSIDE events to clear focus
                // FLAG_NOT_FOCUSABLE: initially not focusable so back key works in other apps
                // When user clicks input, FLAG_NOT_FOCUSABLE will be removed to allow keyboard
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                // Allow keyboard input
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
    }

    /**
     * Sets up the RecyclerView for displaying steps.
     */
    private fun setupRecyclerView() {
        val recyclerView = floatingView?.findViewById<RecyclerView>(R.id.steps_recycler_view)
        recyclerView?.let { rv ->
            rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
            rv.adapter = chatAdapter
            chatAdapter.submitMessages(TaskExecutionManager.messages.value)
        }
    }

    /**
     * Sets up the task input field and start button.
     */
    private fun setupTaskInput() {
        val taskInput = floatingView?.findViewById<EditText>(R.id.task_input)
        val startBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_start)
        val selectTemplateBtn = floatingView?.findViewById<ImageButton>(R.id.btn_select_template)

        // Use OnTouchListener to handle focus BEFORE the click event
        // This ensures FLAG_NOT_FOCUSABLE is removed before system tries to show keyboard
        taskInput?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                Logger.d(TAG, "taskInput touched (ACTION_DOWN)")
                layoutParams?.let { params ->
                    val wasNotFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
                    if (wasNotFocusable) {
                        // Remove FLAG_NOT_FOCUSABLE to allow focus BEFORE the touch is processed
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        if (isAttached.get()) {
                            try {
                                windowManager?.updateViewLayout(floatingView, params)
                                Logger.d(TAG, "setupTaskInput: removed FLAG_NOT_FOCUSABLE on touch")

                                // Use ViewTreeObserver to wait for window focus, then show keyboard
                                floatingView?.viewTreeObserver?.addOnWindowFocusChangeListener(
                                    object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                                        override fun onWindowFocusChanged(hasFocus: Boolean) {
                                            Logger.d(TAG, "Window focus changed: hasFocus=$hasFocus")
                                            if (hasFocus) {
                                                floatingView?.viewTreeObserver?.removeOnWindowFocusChangeListener(this)
                                                taskInput.requestFocus()
                                                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                                                imm.showSoftInput(taskInput, InputMethodManager.SHOW_IMPLICIT)
                                            }
                                        }
                                    },
                                )
                            } catch (e: Exception) {
                                Logger.e(TAG, "Error updating layout for focus", e)
                            }
                        }
                    }
                }
            }
            false // Don't consume the event, let it propagate for normal EditText behavior
        }

        taskInput?.doAfterTextChanged { text ->
            if (text?.isNotBlank() == true) TaskExecutionManager.pauseForDraft()
            else TaskExecutionManager.onEditorBlur(false)
        }
        taskInput?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) TaskExecutionManager.onEditorBlur(taskInput.text?.isNotBlank() == true)
        }

        // Template selection button
        selectTemplateBtn?.setOnClickListener {
            showTemplateSelectionPopup(it, taskInput)
        }

        startBtn?.setOnClickListener {
            val task = taskInput?.text?.toString()?.trim() ?: ""
            if (task.isNotBlank()) {
                if (TaskExecutionManager.isTaskRunning()) {
                    if (TaskExecutionManager.sendMessage(task)) taskInput?.text?.clear()
                    return@setOnClickListener
                }
                // Check if we can start a task and get specific reason if not
                val blockReason = TaskExecutionManager.getStartTaskBlockReason()
                if (blockReason != TaskExecutionManager.StartTaskBlockReason.NONE) {
                    Logger.w(TAG, "Cannot start task: $blockReason")
                    val messageRes =
                        when (blockReason) {
                            TaskExecutionManager.StartTaskBlockReason.SERVICE_NOT_CONNECTED -> {
                                R.string.toast_shizuku_not_running
                            }

                            TaskExecutionManager.StartTaskBlockReason.PHONE_AGENT_NULL -> {
                                R.string.toast_phone_agent_not_ready
                            }

                            TaskExecutionManager.StartTaskBlockReason.TASK_ALREADY_RUNNING -> {
                                R.string.toast_task_already_running
                            }

                            TaskExecutionManager.StartTaskBlockReason.NONE -> {
                                R.string.toast_shizuku_not_running
                            } // Should not happen
                        }
                    Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (TaskExecutionManager.sendMessage(task)) {
                    taskInput?.text?.clear()
                    clearInputFocus()
                }
            }
        }
    }

    /**
     * Shows a popup menu for template selection.
     *
     * @param anchor The view to anchor the popup to
     * @param taskInput The EditText to populate with the selected template
     */
    private fun showTemplateSelectionPopup(anchor: View, taskInput: EditText?) {
        val settingsManager =
            com.kevinluo.autoglm.settings
                .SettingsManager.getInstance(this)
        val templates = settingsManager.getTaskTemplates()

        if (templates.isEmpty()) {
            android.widget.Toast
                .makeText(
                    this,
                    R.string.settings_no_templates,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            return
        }

        val popup = android.widget.PopupMenu(this, anchor)
        templates.forEachIndexed { index, template ->
            popup.menu.add(0, index, index, template.name)
        }

        popup.setOnMenuItemClickListener { item ->
            val selectedTemplate = templates[item.itemId]
            taskInput?.setText(selectedTemplate.description)
            true
        }

        popup.show()
    }

    /**
     * Adds the floating window to the window manager.
     */
    private fun addWindowInternal() {
        if (isAttached.get() || floatingView == null || layoutParams == null) {
            val attached = isAttached.get()
            val hasView = floatingView != null
            val hasParams = layoutParams != null
            Logger.w(TAG, "Cannot add window: isAttached=$attached, view=$hasView, params=$hasParams")
            return
        }

        try {
            Logger.d(TAG, "Adding window with params: x=${layoutParams?.x}, y=${layoutParams?.y}")
            windowManager?.addView(floatingView, layoutParams)
            isAttached.set(true)
            Logger.d(TAG, "Window added successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to add window", e)
        }
    }

    /**
     * Removes the floating window from the window manager.
     */
    private fun removeWindowInternal() {
        if (!isAttached.get() || floatingView == null) {
            Logger.w(TAG, "Cannot remove window: isAttached=${isAttached.get()}, view=${floatingView != null}")
            return
        }

        try {
            windowManager?.removeView(floatingView)
            isAttached.set(false)
            Logger.d(TAG, "Window removed successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to remove window", e)
        }
    }

    /**
     * Removes the window and cleans up resources.
     */
    private fun removeWindow() {
        if (isAttached.get() && floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                Logger.e(TAG, "Error removing window", e)
            }
        }
        floatingView = null
        layoutParams = null
        isAttached.set(false)
    }

    /**
     * Sets up drag behavior for the floating window header.
     */
    private fun setupDragBehavior() {
        val header = floatingView?.findViewById<View>(R.id.header) ?: return
        val params = layoutParams ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isAttached.get()) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(floatingView, params)
                        } catch (e: Exception) {
                            Logger.e(TAG, "Error updating layout", e)
                        }
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }
    }

    /**
     * Sets up click listeners for the floating window buttons.
     */
    private fun setupButtons() {
        floatingView?.findViewById<ImageButton>(R.id.btn_open_app)?.setOnClickListener {
            // Open MainActivity
            val intent =
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            startActivity(intent)
        }

        floatingView?.findViewById<ImageButton>(R.id.btn_minimize)?.setOnClickListener {
            toggleMinimize()
        }

        floatingView?.findViewById<ImageButton>(R.id.btn_close)?.setOnClickListener {
            // Closing is explicit: do not resurrect the service on the next app background event.
            if (TaskExecutionManager.isTaskRunning()) TaskExecutionManager.cancelTask()
            FloatingWindowStateManager.close(this)
        }

        floatingView?.findViewById<MaterialButton>(R.id.btn_stop)?.setOnClickListener {
            Logger.d(TAG, "Stop button clicked")
            TaskExecutionManager.cancelTask()
        }

        floatingView?.findViewById<MaterialButton>(R.id.btn_finish_wait)?.setOnClickListener {
            TaskExecutionManager.finishWaiting()
        }

        floatingView?.findViewById<MaterialButton>(R.id.btn_pause)?.setOnClickListener {
            Logger.d(TAG, "Pause button clicked")
            TaskExecutionManager.pauseTask()
        }

        floatingView?.findViewById<MaterialButton>(R.id.btn_resume)?.setOnClickListener {
            Logger.d(TAG, "Resume button clicked")
            TaskExecutionManager.resumeTask()
        }

        floatingView?.findViewById<MaterialButton>(R.id.btn_new_task)?.setOnClickListener {
            Logger.d(TAG, "New task button clicked")
            // Reset TaskExecutionManager and local UI state
            TaskExecutionManager.resetTask()
            floatingView?.findViewById<EditText>(R.id.task_input)?.text?.clear()
        }
    }

    /**
     * Toggles the minimized state of the floating window.
     */
    private fun toggleMinimize() {
        FloatingWindowStateManager.setMinimized(!FloatingWindowStateManager.snapshot.value.minimized)
    }

    internal fun applyMinimizedPresentation(minimized: Boolean) {
        if (floatingView == null) return
        val inputArea = floatingView?.findViewById<LinearLayout>(R.id.input_area)
        val recyclerView = floatingView?.findViewById<RecyclerView>(R.id.steps_recycler_view)
        val resultView = floatingView?.findViewById<TextView>(R.id.tv_result)
        val stopBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_stop)
        val pauseBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_pause)
        val resumeBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_resume)
        val newTaskBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_new_task)
        val minimizeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_minimize)
        val container = floatingView?.findViewById<View>(R.id.floating_window_container)
        val tokenUsage = floatingView?.findViewById<View>(R.id.token_usage)
        val interaction = floatingView?.findViewById<View>(R.id.interaction_panel)
        val finishWait = floatingView?.findViewById<View>(R.id.btn_finish_wait)
        val previousWidth = layoutParams?.width
        val previousHeight = layoutParams?.height

        tokenUsage?.visibility = if (minimized) View.GONE else View.VISIBLE

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        if (minimized) {
            // Hide all content except header
            inputArea?.visibility = View.GONE
            recyclerView?.visibility = View.GONE
            resultView?.visibility = View.GONE
            stopBtn?.visibility = View.GONE
            pauseBtn?.visibility = View.GONE
            resumeBtn?.visibility = View.GONE
            newTaskBtn?.visibility = View.GONE
            interaction?.visibility = View.GONE
            finishWait?.visibility = View.GONE
            // Change icon to + (expand)
            minimizeBtn?.setImageResource(R.drawable.ic_plus)

            // Shrink window to just header - use fixed height based on header size
            layoutParams?.width = (screenWidth * WIDTH_PERCENT * 0.8).toInt()
            // Use a fixed height for minimized state (header height ~48dp)
            val headerHeight = (48 * displayMetrics.density).toInt()
            layoutParams?.height = headerHeight
        } else {
            // Restore based on current status
            updateUIForStatus(TaskExecutionManager.taskState.value.status)
            val pending = TaskExecutionManager.pendingInteraction.value
            interaction?.visibility = if (pending == null) View.GONE else View.VISIBLE
            finishWait?.visibility = if (TaskExecutionManager.activeWait.value == null) View.GONE else View.VISIBLE
            if (resultView?.text?.isNotEmpty() == true) {
                resultView.visibility = View.VISIBLE
            }
            // Change icon to - (minimize)
            minimizeBtn?.setImageResource(R.drawable.ic_minus)

            // Restore full window size
            layoutParams?.width = (screenWidth * WIDTH_PERCENT).toInt()
            layoutParams?.height = (screenHeight * HEIGHT_PERCENT).toInt()
        }

        if (isAttached.get() && (previousWidth != layoutParams?.width || previousHeight != layoutParams?.height)) {
            try {
                windowManager?.updateViewLayout(floatingView, layoutParams)
            } catch (e: Exception) {
                Logger.e(TAG, "Error updating layout after minimize", e)
            }
        }
    }

}
