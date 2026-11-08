package com.kevinluo.autoglm.display

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import com.kevinluo.autoglm.device.CommandResultParcel
import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.device.DisplayResultParcel
import java.io.FileOutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Sole shell-side display resource. The run coordinator, not this class, owns task execution. */
internal class PrivilegedDisplaySession(private val execute: (Array<String>, Long, Int) -> CommandResultParcel) {
    private var run = ""
    private var generation = 0L
    private var nextGeneration = 0L
    private var background = false
    private var task: SystemTaskBridge.Task? = null
    private var bridge: SystemTaskBridge? = null
    private var manager: DisplayManager? = null
    private var virtual: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var images: HandlerThread? = null
    private val frameLock = Object()
    private var frame: Bitmap? = null
    private var frameAt = 0L
    private var width = 0
    private var height = 0
    private var density = 0

    @Synchronized fun attach(runId: String): DisplayResultParcel = receipt {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid run identity" }
        if (run == runId) return@receipt inspectOwned()
        check(run.isEmpty()) { "上一次显示会话尚未归还" }
        check(Build.VERSION.SDK_INT == 31) { "此设备版本的后台显示能力尚未验证" }
        initialize()
        val source = checkNotNull(bridge).tasks(0).firstOrNull { it.visible && eligible(it) }
            ?: error("请先打开需要操作的目标应用")
        run = runId; generation = ++nextGeneration; task = source; background = false
    }

    @Synchronized fun inspect(runId: String, expected: Long): DisplayResultParcel = receipt {
        requireLease(runId, expected); inspectOwned()
    }

    @Synchronized fun switch(runId: String, expected: Long, toBackground: Boolean): DisplayResultParcel = receipt {
        requireLease(runId, expected)
        reconcilePlacement()
        task = checkNotNull(bridge).settle(checkNotNull(task), onTop = !toBackground)
        inspectOwned()
        if (background == toBackground) {
            task = checkNotNull(bridge).settle(checkNotNull(task), onTop = !toBackground)
            generation = ++nextGeneration
            return@receipt
        }
        if (toBackground) createDisplay()
        val destination = if (toBackground) checkNotNull(virtual).display.displayId else 0
        synchronized(frameLock) { frame?.recycle(); frame = null; frameAt = 0 }
        try {
            // Running transitions show this exact task at the destination, including a reused virtual display.
            // Graceful close is the separate path that always returns tasks behind the main screen.
            task = checkNotNull(bridge).move(checkNotNull(task), destination, onTop = true)
            background = toBackground
        } finally {
            generation = ++nextGeneration
            reconcilePlacement()
        }
    }

    @Synchronized fun close(runId: String, expected: Long): DisplayResultParcel = receipt {
        requireLease(runId, expected)
        task?.let { tracked ->
            val system = checkNotNull(bridge)
            val actual = virtual?.display?.displayId?.let { system.find(it, tracked) } ?: system.find(0, tracked)
            if (actual != null) task = system.settle(actual, onTop = false)
        }
        virtual?.display?.displayId?.let { screen ->
            // All roots on this private display must be handed back before release.
            checkNotNull(bridge).tasks(screen).forEach { source -> task = checkNotNull(bridge).move(source, 0, onTop = false) }
            check(checkNotNull(bridge).tasks(screen).isEmpty()) { "后台仍有未归还任务，保留显示资源" }
            releaseDisplay()
        }
        background = false; run = ""; generation = ++nextGeneration; task = null
    }

