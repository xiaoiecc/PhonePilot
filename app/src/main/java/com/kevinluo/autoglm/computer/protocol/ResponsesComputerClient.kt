package com.kevinluo.autoglm.computer.protocol

import com.kevinluo.autoglm.model.ModelConfig
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

class ResponsesComputerClient(
    private val config: ModelConfig,
    httpClient: OkHttpClient? = null,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val retryJitter: () -> Double = { Random.nextDouble() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val client = (httpClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    /** One network operation owns all attempts and backoff, independent of the caller's task Job. */
    private class RequestOperation {
        private val lock = Any()
        val cancelled = CompletableDeferred<Unit>()
        private var activeCall: Call? = null
        fun checkActive() { if (cancelled.isCompleted) throw CancellationException("Computer request cancelled") }
        fun attach(call: Call) = synchronized(lock) { checkActive(); activeCall = call }
        fun detach(call: Call) = synchronized(lock) { if (activeCall === call) activeCall = null }
        fun cancel() = synchronized(lock) { cancelled.complete(Unit); activeCall?.cancel(); Unit }
    }
    private val activeOperation = AtomicReference<RequestOperation?>(null)

    fun cancel() {
        activeOperation.get()?.cancel()
    }

    suspend fun request(input: JsonArray, previousResponseId: String? = null): ComputerResponse {
        require(config.apiKey.isNotBlank() && config.apiKey != "EMPTY") { "Configure an OpenAI API key first" }
        require(config.apiKey.all { it.code in 33..126 }) { "OpenAI API key contains invalid characters" }
        val endpoint = try { (config.baseUrl.trimEnd('/') + "/responses").toHttpUrl() }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Computer use requires the official https://api.openai.com/v1 endpoint") }
        require(endpoint.isHttps && endpoint.host == "api.openai.com" && endpoint.encodedPath == "/v1/responses" &&
            endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.query == null && endpoint.fragment == null
        ) { "Computer use requires the official https://api.openai.com/v1 endpoint" }
        val body = ComputerProtocol.requestBody(config, input, previousResponseId)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val operation = RequestOperation()
        check(activeOperation.compareAndSet(null, operation)) { "A computer request is already running" }
        return try {
            var attempt = 1
            while (true) {
                operation.checkActive()
                try {
                    val result = executeAttempt(request, operation, attempt)
                    operation.checkActive()
                    return result
                } catch (error: IOException) {
                    operation.checkActive()
                    if (attempt >= ResponsesRetryPolicy.MAX_ATTEMPTS || !ResponsesRetryPolicy.retryable(error)) throw error
                    val wait = ResponsesRetryPolicy.waitMillis(error, attempt, retryJitter())
                    val category = when (error) {
                        is OpenAiHttpException -> "http_${error.status}"
                        is OpenAiTransportException -> error.category.name.lowercase()
                        else -> "unknown"
                    }
                    Logger.d("ResponsesComputerClient", "Retry $attempt/3; category=$category; wait_ms=$wait")
                    awaitBackoff(operation, wait)
                    attempt++
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        } finally {
            activeOperation.compareAndSet(operation, null)
        }
    }

    private suspend fun awaitBackoff(operation: RequestOperation, waitMillis: Long) = coroutineScope {
        operation.checkActive()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { retryDelay(waitMillis) }
        try {
            select<Unit> {
                operation.cancelled.onAwait { throw CancellationException("Computer request cancelled") }
                waiting.onAwait { operation.checkActive() }
            }
        } finally { waiting.cancel() }
    }

    private suspend fun executeAttempt(request: Request, operation: RequestOperation, attempt: Int): ComputerResponse {
        operation.checkActive()
        val call = client.newCall(request)
        operation.attach(call)
        var cancelRegistration: kotlinx.coroutines.DisposableHandle? = null
        return try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                cancelRegistration = operation.cancelled.invokeOnCompletion {
                    call.cancel()
                    continuation.cancel(CancellationException("Computer request cancelled"))
                }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                if (operation.cancelled.isCompleted) CancellationException("Computer request cancelled")
                                else ResponsesRetryPolicy.transport(e, attempt),
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                if (!response.isSuccessful) {
                                    // Only machine-readable codes/parameter names are shown; never raw error text.
                                    val error = runCatching {
                                        (Json.parseToJsonElement(response.peekBody(8192).string()) as? JsonObject)
                                            ?.get("error") as? JsonObject
                                    }.getOrNull()
                                    fun safeField(name: String): String? = (error?.get(name) as? JsonPrimitive)
                                        ?.content?.takeIf { safeToken(it, "[a-zA-Z0-9_.\\[\\]-]{1,80}") && it != "null" }
                                    val requestId = response.header("x-request-id")?.takeIf { safeToken(it, "req_[a-zA-Z0-9_-]{1,100}") }
                                    throw OpenAiHttpException(response.code, safeField("code"), safeField("type"), safeField("param"), requestId,
                                        ResponsesRetryPolicy.retryAfterMillis(response.header("retry-after-ms"), response.header("retry-after"), nowMillis()), attempt)
                                }
                                val responseBody = response.body?.string()
                                    ?: throw ComputerProtocolException("Empty OpenAI response")
                                val result = try { ComputerProtocol.parseResponse(responseBody) }
                                    catch (_: Exception) { throw ComputerProtocolException("OpenAI returned an invalid Responses payload") }
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Exception) {
                                val safe = if (error is IOException && error !is OpenAiHttpException && error !is OpenAiTransportException)
                                    ResponsesRetryPolicy.transport(error, attempt) else error
                                if (continuation.isActive) continuation.resumeWithException(safe)
                            }
                        }
                    }
                })
            }
        } finally {
            cancelRegistration?.dispose()
            operation.detach(call)
        }
    }

    private fun safeToken(value: String, pattern: String): Boolean =
        value.matches(Regex(pattern)) && !value.contains(config.apiKey)
}
