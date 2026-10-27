<?php
// Run by tests/shared/PersistentShares.java on a thread that then ends: a persistent share that shares connections
// keeps the connection to the test's server open after the context closes, until the thread's end cleans the share
// up (php-src's GSHUTDOWN).
$share = curl_share_init_persistent([CURL_LOCK_DATA_CONNECT]);
$ch = curl_init($argv[1]);
curl_setopt($ch, CURLOPT_SHARE, $share);
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
echo curl_exec($ch), "\n";
