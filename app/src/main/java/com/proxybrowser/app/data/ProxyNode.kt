package com.proxybrowser.app.data

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.util.zip.GZIPInputStream

/**
 * 节点模型。字段覆盖 vless / vmess / trojan 三种协议，
 * 以及 tcp / ws / grpc / h2 / httpupgrade / xhttp 六种传输方式，
 * 安全层覆盖 none / tls / reality。
 */
data class ProxyNode(
    val name: String,
    val type: Type,
    val address: String,
    val port: Int,
    /** vless/vmess 的 id；trojan 的 password；ss 的 method:password */
    val uuid: String = "",
    val alterId: Int = 0,
    /** vmess 加密方式，一般 auto */
    val vmessSecurity: String = "auto",
    /** vless encryption，一般 none */
    val encryption: String = "none",

    /** 传输层：tcp / ws / grpc / h2 / httpupgrade / xhttp / kcp / quic */
    val network: String = "tcp",
    /** tcp 的伪装类型：none / http */
    val headerType: String = "none",

    /** 安全层：none / tls / reality */
    val security: String = "none",
    /** TLS SNI（部分机场写作 peer / servername） */
    val sni: String = "",
    /** 证书指纹，chrome / firefox / safari / randomized / android */
    val fingerprint: String = "",
    /** ALPN，逗号分隔，如 h2,http/1.1 */
    val alpn: String = "",
    /** true 时跳过证书校验（自签证书节点需要） */
    val allowInsecure: Boolean = true,

    /** REALITY 公钥 */
    val publicKey: String = "",
    /** REALITY shortId */
    val shortId: String = "",
    /** REALITY spiderX，一般留空 */
    val spiderX: String = "",

    /** ws / httpupgrade 的 path；h2 的 path 也用这个字段 */
    val wsPath: String = "/",
    /** ws / httpupgrade / h2 的 Host 头 */
    val wsHost: String = "",
    /** grpc / xhttp 的 serviceName / path */
    val serviceName: String = "",

    /** 是否为 mux（smux）多路复用节点 */
    val mux: Boolean = false,

    var latencyMs: Long = -1,
    /** 真实 TCP 握手测速结果，与 latencyMs 区分：0 表示还没测过 */
    var reachable: Boolean = false,
    var valid: Boolean = true,
    /** 来源订阅链接；手动添加的为空字符串，归类到「未分组」 */
    var subscription: String = ""
) {
    enum class Type { VLESS, VMESS, TROJAN }

    /** 传输层摘要，用于列表展示 */
    fun transportLabel(): String {
        val net = network.ifEmpty { "tcp" }
        val sec = when (security.lowercase()) {
            "reality" -> "reality"
            "tls" -> "tls"
            else -> ""
        }
        return if (sec.isEmpty()) net else "$net+$sec"
    }
}

object NodeParser {

    fun parseSingle(uri: String): ProxyNode? {
        val u = uri.trim()
        return when {
            u.startsWith("vless://", true) -> parseVless(u)
            u.startsWith("vmess://", true) -> parseVmess(u)
            u.startsWith("trojan://", true) -> parseTrojan(u)
            else -> null
        }
    }

    /**
     * 兼容多种订阅格式：
     * - 明文节点列表（每行一个 vless:// / vmess:// / trojan://）
     * - 整段 base64 编码（绝大多数订阅站点的返回）
     * - 每行各自 base64 编码
     * - gzip 压缩（base64 或明文的 gzip 流）
     */
    fun parse(body: String): List<ProxyNode> {
        val out = mutableListOf<ProxyNode>()
        for (cand in decodeCandidates(body)) {
            for (line in cand.lineSequence()) {
                val t = line.trim()
                // 允许 base64 文本被折行：只要不是节点行就继续
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue
                var node = parseSingle(t)
                if (node == null) node = tryBase64Line(t)
                node?.let {
                    if (it.address.isNotEmpty() && it.port > 0) out.add(it)
                }
            }
        }
        return out.distinctBy { "${it.name}|${it.address}:${it.port}|${it.uuid}" }
    }

