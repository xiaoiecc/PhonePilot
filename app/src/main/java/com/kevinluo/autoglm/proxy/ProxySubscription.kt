package com.kevinluo.autoglm.proxy

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Only node definitions cross the subscription trust boundary; global configuration never does. */
object ProxySubscription {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val supportedTypes = setOf("ss", "ssr", "vmess", "vless", "trojan", "hysteria", "hysteria2", "tuic", "socks5", "http", "anytls")
    private val blockedKeys = setOf("dialer-proxy", "interface-name", "routing-mark", "ip-version", "certificate", "private-key")

    fun parse(text: String): List<ProxyNode> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "订阅内容过大" }
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = 20
            codePointLimit = MAX_BYTES
        }
        val root = Yaml(SafeConstructor(options)).load<Any>(text) as? Map<*, *>
            ?: throw IllegalArgumentException("需要 Clash / Mihomo YAML 格式订阅")
        val rawNodes = root["proxies"] as? List<*> ?: throw IllegalArgumentException("订阅没有 proxies 节点列表，请使用 Clash 格式")
        require(rawNodes.size <= 2000) { "订阅节点过多" }
        val nodes = rawNodes.mapNotNull { raw ->
            val node = raw as? Map<*, *> ?: return@mapNotNull null
            val name = node["name"] as? String ?: return@mapNotNull null
            val type = node["type"] as? String ?: return@mapNotNull null
            if (type !in supportedTypes) return@mapNotNull null
            val server = node["server"] as? String ?: return@mapNotNull null
            val port = (node["port"] as? Number)?.toString()?.toIntOrNull() ?: (node["port"] as? String)?.toIntOrNull()
                ?: return@mapNotNull null
            if (name.isBlank() || name.length > 256 || server.isBlank() || port !in 1..65535) return@mapNotNull null
            if (node.keys.any { it !is String || it in blockedKeys }) return@mapNotNull null
            // External process plugins and file references are not supported inside this app.
            if (node["plugin"] != null && node["plugin"] !in setOf("obfs", "v2ray-plugin", "shadow-tls")) return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val fields = node as Map<String, Any?>
            if (!safeValue(fields)) return@mapNotNull null
            ProxyNode(name, type, LinkedHashMap(fields))
        }
        require(nodes.isNotEmpty()) { "订阅没有可用节点（支持常见 Clash 节点，不支持外部插件或文件引用）" }
        require(nodes.map { it.name }.distinct().size == nodes.size) { "订阅中存在重名节点，请修正订阅" }
        return nodes
    }

    private fun safeValue(value: Any?): Boolean = when (value) {
        null, is String, is Number, is Boolean -> true
        is List<*> -> value.size <= 2000 && value.all(::safeValue)
        is Map<*, *> -> value.keys.all { it is String && it !in blockedKeys && !it.endsWith("-file") } && value.values.all(::safeValue)
        else -> false
    }

    fun preferredNode(nodes: List<ProxyNode>): String =
        nodes.firstOrNull { it.name.contains("Seattle", ignoreCase = true) || it.name.contains("西雅图") }?.name
            ?: nodes.first().name

    fun runtimeConfig(node: ProxyNode, port: Int, password: String): String {
        require(port in 1024..65535)
        require(password.matches(Regex("[a-f0-9]{32}")))
        val selected = LinkedHashMap(node.fields).apply { put("name", "selected") }
        return dump(linkedMapOf(
            "mixed-port" to port,
            "bind-address" to "127.0.0.1",
            "allow-lan" to false,
            "authentication" to listOf("autoglm:$password"),
            "skip-auth-prefixes" to emptyList<String>(),
            "mode" to "rule",
            "log-level" to "silent",
            "ipv6" to false,
            "find-process-mode" to "off",
            "external-controller" to "",
            "geo-auto-update" to false,
            "profile" to mapOf("store-selected" to false, "store-fake-ip" to false),
            "dns" to mapOf("enable" to false),
            "tun" to mapOf("enable" to false),
            "proxies" to listOf(selected),
            "proxy-groups" to emptyList<Any>(),
            "rules" to listOf("MATCH,selected"),
        ))
    }

    fun serialize(nodes: List<ProxyNode>): String = dump(mapOf("proxies" to nodes.map { it.fields }))

    private fun dump(value: Any): String = Yaml(DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
    }).dump(value)
}

// Intentionally not a data class: default toString must never expose credentials.
class ProxyNode(val name: String, val type: String, internal val fields: Map<String, Any?>) {
    override fun toString(): String = name
}
