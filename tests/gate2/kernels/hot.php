<?php
// Kernels of the functions the K8 differential profile ranks worst (wiki/beat-php.md), on WordPress's own code.
// Usage: php hot.php WORDPRESS_DIR ROUNDS [kernel,...]   prints "kernel round ns/op checksum" per round.
error_reporting(E_ALL);
define('ABSPATH', rtrim($argv[1], '/') . '/');
define('WPINC', 'wp-includes');
function get_current_blog_id() { return 1; }
require __DIR__ . '/hot-fns.php';
require ABSPATH . WPINC . '/plugin.php';
require ABSPATH . WPINC . '/cache.php';
require ABSPATH . WPINC . '/option.php';
require ABSPATH . WPINC . '/class-wp-post.php';
require ABSPATH . WPINC . '/utf8.php';
require ABSPATH . WPINC . '/formatting.php';
require ABSPATH . WPINC . '/kses.php';
require ABSPATH . WPINC . '/class-wp-token-map.php';
require ABSPATH . WPINC . '/html-api/html5-named-character-references.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-span.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-text-replacement.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-attribute-token.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-decoder.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-tag-processor.php';
wp_cache_init();

// --- many callbacks of every kind on many hooks: WP_Hook's call sites see hundreds of targets, as a site's do
for ($i = 0; $i < 60; $i++) {
    eval("function hot_f$i(\$v) { return \$v + $i; }");
}
for ($i = 0; $i < 20; $i++) {
    eval("class Hot_C$i { public \$n = $i; public function a(\$v) { return \$v + \$this->n; }"
        . " public static function s(\$v) { return \$v + 2; } public function b(\$v, \$w) { return \$v + \$w; } }");
}
$hot_objects = [];
for ($i = 0; $i < 20; $i++) {
    $class = "Hot_C$i";
    $hot_objects[] = new $class();
}
for ($h = 0; $h < 40; $h++) {
    add_filter("hot_hook_$h", 'hot_f' . ($h % 60));
    add_filter("hot_hook_$h", [$hot_objects[$h % 20], 'a']);
    add_filter("hot_hook_$h", ['Hot_C' . (($h + 7) % 20), 's'], 11);
    add_filter("hot_hook_$h", [$hot_objects[($h + 3) % 20], 'b'], 12, 2);
    add_filter("hot_hook_$h", eval("return static function (\$v) { return \$v + $h; };"), 20);
}
add_filter('hot_one', '__return_true');
add_filter('hot_zero', '__return_true', 10, 0);

$alloptions = [];
for ($i = 0; $i < 160; $i++) {
    $alloptions["option_name_$i"] = $i % 3 === 0 ? "value $i" : ($i % 3 === 1 ? (string) $i : 'a:2:{i:0;s:1:"a";i:1;s:1:"b";}');
}
$alloptions['siteurl'] = 'http://localhost:8080';
$alloptions['home'] = 'http://localhost:8080';
$alloptions['blogname'] = 'Gate 2';
$alloptions['blog_charset'] = 'UTF-8';
wp_cache_set('alloptions', $alloptions, 'options');
wp_cache_set('notoptions', ['nope' => true], 'options');

$settings = ['color' => ['palette' => ['default' => [['slug' => 'black', 'color' => '#000']], 'theme' => []],
    'gradients' => null, 'custom' => true], 'typography' => ['fontSizes' => ['default' => [1, 2, 3]]],
    'spacing' => ['units' => ['px', 'em']]];

$post_row = new stdClass();
foreach (['ID' => 1, 'post_author' => '1', 'post_date' => '2026-11-01 10:00:00', 'post_date_gmt' => '2026-11-01 10:00:00',
    'post_content' => str_repeat('<p>Hello world</p>', 20), 'post_title' => 'Hello world!', 'post_excerpt' => '',
    'post_status' => 'publish', 'comment_status' => 'open', 'ping_status' => 'open', 'post_password' => '',
    'post_name' => 'hello-world', 'to_ping' => '', 'pinged' => '', 'post_modified' => '2026-11-01 10:00:00',
    'post_modified_gmt' => '2026-11-01 10:00:00', 'post_content_filtered' => '', 'post_parent' => 0,
    'guid' => 'http://localhost:8080/?p=1', 'menu_order' => 0, 'post_type' => 'post', 'post_mime_type' => '',
    'comment_count' => '1', 'filter' => 'raw'] as $k => $v) {
    $post_row->$k = $v;
}

