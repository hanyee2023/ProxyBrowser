package com.proxybrowser.app.core

import android.content.Context
import android.util.Log
import com.proxybrowser.app.data.ProxyNode
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * xray-core 进程生命周期管理。
 *
 * 关键点（也是上一版「能 ping 通但打不开网页」的根因）：
 * 1. 二进制必须以 libxray.so 打包进 jniLibs，由系统安装时解压到 nativeLibraryDir；
 *    Android 10+ 只允许在该目录执行 ELF。
 * 2. 配置必须完整（见 XrayConfig）：缺 pbk/sid/path/SNI 时 xray 照样启动监听，
 *    但流量被服务端丢弃 —— 这就是「端口通、网页打不开」。
 * 3. 测速必须走「代理真实请求一个网页」，只做 TCP 握手只能证明服务器端口活着，
 *    不能证明账号/参数正确。旧版用的就是这个会骗人的测速。
 */
object V2RayManager {

    private const val TAG = "V2RayManager"

    val SOCKS_PORT = XrayConfig.SOCKS_PORT
    val HTTP_PORT = XrayConfig.HTTP_PORT

    @Volatile private var process: Process? = null
    @Volatile private var activeNode: ProxyNode? = null
    @Volatile private var running = false
    @Volatile private var logReader: Thread? = null
    @Volatile private var binaryPath: String? = null
    private val lastError = AtomicReference<String>("")

    /** 环形日志，供「内核日志」弹窗展示 */
    private const val MAX_LOG = 400
    private val logs = ArrayDeque<String>()

    fun start(ctx: Context, node: ProxyNode): Boolean {
        stop()
        lastError.set("")
        val bin = locateBinary(ctx) ?: return false

        val configText = try {
            XrayConfig.build(node)
        } catch (e: Exception) {
            // 明文 vless 之类「协议层面就不可能工作」的节点会走到这里，
            // 把原始原因原样带给用户，而不是给一个笼统的失败。
            val msg = e.message ?: "生成配置失败"
            lastError.set(msg)
            appendLog("[配置] ✗ $msg")
            return false
        }
        val configFile = File(ctx.filesDir, "xray_config.json")
        try {
            configFile.writeText(configText)
        } catch (e: Exception) {
            lastError.set("写入配置文件失败")
            Log.e(TAG, "write config failed", e)
            return false
        }
        if (BuildFlags.DEBUG) appendLog("[配置] $configText")

        return try {
            val pb = ProcessBuilder(listOf(bin, "run", "-c", configFile.absolutePath))
            pb.redirectErrorStream(true)
            // 部分机型需要在 filesDir 作为工作目录，避免 xray 读不到资源
            pb.directory(ctx.filesDir)
            val proc = pb.start()
            process = proc
            readLogs(proc)

            // 等端口起来：轮询而不是死等 600ms，慢机也能起来
            var ok = false
            for (i in 0 until 20) {
                Thread.sleep(150)
                if (proc.isAlive.not()) break
                if (isPortOpen(HTTP_PORT, 300)) { ok = true; break }
            }
            when {
                !proc.isAlive -> {
                    val err = lastError.get().ifEmpty { "xray 进程立刻退出（配置不合法）" }
                    appendLog("[失败] $err")
                    cleanup()
                    false
                }
                !ok -> {
                    val err = lastError.get().ifEmpty { "xray 已启动但端口 $HTTP_PORT 未监听" }
                    appendLog("[失败] $err")
                    cleanup()
                    false
                }
                else -> {
                    activeNode = node
                    running = true
                    appendLog("[启动] ${node.name} (${node.type} ${node.transportLabel()})")
                    true
                }
            }
        } catch (e: Exception) {
            lastError.set("启动 xray 异常：${e.javaClass.simpleName} ${e.message ?: ""}")
            appendLog("[异常] ${e.message}")
            cleanup()
            false
        }
    }

