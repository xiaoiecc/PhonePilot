package com.kevinluo.autoglm.history

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only presentation; original per-run step identities and screenshot paths are retained. */
object HistoryTimeline {
    sealed interface Entry {
        val timestamp: Long
        data class Message(val message: HistoryMessage, val historyRunId: String) : Entry {
            override val timestamp get() = message.timestamp
        }
        data class Tool(val call: HistoryToolCall) : Entry {
            // A result becomes known only after the operation (and any human reply) ends.
            override val timestamp get() = maxOf(call.startedAt, call.finishedAt)
        }
        data class LegacyStep(val step: HistoryStep, val historyRunId: String) : Entry {
            override val timestamp get() = step.timestamp
        }
        /** Execution diagnostics are not assistant utterances. */
        data class Outcome(val historyRunId: String, val reason: String, override val timestamp: Long) : Entry
    }
    enum class ImageKind(val label: String) { BEFORE("操作前"), ANNOTATED("动作标注"), AFTER("操作后"), ORIGINAL("原始截图") }
    data class Image(val kind: ImageKind, val path: String)

    /** Input runs are chronological; stable sorting preserves tie order and each tool batch. */
    fun build(runs: List<TaskHistory>): List<Entry> = runs.flatMap { run ->
        val messages = if (run.messages.isNotEmpty()) run.messages.toList() else buildList {
            add(HistoryMessage(HistoryMessageRole.USER, run.taskDescription, run.startTime))
            run.completionMessage?.takeIf { run.success && it.isNotBlank() }?.let {
                add(HistoryMessage(HistoryMessageRole.AGENT, it, run.endTime ?: run.startTime))
            }
        }
        // Storage already associates these calls with this historical run. Its record ID
        // differs from the coordinator runId carried by each trace.
        val tracedTurns = run.toolCalls.map { it.stepNumber }.toSet()
        val legacySteps = run.steps.filter { it.stepNumber !in tracedTurns }
        val reason = run.completionMessage?.trim()?.takeIf { !run.success && it.isNotEmpty() }
        fun diagnosticKey(value: String) = value.trim().removePrefix("ERROR:").trim()
        val alreadyVisible = reason != null && (
            messages.any { it.role == HistoryMessageRole.AGENT && diagnosticKey(it.text) == diagnosticKey(reason) } ||
                run.toolCalls.any { it.status != HistoryToolStatus.SUCCEEDED && diagnosticKey(it.result) == diagnosticKey(reason) } ||
                legacySteps.any { !it.success && it.message?.let { message -> diagnosticKey(message) == diagnosticKey(reason) } == true }
            )
        buildList {
            addAll(messages.map { Entry.Message(it, run.id) })
            addAll(run.toolCalls.map { Entry.Tool(it) })
            addAll(legacySteps.map { Entry.LegacyStep(it, run.id) })
            if (reason != null && !alreadyVisible) {
                add(Entry.Outcome(run.id, reason, maxOf(run.endTime ?: run.startTime,
                    messages.maxOfOrNull { it.timestamp } ?: run.startTime,
                    run.toolCalls.maxOfOrNull { maxOf(it.startedAt, it.finishedAt) } ?: run.startTime,
                    legacySteps.maxOfOrNull { it.timestamp } ?: run.startTime)))
            }
        }
    }.sortedBy { it.timestamp }

    fun images(entry: Entry): List<Image> = when (entry) {
        is Entry.Message, is Entry.Outcome -> emptyList()
        is Entry.Tool -> listOfNotNull(
            entry.call.beforeScreenshotPath?.let { Image(ImageKind.BEFORE, it) },
            entry.call.annotatedScreenshotPath?.let { Image(ImageKind.ANNOTATED, it) },
            entry.call.afterScreenshotPath?.let { Image(ImageKind.AFTER, it) },
        )
        is Entry.LegacyStep -> listOfNotNull(
            entry.step.screenshotPath?.let { Image(ImageKind.ORIGINAL, it) },
            entry.step.annotatedScreenshotPath?.let { Image(ImageKind.ANNOTATED, it) },
        )
    }

    fun statusLabel(status: HistoryToolStatus): String = when (status) {
        HistoryToolStatus.SUCCEEDED -> "成功"
        HistoryToolStatus.FAILED -> "失败"
        HistoryToolStatus.CANCELLED -> "已取消"
        HistoryToolStatus.SKIPPED -> "已跳过"
    }

    fun summary(call: HistoryToolCall): String {
        val resultLine = call.result.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val count = resultLine.codePointCount(0, resultLine.length)
        val preview = if (count > 180) resultLine.substring(0, resultLine.offsetByCodePoints(0, 180)) + "…" else resultLine
        return buildString {
            append("${statusLabel(call.status)} · ${(call.finishedAt - call.startedAt).coerceAtLeast(0)} ms")
            if (preview.isNotBlank()) append("\n$preview")
            if (call.detailsTruncated) append("\n[记录内容已截断]")
        }
    }

    /** Shared by the on-screen cards and export, so errors and truncation cannot disappear. */
    fun text(entry: Entry): String = when (entry) {
        is Entry.Message -> entry.message.text
        is Entry.Outcome -> "任务结束原因\n${entry.reason}"
        is Entry.Tool -> with(entry.call) {
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.getDefault())
            buildString {
                appendLine("$name · 步骤 $stepNumber · ${statusLabel(status)} · ${(finishedAt - startedAt).coerceAtLeast(0)} ms")
                appendLine("开始 ${timeFormat.format(Date(startedAt))}\n结束 ${timeFormat.format(Date(finishedAt))}")
                appendLine("调用 $callId · 任务 $runId")
                appendLine("参数\n$arguments")
                appendLine("结果\n$result")
                if (detailsTruncated) appendLine("[记录内容已截断]")
            }.trimEnd()
        }
        is Entry.LegacyStep -> with(entry.step) {
            listOfNotNull("步骤 $stepNumber · ${if (success) "成功" else "失败"} · $actionDescription",
                thinking.takeIf(String::isNotBlank), message?.takeIf(String::isNotBlank)).joinToString("\n")
        }
    }
}
