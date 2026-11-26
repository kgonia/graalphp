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
function Capture([string] $exe, [string] $arguments, [string] $file) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $exe
    $start.Arguments = $arguments
    $start.WorkingDirectory = $work
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $p = [System.Diagnostics.Process]::Start($start)
    try {
        $out = [System.IO.File]::Create($file)
        try { $p.StandardOutput.BaseStream.CopyTo($out) } finally { $out.Dispose() }
        $p.WaitForExit()
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

$actual = Join-Path $work 'b1.stdout'
$oracle = Join-Path $work 'php.stdout'
$script = Join-Path $fixtures 'contract_d17.php'
Capture $java "-XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler -Xlog:class+load=info:file=class-load.log -cp ""$classes"" pl.codeloom.php.b1.B1Main ""$script""" $actual
Capture 'docker' "run --rm -v ""${fixtures}:/fixtures:ro"" $PhpImage php /fixtures/contract_d17.php" $oracle
$log = Join-Path $work 'class-load.log'
if (!(Select-String -Path $log -SimpleMatch 'pl.codeloom.php.b1.B1Main' -Quiet)) { throw 'Missing class-load evidence' }
if (Select-String -Path $log -Pattern 'com[./]oracle[./]truffle[./]' -Quiet) { throw 'Truffle class loaded' }
$hidden = @(Select-String -Path $log -Pattern ' pl\.codeloom\.php\.b1\.[^ ]+/0x[0-9a-f]+ source: __JVM_LookupDefineClass__')
# Bootstrap-generated JDK lambda proxies have a different source in the class-load log.
if ($hidden.Count -ne 6) { throw "Expected six PHP hidden classes, found $($hidden.Count)" }
$expected = [Convert]::ToBase64String([IO.File]::ReadAllBytes($oracle))
foreach ($file in $actual, (Join-Path $fixtures 'contract_d17.stdout')) {
    if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($file)) -cne $expected) { throw "$file differs from PHP" }
}
Write-Host "0 Truffle classes loaded. All $((Get-Item $oracle).Length) stdout bytes match PHP 8.5.13. Evidence in $work."
