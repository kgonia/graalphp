<?php
// Shared by hooks-a.php and hooks-b.php, so both request contexts install the same hidden classes.
// Bench_Callbacks and the 'hooks' kernel closure are verbatim from bench/gate1/kernels.php. There, $headings is
// computed at load time with preg_match_all (bench_headings); here the host extracts the same headings
// (B1BenchMain.headings, as bench/b1-precheck/probe-b Corpus.java does) and passes them to this factory.

final class Bench_Callbacks {
    public function suffix(string $value): string {
        return $value . '|m';
    }

    public static function wrap(string $value, int $index): string {
        return '<' . $index . '>' . $value;
    }
}

return static function (array $headings) {
    return static function (array $corpus) use ($headings): array {
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
    };
};
