<?php
class Marker {
    public $name;
    public function __construct($name) { $this->name = $name; }
    public function __destruct() { echo "drop ", $this->name, "\n"; }
}
function route($mode) {
    $outer = new Marker('outer');
    $n = 0;
again:
    ++$n;
    if ($n < 2) goto again;
    try {
        $inner = new Marker('inner');
        if ($mode === 1) return 'pending';
        if ($mode === 2) goto done;
        if ($mode === 3) throw new Exception('first');
        echo "body\n";
    } finally {
        echo "finally ", $n, "\n";
        if ($mode === 1) return 'override';
        if ($mode === 3) throw new Exception('second');
    }
    echo "normal\n";
done:
    return 'done';
}
for ($i = 0; $i < 4; $i++) {
    try {
        echo route($i), "\n";
    } catch (Exception $e) {
        $prev = $e->getPrevious();
        echo $e->getMessage(), "@", $e->getLine(), "/", $prev->getMessage(), "@", $prev->getLine(), "\n";
        echo $e->getTraceAsString(), "\n";
    }
}

function replaceResult($fail) {
    try {
        return new Marker('pending result');
    } finally {
        echo "replace\n";
        if ($fail) throw new Exception('cancel');
        return 'replacement';
    }
}
echo replaceResult(false), "\n";
try { replaceResult(true); } catch (Exception $e) { echo $e->getMessage(), "\n"; }
