<?php
// class_implements(), class_parents() and class_uses() (WooCommerce's wc_get_logger checks its logger class)
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});
interface A {}
interface B extends A {}
interface C {}
trait T1 {}
trait T2 { use T1; }
class P implements C {}
class Q extends P implements B, Countable { use T2; function count(): int { return 0; } }
class R extends Q { use T1; }
foreach (['A', 'B', 'P', 'Q', 'R', 'T2'] as $name) {
    echo $name, ': implements ', json_encode(class_implements($name)), ' parents ', json_encode(class_parents($name)),
        ' uses ', json_encode(class_uses($name)), "\n";
}
var_dump(class_implements(new R) === class_implements('R'), class_implements(new RuntimeException()));
class S extends R {}
echo json_encode(class_implements('S')), "
";
spl_autoload_register(function ($c) { echo "autoload $c\n"; });
var_dump(class_implements('Missing'), class_parents('Missing', false), class_uses('missing'));
try {
    class_implements(42);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
