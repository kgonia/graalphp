<?php
$path = __DIR__ . '/data.txt';
file_put_contents($path, str_repeat("x", 200000));
$url = 'file:///' . ltrim(strtr($path, chr(92), '/'), '/');
$n = (int)($argv[1] ?? 3);
$fibers = [];
for ($i = 0; $i < $n; $i++) {
    $f = new Fiber(function () use ($url) {
        $ch = curl_init($url);
        curl_setopt($ch, CURLOPT_WRITEFUNCTION, function ($ch, $data) { Fiber::suspend(strlen($data)); return strlen($data); });
        curl_exec($ch);
        return "ok";
    });
    echo "start $i: ", $f->start(), "\n";
    $fibers[] = $f;
}
foreach ($fibers as $f) { while (!$f->isTerminated()) { $f->resume(); } }
echo "done\n";
