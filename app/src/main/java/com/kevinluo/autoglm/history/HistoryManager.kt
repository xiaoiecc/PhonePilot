package com.kevinluo.autoglm.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.core.ConversationSnapshot
import com.kevinluo.autoglm.screenshot.ScreenshotCompression
import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * Manages task execution history storage and retrieval.
 *
 * This singleton class handles all operations related to task history including:
 * - Recording task execution steps with screenshots
 * - Persisting history to local storage
 * - Loading and querying historical tasks
 * - Managing history lifecycle (creation, deletion, cleanup)
 *
 * Usage example:
 * ```kotlin
 * val historyManager = HistoryManager.getInstance(context)
 * val task = historyManager.startTask("Open Settings app")
 * historyManager.recordStep(1, "Thinking...", action, "Tap on Settings", true)
 * historyManager.completeTask(true, "Task completed successfully")
 * ```
 *
 */
class HistoryManager private constructor(private val context: Context) {
    /** Directory for storing task history files. */
    private val historyDir: File by lazy {
        File(context.filesDir, HISTORY_DIR).also { it.mkdirs() }
    }

    /** Currently recording task, null if no task is being recorded. */
    private var currentTask: TaskHistory? = null

    /** Base64-encoded screenshot data for the current step. */
    private var currentScreenshotBase64: String? = null

    /** Width of the current screenshot in pixels. */
    private var currentScreenshotWidth: Int = 0

    /** Height of the current screenshot in pixels. */
    private var currentScreenshotHeight: Int = 0

    private val _historyList = MutableStateFlow<List<TaskHistory>>(emptyList())

    /** Observable list of all task histories, sorted by most recent first. */
    val historyList: StateFlow<List<TaskHistory>> = _historyList.asStateFlow()

    init {
        loadHistoryIndex()
    }

    /**
     * Starts recording a new task.
     *
     * Creates a new [TaskHistory] instance and sets it as the current task being recorded.
     * All subsequent calls to [recordStep] will add steps to this task until [completeTask] is called.
     *
     * @param taskDescription Human-readable description of the task being executed
     * @return The newly created [TaskHistory] instance
     *
     */
    fun startTask(taskDescription: String, conversationId: String? = null): TaskHistory {
        clearCurrentScreenshot()
        val id = java.util.UUID.randomUUID().toString()
        val task = TaskHistory(id = id, taskDescription = taskDescription, conversationId = conversationId ?: id)
        currentTask = task
        Logger.d(TAG, "Started recording task: ${task.id}")
        return task
    }

    suspend fun recordMessage(role: HistoryMessageRole, text: String) = withContext(Dispatchers.IO) {
        val task = currentTask ?: return@withContext
        task.messages += HistoryMessage(role, text)
        saveTask(task)
    }

    fun updateConversationSession(session: ConversationSnapshot) {
        currentTask?.let { task ->
            task.previousResponseId = session.previousResponseId
            task.totalInputTokens = session.totalInputTokens
            task.totalOutputTokens = session.totalOutputTokens
            task.totalCachedTokens = session.totalCachedTokens
            task.hasInputUsage = session.hasInputUsage
            task.hasOutputUsage = session.hasOutputUsage
        }
    }

    suspend fun loadConversation(conversationId: String): RestoredConversation? = withContext(Dispatchers.IO) {
        val tasks = _historyList.value.filter { it.conversationId == conversationId }.map {
            loadTask(it.id) ?: return@withContext null
        }
        ConversationHistory.restore(tasks, conversationId)
    }

    suspend fun getConversationRuns(conversationId: String): List<TaskHistory> = withContext(Dispatchers.IO) {
        ConversationHistory.runs(_historyList.value.filter { it.conversationId == conversationId }.mapNotNull { loadTask(it.id) }, conversationId)
    }

    suspend fun deleteConversations(conversationIds: Set<String>) {
        deleteTasks(_historyList.value.filter { it.conversationId in conversationIds }.map { it.id }.toSet())
    }

    /**
     * Sets the current screenshot for the next step.
     *
     * The screenshot data will be used when [recordStep] is called to save
     * both the original and annotated versions of the screenshot.
     *
     * @param base64Data Base64-encoded screenshot image data (WebP format)
     * @param width Screenshot width in pixels
     * @param height Screenshot height in pixels
     *
     */
    fun setCurrentScreenshot(base64Data: String, width: Int, height: Int) {
        currentScreenshotBase64 = base64Data
        currentScreenshotWidth = width
        currentScreenshotHeight = height
    }

