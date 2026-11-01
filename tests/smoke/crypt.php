<?php
// crypt() as php_crypt dispatches it: standard and extended DES (crypt_freesec.c), MD5 (php_crypt_r.c), bcrypt,
// SHA-256 and SHA-512 (crypt_sha256.c, crypt_sha512.c) with their known vectors, salt edge cases (short, invalid,
// NUL bytes, too long), the "*0"/"*1" failures, rounds= parsing and limits; the CRYPT_* constants; password_verify()
// and the rest of the password_* family on hashes of every kind.
foreach (['CRYPT_SALT_LENGTH', 'CRYPT_STD_DES', 'CRYPT_EXT_DES', 'CRYPT_MD5', 'CRYPT_BLOWFISH', 'CRYPT_SHA256',
        'CRYPT_SHA512'] as $c) {
    echo $c, ' ', var_export(constant($c), true), "\n";
}

function show(string $password, string $salt): void {
    $hash = crypt($password, $salt);
    echo json_encode(bin2hex($salt)), ' ', strlen($hash), ' ', bin2hex($hash), "\n";
}

// crypt_freesec.c's own tests: [hash, password]
$freesec = [
    ['_J9..CCCCXBrJUJV154M', 'U*U*U*U*'], ['_J9..CCCCXUhOBTXzaiE', 'U*U***U'], ['_J9..CCCC4gQ.mB/PffM', 'U*U***U*'],
    ['_J9..XXXXvlzQGqpPPdk', '*U*U*U*U'], ['_J9..XXXXsqM/YSSP..Y', '*U*U*U*U*'],
    ['_J9..XXXXVL7qJCnku0I', '*U*U*U*U*U*U*U*U'], ['_J9..XXXXAj8cFbP5scI', '*U*U*U*U*U*U*U*U*'],
    ['_J9..SDizh.vll5VED9g', 'ab1234567'], ['_J9..SDizRjWQ/zePPHc', 'cr1234567'],
    ['_J9..SDizxmRI1GjnQuE', 'zxyDPWgydbQjgq'], ['_K9..SaltNrQgIYUAeoY', '726 even'], ['_J9..SDSD5YGyRCr4W4c', ''],
    ['CCNf8Sbh3HDfQ', 'U*U*U*U*'], ['CCX.K.MFy4Ois', 'U*U***U'], ['CC4rMpbg9AMZ.', 'U*U***U*'],
    ['XXxzOu6maQKqQ', '*U*U*U*U'], ['SDbsugeBiC58A', ''], ['./xZjzHv5vzVE', 'password'], ['0A2hXM1rXbYgo', 'password'],
    ['A9RXdR23Y.cY6', 'password'], ['ZziFATVXHo2.6', 'password'], ['zZDDIZ0NOlPzw', 'password'],
    ["\001\002wyd0KZo65Jo", 'password'], ['a_C10Dk/ExaG.', 'password'], ["~\377.5OTsRVjwLo", 'password'],
    ['', ''], [' ', ''], ['a:', ''], ["\na", ''], ['_/......', ''], ['_........', ''], ['_/!......', ''],
    ['_/......!', ''],
];
foreach ($freesec as [$hash, $password]) {
    $result = crypt($password, $hash);
    echo json_encode(bin2hex($hash)), ' ', $result === $hash ? 'same' : bin2hex($result), "\n";
}

// standard DES: only 8 characters of the key count; the salt is any 2 of [./0-9A-Za-z]
show('rasmuslerdorf', 'rl');
show('rasmusle', 'rl');
show('rasmuslerdorf', 'rlanything after the salt');
show('', '..');
show('zz', 'zz');
show("\xff\x80\x7f", '9/');
show('À1234abcd', '99');
show('À9234abcd', '99');
show("ab\0cd", 'ab');
show('ab', 'ab');
foreach (['r', 'r!', '!r', 'r$', 'r_', '_', "r\0l", "\0rl", 'r:', "r\n", "\xc3\xa9x", 'x' . "\xc3\xa9", '*', '**',
        '*0', '*1', '*2', '*0abc', '*1abc', "*\0", '$', '$$', '$1', '$5', '$6', '$2', '$2a', '$2a$', '$3$abc',
        '$x$abc', '$0$', '$7$', '$y$j9T$', '$md5$abc', '$sha1$abc'] as $salt) {
    show('password', $salt);
}

