package com.proxybrowser.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.proxybrowser.app.R
import com.proxybrowser.app.core.AdBlocker
import com.proxybrowser.app.core.LocalProxy
import com.proxybrowser.app.core.Settings
import com.proxybrowser.app.core.UserScriptEngine
import com.proxybrowser.app.core.V2RayManager
import com.proxybrowser.app.core.VideoSniffer
import com.proxybrowser.app.data.NodeStore
import com.proxybrowser.app.data.ProxyNode
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * 主界面（夸克风格）：
 *   顶部：渐变地球/盾牌按钮 + 胶囊地址栏 + 刷新
 *   中部：WebView（沉浸式，内容延伸到状态栏下方）
 *   底部：后退 / 前进 / 主页 / 窗口 / 嗅探 / 菜单 六键工具栏
 *
 * 所有 UI 都用代码构建，避免布局文件和逻辑脱节；
 * 图标统一走 vector + tint，换肤只改 colors.xml。
 */
class BrowserActivity : AppCompatActivity() {

    // ---------- 视图 ----------
    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var urlBar: EditText
    private lateinit var btnGlobe: FrameLayout
    private lateinit var btnReload: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnTabs: ImageButton
    private lateinit var btnSniffer: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var tabCountBadge: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "pb-io").apply { isDaemon = true } }

    // ---------- 标签页 ----------
    private data class Tab(
        val id: Int,
        var title: String = "主页",
        var url: String = "",
        var isHome: Boolean = true,
        var webView: WebView? = null
    )

    private val tabs = mutableListOf<Tab>()
    private var activeTabId = 0
    private var nextTabId = 1

    // ---------- 生命周期 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        AdBlocker.loadEnabled(this)
        VideoSniffer.load(this)

        buildUi()
        applyInsets()
        createTab(home = true)
        updateShieldState()
        refreshNav()
    }

    override fun onResume() {
        super.onResume()
        AdBlocker.loadEnabled(this)
        updateShieldState()
        refreshNav()
        // 从节点页返回后可能已经换了节点
        activeTab()?.let { updateTitleForTab(it) }
    }

    override fun onDestroy() {
        tabs.forEach { it.webView?.let { w -> webContainer.removeView(w); w.destroy() } }
        tabs.clear()
        super.onDestroy()
    }

    override fun onBackPressed() {
        val w = activeWebView()
        if (w != null && w.canGoBack()) {
            w.goBack()
        } else if (tabs.size > 1) {
            closeTab(activeTabId)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /// ===================== UI 构建 =====================

    private fun buildUi() {
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }

        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(getColor(R.color.bg))
        }

        topBar = buildTopBar()
        bottomBar = buildBottomBar()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            addView(topBar)
            addView(webContainer)
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
            addView(bottomBar)
        }
        setContentView(root)
    }

    private fun buildTopBar(): LinearLayout {
        // 左侧：渐变地球（点击=代理开关）
        btnGlobe = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(getColor(R.color.grad_start), getColor(R.color.grad_end))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { showProxyPanel() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_globe)
                layoutParams = FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
            })
        }

        urlBar = EditText(this).apply {
            hint = "搜索或输入网址"
            setHintTextColor(getColor(R.color.text_tertiary))
            setSingleLine()
            background = null
            setPadding(dp(4), 0, dp(4), 0)
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                    commitUrlBar(); true
                } else false
            }
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.background = null
                    mainHandler.post {
                        (v as? EditText)?.let { it.setSelection(it.text.length) }
                    }
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // 输入框获得焦点时清空提示，让用户看到自己在打什么
                    hint = if (s.isNullOrEmpty()) "搜索或输入网址" else ""
                }
            })
        }

        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17))
        }

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(getColor(R.color.surface_sunken))
                setStroke(dp(1), getColor(R.color.divider))
            }
            setPadding(dp(12), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            addView(searchIcon, LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(6) })
            addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        btnReload = iconBtn(R.drawable.ic_refresh, "刷新") {
            val w = activeWebView()
            if (w != null) w.reload() else loadHome(activeTab())
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(10), dp(4))
            addView(btnGlobe)
            addView(pill, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(10); marginEnd = dp(8)
            })
            addView(btnReload)
        }
    }

    private fun buildBottomBar(): LinearLayout {
        btnBack = navBtn(R.drawable.ic_back, "后退") {
            activeWebView()?.let { if (it.canGoBack()) it.goBack() }
        }
        btnForward = navBtn(R.drawable.ic_forward, "前进") {
            activeWebView()?.let { if (it.canGoForward()) it.goForward() }
        }
        btnHome = navBtn(R.drawable.ic_home, "主页") { loadHome(activeTab()) }

        // 窗口：带数量角标
        val tabsWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setOnClickListener { showTabsSheet() }
        }
        btnTabs = ImageButton(this).apply {
            setImageResource(R.drawable.ic_tabs)
            background = null
            imageTintList = ColorStateList.valueOf(getColor(R.color.icon))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { showTabsSheet() }
        }
        tabCountBadge = TextView(this).apply {
            text = ""
            textSize = 9f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(7).toFloat()
                setColor(getColor(R.color.accent))
            }
            layoutParams = FrameLayout.LayoutParams(dp(15), dp(13), Gravity.CENTER).apply {
                leftMargin = dp(14); topMargin = dp(6)
            }
            visibility = View.GONE
        }
        tabsWrap.addView(btnTabs)
        tabsWrap.addView(tabCountBadge)

        btnSniffer = navBtn(R.drawable.ic_sniffer, "嗅探") { startActivity(Intent(this, SnifferActivity::class.java)) }
        btnMenu = navBtn(R.drawable.ic_menu, "菜单") { showMenuSheet() }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(6), dp(4), dp(6), dp(6))
            addView(btnBack, cellLp())
            addView(btnForward, cellLp())
            addView(btnHome, cellLp())
            addView(tabsWrap)
            addView(btnSniffer, cellLp())
            addView(btnMenu, cellLp())
        }
    }

    private fun cellLp() = LinearLayout.LayoutParams(0, dp(48), 1f)

    private fun navBtn(res: Int, desc: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(res)
            contentDescription = desc
            background = getDrawable(R.drawable.bg_ripple)
            imageTintList = ColorStateList.valueOf(getColor(R.color.icon))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { onClick() }
        }

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(res)
            contentDescription = desc
            background = getDrawable(R.drawable.bg_ripple_pill)
            imageTintList = ColorStateList.valueOf(getColor(R.color.icon))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { onClick() }
        }

    /** 状态栏 + 手势导航栏留白；横屏时不再额外加状态栏高度 */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = bars.top + dp(4))
            webContainer.updatePadding(bottom = 0)
            bottomBar.updatePadding(bottom = bars.bottom + dp(4))
            insets
        }
    }

    // ===================== 标签页 =====================

    private fun activeTab(): Tab? = tabs.firstOrNull { it.id == activeTabId }
    private fun activeWebView(): WebView? = activeTab()?.webView

    private fun createTab(home: Boolean, url: String = ""): Tab {
        val tab = Tab(
            id = nextTabId++,
            title = if (home) "主页" else "网页",
            url = if (home) "" else url,
            isHome = home
        )
        tabs.add(tab)
        activeTabId = tab.id
        attach(tab)
        if (home) loadHome(tab) else navigateTab(tab, url)
        return tab
    }

    private fun attach(tab: Tab) {
        tabs.forEach { t ->
            t.webView?.let { w -> if (w.parent != null) webContainer.removeView(w) }
        }
        if (tab.webView == null) tab.webView = newWebView(tab)
        webContainer.addView(
            tab.webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        if (tab.isHome) urlBar.setText("") else urlBar.setText(tab.url)
        updateTabBadge()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(tab: Tab): WebView {
        val w = WebView(this)
        w.setBackgroundColor(getColor(R.color.bg))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = Settings.userAgent(this@BrowserActivity).ifEmpty { defaultUa() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }
        w.addJavascriptInterface(JsBridge(), "PB")
        w.webViewClient = ProxyingClient(tab)
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab.id != activeTabId) return
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrEmpty()) {
                    tab.title = title
                    if (tab.id == activeTabId) refreshNav()
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean = false
        }
        return w
    }

    private fun switchTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        activeTabId = id
        attach(tab)
        refreshNav()
    }

    private fun closeTab(id: Int) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs[idx]
        tab.webView?.let { w -> webContainer.removeView(w); w.destroy() }
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            createTab(home = true)
        } else {
            val fallback = tabs.getOrNull(idx.coerceAtMost(tabs.lastIndex)) ?: tabs.last()
            switchTab(fallback.id)
        }
        updateTabBadge()
    }

    private fun updateTabBadge() {
        if (tabs.size > 1) {
            tabCountBadge.visibility = View.VISIBLE
            tabCountBadge.text = tabs.size.toString()
        } else {
            tabCountBadge.visibility = View.GONE
        }
    }

    // ===================== 导航 =====================

    private fun loadHome(tab: Tab?) {
        val t = tab ?: return
        t.isHome = true
        t.url = ""
        t.title = "主页"
        t.webView?.loadDataWithBaseURL("https://home.proxybrowser.local/", HOME_HTML, "text/html", "utf-8", null)
        if (t.id == activeTabId) urlBar.setText("")
        refreshNav()
    }

    private fun commitUrlBar() {
        val raw = urlBar.text.toString().trim()
        val t = activeTab() ?: return
        hideKeyboard()
        urlBar.clearFocus()
        if (raw.isEmpty()) {
            loadHome(t)
        } else {
            navigateTab(t, raw)
        }
    }

    private fun normalize(input: String): String {
        val raw = input.trim()
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("about:") -> raw
            raw.contains(' ') || !raw.contains('.') ->
                Settings.searchEngine(this) + Uri.encode(raw)
            else -> "https://$raw"
        }
    }

    private fun navigateTab(tab: Tab, input: String) {
        val u = normalize(input)
        tab.isHome = false
        tab.url = u
        tab.webView?.loadUrl(u)
        if (tab.id == activeTabId) urlBar.setText(u)
        refreshNav()
    }

    private fun refreshNav() {
        val w = activeWebView()
        val canBack = w?.canGoBack() == true
        val canFwd = w?.canGoForward() == true
        btnBack.isEnabled = canBack
        btnBack.imageTintList = ColorStateList.valueOf(
            getColor(if (canBack) R.color.icon else R.color.icon_inactive)
        )
        btnForward.isEnabled = canFwd
        btnForward.imageTintList = ColorStateList.valueOf(
            getColor(if (canFwd) R.color.icon else R.color.icon_inactive)
        )
    }

    private fun updateTitleForTab(tab: Tab) {
        if (tab.isHome) urlBar.setText("") else urlBar.setText(tab.url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun defaultUa(): String =
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // ===================== 盾牌 / 代理状态 =====================

    private fun updateShieldState() {
        val on = V2RayManager.isRunning()
        btnGlobe.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (on) {
                colors = intArrayOf(getColor(R.color.green), 0xFF0E9F6E.toInt())
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            } else {
                setColor(getColor(R.color.surface_sunken))
                setStroke(dp(1), getColor(R.color.divider))
            }
        }
        (btnGlobe.getChildAt(0) as? ImageView)?.imageTintList =
            ColorStateList.valueOf(getColor(if (on) R.color.text_on_accent else R.color.icon))
    }

    /** 底部代理面板：当前节点、开关、进入节点管理 */
    private fun showProxyPanel() {
        val node = V2RayManager.activeNode() ?: NodeStore.getActive(this)
        val running = V2RayManager.isRunning()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(4))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(getColor(R.color.bg_soft))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(TextView(this).apply {
            text = if (running) "代理已开启" else "代理未开启"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(if (running) R.color.green else R.color.text_primary))
        })
        card.addView(TextView(this).apply {
            text = node?.let { "${it.name}\n${it.type.name} · ${it.transportLabel()} · ${it.address}:${it.port}" }
                ?: "还没有选择节点，先去添加订阅或粘贴节点链接"
            textSize = 12.5f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val sheet = bottomSheet("代理")
        val body = sheet.findViewById<LinearLayout>(R.id.sheetBody)!!
        body.addView(content)
        body.addView(sheetAction(if (running) "关闭代理" else "开启代理", primary = true) {
            sheet.dismiss()
            toggleProxy()
        })
        body.addView(sheetAction("管理节点 / 订阅") {
            sheet.dismiss()
            startActivity(Intent(this, ProxySettingsActivity::class.java))
        })
        body.addView(sheetAction("连通测试") {
            sheet.dismiss()
            quickProbe()
        })
        sheet.show()
    }

    private fun toggleProxy() {
        if (V2RayManager.isRunning()) {
            V2RayManager.stop()
            updateShieldState()
            activeWebView()?.reload()
            toast("代理已关闭，已切回直连")
            return
        }
        val active = NodeStore.getActive(this)
        if (active == null) {
            toast("请先在「代理设置」里添加并选择一个节点")
            startActivity(Intent(this, ProxySettingsActivity::class.java))
            return
        }
        toast("正在启动内核…")
        ioExecutor.execute {
            val ok = V2RayManager.start(this, active)
            val err = V2RayManager.lastError()
            mainHandler.post {
                updateShieldState()
                if (ok) {
                    toast("代理已开启：${active.name}")
                    activeWebView()?.reload()
                } else {
                    showErrorDialog("代理启动失败", err.ifEmpty { "未知错误，请查看内核日志" })
                }
            }
        }
    }

    /** 真实连通性测试：走代理请求 generate_204 */
    private fun quickProbe() {
        if (!V2RayManager.isRunning()) {
            toast("请先开启代理")
            return
        }
        toast("测试中…")
        ioExecutor.execute {
            val ms = V2RayManager.probeThroughProxy()
            mainHandler.post {
                if (ms > 0) {
                    AlertDialog.Builder(this)
                        .setTitle("连通正常")
                        .setMessage("通过代理访问 www.gstatic.com 成功，耗时 ${ms}ms。\n\n如果网页仍打不开，多半是目标站点本身的问题，可尝试刷新或换节点。")
                        .setPositiveButton("好", null)
                        .show()
                } else {
                    showErrorDialog(
                        "连通失败",
                        "内核在运行，但通过代理访问外网失败。\n\n" +
                            "这通常意味着：节点参数与服务器不匹配（reality 的 pbk/sid、ws 的 path 最常见）、" +
                            "节点已过期/流量用尽，或该端口被网络屏蔽。\n\n建议：到「设置 → 内核日志」看 xray 的报错，" +
                            "或换一个节点重试。"
                    )
                }
            }
        }
    }

    // ===================== 标签页面板 =====================

    private fun showTabsSheet() {
        val sheet = bottomSheet("窗口 (${tabs.size})")
        val body = sheet.findViewById<LinearLayout>(R.id.sheetBody)!!

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)

        for (tab in tabs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(getColor(if (tab.id == activeTabId) R.color.accent_soft else R.color.bg_soft))
                }
                setPadding(dp(12), dp(12), dp(8), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    switchTab(tab.id)
                    sheet.dismiss()
                }
            }
            // 站点首字母方块（缩略图在 WebView 里代价太高，用字母块替代）
            row.addView(TextView(this).apply {
                text = if (tab.isHome) "P" else siteInitial(tab.url)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (tab.isHome) getColor(R.color.accent) else colorFor(tab.url))
                }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = if (tab.isHome) "主页" else tab.title.ifEmpty { "网页" }
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = if (tab.isHome) "新标签页" else tab.url
                textSize = 11.5f
                setTextColor(getColor(R.color.text_tertiary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(info)
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(getColor(R.color.icon_inactive))
                background = getDrawable(R.drawable.bg_ripple_pill)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                setOnClickListener {
                    closeTab(tab.id)
                    sheet.dismiss()
                    if (tabs.isNotEmpty()) showTabsSheet()
                }
            })
            list.addView(row)
        }
        body.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(320)
        ))
        body.addView(sheetAction("＋ 新建窗口", primary = true) {
            sheet.dismiss()
            createTab(home = true)
        })
        sheet.show()
    }

    // ===================== 菜单面板 =====================

    private fun showMenuSheet() {
        val sheet = bottomSheet("菜单")
        val body = sheet.findViewById<LinearLayout>(R.id.sheetBody)!!

        body.addView(menuRow(R.drawable.ic_refresh, "刷新页面") { activeWebView()?.reload() })
        body.addView(menuRow(R.drawable.ic_bookmark, "复制当前链接") {
            val u = activeWebView()?.url ?: ""
            if (u.isEmpty()) toast("当前没有链接") else {
                copyText(u); toast("链接已复制")
            }
        })
        body.addView(menuRow(
            R.drawable.ic_shield_on,
            if (Settings.isAdBlockEnabled(this)) "广告拦截：开" else "广告拦截：关"
        ) {
            Settings.setAdBlockEnabled(this, !Settings.isAdBlockEnabled(this))
            AdBlocker.resetBlockedCount()
            toast(if (Settings.isAdBlockEnabled(this)) "已开启广告拦截" else "已关闭广告拦截")
            activeWebView()?.reload()
        })
        body.addView(menuRow(
            R.drawable.ic_sniffer,
            if (Settings.isSniffer(this)) "视频嗅探：开" else "视频嗅探：关"
        ) {
            Settings.setSniffer(this, !Settings.isSniffer(this))
            toast(if (Settings.isSniffer(this)) "已开启视频嗅探" else "已关闭视频嗅探")
            activeWebView()?.reload()
        })
        body.addView(menuRow(R.drawable.ic_settings, "设置") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        body.addView(menuRow(R.drawable.ic_globe, "代理与节点") {
            startActivity(Intent(this, ProxySettingsActivity::class.java))
        })
        body.addView(menuRow(R.drawable.ic_close, "退出浏览器") {
            finish()
        })
        sheet.show()
    }

    private fun menuRow(icon: Int, title: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_ripple)
            setPadding(dp(10), dp(14), dp(10), dp(14))
            addView(ImageView(this@BrowserActivity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(getColor(R.color.icon))
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) }
            })
            addView(TextView(this@BrowserActivity).apply {
                text = title
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun sheetAction(text: String, primary: Boolean = false, onClick: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (primary) Color.WHITE else getColor(R.color.text_primary))
            background = if (primary) ContextCompat.getDrawable(this@BrowserActivity, R.drawable.bg_grad_accent)!!.mutate()
            else GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(getColor(R.color.surface_sunken))
            }
            setPadding(0, dp(13), 0, dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }
    }

    /** 统一的底部面板外壳（大圆角 + 拖拽条 + 标题） */
    private fun bottomSheet(title: String): BottomSheetDialog {
        val sheet = BottomSheetDialog(this, R.style.Theme_PB_Dialog)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@BrowserActivity, R.drawable.bg_sheet)!!.mutate()
            setPadding(0, dp(8), 0, dp(16))
        }
        wrap.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(getColor(R.color.divider))
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        wrap.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(20), dp(14), dp(20), dp(4))
        })
        val body = LinearLayout(this).apply {
            id = R.id.sheetBody
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        wrap.addView(body)
        sheet.setContentView(wrap)
        sheet.behavior.peekHeight = dp(420)
        return sheet
    }

    // ===================== WebViewClient =====================

    private inner class ProxyingClient(private val tab: Tab) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val scheme = request.url.scheme ?: ""
            if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("sms:") ||
                url.startsWith("intent:") || url.startsWith("magnet:") || url.endsWith(".apk", true)
            ) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                return true
            }
            // 拦截各类 App 私有 scheme，避免 ERR_UNKNOWN_URL_SCHEME
            if (scheme != "http" && scheme != "https" && scheme != "about" &&
                scheme != "javascript" && scheme != "file" && scheme.isNotEmpty()
            ) {
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (url.startsWith("data:") || url.startsWith("blob:") ||
                url.startsWith("about:") || url.startsWith("javascript:") ||
                url.startsWith("file:") || url.startsWith("chrome")
            ) return null

            if (Settings.isAdBlockEnabled(this@BrowserActivity) && AdBlocker.shouldBlock(url)) {
                return AdBlocker.emptyResponse()
            }

            // 代理没开时完全交给 WebView 自己走（系统网络栈比我们的转发更快更稳），
            // 只有走代理才需要接管 —— 这一步能省掉大量无谓的同步请求。
            if (!V2RayManager.isRunning()) return null

            // WebSocket / SSE / 视频分片：Java 转发撑不住长连接与分段请求，直接放行
            val lowUrl = url.lowercase()
            if (lowUrl.startsWith("ws://") || lowUrl.startsWith("wss://")) return null
            val accept = (request.requestHeaders ?: emptyMap())
                .entries.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
            if (accept.contains("text/event-stream", true)) return null

            // 只代理 GET / POST；其它方法（OPTIONS 预检等）放给 WebView 自己处理，
            // 它会在直连下失败，但不会像之前那样把整页拖死
            val method = (request.method ?: "GET").uppercase()
            if (method != "GET" && method != "POST") return null

            val ua = view.settings.userAgentString ?: defaultUa()
            val result = try {
                LocalProxy.fetch(
                    url = url,
                    method = method,
                    reqHeaders = request.requestHeaders ?: emptyMap(),
                    body = null,
                    userAgent = ua,
                    dnt = Settings.isDnt(this@BrowserActivity)
                )
            } catch (e: Exception) {
                null
            }

            if (result == null) {
                // 只有主文档失败时才给用户看错误页，子资源失败静默放行
                if (request.isForMainFrame) {
                    val reason = if (V2RayManager.isRunning())
                        "代理已开启，但请求没有拿到响应。"
                    else
                        "直连请求失败，请检查网络。"
                    return htmlResponse(
                        200,
                        LocalProxy.errorPage(url, reason)
                    )
                }
                return null
            }

            return WebResourceResponse(
                result.mime,
                result.encoding,
                result.status,
                result.message,
                result.headers,
                result.stream
            )
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (url == null) return
            if (url.startsWith("https://home.proxybrowser.local")) {
                tab.isHome = true
                if (tab.id == activeTabId) urlBar.setText("")
                return
            }
            tab.isHome = false
            tab.url = url
            if (tab.id == activeTabId) {
                urlBar.setText(url)
                refreshNav()
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (tab.id == activeTabId) {
                progressBar.visibility = View.INVISIBLE
                refreshNav()
                if (tab.title.isEmpty() || tab.title == "网页") tab.title = view.title ?: "网页"
            }
            // 油猴脚本
            if (Settings.isUserScript(this@BrowserActivity)) {
                runCatching {
                    val scripts = UserScriptEngine.loadAll(this@BrowserActivity)
                    val matched = UserScriptEngine.matches(url ?: "", scripts)
                    if (matched.isNotEmpty()) {
                        val js = UserScriptEngine.buildInjection(matched)
                        if (js.isNotEmpty()) view.evaluateJavascript("(function(){$js})()", null)
                    }
                }
            }
            // 视频嗅探
            if (Settings.isSniffer(this@BrowserActivity)) {
                try {
                    val js = assets.open("sniffer.js").bufferedReader().use { it.readText() }
                    view.evaluateJavascript(js, null)
                } catch (_: Exception) {}
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            if (request.isForMainFrame) {
                progressBar.visibility = View.INVISIBLE
                val desc = error.description?.toString() ?: "网络错误"
                view.loadDataWithBaseURL(
                    null,
                    LocalProxy.errorPage(request.url.toString(), "$desc（${error.errorCode}）"),
                    "text/html", "utf-8", null
                )
            }
        }
    }

    private fun htmlResponse(code: Int, html: String): WebResourceResponse =
        WebResourceResponse(
            "text/html", "utf-8", code, "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(html.toByteArray())
        )

    // ===================== JS Bridge =====================

    private inner class JsBridge {
        @JavascriptInterface
        fun report(json: String) {
            ioExecutor.execute {
                runCatching {
                    val o = org.json.JSONObject(json)
                    val url = o.optString("url")
                    if (url.isEmpty()) return@runCatching
                    val ext = VideoSniffer.extOf(url)
                    VideoSniffer.add(
                        this@BrowserActivity,
                        VideoSniffer.Media(
                            url = url,
                            type = o.optString("type", "video"),
                            pageUrl = o.optString("page", ""),
                            title = o.optString("title", ""),
                            size = 0,
                            ext = ext
                        )
                    )
                    mainHandler.post { toast("嗅探到视频 $ext") }
                }
            }
        }

        @JavascriptInterface
        fun copyToClipboard(s: String) {
            mainHandler.post { copyText(s); toast("已复制") }
        }

        @JavascriptInterface
        fun openUrl(u: String) {
            mainHandler.post { activeWebView()?.loadUrl(u) }
        }

        /** 主页用来展示代理状态，返回 {running, node, transport} */
        @JavascriptInterface
        fun state(): String {
            val node = V2RayManager.activeNode()
            return JSONObject().apply {
                put("running", V2RayManager.isRunning())
                put("node", node?.name ?: "未选择节点")
                put("transport", node?.let { " · ${it.transportLabel()}" } ?: "")
            }.toString()
        }
    }

    // ===================== 小工具 =====================

    private fun copyText(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", s))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showErrorDialog(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .setNeutralButton("查看内核日志") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_log", true))
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun siteInitial(url: String): String {
        val host = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
        return host.removePrefix("www.").take(1).uppercase().ifEmpty { "?" }
    }

    private fun colorFor(url: String): Int {
        val palette = intArrayOf(
            0xFF3A6BFF.toInt(), 0xFF12B981.toInt(), 0xFFF59E0B.toInt(),
            0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(), 0xFF06B6D4.toInt()
        )
        return palette[Math.abs(url.hashCode()) % palette.size]
    }

    companion object {
        /** 内置主页（夸克风格：大搜索框 + 快捷卡片 + 状态） */
        private const val HOME_HTML = """
<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  body{margin:0;padding:0;background:#fff;color:#16181D;
       font-family:-apple-system,"PingFang SC","HarmonyOS Sans","Microsoft YaHei",sans-serif}
  .wrap{padding:6px 20px 28px}
  .hero{margin-top:18px;margin-bottom:20px;display:flex;align-items:center;gap:12px}
  .mark{width:44px;height:44px;border-radius:14px;
        background:linear-gradient(135deg,#5B8CFF,#3A5BF0);
        display:flex;align-items:center;justify-content:center;
        box-shadow:0 6px 16px rgba(58,107,255,.28)}
  .mark svg{width:24px;height:24px}
  .hero h1{margin:0;font-size:19px;font-weight:700;letter-spacing:-.2px}
  .hero p{margin:3px 0 0;font-size:11.5px;color:#9AA1AE}
  .search{display:flex;align-items:center;height:46px;padding:0 16px;
          background:#F2F4F9;border-radius:23px;border:1px solid #EDEFF5}
  .search:focus-within{background:#fff;border-color:#BFD0FF}
  .search svg{width:17px;height:17px;color:#9AA1AE;margin-right:8px;flex:none}
  .search input{flex:1;border:0;outline:0;background:transparent;font-size:14.5px;color:#16181D}
  .search input::placeholder{color:#9AA1AE}
  .section{margin:24px 0 12px;font-size:11.5px;font-weight:700;color:#9AA1AE;
           letter-spacing:.08em;text-transform:uppercase}
  .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:9px}
  .tile{background:#F8F9FC;border-radius:16px;padding:12px 6px 10px;
        display:flex;flex-direction:column;align-items:center;cursor:pointer;
        transition:transform .12s,background .12s}
  .tile:active{transform:scale(.95);background:#EFF2F9}
  .ico{width:38px;height:38px;border-radius:12px;display:flex;align-items:center;
       justify-content:center;color:#fff;font-weight:700;font-size:16px;margin-bottom:7px}
  .tile span{font-size:10.5px;color:#5B6270;text-align:center;line-height:1.2}
  .status{margin-top:26px;background:#F8F9FC;border-radius:18px;padding:14px 16px;
          display:flex;align-items:center;gap:10px}
  .dot{width:8px;height:8px;border-radius:50%;background:#C6CBD6;flex:none}
  .dot.on{background:#12B981;box-shadow:0 0 0 4px rgba(18,185,129,.14)}
  .stext{font-size:12.5px;color:#5B6270;line-height:1.5}
  .stext b{color:#16181D;font-weight:600}
</style></head><body>
<div class="wrap">
  <div class="hero">
    <div class="mark">
      <svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round">
        <circle cx="12" cy="12" r="8.6"/><path d="M3.4 12h17.2"/>
        <path d="M12 3.4c2.4 2.4 3.6 5.4 3.6 8.6s-1.2 6.2-3.6 8.6c-2.4-2.4-3.6-5.4-3.6-8.6S9.6 5.8 12 3.4z"/>
      </svg>
    </div>
    <div>
      <h1>ProxyBrowser</h1>
      <p id="tagline">轻量代理浏览器</p>
    </div>
  </div>

  <form class="search" onsubmit="return onSearch(event)">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
      <circle cx="11" cy="11" r="6.8"/><path d="M16.1 16.1 20.2 20.2"/>
    </svg>
    <input id="q" type="text" placeholder="搜索或输入网址" autocomplete="off">
  </form>

  <div class="section">常用入口</div>
  <div class="grid">
    <div class="tile" onclick="go('https://www.google.com')">
      <div class="ico" style="background:#4285F4">G</div><span>Google</span></div>
    <div class="tile" onclick="go('https://www.youtube.com')">
      <div class="ico" style="background:#FF0033">▶</div><span>YouTube</span></div>
    <div class="tile" onclick="go('https://x.com')">
      <div class="ico" style="background:#111">𝕏</div><span>X</span></div>
    <div class="tile" onclick="go('https://web.telegram.org')">
      <div class="ico" style="background:#229ED9">✈</div><span>Telegram</span></div>
    <div class="tile" onclick="go('https://github.com')">
      <div class="ico" style="background:#24292F">G</div><span>GitHub</span></div>
    <div class="tile" onclick="go('https://www.wikipedia.org')">
      <div class="ico" style="background:#4A4A4A">W</div><span>Wiki</span></div>
    <div class="tile" onclick="go('https://www.bilibili.com')">
      <div class="ico" style="background:#00A1D6">B</div><span>B站</span></div>
    <div class="tile" onclick="go('https://www.baidu.com')">
      <div class="ico" style="background:#2932E1">百</div><span>百度</span></div>
  </div>

  <div class="status">
    <span class="dot" id="dot"></span>
    <div class="stext">
      <b id="s1">代理未开启</b><br>
      <span id="s2">点左上角地球图标，选择节点并开启代理</span>
    </div>
  </div>
</div>
<script>
function onSearch(e){
  e.preventDefault();
  var v=document.getElementById('q').value.trim();
  if(!v) return false;
  if(/^https?:\/\//i.test(v)) window.location.href=v;
  else if(/^[a-z0-9-]+(\.[a-z0-9-]+)+(\/|$)/i.test(v)) window.location.href='https://'+v;
  else window.location.href='https://www.baidu.com/s?wd='+encodeURIComponent(v);
  return false;
}
function go(u){ window.location.href=u; }
(function(){
  if(!window.PB||!window.PB.state) return;
  try{
    var s=JSON.parse(window.PB.state());
    if(s.running){
      document.getElementById('dot').className='dot on';
      document.getElementById('s1').textContent='代理已开启';
      document.getElementById('s2').textContent=s.node+s.transport;
    }
  }catch(e){}
})();
</script>
</body></html>
"""
    }
}
