<?php
// Included twice by class-early-binding.php: the second time the names are taken, so the classes are declared
// when their statements run, and the first one fails there.
namespace Ns {
    echo class_exists('Ns\InNamespace', false) ? "InNamespace bound\n" : "InNamespace not bound\n";
    echo class_exists('Ns\NsChild', false) ? "NsChild bound\n" : "NsChild not bound\n";
    echo "before the declarations\n";
    class InNamespace {}
    class NsChild extends InNamespace {}
}
