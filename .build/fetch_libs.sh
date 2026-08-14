#!/usr/bin/env bash
# Re-create the headless test toolchain under /tmp/build/lib (sandbox wipes /tmp).
# Downloads Kotlin compiler + JUnit console standalone via Maven Central (through the
# sandbox HTTP(S) proxy). Run before .build/run_tests.sh whenever it fails with
# "Could not find or load main class org.jetbrains.kotlin.cli.jvm.K2JVMCompiler".
set -euo pipefail

BASE=https://repo1.maven.org/maven2
LIB=/tmp/build/lib
mkdir -p "$LIB"

# name|url
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

for entry in "${downloads[@]}"; do
  name="${entry%%|*}"
  path="${entry##*|}"
  if [ -s "$LIB/$name" ]; then
    echo "skip $name (exists)"
    continue
  fi
  echo "fetch $name ..."
  curl -sS -f -L -o "$LIB/$name" "$BASE/$path"
  echo "  -> $(stat -c%s "$LIB/$name") bytes"
done

echo "== lib dir =="
ls -la "$LIB"
