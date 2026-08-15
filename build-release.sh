#!/bin/bash
# ============================================================
# Legado 阅读 · 一键打包脚本 (assembleRelease)
#
# 功能：
#   1. 自动检测本地依赖（JDK21 / Android SDK / Gradle缓存）
#      本地已有 -> 直接复用，不做重复下载
#   2. 本地缺失 -> 自动从国内镜像下载（按顺序逐个尝试，
#      哪个镜像可用就用哪个：清华 -> 华为 -> 腾讯 -> 阿里云）
#   3. 自动安装缺失的 SDK 组件（platform-36 / build-tools / NDK）
#   4. 执行 ./gradlew assembleRelease 打包
#   5. 校验 APK 签名并输出产物路径
#
# 用法：
#   ./build-release.sh            # 一键打包
#   ./build-release.sh --skip-env # 跳过环境检查，直接打包
# ============================================================
set -e

# ---------- 输出样式 ----------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info() { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# 有 sudo 就用 sudo，没有（root/容器）直接执行
SUDO=""
command -v sudo >/dev/null 2>&1 && SUDO="sudo"
asroot() { if [ -n "$SUDO" ]; then $SUDO "$@"; else "$@"; fi; }

# ---------- 路径与版本 ----------
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

JDK_HOME_DEFAULT="/opt/jdk21"
SDK_HOME_DEFAULT="/opt/android-sdk"
GRADLE_CACHE_DEFAULT="${HOME}/.gradle"

COMPILE_SDK="android-36"
BUILD_TOOLS="36.0.0"
NDK_VERSION="25.2.9519653"

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64)  JDK_ARCH="x64"      ;;
  aarch64) JDK_ARCH="aarch64"   ;;
  *) err "不支持的架构: $ARCH" ;;
esac

SKIP_ENV=0
[ "$1" = "--skip-env" ] && SKIP_ENV=1

# ---------- 镜像列表（按优先级，谁通用谁） ----------
# JDK 21 (Temurin/OpenJDK)
JDK_TUNA="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/${JDK_ARCH}/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz"
[ "$JDK_ARCH" = "aarch64" ] && \
  JDK_TUNA="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/aarch64/linux/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12_8.tar.gz"
JDK_HW="https://mirrors.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_linux-${JDK_ARCH}_bin.tar.gz"
JDK_MIRRORS=("$JDK_TUNA" "$JDK_HW")

# Android cmdline-tools
CMDLINE_GOOGLE="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TENCENT="https://mirrors.cloud.tencent.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_HW="https://mirrors.huaweicloud.com/android-sdk/commandlinetools-linux-11076708_latest.zip"
CMDLINE_MIRRORS=("$CMDLINE_GOOGLE" "$CMDLINE_TENCENT" "$CMDLINE_HW")

# ---------- 通用下载：逐个镜像尝试 ----------
# 用法: download_from_mirrors <保存路径> <url1> <url2> ...
download_from_mirrors() {
  local out="$1"; shift
  for url in "$@"; do
    info "尝试下载: $url"
    if curl -fSL --retry 2 --connect-timeout 12 --max-time 1200 -o "$out" "$url" 2>/dev/null; then
      [ -s "$out" ] && { ok "下载成功: $url"; return 0; }
    fi
    warn "该镜像下载失败，换下一个..."
  done
  rm -f "$out"
  err "所有镜像均下载失败: $out"
}

# ---------- 1. JDK 21 ----------
find_local_jdk() {
  local cand
  for cand in "${JAVA_HOME:-}" "$JDK_HOME_DEFAULT" \
              /usr/lib/jvm/jdk-21* /usr/lib/jvm/java-21* \
              /usr/lib/jvm/temurin-21* /usr/lib/jvm/java-21-openjdk-*; do
    [ -n "$cand" ] || continue
    if [ -x "$cand/bin/java" ] && "$cand/bin/java" -version 2>&1 | grep -q '"21'; then
      echo "$cand"; return 0
    fi
  done
  return 1
}

install_jdk() {
  info "未检测到 JDK 21，开始从国内镜像下载..."
  local tarball="/tmp/jdk21.tar.gz" dest_dir="/opt"
  download_from_mirrors "$tarball" "${JDK_MIRRORS[@]}"
  info "解压 JDK 到 $dest_dir ..."
  asroot mkdir -p "$dest_dir"
  asroot tar -xzf "$tarball" -C "$dest_dir"
  rm -f "$tarball"
  # 解压后目录名不确定，统一软链到 /opt/jdk21
  local dir
  dir="$(find "$dest_dir" -maxdepth 1 -type d -name 'jdk-*' | head -1)"
  [ -n "$dir" ] || err "JDK 解压失败"
  asroot rm -rf "$JDK_HOME_DEFAULT"
  asroot ln -s "$dir" "$JDK_HOME_DEFAULT"
  [ -x "$JDK_HOME_DEFAULT/bin/java" ] || err "JDK 安装失败"
  ok "JDK 21 安装完成: $JDK_HOME_DEFAULT"
}

