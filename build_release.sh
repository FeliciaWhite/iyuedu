#!/bin/bash
set -e
export ANDROID_HOME=/workspace/yilai/android-sdk
cd /workspace
./gradlew assembleRelease --no-daemon > /tmp/gradle_build.log 2>&1
echo "BUILD_EXIT_CODE=$?" >> /tmp/gradle_build.log
