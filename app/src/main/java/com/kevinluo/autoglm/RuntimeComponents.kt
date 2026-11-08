package com.kevinluo.autoglm

import android.content.Context
import com.kevinluo.autoglm.agent.PhoneAgent
import com.kevinluo.autoglm.app.AppResolver
import com.kevinluo.autoglm.computer.protocol.ResponsesComputerClient
import com.kevinluo.autoglm.computer.runtime.AndroidComputerDriver
import com.kevinluo.autoglm.computer.runtime.AndroidToolExecutor
import com.kevinluo.autoglm.computer.runtime.FunctionToolBackend
import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.input.TextInputManager
import com.kevinluo.autoglm.model.ModelConfig
import com.kevinluo.autoglm.proxy.ProxyManager
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.settings.SettingsManager
import com.kevinluo.autoglm.task.TaskExecutionManager
import com.kevinluo.autoglm.ui.FloatingWindowService
import com.kevinluo.autoglm.util.HumanizedSwipeGenerator
import okhttp3.OkHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.kevinluo.autoglm.ui.FloatingWindowStateManager
import com.kevinluo.autoglm.accessibility.PhoneAccessibilityService
import com.kevinluo.autoglm.core.ExecutionMode
import com.kevinluo.autoglm.core.RunControl
import com.kevinluo.autoglm.display.DisplaySessionClient

/** Android composition root. Views never receive a device capability or an AgentRunner. */
class ComponentManager private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: ComponentManager? = null
        fun getInstance(context: Context): ComponentManager = instance ?: synchronized(this) {
            instance ?: ComponentManager(context.applicationContext).also { instance = it }
        }
        fun clearInstance() = synchronized(this) { instance?.cleanup(); instance = null }
    }

    val settingsManager by lazy { SettingsManager.getInstance(context) }
    val historyManager by lazy { HistoryManager.getInstance(context) }
    internal var userService: IUserService? = null
        private set
    private var deviceExecutorInternal: DeviceExecutor? = null
    private var textInputManagerInternal: TextInputManager? = null
    private var screenshotServiceInternal: ScreenshotService? = null
    private var phoneAgentInternal: PhoneAgent? = null
    private val appResolver by lazy { AppResolver(context.packageManager) }
    private val swipeGenerator by lazy { HumanizedSwipeGenerator() }
    val isServiceConnected get() = userService != null
    internal val phoneAgent get() = phoneAgentInternal
    internal val screenshotService get() = screenshotServiceInternal
    internal val deviceExecutor get() = deviceExecutorInternal

    fun onServiceConnected(service: IUserService) {
        if (userService?.asBinder() == service.asBinder() && phoneAgentInternal != null) return
        if (userService != null) cleanupServiceDependentComponents()
        userService = service
        deviceExecutorInternal = DeviceExecutor(service)
        textInputManagerInternal = TextInputManager(service, expectedTarget = {
            deviceExecutorInternal?.displays?.state?.takeIf { it.background }?.let { it.displayId to it.packageName }
        })
        screenshotServiceInternal = ScreenshotService(service, backgroundCapture = { imageId ->
            val displays = checkNotNull(deviceExecutorInternal).displays
            val target = displays.state
            if (!target.background) null else displays.captureBlocking(target.runId, target.generation, imageId)
        }, floatingWindowControllerProvider = {
            if (deviceExecutorInternal?.displays?.state?.background == true) null else FloatingWindowService.getInstance()
        })
        createRunner()
    }

    fun onServiceDisconnected() {
        cleanupServiceDependentComponents()
        userService = null
    }

    private fun cleanupServiceDependentComponents() {
        TaskExecutionManager.onRunnerUnavailable()
        phoneAgentInternal = null
        screenshotServiceInternal = null
        textInputManagerInternal = null
        deviceExecutorInternal = null
    }

    private fun createRunner() {
        val device = checkNotNull(deviceExecutorInternal)
        val input = checkNotNull(textInputManagerInternal)
        val screenshots = checkNotNull(screenshotServiceInternal)
        val config = settingsManager.getAgentConfig()
        // Primary-display controls cannot obscure or receive input from the private target display.
        val window = { if (device.displays.state.background) null else FloatingWindowService.getInstance() }
        phoneAgentInternal = PhoneAgent(
            tools = AndroidToolExecutor(AndroidComputerDriver(device, input, window),
                FunctionToolBackend(device, input, appResolver, swipeGenerator, window),
                  screenshots, historyManager, config.screenshotDelayMs, textEditor = input::editText, window = window,
                  beforeDeviceTools = { control ->
                      control.checkpoint()
                      if (!device.displays.state.background) {
                          control.emit(com.kevinluo.autoglm.core.RunProgress.DeviceToolsRequested)
                          awaitForegroundHandoff()
                      }
                      control.deviceOperation {
                          val target = device.displays.state
                          if (target.runId.isNotEmpty()) {
                              check(target.runId == control.runId) { "显示会话不属于当前任务" }
                              check(device.displays.refresh(target.runId, target.generation).success) { "目标应用已离开预期位置，尚未执行操作" }
                          }
                      }
                      control.checkpoint()
                  }),
            config = config, historyManager = historyManager,
            prepareComputerClient = { createComputerClient() },
            imageLoader = com.kevinluo.autoglm.history.ChatAttachmentStore.getInstance(context)::read,
        )
    }

    private suspend fun awaitForegroundHandoff() {
        check(withTimeoutOrNull(5000) {
            FloatingWindowStateManager.snapshot.first { !it.appInForeground }
        } != null) { "请将应用切到后台后重试；尚未操作手机" }
        delay(150)
    }

    /** Invoked only by the coordinator while its device-operation gate is held. */
    internal suspend fun switchExecutionPlacement(runId: String, mode: ExecutionMode) {
        awaitForegroundHandoff()
        val displays = checkNotNull(deviceExecutorInternal).displays
        val previous = displays.state
        val lease = if (previous.runId.isEmpty()) displays.attach(runId) else previous
        check(lease.success && lease.runId == runId) { lease.message.ifBlank { "目标应用无法迁移" } }
        try {
            val moved = displays.switchMode(runId, lease.generation, mode == ExecutionMode.BACKGROUND)
            check(moved.success && moved.background == (mode == ExecutionMode.BACKGROUND)) { moved.message.ifBlank { "切换未完成" } }
        } finally {
            // Even partial migration invalidates old node leases; no main-display fallback.
            PhoneAccessibilityService.instance?.selectDisplay(displays.state.displayId)
        }
    }

    internal suspend fun releaseExecutionPlacement() {
        val displays = deviceExecutorInternal?.displays ?: return
        val target = displays.state
        try {
            if (target.runId.isNotEmpty()) {
                val released = displays.close(target.runId, target.generation)
                check(released.success) { "应用归还未完成，已保留设备资源供恢复" }
            }
        } finally { PhoneAccessibilityService.instance?.selectDisplay(0) }
    }

    fun reinitializeAgent() {
        if (userService == null || TaskExecutionManager.isTaskRunning()) return
        createRunner()
    }

    suspend fun createComputerClient(config: ModelConfig = settingsManager.getModelConfig()): ResponsesComputerClient {
        val proxy = ProxyManager.getInstance(context)
        proxy.ensureStarted()
        return ResponsesComputerClient(config, proxy.configureClient(OkHttpClient.Builder()).build())
    }

    fun cleanup() { cleanupServiceDependentComponents(); userService = null }
    fun getStateSummary(): String = "Device connected: $isServiceConnected; runner available: ${phoneAgentInternal != null}"
}
