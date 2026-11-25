[CmdletBinding()]
param(
    [int]$Rounds = 5,
    [string]$Cpuset = "14-21"
)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path "$PSScriptRoot/../../..").Path.Replace('\', '/')
$outDir = "$PSScriptRoot/out-v3"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# Pinned image digest for PHP 8.5.13 + exact local image ID for probe-graal:25.4
$phpImage = "php@sha256:dbf32b33595283b79fd1a37ebc1614bdf1b83d3750daad9a57d67dbb9b96ec16"
$jvmImage = "probe-graal:25.4"
$phpJit = "-d opcache.enable_cli=1 -d opcache.jit=tracing -d opcache.jit_buffer_size=128M -d memory_limit=1G -d opcache.file_update_protection=0"
$c2Flags = "-XX:-UseJVMCICompiler -Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m"
$graalFlags = "-XX:+UseJVMCICompiler -Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m -Djdk.graal.internal.MaxHeapSize=64m"
$cp = "bench/b1-precheck/probe-b/build/classes"

Write-Host "=== Probe B (Rev 3) Environment & Digest Verification ==="
$phpDigestActual = (docker image inspect --format '{{index .RepoDigests 0}}' $phpImage)
$jvmIdActual = (docker image inspect --format '{{.Id}}' $jvmImage)
if ($LASTEXITCODE -ne 0 -or $jvmIdActual -notmatch '^sha256:[0-9a-f]{64}$') { throw "Cannot resolve JVM image ID for $jvmImage" }
$phpProbeOut = docker run --rm --cpuset-cpus $Cpuset -v "${root}:/w" -w /w $phpImage sh -c "php $phpJit bench/b1-precheck/probe-b/php-ref/probe_env.php"
if ($LASTEXITCODE -ne 0) { throw "PHP JIT verification failed!" }
$jvmVerOut = docker run --rm --cpuset-cpus $Cpuset -v "${root}:/w" -w /w $jvmIdActual sh -c "java -version 2>&1"
if ($LASTEXITCODE -ne 0) { throw "JVM verification failed!" }

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText("$outDir/env-php.txt", "$phpProbeOut`ndigest=$phpDigestActual`n", $utf8NoBom)
[IO.File]::WriteAllText("$outDir/env-jvm.txt", ($jvmVerOut -join "`n") + "`nimage_id=$jvmIdActual`n", $utf8NoBom)
Write-Host $phpProbeOut
Write-Host "PHP digest: $phpDigestActual"
Write-Host "JVM image:  $jvmIdActual"

function Invoke-BenchRun([string]$engine, [string]$kernel, [int]$r) {
    $outFile = "$outDir/${kernel}_${engine}_r${r}.txt"
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Round $r | $kernel | $engine"
    if ($engine -eq "php") {
        if ($kernel -eq "hooks") {
            $cmd = "php $phpJit bench/b1-precheck/probe-b/php-ref/probe_env.php >/dev/null && php $phpJit bench/gate1/run.php hooks 400 && echo peak_memory `$(cat /sys/fs/cgroup/memory.peak)"
        } else {
            $cmd = "php $phpJit bench/b1-precheck/probe-b/php-ref/probe_env.php >/dev/null && php $phpJit bench/b1-precheck/probe-b/php-ref/hot.php target/gate2/wordpress 3000 $kernel && echo peak_memory `$(cat /sys/fs/cgroup/memory.peak)"
        }
        docker run --rm --cpuset-cpus $Cpuset -v "${root}:/w" -w /w $phpImage sh -c $cmd | Set-Content -Encoding utf8 $outFile
    } else {
        $flags = if ($engine -eq "c2") { $c2Flags } else { $graalFlags }
        $javaArgs = switch ($kernel) {
            "hooks"      { "hooks 400 bench/gate1/corpus.php" }
            "hooks-ctxb" { "hooks-ctxb 400 bench/gate1/corpus.php" }
            "hooks-2ctx" { "hooks-2ctx 400 bench/gate1/corpus.php" }
            "aget"       { "aget 3000" }
            "post"       { "post 3000" }
            "mega"       { "mega 3000" }
            "mega-ctxb"  { "mega-ctxb 3000" }
            "mega-2ctx"  { "mega-2ctx 3000" }
        }
        $cmd = "java $flags -cp $cp pl.codeloom.php.runtime.ProbeBMain $javaArgs && echo peak_memory `$(cat /sys/fs/cgroup/memory.peak)"
        docker run --rm --cpuset-cpus $Cpuset -v "${root}:/w" -w /w $jvmIdActual sh -c $cmd | Set-Content -Encoding utf8 $outFile
    }
    if ($LASTEXITCODE -ne 0) {
        throw "FAILED: $engine $kernel r$r (exit $LASTEXITCODE)"
    }
}

# Interleave ALL kernels (including paired solo + ctxb + 2ctx) within each round!
for ($r = 1; $r -le $Rounds; $r++) {
    $engines = if ($r % 2 -eq 1) { @("php", "c2", "graal") } else { @("graal", "c2", "php") }
    $jvmEngines = if ($r % 2 -eq 1) { @("c2", "graal") } else { @("graal", "c2") }

    foreach ($k in @("hooks", "aget", "post", "mega")) {
        foreach ($e in $engines) {
            Invoke-BenchRun $e $k $r
        }
        if ($k -eq "hooks") {
            foreach ($e in $jvmEngines) {
                Invoke-BenchRun $e "hooks-ctxb" $r
                Invoke-BenchRun $e "hooks-2ctx" $r
            }
        } elseif ($k -eq "mega") {
            foreach ($e in $jvmEngines) {
                Invoke-BenchRun $e "mega-ctxb" $r
                Invoke-BenchRun $e "mega-2ctx" $r
            }
        }
    }
}

# Dedicated steady-state LogCompilation (clean stdout timestamps) + NMT summary snapshots
Write-Host "=== Running Steady-State Deoptimization & NMT Checks ==="
foreach ($engine in @("c2", "graal")) {
    $flags = if ($engine -eq "c2") { $c2Flags } else { $graalFlags }
    foreach ($k in @("hooks-2ctx", "mega-2ctx")) {
        $javaArgs = if ($k -eq "hooks-2ctx") { "hooks-2ctx 400 bench/gate1/corpus.php" } else { "mega-2ctx 3000" }
        $logRel = "bench/b1-precheck/probe-b/out-v3/deopt_${k}_${engine}.xml"
        $outTxt = "$outDir/deopt_${k}_${engine}.txt"
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Deopt + NMT | $k | $engine"
        $cmd = "java $flags -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -XX:+LogCompilation -XX:LogFile=$logRel -cp $cp pl.codeloom.php.runtime.ProbeBMain $javaArgs"
        docker run --rm --cpuset-cpus $Cpuset -v "${root}:/w" -w /w $jvmIdActual sh -c $cmd | Set-Content -Encoding utf8 $outTxt
        if ($LASTEXITCODE -ne 0) {
            throw "FAILED deopt/NMT check: $engine $k (exit $LASTEXITCODE)"
        }
    }
}

Write-Host "=== Summarizing Results ==="
python "$PSScriptRoot/summarize.py" "$outDir"
