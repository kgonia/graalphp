<?php
// Issue 061: offsetUnset and offsetSet on an ArrayIterator while foreach walks it.
function run($init, $at, $op) {
  $it = new ArrayIterator($init); $out = '';
  foreach ($it as $k => $v) { $out .= $k; if ($k === $at) $op($it); }
  return $out;
}
echo run(['a'=>1,'b'=>2,'c'=>3], 'a', fn($it) => $it->offsetUnset('b')), "\n";
echo run(['a'=>1,'b'=>2,'c'=>3], 'b', function ($it) { $it->offsetUnset('a'); $it->offsetSet('d', 4); }), "\n";
echo run(['a'=>1,'b'=>2,'c'=>3], 'a', fn($it) => $it->offsetUnset('a')), "\n";
echo run(['a'=>1,'b'=>2,'c'=>3], 'b', fn($it) => $it->offsetUnset('b')), "\n";
echo run(['a'=>1,'b'=>2,'c'=>3], 'c', fn($it) => $it->offsetSet('a', 9)), "\n";
echo run(['a'=>1,'b'=>2,'c'=>3], 'a', fn($it) => $it->append(7)), "\n";
echo run(['b'=>2,'a'=>1,'c'=>3], 'b', fn($it) => $it->ksort()), "\n";
echo run(['c'=>3,'b'=>2,'a'=>1], 'b', fn($it) => $it->ksort()), "\n";
echo run(['a'=>1,'b'=>2,'c'=>3, 'd'=>4], 'b', function ($it) { $it->offsetUnset('a'); $it->ksort(); }), "\n";
$a = [1, 2, 3]; next($a); $it = new ArrayIterator($a); var_dump($it->key(), $it->current());
$it = new ArrayIterator([1, 2, 3]); $it->next(); $it->offsetUnset(1); var_dump($it->key(), $it->valid()); $it->next(); var_dump($it->key());
$it = new ArrayIterator(['x' => 1, 'y' => 2]); $it->seek(1); var_dump($it->key()); $it->offsetUnset('x'); var_dump($it->key()); $it->rewind(); var_dump($it->key());
$it = new ArrayIterator([1, 2]); foreach ($it as $k => $v) { echo $k; if ($k == 0) { $it->offsetUnset(1); $it->offsetSet(1, 5); } } echo "\n";
// many deletes and appends while it walks: the holes are compacted under the iterator, which nothing shows
$it = new ArrayIterator(range(0, 9)); $out = [];
foreach ($it as $k => $v) { $out[] = $k; if ($k < 30) { $it->offsetUnset($k); $it->offsetSet($k + 100, $v); } }
echo implode(',', $out), "\n", implode(',', array_keys($it->getArrayCopy())), "\n";
