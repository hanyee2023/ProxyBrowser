# ProxyBrowser

轻量 Android 代理浏览器：**App 内 WebView + 内嵌 xray-core**，直接在浏览器里走自己的节点，
不需要 VpnService，也不需要额外装代理 App。

界面为**夸克风格**：胶囊地址栏、渐变图标、底部六键工具栏、卡片式设置页。

---

## 1. 这次修了什么（上一版「能 ping 通但连不上」的原因）

「测速显示 30ms，节点是绿的，但网页打不开」——这不是网络问题，是配置问题。
旧版有 6 个叠加的坑，逐个修掉：

| # | 问题 | 现象 | 修复 |
|---|---|---|---|
| 1 | 配置只写了 `security/tls`，**没写 reality 的 `pbk` / `sid`**，也没写 ws 的 `path`、`Host`、`grpc` 的 `serviceName` | xray 正常启动、端口正常监听（TCP 握手成功→测速很绿），但发出的流量被服务端丢弃 → 网页一直转圈 | 重写 `XrayConfig.kt`：tls / reality / ws / grpc / httpupgrade / xhttp / kcp 全参数生成，vless 的 `flow=xtls-rprx-vision` 也只在与 tcp+tls/reality 匹配时下发 |
| 2 | 节点解析**没有 URL 解码 query**，`path=%2Fws`、`sni=a.com` 带转义时全解析错；ws 默认路径被写成空字符串 | 部分机场节点 connect 后立刻断 | 重写 `NodeParser`：统一 `queryMap` 做 URL 解码 + 小写键 + 缺省值兜底，兼容 `peer`/`servername`/`skip-cert-verify` 等别名 |
| 3 | WebView 请求用 Java 的 `HttpURLConnection` + `Proxy(SOCKS)` 走 127.0.0.1 | Java 的 SOCKS 实现不做远程 DNS，`HttpURLConnection` 对 chunked/gzip 响应处理不稳，CDN 页面经常半截或空白 | 新增 `LocalProxy`：改走 xray 自带 **HTTP 入站 (10809)**，与 v2rayNG / Clash 同一条链路；本地负责解 gzip、跟随重定向、保持 Cookie |
| 4 | 测速只做 TCP `Socket.connect` | 「端口活着 ≠ 节点可用」，用户完全无法判断是节点挂了还是参数错了 | 测速分两层：`TCP 握手` 看端口存活，`连通测试` 走代理真实请求 `gstatic.com/generate_204`，只有后者成功才算「能用」 |
| 5 | `locateBinary()` 用 `canExecute()` 判断内核是否可用，但 **APK 里 `.so` 的权限是 `644`（无执行位）** | 内核明明打进去了，App 却一直报「xray 内核缺失」，代理根本无法启动 | 改为「存在 + 体积合理 → 主动 `setExecutable` / `chmod 755`」，失败再复制到 `codeCacheDir` 重试；CI 额外把 APK 内条目权限改成 `0755` 再重签名 |
| 6 | 部分传输方式 xray 26 **已移除或禁止**，代码却照旧生成 | 内核直接退出：`HTTP transport has been removed`、`QUIC transport has been removed`、`REALITY only supports RAW, XHTTP and gRPC`、`vless without TLS is prohibited` | `h2`/`quic` 自动降级为 `xhttp`；`reality + ws` 与「公网明文 vless」在生成配置前就抛出**看得懂的中文提示**，不再让内核静默失败 |

另外修掉的小问题：

- `.github/workflows/build.yml` 里 `v26.3.27` 这个 tag 不存在 → 改成 `v26.10.10` 并加 `sha256` 校验
- `app/build.gradle`（Groovy）与 `app/build.gradle.kts` 同时存在，AGP 会随机选一个 → 删掉 Groovy 版
- `downloadXray` 任务下载到 `assets/xray/`，但代码是从 `nativeLibraryDir` 找 `libxray.so` → 统一为「CI 下载 → 放进 `jniLibs/arm64-v8a/libxray.so`」
- 二进制只在 `assets`，Android 10+ 不允许从 `filesDir` 执行 ELF → 现在走 `nativeLibraryDir`
- `layout/activity_sniffer.xml`、`item_media.xml` 与新 UI 冲突 → 移除，页面全部代码构建
- WebView 在**代理未开启时也全部拦截转发**，一个页面几十个请求全走 Java 同步 IO → 代理关闭时直接返回 `null` 交回系统网络栈；`ws://`、SSE、非 GET/POST 一律放行

