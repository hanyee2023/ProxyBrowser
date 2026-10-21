#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
XrayConfig.kt 的等价实现 + 真实 xray-core 配置校验。

用途：改完 XrayConfig.kt 后，先在这里同步改一遍、跑一次，
确认每种协议组合都能被 xray 接受，再提交。这样可以避免
「内核启动失败」或「内核能启动但参数缺失、服务端丢包」，
后者正是「能 ping 通但打不开网页」的根因。

用法：
    # 1) 下载与 CI 同版本的 xray（任意平台均可，只用于 -test）
    #    Linux x86_64: https://github.com/XTLS/Xray-core/releases/download/v26.10.10/Xray-linux-64.zip
    #    Windows:      .../Xray-windows-64.zip
    # 2) 解压后把下方 XRAY 改成本机 xray 可执行文件路径
    # 3) python3 tools/verify_xray_config.py
"""
import json, os, subprocess, sys, tempfile

SOCKS_PORT, HTTP_PORT = 10808, 10809


def node(**kw):
    base = dict(
        name="test", type="vless", address="example.com", port=443,
        uuid="b831381d-6324-4d53-ad4f-8cda48b30811",
        encryption="none", vmessSecurity="auto", alterId=0,
        network="tcp", security="none", headerType="",
        wsPath="/", wsHost="", serviceName="", sni="",
        fingerprint="chrome", alpn="", allowInsecure=False,
        publicKey="", shortId="", spiderX="", mux=False,
    )
    base.update(kw)
    return base


def flow_of(n):
    net = (n["network"] or "tcp").lower().split(",")[0]
    sec = n["security"].lower()
    return "xtls-rprx-vision" if (net == "tcp" and sec in ("tls", "reality")) else ""


def build_outbound(n):
    t = n["type"]
    if t == "vless":
        settings = {"vnext": [{
            "address": n["address"], "port": n["port"],
            "users": [{
                "id": n["uuid"],
                "encryption": n["encryption"] or "none",
                "flow": flow_of(n),
                "level": 0,
            }],
        }]}
    elif t == "vmess":
        settings = {"vnext": [{
            "address": n["address"], "port": n["port"],
            "users": [{
                "id": n["uuid"],
                "alterId": n["alterId"],
                "security": n["vmessSecurity"] or "auto",
                "level": 0,
            }],
        }]}
    elif t == "trojan":
        settings = {"servers": [{
            "address": n["address"], "port": n["port"],
            "password": n["uuid"], "level": 0,
        }]}
    else:
        raise ValueError(t)

    out = {
        "tag": "proxy",
        "protocol": t,
        "settings": settings,
        "streamSettings": build_stream(n),
    }
    if n["mux"]:
        out["mux"] = {
            "enabled": True, "concurrency": 8,
            "xudpConcurrency": 16, "xudpProxyUDP443": "reject",
        }
    return out


def is_private_host(host):
    if not host:
        return False
    if host in ("localhost",) or host.endswith(".local") or host.endswith(".lan"):
        return True
    h = host.removeprefix("[").removesuffix("]")
    if h.startswith(("10.", "127.", "192.168.", "169.254.")):
        return True
    if h.startswith("172."):
        try:
            return 16 <= int(h.split(".")[1]) <= 31
        except Exception:
            return False
    if h == "::1" or h.startswith(("fc", "fd", "fe80")):
        return True
    return False


class ConfigError(Exception):
    pass


def build_stream(n):
    net = (n["network"] or "tcp").lower()
    if "," in net:
        net = net.split(",")[0]
    net = {"h2": "xhttp", "http": "xhttp", "quic": "xhttp",
           "mkcp": "kcp", "splithttp": "xhttp"}.get(net, net)
    sec = (n["security"] or ("tls" if n["type"] == "trojan" else "none")).lower()

    if n["type"] == "vless" and sec == "none" and not is_private_host(n["address"]):
        raise ConfigError("vless 明文传输被内核禁止")
    if sec == "reality" and net not in ("tcp", "raw", "xhttp", "grpc"):
        raise ConfigError("reality 不支持 " + net)

    st = {"network": net}

    if net == "ws":
        st["wsSettings"] = {
            "path": n["wsPath"] or "/",
            "headers": ({"Host": n["wsHost"]} if n["wsHost"] else {}),
            "maxEarlyData": 2048,
            "earlyDataHeaderName": "Sec-WebSocket-Protocol",
        }
    elif net == "grpc":
        st["grpcSettings"] = {
            "serviceName": n["serviceName"] or (n["wsPath"].removeprefix("/")),
            "multiMode": False,
        }
    elif net == "httpupgrade":
        s = {"path": n["wsPath"] or "/"}
        if n["wsHost"]:
            s["host"] = n["wsHost"]
        st["httpupgradeSettings"] = s
    elif net == "xhttp":
        s = {"path": n["serviceName"] or n["wsPath"] or "/"}
        if n["wsHost"]:
            s["host"] = n["wsHost"]
        st["xhttpSettings"] = s
    elif net in ("kcp", "mkcp"):
        st["kcpSettings"] = {
            "mtu": 1350, "tti": 50, "uplinkCapacity": 5, "downlinkCapacity": 20,
            "congestion": False, "readBufferSize": 1, "writeBufferSize": 1,
            "header": {"type": n["headerType"] or "none"},
        }
    else:
        ht = "http" if n["headerType"].lower() == "http" else "none"
        tcp = {"header": {"type": ht}}
        if ht == "http":
            tcp["header"]["request"] = {
                "path": [n["wsPath"] or "/"],
                "headers": {"Host": [n["wsHost"] or n["address"]]},
            }
        st["tcpSettings"] = tcp

    if sec == "reality":
        st["security"] = "reality"
        st["realitySettings"] = {
            "serverName": n["sni"] or n["wsHost"],
            "fingerprint": n["fingerprint"] or "chrome",
            "publicKey": n["publicKey"],
            "shortId": n["shortId"],
        }
        if n["spiderX"]:
            st["realitySettings"]["spiderX"] = n["spiderX"]
    elif sec in ("tls", "xtls"):
        st["security"] = "tls"
        alpn = [a.strip() for a in n["alpn"].split(",") if a.strip()]
        st["tlsSettings"] = {
            "serverName": n["sni"] or n["wsHost"] or n["address"],
            "allowInsecure": n["allowInsecure"],
            "fingerprint": n["fingerprint"] or "chrome",
            "alpn": alpn if alpn else ["h2", "http/1.1"],
        }
    else:
        st["security"] = "none"
    return st


def build(n):
    return {
        "log": {"loglevel": "info", "error": "", "access": ""},
        "inbounds": [
            {"tag": "http-in", "listen": "127.0.0.1", "port": HTTP_PORT, "protocol": "http",
             "settings": {"timeout": 0, "allowTransparent": False}},
            {"tag": "socks-in", "listen": "127.0.0.1", "port": SOCKS_PORT, "protocol": "socks",
             "settings": {"auth": "noauth", "udp": False, "ip": "127.0.0.1"}},
        ],
        "outbounds": [
            build_outbound(n),
            {"tag": "direct", "protocol": "freedom", "settings": {"domainStrategy": "UseIP"}},
            {"tag": "block", "protocol": "blackhole", "settings": {}},
        ],
        "routing": {
            "domainStrategy": "AsIs",
            "rules": [{"type": "field", "network": "tcp,udp", "outboundTag": "proxy"}],
        },
    }


CASES = [
    # ---- 主流可用组合：必须能被 xray 接受 ----
    ("vless+tcp+reality+vision", node(network="tcp", security="reality",
                                      publicKey="jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0",
                                      shortId="0123abcd", sni="www.microsoft.com")),
    ("vless+tcp+tls+vision", node(network="tcp", security="tls", sni="example.org")),
    ("vless+ws+tls", node(network="ws", security="tls", wsPath="/ray", wsHost="cdn.example.com")),
    ("vless+tcp+reality(无vision)", node(network="tcp", security="reality",
                                        publicKey="jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0",
                                        shortId="ff", sni="www.microsoft.com")),
    ("vless+grpc+tls", node(network="grpc", security="tls", serviceName="GunService", sni="a.example.com")),
    ("vless+httpupgrade+tls", node(network="httpupgrade", security="tls", wsPath="/up", wsHost="a.example.com")),
    ("vless+xhttp+reality", node(network="xhttp", security="reality", serviceName="/xh",
                                 publicKey="jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0",
                                 shortId="ab", sni="www.microsoft.com")),
    ("vless+xhttp+tls", node(network="xhttp", security="tls", serviceName="/xh",
                             wsHost="a.example.com")),
    # h2 / quic 已被 xray 26 移除，代码里降级为 xhttp，必须仍能启动
    ("vless+h2降级xhttp", node(network="h2", security="tls", wsPath="/h2", wsHost="a.example.com")),
    ("vless+quic降级xhttp", node(network="quic", security="tls", wsPath="/q", wsHost="a.example.com")),
    ("vless+kcp+tls", node(network="kcp", security="tls", headerType="srtp", sni="a.example.com")),
    ("vless+tcp+vision+mux", node(network="tcp", security="tls", sni="e.com", mux=True)),
    ("vmess+ws+tls", node(type="vmess", network="ws", security="tls",
                          wsPath="/vm", wsHost="c.example.com", vmessSecurity="auto")),
    ("vmess+grpc+tls", node(type="vmess", network="grpc", security="tls", serviceName="vmg")),
    ("vmess+tcp+tls", node(type="vmess", network="tcp", security="tls", sni="v.example.com")),
    ("trojan+tcp+tls", node(type="trojan", network="tcp", security="tls", sni="t.example.com")),
    ("trojan+ws+tls", node(type="trojan", network="ws", security="tls",
                           wsPath="/tj", wsHost="t.example.com")),
    # 内网明文 vless 是被允许的
    ("vless+ws+明文(内网)", node(network="ws", security="none", address="192.168.1.10", wsPath="/x")),
]

# 预期被我们自己拦下的用例：代码抛异常，不下发配置给内核
EXPECT_REJECT = [
    ("vless+明文公网(应拒绝)", node(network="ws", security="none",
                                  address="example.com", wsPath="/x")),
    ("vless+ws+reality(应拒绝)", node(network="ws", security="reality", wsPath="/ray",
                                     wsHost="cdn.example.com", sni="www.microsoft.com",
                                     publicKey="jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0",
                                     shortId="ff")),
]


def main():
    xray = os.environ.get("XRAY_BIN", "./xray")
    ok = fail = 0
    for name, n in CASES:
        cfg = build(n)
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(cfg, f, indent=2)
            path = f.name
        r = subprocess.run([xray, "run", "-test", "-c", path],
                           capture_output=True, text=True)
        out = (r.stdout + r.stderr).strip().splitlines()
        tail = " | ".join(out[-3:]) if out else ""
        if r.returncode == 0:
            ok += 1
            print(f"  PASS  {name}")
        else:
            fail += 1
            print(f"  FAIL  {name}\n        {tail}")
        os.unlink(path)

    print("\n-- 预期拒绝（由代码拦下，不下发内核）--")
    rejected_ok = 0
    for name, n in EXPECT_REJECT:
        try:
            build(n)
            print(f"  FAIL  {name}  ← 竟然生成了配置")
            fail += 1
        except ConfigError as e:
            rejected_ok += 1
            print(f"  PASS  {name}  ({e})")

    print(f"\n结果: 可用组合 {ok} 通过 / {fail} 失败；预期拒绝 {rejected_ok}/{len(EXPECT_REJECT)}")
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
