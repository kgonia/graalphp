# The reference engine for the PHPUnit suites: gate1-php:8.5 (PHP 8.5.12, OPcache with the tracing JIT on in CLI)
# plus what a suite needs that Gate 1 doesn't: mysqli (WordPress's database), gd and exif (WordPress's core tests
# refuse to start without gd; its image tests use both), and unzip for composer's dist downloads.
# Built by tests/wp-phpunit/setup.sh as wp-phpunit-php:8.5.
FROM gate1-php:8.5
RUN apt-get update \
 && apt-get install -y --no-install-recommends libpng-dev libjpeg62-turbo-dev libwebp-dev libfreetype-dev \
      libavif-dev unzip \
 && docker-php-ext-configure gd --with-jpeg --with-webp --with-freetype --with-avif \
 && docker-php-ext-install -j4 gd mysqli exif \
 && rm -rf /var/lib/apt/lists/*
