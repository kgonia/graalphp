"""Writes target/cold/functions.php for bench/cold/run.php: two sets of COUNT small functions of four shapes (a loop
with a branch, an array built then walked by foreach, strings joined, calls of another function of the set), each
called a few times, so a pass over a set is mostly first executions: translating, specializing, the interpreter's
cold paths.

Usage: python bench/cold/generate.py [count]   (default 2000 per set)
"""
import os
import sys

count = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
os.makedirs(os.path.join(root, "target", "cold"), exist_ok=True)
out = [f"<?php\nconst COLD_COUNT = {count};\n"]
for k in range(2 * count):
    c1, c2, c3 = 3 + k % 17, 5 + k % 23, 200 + k % 300
    shape = k % 4
    if shape == 0:
        body = f"""    $s = {c1};
    for ($i = 0; $i < $n; $i++) {{
        $s += $i * $m + {c2};
        if ($s > {c3}) {{
            $s -= {c3};
        }}
    }}
    return $s;"""
    elif shape == 1:
        body = f"""    $a = [];
    for ($i = 0; $i < $n; $i++) {{
        $a[] = $i * {c1} % {c2};
    }}
    $t = 0;
    foreach ($a as $k => $v) {{
        $t += $k * $v + $m;
    }}
    return $t + count($a);"""
    elif shape == 2:
        body = f"""    $parts = ['p{c1}'];
    for ($i = 0; $i < $n; $i++) {{
        $parts[] = 'x' . ($i + $m);
    }}
    $s = '';
    foreach ($parts as $p) {{
        $s = $s . $p . ',';
    }}
    return $s;"""
    else:
        callee = k - 3  # a loop function (shape 0) of the same set
        body = f"""    $r = 0;
    for ($i = 0; $i < $n; $i += 4) {{
        $r = $r + cold_{callee}(($i + {c1}) % 5, $m);
    }}
    return $r;"""
    out.append(f"function cold_{k}($n, $m) {{\n{body}\n}}\n")
open(os.path.join(root, "target", "cold", "functions.php"), "w", newline="\n").write("\n".join(out))
print(f"wrote target/cold/functions.php (2 sets of {count} functions)")
