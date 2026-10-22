#!/usr/bin/env bash
#
# dev-setup.sh —— 在 GitHub Codespaces / 任意 Debian 系 Linux 上，一条命令装好环境并编译出 APK
#
#   bash dev-setup.sh              # 装环境 + 编译 debug APK
#   bash dev-setup.sh --skip-build # 只装环境，不编译
#   bash dev-setup.sh --clean      # 先 clean 再编译
#
# 脚本做的事：
#   1. 安装 JDK 17 + 基础工具（unzip/curl）
#   2. 安装 Android SDK（platform 34 + build-tools 34.0.0 + platform-tools）
#   3. 下载真正的 xray-core arm64 二进制，校验 sha256，放进 jniLibs
#   4. 编译 debug APK，并校验 APK 内 .so 的可执行位
#
# 幂等：已经装好的步骤会自动跳过，重复运行不会重装。

set -euo pipefail

# ---------- 配置 ----------
XRAY_VERSION="v26.10.10"
XRAY_SHA256="cbd594a6dfd4bef98283788e10fb2c299cb3c8af61b5b3ffdb1be4e96da955ef"
CMDTOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
SDK_PLATFORM="platforms;android-34"
SDK_BUILDTOOLS="build-tools;34.0.0"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

SKIP_BUILD=0
DO_CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    --clean)      DO_CLEAN=1 ;;
    -h|--help)    sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $arg（用 --help 查看用法）" >&2; exit 1 ;;
  esac
done

# ---------- 输出风格 ----------
step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '    \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

cd "$PROJECT_DIR"

# ---------- 0. 环境自检 ----------
step "0/5 环境自检"
[ -f "settings.gradle.kts" ] || die "当前目录不是项目根目录（找不到 settings.gradle.kts）"
[ -d "app" ] || die "当前目录缺少 app/ 模块目录"
ok "项目根目录：$PROJECT_DIR"
ok "架构：$(uname -m) / 系统：$(uname -s)"

if [ "$(uname -s)" != "Linux" ]; then
  warn "本脚本针对 Linux（Codespaces）。macOS 建议直接装 Android Studio。"
fi

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  command -v sudo >/dev/null && SUDO="sudo" || warn "非 root 且没有 sudo，后续安装可能失败"
fi

# ---------- 1. JDK 17 ----------
step "1/5 JDK 17"
# 判断已有 java 是否 >= 17（AGP 8.x 要求 17+）
JAVA_OK=0
if command -v java >/dev/null 2>&1; then
  JV=$(java -version 2>&1 | head -1)
  if echo "$JV" | grep -qE 'version "(1[7-9]|[2-9][0-9])'; then
    JAVA_OK=1
    ok "已安装：$JV"
  else
    warn "现有版本过低：$JV"
  fi
fi

if [ "$JAVA_OK" -eq 0 ]; then
  echo "    安装 openjdk-17-jdk（可能需要 1-2 分钟）..."
  $SUDO apt-get update -qq
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq \
    openjdk-17-jdk unzip curl ca-certificates >/dev/null
  ok "安装完成：$(java -version 2>&1 | head -1)"
fi

# 从当前 java 反向推断 JAVA_HOME，保证两者版本一致。
# （直接写死 /usr/lib/jvm/java-17-openjdk-amd64 会出现「java 是 20、JAVA_HOME 是 17」的不一致）
JAVA_BIN=$(readlink -f "$(command -v java)")
export JAVA_HOME=$(dirname "$(dirname "$JAVA_BIN")")

if [ ! -x "$JAVA_HOME/bin/javac" ]; then
  # 只有 JRE 没 JDK 时，回头找候选路径
  for c in \
    "/usr/lib/jvm/java-17-openjdk-amd64" \
    "/usr/lib/jvm/java-17-openjdk" \
    "/usr/lib/jvm/temurin-17-jdk-amd64" \
    "/opt/java/openjdk" ; do
    if [ -x "$c/bin/javac" ]; then export JAVA_HOME="$c"; break; fi
  done
