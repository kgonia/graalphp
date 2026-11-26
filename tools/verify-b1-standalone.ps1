[CmdletBinding()]
param(
    [string] $Jdk = 'C:\Users\krzgo\AppData\Local\javm\jdk\temurin@25.0.4',
    [string] $PhpImage = 'php@sha256:dbf32b33595283b79fd1a37ebc1614bdf1b83d3750daad9a57d67dbb9b96ec16'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$src = Join-Path $root 'src/main/java/pl/codeloom/php'
$fixtures = Join-Path $root 'src/test/resources/b1'
$work = Join-Path $root ('target/b1-standalone/' + [guid]::NewGuid().ToString('N'))
$classes = (New-Item -ItemType Directory -Force -Path (Join-Path $work 'classes')).FullName
$javac = Join-Path $Jdk 'bin/javac.exe'
$java = Join-Path $Jdk 'bin/java.exe'

# Use OS byte streams. Windows PowerShell's native-output redirection decodes, then re-encodes text.
function Capture([string] $exe, [string] $arguments, [string] $file, [string] $diagnostics, [int] $expectedExit = 0) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $exe
    $start.Arguments = $arguments
    $start.WorkingDirectory = $work
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = [bool]$diagnostics
    $p = [System.Diagnostics.Process]::Start($start)
    try {
        if ($diagnostics) { $readErrors = $p.StandardError.ReadToEndAsync() }
        $out = [System.IO.File]::Create($file)
        try { $p.StandardOutput.BaseStream.CopyTo($out) } finally { $out.Dispose() }
        $p.WaitForExit()
        if ($diagnostics) { [IO.File]::WriteAllText($diagnostics,$readErrors.Result) }
        if ($p.ExitCode -ne $expectedExit) { throw "$exe exited with $($p.ExitCode)" }
    } finally { $p.Dispose() }
}

$version = & $javac -version
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 25\.0\.4$') { throw "Unexpected compiler: $version" }
$phpVersion = & docker run --rm $PhpImage php -v
if ($LASTEXITCODE -ne 0 -or $phpVersion[0] -notmatch '^PHP 8\.5\.13 ') { throw "Unexpected PHP image: $phpVersion" }
Write-Host "$version / $($phpVersion[0])"
$sources = @(
    foreach ($dir in 'parser/zend','ir','b1') {
        Get-ChildItem (Join-Path $src $dir) -Recurse -Filter '*.java' | ForEach-Object FullName
    }
    foreach ($name in 'parser/Ast.java','parser/AstBody.java','parser/JumpChecks.java','parser/Names.java','runtime/Phases.java') {
        Join-Path $src $name
    }
)
$argfile = Join-Path $work 'sources.txt'
[IO.File]::WriteAllLines($argfile, [string[]]($sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }),
    (New-Object Text.UTF8Encoding($false)))
& $javac --release 25 -encoding UTF-8 -proc:none --class-path $classes --source-path $classes -d $classes "@$argfile"
if ($LASTEXITCODE -ne 0) { throw 'Truffle-free compilation failed' }
& $java -cp $classes pl.codeloom.php.b1.B1Lint (Join-Path $classes 'pl/codeloom/php/b1/runtime')
if ($LASTEXITCODE -ne 0) { throw 'Runtime lint failed' }

$cases = @('contract_d17','w1d35_aget','w1d35_hooks','w1d35_post','w1d35_eval_mega','w1d35_sideblob','w1d35_outline','w2_bound_closure','w2_gc','d17_fixture','large-function')
$cases += @(Get-ChildItem $fixtures -Filter 'w2_audit_*.php' | Sort-Object Name | ForEach-Object BaseName)
$cases += @(Get-ChildItem (Join-Path $fixtures 'audit_d35') -Filter '*.php' | Sort-Object Name | ForEach-Object { 'audit_d35/' + $_.BaseName })
# These D17 contracts target both backends; the other audit files exercise legacy Truffle translation.
$cases += @('ownership','nested','sends','return_cleanup','catch_binding','construction','trace','operations',
    'shutdown','binary_operands','drain_collections','shutdown_repeat','handler_shutdown','exception_signatures',
    'late_class','handler_top' | ForEach-Object { "audit/$_" })
