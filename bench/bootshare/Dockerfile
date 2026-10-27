# PHP 8.5 serving WordPress for the bootstrap-share measurement: the Gate 1 reference (OPcache, tracing JIT) with
# mysqli, configured as a production server would be.
FROM gate1-php:8.5
RUN docker-php-ext-install mysqli
COPY server.ini /usr/local/etc/php/conf.d/zz-server.ini
