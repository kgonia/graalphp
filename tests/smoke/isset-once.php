<?php
// isset() and ?? evaluate the container and the key once (php-src's FETCH_DIM_IS / ISSET_ISEMPTY_DIM_OBJ): an
// ArrayAccess container's methods run once per level, a key's side effects happen once.
class Log implements ArrayAccess {
    public array $data;
    public function __construct(private string $name, array $data) { $this->data = $data; }
    public function offsetExists(mixed $k): bool { echo "{$this->name}.exists($k) "; return isset($this->data[$k]); }
    public function offsetGet(mixed $k): mixed { echo "{$this->name}.get($k) "; return $this->data[$k]; }
    public function offsetSet(mixed $k, mixed $v): void { $this->data[$k] = $v; }
    public function offsetUnset(mixed $k): void { unset($this->data[$k]); }
}
$o = new Log('o', ['a' => new Log('i', ['b' => 'deep', 'n' => null]), 's' => 'str']);
var_dump($o['a']['b'] ?? 'none');
echo "\n";
var_dump($o['a']['n'] ?? 'none');
echo "\n";
var_dump($o['a']['x'] ?? 'none');
echo "\n";
var_dump(isset($o['a']['b']), isset($o['z']['b']));
echo "\n";
$i = 0;
$s = 'hello';
$arr = ['s' => 'hello'];
var_dump($s[$i++] ?? 'no', $i);
var_dump($arr['s'][$i++] ?? 'no', $i);
var_dump(isset($arr['s'][$i++]), $i);
var_dump($s['x'] ?? 'no', isset($s['1']), isset($s[-5]), isset($s[5]));
$nested = ['a' => ['b' => ['c' => 1]]];
var_dump($nested['a']['b']['c'] ?? 0, $nested['a']['x']['c'] ?? 0, isset($nested['a']['b']['c']));
