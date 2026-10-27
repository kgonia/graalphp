<?php
// Stream contexts, default_socket_timeout and the socket functions where no server is needed: a refused port, a
// name that doesn't resolve, addresses that don't parse, and the socket functions on streams that aren't sockets.
// The rest runs against servers in tests/gate2/http.sh sockets.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});

function t($label, $f) {
    echo "== $label\n";
    try {
        var_dump($f());
    } catch (Throwable $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
    }
}

// fopen() makes the default context first: its resource takes an id
$f = fopen(__FILE__, 'r');
var_dump($f, stream_context_get_options($f), stream_context_get_default());

t('refused', function () {
    $s = stream_socket_client('tcp://127.0.0.1:1', $no, $str, 2);
    return [$s, $no, $str];
});
t('refused fsockopen', function () {
    $s = fsockopen('127.0.0.1', 1, $no, $str, 2);
    return [$s, $no, $str];
});
t('refused, the port in the name', function () {
    $s = fsockopen('127.0.0.1:1', -1, $no, $str, 2);
    return [$s, $no, $str];
});
t('unresolvable', function () {
    $s = stream_socket_client('tcp://nonexistent.invalid:80', $no, $str, 2);
    return [$s, $no, $str];
});
t('unresolvable fsockopen', function () {
    $s = fsockopen('nonexistent.invalid', 80, $no, $str, 2);
    return [$s, $no, $str];
});
t('unknown transport', fn() => [stream_socket_client('foo://x:1', $no, $str, 1), $no, $str]);
t('no port', fn() => [stream_socket_client('tcp://127.0.0.1', $no, $str, 1), $no, $str]);
t('no port fsockopen', fn() => [fsockopen('127.0.0.1', -1, $no, $str, 1), $no, $str]);
t('bad IPv6', fn() => [stream_socket_client('tcp://[::1', $no, $str, 1), $no, $str]);
t('null byte', fn() => [stream_socket_client("tcp://a\0b:1", $no, $str, 1), $no, $str]);
t('quoted host', fn() => [stream_socket_client("tc'p://x:1", $no, $str, 1), $no, $str]);
t('fsockopen timeout', fn() => fsockopen('127.0.0.1', 1, $no, $str, -2));
t('stream_socket_client timeout', fn() => stream_socket_client('tcp://127.0.0.1:1', $no, $str, INF));
t('set_timeout on a file', fn() => stream_set_timeout(fopen(__FILE__, 'r'), 1));
t('set_timeout on memory', fn() => stream_set_timeout(fopen('php://memory', 'r'), 1));
t('set_blocking on a file', fn() => stream_set_blocking(fopen(__FILE__, 'r'), false));
t('get_name on a file', fn() => stream_socket_get_name(fopen(__FILE__, 'r'), false));
t('enable_crypto on a file', fn() => stream_socket_enable_crypto(fopen(__FILE__, 'r'), true,
    STREAM_CRYPTO_METHOD_TLS_CLIENT));
t('disable crypto on a file', fn() => stream_socket_enable_crypto(fopen(__FILE__, 'r'), false));
t('enable_crypto without a method', fn() => stream_socket_enable_crypto(fopen(__FILE__, 'r'), true));
t('contexts', function () {
    $options = ['http' => ['method' => 'GET']];
    $c = stream_context_create($options);
    stream_context_set_option($c, 'ssl', 'verify_peer', false);
    stream_context_set_options($c, ['http' => ['header' => 'X: y'], 'ssl' => [0 => 'a', 'b' => 1]]);
    $got = stream_context_get_options($c);
    $got['http']['method'] = 'POST';
    return [$options, stream_context_get_options($c), get_resource_type($c)];
});
t('options not of the form', fn() => stream_context_create(['http' => 1]));
t('options in a list', fn() => stream_context_create([['a' => 1]]));
t('a wrapper\'s options in a list', fn() => stream_context_get_options(stream_context_create(['http' => [1, 2]])));
t('set_option with two arguments', function () {
    $c = stream_context_create();
    return [stream_context_set_option($c, ['a' => ['b' => 1]]), stream_context_get_options($c)];
});
t('set_option without a name', fn() => stream_context_set_option(stream_context_create(), 'a'));
t('set_option with an array and a name', fn() => stream_context_set_option(stream_context_create(), ['a' => []],
    'x'));
t('set_option without a value', fn() => stream_context_set_option(stream_context_create(), 'a', 'b'));
t('params', fn() => stream_context_get_options(stream_context_create(null, ['options' => ['x' => ['y' => 2]]])));
t('a directory\'s context', fn() => stream_context_get_options(opendir(__DIR__)));
t('not a context', fn() => stream_context_get_options('x'));
t('default context', function () {
    $d = stream_context_get_default(['x' => ['y' => 1]]);
    $e = stream_context_set_default(['x' => ['z' => 2]]);
    return [$d === $e, stream_context_get_options(fopen(__FILE__, 'r')), stream_context_get_options($d)];
});
t('fopen with a string context', fn() => fopen(__FILE__, 'r', false, 'x'));
t('fopen with a stream as context', fn() => fopen(__FILE__, 'r', false, fopen(__FILE__, 'r')));
t('default_socket_timeout', function () {
    $values = [ini_get('default_socket_timeout'), ini_get_all('standard')['default_socket_timeout']];
    foreach (['5s', '0x10', '012', '1k', ' 7 ', 'abc', '0z', '1kk', '-1', '99999999999999999999', '0x', ''] as $v) {
        $values[] = [ini_set('default_socket_timeout', $v), ini_get('default_socket_timeout')];
    }
    ini_restore('default_socket_timeout');
    $values[] = ini_get('default_socket_timeout');
    return $values;
});
t('ini_get_all core', fn() => isset(ini_get_all('core')['default_socket_timeout']));
