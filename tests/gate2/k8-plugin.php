<?php
/*
 * A must-use plugin for `tests/gate2/native.sh k8` (wiki/beat-php.md): a request's phases, and its page rendered
 * again and again inside the one request.
 *
 * ?k8=N: the request runs as any other up to template_redirect. There, after WordPress's own callbacks, the plugin
 * runs what wp-blog-header.php runs after the bootstrap, N times: wp() (the request parsed, the main query) and
 * template-loader.php (template_redirect's callbacks, the template found and included), each time into a buffer.
 * The answer is JSON: the times at which the bootstrap's hooks ended (ms since the request's start), and per
 * iteration its time and wp()'s part of it (ns), its output's length and crc32, the queries it sent, and when it
 * started (ns after "loop_start", the loop's start as a Unix time: what places a render beside a log's lines). The first
 * iteration is a request's own work with the request's caches cold (the object cache, the theme's JSON, the
 * registries' lazy parts); the later ones are the same PHP code with those caches warm, so they measure the engine
 * on WordPress's rendering code alone.
 * ?k8=N&k8dump=1 answers with the last iteration's page instead. ?k8=N&k8prof=MICROSECONDS, on a PHP with the
 * Excimer extension: iterations 3 and up are sampled at that period, and the answer's "profile" is the samples per
 * function, [name, self, inclusive] (a built-in's time is its calling PHP function's).
 *
 * Every iteration starts from the state the request had at template_redirect in what a render fills once and
 * prints at its end: the hooks (a render adds closures to wp_head and wp_footer), the registered scripts and styles
 * (their inline parts are appended to), the style engine's stores, and the response's headers (a render adds its
 * Link headers to those there are: after some fifty renders nginx refuses the response's header as too big). Without
 * that the page grows with each iteration (?k8raw=1 leaves them as they are). The copies are made outside the measured time. What is printed
 * once in a request whatever the hooks say is missing from the later iterations: the script modules' tags and the
 * emoji loader's inline script, 4 KB of the home page's 77 KB.
 */
if (isset($_GET['k8'])) {
    $GLOBALS['k8_t'] = ['start' => (float) $_SERVER['REQUEST_TIME_FLOAT'], 'mu' => microtime(true)];
    foreach (['plugins_loaded', 'init', 'wp_loaded', 'wp'] as $k8_hook) {
        add_action($k8_hook, function () use ($k8_hook) {
            if (!isset($GLOBALS['k8_t'][$k8_hook])) {
                $GLOBALS['k8_t'][$k8_hook] = microtime(true);
            }
        }, PHP_INT_MAX);
    }

    function k8_dependencies($dependencies) {
        $copy = clone $dependencies;
        foreach ($copy->registered as $handle => $dependency) {
            $copy->registered[$handle] = clone $dependency;
        }
        return $copy;
    }

    function k8_hooks(array $hooks): array {
        $copy = [];
        foreach ($hooks as $name => $hook) {
            $copy[$name] = clone $hook;
        }
        return $copy;
    }

    add_action('template_redirect', function () {
        static $running = false;
        if ($running) {
            return;
        }
        $running = true;
        global $wp_filter, $wp_styles, $wp_scripts, $wpdb;
        $GLOBALS['k8_t']['template_redirect'] = microtime(true);
        set_time_limit(0);
        $n = max(1, (int) $_GET['k8']);
        $reset = !isset($_GET['k8raw']);
        $hooks = k8_hooks($wp_filter);
        $styles = k8_dependencies(wp_styles());
        $scripts = k8_dependencies(wp_scripts());
        $headers = headers_list();
        $rows = [];
        $html = '';
        $profiler = null;
        $loop = [microtime(true), hrtime(true)];
        for ($i = 0; $i < $n; $i++) {
            if ($reset) {
                $wp_filter = k8_hooks($hooks);
                $wp_styles = k8_dependencies($styles);
                $wp_scripts = k8_dependencies($scripts);
                WP_Style_Engine_CSS_Rules_Store::remove_all_stores();
                header_remove();
                foreach ($headers as $header) {
                    header($header, false);
                }
            }
            if ($i === 3 && isset($_GET['k8prof']) && class_exists('ExcimerProfiler')) {
                $profiler = new ExcimerProfiler();
                $profiler->setPeriod(max(1, (int) $_GET['k8prof']) / 1e6);
                $profiler->setEventType(EXCIMER_REAL);
                $profiler->setMaxDepth(500);
                $profiler->start();
            }
            $queries = $wpdb->num_queries;
            $t0 = hrtime(true);
            ob_start();
            wp();
            $t1 = hrtime(true);
            require ABSPATH . WPINC . '/template-loader.php';
            $html = ob_get_clean();
            $rows[] = [hrtime(true) - $t0, $t1 - $t0, strlen($html), crc32($html), $wpdb->num_queries - $queries,
                $t0 - $loop[1]];
        }
        $end = microtime(true);
        $profile = null;
        if ($profiler !== null) {
            $profiler->stop();
            $profile = [];
            foreach ($profiler->getLog()->aggregateByFunction() as $name => $counts) {
                $profile[] = [$name, $counts['self'], $counts['inclusive']];
            }
        }
        if (isset($_GET['k8dump'])) {
            echo $html;
            exit;
        }
        $t = $GLOBALS['k8_t'];
        $phases = [];
        foreach (['mu', 'plugins_loaded', 'init', 'wp_loaded', 'wp', 'template_redirect'] as $name) {
            $phases[$name] = round(1000 * ($t[$name] - $t['start']), 3);
        }
        $phases['end'] = round(1000 * ($end - $t['start']), 3);
        header('Content-Type: application/json');
        echo json_encode(['engine' => PHP_VERSION . ' ' . PHP_SAPI, 'phases' => $phases, 'iterations' => $rows,
            'peak' => memory_get_peak_usage(), 'queries' => $wpdb->num_queries, 'profile' => $profile,
            'loop_start' => $loop[0]]), "\n";
        exit;
    }, PHP_INT_MAX);
}