fi
[ -x "$JAVA_HOME/bin/javac" ] || die "找不到 javac，请确认装的是 JDK 而不只是 JRE"
ok "JAVA_HOME=$JAVA_HOME"
ok "版本：$(java -version 2>&1 | head -1)"

# ---------- 2. Android SDK ----------
step "2/5 Android SDK"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ -x "$SDKMANAGER" ]; then
  ok "cmdline-tools 已存在，跳过下载"
else
  echo "    下载 commandline-tools ..."
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  TMPZIP=$(mktemp /tmp/cmdtools-XXXXXX.zip)
  curl -sSL --retry 3 --connect-timeout 20 -o "$TMPZIP" "$CMDTOOLS_URL" \
    || die "下载 commandline-tools 失败，请检查网络"
  unzip -oq "$TMPZIP" -d "$ANDROID_HOME/cmdline-tools"
  rm -f "$TMPZIP"
  # 压缩包解开后是 cmdline-tools/cmdline-tools，需要重命名为 latest
  if [ -d "$ANDROID_HOME/cmdline-tools/cmdline-tools" ]; then
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  ok "cmdline-tools 安装完成"
fi

[ -x "$SDKMANAGER" ] || die "sdkmanager 不存在：$SDKMANAGER"

# 接受协议（首次必须，否则 install 会卡在交互）
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
ok "licenses 已接受"

# 只装缺的包，避免每次都联网检查
NEED=()
[ -d "$ANDROID_HOME/platforms/android-34" ]   || NEED+=("$SDK_PLATFORM")
[ -d "$ANDROID_HOME/build-tools/34.0.0" ]     || NEED+=("$SDK_BUILDTOOLS")
[ -d "$ANDROID_HOME/platform-tools" ]         || NEED+=("platform-tools")

