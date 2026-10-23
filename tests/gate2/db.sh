#!/usr/bin/env bash
# Starts the Gate 2 MariaDB (container gate2-mariadb, host port 3307, database wordpress, user wp / wp).
set -euo pipefail
if docker ps --format '{{.Names}}' | grep -qx gate2-mariadb; then echo "gate2-mariadb running"; exit 0; fi
docker rm -f gate2-mariadb > /dev/null 2>&1 || true
docker run -d --name gate2-mariadb -p 3307:3306 -e MARIADB_ROOT_PASSWORD=root -e MARIADB_DATABASE=wordpress \
  -e MARIADB_USER=wp -e MARIADB_PASSWORD=wp mariadb:11.4 > /dev/null
for i in $(seq 60); do
  if docker exec gate2-mariadb mariadb -uwp -pwp -e "select 1" wordpress > /dev/null 2>&1; then echo "gate2-mariadb ready"; exit 0; fi
  sleep 1
done
echo "gate2-mariadb did not start"; exit 1