setup_env() {
  info "=== 检查 JDK 21 ==="
  local jdk
  if jdk="$(find_local_jdk)"; then
    JAVA_HOME="$jdk"; ok "复用本地 JDK: $JAVA_HOME"
  else
    install_jdk; JAVA_HOME="$JDK_HOME_DEFAULT"
  fi
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"

  info "=== 检查 Android SDK ==="
  local sdk="$SDK_HOME_DEFAULT"
  local sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sdkmanager" ]; then
    info "未检测到 cmdline-tools，下载安装..."
    asroot mkdir -p "$sdk/cmdline-tools"
    local clt="/tmp/cmdline-tools.zip"
    download_from_mirrors "$clt" "${CMDLINE_MIRRORS[@]}"
    asroot unzip -q -o "$clt" -d "$sdk/cmdline-tools"
    rm -f "$clt"
    asroot mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest" 2>/dev/null || true
    [ -x "$sdkmanager" ] || err "cmdline-tools 安装失败"
    ok "cmdline-tools 安装完成"
  else
    ok "复用本地 cmdline-tools"
  fi

  # 接受许可证（幂等）
  yes | "$sdkmanager" --sdk_root="$sdk" --licenses >/dev/null 2>&1 || true
  asroot mkdir -p "$sdk/licenses"
  printf "24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n" | asroot tee "$sdk/licenses/android-sdk-license" >/dev/null
  printf "84831b9409646a918e30573bab4c9c91346d8abd\n" | asroot tee "$sdk/licenses/android-sdk-preview-license" >/dev/null

  # 检查并安装缺失组件
  local missing=0 need=""
  for pkg in "platforms;$COMPILE_SDK" "build-tools;$BUILD_TOOLS" "ndk;$NDK_VERSION"; do
    if ! "$sdkmanager" --sdk_root="$sdk" --list_installed 2>/dev/null | grep -q "^[[:space:]]*$pkg"; then
      missing=1; need="$need $pkg"
    fi
  done
  if [ "$missing" = "1" ]; then
    info "安装缺失的 SDK 组件:$need（优先 dl.google.com，失败自动切镜像）..."
    if yes | "$sdkmanager" --sdk_root="$sdk" --install $need >/dev/null 2>&1; then
      ok "SDK 组件安装完成"
    else
      warn "默认源安装失败，切换腾讯云镜像重试..."
      local cfg="${HOME}/.android/repositories.cfg"
      mkdir -p "$(dirname "$cfg")"
      echo '<?xml version="1.0" encoding="utf-8"?>
<sdk-repository>
    <add-on>https://mirrors.cloud.tencent.com/android/addon2.xml</add-on>
    <repository>https://mirrors.cloud.tencent.com/android/repository2-1.xml</repository>
    <system-image>https://mirrors.cloud.tencent.com/android/sys-img2-1.xml</system-image>
    <extra>https://mirrors.cloud.tencent.com/android/extra.xml</extra>
</sdk-repository>' > "$cfg"
      yes | "$sdkmanager" --sdk_root="$sdk" --install $need >/dev/null 2>&1 || err "SDK 组件安装失败"
      ok "SDK 组件安装完成（腾讯镜像）"
    fi
  else
    ok "SDK 组件齐全: platform-36 / build-tools / NDK"
  fi

  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
  export PATH="$sdk/platform-tools:$sdk/cmdline-tools/latest/bin:$PATH"
}

# ---------- 2. 打包 ----------
do_build() {
  info "=== 开始打包 assembleRelease ==="
  info "Gradle wrapper 将自动下载 Gradle 8.14.3（项目已配置腾讯镜像）"
  info "本地 Gradle 依赖缓存: ${GRADLE_CACHE_DEFAULT} 存在则自动复用"
  [ -d "$GRADLE_CACHE_DEFAULT" ] && ok "复用本地 Gradle 缓存 $GRADLE_CACHE_DEFAULT"

  ./gradlew assembleRelease --no-daemon 2>&1 | tee /tmp/legado_build.log
  local code=${PIPESTATUS[0]}
  [ "$code" -ne 0 ] && err "构建失败，日志: /tmp/legado_build.log"
  ok "构建成功"
}

# ---------- 3. 产物验证 ----------
verify_apk() {
  info "=== 验证 APK ==="
  local apk
  apk="$(find "$PROJECT_DIR/app/build/outputs/apk" -name "*.apk" -type f -newer /tmp/legado_build.log 2>/dev/null | head -1)"
  [ -z "$apk" ] && apk="$(find "$PROJECT_DIR/app/build/outputs/apk" -name "*.apk" -type f | sort | tail -1)"
  [ -z "$apk" ] && err "未找到 APK 产物"

  local size
  size="$(ls -lh "$apk" | awk '{print $5}')"
  ok "APK 产物: $apk (${size})"

  local apksigner="$SDK_HOME_DEFAULT/build-tools/$BUILD_TOOLS/apksigner"
  if [ -x "$apksigner" ]; then
    info "签名证书信息:"
    "$apksigner" verify --print-certs "$apk" 2>/dev/null | grep -E "Signer #1|SHA-1" | head -3 || warn "apksigner 验证失败"
  fi
  info "安装: adb install -r \"$apk\""
}

# ---------- main ----------
echo "=============================================="
echo "  Legado 阅读 · 一键打包"
echo "  架构: $ARCH | 签名: release-testkey.jks"
echo "=============================================="

[ "$SKIP_ENV" = "1" ] && info "跳过环境检查（--skip-env）" || setup_env
do_build
verify_apk
ok "全部完成 🎉"
