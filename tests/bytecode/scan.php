<?php
// Translates every function, method and closure body of the files listed (one path per line in the file named by
// the first argument) without running them (truffle_translate): a construct the translator doesn't cover, or a
// compile error, is listed on stdout with its file, and the scan goes on. Run with bin-php.sh (it declares
// truffle_*):   JAVA_OPTS="-Dpolyglot.php.Parser=java" ./bin-php.sh tests/bytecode/scan.php LIST
foreach (file($argv[1], FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $path) {
    try {
        foreach (truffle_translate($path) as $error) {
            echo "$path: $error\n";
        }
    } catch (\Throwable $e) {
        echo "$path: ", $e->getMessage(), "\n";
    }
}
