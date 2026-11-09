"""Differential fuzzing of object lifetimes: random PHP programs whose objects say when they die.

Each program is a sequence of statements over a few variables, at the top level and inside functions. Objects of
class D carry a label; their __destruct prints "~label". After every statement the program prints "@k", so a
destructor's line lands between the markers of the statement that freed the object, which is php-src's moment.
The last statement prints "@end"; what follows is shutdown (globals, then objects left), compared separately.

The statements are the places a reference count changes: assignment, overwrite, unset, references, array elements
and their copies, properties, temporaries passed to and returned from calls, fluent calls on temporaries, clone,
array_pop/array_shift, closures capturing values, `global`, exceptions whose traces hold arguments, and locals
freed when a function returns. Variables start as null and are mostly read directly (a CV operand, the common path in php-src and in the
counting code), sometimes as `$v ?? null` (a temporary); a read after unset warns on both sides and the warnings
are compared. Element, property, call and pop operations are guarded (instanceof, is_array), so a program never
throws.

Usage: python gen.py SEED COUNT OUTDIR [--features=a,b,...]   writes OUTDIR/p<SEED+i>.php
Features (default all): refs, arrays, props, calls, fluent, clone, pops, closures, globals, exceptions, functions
"""
import os
import random
import sys

FEATURES = ["refs", "arrays", "props", "calls", "fluent", "clone", "pops", "closures", "globals", "exceptions",
            "functions"]

PRELUDE = r'''<?php
class D {
    public $n; public $p; public $q;
    function __construct($n) { $this->n = $n; }
    function __destruct() { echo "~{$this->n}\n"; }
    function self() { return $this; }
    function setP($x) { $this->p = $x; return $this; }
    function take($x) { $l = $x; return 1; }
    function __clone() { $this->n = $this->n . 'c'; }
}
class Boom extends Exception {}
function id($x) { return $x; }
function drop($x) { return 0; }
function pair($a, $b) { return [$a, $b]; }
function fresh($n) { $t = new D($n); return $t; }
function local($n) { $l = new D($n); echo "in-local\n"; return 0; }
function thrower($x) { throw new Boom('b'); }
'''


