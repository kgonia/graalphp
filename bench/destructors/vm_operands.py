"""php-src's rule for each temporary operand, from its VM's source (Zend/zend_vm_def.h): which opcodes take a TMP or
VAR operand and whether the handler frees it once used (FREE_OP1/FREE_OP2...), or not (it moves it on: SEND_VAL,
RETURN, ASSIGN's value, an array element; or keeps it: a call frame's $this, a foreach's FE_RESET copy).

The destructors' timing is these rules (wiki/destructors.md): a temporary dies at the opcode that frees it, a moved
one goes where it was moved. The table is the reference the translator's release points are checked against, one
construct at a time, instead of comparing program outputs.

Usage: python -I vm_operands.py ZEND_VM_DEF_H [--markdown]
"""
import re
import sys

HANDLER = re.compile(r'^ZEND_VM_(?:HOT_|COLD_|INLINE_|HOT_NOCONST_|HOT_NOCONSTCONST_|COLD_CONST_|COLD_CONSTCONST_|'
                     r'HOT_TYPE_SPEC_|HOT_SEND_|NOCONST_|NOCONSTCONST_|)HANDLER\((\d+),\s*(ZEND_\w+),\s*([^,]+),'
                     r'\s*([^,)]+)', re.M)


def operand_types(spec):
    return set(t.strip() for t in spec.split('|'))


def main(path, markdown):
    src = open(path, encoding='utf-8', errors='replace').read()
    matches = list(HANDLER.finditer(src))
    rows = []
    for i, m in enumerate(matches):
        body = src[m.end():matches[i + 1].start() if i + 1 < len(matches) else len(src)]
        name = m.group(2)[len('ZEND_'):]
        ops = []
        for n, spec in ((1, m.group(3)), (2, m.group(4))):
            types = operand_types(spec) & {'TMP', 'VAR', 'TMPVAR', 'TMPVARCV'}
            if not types:
                continue
            frees = re.findall(rf'FREE_OP{n}\w*', body)
            moved = bool(re.search(rf'OP{n}_TYPE\s*==\s*IS_TMP_VAR|OP{n}_TYPE\s*&\s*\(IS_TMP_VAR', body))
            if frees:
                fate = 'freed' + (' (or kept: ' + ', '.join(sorted(set(f for f in frees if f != f'FREE_OP{n}')))
                                  + ')' if set(frees) - {f'FREE_OP{n}'} else '')
            elif moved:
                fate = 'moved (TMP) / special'
            else:
                fate = 'kept or moved (no FREE_OP)'
            ops.append(f'op{n} {"|".join(sorted(types))}: {fate}')
        if ops:
            rows.append((name, ops))
    seen = set()
    if markdown:
        print('| opcode | operands |\n|---|---|')
    for name, ops in rows:
        if name in seen:
            continue
        seen.add(name)
        print(f'| {name} | {"; ".join(ops)} |' if markdown else f'{name:32s} {"; ".join(ops)}')
    print(f'{len(seen)} opcodes with TMP/VAR operands', file=sys.stderr)


if __name__ == '__main__':
    main(sys.argv[1], '--markdown' in sys.argv)
