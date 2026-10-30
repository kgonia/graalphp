#!/usr/bin/env python3
"""Fixes what Bison 3.8.2's Java skeleton writes that isn't valid or usable Java, in ZendLanguageParser.java.

- Bison breaks "??" in symbol names as "?""?" (against C trigraphs), which in Java is two literals side by side:
  "'?""?='" becomes "'??='" (the names of T_COALESCE_EQUAL and T_COALESCE).
- Context.getExpectedTokens, which a custom error report calls, is package-private, as Bison expects the report
  in the parser's own file (%code lexer); ZendLexer reports from its own package, so it becomes public.
- A table is one array initializer in one method, and php-src's yytable and yycheck (about 11,000 entries each)
  make a method over the 64 KB javac allows; such a table's initializer is split into methods of TABLE_PART
  entries, whose arrays the table's own method joins. The entries stay as Bison wrote them.
- yyaction, every rule's action in one switch, is about 19 KB of bytecode, and HotSpot doesn't JIT-compile a
  method over 8000 bytes (HugeMethodLimit), so every reduction would run in the bytecode interpreter. Its cases
  move, as Bison wrote them, into methods of ACTION_PART cases each; yyaction calls the one with the rule. $$
  (yyval) goes between them through a field, and an action's "return YYERROR" (or YYABORT, YYACCEPT) returns from
  its method, which yyaction passes on.
- Bison's C skeleton, which php-src's parser is made with, stops at YYMAXDEPTH (10000) states on its stack with
  "memory exhausted"; the Java skeleton's stack grows without a bound. YYStack.push ends the parse where the C
  parser does (ZendParser reports it as php-src's yyexhaustedlab does).

Usage: post_bison.py <Bison's output> <result>
"""
import re
import sys

TABLE_PART = 3000
ACTION_PART = 120

TABLE = re.compile(r'(  private static final (\w+)\[\] (\w+)_init\(\)\n  \{\n    return new \w+\[\]\n    \{\n)(.*?)'
                   r'\n    \};\n  \}', flags=re.S)


def fail(message):
    sys.exit('post_bison.py: ' + message)


def split_table(m):
    type_, name, body = m.group(2), m.group(3), m.group(4)
    entries = [e.strip() for e in body.replace('\n', ' ').split(',') if e.strip()]
    if len(entries) <= TABLE_PART:
        return m.group(0)
    parts = [entries[i:i + TABLE_PART] for i in range(0, len(entries), TABLE_PART)]
    out = ['  private static final %s[] %s_init()' % (type_, name),
           '  {',
           '    // split by tools/parser/post_bison.py: one initializer of %d entries is too large a method'
           % len(entries),
           '    %s[] table = new %s[%d];' % (type_, type_, len(entries))]
    offset = 0
    for k, part in enumerate(parts):
        out.append('    System.arraycopy(%s_init_%d(), 0, table, %d, %d);' % (name, k, offset, len(part)))
        offset += len(part)
    out.append('    return table;')
    out.append('  }')
    for k, part in enumerate(parts):
        out.append('')
        out.append('  private static %s[] %s_init_%d()' % (type_, name, k))
        out.append('  {')
        out.append('    return new %s[]' % type_)
        out.append('    {')
        for i in range(0, len(part), 10):
            out.append('  ' + ', '.join('%5s' % e for e in part[i:i + 10]) + (',' if i + 10 < len(part) else ''))
        out.append('    };')
        out.append('  }')
    return '\n'.join(out)


ACTION_SWITCH = ('    Object yyval = (0 < yylen) ? yystack.valueAt(yylen - 1) : yystack.valueAt(0);\n\n'
                 '    switch (yyn)\n      {\n')
ACTION_END = '        default: break;\n      }\n'


