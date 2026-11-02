<?php
// The built-in functions, classes and constants a plugin's code names, and which of them an engine lacks
// (tests/gate2/woo.sh builtins). Names come from the tokens: a name followed by "(" that isn't a method, a
// declaration or `new` is a function call; any other name that PHP 8.5 declares as an internal class or constant
// counts as one. Strings and comments don't count; names built at run time aren't seen.
// php woo-builtins.php scan DIR > names.json   (on PHP 8.5: what it declares)
// php woo-builtins.php check names.json        (on the engine under test: what it lacks, with a call site each)

if ($argv[1] === 'scan') {
    $internal = array_flip(get_defined_functions()['internal']);
    $classes = [];
    foreach (array_merge(get_declared_classes(), get_declared_interfaces(), get_declared_traits()) as $c) {
        if ((new ReflectionClass($c))->isInternal()) {
            $classes[strtolower($c)] = $c;
        }
    }
    $constants = [];
    foreach (get_defined_constants(true) as $group => $list) {
        if ($group !== 'user') {
            $constants += $list;
        }
    }
    $used = ['functions' => [], 'classes' => [], 'constants' => []];
    $files = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($argv[2], FilesystemIterator::SKIP_DOTS));
    foreach ($files as $file) {
        if (substr($file, -4) !== '.php') {
            continue;
        }
        $tokens = array_values(array_filter(token_get_all(file_get_contents($file)),
            fn($t) => !is_array($t) || !in_array($t[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT], true)));
        foreach ($tokens as $i => $t) {
            if (!is_array($t) || !in_array($t[0], [T_STRING, T_NAME_FULLY_QUALIFIED, T_NAME_QUALIFIED], true)) {
                continue;
            }
            $name = ltrim($t[1], '\\');
            if ($t[0] === T_NAME_QUALIFIED) {
                continue; // a namespaced name: never a global built-in
            }
            $lower = strtolower($name);
            $prev = $tokens[$i - 1] ?? null;
            $next = $tokens[$i + 1] ?? null;
            $where = substr($file, strlen($argv[2]) + 1) . ':' . $t[2];
            $member = is_array($prev) && in_array($prev[0], [T_OBJECT_OPERATOR, T_NULLSAFE_OBJECT_OPERATOR,
                    T_DOUBLE_COLON, T_FUNCTION, T_CONST], true);
            if ($next === '(' && !$member && !(is_array($prev) && $prev[0] === T_NEW)) {
                if (isset($internal[$lower])) {
                    $used['functions'][$lower] ??= [0, $where];
                    $used['functions'][$lower][0]++;
                }
            } elseif (isset($classes[$lower]) && !$member) {
                $used['classes'][$classes[$lower]] ??= [0, $where];
                $used['classes'][$classes[$lower]][0]++;
            } elseif (isset($constants[$name]) && !$member) {
                $used['constants'][$name] ??= [0, $where];
                $used['constants'][$name][0]++;
            }
        }
    }
    foreach ($used as &$list) {
        ksort($list);
    }
    echo json_encode($used, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES), "\n";
} else {
    $used = json_decode(file_get_contents($argv[2]), true);
    $missing = 0;
    foreach ($used['functions'] as $name => [$count, $where]) {
        if (!function_exists($name)) {
            echo "function $name ($count sites, first $where)\n";
            $missing++;
        }
    }
    foreach ($used['classes'] as $name => [$count, $where]) {
        if (!class_exists($name, false) && !interface_exists($name, false)
            && !(function_exists("trait_exists") && trait_exists($name, false))) {
            echo "class $name ($count sites, first $where)\n";
            $missing++;
        }
    }
    foreach ($used['constants'] as $name => [$count, $where]) {
        if (!defined($name)) {
            echo "constant $name ($count sites, first $where)\n";
            $missing++;
        }
    }
    printf("%d functions, %d classes, %d constants named; %d missing\n", count($used['functions']),
        count($used['classes']), count($used['constants']), $missing);
}
