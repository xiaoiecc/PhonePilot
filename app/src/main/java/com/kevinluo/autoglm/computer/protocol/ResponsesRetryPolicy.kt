package com.kevinluo.autoglm.computer.protocol

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException
import kotlin.math.ceil

enum class TransportFailureCategory(val retryable: Boolean) {
    DNS(true), CONNECT(true), TIMEOUT(true), INTERRUPTED_IO(true), NETWORK_IO(true),
    TLS(false), PROTOCOL(false), CONFIGURATION(false),
}

/** Safe diagnostics only. Original exception text/cause may contain proxy credentials or URLs. */
class OpenAiTransportException internal constructor(
    val category: TransportFailureCategory,
    val exceptionClass: String,
    val attempts: Int,
) : IOException("OpenAI transport failure; category=${category.name.lowercase()}; class=$exceptionClass; attempts=$attempts")

class OpenAiHttpException internal constructor(
    val status: Int,
    val errorCode: String?,
    val errorType: String?,
    val errorParam: String?,
    val requestId: String?,
    val retryAfterMillis: Long?,
    val attempts: Int,
) : IOException(buildString {
    append("OpenAI API returned HTTP ").append(status).append("; category=http; attempts=").append(attempts)
    errorCode?.let { append("; code=").append(it) }
    errorType?.let { append("; type=").append(it) }
    errorParam?.let { append("; param=").append(it) }
    requestId?.let { append("; request_id=").append(it) }
    if (retryAfterMillis != null && retryAfterMillis > ResponsesRetryPolicy.MAX_WAIT_MS) {
        append("; retry_after_ms=").append(retryAfterMillis)
        append(" exceeds maximum_wait_ms=").append(ResponsesRetryPolicy.MAX_WAIT_MS).append("; not retried early")
    }
})

internal object ResponsesRetryPolicy {
    const val MAX_ATTEMPTS = 4
    const val MAX_WAIT_MS = 60_000L
    private val nonRetryableQuotaCodes = setOf(
        "insufficient_quota", "quota_exceeded", "billing_hard_limit_reached", "billing_not_active",
        "billing_required", "insufficient_funds", "spend_limit_reached", "spend_limit_exceeded",
        "usage_limit_reached", "monthly_limit_reached", "budget_exceeded",
        "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded",
    )

    fun transport(error: IOException, attempts: Int): OpenAiTransportException {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val tls = chain.firstOrNull { it is SSLException || it is CertificateException || it is CertPathValidatorException }
        val classified = tls ?: chain.firstOrNull { it is ProtocolException || it is UnknownServiceException }
            ?: chain.firstOrNull { it is SocketTimeoutException || it is UnknownHostException || it is ConnectException }
            ?: chain.firstOrNull { it is InterruptedIOException }
            ?: error
        val category = when (classified) {
            is SSLException, is CertificateException, is CertPathValidatorException -> TransportFailureCategory.TLS
            is ProtocolException -> TransportFailureCategory.PROTOCOL
            is UnknownServiceException -> TransportFailureCategory.CONFIGURATION
            is SocketTimeoutException -> TransportFailureCategory.TIMEOUT
            is UnknownHostException -> TransportFailureCategory.DNS
            is ConnectException -> TransportFailureCategory.CONNECT
            is InterruptedIOException -> TransportFailureCategory.INTERRUPTED_IO
            else -> TransportFailureCategory.NETWORK_IO
        }
        val name = classified.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,100}")) } ?: "IOException"
        return OpenAiTransportException(category, name, attempts)
    }

    fun retryable(error: IOException): Boolean = when (error) {
        is OpenAiTransportException -> error.category.retryable
        is OpenAiHttpException -> (error.status in setOf(408, 409, 429) || error.status in 500..599) &&
            !(error.status == 429 && (error.errorCode in nonRetryableQuotaCodes || error.errorType in nonRetryableQuotaCodes)) &&
            (error.retryAfterMillis == null || error.retryAfterMillis <= MAX_WAIT_MS)
        else -> false
    }

    fun waitMillis(error: IOException, failedAttempt: Int, jitter: Double): Long {
        (error as? OpenAiHttpException)?.retryAfterMillis?.let { return it }
        val base = (1000L shl (failedAttempt - 1).coerceIn(0, 2))
        val sample = if (jitter.isFinite()) jitter.coerceIn(0.0, 1.0) else 1.0
        return (base * (0.75 + sample * 0.25)).toLong()
    }

    fun retryAfterMillis(milliseconds: String?, standard: String?, nowMillis: Long): Long? {
        fun numeric(value: String?, multiplier: Double): Long? {
            val number = value?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() && it >= 0 } ?: return null
            return ceil(number * multiplier).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        }
        numeric(milliseconds, 1.0)?.let { return it }
        numeric(standard, 1000.0)?.let { return it }
        val value = standard?.trim() ?: return null
        val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val position = ParsePosition(0)
        val date = parser.parse(value, position)?.takeIf { position.index == value.length } ?: return null
        if (date.time <= nowMillis) return 0
        return if (nowMillis < 0 && date.time > Long.MAX_VALUE + nowMillis) Long.MAX_VALUE else date.time - nowMillis
    }
}
