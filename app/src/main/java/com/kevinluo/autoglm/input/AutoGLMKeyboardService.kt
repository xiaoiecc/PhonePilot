package com.kevinluo.autoglm.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Base64
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.widget.ImageButton
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Built-in Input Method Service for AutoGLM text input.
 *
 * This IME provides a minimal keyboard interface that receives text input commands
 * via broadcasts. It provides text input functionality within the AutoGLM application.
 *
 * Features:
 * - Receives text via Base64-encoded broadcasts
 * - Supports all Unicode characters including Chinese, emoji, etc.
 * - Provides clear text functionality
 * - Minimal UI footprint - shows only a status indicator
 *
 * Broadcast Actions:
 * - [ACTION_INPUT_TEXT]: Input text (Base64 encoded in "msg" extra)
 * - [ACTION_INPUT_B64]: Input text (Base64 encoded)
 * - [ACTION_CLEAR_TEXT]: Clear current input field
 * - [ACTION_INPUT_CHARS]: Input text directly without encoding
 *
 * Usage:
 * 1. Enable the keyboard in system settings
 * 2. Switch to AutoGLM Keyboard when text input is needed
 * 3. Send broadcasts to input text programmatically
 *
 */
class AutoGLMKeyboardService : InputMethodService() {
    private val inputScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var editorToken: KeyboardEditorToken? = null
    private var publishedConnection: InputConnection? = null
    /**
     * BroadcastReceiver for handling text input commands.
     *
     * Listens for input and clear text broadcasts and processes them
     * through the InputConnection.
     */
    private val inputReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Logger.d(TAG, "Received broadcast: ${intent.action}")

