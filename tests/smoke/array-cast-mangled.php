<?php
// (array) on an object and get_mangled_object_vars(): the property table, private and protected properties under
// their mangled names ("\0Class\0name", "\0*\0name"), declared ones in declaration order (a parent's first) then
// the dynamic ones, numeric names as int keys, a reference something else holds kept one.
set_error_handler(function ($type, $message, $file, $line) {
    echo "error $type: $message (line $line)\n";
    return true;
});
function keys(array $array)
{
    foreach ($array as $key => $value) {
        echo '  ', var_export(str_replace("\0", '~', (string) $key), true), ' (', gettype($key), ') => ',
            is_scalar($value) ? var_export($value, true) : gettype($value), "\n";
    }
}

class Base
{
    private $secret = 's';
    protected $shared = 'p';
    public $open = 'o';
}
#[AllowDynamicProperties]
class Derived extends Base
{
    private $own = 'd';
    protected $more = 'm';
}
$d = new Derived();
$d->{'12'} = 'numeric';
$d->extra = 'e';
unset($d->open);
$d->open = 'again';
echo "(array):\n";
keys((array) $d);
echo "get_mangled_object_vars:\n";
keys(get_mangled_object_vars($d));
var_dump(array_key_exists("\0*\0shared", (array) $d), array_key_exists('shared', (array) $d));
var_dump(((array) $d)["\0Base\0secret"]);

$o = new stdClass();
$o->a = 1;
$o->b = [1, 2];
$x = (array) $o;
$x['b'][] = 3;
var_dump(count($o->b));
$r = &$o->a;
$y = (array) $o;
$y['a'] = 'through the reference';
var_dump($o->a);
unset($r);

var_dump(get_mangled_object_vars(function () {
}));
try {
    get_mangled_object_vars([1]);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
