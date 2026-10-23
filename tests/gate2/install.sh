#!/usr/bin/env bash
# Installs WordPress into the Gate 2 database once, on regular PHP (the wordpress:cli image, which has wp-cli
# and mysqli): an empty database would make wp-load.php redirect to the installer and exit. Run after db.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
cp tests/gate2/wp-config.php target/gate2/wordpress/wp-config.php
root=$(pwd -W 2>/dev/null || pwd)
wp() {
  MSYS_NO_PATHCONV=1 docker run --rm --add-host=host.docker.internal:host-gateway -v "$root/target/gate2/wordpress:/var/www/html" \
    wordpress:cli-php8.4 wp --path=/var/www/html "$@"
}
if wp core is-installed 2> /dev/null; then echo "WordPress already installed"; exit 0; fi
wp core install --url=http://localhost --title="Gate 2" --admin_user=admin --admin_password=admin \
  --admin_email=admin@example.com --skip-email
echo "installed: $(wp db query 'select count(*) from wp_posts' --skip-column-names 2>/dev/null || echo '?') posts"
