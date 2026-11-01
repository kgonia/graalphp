#!/bin/sh
# Builds php-src's crypt_blowfish.c (ext/standard of the image's own PHP source) as a shared library, so Truffle PHP
# runs the bcrypt code PHP runs, with PHP's CFLAGS in the official image (-O2 -fstack-protector-strong -fpic). The
# file needs only the C library; its entry point is php_crypt_blowfish_rn, which php_crypt calls.
# Usage (in the gate1-php image): build-bcrypt.sh <out-dir>
set -eu
out=$1
work=$(mktemp -d)
cd "$work"
tar -xJf /usr/src/php.tar.xz --wildcards '*/ext/standard/crypt_blowfish.[ch]'
cd php-*/ext/standard
mkdir -p "$out"
gcc -O2 -fstack-protector-strong -fpic -shared -Wl,--no-undefined -o "$out/libphpbcrypt.so" crypt_blowfish.c
rm -rf "$work"
echo "built $out/libphpbcrypt.so"
