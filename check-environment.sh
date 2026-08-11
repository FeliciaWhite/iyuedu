#!/bin/bash

echo "=== Legado构建环境健康检查 ==="
echo ""

echo "1. Java环境..."
java -version 2>&1 | grep -E "(version|Java)" || echo "❌ Java未安装或配置不正确"

echo ""
echo "2. Android SDK..."
if [ -d "/opt/android-sdk/build-tools/35.0.0" ]; then
    echo "✅ build-tools 35.0.0 存在"
else
    echo "❌ build-tools 35.0.0 缺失"
fi

if [ -d "/opt/android-sdk/platforms/android-35" ]; then
    echo "✅ platforms;android-35 存在"
else
    echo "❌ platforms;android-35 缺失"
fi

if [ -d "/opt/android-sdk/platforms/android-36" ]; then
    echo "✅ platforms;android-36 存在"
else
    echo "⚠️  platforms;android-36 缺失（非必需）"
fi

echo ""
echo "3. Gradle版本..."
./gradlew --version 2>&1 | grep "Gradle" || echo "❌ Gradle初始化失败"

echo ""
echo "4. 项目结构..."
[ -f "app/build.gradle" ] && echo "✅ app/build.gradle 存在" || echo "❌ app/build.gradle 缺失"
[ -f "gradle.properties" ] && echo "✅ gradle.properties 存在" || echo "❌ gradle.properties 缺失"
[ -f "gradle/wrapper/gradle-wrapper.properties" ] && echo "✅ gradle-wrapper.properties 存在" || echo "❌ gradle-wrapper.properties 缺失"
[ -f "local.properties" ] && echo "✅ local.properties 存在" || echo "⚠️  local.properties 缺失（可能使用环境变量）"

echo ""
echo "5. 关键目录权限..."
ls -ld /opt/android-sdk/ 2>/dev/null | awk '{print "✅ Android SDK权限: "$1}' || echo "❌ 无法访问Android SDK目录"

echo ""
echo "6. 环境变量..."
echo "   JAVA_HOME: ${JAVA_HOME:-未设置}"
echo "   ANDROID_HOME: ${ANDROID_HOME:-未设置}"

echo ""
echo "=== 检查完成 ==="
echo ""
echo "建议："
echo "1. 如果有❌标记的问题，请根据WAKEUP.md中的故障排除指南解决"
echo "2. 运行构建测试：./gradlew assembleAppRelease --stacktrace"
echo "3. 验证APK输出：find app/build/outputs/apk -name \"*.apk\" -type f"