foreach ($name in $cases) {
    $label = $name.Replace('/', '-')
    $actual = Join-Path $work "$label.b1.stdout"
    $oracle = Join-Path $work "$label.php.stdout"
    $relative = if ($name.StartsWith('audit/')) { $name.Substring(6) } else { $name }
    $dir = switch -Wildcard ($name) {
        'audit/*' { Join-Path $fixtures 'audit' }
        'd17_fixture'    { Join-Path $root 'bench/b1-precheck/probe-b/php-ref' }
        'large-function' { Join-Path $root 'src/test/resources/compile' }
        default          { $fixtures }
    }
    $script = Join-Path $dir "$relative.php"
    $expectedExit = if ($name -eq 'w2_audit_callable_fatal' -or $name -like 'audit_d35/fatal_*') { 255 } else { 0 }
    Capture 'docker' "run --rm -v ""${dir}:/fixtures:ro"" $PhpImage php /fixtures/$relative.php" $oracle $null $expectedExit
    $expected = [Convert]::ToBase64String([IO.File]::ReadAllBytes($oracle))
    if ($name -eq 'd17_fixture' -and (Get-Item $oracle).Length -ne 556) { throw 'Unexpected D17 transcript length' }
    $budgets = if ($name -eq 'w1d35_outline') { 7500,220 } else { 7500 }
    foreach ($budget in $budgets) {
        $log = "$label-$budget.log"
        $report = Join-Path $work "$label-$budget.lint"
        Capture $java "-XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler -Xlog:class+load=info:file=$log -cp ""$classes"" pl.codeloom.php.b1.B1Main ""$script"" /fixtures/$relative.php $budget" $actual $report $expectedExit
        $log = Join-Path $work $log
        if (Select-String -Path $log -Pattern 'com[./]oracle[./]truffle[./]' -Quiet) { throw 'Truffle class loaded' }
        $hidden = @(Select-String -Path $log -Pattern ' pl\.codeloom\.php\.b1\.Compiled\d+/0x[0-9a-f]+ source: __JVM_LookupDefineClass__')
        foreach ($file in $actual, (Join-Path $fixtures "$name.stdout")) {
            if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($file)) -cne $expected) { throw "$file differs from PHP" }
        }
        if ($expectedExit -eq 255) {
            if ($name -eq 'w2_audit_callable_fatal' -and $hidden.Count -ne 0) { throw 'Compile fatal after hidden classes were published' }
            Write-Host "$name verified fatal, exit 255, $($hidden.Count) hidden classes and 0 Truffle loads."
            continue
        }
        $stats = [IO.File]::ReadAllText($report).Trim()
        if ($stats -notmatch '^B1Lint: (\d+) hidden classes \((\d+) eval\), 0 violations; live destructibles: 0; outline helpers: (\d+); max Code: (\d+)$') {
            throw "Invalid execution report for $name. $stats"
        }
        if ($hidden.Count -eq 0 -or $hidden.Count -ne [int]$Matches[1]) { throw "Hidden class count differs from lint count for $name" }
        if ($name -eq 'w1d35_eval_mega' -and [int]$Matches[2] -eq 0) { throw 'Missing runtime eval compilations' }
        if ($budget -eq 220 -and [int]$Matches[3] -lt 2) { throw 'Expected forced outline helpers' }
        if ($name -eq 'large-function' -and [int]$Matches[3] -eq 0) { throw 'Automatic outlining did not trigger' }
        if ([int]$Matches[4] -gt $budget) { throw 'Method exceeds budget' }
        Write-Host $stats
        Write-Host "$name ($budget B): $($hidden.Count) hidden classes, 0 Truffle loads, $((Get-Item $oracle).Length) bytes match PHP."
    }
}
# Check request reinitialization with a statement-bound class and a static initializer.
$repeat = Join-Path $fixtures 'w2_audit_repeat.php'
$repeatOut = Join-Path $work 'repeat.stdout'
Capture $java "-cp ""$classes"" pl.codeloom.php.b1.B1Main ""$repeat"" /fixtures/w2_audit_repeat.php 7500 3" $repeatOut (Join-Path $work 'repeat.methods')
if ([IO.File]::ReadAllText($repeatOut) -cne ([IO.File]::ReadAllText((Join-Path $work 'w2_audit_repeat.php.stdout')) * 3)) { throw 'Repeated request output differs from PHP' }

