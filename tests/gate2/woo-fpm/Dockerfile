# PHP 8.5.12 as php-fpm behind nginx for tests/gate2/woo.sh's load runs: the Gate 1 reference's OPcache with the
# tracing JIT (bench/php/jit.ini), mysqli, OPcache sized for WordPress and WooCommerce, no timestamp checks
# (bench/bootshare/server.ini), pm=static with FPM_CHILDREN workers. nginx serves static files and passes the rest
# to php-fpm with WordPress's front-controller rule, as php -S's router does for the other variants.
FROM php:8.5.12-fpm-trixie
RUN apt-get update && apt-get install -y --no-install-recommends nginx && rm -rf /var/lib/apt/lists/* \
 && docker-php-ext-install mysqli \
 && (php -m | grep -qi 'zend opcache' || docker-php-ext-install opcache)
COPY php.ini /usr/local/etc/php/conf.d/zz-woo.ini
COPY nginx.conf /etc/nginx/nginx.conf
COPY start.sh /usr/local/bin/woo-fpm-start
RUN chmod +x /usr/local/bin/woo-fpm-start && rm -f /usr/local/etc/php-fpm.d/zz-docker.conf /usr/local/etc/php-fpm.d/www.conf.default
