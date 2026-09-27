#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Pixiko —— macOS / Linux 启动脚本（对应 Windows 的 start.bat）
#
# 与 run-macos.sh 的区别：本脚本**只启动，不编译**，用的是已经躺在
# build/pixiko.jar 里的现成产物 —— 对应 Windows 那个「开箱即用包」的入口。
# 源码检出（本仓库）请优先用 run-macos.sh，它会先编译。
#
#   ./start-macos.sh           启动
#   ./start-macos.sh --help    查看机器人自带的参数
#
# 环境变量 JAVA_HOME 可指定 JDK（需 17 及以上）。
# 兼容 macOS 自带的 bash 3.2：不使用数组，也不使用 set -u。
# ---------------------------------------------------------------------------

cd "$(dirname "$0")" || exit 1
ROOT="$(pwd)"

GSON="lib/gson-2.13.1.jar"
SPRING_DIR="lib/spring"
OUT_JAR="build/pixiko.jar"
MAIN_CLASS="cn.szu.bot.Main"

err() { printf '\n[错误] %s\n' "$1" >&2; exit 1; }

# --- 1. 找 JDK 17+ ---------------------------------------------------------
try_jdk() {
    [ -n "$1" ] || return 1
    [ -x "$1/bin/javac" ] || return 1
    _major="$("$1/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
    [ -n "$_major" ] && [ "$_major" -ge 17 ] || return 1
    printf '%s' "$1"
}

JDK="$(try_jdk "${JAVA_HOME:-}")"
if [ -z "$JDK" ]; then
    JDK="$(try_jdk "$(/usr/libexec/java_home -v 17 2>/dev/null)")"
fi
if [ -z "$JDK" ]; then
    _javac="$(command -v javac 2>/dev/null)"
    if [ -n "$_javac" ]; then
        JDK="$(try_jdk "$(cd "$(dirname "$_javac")/.." && pwd)")"
    fi
fi
[ -n "$JDK" ] || err "找不到 JDK 17 或更高版本。请安装 JDK 并把 java/javac 放进 PATH，或用 JAVA_HOME 指定。"

# --- 2. 产物与依赖检查（与 start.bat 的三项检查一致）----------------------
[ -f "$OUT_JAR" ] || err "找不到 $OUT_JAR。本脚本用于已编译好的包；源码检出请用 ./run-macos.sh（它会先编译）。"
[ -d "$SPRING_DIR" ] && [ -n "$(ls -A "$SPRING_DIR" 2>/dev/null)" ] || err "找不到 $SPRING_DIR（网页层用的 Spring Boot jar）。获取办法见 README.md。"
[ -f "$GSON" ] || err "找不到 $GSON。run-macos.sh 会自动下载；也可照 README.md 手工获取。"

# --- 3. 启动 --------------------------------------------------------------
printf '%s\n' "JDK     : $JDK ($("$JDK/bin/javac" -version 2>&1))"
printf '%s\n' "启动    : $MAIN_CLASS（bot.home=$ROOT）"
printf '%s\n' "         网页控制台 http://127.0.0.1:8787 ，首次启动的配置页在 /setup"
printf '\n'
exec "$JDK/bin/java" \
    -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    "-Dbot.home=$ROOT/." \
    -cp "$ROOT/$OUT_JAR:$ROOT/$GSON:$ROOT/$SPRING_DIR/*" \
    "$MAIN_CLASS" "$@"