### 验证方式（不是「我觉得修好了」）

用与 CI 同版本的 **xray 26.10.10** 二进制，对 18 种协议组合跑 `xray run -test`，全部通过；
并实际启动内核，经 HTTP 入站 / SOCKS 入站拉取真实网页，验证远程 DNS、HTTPS CONNECT、gzip 解码。

```bash
# 改完 XrayConfig.kt 后，先跑这个再提交
XRAY_BIN=/path/to/xray python3 tools/verify_xray_config.py
# 结果: 可用组合 18 通过 / 0 失败；预期拒绝 2/2
```

---

## 2. 架构

```
WebView
  └─ shouldInterceptRequest
        ├─ 命中广告规则 → 返回 1x1 gif
        └─ LocalProxy.fetch()
              ├─ 代理开启 → HTTP 127.0.0.1:10809 ┐
              └─ 代理关闭 → 直连                 │
                                                 ▼
                                          xray-core（内置二进制）
                                            ├─ HTTP 入站 10809
                                            ├─ SOCKS 入站 10808
                                            └─ outbound: vless/vmess/trojan
                                                  (tcp/ws/grpc/xhttp + tls/reality)
                                                        └─ 你的节点
```

| 文件 | 作用 |
|---|---|
| `core/XrayConfig.kt` | ProxyNode → xray JSON（全参数） |
| `core/V2RayManager.kt` | 进程生命周期、环形日志、TCP 探测、**代理真实连通测试** |
| `core/LocalProxy.kt` | WebView 请求统一出口（HTTP 入站/直连、gzip、重定向、Cookie） |
| `core/AdBlocker.kt` | 精简域名/路径规则，返回 1x1 gif |
| `core/UserScriptEngine.kt` | 油猴元数据 + 注入 |
| `core/VideoSniffer.kt` | 嗅探结果（持久化，最多 200 条） |
| `core/Settings.kt` | 开关持久化 |
| `data/ProxyNode.kt` | 节点模型 + URI 解析 |
| `data/NodeStore.kt` | 节点/订阅持久化 |
| `ui/BrowserActivity.kt` | 主界面（胶囊地址栏 + 六键工具栏 + 底部面板） |
| `ui/ProxySettingsActivity.kt` | 节点卡片列表、测速、连通测试、节点详情 |
| `ui/SettingsActivity.kt` | 分组设置 + 内核日志 + 协议说明 |
| `ui/SnifferActivity.kt` | 嗅探结果卡片流 |
| `ui/Ui.kt` | 共用 UI 积木（圆角、胶囊、按钮） |
| `res/drawable/*` | 全部图标为矢量，统一 `tint`，圆润线性风格 |
| `tools/verify_xray_config.py` | 用真实 xray 二进制校验配置（18 种协议组合） |
| `dev-setup.sh` | Codespaces 一键装环境 + 出 APK（含 .so 权限修正与重签名） |

---

## 3. 上手

1. GitHub 新建仓库 `ProxyBrowser`，把本项目**根目录所有内容**（含隐藏的 `.github/`）推上去
2. 进 **Actions → Build APK**，5–15 分钟
3. 下载 Artifacts 里的 `proxy-browser-apk`
4. 打开 App → 点左上角**地球** → 「管理节点」→ ＋ 添加订阅或粘贴节点 → 「连接」
5. 连接后会自动做一次连通测试，通过才说明节点真能用

> 只支持 **arm64-v8a**（xray 官方 Android 二进制只有该架构，覆盖 99% 近年国产手机）。

---

## 4. 支持的协议

- 协议：**vmess / vless / trojan**
- 传输：**tcp(raw)、ws、grpc、httpupgrade、xhttp（含 h2/quic 自动降级）**、kcp
- 安全：**none、tls（SNI / ALPN / 指纹 / allowInsecure）、reality（pbk + sid + spiderX）**
- 其它：`alterId`、`flow=xtls-rprx-vision`、mux

**限制（xray 内核层面，不是 App 的 bug）：**

