package com.kevinluo.autoglm.proxy

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kevinluo.autoglm.BaseActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Self-contained proxy settings; deliberately keeps subscription secrets off screenshots/logs. */
class ProxySettingsActivity : BaseActivity() {
    private lateinit var manager: ProxyManager
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var nodeSpinner: Spinner
    private val buttons = mutableListOf<Button>()
    private var nodeNames = emptyList<String>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try { buildScreen() } catch (_: Exception) {
            setContentView(TextView(this).apply {
                text = "无法打开代理加密存储。请检查设备安全存储，或重新安装后重新导入订阅。"
                setPadding(32, 96, 32, 32)
                textSize = 18f
            })
        }
    }

    private fun buildScreen() {
        manager = ProxyManager.getInstance(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        setupEdgeToEdgeInsets(root)
        root.addView(TextView(this).apply { text = "应用内代理"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = "导入 Clash / Mihomo 订阅，选择节点后连接。只代理本应用的 OpenAI 请求，不改变其他应用的网络。"
            textSize = 15f
            setPadding(0, 16, 0, 20)
        })
        statusView = TextView(this).apply { textSize = 17f }
        root.addView(statusView)
        root.addView(TextView(this).apply {
            text = if (manager.coreAvailable()) "内置 Mihomo 核心已就绪" else "此安装包缺少设备对应的代理核心"
            setPadding(0, 12, 0, 12)
        })
        val url = EditText(this).apply {
            hint = "HTTPS 订阅地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(manager.subscriptionUrl())
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(url)
        addButton(root, "获取 / 更新订阅") {
            val imported = manager.refreshSubscription(url.text.toString())
            updateNodes(imported)
            resultView.text = "已导入 ${imported.size} 个节点。更新后的配置将在下次连接时使用。"
        }
        root.addView(TextView(this).apply { text = "选择节点"; setPadding(0, 20, 0, 8) })
        nodeSpinner = Spinner(this)
        root.addView(nodeSpinner)
        updateNodes(manager.nodes())
        addButton(root, "连接所选节点") {
            val selected = nodeNames.getOrNull(nodeSpinner.selectedItemPosition)
                ?: throw IllegalStateException("请先导入订阅")
            if (manager.status.value.state == ProxyState.RUNNING) manager.stop()
            manager.selectNode(selected)
            manager.startSelected()
            resultView.text = "代理已启动，可点击下方按钮检查 OpenAI 连通性。"
        }
        addButton(root, "检查 OpenAI 连通性") { resultView.text = manager.testConnection() }
        addButton(root, "停止代理，恢复直连") { manager.stop(); resultView.text = "已停止内置代理" }
        resultView = TextView(this).apply { setPadding(0, 16, 0, 16); textSize = 15f }
        root.addView(resultView)
        root.addView(TextView(this).apply {
            text = "订阅与节点凭据保存在应用加密存储中。连接会在下次 API 请求时自动恢复；退出或系统结束应用后不保证保持连接。Mihomo 使用 GPL-3.0 许可证。"
            textSize = 12f
        })
        addButton(root, "返回") { finish() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.status.collect { statusView.text = it.message }
            }
        }
    }

    private fun updateNodes(nodes: List<ProxyNode>) {
        nodeNames = nodes.map { it.name }
        nodeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nodeNames)
        val index = nodeNames.indexOf(manager.selectedNode)
        if (index >= 0) nodeSpinner.setSelection(index)
    }

    private fun addButton(parent: LinearLayout, title: String, action: suspend () -> Unit) {
        val button = Button(this).apply {
            text = title
            setOnClickListener {
                if (!busy) lifecycleScope.launch {
                    busy = true
                    buttons.forEach { it.isEnabled = false }
                    try { action() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { resultView.text = e.message ?: "操作失败，请检查配置" }
                    finally { busy = false; buttons.forEach { it.isEnabled = true } }
                }
            }
        }
        buttons += button
        parent.addView(button)
    }
}
