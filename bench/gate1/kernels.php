<?php
// Gate 1 kernels. Each takes the corpus (list of [title, content, has_blocks]) and returns its outputs.
// Inputs derived from the corpus are prepared once here, outside the timed region.

final class Bench_Callbacks {
    public function suffix(string $value): string {
        return $value . '|m';
    }

    public static function wrap(string $value, int $index): string {
        return '<' . $index . '>' . $value;
    }
}

function bench_upper_first(string $value): string {
    return ucfirst($value);
}

function bench_headings(array $corpus): array {
    $headings = [];
    foreach ($corpus as [$title, $content]) {
        $headings[] = $title;
        preg_match_all('/<h[1-6][^>]*>(.*?)<\/h[1-6]>/s', $content, $matches);
        foreach ($matches[1] as $heading) {
            $headings[] = $heading;
        }
    }
    return $headings;
}

function bench_classic(array $corpus): array {
    $posts = [];
    foreach ($corpus as [, $content]) {
        $posts[] = preg_replace('/<!-- \/?wp:[^>]*-->\n?/', '', $content);
    }
    return $posts;
}

$headings = bench_headings($corpus);
$classic  = bench_classic($corpus);

return [
    // Hook dispatch: registration plus apply_filters/do_action through WP_Hook with mixed callable kinds.
    'hooks' => static function (array $corpus) use ($headings): array {
        remove_all_filters('bench_title');
        remove_all_actions('bench_seen');
        $object = new Bench_Callbacks();
        $seen   = 0;
        add_filter('bench_title', 'trim');
        add_filter('bench_title', 'bench_upper_first', 5);
        add_filter('bench_title', [$object, 'suffix'], 20);
        add_filter('bench_title', ['Bench_Callbacks', 'wrap'], 20, 2);
        add_filter('bench_title', static fn(string $value): string => str_replace('  ', ' ', $value), 30);
        add_filter('bench_title', static function (string $value, int $index) use (&$seen): string {
            do_action('bench_seen', $index);
            return $index % 7 === 0 ? apply_filters('bench_nested', $value) : $value;
        }, 40, 2);
        add_action('bench_seen', static function (int $index) use (&$seen): void {
            $seen += $index;
        });
        $out = [];
        for ($round = 0; $round < 20; $round++) {
            foreach ($headings as $index => $heading) {
                $out[] = apply_filters('bench_title', $heading, $index);
            }
        }
        $out[] = $seen;
        return $out;
    },

    'escape' => static function (array $corpus) use ($headings): array {
        $out = [];
        foreach ($headings as $heading) {
            $out[] = esc_html($heading);
            $out[] = esc_attr($heading);
        }
        foreach ($corpus as [, $content]) {
            $out[] = esc_html($content);
        }
        return $out;
    },

    'slugs' => static function (array $corpus) use ($headings): array {
        $out = [];
        for ($round = 0; $round < 20; $round++) {
            foreach ($headings as $heading) {
                $out[] = sanitize_title($heading . ' ' . $round);
            }
        }
        return $out;
    },

    'wpautop' => static function (array $corpus) use ($classic): array {
        $out = [];
        foreach ($classic as $content) {
            $out[] = wpautop($content);
        }
        return $out;
    },

    'wptexturize' => static function (array $corpus): array {
        $out = [];
        foreach ($corpus as [, $content]) {
            $out[] = wptexturize($content);
        }
        return $out;
    },

    'blocks' => static function (array $corpus): array {
        $out = [];
        foreach ($corpus as [, $content, $hasBlocks]) {
            if ($hasBlocks) {
                $out[] = parse_blocks($content);
            }
        }
        return $out;
    },

    'kses' => static function (array $corpus): array {
        $out = [];
        foreach ($corpus as [, $content]) {
            $out[] = wp_kses_post($content);
        }
        return $out;
    },
];
