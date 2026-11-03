<?php
// included from Includer::load: its variables, its $this and its class scope
$fromInclude = $local . ' seen';
return [$fromInclude, $this->own, self::WHO, static::class, get_class($this), isset($x)];
