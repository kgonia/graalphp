#!/usr/bin/env bash
# Builds truffle-dsl-processor 25.4.4.1.1 with the patches in patches/ and installs it in ~/.m2 as
# org.graalvm.truffle:truffle-dsl-processor:25.4.4.1.1-patched. Only the changed classes are recompiled; everything
# else in the jar is the released one. Needs JAVA_HOME (JDK 21+) and mvn on PATH.
set -euo pipefail
cd "$(dirname "$0")"
base=25.4.4.1.1
version=$base-patched
m2=$HOME/.m2/repository/org/graalvm/truffle/truffle-dsl-processor/$base
jar=$m2/truffle-dsl-processor-$base.jar
[ -f "$jar" ] || mvn -q -B dependency:get -Dartifact=org.graalvm.truffle:truffle-dsl-processor:$base
src=target/truffle/src/com.oracle.truffle.dsl.processor/src
rm -rf target && mkdir -p "$src" target/classes
curl -sSfL -o target/sources.jar \
  https://repo1.maven.org/maven2/org/graalvm/truffle/truffle-dsl-processor/$base/truffle-dsl-processor-$base-sources.jar
(cd "$src" && unzip -q ../../../../sources.jar)
# the patches use upstream's paths (truffle/src/...), applied from target/; patch, not git apply: inside a git
# checkout git apply skips paths outside the current directory without an error, and the jar would be the stock one
(cd target && for p in ../patches/*.diff; do patch -p1 --fuzz=0 --batch -s -i "$p"; echo "applied $p"; done)
cp "$jar" target/truffle-dsl-processor-$version.jar
w() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
"$JAVA_HOME/bin/javac" -nowarn -d target/classes -cp "$(w "$jar")" \
  $(sed -n 's#^+++ b/#target/#p' patches/*.diff | sort -u)
(cd target/classes && "$JAVA_HOME/bin/jar" uf ../truffle-dsl-processor-$version.jar com)
mvn -q -B install:install-file -Dfile="$(w "$PWD/target/truffle-dsl-processor-$version.jar")" \
  -DgroupId=org.graalvm.truffle -DartifactId=truffle-dsl-processor -Dversion=$version -Dpackaging=jar
echo "installed org.graalvm.truffle:truffle-dsl-processor:$version"
