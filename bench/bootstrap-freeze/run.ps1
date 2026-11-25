[CmdletBinding()]
param(
    [int] $Port = 8098,
    [int] $WarmRounds = 15,
    [int] $TimingRounds = 25,
    [int] $HeapWarmRounds = 150,
    [switch] $CompileOnly,
    [switch] $ExactAudit,
    [string] $Python,
    [string] $ElementorRoot
)
$ErrorActionPreference = 'Stop'
$pyArgs = @()
if (!$Python) {
    if (Get-Command py -ErrorAction SilentlyContinue) {
        $Python = 'py'
        $pyArgs = @('-3')
    } elseif (Get-Command python -ErrorAction SilentlyContinue) {
        $Python = 'python'
    } else {
        # uv-managed interpreters also work without a system Python or an activated venv.
        $interpreters = @(Get-ChildItem -Path "$env:APPDATA/uv/python/cpython-3.*/python.exe" -ErrorAction SilentlyContinue |
                          Sort-Object FullName -Descending)
        if ($interpreters.Count -eq 0) { throw 'Set -Python to a Python 3.10+ executable.' }
        $Python = $interpreters[0].FullName
    }
}
$arguments = @('-B', "$PSScriptRoot/verify.py", '--port', $Port, '--warm-rounds', $WarmRounds,
               '--timing-rounds', $TimingRounds, '--heap-warm-rounds', $HeapWarmRounds)
if ($ElementorRoot) { $arguments += @('--elementor-root', $ElementorRoot) }
if ($ExactAudit) { $arguments += '--exact-audit' }
if ($CompileOnly) { $arguments += '--compile-only' }
& $Python @pyArgs @arguments
exit $LASTEXITCODE
