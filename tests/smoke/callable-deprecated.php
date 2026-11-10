<?php
// "self", "parent" and "static" in a callable given to a built-in are deprecated since PHP 8.2
// (zend_is_callable_check_class, zend_is_callable_check_func): each resolution says so, in the line of the call
// that resolves. is_callable() asks without the message.

class A {
    public static function who() { return static::class; }

    public function run() {
        $r = [];
        for ($i = 0; $i < 2; $i++) {
            $r[] = call_user_func(['self', 'who']);
            $r[] = call_user_func('static::who');
            $r[] = implode(',', array_map(['static', 'who'], [1, 2]));
            $r[] = var_export(is_callable(['self', 'who']), true);
            $r[] = var_export(is_callable('static::who'), true);
        }
        return implode(' ', $r);
    }
}

class B extends A {
    public function up() {
        $list = [2, 1];
        usort($list, ['parent', 'cmp']);
        return call_user_func(['parent', 'who']) . ' ' . call_user_func([$this, 'parent::who']) . ' '
            . call_user_func('parent::who') . ' ' . implode('', $list);
    }

    public static function cmp($a, $b) { return $a <=> $b; }
}

echo (new B)->run(), "\n";
echo (new B)->up(), "\n";
echo (new A)->run(), "\n";
