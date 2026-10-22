# Truffle PHP on Linux, in the reference PHP image (same Debian and CPU), calling the same PCRE2 code PHP uses:
# its bundled pcre2lib, built here as a shared library (build-pcre2.sh). Build:
#   docker build -t gate1-truffle:8.5 bench/truffle    (needs graalvm-linux.tar.gz here:
#   https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-x64_bin.tar.gz)
ARG PHP=8.5
FROM gate1-php:${PHP}
COPY graalvm-linux.tar.gz build-pcre2.sh /tmp/
RUN mkdir /opt/graalvm && tar -xzf /tmp/graalvm-linux.tar.gz -C /opt/graalvm --strip-components=1 \
    && rm /tmp/graalvm-linux.tar.gz \
    && sh /tmp/build-pcre2.sh /opt/pcre2-php
ENV GRAALVM_HOME=/opt/graalvm
ENV JAVA_OPTS_BASE=-Dphp.pcre2=/opt/pcre2-php/libpcre2-8.so
