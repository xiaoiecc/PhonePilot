package com.kevinluo.autoglm.history

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.home.ChatMessageAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Conversation messages and individual tool records share one chronological timeline. */
class HistoryDetailAdapter(private val historyManager: HistoryManager, private val coroutineScope: CoroutineScope) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var task: TaskHistory? = null
    private var timeline: List<HistoryTimeline.Entry> = emptyList()
    private var executionDurationMs: Long? = null
    private val expandedCalls = mutableSetOf<Pair<String, String>>()
    private val images = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val imageHolders = mutableSetOf<RecordViewHolder>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun setTask(task: TaskHistory, executionDurationMs: Long? = null,
        timeline: List<HistoryTimeline.Entry> = HistoryTimeline.build(listOf(task))) {
        imageHolders.forEach { it.clearImage() }
        this.task = task
        this.timeline = timeline.toList()
        this.executionDurationMs = executionDurationMs
        notifyDataSetChanged()
    }

    fun cleanup() {
        imageHolders.forEach { it.clearImage() }
        imageHolders.clear()
        expandedCalls.clear()
        // Displayed bitmaps can outlive cache eviction. Let their last owner release them.
        images.evictAll()
    }

    override fun getItemCount() = if (task == null) 0 else timeline.size + 1
    override fun getItemViewType(position: Int) = when {
        position == 0 -> HEADER
        timeline[position - 1] is HistoryTimeline.Entry.Message -> MESSAGE
        else -> RECORD
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_history_header, parent, false))
            MESSAGE -> ChatMessageAdapter.MessageViewHolder(inflater.inflate(R.layout.item_chat_message, parent, false))
            else -> RecordViewHolder(inflater.inflate(R.layout.item_history_step, parent, false)).also { imageHolders += it }
        }
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> task?.let(holder::bind)
            is ChatMessageAdapter.MessageViewHolder -> holder.bind((timeline[position - 1] as HistoryTimeline.Entry.Message).message)
            is RecordViewHolder -> holder.bind(timeline[position - 1])
        }
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is RecordViewHolder) holder.clearImage()
        super.onViewRecycled(holder)
    }

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(task: TaskHistory) {
            // The title remains a summary; every actual prompt appears below in its own bubble.
            itemView.findViewById<TextView>(R.id.taskDescription).text = task.taskDescription
            itemView.findViewById<TextView>(R.id.statusText).apply {
                setText(if (task.success) R.string.history_success else R.string.history_failed)
                setTextColor(ContextCompat.getColor(context, if (task.success) R.color.status_success else R.color.status_error))
            }
            itemView.findViewById<TextView>(R.id.infoText).text =
                "${dateFormat.format(Date(task.startTime))} · ${task.stepCount} 步 · ${task.toolCalls.size} 次调用 · ${(executionDurationMs ?: task.duration).coerceAtLeast(0) / 1000} 秒"
        }
    }

    inner class RecordViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val number = view.findViewById<TextView>(R.id.stepNumber)
        private val description = view.findViewById<TextView>(R.id.actionDescription)
        private val status = view.findViewById<ImageView>(R.id.statusIcon)
        private val thinkingSection = view.findViewById<LinearLayout>(R.id.thinkingSection)
        private val thinking = view.findViewById<TextView>(R.id.thinkingText)
        private val detail = view.findViewById<TextView>(R.id.messageText)
        private val section = view.findViewById<LinearLayout>(R.id.screenshotSection)
        private val image = view.findViewById<ImageView>(R.id.screenshotImage)
        private val original = view.findViewById<MaterialButton>(R.id.btnOriginal)
        private val annotated = view.findViewById<MaterialButton>(R.id.btnAnnotated)
        private val after = LayoutInflater.from(view.context).inflate(
            R.layout.item_history_screenshot_button, original.parent as ViewGroup, false,
        ) as MaterialButton
        private val buttons = listOf(original, annotated, after)
        private val expand = LayoutInflater.from(view.context).inflate(
            R.layout.item_history_screenshot_button, detail.parent as ViewGroup, false,
        ) as MaterialButton
        private var imageJob: Job? = null
        private var requestedPath: String? = null

        init {
            (original.parent as LinearLayout).addView(after)
            (detail.parent as LinearLayout).addView(expand)
            detail.setTextIsSelectable(true)
        }

        fun bind(entry: HistoryTimeline.Entry) {
            clearImage()
            val success: Boolean
            when (entry) {
                is HistoryTimeline.Entry.Tool -> {
                    number.text = entry.call.stepNumber.toString()
                    description.text = entry.call.name
                    success = entry.call.status == HistoryToolStatus.SUCCEEDED
                    thinkingSection.visibility = View.GONE
                    val key = entry.call.runId to entry.call.callId
                    fun updateDetails() {
                        val expanded = key in expandedCalls
                        detail.text = if (expanded) HistoryTimeline.text(entry) else HistoryTimeline.summary(entry.call)
                        expand.text = if (expanded) "收起详情" else "展开详情"
                    }
                    expand.visibility = View.VISIBLE
                    expand.setOnClickListener {
                        if (!expandedCalls.remove(key)) expandedCalls.add(key)
                        updateDetails()
                    }
                    updateDetails()
                    detail.visibility = View.VISIBLE
                    status.contentDescription = HistoryTimeline.statusLabel(entry.call.status)
                }
                is HistoryTimeline.Entry.LegacyStep -> {
                    expand.visibility = View.GONE
                    expand.setOnClickListener(null)
                    number.text = entry.step.stepNumber.toString()
                    description.text = entry.step.actionDescription
                    success = entry.step.success
                    thinkingSection.visibility = if (entry.step.thinking.isBlank()) View.GONE else View.VISIBLE
                    thinking.text = entry.step.thinking
                    detail.text = entry.step.message
                    detail.visibility = if (entry.step.message.isNullOrBlank()) View.GONE else View.VISIBLE
                    status.contentDescription = if (success) "成功" else "失败"
                }
                is HistoryTimeline.Entry.Outcome -> {
                    number.text = "!"
                    description.text = "任务结束原因"
                    success = false
                    thinkingSection.visibility = View.GONE
                    expand.visibility = View.GONE
                    expand.setOnClickListener(null)
                    detail.text = entry.reason
                    detail.visibility = View.VISIBLE
                    status.contentDescription = "任务未完成"
                }
                is HistoryTimeline.Entry.Message -> return
            }
            status.setImageResource(if (success) R.drawable.ic_check_circle else R.drawable.ic_error)
            val statusColor = if (success) R.color.status_success else if (entry is HistoryTimeline.Entry.Tool &&
                entry.call.status in setOf(HistoryToolStatus.CANCELLED, HistoryToolStatus.SKIPPED)) R.color.status_waiting else R.color.status_error
            status.setColorFilter(ContextCompat.getColor(itemView.context, statusColor))
            val choices = HistoryTimeline.images(entry)
            section.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
            buttons.forEachIndexed { index, button ->
                val choice = choices.getOrNull(index)
                button.visibility = if (choice == null) View.GONE else View.VISIBLE
                button.text = choice?.kind?.label
                button.setOnClickListener {
                    if (choice != null) selectImage(choice, choices)
                }
            }
            choices.firstOrNull { it.kind == HistoryTimeline.ImageKind.ANNOTATED }
                ?.let { selectImage(it, choices) } ?: choices.firstOrNull()?.let { selectImage(it, choices) }
        }

        private fun selectImage(choice: HistoryTimeline.Image, choices: List<HistoryTimeline.Image>) {
            buttons.forEachIndexed { index, button -> button.alpha = if (choices.getOrNull(index) == choice) 1f else 0.5f }
            image.contentDescription = choice.kind.label
            imageJob?.cancel()
            requestedPath = choice.path
            image.setImageDrawable(null)
            images.get(choice.path)?.let { image.setImageBitmap(it); return }
            imageJob = coroutineScope.launch {
                val bitmap = withContext(Dispatchers.IO) { historyManager.getScreenshotBitmap(choice.path) }
                if (bitmap != null) {
                    images.put(choice.path, bitmap)
                    if (requestedPath == choice.path) image.setImageBitmap(bitmap)
                }
            }
        }

        fun clearImage() {
            imageJob?.cancel()
            imageJob = null
            requestedPath = null
            image.setImageDrawable(null)
        }
    }

    companion object {
        private const val HEADER = 0
        private const val MESSAGE = 1
        private const val RECORD = 2
    }
}
