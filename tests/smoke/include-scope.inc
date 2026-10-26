<?php
// Included by include-scope.php: its variables are the including scope's.
$seen = isset($local) ? "local=$local" : 'no local';
$local = ($local ?? 0) + 1;
$created = 'made by the include';
$list[] = 'appended';
if (isset($counter)) {
    $counter++;
}
if (isset($this)) {
    $seen .= ' this=' . get_class($this) . ' static=' . static::class . ' name=' . $this->name;
}
if (isset($drop)) {
    unset($drop);
}
$capture = function () use ($local) {
    return "captured $local";
};
$arrow = fn() => "arrow $local";
global $shared;
$shared = ($shared ?? 'global') . '+';
extract(['extracted' => 'from extract']);
$compacted = compact('local', 'created');
$names = ['local'];
$dynamic = compact($names);
if (!empty($nested)) {
    $nested = false;
    include __DIR__ . '/include-scope-nested.inc';
}
return $seen;
