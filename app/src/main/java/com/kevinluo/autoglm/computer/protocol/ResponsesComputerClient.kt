package com.kevinluo.autoglm.computer.protocol

import com.kevinluo.autoglm.model.ModelConfig
import kotlinx.coroutines.CancellationException
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

class ResponsesComputerClient(private val config: ModelConfig, httpClient: OkHttpClient? = null) {
    private val client = (httpClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val activeCall = AtomicReference<Call?>(null)

    fun cancel() {
        activeCall.get()?.cancel()
    }

    suspend fun request(input: JsonArray, previousResponseId: String? = null): ComputerResponse {
        require(config.apiKey.isNotBlank() && config.apiKey != "EMPTY") { "Configure an OpenAI API key first" }
        val endpoint = (config.baseUrl.trimEnd('/') + "/responses").toHttpUrl()
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
        val call = client.newCall(request)
        check(activeCall.compareAndSet(null, call)) { "A computer request is already running" }
        return try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                if (call.isCanceled()) CancellationException("Computer request cancelled")
                                else IOException("Unable to reach the OpenAI API; check the network and proxy"),
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                if (!response.isSuccessful) {
                                    // Only machine-readable codes/parameter names are shown; never raw error text.
                                    val error = runCatching {
                                        (Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject)
                                            ?.get("error") as? JsonObject
                                    }.getOrNull()
                                    fun safeField(name: String): String? = (error?.get(name) as? JsonPrimitive)
                                        ?.content?.takeIf { it.matches(Regex("[a-zA-Z0-9_.\\[\\]-]{1,80}")) && it != "null" }
                                    val detail = listOfNotNull(safeField("code"), safeField("param")).joinToString(", ")
                                    throw IOException("OpenAI API returned HTTP ${response.code}" + if (detail.isBlank()) "" else " ($detail)")
                                }
                                val responseBody = response.body?.string()
                                    ?: throw ComputerProtocolException("Empty OpenAI response")
                                val result = ComputerProtocol.parseResponse(responseBody)
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                })
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }
}
