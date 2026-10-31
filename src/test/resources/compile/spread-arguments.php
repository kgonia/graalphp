<?php
// Calls with unpacked arguments, as WordPress's user_can() and current_user_can() make them: the argument loop
// with a spread once let the frame escape (ArgumentsNode.valuesWithSpread), and the compilation bailed out.
class User {
    public function has_cap(string $cap, ...$args): bool {
        return strlen($cap) + count($args) > 3;
    }
}
function user_can(User $user, string $capability, ...$args): bool {
    return $user->has_cap($capability, ...$args);
}
function bump(&$a, &$b, $c) {
    $a += $c;
    $b .= 'x';
}
function call_values(callable $f, array $args) {
    return $f(...$args);
}
$user = new User();
$total = 0;
for ($i = 0; $i < 3000; $i++) {
    $total += user_can($user, 'edit_post', $i, 'meta') ? 1 : 0;
    $pair = [$i, '', 1];
    bump(...$pair);
    $x = 1;
    $y = 'a';
    bump($x, $y, ...[2]);
    $total += call_values('max', [$i, 7, 3]) + $x;
}
echo $total, "\n";
