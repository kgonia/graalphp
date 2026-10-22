<?php
// Lists the statements a file runs at include time (everything except function and class declarations).
// Usage: php ast-toplevel.php <file.php>...
foreach (array_slice($argv, 1) as $file) {
    $ast = ast\parse_file($file, 120);
    echo "== $file\n";
    foreach ($ast->children as $stmt) {
        if (!$stmt instanceof ast\Node || in_array($stmt->kind, [ast\AST_FUNC_DECL, ast\AST_CLASS], true)) {
            continue;
        }
        $line = trim(file($file)[$stmt->lineno - 1]);
        printf("  %5d %-18s %s\n", $stmt->lineno, ast\get_kind_name($stmt->kind), substr($line, 0, 100));
    }
}