// extended DES: "_", 4 characters of count, 4 of salt; a key of any length
show('rasmuslerdorf', '_J9..rasm');
show('À1234abcd', '_01234567');
show('À9234abcd', '_01234567');
show('a very long password that is longer than eight characters', '_J9..rasm');
show('a very long password that is longer than eight characters!', '_J9..rasm');
show('pw', '_J9..rasmTRAILING');
show('pw', '_/...ab..');
show('pw', '_zzzzabcd');
show('pw', '_..../...');
foreach (['_', '_J', '_J9', '_J9.', '_J9..', '_J9..r', '_J9..ra', '_J9..ras', "_J9..ras\0", "_J9.\0rasm", '_....rasm',
        '_J9..ra!m', '_J!..rasm', "_J9..ras\xff", "_\xffJ9.rasm", '_J9$.rasm', '__J9.rasm'] as $salt) {
    show('password', $salt);
}

// MD5: up to 8 characters of salt, ending at '$'
show('rasmuslerdorf', '$1$rasmusle$');
show('rasmuslerdorf', '$1$rasmusle');
show('rasmuslerdorf', '$1$rasmuslerdorf$');
show('rasmuslerdorf', '$1$');
show('rasmuslerdorf', '$1$$');
show('rasmuslerdorf', '$1$$abc');
show('rasmuslerdorf', '$1$a$b$c');
show('rasmuslerdorf', '$1$ab:cd');
show('rasmuslerdorf', "$1$\xff\x80\x01");
show('rasmuslerdorf', '$1$ab' . "\0cd");
show('', '$1$salt$');
show(str_repeat('x', 15), '$1$salt$');
show(str_repeat('x', 16), '$1$salt$');
show(str_repeat('x', 17), '$1$salt$');
show(str_repeat('xyz', 100), '$1$salt$');
show("pass\0word", '$1$salt$');
show('pass', '$1$salt$');

// SHA-256 and SHA-512: [salt, password]
$sha = [
    ['$5$saltstring', 'Hello world!'],
    ['$5$rounds=10000$saltstringsaltstring', 'Hello world!'],
    ['$5$rounds=5000$toolongsaltstring', 'This is just a test'],
    ['$5$rounds=1400$anotherlongsaltstring', 'a very much longer text to encrypt.  This one even stretches over morethan one line.'],
    ['$5$rounds=77777$short', 'we have a short salt string but not a short password'],
    ['$5$rounds=1000000000$roundstoohigh', 'the number of rounds is too high'],
    ['$5$rounds=10$roundstoolow', 'the number of rounds is too low'],
    ['$6$saltstring', 'Hello world!'],
    ['$6$rounds=10000$saltstringsaltstring', 'Hello world!'],
    ['$6$rounds=5000$toolongsaltstring', 'This is just a test'],
    ['$6$rounds=1400$anotherlongsaltstring', 'a very much longer text to encrypt.  This one even stretches over morethan one line.'],
    ['$6$rounds=77777$short', 'we have a short salt string but not a short password'],
    ['$6$$bar$', 'foo'],
    ['$6$rounds=10$roundstoolow', 'the number of rounds is too low'],
    ['$6$rounds=1000000000$roundstoohigh', 'the number of rounds is too high'],
];
foreach ($sha as [$salt, $password]) {
    var_dump(crypt($password, $salt));
}
foreach (['5', '6'] as $id) {
    foreach (['', '$', 'a', 'abc$def', 'sixteencharssalt', 'seventeencharsalt', "ab\0cd", "\xff\xfe", 'a b:c',
            'rounds=999$s', 'rounds=1000$s', 'rounds=1001$s', 'rounds=01000$s', 'rounds= 1000$s', "rounds=\t1000\$s",
            'rounds=+1000$s', 'rounds=-1000$s', 'rounds=-0$s', 'rounds=0$s', 'rounds=$s', 'rounds=1000', 'rounds=1000x$s',
            'rounds=x$s', 'rounds=18446744073709551616$s', 'rounds=18446744073709552000$s', 'rounds=4294968296$s',
            'rounds=1000$', 'rounds=1000$$', 'rounds=1000$rounds=2000$s', 'rounds=1000$sixteencharssaltX',
            'ROUNDS=1000$s', 'rounds:1000$s', 'rounds=1000$a$b'] as $rest) {
        show('password', '$' . $id . '$' . $rest);
    }
    show('', '$' . $id . '$salt');
    show(str_repeat('p', 31), '$' . $id . '$salt');
    show(str_repeat('p', 32), '$' . $id . '$salt');
    show(str_repeat('p', 33), '$' . $id . '$salt');
    show(str_repeat('p', 64), '$' . $id . '$salt');
    show(str_repeat('p', 65), '$' . $id . '$salt');
    show(str_repeat('p', 200), '$' . $id . '$salt');
    show("pass\0word", '$' . $id . '$salt');
}
// salts beyond PHP_MAX_SALT_LEN (123) are cut there
foreach ([120, 121, 122, 123, 124, 4096] as $n) {
    show('A', '$5$' . str_repeat('A', $n));
    show('A', '$1$' . str_repeat('A', $n));
    show('A', 'AB' . str_repeat('A', $n));
}

