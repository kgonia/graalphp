<?php
// Exports php-src's own parse of a file (zend_ast via the php-ast extension) as JSON for the Truffle front end.
// Usage: php ast-export.php <out-dir> <file.php>...   writes <out-dir>/<file path>.json per input, mirroring the
// relative path so the runtime can find the AST of a file it is asked to include.
//
// Node:   {"k": kind name, "f": flags, "l": line, "c": children}; children is a list for list kinds, else an object.
// Values: JSON strings for valid UTF-8, {"b": base64} for other byte strings, {"d": "1.5"} for floats
//         (so 1.0 stays a float), ints and null as is.

const AST_VERSION = 120;

function export_value(mixed $v): mixed {
    if ($v instanceof ast\Node) {
        $children = [];
        foreach ($v->children as $key => $child) {
            $children[$key] = export_value($child);
        }
        $node = ['k' => ast\get_kind_name($v->kind), 'l' => $v->lineno];
        if ($v->flags !== 0) {
            $node['f'] = $v->flags;
        }
        if (isset($v->endLineno)) {
            $node['e'] = $v->endLineno;
        }
        $node['c'] = array_is_list($children) ? $children : (object) $children;
        return $node;
    }
    if (is_string($v)) {
        return mb_check_encoding($v, 'UTF-8') ? $v : ['b' => base64_encode($v)];
    }
    if (is_float($v)) {
        return ['d' => var_export($v, true)];
    }
    return $v;
}


[$_, $outDir] = $argv;
foreach (array_slice($argv, 2) as $file) {
    $ast = ast\parse_file($file, AST_VERSION);
    $json = json_encode(export_value($ast), JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
    $target = $outDir . '/' . ltrim($file, '/') . '.json';
    @mkdir(dirname($target), 0777, true);
    file_put_contents($target, $json);
}
