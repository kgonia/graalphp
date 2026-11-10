"""Checks the host before a check runs; exit 1 with the reasons when one fails (wiki/checks.md).

  perf     before a timed run (L2), with the CPU lock already held by the caller:
           - no other check run is active (target/check/run.pid);
           - the WSL VM runs on the E-cores only (`(Get-Process vmmemWSL).ProcessorAffinity = 0x3FC3FC`, which an
             unelevated shell can't read back): 24 busy vCPUs for a few seconds must load the E-cores and leave the
             P-cores at Windows' own load;
           - the host is quiet: the E-cores' mean load over a few seconds under QUIET (default 15%);
           - memlock has GB free (default 6).
  correct  before an untimed run (L0, L1): no other check run is active; no timed run holds the CPU lock (the WSL
           VM's 24 vCPUs share the 16 E-cores, so a container on CPUs 6-13 slows a timed run on 14-21 too; BESIDE=1
           runs anyway); memlock has GB free (default 4).

Every result is printed as one JSON object on the last line (the host conditions a run records with its results).
The host's logical processors (bench/perf-step4/lpload.ps1): P-cores 0, 1, 10-13, 22, 23; the other 16 E-cores.

Usage: python -I preflight.py perf|correct [--gb N] [--quiet PERCENT]
"""
import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
P_CORES = [0, 1, 10, 11, 12, 13, 22, 23]
E_CORES = [lp for lp in range(24) if lp not in P_CORES]
MEMLOCK = ['python', 'C:/Users/krzgo/Projects/memory-lock/memlock.py']
PIDFILE = os.path.join(ROOT, 'target', 'check', 'run.pid')
CPU_LOCK = 'C:/Users/krzgo/Projects/.cpu-14-21.lock'

SAMPLE = r'''
$sum = @{}
for ($i = 0; $i -lt SECONDS; $i++) {
    Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor | Where-Object { $_.Name -match '^\d+$' } |
        ForEach-Object { $sum[[int]$_.Name] += [double]$_.PercentProcessorTime }
    Start-Sleep -Milliseconds 1000
}
(0..23 | ForEach-Object { '{0:N1}' -f ($sum[$_] / SECONDS) }) -join ' '
'''


def sample(seconds):
    """Mean load per logical processor over `seconds` one-second samples."""
    out = subprocess.run(['powershell', '-NoProfile', '-Command', SAMPLE.replace('SECONDS', str(seconds))],
                         capture_output=True, text=True, check=True).stdout.split()
    return [float(v.replace(',', '.')) for v in out[-24:]]


def mean(values):
    return sum(values) / len(values)


def other_run():
    """The pid of another live check run, or None."""
    try:
        pid = int(open(PIDFILE).read().split()[0])
    except (OSError, ValueError, IndexError):
        return None
    if pid in (os.getppid(), os.getpid(), int(os.environ.get('CHECK_RUN_PID') or 0)):
        return None
    alive = subprocess.run(['ps', '-p', str(pid)], capture_output=True).returncode == 0
    return pid if alive else None


def memlock_free(gb):
    return subprocess.run(MEMLOCK + ['status', '--gb', str(gb)], capture_output=True).returncode == 0


def wsl_on_e_cores():
    """Loads every vCPU of the WSL VM for 6 s and samples the host meanwhile: (ok, E-core mean, P-core mean)."""
    busy = subprocess.Popen(['docker', 'run', '--rm', 'alpine:3', 'sh', '-c',
                             'for i in $(seq 24); do timeout 6 sh -c "while :; do :; done" & done; wait'],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        loads = sample(4)
    finally:
        busy.wait()
    e, p = mean([loads[lp] for lp in E_CORES]), mean([loads[lp] for lp in P_CORES])
    return e >= 80 and p <= 60, round(e), round(p)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('kind', choices=['perf', 'correct'])
    parser.add_argument('--gb', type=int)
    parser.add_argument('--quiet', type=float, default=float(os.environ.get('QUIET', 15)))
    args = parser.parse_args()
    problems, host = [], {}
    pid = other_run()
    if pid:
        problems.append(f'another check run is active (pid {pid}, {PIDFILE})')
    gb = args.gb or (6 if args.kind == 'perf' else 4)
    if not memlock_free(gb):
        problems.append(f'memlock has no {gb} GB free')
    if args.kind == 'correct' and os.path.isdir(CPU_LOCK) and not os.environ.get('BESIDE'):
        try:
            note = open(os.path.join(CPU_LOCK, 'note'), encoding='utf-8').read().strip()
        except OSError:
            note = '?'
        problems.append(f'a timed run holds the CPU lock ({note}); BESIDE=1 runs anyway')
    if args.kind == 'perf' and not problems:
        ok, e, p = wsl_on_e_cores()
        host['affinity_test'] = {'e_cores': e, 'p_cores': p}
        if not ok:
            problems.append(f'the WSL VM is not on the E-cores only: 24 busy vCPUs gave E-cores {e}%, P-cores {p}% '
                            '(set it in an elevated PowerShell: (Get-Process vmmemWSL).ProcessorAffinity = 0x3FC3FC)')
        loads = sample(3)
        host['e_cores'] = round(mean([loads[lp] for lp in E_CORES]), 1)
        host['p_cores'] = round(mean([loads[lp] for lp in P_CORES]), 1)
        if host['e_cores'] > args.quiet:
            problems.append(f"the host isn't quiet: E-cores at {host['e_cores']}% (QUIET {args.quiet}%)")
    for problem in problems:
        print('preflight:', problem, file=sys.stderr)
    host['ok'] = not problems
    print(json.dumps(host))
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
