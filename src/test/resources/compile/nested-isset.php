<?php
// Nested isset() and ?? on arrays, as WordPress's block supports and theme.json code write them
// (wp_render_layout_support_flag): each level once evaluated its container two more times, so the compiled code
// grew as 2^depth. Strings and ArrayAccess objects as containers come last, after compilation.
function layout(array $block): string {
    $gap = $block['attrs']['style']['spacing']['blockGap'] ?? null;
    $type = $block['attrs']['layout']['type'] ?? 'default';
    $justify = isset($block['attrs']['layout']['justifyContent']) ? $block['attrs']['layout']['justifyContent'] : '';
    $wide = $block['attrs']['layout']['wideSize'] ?? $block['attrs']['layout']['contentSize'] ?? '';
    return $type . ':' . ($gap ?? '-') . ':' . $justify . ':' . $wide;
}
$blocks = [
    ['attrs' => ['layout' => ['type' => 'flex', 'justifyContent' => 'left'], 'style' => ['spacing' => ['blockGap' => '1em']]]],
    ['attrs' => ['layout' => ['contentSize' => '600px']]],
    ['attrs' => []],
];
$out = 0;
for ($i = 0; $i < 3000; $i++) {
    $out += strlen(layout($blocks[$i % 3]));
}
$s = 'text';
$out += isset($s[1]) ? 1 : 0;
echo $out, "\n";
