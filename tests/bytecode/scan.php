<?php
// Translates every function, method and closure body of the files listed (one path per line in the file named by
// the first argument) without running them (truffle_translate), so -Dphp.bytecode.report=all lists, for each
// body, whether it goes to bytecode and what keeps it on the tree. Run with bin-php.sh (it declares truffle_*):
//   JAVA_OPTS="-Dpolyglot.php.Parser=java -Dphp.bytecode.report=all" ./bin-php.sh tests/bytecode/scan.php LIST
// A file that fails to translate is listed on stdout with its errors, and the scan goes on.
foreach (file($argv[1], FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $path) {
    file_put_contents("php://stderr", "php.bytecode file     $path\n");
    try {
        foreach (truffle_translate($path) as $error) {
            echo "$path: $error\n";
        }
    } catch (\Throwable $e) {
        echo "$path: ", $e->getMessage(), "\n";
    }
}
