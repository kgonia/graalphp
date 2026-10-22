<?php
// Writes php-src's named HTML entity tables as "name<TAB>hex-of-UTF-8" lines, one file per doctype, for the
// Truffle runtime's htmlspecialchars/html_entity_decode. Usage: php entity-tables.php <out-dir>
$outDir = $argv[1];
$doctypes = ['html401' => ENT_HTML401, 'html5' => ENT_HTML5, 'xhtml' => ENT_XHTML, 'xml1' => ENT_XML1];
foreach ($doctypes as $name => $flag) {
    $table = get_html_translation_table(HTML_ENTITIES, ENT_QUOTES | $flag, 'UTF-8');
    $lines = [];
    foreach ($table as $char => $entity) {
        $lines[] = substr($entity, 1, -1) . "\t" . bin2hex($char);
    }
    // Known gap: HTML5 decoding accepts more aliases than this one-name-per-character encode table lists.
    file_put_contents("$outDir/entities-$name.txt", implode("\n", $lines) . "\n");
    echo "$name: ", count($lines), " entities\n";
}
