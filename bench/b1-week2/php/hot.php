<?php
// B1 Week 2 harness: bench/b1-precheck/probe-b/php-ref/hot.php's setup and its kernels mega, none, one, hasf, aget
// and post, compiled by B1. Lines from hot.php are verbatim; this file keeps only what those six kernels use:
// - WordPress code comes from the unmodified wp-includes/plugin.php and class-wp-post.php;
// - _wp_array_get and __return_true are hot-fns.php's own declarations, which the host links before this file
//   (B1 links them alone; the rest of hot-fns.php is not part of these kernels);
// - the cache, options and HTML API setup of hot.php serve its other kernels (opt, uri, tags, esc, args).
// The host sets $b1_wordpress (hot.php's argv[1]) and $b1_ctxb, the paired CtxB variant of ProbeBMain mega-ctxb:
// hot_f0 adds 100 instead of 0, and a callback on the special 'all' hook runs on every apply_filters.
// The host times one call of the returned runner, hot.php's inner loop of $inner kernel calls.

require $b1_wordpress . '/wp-includes/plugin.php';
require $b1_wordpress . '/wp-includes/class-wp-post.php';

// --- many callbacks of every kind on many hooks: WP_Hook's call sites see hundreds of targets, as a site's do
for ($i = 0; $i < 60; $i++) {
    if ($b1_ctxb && $i === 0) {
        eval("function hot_f0(\$v) { return \$v + 100; }");
    } else {
        eval("function hot_f$i(\$v) { return \$v + $i; }");
    }
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

$b1_all_calls = 0;
if ($b1_ctxb) {
    add_filter('all', static function () use (&$b1_all_calls) {
        $b1_all_calls++;
    });
}

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
    'post' => static function () use ($post_row) {
        $v = 0;
        for ($i = 0; $i < 40; $i++) {
            $post = new WP_Post($post_row);
            $v += $post->ID + strlen($post->post_title);
        }
        return $v;
    },
];

// hot.php's timed loop: $inner calls of one kernel.
$run = static function ($kernel, $inner) {
    $sum = 0;
    for ($n = 0; $n < $inner; $n++) {
        $sum += $kernel();
    }
    return $sum;
};

return ['kernels' => $kernels, 'run' => $run];