$html = str_repeat('<div class="wp-block-group has-background" style="color:red"><p class="a b">Text &amp; more</p>'
    . '<img src="a.png" alt="An image" width="10" height="20"><a href="/x?y=1" class="more">link</a></div>', 6);

$kernels = [
    // 200 calls per op
    'mega' => static function () {
        $v = 0;
        for ($h = 0; $h < 40; $h++) {
            $v = apply_filters("hot_hook_$h", $v, 3);
        }
        return $v;
    },
    'none' => static function () {
        $v = 0;
        for ($h = 0; $h < 40; $h++) {
            $v += apply_filters("hot_none_$h", $h, 3);
        }
        return $v;
    },
    'one' => static function () {
        $v = 0;
        for ($h = 0; $h < 40; $h++) {
            $v += apply_filters('hot_one', false) ? 1 : 0;
            $v += apply_filters('hot_zero', false) ? 1 : 0;
        }
        return $v;
    },
    'hasf' => static function () {
        $v = 0;
        for ($h = 0; $h < 40; $h++) {
            $v += has_filter("hot_hook_$h") ? 1 : 0;
            $v += (int) has_filter("hot_hook_$h", 'hot_f' . ($h % 60));
            $v += (int) has_filter("hot_hook_$h", 'missing');
            $v += (int) has_filter("hot_none_$h", 'missing');
        }
        return $v;
    },
    'opt' => static function () {
        $v = 0;
        for ($i = 0; $i < 40; $i++) {
            $v += strlen(get_option('blogname'));
            $v += strlen(get_option('home'));
            $v += count((array) get_option('option_name_' . ($i * 3 + 2)));
            $v += get_option('nope', 7);
        }
        return $v;
    },
    'aget' => static function () use ($settings) {
        $v = 0;
        for ($i = 0; $i < 40; $i++) {
            $v += count(_wp_array_get($settings, ['color', 'palette', 'default'], []));
            $v += _wp_array_get($settings, ['color', 'gradients'], 5) ?? 1;
            $v += _wp_array_get($settings, ['typography', 'lineHeight'], 2);
            $v += count(_wp_array_get($settings, ['spacing', 'units']));
        }
        return $v;
    },
    'uri' => static function () {
        $v = 0;
        for ($i = 0; $i < 40; $i++) {
            $v += strlen(utf8_uri_encode('hello-world-zażółć-gęślą-jaźń-and-some-more-text', 200));
        }
        return $v;
    },
    'post' => static function () use ($post_row) {
        $v = 0;
        for ($i = 0; $i < 40; $i++) {
            $post = new WP_Post($post_row);
            $v += $post->ID + strlen($post->post_title);
        }
        return $v;
    },
    'tags' => static function () use ($html) {
        $v = 0;
        $p = new WP_HTML_Tag_Processor($html);
        while ($p->next_tag()) {
            $v += strlen((string) $p->get_attribute('class'));
            if ('IMG' === $p->get_tag()) {
                $p->set_attribute('loading', 'lazy');
                $p->add_class('wp-image');
            }
        }
        return $v + strlen($p->get_updated_html());
    },
    'esc' => static function () {
        $v = 0;
        for ($i = 0; $i < 40; $i++) {
            $v += strlen(esc_attr('wp-block-group has-background'));
            $v += strlen(esc_html('Hello world & more <b>'));
            $v += strlen(esc_url('http://localhost:8080/?p=1'));
            $v += strlen(sanitize_key('Some_Key-' . $i));
        }
        return $v;
    },
    'args' => static function () {
        $v = 0;
        for ($i = 0; $i < 40; $i++) {
            $a = wp_parse_args(['a' => $i, 'b' => 2], ['a' => 0, 'b' => 0, 'c' => 3, 'd' => 4, 'e' => null]);
            $v += $a['a'] + $a['c'];
            $b = wp_parse_args('x=1&y=2', ['z' => 3]);
            $v += $b['z'] + count($b);
        }
        return $v;
    },
];

$rounds = (int) ($argv[2] ?? 20);
$selected = isset($argv[3]) ? explode(',', $argv[3]) : array_keys($kernels);
$inner = 250;
foreach ($selected as $name) {
    $kernel = $kernels[$name];
    for ($round = 0; $round < $rounds; $round++) {
        $t = hrtime(true);
        $sum = 0;
        for ($n = 0; $n < $inner; $n++) {
            $sum += $kernel();
        }
        $ns = (hrtime(true) - $t) / $inner;
        // every round, as ops.php: opstable.py's first decile of the last half is what compares here (issue 101)
        printf("%s %d %.0f %d\n", $name, $round, $ns, $sum);
    }
}