    fun stop() {
        if (running) appendLog("[停止] 代理已关闭")
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
    fun lastError(): String = lastError.get()

    fun ensureInstalled(ctx: Context) {
        runCatching { locateBinary(ctx) }
    }

    fun logs(): List<String> = synchronized(logs) { logs.toList() }

    fun clearLogs() = synchronized(logs) { logs.clear() }

    private fun appendLog(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        synchronized(logs) {
            logs.addLast("$stamp  $line")
            while (logs.size > MAX_LOG) logs.removeFirst()
        }
    }

    // ============ 二进制 ============

    /**
     * 定位可执行的 xray 二进制。
     *
     * 踩过的坑：APK 里打包的 libxray.so，其 zip 内 Unix 权限位是 644（无执行位），
     * 系统解压到 nativeLibraryDir 后也是 rw-r--r--。
     * 所以绝不能只用 canExecute() 判断，否则永远找不到内核。
     * 正确做法：只要文件存在且体积合理，就主动 chmod 755 再执行。
     */
    private fun locateBinary(ctx: Context): String? {
        binaryPath?.let { if (File(it).exists() && File(it).length() > 1024 * 1024) return it }

        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        val candidates = listOf(
            File(libDir, "libxray.so"),
            File(libDir, "libxray.so.so"),
            File(ctx.applicationInfo.sourceDir).parentFile?.let { File(it, "lib/arm64/libxray.so") }
        ).filterNotNull()

        for (f in candidates) {
            if (!f.exists() || f.length() <= 1024 * 1024) continue
            if (!ensureExecutable(f)) continue
            binaryPath = f.absolutePath
            appendLog("[内核] 已找到 xray 二进制（${f.length() / 1024 / 1024} MB，${f.absolutePath}）")
            return f.absolutePath
        }

        // 兜底：复制到 codeCacheDir 再 chmod（部分定制系统 nativeLibraryDir 不可执行）
        val fallback = File(ctx.codeCacheDir, "xray")
        val source = candidates.firstOrNull { it.exists() && it.length() > 1024 * 1024 }
        if (source != null) {
            val copied = runCatching {
                fallback.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    fallback.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }.getOrDefault(false)
            if (copied && ensureExecutable(fallback)) {
                binaryPath = fallback.absolutePath
                appendLog("[内核] 从 nativeLibraryDir 复制到 codeCacheDir 后执行")
                return fallback.absolutePath
            }
        }
        if (fallback.exists() && fallback.length() > 1024 * 1024 && ensureExecutable(fallback)) {
            binaryPath = fallback.absolutePath
            return fallback.absolutePath
        }

        val msg = "xray 内核缺失或不可执行（nativeLibraryDir 里没有可用的 libxray.so）。" +
            "请确认 CI 的「Bundle xray-core binary」步骤成功，且手机是 arm64 架构。"
        lastError.set(msg)
        appendLog("[内核] $msg")
        Log.e(TAG, msg)
        return null
    }

    /** 补上执行位；已可执行则直接通过 */
    private fun ensureExecutable(f: File): Boolean {
        if (f.canExecute()) return true
        val ok = runCatching { f.setExecutable(true, false) }.getOrDefault(false)
        if (ok && f.canExecute()) {
            appendLog("[内核] 已为 ${f.name} 补上执行权限")
            return true
        }
        // setExecutable 在部分 ROM 上无效，直接调 chmod 兜底
        val chmod = runCatching {
            ProcessBuilder("chmod", "755", f.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }.getOrDefault(-1)
        val finalOk = f.canExecute()
        if (!finalOk) Log.e(TAG, "chmod 失败: setExecutable=$ok, chmod=$chmod, path=${f.absolutePath}")
        return finalOk
    }

    private fun readLogs(proc: Process) {
        val t = Thread({
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        appendLog(line)
                        Log.d(TAG, line)
                        val low = line.lowercase()
                        if (low.contains("failed") || low.contains("error") ||
                            low.contains("invalid") || low.contains("cannot") ||
                            low.contains("rejected") || low.contains("unsupported")
                        ) {
                            lastError.set(line.take(160))
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "xray-logger").apply { isDaemon = true }
        t.start()
        logReader = t
    }

    private fun isPortOpen(port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    } catch (_: Exception) { false }

    // ============ 测速 / 连通性测试 ============

    fun pingTcp(host: String, port: Int, timeoutMs: Int = 4000): Long = try {
        val t0 = System.currentTimeMillis()
        Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
        System.currentTimeMillis() - t0
    } catch (_: Exception) { -1L }

    /**
     * 真实连通性：通过已启动的 HTTP 入站请求一个轻量地址。
     * 只有这里返回 200/204/301 才说明「节点真的能用」。
     */
    fun probeThroughProxy(url: String = "http://www.gstatic.com/generate_204", timeoutMs: Int = 8000): Long {
        if (!isRunning()) return -1
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", HTTP_PORT))
            val t0 = System.currentTimeMillis()
            val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "curl/8.0")
            }
            val code = conn.responseCode
            runCatching { conn.inputStream?.close() }
            conn.disconnect()
            if (code in 200..399) System.currentTimeMillis() - t0 else -1L
        } catch (e: Exception) {
            Log.d(TAG, "probe failed: ${e.message}")
            -1L
        }
    }
}

/** 构建类型判断，避免在 Release 包里输出完整配置 */
internal object BuildFlags {
    val DEBUG: Boolean = runCatching {
        Class.forName("com.proxybrowser.app.BuildConfig")
            .getField("DEBUG").getBoolean(null)
    }.getOrDefault(true)
}
