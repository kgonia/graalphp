<?php
// Client sockets, run by each engine inside WordPress (tests/gate2/http.sh sockets): fsockopen and
// stream_socket_client against both servers (PHP's on 8081, Truffle PHP's on 8080, reached from either container as
// host.docker.internal), a refused port, a name that doesn't resolve, a connect and a read that time out, the
// stream functions on sockets, and wp_remote_get() through Requests' fsockopen transport. Prints only what is the
// same for any correct engine: no addresses, timings or byte counts that depend on how the network splits data.
// wp-config.php blocks external requests; these hosts are the probe's targets
define('WP_ACCESSIBLE_HOSTS', 'host.docker.internal,127.0.0.1,nonexistent.invalid');
require __DIR__ . '/wp-load.php';
header('Content-Type: text/plain');

set_error_handler(function ($type, $message) {
    if (!(error_reporting() & $type)) {
        return false; // silenced with @
    }
    echo "  [$type] $message\n";
    return true;
});

$servers = ['php' => 'host.docker.internal:8081', 'truffle' => 'host.docker.internal:8080'];
$path = '/wp-includes/css/dashicons.min.css';

function meta($stream, $label) {
    $meta = stream_get_meta_data($stream);
    unset($meta['unread_bytes']);
    echo "  $label: ", json_encode($meta), "\n";
}

// a response's status line and the headers that don't vary between requests
function head($stream) {
    $status = fgets($stream);
    echo '  status: ', trim($status), "\n";
    while (($line = fgets($stream)) !== false && $line !== "\r\n") {
        if (preg_match('/^(content-type|content-length):/i', $line)) {
            echo '  ', strtolower(trim($line)), "\n";
        }
    }
}

