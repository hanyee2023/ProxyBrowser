package com.proxybrowser.app.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.proxybrowser.app.data.ProxyNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object V2RayManager {

    private const val TAG = "V2RayManager"
    const val PORT = 10808

    @Volatile private var process: Process? = null
    @Volatile private var activeNode: ProxyNode? = null
    @Volatile private var running = false
    private val executor = Executors.newSingleThreadExecutor()

    fun start(ctx: Context, node: ProxyNode): Boolean {
        stop()
        val binaryPath = extractXray(ctx) ?: run {
            Log.e(TAG, "xray binary not found in assets")
            return false
        }
        val configFile = File(ctx.filesDir, "v2ray_config.json")
        try {
            configFile.writeText(buildConfig(node))
        } catch (e: Exception) {
            Log.e(TAG, "failed to write config", e)
            return false
        }

        return try {
            val cmd = listOf(binaryPath, "run", "-c", configFile.absolutePath)
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            // 后台排空 xray 输出，避免管道写满导致进程卡死
            val errLog = StringBuilder()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            executor.execute {
                try { reader.forEachLine { errLog.appendLine(it) } } catch (_: Exception) {}
            }
            // 等 SOCKS 端口真正就绪；若 xray 因配置错误立即退出则直接判定失败
            val deadline = System.currentTimeMillis() + 4000
            var bound = false
            while (System.currentTimeMillis() < deadline) {
                if (proc.isAlive.not()) {
                    Log.e(TAG, "xray exited immediately (config error?). last log:\n$errLog")
                    running = false
                    process = null
                    return false
                }
                if (tryConnect()) { bound = true; break }
                Thread.sleep(120)
            }
            if (!bound) {
                Log.e(TAG, "xray started but SOCKS port $PORT not ready. last log:\n$errLog")
                running = false
                try { proc.destroy() } catch (_: Exception) {}
                process = null
                return false
            }
            running = true
            activeNode = node
            Log.i(TAG, "xray started for ${node.name}")
            applySystemProxy(ctx)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to start xray", e)
            running = false
            false
        }
    }

    fun stop() {
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        activeNode = null
        running = false
        clearSystemProxy()
    }

    fun isRunning(): Boolean = running && process?.isAlive == true

    /** 预热：把 assets 里的 xray 二进制解压到 filesDir 并赋可执行权限 */
    fun ensureInstalled(ctx: Context): Boolean = extractXray(ctx) != null

    fun activeNode(): ProxyNode? = activeNode
    fun port(): Int = PORT

    /** API 28+ 由系统 WebView 代理接管，shouldInterceptRequest 不再做 SOCKS 转发 */
    fun proxyHandledBySystem(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private fun tryConnect(): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", PORT), 300) }
        true
    } catch (_: Exception) { false }

    /**
     * 通过系统 WebView 代理（ProxyController，API 28+）把浏览器流量路由到本地 SOCKS5。
     * 用反射调用，避免某些构建环境下 android.webkit.ProxyController 在编译期不可见。
     */
    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun applySystemProxy(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val ctrlCls = Class.forName("android.webkit.ProxyController")
            val controller = ctrlCls.getMethod("getInstance").invoke(null)!!
            val builderCls = Class.forName("android.webkit.ProxyConfig\$Builder")
            val builder = builderCls.getConstructor().newInstance()
            builderCls.getMethod("addProxyRule", String::class.java)
                .invoke(builder, "socks5://127.0.0.1:$PORT")
            val config = builderCls.getMethod("build").invoke(builder)!!
            val listener = Runnable { }
            ctrlCls.getMethod(
                "setProxyOverride",
                Class.forName("android.webkit.ProxyConfig"),
                Executor::class.java,
                Runnable::class.java
            ).invoke(controller, config, executor, listener)
        } catch (e: Exception) {
            Log.e(TAG, "applySystemProxy failed", e)
        }
    }

    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun clearSystemProxy() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val ctrlCls = Class.forName("android.webkit.ProxyController")
            val controller = ctrlCls.getMethod("getInstance").invoke(null)!!
            val listener = Runnable { }
            ctrlCls.getMethod("clearProxyOverride", Executor::class.java, Runnable::class.java)
                .invoke(controller, executor, listener)
        } catch (e: Exception) {
            Log.e(TAG, "clearSystemProxy failed", e)
        }
    }

    private fun extractXray(ctx: Context): String? {
        // Android 10+ 禁止在 filesDir 执行二进制；nativeLibraryDir 仍可执行
        val nativeDir = ctx.applicationInfo.nativeLibraryDir?.let { File(it) }
        val destDir = if (nativeDir != null && nativeDir.exists()) nativeDir else ctx.filesDir
        val dest = File(destDir, "xray")
        if (dest.exists() && dest.canExecute() && dest.length() > 0) return dest.absolutePath
        return try {
            ctx.assets.open("xray/xray").use { inStream ->
                dest.outputStream().use { out -> inStream.copyTo(out) }
            }
            dest.setExecutable(true)
            if (!dest.canExecute()) {
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()
                } catch (_: Exception) {}
            }
            if (dest.canExecute()) dest.absolutePath else null
        } catch (e: IOException) {
            Log.e(TAG, "extract xray failed", e)
            null
        }
    }

    // ============ xray 配置 ============
    private fun buildConfig(node: ProxyNode): String {
        val outbound = when (node.type) {
            ProxyNode.Type.VMESS -> JSONObject().apply {
                put("protocol", "vmess")
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", node.uuid)
                            put("alterId", node.alterId)
                            put("security", "auto")
                            put("level", 0)
                        }))
                    }))
                })
                put("streamSettings", buildStream(node))
            }
            ProxyNode.Type.VLESS -> JSONObject().apply {
                put("protocol", "vless")
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", node.uuid)
                            put("encryption", if (node.encryption.isNotEmpty()) node.encryption else "none")
                            put("level", 0)
                        }))
                    }))
                })
                put("streamSettings", buildStream(node))
            }
            ProxyNode.Type.TROJAN -> JSONObject().apply {
                put("protocol", "trojan")
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("password", node.uuid)
                        put("level", 0)
                    }))
                })
                put("streamSettings", buildStream(node))
            }
        }

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("access", "")
                put("error", "")
                put("loglevel", "warning")
            })
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("port", PORT)
                put("protocol", "socks")
                put("listen", "127.0.0.1")
                put("settings", JSONObject().apply { put("auth", "noauth") })
            }))
            put("outbounds", JSONArray().apply {
                put(outbound)
                put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
                put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
            })
            put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("rules", JSONArray())
            })
        }.toString()
    }

    private fun buildStream(node: ProxyNode): JSONObject {
        val net = if (node.network.isNotEmpty()) node.network else "tcp"
        val st = JSONObject().apply { put("network", net) }
        if (net == "ws") {
            st.put("wsSettings", JSONObject().apply {
                put("path", if (node.wsPath.isNotEmpty()) node.wsPath else "/")
                put("headers", JSONObject().apply {
                    if (node.wsHost.isNotEmpty()) put("Host", node.wsHost)
                })
            })
        }
        val security = when (node.type) {
            ProxyNode.Type.TROJAN -> "tls"
            ProxyNode.Type.VLESS -> if (node.security.isNotEmpty()) node.security
                else if (node.sni.isNotEmpty()) "tls" else "none"
            ProxyNode.Type.VMESS -> if (node.security.isNotEmpty()) node.security else "none"
        }
        if (security == "tls") {
            st.put("security", "tls")
            st.put("tlsSettings", JSONObject().apply {
                if (node.sni.isNotEmpty()) put("serverName", node.sni)
                put("allowInsecure", true)
            })
        } else {
            st.put("security", "none")
        }
        return st
    }
}
