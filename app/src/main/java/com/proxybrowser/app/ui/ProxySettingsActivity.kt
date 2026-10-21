package com.proxybrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.proxybrowser.app.R
import com.proxybrowser.app.core.V2RayManager
import com.proxybrowser.app.data.NodeParser
import com.proxybrowser.app.data.NodeStore
import com.proxybrowser.app.data.ProxyNode
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * 节点管理页（夸克风格卡片列表）。
 *
 * 相对旧版的关键改进：
 * 1. 测速 = TCP 握手 + 通过代理真实请求，两个结果分开显示，不再被“端口活着”骗；
 * 2. 每个节点可以点开看完整参数（协议/传输/TLS/SNI/路径），便于和机场面板对照；
 * 3. 导入失败会说明原因（不是节点格式、是 yaml、还是网络问题）。
 */
class ProxySettingsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var summary: TextView
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private val expanded = mutableMapOf<String, Boolean>()
    /** 临时保存测速结果，避免频繁写 SP */
    private val probeResult = mutableMapOf<String, Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@ProxySettingsActivity, R.color.bg))
        }

        // 顶部栏 + 「添加」按钮
        val addBtn = Ui.iconButton(this, R.drawable.ic_plus, onClick = { addNodeDialog() })
        root.addView(Ui.topBar(this, "代理与节点", addBtn))

        // 状态摘要
        summary = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.color(this@ProxySettingsActivity, R.color.text_secondary))
            setPadding(Ui.dp(this@ProxySettingsActivity, 18), 0, Ui.dp(this@ProxySettingsActivity, 18), Ui.dp(this@ProxySettingsActivity, 8))
        }
        root.addView(summary)

        // 操作条
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@ProxySettingsActivity, 14), 0, Ui.dp(this@ProxySettingsActivity, 14), Ui.dp(this@ProxySettingsActivity, 10))
        }
        actionBar.addView(Ui.primaryButton(this, "＋ 订阅 / 节点") { addNodeDialog() })
        actionBar.addView(Ui.ghostButton(this, "测速") { testAll() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(this@ProxySettingsActivity, 8) }
        })
        actionBar.addView(Ui.ghostButton(this, "排序") { sortByLatency() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(this@ProxySettingsActivity, 8) }
        })
        actionBar.addView(Ui.ghostButton(this, "删除") { deleteMenu() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(this@ProxySettingsActivity, 8) }
        })
        root.addView(actionBar)

        loading = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@ProxySettingsActivity, 26), Ui.dp(this@ProxySettingsActivity, 26)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = Ui.dp(this@ProxySettingsActivity, 6)
            }
        }
        root.addView(loading)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@ProxySettingsActivity, 14), 0, Ui.dp(this@ProxySettingsActivity, 14), Ui.dp(this@ProxySettingsActivity, 28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    // ===================== 列表 =====================

    private fun rebuild() {
        content.removeAllViews()
        val nodes = NodeStore.load(this)
        val subs = NodeStore.loadSubs(this)
        val active = NodeStore.getActive(this)
        val activeKey = active?.let { NodeStore.keyOf(it) }

        summary.text = if (nodes.isEmpty()) {
            "还没有节点"
        } else {
            val reach = nodes.count { it.reachable }
            val on = active?.let { " · 当前：${it.name}" } ?: ""
            "共 ${nodes.size} 个节点 · ${subs.size} 个订阅${on}"
        }

        if (nodes.isEmpty()) {
            content.addView(emptyCard())
            return
        }

        val groups = nodes.groupBy { it.subscription }
        val order = subs.map { it.url }.toMutableList()
        if (groups.containsKey("")) order.add("")

        for (key in order) {
            val list = groups[key] ?: continue
            val label = if (key.isEmpty()) "手动添加"
            else subs.firstOrNull { it.url == key }?.name?.ifEmpty { null } ?: "订阅"
            content.addView(groupBlock(key, list, label, activeKey))
        }
    }

    private fun emptyCard(): View {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(ctx, 20).toFloat()
                setColor(Ui.color(ctx, R.color.bg_soft))
            }
            setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 36), Ui.dp(ctx, 24), Ui.dp(ctx, 36))
            addView(TextView(ctx).apply {
                text = "🌐"
                textSize = 34f
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = "还没有节点"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Ui.color(ctx, R.color.text_primary))
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(ctx, 10), 0, 0)
            })
            addView(TextView(ctx).apply {
                text = "点右上角 ＋ 添加订阅链接，\n或直接粘贴 vless:// vmess:// trojan:// 节点"
                textSize = 12.5f
                setTextColor(Ui.color(ctx, R.color.text_secondary))
                gravity = Gravity.CENTER
                setLineSpacing(Ui.dp(ctx, 4).toFloat(), 1f)
                setPadding(0, Ui.dp(ctx, 8), 0, 0)
            })
        }
    }

    private fun groupBlock(key: String, nodes: List<ProxyNode>, label: String, activeKey: String?): View {
        val ctx = this
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 12) }
        }
        val open = expanded[key] != false

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
            setOnClickListener {
                expanded[key] = !open
                rebuild()
            }
        }
        header.addView(TextView(ctx).apply {
            text = label
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Ui.color(ctx, R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Ui.pillTag(ctx, "${nodes.size}", Ui.color(ctx, R.color.accent)))
        header.addView(TextView(ctx).apply {
            text = if (open) "▾" else "▸"
            textSize = 13f
            setTextColor(Ui.color(ctx, R.color.text_tertiary))
            setPadding(Ui.dp(ctx, 8), 0, 0, 0)
        })
        wrap.addView(header)

        if (open) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(ctx, 18).toFloat()
                    setColor(Ui.color(ctx, R.color.bg_soft))
                }
                setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
            }
            nodes.forEachIndexed { i, n ->
                if (i > 0) card.addView(View(ctx).apply {
                    setBackgroundColor(Ui.color(ctx, R.color.divider))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = Ui.dp(ctx, 14) }
                })
                card.addView(nodeRow(n, NodeStore.keyOf(n) == activeKey))
            }
            wrap.addView(card)
        }
        return wrap
    }

    private fun nodeRow(n: ProxyNode, isActive: Boolean): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_ripple)
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12))
            setOnClickListener { showNodeDetail(n) }
        }

        // 左侧状态点
        row.addView(View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(
                    when {
                        isActive -> Ui.color(ctx, R.color.green)
                        n.reachable -> Ui.color(ctx, R.color.accent_light)
                        n.latencyMs > 0 -> Ui.color(ctx, R.color.orange)
                        else -> Ui.color(ctx, R.color.icon_inactive)
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 8), Ui.dp(ctx, 8)).apply {
                marginEnd = Ui.dp(ctx, 12)
            }
        })

        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(ctx).apply {
            text = n.name
            textSize = 14.5f
            setTextColor(Ui.color(ctx, R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val tags = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 5), 0, 0)
        }
        tags.addView(Ui.pillTag(ctx, n.type.name, Ui.color(ctx, R.color.accent)))
        tags.addView(Ui.pillTag(ctx, n.transportLabel(), Ui.color(ctx, R.color.text_secondary)).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(ctx, 5) }
        })
        val timing = probeResult[NodeStore.keyOf(n)] ?: n.latencyMs
        tags.addView(TextView(ctx).apply {
            text = latencyText(n, timing)
            textSize = 11f
            setTextColor(latencyColor(n, timing))
            setPadding(Ui.dp(ctx, 8), 0, 0, 0)
        })
        info.addView(tags)
        row.addView(info)

        val use = TextView(ctx).apply {
            text = if (isActive) "使用中" else "连接"
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (isActive) Ui.color(ctx, R.color.green) else Ui.color(ctx, R.color.text_on_accent))
            background = if (isActive) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(ctx, 999).toFloat()
                setColor(Ui.color(ctx, R.color.green_soft))
            } else android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(ctx, 999).toFloat()
                colors = intArrayOf(Ui.color(ctx, R.color.grad_start), Ui.color(ctx, R.color.grad_end))
                gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            }
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 7), Ui.dp(ctx, 14), Ui.dp(ctx, 7))
            setOnClickListener {
                if (isActive) toggleOff() else connectTo(n)
            }
        }
        row.addView(use)
        return row
    }

    private fun latencyText(n: ProxyNode, ms: Long): String = when {
        ms > 0 -> "${ms}ms"
        else -> "未测"
    }

    private fun latencyColor(n: ProxyNode, ms: Long): Int = when {
        ms <= 0 -> Ui.color(this, R.color.text_tertiary)
        ms < 200 -> Ui.color(this, R.color.green)
        ms < 600 -> Ui.color(this, R.color.orange)
        else -> Ui.color(this, R.color.red)
    }

    // ===================== 连接 =====================

    private fun connectTo(n: ProxyNode) {
        loading.visibility = View.VISIBLE
        pool.execute {
            val ok = V2RayManager.start(this, n)
            val err = V2RayManager.lastError()
            main.post {
                loading.visibility = View.GONE
                if (ok) {
                    NodeStore.setActive(this, n)
                    toast("已连接：${n.name}")
                    // 连上后立刻做一次真实连通测试，避免“连上了但没网”
                    verifyProxy(n)
                } else {
                    showFailure("节点连接失败", err.ifEmpty { "未知错误" })
                }
                rebuild()
            }
        }
    }

    private fun verifyProxy(n: ProxyNode) {
        pool.execute {
            val ms = V2RayManager.probeThroughProxy()
            main.post {
                if (ms > 0) {
                    probeResult[NodeStore.keyOf(n)] = ms
                    toast("连通正常（${ms}ms）")
                    rebuild()
                } else {
                    showFailure(
                        "节点已连上，但访问外网失败",
                        "内核运行正常，TCP 也能握手（测速会显示 ms 很小），但请求发不出去。\n\n" +
                            "最常见原因（按概率）：\n" +
                            "1. reality 节点缺 pbk / sid，或 sid 与服务端不一致；\n" +
                            "2. ws / grpc 的 path、Host 与服务端不匹配；\n" +
                            "3. 节点的 SNI 被服务端拒绝；\n" +
                            "4. 账号到期 / 流量用尽。\n\n" +
                            "建议：点开节点详情核对参数，或到「设置 → 内核日志」看 xray 报错。"
                    )
                }
            }
        }
    }

    private fun toggleOff() {
        V2RayManager.stop()
        NodeStore.setActive(this, null)
        toast("已断开代理")
        rebuild()
    }

    private fun showFailure(title: String, msg: String) {
        AlertDialog.Builder(this, R.style.Theme_PB_Dialog)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .setNeutralButton("查看内核日志") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_log", true))
            }
            .show()
    }

    // ===================== 节点详情 =====================

    private fun showNodeDetail(n: ProxyNode) {
        val rows = mutableListOf(
            "协议" to n.type.name,
            "服务器" to "${n.address}:${n.port}",
            "传输" to n.network,
            "安全" to n.security.ifEmpty { "none" },
            "SNI" to n.sni.ifEmpty { "—" },
            "Host" to n.wsHost.ifEmpty { "—" },
            "路径" to n.wsPath.ifEmpty { "—" },
            "serviceName" to n.serviceName.ifEmpty { "—" },
            "指纹" to n.fingerprint.ifEmpty { "—" }
        )
        if (n.publicKey.isNotEmpty()) rows.add("reality pbk" to n.publicKey)
        if (n.shortId.isNotEmpty()) rows.add("reality sid" to n.shortId)
        if (n.alterId > 0) rows.add("alterId" to n.alterId.toString())
        rows.add("ID / 密码" to n.uuid.take(12) + "…")

        val body = rows.joinToString("\n") { (k, v) -> "$k：$v" }
        AlertDialog.Builder(this, R.style.Theme_PB_Dialog)
            .setTitle(n.name)
            .setMessage(body)
            .setPositiveButton("关闭", null)
            .setNeutralButton("单独测速") { _, _ -> testOne(n) }
            .setNegativeButton("删除") { _, _ ->
                NodeStore.save(this, NodeStore.load(this).filter { NodeStore.keyOf(it) != NodeStore.keyOf(n) })
                if (NodeStore.getActive(this)?.let { NodeStore.keyOf(it) } == NodeStore.keyOf(n)) {
                    V2RayManager.stop()
                    NodeStore.setActive(this, null)
                }
                rebuild()
                toast("已删除")
            }
            .show()
    }

    // ===================== 添加 =====================

    private fun addNodeDialog() {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
        }
        val etName = Ui.input(ctx, "备注名（可留空，如：香港 01）")
        val etBody = Ui.input(ctx, "订阅链接 https://… 或 vless:// vmess:// trojan://", multiLine = true)
        box.addView(etName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(etBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 140)).apply {
            topMargin = Ui.dp(ctx, 10)
        })
        box.addView(TextView(ctx).apply {
            text = "小贴士：多数机场的订阅链接返回的是 base64 列表；如果是 yaml / clash 格式，需要换成 v2ray 订阅地址。"
            textSize = 11f
            setTextColor(Ui.color(ctx, R.color.text_tertiary))
            setPadding(0, Ui.dp(ctx, 10), 0, 0)
            setLineSpacing(Ui.dp(ctx, 3).toFloat(), 1f)
        })

        AlertDialog.Builder(ctx, R.style.Theme_PB_Dialog)
            .setTitle("添加订阅 / 节点")
            .setView(box)
            .setPositiveButton("导入") { _, _ ->
                importOrAdd(etBody.text.toString().trim(), etName.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importOrAdd(raw: String, name: String) {
        if (raw.isEmpty()) return
        if (NodeParser.parseSingle(raw) != null) {
            val n = NodeParser.parseSingle(raw)!!
            val nodes = NodeStore.load(this).toMutableList()
            nodes.add(if (name.isEmpty()) n else n.copy(name = name))
            NodeStore.save(this, nodes)
            rebuild()
            toast("已添加：${n.name}")
            return
        }
        if (raw.startsWith("vless://") || raw.startsWith("vmess://") || raw.startsWith("trojan://")) {
            toast("节点格式无法解析，请检查链接是否完整")
            return
        }

        loading.visibility = View.VISIBLE
        pool.execute {
            var err: String? = null
            var parsed = emptyList<ProxyNode>()
            try {
                val body = fetchText(normalizeSub(raw))
                when {
                    body.isEmpty() -> err = "订阅内容为空（可能被墙，或链接已失效）"
                    else -> {
                        parsed = NodeParser.parse(body)
                        if (parsed.isEmpty()) {
                            err = if (body.trimStart().startsWith("proxies:") ||
                                body.contains("proxy-groups:")
                            ) {
                                "这是 Clash / yaml 订阅，本 App 只支持 v2ray 格式（base64 节点列表）"
                            } else {
                                "未解析到节点：可能是 ss / hysteria2 等暂不支持的协议"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                err = "导入失败：${e.javaClass.simpleName}"
            }
            main.post {
                loading.visibility = View.GONE
                if (err != null) {
                    toast(err)
                    return@post
                }
                val tagged = parsed.map { it.copy(subscription = raw) }
                val nodes = (NodeStore.load(this) + tagged)
                    .distinctBy { NodeStore.keyOf(it) }
                    .toMutableList()
                NodeStore.save(this, nodes)
                NodeStore.addSub(this, name, raw)
                expanded[raw] = true
                rebuild()
                toast("已导入 ${tagged.size} 个节点")
            }
        }
    }

    private fun normalizeSub(raw: String): String = when {
        raw.startsWith("sub://") -> {
            val b = raw.removePrefix("sub://")
            val host = runCatching {
                String(android.util.Base64.decode(b, android.util.Base64.DEFAULT))
            }.getOrNull() ?: b
            if (host.startsWith("http")) host else "https://$host"
        }
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        else -> "https://$raw"
    }

    private fun fetchText(url: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "v2rayNG/1.9.16")
                setRequestProperty("Accept", "*/*")
            }
            val enc = conn.contentEncoding?.lowercase()
            val stream = if (enc == "gzip") GZIPInputStream(conn.inputStream) else conn.inputStream
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ""
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ===================== 测速 =====================

    private fun testAll() {
        val nodes = NodeStore.load(this).toMutableList()
        if (nodes.isEmpty()) {
            toast("请先添加节点")
            return
        }
        loading.visibility = View.VISIBLE
        probeResult.clear()
        var done = 0
        val total = nodes.size
        for (n in nodes) {
            pool.execute {
                val ms = V2RayManager.pingTcp(n.address, n.port, 4000)
                n.latencyMs = ms
                n.reachable = ms > 0
                synchronized(nodes) {
                    done++
                    if (done >= total) {
                        NodeStore.save(this, nodes)
                        main.post {
                            loading.visibility = View.GONE
                            rebuild()
                            val ok = nodes.count { it.reachable }
                            toast("TCP 握手：$ok/$total 可用（这是服务器端口是否活着，不代表能上网）")
                        }
                    }
                }
            }
        }
    }

    private fun testOne(n: ProxyNode) {
        toast("测试中…")
        pool.execute {
            val ms = V2RayManager.pingTcp(n.address, n.port, 4000)
            main.post {
                if (ms > 0) toast("TCP 握手成功：${ms}ms")
                else toast("TCP 握手失败：端口不通或被屏蔽")
            }
        }
    }

    private fun sortByLatency() {
        val nodes = NodeStore.load(this).toMutableList()
        nodes.sortBy { if (it.latencyMs > 0) it.latencyMs else Long.MAX_VALUE }
        NodeStore.save(this, nodes)
        rebuild()
        toast("已按延迟排序")
    }

    private fun deleteMenu() {
        val ctx = this
        val subs = NodeStore.loadSubs(this)
        val hasUngrouped = NodeStore.load(this).any { it.subscription.isEmpty() }
        if (subs.isEmpty() && !hasUngrouped) {
            toast("没有可删除的分组")
            return
        }
        val labels = mutableListOf<String>()
        subs.forEach { labels.add(if (it.name.isNotEmpty()) it.name else it.url) }
        if (hasUngrouped) labels.add("手动添加的节点")
        val checked = BooleanArray(labels.size)

        AlertDialog.Builder(ctx, R.style.Theme_PB_Dialog)
            .setTitle("删除分组（可多选）")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("删除") { _, _ ->
                var n = 0
                subs.forEachIndexed { i, sub ->
                    if (checked.getOrElse(i) { false }) {
                        NodeStore.deleteSub(ctx, sub.url)
                        n++
                    }
                }
                if (hasUngrouped && checked.last()) {
                    NodeStore.save(ctx, NodeStore.load(ctx).filter { it.subscription.isNotEmpty() })
                    n++
                }
                if (n > 0) {
                    V2RayManager.stop()
                    NodeStore.setActive(ctx, null)
                }
                rebuild()
                toast(if (n > 0) "已删除 $n 个分组" else "未选择")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
