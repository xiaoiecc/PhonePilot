package com.kevinluo.autoglm.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kevinluo.autoglm.BaseActivity
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.MainActivity
import com.kevinluo.autoglm.home.ChatMessageAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity for displaying detailed task history with steps.
 *
 * Shows comprehensive information about a single task execution including:
 * - Task description and status
 * - All execution steps with thinking and actions
 * - Screenshots (original and annotated)
 * - Duration and timing information
 *
 * Features:
 * - Copy task prompt to clipboard
 * - Save task as image to gallery
 * - Share task as image
 * - Delete task
 *
 */
class HistoryDetailActivity : BaseActivity() {
    private lateinit var historyManager: HistoryManager
    private var taskId: String? = null
    private var conversationId: String? = null
    private var executionDurationMs: Long? = null
    private var task: TaskHistory? = null
    private var timeline: List<HistoryTimeline.Entry> = emptyList()

    private lateinit var contentRecyclerView: RecyclerView
    private lateinit var detailAdapter: HistoryDetailAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_detail)
        setupEdgeToEdgeInsets(R.id.rootLayout, applyTop = true, applyBottom = false)

        historyManager = HistoryManager.getInstance(this)
        taskId = intent.getStringExtra(EXTRA_TASK_ID)
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)

        Logger.d(TAG, "HistoryDetailActivity created for task: $taskId")
        setupViews()
        loadTask()
    }

    /**
     * Sets up all view references and click listeners.
     */
    private fun setupViews() {
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener {
            finish()
        }

        findViewById<ImageButton>(R.id.copyPromptBtn).setOnClickListener {
            copyPromptToClipboard()
        }

        findViewById<ImageButton>(R.id.saveImageBtn).setOnClickListener {
            saveAsImage()
        }

        findViewById<ImageButton>(R.id.shareBtn).setOnClickListener {
            shareAsImage()
        }

        findViewById<ImageButton>(R.id.deleteBtn).setOnClickListener {
            showDeleteDialog()
        }
        findViewById<MaterialButton>(R.id.viewConversationBtn).setOnClickListener { showConversation() }
        findViewById<MaterialButton>(R.id.resumeConversationBtn).setOnClickListener { resumeConversation() }

        // Setup RecyclerView with true recycling
        contentRecyclerView = findViewById(R.id.contentRecyclerView)
        setupEdgeToEdgeInsets(R.id.sessionDetailActions, applyTop = false, applyBottom = true)
        detailAdapter = HistoryDetailAdapter(historyManager, lifecycleScope)
        contentRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HistoryDetailActivity)
            adapter = detailAdapter
            setHasFixedSize(false)
            // Enable view caching for smoother scrolling
            setItemViewCacheSize(3)
        }
    }

    /**
     * Loads the task from history manager.
     */
    private fun loadTask() {
        lifecycleScope.launch {
            val id = conversationId
            if (id != null) {
                // Storage returns chronological runs; projections use newest-first index order
                // to preserve completion/cursor identity when two runs share a timestamp.
                val runs = historyManager.getConversationRuns(id).asReversed()
                task = ConversationHistory.detail(runs, id)
                timeline = HistoryTimeline.build(ConversationHistory.runs(runs, id))
                executionDurationMs = ConversationHistory.summaries(runs).singleOrNull()?.executionDurationMs
            } else {
                // Existing task deep links still display the requested historical run.
                task = taskId?.let { historyManager.getTask(it) }
                timeline = task?.let { HistoryTimeline.build(listOf(it)) }.orEmpty()
            }
            task?.let {
                Logger.d(TAG, "Loaded task with ${it.stepCount} steps")
                detailAdapter.setTask(it, executionDurationMs, timeline)
                findViewById<MaterialButton>(R.id.viewConversationBtn).isEnabled = true
                findViewById<MaterialButton>(R.id.resumeConversationBtn).isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detailAdapter.cleanup()
    }

    private fun resumeConversation() {
        val conversationId = task?.conversationId ?: return
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("resume_conversation_id", conversationId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }

    private fun showConversation() {
        val conversationId = task?.conversationId ?: return
        lifecycleScope.launch {
            val restored = historyManager.loadConversation(conversationId)
            if (restored == null) {
                Toast.makeText(this@HistoryDetailActivity, R.string.session_load_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val messages = RecyclerView(this@HistoryDetailActivity).apply {
                layoutManager = LinearLayoutManager(this@HistoryDetailActivity).apply { stackFromEnd = true }
                adapter = ChatMessageAdapter().apply { submitMessages(restored.messages) }
                val padding = (12 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.6f).toInt())
            }
            MaterialAlertDialogBuilder(this@HistoryDetailActivity)
                .setTitle(R.string.session_messages)
                .setView(messages)
                .setPositiveButton(R.string.session_continue) { _, _ -> resumeConversation() }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    /**
     * Shows a confirmation dialog for deleting the task.
     */
    private fun showDeleteDialog() {
        AlertDialog
            .Builder(this)
            .setMessage(R.string.history_delete_confirm)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                deleteTask()
            }.setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Copies the task prompt/description to clipboard.
     *
     */
    private fun copyPromptToClipboard() {
        val currentTask = task ?: return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AutoGLM Prompt", currentTask.taskDescription)
        clipboard.setPrimaryClip(clip)

        Logger.d(TAG, "Copied prompt to clipboard")
        Toast.makeText(this, R.string.history_prompt_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Deletes the current task from history.
     */
    private fun deleteTask() {
        lifecycleScope.launch {
            val groupId = conversationId
            if (groupId != null) {
                historyManager.deleteConversations(setOf(groupId))
            } else {
                // A legacy run deep link still represents only that individual record.
                val runId = taskId ?: return@launch
                historyManager.deleteTask(runId)
            }
            finish()
        }
    }

    /**
     * Formats duration in milliseconds to a human-readable string.
     *
     * @param ms Duration in milliseconds
     * @return Formatted duration string (e.g., "30秒", "2分15秒")
     */
    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 60 -> "${seconds}秒"
            seconds < 3600 -> "${seconds / 60}分${seconds % 60}秒"
            else -> "${seconds / 3600}时${(seconds % 3600) / 60}分"
        }
    }

    /**
     * Saves the task history as an image to gallery.
     *
     * Generates a visual representation of the task and saves it to the device's gallery.
     *
     */
    private fun saveAsImage() {
        val currentTask = task ?: return

        Logger.d(TAG, "Saving task as image")
        Toast.makeText(this, R.string.history_generating_image, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                var saved = true
                withContext(Dispatchers.Default) {
                    generateShareImages(currentTask, timeline.toList()) { bitmap ->
                        if (!withContext(Dispatchers.IO) { saveBitmapToGallery(bitmap) }) saved = false
                    }
                }

                if (saved) {
                    Logger.d(TAG, "Image saved to gallery")
                    Toast.makeText(this@HistoryDetailActivity, R.string.history_save_success, Toast.LENGTH_SHORT).show()
                } else {
                    Logger.e(TAG, "Failed to save image to gallery")
                    Toast.makeText(this@HistoryDetailActivity, R.string.history_save_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Error saving image", e)
                Toast.makeText(this@HistoryDetailActivity, R.string.history_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Shares the task history as an image.
     *
     * Generates a visual representation of the task and opens the system share sheet.
     *
     */
    private fun shareAsImage() {
        val currentTask = task ?: return

        Logger.d(TAG, "Sharing task as image")
        Toast.makeText(this, R.string.history_generating_image, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val files = mutableListOf<File>()
                withContext(Dispatchers.Default) {
                    generateShareImages(currentTask, timeline.toList()) { bitmap ->
                        files += withContext(Dispatchers.IO) { saveBitmapToCache(bitmap) }
                    }
                }
                shareImageFiles(files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Error sharing image", e)
                Toast.makeText(this@HistoryDetailActivity, R.string.history_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Generates a share image from task history.
     *
     * Streams the same message/tool timeline into bounded image pages, retaining all records.
     *
     * @param task Task history to render
     * @param consumePage Writes each image before it is recycled by this renderer
     */
    private suspend fun generateShareImages(
        task: TaskHistory,
        entries: List<HistoryTimeline.Entry>,
        consumePage: suspend (Bitmap) -> Unit,
    ) {
        // A conversation can be arbitrarily long. Render one bounded page at a time,
        // writing/releasing it before allocating the next instead of clipping a giant bitmap.
        val width = 1080
        val pageHeight = 6000
        val padding = 40f
        val lineHeight = 42f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Color.WHITE }
        val background = Paint().apply { color = Color.parseColor("#2A2A2A") }
        var page = Bitmap.createBitmap(width, pageHeight, Bitmap.Config.ARGB_8888)
        var canvas = Canvas(page).apply { drawColor(Color.parseColor("#1A1A1A")) }
        var y = padding
        suspend fun flush(last: Boolean) {
            currentCoroutineContext().ensureActive()
            val height = if (last) (y + padding).toInt().coerceIn(1, pageHeight) else pageHeight
            val output = if (height == pageHeight) page else Bitmap.createBitmap(page, 0, 0, width, height)
            try { consumePage(output) } finally {
                if (output !== page) output.recycle()
                page.recycle()
            }
            if (!last) {
                page = Bitmap.createBitmap(width, pageHeight, Bitmap.Config.ARGB_8888)
                canvas = Canvas(page).apply { drawColor(Color.parseColor("#1A1A1A")) }
                y = padding
            }
        }
        suspend fun text(value: String, user: Boolean? = null) {
            paint.color = if (user == true) Color.parseColor("#D4E8FF") else Color.WHITE
            for (line in wrapText(value, paint, width - padding * 2 - 24)) {
                currentCoroutineContext().ensureActive()
                if (y + lineHeight + padding > pageHeight) flush(false)
                if (user != null) {
                    background.color = Color.parseColor(if (user) "#344457" else "#2A2A2A")
                    canvas.drawRoundRect(padding, y, width - padding, y + lineHeight, 8f, 8f, background)
                }
                canvas.drawText(line, padding + 12, y + 32, paint)
                y += lineHeight
            }
            y += 12
        }
        try {
            text("AutoGLM 会话记录 · ${if (task.success) "成功" else "未成功"}")
            text("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(task.startTime))} · ${formatDuration(executionDurationMs ?: task.duration)}")
            for (entry in entries) {
                val message = entry as? HistoryTimeline.Entry.Message
                if (message != null) {
                    val user = message.message.role == HistoryMessageRole.USER
                    text(if (user) "你" else "助手", user)
                    text(message.message.text, user)
                } else {
                    text(HistoryTimeline.text(entry))
                }
                for (image in HistoryTimeline.images(entry)) {
                    text(image.kind.label)
                    val screenshot = historyManager.getScreenshotBitmap(image.path)
                    if (screenshot == null) {
                        text("[截图不可用]")
                        continue
                    }
                    try {
                        val scale = minOf((width - padding * 2) / screenshot.width,
                            (pageHeight - padding * 2 - lineHeight) / screenshot.height)
                        val targetWidth = (screenshot.width * scale).toInt().coerceAtLeast(1)
                        val targetHeight = (screenshot.height * scale).toInt().coerceAtLeast(1)
                        if (y + targetHeight + padding > pageHeight) {
                            flush(false)
                            text(image.kind.label)
                        }
                        val left = (width - targetWidth) / 2f
                        canvas.drawBitmap(screenshot, null,
                            android.graphics.RectF(left, y, left + targetWidth, y + targetHeight), null)
                        y += targetHeight + 20
                    } finally { screenshot.recycle() }
                }
                y += 20
            }
            text("由 AutoGLM For Android 生成")
            flush(true)
        } finally {
            if (!page.isRecycled) page.recycle()
        }
    }
    /**
     * Wraps text to fit within a given width.
     *
     * @param text Text to wrap
     * @param paint Paint used for measuring text width
     * @param maxWidth Maximum width in pixels
     * @return List of wrapped text lines
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var remaining = paragraph
            if (remaining.isEmpty()) lines += ""
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                lines += remaining.substring(0, count)
                remaining = remaining.substring(count)
            }
        }

        return lines.ifEmpty { listOf("") }
    }

    /**
     * Saves bitmap to cache directory.
     *
     * @param bitmap Bitmap to save
     * @return File reference to the saved image
     */
    private fun saveBitmapToCache(bitmap: Bitmap): File {
        val cacheDir = File(cacheDir, "share")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val file = File(cacheDir, "autoglm_task_${System.nanoTime()}.webp")
        FileOutputStream(file).use { out ->
            @Suppress("DEPRECATION")
            val format =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
            bitmap.compress(format, 90, out)
        }
        return file
    }

    /**
     * Shares the image file using system share sheet.
     *
     * @param files Ordered timeline pages to share
     */
    private fun shareImageFiles(files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { file -> FileProvider.getUriForFile(this, "$packageName.fileprovider", file) })

        val intent =
            Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/webp"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newUri(contentResolver, "AutoGLM 会话记录", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        startActivity(Intent.createChooser(intent, getString(R.string.history_share_title)))
    }

    /**
     * Saves bitmap to device gallery.
     *
     * Uses MediaStore API on Android 10+ and legacy storage on older versions.
     *
     * @param bitmap Bitmap to save
     * @return True if save was successful, false otherwise
     */
    private fun saveBitmapToGallery(bitmap: Bitmap): Boolean {
        val filename = "AutoGLM_${System.nanoTime()}.webp"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ use MediaStore
            val contentValues =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/webp")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AutoGLM")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    @Suppress("DEPRECATION")
                    val format =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            Bitmap.CompressFormat.WEBP
                        }
                    bitmap.compress(format, 90, out)
                }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
                true
            } ?: false
        } else {
            // Legacy storage
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val autoglmDir = File(picturesDir, "AutoGLM")
            if (!autoglmDir.exists()) autoglmDir.mkdirs()

            val file = File(autoglmDir, filename)
            FileOutputStream(file).use { out ->
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
            }

            // Notify gallery using MediaScannerConnection
            MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf("image/webp"),
                null,
            )

            true
        }
    }

    companion object {
        private const val TAG = "HistoryDetailActivity"

        /** Intent extra key for task ID. */
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
