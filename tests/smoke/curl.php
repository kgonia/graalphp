<?php
// ext/curl over libcurl: file:// transfers (to the returned string, the output, callbacks, PHP streams), uploads,
// curl_multi, copies and resets, errors (libcurl's codes, curl_setopt's ValueErrors and TypeErrors), the handle
// classes, CURLFile and CURLStringFile, constants. HTTP goes through WordPress's probe (tests/gate2).
$dir = str_replace('\\', '/', __DIR__);
// file:///C:/... when Truffle PHP runs the smoke tests on a Windows host (libcurl shows it as file://C:/...)
$base = 'file://' . ($dir[0] === '/' ? '' : '/') . $dir;
ob_start(function ($text) use ($dir, $base) {
    return str_replace([$base, 'file://' . $dir, __DIR__ . "\\", $dir . '/'], ['BASE', 'BASE', 'DIR/', 'DIR/'],
        $text);
});
$tmp = sys_get_temp_dir() . '/truffle-smoke-curl-' . bin2hex(random_bytes(4));
mkdir($tmp);
$tmpUrl = 'file://' . (str_starts_with(str_replace('\\', '/', $tmp), '/') ? '' : '/') . str_replace('\\', '/', $tmp);
$data = "$base/curl.php";
$size = filesize(__FILE__);

/** An object without its id (php-src reuses the ids of freed objects). */
function show($value)
{
    echo get_class($value), ' ', json_encode(get_object_vars($value), JSON_UNESCAPED_SLASHES), "\n";
}

function attempt(callable $f)
{
    try {
        var_dump($f());
    } catch (Throwable $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
    }
}

echo "-- the extension\n";
var_dump(extension_loaded('curl'), function_exists('curl_multi_get_handles'), class_exists('CurlHandle'),
    class_exists('CURLStringFile'));
var_dump(CURLOPT_URL, CURLOPT_RETURNTRANSFER, CURLOPT_HEADERFUNCTION, CURLE_OK, CURLE_WRITE_ERROR,
    CURLE_COULDNT_CONNECT, CURLINFO_HTTP_CODE, CURLINFO_PRIVATE, CURLINFO_HEADER_OUT, CURLM_CALL_MULTI_PERFORM,
    CURLMSG_DONE, CURLPROTO_HTTPS, CURL_HTTP_VERSION_1_1, CURL_VERSION_SSL, CURLOPT_SAFE_UPLOAD,
    CURL_LOCK_DATA_DNS, CURLSHOPT_SHARE, CURLPAUSE_ALL, CURL_PUSH_DENY);
$version = curl_version();
var_dump(array_keys($version), is_int($version['version_number']), ($version['features'] & CURL_VERSION_SSL) !== 0,
    in_array('file', $version['protocols']), in_array('https', $version['protocols']));

echo "-- a transfer into a string\n";
$ch = curl_init($data);
show($ch);
var_dump((int) $ch === spl_object_id($ch));
var_dump(curl_setopt($ch, CURLOPT_RETURNTRANSFER, true));
$body = curl_exec($ch);
var_dump(strlen($body) === $size, substr($body, 0, 5), curl_errno($ch), curl_error($ch));
echo curl_getinfo($ch, CURLINFO_EFFECTIVE_URL), "
";
var_dump(curl_getinfo($ch, CURLINFO_RESPONSE_CODE),
    curl_getinfo($ch, CURLINFO_SIZE_DOWNLOAD) == $size, curl_getinfo($ch, CURLINFO_CONTENT_TYPE),
    curl_getinfo($ch, CURLINFO_SCHEME), curl_getinfo($ch, CURLINFO_PROTOCOL) === CURLPROTO_FILE,
    curl_getinfo($ch, CURLINFO_EFFECTIVE_METHOD), curl_getinfo($ch, CURLINFO_HEADER_OUT), curl_getinfo($ch, 12345));
$info = curl_getinfo($ch);
foreach (['url', 'content_type', 'http_code', 'header_size', 'request_size', 'redirect_count', 'size_upload',
             'upload_content_length', 'primary_port', 'scheme', 'effective_method'] as $key) {
    echo $key, ' => ', var_export($info[$key], true), "\n";
}
var_dump($info['size_download'] == $size, $info['download_content_length'] == $size, is_float($info['total_time']),
    is_int($info['total_time_us']), array_key_exists('certinfo', $info), array_key_exists('request_header', $info));
var_dump(curl_setopt($ch, CURLOPT_PRIVATE, ['id' => 7]), curl_getinfo($ch, CURLINFO_PRIVATE));

