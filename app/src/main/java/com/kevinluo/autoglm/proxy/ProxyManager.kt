package com.kevinluo.autoglm.proxy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ProxyState { STOPPED, STARTING, RUNNING, FAILED }
data class ProxyStatus(val state: ProxyState, val message: String)

/** App-owned local proxy. No global VPN, exported controller or external Clash app. */
class ProxyManager private constructor(private val context: Context) {
    companion object {
        const val PORT = 17890
        @Volatile private var instance: ProxyManager? = null
        fun getInstance(context: Context): ProxyManager = instance ?: synchronized(this) {
            instance ?: ProxyManager(context.applicationContext).also { instance = it }
        }
    }

    private val prefs by lazy {
        // Fail closed if encrypted storage cannot be opened; no plaintext fallback.
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "autoglm_proxy_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(ProxyStatus(ProxyState.STOPPED, "代理未启动"))
    val status: StateFlow<ProxyStatus> = mutableStatus
    @Volatile private var process: Process? = null
    @Volatile private var password: String? = null
    private val directory get() = File(context.noBackupFilesDir, "proxy")
    private val runtimeFile get() = File(directory, "runtime.yaml")

    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    val selectedNode: String? get() = prefs.getString("selected", null)
    fun subscriptionUrl(): String = prefs.getString("subscription_url", "") ?: ""
    fun nodes(): List<ProxyNode> = prefs.getString("nodes", null)?.let(ProxySubscription::parse) ?: emptyList()
    fun coreAvailable(): Boolean = File(context.applicationInfo.nativeLibraryDir, "libmihomo.so").canExecute()

    suspend fun refreshSubscription(urlText: String): List<ProxyNode> = withContext(Dispatchers.IO) {
        val url = urlText.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "请输入 HTTPS 订阅地址" }
        // Subscription uses direct network by default, or the already-running app proxy.
        val builder = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).followSslRedirects(false)
        if (activeProxy() != null) configureClient(builder) else builder.proxy(Proxy.NO_PROXY)
        val request = Request.Builder().url(url).header("User-Agent", "clash.meta/AutoGLM-Terra")
            .header("Accept", "application/yaml,text/yaml,text/plain,*/*").build()
        val subscriptionText = try {
            builder.build().newCall(request).await().use { response ->
                if (!response.isSuccessful) throw SubscriptionHttpException(response.code)
                val body = response.body ?: throw SubscriptionBodyException(tooLarge = false)
                if (body.contentLength() > ProxySubscription.MAX_BYTES) throw SubscriptionBodyException(tooLarge = true)
                val source = body.source()
                source.request(ProxySubscription.MAX_BYTES.toLong() + 1)
                if (source.buffer.size > ProxySubscription.MAX_BYTES) throw SubscriptionBodyException(tooLarge = true)
                source.readUtf8().also { if (it.isBlank()) throw SubscriptionBodyException(tooLarge = false) }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { throw IllegalStateException(ProxyFailureMessages.subscriptionNetwork(e)) }
        val imported = try {
            ProxySubscription.parse(subscriptionText)
        } catch (e: Exception) {
            throw IllegalStateException(ProxyFailureMessages.subscriptionParse(e))
        }
        lock.withLock {
            // Refreshing does not replace a live process; selected definitions apply on next connection.
            val selected = selectedNode?.takeIf { old -> imported.any { it.name == old } }
                ?: ProxySubscription.preferredNode(imported)
            check(prefs.edit().putString("subscription_url", url.toString())
                .putString("nodes", ProxySubscription.serialize(imported)).putString("selected", selected).commit()) {
                "无法保存加密订阅"
            }
        }
        imported
    }

    suspend fun selectNode(name: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(nodes().any { it.name == name }) { "所选节点不存在" }
            check(!enabled || mutableStatus.value.state != ProxyState.RUNNING) { "请先停止连接，再切换节点" }
            check(prefs.edit().putString("selected", name).commit()) { "无法保存节点" }
        }
    }

    suspend fun startSelected() = withContext(Dispatchers.IO) {
        lock.withLock {
            check(prefs.edit().putBoolean("enabled", true).commit()) { "无法保存代理状态" }
            startLocked()
        }
    }

    /** Called before building an API client: configured proxy failure must never become direct access. */
    suspend fun ensureStarted() = withContext(Dispatchers.IO) {
        lock.withLock { if (enabled && activeProxy() == null) startLocked() }
    }

