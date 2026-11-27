#!/bin/sh
# Per vCPU load inside the WSL VM (docker-desktop), from /proc/stat (not namespaced): SECONDS samples 1 s apart, one
# line per sample "time cpu0 ... cpuN" in percent busy, then the mean per vCPU. Runs in a container (alpine).
n=${1:-10}
snap() { grep '^cpu[0-9]' /proc/stat | awk '{t=0; for (i=2;i<=NF;i++) t+=$i; print $1, t, $5+$6}'; }
snap > /tmp/a
i=0; : > /tmp/all
while [ $i -lt $n ]; do
  sleep 1; snap > /tmp/b
  line=$(paste /tmp/a /tmp/b | awk '{dt=$5-$2; di=$6-$3; printf "%3d ", dt ? 100*(dt-di)/dt : 0}')
  echo "$(date +%T) $line" | tee -a /tmp/all
  mv /tmp/b /tmp/a; i=$((i+1))
done
awk '{for (i=2;i<=NF;i++) s[i]+=$i; n=NF} END {printf "mean     "; for (i=2;i<=n;i++) printf "%3.0f ", s[i]/NR; print ""}' /tmp/all
