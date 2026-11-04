<?php
// Casts, print, global constants the translation doesn't fold, magic constants and $obj::class in functions on
// bytecode.

namespace Ns {
    const OWN = 'ns own';
    const E_ALL = 'ns E_ALL';

    function constants() {
        // OWN and E_ALL are the namespace's; E_ERROR falls back to the global one; \E_ALL is the global one
        return [OWN, E_ALL, \E_ALL, E_ERROR, \Ns\OWN, namespace\OWN, __NAMESPACE__, __FUNCTION__];
    }

    function undefined_here() {
        try {
            return NOT_DEFINED_ANYWHERE;
        } catch (\Error $e) {
            return $e::class . ': ' . $e->getMessage();
        }
    }
}

namespace {
    class Plain {
        public $a = 1;
        protected $b = 2;
        private $c = 3;
        public $list = [1, 2];
    }

    class Str {
        function __toString(): string {
            echo "[toString]";
            return "str object";
        }
    }

    class Magic {
        function method() {
            return [__FUNCTION__, __METHOD__, __CLASS__, __LINE__];
        }

        static function staticMethod() {
            return [__FUNCTION__, __METHOD__, __CLASS__];
        }

        function gen() {
            yield __METHOD__;
            yield __CLASS__;
            yield __LINE__;
        }
    }

    function rel($text) {
        return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
    }

    function show($label, $value) {
        echo $label, ': ';
        var_dump($value);
    }

    function casts($v) {
        echo json_encode([(int) $v, (float) $v, (bool) $v]), ' ';
        var_dump((string) $v);
    }

    function scalar_casts() {
        foreach ([0, 1, -5, 3.0, 2.5, -0.0, '12', '12abc', ' 7', '1e3', '0x1A', 'abc', '', '0', '0.0', true,
                  false, null] as $v) {
            casts($v);
        }
        show('float string', (string) 0.1);
        show('float string 2', (string) 1e25);
        show('float string 3', (string) -1.0);
        show('array to bool', [(bool) [], (bool) [0]]);
        show('array to int', [(int) [], (int) [1, 2], (float) [3]]);
    }

    function array_casts() {
        show('scalar to array', (array) 'a');
        show('int to array', (array) 5);
        show('null to array', (array) null);
        show('array stays', (array) [1, 'k' => 2]);
        $o = new Plain();
        $arr = (array) $o;
        foreach ($arr as $value) {
            echo json_encode($value), ' ';
        }
        echo "\n";
        $std = (object) ['a' => 1, 'b' => [2, 3]];
        show('array to object', $std);
        show('scalar to object', (object) 'text');
        echo 'null to object: ', json_encode((object) null), "\n";
        show('object stays', (object) $o === $o);
        $back = (array) $std;
        $back['b'] = 4;
        show('copy on write', [$std->b, $back['b']]);
    }

    function object_casts() {
        $s = new Str();
        show('string of object', (string) $s);
        show('bool of object', (bool) $s);
        show('interpolated', "x" . (string) $s);
    }

    function prints() {
        print "printed\n";
        $r = print "printed again\n";
        show('print value', $r);
        if (print "") {
            echo "print is truthy\n";
        }
        print("a") && print("b\n");
        echo 'sum ', (print "p") + 1, "\n";
        print 1.5;
        print "\n";
    }

    define('DEFINED_LATER', 'later');

    function constants() {
        return [DEFINED_LATER, PHP_EOL, PHP_INT_MAX, E_ALL, M_PI, true, FALSE, Null, \PHP_INT_SIZE, USER_CONST];
    }

    function maybe_defined() {
        try {
            return LATE_CONST;
        } catch (Error $e) {
            return $e::class . ': ' . $e->getMessage();
        }
    }

    function magic() {
        return [__FUNCTION__, __METHOD__, __CLASS__, __LINE__, __NAMESPACE__, rel(__FILE__), rel(__DIR__ . '/')];
    }

    function class_names($values) {
        foreach ($values as $v) {
            try {
                echo $v::class, "\n";
            } catch (TypeError $e) {
                echo $e::class, ': ', $e->getMessage(), "\n";
            }
        }
    }

    function mixed_gen() {
        $x = yield (int) '42x';
        echo "got ", (string) $x, "\n";
        print "printed in a generator\n";
        yield DEFINED_LATER;
        yield __FUNCTION__;
    }

    const USER_CONST = 'user';

    scalar_casts();
    array_casts();
    object_casts();
    prints();
    show('constants', constants());
    show('ns constants', Ns\constants());
    show('ns undefined', Ns\undefined_here());
    show('late before', maybe_defined());
    define('LATE_CONST', 'now defined');
    show('late after', maybe_defined());
    show('late again', maybe_defined());
    show('magic', magic());
    $m = new Magic();
    show('method', $m->method());
    show('static method', Magic::staticMethod());
    show('method generator', iterator_to_array($m->gen()));
    $closure = function () {
        return [rel(__FUNCTION__), rel(__METHOD__), __CLASS__];
    };
    show('closure', $closure());
    class_names([new Plain(), $m, $closure, 'Plain', 1, null, [1], 1.5, true]);
    $g = mixed_gen();
    show('gen first', $g->current());
    show('gen second', $g->send(7));
    $g->next();
    show('gen third', $g->current());
}
