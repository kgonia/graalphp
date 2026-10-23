<?php
// mysqli as mysqlnd behaves: a query goes to the server as its bytes and a text column comes back as the bytes the
// server sent, in the session's character set (not necessarily valid UTF-8), a value is its column's text (zero
// dates included); mysqli_real_escape_string follows the connection's charset and NO_BACKSLASH_ESCAPES; errors
// are reported by mysqli_report()'s mode. Run by tests/gate2/mysqli.sh on both engines.

// PHP 8.1's default report mode throws mysqli_sql_exception
$db = mysqli_connect('host.docker.internal', 'wp', 'wp', 'wordpress', 3307);
try {
    mysqli_set_charset($db, 'klingon');
} catch (mysqli_sql_exception $e) {
    echo get_class($e), ' ', $e->getCode(), ' ', $e->getSqlState(), ' ', $e->getMessage(), "\n";
}
try {
    mysqli_query($db, 'SELEC 1');
} catch (mysqli_sql_exception $e) {
    echo get_class($e), ' ', $e->getCode(), ' ', $e->getSqlState(), "\n";
}
try {
    mysqli_connect('host.docker.internal', 'wp', 'wrong password', 'wordpress', 3307);
} catch (mysqli_sql_exception $e) {
    echo get_class($e), ' ', $e->getCode(), ' ', $e->getSqlState(), "\n";
}

// WordPress turns reporting off (wpdb): errors are return values
mysqli_report(MYSQLI_REPORT_OFF);
var_dump(mysqli_set_charset($db, 'klingon'), mysqli_errno($db), mysqli_error($db), mysqli_character_set_name($db));
var_dump(mysqli_query($db, 'SELEC 1'), mysqli_errno($db));

function show($db, $sql) {
    $result = mysqli_query($db, $sql);
    if ($result === false) {
        echo "error: ", mysqli_errno($db), "\n";
        return;
    }
    if ($result === true) {
        echo "ok, ", mysqli_affected_rows($db), " rows\n";
        return;
    }
    while ($row = mysqli_fetch_row($result)) {
        echo implode(' ', array_map(fn($v) => $v === null ? 'NULL' : bin2hex($v), $row)), "\n";
    }
}

mysqli_query($db, 'DROP TABLE IF EXISTS bytes_test');
mysqli_query($db, "CREATE TABLE bytes_test (id INT, l VARCHAR(20) CHARACTER SET latin1, u VARCHAR(20) CHARACTER SET utf8mb4,
    b VARBINARY(20), d DATETIME, t TEXT CHARACTER SET latin1)");

// utf8mb4 session: UTF-8 in, UTF-8 out; a latin1 column is converted both ways
mysqli_set_charset($db, 'utf8mb4');
show($db, "INSERT INTO bytes_test VALUES (1, 'caf\u{e9}', 'caf\u{e9} \u{1F600}', X'00FF80', '0000-00-00 00:00:00', 'a''b\\\\c')");
show($db, 'SELECT l, u, b, d, t FROM bytes_test WHERE id = 1');

// latin1 session, as on sites with DB_CHARSET latin1: one byte per character, not valid UTF-8
mysqli_set_charset($db, 'latin1');
show($db, 'SELECT l, u FROM bytes_test WHERE id = 1');
show($db, "INSERT INTO bytes_test (id, l, t) VALUES (2, 'caf\xE9', 'na\xEFve \\'q\\' \x80\x9F')");
show($db, 'SELECT l, t, LENGTH(l), LENGTH(t) FROM bytes_test WHERE id = 2');
show($db, "SELECT id FROM bytes_test WHERE l = 'caf\xE9'");

// back in utf8mb4, the latin1 text reads as UTF-8
mysqli_set_charset($db, 'utf8mb4');
show($db, 'SELECT l, t FROM bytes_test WHERE id = 2');

// a serialized option written and read in a latin1 session keeps its byte lengths
mysqli_set_charset($db, 'latin1');
$value = serialize(['name' => "Andr\xE9"]);
show($db, "INSERT INTO bytes_test (id, t) VALUES (3, '" . mysqli_real_escape_string($db, $value) . "')");
$row = mysqli_fetch_row(mysqli_query($db, 'SELECT t FROM bytes_test WHERE id = 3'));
var_dump(unserialize($row[0]) === ['name' => "Andr\xE9"]);

// latin1 bytes in a column name and in a comment reach the server as they are
show($db, "ALTER TABLE bytes_test ADD COLUMN `caf\xE9` INT");
show($db, "INSERT INTO bytes_test (id, `caf\xE9`) VALUES (4, 7) /* na\xEFve */");
show($db, "SELECT `caf\xE9` FROM bytes_test WHERE id = 4");
mysqli_query($db, 'DROP TABLE IF EXISTS auto_test');
mysqli_query($db, 'CREATE TABLE auto_test (id INT AUTO_INCREMENT PRIMARY KEY, v INT)');
mysqli_query($db, 'INSERT INTO auto_test (v) VALUES (1), (2)');
mysqli_query($db, 'INSERT INTO auto_test (v) VALUES (3)');
echo mysqli_insert_id($db), ' ', mysqli_affected_rows($db), "\n";
mysqli_query($db, 'DROP TABLE auto_test');

// escaping follows the connection's charset: a valid multibyte character is copied whole (its trail byte may be
// a quote or a backslash), a lead byte without its trail is escaped in the charsets whose lead bytes mysqlnd sees
$inputs = ["\xBF'", "\x81\\", "\xA1\x5C'", "\xC3'", "a\0b\n\r\x1A\"x", "\xE6\x97\xA5'", "\x8F\xA1\xA1'", "\x81\x30\x81\x30'"];
foreach (['gbk', 'sjis', 'big5', 'cp932', 'euckr', 'eucjpms', 'ujis', 'gb2312', 'gb18030', 'utf8', 'utf8mb4', 'latin1'] as $charset) {
    var_dump(mysqli_set_charset($db, $charset), mysqli_character_set_name($db));
    foreach ($inputs as $input) {
        echo $charset, ' ', bin2hex($input), ' -> ', bin2hex(mysqli_real_escape_string($db, $input)), "\n";
    }
}

// NO_BACKSLASH_ESCAPES: quotes are doubled, backslashes left alone, and the literal stays closed
mysqli_set_charset($db, 'utf8mb4');
mysqli_query($db, "SET SESSION sql_mode = CONCAT(@@sql_mode, ',NO_BACKSLASH_ESCAPES')");
$hostile = "\\' OR 1=1 -- ";
echo bin2hex(mysqli_real_escape_string($db, $hostile)), "\n";
show($db, "SELECT '" . mysqli_real_escape_string($db, $hostile) . "' AS v");
mysqli_query($db, "SET SESSION sql_mode = REPLACE(@@sql_mode, 'NO_BACKSLASH_ESCAPES', '')");
echo bin2hex(mysqli_real_escape_string($db, $hostile)), "\n";
show($db, "SELECT '" . mysqli_real_escape_string($db, $hostile) . "' AS v");

// MYSQLI_REPORT_INDEX: a query run without an index throws under STRICT
mysqli_report(MYSQLI_REPORT_ALL);
try {
    mysqli_query($db, 'SELECT id FROM bytes_test WHERE id = 1');
} catch (mysqli_sql_exception $e) {
    echo $e->getCode(), ' ', $e->getSqlState(), ' ', $e->getMessage(), "\n";
}
mysqli_report(MYSQLI_REPORT_OFF);

mysqli_query($db, 'DROP TABLE bytes_test');
