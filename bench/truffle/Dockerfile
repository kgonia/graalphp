# Truffle PHP on Linux, in the reference PHP image (same Debian, same CPU and the distro's libpcre2), so regex
# kernels compare like for like. Build: docker build -t gate1-truffle bench/truffle  (needs graalvm-linux.tar.gz
# here: https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-x64_bin.tar.gz)
ARG PHP=8.5
FROM gate1-php:${PHP}
COPY graalvm-linux.tar.gz /tmp/
RUN mkdir /opt/graalvm && tar -xzf /tmp/graalvm-linux.tar.gz -C /opt/graalvm --strip-components=1 \
    && rm /tmp/graalvm-linux.tar.gz
ENV GRAALVM_HOME=/opt/graalvm