echo "-- to the output, with headers\n";
$ch = curl_init("$base/curl-data/hello.txt");
var_dump(curl_exec($ch));
curl_setopt_array($ch, [CURLOPT_HEADER => true, CURLOPT_RETURNTRANSFER => true]);
$response = curl_exec($ch);
// file:// responses carry Content-Length, Accept-ranges and the file's Last-Modified
echo preg_replace('/^Last-Modified: .*\r\n/m', "Last-Modified: X\r\n", $response);
var_dump(curl_multi_getcontent($ch) === $response);

echo "-- callbacks\n";
$ch = curl_init("$base/curl-data/hello.txt");
$headers = [];
$chunks = [];
curl_setopt($ch, CURLOPT_HEADERFUNCTION, function ($handle, $header) use (&$headers, $ch) {
    $headers[] = str_starts_with($header, 'Last-Modified') ? 'Last-Modified' : $header;
    var_dump($handle === $ch);
    return strlen($header);
});
curl_setopt($ch, CURLOPT_WRITEFUNCTION, function ($handle, $chunk) use (&$chunks) {
    $chunks[] = $chunk;
    return strlen($chunk);
});
curl_setopt($ch, CURLOPT_NOBODY, true);
var_dump(curl_exec($ch), $headers, $chunks);
curl_setopt($ch, CURLOPT_NOBODY, false);
$headers = [];
var_dump(curl_exec($ch), $headers, $chunks);

class Sink
{
    public string $body = '';

    public function body($handle, string $data): int
    {
        $this->body .= $data;
        return strlen($data);
    }

    public static function none($handle, string $data): int
    {
        return 0;
    }
}
$sink = new Sink();
curl_setopt($ch, CURLOPT_WRITEFUNCTION, [$sink, 'body']);
curl_setopt($ch, CURLOPT_HEADERFUNCTION, null);
var_dump(curl_exec($ch), $sink->body);
// a callback returning less than it was given fails the transfer
curl_setopt($ch, CURLOPT_WRITEFUNCTION, 'Sink::none');
var_dump(curl_exec($ch), curl_errno($ch), curl_strerror(curl_errno($ch)));
// a callback that throws: the transfer ends, the callbacks after it don't run, curl_exec throws
$calls = 0;
$big = curl_init($data);
curl_setopt($big, CURLOPT_BUFFERSIZE, 1024);
curl_setopt($big, CURLOPT_WRITEFUNCTION, function ($handle, $chunk) use (&$calls) {
    $calls++;
    throw new RuntimeException("stop at $calls");
});
attempt(fn() => curl_exec($big));
var_dump($calls, curl_errno($big));
// curl_close and curl_reset can't run inside a callback
curl_setopt($big, CURLOPT_WRITEFUNCTION, function ($handle, $chunk) {
    curl_reset($handle);
});
attempt(fn() => curl_exec($big));
curl_setopt($big, CURLOPT_WRITEFUNCTION, function ($handle, $chunk) {
    @curl_close($handle);
});
attempt(fn() => curl_exec($big));
attempt(fn() => curl_setopt($big, CURLOPT_WRITEFUNCTION, 'no_such_function'));
attempt(fn() => curl_setopt($big, CURLOPT_PROGRESSFUNCTION, [new Sink(), 'nope']));
attempt(fn() => curl_setopt_array($big, [CURLOPT_HEADERFUNCTION => 42]));
// progress: called while the transfer runs, and a non-zero return aborts it
$progress = 0;
$ch = curl_init($data);
curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_NOPROGRESS => false,
    CURLOPT_XFERINFOFUNCTION => function ($handle, $dltotal, $dlnow, $ultotal, $ulnow) use (&$progress) {
        $progress++;
        return 0;
    }]);
var_dump(strlen(curl_exec($ch)) === $size, $progress > 0);
curl_setopt($ch, CURLOPT_XFERINFOFUNCTION, fn() => 1);
var_dump(curl_exec($ch), curl_errno($ch), curl_strerror(curl_errno($ch)));
$debug = [];
$ch = curl_init("$base/curl-data/hello.txt");
curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_VERBOSE => true,
    CURLOPT_DEBUGFUNCTION => function ($handle, $type, $text) use (&$debug) {
        $debug[$type] = true;
    }]);
var_dump(curl_exec($ch), isset($debug[CURLINFO_TEXT]));
attempt(fn() => curl_setopt($ch, CURLINFO_HEADER_OUT, true));

