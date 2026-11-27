<?php
// Translation alone, cold: every file listed (one path per line in the file named by the first argument) parsed
// and translated with every function, method and closure body in it (truffle_translate), nothing run, in a JVM
// just started, as a cold visit translates. Prints the time and the bodies' errors. bench/cold-visit/translate.sh.
$files = file($argv[1], FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
$start = hrtime(true);
$errors = 0;
foreach ($files as $path) {
    try {
        $errors += count(truffle_translate($path));
    } catch (\Throwable $e) {
        $errors++;
    }
}
printf("translated %d files in %.3f s, %d errors\n", count($files), (hrtime(true) - $start) / 1e9, $errors);
