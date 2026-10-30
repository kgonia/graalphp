# The parser's generator toolchain, pinned so tools/parser/generate.sh gives the same Java byte for byte:
# - GNU Bison 3.8.2, the version php-src 8.5.12's own zend_language_parser.c was made with, for its Java skeleton;
# - re2c 4.6, whose Java backend takes php-src's scanner (php-src itself uses re2c's C backend);
# - Python 3 for the scripts that derive the Java grammar and scanner from php-src's;
# - the sources they read: php-src 8.5.12 (the reference image gate1-php:8.5's PHP) and php-ast 1.1.3 (its ast
#   extension, whose ast.c gives the tree shape the runtime reads).
# Every download is checked against its SHA-256.
FROM debian:trixie-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential m4 python3 curl ca-certificates xz-utils \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/src
RUN curl -fsSL -o bison.tar.xz https://ftp.gnu.org/gnu/bison/bison-3.8.2.tar.xz \
 && echo "9bba0214ccf7f1079c5d59210045227bcf619519840ebfa80cd3849cff5a5bf2  bison.tar.xz" | sha256sum -c - \
 && tar -xJf bison.tar.xz && cd bison-3.8.2 && ./configure --prefix=/usr/local --disable-nls -q \
 && make -j"$(nproc)" -s && make install -s && cd .. && rm -rf bison-3.8.2 bison.tar.xz
RUN curl -fsSL -o re2c.tar.xz https://github.com/skvadrik/re2c/releases/download/4.6/re2c-4.6.tar.xz \
 && echo "75bf2696445e831d0d44e0d9f2909eeffc18c09757f222b2fb025f7e59fe130b  re2c.tar.xz" | sha256sum -c - \
 && tar -xJf re2c.tar.xz && cd re2c-4.6 && ./configure --prefix=/usr/local -q \
 && make -j"$(nproc)" -s && make install -s && cd .. && rm -rf re2c-4.6 re2c.tar.xz
RUN curl -fsSL -o php.tar.xz https://www.php.net/distributions/php-8.5.12.tar.xz \
 && echo "08bc2eba2a588654ade1357174aa646b761556c862dda0678345ee29ed481209  php.tar.xz" | sha256sum -c - \
 && mkdir php-src && tar -xJf php.tar.xz -C php-src --strip-components=1 \
      php-8.5.12/Zend php-8.5.12/LICENSE php-8.5.12/ext/tokenizer \
 && rm php.tar.xz
RUN curl -fsSL -o ast.tgz https://pecl.php.net/get/ast-1.1.3.tgz \
 && echo "528b54aabcfce6bf0e94b5940a73cca357d4a52fad9fef4cd4dc025abc1a9fbd  ast.tgz" | sha256sum -c - \
 && mkdir php-ast && tar -xzf ast.tgz -C php-ast --strip-components=1 && rm ast.tgz
WORKDIR /w