    /** 尝试把订阅正文还原成明文节点列表（整段 base64 / gzip） */
    private fun decodeCandidates(body: String): List<String> {
        val cands = mutableListOf(body)
        val trimmed = body.trim().replace(Regex("\\s+"), "")
        if (trimmed.length > 16) {
            runCatching {
                val bytes = b64(trimmed)
                val plain = tryGunzip(bytes) ?: bytes
                val text = String(plain, Charsets.UTF_8)
                if (looksLikeNodes(text)) cands.add(text)
            }
        }
        return cands
    }

    private fun tryBase64Line(line: String): ProxyNode? {
        if (line.length < 16) return null
        return runCatching {
            val bytes = b64(line)
            val plain = tryGunzip(bytes) ?: bytes
            val text = String(plain, Charsets.UTF_8).trim()
            if (looksLikeNodes(text)) parseSingle(text) else null
        }.getOrNull()
    }

    /** 宽松 base64 解码，兼容 URL-safe 与缺失 padding 的订阅 */
    private fun b64(s: String): ByteArray {
        val norm = s.replace('-', '+').replace('_', '/')
        val pad = (4 - norm.length % 4) % 4
        val padded = norm + "=".repeat(pad)
        return Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP)
    }

    private fun tryGunzip(bytes: ByteArray): ByteArray? = runCatching {
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } else null
    }.getOrNull()

    private fun looksLikeNodes(t: String): Boolean =
        t.contains("vless://") || t.contains("vmess://") || t.contains("trojan://")

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** 拆出 query 参数表（key 全部小写，value 已 URL 解码） */
    private fun queryMap(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isEmpty()) return map
        for (kv in query.split('&')) {
            if (kv.isEmpty()) continue
            val eq = kv.indexOf('=')
            val k = if (eq < 0) kv else kv.substring(0, eq)
            val v = if (eq < 0) "" else kv.substring(eq + 1)
            map[k.lowercase()] = decode(v)
        }
        return map
    }

    private fun boolOf(v: String?): Boolean =
        v == "1" || v.equals("true", true) || v.equals("yes", true)

    private fun parseVless(uri: String): ProxyNode? = runCatching {
        val body = uri.substringAfter("://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) decode(body.substring(hashIdx + 1)) else ""
        val core = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val qIdx = core.indexOf('?')
        val query = if (qIdx >= 0) core.substring(qIdx + 1) else ""
        val authority = if (qIdx >= 0) core.substring(0, qIdx) else core
        val atIdx = authority.lastIndexOf('@')
        val uuid = if (atIdx >= 0) decode(authority.substring(0, atIdx)) else ""
        val hp = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
        val host = hostOf(hp)
        val port = portOf(hp)

        val q = queryMap(query)
        val network = q["type"].orEmpty().ifEmpty { "tcp" }
        val security = q["security"].orEmpty().ifEmpty { "none" }
        val sni = q["sni"] ?: q["peer"] ?: q["servername"] ?: ""
        val wsPath = q["path"].orEmpty().ifEmpty { "/" }
        val wsHost = q["host"].orEmpty()
        val headerType = q["headertype"].orEmpty().ifEmpty { "none" }

        ProxyNode(
            name = name.ifEmpty { "$host:$port" },
            type = ProxyNode.Type.VLESS,
            address = host,
            port = port,
            uuid = uuid,
            encryption = q["encryption"].orEmpty().ifEmpty { "none" },
            network = network,
            headerType = headerType,
            security = security,
            sni = sni,
            fingerprint = q["fp"].orEmpty(),
            alpn = q["alpn"].orEmpty(),
            allowInsecure = boolOf(q["allowinsecure"]) || boolOf(q["insecure"]),
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            wsPath = wsPath,
            wsHost = wsHost,
            serviceName = q["servicename"].orEmpty().ifEmpty { wsPath },
            mux = q["mux"].orEmpty().toIntOrNull() == 1
        )
    }.getOrNull()

    private fun parseTrojan(uri: String): ProxyNode? = runCatching {
        val body = uri.substringAfter("://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) decode(body.substring(hashIdx + 1)) else ""
        val core = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val qIdx = core.indexOf('?')
        val query = if (qIdx >= 0) core.substring(qIdx + 1) else ""
        val authority = if (qIdx >= 0) core.substring(0, qIdx) else core
        val atIdx = authority.lastIndexOf('@')
        val password = if (atIdx >= 0) decode(authority.substring(0, atIdx)) else ""
        val hp = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
        val host = hostOf(hp)
        val port = portOf(hp)

        val q = queryMap(query)
        val network = q["type"].orEmpty().ifEmpty { "tcp" }
        val security = q["security"].orEmpty().ifEmpty { "tls" }
        val wsPath = q["path"].orEmpty().ifEmpty { "/" }

        ProxyNode(
            name = name.ifEmpty { "$host:$port" },
            type = ProxyNode.Type.TROJAN,
            address = host,
            port = port,
            uuid = password,
            network = network,
            headerType = q["headertype"].orEmpty().ifEmpty { "none" },
            security = security,
            sni = q["sni"] ?: q["peer"] ?: "",
            fingerprint = q["fp"].orEmpty(),
            alpn = q["alpn"].orEmpty(),
            allowInsecure = boolOf(q["allowinsecure"]) || boolOf(q["insecure"]),
            wsPath = wsPath,
            wsHost = q["host"].orEmpty(),
            serviceName = q["servicename"].orEmpty().ifEmpty { wsPath }
        )
    }.getOrNull()

    private fun parseVmess(uri: String): ProxyNode? = runCatching {
        val b64 = uri.substringAfter("://")
        val text = String(b64(b64), Charsets.UTF_8).trim()
        val o = JSONObject(text)
        val net = o.optString("net", "tcp").ifEmpty { "tcp" }
        val tlsRaw = o.optString("tls", "")
        val security = when {
            tlsRaw.equals("tls", true) -> "tls"
            tlsRaw.equals("reality", true) -> "reality"
            tlsRaw.equals("xtls", true) -> "tls"
            else -> "none"
        }
        val host = o.optString("add", "")
        val port = o.optInt("port", 0).let { if (it > 0) it else o.optString("port", "443").toIntOrNull() ?: 443 }
        ProxyNode(
            name = o.optString("ps", "").ifEmpty { "$host:$port" },
            type = ProxyNode.Type.VMESS,
            address = host,
            port = port,
            uuid = o.optString("id", ""),
            alterId = o.optString("aid", "0").toIntOrNull() ?: 0,
            vmessSecurity = o.optString("scy", "auto").ifEmpty { "auto" },
            network = net,
            headerType = o.optString("type", "none").ifEmpty { "none" },
            security = security,
            sni = o.optString("sni", "").ifEmpty { o.optString("peer", "") },
            fingerprint = o.optString("fp", ""),
            alpn = o.optString("alpn", ""),
            allowInsecure = o.optString("allowInsecure", "0") == "1" ||
                o.optString("skip-cert-verify", "0") == "1",
            wsPath = o.optString("path", "/").ifEmpty { "/" },
            wsHost = o.optString("host", ""),
            serviceName = o.optString("path", "").ifEmpty { o.optString("serviceName", "") }
        )
    }.getOrNull()

    /** 解析 host:port，兼容 [IPv6]:port */
    private fun hostOf(hp: String): String {
        if (hp.startsWith("[")) return hp.substringBefore(']').removePrefix("[")
        return hp.substringBeforeLast(':', hp)
    }

    private fun portOf(hp: String): Int {
        val p = if (hp.startsWith("[")) hp.substringAfter("]:", "") else hp.substringAfterLast(':', "")
        return p.toIntOrNull() ?: 443
    }
}
