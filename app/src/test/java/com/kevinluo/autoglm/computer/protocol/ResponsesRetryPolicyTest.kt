package com.kevinluo.autoglm.computer.protocol

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResponsesRetryPolicyTest {
    private fun http(status: Int, code: String? = null, type: String? = null, retryAfter: Long? = null) =
        OpenAiHttpException(status, code, type, null, "req_safe", retryAfter, 1)

    @Test fun `status and machine-readable quota distinctions govern retry`() {
        listOf(408, 409, 429, 500, 503, 599).forEach { assertTrue(ResponsesRetryPolicy.retryable(http(it))) }
        listOf(400, 401, 403, 404, 422, 302).forEach { assertFalse(ResponsesRetryPolicy.retryable(http(it))) }
        listOf("insufficient_quota", "billing_hard_limit_reached", "spend_limit_exceeded",
            "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded").forEach { code ->
            assertFalse(ResponsesRetryPolicy.retryable(http(429, code)))
            assertFalse(ResponsesRetryPolicy.retryable(http(429, type = code)))
        }
        assertTrue(ResponsesRetryPolicy.retryable(http(429, "rate_limit_exceeded")))
    }

    @Test fun `low-level classes remain distinct without leaking original diagnostic text or cause`() {
        listOf(
            UnknownHostException("proxy://user:secret@host") to TransportFailureCategory.DNS,
            ConnectException("secret address") to TransportFailureCategory.CONNECT,
            SocketTimeoutException("secret") to TransportFailureCategory.TIMEOUT,
            InterruptedIOException("secret") to TransportFailureCategory.INTERRUPTED_IO,
            IOException("secret") to TransportFailureCategory.NETWORK_IO,
            ProtocolException("secret") to TransportFailureCategory.PROTOCOL,
            UnknownServiceException("secret") to TransportFailureCategory.CONFIGURATION,
            SSLPeerUnverifiedException("secret") to TransportFailureCategory.TLS,
            SSLHandshakeException("secret") to TransportFailureCategory.TLS,
            IOException("secret", CertificateException("secret")) to TransportFailureCategory.TLS,
        ).forEach { (original, expected) ->
            val safe = ResponsesRetryPolicy.transport(original, 4)
            assertEquals(expected, safe.category)
            assertEquals(4, safe.attempts)
            assertEquals(expected.retryable, ResponsesRetryPolicy.retryable(safe))
            assertFalse(safe.message!!.contains("secret"))
            assertNull(safe.cause)
            assertTrue(safe.message!!.contains("class="))
        }
    }

    @Test fun `retry-after milliseconds seconds and date are honored without early rounding`() {
        assertEquals(1501L, ResponsesRetryPolicy.retryAfterMillis("1500.1", "9", 0))
        assertEquals(1501L, ResponsesRetryPolicy.retryAfterMillis(null, "1.5001", 0))
        assertEquals(2000L, ResponsesRetryPolicy.retryAfterMillis(null, "Thu, 01 Jan 1970 00:00:02 GMT", 0))
        assertEquals(0L, ResponsesRetryPolicy.retryAfterMillis(null, "Thu, 01 Jan 1970 00:00:02 GMT", 3000))
        assertEquals(Long.MAX_VALUE, ResponsesRetryPolicy.retryAfterMillis(null, "1e9999", 0))
        assertNull(ResponsesRetryPolicy.retryAfterMillis("NaN", "-1", 0))
        assertFalse(ResponsesRetryPolicy.retryable(http(503, retryAfter = 60_001)))
        assertTrue(http(503, retryAfter = 60_001).message!!.contains("not retried early"))
    }

    @Test fun `fallback backoff is bounded exponential with deterministic jitter`() {
        val error = ResponsesRetryPolicy.transport(IOException(), 1)
        assertEquals(listOf(1000L, 2000L, 4000L), (1..3).map { ResponsesRetryPolicy.waitMillis(error, it, 1.0) })
        assertEquals(750L, ResponsesRetryPolicy.waitMillis(error, 1, 0.0))
        assertEquals(4000L, ResponsesRetryPolicy.waitMillis(error, 9, Double.NaN))
        assertEquals(5500L, ResponsesRetryPolicy.waitMillis(http(429, retryAfter = 5500), 1, 0.0))
    }
}
