<?php
// setcookie()/setrawcookie(): php-src's checks of the name, raw value, path, domain, expires and options. (php-cli
// sends no headers, so the calls that pass return true; buffering keeps the headers unsent, as under a server.)
ob_start();

function attempt(string $label, callable $call): void
{
    try {
        $result = $call();
        echo $label, ": ", var_export($result, true), "\n";
    } catch (ValueError | ArgumentCountError | TypeError $e) {
        echo $label, ": ", get_class($e), ": ", $e->getMessage(), "\n";
    }
}

attempt('plain', fn() => setcookie('name', 'value'));
attempt('no value', fn() => setcookie('name'));
attempt('empty value', fn() => setrawcookie('name', ''));
attempt('encoded value', fn() => setcookie('name', "a b;c,d\t\n"));
attempt('all positional', fn() => setcookie('name', 'v', 1700000000, '/path', 'example.com', true, true));
attempt('empty name', fn() => setcookie(''));
attempt('raw empty name', fn() => setrawcookie('', 'v'));
foreach (['=', ',', ';', ' ', "\t", "\r", "\n", "\013", "\014"] as $c) {
    attempt('name ' . json_encode($c), fn() => setcookie("a{$c}b", 'v'));
}
foreach ([',', ';', ' ', "\t", "\r", "\n", "\013", "\014", '='] as $c) {
    attempt('raw value ' . json_encode($c), fn() => setrawcookie('n', "a{$c}b"));
}
// past a NUL the checks stop (strpbrk), and the header op refuses the line
attempt('name after NUL', fn() => @setcookie("a\0;b", 'v'));
attempt('samesite newline', fn() => @setcookie('n', 'v', ['samesite' => "Lax\r\nX-Injected: 1"]));
attempt('path', fn() => setcookie('n', 'v', 0, '/a;Domain=evil.example'));
attempt('path space', fn() => setcookie('n', 'v', 0, '/a b'));
attempt('domain', fn() => setcookie('n', 'v', 0, '/', "example.com\r\nX-Injected: 1"));
attempt('domain option', fn() => setcookie('n', 'v', ['domain' => 'a,b']));
attempt('path option', fn() => setrawcookie('n', 'v', ['path' => "/\t"]));
attempt('expires 9999', fn() => setcookie('n', 'v', 253402300799));
attempt('expires 10000', fn() => setcookie('n', 'v', 253402300800));
attempt('expires option 10000', fn() => setcookie('n', 'v', ['expires' => 253402300800]));
attempt('negative expires', fn() => setcookie('n', 'v', -5));
attempt('options', fn() => setcookie('n', 'v', ['expires' => 1700000000, 'path' => '/', 'domain' => 'example.com',
    'secure' => true, 'httponly' => true, 'samesite' => 'Strict']));
attempt('options case', fn() => setcookie('n', 'v', ['Expires' => 0, 'PATH' => '/', 'SameSite' => 'Lax']));
attempt('partitioned', fn() => setcookie('n', 'v', ['partitioned' => true, 'secure' => true]));
attempt('partitioned without secure', fn() => setcookie('n', 'v', ['partitioned' => true]));
attempt('unknown option', fn() => setcookie('n', 'v', ['max-age' => 5]));
attempt('raw unknown option', fn() => setrawcookie('n', 'v', ['Bogus' => 5]));
attempt('numeric key', fn() => setcookie('n', 'v', [0 => 'x']));
attempt('options and more', fn() => setcookie('n', 'v', [], '/'));
attempt('samesite anything', fn() => setcookie('n', 'v', ['samesite' => 'a;b']));
attempt('bad name, bad path', fn() => setrawcookie('a=b', 'c;d', 0, ';'));
attempt('bad value, bad path', fn() => setrawcookie('ab', 'c;d', 0, ';'));
attempt('bad path, bad domain', fn() => setrawcookie('ab', 'cd', 0, ';', ';'));
var_dump(headers_list());

ob_end_flush();
