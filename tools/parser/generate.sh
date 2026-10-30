#!/usr/bin/env bash
# Regenerates the Java parser from php-src 8.5.12 and php-ast 1.1.3 in the pinned toolchain image
# (tools/parser/Dockerfile: Bison 3.8.2, re2c 4.6, Python 3), and commits nothing: the result is the committed
# sources under src/main/grammar and src/main/java/pl/codeloom/php/parser/zend/{derived,generated}.
# Usage: tools/parser/generate.sh            regenerate in place
#        tools/parser/generate.sh --check    regenerate into a scratch copy and fail if it differs from what is
#                                            committed (byte for byte)
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
tag=truffle-php-parsergen:$(sha256sum tools/parser/Dockerfile | cut -c1-12)
if ! docker image inspect "$tag" > /dev/null 2>&1; then
    docker build --cpuset-cpus "${CPUSET:-6-13}" -t "$tag" tools/parser
fi
files="src/main/grammar/zend_language_parser.y src/main/grammar/zend_language_scanner.re
    src/main/java/pl/codeloom/php/parser/zend/derived/ZendConstants.java
    src/main/java/pl/codeloom/php/parser/zend/derived/PhpAstData.java
    src/main/java/pl/codeloom/php/parser/zend/generated/ZendLanguageParser.java
    src/main/java/pl/codeloom/php/parser/zend/generated/ZendLanguageScanner.java"
if [ "${1:-}" = --check ]; then
    MSYS_NO_PATHCONV=1 docker run --rm --name "parsergen-check-$$" --cpuset-cpus "${CPUSET:-6-13}" \
        -v "$root:/w:ro" -w /w "$tag" sh -c "
            mkdir -p /tmp/gen && sh tools/parser/generate-in-container.sh /tmp/gen && status=0 &&
            for f in $(echo $files); do
                if ! cmp -s \"/w/\$f\" \"/tmp/gen/\$f\"; then echo \"differs: \$f\"; status=1; fi
            done; exit \$status"
    echo "regenerated sources are the committed ones"
else
    MSYS_NO_PATHCONV=1 docker run --rm --name "parsergen-$$" --cpuset-cpus "${CPUSET:-6-13}" \
        -v "$root:/w" -w /w "$tag" sh tools/parser/generate-in-container.sh /w
fi
