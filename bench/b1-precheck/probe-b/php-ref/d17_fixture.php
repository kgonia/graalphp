<?php
declare(strict_types=1);

// Probe B D17 Destructor Correctness Fixture (Rev 2)
// Verified byte-for-byte against PHP 8.5.13 CLI.

// Part 1: WP_HTML_Token::__destruct callback pattern releasing bookmarks past 10,000 tokens
// (mirrors wp-includes/html-api/class-wp-html-token.php:113 call_user_func($this->on_destroy, $this->bookmark_name))
class HtmlToken {
    public string $bookmark_name;
    public $on_destroy;
    public int $index;

    public function __construct(int $index, string $bookmark_name, callable $on_destroy) {
        $this->index = $index;
        $this->bookmark_name = $bookmark_name;
        $this->on_destroy = $on_destroy;
    }

    public function __destruct() {
        if (is_callable($this->on_destroy)) {
            ($this->on_destroy)($this->bookmark_name, $this->index);
        }
    }
}

class BookmarkProcessor {
    public array $bookmarks = [];
    public int $peak_bookmarks = 0;
    public int $released = 0;

    public function process(int $count): void {
        $on_destroy = function (string $name, int $idx): void {
            unset($this->bookmarks[$name]);
            $this->released++;
            if ($idx < 3 || $idx >= 11997) {
                echo "bookmark_dtor:", $idx, ":active=", count($this->bookmarks), "\n";
            }
        };
        for ($i = 0; $i < $count; $i++) {
            $bname = "bm_" . $i;
            $this->bookmarks[$bname] = $i;
            $cur = count($this->bookmarks);
            if ($cur > $this->peak_bookmarks) {
                $this->peak_bookmarks = $cur;
            }
            $tok = new HtmlToken($i, $bname, $on_destroy);
            // Releasing $tok must invoke $on_destroy promptly before next iteration
            unset($tok);
        }
    }
}

$proc = new BookmarkProcessor();
$proc->process(12000);
echo "bookmark_summary:active=", count($proc->bookmarks), ",peak=", $proc->peak_bookmarks, ",released=", $proc->released, "\n";
unset($proc);
echo "part1_gc:", gc_collect_cycles(), "\n";

// Part 2: Unreachable 2-object cycle collected by explicit gc_collect_cycles()
class CycleNode {
    public string $name;
    public mixed $peer = null;

    public function __construct(string $name) {
        $this->name = $name;
    }

    public function __destruct() {
        echo "cycle_dtor:", $this->name, "\n";
    }
}

function make_object_cycle(): void {
    $a = new CycleNode('A');
    $b = new CycleNode('B');
    $a->peer = $b;
    $b->peer = $a;
    echo "before_unset_cycle\n";
    unset($a, $b);
    echo "after_unset_cycle\n";
}

make_object_cycle();
$collected1 = gc_collect_cycles();
echo "gc_collect_1:", $collected1, "\n";

// Part 3: Unreachable cycle through arrays
function make_array_cycle(): void {
    $c = new CycleNode('C');
    $d = new CycleNode('D');
    $c->peer = ['next' => $d];
    $d->peer = ['next' => $c];
    unset($c, $d);
    echo "after_unset_array_cycle\n";
}

make_array_cycle();
$collected2 = gc_collect_cycles();
echo "gc_collect_2:", $collected2, "\n";

// Part 4: Resurrection inside __destruct (runs __destruct only once)
$resurrected_holder = null;
class Phoenix {
    public string $name;
    public function __construct(string $name) { $this->name = $name; }
    public function __destruct() {
        global $resurrected_holder;
        echo "phoenix_dtor:", $this->name, "\n";
        $resurrected_holder = $this;
    }
}

function run_phoenix(): void {
    $p = new Phoenix('P1');
    echo "phoenix_unset_first\n";
    unset($p);
    echo "phoenix_after_first_unset\n";
}

run_phoenix();
echo "phoenix_unset_global\n";
$resurrected_holder = null;
echo "phoenix_done\n";

// Part 5: Request shutdown order with aliased globals + destructor mutation + arrays
// Tests Zend's iterative rc==1 reverse-globals pass followed by handle-order object-store pass!
class ShutdownObj {
    public string $label;
    public function __construct(string $label) {
        $this->label = $label;
    }
    public function __destruct() {
        echo "shutdown_dtor:", $this->label, "\n";
        if ($this->label === 'G2') {
            // Mutate global during shutdown pass: drops G1 rc from 2 -> 1 so next iteration collects G1 before A1/A2!
            global $g1_alias;
            $g1_alias = null;
        }
    }
}

$g1 = new ShutdownObj('G1');
$g1_alias = $g1; // rc == 2 initially (skipped in reverse pass 1!)
$g3 = new ShutdownObj('G3'); // aliased by g3_alias, stays rc == 2 until object-store handle-order pass!
$g3_alias = $g3;
$g2 = new ShutdownObj('G2'); // rc == 1 (destroyed in reverse pass 1, clears $g1_alias -> G1 destroyed in reverse pass 2!)
$gArr = [new ShutdownObj('A1'), new ShutdownObj('A2')];
echo "script_end\n";
