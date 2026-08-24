package com.proxybrowser.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.proxybrowser.app.R
import com.proxybrowser.app.core.AdBlocker
import com.proxybrowser.app.core.Settings
import com.proxybrowser.app.core.UserScriptEngine
import com.proxybrowser.app.core.V2RayManager
import com.proxybrowser.app.core.VideoSniffer
import com.proxybrowser.app.data.NodeStore
import com.proxybrowser.app.data.ProxyNode
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors

class BrowserActivity : AppCompatActivity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var proxyToggle: ImageView
    private lateinit var btnSearch: ImageView
    private lateinit var btnSniffer: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnForward: ImageView
    private lateinit var btnRefresh: ImageView
    private lateinit var btnHome: ImageView
    private lateinit var btnTabs: ImageView
    private lateinit var btnSettings: ImageView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "pb-io").apply { isDaemon = true } }

    // ============ Tabs ============
    data class Tab(val id: Int, var title: String, var url: String, var isHome: Boolean, var webView: WebView?)
    private val tabs = mutableListOf<Tab>()
    private var activeTabId = 0
    private var nextTabId = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdBlocker.loadEnabled(this)
        VideoSniffer.load(this)

        buildUi()

        // 启动即浏览器，默认不开启代理（盾牌灰色）
        createTab(home = true)
        updateShield()
        refreshNavButtons()
    }

    // ============ UI ============
    private fun buildUi() {
        val ctx = this
        // Top bar
        proxyToggle = makeIconBtn(R.drawable.ic_shield_off) { toggleProxy() }
        btnSearch = makeIconBtn(R.drawable.ic_search) { navigateTo(urlBar.text.toString()) }

        urlBar = EditText(ctx).apply {
            hint = "搜索或输入网址"
            setSingleLine()
            setBackgroundResource(R.drawable.bg_url_bar)
            setPadding(36, 14, 36, 14)
            textSize = 14f
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> navigateTo(text.toString()); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { refreshNavButtons() }
            })
        }
        urlBar.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        urlBar.compoundDrawablePadding = 8

        btnSniffer = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_sniffer)
            setBackgroundResource(R.drawable.bg_btn_ghost)
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            val s = dp(24)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { showSnifferDialog() }
        }

        val urlWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(urlBar, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(btnSniffer)
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 4)
            addView(proxyToggle, lp(36, 36, 0, 0, 0, 2))
            addView(urlWrap, lp(0, 40, 1, 2, 0, 2))
            addView(btnSearch, lp(36, 36, 0, 2, 0, 0))
        }

        // Progress
        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }

        // Web container
        webContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // Bottom bar
        btnBack = makeIconBtn(R.drawable.ic_back) { activeWebView()?.let { if (it.canGoBack()) it.goBack() } }
        btnForward = makeIconBtn(R.drawable.ic_forward) { activeWebView()?.let { if (it.canGoForward()) it.goForward() } }
        btnRefresh = makeIconBtn(R.drawable.ic_refresh) { activeWebView()?.reload() }
        btnHome = makeIconBtn(R.drawable.ic_home) { loadHome(activeTab()) }
        btnTabs = makeIconBtn(R.drawable.ic_tabs) { showTabsDialog() }
        btnSettings = makeIconBtn(R.drawable.ic_settings) { startActivity(Intent(this@BrowserActivity, SettingsActivity::class.java)) }

        val bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
            setBackgroundColor(getColor(R.color.bg))
            addView(btnBack, lp(0, 44, 1, 0, 0, 0))
            addView(btnForward, lp(0, 44, 1, 0, 0, 0))
            addView(btnRefresh, lp(0, 44, 1, 0, 0, 0))
            addView(btnHome, lp(0, 44, 1, 0, 0, 0))
            addView(btnTabs, lp(0, 44, 1, 0, 0, 0))
            addView(btnSettings, lp(0, 44, 1, 0, 0, 0))
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            addView(topBar)
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            addView(webContainer)
            addView(View(ctx).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
            addView(bottomBar)
        }
        setContentView(root)
    }

    private fun makeIconBtn(resId: Int, onClick: () -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(resId)
            setBackgroundResource(R.drawable.bg_btn_ghost)
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            val s = dp(30)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { onClick() }
        }
    }

    private fun lp(w: Int, h: Int, weight: Int, l: Int, t: Int, r: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            if (w == 0) 0 else dp(w),
            if (h == 0) 0 else dp(h),
            weight.toFloat()
        ).apply { setMargins(dp(l), dp(t), dp(r), 0) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ============ Tabs ============
    private fun activeTab(): Tab? = tabs.firstOrNull { it.id == activeTabId }
    private fun activeWebView(): WebView? = activeTab()?.webView

    private fun createTab(home: Boolean, url: String = ""): Tab {
        val tab = Tab(id = nextTabId++, title = if (home) "主页" else "新标签页", url = if (home) "" else url, isHome = home, webView = null)
        tabs.add(tab)
        activeTabId = tab.id
        attachTab(tab)
        if (home) loadHome(tab) else navigateTab(tab, url)
        return tab
    }

    private fun attachTab(tab: Tab) {
        // 移除当前已挂载的 webView
        val current = activeTab()?.webView
        if (current != null && current.parent == webContainer) webContainer.removeView(current)
        if (tab.webView == null) tab.webView = newWebView(tab)
        webContainer.removeView(tab.webView)
        webContainer.addView(tab.webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(tab: Tab): WebView {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.loadsImagesAutomatically = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.userAgentString = Settings.userAgent(this).ifEmpty {
            "Mozilla/5.0 (Linux; Android 13; ProxyBrowser) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        w.webViewClient = ProxyingClient(tab)
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab.id == activeTabId) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrEmpty() && !tab.isHome) {
                    tab.title = title
                }
            }
        }
        w.addJavascriptInterface(JsBridge(this), "PB")
        return w
    }

    private fun switchTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        activeTabId = id
        attachTab(tab)
        // 同步地址栏
        if (tab.isHome) urlBar.setText("") else urlBar.setText(tab.url)
        refreshNavButtons()
    }

    private fun closeTab(id: Int) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs[idx]
        tab.webView?.destroy()
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            createTab(home = true)
        } else {
            val fallback = tabs.getOrNull(idx.coerceAtMost(tabs.lastIndex)) ?: tabs.last()
            switchTab(fallback.id)
        }
    }

    // ============ Navigation ============
    private fun loadHome(tab: Tab?) {
        val t = tab ?: return
        t.isHome = true
        t.url = ""
        t.title = "主页"
        t.webView?.loadDataWithBaseURL("https://pb.local/", HOME_HTML, "text/html", "utf-8", null)
        if (t.id == activeTabId) urlBar.setText("")
    }

    private fun navigateTo(input: String) {
        val t = activeTab() ?: return
        val raw = input.trim()
        if (raw.isEmpty()) { loadHome(t); return }
        navigateTab(t, raw)
    }

    private fun navigateTab(tab: Tab, raw: String) {
        val u = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(' ') || !raw.contains('.') -> Settings.searchEngine(this) + Uri.encode(raw)
            else -> "https://$raw"
        }
        tab.isHome = false
        tab.url = u
        tab.webView?.loadUrl(u)
        if (tab.id == activeTabId) urlBar.setText(u)
    }

    private fun refreshNavButtons() {
        val w = activeWebView()
        val canBack = w?.canGoBack() == true
        val canFwd = w?.canGoForward() == true
        btnBack.isEnabled = canBack
        btnBack.alpha = if (canBack) 1f else 0.3f
        btnForward.isEnabled = canFwd
        btnForward.alpha = if (canFwd) 1f else 0.3f
        btnTabs.alpha = 1f
    }

    // ============ Proxy shield ============
    private fun updateShield() {
        if (V2RayManager.isRunning()) {
            proxyToggle.setImageResource(R.drawable.ic_shield_on)
        } else {
            proxyToggle.setImageResource(R.drawable.ic_shield_off)
        }
    }

    private fun toggleProxy() {
        if (V2RayManager.isRunning()) {
            V2RayManager.stop()
            updateShield()
            Toast.makeText(this, "代理已关闭", Toast.LENGTH_SHORT).show()
            return
        }
        val active = NodeStore.getActive(this)
        if (active == null) {
            Toast.makeText(this, "请先在「设置 → 代理设置」中添加并选择节点", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, ProxySettingsActivity::class.java))
            return
        }
        if (V2RayManager.start(this, active)) {
            updateShield()
            Toast.makeText(this, "代理已开启：${active.name}", Toast.LENGTH_SHORT).show()
        } else {
            updateShield()
            Toast.makeText(this, "代理启动失败，请检查节点配置或订阅", Toast.LENGTH_LONG).show()
        }
    }

    // ============ Tabs dialog ============
    private fun showTabsDialog() {
        val ctx = this
        val scroll = ScrollView(ctx)
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        // 顶部 + 新建
        val addRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 12, 8, 12)
        }
        val addTv = TextView(ctx).apply {
            text = "＋ 新建标签页"
            textSize = 16f
            setTextColor(getColor(R.color.accent))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        addRow.addView(addTv)
        addRow.setOnClickListener {
            createTab(home = true)
            showTabs()
        }
        list.addView(addRow)
        list.addView(View(ctx).apply {
            setBackgroundColor(getColor(R.color.divider))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        for (tab in tabs) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 14, 8, 14)
            }
            val title = TextView(ctx).apply {
                text = if (tab.isHome) "主页" else (tab.title.ifEmpty { tab.url.ifEmpty { "新标签页" } })
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_node_idle)
                setBackgroundResource(R.drawable.bg_btn_ghost)
                val p = (8 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                val s = dp(22)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setOnClickListener {
                    closeTab(tab.id)
                    showTabs()
                }
            }
            row.addView(title)
            row.addView(del)
            row.setOnClickListener {
                switchTab(tab.id)
                dialog?.dismiss()
                dialog = null
            }
            list.addView(row)
        }
        scroll.addView(list)

        dialog = AlertDialog.Builder(ctx)
            .setTitle("标签页 (${tabs.size})")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    private var dialog: AlertDialog? = null
    private fun showTabs() {
        dialog?.dismiss()
        dialog = null
        showTabsDialog()
    }

    // ============ Sniffer dialog ============
    private fun showSnifferDialog() {
        val ctx = this
        val items = VideoSniffer.getAll()
        val sb = StringBuilder()
        sb.append("视频嗅探已${if (Settings.isSniffer(ctx)) "开启" else "关闭"}。\n\n")
        if (items.isEmpty()) {
            sb.append("暂未嗅探到视频。\n浏览含视频的页面（如抖音/YouTube）后，这里会列出可下载链接。")
        } else {
            items.take(20).forEachIndexed { i, m ->
                sb.append("${i + 1}. ${m.title.ifEmpty { m.type }}  (${m.ext})\n${m.url}\n\n")
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle("视频嗅探")
            .setMessage(sb.toString())
            .setPositiveButton(if (Settings.isSniffer(ctx)) "关闭嗅探" else "开启嗅探") { _, _ ->
                Settings.setSniffer(ctx, !Settings.isSniffer(ctx))
                Toast.makeText(ctx, if (Settings.isSniffer(ctx)) "已开启视频嗅探" else "已关闭视频嗅探", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("复制全部", null)
            .setNeutralButton("关闭", null)
            .show()
    }

    override fun onBackPressed() {
        val w = activeWebView()
        if (w?.canGoBack() == true) {
            w.goBack()
        } else if (tabs.size > 1) {
            closeTab(activeTabId)
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        AdBlocker.loadEnabled(this)
        updateShield()
        refreshNavButtons()
    }

    override fun onDestroy() {
        tabs.forEach { it.webView?.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    // ============ WebViewClient (SOCKS 代理拦截) ============
    private inner class ProxyingClient(private val tab: Tab) : WebViewClient() {
        private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", V2RayManager.PORT))
        private val tag = "ProxyClient"

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val scheme = request.url.scheme ?: ""
            if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("sms:") ||
                url.startsWith("intent:") || url.startsWith("magnet:") || url.endsWith(".apk", true)
            ) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                return true
            }
            // 拦截非 http/https 的私有 scheme（如 baiduboxapp://），避免 ERR_UNKNOWN_URL_SCHEME
            if (scheme != "http" && scheme != "https" && scheme != "about" && scheme != "javascript" && scheme.isNotEmpty()) {
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (url.startsWith("data:") || url.startsWith("blob:") ||
                url.startsWith("about:") || url.startsWith("javascript:")
            ) return null
            // 去广告：无论是否走代理都拦截
            if (AdBlocker.shouldBlock(url)) return AdBlocker.emptyResponse()
            // 代理开启时通过 SOCKS 转发；关闭时直接加载
            if (!V2RayManager.isRunning()) return null
            if ((request.method ?: "GET").equals("GET", true).not()) return null
            return try { fetchViaSocks(request, url) } catch (e: Exception) { null }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (url != null && tab.id == activeTabId) {
                tab.url = url
                tab.isHome = false
                urlBar.setText(url)
                urlBar.setSelection(url.length)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (tab.id == activeTabId) refreshNavButtons()
            if (Settings.isUserScript(this@BrowserActivity)) {
                val scripts = UserScriptEngine.loadAll(this@BrowserActivity)
                val matched = UserScriptEngine.matches(url ?: "", scripts)
                if (matched.isNotEmpty()) {
                    val js = UserScriptEngine.buildInjection(matched)
                    if (js.isNotEmpty()) view.evaluateJavascript("(function(){$js})()", null)
                }
            }
            if (Settings.isSniffer(this@BrowserActivity)) {
                view.evaluateJavascript(VideoSniffer.HOOK_JS, null)
            }
        }

        private fun fetchViaSocks(req: WebResourceRequest, urlStr: String): WebResourceResponse? {
            val conn: HttpURLConnection = try {
                (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = req.method ?: "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    req.requestHeaders?.forEach { (k, v) ->
                        if (k.equals("Host", true) || k.equals("Connection", true) || k.equals("Accept-Encoding", true)) return@forEach
                        v?.let { setRequestProperty(k, it) }
                    }
                    if (getRequestProperty("User-Agent") == null) {
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; ProxyBrowser) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                    }
                    if (Settings.isDnt(this@BrowserActivity)) setRequestProperty("DNT", "1")
                }
            } catch (e: Exception) { return null }
            return try {
                val code = conn.responseCode
                val stream: InputStream = try { conn.inputStream } catch (_: Exception) { conn.errorStream ?: return null }
                val mime = conn.contentType ?: "application/octet-stream"
                val charset = runCatching { mime.substringAfter("charset=", "").ifBlank { "utf-8" } }.getOrDefault("utf-8")
                val reason = conn.responseMessage ?: ""
                val headers: Map<String, List<String>> = try { conn.headerFields ?: emptyMap() } catch (_: Exception) { emptyMap() }
                val safeHeaders: MutableMap<String, String> = headers.mapNotNull { (k, v) -> if (k == null) null else k to v.joinToString(", ") }.toMap().toMutableMap()
                WebResourceResponse(mime.substringBefore(";"), charset, code, reason, safeHeaders, stream)
            } catch (e: Exception) {
                try { conn.errorStream?.close() } catch (_: Exception) {}
                try { conn.inputStream?.close() } catch (_: Exception) {}
                null
            }
        }
    }

    // ============ JS Bridge ============
    private inner class JsBridge(private val ctx: Context) {
        @JavascriptInterface
        fun report(json: String) {
            ioExecutor.execute {
                runCatching {
                    val o = org.json.JSONObject(json)
                    val url = o.optString("url")
                    val type = o.optString("type", "video")
                    val pageUrl = o.optString("page", "")
                    val title = o.optString("title", "")
                    val ext = runCatching {
                        val u = Uri.parse(url)
                        MimeMap.ext(u.path ?: "") ?: ".bin"
                    }.getOrDefault(".bin")
                    if (url.isNotEmpty()) {
                        VideoSniffer.add(ctx, VideoSniffer.Media(url, type, pageUrl, title, 0, ext))
                        mainHandler.post { Toast.makeText(ctx, "嗅探到视频：$ext", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }

        @JavascriptInterface
        fun copyToClipboard(s: String) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", s))
            mainHandler.post { Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        private const val HOME_HTML = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        body{font-family:-apple-system,Roboto,sans-serif;background:#fff;color:#1f1f1f;
        display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0}
        .logo{font-size:34px;font-weight:700;margin-bottom:6px}
        .sub{color:#8a8a8e;font-size:13px;margin-bottom:24px}
        .proxy{font-size:12px;color:#8a8a8e;margin-top:30px}
        .dot{display:inline-block;width:8px;height:8px;border-radius:4px;background:#c7c7cc;margin-right:6px;vertical-align:middle}
        </style></head><body>
        <div class="logo">ProxyBrowser</div>
        <div class="sub">在上方地址栏输入网址或搜索内容</div>
        <div class="proxy"><span class="dot" id="proxyDot"></span><span id="proxyDetail">代理未开启</span></div>
        </body></html>
        """
    }
}

private object MimeMap {
    fun ext(path: String): String? {
        val i = path.lastIndexOf('.')
        if (i < 0 || i < path.length - 6) return null
        return path.substring(i).lowercase()
    }
}
