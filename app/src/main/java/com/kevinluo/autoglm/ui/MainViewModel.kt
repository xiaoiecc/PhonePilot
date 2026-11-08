package com.kevinluo.autoglm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevinluo.autoglm.task.TaskExecutionManager
import com.kevinluo.autoglm.task.isActive
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Data class representing the permission states across the app.
 *
 * Used for cross-Fragment state sharing to synchronize permission UI.
 *
 * @property shizuku Whether Shizuku permission is granted and connected
 * @property overlay Whether overlay (draw over other apps) permission is granted
 * @property keyboard Whether the custom keyboard is enabled
 * @property battery Whether battery optimization is disabled for the app
 */
data class PermissionStates(
    val shizuku: Boolean = false,
    val overlay: Boolean = false,
    val keyboard: Boolean = false,
    val battery: Boolean = false,
    val accessibility: Boolean = false,
)

/**
 * Enum representing the types of permissions the app requires.
 */
enum class PermissionType {
    /** Shizuku permission for system-level operations. */
    SHIZUKU,

    /** Overlay permission for floating window. */
    OVERLAY,

    /** Keyboard permission for custom input method. */
    KEYBOARD,

    /** Battery optimization exemption. */
    BATTERY,

    /** Accessibility service enabled and connected. */
    ACCESSIBILITY,
}

/**
 * Data class representing the UI state for the main screen.
 *
 * @property shizukuStatus Current Shizuku connection status
 * @property hasOverlayPermission Whether the app has overlay permission
 * @property taskStatus Current task execution status
 * @property stepNumber Current step number in task execution
 * @property thinking Current thinking text from the model
 * @property currentAction Current action being executed
 * @property isTaskRunning Whether a task is currently running
 * @property canStartTask Whether a new task can be started
 * @property hasAttachments Whether the composer contains image attachments
 *
 */
data class MainUiState(
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val hasOverlayPermission: Boolean = false,
    val taskStatus: TaskStatus = TaskStatus.IDLE,
    val stepNumber: Int = 0,
    val thinking: String = "",
    val currentAction: String = "",
    val isTaskRunning: Boolean = false,
    val canStartTask: Boolean = false,
    val canContinueConversation: Boolean = false,
    val hasAttachments: Boolean = false,
)

/**
 * Enum representing the Shizuku connection status.
 *
 */
enum class ShizukuStatus {
    /** Shizuku service is not running. */
    NOT_RUNNING,

    /** Shizuku is running but permission not granted. */
    NO_PERMISSION,

    /** Currently connecting to Shizuku. */
    CONNECTING,

    /** Successfully connected to Shizuku. */
    CONNECTED,
}

/**
 * Sealed class representing one-time UI events.
 *
 * These events are consumed once and not persisted in the UI state.
 *
 */
sealed class MainUiEvent {
    /**
     * Event to show a toast message from a string resource.
     *
     * @property messageResId The string resource ID for the message
     */
    data class ShowToast(val messageResId: Int) : MainUiEvent()

    /**
     * Event to show a toast message with a text string.
     *
     * @property message The message text to display
     */
    data class ShowToastText(val message: String) : MainUiEvent()

    /**
     * Event indicating task completion.
     *
     * @property message The completion message
     */
    sealed class RunEvent(open val runId: String) : MainUiEvent()
    data class TaskCompleted(override val runId: String, val message: String) : RunEvent(runId)

    /**
     * Event indicating task failure.
     *
     * @property error The error message
     */
    data class TaskFailed(override val runId: String, val error: String) : RunEvent(runId)

    /** Event to minimize the app. */
    data class MinimizeApp(override val runId: String) : RunEvent(runId)
}

