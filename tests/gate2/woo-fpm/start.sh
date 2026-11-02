#!/bin/sh
# php-fpm with FPM_CHILDREN static workers, and nginx in front
set -e
cat > /usr/local/etc/php-fpm.d/www.conf <<CONF
[www]
user = www-data
group = www-data
listen = 127.0.0.1:9000
pm = static
pm.max_children = ${FPM_CHILDREN:-8}
catch_workers_output = yes
decorate_workers_output = no
clear_env = yes
; the image's docker.conf logs every request; the Truffle servers log none
access.log = /dev/null
CONF
php-fpm -D
exec nginx -g 'daemon off;'