echo "-- PHP streams\n";
$out = fopen("$tmp/out.txt", 'w');
$ch = curl_init("$base/curl-data/hello.txt");
var_dump(curl_setopt($ch, CURLOPT_FILE, $out), curl_exec($ch));
fclose($out);
var_dump(file_get_contents("$tmp/out.txt"));
// the stream closed since: the handle writes to the output again
var_dump(curl_exec($ch));
$headerStream = fopen('php://memory', 'w+');
curl_setopt($ch, CURLOPT_WRITEHEADER, $headerStream);
curl_setopt($ch, CURLOPT_NOBODY, true);
var_dump(curl_exec($ch));
rewind($headerStream);
var_dump(substr(stream_get_contents($headerStream), 0, 18));
attempt(fn() => curl_setopt($ch, CURLOPT_FILE, fopen("$base/curl-data/hello.txt" === '' ? '' : __FILE__, 'r')));
attempt(fn() => curl_setopt($ch, CURLOPT_INFILE, 'not a stream'));
attempt(fn() => curl_setopt($ch, CURLOPT_WRITEHEADER, null));

echo "-- uploads\n";
$in = fopen('php://memory', 'w+');
fwrite($in, "uploaded through CURLOPT_INFILE\n");
rewind($in);
$ch = curl_init("$tmpUrl/upload.txt");
curl_setopt_array($ch, [CURLOPT_UPLOAD => true, CURLOPT_INFILE => $in, CURLOPT_INFILESIZE => 32]);
var_dump(curl_exec($ch), curl_getinfo($ch, CURLINFO_SIZE_UPLOAD), file_get_contents("$tmp/upload.txt"));
$parts = ["read ", "by ", "a callback\n"];
$ch = curl_init("$tmpUrl/callback.txt");
curl_setopt_array($ch, [CURLOPT_UPLOAD => true,
    CURLOPT_READFUNCTION => function ($handle, $stream, $length) use (&$parts) {
        var_dump($stream, $length > 0);
        return (string) array_shift($parts);
    }]);
var_dump(curl_exec($ch), file_get_contents("$tmp/callback.txt"));

echo "-- errors\n";
foreach (["$base/curl-data/missing.txt", 'nope://example', 'http://[', ''] as $url) {
    $ch = curl_init($url);
    var_dump(curl_exec($ch), curl_errno($ch), curl_strerror(curl_errno($ch)));
}
var_dump(curl_error(curl_init()), curl_strerror(CURLE_OK), curl_strerror(9999), curl_multi_strerror(CURLM_OK),
    curl_multi_strerror(9999), curl_share_strerror(0), curl_share_strerror(9999));
$ch = curl_init();
attempt(fn() => curl_setopt($ch, 99999, 1));
attempt(fn() => curl_setopt($ch, CURLOPT_URL, "a\0b"));
attempt(fn() => curl_setopt($ch, CURLOPT_HTTPHEADER, 'Accept: x'));
attempt(fn() => curl_setopt($ch, CURLOPT_SAFE_UPLOAD, false));
attempt(fn() => curl_setopt($ch, CURLOPT_SAFE_UPLOAD, true));
attempt(fn() => curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 1));
attempt(fn() => curl_setopt($ch, 'url', 'x'));
attempt(fn() => curl_setopt($ch, CURLOPT_URL));
attempt(fn() => curl_setopt('handle', CURLOPT_URL, 'x'));
attempt(fn() => curl_setopt_array($ch, ['url' => 'x']));
attempt(fn() => curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, 99999 => 1]));
attempt(fn() => curl_setopt_array($ch, 'x'));
attempt(fn() => curl_init([]));
attempt(fn() => curl_init("x\0y"));
attempt(fn() => curl_getinfo($ch, 'x'));
attempt(fn() => curl_exec(curl_multi_init()));
attempt(fn() => curl_escape($ch, []));
attempt(fn() => curl_version(1));
attempt(fn() => new CurlHandle());
attempt(fn() => new CurlMultiHandle());
attempt(fn() => clone curl_multi_init());
attempt(fn() => clone curl_share_init());
attempt(fn() => serialize($ch));
attempt(fn() => unserialize('O:10:"CurlHandle":0:{}'));
attempt(fn() => curl_close($ch));

echo "-- copies and resets\n";
$ch = curl_init("$base/curl-data/hello.txt");
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
curl_setopt($ch, CURLOPT_PRIVATE, 'source');
$copy = curl_copy_handle($ch);
$clone = clone $ch;
show($copy);
var_dump($copy !== $ch, curl_exec($copy), curl_getinfo($copy, CURLINFO_PRIVATE), curl_exec($clone));
curl_setopt($copy, CURLOPT_URL, "$base/curl-data/missing.txt");
var_dump(curl_exec($copy), curl_exec($ch));
curl_reset($ch);
var_dump(curl_getinfo($ch, CURLINFO_PRIVATE), curl_exec($ch), curl_errno($ch));
curl_setopt($ch, CURLOPT_URL, "$base/curl-data/hello.txt");
var_dump(curl_exec($ch));
var_dump(curl_escape($ch, "a b&c/ü"), curl_unescape($ch, 'a%20b%26c%2F%C3%BC'), curl_unescape($ch, '%zz'),
    curl_pause($ch, CURLPAUSE_CONT), curl_upkeep($ch));

