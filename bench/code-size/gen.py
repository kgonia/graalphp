"""Fixtures of one statement pattern repeated N times in a method, to see each pattern's IR nodes and code size.
Usage: python gen.py OUTDIR N [dyn]   dyn: the array's keys are built at run time, so no site's remembered slot hits
(the literal keys are other instances) and the sites become probes."""
import os
import sys
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
N = int(sys.argv[2])
dyn = len(sys.argv) > 3
patterns = {
    'read':      "$x = $q['k%d'];",
    'isset':     "if (isset($q['k%d'])) { $n++; }",
    'empty':     "if (!empty($q['k%d'])) { $n++; }",
    'coalesce':  "$x = $q['k%d'] ?? '';",
    'write':     "$q['k%d'] = $n;",
    'writestr':  "$q['k%d'] = 'v';",
    'coalasg':   "$q['k%d'] ??= 'd';",
    'propwrite': "$this->flags['k%d'] = $n;",
    'propread':  "$x = $this->flags['k%d'];",
    'append':    "$parts[] = $n;",
    'assign':    "$x = $n; $y = $x;",
    'interp':    "$x = \"k={$q['k%d']}\";",
    'lread':     "$x = $a['k%d'];",
    'lisset':    "if (isset($a['k%d'])) { $n++; }",
    'lwrite':    "$a['k%d'] = $n;",
    'lwritestr': "$a['k%d'] = 'v';",
    'kexists':   "if (array_key_exists('k%d', $a)) { $n++; }",
    'issetread': "if (isset($q['k%d'])) { $x = $q['k%d']; }",
    'lissetread': "if (isset($a['k%d'])) { $x = $a['k%d']; }",
    'lternary':  "$x = isset($a['k%d']) ? $a['k%d'] : '';",
    # keys the array doesn't hold: an optional key's test, which no remembered slot answers
    'labsent':   "if (isset($a['m%d'])) { $n++; }",
    'lcoalabs':  "$x = $a['m%d'] ?? '';",
    'kexistabs': "if (array_key_exists('m%d', $a)) { $n++; }",
}
suffix = 'd' if dyn else ''
for name, pattern in patterns.items():
    body = '\n'.join('        ' + pattern.replace('%d', str(i)) for i in range(N))
    if dyn:
        make = "$vars = []; for ($j = 0; $j < %d; $j++) { $vars['k' . $j] = 'v' . $j; }" % N
    else:
        make = "$vars = [" + ', '.join("'k%d' => 'v%d'" % (i, i) for i in range(N)) + "];"
    open(f'{out}/{name}{suffix}.php', 'w', newline='\n').write(f'''<?php
final class Query {{
    public array $vars = [];
    public array $flags = [];
    public function __construct(array $vars) {{ $this->vars = $vars; $this->flags = $vars; }}
    public function sql(array $a): int {{
        $q = &$this->vars;
        $n = 0; $x = ''; $y = ''; $parts = [];
{body}
        return $n + strlen($x) + count($parts);
    }}
}}
$total = 0;
for ($i = 0; $i < 600; $i++) {{
    {make}
    $query = new Query($vars);
    $total += $query->sql($vars);
}}
echo $total, "\n";
''')
print(' '.join(n + suffix for n in patterns))
