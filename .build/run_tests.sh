#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/tmp/tools/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"

LIB=/tmp/build/lib
KOMPILE_CP="$LIB/kotlin-compiler.jar:$LIB/kotlin-daemon-embeddable.jar:$LIB/kotlin-stdlib.jar:$LIB/kotlin-stdlib-common.jar:$LIB/kotlin-reflect.jar:$LIB/kotlin-stdlib-jdk8.jar:$LIB/kotlin-stdlib-jdk7.jar:$LIB/annotations-13.jar:$LIB/trove4j.jar"
CP_MAIN="$LIB/kotlin-stdlib.jar:$LIB/kotlin-stdlib-common.jar:$LIB/kotlin-stdlib-jdk7.jar:$LIB/kotlin-stdlib-jdk8.jar:$LIB/kotlin-reflect.jar:$LIB/gson.jar:$LIB/snakeyaml.jar:$LIB/annotations-13.jar:$LIB/trove4j.jar"
CP_TEST_BASE="$LIB/kotlin-test.jar:$LIB/kotlin-test-common.jar:$LIB/kotlin-test-junit5.jar"
CP_JUNIT_CONSOLE_FAT="$LIB/junit-console.jar"
# junit-platform-console-standalone is a fat jar that includes jupiter + platform classes.
# Use it also as a compile-time dependency so tests see org.junit.jupiter.api.* / opentest4j / platform.*
# without needing the individual jars (which were partially corrupted by 429 responses).

MAIN_OUT=/workspace/.build/out/main
TEST_OUT=/workspace/.build/out/test
rm -rf "$MAIN_OUT" "$TEST_OUT"; mkdir -p "$MAIN_OUT" "$TEST_OUT"

cat > /workspace/.build/headless_mains.list << 'EOF'
src/main/kotlin/com/github/reactunitconverter/model/Px2RemConfig.kt
src/main/kotlin/com/github/reactunitconverter/converter/InlineStylePxConverter.kt
src/main/kotlin/com/github/reactunitconverter/extract/InlineStyleExtractor.kt
src/main/kotlin/com/github/reactunitconverter/extract/ClassNameInferencer.kt
src/main/kotlin/com/github/reactunitconverter/config/Px2RemConfigDetector.kt
src/main/kotlin/com/github/reactunitconverter/service/ProjectConfigState.kt
src/main/kotlin/com/github/reactunitconverter/analyzer/ReactCssPropertyShape.kt
EOF

find src/test/kotlin -name "*.kt" -type f | sort > /workspace/.build/test_sources.list

echo "== step1 compile headless main kotlin sources =="
java -Xmx3G -cp "$KOMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -d "$MAIN_OUT" \
  -classpath "$CP_MAIN" \
  @/workspace/.build/headless_mains.list

echo "== step2 compile test kotlin sources =="
java -Xmx3G -cp "$KOMPILE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -d "$TEST_OUT" \
  -classpath "$MAIN_OUT:$CP_MAIN:$CP_TEST_BASE:$CP_JUNIT_CONSOLE_FAT" \
  @/workspace/.build/test_sources.list

echo "== step3 run JUnit 5 =="
java -Xmx2G -jar "$CP_JUNIT_CONSOLE_FAT" \
  --class-path "$MAIN_OUT:$TEST_OUT:$CP_MAIN:$CP_TEST_BASE" \
  --scan-classpath --disable-ansi-colors
