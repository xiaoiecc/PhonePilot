package com.kevinluo.autoglm.computer.protocol

import com.kevinluo.autoglm.model.ModelConfig
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ResponsesComputerClientTest {
    private val config = ModelConfig(
        baseUrl = "https://api.openai.com/v1",
        apiKey = "fixture-key",
        modelName = "gpt-6.1-terra",
        timeoutSeconds = 5,
    )
    private val input = ComputerProtocol.initialInput("Check the screen", "UklGR-example", 720, 1280)
    private val answer =
        """{"id":"r","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Done"}]}]}"""

    @Test
    fun `transport sends one official Responses request using injected client`() = runBlocking {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("https://api.openai.com/v1/responses", chain.request().url.toString())
            assertEquals("Bearer fixture-key", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val response = ResponsesComputerClient(config, http).request(input)
        assertEquals("Done", response.text)
        assertEquals(1, calls.get())
        http.dispatcher.executorService.shutdown()
    }

    @Test
    fun `http failure exposes status only and is not retried`() {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body("secret diagnostic".toResponseBody()).build()
        }.build()
        val failure = assertThrows(IOException::class.java) {
            runBlocking { ResponsesComputerClient(config, http).request(input) }
        }
        assertEquals("OpenAI API returned HTTP 401; category=http; attempts=1", failure.message)
        assertEquals(1, calls.get())
        http.dispatcher.executorService.shutdown()
    }

    @Test
    fun `http failure includes sanitized server message`() {
        val encoded = "a".repeat(300)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad request")
                .body("""{"error":{"type":"invalid_request_error","param":"input","message":"Invalid image input: fixture-key data:image/webp;base64,$encoded"}}""".toResponseBody()).build()
        }.build()
        val failure = assertThrows(OpenAiHttpException::class.java) {
            runBlocking { ResponsesComputerClient(config, http).request(input) }
        }
        assertTrue(failure.message!!.contains("Invalid image input"))
        assertTrue(failure.message!!.contains("[redacted]"))
        assertTrue(failure.message!!.contains("[encoded-data]"))
        assertTrue(!failure.message!!.contains(encoded))
        http.dispatcher.executorService.shutdown()
    }

    @Test
    fun `detailed diagnostics remove credential urls and fields and stay bounded`() {
        val diagnostic = "Invalid image input; " +
            "https://proxy-user:proxy-pass@example.com/subscribe?token=query-secret#fragment-secret " +
            "password=short-pass api_key=other-key token=short-token client_secret=client-value " +
            "\"access_token\":\"json-secret\" Bearer bearer-value sk-project-other-value " +
            "\nCookie: sid=cookie-secret; preference=value\n" + "Diagnostic detail. ".repeat(250)
        val payload = buildJsonObject {
            put("error", buildJsonObject { put("type", "invalid_request_error"); put("message", diagnostic) })
        }.toString()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad request")
                .body(payload.toResponseBody()).build()
        }.build()
        try {
            val failure = assertThrows(OpenAiHttpException::class.java) {
                runBlocking { ResponsesComputerClient(config, http).request(input) }
            }
            val message = requireNotNull(failure.errorMessage)
            assertTrue(message.startsWith("Invalid image input"))
            assertTrue(message.length <= 2048)
            assertTrue(message.endsWith("[truncated]"))
            listOf("proxy-user", "proxy-pass", "query-secret", "fragment-secret", "short-pass", "other-key",
                "short-token", "client-value", "json-secret", "bearer-value", "sk-project-other-value", "cookie-secret")
                .forEach { assertFalse(message.contains(it), it) }
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun `malformed success payload never exposes decoder source excerpts`() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"r","status":"completed","output":[{"type":"function_call","status":"completed","call_id":"c","name":"android_computer","arguments":"private user message"}]}""".toResponseBody()).build()
        }.build()
        try {
            val failure = assertThrows(ComputerProtocolException::class.java) {
                runBlocking { ResponsesComputerClient(config, http).request(input) }
            }
            assertEquals("OpenAI returned an invalid Responses payload", failure.message)
            assertFalse(failure.toString().contains("private user message"))
            // Coroutine stack recovery may wrap a safe copy of this exception as its cause.
            generateSequence<Throwable>(failure) { it.cause }.take(8).forEach {
                assertTrue(it is ComputerProtocolException)
                assertEquals("OpenAI returned an invalid Responses payload", it.message)
                assertFalse(it.toString().contains("private user message"))
            }
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun `coroutine cancellation cancels the active okhttp call`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captured = AtomicReference<Call>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            captured.set(chain.call())
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("Fixture cancelled")
        }.build()
        val client = ResponsesComputerClient(config, http)
        val job = launch(Dispatchers.Default) { client.request(input) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(captured.get().isCanceled())
        } finally {
            release.countDown()
            job.cancelAndJoin()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `nonofficial endpoint is rejected before any network request`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ResponsesComputerClient(config.copy(baseUrl = "https://example.com/v1")).request(input)
            }
        }
    }
}
