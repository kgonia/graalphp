# Truffle PHP's FastCGI server (launcher.FastCgiMain, JVM or native image) behind nginx, for tests/gate2/native.sh's
# *-fcgi variants: the same nginx and nginx.conf as the php-fpm image (tests/gate2/woo-fpm), so the two backends get
# the same requests through the same front. Build from tests/gate2:
#   docker build -t gate2-fcgi:graal25.4 -f tests/gate2/fcgi/Dockerfile tests/gate2
FROM gate1-truffle:8.5-graal25.4
RUN apt-get update && apt-get install -y --no-install-recommends nginx && rm -rf /var/lib/apt/lists/*
COPY woo-fpm/nginx.conf /etc/nginx/nginx.conf
COPY fcgi/start.sh /usr/local/bin/fcgi-start
RUN chmod +x /usr/local/bin/fcgi-start
