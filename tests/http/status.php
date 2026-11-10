<?php
// The status a web SAPI sends: header('HTTP/...') lines, http_response_code(), a Status header, a Location's
// redirect, each as ?m= asks (php-cgi and php-fpm write it as their CGI Status, tests/http/fpm.sh)
switch ($_GET['m'] ?? '') {
    case 'code':
        http_response_code((int) $_GET['c']);
        break;
    case 'line':
        header('HTTP/1.1 ' . $_GET['c'] . ' Custom Reason');
        break;
    case 'status':
        header('Status: ' . $_GET['c'] . ' Mine');
        var_dump(http_response_code());
        break;
    case 'line-then-code':
        header('HTTP/1.1 404 Custom');
        var_dump(http_response_code(410));
        var_dump(http_response_code());
        break;
    case 'code-then-status':
        http_response_code(404);
        header('Status: 410 Gone Too');
        break;
    case 'location':
        header('Location: /elsewhere');
        break;
    case 'location-301':
        header('HTTP/1.1 404 Custom');
        header('Location: /elsewhere', true, 301);
        var_dump(http_response_code());
        break;
}
var_dump(headers_list());
