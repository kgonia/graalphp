<?php
// Run by tests/shared/run.sh in many contexts at once on one engine, with a small heap so that the GC runs often:
// curl handles are dropped while transfers, callbacks and error handlers run, the Cleaner's thread queues them, and
// each context frees them at its next curl call (models/curl, wiki/tla-curl.md). Every transfer must complete and
// every curl_multi_add_handle succeed, as on php-src, whose call frames and handles hold what they use.
$url = 'file://' . (DIRECTORY_SEPARATOR === '\\' ? '/' : '') . str_replace('\\', '/', __FILE__);
$size = filesize(__FILE__);
$tmp = tempnam(sys_get_temp_dir(), 'curl-lifecycle');

/** Garbage, and curl calls that free what the Cleaner queued. */
function churn()
{
    for ($i = 0; $i < 4; $i++) {
        $junk = str_repeat('x', 100000);
        curl_init();
    }
}

set_error_handler(function () {
    // curl_multi_add_handle's warning: its call still holds the multi handle
    unset($GLOBALS['mh']);
    churn();
    return true;
});

$bad = 0;
for ($round = 0; $round < 100; $round++) {
    // a multi whose easies (one a copy) share a share handle; their callbacks drop the easies' variables
    $mh = curl_multi_init();
    $sh = curl_share_init();
    curl_share_setopt($sh, CURLSHOPT_SHARE, CURL_LOCK_DATA_DNS);
    $got = [0, 0, 0];
    $chs = [];
    for ($j = 0; $j < 3; $j++) {
        $ch = $j < 2 ? curl_init($url) : curl_copy_handle($chs[0]);
        curl_setopt($ch, CURLOPT_SHARE, $sh);
        curl_setopt($ch, CURLOPT_WRITEFUNCTION, function ($handle, $data) use (&$got, &$chs, $j) {
            $got[$j] += strlen($data);
            unset($chs[$j]);
            churn();
            return strlen($data);
        });
        curl_multi_add_handle($mh, $ch);
        $chs[$j] = $ch;
    }
    unset($ch, $sh);
    do {
        curl_multi_exec($mh, $running);
    } while ($running);
    foreach ($got as $bytes) {
        if ($bytes !== $size) {
            $bad++;
        }
    }
    unset($mh, $chs);

    // the error handler of curl_multi_add_handle's warning drops the multi handle passed to it
    $GLOBALS['mh'] = curl_multi_init();
    $ch = curl_init($url);
    $fp = fopen($tmp, 'w');
    curl_setopt($ch, CURLOPT_FILE, $fp);
    fclose($fp);
    if (curl_multi_add_handle($GLOBALS['mh'], $ch) !== 0) {
        $bad++;
    }

    // curl_exec's callback drops the only variable holding the handle
    $GLOBALS['easy'] = curl_init($url);
    $seen = 0;
    curl_setopt($GLOBALS['easy'], CURLOPT_WRITEFUNCTION, function ($handle, $data) use (&$seen) {
        unset($GLOBALS['easy']);
        churn();
        $seen += strlen($data);
        return strlen($data);
    });
    if (curl_exec($GLOBALS['easy']) !== true || $seen !== $size) {
        $bad++;
    }

    // a persistent share: the thread's own, used by the contexts that run on it one after another
    $persistent = curl_share_init_persistent([CURL_LOCK_DATA_DNS, CURL_LOCK_DATA_CONNECT]);
    $ch = curl_init($url);
    curl_setopt($ch, CURLOPT_SHARE, $persistent);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    if (strlen(curl_exec($ch)) !== $size) {
        $bad++;
    }
    unset($ch, $persistent);
}
unlink($tmp);
echo $bad === 0 ? "curl-lifecycle ok" : "curl-lifecycle: $bad failures", "\n";
