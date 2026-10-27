<?php
// The marks of a timed request (prepend.php): when each phase ends, after its hook's last callback. "core" is the
// core files, the database connection and the autoloaded options: everything before the mu-plugins.
if (isset($GLOBALS['bootshare_marks'])) {
    $GLOBALS['bootshare_marks']['core'] = hrtime(true);
    foreach (['muplugins_loaded', 'plugins_loaded', 'after_setup_theme', 'init', 'wp_loaded', 'admin_init',
            'rest_api_init', 'wp', 'template_redirect'] as $hook) {
        add_action($hook, function () use ($hook) {
            $GLOBALS['bootshare_marks'][$hook] ??= hrtime(true);
            if ($hook === 'wp_loaded') {
                $GLOBALS['bootshare_boot_peak'] = memory_get_peak_usage();
                $GLOBALS['bootshare_boot_live'] = memory_get_usage();
            }
        }, PHP_INT_MAX);
    }
}

// With X-Bootshare-Profile, the time of each callback on the bootstrap's hooks and of each plugin's main file, to
// /srv/profile.jsonl: which of the bootstrap's work depends on the request. Each hook's callbacks are wrapped just
// before it fires (callbacks added while it runs aren't timed).
if (isset($GLOBALS['bootshare_marks'], $_SERVER['HTTP_X_BOOTSHARE_PROFILE'])) {
    $GLOBALS['bootshare_profile'] = [];
    $GLOBALS['bootshare_plugin_start'] = hrtime(true);
    add_action('plugin_loaded', function ($file) {
        $now = hrtime(true);
        $GLOBALS['bootshare_profile']['include ' . basename(dirname($file))] = $now - $GLOBALS['bootshare_plugin_start'];
        $GLOBALS['bootshare_plugin_start'] = $now;
    });
    $wrap = function ($hook) {
        foreach ($GLOBALS['wp_filter'][$hook]->callbacks ?? [] as $priority => $callbacks) {
            foreach ($callbacks as $id => $callback) {
                $function = $callback['function'];
                if (is_array($function)) {
                    $name = (is_object($function[0]) ? get_class($function[0]) : $function[0]) . '::' . $function[1];
                } elseif ($function instanceof Closure) {
                    $reflection = new ReflectionFunction($function);
                    $name = 'closure ' . str_replace(ABSPATH, '', $reflection->getFileName()) . ':'
                        . $reflection->getStartLine();
                } else {
                    $name = is_string($function) ? $function : get_class($function) . '::__invoke';
                }
                $GLOBALS['wp_filter'][$hook]->callbacks[$priority][$id]['function'] =
                    function (...$args) use ($function, $hook, $name) {
                        $start = hrtime(true);
                        try {
                            return $function(...$args);
                        } finally {
                            $key = "$hook $name";
                            $GLOBALS['bootshare_profile'][$key] = ($GLOBALS['bootshare_profile'][$key] ?? 0)
                                + hrtime(true) - $start;
                        }
                    };
            }
        }
    };
    add_action('muplugins_loaded', fn() => $wrap('plugins_loaded'), PHP_INT_MAX);
    add_action('after_setup_theme', fn() => $wrap('init'), PHP_INT_MAX);
    add_action('init', fn() => $wrap('wp_loaded'), PHP_INT_MAX);
    add_action('parse_request', fn() => $wrap('rest_api_init'), PHP_INT_MIN);
    register_shutdown_function(function () {
        file_put_contents('/srv/profile.jsonl', json_encode(['page' => $_SERVER['HTTP_X_BOOTSHARE'],
            'ns' => $GLOBALS['bootshare_profile']]) . "\n", FILE_APPEND | LOCK_EX);
    });
}
