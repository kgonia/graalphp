<?php
class Cycle {
    public $self;
    public static int $destroyed = 0;
    function __construct() { $this->self = $this; }
    function __destruct() { self::$destroyed++; gc_collect_cycles(); }
}
for ($i = 0; $i < 24000; $i++) { $value = new Cycle(); unset($value); }
echo Cycle::$destroyed > 0, "\n";
gc_collect_cycles();
echo Cycle::$destroyed, "\n";
