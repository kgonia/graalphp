<?php
class Owner {
    public mixed $edge = null;
    public function attach(): void {
        $self = $this;
        $this->edge = function () use (&$self) { return $self; };
    }
    public function __destruct() {
        global $saved;
        $saved = $this;
        echo "saved\n";
    }
}
$x = new Owner();
$x->attach();
echo gc_collect_cycles(), "\n";
unset($x);
echo gc_collect_cycles(), "\n";
echo is_callable($saved->edge), "\n";
unset($saved);
echo gc_collect_cycles(), "\n";
echo gc_collect_cycles(), "\n";
