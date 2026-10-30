#!/usr/bin/env python3
"""Splits the DFA re2c's Java backend writes into methods small enough for the JIT.

re2c's Java code model is "loop/switch": the whole DFA (php-src's scanner has about a thousand states) is one
`while (true) { switch (yystate) { case N: ... } }` loop in one method. javac refuses a method over 64 KB of
bytecode, and HotSpot doesn't JIT-compile one over 8000 bytes (HugeMethodLimit), so that method would stay in the
bytecode interpreter. This script keeps re2c's states as they are and only regroups them: consecutive states go
into chunk methods, each with the same loop over its own states; a transition to a state in another chunk stores
the DFA's registers (yystate, yych, yyaccept) in fields and returns to a dispatcher, which calls that state's chunk.
The bit-vector tables re2c declares as local arrays (allocated on every call) become static fields.

The input is re2c's output; the lines `// @dfa-begin` and `// @dfa-end` around the re2c block, and
`// @dfa-methods` where the chunk methods go, come from the .re file.

Usage: split_dfa.py <re2c output> <result>
"""
import re
import sys

#: Budget of a chunk in source characters of its states; tools/parser/generate.sh checks the result with
#: ScannerMethodSizeTest (no generated method over HugeMethodLimit).
CHUNK_CHARS = 26000

CONTINUE = 'YYCONTINUE'


def fail(message):
    sys.exit('split_dfa.py: ' + message)


def code_depths(lines):
    """The brace depth at the start of each line, counting only braces outside Java strings, chars and comments."""
    depths = []
    depth = 0
    in_comment = False
    for line in lines:
        depths.append(depth)
        i = 0
        while i < len(line):
            c = line[i]
            if in_comment:
                if line.startswith('*/', i):
                    in_comment = False
                    i += 2
                    continue
                i += 1
                continue
            if line.startswith('//', i):
                break
            if line.startswith('/*', i):
                in_comment = True
                i += 2
                continue
            if c in '"\'':
                i += 1
                while i < len(line) and line[i] != c:
                    i += 2 if line[i] == '\\' else 1
                i += 1
                continue
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
            i += 1
    return depths