| 组合 | 结果 |
|---|---|
| `reality` + `ws` / `httpupgrade` / `kcp` | 内核不支持，App 直接提示换节点 |
| `vless` 明文连公网 | 内核禁止，App 直接提示「改用 TLS / reality」 |
| 裸 `h2` / `quic` 传输 | xray 26 已移除，App 自动降级为 `xhttp` |
| `ss` / `hysteria2` / `tuic` / `wireguard` | 不支持，订阅里混有会被跳过，不报错 |

---

## 5. 构建

### 方式 A：Codespaces 里直接出包（推荐先这样验证）

不需要本地环境，也不需要等 CI。上传源码后一条命令：

```bash
bash dev-setup.sh
```

脚本会依次完成：

1. 装 JDK 17 + 基础工具
2. 装 Android SDK（platform 34 / build-tools 34.0.0 / platform-tools）
3. 下载真 xray 二进制（github 直连失败会自动切 `gh-proxy.com` 等镜像）+ sha256 校验 + 架构校验
4. 编译 debug APK
5. **校验 APK 内 `.so` 的可执行位，没有就修正为 0755 并重新签名**

产物在 `app/build/outputs/apk/debug/app-debug.apk`，左侧文件树右键即可下载。

常用参数：

```bash
bash dev-setup.sh --skip-build   # 只装环境，不编译
bash dev-setup.sh --clean        # 先 clean 再编译
```

> 第 5 步很关键。AGP 打包 `.so` 默认写 `644`（无执行位），
> Android 解压后没有 `x` 位，App 会报「内核缺失」——这是「代理连不上」的根因之一。
> 脚本会自动修掉，所以**本地出的包也能正常用**，不必非得等 CI。

### 方式 B：云端 CI

push 到 `main` 即可，`.github/workflows/build.yml` 会自动
下载 xray → 打包 → 校验 `.so` 可执行位 → 重签名 → 上传 Artifacts。

### 方式 C：本地命令行

```bash
cp /path/to/xray app/src/main/jniLibs/arm64-v8a/libxray.so
./gradlew assembleDebug
```

本地没有二进制时，`app/build.gradle.kts` 里的 `ensureXrayBinary` 会自动生成一个
1.1MB 的**占位文件**，让编译照常通过（用于验证 UI 与编译链路）。
注意：这样打出的 APK 代理功能不可用——`dev-setup.sh` 会检测并拒绝这种包。

---

## 6. 排障顺序（照着走，基本能定位）

| 现象 | 看哪里 | 结论 |
|---|---|---|
| 点连接后提示「xray 内核缺失」 | 手机架构不是 arm64；或本地用的是占位 `.so`；或 CI 的 Bundle 步骤失败 | 换机 / 用 CI 产物 / 看 Actions 日志 |
| 点连接后提示「reality 只支持 tcp/xhttp/grpc」 | 节点是 `reality + ws` | 协议层面不支持，换节点 |
| 点连接后提示「vless 明文传输被禁止」 | 节点 `security=none` 且地址是公网 | 换带 TLS / reality 的节点 |
| 测速有 ms，但网页打不开 | 节点详情 → 核对 `pbk`、`sid`、`path`、`SNI` 与机场面板是否一致 | 参数不一致 |
| 连接后提示「访问外网失败」 | 设置 → 内核日志 | 看 xray 是否报 TLS / reality 相关错误 |
| 网页能开但很慢 / 卡住 | 先关掉代理用直连试；再看是否只有代理下慢 | 代理链路带宽或节点问题 |
| 所有节点都不通 | 先用原始订阅在 v2rayNG 里试 | 排除 App 侧问题 |

内置页面上出现错误时，会直接显示失败原因，并提示去「内核日志」——不用再连电脑抓 logcat。

---

## 7. 主题（夸克风格）

| 元素 | 值 |
|---|---|
| 背景 | `#FFFFFF` / 分组底 `#F5F7FB` |
| 主文本 | `#16181D` |
| 副文本 | `#6B7280` / 三级 `#9AA1AE` |
| 主色渐变 | `#5B8CFF → #3A5BF0` |
| 成功 / 警告 / 危险 | `#12B981` / `#F59E0B` / `#F04438` |
| 圆角 | 胶囊 999dp · 卡片 18dp · 弹窗 24dp · 地址栏 22dp |

改主题只需要动 `res/values/colors.xml`。
