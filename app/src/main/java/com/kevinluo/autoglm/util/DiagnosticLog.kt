package com.kevinluo.autoglm.util

import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in structured diagnostics. Callers pass operational fields, never message or image payloads. */
object DiagnosticLog {
    private val detailedEnabled = AtomicBoolean(false)

    val isDetailedEnabled: Boolean get() = detailedEnabled.get()

    fun setDetailedEnabled(enabled: Boolean) {
        val changed = detailedEnabled.getAndSet(enabled) != enabled
        Logger.minLevel = if (enabled) Logger.Level.VERBOSE else Logger.Level.DEBUG
        if (changed) Logger.i(TAG, "Detailed diagnostics ${if (enabled) "enabled" else "disabled"}")
    }

    fun event(component: String, operation: String, vararg fields: Pair<String, Any?>) {
        if (!isDetailedEnabled) return
        Logger.v("$TAG/$component", formatEvent(operation, fields))
    }

    internal inline fun <T> trace(
        component: String,
        operation: String,
        vararg fields: Pair<String, Any?>,
        block: () -> T,
    ): T {
        if (!isDetailedEnabled) return block()
        event(component, "$operation.started", *fields)
        val started = System.nanoTime()
        return try {
            val result = block()
            event(component, "$operation.completed", *fields, "elapsedMs" to elapsedMs(started))
            result
        } catch (error: Throwable) {
            event(component, "$operation.failed", *fields, "elapsedMs" to elapsedMs(started),
                "errorType" to error.javaClass.name)
            Logger.v("$TAG/$component", "$operation.stack ${formatContext(fields)}", error)
            throw error
        }
    }

    internal suspend inline fun <T> traceSuspend(
        component: String,
        operation: String,
        vararg fields: Pair<String, Any?>,
        block: suspend () -> T,
    ): T {
        if (!isDetailedEnabled) return block()
        event(component, "$operation.started", *fields)
        val started = System.nanoTime()
        return try {
            val result = block()
            event(component, "$operation.completed", *fields, "elapsedMs" to elapsedMs(started))
            result
        } catch (error: Throwable) {
            event(component, "$operation.failed", *fields, "elapsedMs" to elapsedMs(started),
                "errorType" to error.javaClass.name)
            Logger.v("$TAG/$component", "$operation.stack ${formatContext(fields)}", error)
            throw error
        }
    }

    fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    internal fun formatEvent(operation: String, fields: Array<out Pair<String, Any?>>): String = buildString {
        append(operation)
        append(" pid=")
        append(Process.myPid())
        append(" thread=")
        append(Thread.currentThread().name)
        append('#')
        append(Thread.currentThread().id)
        if (fields.isNotEmpty()) {
            append(' ')
            append(formatContext(fields))
        }
    }

    internal fun formatContext(fields: Array<out Pair<String, Any?>>): String = fields.joinToString(" ") { (key, value) ->
        "${key.sanitizeKey()}=${render(value).sanitize()}"
    }

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number, is Enum<*> -> value.toString()
        is CharSequence -> value.toString()
        is IntArray -> value.joinToString(",", "[", "]")
        is LongArray -> value.joinToString(",", "[", "]")
        is Array<*> -> value.map(::render).joinToString(",", "[", "]")
        is Collection<*> -> value.map(::render).joinToString(",", "[", "]")
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, entry) ->
            "${render(key)}:${render(entry)}"
        }
        else -> value.javaClass.name
    }

    private fun String.sanitizeKey(): String = replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)

    private fun String.sanitize(): String = take(MAX_VALUE_LENGTH)
        .replace(Regex("(?i)Bearer\\s+\\S+"), "[redacted]")
        .replace(Regex("\\bsk-[A-Za-z0-9_-]+"), "[redacted]")
        .replace(Regex("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\\r\\n]+"), "[encoded-data]")
        .replace(Regex("(?i)(api[_-]?key|authorization|proxy-authorization|cookie|password|passwd|secret|token)\\s*[:=]\\s*[^\\s,;}]+"), "$1=[redacted]")
        .replace(Regex("(?i)(https?://[^\\s/@:]+):([^\\s/@]+)@"), "$1:[redacted]@")
        .replace(Regex("\\s+"), " ")
        .let { if (length > MAX_VALUE_LENGTH) it + "…" else it }

    private const val TAG = "Diag"
    private const val MAX_VALUE_LENGTH = 4000
}
