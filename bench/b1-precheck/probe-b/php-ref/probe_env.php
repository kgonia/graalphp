<?php
declare(strict_types=1);
$s = opcache_get_status(false);
$on = (int) ($s['jit']['on'] ?? 0);
$kind = (int) ($s['jit']['kind'] ?? -1);
echo "PHP ", PHP_VERSION, " jit_on=", $on, " kind=", $kind, "\n";
if ($on !== 1 || $kind !== 5) {
    fwrite(STDERR, "ERROR: Expected OPcache tracing JIT active (jit_on=1, kind=5), got on={$on} kind={$kind}\n");
    exit(1);
}
