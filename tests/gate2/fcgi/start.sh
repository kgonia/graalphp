#!/bin/bash
# The FastCGI server given as the arguments, then nginx in front once the server listens on 127.0.0.1:9000, as the
# php-fpm image starts nginx after php-fpm: a client polling a static file sees the server ready, not nginx alone.
"$@" &
server=$!
until (: < /dev/tcp/127.0.0.1/9000) 2> /dev/null; do
  kill -0 $server 2> /dev/null || { echo "fcgi-start: the server exited"; wait $server; exit 1; }
  sleep 0.01
done
exec nginx -g 'daemon off;'
