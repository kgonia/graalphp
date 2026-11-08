"""Which php-src opcodes the destructor fixtures run with a temporary operand, against the opcodes that can take one.

A destructor's timing is decided where an opcode frees (or moves) a TMP or VAR operand; a fixture case per such
opcode (tests/rcaudit/opcodes.php, sequence.php) checks the translator's release point for it against PHP. This
reads php-src's own opcode dumps (opcache.opt_debug_level=0x10000: the opcodes before the optimizer, as PHP without
opcache runs them) and counts, per opcode, the instructions with a T or V operand:
- of the fixtures (the covered ones),
- optionally of a corpus (WordPress: what real code compiles to), so an opcode the corpus uses with a temporary but
  no fixture covers stands out, its count saying how much it matters.
The opcodes that can take one at all come from Zend/zend_vm_def.h (vm_operands.py's table).

Usage: python -I opcode_coverage.py FIXTURES.dump [--corpus CORPUS.dump] [--vm ZEND_VM_DEF_H]
(opcode_coverage.sh makes the dumps)
"""
import collections
import os
import re
import sys

LINE = re.compile(r'^\d{4}\s+(?:[TV]\d+\s*=\s*)?([A-Z_]+)(.*)$')
LITERAL = re.compile(r'string\("(?:[^"\\]|\\.)*"\)')
TEMP = re.compile(r'(?<![A-Za-z])([TV])\d+\b')


def counts(path):
    """Per opcode: (instructions with a T/V operand, of which with a V)."""
    found = collections.Counter()
    for line in open(path, encoding='utf-8', errors='replace'):
        m = LINE.match(line.strip())
        if not m:
            continue
        operands = LITERAL.sub('""', m.group(2))
        if TEMP.search(operands):
            found[m.group(1)] += 1
    return found


def vm_opcodes(path):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import vm_operands  # noqa: E402
    src = open(path, encoding='utf-8', errors='replace').read()
    names = set()
    for m in vm_operands.HANDLER.finditer(src):
        types = vm_operands.operand_types(m.group(3)) | vm_operands.operand_types(m.group(4))
        if types & {'TMP', 'VAR', 'TMPVAR', 'TMPVARCV'}:
            names.add(m.group(2)[len('ZEND_'):])
    return names


def main(args):
    fixtures = counts(args[0])
    corpus = counts(args[args.index('--corpus') + 1]) if '--corpus' in args else None
    vm = vm_opcodes(args[args.index('--vm') + 1]) if '--vm' in args else None
    print(f'fixtures: {len(fixtures)} opcodes run with a temporary operand')
    if corpus is not None:
        missing = sorted(((n, op) for op, n in corpus.items() if op not in fixtures), reverse=True)
        print(f'corpus: {len(corpus)} opcodes with a temporary operand; not in the fixtures: {len(missing)}')
        for n, op in missing:
            print(f'  {n:8d}  {op}')
    if vm is not None:
        seen = set(fixtures) | set(corpus or ())
        never = sorted(vm - seen)
        print(f'zend_vm_def.h: {len(vm)} opcodes can take one; run by neither: {len(never)}')
        print('  ' + ' '.join(never))
    if corpus is None and vm is None:
        for op, n in fixtures.most_common():
            print(f'  {n:8d}  {op}')


if __name__ == '__main__':
    if not sys.argv[1:]:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1:])
