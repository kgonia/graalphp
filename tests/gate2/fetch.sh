#!/usr/bin/env bash
# Fetches the whole WordPress core at the commit the Gate 1 kernels use, into target/gate2/wordpress.
set -euo pipefail
cd "$(dirname "$0")/../.."
WP=d86e2079edbf869a0393ee99a91da19351420461         # WordPress/WordPress trunk, 2026-10-22 (as bench/gate1/fetch.sh)
rm -rf target/gate2/wordpress && mkdir -p target/gate2
curl -fsSL "https://codeload.github.com/WordPress/WordPress/tar.gz/$WP" | tar -xz -C target/gate2
mv "target/gate2/WordPress-$WP" target/gate2/wordpress
echo "fetched WordPress $WP: $(find target/gate2/wordpress -name '*.php' | wc -l) PHP files"
