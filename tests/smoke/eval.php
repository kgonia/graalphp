<?php
// eval: the calling scope's variables, its return value (null without return), its file and line, parse errors,
// declarations, traces, and $this and self in a method

// the last part of a path, whichever separator it has
function tail($path) {
    return preg_replace('~^.*[/\\\\]~', '', $path);
}

function inFunction() {
    $a = 1;
    $r = eval('$b = $a + 1; return [tail(__FILE__), __LINE__, $b];');
    var_dump($r, $b);
}
inFunction();

$g = 10;
var_dump(eval('return $g * 2;'));
var_dump(eval('$h = 5;'));
var_dump($h);
var_dump(eval('1;'));
eval('?>inline <?php echo "html\n";');

try {
    eval('1 +;');
} catch (ParseError $e) {
    echo get_class($e), ': ', $e->getMessage(), ' ', tail($e->getFile()), ':', $e->getLine(), "\n";
}

eval('function declaredInEval($x) { return $x * 3; } class InEval { const C = 4; }');
var_dump(declaredInEval(2), InEval::C);

eval('function thrower() { throw new Exception("from eval"); }');
try {
    thrower();
} catch (Exception $e) {
    echo tail($e->getFile()), ':', $e->getLine(), "\n";
    foreach ($e->getTrace() as $frame) {
        echo $frame['function'], ' ', isset($frame['args']) ? count($frame['args']) : '-', "\n";
    }
}

function traced() {
    return eval('return debug_backtrace();');
}
foreach (traced() as $frame) {
    echo $frame['function'], ' ', isset($frame['args']) ? count($frame['args']) : '-', ' ',
        tail($frame['file'] ?? '?'), "\n";
}

class Host {
    private $secret = 'hidden';
    const NAME = 'host';

    function run() {
        return eval('return [$this->secret, self::NAME, static::class, __CLASS__];');
    }
}
class Child extends Host {
}
var_dump((new Child)->run());

$byRef = [1, 2];
eval('$byRef[] = 3; $alias = &$byRef;');
$alias[] = 4;
var_dump($byRef);

for ($i = 0; $i < 3; $i++) {
    eval('$sum = ($sum ?? 0) + $i;');
}
var_dump($sum);

echo eval('return "nested " . eval("return 1 + 1;");'), "\n";
var_dump(eval(''));
eval('echo "warn: ", $undefinedInEval ?? "none", "\n";');
