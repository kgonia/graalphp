<?php
// Top-level code that includes many files, as wp-settings.php does (about 340 require statements, each of a path
// built as ABSPATH . WPINC . '/name.php'): its compiled code was over HotSpot's install limit (640 KB). The file
// with the includes runs 60 times, so its top-level code compiles; it has a budget of IR nodes (CompilationTest).
// CompilationTest's engine may hold more than one context, as the HTTP server's does, so each constant is read
// from the running context (about 19 IR nodes a read; with one context, a constant is a compile-time constant).
// IR budget: settings.php 12500
define('BASE', __DIR__ . '/includes');
define('PARTS', '/parts');
$count = 0;
for ($i = 0; $i < 60; $i++) {
    include BASE . '/settings.php';
}
echo $count, "\n";