def split_actions(text):
    start = text.index(ACTION_SWITCH) + len(ACTION_SWITCH)
    end = text.index(ACTION_END, start)
    cases = re.split(r'\n(?=[ \t]*case \d+: .*\n  if \(yyn == \d+\)\n)', text[start:end])
    if any(not re.match(r'[ \t]*case (\d+): .*\n  if \(yyn == \1\)\n', c) for c in cases):
        fail('an action case not in the shape "case N: ... if (yyn == N)"')
    numbers = [int(re.match(r'[ \t]*case (\d+):', c).group(1)) for c in cases]
    parts = [list(range(i, min(i + ACTION_PART, len(cases)))) for i in range(0, len(cases), ACTION_PART)]
    dispatch = ['    // the actions are in methods of %d (split by tools/parser/post_bison.py)' % ACTION_PART,
                '    this.yyval = yyval;',
                '    int yystatus = switch (YYACTION_PART[yyn])',
                '      {']
    for k in range(len(parts)):
        dispatch.append('        case %d -> yyaction_%d(yyn, yystack);' % (k, k))
    dispatch += ['        default -> YYNEWSTATE;',
                 '      };',
                 '    if (yystatus != YYNEWSTATE)',
                 '      return yystatus;',
                 '    yyval = this.yyval;',
                 '']
    head = text[:text.index(ACTION_SWITCH)] + ACTION_SWITCH.split('\n\n')[0] + '\n\n'
    tail = text[end + len(ACTION_END):]
    method_end = tail.index('\n  }\n') + len('\n  }\n')
    methods = ['', '  /** $$ between yyaction and the method with the rule\'s action. */',
               '  private Object yyval;', '',
               '  /** The method (yyaction_N) with each rule\'s action, -1 for a rule without one. */',
               '  private static final byte[] YYACTION_PART = yyaction_part_init();', '',
               '  private static byte[] yyaction_part_init()', '  {']
    rules = max(numbers) + 1
    methods.append('    byte[] part = new byte[%d];' % (rules + 1))
    methods.append('    java.util.Arrays.fill(part, (byte) -1);')
    for k, part in enumerate(parts):
        members = [numbers[i] for i in part]
        methods.append('    for (int rule : new int[] {%s})' % ', '.join(str(n) for n in members))
        methods.append('      part[rule] = %d;' % k)
    methods += ['    return part;', '  }']
    for k, part in enumerate(parts):
        methods += ['', '  private int yyaction_%d(int yyn, YYStack yystack)' % k, '  {',
                    '    Object yyval = this.yyval;', '    switch (yyn)', '      {']
        methods += [cases[i] for i in part]
        methods += [ACTION_END.rstrip('\n'), '    this.yyval = yyval;', '    return YYNEWSTATE;', '  }']
    return head + '\n'.join(dispatch) + '\n' + tail[:method_end] + '\n'.join(methods) + '\n' + tail[method_end:]


def main(source, target):
    text = open(source, encoding='utf-8').read()
    text = split_actions(text)
    fixed, count = re.subn(r'"(\'[^"\n]*?\?)""(\?[^"\n]*\')"', r'"\1\2"', text)
    if count != 2:
        fail('expected the two names with "??" split, found %d' % count)
    fixed, count = re.subn(r'^(\s*)int getExpectedTokens\(', r'\1public int getExpectedTokens(', fixed, flags=re.M)
    if count != 2:
        fail('expected the two getExpectedTokens, found %d' % count)
    fixed, count = TABLE.subn(split_table, fixed)
    if count < 10:
        fail('expected Bison\'s tables, found %d' % count)
    push = '    public final void push(int state, Object value) {\n      height++;\n'
    if fixed.count(push) != 1:
        fail('expected one YYStack.push')
    fixed = fixed.replace(push, push + '      // the C skeleton\'s bound (post_bison.py)\n'
                          '      if (height + 1 >= YYMAXDEPTH)\n'
                          '        throw new MemoryExhausted();\n')
    open(target, 'w', encoding='utf-8', newline='\n').write(fixed)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        fail('usage: post_bison.py <in> <out>')
    main(sys.argv[1], sys.argv[2])
