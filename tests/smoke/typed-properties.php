<?php
// Typed properties and class constants: uninitialized state, checked and converted writes (coercive mode), the
// assignment's value, ++ past PHP_INT_MAX, unset() with __get/__set, references, var_dump's property table, and
// the inheritance checks that pass. Each case runs at the top level (the tree) and in a function (bytecode).

class Post {
    public int $id;
    public ?string $title = null;
    public float $score = 1;
    protected array $meta = [];
    private bool $draft = false;
    public $plain;
    public const int LIMIT = 10;
    public const float RATIO = 2;

    public function meta(string $key, $value): static {
        $this->meta[$key] = $value;
        return $this;
    }
}

class Page extends Post {
    public int $id = 0;
    public const int LIMIT = 20;
}

class Lazy {
    public int $value;

    public function __get($name) {
        echo "  __get($name)\n";
        return $this->$name = 42;
    }

    public function __set($name, $value) {
        echo "  __set($name)\n";
        $this->$name = $value;
    }
}

set_error_handler(function ($no, $message, $file, $line) {
    echo "  [$no] $message line $line\n";
    return true;
});

/** var_dump without object handles, which this runtime numbers differently. */
function dump(...$values) {
    ob_start();
    var_dump(...$values);
    echo preg_replace('/#\d+/', '#', ob_get_clean());
}

function attempt(string $label, callable $f) {
    try {
        echo $label, ": ";
        dump($f());
    } catch (Error $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
    }
}

function cases(string $where) {
    echo "-- $where\n";
    $p = new Post();
    dump($p);
    attempt("read uninitialized", fn() => $p->id);
    attempt("isset uninitialized", fn() => isset($p->id));
    attempt("coalesce uninitialized", fn() => $p->id ?? "default");
    attempt("assign numeric string", fn() => $p->id = "12");
    attempt("assign float", fn() => $p->id = 7.0);
    attempt("assign fractional float", fn() => $p->id = 7.5);
    attempt("assign array", fn() => $p->id = []);
    attempt("assign null to nullable", fn() => $p->title = null);
    attempt("assign int to string", fn() => $p->title = 5);
    attempt("assign int to float", fn() => $p->score = 3);
    attempt("compound", fn() => $p->score += 1);
    $p->id = PHP_INT_MAX;
    attempt("increment past max", fn() => $p->id++);
    attempt("fluent static", fn() => get_class($p->meta("k", 1)));
    attempt("auto-initialize array", function () { $q = new Post(); $q->title[] = 1; });
    attempt("reference to uninitialized", function () { $q = new Post(); $r = &$q->id; });
    attempt("reference to nullable", function () { $q = new Post(); $r = &$q->title; return $r; });
    unset($p->id);
    dump($p);
    $page = new Page();
    dump($page, Page::LIMIT, Post::RATIO);
    $lazy = new Lazy();
    attempt("never initialized", fn() => $lazy->value);
    unset($lazy->value);
    attempt("unset then read", fn() => $lazy->value);
    unset($lazy->value);
    $lazy->value = "5";
    dump($lazy);
}

cases("function");
echo "-- top level\n";
$p = new Post();
try {
    $p->id = "x";
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
dump($p->id = "3", $p->score = 2, $p);
