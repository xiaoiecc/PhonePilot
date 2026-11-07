package com.kevinluo.autoglm.home

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.core.InteractionKind
import com.kevinluo.autoglm.core.InteractionReply
import com.kevinluo.autoglm.core.PendingInteraction
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.settings.SettingsManager
import com.kevinluo.autoglm.settings.TaskTemplate
import com.kevinluo.autoglm.task.TaskExecutionManager
import com.kevinluo.autoglm.task.isActive
import com.kevinluo.autoglm.ui.FloatingWindowStateManager
import com.kevinluo.autoglm.ui.MainUiState
import com.kevinluo.autoglm.ui.MainViewModel
import com.kevinluo.autoglm.ui.ShizukuStatus
import com.kevinluo.autoglm.ui.TaskStatus
import com.kevinluo.autoglm.util.Logger
import com.kevinluo.autoglm.util.showWithPrimaryButtons
import com.kevinluo.autoglm.voice.VoiceError
import com.kevinluo.autoglm.voice.VoiceInputManager
import com.kevinluo.autoglm.voice.VoiceRecognitionResult
import com.kevinluo.autoglm.voice.VoiceRecordingDialog
import kotlinx.coroutines.launch

/**
 * Conversation screen with a persistent composer and independent execution controls.
 */
class TaskFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var settingsManager: SettingsManager
    private var voiceInputManager: VoiceInputManager? = null

    // Task Input Views
    private lateinit var taskInput: TextInputEditText
    private lateinit var btnVoiceInput: ImageButton
    private lateinit var btnSelectTemplate: ImageButton
    private lateinit var btnStartTask: MaterialButton
    private lateinit var btnNewConversation: MaterialButton
    private lateinit var chatMessages: RecyclerView
    private lateinit var chatEmptyState: View
    private lateinit var chatStatus: TextView
    private lateinit var chatPause: MaterialButton
    private lateinit var chatStop: MaterialButton
    private var chatAdapter: ChatMessageAdapter? = null
    private var changingComposer = false
    private var restoringChatPosition = false
    private var displayedConversationId: String? = null
    private var sessionPicker: ChatSessionPicker? = null
    private var interactionDialog: AlertDialog? = null
    private var displayedInteraction: PendingInteraction? = null

    // Floating Window Button
    private lateinit var btnFloatingWindow: ImageButton

    // Permission request launcher
    private val audioPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                showVoiceInputDialog()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.voice_permission_denied,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Logger.d(TAG, "TaskFragment onCreateView")
        return inflater.inflate(R.layout.fragment_task, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.d(TAG, "TaskFragment onViewCreated")

        settingsManager = SettingsManager.getInstance(requireContext())
        restoringChatPosition = savedInstanceState != null

        initViews(view)
        setupListeners()
        observeViewModel()
    }

    override fun onPause() {
        dismissInteractionDialog()
        displayedInteraction = null
        super.onPause()
    }

    override fun onDestroyView() {
        dismissInteractionDialog()
        displayedInteraction = null
        sessionPicker?.dismiss()
        sessionPicker = null
        viewModel.saveDraft(displayedConversationId, taskInput.text?.toString().orEmpty())
        chatMessages.adapter = null
        chatAdapter = null
        voiceInputManager?.release()
        voiceInputManager = null
        super.onDestroyView()
    }

    /**
     * Initializes all view references.
     */
    private fun initViews(view: View) {
        // Task Input Views
        taskInput = view.findViewById(R.id.taskInput)
        displayedConversationId = TaskExecutionManager.taskState.value.conversationId
        taskInput.setText(viewModel.draftFor(displayedConversationId))
        btnVoiceInput = view.findViewById(R.id.btnVoiceInput)
        btnSelectTemplate = view.findViewById(R.id.btnSelectTemplate)
        btnStartTask = view.findViewById(R.id.btnStartTask)
        btnNewConversation = view.findViewById(R.id.btnNewConversation)
        chatMessages = view.findViewById(R.id.chatMessages)
        chatEmptyState = view.findViewById(R.id.chatEmptyState)
        chatStatus = view.findViewById(R.id.chatStatus)
        chatPause = view.findViewById(R.id.chatPause)
        chatStop = view.findViewById(R.id.chatStop)
        chatMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        chatAdapter = ChatMessageAdapter().also { chatMessages.adapter = it }

        // Floating Window Button
        btnFloatingWindow = view.findViewById(R.id.btnFloatingWindow)
    }

    /**
     * Sets up click listeners and text change listeners.
     */
    private fun setupListeners() {
        requireView().findViewById<ImageButton>(R.id.btnConversations).setOnClickListener {
            sessionPicker?.dismiss()
            sessionPicker = ChatSessionPicker(requireContext(), viewLifecycleOwner.lifecycleScope,
                HistoryManager.getInstance(requireContext())) { conversationId ->
                if (conversationId == TaskExecutionManager.taskState.value.conversationId) true
                else TaskExecutionManager.loadConversation(conversationId)
            }.also { it.show() }
        }
        // Task input text change listener
        taskInput.doAfterTextChanged { text ->
            if (changingComposer) return@doAfterTextChanged
            viewModel.updateTaskInput(text?.isNotBlank() == true)
            if (text?.isNotBlank() == true && taskInput.hasFocus()) {
                TaskExecutionManager.pauseForDraft()
            } else if (text.isNullOrBlank()) {
                TaskExecutionManager.onEditorBlur(false)
            }
            updateUiState(viewModel.uiState.value)
        }
        taskInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) TaskExecutionManager.onEditorBlur(taskInput.text?.isNotBlank() == true)
        }

        // Voice input button
        btnVoiceInput.setOnClickListener {
            startVoiceInput()
        }

        // Template selection button
        btnSelectTemplate.setOnClickListener {
            showTemplateSelector()
        }

        // Start task button
        btnStartTask.setOnClickListener {
            startTask()
        }
        btnNewConversation.setOnClickListener {
            if (TaskExecutionManager.isTaskRunning()) {
                Toast.makeText(requireContext(), R.string.chat_stop_before_new, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.resetAgent()
                // Starting a blank conversation never discards an unsent draft.
                taskInput.requestFocus()
                Toast.makeText(requireContext(), R.string.chat_new_ready, Toast.LENGTH_SHORT).show()
            }
        }
        chatPause.setOnClickListener {
            if (viewModel.uiState.value.taskStatus == TaskStatus.PAUSED) viewModel.resumeTask()
            else viewModel.pauseTask()
        }
        chatStop.setOnClickListener { viewModel.cancelTask() }

        // Floating window button
        btnFloatingWindow.setOnClickListener {
            toggleFloatingWindow()
        }
    }

    /**
     * Observes ViewModel state and updates UI accordingly.
     *
     * Observes both the main UI state and permission states for cross-Fragment
     * synchronization. When permissions change in SettingsFragment, this Fragment
     * will automatically update its UI.
     *
     * _Requirements: 5.2, 5.3_
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Never leave an application dialog over the phone being controlled.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    TaskExecutionManager.pendingInteraction.collect(::renderPendingInteraction)
                } finally {
                    dismissInteractionDialog()
                    displayedInteraction = null
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateUiState(state)
                    }
                }
                launch {
                    TaskExecutionManager.messages.collect { messages ->
                        val adapter = chatAdapter ?: return@collect
                        val conversationId = TaskExecutionManager.taskState.value.conversationId
                        val switched = displayedConversationId != conversationId
                        if (switched) {
                            viewModel.saveDraft(displayedConversationId, taskInput.text?.toString().orEmpty())
                            changingComposer = true
                            try { taskInput.setText(viewModel.draftFor(conversationId)) }
                            finally { changingComposer = false }
                            displayedConversationId = conversationId
                            viewModel.updateTaskInput(!taskInput.text.isNullOrBlank())
                        }
                        val followLatest = switched || (!restoringChatPosition &&
                            (adapter.itemCount == 0 || !chatMessages.canScrollVertically(1))
                        )
                        adapter.submitMessages(messages)
                        restoringChatPosition = false
                        chatEmptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                        if (followLatest && messages.isNotEmpty()) {
                            chatMessages.scrollToPosition(messages.lastIndex)
                        }
                    }
                }

                // Observe permission states for cross-Fragment synchronization
                // When permissions change in SettingsFragment, update UI here
                launch {
                    viewModel.permissionStates.collect { permissionStates ->
                        onPermissionStatesChanged(permissionStates)
                    }
                }
            }
        }
    }

    private fun dismissInteractionDialog() {
        interactionDialog?.let { dialog ->
            // Lifecycle/coordinator dismissal is presentation cleanup, never a reply.
            dialog.setOnCancelListener(null)
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
        interactionDialog = null
    }

    private fun renderPendingInteraction(pending: PendingInteraction?) {
        if (displayedInteraction?.runId == pending?.runId && displayedInteraction?.id == pending?.id) return
        dismissInteractionDialog()
        displayedInteraction = pending
        if (pending == null || pending.kind == InteractionKind.TEXT) return
        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        fun reply(value: InteractionReply) {
            TaskExecutionManager.replyInteraction(pending.runId, pending.id, value)
        }
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(when (pending.kind) {
                InteractionKind.CONFIRM -> R.string.dialog_confirm_title
                InteractionKind.TAKEOVER -> R.string.takeover_title
                else -> R.string.dialog_select_option
            })
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> reply(InteractionReply.Cancelled) }
        if (pending.kind == InteractionKind.CHOICE) {
            // AlertDialog's message hides its item list, so keep the prompt as the title.
            builder.setTitle(pending.prompt)
                .setItems(pending.options.toTypedArray()) { _, index -> reply(InteractionReply.Choice(index)) }
        } else {
            builder.setMessage(pending.prompt)
                .setPositiveButton(if (pending.kind == InteractionKind.TAKEOVER) {
                    R.string.takeover_completed
                } else R.string.dialog_confirm) { _, _ -> reply(InteractionReply.Accepted(true)) }
        }
        interactionDialog = builder.showWithPrimaryButtons().also { dialog ->
            dialog.setOnCancelListener { reply(InteractionReply.Cancelled) }
            dialog.setOnDismissListener {
                if (interactionDialog === dialog) interactionDialog = null
            }
        }
    }

    /**
     * Handles permission state changes from cross-Fragment synchronization.
     *
     * When permissions are granted or revoked in SettingsFragment, this method
     * updates the TaskFragment UI accordingly.
     *
     * @param permissionStates The updated permission states
     *
     * _Requirements: 5.2, 5.3_
     */
    private fun onPermissionStatesChanged(permissionStates: com.kevinluo.autoglm.ui.PermissionStates) {
        Logger.d(TAG, "Permission states changed: $permissionStates")

        // The start button state is already managed through uiState.canStartTask
        // which is updated when permissions change. However, we can add additional
        // UI feedback here if needed.

        // Update floating window button state based on overlay permission
        btnFloatingWindow.isEnabled = permissionStates.overlay

        // If Shizuku is not connected, we could show a hint or disable certain features
        if (!permissionStates.shizuku) {
            // The start button will already be disabled through canStartTask
            // Additional UI hints could be added here if needed
        }
    }

    /**
     * Updates UI based on the current state.
     */
    private fun updateUiState(state: MainUiState) {
        val active = state.taskStatus.isActive
        btnStartTask.isEnabled = !taskInput.text.isNullOrBlank() &&
            (state.canStartTask || state.canContinueConversation || active) && state.taskStatus != TaskStatus.CANCELLING
        btnStartTask.setText(R.string.send_message)
        btnNewConversation.isEnabled = !active && !TaskExecutionManager.isTaskRunning()
        chatPause.visibility = if (state.taskStatus == TaskStatus.RUNNING ||
            state.taskStatus == TaskStatus.PAUSED) View.VISIBLE else View.GONE
        chatPause.setText(if (state.taskStatus == TaskStatus.PAUSED) R.string.resume_task else R.string.pause_task)
        chatStop.visibility = if (active) View.VISIBLE else View.GONE
        chatStatus.text = when (state.taskStatus) {
            TaskStatus.RUNNING -> getString(R.string.chat_running, state.stepNumber)
            TaskStatus.PAUSED -> getString(R.string.chat_paused)
            TaskStatus.CANCELLING -> getString(R.string.task_status_cancelling)
            TaskStatus.WAITING_FOR_USER, TaskStatus.WAITING_CONFIRMATION, TaskStatus.WAITING_TAKEOVER ->
                getString(R.string.chat_waiting)
            TaskStatus.COMPLETED -> getString(R.string.chat_completed)
            TaskStatus.FAILED -> getString(R.string.chat_failed)
            else -> getString(if (state.canContinueConversation) R.string.chat_continuing else R.string.chat_ready)
        }
    }

    /**
     * Starts a new task with the current input.
     */
    private fun startTask() {
        val taskDescription = taskInput.text?.toString()?.trim()

        if (taskDescription.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.toast_task_empty, Toast.LENGTH_SHORT).show()
            return
        }

        // Capture the displayed question identity; a late send must not answer a newer one.
        displayedInteraction?.takeIf { it.kind == InteractionKind.TEXT }?.let { pending ->
            onSubmissionResult(TaskExecutionManager.replyInteraction(
                pending.runId, pending.id, InteractionReply.Text(taskDescription),
            ))
            return
        }

        val state = viewModel.uiState.value

        // Check Shizuku connection
        if (state.shizukuStatus != ShizukuStatus.CONNECTED) {
            Toast.makeText(
                requireContext(),
                R.string.toast_shizuku_not_running,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        // Check overlay permission
        if (!state.hasOverlayPermission) {
            Toast.makeText(
                requireContext(),
                R.string.toast_overlay_permission_required,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        onSubmissionResult(viewModel.startTask(taskDescription))
    }

    private fun onSubmissionResult(accepted: Boolean) {
        if (accepted) {
            // Acceptance is synchronous; a later result must never clear a newer draft.
            changingComposer = true
            try {
                taskInput.text?.clear()
            } finally {
                changingComposer = false
            }
            viewModel.updateTaskInput(false)
            taskInput.clearFocus()
            ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(taskInput.windowToken, 0)
            updateUiState(viewModel.uiState.value)
        } else {
            Toast.makeText(requireContext(), R.string.chat_submission_rejected, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Starts voice input for task description.
     */
    private fun startVoiceInput() {
        // Check audio permission
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        // Initialize voice input manager if needed
        if (voiceInputManager == null) {
            voiceInputManager = VoiceInputManager(requireContext())
        }

        // Check if model is ready
        if (!voiceInputManager!!.isModelReady()) {
            Toast.makeText(
                requireContext(),
                R.string.voice_model_required,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        showVoiceInputDialog()
    }

    /**
     * Shows the voice recording dialog.
     */
    private fun showVoiceInputDialog() {
        val manager = voiceInputManager ?: return

        VoiceRecordingDialog(
            context = requireContext(),
            voiceInputManager = manager,
            onResult = { result: VoiceRecognitionResult ->
                handleVoiceResult(result)
            },
            onError = { error: VoiceError ->
                handleVoiceError(error)
            },
        ).show()
    }

    /**
     * Handles voice recognition result.
     */
    private fun handleVoiceResult(result: VoiceRecognitionResult) {
        if (result.text.isNotBlank()) {
            taskInput.setText(result.text)
            taskInput.setSelection(result.text.length)
        }
    }

    /**
     * Handles voice recognition error.
     */
    private fun handleVoiceError(error: VoiceError) {
        val messageResId =
            when (error) {
                VoiceError.PermissionDenied -> R.string.voice_permission_denied
                VoiceError.ModelNotDownloaded -> R.string.voice_model_required
                VoiceError.ModelLoadFailed -> R.string.voice_model_load_failed
                VoiceError.RecordingFailed -> R.string.voice_recording_failed
                VoiceError.RecognitionFailed -> R.string.voice_recognition_failed
                VoiceError.NetworkError -> R.string.voice_network_error
                is VoiceError.Unknown -> R.string.voice_unknown_error
            }
        Toast.makeText(requireContext(), messageResId, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows the template selection dialog.
     */
    private fun showTemplateSelector() {
        val templates = settingsManager.getTaskTemplates()

        if (templates.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.settings_no_templates,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val templateNames = templates.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.task_select_template)
            .setItems(templateNames) { _, which ->
                val selectedTemplate = templates[which]
                applyTemplate(selectedTemplate)
            }
            .setNegativeButton(R.string.cancel, null)
            .showWithPrimaryButtons()
    }

    /**
     * Applies a template to the task input.
     */
    private fun applyTemplate(template: TaskTemplate) {
        taskInput.setText(template.description)
        taskInput.setSelection(template.description.length)
        Logger.d(TAG, "Applied template: ${template.name}")
    }

    /**
     * Toggles the floating window visibility.
     *
     * When enabling, minimizes the app first so the floating window becomes visible.
     */
    private fun toggleFloatingWindow() {
        val wasEnabled = FloatingWindowStateManager.isUserEnabled()
        FloatingWindowStateManager.toggleByUser(requireContext())

        // If enabling floating window, minimize app so it becomes visible
        if (!wasEnabled) {
            activity?.moveTaskToBack(true)
        }
    }

    companion object {
        private const val TAG = "TaskFragment"
    }
}