# Each request calls Query::sql 600 times. Keep compilation events separate from the PHP bytes.
$script = Join-Path $root 'src/test/resources/compile/large-function.php'
$compilation = Join-Path $work 'large-function.c2.log'
$methods = Join-Path $work 'large-function.methods'
$warmOutput = Join-Path $work 'large-function.c2.stdout'
Capture $java "-Xbatch -Xmx256m -XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler -XX:CompileThresholdScaling=0.12 -Xlog:jit+compilation=debug:file=large-function.c2.log:uptimemillis -Xlog:class+load=info:file=c2-loads.log -cp ""$classes"" pl.codeloom.php.b1.B1Main ""$script"" /fixtures/large-function.php 7500 12" $warmOutput $methods
$expectedWarm = [Text.Encoding]::ASCII.GetBytes(("1094741`n" * 12))
if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($warmOutput)) -cne [Convert]::ToBase64String($expectedWarm)) { throw 'C2 output differs from PHP' }
$steadyMarker = Select-String -Path $methods -Pattern '^STEADY (\d+)$'
if (@($steadyMarker).Count -ne 1) { throw 'Missing steady-state timestamp' }
$steady = [long]$steadyMarker.Matches[0].Groups[1].Value
if (Select-String -Path (Join-Path $work 'c2-loads.log') -Pattern 'com[./]oracle[./]truffle[./]' -Quiet) { throw 'Truffle loaded during warmup' }
$hot = @(Select-String -Path $methods -Pattern '^METHOD Query::sql (\S+) (run|fragment\S*) (\d+)$')
$names = @($hot | ForEach-Object { $_.Matches[0].Groups[2].Value } | Sort-Object -Unique)
if ($names.Count -ne 9 -or $hot.Count -ne 9 -or $names -notcontains 'run') { throw 'Missing Query::sql method sizes' }
foreach ($line in (Get-Content $methods)) { Write-Host $line }
foreach ($entry in $hot) {
    $groups = $entry.Matches[0].Groups
    $symbol = [regex]::Escape($groups[1].Value + '::' + $groups[2].Value)
    $size = $groups[3].Value
    $events = Select-String -Path $compilation -Pattern "^\[(\d+)ms\]\s+(\d+)\s+[!bs ]*4\s+$symbol \($size bytes\)\s*(.*)$"
    $live = @{}
    foreach ($event in $events) {
        $fields = $event.Matches[0].Groups
        $id = $fields[2].Value
        $time = [long]$fields[1].Value
        $status = $fields[3].Value.Trim()
        if (!$status) { $live[$id] = $time }
        elseif ($status -match 'not entrant|zombie') {
            if ($time -ge $steady) { throw "$symbol lost Tier 4 during steady state: $event" }
            $live.Remove($id)
        }
    }
    if (@($live.Values | Where-Object { $_ -lt $steady }).Count -eq 0) {
        throw "Missing live regular Tier 4 installation before steady state for $symbol"
    }
}
Write-Host "C2 Tier 4 installed Query::sql run and all $($hot.Count - 1) fragment methods."
Write-Host "Evidence in $work."
