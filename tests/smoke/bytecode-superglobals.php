<?php
// Superglobals and $GLOBALS in functions on bytecode (BytecodeTranslator's RefPlace): superglobals read, written,
// compound assignments and increments, isset(), empty(), ??, ??=, unset(), elements under them (read, written,
// appended, unset, by reference), as arguments by value and by reference, references taken and bound, foreach and
// list() targets, and $_SESSION, which doesn't exist until written (reading it, unsetting an element of it or
// passing it by value doesn't create it). $GLOBALS: read as a whole (a copy), $GLOBALS['name'] and $GLOBALS[$name]
// read, written, compound, isset(), unset(), bound by reference, as arguments, and the order of evaluation.

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function sessionExists() {
    return array_key_exists('_SESSION', $GLOBALS);
}

function superReads() {
    show('arrays', [is_array($_GET), is_array($_POST), is_array($_COOKIE), is_array($_FILES), is_array($_SERVER),
        is_array($_REQUEST)]);
    show('server', [isset($_SERVER['argv']), is_array($_SERVER['argv']), $_SERVER['argc'] ?? null]);
    show('missing', [$_GET['missing'] ?? 'default', isset($_POST['x']), empty($_COOKIE), isset($_GET)]);
    show('session', [isset($_SESSION), empty($_SESSION), $_SESSION ?? 'none', $_SESSION['user'] ?? 'nobody',
        isset($_SESSION['user']), sessionExists()]);
}

function superWrites() {
    $_GET['a'] = 1;
    $_GET['n']['m'][] = 2;
    $_GET['n']['m'][] = 3;
    $_GET['count'] = 10;
    $_GET['count'] += 5;
    $_GET['count']++;
    $old = $_GET['count']--;
    --$_GET['count'];
    $_COOKIE['c'] = 'x';
    $_COOKIE['c'] .= 'y';
    $_REQUEST['r'] ??= 'first';
    $_REQUEST['r'] ??= 'second';
    $_POST = ['p' => 1];
    $_POST['q'] = $_POST;
    show('written', [$_GET, $old, $_COOKIE, $_REQUEST, $_POST]);
    show('element', [$_GET['n']['m'][1], isset($_GET['n']['m'][0]), isset($_GET['n']['x']), $_GET['n']['m'][5] ?? 'd']);
    $_FILES = 5;
    $_FILES += 2;
    $_FILES++;
    show('whole', $_FILES);
    $_FILES = [];
}

function superUnset() {
    unset($_SESSION['x']);
    unset($_SESSION['x']['y']);
    show('unset of a missing session', sessionExists());
    unset($_GET['a'], $_GET['n']['m'][0], $_GET['nope']['x']);
    show('unset elements', $_GET);
    unset($_COOKIE);
    show('unset whole', [isset($_COOKIE), array_key_exists('_COOKIE', $GLOBALS)]);
    $_COOKIE = [];
}

function addOne(&$list) {
    $list[] = is_array($list) ? count($list) : 0;
}

function byValue($value) {
    return $value;
}

function superArguments() {
    show('by value', [count($_GET), byValue($_GET), byValue($_GET['count']), byValue($_SESSION['k'] ?? 1)]);
    show('passing a missing session by value', sessionExists());
    addOne($_POST);
    addOne($_GET['list']);
    addOne($_GET['list']);
    $_GET['sorted'] = [3, 1, 2];
    sort($_GET['sorted']);
    $_REQUEST['stack'] = [];
    array_push($_REQUEST['stack'], 'pushed');
    show('by reference', [$_POST, $_GET['list'], $_GET['sorted'], $_REQUEST['stack']]);
}

function &sessionRef() {
    return $_SESSION;
}

function superReferences() {
    $r = &$_GET['count'];
    $r = 'through a reference';
    show('reference to an element', $_GET['count']);
    $session = &sessionRef();
    show('session created by a reference', [sessionExists(), $_SESSION]);
    $session = ['user' => 'u'];
    show('session written through it', $_SESSION);
    $whole = &$_REQUEST;
    $whole['added'] = true;
    show('reference to a superglobal', $_REQUEST);
    $data = ['bound' => 1];
    $_SESSION = &$data;
    $data['more'] = 2;
    show('superglobal bound', $_SESSION);
    $value = 'v';
    $_GET['bound'] = &$value;
    $value = 'changed';
    show('element bound', $_GET['bound']);
    $_POST = [1, 2];
    foreach ($_POST as &$item) {
        $item *= 10;
    }
    unset($item);
    foreach ($_GET['sorted'] as $k => &$item) {
        $item = -$item;
    }
    unset($item);
    show('foreach by reference', [$_POST, $_GET['sorted']]);
    $list = [&$_POST];
    show('array literal of a reference', $list);
}

