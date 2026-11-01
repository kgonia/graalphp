<?php
// bcrypt against PHP's crypt_blowfish.c, by many generated inputs: the $2a$, $2b$, $2x$ and $2y$ variants at costs
// 4 to 10, keys of every length up to 72 bytes and past it, with NUL bytes (a key is a C string: it ends there) and
// 8-bit bytes ($2x$'s sign-extension bug, $2a$'s safety measure), salts whose last character carries unused bits,
// and settings broken at every position. The inputs come from an MD5 stream, so both engines see the same bytes.

/** n bytes of the stream named $name. */
function md5Stream(string $name, int $n): string
{
    $out = '';
    for ($i = 0; strlen($out) < $n; $i++) {
        $out .= md5("$name/$i", true);
    }
    return substr($out, 0, $n);
}

const ALPHABET = './ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

function makeSalt(string $name): string
{
    $salt = '';
    foreach (str_split(md5Stream($name, 22)) as $byte) {
        $salt .= ALPHABET[ord($byte) & 63];
    }
    return $salt;
}

/** A key of $length bytes: plain ASCII, 8-bit, with NULs, or 0xff-heavy (the bytes $2a$'s safety looks at). */
function makeKey(string $name, int $length, int $kind): string
{
    $bytes = md5Stream($name, $length);
    if ($kind === 0) {
        return preg_replace_callback('/./s', fn($m) => chr(0x21 + ord($m[0]) % 94), $bytes);
    } elseif ($kind === 2) {
        return preg_replace_callback('/./s', fn($m) => ord($m[0]) < 40 ? "\0" : $m[0], $bytes);
    } elseif ($kind === 3) {
        return preg_replace_callback('/./s', fn($m) => ord($m[0]) < 128 ? "\xff" : $m[0], $bytes);
    }
    return $bytes;
}

$variants = ['2a', '2b', '2x', '2y'];
$lengths = [0, 1, 2, 3, 4, 5, 7, 8, 16, 31, 55, 56, 64, 71, 72, 73, 74, 100, 256];
$n = 0;
foreach ($lengths as $length) {
    foreach ([0, 1, 2, 3] as $kind) {
        foreach ($variants as $variant) {
            $key = makeKey("key/$length/$kind/$variant", $length, $kind);
            $setting = '$' . $variant . '$0' . (4 + $n % 3) . '$' . makeSalt("salt/$n");
            $hash = crypt($key, $setting);
            // password_verify reads its password as crypt() does: up to its first NUL
            printf("%s %s %s %d\n", bin2hex($key), $setting, $hash, password_verify($key, $hash));
            $n++;
        }
    }
}

// the costs WordPress and PHP's defaults use, and the range up to them
foreach ([4, 5, 6, 7, 8, 9, 10] as $cost) {
    foreach ($variants as $variant) {
        $setting = sprintf('$%s$%02d$%s', $variant, $cost, makeSalt("cost/$cost/$variant"));
        echo $setting, ' ', crypt(makeKey("cost/$cost/$variant", 40, 1), $setting), "\n";
    }
}

// WordPress's own key: base64 of an HMAC-SHA384 (wp_hash_password), at PHP's default cost
$key = base64_encode(hash_hmac('sha384', 'admin', 'wp-sha384', true));
echo crypt($key, '$2y$12$' . makeSalt('wordpress')), "\n";
var_dump(password_verify($key, crypt($key, '$2y$12$' . makeSalt('wordpress'))));

// the salt's last character: only its top two bits count, the rest are cleared in the output
foreach (str_split(ALPHABET) as $c) {
    echo $c, ' ', crypt('U*U', '$2b$04$abcdefghijklmnopqrstu' . $c), "\n";
}

// a valid setting broken at each position, with each of these bytes, and settings cut short or followed by more.
// At position 0 a salt character would make a DES setting (two of them, the '2' after it the second): not bcrypt.
$valid = '$2y$04$abcdefghijklmnopqrstuu';
foreach (range(0, strlen($valid) - 1) as $at) {
    foreach (["\0", '$', '!', '.', 'z', '9', '4', "\x80", "\xff"] as $byte) {
        if ($at === 0 && preg_match('#^[./0-9A-Za-z]$#', $byte)) {
            continue;
        }
        $setting = substr_replace($valid, $byte, $at, 1);
        echo $at, ' ', bin2hex($byte), ' ', crypt('password', $setting), "\n";
    }
}
foreach (range(0, strlen($valid)) as $length) {
    echo $length, ' ', crypt('password', substr($valid, 0, $length)), "\n";
}
foreach (['', 'x', 'U*U.ignored', '$2y$04$abcdefghijklmnopqrstuuMORE', "\$2y\$04\$abcdefghijklmnopqrstuu\0more"] as $tail) {
    echo json_encode($tail), ' ', crypt('password', $valid . $tail), "\n";
}
foreach (['$2y$4$abcdefghijklmnopqrstuu', '$2Y$04$abcdefghijklmnopqrstuu', '$2$04$abcdefghijklmnopqrstuu',
          '$2y$-1$abcdefghijklmnopqrstuu', '$2y$31x$abcdefghijklmnopqrstu', '$2y$ 4$abcdefghijklmnopqrstuu'] as $s) {
    echo $s, ' ', crypt('password', $s), "\n";
}
