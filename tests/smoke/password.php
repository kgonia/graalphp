<?php
// bcrypt (crypt_blowfish.c) through crypt() with fixed salts, and password_hash() and its family (argon2 aside:
// Truffle PHP has no argon2 yet).
set_error_handler(function ($type, $message) {
    echo "error $type: $message\n";
    return true;
});

$salt = 'abcdefghijklmnopqrstuu';
$keys = ['', 'U*U', 'password', "8b \xd0\xc1\xd2\xcf\xcc\xd8", "\xff\xa3" . '34' . "\xff\xff\xff\xa3" . '345',
    "\xa3", "\xff\xa3" . '345', str_repeat('0123456789', 8), "pass\0word", "\xd1\x91"];
foreach (['2a', '2b', '2x', '2y'] as $variant) {
    foreach ($keys as $key) {
        echo $variant, ' ', bin2hex($key), ' ', crypt($key, '$' . $variant . '$05$' . $salt), "\n";
    }
}

// the salt's last character carries two bits; settings crypt_blowfish refuses
echo crypt('x', '$2y$04$abcdefghijklmnopqrstuz'), "\n";
foreach (['$2y$03$' . $salt, '$2y$32$' . $salt, '$2c$05$' . $salt, '$2y$05$abc', '$2y$05$' . str_repeat('!', 22),
          '$2y$5$' . $salt, '*0', '*1', '', '$', '$2', "\$2y\$05\$abc\0defghijklmnopqrstuu", '$9$x'] as $setting) {
    echo json_encode($setting), ' ', crypt('x', $setting), "\n";
}

$hash = password_hash('secret', PASSWORD_DEFAULT, ['cost' => 4]);
var_dump(strlen($hash), substr($hash, 0, 7));
var_dump(password_verify('secret', $hash), password_verify('Secret', $hash), password_verify("secret\0x", $hash));
var_dump(password_get_info($hash), password_needs_rehash($hash, PASSWORD_BCRYPT, ['cost' => 4]));
var_dump(password_needs_rehash($hash, PASSWORD_BCRYPT), password_needs_rehash($hash, 'nope'));
var_dump(password_needs_rehash('$2a$04$' . $salt . str_repeat('a', 31), PASSWORD_BCRYPT, ['cost' => 4]));
var_dump(password_hash('x', null, ['cost' => '04'])[5], password_verify('x', password_hash('x', 1, ['cost' => 5])));
var_dump(password_get_info('$2y$04$short'), password_get_info('nothing'), password_get_info('$2y$'));
var_dump(in_array('2y', password_algos(), true), PASSWORD_BCRYPT, PASSWORD_DEFAULT, PASSWORD_BCRYPT_DEFAULT_COST);

// verify against crypt()'s hashes of every variant, and hashes made by other tools
$a = crypt('U*U', '$2a$05$CCCCCCCCCCCCCCCCCCCCC.');
var_dump($a, password_verify('U*U', $a), password_verify('U*U', substr($a, 0, 59)), password_verify('', '*0'));
var_dump(password_verify('rasmuslerdorf', '$2y$07$BCryptRequires22Chrcte/VlQH0piJtjXl.0t1XkA8pw9dMXTpOq'));

foreach ([fn() => password_hash("a\0b", PASSWORD_BCRYPT), fn() => password_hash('a', PASSWORD_BCRYPT, ['cost' => 3]),
          fn() => password_hash('a', PASSWORD_BCRYPT, ['cost' => 32]), fn() => password_hash('a', 'nope'),
          fn() => password_hash('a', 7), fn() => password_hash('a', [])] as $f) {
    try {
        $f();
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
echo strlen(password_hash('a', PASSWORD_BCRYPT, ['cost' => 4, 'salt' => 'ignored-salt-ignored-salt'])), "\n";
