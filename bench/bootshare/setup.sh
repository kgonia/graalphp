#!/usr/bin/env bash
# Sets up the bootstrap-share site once: a copy of Gate 2's WordPress core in target/bootshare/wordpress and two
# databases in the Gate 2 MariaDB, each with 50 generated posts: wp_bootshare_core (no plugins) and
# wp_bootshare_plugins (WooCommerce, Yoast SEO, Elementor, Contact Form 7 and Akismet active). Run after
# tests/gate2/fetch.sh and tests/gate2/db.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
site=target/bootshare/wordpress
if [ ! -d $site ]; then
  mkdir -p target/bootshare && cp -r target/gate2/wordpress $site
  rm -f $site/sockets-probe.php && rm -rf $site/wp-content/mu-plugins
fi
mkdir -p $site/wp-content/mu-plugins target/bootshare/plugins
cp bench/bootshare/wp-config.php $site/wp-config.php
cp bench/bootshare/mu-plugin.php $site/wp-content/mu-plugins/bootshare.php
wp() {
  MSYS_NO_PATHCONV=1 docker run --rm --add-host=host.docker.internal:host-gateway -e BOOTSHARE_DB="$db" \
    -v "$root/$site:/var/www/html" -v "$root/target/bootshare/plugins:/plugins" wordpress:cli-php8.4 wp --path=/var/www/html "$@"
}
for db in core plugins; do
  if wp core is-installed 2> /dev/null; then echo "$db: installed"; continue; fi
  docker exec gate2-mariadb mariadb -uroot -proot -e "create database if not exists wp_bootshare_$db;
    grant all on wp_bootshare_$db.* to wp@'%'"
  wp core install --url=http://localhost --title="Bootstrap share" --admin_user=admin --admin_password=admin \
    --admin_email=admin@example.com --skip-email
  wp post generate --count=50 --post_content="$(printf '<p>Lorem ipsum dolor sit amet.</p>%.0s' $(seq 20))"
  if [ $db = plugins ]; then
    # wp-config.php blocks WordPress's own downloads: the zips come from curl
    zips=()
    for plugin in woocommerce wordpress-seo elementor contact-form-7 akismet; do
      [ -f target/bootshare/plugins/$plugin.zip ] || curl -fsSL -o target/bootshare/plugins/$plugin.zip         "https://downloads.wordpress.org/plugin/$plugin.latest-stable.zip"
      zips+=(/plugins/$plugin.zip)
    done
    wp plugin install "${zips[@]}" --activate
  fi
  echo "$db: installed, plugins: $(wp plugin list --status=active --field=name | tr '\n' ' ')"
done
