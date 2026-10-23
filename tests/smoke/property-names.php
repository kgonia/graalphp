<?php
// Property names are byte strings: a non-ASCII or invalid UTF-8 name is the same property whether it is written
// in the source, computed, decoded from JSON or unserialized, and comes back with its bytes in every listing.
$o = new stdClass;
$o->café = 1;
$n = 'café';
var_dump($o->{'café'}, $o->$n, isset($o->$n), property_exists($o, 'café'));
$p = (object)['café' => 2, "x\xFF" => 3];
var_dump($p->café, $p->{"x\xFF"}, (array)$p, json_encode($p, JSON_INVALID_UTF8_SUBSTITUTE));
var_dump(json_decode('{"café":4}')->café, unserialize(serialize($p))->café, get_object_vars($p));
foreach ($p as $k => $v) echo bin2hex($k), "\n";
class K { public $ñ = 5; }
$k = new K; $m = 'ñ';
var_dump($k->ñ, $k->$m, (array)$k, get_object_vars($k));
$k->$m = 6; var_dump($k->ñ);
unset($p->{"x\xFF"}); var_dump(count((array)$p));
class M
{
    public static $größe = 7;

    public function __construct(public $été = 8)
    {
    }

    public function __get($name)
    {
        return bin2hex($name);
    }
}
$m = new M;
$q = "\xC3\xA9t\xC3\xA9";
var_dump(M::$größe, $m->été, $m->$q, $m->{"no\xFFpe"}, $m->ünknown);
var_export($p);
echo "\n";
print_r($p);
var_dump(serialize($p), unserialize(serialize($p)) == $p, (array)unserialize(serialize($p)));
