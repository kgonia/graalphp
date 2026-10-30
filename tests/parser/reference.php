<?php
// The reference for the Java parser's tests, from PHP 8.5 itself (run in gate1-php:8.5 by tests/parser/reference.sh).
// Usage: php reference.php <out-dir> <list of files> <errors file>
// For each file:
//   <out-dir>/tokens/<file>.tok  token_get_all()'s tokens, one per line: the token's name (token_name()) or, for a
//                                one-character token, the character in single quotes; its line ("-" for a one-character
//                                token, which token_get_all gives without one); its text in base64.
// and one line in the errors file for each file that ast\parse_file() (php-ast version 120, as
// tools/ast-export.php) rejects: file, the exception's class, its line, its message.

[$_, $outDir, $list, $errorsFile] = $argv;
$errors = fopen($errorsFile, 'w');
foreach (file($list, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $file) {
    $code = file_get_contents($file);
    $dump = '';
    foreach (token_get_all($code) as $token) {
        $dump .= is_array($token)
            ? token_name($token[0]) . ' ' . $token[2] . ' ' . base64_encode($token[1]) . "\n"
            : "'" . $token . "' - " . base64_encode($token) . "\n";
    }
    $target = "$outDir/tokens/" . ltrim($file, '/') . '.tok';
    @mkdir(dirname($target), 0777, true);
    file_put_contents($target, $dump);
    try {
        ast\parse_file($file, 120);
    } catch (ParseError | CompileError $e) {
        fwrite($errors, $file . "\t" . get_class($e) . "\t" . $e->getLine() . "\t"
            . str_replace(["\\", "\t", "\n"], ["\\\\", "\t", "\n"], $e->getMessage()) . "\n");
    }
}
