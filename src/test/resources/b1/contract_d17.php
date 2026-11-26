<?php
class Box {
    public $tag;

    public function __construct($tag) {
        $this->tag = $tag;
    }

    public function __destruct() {
        echo "destroy:$this->tag\n";
    }
}

function makeObj($tag) {
    $o = new Box($tag);
    return $o;
}

function throwAtLine($msg) {
    throw new Exception($msg);
}

function takeRef(int &$x, $obj, $delta) {
    echo "enter:takeRef:$obj->tag:$x\n";
    $x = $x + $delta;
    return $x;
}

$a = 10;
$r = takeRef($a, makeObj('normal_arg'), 5);
echo "after_normal:a=$a:r=$r\n";

try {
    $r2 = takeRef($a, makeObj('aborted_arg'), throwAtLine('boom'));
    echo "UNREACHABLE\n";
} catch (Exception $e) {
    echo "caught:" . $e->getMessage() . ":line=" . $e->getLine() . "\n";
}
echo "after_abort:a=$a\n";

$held = makeObj('held1');
$held = makeObj('held2');
echo "before_end\n";