function superTargets() {
    foreach (['a', 'b'] as $_GET['loop']) {
    }
    foreach (['k' => 'v'] as $_GET['key'] => $_POST) {
    }
    [$_COOKIE['x'], $_COOKIE['y']] = [1, 2];
    show('targets', [$_GET['loop'], $_GET['key'], $_POST, $_COOKIE]);
}

$g = 'global g';
$list = [3, 1];
$counter = 1;
$arr = ['k' => 'v'];

function globalsReads() {
    $globals = $GLOBALS;
    show('whole', [is_array($GLOBALS), isset($globals['g']), array_key_exists('counter', $GLOBALS)]);
    $copy = $GLOBALS;
    $copy['g'] = 'changed in the copy';
    show('a copy', $GLOBALS['g']);
    $name = 'g';
    show('read', [$GLOBALS['g'], $GLOBALS[$name], $GLOBALS['arr']['k'], $GLOBALS['missing'] ?? 'default',
        $GLOBALS['_GET']['loop'], $GLOBALS['list'][0]]);
    show('isset', [isset($GLOBALS), empty($GLOBALS), isset($GLOBALS['g']), isset($GLOBALS['missing']),
        isset($GLOBALS[$name]), isset($GLOBALS['arr']['k']), isset($GLOBALS['arr']['zz']), empty($GLOBALS['g'])]);
    $keys = [];
    foreach ($GLOBALS as $key => $unused) {
        if ($key === 'g' || $key === 'list') {
            $keys[] = $key;
        }
    }
    show('foreach', $keys);
}

function globalsWrites() {
    global $created, $counter, $arr;
    $GLOBALS['created'] = 'made through $GLOBALS';
    $name = 'computed';
    $GLOBALS[$name] = 'by a computed name';
    $GLOBALS['counter'] += 5;
    $GLOBALS['counter']++;
    $GLOBALS['g'] .= '!';
    $GLOBALS[$name] .= '!';
    $GLOBALS['fresh'] ??= 'fresh';
    $GLOBALS['fresh'] ??= 'not again';
    $GLOBALS['arr']['new'] = 1;
    $GLOBALS['arr']['list'][] = 'appended';
    $GLOBALS['arr']['new'] += 1;
    $GLOBALS['arr']['n'] ??= 'n';
    show('written', [$created, $counter, $arr, $GLOBALS['g'], $GLOBALS['computed'], $GLOBALS['fresh']]);
    unset($GLOBALS['created'], $GLOBALS[$name], $GLOBALS['arr']['list']);
    show('unset', [isset($GLOBALS['created']), isset($GLOBALS['computed']), $arr, $created]);
}

function globalsArguments() {
    addOne($GLOBALS['list']);
    $name = 'list';
    sort($GLOBALS[$name]);
    addOne($GLOBALS['arr']['args']);
    show('arguments', [$GLOBALS['list'], count($GLOBALS['list']), byValue($GLOBALS[$name]), $GLOBALS['arr']['args'],
        byValue($GLOBALS['notThere'] ?? null), array_key_exists('notThere', $GLOBALS)]);
}

function globalsReferences() {
    global $g, $bound;
    $r = &$GLOBALS['g'];
    $r = 'through a reference';
    $local = 'local';
    $GLOBALS['bound'] = &$local;
    $local = 'local changed';
    $name = 'other';
    $local2 = 'second';
    $GLOBALS[$name] = &$local2;
    show('references', [$g, $GLOBALS['bound'], $bound, $GLOBALS['other']]);
    foreach ($GLOBALS['list'] as &$item) {
        $item = $item * 2;
    }
    unset($item);
    $element = &$GLOBALS['arr']['refd'];
    $element = 'element';
    show('foreach by reference and an element', [$GLOBALS['list'], $GLOBALS['arr']['refd']]);
}

function globalsOrder() {
    $log = [];
    $key = function ($suffix = '') use (&$log) {
        $log[] = 'key';
        return 'ordered' . $suffix;
    };
    $value = function () use (&$log) {
        $log[] = 'value';
        return 'v';
    };
    $GLOBALS[$key()] = $value();
    $GLOBALS[$key()] .= $value();
    $GLOBALS[$key('Array')]['k'] = $value();
    show('order', [$log, $GLOBALS['ordered'], $GLOBALS['orderedArray']]);
}

superReads();
superWrites();
superUnset();
superArguments();
superReferences();
superTargets();
globalsReads();
globalsWrites();
globalsArguments();
globalsReferences();
$arrow = fn() => [$GLOBALS['g'], count($_GET) > 0];
show('arrow function at the top level', $arrow());
show('closure', (function () {
    return $GLOBALS['counter'] . ' ' . (isset($_SESSION) ? 'session' : 'no session');
})());
try {
    globalsOrder();
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
