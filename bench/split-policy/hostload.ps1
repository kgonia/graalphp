# The host's CPU load outside the benchmark cores (0-13, 22-23) and on them (14-21), averaged over 3 s, and the six
# processes using the most CPU: one line each, for the A/B's log (bench/split-policy/runs.sh).
$samples = foreach ($i in 1..3) {
    Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor | Where-Object { $_.Name -match '^\d+$' }
    Start-Sleep -Seconds 1
}
$cores = $samples | Group-Object Name | ForEach-Object {
    [pscustomobject]@{ core = [int]$_.Name; load = ($_.Group | Measure-Object PercentProcessorTime -Average).Average } }
$outside = ($cores | Where-Object { $_.core -lt 14 -or $_.core -gt 21 } | Measure-Object load -Average).Average
$bench = ($cores | Where-Object { $_.core -ge 14 -and $_.core -le 21 } | Measure-Object load -Average).Average
$top = Get-CimInstance Win32_PerfFormattedData_PerfProc_Process |
    Where-Object { $_.Name -ne '_Total' -and $_.Name -ne 'Idle' } |
    Sort-Object PercentProcessorTime -Descending | Select-Object -First 6 |
    ForEach-Object { '{0} {1}%' -f $_.Name, $_.PercentProcessorTime }
'outside 14-21 {0:N0}%, on 14-21 {1:N0}%; top: {2}' -f $outside, $bench, ($top -join ', ')
