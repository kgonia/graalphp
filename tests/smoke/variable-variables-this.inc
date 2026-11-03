<?php
// included by variable-variables.php from a method: "this" by name is the including call's $this, but isset() and
// empty() look it up in the symbol table only
$t = 'this';
var_dump(isset($$t), empty($$t), is_object($$t ?? null), $$t === $this);
