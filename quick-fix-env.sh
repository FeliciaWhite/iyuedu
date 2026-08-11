#!/bin/bash
# 快速修复Android构建环境脚本
# 适用于当前CNB工作空间环境缺失问题

set -e

echo "🚀 开始快速修复Android构建环境..."

# 检查并安装Java 17
if ! command -v java &> /dev/null; then
    echo "📦 安装OpenJDK 17..."
    apt-get update
    apt-get install -y openjdk-17-jdk-headless
    echo "✅ OpenJDK 17安装完成"
else
    echo "✅ Java已安装: $(java -version 2>&1 | head -1)"
fi

# 设置JAVA_HOME
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
echo "🔧 JAVA_HOME设置为: $JAVA_HOME"

# 验证Gradle
echo "📋 验证Gradle环境..."
./gradlew --version

# 检查Android SDK
if [ ! -d "/opt/android-sdk" ]; then
    echo "⚠️ Android SDK未安装。请重新启动工作空间以应用CNB配置。"
    echo "   或者运行: apt-get update && apt-get install -y wget unzip && ./setup-android-sdk.sh"
else
    echo "✅ Android SDK已安装: /opt/android-sdk"
fi

echo ""
echo "🎉 环境修复完成！"
echo "👉 接下来可执行: ./gradlew assembleDebug"
echo "👉 或等待下次工作空间启动时自动应用CNB完整配置"