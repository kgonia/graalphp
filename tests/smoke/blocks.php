<?php
require __DIR__ . '/../../bench/gate1/wordpress/wp-includes/class-wp-block-parser-block.php';
require __DIR__ . '/../../bench/gate1/wordpress/wp-includes/class-wp-block-parser-frame.php';
require __DIR__ . '/../../bench/gate1/wordpress/wp-includes/class-wp-block-parser.php';
$p = new WP_Block_Parser();
print_r($p->parse('<!-- wp:paragraph --><p>a</p><!-- /wp:paragraph -->'));
print_r($p->parse('<!-- wp:group {"layout":{"type":"flex"}} --><div><!-- wp:paragraph {"x":1} --><p>b</p><!-- /wp:paragraph --></div><!-- /wp:group -->tail<!-- wp:spacer /-->'));
