package com.proxybrowser.app.core

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * 浏览器请求的统一出口。
 *
 * 调度策略：
 * - 代理开启：转到 xray 的 HTTP 入站（127.0.0.1:10809），由 xray 做远程 DNS + TLS + 分流；
 * - 代理关闭：直连。
 *
 * 为什么不用 Java 的 SOCKS 实现？
 * java.net.SocksSocketImpl 在本机只做 SOCKS5 CONNECT，很多 ROM 下走
 * `Proxy(Type.SOCKS)` 时 WebView 拿到的结果不稳定，而且它对 chunked / gzip 的处理
 * 依赖 HttpURLConnection 的假设，遇到 CDN 会返回半截页面 ——
 * 这正是旧版「节点端口通、网页打不开」的第二层原因。
 * 改成「本地 HTTP 转发 → xray 原生 HTTP 代理」后链路与 Clash/v2rayNG 一致。
 */
object LocalProxy {

    private const val TAG = "LocalProxy"
    private const val UA_FALLBACK =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    data class Result(
        val status: Int,
        val message: String,
        val mime: String,
        val encoding: String,
        val headers: Map<String, String>,
        val stream: InputStream,
        /** 代理链路出错时带上原因，便于在页面上直接看到 */
        val error: String? = null
    )

    /** 是否此刻走代理 */
    fun proxied(): Boolean = V2RayManager.isRunning()

    private fun proxyOrNull(): Proxy? =
        if (proxied()) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", XrayConfig.HTTP_PORT))
        else null

    fun fetch(
        url: String,
        method: String,
        reqHeaders: Map<String, String>,
        body: ByteArray?,
        userAgent: String,
        dnt: Boolean,
        timeoutMs: Int = 20000
    ): Result? {
        var currentUrl = url
        var currentMethod = method.uppercase(Locale.ROOT)
        var currentBody = body
        var redirects = 0
        val cookieJar = mutableListOf<String>()

        while (redirects <= 5) {
            val conn = try {
                openConnection(currentUrl, currentMethod, reqHeaders, currentBody, userAgent, dnt,
                    cookieJar, timeoutMs)
            } catch (e: Exception) {
                Log.w(TAG, "connect failed $currentUrl: ${e.message}")
                return null
            }

            val code = try { conn.responseCode } catch (e: Exception) {
                Log.w(TAG, "no response $currentUrl: ${e.message}")
                conn.disconnect()
                return null
            }

            // 记录 Set-Cookie，跨域重定向时保持会话
            conn.headerFields?.forEach { (k, v) ->
                if (k != null && k.equals("Set-Cookie", true)) v?.forEach { cookieJar.add(it) }
            }

            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty() || redirects == 5) return null
                currentUrl = resolve(currentUrl, loc)
                if (code == 303) { currentMethod = "GET"; currentBody = null }
                redirects++
                continue
            }