    private suspend fun startLocked() {
        if (activeProxy() != null) return
        mutableStatus.value = ProxyStatus(ProxyState.STARTING, "正在启动内置代理…")
        var started: Process? = null
        try {
            val executable = File(context.applicationInfo.nativeLibraryDir, "libmihomo.so")
            check(executable.canExecute()) { "安装包未包含适合此设备的 Mihomo 核心" }
            val node = nodes().firstOrNull { it.name == selectedNode } ?: error("请先导入订阅并选择节点")
            // Do not connect to or terminate an unrelated service occupying our port.
            ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", PORT)) }
            check(directory.exists() || directory.mkdirs()) { "无法创建代理工作目录" }
            val secret = UUID.randomUUID().toString().replace("-", "")
            runtimeFile.writeText(ProxySubscription.runtimeConfig(node, PORT, secret), Charsets.UTF_8)
            started = ProcessBuilder(executable.absolutePath, "-d", directory.absolutePath, "-f", runtimeFile.absolutePath)
                .directory(directory).redirectErrorStream(true).start()
            val current = checkNotNull(started)
            process = current
            // Drain core output to prevent blocking. Never persist or expose credential-bearing core logs.
            scope.launch { runCatching { current.inputStream.use { input -> val buffer = ByteArray(4096); while (input.read(buffer) >= 0) { /* discard */ } } } }
            var ready = false
            repeat(100) {
                kotlin.coroutines.coroutineContext.ensureActive()
                check(isAlive(current)) { "代理核心未能启动，请检查节点协议和安装包" }
                if (!ready) {
                    ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 100) }; true }.getOrDefault(false)
                    if (!ready) delay(100)
                }
            }
            check(ready) { "代理启动超时" }
            password = secret
            mutableStatus.value = ProxyStatus(ProxyState.RUNNING, "已连接：${node.name}")
            scope.launch {
                current.waitFor()
                lock.withLock {
                    if (process === current) {
                        process = null; password = null
                        runtimeFile.delete()
                        mutableStatus.value = ProxyStatus(ProxyState.FAILED, "代理核心已退出；下次请求将尝试重新连接")
                    }
                }
            }
        } catch (e: Exception) {
            started?.destroy()
            process = null; password = null
            runtimeFile.delete()
            mutableStatus.value = ProxyStatus(ProxyState.FAILED, "代理启动失败，请检查核心、节点或端口占用")
            if (e is CancellationException) throw e
            throw IllegalStateException(mutableStatus.value.message)
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        lock.withLock {
            withContext(NonCancellable) {
                val current = process
                current?.destroy()
                if (current != null) {
                    repeat(20) { if (isAlive(current)) delay(100) }
                    check(!isAlive(current)) { "代理仍在退出，请稍后重试" }
                }
                process = null; password = null
                runtimeFile.delete()
                check(prefs.edit().putBoolean("enabled", false).commit()) { "无法保存代理状态" }
                mutableStatus.value = ProxyStatus(ProxyState.STOPPED, "代理已停止（API 将直接连接）")
            }
        }
    }

    fun activeProxy(): Proxy? = if (process?.let(::isAlive) == true && password != null)
        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", PORT)) else null

    fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (!enabled) return builder.proxy(Proxy.NO_PROXY)
        val proxy = activeProxy() ?: throw IllegalStateException("代理未就绪，请先连接或重试")
        val secret = password ?: throw IllegalStateException("代理未就绪")
        return builder.proxy(proxy).proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) null
            else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic("autoglm", secret)).build()
        }
    }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        ensureStarted()
        check(activeProxy() != null) { "请先启动代理" }
        val client = configureClient(OkHttpClient.Builder()).callTimeout(25, TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url("https://api.openai.com/v1/models").build()).await().use {
                if (it.code == 401) "OpenAI 官方 API 已连通（未发送 API Key）"
                else if (it.isSuccessful) "OpenAI 官方 API 已连通"
                else "已收到 OpenAI HTTP ${it.code}；请检查节点可用性"
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw IllegalStateException("OpenAI 连接失败，请尝试其他节点")
        }
    }

    private fun isAlive(value: Process): Boolean = try { value.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, resource, _ -> resource.close() }
                else response.close()
            }
        })
    }
}
