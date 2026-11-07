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
    private var userService: IUserService? = null
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
        textInputManagerInternal = TextInputManager(service)
        screenshotServiceInternal = ScreenshotService(service) { FloatingWindowService.getInstance() }
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
        val window = { FloatingWindowService.getInstance() }
        phoneAgentInternal = PhoneAgent(
            tools = AndroidToolExecutor(AndroidComputerDriver(device, input, window),
                FunctionToolBackend(device, input, appResolver, swipeGenerator, window),
                  screenshots, historyManager, config.screenshotDelayMs, textEditor = input::editText),
            config = config, historyManager = historyManager,
            prepareComputerClient = { createComputerClient() },
        )
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
