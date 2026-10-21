package com.proxybrowser.app.core

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * 极简广告/追踪拦截。
 *
 * 规则集刻意保持「小而准」：只拦公认的广告/统计域名与常见埋点路径。
 * 大而全的 EasyList 在 WebView 里逐条匹配会显著拖慢首屏，
 * 且容易误伤（很多视频站的关键 JS 在 cdn 上）。
 */
object AdBlocker {

    private val hostRules = mutableListOf<String>()
    private val pathRules = mutableListOf<String>()

    /** 命中计数，用于设置页展示「已拦截 N」 */
    @Volatile var blockedCount: Int = 0
        private set

    /** 供外部清零（计数器本身只允许内部自增） */
    fun resetBlockedCount() {
        blockedCount = 0
    }

    fun loadEnabled(ctx: Context) {
        if (hostRules.isNotEmpty()) return
        hostRules.addAll(
            listOf(
                "doubleclick.net",
                "googlesyndication.com",
                "googleadservices.com",
                "google-analytics.com",
                "googletagmanager.com",
                "googletagservices.com",
                "adservice.google.com",
                "ad-delivery.net",
                "amazon-adsystem.com",
                "scorecardresearch.com",
                "quantserve.com",
                "outbrain.com",
                "taboola.com",
                "criteo.com",
                "criteo.net",
                "moatads.com",
                "adnxs.com",
                "rubiconproject.com",
                "pubmatic.com",
                "openx.net",
                "smartadserver.com",
                "adsrvr.org",
                "casalemedia.com",
                "sizmek.com",
                "umeng.com",
                "cnzz.com",
                "hm.baidu.com",
                "pos.baidu.com",
                "cpro.baidu.com",
                "gdt.qq.com",
                "ta.qq.com",
                "pingfore.qq.com",
                "mobads.baidu.com",
                "ad.xiaomi.com",
                "ad.mi.com",
                "growingio.com",
                "appsflyer.com",
                "adjust.com",
                "bugly.qq.com"
            )
        )
        pathRules.addAll(
            listOf(
                "/ads/", "/adserver/", "/advert/", "/banner/ad", "/popunder",
                "/pagead/", "/analytics.js", "/gtag/js", "/beacon.js",
                "/track.gif", "/ad-banner"
            )
        )
    }

    fun shouldBlock(url: String): Boolean {
        if (hostRules.isEmpty()) return false
        val lower = url.lowercase()
        val host = runCatching { java.net.URI(lower).host ?: "" }.getOrDefault("")
        val hit = hostRules.any { host == it || host.endsWith(".$it") } ||
            pathRules.any { lower.contains(it) }
        if (hit) blockedCount++
        return hit
    }

    /** 1x1 透明 gif，比 204 更不容易让页面脚本报错 */
    fun emptyResponse(): WebResourceResponse {
        val gif = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x21, 0xF9.toByte(),
            0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
        )
        return WebResourceResponse(
            "image/gif", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(gif)
        )
    }
}
