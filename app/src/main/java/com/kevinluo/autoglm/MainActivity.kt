package com.kevinluo.autoglm

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kevinluo.autoglm.ui.MainUiEvent
import com.kevinluo.autoglm.ui.MainViewModel
import com.kevinluo.autoglm.ui.PermissionType
import com.kevinluo.autoglm.ui.ShizukuStatus
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Main Activity that hosts the bottom navigation and Fragment container.
 *
 * Responsible for:
 * - Setting up Navigation with BottomNavigationView
 * - Managing Shizuku service connection
 * - Coordinating cross-Fragment state via MainViewModel
 */
class MainActivity : BaseActivity() {
    private lateinit var navController: NavController
    private lateinit var bottomNav: BottomNavigationView
    private val viewModel: MainViewModel by viewModels()

    private val onRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Logger.i(TAG, "Shizuku permission granted")
                    Toast.makeText(this, R.string.toast_shizuku_permission_granted, Toast.LENGTH_SHORT).show()
                    ShizukuConnectionManager.refresh()
                } else {
                    Logger.w(TAG, "Shizuku permission denied")
                    Toast.makeText(this, R.string.toast_shizuku_permission_denied, Toast.LENGTH_SHORT).show()
                    viewModel.updateShizukuStatus(ShizukuStatus.NO_PERMISSION)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.d(TAG, "MainActivity created")

        setupNavigation()
        setupEdgeToEdge()
        setupShizuku()
        observeEvents()
        checkOverlayPermission()
        checkAccessibilityPermission()

        // Handle incoming Intent for backward compatibility
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Handles incoming Intents and routes to the appropriate Fragment.
     *
     * Supports backward compatibility with existing deep links and Intents
     * that previously targeted HistoryActivity or SettingsActivity.
     *
     * @param intent The incoming Intent to handle
     *
     * _Requirements: 6.5_
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val targetFragment = intent.getStringExtra(EXTRA_TARGET_FRAGMENT)

        Logger.d(TAG, "Handling intent: action=$action, targetFragment=$targetFragment")
        intent.getStringExtra("resume_conversation_id")?.takeIf { it.isNotBlank() }?.let { conversationId ->
            intent.removeExtra("resume_conversation_id")
            lifecycleScope.launch {
                if (com.kevinluo.autoglm.task.TaskExecutionManager.loadConversation(conversationId)) {
                    bottomNav.selectedItemId = R.id.taskFragment
                } else {
                    Toast.makeText(this@MainActivity, R.string.chat_session_open_failed, Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        // Determine target destination based on action or extra
        val destinationId =
            when {
                action == ACTION_NAVIGATE_SETTINGS -> R.id.settingsFragment
                action == ACTION_NAVIGATE_HISTORY -> R.id.historyFragment
                targetFragment == TARGET_SETTINGS -> R.id.settingsFragment
                targetFragment == TARGET_HISTORY -> R.id.historyFragment
                targetFragment == TARGET_TASK -> R.id.taskFragment
                else -> null
            }

        // Navigate to the target destination if specified
        destinationId?.let { destId ->
            // Post navigation to ensure NavController is ready
            bottomNav.post {
                try {
                    if (navController.currentDestination?.id != destId) {
                        // Route external entry points through the same tab-stack owner as taps.
                        bottomNav.selectedItemId = destId
                        Logger.d(TAG, "Navigated to destination: $destId")
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to navigate to destination: $destId", e)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
        checkAccessibilityPermission()
        ShizukuConnectionManager.refresh()
        com.kevinluo.autoglm.display.ScreenModeController.refresh(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.d(TAG, "MainActivity destroyed")

        Shizuku.removeRequestPermissionResultListener(onRequestPermissionResultListener)

    }

    /**
     * Sets up the Navigation component with BottomNavigationView.
     *
     * Configures state preservation to ensure Fragment state is maintained
     * when switching between tabs.
     *
     * _Requirements: 1.4, 5.1_
     */
    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        bottomNav = findViewById(R.id.bottom_nav)

        // Configure BottomNavigationView to save and restore Fragment state
        // This ensures that when switching tabs, the previous Fragment's state is preserved
        bottomNav.setupWithNavController(navController)

        // NavigationUI already owns selection, state saving, restoration and destination sync.
        // Replacing its listener corrupts tab restoration after an external settings intent.

        Logger.d(TAG, "Navigation setup complete with state preservation")
    }

    /**
     * Sets up edge-to-edge display with proper insets handling.
     *
     * Applies padding to the root layout for status bar and
     * to the bottom navigation for navigation bar.
     */
    private fun setupEdgeToEdge() {
        val rootLayout = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.rootLayout)
        val host = findViewById<View>(R.id.nav_host_fragment)
        var keyboardVisible = false
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right,
                bottom = if (keyboardVisible) ime.bottom else 0)
            bottomNav.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            host.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
                bottomMargin = if (keyboardVisible) 0 else
                    bottomNav.height.takeIf { it > 0 } ?: ((60 * resources.displayMetrics.density).toInt() + bars.bottom)
            }
            insets
        }
        bottomNav.doOnLayout {
            if (!keyboardVisible) host.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
                bottomMargin = bottomNav.height
            }
        }
        setupEdgeToEdgeInsets(bottomNav, applyTop = false, applyBottom = true)
        ViewCompat.requestApplyInsets(rootLayout)
    }

    /**
     * Sets up Shizuku listeners and checks initial state.
     */
    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(onRequestPermissionResultListener)
        ShizukuConnectionManager.initialize(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ShizukuConnectionManager.status.collect { viewModel.updateShizukuStatus(it) }
            }
        }
        checkShizukuPermission()
    }

    /** Permission prompts belong to visible UI; the process connection only observes permission. */
    private fun checkShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) return
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, R.string.toast_shizuku_version_low, Toast.LENGTH_SHORT).show()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuConnectionManager.refresh()
            } else if (!Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (error: Exception) {
            Logger.e(TAG, "Unable to check Shizuku permission", error)
            viewModel.updateShizukuStatus(ShizukuStatus.NOT_RUNNING)
        }
    }
    /**
     * Checks and updates overlay permission status.
     */
    private fun checkOverlayPermission() {
        val hasPermission = Settings.canDrawOverlays(this)
        viewModel.updateOverlayPermission(hasPermission)
    }

    private fun checkAccessibilityPermission() {
        viewModel.updatePermissionState(
            PermissionType.ACCESSIBILITY,
            com.kevinluo.autoglm.accessibility.AccessibilityPermission.isServiceReady(this),
        )
    }

    /**
     * Observes ViewModel events and handles them.
     */
    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    /**
     * Handles UI events from the ViewModel.
     */
    private fun handleEvent(event: MainUiEvent) {
        if (event is MainUiEvent.RunEvent) {
            val task = com.kevinluo.autoglm.task.TaskExecutionManager.taskState.value
            if (!com.kevinluo.autoglm.task.RunEventIdentity.accepts(
                event.runId, task.runId, requiresActive = event is MainUiEvent.MinimizeApp, active = task.isExecuting,
            )) return
        }
        when (event) {
            is MainUiEvent.ShowToast -> {
                Toast.makeText(this, event.messageResId, Toast.LENGTH_SHORT).show()
            }

            is MainUiEvent.ShowToastText -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }

            is MainUiEvent.TaskCompleted -> {
                Logger.i(TAG, "Task completed: ${event.message}")
            }

            is MainUiEvent.TaskFailed -> {
                Logger.w(TAG, "Task failed: ${event.error}")
            }

            is MainUiEvent.MinimizeApp -> {
                moveTaskToBack(true)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

        // Intent actions for backward compatibility
        const val ACTION_NAVIGATE_SETTINGS = "com.kevinluo.autoglm.NAVIGATE_SETTINGS"
        const val ACTION_NAVIGATE_HISTORY = "com.kevinluo.autoglm.NAVIGATE_HISTORY"

        // Extra key for specifying target fragment
        const val EXTRA_TARGET_FRAGMENT = "target_fragment"

        // Target fragment values
        const val TARGET_TASK = "task"
        const val TARGET_HISTORY = "history"
        const val TARGET_SETTINGS = "settings"
    }
}
