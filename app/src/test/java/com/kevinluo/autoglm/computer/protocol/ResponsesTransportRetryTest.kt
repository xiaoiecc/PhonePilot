package com.kevinluo.autoglm.computer.protocol

import com.kevinluo.autoglm.model.ModelConfig
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResponsesTransportRetryTest {
    private val config = ModelConfig(baseUrl = "https://api.openai.com/v1", apiKey = "fixture-key", modelName = "gpt-6.1-terra", timeoutSeconds = 5)
    private val input = ComputerProtocol.initialInput("Keep exactly this input", "fixture-image", 720, 1280)
    private val answer = """{"id":"r","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Done"}]}]}"""
    private fun reply(chain: Interceptor.Chain, code: Int = 200, body: String = answer, headers: Map<String, String> = emptyMap()): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(body.toResponseBody()).apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    @Test fun `transient failures retry identical body and cursor and return one final response`() = runBlocking {
        val calls = AtomicInteger()
        val bodies = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            synchronized(bodies) { bodies += buffer.readUtf8() }
            when (calls.incrementAndGet()) {
                1 -> throw IOException("secret proxy credentials")
                2 -> reply(chain, 503, "private upstream diagnostic")
                else -> reply(chain)
            }
        }.build()
        try {
            val result = ResponsesComputerClient(config, http, retryDelay = { waits += it }, retryJitter = { 1.0 }).request(input, "previous-cursor")
            assertEquals("Done", result.text)
            assertEquals(3, calls.get())
            assertEquals(1, bodies.distinct().size)
            assertTrue(bodies.first().contains("\"previous_response_id\":\"previous-cursor\""))
            assertEquals(listOf(1000L, 2000L), waits)
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test fun `transport exhaustion is four attempts with safe class category and counts`() {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw IOException("https://proxy-user:proxy-password@host fixture-key") }.build()
        try {
            val failure = assertThrows(OpenAiTransportException::class.java) {
                runBlocking { ResponsesComputerClient(config, http, retryDelay = {}).request(input) }
            }
            assertEquals(4, calls.get()); assertEquals(4, failure.attempts)
            assertEquals(TransportFailureCategory.NETWORK_IO, failure.category)
            assertEquals("IOException", failure.exceptionClass)
            assertFalse(failure.toString().contains("proxy-password")); assertFalse(failure.toString().contains("fixture-key"))
            assertNull(failure.cause)
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test fun `auth quota parse and certificate faults are not retried and diagnostics stay sanitized`() {
        listOf("auth", "quota", "parse", "certificate").forEach { scenario ->
            val calls = AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                when (scenario) {
                    "auth" -> reply(chain, 403, "fixture-key raw secret headers")
                    "quota" -> reply(chain, 429, """{"error":{"code":"insufficient_quota","type":"insufficient_quota","message":"Quota exceeded; token=private-token fixture-key"}}""", mapOf("x-request-id" to "req_safe"))
                    "parse" -> reply(chain, body = "fixture-key invalid JSON")
                    else -> throw SSLPeerUnverifiedException("fixture-key certificate host details")
                }
            }.build()
            try {
                val failure = assertThrows(Exception::class.java) { runBlocking { ResponsesComputerClient(config, http, retryDelay = {}).request(input) } }
                assertEquals(1, calls.get(), scenario)
                assertFalse(failure.toString().contains("fixture-key"), scenario)
                assertFalse(failure.toString().contains("secret body"), scenario)
                assertFalse(failure.toString().contains("private-token"), scenario)
                if (scenario == "quota") {
                    assertTrue(failure.toString().contains("Quota exceeded"), scenario)
                    assertEquals("insufficient_quota", (failure as OpenAiHttpException).errorCode)
                    assertEquals("req_safe", failure.requestId)
                }
            } finally { http.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `rate limit retry-after is honored and an excessive server delay stops explicitly`() = runBlocking {
        val calls = AtomicInteger(); val waits = mutableListOf<Long>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.incrementAndGet() == 1) reply(chain, 429, """{"error":{"code":"rate_limit_exceeded"}}""", mapOf("Retry-After" to "2", "retry-after-ms" to "1250")) else reply(chain)
        }.build()
        try {
            assertEquals("Done", ResponsesComputerClient(config, http, retryDelay = { waits += it }).request(input).text)
            assertEquals(listOf(1250L), waits); assertEquals(2, calls.get())
        } finally { http.dispatcher.executorService.shutdown() }
        val excessive = AtomicInteger()
        val longWait = OkHttpClient.Builder().addInterceptor { chain -> excessive.incrementAndGet(); reply(chain, 503, "secret", mapOf("Retry-After" to "61")) }.build()
        try {
            val failure = assertThrows(OpenAiHttpException::class.java) { runBlocking { ResponsesComputerClient(config, longWait, retryDelay = { fail("Should not wait or retry early") }).request(input) } }
            assertEquals(1, excessive.get()); assertEquals(61_000L, failure.retryAfterMillis)
            assertTrue(failure.message!!.contains("not retried early"))
        } finally { longWait.dispatcher.executorService.shutdown() }
    }

    @Test fun `call timeout cancellation without explicit operation cancel remains a retryable IO failure`() {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); chain.call().cancel(); throw InterruptedIOException("timeout fixture secret")
        }.build()
        try {
            val failure = assertThrows(OpenAiTransportException::class.java) { runBlocking { ResponsesComputerClient(config, http, retryDelay = {}).request(input) } }
            assertEquals(4, calls.get()); assertEquals(TransportFailureCategory.INTERRUPTED_IO, failure.category)
            assertEquals("InterruptedIOException", failure.exceptionClass)
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test fun `explicit client cancel interrupts active call without cancelling caller and never retries`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val captured = AtomicReference<Call>(); val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); captured.set(chain.call()); entered.countDown()
            release.await(5, TimeUnit.SECONDS); throw IOException("private fixture")
        }.build()
        val client = ResponsesComputerClient(config, http, retryDelay = {})
        val pending = async(Dispatchers.Default) { client.request(input) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            client.cancel()
            assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
            assertTrue(isActive); assertTrue(captured.get().isCanceled()); assertEquals(1, calls.get())
        } finally { release.countDown(); pending.cancelAndJoin(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun `backoff owns single flight and explicit cancel interrupts delay before any next request`() = runBlocking {
        val calls = AtomicInteger(); val waiting = CompletableDeferred<Unit>(); val delayCancelled = CompletableDeferred<Unit>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.incrementAndGet() == 1) throw IOException("transient") else reply(chain)
        }.build()
        val client = ResponsesComputerClient(config, http, retryDelay = {
            waiting.complete(Unit)
            try { awaitCancellation() } finally { delayCancelled.complete(Unit) }
        })
        val pending = async { client.request(input) }
        try {
            waiting.await()
            assertThrows(IllegalStateException::class.java) { runBlocking { client.request(input) } }
            client.cancel()
            assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
            assertTrue(delayCancelled.isCompleted); assertTrue(isActive); assertEquals(1, calls.get())
            assertEquals("Done", client.request(input).text)
            assertEquals(2, calls.get())
        } finally { pending.cancelAndJoin(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun `caller cancellation also interrupts backoff and releases operation ownership`() = runBlocking {
        val calls = AtomicInteger(); val waiting = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val http = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw IOException("transient") }.build()
        val client = ResponsesComputerClient(config, http, retryDelay = {
            waiting.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        val pending = async { client.request(input) }
        try {
            waiting.await()
            pending.cancelAndJoin()
            assertTrue(cancelled.isCompleted); assertEquals(1, calls.get())
        } finally { pending.cancelAndJoin(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun `machine fields and request IDs containing credentials are omitted from diagnostics`() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            reply(chain, 401, """{"error":{"code":"fixture-key","type":"invalid_request_error","param":"https://user:password@proxy","message":"fixture-key"}}""",
                mapOf("x-request-id" to "req_fixture-key"))
        }.build()
        try {
            val failure = assertThrows(OpenAiHttpException::class.java) { runBlocking { ResponsesComputerClient(config, http).request(input) } }
            assertEquals("invalid_request_error", failure.errorType)
            assertNull(failure.errorCode); assertNull(failure.errorParam); assertNull(failure.requestId)
            assertFalse(failure.toString().contains("fixture-key")); assertFalse(failure.toString().contains("password"))
            val invalidKey = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { ResponsesComputerClient(config.copy(apiKey = "secret-key\ninvalid"), http).request(input) }
            }
            assertFalse(invalidKey.toString().contains("secret-key"))
        } finally { http.dispatcher.executorService.shutdown() }
    }
}
