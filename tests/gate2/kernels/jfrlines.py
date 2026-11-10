"""Where a JFR recording's execution samples are, by the bytecode operation that was running (the generated
interpreter's handleX_ frame) and inside it by the Java line (wiki/ceiling.md).
Run the JVM with -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -Dpolyglot.compiler.NodeSourcePositions=true
-Djdk.graal.TrackNodeSourcePosition=true: without the first a compiled function's samples all read as its last
safepoint, without the other two as one of a few positions. A sample can land one instruction late (skid): read a
line's count as "this line or the one before it in the machine code".
Usage: jfr print --events jdk.ExecutionSample --stack-depth 12 FILE.jfr | python -I jfrlines.py [SKIP] [LINES] [NS]
       the first SKIP samples (the warm-up) are left out (default 0); LINES inner positions per operation (default
       6); NS, the kernel's ns per operation, turns the shares into ns.
"""
import collections
import re
import sys

skip = int(sys.argv[1]) if len(sys.argv) > 1 else 0
lines = int(sys.argv[2]) if len(sys.argv) > 2 else 6
ns = float(sys.argv[3]) if len(sys.argv) > 3 else None
frame = re.compile(r'^\s+(\S+)\(.*\)\s+line: (\d+)')
handler = re.compile(r'CachedBytecodeNode\.handle([A-Za-z0-9]+?)_?$')
samples = []
stack = None
for line in sys.stdin:
    if 'stackTrace = [' in line:
        stack = []
    elif stack is not None:
        m = frame.match(line)
        if m:
            stack.append(('.'.join(m.group(1).split('.')[-2:]), m.group(2)))
        elif line.strip().startswith(']'):
            samples.append(stack)
            stack = None
samples = [s for s in samples[skip:] if s]
total = len(samples)
ops = collections.Counter()
inner = collections.defaultdict(collections.Counter)
for s in samples:
    op = None
    above = []
    for name, line in s:
        m = handler.search(name)
        if m:
            op = m.group(1)
            break
        if 'CachedBytecodeNode.continueAt' in name or 'PhpBytecodeRootNodeGen.' in name:
            op = '(dispatch)'
            break
        above.append(f'{name.split("$")[-1]}:{line}')
    if op is None:
        op = '(outside: ' + s[0][0] + ')'
        above = []
    ops[op] += 1
    inner[op][' < '.join(above[:3]) or '(the handler itself)'] += 1


def share(n):
    text = f'{100 * n / total:5.1f}%'
    return text + (f' {ns * n / total:5.2f} ns' if ns else '')


print(f'{total} samples')
for op, n in ops.most_common(30):
    print(f'{share(n)}  {op}')
    for key, k in inner[op].most_common(lines):
        print(f'      {share(k)}  {key}')
