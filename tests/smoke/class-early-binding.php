<?php
// Issue 003: php-src binds a top-level class when it compiles the file (zend_compile_class_decl), before any of the
// file runs, if it implements no interface and uses no trait, and has no parent or a parent already linked then.
// Every other class is declared when its statement runs.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});
register_shutdown_function(function () {
    $e = error_get_last();
    echo "shutdown: ", $e['type'], " ", $e['line'], "\n";
});
spl_autoload_register(function ($name) { echo "autoload $name\n"; });

$names = ['Plain', 'Child', 'GrandChild', 'LaterParentChild', 'LaterParent', 'WithInterface', 'AfterUnbound',
    'Unbound', 'WithTrait', 'Ifc', 'Tr', 'InBlock', 'Ns\InNamespace', 'Ns\NsChild', 'FromException',
    'Conditional', 'FromIncluded', 'MissingParentChild'];
foreach ($names as $name) {
    $kind = interface_exists($name, false) ? 'interface' : (trait_exists($name, false) ? 'trait'
        : (class_exists($name, false) ? 'class' : '-'));
    echo "$name: $kind\n";
}
echo (new Plain())->hello(), "\n";
echo get_parent_class(new GrandChild()), "\n";
echo Child::NAME, "\n";

class Plain { function hello() { return "hello"; } }
class Child extends Plain { const NAME = 'child'; }
class GrandChild extends Child {}
class LaterParentChild extends LaterParent {}
class LaterParent {}
interface Ifc {}
class WithInterface implements Ifc {}
class Unbound implements Ifc {}
class AfterUnbound extends Unbound {}
trait Tr {}
class WithTrait { use Tr; }
{
    class InBlock {}
}
class FromException extends Exception {}
if (true) {
    class Conditional {}
}
require __DIR__ . '/class-early-binding.inc';
echo class_exists('FromIncluded', false) ? "FromIncluded bound\n" : "FromIncluded not bound\n";
echo class_exists('IncludedLater', false) ? "IncludedLater bound\n" : "IncludedLater not bound\n";
require __DIR__ . '/class-early-binding-ns.inc';
echo Ns\NsChild::class, "\n";
echo "missing parent:\n";
echo class_exists('MissingParentChild') ? "declared\n" : "not declared\n";
echo "twice:\n";
require __DIR__ . '/class-early-binding-ns.inc';
echo "not reached\n";
class MissingParentChild extends MissingParent {}
