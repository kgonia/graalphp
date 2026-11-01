<?php
// The Gate 1 kernels (bench/gate1/run.php) as a page: native.sh's volume serves it as /gate1-kernels.php, so the
// HTTP launcher, on the JVM and as a native image, runs them in one request. ?k=kernel,kernel|all&n=iterations
// (defaults all, 300). The output is run.php's: "kernel iteration nanoseconds checksum native-ns" per line.
$argv = ['run.php', $_GET['k'] ?? 'all', $_GET['n'] ?? '300'];
$argc = count($argv);
header('Content-Type: text/plain');
require '/srv/bench/gate1/run.php';
