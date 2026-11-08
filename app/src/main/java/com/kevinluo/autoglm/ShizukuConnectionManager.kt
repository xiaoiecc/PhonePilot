package com.kevinluo.autoglm

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.kevinluo.autoglm.connection.ConnectionGenerations
import com.kevinluo.autoglm.ui.ShizukuStatus
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** The application, not an Activity, owns the privileged device connection. */
object ShizukuConnectionManager {
    private const val TAG = "ShizukuConnection"
    private const val BIND_TIMEOUT_MS = 10000L
    private val main = Handler(Looper.getMainLooper())
    private val retry = ServiceReconnectPolicy()
    private val generations = ConnectionGenerations()
    private var context: Context? = null
    private var connection: ServiceConnection? = null
    @Volatile private var binder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var retryTask: Runnable? = null
    private var bindTimeout: Runnable? = null
    private var stableConnectionTask: Runnable? = null
    private val mutableStatus = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = mutableStatus.asStateFlow()

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name))
            .daemon(false).processNameSuffix("service").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    }

    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener {
        val event = generations.capture()
        main.post {
            if (!generations.isCurrent(event) || runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return@post
            detach()
            mutableStatus.value = ShizukuStatus.NOT_RUNNING
        }
    }

    fun initialize(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        refresh()
    }

    /** Called on foreground/permission grant; automatic retries never request permission. */
    fun refresh() {
        val event = generations.supersedeEvents()
        com.kevinluo.autoglm.util.DiagnosticLog.event("Shizuku", "refresh.requested",
            "revision" to event.revision, "status" to mutableStatus.value,
            "binderAlive" to (binder?.isBinderAlive == true), "connectionPresent" to (connection != null))
        main.post {
            if (!generations.isCurrent(event)) {
                com.kevinluo.autoglm.util.DiagnosticLog.event("Shizuku", "refresh.stale", "revision" to event.revision)
                return@post
            }
            retry.reset()
            retryTask?.let(main::removeCallbacks)
            retryTask = null
            connect()
        }
    }

    private fun available(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun connect() {
        val application = context ?: return
        com.kevinluo.autoglm.util.DiagnosticLog.event("Shizuku", "connect.started",
            "status" to mutableStatus.value, "available" to available(),
            "binderAlive" to (binder?.isBinderAlive == true), "connectionPresent" to (connection != null))
        if (!available()) {
            detach()
            mutableStatus.value = if (Shizuku.pingBinder()) ShizukuStatus.NO_PERMISSION else ShizukuStatus.NOT_RUNNING
            com.kevinluo.autoglm.util.DiagnosticLog.event("Shizuku", "connect.unavailable", "status" to mutableStatus.value)
            return
        }
        if (binder?.isBinderAlive == true) {
            mutableStatus.value = ShizukuStatus.CONNECTED
            return
        }
        if (binder != null) detach()
        // A pending attempt owns its callback until connected or its deadline expires.
        if (connection != null) return
        val generation = generations.beginAttempt()
        com.kevinluo.autoglm.util.DiagnosticLog.event("Shizuku", "bind.started", "attemptId" to generation.id)
        val attempt = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val delivery = generations.beginBinding(generation, service) ?: return
                main.post {
                    if (!generations.isCurrent(delivery) || connection !== this) return@post
                    if (service == null || !service.isBinderAlive || !available()) {
                        disconnected(this, generation)
                        return@post
                    }
                    if (binder == service) return@post
                    try {
                        val recipient = IBinder.DeathRecipient {
                            main.post {
                                if (generations.isCurrent(delivery) && binder === service) disconnected(this, generation)
                            }
                        }
                        service.linkToDeath(recipient, 0)
                        val previousBinder = binder
                        val previousRecipient = deathRecipient
                        if (previousBinder != null && previousRecipient != null) {
                            runCatching { previousBinder.unlinkToDeath(previousRecipient, 0) }
                        }
                        binder = service
                        deathRecipient = recipient
                        generations.supersedeEvents()
                        bindTimeout?.let(main::removeCallbacks)
                        bindTimeout = null
                        ComponentManager.getInstance(application).onServiceConnected(IUserService.Stub.asInterface(service))
                        mutableStatus.value = ShizukuStatus.CONNECTED
                        retry.connected()
                        stableConnectionTask?.let(main::removeCallbacks)
                        stableConnectionTask = Runnable {
                            if (!generations.isCurrent(generation) || connection !== this || binder !== service) return@Runnable
                            stableConnectionTask = null
                            if (service.isBinderAlive) retry.resetIfStable()
                        }.also { main.postDelayed(it, ServiceReconnectPolicy.STABILITY_WINDOW_MS) }
                        Logger.i(TAG, "UserService connected")
                        com.kevinluo.autoglm.util.DiagnosticLog.event("Shizuku", "connected",
                            "attemptId" to generation.id, "bindingId" to delivery.id,
                            "binderAlive" to service.isBinderAlive, "status" to mutableStatus.value)
                    } catch (error: Exception) {
                        Logger.e(TAG, "UserService connection initialization failed", error)
                        disconnected(this, generation)
                    }
                }
            }

            private fun postDisconnected() {
                val observedBinder = binder
                val delivery = generations.currentBinding()
                main.post {
                    if ((delivery == null || generations.isCurrent(delivery)) && binder === observedBinder) disconnected(this, generation)
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) = postDisconnected()
            override fun onBindingDied(name: ComponentName?) = postDisconnected()
            override fun onNullBinding(name: ComponentName?) = postDisconnected()
        }
        connection = attempt
        mutableStatus.value = ShizukuStatus.CONNECTING
        bindTimeout = Runnable {
            if (binder == null) disconnected(attempt, generation)
        }.also { main.postDelayed(it, BIND_TIMEOUT_MS) }
        try {
            Shizuku.bindUserService(args, attempt)
        } catch (error: Exception) {
            Logger.e(TAG, "UserService bind failed", error)
            disconnected(attempt, generation)
        }
    }

    private fun disconnected(attempt: ServiceConnection, generation: ConnectionGenerations.Attempt) {
        if (!generations.isCurrent(generation) || connection !== attempt) {
            com.kevinluo.autoglm.util.DiagnosticLog.event("Shizuku", "disconnect.stale", "attemptId" to generation.id)
            return
        }
        detach()
        mutableStatus.value = ShizukuStatus.NOT_RUNNING
        com.kevinluo.autoglm.util.DiagnosticLog.event("Shizuku", "disconnected", "attemptId" to generation.id)
        if (!available()) {
            mutableStatus.value = if (Shizuku.pingBinder()) ShizukuStatus.NO_PERMISSION else ShizukuStatus.NOT_RUNNING
            return
        }
        val delay = retry.nextDelayMs() ?: return
        val event = generations.capture()
        com.kevinluo.autoglm.util.DiagnosticLog.event("Shizuku", "retry.scheduled",
            "attemptId" to generation.id, "revision" to event.revision, "delayMs" to delay)
        retryTask = Runnable {
            if (!generations.isCurrent(event)) return@Runnable
            retryTask = null
            connect()
        }.also { main.postDelayed(it, delay) }
    }

    private fun detach() {
        generations.invalidate()
        stableConnectionTask?.let(main::removeCallbacks)
        stableConnectionTask = null
        retry.disconnected()
        retryTask?.let(main::removeCallbacks)
        retryTask = null
        bindTimeout?.let(main::removeCallbacks)
        bindTimeout = null
        val oldConnection = connection
        connection = null
        val oldBinder = binder
        val recipient = deathRecipient
        binder = null
        deathRecipient = null
        if (oldBinder != null && recipient != null) runCatching { oldBinder.unlinkToDeath(recipient, 0) }
        if (oldConnection != null) runCatching {
            // Remove only this registration. Do not destroy a service used by the active app process.
            Shizuku.unbindUserService(args, oldConnection, false)
        }
        if (oldBinder != null) context?.let { ComponentManager.getInstance(it).onServiceDisconnected() }
    }
}

/** Bounded retries are independent from Activity recreation and never open permission prompts. */
internal class ServiceReconnectPolicy(private val clockMs: () -> Long = { System.nanoTime() / 1000000 }) {
    private var attempts = 0
    private var connectedAt: Long? = null
    fun reset() { attempts = 0 }
    fun connected() { connectedAt = clockMs() }
    fun disconnected() { connectedAt = null }
    fun resetIfStable(): Boolean {
        val started = connectedAt ?: return false
        if (clockMs() - started < STABILITY_WINDOW_MS) return false
        attempts = 0
        return true
    }
    fun nextDelayMs(): Long? = delays.getOrNull(attempts++)
    companion object {
        const val STABILITY_WINDOW_MS = 30000L
        private val delays = listOf(250L, 1000L, 3000L)
    }
}
