# Reference PHP for Gate 1: official CLI image + php-ast (exports zend_ast) + OPcache with the tracing JIT.
ARG PHP=8.5
FROM php:${PHP}-cli
RUN pecl install ast-1.1.3 && docker-php-ext-enable ast \
 && (php -m | grep -qi 'zend opcache' || docker-php-ext-install opcache)
COPY jit.ini /usr/local/etc/php/conf.d/zz-jit.ini
