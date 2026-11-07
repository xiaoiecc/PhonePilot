package com.kevinluo.autoglm.proxy

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal class SubscriptionHttpException(val statusCode: Int) : IOException()
internal class SubscriptionBodyException(val tooLarge: Boolean) : IOException()

/** Describe only trusted categories and numeric status, never arbitrary exception text/URLs. */
internal object ProxyFailureMessages {
    fun subscriptionNetwork(error: Exception): String = when (error) {
        is SubscriptionHttpException -> "订阅服务返回 HTTP ${error.statusCode}。请检查订阅有效期、访问限制或重定向。"
        is SubscriptionBodyException -> if (error.tooLarge) "订阅内容超过 2 MiB 限制。" else "订阅服务返回空内容。"
        is UnknownHostException -> "订阅获取失败：DNS 无法解析服务器（UnknownHostException）。请检查手机网络与 DNS。"
        is SSLHandshakeException -> "订阅获取失败：TLS 握手失败（SSLHandshakeException）。请检查手机日期、证书与网络。"
        is SSLPeerUnverifiedException -> "订阅获取失败：TLS 证书校验失败（SSLPeerUnverifiedException）。"
        is SSLException -> "订阅获取失败：TLS 连接中断（SSLException）。请检查手机网络。"
        is SocketTimeoutException -> "订阅获取失败：网络超时（SocketTimeoutException）。请检查手机网络后重试。"
        is InterruptedIOException -> "订阅获取失败：请求超时或中断（InterruptedIOException）。"
        is ConnectException -> "订阅获取失败：无法连接服务器（ConnectException）。请检查手机网络。"
        is IOException -> "订阅获取失败：网络读取失败（IOException）。请检查手机网络。"
        else -> "订阅获取失败：客户端内部错误（${error.javaClass.simpleName}）。"
    }

    fun subscriptionParse(error: Exception): String =
        "订阅解析失败（${error.javaClass.simpleName}）：需要 Clash YAML 且包含支持的 proxies 节点；不支持 YAML 集合别名或外部文件引用。"
}
