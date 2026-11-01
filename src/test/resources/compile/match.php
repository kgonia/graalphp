<?php
// match in hot functions: int and string subjects, several conditions per arm, match (true) over expressions,
// nested matches, a throw in an arm, UnhandledMatchError caught. The arms are a constant list, unrolled; an arm
// first taken after compilation (and the error) deoptimizes once, then is compiled too. The budgets: about twice
// the IR the functions compile to now (2026-11-01: status 179, size 140, kind 541, strict 376).
// IR budget: status 400
// IR budget: size 300
// IR budget: kind 1100
// IR budget: strict 800
function status(int $code): string {
    return match ($code) {
        200, 201, 204 => 'ok',
        301, 302 => 'redirect',
        404 => 'missing',
        500, 502, 503 => 'error',
        default => 'other',
    };
}
function kind(string $type): int {
    return match ($type) {
        'post', 'page' => 1,
        'attachment' => 2,
        'revision' => match (strlen($type) % 3) { 0 => 30, 1 => 31, 2 => 32 },
        default => throw new InvalidArgumentException("unknown type $type"),
    };
}
function size(int $n): string {
    return match (true) {
        $n < 10 => 'small',
        $n < 100, $n === 1000 => 'medium',
        default => 'large',
    };
}
function strict(mixed $value): string {
    return match ($value) {
        1 => 'int',
        '1' => 'string',
        1.0 => 'float',
        true => 'bool',
    };
}
$codes = [200, 301, 404, 500, 201, 302, 999];
$types = ['post', 'page', 'attachment', 'revision'];
$values = [1, '1', 1.0, true];
$out = 0;
for ($i = 0; $i < 3000; $i++) {
    $out += strlen(status($codes[$i % 7])) + kind($types[$i % 4]) + strlen(size($i % 200));
    $out += strlen(strict($values[$i % 4]));
    if ($i > 2000) {
        $out += strlen(status(503)) + strlen(size(1000));
        try {
            $out += kind('nav_menu_item');
        } catch (InvalidArgumentException $e) {
            $out++;
        }
        try {
            strict(null);
        } catch (UnhandledMatchError $e) {
            $out += strlen($e->getMessage());
        }
    }
}
echo $out, "\n";
