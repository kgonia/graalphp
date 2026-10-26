<?php
// http_build_query (ext/standard/http.c): nesting, prefixes, separators, both encodings, objects, recursion.

class Point {
    public $x = 1;
    protected $hidden = 2;
    private $secret = 3;
    public $y = 'a b';
    public $none = null;
}

$data = ['a' => 'x y', 'b' => ['c' => 1, 'd' => [true, false]], 5 => 1.5, 'e' => null, 'f~' => 'g~h',
    'nested' => [['k' => 'v'], 7 => 'seven'], 'pt' => new Point, 'empty' => [], 'neg' => -3, 'f' => 0.1 + 0.2,
    "\xc3\xa9" => "\xe2\x82\xac", 'big' => 1e100];
echo http_build_query($data), "\n";
echo http_build_query($data, 'n_'), "\n";
echo http_build_query($data, 'n_', '&amp;'), "\n";
echo http_build_query($data, '', null, PHP_QUERY_RFC3986), "\n";
echo http_build_query([1, 2, [3, 4]], 'p'), "\n";
echo http_build_query([1, 2], '', ''), "\n";
echo http_build_query(new Point), "\n";
echo json_encode(http_build_query([])), json_encode(http_build_query(['x' => null, 'y' => []])), "\n";

$recursive = ['a' => 1];
$recursive['self'] = &$recursive;
echo http_build_query($recursive), "\n";
$o = new Point;
$o->x = $o;
echo http_build_query(['o' => $o]), "\n";

ini_set('arg_separator.output', ';');
echo http_build_query(['a' => 1, 'b' => 2]), ' ', ini_get('arg_separator.output'), "\n";
var_dump(ini_set('arg_separator.output', ''), ini_get('arg_separator.output'));
try {
    http_build_query('a=b');
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