/**
 * ViewModel for MainActivity.
 *
 * Manages UI state using StateFlow for reactive updates and observes
 * [TaskExecutionManager] to receive task execution state updates.
 *
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())

    /** Observable UI state for the main screen. */
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainUiEvent>()

    /** Observable stream of one-time UI events. */
    val events = _events.asSharedFlow()

    private val _outputLog = MutableStateFlow("")

    /** Observable output log for displaying task execution details. */
    val outputLog: StateFlow<String> = _outputLog.asStateFlow()

    private val logBuilder = StringBuilder()
    private val conversationDrafts = ComposerDraftStore()
    private var attachmentSelection: ComposerDraftStore.ImportTicket? = null

    fun saveDraft(conversationId: String?, text: String, images: List<com.kevinluo.autoglm.core.ChatImage> = emptyList()) {
        conversationDrafts.save(conversationId, text, images)
    }
    fun draftFor(conversationId: String?): String = conversationDrafts.get(conversationId).text
    fun attachmentsFor(conversationId: String?): List<com.kevinluo.autoglm.core.ChatImage> =
        conversationDrafts.get(conversationId).images
    fun beginAttachmentSelection(conversationId: String?) {
        attachmentSelection = conversationDrafts.ticket(conversationId)
    }
    fun takeAttachmentSelection(): ComposerDraftStore.ImportTicket? =
        attachmentSelection.also { attachmentSelection = null }
    fun appendDraftAttachments(ticket: ComposerDraftStore.ImportTicket, images: List<com.kevinluo.autoglm.core.ChatImage>): Boolean =
        conversationDrafts.append(ticket, images)
    fun clearDraft(conversationId: String?) = conversationDrafts.clear(conversationId)

    // region Cross-Fragment State Sharing

    private val _permissionStates = MutableStateFlow(PermissionStates())

    /**
     * Observable permission states for cross-Fragment synchronization.
     *
     * When permissions change in SettingsFragment, other Fragments can observe
     * this StateFlow to update their UI accordingly.
     */
    val permissionStates: StateFlow<PermissionStates> = _permissionStates.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)

    /**
     * Observable Shizuku service connection state.
     *
     * Indicates whether the UserService is bound and ready for use.
     */
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    // endregion

    init {
        observeTaskState()
    }

    /**
     * Observes TaskExecutionManager.taskState and updates _uiState accordingly.
     *
     * This replaces the previous PhoneAgentListener implementation.
     */
    private fun observeTaskState() {
        viewModelScope.launch {
            TaskExecutionManager.taskState.collect { taskState ->
                Logger.d(TAG, "TaskState updated: status=${taskState.status}, step=${taskState.stepNumber}")

                val isRunning = taskState.status.isActive || taskState.isExecuting

                _uiState.value =
                    _uiState.value.copy(
                        taskStatus = taskState.status,
                        stepNumber = taskState.stepNumber,
                        thinking = taskState.thinking,
                        currentAction = taskState.currentAction,
                        isTaskRunning = isRunning,
                        canStartTask = calculateCanStartTask(isRunning = isRunning),
                        canContinueConversation = TaskExecutionManager.canContinueConversation(),
                    )

            }
        }
        viewModelScope.launch {
            TaskExecutionManager.deviceToolRequests.collect { runId ->
                _events.emit(MainUiEvent.MinimizeApp(runId))
            }
        }
        viewModelScope.launch {
            TaskExecutionManager.completions.collect { completed ->
                if (!com.kevinluo.autoglm.task.RunEventIdentity.accepts(completed.runId, TaskExecutionManager.taskState.value.runId)) return@collect
                val result = completed.outcome
                appendLog(if (result.success) "Task completed: ${result.message}" else "Task failed: ${result.message}")
                _events.emit(if (result.success) MainUiEvent.TaskCompleted(completed.runId, result.message) else MainUiEvent.TaskFailed(completed.runId, result.message))
            }
        }
    }

    /**
     * Updates the Shizuku connection status.
     *
     * @param status The new Shizuku status
     *
     */
    fun updateShizukuStatus(status: ShizukuStatus) {
        Logger.d(TAG, "updateShizukuStatus: $status")
        _uiState.value =
            _uiState.value.copy(
                shizukuStatus = status,
                canStartTask = calculateCanStartTask(status),
            )
        // Sync with permission states for cross-Fragment sharing
        val isConnected = status == ShizukuStatus.CONNECTED
        updatePermissionState(PermissionType.SHIZUKU, isConnected)
        _isServiceConnected.value = isConnected
    }

    /**
     * Updates the overlay permission status.
     *
     * @param hasPermission Whether the app has overlay permission
     *
     */
    fun updateOverlayPermission(hasPermission: Boolean) {
        Logger.d(TAG, "updateOverlayPermission: $hasPermission")
        _uiState.value =
            _uiState.value.copy(
                hasOverlayPermission = hasPermission,
                canStartTask = calculateCanStartTask(hasOverlayPermission = hasPermission),
            )
        // Also update permission states for cross-Fragment synchronization
        updatePermissionState(PermissionType.OVERLAY, hasPermission)
    }

    // region Cross-Fragment State Update Methods

    /**
     * Updates a specific permission state.
     *
     * This method updates the shared permission states that can be observed
     * by all Fragments. Use this when a permission is granted or revoked.
     *
     * @param type The type of permission being updated
     * @param granted Whether the permission is granted
     */
    fun updatePermissionState(type: PermissionType, granted: Boolean) {
        Logger.d(TAG, "updatePermissionState: $type = $granted")
        _permissionStates.value =
            when (type) {
                PermissionType.SHIZUKU -> _permissionStates.value.copy(shizuku = granted)
                PermissionType.OVERLAY -> _permissionStates.value.copy(overlay = granted)
                PermissionType.KEYBOARD -> _permissionStates.value.copy(keyboard = granted)
                PermissionType.BATTERY -> _permissionStates.value.copy(battery = granted)
                PermissionType.ACCESSIBILITY -> _permissionStates.value.copy(accessibility = granted)
            }
    }

    /**
     * Updates all permission states at once.
     *
     * Use this method when initializing or refreshing all permission states.
     *
     * @param states The new permission states
     */
    fun updateAllPermissionStates(states: PermissionStates) {
        Logger.d(TAG, "updateAllPermissionStates: $states")
        _permissionStates.value = states
    }

    /**
     * Sets the Shizuku service connection state.
     *
     * @param connected Whether the service is connected
     */
    fun setServiceConnected(connected: Boolean) {
        Logger.d(TAG, "setServiceConnected: $connected")
        _isServiceConnected.value = connected
        // Also update Shizuku permission state
        updatePermissionState(PermissionType.SHIZUKU, connected)
    }

    // endregion

    /**
     * Updates the task input availability based on whether text or images are present.
     *
     * @param hasText Whether the task input field has text
     * @param hasAttachments Whether the composer has selected images
     *
     */
    fun updateTaskInput(hasText: Boolean, hasAttachments: Boolean = _uiState.value.hasAttachments) {
        _uiState.value = _uiState.value.copy(
            hasAttachments = hasAttachments,
            canStartTask = calculateCanStartTask(hasTaskText = hasText, hasAttachments = hasAttachments),
        )
    }

    /**
     * Calculates whether a new task can be started based on current state.
     *
     * @param status Current Shizuku status
     * @param hasOverlayPermission Whether overlay permission is granted
     * @param hasTaskText Whether task input has text
     * @param hasAttachments Whether image attachments are selected
     * @param isRunning Whether a task is currently running
     * @return true if a new task can be started, false otherwise
     */
    private fun calculateCanStartTask(
        status: ShizukuStatus = _uiState.value.shizukuStatus,
        hasOverlayPermission: Boolean = _uiState.value.hasOverlayPermission,
        hasTaskText: Boolean = true,
        hasAttachments: Boolean = _uiState.value.hasAttachments,
        isRunning: Boolean = _uiState.value.isTaskRunning,
    ): Boolean = status == ShizukuStatus.CONNECTED &&
        hasOverlayPermission &&
        (hasTaskText || hasAttachments) &&
        !isRunning &&
        TaskExecutionManager.canStartTask()

    /**
     * Starts a new task with the given description.
     *
     * @param taskDescription The description of the task to execute
     * @param images Selected image attachments in display order
     *
     */
    fun startTask(taskDescription: String, images: List<com.kevinluo.autoglm.core.ChatImage> = emptyList()): Boolean {
        val alreadyRunning = TaskExecutionManager.isTaskRunning()
        if (!TaskExecutionManager.sendMessage(taskDescription, images)) return false
        if (!alreadyRunning) {
            logBuilder.clear()
            _outputLog.value = ""
        }
        return true
    }

    fun startNewConversation(taskDescription: String, images: List<com.kevinluo.autoglm.core.ChatImage> = emptyList()): Boolean {
        if (!TaskExecutionManager.startNewConversation(taskDescription, images)) return false
        logBuilder.clear()
        _outputLog.value = ""
        return true
    }

    /**
     * Cancels the currently running task.
     *
     */
    fun cancelTask() {
        Logger.d(TAG, "Cancelling task")
        TaskExecutionManager.cancelTask()
        appendLog("Task cancelled by user")
    }

    /**
     * Pauses the currently running task.
     */
    fun pauseTask() {
        Logger.d(TAG, "Pausing task")
        val paused = TaskExecutionManager.pauseTask()
        if (paused) {
            appendLog("Task paused by user")
        } else {
            Logger.w(TAG, "Failed to pause task - not running")
        }
    }

    /**
     * Resumes the paused task.
     */
    fun resumeTask() {
        Logger.d(TAG, "Resuming task")
        val resumed = TaskExecutionManager.resumeTask()
        if (resumed) {
            appendLog("Task resumed by user")
        } else {
            Logger.w(TAG, "Failed to resume task - not paused")
        }
    }

    /**
     * Resets the agent state before starting a new task.
     */
    fun resetAgent() {
        Logger.d(TAG, "Resetting agent")
        conversationDrafts.invalidateImports(TaskExecutionManager.taskState.value.conversationId)
        conversationDrafts.invalidateImports(null)
        TaskExecutionManager.resetTask()
        logBuilder.clear()
        _outputLog.value = ""
    }

    /**
     * Appends a timestamped message to the output log.
     *
     * @param message The message to append
     */
    private fun appendLog(message: String) {
        val timestamp =
            java.text
                .SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
        logBuilder.append("[$timestamp] $message\n")
        _outputLog.value = logBuilder.toString()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
