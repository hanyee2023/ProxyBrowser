package com.proxybrowser.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视频嗅探结果仓库：内存列表 + 持久化（关掉 App 也不丢）。
 */
object VideoSniffer {

    private const val PREFS = "pb_sniffer"
    private const val KEY_LIST = "list"
    private const val MAX_ITEMS = 200

    data class Media(
        val url: String,
        val type: String,
        val pageUrl: String,
        val title: String,
        val size: Long,
        val ext: String,
        val time: Long = System.currentTimeMillis()
    )

    private val items = mutableListOf<Media>()
    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIST, "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    Media(
                        url = o.optString("url"),
                        type = o.optString("type", "video"),
                        pageUrl = o.optString("pageUrl"),
                        title = o.optString("title"),
                        size = o.optLong("size", 0),
                        ext = o.optString("ext", ".mp4"),
                        time = o.optLong("time", System.currentTimeMillis())
                    )
                )
            }
        }
    }

    @Synchronized
    fun add(ctx: Context, media: Media) {
        load(ctx)
        if (items.any { it.url == media.url }) return
        items.add(0, media)
        while (items.size > MAX_ITEMS) items.removeAt(items.size - 1)
        persist(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, url: String) {
        load(ctx)
        items.removeAll { it.url == url }
        persist(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        items.clear()
        persist(ctx)
    }

    @Synchronized
    fun getAll(ctx: Context? = null): List<Media> {
        if (ctx != null) load(ctx)
        return items.toList()
    }

    /** 别名，兼容旧调用 */
    @Synchronized
    fun all(ctx: Context? = null): List<Media> = getAll(ctx)

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("url", it.url)
                put("type", it.type)
                put("pageUrl", it.pageUrl)
                put("title", it.title)
                put("size", it.size)
                put("ext", it.ext)
                put("time", it.time)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** 根据 URL 猜扩展名 */
    fun extOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val i = path.lastIndexOf('.')
        if (i < 0 || path.length - i > 6) {
            return if (url.contains("m3u8")) ".m3u8" else ".mp4"
        }
        return path.substring(i).lowercase()
    }
}