                val pending = goAsync()
                pending.setResultCode(android.app.Activity.RESULT_CANCELED)
                inputScope.launch {
                    try {
                        val result = handleLegacyCommand(intent)
                        pending.setResultCode(if (result.success) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Logger.w(TAG, "Keyboard broadcast request failed")
                    } finally { pending.finish() }
                }
            }
        }

    /**
     * Flag indicating whether the receiver is currently registered.
     */
    private var isReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        Logger.i(TAG, "AutoGLMKeyboardService created")
        // Register receiver immediately when service is created
        registerInputReceiver()
    }

    override fun onDestroy() {
        Logger.i(TAG, "AutoGLMKeyboardService destroyed")
        releaseEditor()
        inputScope.cancel()
        unregisterInputReceiver()
        super.onDestroy()
    }

    private var switchButton: ImageButton? = null

    /**
     * Called when the keyboard view is created.
     *
     * Creates a minimal view showing the keyboard status. The actual text input
     * is handled via broadcasts, so no interactive keyboard UI is needed.
     *
     * @return The keyboard view to display
     */
    override fun onCreateInputView(): View {
        Logger.d(TAG, "onCreateInputView called")

        // Register receiver when input view is created
        registerInputReceiver()

        // Create a minimal status view
        val view = layoutInflater.inflate(R.layout.keyboard_autoglm, null)

        // Setup switch keyboard button
        switchButton =
            view.findViewById<ImageButton>(R.id.btn_switch_keyboard)?.apply {
                setOnClickListener { switchToNextKeyboard() }
            }

        // Update button visibility based on system UI
        updateSwitchButtonVisibility()

        return view
    }

    /**
     * Updates the visibility of the switch keyboard button.
     *
     * Only shows the button if:
     * 1. System doesn't show its own switch button (shouldOfferSwitchingToNextInputMethod)
     * 2. There are multiple input methods enabled
     *
     * Note: shouldOfferSwitchingToNextInputMethod requires API 28+.
     * On older devices, we always show the switch button.
     */
    private fun updateSwitchButtonVisibility() {
        val shouldShow =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                !shouldOfferSwitchingToNextInputMethod()
            } else {
                // On API < 28, always show the switch button
                true
            }
        Logger.d(TAG, "Switch button visibility: shouldShow=$shouldShow")
        switchButton?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    /**
     * Switches to the next input method (keyboard).
     *
     * This allows users to quickly switch to another keyboard without
     * going through system settings.
     */
    private fun switchToNextKeyboard() {
        Logger.d(TAG, "Switching to next keyboard")
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // For Android 9+, use switchToNextInputMethod
                switchToNextInputMethod(false)
            } else {
                // For older versions, show input method picker
                @Suppress("DEPRECATION")
                imm.switchToNextInputMethod(window?.window?.attributes?.token, false)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to switch keyboard", e)
            // Fallback: show input method picker dialog
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (e2: Exception) {
                Logger.e(TAG, "Failed to show input method picker", e2)
            }
        }
    }

    /**
     * Called when the keyboard starts input on a new editor.
     *
     * @param editorInfo Information about the text field
     * @param restarting Whether this is a restart of input on the same editor
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Logger.d(TAG, "onStartInput: restarting=$restarting, editorType=${attribute?.inputType}")
        publishEditor(forceNew = true)
    }

    /**
     * Called when the keyboard view is shown.
     *
     * Ensures the broadcast receiver is registered when the keyboard becomes visible.
     *
     * @param info Information about the text field
     * @param restarting Whether this is a restart
     */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Logger.d(TAG, "onStartInputView: restarting=$restarting")
        publishEditor(forceNew = false)

        // Ensure receiver is registered
        registerInputReceiver()

        // Update switch button visibility (system state may have changed)
        updateSwitchButtonVisibility()
    }

    /**
     * Called when the keyboard view is hidden.
     *
     * @param finishingInput Whether input is finishing
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Logger.d(TAG, "onFinishInputView: finishingInput=$finishingInput")
        if (finishingInput) releaseEditor()
    }

    /**
     * Called when input is finished.
     */
    override fun onFinishInput() {
        releaseEditor()
        super.onFinishInput()
        Logger.d(TAG, "onFinishInput")
    }

    /**
     * Registers the broadcast receiver for input commands.
     *
     * Uses appropriate registration method based on Android version.
     */
    private fun registerInputReceiver() {
        if (isReceiverRegistered) {
            Logger.d(TAG, "Receiver already registered")
            return
        }

        val filter =
            IntentFilter().apply {
                addAction(ACTION_INPUT_TEXT)
                addAction(ACTION_INPUT_B64)
                addAction(ACTION_CLEAR_TEXT)
                addAction(ACTION_INPUT_CHARS)
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(inputReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(inputReceiver, filter)
            }
            isReceiverRegistered = true
            Logger.i(TAG, "Input receiver registered")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to register input receiver", e)
        }
    }

    /**
     * Unregisters the broadcast receiver.
     */
    private fun unregisterInputReceiver() {
        if (!isReceiverRegistered) {
            return
        }

        try {
            unregisterReceiver(inputReceiver)
            isReceiverRegistered = false
            Logger.i(TAG, "Input receiver unregistered")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to unregister input receiver", e)
        }
    }

    private fun publishEditor(forceNew: Boolean) {
        val connection = currentInputConnection
        if (!forceNew && connection === publishedConnection && editorToken != null) return
        releaseEditor()
        if (connection != null) {
            publishedConnection = connection
            editorToken = KeyboardInputGateway.gateway.activate(this, InputConnectionEditor(connection), currentInputEditorInfo?.packageName)
        }
    }

    private fun releaseEditor() {
        KeyboardInputGateway.gateway.deactivate(this, editorToken)
        editorToken = null
        publishedConnection = null
    }

    /** Legacy broadcasts and in-app typing share exactly the same validated edit sequence. */
    private suspend fun handleLegacyCommand(intent: Intent): InputResult {
        val token = KeyboardInputGateway.gateway.currentToken()
            ?: return InputResult.failure("没有活动输入框")
        val text = when (intent.action) {
            ACTION_CLEAR_TEXT -> ""
            ACTION_INPUT_CHARS -> intent.getStringExtra(EXTRA_MSG)
                ?: return InputResult.failure("缺少输入文字")
            ACTION_INPUT_TEXT, ACTION_INPUT_B64 -> {
                val encoded = intent.getStringExtra(EXTRA_MSG) ?: return InputResult.failure("缺少输入文字")
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }
            else -> return InputResult.failure("未知输入请求")
        }
        return KeyboardInputGateway.gateway.input(token, text, replace = intent.action == ACTION_CLEAR_TEXT)
    }
    companion object {
        private const val TAG = "AutoGLMKeyboard"

        /**
         * Broadcast action for Base64-encoded text input.
         */
        const val ACTION_INPUT_B64 = "ADB_INPUT_B64"

        /**
         * Broadcast action for Base64-encoded text input (AutoGLM specific).
         */
        const val ACTION_INPUT_TEXT = "AUTOGLM_INPUT_TEXT"

        /**
         * Broadcast action for clearing text.
         */
        const val ACTION_CLEAR_TEXT = "ADB_CLEAR_TEXT"

        /**
         * Broadcast action for direct text input without encoding.
         */
        const val ACTION_INPUT_CHARS = "AUTOGLM_INPUT_CHARS"

        /**
         * Extra key for the text message in broadcasts.
         */
        const val EXTRA_MSG = "msg"
    }
}