    @Synchronized fun capture(runId: String, expected: Long, imageId: String): DisplayResultParcel {
        var path = ""
        return receipt {
            requireLease(runId, expected); inspectOwned()
            check(background) { "当前不是后台显示屏" }
            path = DisplayInputPolicy.screenshotPath(imageId)
            val deadline = SystemClock.elapsedRealtime() + 3500
            synchronized(frameLock) {
                while (frame == null && SystemClock.elapsedRealtime() < deadline) frameLock.wait(100)
                val image = checkNotNull(frame) { "后台画面尚未绘制，请重新观察" }
                // Static windows do not continuously repaint. Age alone does not invalidate their last frame.
                FileOutputStream(path).use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        }.let { r -> if (!r.success) r else DisplayResultParcel(r.success, r.message, r.runId, r.generation,
            r.displayId, r.taskId, r.packageName, r.width, r.height, r.density, r.background, path) }
    }

    @Synchronized fun input(runId: String, expected: Long, operation: Int, values: IntArray): CommandResultParcel = try {
        requireLease(runId, expected); inspectOwned()
        val screen = if (background) checkNotNull(virtual).display.displayId else 0
        val argv = DisplayInputPolicy.arguments(screen, background, operation, values)
        execute(argv, 10000, 16000)
    } catch (_: Exception) { CommandResultParcel(null, "", "目标显示会话已变化或操作不受支持", false, false) }

    @Synchronized fun launch(runId: String, expected: Long, component: String): CommandResultParcel = try {
        requireLease(runId, expected)
        val packageName = component.substringBefore('/')
        require(DeviceExecutor.isComponentName(packageName, component))
        val screen = if (background) checkNotNull(virtual).display.displayId else 0
        if (background) synchronized(frameLock) { frame?.recycle(); frame = null; frameAt = 0 }
        val result = execute(arrayOf("am", "start", "--display", "$screen", "-n", component), 10000, 16000)
        if (!result.toResult().succeeded) result else {
            val deadline = SystemClock.elapsedRealtime() + 3000
            var actual: SystemTaskBridge.Task? = null
            while (actual == null && SystemClock.elapsedRealtime() < deadline) {
                actual = checkNotNull(bridge).tasks(screen).firstOrNull { it.visible && it.packageName == packageName }
                if (actual == null) Thread.sleep(50)
            }
            if (actual != null) { task = actual; result }
            else CommandResultParcel(null, "", "启动请求已接受，但目标显示屏尚未验证到应用；请重新观察", false, false)
        }
    } catch (_: Exception) { CommandResultParcel(null, "", "应用启动显示会话无效", false, false) }

    private fun requireLease(runId: String, expected: Long) {
        check(run.isNotEmpty() && run == runId && generation == expected) { "显示会话已过期" }
    }
    @Synchronized fun shutdown(): Boolean = if (run.isEmpty()) true else close(run, generation).success
    private fun inspectOwned() {
        val previous = checkNotNull(task) { "没有目标应用任务" }
        val screen = if (background) checkNotNull(virtual).display.displayId else 0
        val system = checkNotNull(bridge)
        val roots = system.tasks(screen)
        system.find(screen, previous)?.let { retained ->
            check(roots.any { system.sameIdentity(retained, it) }) { "任务迁移未完成，请重试切换或停止" }
        }
        val current = roots.firstOrNull { eligible(it) && it.visible }
        check(current != null) { "目标应用不在预期显示屏，请暂停后恢复" }
        if (!background) check(system.sameIdentity(previous, current)) { "主屏应用已切换，请先恢复原任务" }
        task = current
    }
    private fun reconcilePlacement() {
        val target = task ?: return
        val system = bridge ?: return
        val secondary = virtual?.display?.displayId
        val onSecondary = secondary?.let { system.find(it, target) }
        val onPrimary = system.find(0, target)
        when {
            onSecondary != null -> { task = onSecondary; background = true }
            onPrimary != null -> { task = onPrimary; background = false }
            else -> error("目标任务落点不明确，必须恢复后再继续")
        }
    }
    private fun eligible(t: SystemTaskBridge.Task): Boolean = t.component != null &&
        t.packageName !in setOf("com.kevinluo.autoglm", "com.android.systemui", "com.huawei.android.launcher", "com.android.launcher3")

    private fun initialize() {
        if (manager != null) return
        val worker = HandlerThread("PhonePilot-display-frames").also { it.start(); images = it }
        val setup = FutureTask {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
            val system = at.getMethod("getSystemContext").invoke(thread) as Context
            val shell = object : ContextWrapper(system) {
                override fun getPackageName() = "com.android.shell"
                override fun getOpPackageName() = "com.android.shell"
                override fun getApplicationContext(): Context = this
                @RequiresApi(31)
                override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
            }
            val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }
            manager = constructor.newInstance(shell)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            checkNotNull(manager?.getDisplay(0)).getRealMetrics(metrics)
            width = metrics.widthPixels; height = metrics.heightPixels; density = metrics.densityDpi
            check(width in 320..4096 && height in 320..4096 && density in 72..1000) { "Invalid primary display geometry" }
            bridge = SystemTaskBridge()
        }
        Handler(worker.looper).post(setup)
        try { setup.get(10000, TimeUnit.MILLISECONDS) } catch (error: Exception) {
            worker.quitSafely(); images = null; manager = null; bridge = null; throw error
        }
    }
    private fun createDisplay() {
        if (virtual != null) return
        val source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        source.setOnImageAvailableListener({ available ->
            runCatching {
                available.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    val stride = plane.rowStride / plane.pixelStride
                    val padded = Bitmap.createBitmap(stride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val cropped = if (stride == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (cropped !== padded) padded.recycle()
                    synchronized(frameLock) { frame?.recycle(); frame = cropped; frameAt = SystemClock.elapsedRealtime(); frameLock.notifyAll() }
                }
            }
        }, Handler(checkNotNull(images).looper))
        try {
            // Resolve hidden framework flags by their actual names instead of bypassing the public SDK IntDef.
            // This resource is created only by the explicitly gated API-31 capability path.
            val flagNames = arrayOf("VIRTUAL_DISPLAY_FLAG_PUBLIC", "VIRTUAL_DISPLAY_FLAG_PRESENTATION",
                "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
                "VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT", "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")
            val flags = flagNames.fold(0) { bits, name -> bits or DisplayManager::class.java.getField(name).getInt(null) }
            check(flags == 459) { "后台显示屏标志与已验证能力档案不符" }
            virtual = checkNotNull(manager).createVirtualDisplay("PhonePilot background", width, height, density, source.surface, flags)
            check(virtual != null) { "创建后台显示屏失败" }
            reader = source
        } catch (error: Exception) { source.close(); throw error }
    }
    private fun releaseDisplay() {
        virtual?.release(); virtual = null; reader?.close(); reader = null
        synchronized(frameLock) { frame?.recycle(); frame = null; frameAt = 0 }
    }
    private fun receipt(block: () -> Unit): DisplayResultParcel = try { block(); result(true, "") }
        catch (error: Exception) {
            val reason = error.message?.takeIf { it.length <= 100 && '\n' !in it }
            result(false, reason ?: "显示操作失败，已保留任务供恢复")
        }
    private fun result(ok: Boolean, reason: String) = DisplayResultParcel(ok, reason, run, generation,
        if (background) virtual?.display?.displayId ?: -1 else 0, task?.id ?: -1, task?.packageName.orEmpty(),
        width, height, density, background)
}
