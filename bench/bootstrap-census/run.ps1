$ErrorActionPreference = 'Stop'
$root = "C:/Users/krzgo/Projects/wordpress"
$elem = "C:/Users/krzgo/Projects/wordpress-elementor"
$census = "$root/bench/bootstrap-census"
$outDir = "$census/out"
$classesDir = "$census/classes"
$name = "bootstrap-census-truffle"
$port = 8097

New-Item -ItemType Directory -Force -Path $outDir, $classesDir | Out-Null
Get-ChildItem -Path $outDir -ErrorAction SilentlyContinue | Remove-Item -Force -Recurse
Get-ChildItem -Path $classesDir -ErrorAction SilentlyContinue | Remove-Item -Force -Recurse

$dbHost = (docker inspect gate2-mariadb --format '{{.NetworkSettings.Networks.bridge.IPAddress}}').Trim() + ":3306"
Write-Host "MariaDB: $dbHost"

# 1. Compile the 8 overlay Java files using the container's GraalVM javac (guaranteed ABI match)
Write-Host "Compiling overlay classes inside gate1-truffle:8.5-graal25.4..."
docker run --rm `
  -v "${root}:/w:ro" `
  -v "${elem}:/elem:ro" `
  -v "${census}:/census" `
  gate1-truffle:8.5-graal25.4 sh -c 'set -eu; find /census/src -name "*.java" > /tmp/sources.txt; $GRAALVM_HOME/bin/javac -d /census/classes -cp "/elem/target/classes:/w/target/lib/*" @/tmp/sources.txt; echo "Compiled $(wc -l < /tmp/sources.txt) Java files cleanly."'
if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }

# 2. Launch HttpMain (--threads=1, interpreter mode for fast deterministic census)
docker rm -f $name 2>$null | Out-Null
Write-Host "Starting census server on port $port..."
docker run -d --name $name --cpuset-cpus "14-21" -p "${port}:8080" `
  -e BOOTSHARE_DB_HOST="$dbHost" `
  -v "${root}:/w:ro" `
  -v "${elem}:/elem:ro" `
  -v "${census}:/census" `
  gate1-truffle:8.5-graal25.4 sh -c 'set -eu; mkdir -p /srv/target/bootshare; cp -r /w/target/bootshare/wordpress /srv/target/bootshare/; mkdir -p /srv/target/bootshare/wordpress/wp-content/mu-plugins; cp /census/zzz-census.php /srv/target/bootshare/wordpress/wp-content/mu-plugins/zzz-census.php; cd /srv; exec $GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow $JAVA_OPTS_BASE -Xmx2g -Dpolyglot.php.Parser=java -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false -Dphp.bootstrap.census=/census/out -cp "/census/classes:/elem/target/classes:/w/target/lib/*" pl.codeloom.php.launcher.HttpMain --port=8080 --threads=1 --docroot=target/bootshare/wordpress --ast-root=/none --server-name=0.0.0.0 --host=0.0.0.0' | Out-Null

try {
  # Wait for server ready
  $ready = $false
  for ($i = 0; $i -lt 120; $i++) {
    $running = (docker inspect -f '{{.State.Running}}' $name 2>$null).Trim()
    if ($running -ne "true") {
      Write-Host "Container exited early!"
      cmd /c "docker logs $name 2>&1"
      throw "Container exited early"
    }
    $code = & curl.exe -s -o NUL -w "%{http_code}" "http://127.0.0.1:$port/wp-includes/css/dashicons.css"
    if ($code -eq "200") { $ready = $true; break }
    Start-Sleep -Seconds 1
  }
  if (-not $ready) { throw "Server failed to start" }
  Write-Host "Server ready."

  $cookieJar = "$outDir/cookies.txt"

  function Invoke-CensusReq([string]$label, [string]$path, [string]$barrier, [bool]$auth) {
    $curlArgs = @("-s", "-o", "$outDir/resp-$label.html", "-m", "300", "-w", "%{http_code} %{time_total}s",
      "-H", "Host: localhost", "-H", "X-Bootshare-Db: plugins",
      "-H", "X-Census-Page: $label", "-H", "X-Census-Barrier: $barrier")
    if ($auth) { $curlArgs += @("-b", $cookieJar) }
    $curlArgs += "http://127.0.0.1:$port$path"
    $res = & curl.exe @curlArgs
    Write-Host ("  [{0,-20}] {1,-32} barrier={2,-14} -> {3}" -f $label, $path, $barrier, $res)
  }

  # 1 & 2: Anonymous home page twice, 2s apart (tests cross-request identicality)
  Invoke-CensusReq "home-anon-1" "/" "wp_loaded" $false
  Start-Sleep -Seconds 2
  Invoke-CensusReq "home-anon-2" "/" "wp_loaded" $false

  # Login to get authenticated cookie jar (no X-Census-Page header -> ignored by census)
  $loginRes = & curl.exe -s -o NUL -m 300 -H "Host: localhost" -H "X-Bootshare-Db: plugins" `
    -c $cookieJar -b "wordpress_test_cookie=WP%20Cookie%20check" `
    -d "log=admin" -d "pwd=admin" -d "testcookie=1" `
    -w "%{http_code} %{time_total}s" "http://127.0.0.1:$port/wp-login.php"
  Write-Host "  [login               ] /wp-login.php                    -> $loginRes"

  # 3-5: Authenticated pages
  Invoke-CensusReq "home-auth" "/" "wp_loaded" $true
  Invoke-CensusReq "post-auth" "/?p=1" "wp_loaded" $true
  Invoke-CensusReq "dashboard-auth" "/wp-admin/" "wp_loaded" $true

  # 6-7: Anonymous REST with wp_loaded and rest_api_init barriers
  Invoke-CensusReq "rest-anon-wploaded" "/?rest_route=/wp/v2/posts" "wp_loaded" $false
  Invoke-CensusReq "rest-anon-restinit" "/?rest_route=/wp/v2/posts" "rest_api_init" $false

  Start-Sleep -Seconds 1
}
finally {
  cmd /c "docker logs $name > `"$outDir\server.log`" 2>&1"
  cmd /c "docker rm -f $name > NUL 2>&1"
}

Write-Host "`n=== SUMMARY ==="
if (Test-Path "$outDir/summary.txt") { Get-Content "$outDir/summary.txt" }
Write-Host "`n=== RUNNING ANALYZER ==="
python "$census/analyze.py" "$outDir" | Tee-Object -FilePath "$outDir/report.txt"