    /** A text-only tool turn must not inherit an image from an earlier turn or task. */
    fun clearCurrentScreenshot() {
        currentScreenshotBase64 = null
        currentScreenshotWidth = 0
        currentScreenshotHeight = 0
    }

    /** Persist provenance for one invocation; images never contain invented/replayed actions. */
    suspend fun recordToolCall(call: HistoryToolCall, before: Screenshot? = null, after: Screenshot? = null) = withContext(Dispatchers.IO) {
        val task = currentTask ?: return@withContext
        val prefix = "tool_" + java.util.UUID.randomUUID().toString()
        var beforePath: String? = null
        var afterPath: String? = null
        var annotatedPath: String? = null
        var imageFailure = false
        fun saveImage(label: String, bytes: ByteArray): String {
            val directory = File(historyDir, task.id).also { it.mkdirs() }
            val file = File(directory, "${prefix}_${label}.${ScreenshotCompression.extensionFor(bytes)}")
            FileOutputStream(file).use { it.write(bytes) }
            return file.absolutePath
        }
        try {
            if (before != null && !before.isSensitive) {
                val bytes = Base64.decode(before.base64Data, Base64.DEFAULT)
                beforePath = saveImage("before", bytes)
                if (call.actions.isNotEmpty()) {
                    val action = call.actions.singleOrNull() ?: AgentAction.Batch(call.actions, delayMs = 0)
                    val annotation = ScreenshotAnnotator.createAnnotation(action, before.width, before.height)
                    if (annotation !is ActionAnnotation.None) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            try {
                                val density = context.resources.displayMetrics.density *
                                    bitmap.width.toFloat() / context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                                val annotated = ScreenshotAnnotator.annotate(bitmap, annotation, density)
                                try { annotatedPath = saveImage("annotated", ScreenshotCompression.encode(annotated)) }
                                finally { annotated.recycle() }
                            } finally { bitmap.recycle() }
                        } else imageFailure = true
                    }
                }
            }
        } catch (_: Exception) { imageFailure = true }
        try {
            if (after != null && !after.isSensitive) {
                afterPath = if (beforePath != null && before?.base64Data == after.base64Data) beforePath
                    else saveImage("after", Base64.decode(after.base64Data, Base64.DEFAULT))
            }
        } catch (_: Exception) { imageFailure = true }
        task.toolCalls.add(call.copy(beforeScreenshotPath = beforePath, afterScreenshotPath = afterPath,
            annotatedScreenshotPath = annotatedPath,
            result = call.result + if (imageFailure) "\n[历史截图保存不完整；工具执行结果不因此改变]" else ""))
    }

    /**
     * Records a step in the current task.
     *
     * Saves the step information including thinking, action, and screenshot to the current task.
     * If a screenshot is available (set via [setCurrentScreenshot]), it will be saved to disk
     * and optionally annotated with action visualization.
     *
     * @param stepNumber Sequential step number within the task
     * @param thinking Model's reasoning/thinking for this step
     * @param action The agent action executed, or null if no action
     * @param actionDescription Human-readable description of the action
     * @param success Whether the step executed successfully
     * @param message Optional additional message or error details
     *
     */
    suspend fun recordStep(
        stepNumber: Int,
        thinking: String,
        action: AgentAction?,
        actionDescription: String,
        success: Boolean,
        message: String? = null,
    ) = withContext(Dispatchers.IO) {
        val task = currentTask ?: return@withContext

        var screenshotPath: String? = null
        var annotatedPath: String? = null

        val tracedImages = task.toolCalls.filter { it.stepNumber == stepNumber }
        if (tracedImages.isNotEmpty()) {
            // Per-call traces already own the correct pre/post images. Never draw coordinates
            // on a later, changed screen or save another copy of the same response screenshot.
            screenshotPath = tracedImages.lastOrNull { it.afterScreenshotPath != null || it.beforeScreenshotPath != null }
                ?.let { it.afterScreenshotPath ?: it.beforeScreenshotPath }
        } else currentScreenshotBase64?.let { base64 ->
            try {
                // Reuse exactly the compressed bytes uploaded to the model (WebP or PNG).
                val webpBytes = Base64.decode(base64, Base64.DEFAULT)

                // Save original screenshot directly without re-compression
                screenshotPath = saveScreenshotBytes(task.id, stepNumber, webpBytes, false)

                // Create and save annotated screenshot if action has visual annotation
                if (action != null) {
                    val annotation =
                        ScreenshotAnnotator.createAnnotation(
                            action,
                            currentScreenshotWidth,
                            currentScreenshotHeight,
                        )
                    if (annotation !is ActionAnnotation.None) {
                        // Only decode bitmap when we need to annotate
                        val bitmap = BitmapFactory.decodeByteArray(webpBytes, 0, webpBytes.size)
                        if (bitmap != null) {
                            // Calculate scaled density based on screenshot size vs typical screen size
                            // This ensures annotations look proportional on scaled screenshots
                            val baseDensity = context.resources.displayMetrics.density
                            val scaleFactor = bitmap.width.toFloat() / context.resources.displayMetrics.widthPixels
                            val scaledDensity = baseDensity * scaleFactor
                            val annotatedBitmap = ScreenshotAnnotator.annotate(bitmap, annotation, scaledDensity)
                            annotatedPath = saveScreenshotBitmap(task.id, stepNumber, annotatedBitmap, true)
                            annotatedBitmap.recycle()
                            bitmap.recycle()
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save screenshot for step $stepNumber", e)
            }
        }

        val step =
            HistoryStep(
                stepNumber = stepNumber,
                thinking = thinking,
                action = action,
                actionDescription = actionDescription,
                screenshotPath = screenshotPath,
                annotatedScreenshotPath = annotatedPath,
                success = success,
                message = message,
            )

        task.steps.add(step)
        Logger.d(TAG, "Recorded step $stepNumber for task ${task.id}")

        // Clear current screenshot
        currentScreenshotBase64 = null
    }

    /**
     * Completes the current task recording.
     *
     * Finalizes the task with success/failure status and saves it to persistent storage.
     * Runs with neither steps nor messages are discarded. The history list is updated and
     * old entries are trimmed if the maximum count is exceeded.
     *
     * @param success Whether the task completed successfully
     * @param message Optional completion message or error description
     *
     */
    suspend fun completeTask(success: Boolean, message: String?) = withContext(Dispatchers.IO) {
        val task = currentTask ?: return@withContext

        // Discard only runs with no steps and no conversation messages.
        if (task.steps.isEmpty() && task.messages.isEmpty() && task.toolCalls.isEmpty()) {
            Logger.d(TAG, "Skipping empty task ${task.id}")
            currentTask = null
            return@withContext
        }

        task.endTime = System.currentTimeMillis()
        task.success = success
        task.completionMessage = message

        // Save task to disk
        saveTask(task)

        // Update history list
        val updatedList = _historyList.value.toMutableList()
        updatedList.add(0, task)

        // Trim old history if needed
        val keptConversations = updatedList.map { it.conversationId }.distinct().take(MAX_HISTORY_COUNT).toSet()
        val removedRuns = updatedList.filter { it.conversationId !in keptConversations }
        removedRuns.forEach { deleteTaskFiles(it.id) }
        updatedList.removeAll(removedRuns.toSet())

        _historyList.value = updatedList
        saveHistoryIndex()

        Logger.d(TAG, "Completed task ${task.id}, success=$success")
        currentTask = null
    }

    /**
     * Gets a task history by ID.
     *
     * Loads the complete task history including all steps from persistent storage.
     *
     * @param taskId Unique identifier of the task to retrieve
     * @return The [TaskHistory] if found, null otherwise
     *
     */
    suspend fun getTask(taskId: String): TaskHistory? = withContext(Dispatchers.IO) {
        loadTask(taskId)
    }

    /**
     * Deletes a task history.
     *
     * Removes the task and all associated files (screenshots) from storage.
     *
     * @param taskId Unique identifier of the task to delete
     *
     */
    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        deleteTaskFiles(taskId)
        _historyList.value = _historyList.value.filter { it.id != taskId }
        saveHistoryIndex()
    }

    /**
     * Deletes multiple task histories.
     *
     * Batch deletion of tasks and their associated files.
     *
     * @param taskIds Set of unique identifiers of tasks to delete
     *
     */
    suspend fun deleteTasks(taskIds: Set<String>) = withContext(Dispatchers.IO) {
        taskIds.forEach { taskId ->
            deleteTaskFiles(taskId)
        }
        _historyList.value = _historyList.value.filter { it.id !in taskIds }
        saveHistoryIndex()
    }

    /**
     * Clears all history.
     *
     * Removes all task histories and their associated files from storage.
     *
     */
    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        historyDir.listFiles()?.forEach { it.deleteRecursively() }
        _historyList.value = emptyList()
        saveHistoryIndex()
    }

    /**
     * Gets the screenshot bitmap for a step.
     *
     * Loads and decodes a screenshot image from the given file path.
     *
     * @param path Absolute file path to the screenshot, or null
     * @return Decoded [Bitmap] if the file exists and is valid, null otherwise
     *
     */
    fun getScreenshotBitmap(path: String?): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(path)
    }

    // Private helper methods

    /**
     * Saves raw WebP bytes directly to file (no re-compression).
     *
     * @param taskId Task identifier for directory organization
     * @param stepNumber Step number for filename
     * @param webpBytes Raw WebP image bytes
     * @param annotated Whether this is an annotated screenshot
     * @return Absolute file path of the saved screenshot
     */
    private fun saveScreenshotBytes(
        taskId: String,
        stepNumber: Int,
        webpBytes: ByteArray,
        annotated: Boolean,
    ): String {
        val taskDir = File(historyDir, taskId).also { it.mkdirs() }
        val suffix = if (annotated) "_annotated" else ""
        val extension = ScreenshotCompression.extensionFor(webpBytes)
        val file = File(taskDir, "step_${stepNumber}$suffix.$extension")

        FileOutputStream(file).use { out ->
            out.write(webpBytes)
        }

        return file.absolutePath
    }

    /**
     * Saves bitmap as WebP (used for annotated screenshots).
     *
     * @param taskId Task identifier for directory organization
     * @param stepNumber Step number for filename
     * @param bitmap Bitmap to save
     * @param annotated Whether this is an annotated screenshot
     * @return Absolute file path of the saved screenshot
     */
    private fun saveScreenshotBitmap(taskId: String, stepNumber: Int, bitmap: Bitmap, annotated: Boolean): String {
        return saveScreenshotBytes(taskId, stepNumber, ScreenshotCompression.encode(bitmap), annotated)
    }

    /**
     * Saves a task's metadata to JSON file.
     *
     * @param task Task history to save
     */
    private fun saveTask(task: TaskHistory) {
        val taskDir = File(historyDir, task.id).also { it.mkdirs() }
        val metaFile = File(taskDir, "meta.json")
        val json = buildJsonObject {
            put("id", task.id)
            put("taskDescription", task.taskDescription)
            put("startTime", task.startTime)
            put("conversationId", task.conversationId)
            put("previousResponseId", task.previousResponseId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("totalInputTokens", task.totalInputTokens)
            put("totalOutputTokens", task.totalOutputTokens)
            put("totalCachedTokens", task.totalCachedTokens)
            put("hasInputUsage", task.hasInputUsage)
            put("hasOutputUsage", task.hasOutputUsage)
            put("endTime", task.endTime?.let { JsonPrimitive(it) } ?: JsonNull)
            put("success", task.success)
            put("completionMessage", task.completionMessage?.let { JsonPrimitive(it) } ?: JsonNull)
            put("toolCalls", buildJsonArray { task.toolCalls.forEach { add(HistoryToolCallJson.encode(it)) } })
            put("steps", buildJsonArray {
                task.steps.forEach { step ->
                    add(buildJsonObject {
                        put("stepNumber", step.stepNumber)
                        put("timestamp", step.timestamp)
                        put("thinking", step.thinking)
                        put("actionDescription", step.actionDescription)
                        put("screenshotPath", step.screenshotPath?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("annotatedScreenshotPath", step.annotatedScreenshotPath?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("success", step.success)
                        put("message", step.message?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
            put("messages", buildJsonArray {
                task.messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role.name)
                        put("text", message.text)
                        put("timestamp", message.timestamp)
                    })
                }
            })
        }
        metaFile.writeText(json.toString())
    }

    /**
     * Loads a task from its JSON metadata file.
     *
     * @param taskId Task identifier to load
     * @return Loaded TaskHistory, or null if not found or invalid
     */
    private fun loadTask(taskId: String): TaskHistory? {
        val metaFile = File(historyDir, "$taskId/meta.json")
        if (!metaFile.exists()) return null
        return try {
            taskHistoryFromJson(metaFile.readText())
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load task $taskId", e)
            null
        }
    }

    /**
     * Deletes all files associated with a task.
     *
     * @param taskId Task identifier whose files should be deleted
     */
    private fun deleteTaskFiles(taskId: String) {
        File(historyDir, taskId).deleteRecursively()
    }

    /**
     * Loads the history index from persistent storage.
     *
     * Populates [_historyList] with all saved task histories.
     */
    private fun loadHistoryIndex() {
        val indexFile = File(historyDir, INDEX_FILE)
        if (!indexFile.exists()) return

        try {
            val json = JSONArray(indexFile.readText())
            val list = mutableListOf<TaskHistory>()

            for (i in 0 until json.length()) {
                val taskId = json.getString(i)
                loadTask(taskId)?.let { list.add(it) }
            }

            _historyList.value = list
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load history index", e)
        }
    }

    /**
     * Saves the history index to persistent storage.
     *
     * Writes the list of task IDs to the index file for quick loading on startup.
     */
    private fun saveHistoryIndex() {
        val indexFile = File(historyDir, INDEX_FILE)
        val json = buildJsonArray { _historyList.value.forEach { add(JsonPrimitive(it.id)) } }
        indexFile.writeText(json.toString())
    }

    companion object {
        private const val TAG = "HistoryManager"
        private const val HISTORY_DIR = "task_history"
        private const val INDEX_FILE = "history_index.json"
        private const val MAX_HISTORY_COUNT = 50

        @Volatile
        private var instance: HistoryManager? = null

        internal fun taskHistoryFromJson(raw: String): TaskHistory {
            val json = Json.parseToJsonElement(raw).jsonObject
            val steps = json["steps"]?.jsonArray?.map { element ->
                val step = element.jsonObject
                HistoryStep(
                    stepNumber = step.getValue("stepNumber").jsonPrimitive.int,
                    timestamp = step.getValue("timestamp").jsonPrimitive.long,
                    thinking = step.getValue("thinking").jsonPrimitive.content,
                    action = null,
                    actionDescription = step.getValue("actionDescription").jsonPrimitive.content,
                    screenshotPath = step["screenshotPath"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    annotatedScreenshotPath = step["annotatedScreenshotPath"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    success = step.getValue("success").jsonPrimitive.boolean,
                    message = step["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                )
            }?.toMutableList() ?: mutableListOf()
            val messages = json["messages"]?.jsonArray?.map { element ->
                val message = element.jsonObject
                HistoryMessage(
                    role = HistoryMessageRole.valueOf(message.getValue("role").jsonPrimitive.content),
                    text = message.getValue("text").jsonPrimitive.content,
                    timestamp = message.getValue("timestamp").jsonPrimitive.long,
                )
            }?.toMutableList() ?: mutableListOf()
            val id = json.getValue("id").jsonPrimitive.content
            return TaskHistory(
                id = id,
                taskDescription = json.getValue("taskDescription").jsonPrimitive.content,
                startTime = json.getValue("startTime").jsonPrimitive.long,
                conversationId = json["conversationId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: id,
                endTime = json["endTime"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
                success = json.getValue("success").jsonPrimitive.boolean,
                completionMessage = json["completionMessage"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                steps = steps,
                messages = messages,
                previousResponseId = json["previousResponseId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                totalInputTokens = json["totalInputTokens"]?.jsonPrimitive?.long ?: 0,
                totalOutputTokens = json["totalOutputTokens"]?.jsonPrimitive?.long ?: 0,
                totalCachedTokens = json["totalCachedTokens"]?.jsonPrimitive?.long ?: 0,
                hasInputUsage = json["hasInputUsage"]?.jsonPrimitive?.boolean ?: false,
                hasOutputUsage = json["hasOutputUsage"]?.jsonPrimitive?.boolean ?: false,
                toolCalls = json["toolCalls"]?.jsonArray?.mapNotNull { element ->
                    // A damaged optional trace must not make an otherwise readable old chat vanish.
                    runCatching { HistoryToolCallJson.decode(element.jsonObject) }.getOrNull()
                }?.toMutableList() ?: mutableListOf(),
            )
        }

        /**
         * Gets the singleton instance of HistoryManager.
         *
         * @param context Android context, application context will be used
         * @return The singleton HistoryManager instance
         */
        fun getInstance(context: Context): HistoryManager = instance ?: synchronized(this) {
            instance ?: HistoryManager(context.applicationContext).also { instance = it }
        }
    }
}
