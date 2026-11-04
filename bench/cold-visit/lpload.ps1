# Per logical processor load on the Windows host: SECONDS samples 1 s apart (Win32_PerfFormattedData_PerfOS_Processor,
# locale independent), one line per sample "time lp0 lp1 ... lp23", then the mean per LP and the means over the
# P-cores and the E-cores. This machine's P-cores (Core Ultra 9 275HX, efficiency class 1 in
# GetSystemCpuSetInformation, bench/cold-visit/cpusets.ps1) are LPs 0, 1, 10-13, 22, 23; the other 16 are E-cores.
param([int]$Seconds = 10)
$pcores = @(0, 1, 10, 11, 12, 13, 22, 23)
$sum = @{}
for ($i = 0; $i -lt $Seconds; $i++) {
    $rows = Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor | Where-Object { $_.Name -match '^\d+$' } |
        Sort-Object { [int]$_.Name }
    $line = (Get-Date -Format 'HH:mm:ss') + ' ' + (($rows | ForEach-Object { '{0,3}' -f $_.PercentProcessorTime }) -join ' ')
    $line
    foreach ($r in $rows) { $sum[[int]$r.Name] += [double]$r.PercentProcessorTime }
    Start-Sleep -Milliseconds 1000
}
'mean     ' + ((0..23 | ForEach-Object { '{0,3:N0}' -f ($sum[$_] / $Seconds) }) -join ' ')
$p = ($pcores | ForEach-Object { $sum[$_] / $Seconds } | Measure-Object -Average).Average
$e = (0..23 | Where-Object { $pcores -notcontains $_ } | ForEach-Object { $sum[$_] / $Seconds } | Measure-Object -Average).Average
$b = (14..21 | ForEach-Object { $sum[$_] / $Seconds } | Measure-Object -Average).Average
'P-cores {0:N0}%, E-cores {1:N0}%, LPs 14-21 {2:N0}%' -f $p, $e, $b
