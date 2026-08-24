package com.proxybrowser.app.core

import android.content.Context
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
import java.util.concurrent.atomic.AtomicReference

/**
 * 通过 xray-core 二进制（随 APK 作为 native lib 打包在 jniLibs/arm64-v8a/libxray.so）
 * 启动本地 SOCKS5 代理，WebView 的请求经 shouldInterceptRequest 走 127.0.0.1:10808 出去。
 *
 * 关键点：
 * 1. xray 以 libxray.so 形式打包，安装后由系统提取到 nativeLibraryDir。
 * 2. config 必须根据节点类型生成真实 outbound（vless / vmess / trojan），
 *    否则流量会走 freedom（直连），等于没代理。
 * 3. Android 10+ 禁止在 filesDir 执行二进制，必须复制到 nativeLibraryDir。
 */
object V2RayManager {

    private const val TAG = "V2RayManager"
    const val PORT = 10808

    @Volatile private var process: Process? = null
    @Volatile private var activeNode: ProxyNode? = null
    @Volatile private var running = false
    @Volatile private var logReader: Thread? = null
    private val lastError = AtomicReference<String>("")

    fun start(ctx: Context, node: ProxyNode): Boolean {
        stop()
        val binaryPath = extractXray(ctx) ?: run {
            lastError.set("xray 二进制未找到（assets/xray/xray 缺失）")
            Log.e(TAG, "xray binary not found in assets")
            return false
        }
        val configFile = File(ctx.filesDir, "v2ray_config.json")
        try {
            configFile.writeText(buildConfig(node))
        } catch (e: Exception) {
            lastError.set("写入配置文件失败")
            Log.e(TAG, "failed to write config", e)
            return false
        }

        return try {
            val cmd = listOf(binaryPath, "run", "-c", configFile.absolutePath)
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            process = pb.start()
            readLogs(process!!)

            // 给 xray 完成启动解析的时间；配置错误会立刻退出
            Thread.sleep(600)
            if (process?.isAlive != true) {
                val err = lastError.get().ifEmpty { "xray 进程立刻退出（配置不合法？）" }
                Log.e(TAG, "xray exited immediately: $err")
                cleanup()
                false
            } else if (!isSocksPortOpen(PORT, 2000)) {
                Log.e(TAG, "xray started but SOCKS port $PORT is not listening")
                cleanup()
                false
            } else {
                activeNode = node
                running = true
                Log.i(TAG, "xray started for ${node.name}")
                true
            }
        } catch (e: Exception) {
            lastError.set("启动 xray 异常：${e.javaClass.simpleName}")
            Log.e(TAG, "failed to start xray", e)
            cleanup()
            false
        }
    }

    fun stop() {
        cleanup()
    }

    private fun cleanup() {
        try { process?.destroy() } catch (_: Exception) {}
        try { logReader?.interrupt() } catch (_: Exception) {}
        process = null
        logReader = null
        activeNode = null
        running = false
    }

    fun isRunning(): Boolean = running && process?.isAlive == true
    fun activeNode(): ProxyNode? = activeNode
    fun port(): Int = PORT
    fun lastError(): String = lastError.get()

    /**
     * 应用启动时预热：把 xray 二进制释放到 nativeLibraryDir。
     * 失败不抛异常，避免 App 启动崩溃。
     */
    fun ensureInstalled(ctx: Context) {
        extractXray(ctx)
    }

    private fun extractXray(ctx: Context): String? {
        // Android 10+ 只允许在 nativeLibraryDir 执行 ELF 二进制。
        // xray 作为 libxray.so 随 APK 打包，系统安装时已提取到 nativeLibraryDir。
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        val dest = File(libDir, "libxray.so")
        if (dest.exists() && dest.canExecute()) return dest.absolutePath
        lastError.set("xray 二进制未找到（${dest.absolutePath} 缺失或不可执行）")
        Log.e(TAG, "xray binary not found at ${dest.absolutePath}")
        return null
    }

    private fun readLogs(proc: Process) {
        lastError.set("")
        val t = Thread({
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val txt = line ?: ""
                        Log.d(TAG, txt)
                        // 记录最后一条 error/warning 以便 toast 展示
                        if (txt.contains("failed", true) || txt.contains("error", true) ||
                            txt.contains("invalid", true) || txt.contains("cannot", true)) {
                            lastError.set(txt.take(120))
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {}
        }, "xray-logger")
        t.isDaemon = true
        t.start()
        logReader = t
    }

    private fun isSocksPortOpen(port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    } catch (_: Exception) { false }

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
