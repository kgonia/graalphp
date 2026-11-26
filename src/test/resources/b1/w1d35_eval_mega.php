<?php
class Watch {
    public function __destruct() { echo "drop\n"; }
}
function exercise() {
    $guard = new Watch();
    $callbacks = [];
    for ($i = 0; $i < 8; $i++) {
        eval("function hot_f$i(\$v) { return \$v + $i; }");
        eval("class Hot_C$i { public \$n = $i; public function a(\$v) { return \$v + \$this->n; }"
            . " public static function s(\$v) { return \$v + 2; } }");
        $class = "Hot_C$i";
        $obj = new $class();
        $callbacks[] = "hot_f$i";
        $callbacks[] = [$obj, 'a'];
        $callbacks[] = [$class, 's'];
        $callbacks[] = eval("return static function (\$v) { return \$v + $i; };");
    }
    $total = 0;
    foreach ($callbacks as $key => $fn) {
        $total += $fn($key);
    }
    $calls = 0;
    $counted = static function ($a, $b) use ($total, &$calls) {
        ++$calls;
        return $total + $a + $b;
    };
    echo $counted(3, 4), "/", $calls, "\n";
    $args = [8, 5, 3, 1];
    echo call_user_func_array($counted, array_slice($args, 1, 2)), "/", $calls, "\n";
}
exercise();

class Element {
    public $id;
    public function __construct($id) { $this->id = $id; }
    public function __destruct() {
        echo "element ", $this->id, "\n";
        if ($this->id === 1) throw new Exception("element cleanup");
    }
}
function releaseElements() {
    $items = [new Element(0), new Element(1), new Element(2)];
    $items = null;
}
try { releaseElements(); } catch (Exception $e) { echo $e->getMessage(), "\n"; }