echo "-- multi\n";
$mh = curl_multi_init();
$handles = [];
foreach (['hello.txt', 'second.txt', 'missing.txt'] as $name) {
    $handles[$name] = curl_init("$base/curl-data/$name");
    curl_setopt($handles[$name], CURLOPT_RETURNTRANSFER, true);
    var_dump(curl_multi_add_handle($mh, $handles[$name]));
}
var_dump(curl_multi_add_handle($mh, $handles['hello.txt']), curl_multi_errno($mh), count(curl_multi_get_handles($mh)));
$done = [];
do {
    $status = curl_multi_exec($mh, $active);
    while ($message = curl_multi_info_read($mh, $queued)) {
        $name = array_search($message['handle'], $handles, true);
        $done[$name] = [$message['msg'], $message['result'], curl_errno($message['handle']),
            curl_multi_getcontent($message['handle'])];
        var_dump(curl_multi_remove_handle($mh, $message['handle']), is_int($queued));
    }
    if ($active) {
        curl_multi_select($mh, 0.05);
    }
} while ($active || $status === CURLM_CALL_MULTI_PERFORM);
ksort($done);
var_dump($status, $active, $done, curl_multi_info_read($mh), curl_multi_get_handles($mh));
var_dump(curl_multi_remove_handle($mh, $handles['hello.txt']), curl_multi_errno($mh));
var_dump(curl_multi_setopt($mh, CURLMOPT_MAXCONNECTS, 4), curl_multi_select($mh, 0));
attempt(fn() => curl_multi_setopt($mh, 99999, 1));
attempt(fn() => curl_multi_select($mh, -1));
curl_multi_add_handle($mh, $handles['second.txt']);
var_dump(curl_multi_close($mh), curl_multi_get_handles($mh));

echo "-- share handles\n";
$sh = curl_share_init();
show($sh);
var_dump(curl_share_setopt($sh, CURLSHOPT_SHARE, CURL_LOCK_DATA_DNS), curl_share_errno($sh));
attempt(fn() => curl_share_setopt($sh, 99999, 1));
$ch = curl_init("$base/curl-data/hello.txt");
var_dump(curl_setopt($ch, CURLOPT_SHARE, $sh), curl_setopt($ch, CURLOPT_RETURNTRANSFER, 1), curl_exec($ch));
$persistent = curl_share_init_persistent([CURL_LOCK_DATA_CONNECT, CURL_LOCK_DATA_DNS, CURL_LOCK_DATA_DNS]);
show($persistent);
var_dump(curl_setopt($ch, CURLOPT_SHARE, $persistent), curl_exec($ch));
attempt(fn() => curl_share_init_persistent([]));
attempt(fn() => curl_share_init_persistent([CURL_LOCK_DATA_COOKIE]));
attempt(fn() => curl_share_init_persistent(['dns']));
attempt(fn() => new CurlSharePersistentHandle());

echo "-- CURLFile and CURLStringFile\n";
$file = new CURLFile($dir . '/curl-data/hello.txt');
show($file);
echo $file->getFilename(), "
";
var_dump($file->getMimeType(), $file->getPostFilename());
$file->setMimeType('text/plain');
$file->setPostFilename('greeting.txt');
var_dump($file->getMimeType(), $file->getPostFilename());
show(curl_file_create('x.txt', 'text/plain', 'y.txt'));
show(curl_file_create('x.txt', null, null));
$string = new CURLStringFile('contents', 'name.txt');
show($string);
show(new CURLStringFile('a', 'b', 'text/csv'));
$ch = curl_init("$base/curl-data/hello.txt");
var_dump(curl_setopt($ch, CURLOPT_POSTFIELDS, ['file' => $file, 'string' => $string, 'list' => [1, 2], 'n' => 3]),
    curl_setopt($ch, CURLOPT_POSTFIELDS, []), curl_setopt($ch, CURLOPT_POSTFIELDS, 'a=1&b=2'));
$copy = curl_copy_handle($ch);
var_dump($copy instanceof CurlHandle);
attempt(fn() => new CURLFile("a\0b"));
attempt(fn() => curl_file_create([]));
attempt(fn() => serialize($file));
var_dump(serialize($string) !== '');

array_map('unlink', glob("$tmp/*"));
rmdir($tmp);
