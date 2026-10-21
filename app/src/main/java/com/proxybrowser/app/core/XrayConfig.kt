package com.proxybrowser.app.core

import com.proxybrowser.app.data.ProxyNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 ProxyNode 转成 xray-core 配置 JSON。
 *
 * 入站（都由 xray 自己监听，App 内部使用）：
 *   - HTTP 127.0.0.1:10809 → LocalProxyServer 用它抓网页，支持远程 DNS 与 TLS
 *   - SOCKS 127.0.0.1:10808 → 备用 / 第三方工具
 * 出站：按节点类型生成 vless / vmess / trojan outbound，
 * 并按 network / security 生成完整 streamSettings（tls / reality / ws / grpc / xhttp ...）。
 *
 * 只要这里少写一个参数（例如 reality 的 pbk/sid、ws 的 path、tls 的 SNI），
 * xray 仍会正常启动并监听端口 —— 但流量到服务端会被直接丢弃，
 * 表现就是「能 ping 通、代理却打不开网页」。所以这里必须写全。
 */
object XrayConfig {

    const val SOCKS_PORT = 10808
    const val HTTP_PORT = 10809

    fun build(node: ProxyNode): String {
        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", SOCKS_PORT)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", false)
                put("ip", "127.0.0.1")
            })
        }
        // WebView 抓页面走这个 HTTP 入站：xray 负责远程 DNS 与 TLS，
        // 比 Java 的 SOCKS 实现可靠得多。
        val httpInbound = JSONObject().apply {
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("port", HTTP_PORT)
            put("protocol", "http")
            put("settings", JSONObject().apply {
                put("timeout", 0)
                put("allowTransparent", false)
            })
        }

        val proxyOut = buildOutbound(node)
        val directOut = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply { put("domainStrategy", "UseIP") })
        }
        val blockOut = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject())
        }

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "info")
                put("error", "")
                put("access", "")
            })
            put("inbounds", JSONArray().put(httpInbound).put(socksInbound))
            put("outbounds", JSONArray().put(proxyOut).put(directOut).put(blockOut))
            put("routing", JSONObject().apply {
                // 域名交给代理服务器解析，避免本机 DNS 污染
                put("domainStrategy", "AsIs")
                put("rules", JSONArray().put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "proxy")
                }))
            })
        }.toString(2)
    }

    // ============ outbound ============

    private fun buildOutbound(node: ProxyNode): JSONObject {
        val settings = when (node.type) {
            ProxyNode.Type.VLESS -> JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", node.address)
                    put("port", node.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", node.uuid)
                        put("encryption", node.encryption.ifEmpty { "none" })
                        put("flow", flowOf(node))
                        put("level", 0)
                    }))
                }))
            }
            ProxyNode.Type.VMESS -> JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", node.address)
                    put("port", node.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", node.uuid)
                        put("alterId", node.alterId)
                        put("security", node.vmessSecurity.ifEmpty { "auto" })
                        put("level", 0)
                    }))
                }))
            }
            ProxyNode.Type.TROJAN -> JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("address", node.address)
                    put("port", node.port)
                    put("password", node.uuid)
                    put("level", 0)
                }))
            }
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", node.type.name.lowercase())
            put("settings", settings)
            put("streamSettings", buildStream(node))
            if (node.mux) {
                put("mux", JSONObject().apply {
                    put("enabled", true)
                    put("concurrency", 8)
                    put("xudpConcurrency", 16)
                    put("xudpProxyUDP443", "reject")
                })
            }
        }
    }

    /** vless 的 flow：只有 tcp + tls/reality 才可能有 xtls-rprx-vision */
    private fun flowOf(node: ProxyNode): String {
        val net = node.network.ifEmpty { "tcp" }.lowercase().substringBefore(',')
        val sec = node.security.lowercase()
        return if (net == "tcp" && (sec == "tls" || sec == "reality")) "xtls-rprx-vision" else ""
    }

    /** 内网 / 本机地址：这些地址上 vless 明文是允许的 */
    private fun isPrivateHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")) return true
        val h = host.removePrefix("[").removeSuffix("]")
        if (h.startsWith("10.") || h.startsWith("127.") || h.startsWith("192.168.")) return true
        if (h.startsWith("172.")) {
            val second = h.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            if (second in 16..31) return true
        }
        if (h.startsWith("169.254.")) return true
        if (h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")) return true
        return false
    }

    // ============ streamSettings ============

    private fun buildStream(node: ProxyNode): JSONObject {
        var net = node.network.ifEmpty { "tcp" }.lowercase()
        // 老配置里偶见 "tcp,h2" 之类的写法，取第一个
        if (net.contains(',')) net = net.substringBefore(',')

        // xray-core 26.x 已移除裸 h2 与 quic 传输（报错 "has been removed and
        // migrated to XHTTP"）。遇到这两个直接降级到 xhttp，否则内核会启动失败。
        // grpc 仍保留但告警，暂不处理。
        net = when (net) {
            "h2", "http" -> "xhttp"
            "quic" -> "xhttp"
            "mkcp" -> "kcp"
            "splithttp" -> "xhttp"
            else -> net
        }

        var sec = node.security.ifEmpty { if (node.type == ProxyNode.Type.TROJAN) "tls" else "none" }
            .lowercase()

        // vless 明文（无 TLS 无 reality）连公网 IP/域名，xray 会拒绝启动：
        //   "vless without TLS or other encryption is prohibited"
        // 这类节点在协议层面就不可能工作，抛明确异常让上层提示用户换节点，
        // 而不是让内核静默失败。
        if (node.type == ProxyNode.Type.VLESS && sec == "none" && !isPrivateHost(node.address)) {
            throw IllegalArgumentException(
                "该节点是 VLESS 明文传输（security=none），xray 内核禁止连接公网地址。" +
                    "请改用带 TLS 或 reality 的节点；如果服务器在内网/本机，可忽略此限制。"
            )
        }

        // REALITY 官方只支持 RAW(tcp) / XHTTP / gRPC，配 ws/reality 内核直接报错：
        //   "REALITY only supports RAW, XHTTP and gRPC for now."
        if (sec == "reality" && net !in setOf("tcp", "raw", "xhttp", "grpc")) {
            throw IllegalArgumentException(
                "该节点是 reality + $net，但 xray 内核的 reality 只支持 tcp / xhttp / grpc。" +
                    "请让服务端改用支持的传输方式，或换用其它节点。"
            )
        }

        val st = JSONObject().apply { put("network", net) }

        when (net) {
            "ws" -> st.put("wsSettings", JSONObject().apply {
                put("path", node.wsPath.ifEmpty { "/" })
                put("headers", JSONObject().apply {
                    if (node.wsHost.isNotEmpty()) put("Host", node.wsHost)
                })
                // 部分 CDN 需要 early data 才能稳定
                put("maxEarlyData", 2048)
                put("earlyDataHeaderName", "Sec-WebSocket-Protocol")
            })
            "grpc" -> st.put("grpcSettings", JSONObject().apply {
                put("serviceName", node.serviceName.ifEmpty { node.wsPath.removePrefix("/") })
                put("multiMode", false)
            })
            "httpupgrade" -> st.put("httpupgradeSettings", JSONObject().apply {
                put("path", node.wsPath.ifEmpty { "/" })
                if (node.wsHost.isNotEmpty()) put("host", node.wsHost)
            })
            // 注意：xray 26 的 xhttp 没有 "mode" 字段了，多写了会报 unknown field
            "xhttp" -> st.put("xhttpSettings", JSONObject().apply {
                put("path", node.serviceName.ifEmpty { node.wsPath.ifEmpty { "/" } })
                if (node.wsHost.isNotEmpty()) put("host", node.wsHost)
            })
            "kcp" -> st.put("kcpSettings", JSONObject().apply {
                put("mtu", 1350)
                put("tti", 50)
                put("uplinkCapacity", 5)
                put("downlinkCapacity", 20)
                put("congestion", false)
                put("readBufferSize", 1)
                put("writeBufferSize", 1)
                put("header", JSONObject().apply {
                    put("type", node.headerType.ifEmpty { "none" })
                })
            })
            else -> {
                // tcp
                st.put("tcpSettings", JSONObject().apply {
                    put("header", JSONObject().apply {
                        val ht = if (node.headerType.lowercase() == "http") "http" else "none"
                        put("type", ht)
                        if (ht == "http") {
                            put("request", JSONObject().apply {
                                put("path", JSONArray().put(node.wsPath.ifEmpty { "/" }))
                                put("headers", JSONObject().apply {
                                    put("Host", JSONArray().apply {
                                        put(node.wsHost.ifEmpty { node.address })
                                    })
                                })
                            })
                        }
                    })
                })
            }
        }

        when (sec) {
            "reality" -> {
                st.put("security", "reality")
                st.put("realitySettings", JSONObject().apply {
                    // serverName 必须是服务端允许的伪装域名
                    put("serverName", node.sni.ifEmpty { node.wsHost })
                    put("fingerprint", node.fingerprint.ifEmpty { "chrome" })
                    put("publicKey", node.publicKey)
                    put("shortId", node.shortId)
                    if (node.spiderX.isNotEmpty()) put("spiderX", node.spiderX)
                })
                // reality 自带加密，不再叠加 tlsSettings
            }
            "tls", "xtls" -> {
                st.put("security", "tls")
                st.put("tlsSettings", JSONObject().apply {
                    val sni = node.sni.ifEmpty { node.wsHost.ifEmpty { node.address } }
                    put("serverName", sni)
                    put("allowInsecure", node.allowInsecure)
                    put("fingerprint", node.fingerprint.ifEmpty { "chrome" })
                    val alpn = node.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    put("alpn", JSONArray().apply {
                        if (alpn.isEmpty()) {
                            put("h2"); put("http/1.1")
                        } else alpn.forEach { put(it) }
                    })
                })
            }
            else -> {
                st.put("security", "none")
            }
        }
        return st
    }
}
