<?php
// An included file runs in the including scope: a function's variables, its $this and static class, or the globals.

function plain() {
    $local = 10;
    $list = ['first'];
    $count = 5;
    $counter = &$count;
    $drop = 'dropped?';
    $result = include __DIR__ . '/include-scope.inc';
    var_dump($result, $local, $created, $list, $count, isset($drop), $capture(), $arrow(), $extracted, $compacted,
        $dynamic);
}
plain();

function twice() {
    $nested = true;
    include __DIR__ . '/include-scope.inc';
    var_dump($local, $fromNested, $nested);
    // a variable this function never names lives in the call's table, where the next include finds it
    include __DIR__ . '/include-scope-reader.inc';
    var_dump(compact('readBack'));
    $readBack = null;
}
twice();

class Page {
    public $name = 'page';

    public function render() {
        return include __DIR__ . '/include-scope.inc';
    }

    public static function make() {
        return new static();
    }
}
class SubPage extends Page {
}
var_dump(SubPage::make()->render());

$fn = function () {
    $local = 41;
    include __DIR__ . '/include-scope.inc';
    return $local;
};
var_dump($fn());

function versioned() {
    static $version;
    if (!isset($version)) {
        require __DIR__ . '/include-scope-version.inc';
    }
    return $version;
}
var_dump(versioned(), versioned());

// at the top level the variables are the globals
$local = 100;
include __DIR__ . '/include-scope.inc';
var_dump($local, $created, $shared, $GLOBALS['extracted']);
