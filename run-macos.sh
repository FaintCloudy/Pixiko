#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Pixiko —— macOS / Linux 启动脚本（对应 Windows 的 run.bat + build.ps1）
#
#   ./run-macos.sh            编译源码后启动机器人
#   ./run-macos.sh --setup    同上，并进入命令行配置向导
#   ./run-macos.sh --help     查看机器人自带的参数
#
#   JAVA_HOME=/path/to/jdk ./run-macos.sh     指定 JDK（需 17 及以上）
#
# 与 run.bat 保持一致的两件事，别改：
#   1) -Dbot.home 指向本仓库根目录，config.json / data / logs / work 都相对它解析；
#   2) Spring Boot 的 jar 只挂在运行期 classpath 上，绝不合并进 build/pixiko.jar ——
#      同名 META-INF 条目（spring.factories、AutoConfiguration.imports）会被覆盖，
#      Spring Boot 会静默退化成非 Web 应用。
#
# 兼容 macOS 自带的 bash 3.2：不使用数组，也不使用 set -u。
# ---------------------------------------------------------------------------

cd "$(dirname "$0")" || exit 1
ROOT="$(pwd)"

GSON="lib/gson-2.13.1.jar"
GSON_URL="https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.1/gson-2.13.1.jar"
GSON_SHA256="94855942d4992f112946d3de1c334e709237b8126d8130bf07807c018a4a2120"
SPRING_DIR="lib/spring"
CLASSES_DIR="build/classes"
OUT_JAR="build/pixiko.jar"
MANIFEST="build/MANIFEST.MF"
MAIN_CLASS="cn.szu.bot.Main"

err() { printf '\n[错误] %s\n' "$1" >&2; exit 1; }
info() { printf '%s\n' "$1"; }

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
info "JDK     : $JDK ($("$JDK/bin/javac" -version 2>&1))"

# --- 2. gson 依赖：与 build.ps1 用同一个 SHA-256 ---------------------------
verify_gson() {
    [ -f "$GSON" ] || return 1
    [ "$(shasum -a 256 "$GSON" 2>/dev/null | awk '{print $1}')" = "$GSON_SHA256" ]
}
if ! verify_gson; then
    info "依赖    : $GSON 缺失或校验不符，正在下载 …"
    mkdir -p lib
    curl -fsSL --retry 3 -o "$GSON" "$GSON_URL" \
        || err "gson 下载失败，请检查网络，或自行下载后放到 $GSON。"
    verify_gson || err "gson SHA-256 校验失败（期望 $GSON_SHA256），已下载的文件不可信。"
fi
info "依赖    : $GSON (SHA-256 校验通过)"

# --- 3. Spring Boot 运行时（不在仓库里，见 README）------------------------
if [ ! -d "$SPRING_DIR" ] || [ -z "$(ls -A "$SPRING_DIR" 2>/dev/null)" ]; then
    err "缺少 $SPRING_DIR（网页层用的 Spring Boot jar）。获取办法见 README.md「依赖的第三方 jar 不在本仓库里」：用 Maven 解析 Spring Boot 3.5.6 的 spring-boot-starter-web，把解析到的 jar 全部放进 $SPRING_DIR。"
fi
info "依赖    : $SPRING_DIR ($(ls -1 "$SPRING_DIR" | wc -l | tr -d ' ') 个 jar)"

# --- 4. 编译主源码（等价 build.ps1 的 javac --release 17）------------------
mkdir -p "$CLASSES_DIR" build
find src/main/java -name '*.java' | sort > build/sources.txt
[ -s build/sources.txt ] || err "src/main/java 下没有找到 .java 源文件。"
info "编译    : $(wc -l < build/sources.txt | tr -d ' ') 个源文件 …"
"$JDK/bin/javac" --release 17 -encoding UTF-8 \
    -cp "$GSON:$SPRING_DIR/*" -d "$CLASSES_DIR" "@build/sources.txt" \
    || err "Java 编译失败。"
info "编译    : 完成，$(find "$CLASSES_DIR" -name '*.class' | wc -l | tr -d ' ') 个 class"

# --- 5. 打包（manifest 与 build.ps1 逐字一致）-----------------------------
printf 'Manifest-Version: 1.0\nMain-Class: %s\nClass-Path: ../lib/gson-2.13.1.jar\n\n' "$MAIN_CLASS" > "$MANIFEST"
"$JDK/bin/jar" --create --file "$OUT_JAR" --manifest "$MANIFEST" -C "$CLASSES_DIR" . \
    || err "打包 $OUT_JAR 失败。"
info "打包    : $OUT_JAR"

# --- 6. 启动 --------------------------------------------------------------
info "启动    : $MAIN_CLASS（bot.home=$ROOT）"
info "         网页控制台 http://127.0.0.1:8787 ，首次启动的配置页在 /setup"
info ""
exec "$JDK/bin/java" \
    -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    "-Dbot.home=$ROOT/." \
    -cp "$ROOT/$OUT_JAR:$ROOT/$GSON:$ROOT/$SPRING_DIR/*" \
    "$MAIN_CLASS" "$@"
