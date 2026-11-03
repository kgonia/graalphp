<?php
class Cache { public $cache = []; function get($key, $group = 'default') {
    if (isset($this->cache[$group][$key])) { $found = true; return is_object($this->cache[$group][$key]) ? clone $this->cache[$group][$key] : $this->cache[$group][$key]; }
    return false; } }
function cget($key, $group = '') { global $cachex; return $cachex->get($key, $group); }
function salted($key, $group, $salt) {
    $salt = is_array($salt) ? implode(':', $salt) : $salt;
    $cache = cget($key, $group);
    if (!is_array($cache)) { return false; }
    if (!isset($cache['salt']) || !isset($cache['data']) || $salt !== $cache['salt']) { return false; }
    return $cache['data'];
}
function filt($tag, $value) { global $wp_filter, $wp_current_filter; if (!isset($wp_filter[$tag])) { return $value; } $wp_current_filter[] = $tag; $value = $value . $wp_filter[$tag]; array_pop($wp_current_filter); return $value; }
function many($v) { $v = filt('a', $v); $v = filt('b', $v); $v = filt('c', $v); $v = filt('d', $v); return $v; }
$cachex = new Cache(); $cachex->cache['g']['k'] = ['salt' => 's', 'data' => 'd'];
$wp_filter = ['b' => '!']; $wp_current_filter = [];
for ($i = 0; $i < 30000; $i++) { salted('k', 'g', 's'); salted('k', 'g', ['s']); many('x'); }
echo "ok\n";