// bcrypt still dispatches
var_dump(crypt('rasmuslerdorf', '$2a$07$rasmuslerd............'));
var_dump(crypt('U*U', '$2a$CCCCCCCCCCCCCCCCCCCCC.E5YPO9kmyuRGyh0XouQYb4YMJKvyOeW'));

// password_verify() checks every hash php_crypt knows
$hashes = ['rl.3StKT.4T8M', '_J9..rasmBYk8r9AiWNc', '$1$rasmusle$rISCgZzpwk3UhDidwXvin0',
    crypt('rasmuslerdorf', '$5$rounds=2000$abc'), crypt('rasmuslerdorf', '$6$salt'), crypt('rasmuslerdorf', '$5$'),
    '$2a$07$rasmuslerd............nIdrcHdxcUxWomQX9j6kvERCFjTg7Ra'];
foreach ($hashes as $hash) {
    echo $hash, ' ', var_export(password_verify('rasmuslerdorf', $hash), true), ' ',
        var_export(password_verify('rasmuslerdorF', $hash), true), ' ',
        var_export(password_verify('rasmuslerdorf', $hash . 'x'), true), ' ',
        var_export(password_verify("rasmuslerdorf\0x", $hash), true), ' ',
        json_encode(password_get_info($hash)), ' ', var_export(password_needs_rehash($hash, PASSWORD_DEFAULT), true),
        "\n";
}
var_dump(password_verify('rasmusle', 'rl.3StKT.4T8M'));
var_dump(password_verify('rasmuslerdorfxyz', 'rl.3StKT.4T8M'));
var_dump(password_verify('rasmuslerdorf', 'rl.3StKT.4T8'));
var_dump(password_verify('rasmuslerdorf', 'rlanything'));
var_dump(password_verify('', '*0'));
var_dump(password_verify('', '*1'));
var_dump(password_verify('', 'SDbsugeBiC58A'));
var_dump(password_verify('', '$1$'));
var_dump(password_verify('', crypt('', '$1$')));
var_dump(password_verify('the number of rounds is too high', '$5$rounds=1000000000$roundstoohigh'));
var_dump(password_verify('rasmuslerdorf', "rl.3StKT.4T8M\0"));
var_dump(password_verify('rasmuslerdorf', "rl\0.3StKT.4T8M"));
var_dump(password_verify('password', crypt('password', 'rounds')));

try {
    crypt('x');
} catch (ArgumentCountError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    crypt('x', []);
} catch (TypeError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
var_dump(crypt(123, 45));
var_dump(crypt(1.5, 'ab'));
