<?php
require __DIR__ . '/../../bench/gate1/wordpress/wp-includes/class-wp-block-parser-block.php';
require __DIR__ . '/../../bench/gate1/wordpress/wp-includes/class-wp-block-parser-frame.php';
require __DIR__ . '/../../bench/gate1/wordpress/wp-includes/class-wp-block-parser.php';
$corpus = require __DIR__ . '/../../bench/gate1/corpus.php';
foreach ($corpus as $i => [$title, $content]) {
    echo $i, ' ', strlen($content), "\n";
    $p = new WP_Block_Parser();
    echo crc32(json_encode($p->parse($content))), "\n";
}
