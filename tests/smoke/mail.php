<?php
// mail(): what it checks before and while handing the message to sendmail_path (WooCommerce's order emails, through
// PHPMailer). sendmail_path is PHP_INI_SYSTEM, so here it is the default /usr/sbin/sendmail, missing on both sides:
// the shell's own message about it (dash's or Git for Windows' sh's) is left out by mail.filter, the warning stays.
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});
foreach (['sendmail_path', 'sendmail_from', 'mail.add_x_header', 'mail.cr_lf_mode', 'mail.force_extra_parameters',
    'mail.log', 'mail.mixed_lf_and_crlf', 'syslog.facility', 'syslog.filter', 'syslog.ident'] as $name) {
    echo $name, ' = ', var_export(ini_get($name), true), ', ini_set: ', var_export(@ini_set($name, 'x'), true), "\n";
}
var_dump(ini_set('syslog.filter', 'all'), ini_get('syslog.filter'), ini_set('syslog.filter', 'raw'),
    ini_get('syslog.filter'));
ini_restore('syslog.filter');
var_dump(ini_get('syslog.filter'));
function t($label, ...$args) {
    echo "== $label\n";
    try {
        var_dump(mail(...$args));
    } catch (Throwable $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
        for ($p = $e->getPrevious(); $p; $p = $p->getPrevious()) {
            echo "  previous ", get_class($p), ": ", $p->getMessage(), "\n";
        }
    }
}
class S { function __toString(): string { return "X-S: 1"; } }
t('few', "a", "b");
t('many', "a", "b", "c", "", "", 1);
t('to nul', "a\0b", "s", "m");
t('subject nul', "a", "s\0", "m");
t('message nul', "a", "s", "m\0");
t('params nul', "a", "s", "m", "", "x\0");
t('headers nul', "a", "s", "m", "X: 1\0");
t('to array', [], "s", "m");
t('params array', "a", "s", "m", "", []);
t('headers int', "a", "s", "m", 5);
t('headers null', "a", "s", "m", null);
t('headers object', "a", "s", "m", new stdClass);
t('headers stringable', "a", "s", "m", new S);
t('to null', null, "s", "m");
t('params null', "a", "s", "m", "", null);
t('numeric key', "a", "s", "m", ['From' => 'x', 0 => 'y']);
t('bad name', "a", "s", "m", ['Fr om' => 'x']);
t('bad name colon', "a", "s", "m", ['X:Y' => 'x']);
t('bad name 8bit', "a", "s", "m", ["X\xC3\xA9" => 'x']);
t('lf', "a", "s", "m", ['X' => "a\nb"]);
t('lf fold', "a", "s", "m", ['X' => "a\n b"]);
t('cr', "a", "s", "m", ['X' => "a\rb"]);
t('cr end', "a", "s", "m", ['X' => "a\r"]);
t('crlf', "a", "s", "m", ['X' => "a\r\nb"]);
t('crlf end', "a", "s", "m", ['X' => "a\r\n"]);
t('crlf fold', "a", "s", "m", ['X' => "a\r\n\tb"]);
t('nul', "a", "s", "m", ['X' => "a\0b"]);
t('to', "a", "s", "m", ['TO' => "x"]);
t('subject', "a", "s", "m", ['subject' => "x"]);
t('from array', "a", "s", "m", ['FROM' => ["x"]]);
t('cc array', "a", "s", "m", ['Cc' => ["x"]]);
t('from int', "a", "s", "m", ['From' => 1]);
t('x int', "a", "s", "m", ['X' => 1.5]);
t('x null', "a", "s", "m", ['X' => null]);
t('x true', "a", "s", "m", ['X' => true]);
t('list key', "a", "s", "m", ['X' => ['a' => 'b']]);
t('list int', "a", "s", "m", ['X' => [1]]);
t('list obj', "a", "s", "m", ['X' => [new stdClass]]);
t('list nested', "a", "s", "m", ['X' => [['a']]]);
t('list two errors', "a", "s", "m", ['X' => ["a\nb", "c\rd"]]);
t('list error then type', "a", "s", "m", ['X' => ["a\nb", 5]]);
t('list bad name', "a", "s", "m", ['X Y' => ["a", "b"]]);
t('second header error', "a", "s", "m", ['X' => "a\nb", 'Y' => "c\nd"]);
t('empty name', "a", "s", "m", ['' => "a"]);
$v = 'ref';
t('ref value', "a", "s", "m", ['X' => &$v, 'To' => 1]);
t('malformed lead', "a", "s", "m", " X: 1");
t('malformed lead nl', "a", "s", "m", "\nX: 1");
t('malformed cr', "a", "s", "m", "X: 1\rY: 2");
t('malformed crcr', "a", "s", "m", "X: 1\r\rY");
t('malformed lflf', "a", "s", "m", "X: 1\n\nY");
t('malformed crlfcr', "a", "s", "m", "X: 1\r\n\rY");
t('malformed lfcr', "a", "s", "m", "X: 1\n\rY");
t('malformed colon', "a", "s", "m", ":X");
t('malformed 8bit', "a", "s", "m", "\xC3X");
t('ok cr', "a", "s", "m", "X: 1\rYY: 2");
t('trailing ws trimmed', "a", "s", "m", "X: 1\r\n\r\n \t\x0B");
t('missing sendmail', "a", "s", "m");
echo "done\n";
