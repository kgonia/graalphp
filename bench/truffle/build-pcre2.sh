#!/bin/sh
# Builds the PCRE2 that PHP bundles (ext/pcre/pcre2lib of the image's own PHP source) as a shared library with
# the standard pcre2_*_8 symbol names, so Truffle PHP calls the same regex engine code as the PHP it is compared
# with (D7/D9). PHP's php_compat.h renames the symbols to php_pcre2_*, so config.h is used without PHP's headers.
# Usage (in the gate1-php image): build-pcre2.sh <out-dir>
set -eu
out=$1
work=$(mktemp -d)
cd "$work"
tar -xJf /usr/src/php.tar.xz --wildcards '*/ext/pcre/pcre2lib/*'
cd php-*/ext/pcre/pcre2lib
sed -i -e '/#include <php_compat.h>/d' -e '/# *include <php_config.h>/d' -e '/# *include <config.w32.h>/d' \
    -e '/#ifdef PHP_WIN32/,/#endif/d' config.h
mkdir -p "$out"
# Every source except those PCRE2 #includes into others (the JIT matcher and helpers into pcre2_jit_compile.c,
# the Unicode property tables into pcre2_tables.c) and pcre2test's printer. PHP's CFLAGS in the official
# image: -O2 -fstack-protector-strong -fpic.
sources=$(ls pcre2_*.c | grep -v -e '^pcre2_jit_match.c$' -e '^pcre2_jit_misc.c$' -e '^pcre2_printint.c$' \
    -e '^pcre2_ucptables.c$')
gcc -O2 -fstack-protector-strong -fpic -shared -Wl,--no-undefined -DHAVE_CONFIG_H -DPCRE2_CODE_UNIT_WIDTH=8 \
    -DHAVE_PCRE_JIT_SUPPORT=1 -DHAVE_STDINT_H=1 -DHAVE_INTTYPES_H=1 -DHAVE_MEMMOVE=1 -I. \
    -o "$out/libpcre2-8.so" $sources
cp pcre2.h "$out/"
rm -rf "$work"
echo "built $out/libpcre2-8.so"