foreach ($servers as $name => $server) {
    echo "== stream_socket_client to $name\n";
    $s = stream_socket_client("tcp://$server", $errno, $errstr, 5);
    var_dump($errno, $errstr, get_resource_type($s));
    meta($s, 'connected');
    $peer = stream_socket_get_name($s, true);
    var_dump(str_ends_with($peer, ':' . substr($server, -4)), stream_socket_get_name($s, false) !== false);
    var_dump(fwrite($s, "GET $path HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
    head($s);
    // fread gives what one recv gives: how many calls the body takes depends on the network
    $body = '';
    $strings = true;
    while (!feof($s)) {
        $block = fread($s, 8192);
        $strings = $strings && is_string($block) && strlen($block) <= 8192;
        $body .= $block;
    }
    echo '  body: ', strlen($body), ' bytes, md5 ', md5($body), ', all strings: ', var_export($strings, true), "\n";
    meta($s, 'at the end');
    var_dump(fread($s, 10), fgets($s), feof($s));
    var_dump(stream_context_get_options($s));
    var_dump(fclose($s));

    echo "== fsockopen to $name\n";
    [$host, $port] = explode(':', $server);
    $s = fsockopen($host, (int) $port, $errno, $errstr, 5);
    var_dump($errno, $errstr);
    meta($s, 'connected');
    var_dump(stream_context_get_options($s));
    fwrite($s, "HEAD $path HTTP/1.0\r\nHost: localhost\r\n\r\n");
    var_dump(stream_set_timeout($s, 5), stream_set_blocking($s, true));
    head($s);
    var_dump(fgets($s), feof($s));
    fclose($s);

    echo "== read timeout on $name\n";
    $s = stream_socket_client("tcp://$server", $errno, $errstr, 5);
    var_dump(stream_set_timeout($s, 0, 300000));
    var_dump(fread($s, 100));
    meta($s, 'after fread');
    var_dump(fgets($s), stream_get_meta_data($s)['timed_out'], feof($s));
    stream_set_timeout($s, 1);
    var_dump(stream_get_meta_data($s)['timed_out']);
    echo "== non-blocking on $name\n";
    var_dump(stream_set_blocking($s, false), fread($s, 100), fgets($s), feof($s));
    meta($s, 'non-blocking');
    fclose($s);

    echo "== TLS on a plain HTTP server ($name)\n";
    $s = stream_socket_client("tcp://$server", $errno, $errstr, 5);
    var_dump(stream_socket_enable_crypto($s, true, STREAM_CRYPTO_METHOD_TLS_CLIENT));
    fclose($s);
    $s = stream_socket_client("ssl://$server", $errno, $errstr, 5);
    var_dump($s);
echo "  errno=$errno errstr=$errstr\n";
}

echo "== refused\n";
var_dump(stream_socket_client('tcp://127.0.0.1:1', $errno, $errstr, 5));
echo "  errno=$errno errstr=$errstr\n";
var_dump(fsockopen('127.0.0.1', 1, $errno, $errstr, 5));
echo "  errno=$errno errstr=$errstr\n";
echo "== unresolvable\n";
var_dump(stream_socket_client('tcp://nonexistent.invalid:80', $errno, $errstr, 5));
echo "  errno=$errno errstr=$errstr\n";
var_dump(fsockopen('nonexistent.invalid', 80, $errno, $errstr, 5));
echo "  errno=$errno errstr=$errstr\n";
echo "== connect timeout\n";
var_dump(stream_socket_client('tcp://10.255.255.1:80', $errno, $errstr, 1));
echo "  errno=$errno errstr=$errstr\n";
echo "== bad addresses\n";
var_dump(stream_socket_client('tcp://127.0.0.1', $errno, $errstr, 1));
echo "  errno=$errno errstr=$errstr\n";
var_dump(stream_socket_client('udp2://127.0.0.1:1', $errno, $errstr, 1));
echo "  errno=$errno errstr=$errstr\n";
var_dump(stream_socket_client('tcp://[::1', $errno, $errstr, 1));
echo "  errno=$errno errstr=$errstr\n";
echo "== contexts\n";
$context = stream_context_create(['ssl' => ['verify_peer' => true]]);
var_dump(stream_context_set_option($context, 'ssl', 'peer_name', 'example.org'));
var_dump(stream_context_set_options($context, ['socket' => ['tcp_nodelay' => true]]));
$options = stream_context_get_options($context);
$options['ssl']['verify_peer'] = false;
var_dump(stream_context_get_options($context), $options['ssl']);
$s = stream_socket_client('tcp://' . $servers['php'], $errno, $errstr, 5, STREAM_CLIENT_CONNECT, $context);
var_dump(stream_context_get_options($s) === stream_context_get_options($context));
fclose($s);
var_dump(ini_get('default_socket_timeout'), function_exists('fsockopen'));

echo "== wp_remote_get through Requests' fsockopen transport\n";
add_action('requests-requests.before_request', function (&$url, &$headers, &$data, &$type, &$options) {
    $options['transport'] = \WpOrg\Requests\Transport\Fsockopen::class;
}, 10, 5);
// a server's first WordPress page can take long while it warms up: a generous timeout
foreach ($servers as $name => $server) {
    foreach ([$path, '/nonexistent-file.css'] as $file) {
        $response = wp_remote_get("http://$server$file", ['timeout' => 60]);
        if (is_wp_error($response)) {
            echo "  $name $file: error ", $response->get_error_message(), "\n";
            continue;
        }
        echo "  $name $file: ", wp_remote_retrieve_response_code($response), ' ',
            wp_remote_retrieve_response_message($response), ', ', wp_remote_retrieve_header($response, 'content-type');
        if ($file === $path) {
            echo ', ', strlen(wp_remote_retrieve_body($response)), " bytes, md5 ",
                md5(wp_remote_retrieve_body($response));
        }
        echo "\n";
    }
}
$response = wp_remote_get('http://127.0.0.1:1/');
echo '  refused: ', $response->get_error_code(), ': ', $response->get_error_message(), "\n";
$response = wp_remote_get('http://nonexistent.invalid/');
echo '  unresolvable: ', $response->get_error_code(), ': ', $response->get_error_message(), "\n";
$response = wp_remote_post('http://' . $servers['truffle'] . '/wp-login.php', ['body' => ['log' => 'x', 'pwd' => 'y'], 'timeout' => 60,
    'redirection' => 0]);
echo '  post: ', wp_remote_retrieve_response_code($response), "\n";

require_once ABSPATH . 'wp-admin/includes/file.php';
echo "== get_filesystem_method: ", get_filesystem_method([], "/proc/", true), "\n";
