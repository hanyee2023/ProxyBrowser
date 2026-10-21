package com.proxybrowser.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 节点持久化：存到 SharedPreferences（JSON），重启不丢。
 * 同时记录“当前选中节点”的 key 与订阅链接列表（含订阅名称）。
 */
object NodeStore {

    private const val PREFS = "pb_nodes"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"
    private const val PREFS_SUB = "pb_subs"
    private const val KEY_SUBS = "subs"

    /** 一条订阅记录：名称 + 链接 */
    data class Subscription(val name: String, val url: String)

    private fun sp(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): MutableList<ProxyNode> {
        val raw = sp(context).getString(KEY_LIST, "[]") ?: "[]"
        val out = mutableListOf<ProxyNode>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
        }
        return out
    }

    fun save(context: Context, nodes: List<ProxyNode>) {
        val arr = JSONArray()
        nodes.forEach { arr.put(toJson(it)) }
        sp(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun setActive(context: Context, node: ProxyNode?) {
        sp(context).edit().putString(KEY_ACTIVE, node?.let { keyOf(it) } ?: "").apply()
    }

    fun getActive(context: Context): ProxyNode? {
        val k = sp(context).getString(KEY_ACTIVE, "") ?: return null
        if (k.isEmpty()) return null
        return load(context).firstOrNull { keyOf(it) == k }
    }

    fun keyOf(n: ProxyNode) = "${n.address}:${n.port}|${n.uuid}|${n.subscription}"

    // ============ 订阅管理（名称 + 链接） ============
    fun loadSubs(context: Context): MutableList<Subscription> {
        val raw = context.getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE)
            .getString(KEY_SUBS, "[]") ?: "[]"
        val out = mutableListOf<Subscription>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Subscription(o.optString("name", ""), o.optString("url", "")))
            }
        }
        return out
    }

    fun saveSubs(context: Context, subs: List<Subscription>) {
        val arr = JSONArray()
        subs.forEach { arr.put(JSONObject().apply { put("name", it.name); put("url", it.url) }) }
        context.getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUBS, arr.toString()).apply()
    }

    fun addSub(context: Context, name: String, url: String) {
        val list = loadSubs(context).toMutableList()
        val idx = list.indexOfFirst { it.url == url }
        if (idx >= 0) {
            if (name.isNotEmpty()) list[idx] = Subscription(name, url)
        } else {
            list.add(Subscription(name, url))
        }
        saveSubs(context, list)
    }

    /** 删除某个订阅链接，以及所有归属于它的节点 */
    fun deleteSub(context: Context, url: String) {
        val subs = loadSubs(context).toMutableList()
        subs.removeAll { it.url == url }
        saveSubs(context, subs)
        val nodes = load(context).filter { it.subscription != url }
        save(context, nodes)
        val active = getActive(context)
        if (active != null && active.subscription == url) setActive(context, null)
    }

    private fun toJson(n: ProxyNode) = JSONObject().apply {
        put("name", n.name)
        put("type", n.type.name)
        put("address", n.address)
        put("port", n.port)
        put("uuid", n.uuid)
        put("alterId", n.alterId)
        put("vmessSecurity", n.vmessSecurity)
        put("encryption", n.encryption)
        put("network", n.network)
        put("headerType", n.headerType)
        put("security", n.security)
        put("sni", n.sni)
        put("fingerprint", n.fingerprint)
        put("alpn", n.alpn)
        put("allowInsecure", n.allowInsecure)
        put("publicKey", n.publicKey)
        put("shortId", n.shortId)
        put("spiderX", n.spiderX)
        put("wsPath", n.wsPath)
        put("wsHost", n.wsHost)
        put("serviceName", n.serviceName)
        put("mux", n.mux)
        put("latencyMs", n.latencyMs)
        put("reachable", n.reachable)
        put("valid", n.valid)
        put("subscription", n.subscription)
    }

    private fun fromJson(o: JSONObject) = ProxyNode(
        name = o.optString("name", ""),
        type = runCatching { ProxyNode.Type.valueOf(o.optString("type", "VLESS")) }
            .getOrDefault(ProxyNode.Type.VLESS),
        address = o.optString("address", ""),
        port = o.optInt("port", 0),
        uuid = o.optString("uuid", ""),
        alterId = o.optInt("alterId", 0),
        vmessSecurity = o.optString("vmessSecurity", "auto"),
        encryption = o.optString("encryption", "none"),
        network = o.optString("network", "tcp"),
        headerType = o.optString("headerType", "none"),
        security = o.optString("security", "none"),
        sni = o.optString("sni", ""),
        fingerprint = o.optString("fingerprint", ""),
        alpn = o.optString("alpn", ""),
        allowInsecure = o.optBoolean("allowInsecure", true),
        publicKey = o.optString("publicKey", ""),
        shortId = o.optString("shortId", ""),
        spiderX = o.optString("spiderX", ""),
        wsPath = o.optString("wsPath", "/"),
        wsHost = o.optString("wsHost", ""),
        serviceName = o.optString("serviceName", ""),
        mux = o.optBoolean("mux", false),
        latencyMs = o.optLong("latencyMs", -1),
        reachable = o.optBoolean("reachable", false),
        valid = o.optBoolean("valid", true),
        subscription = o.optString("subscription", "")
    )
}
