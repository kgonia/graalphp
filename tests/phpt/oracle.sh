#!/usr/bin/env bash
# Runs inside the gate1-php image: each case's SKIPIF, then the case itself, on PHP. stdout goes to
# expected/<case>.out; expected/results.txt gets "case<TAB>outcome<TAB>exit code". Diagnostics are not displayed
# (this runtime emits no warnings), so the comparison is of what the program prints. PHP's log goes to
# expected/<case>.err, so a fatal error's message can be compared with the reason Truffle PHP died.
# Usage (from the repo root): bench/gate1/php.sh bash tests/phpt/oracle.sh target/phpt
set -u
dir=$1
out=$dir/expected
mkdir -p "$out"
: > "$out/results.txt"
opts=(-d display_errors=0 -d log_errors=0 -d error_reporting=E_ALL -d zend.assertions=1 -d precision=14
      -d serialize_precision=-1 -d memory_limit=128M -d output_buffering=0)
while IFS= read -r case; do
    [ -z "$case" ] && continue
    skipif="$dir/cases/${case%.php}.skipif.php"
    if [ -f "$skipif" ]; then
        verdict=$(cd "$(dirname "$skipif")" && timeout 10 php "${opts[@]}" "$(basename "$skipif")" 2>/dev/null)
        if [[ "$verdict" =~ ^[[:space:]]*[Ss]kip ]]; then
            printf '%s\tskip\t0\n' "$case" >> "$out/results.txt"
            continue
        fi
    fi
    mkdir -p "$(dirname "$out/$case")"
    rm -f "$out/$case.err"
    (cd "$(dirname "$dir/cases/$case")" && timeout 10 php "${opts[@]}" -d log_errors=1 -d error_log="$OLDPWD/$out/$case.err" \
        "$(basename "$case")" > "$OLDPWD/$out/$case.out" 2>/dev/null)
    code=$?
    if [ $code -eq 124 ]; then outcome=timeout; else outcome=ran; fi
    printf '%s\t%s\t%d\n' "$case" "$outcome" "$code" >> "$out/results.txt"
done < "$dir/cases/cases.txt"
cut -f2 "$out/results.txt" | sort | uniq -c
