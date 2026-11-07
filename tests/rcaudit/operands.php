<?php
// Owning temporaries consumed by an operation (not moved into a call): php-src frees a TMP or VAR operand once the
// opcode used it (FREE_OP), before the result is used. Expected: PHP 8.5 (tests/rcaudit/operands.expected).
class D {
    function __construct(public $n) {}
    function __destruct() { echo "~{$this->n} "; }
    function m() { return $this->n; }
}
class P {
    public $kid;
    function __construct(public $n) { $this->kid = new D("$n.kid"); }
    function __destruct() { echo "~{$this->n} "; }
}
function make($n) { return new D($n); }
echo "1: "; echo is_object(new D("a")) ? "y " : "n ", "after "; echo "|\n";
echo "2: "; echo (new D("b"))->n, " after "; echo "|\n";
echo "3: "; echo make("c")->n, " after "; echo "|\n";
echo "4: "; echo make("d")->m(), " after "; echo "|\n";
echo "5: "; echo count([new D("e")]), " after "; echo "|\n";
echo "6: "; echo get_class(new D("f")), " after "; echo "|\n";
echo "7: "; echo (new D("g") == null) ? "eq " : "ne ", "after "; echo "|\n";
echo "8: "; echo strlen(make("h")->n), " after "; echo "|\n";
echo "9: "; $x = make("i")->n . " "; echo "assigned "; echo "|\n";
echo "10: "; echo make("j")->m() . make("k")->m(), " after "; echo "|\n";
echo "11: "; if (new D("c")) { echo "body "; } echo "|
";
echo "12: "; $i = 0; while (make("w$i")->n && $i++ < 1) { echo "body "; } echo "|
";
echo "13: "; echo make("t") ? "t " : "f "; echo "|
";
echo "14: "; switch (make("s")->n) { case "s": echo "case "; } echo "|
";
echo "15: "; echo (new P("p"))->kid->n, " after "; echo "|
";
echo "16: "; $o = make("x1") ?: null; echo "kept "; $o = null; echo "|
";
echo "17: "; foreach ([make("e1")] as $v) { echo "body "; } echo "after "; unset($v); echo "|
";
echo "18: "; echo (string) make("s1")->n, (make("s2") == null ? " eq" : " ne"), " after "; echo "|
";
