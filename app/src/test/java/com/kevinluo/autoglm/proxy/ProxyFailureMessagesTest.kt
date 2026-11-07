package com.kevinluo.autoglm.proxy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ProxyFailureMessagesTest : StringSpec({
    val secret = "https://example.invalid/subscribe?token=must-not-appear"

    "HTTP status survives sanitization" {
        ProxyFailureMessages.subscriptionNetwork(SubscriptionHttpException(403)) shouldContain "HTTP 403"
    }
    "DNS TLS and timeout retain actionable categories without exception text" {
        listOf(
            UnknownHostException(secret) to "DNS",
            SSLHandshakeException(secret) to "TLS",
            SocketTimeoutException(secret) to "超时",
        ).forEach { (error, category) ->
            val message = ProxyFailureMessages.subscriptionNetwork(error)
            message shouldContain category
            message shouldNotContain secret
            message shouldNotContain "must-not-appear"
        }
    }
    "parser and generic IO exceptions never expose subscription content" {
        ProxyFailureMessages.subscriptionNetwork(IOException(secret)) shouldNotContain secret
        ProxyFailureMessages.subscriptionParse(IllegalArgumentException(secret)) shouldNotContain secret
        ProxyFailureMessages.subscriptionParse(IllegalArgumentException(secret)) shouldContain "订阅解析失败"
    }
})
