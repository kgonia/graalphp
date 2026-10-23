<?php
// mysqli passes bytes through as mysqlnd does: what a query sends and what a result returns are the bytes of
// the session's character set, whatever they are (not necessarily valid UTF-8), and a value is its column's
// text as the server sent it (zero dates included). Run by tests/gate2/mysqli.sh on both engines.
$db = mysqli_connect('host.docker.internal', 'wp', 'wp', 'wordpress', 3307);

function show($db, $sql) {
    $result = mysqli_query($db, $sql);
    if ($result === false) {
        echo "error: ", mysqli_error($db), "\n";
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

mysqli_query($db, 'DROP TABLE bytes_test');
