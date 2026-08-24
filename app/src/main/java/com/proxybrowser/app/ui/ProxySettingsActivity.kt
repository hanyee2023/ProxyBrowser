package com.proxybrowser.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.proxybrowser.app.R
import com.proxybrowser.app.core.V2RayManager
import com.proxybrowser.app.data.NodeParser
import com.proxybrowser.app.data.NodeStore
import com.proxybrowser.app.data.ProxyNode
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ProxySettingsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var loading: ProgressBar
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)
    private val expanded = mutableMapOf<String, Boolean>() // 订阅URL -> 是否展开

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this

        val back = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(R.drawable.bg_btn_ghost)
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            val s = dp(36)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { finish() }
        }
        val title = TextView(ctx).apply {
            text = "代理设置"
            textSize = 18f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 10, 16, 10)
            addView(back)
            addView(title)
        }

        // 操作栏
        val actionBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 8)
        }
        actionBar.addView(actionBtn("＋ 订阅") { addSubscription() })
        actionBar.addView(actionBtn("测速") { testAll() })
        actionBar.addView(actionBtn("排序") { sortByLatency() })
        actionBar.addView(actionBtn("删除") { deleteMenu() })

        loading = ProgressBar(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val scroll = ScrollView(ctx)
        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 16)
        }
        scroll.addView(content)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            addView(topBar)
            addView(View(ctx).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
            addView(actionBar)
            addView(loading)
            addView(scroll)
        }
        setContentView(root)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    // ============ Build grouped list ============
    private fun rebuild() {
        content.removeAllViews()
        val nodes = NodeStore.load(this)
        val groups = nodes.groupBy { it.subscription.ifEmpty { "" } }
        if (groups.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "还没有节点。点击「＋ 订阅」添加订阅链接，或粘贴 vless/vmess/trojan 节点。"
                setTextColor(getColor(R.color.text_secondary))
                setPadding(16, 32, 16, 32)
            })
            return
        }
        val subs = NodeStore.loadSubs(this)
        // 先按订阅顺序，再放未分组
        val order = subs.toMutableList()
        if (groups.containsKey("")) order.add("")
        for (key in order) {
            val list = groups[key] ?: continue
            content.addView(groupBlock(key, list))
        }
    }

    private fun groupBlock(key: String, nodes: List<ProxyNode>): View {
        val ctx = this
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            val m = dp(8)
            setPadding(m, m, m, m)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(10))
            layoutParams = lp
        }
        // 头部
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 6, 4, 6)
        }
        val isUngrouped = key.isEmpty()
        val name = TextView(ctx).apply {
            text = if (isUngrouped) "未分组（手动添加）" else key
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val count = TextView(ctx).apply {
            text = "${nodes.size}"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(8), 0, dp(4), 0)
        }
        val arrow = TextView(ctx).apply {
            text = if (expanded[key] != false) "▾" else "▸"
            textSize = 16f
            setTextColor(getColor(R.color.text_secondary))
        }
        header.addView(name)
        header.addView(count)
        header.addView(arrow)
        header.setOnClickListener {
            expanded[key] = expanded[key] == false
            rebuild()
        }
        wrap.addView(header)

        if (expanded[key] != false) {
            for (n in nodes) wrap.addView(nodeRow(n))
        }
        return wrap
    }

    private fun nodeRow(n: ProxyNode): View {
        val ctx = this
        val active = NodeStore.getActive(ctx)
        val isActive = active != null && NodeStore.keyOf(active) == NodeStore.keyOf(n)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 12, 8, 12)
            setBackgroundResource(R.drawable.bg_btn_ghost)
        }
        val icon = ImageView(ctx).apply {
            setImageResource(if (isActive) R.drawable.ic_node_active else R.drawable.ic_node_idle)
            val s = dp(14)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(0, 0, dp(10), 0) }
        }
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nName = TextView(ctx).apply {
            text = n.name
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val nDetail = TextView(ctx).apply {
            text = "${n.type.name.lowercase()}  ${n.address}:${n.port}   ·   ${latencyText(n)}"
            textSize = 12f
            setTextColor(latencyColor(n))
            setPadding(0, 2, 0, 0)
        }
        info.addView(nName)
        info.addView(nDetail)

        val connect = Button(ctx).apply {
            text = if (isActive) "已连接" else "连接"
            textSize = 13f
            isEnabled = !isActive
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundColor(if (isActive) getColor(R.color.green) else getColor(R.color.accent))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), 0, 0, 0)
            }
            setOnClickListener { connectTo(n) }
        }

        row.addView(icon)
        row.addView(info)
        row.addView(connect)
        return row
    }

    private fun latencyText(n: ProxyNode): String = when {
        n.latencyMs > 0 -> "${n.latencyMs} ms"
        else -> "未测速"
    }
    private fun latencyColor(n: ProxyNode): Int = when {
        n.latencyMs <= 0 -> Color.parseColor("#9CA3AF")
        n.latencyMs < 300 -> Color.parseColor("#22C55E")
        n.latencyMs < 800 -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#EF4444")
    }

    // ============ 连接 ============
    private fun connectTo(n: ProxyNode) {
        if (V2RayManager.start(this, n)) {
            NodeStore.setActive(this, n)
            Toast.makeText(this, "已连接：${n.name}", Toast.LENGTH_SHORT).show()
        } else {
            NodeStore.setActive(this, null)
            val detail = V2RayManager.lastError()
            val msg = if (detail.isNotEmpty()) "连接失败：$detail" else "连接失败，请检查节点配置"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        rebuild()
    }

    // ============ 添加订阅 / 单节点 ============
    private fun addSubscription() {
        val et = EditText(this).apply {
            hint = "订阅链接 https://...  或  直接粘贴 vless:// / vmess:// / trojan://"
            minHeight = (60 * resources.displayMetrics.density).toInt()
            setSingleLine(false)
            setPadding(24, 16, 24, 16)
            setTextColor(getColor(R.color.text_primary))
        }
        AlertDialog.Builder(this)
            .setTitle("添加代理")
            .setView(et)
            .setPositiveButton("确定") { _, _ -> doImportOrAdd(et.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doImportOrAdd(raw: String) {
        if (raw.isEmpty()) return
        if (raw.startsWith("vless://") || raw.startsWith("vmess://") || raw.startsWith("trojan://")) {
            val n = NodeParser.parseSingle(raw)
            if (n == null) {
                Toast.makeText(this, "节点格式无法解析", Toast.LENGTH_SHORT).show()
            } else {
                val nodes = NodeStore.load(this).toMutableList()
                nodes.add(n)
                NodeStore.save(this, nodes)
                rebuild()
                Toast.makeText(this, "已添加：${n.name}", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // 订阅
        loading.visibility = View.VISIBLE
        executor.execute {
            var err: String? = null
            var parsed = emptyList<ProxyNode>()
            try {
                val target = normalizeSub(raw)
                val body = fetchText(target, 8000, 10000)
                if (body.isEmpty()) err = "订阅内容为空"
                else {
                    parsed = NodeParser.parse(body)
                    if (parsed.isEmpty()) err = "未解析到节点（格式不支持？）"
                }
            } catch (e: Exception) {
                err = "导入失败：${e.javaClass.simpleName}"
            }
            mainHandler.post {
                loading.visibility = View.GONE
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    return@post
                }
                val tagged = parsed.map { it.copy(subscription = raw) }
                val nodes = (NodeStore.load(this) + tagged).distinctBy { NodeStore.keyOf(it) }.toMutableList()
                NodeStore.save(this, nodes)
                NodeStore.addSub(this, raw)
                rebuild()
                Toast.makeText(this, "已导入 ${tagged.size} 个节点", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun normalizeSub(raw: String): String = when {
        raw.startsWith("sub://") -> {
            val b = raw.removePrefix("sub://")
            val host = runCatching { String(android.util.Base64.decode(b, android.util.Base64.DEFAULT)) }.getOrNull() ?: b
            if (host.startsWith("http")) host else "https://$host"
        }
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        else -> "https://$raw"
    }

    private fun fetchText(url: String, connectMs: Int, readMs: Int): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectMs
                readTimeout = readMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; ProxyBrowser)")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ""
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ============ 测速 ============
    private fun testAll() {
        val nodes = NodeStore.load(this).toMutableList()
        if (nodes.isEmpty()) { Toast.makeText(this, "请先添加节点", Toast.LENGTH_SHORT).show(); return }
        loading.visibility = View.VISIBLE
        nodes.forEach { it.latencyMs = -1L }
        var done = 0
        val total = nodes.size
        for (n in nodes) {
            executor.execute {
                n.latencyMs = ping(n.address, n.port, 3000)
                synchronized(nodes) {
                    done++
                    if (done >= total) {
                        NodeStore.save(this, nodes)
                        mainHandler.post {
                            loading.visibility = View.GONE
                            rebuild()
                            val ok = nodes.count { it.latencyMs > 0 }
                            Toast.makeText(this, "测速完成：$ok/$total 可用", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun ping(host: String, port: Int, timeout: Int): Long = try {
        val t0 = System.currentTimeMillis()
        java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(host, port), timeout); s.close() }
        System.currentTimeMillis() - t0
    } catch (_: Exception) { -1L }

    // ============ 排序 ============
    private fun sortByLatency() {
        val nodes = NodeStore.load(this).toMutableList()
        nodes.sortBy { if (it.latencyMs > 0) it.latencyMs else Long.MAX_VALUE }
        NodeStore.save(this, nodes)
        rebuild()
        Toast.makeText(this, "已按延迟从低到高排序", Toast.LENGTH_SHORT).show()
    }

    // ============ 删除 ============
    private fun deleteMenu() {
        val subs = NodeStore.loadSubs(this).toMutableList()
        val options = subs.toMutableList()
        options.add("■ 未分组节点（手动添加）")
        val labels = options.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("删除订阅 / 节点")
            .setItems(labels) { _, which ->
                val sel = options[which]
                if (sel.startsWith("■ 未分组")) {
                    val remaining = NodeStore.load(this).filter { it.subscription.isNotEmpty() }
                    NodeStore.save(this, remaining)
                } else {
                    NodeStore.deleteSub(this, sel)
                }
                rebuild()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============ UI helpers ============
    private fun actionBtn(label: String, onClick: () -> Unit): View {
        val ctx = this
        val b = Button(ctx).apply {
            text = label
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(getColor(R.color.surface))
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setOnClickListener { onClick() }
        }
        return b
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
