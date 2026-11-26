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
function Capture([string] $exe, [string] $arguments, [string] $file, [string] $diagnostics) {
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
        if ($p.ExitCode -ne 0) { throw "$exe exited with $($p.ExitCode)" }
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

foreach ($name in 'contract_d17','w1d35_aget','w1d35_hooks','w1d35_post','w1d35_eval_mega','w1d35_sideblob','w1d35_outline') {
    $actual = Join-Path $work "$name.b1.stdout"
    $oracle = Join-Path $work "$name.php.stdout"
    $script = Join-Path $fixtures "$name.php"
    Capture 'docker' "run --rm -v ""${fixtures}:/fixtures:ro"" $PhpImage php /fixtures/$name.php" $oracle
    $expected = [Convert]::ToBase64String([IO.File]::ReadAllBytes($oracle))
    $budgets = if ($name -eq 'w1d35_outline') { 7500,220 } else { 7500 }
    foreach ($budget in $budgets) {
        $log = "$name-$budget.log"
        $report = Join-Path $work "$name-$budget.lint"
        Capture $java "-XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler -Xlog:class+load=info:file=$log -cp ""$classes"" pl.codeloom.php.b1.B1Main ""$script"" /fixtures/$name.php $budget" $actual $report
        $log = Join-Path $work $log
        if (Select-String -Path $log -Pattern 'com[./]oracle[./]truffle[./]' -Quiet) { throw 'Truffle class loaded' }
        $hidden = @(Select-String -Path $log -Pattern ' pl\.codeloom\.php\.b1\.Compiled\d+/0x[0-9a-f]+ source: __JVM_LookupDefineClass__')
        $stats = [IO.File]::ReadAllText($report).Trim()
        if ($stats -notmatch '^B1Lint: (\d+) hidden classes \((\d+) eval\), 0 violations; live destructibles: 0; outline helpers: (\d+); max Code: (\d+)$') {
            throw "Invalid execution report for $name. $stats"
        }
        if ($hidden.Count -eq 0 -or $hidden.Count -ne [int]$Matches[1]) { throw "Hidden class count differs from lint count for $name" }
        if ($name -eq 'w1d35_eval_mega' -and [int]$Matches[2] -eq 0) { throw 'Missing runtime eval compilations' }
        if ($budget -eq 220 -and [int]$Matches[3] -lt 2) { throw 'Expected forced outline helpers' }
        if ([int]$Matches[4] -gt $budget) { throw 'Method exceeds budget' }
        Write-Host $stats
        foreach ($file in $actual, (Join-Path $fixtures "$name.stdout")) {
            if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($file)) -cne $expected) { throw "$file differs from PHP" }
        }
        Write-Host "$name ($budget B): $($hidden.Count) hidden classes, 0 Truffle loads, $((Get-Item $oracle).Length) bytes match PHP."
    }
}
Write-Host "Evidence in $work."