class Gen:
    def __init__(self, seed, features):
        self.r = random.Random(seed)
        self.f = set(features)
        self.label = 0
        self.mark = 0
        self.funcs = []

    def new(self):
        self.label += 1
        return f"new D('o{self.label}')"

    def var(self, pool):
        return self.r.choice(pool)

    def rd(self, v):
        """A read of v: mostly the variable itself (a CV operand, the common path), sometimes `v ?? null` (a TMP)."""
        return v if self.r.random() < 0.8 else f"({v} ?? null)"

    def stmt(self, pool, depth):
        r = self.r
        a, b = self.var(pool), self.var(pool)
        kinds = ["new", "new", "copy", "null", "unset"]
        if "refs" in self.f:
            kinds += ["ref"]
        if "arrays" in self.f:
            kinds += ["arr", "push", "elem", "arrcopy"]
        if "props" in self.f:
            kinds += ["prop", "propnew", "propnull", "read"]
        if "calls" in self.f:
            kinds += ["id", "idtemp", "drop", "droptemp", "fresh", "local", "pair", "take"]
        if "fluent" in self.f:
            kinds += ["fluent", "fluentstore", "setp"]
        if "clone" in self.f:
            kinds += ["clone"]
        if "pops" in self.f:
            kinds += ["pop", "shift"]
        if "closures" in self.f:
            kinds += ["closure", "closurecall"]
        if "exceptions" in self.f:
            kinds += ["throw", "unsete"]
        if "functions" in self.f and depth == 0:
            kinds += ["func", "func"]
        if "globals" in self.f and depth == 0:
            kinds += ["global"]
        k = r.choice(kinds)
        if k == "new":
            return f"{a} = {self.new()};"
        if k == "copy":
            return f"{a} = {self.rd(b)};"
        if k == "null":
            return f"{a} = null;"
        if k == "unset":
            return f"unset({a});"
        if k == "ref":
            return f"{a} = &{b};"
        if k == "arr":
            return f"{a} = [{self.rd(b)}, {self.new()}];"
        if k == "push":
            return f"if (is_array({a} ?? null)) {{ {a}[] = {self.rd(b)}; }}"
        if k == "elem":
            return f"if (is_array({a} ?? null)) {{ {a}['k'] = {self.new()}; }}"
        if k == "arrcopy":
            return f"if (is_array({b} ?? null)) {{ {a} = {b}; {a}[] = 1; }}"
        if k == "prop":
            return f"if (({a} ?? null) instanceof D) {{ {a}->p = {self.rd(b)}; }}"
        if k == "propnew":
            return f"if (({a} ?? null) instanceof D) {{ {a}->q = {self.new()}; }}"
        if k == "propnull":
            return f"if (({a} ?? null) instanceof D) {{ {a}->{r.choice('pq')} = null; }}"
        if k == "read":
            return f"if (({b} ?? null) instanceof D) {{ {a} = {b}->{r.choice('pq')}; }}"
        if k == "id":
            return f"{a} = id({self.rd(b)});"
        if k == "idtemp":
            return f"{a} = id({self.new()});"
        if k == "drop":
            return f"drop({self.rd(b)});"
        if k == "droptemp":
            return f"drop({self.new()});"
        if k == "fresh":
            self.label += 1
            return f"{a} = fresh('o{self.label}');"
        if k == "local":
            self.label += 1
            return f"local('o{self.label}');"
        if k == "pair":
            return f"{a} = pair({self.rd(b)}, {self.new()});"
        if k == "take":
            return f"if (({a} ?? null) instanceof D) {{ {a}->take({self.new()}); }}"
        if k == "fluent":
            return f"({self.new()})->self();"
        if k == "fluentstore":
            return f"{a} = ({self.new()})->self();"
        if k == "setp":
            return f"{a} = ({self.new()})->setP({self.rd(b)});"
        if k == "clone":
            return f"if (({b} ?? null) instanceof D) {{ {a} = clone {b}; }}"
        if k == "pop":
            return f"if (is_array({b} ?? null)) {{ {a} = array_pop({b}); }}"
        if k == "shift":
            return f"if (is_array({b} ?? null)) {{ {a} = array_shift({b}); }}"
        if k == "closure":
            return f"{b} ??= null; {a} = function () use ({b}) {{ return {b}; }};"
        if k == "closurecall":
            return f"if (({a} ?? null) instanceof Closure) {{ {b} = {a}(); }}"
        if k == "throw":
            return f"try {{ thrower({self.new()}); }} catch (Boom $e) {{ echo \"caught\\n\"; }}"
        if k == "unsete":
            return "unset($e);"
        if k == "func":
            return self.function(pool)
        if k == "global":
            return self.global_function(pool)
        raise AssertionError(k)

    def marked(self, s):
        self.mark += 1
        return f"{s} echo \"@{self.mark}\\n\";"

    def function(self, pool):
        name = f"f{len(self.funcs)}"
        locals_ = ["$l0", "$l1", "$l2"]
        params = self.r.sample(pool, 1)
        body = [self.marked(self.stmt(locals_ + ["$a0"], 1)) for _ in range(self.r.randint(2, 6))]
        ret = self.r.choice(["return 0;", f"return {self.rd(self.r.choice(locals_))};", "return $a0;"])
        self.funcs.append(f"function {name}($a0) {{\n    $l0 = $l1 = $l2 = null;\n    " + "\n    ".join(body)
                          + f"\n    {ret}\n}}")
        return f"{self.r.choice(pool)} = {name}({self.rd(params[0])});"

    def global_function(self, pool):
        name = f"g{len(self.funcs)}"
        g = self.r.choice(pool)
        body = [self.marked(self.stmt([g, "$l0"], 1)) for _ in range(self.r.randint(1, 4))]
        self.funcs.append(f"function {name}() {{\n    global {g};\n    $l0 = null;\n    " +"\n    ".join(body) + "\n}")
        return f"{name}();"

    def program(self, n):
        pool = ["$v0", "$v1", "$v2", "$v3"]
        main = [self.marked(self.stmt(pool, 0)) for _ in range(n)]
        return (PRELUDE + "\n".join(self.funcs) + "\n$v0 = $v1 = $v2 = $v3 = null;\n" + "\n".join(main) + '\necho "@end\\n";\n')


def main():
    seed, count, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
    features = FEATURES
    for a in sys.argv[4:]:
        if a.startswith("--features="):
            features = a.split("=", 1)[1].split(",")
    os.makedirs(out, exist_ok=True)
    for i in range(count):
        g = Gen(seed + i, features)
        with open(os.path.join(out, f"p{seed + i}.php"), "w", newline="\n") as fh:
            fh.write(g.program(g.r.randint(8, 30)))


if __name__ == "__main__":
    main()
