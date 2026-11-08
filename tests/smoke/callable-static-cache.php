<?php
// Issue 056: 'static::m' as a callable string resolves against the calling class on each call.
class P { static function m() { return static::class; } function run() { return array_map('static::m', [1])[0]; } }
class A2 extends P {}
class B2 extends P {}
echo @(new A2)->run(), ' ', @(new B2)->run(), "\n";
