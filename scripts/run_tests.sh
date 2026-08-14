#!/usr/bin/env bash
# 一键运行 React Unit Converter 插件的 headless 单元测试。
#
# 把「手动跑起来」的完整过程脚本化，覆盖沙箱 /tmp 被清空、JDK 被卸载等环境丢失场景：
#   1. 定位 JDK 17（优先 $JAVA_HOME，其次 mise /usr/lib/jvm 下的 17，最后 PATH 上的 java）
#   2. 确保 /tmp/build/lib 下的 Kotlin 编译器 + JUnit 控制台 jar 就绪，缺则从 Maven Central 下载
#   3. 编译 headless main 源码
#   4. 编译 test 源码
#   5. 运行 JUnit 5
#
# 用法: bash scripts/run_tests.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/.build"
LIB=/tmp/build/lib
BASE=https://repo1.maven.org/maven2

# ---------------------------------------------------------------- 1. JDK 17
# Kotlin 1.9.10 编译器无法解析 25 这种新版本号（JavaVersion.parse 会抛异常），
# 所以必须优先挑一个 17 系的 JDK，而不是随便用 $JAVA_HOME。
find_jdk() {
  # 1. mise 安装的 JDK 17
  for d in /root/.local/share/mise/installs/java/17*; do
    [ -x "$d/bin/java" ] && { echo "$d"; return 0; }
  done
  # 2. /usr/lib/jvm 下的 17
  for d in /usr/lib/jvm/*17*; do
    [ -x "$d/bin/java" ] && { echo "$d"; return 0; }
  done
  # 3. $JAVA_HOME（仅当版本是 17）
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] \
    && "$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -q '"17'; then
    echo "$JAVA_HOME"; return 0
  fi
  # 4. PATH 上的 java（仅当版本是 17）
  local j; j="$(command -v java 2>/dev/null || true)"
  if [ -n "$j" ] && "$j" -version 2>&1 | head -1 | grep -q '"17'; then
    echo "$(cd "$(dirname "$j")/.." && pwd)"; return 0
  fi
  return 1
}

JDK="$(find_jdk || true)"
if [ -z "$JDK" ]; then
  echo "错误: 找不到可用的 JDK 17，请先安装或用 JAVA_HOME 指向 JDK 17" >&2
  exit 1
fi
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$PATH"
echo "== JDK: $JAVA_HOME =="
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# ------------------------------------------------------- 2. 工具链 jar 就绪
# name|maven-path
downloads=(
  "kotlin-compiler.jar|org/jetbrains/kotlin/kotlin-compiler/1.9.10/kotlin-compiler-1.9.10.jar"
  "kotlin-daemon-embeddable.jar|org/jetbrains/kotlin/kotlin-daemon-embeddable/1.9.10/kotlin-daemon-embeddable-1.9.10.jar"
  "kotlin-stdlib.jar|org/jetbrains/kotlin/kotlin-stdlib/1.9.10/kotlin-stdlib-1.9.10.jar"
  "kotlin-stdlib-common.jar|org/jetbrains/kotlin/kotlin-stdlib-common/1.9.10/kotlin-stdlib-common-1.9.10.jar"
  "kotlin-reflect.jar|org/jetbrains/kotlin/kotlin-reflect/1.9.10/kotlin-reflect-1.9.10.jar"
  "kotlin-stdlib-jdk8.jar|org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.9.10/kotlin-stdlib-jdk8-1.9.10.jar"
  "kotlin-stdlib-jdk7.jar|org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.9.10/kotlin-stdlib-jdk7-1.9.10.jar"
  "annotations-13.jar|org/jetbrains/annotations/13.0/annotations-13.0.jar"
  "trove4j.jar|org/jetbrains/intellij/deps/trove4j/1.0.20181211/trove4j-1.0.20181211.jar"
  "gson.jar|com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"
  "snakeyaml.jar|org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar"
  "kotlin-test.jar|org/jetbrains/kotlin/kotlin-test/1.9.10/kotlin-test-1.9.10.jar"
  "kotlin-test-common.jar|org/jetbrains/kotlin/kotlin-test-common/1.9.10/kotlin-test-common-1.9.10.jar"
  "kotlin-test-junit5.jar|org/jetbrains/kotlin/kotlin-test-junit5/1.9.10/kotlin-test-junit5-1.9.10.jar"
  "junit-console.jar|org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar"
)

mkdir -p "$LIB"
need_fetch=0
for entry in "${downloads[@]}"; do
  name="${entry%%|*}"
  [ -s "$LIB/$name" ] || { need_fetch=1; break; }
done
if [ "$need_fetch" -eq 1 ]; then
  echo "== 下载工具链 jar 到 $LIB =="
  for entry in "${downloads[@]}"; do
    name="${entry%%|*}"
    path="${entry##*|}"
    if [ -s "$LIB/$name" ]; then
      echo "skip $name (exists)"
      continue
    fi
    echo "fetch $name ..."
    curl -sS -f -L -o "$LIB/$name" "$BASE/$path"
  done
else
  echo "== 工具链 jar 已就绪，跳过下载 =="
fi

KOMPILE_CP="$LIB/kotlin-compiler.jar:$LIB/kotlin-daemon-embeddable.jar:$LIB/kotlin-stdlib.jar:$LIB/kotlin-stdlib-common.jar:$LIB/kotlin-reflect.jar:$LIB/kotlin-stdlib-jdk8.jar:$LIB/kotlin-stdlib-jdk7.jar:$LIB/annotations-13.jar:$LIB/trove4j.jar"
CP_MAIN="$LIB/kotlin-stdlib.jar:$LIB/kotlin-stdlib-common.jar:$LIB/kotlin-stdlib-jdk7.jar:$LIB/kotlin-stdlib-jdk8.jar:$LIB/kotlin-reflect.jar:$LIB/gson.jar:$LIB/snakeyaml.jar:$LIB/annotations-13.jar:$LIB/trove4j.jar"
CP_TEST_BASE="$LIB/kotlin-test.jar:$LIB/kotlin-test-common.jar:$LIB/kotlin-test-junit5.jar"
CP_JUNIT_CONSOLE_FAT="$LIB/junit-console.jar"

MAIN_OUT="$BUILD/out/main"
TEST_OUT="$BUILD/out/test"
rm -rf "$MAIN_OUT" "$TEST_OUT"; mkdir -p "$MAIN_OUT" "$TEST_OUT"

# 纯逻辑 main 源码（不依赖 IntelliJ SDK），保证 JVM 测试可编译运行
cat > "$BUILD/headless_mains.list" << 'EOF'
src/main/kotlin/com/github/reactunitconverter/model/Px2RemConfig.kt
src/main/kotlin/com/github/reactunitconverter/converter/InlineStylePxConverter.kt
src/main/kotlin/com/github/reactunitconverter/extract/InlineStyleExtractor.kt
src/main/kotlin/com/github/reactunitconverter/extract/ClassNameInferencer.kt
src/main/kotlin/com/github/reactunitconverter/config/Px2RemConfigDetector.kt
src/main/kotlin/com/github/reactunitconverter/service/ProjectConfigState.kt
src/main/kotlin/com/github/reactunitconverter/analyzer/ReactCssPropertyShape.kt
src/main/kotlin/com/github/reactunitconverter/extract/CssModuleImportPath.kt
EOF

cd "$ROOT"
find src/test/kotlin -name "*.kt" -type f | sort > "$BUILD/test_sources.list"

echo "== step1 compile headless main kotlin sources =="
java -Xmx3G -cp "$KOMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -d "$MAIN_OUT" \
  -classpath "$CP_MAIN" \
  @"$BUILD/headless_mains.list"

echo "== step2 compile test kotlin sources =="
java -Xmx3G -cp "$KOMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -d "$TEST_OUT" \
  -classpath "$MAIN_OUT:$CP_MAIN:$CP_TEST_BASE:$CP_JUNIT_CONSOLE_FAT" \
  @"$BUILD/test_sources.list"

echo "== step3 run JUnit 5 =="
java -Xmx2G -jar "$CP_JUNIT_CONSOLE_FAT" \
  --class-path "$MAIN_OUT:$TEST_OUT:$CP_MAIN:$CP_TEST_BASE" \
  --scan-classpath --disable-ansi-colors
