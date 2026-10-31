#!/usr/bin/env bash
# 跨平台构建脚本（Windows 用 Git Bash，macOS/Linux 用 sh）
# 用法: ./build.sh <versionCode>   例如 ./build.sh 226
# 依赖目录结构: 仓库在 work/bridge，工具链在同级 work/toolchain（jdk-17.0.20.1+1 / gradle-8.13 / keystore）
set -e

VC="${1:?用法: ./build.sh <versionCode>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="$(cd "$HERE/../toolchain" && pwd)"

# 把工具链路径转成当前平台 java/gradle 能识别的形式
if uname | grep -qi -e mingw -e msys -e cygwin; then
  JHOME="$(cygpath -w "$TC/jdk-17.0.20.1+1")"
  KS="$(cygpath -w "$TC/keystore/debug.keystore")"
  GRADLE="$TC/gradle-8.13/bin/gradle.bat"
else
  JHOME="$TC/jdk-17.0.20.1+1"
  KS="$TC/keystore/debug.keystore"
  GRADLE="$TC/gradle-8.13/bin/gradle"
fi

export JAVA_HOME="$JHOME"
export OPLUSBAND_KEYSTORE="$KS"
export OPLUSBAND_STORE_PASSWORD=android
export OPLUSBAND_KEY_ALIAS=androiddebugkey
export OPLUSBAND_KEY_PASSWORD=android

if [ ! -f "$GRADLE" ]; then
  echo "ERROR: 找不到 gradle，请先准备 toolchain（见 docs/DEV.md 的『工具链』一节）"
  exit 1
fi

"$GRADLE" -I init.local.gradle :app:assembleDebug -PhotReloadVersionCode="$VC"
echo "BUILD DONE -> app/build/outputs/apk/debug/app-debug.apk (versionCode=$VC)"
