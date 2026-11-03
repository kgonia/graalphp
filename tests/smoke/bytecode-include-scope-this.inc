<?php
// Included by bytecode-include-scope.php into Renderer::render() and a closure bound to Renderer: the including
// code's $this, static class and calling scope (private and protected properties)
$items[] = count($items);
return [get_class($this), static::class, $this->secret, $this->shared, $title, get_object_vars($this),
    isset($items) ? $items : null];