if [ ${#NEED[@]} -gt 0 ]; then
  echo "    安装组件：${NEED[*]}"
  "$SDKMANAGER" "${NEED[@]}" >/dev/null || die "Android SDK 组件安装失败"
  ok "组件安装完成"
else
  ok "platform 34 / build-tools 34.0.0 / platform-tools 均已就绪"
fi

# local.properties（Gradle 靠它定位 SDK）
echo "sdk.dir=$ANDROID_HOME" > local.properties
ok "已写入 local.properties"

# ---------- 3. xray 二进制 ----------
step "3/5 xray-core 二进制（arm64-v8a）"
SO_DIR="app/src/main/jniLibs/arm64-v8a"
SO="$SO_DIR/libxray.so"
MIN_SIZE=5000000   # 真二进制约 30MB，占位文件约 1MB

if [ -f "$SO" ] && [ "$(stat -c%s "$SO" 2>/dev/null || echo 0)" -ge "$MIN_SIZE" ]; then
  ok "已存在真实二进制（$(( $(stat -c%s "$SO") / 1024 / 1024 )) MB），跳过下载"
else
  mkdir -p "$SO_DIR"
  TMPZIP=$(mktemp /tmp/xray-XXXXXX.zip)
  FILE_NAME="Xray-android-arm64-v8a.zip"
  GH_PATH="https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/${FILE_NAME}"

  # 直连 + 两个镜像，逐个尝试。墙内直连 GitHub 常超时，镜像能救回来。
  URLS=(
    "$GH_PATH"
    "https://gh-proxy.com/${GH_PATH}"
    "https://ghproxy.net/${GH_PATH}"
  )

  GOT=0
  for u in "${URLS[@]}"; do
    echo "    尝试：${u%%/https*}"
    if curl -fL --retry 2 --retry-delay 2 --connect-timeout 15 --max-time 180 \
         -o "$TMPZIP" "$u" 2>/dev/null; then
      GOT=1
      break
    fi
    warn "失败，换下一个源"
  done
  [ "$GOT" -eq 1 ] || die "所有源都下载失败。请手动下载 ${FILE_NAME}，解压后把 xray 放到 $SO"

  echo "    校验 sha256 ..."
  echo "${XRAY_SHA256}  ${TMPZIP}" | sha256sum -c - >/dev/null 2>&1 \
    || die "sha256 校验失败，文件可能损坏或被篡改（镜像内容不对时也会这样）"

  rm -rf /tmp/xray-extract && mkdir -p /tmp/xray-extract
  unzip -oq "$TMPZIP" -d /tmp/xray-extract
  [ -f /tmp/xray-extract/xray ] || die "压缩包里没有 xray 可执行文件"
  cp /tmp/xray-extract/xray "$SO"
  chmod +x "$SO"
  rm -f "$TMPZIP"
  rm -rf /tmp/xray-extract
  ok "已放入 $SO（$(( $(stat -c%s "$SO") / 1024 / 1024 )) MB）"
fi

# 架构校验：必须是 ARM aarch64，否则装到手机上跑不起来
FILEINFO=$(file -b "$SO" 2>/dev/null || echo "")
if echo "$FILEINFO" | grep -qi "aarch64\|ARM64\|ARM aarch64"; then
  ok "架构正确：aarch64"
elif [ -z "$FILEINFO" ]; then
  warn "系统没有 file 命令，跳过架构校验"
else
  die "架构不对（$FILEINFO）。请删除 $SO 后重跑，或手动替换为 arm64 版本"
fi

# 确认 .gitignore 排除了 jniLibs，避免 30MB 二进制被提交
if [ -f .gitignore ] && ! grep -q "jniLibs" .gitignore; then
  warn ".gitignore 没有排除 jniLibs，二进制可能被提交；建议加一行 app/src/main/jniLibs/"
fi

# ---------- 4. 编译 APK ----------
step "4/5 编译 debug APK"

GRADLE_ARGS=(assembleDebug --no-daemon --console=plain)
[ "$DO_CLEAN" -eq 1 ] && GRADLE_ARGS=(clean "${GRADLE_ARGS[@]}")

if [ "$SKIP_BUILD" -eq 1 ]; then
  warn "--skip-build 已指定，跳过编译"
else
  # 优先用 wrapper（版本可控）；没有则退回系统 gradle
  if [ -f gradlew ]; then
    chmod +x gradlew
    GRADLE_CMD=("./gradlew")
    ok "使用 gradlew（$(grep distributionUrl gradle/wrapper/gradle-wrapper.properties 2>/dev/null | sed 's/.*gradle-\(.*\)-bin.zip/\1/')）"
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD=("gradle")
    warn "项目里没有 gradlew，退回系统 gradle：$(gradle --version 2>/dev/null | grep '^Gradle' | head -1)"
  else
    die "既没有 gradlew 也没有系统 gradle。请执行 gradle wrapper 生成，或安装 Gradle。"
  fi

  echo "    首次编译需要下载依赖，约 3-6 分钟 ..."

  # 第一次尝试：用选定的命令
  if JAVA_HOME="$JAVA_HOME" "${GRADLE_CMD[@]}" "${GRADLE_ARGS[@]}"; then
    ok "编译成功"
  else
    # wrappper 下载 Gradle 发行包失败（墙内 services.gradle.org 常超时）时，
    # 自动退回系统 gradle 再试一次，不直接判死。
    if [ "${GRADLE_CMD[0]}" = "./gradlew" ] && command -v gradle >/dev/null 2>&1; then
      warn "gradlew 失败（多为下载 Gradle 发行包超时），改用系统 gradle 重试"
      if JAVA_HOME="$JAVA_HOME" gradle "${GRADLE_ARGS[@]}"; then
        ok "编译成功（系统 gradle）"
      else
        die "编译失败。若日志是下载依赖超时，请重跑；若报 Kotlin 错误，把日志发我。"
      fi
    else
      die "编译失败。若日志是下载依赖超时，请重跑；若报 Kotlin 错误，把日志发我。"
    fi
  fi
fi

# ---------- 5. 产物校验 ----------
step "5/5 APK 产物校验"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [ "$SKIP_BUILD" -eq 1 ] && [ ! -f "$APK" ]; then
  warn "未编译且无历史产物，跳过校验"
  exit 0
fi

[ -f "$APK" ] || die "没有找到 $APK"

APK_SIZE=$(( $(stat -c%s "$APK") / 1024 / 1024 ))
ok "APK：$APK（${APK_SIZE} MB）"

# 确认 .so 真的打进去了，且体积不是占位文件
SO_IN_APK=$(unzip -l "$APK" | awk '/lib\/arm64-v8a\/libxray\.so/{print $1}' | head -1)
if [ -z "$SO_IN_APK" ]; then
  die "APK 里没有 lib/arm64-v8a/libxray.so —— 代理会无法启动"
fi
if [ "$SO_IN_APK" -lt "$MIN_SIZE" ]; then
  die "APK 里的 libxray.so 只有 $(( SO_IN_APK / 1024 )) KB，是占位文件，装到手机也不能用"
fi
ok "APK 内含 libxray.so：$(( SO_IN_APK / 1024 / 1024 )) MB"

# 打印实际权限位（AGP 常写 644，没执行位会导致「内核缺失」）
ATTR=$(unzip -Z -v "$APK" lib/arm64-v8a/libxray.so 2>/dev/null \
  | grep -i "unix file attributes" | awk '{print $4}' | tr -d '()' | head -1)

if [ -n "$ATTR" ] && [ "$(( ATTR & 0111 ))" -eq 0 ]; then
  warn "zip 内权限位 $ATTR 无执行位，正在修正为 0755 ..."
  python3 - "$APK" <<'PY'
import sys, zipfile, shutil
apk, target = sys.argv[1], 'lib/arm64-v8a/libxray.so'
with zipfile.ZipFile(apk) as z:
    if not ((z.getinfo(target).external_attr >> 16) & 0o111):
        tmp = apk + '.tmp'
        with zipfile.ZipFile(apk) as zin, \
             zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == target:
                    item.external_attr = (0o100755 & 0xFFFF) << 16
                    item.create_system = 3
                zout.writestr(item, data)
        shutil.move(tmp, apk)
with zipfile.ZipFile(apk) as z:
    fixed = (z.getinfo(target).external_attr >> 16) & 0o777
print('    修正后权限位：%s' % oct(fixed))
assert fixed & 0o111, 'exec bit 修正失败'
PY

  # 改过 zip 内容后原签名失效，必须重签名，否则安装时报「解析包出错」
  BT="$ANDROID_HOME/build-tools/34.0.0"
  [ -d "$BT" ] || BT=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | tail -1)
  KS="$HOME/.android/debug.keystore"
  if [ -x "$BT/apksigner" ]; then
    echo "    重新签名 ..."
    mkdir -p "$(dirname "$KS")"
    [ -f "$KS" ] || keytool -genkeypair -v -keystore "$KS" -storepass android \
      -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
    "$BT/zipalign" -f -p 4 "$APK" "${APK%.apk}-aligned.apk"
    "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
      --ks-key-alias androiddebugkey --out "${APK%.apk}-signed.apk" "${APK%.apk}-aligned.apk"
    mv "${APK%.apk}-signed.apk" "$APK"
    rm -f "${APK%.apk}-aligned.apk"
    "$BT/apksigner" verify "$APK" >/dev/null 2>&1 && ok "签名校验通过"
  else
    warn "找不到 apksigner，跳过重签名。装机会提示「解析包出错」，请改用 CI 产物。"
  fi
else
  ok "zip 内权限位：${ATTR:-未知}（含执行位）"
fi

cat <<EOF

────────────────────────────────────────────────────────
 ✅ 全部完成

 APK 路径：
   $PROJECT_DIR/$APK

 下载方式（Codespaces）：
   1. 左侧文件树展开 app/build/outputs/apk/debug
   2. 右键 app-debug.apk → Download

 安装提示：
   · 签名与旧版不同，需先卸载旧 App
   · 手机需 Android 7.0+ 且为 arm64 架构

 验证通过后再推送：
   git add -A
   git commit -m "fix: 修复代理链路并重构夸克风格 UI"
   git push
────────────────────────────────────────────────────────
EOF
