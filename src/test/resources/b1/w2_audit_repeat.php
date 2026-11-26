<?php
class Counter {
    public static int $value = 1;
    public function __toString(): string { return 'late'; }
}
Counter::$value++;
echo Counter::$value, "/", new Counter(), "\n";