def main(source, target):
    text = open(source, encoding='utf-8').read()
    lines = text.split('\n')
    begin = [i for i, l in enumerate(lines) if l.strip() == '// @dfa-begin']
    end = [i for i, l in enumerate(lines) if l.strip() == '// @dfa-end']
    methods = [i for i, l in enumerate(lines) if l.strip() == '// @dfa-methods']
    if len(begin) != 1 or len(end) != 1 or len(methods) != 1 or not begin[0] < end[0]:
        fail('expected one @dfa-begin, @dfa-end and @dfa-methods marker')
    block = lines[begin[0] + 1:end[0]]
    indent = re.match(r'\s*', lines[begin[0]]).group(0)

    # re2c's block: "{", the registers, the bit-vector tables, then the loop over the states
    pos = 0

    def skip_blank():
        nonlocal pos
        while pos < len(block) and not block[pos].strip():
            pos += 1

    def expect(pattern):
        nonlocal pos
        skip_blank()
        m = re.fullmatch(pattern, block[pos].strip()) if pos < len(block) else None
        if not m:
            fail('expected /%s/ at block line %d: %r' % (pattern, pos, block[pos] if pos < len(block) else None))
        pos += 1
        return m

    expect(r'\{')
    expect(r'int yych = 0;')
    accept = False
    skip_blank()
    if block[pos].strip() == 'int yyaccept = 0;':
        accept = True
        pos += 1
    start = expect(r'int yystate = (.+);').group(1)
    tables = []
    while True:
        skip_blank()
        m = re.fullmatch(r'final int\[\] (yybm\w*) = new int\[\]\{', block[pos].strip())
        if not m:
            break
        pos += 1
        rows = []
        while block[pos].strip() != '};':
            rows.append(block[pos].strip())
            pos += 1
        pos += 1
        tables.append((m.group(1), rows))
    expect(r'yyl: while \(true\) \{')
    expect(r'switch \(yystate\) \{')

    # the states: "case N:" lines at the switch's own depth, up to its "default:"
    body = block[pos:]
    depths = code_depths(body)
    states = []
    default = None
    for i, (line, depth) in enumerate(zip(body, depths)):
        if depth != 0:
            continue
        m = re.fullmatch(r'\s*case (\d+):\s*', line)
        if m:
            states.append([int(m.group(1)), i])
        elif re.fullmatch(r'\s*default:\s*', line):
            default = i
            break
    if not states or default is None:
        fail('no states found')
    if 'throw new IllegalStateException' not in body[default + 1]:
        fail('unexpected default case: %r' % body[default + 1])
    closing = [l.strip() for l in body[default + 2:] if l.strip()]
    if closing != ['}', '}', '}']:
        fail('unexpected end of the block: %r' % closing)
    cases = []
    for k, (number, first) in enumerate(states):
        last = states[k + 1][1] if k + 1 < len(states) else default
        cases.append((number, body[first:last]))

    # consecutive states into chunks
    chunks = []
    size = 0
    for number, case in cases:
        length = sum(len(l) for l in case)
        if not chunks or size + length > CHUNK_CHARS:
            chunks.append([])
            size = 0
        chunks[-1].append((number, case))
        size += length
    chunk_of = {}
    for c, chunk in enumerate(chunks):
        for number, _ in chunk:
            chunk_of[number] = c
    count = max(chunk_of) + 1
    if sorted(chunk_of) != list(range(count)):
        fail('the states are not numbered 0..%d' % (count - 1))

    out = lines[:begin[0]]
    out.append(indent + '// re2c\'s DFA, its states grouped into methods by tools/parser/split_dfa.py')
    out.append(indent + 'yystate = ' + start + ';')
    out.append(indent + 'while (true) {')
    out.append(indent + '    int yyresult;')
    out.append(indent + '    switch (YYCHUNK[yystate]) {')
    for c in range(len(chunks)):
        out.append(indent + '        case %d -> yyresult = yyChunk%d();' % (c, c))
        pass
    out.append(indent + '        default -> throw new IllegalStateException("internal lexer error");')
    out.append(indent + '    }')
    out.append(indent + '    if (yyresult != %s) {' % CONTINUE)
    out.append(indent + '        return yyresult;')
    out.append(indent + '    }')
    out.append(indent + '}')
    out += lines[end[0] + 1:methods[0]]

    member = re.match(r'\s*', lines[methods[0]]).group(0)
    out.append(member + '/** A transition into another chunk of the DFA\'s states: run the chunk of yystate. */')
    out.append(member + 'private static final int %s = Integer.MIN_VALUE;' % CONTINUE)
    out.append('')
    out.append(member + '/** The DFA\'s registers between chunks. */')
    out.append(member + 'private int yystate;')
    out.append(member + 'private int yych;')
    if accept:
        out.append(member + 'private int yyaccept;')
    out.append('')
    out.append(member + '/** The chunk of each state. */')
    out.append(member + 'private static final byte[] YYCHUNK = {')
    numbers = [str(chunk_of[n]) for n in range(count)]
    for i in range(0, len(numbers), 32):
        out.append(member + '    ' + ', '.join(numbers[i:i + 32]) + ',')
    out.append(member + '};')
    for name, rows in tables:
        out.append('')
        out.append(member + 'private static final int[] %s = {' % name)
        for row in rows:
            out.append(member + '    ' + row)
        out.append(member + '};')
    for c, chunk in enumerate(chunks):
        out.append('')
        out.append(member + 'private int yyChunk%d() {' % c)
        out.append(member + '    int yych = this.yych;')
        if accept:
            out.append(member + '    int yyaccept = this.yyaccept;')
        out.append(member + '    int yystate = this.yystate;')
        out.append(member + '    yyl: while (true) {')
        out.append(member + '        switch (yystate) {')
        for number, case in chunk:
            i = 0
            while i < len(case):
                line = case[i]
                m = re.fullmatch(r'(\s*)yystate = (\d+);', line)
                if m and i + 1 < len(case) and case[i + 1].strip() == 'continue yyl;':
                    target_state = int(m.group(2))
                    if chunk_of[target_state] != c:
                        pad = m.group(1)
                        out.append(pad + 'this.yych = yych;')
                        if accept:
                            out.append(pad + 'this.yyaccept = yyaccept;')
                        out.append(pad + 'this.yystate = %d;' % target_state)
                        out.append(pad + 'return %s;' % CONTINUE)
                    else:
                        out += case[i:i + 2]
                    i += 2
                    continue
                elif line.strip() == 'continue yyl;':
                    fail('a "continue yyl" without a state before it in state %d' % number)
                out.append(line)
                i += 1
        out.append(member + '            default:')
        out.append(member + '                throw new IllegalStateException("internal lexer error");')
        out.append(member + '        }')
        out.append(member + '    }')
        out.append(member + '}')
    out += lines[methods[0] + 1:]
    open(target, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        fail('usage: split_dfa.py <re2c output> <result>')
    main(sys.argv[1], sys.argv[2])
