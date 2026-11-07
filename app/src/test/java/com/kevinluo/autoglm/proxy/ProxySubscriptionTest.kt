package com.kevinluo.autoglm.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.yaml.snakeyaml.Yaml

class ProxySubscriptionTest : StringSpec({
    val subscription = """
        mixed-port: 8888
        external-controller: 0.0.0.0:9090
        tun: {enable: true}
        rules: [MATCH,DIRECT]
        proxies:
          - {name: Seattle 01, type: trojan, server: example.com, port: 443, password: secret}
          - {name: Japan, type: ss, server: example.org, port: 443, cipher: aes-128-gcm, password: secret}
    """.trimIndent()

    "preferred node chooses Seattle and encrypted cache can round trip" {
        val nodes = ProxySubscription.parse(subscription)
        ProxySubscription.preferredNode(nodes) shouldBe "Seattle 01"
        ProxySubscription.parse(ProxySubscription.serialize(nodes)).map { it.name } shouldBe nodes.map { it.name }
        nodes.first().toString() shouldNotContain "secret"
    }

    "emoji at the YAML reader buffer boundary parses without losing Unicode" {
        // SnakeYAML 2.3 filled all 1024 chars, then tried to read a low surrogate out of bounds.
        val prefix = "title: \""
        val boundaryYaml = prefix + "x".repeat(1023 - prefix.length) + "😀\"\n" +
            "proxies: [{name: '🇺🇸 西雅图 Seattle', type: trojan, server: example.invalid, port: 443, password: test}]"
        val nodes = ProxySubscription.parse(boundaryYaml)
        nodes.single().name shouldBe "🇺🇸 西雅图 Seattle"
        ProxySubscription.parse(ProxySubscription.serialize(nodes)).single().name shouldBe nodes.single().name
    }

    "subscription global rules and listeners never enter runtime config" {
        val config = ProxySubscription.runtimeConfig(ProxySubscription.parse(subscription).first(), 17890, "a".repeat(32))
        val data = Yaml().load<Map<String, Any>>(config)
        data["mixed-port"] shouldBe 17890
        data["bind-address"] shouldBe "127.0.0.1"
        data["external-controller"] shouldBe ""
        data["allow-lan"] shouldBe false
        data["tun"] shouldBe mapOf("enable" to false)
        data["rules"] shouldBe listOf("MATCH,selected")
        data["authentication"] shouldBe listOf("autoglm:${"a".repeat(32)}")
        data["skip-auth-prefixes"] shouldBe emptyList<String>()
        (data["proxies"] as List<*>).size shouldBe 1
    }

    "unsafe YAML object constructors and duplicate names are rejected" {
        shouldThrow<Exception> { ProxySubscription.parse("!!javax.script.ScriptEngineManager []") }
        shouldThrow<IllegalArgumentException> { ProxySubscription.parse(subscription.replace("name: Japan", "name: Seattle 01")) }
    }

    "external file and chained proxy directives are rejected" {
        shouldThrow<IllegalArgumentException> {
            ProxySubscription.parse("proxies: [{name: test, type: trojan, server: example.com, port: 443, password: secret, dialer-proxy: other}]")
        }
        shouldThrow<IllegalArgumentException> {
            ProxySubscription.parse("proxies: [{name: test, type: trojan, server: example.com, port: 443, password: secret, tls: {certificate-file: /secret}}]")
        }
    }

    "provider-only and oversized inputs are rejected" {
        shouldThrow<IllegalArgumentException> { ProxySubscription.parse("proxy-providers: {}") }
        shouldThrow<IllegalArgumentException> { ProxySubscription.parse("a".repeat(ProxySubscription.MAX_BYTES + 1)) }
        shouldThrow<IllegalArgumentException> { ProxySubscription.parse("proxies: [{name: bad, type: http, server: example.com, port: 4294967739}]") }
    }
})
