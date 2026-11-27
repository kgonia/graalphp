[CmdletBinding()]
param(
    [int]$Rounds = 5,
    [string]$Cpuset = "14-21",
    [string]$OutName = "out-baseline",
    [ValidateSet("core", "hot", "k0", "all")]
    [string]$Suite = "core",
    [int]$HooksRounds = 400,
    [int]$HotRounds = 3000,
    [int]$K0Rounds = 20
)
# B1 Week 2 harness (modelled on bench/b1-precheck/probe-b/run-bench.ps1): real PHP compiled by B1 on stock
# OpenJDK C2 (25 and 28-ea) against PHP 8.5.13 tracing JIT, interleaved, Linux Docker pinned to $Cpuset.
#   -Suite core: hooks, aget, post, mega (+ hooks/mega CtxB and paired 2-context runs)
#   -Suite hot:  none, one, hasf            -Suite k0: the 10 bench/k0 kernels       -Suite all
# Then summarize.py prints the tables. Use a distinct -OutName per variant (out-baseline, out-opt, ...).
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path "$PSScriptRoot/../..").Path.Replace('\', '/')
$outRel = "bench/b1-week2/$OutName"
$outDir = "$root/$outRel"
$cp = "bench/b1-week2/build/classes"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# Pinned digests (pulled locally); never :latest.
$phpImage = "php@sha256:dbf32b33595283b79fd1a37ebc1614bdf1b83d3750daad9a57d67dbb9b96ec16"
$jvmImages = [ordered]@{
    "c2-25" = "eclipse-temurin@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3"
    "c2-28" = "openjdk@sha256:96bd7dac4b8eece1050077de4e9622169552439a48cd9f335b1a8f02ca43435d"
}
$phpJit = "-d opcache.enable_cli=1 -d opcache.jit=tracing -d opcache.jit_buffer_size=128M -d memory_limit=1G -d opcache.file_update_protection=0"
# JDK 28-ea has no JVMCI (UseJVMCICompiler is an unrecognized option there); its C2 is the only top tier anyway.
$common = "-Xmx64m -XX:+UseParallelGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=48m"
$jvmFlags = @{
    "c2-25" = "-XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler $common"
    "c2-28" = "-XX:+UnlockExperimentalVMOptions $common"
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Invoke-Docker([string]$image, [string]$command, [string]$outFile) {
    $output = & docker run --rm --cpuset-cpus $Cpuset -v "${root}:/w" -w /w $image sh -c $command
    $code = $LASTEXITCODE
    if ($outFile) { [IO.File]::WriteAllText($outFile, (($output | ForEach-Object { "$_" }) -join "`n") + "`n", $utf8NoBom) }
    if ($code -ne 0) { throw "FAILED (exit $code): $image $command" }
    return $output
}

Write-Host "=== B1 Week 2: environment and digests ==="
foreach ($image in @($phpImage) + @($jvmImages.Values)) {
    & docker image inspect --format '{{index .RepoDigests 0}}' $image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Image not present locally: $image" }
}
$phpEnv = Invoke-Docker $phpImage "php $phpJit bench/b1-precheck/probe-b/php-ref/probe_env.php" $null
if ("$phpEnv" -notmatch 'jit_on=1' -or "$phpEnv" -notmatch 'kind=5') { throw "PHP tracing JIT not active: $phpEnv" }
[IO.File]::WriteAllText("$outDir/env-php.txt", "$phpEnv`ndigest=$phpImage`n", $utf8NoBom)
Write-Host $phpEnv
foreach ($engine in $jvmImages.Keys) {
    $version = Invoke-Docker $jvmImages[$engine] "java -version 2>&1" $null
    [IO.File]::WriteAllText("$outDir/env-$engine.txt", (($version | ForEach-Object { "$_" }) -join "`n") + "`ndigest=$($jvmImages[$engine])`n", $utf8NoBom)
    Write-Host "$engine : $($version | Select-Object -First 1)"
}

Write-Host "=== Truffle-free build (JDK 25), runtime B1Lint, check on both JDKs ==="
$build = 'set -e; rm -rf bench/b1-week2/build && mkdir -p bench/b1-week2/build/classes; S=src/main/java/pl/codeloom/php; ' +
    '{ find $S/parser/zend $S/ir $S/b1 -name "*.java"; for f in parser/Ast.java parser/AstBody.java parser/JumpChecks.java parser/Names.java runtime/Phases.java; do echo $S/$f; done; } > bench/b1-week2/build/sources.txt; ' +
    'javac --release 25 -proc:none -encoding UTF-8 -nowarn -d bench/b1-week2/build/classes @bench/b1-week2/build/sources.txt; ' +
    'java -cp bench/b1-week2/build/classes pl.codeloom.php.b1.B1Lint bench/b1-week2/build/classes/pl/codeloom/php/b1/runtime'
Invoke-Docker $jvmImages["c2-25"] $build "$outDir/build.txt" | Write-Host
foreach ($engine in $jvmImages.Keys) {
    $log = "$outRel/classload-$engine.log"
    $check = Invoke-Docker $jvmImages[$engine] "java $($jvmFlags[$engine]) -Xlog:class+load=info:file=$log -cp $cp pl.codeloom.php.b1.B1BenchMain check" "$outDir/check-$engine.txt"
    if (($check | Select-Object -Last 1) -ne 'CHECK OK') { throw "B1BenchMain check failed on $engine" }
    if (Select-String -Path "$outDir/classload-$engine.log" -Pattern 'com[./]oracle[./]truffle' -Quiet) { throw "Truffle class loaded on $engine" }
    $loaded = (Select-String -Path "$outDir/classload-$engine.log" -Pattern ' source: ').Count
    Write-Host "$engine check: CHECK OK, $loaded classes loaded, 0 Truffle"
}

$kernels = switch ($Suite) {
    "core" { @("hooks", "aget", "post", "mega") }
    "hot" { @("none", "one", "hasf") }
    "k0" { @("fannkuch", "spectralnorm", "binarytrees", "nbody", "fib", "intloop", "floatloop", "mandelbrot", "sieve", "bubblesort") | ForEach-Object { "k0-$_" } }
    "all" { @("hooks", "aget", "post", "mega", "none", "one", "hasf") + (@("fannkuch", "spectralnorm", "binarytrees", "nbody", "fib", "intloop", "floatloop", "mandelbrot", "sieve", "bubblesort") | ForEach-Object { "k0-$_" }) }
}

function Get-JavaArgs([string]$kernel) {
    if ($kernel.StartsWith("hooks")) { return "$kernel $HooksRounds" }
    if ($kernel.StartsWith("k0-")) { return "k0 $($kernel.Substring(3)) $K0Rounds" }
    return "$kernel $HotRounds"
}

function Invoke-BenchRun([string]$engine, [string]$kernel, [int]$r) {
    $outFile = "$outDir/${kernel}_${engine}_r${r}.txt"
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] round $r | $kernel | $engine"
    if ($engine -eq "php") {
        $run = if ($kernel -eq "hooks") { "bench/gate1/run.php hooks $HooksRounds" }
            elseif ($kernel.StartsWith("k0-")) { "bench/k0/run.php $($kernel.Substring(3)) $K0Rounds" }
            else { "bench/b1-precheck/probe-b/php-ref/hot.php target/gate2/wordpress $HotRounds $kernel" }
        $cmd = "php $phpJit bench/b1-precheck/probe-b/php-ref/probe_env.php >/dev/null && php $phpJit $run && echo peak_memory `$(cat /sys/fs/cgroup/memory.peak)"
        Invoke-Docker $phpImage $cmd $outFile | Out-Null
    } else {
        $cmd = "java $($jvmFlags[$engine]) -cp $cp pl.codeloom.php.b1.B1BenchMain $(Get-JavaArgs $kernel) && echo peak_memory `$(cat /sys/fs/cgroup/memory.peak)"
        Invoke-Docker $jvmImages[$engine] $cmd $outFile | Out-Null
    }
}

# Interleave engines (alternating order) and every kernel, including the CtxB and paired runs, within each round.
for ($r = 1; $r -le $Rounds; $r++) {
    $engines = if ($r % 2 -eq 1) { @("php", "c2-25", "c2-28") } else { @("c2-28", "c2-25", "php") }
    $jvms = @($engines | Where-Object { $_ -ne "php" })
    foreach ($k in $kernels) {
        foreach ($e in $engines) { Invoke-BenchRun $e $k $r }
        if ($k -eq "hooks" -or $k -eq "mega") {
            foreach ($e in $jvms) {
                Invoke-BenchRun $e "$k-ctxb" $r
                Invoke-BenchRun $e "$k-2ctx" $r
            }
        }
    }
}

# Steady-state compilation, deoptimization, NMT and nmethod sizes of the paired runs (separate, untimed).
if ($kernels -contains "hooks" -or $kernels -contains "mega") {
    Write-Host "=== Steady-state LogCompilation and NMT ==="
    foreach ($engine in $jvmImages.Keys) {
        foreach ($k in @("hooks-2ctx", "mega-2ctx") | Where-Object { $kernels -contains $_.Split('-')[0] }) {
            Write-Host "[$(Get-Date -Format 'HH:mm:ss')] deopt + NMT | $k | $engine"
            $cmd = "java $($jvmFlags[$engine]) -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$outRel/deopt_${k}_${engine}.xml " +
                "-XX:NativeMemoryTracking=summary -XX:+PrintNMTStatistics -Db1.methods=true -cp $cp pl.codeloom.php.b1.B1BenchMain $(Get-JavaArgs $k)"
            Invoke-Docker $jvmImages[$engine] $cmd "$outDir/deopt_${k}_${engine}.txt" | Out-Null
        }
    }
}

Write-Host "=== Summary ==="
python "$PSScriptRoot/summarize.py" "$outDir" --rounds $Rounds
if ($LASTEXITCODE -ne 0) { throw "summarize.py failed" }