            val rawStream: InputStream = try {
                if (code >= 400) (conn.errorStream ?: conn.inputStream) else conn.inputStream
            } catch (e: Exception) {
                conn.errorStream ?: return null
            }
            val contentType = conn.contentType ?: guessMime(currentUrl)
            val charset = charsetOf(contentType)
            val encoding = (conn.contentEncoding ?: "").lowercase(Locale.ROOT)
            val headers = LinkedHashMap<String, String>()
            conn.headerFields?.forEach { (k, v) ->
                if (k == null) return@forEach
                // 这些头交给 WebView 自己处理，转发反而出错
                if (k.equals("Content-Encoding", true) || k.equals("Content-Length", true) ||
                    k.equals("Transfer-Encoding", true) || k.equals("Connection", true)
                ) return@forEach
                headers[k] = v.joinToString(", ")
            }
            val decoded = decode(rawStream, encoding)
            return Result(
                status = code,
                message = conn.responseMessage ?: "",
                mime = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" },
                encoding = charset,
                headers = headers,
                stream = decoded
            )
        }
        return null
    }

    private fun openConnection(
        url: String,
        method: String,
        reqHeaders: Map<String, String>,
        body: ByteArray?,
        userAgent: String,
        dnt: Boolean,
        cookies: List<String>,
        timeoutMs: Int
    ): HttpURLConnection {
        val px = proxyOrNull()
        val conn = (if (px != null) URL(url).openConnection(px) else URL(url).openConnection())
            as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = method
        conn.useCaches = false

        reqHeaders.forEach { (k, v) ->
            if (k.equals("Host", true) || k.equals("Connection", true) ||
                k.equals("Accept-Encoding", true) || k.equals("Content-Length", true) ||
                k.equals("Content-Type", true) || k.startsWith(":", true) ||
                k.startsWith("Sec-", true) || k.equals("Upgrade-Insecure-Requests", true)
            ) return@forEach
            runCatching { conn.setRequestProperty(k, v) }
        }
        if (conn.getRequestProperty("User-Agent") == null) {
            conn.setRequestProperty("User-Agent", userAgent.ifEmpty { UA_FALLBACK })
        }
        if (dnt) conn.setRequestProperty("DNT", "1")
        if (cookies.isNotEmpty()) {
            conn.setRequestProperty("Cookie", cookies.joinToString("; ") { it.substringBefore(';') })
        }
        // 自己声明可接受 gzip，再在本地解压；否则部分 CDN 返回 br，Java 解不了
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate")

        val ct = reqHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
        if (ct != null) conn.setRequestProperty("Content-Type", ct)

        if (body != null && body.isNotEmpty()) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        return conn
    }

    private fun decode(input: InputStream, encoding: String): InputStream = try {
        when {
            encoding.contains("gzip") -> GZIPInputStream(BufferedInputStream(input))
            encoding.contains("deflate") -> InflaterInputStream(BufferedInputStream(input))
            else -> input
        }
    } catch (e: Exception) {
        Log.w(TAG, "decode failed: ${e.message}")
        input
    }

    private fun charsetOf(contentType: String): String {
        val m = Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE).find(contentType)
        return m?.groupValues?.get(1)?.lowercase() ?: "utf-8"
    }

    private fun guessMime(url: String): String {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    /** 相对 Location 转绝对 URL */
    fun resolve(base: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) return location
        return try {
            java.net.URI(base).resolve(location).toString()
        } catch (_: Exception) {
            if (location.startsWith("//")) "https:$location"
            else base.substringBefore('/', "") + location
        }
    }

    /** 空响应（用于广告拦截） */
    fun emptyStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    /** 代理出错时给 WebView 的提示页，直接显示原因，不用猜 */
    fun errorPage(url: String, reason: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        body{font-family:-apple-system,'PingFang SC',sans-serif;background:#f6f7fb;color:#1b1b1f;
        margin:0;padding:48px 24px;line-height:1.7}
        h2{font-size:19px;margin:0 0 10px}
        .reason{background:#fff;border-radius:16px;padding:16px 18px;font-size:13px;color:#5b5b66;
        word-break:break-all;box-shadow:0 2px 10px rgba(20,40,90,.06)}
        .tip{margin-top:18px;font-size:13px;color:#6b6b76}
        code{background:#eef1f8;padding:2px 6px;border-radius:6px;font-size:12px}
        </style></head><body>
        <h2>页面加载失败</h2>
        <div class="reason">$reason</div>
        <div class="tip">
        常见原因：<br>
        1. 节点参数与服务器不匹配（如 reality 缺少 pbk/sid、ws 路径写错）；<br>
        2. 节点已过期或流量用尽；<br>
        3. 当前网络屏蔽了该端口。<br><br>
        可在「设置 → 代理设置」里点<b>连通测试</b>查看具体错误，
        或到「设置 → 内核日志」看 xray 的原始输出。<br><br>
        <code>$url</code>
        </div></body></html>
    """.trimIndent()
}
