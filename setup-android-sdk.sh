#!/bin/bash
# Android SDK手动安装脚本（备用方案）

set -e

echo "📱 开始安装Android SDK..."

# 设置环境变量
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

# 创建目录
mkdir -p ${ANDROID_HOME}/cmdline-tools

# 下载命令行工具（使用腾讯云镜像加速）
echo "⬇️ 下载Android命令行工具（腾讯云镜像）..."
wget -q https://mirrors.cloud.tencent.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip

echo "📦 解压..."
unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools
mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest
rm /tmp/cmdline-tools.zip

# 配置腾讯云镜像源
echo "⚙️ 配置腾讯云Android镜像源..."
mkdir -p /root/.android
echo '<?xml version="1.0" encoding="utf-8"?>
<sdk-repository>
    <add-on>https://mirrors.cloud.tencent.com/android/addon2.xml</add-on>
    <repository>https://mirrors.cloud.tencent.com/android/repository2-1.xml</repository>
    <system-image>https://mirrors.cloud.tencent.com/android/sys-img2-1.xml</system-image>
    <extra>https://mirrors.cloud.tencent.com/android/extra.xml</extra>
</sdk-repository>' > /root/.android/repositories.cfg

export REPO_HOST=mirrors.cloud.tencent.com

# 接受许可证
echo "📝 接受Android SDK许可证..."
yes | sdkmanager --licenses || true

# 安装必要组件
echo "🔧 安装Android平台工具..."
sdkmanager "platform-tools"

echo "🔧 安装Android SDK 35..."
sdkmanager "platforms;android-35"

echo "🔧 安装Build Tools 35.0.0..."
sdkmanager "build-tools;35.0.0"

echo "🔧 安装NDK..."
sdkmanager "ndk;26.1.10909125"

echo ""
echo "✅ Android SDK安装完成！"
echo "👉 ANDROID_HOME: ${ANDROID_HOME}"
echo "👉 可用命令: sdkmanager, adb, fastboot"