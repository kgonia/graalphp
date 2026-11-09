"""Where each owning temporary is released, by php-src and by the translator, compared body by body without running
any code: the static check of the destructors' timing (wiki/destructors.md, "Checks that don't need PHP").

php-src's side comes from its opcode dump before the optimizer (opcode_coverage.sh makes it: PHP without OPcache
runs these opcodes). A temporary that may own a count is a call's result (DO_*CALL), `new`'s object, a closure
(DECLARE_LAMBDA_FUNCTION), an array literal (INIT_ARRAY) or an (array)/(object) cast; it is released by the opcode
that uses it (FREE_OP), moved on by the ones that take it (SEND_*, RETURN, ASSIGN, an array element), or passed on
by the ones whose value it is (QM_ASSIGN, `?:`, `??`, VERIFY_RETURN_TYPE, (array) of an array, foreach's reset),
which the walk follows to their value's user.

Ours comes from the translator (-Dphp.rc.releases=FILE, with truffle_translate: translated, not run): each owning
temporary's origin and the operation that releases it (BytecodeTranslator.releaseLabel).

Both are named in one vocabulary (LABELS), aligned per body by origin (a call's name, new's class...), and the
pairs whose release differs are listed, most frequent first: each is a construct that releases elsewhere than
php-src does. "fetch?" (ours) is a borrowing read, which releases there unless the value read is counted.

Usage: python -I release_diff.py PHP.dump OURS.txt [--examples N] [--strip REGEX] [--show BODY-REGEX]
"""
import collections
import difflib
import re
import sys

HEADER = re.compile(r'^(\S.*):$')
FILE = re.compile(r'^\s+; (/\S+):\d+-\d+$')
OPLINE = re.compile(r'^(\d{4}) (?:([TV]\d+) = )?([A-Z_0-9]+(?:\(\w+\))?)(?: (.*))?$')
LITERAL = re.compile(r'string\("(?:[^"\\]|\\.)*"\)')
STRING = re.compile(r'string\("((?:[^"\\]|\\.)*)"\)')
VAR = re.compile(r'\b[TV]\d+\b')
PREFIX = r'^(?:.*?/target/gate2/wordpress/|/[cwm]/)'

CALL_INIT = {'INIT_FCALL', 'INIT_FCALL_BY_NAME', 'INIT_NS_FCALL_BY_NAME', 'INIT_METHOD_CALL',
             'INIT_STATIC_METHOD_CALL', 'INIT_DYNAMIC_CALL', 'INIT_USER_CALL', 'INIT_PARENT_PROPERTY_HOOK_CALL', 'NEW'}
CALL_DO = {'DO_FCALL', 'DO_ICALL', 'DO_UCALL', 'DO_FCALL_BY_NAME'}
# opcodes whose value is the operand's (followed to their value's user)
PASS = {'QM_ASSIGN', 'JMP_SET', 'COALESCE', 'VERIFY_RETURN_TYPE', 'FE_RESET_R', 'FE_RESET_RW', 'SEPARATE'}
# opcodes that use an operand without freeing it (the next use decides)
SKIP = {'JMP_NULL', 'COPY_TMP', 'FETCH_LIST_R', 'FETCH_LIST_W', 'FE_FETCH_R', 'FE_FETCH_RW', 'BIND_LEXICAL'}
LABELS = {}
for names, label in (
        ('SEND_VAL SEND_VAL_EX SEND_VAR SEND_VAR_EX SEND_VAR_NO_REF SEND_VAR_NO_REF_EX SEND_FUNC_ARG SEND_USER '
         'SEND_ARRAY SEND_UNPACK SEND_REF COUNT STRLEN GET_CLASS GET_TYPE ARRAY_KEY_EXISTS IN_ARRAY '
         'DEFINED INCLUDE_OR_EVAL CLONE INIT_DYNAMIC_CALL INIT_USER_CALL CALLABLE_CONVERT', 'arg'),
        ('RETURN RETURN_BY_REF GENERATOR_RETURN', 'return'),
        ('YIELD YIELD_FROM', 'yield'),
        ('THROW', 'throw'),
        ('ASSIGN ASSIGN_REF OP_DATA INIT_ARRAY ADD_ARRAY_ELEMENT ASSIGN_STATIC_PROP BIND_STATIC', 'store'),
        ('ECHO', 'echo'),
        ('FREE', 'stmt'),
        ('FE_FREE', 'foreach-end'),
        ('CONCAT FAST_CONCAT ROPE_INIT ROPE_ADD ROPE_END', 'concat'),
        ('JMPZ JMPNZ JMPZ_EX JMPNZ_EX BOOL BOOL_NOT', 'test'),
        ('FETCH_OBJ_R FETCH_DIM_R FETCH_OBJ_IS FETCH_DIM_IS FETCH_OBJ_FUNC_ARG FETCH_DIM_FUNC_ARG FETCH_OBJ_W '
         'FETCH_DIM_W FETCH_OBJ_RW FETCH_DIM_RW FETCH_OBJ_UNSET FETCH_DIM_UNSET ASSIGN_DIM ASSIGN_OBJ ASSIGN_DIM_OP '
         'ASSIGN_OBJ_OP PRE_INC_OBJ PRE_DEC_OBJ POST_INC_OBJ POST_DEC_OBJ UNSET_DIM UNSET_OBJ ASSIGN_OBJ_REF', 'fetch'),
        ('ISSET_ISEMPTY_PROP_OBJ ISSET_ISEMPTY_DIM_OBJ', 'isset'),
        ('INIT_METHOD_CALL', 'receiver'),
        ('FETCH_CLASS FETCH_CLASS_NAME', 'class'),
        ('INSTANCEOF', 'instanceof'),
        ('TYPE_CHECK', 'typecheck'),
        ('CAST', 'cast'),
        ('CASE CASE_STRICT MATCH SWITCH_LONG SWITCH_STRING MATCH_ERROR', 'match-end')):
    for name in names.split():
        LABELS[name] = label
OURS = {'stmt:AST_ECHO': 'echo', 'stmt:AST_FOREACH': 'foreach-end',
        'stmt:AST_RETURN': 'return', 'stmt:AST_THROW': 'throw'}


def last_name(text):
    names = STRING.findall(text or '')
    if not names:
        return '?'
    return names[-1].replace('\\\\', '\\').rsplit('\\', 1)[-1].lower()


def closure_name(name, strip):
    # a closure's name holds its file's path: as the key's
    return re.sub(r'(\{closure:)' + strip.lstrip('^'), r'\1', name)


def php_bodies(path, strip):
    """{(file, body): [(origin, label)]} in the order the values are made."""
    bodies = {}
    name = file = None
    ops = []

    def flush():
        if name is not None and file is not None:
            bodies[(re.sub(strip, '', file), closure_name(name, strip))] = php_events(ops)

    for line in open(path, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        m = HEADER.match(line)
        if m and not line.startswith(' '):
            flush()
            name, file, ops = m.group(1), None, []
            if name == '$_main':
                name = '(main)'
            continue
        m = FILE.match(line)
        if m and file is None:
            file = m.group(1)
            continue
        m = OPLINE.match(line)
        if m:
            ops.append((m.group(2), m.group(3), m.group(4) or ''))
    flush()
    return bodies


def php_events(ops):
    events = []
    frames = []
    for i, (result, opcode, operands) in enumerate(ops):
        origin = None
        if opcode in CALL_INIT:
            if opcode == 'NEW':
                # its object's event once the constructor's call ends (after its arguments', as ours)
                frames.append(('new:' + last_name(operands), i, result))
            elif opcode == 'INIT_METHOD_CALL':
                frames.append('mcall:' + last_name(operands))
            elif opcode == 'INIT_STATIC_METHOD_CALL':
                frames.append('scall:' + last_name(operands))
            elif opcode in ('INIT_DYNAMIC_CALL', 'INIT_PARENT_PROPERTY_HOOK_CALL'):
                frames.append('call:?')
            else:
                frames.append('call:' + last_name(operands))
        elif opcode in CALL_DO or opcode == 'CALLABLE_CONVERT':
            frame = frames.pop() if frames else 'call:?'
            if isinstance(frame, tuple):
                name, at, var = frame
                if var:
                    events.append((i, name, consumer(ops, i, var)))
            elif result:
                origin = frame
        elif opcode == 'DECLARE_LAMBDA_FUNCTION' and result:
            origin = 'closure'
        elif opcode == 'INIT_ARRAY' and result:
            # its event once its elements are in (after theirs, as ours): where it is first used
            done = next((j for j in range(i + 1, len(ops)) if result in operand_vars(ops[j][2])
                         and not (ops[j][0] == result and ops[j][1] in ('ADD_ARRAY_ELEMENT', 'ADD_ARRAY_UNPACK'))),
                        i)
            events.append((done - 0.5, 'array', consumer(ops, i, result)))
        elif opcode == 'CAST' and result and ('(array)' in operands or '(object)' in operands):
            origin = 'cast'
        elif opcode.startswith('FRAMELESS_ICALL') and result:
            # PHP 8.4's frameless call of a built-in: FRAMELESS_ICALL_n(name)
            origin = 'call:' + opcode[opcode.index('(') + 1:-1].lower()
        if origin:
            events.append((i, origin, consumer(ops, i, result)))
    return [(origin, label) for _, origin, label in sorted(events, key=lambda e: e[0])]


def operand_vars(operands):
    return VAR.findall(LITERAL.sub('""', operands))


def consumer(ops, at, var, depth=0):
    for j in range(at + 1, len(ops)):
        result, opcode, operands = ops[j]
        used = operand_vars(operands)
        if var not in used:
            continue
        if opcode in SKIP or opcode == 'VERIFY_RETURN_TYPE' and not result:
            continue
        if opcode == 'CAST' and '(array)' in operands and result and depth < 20:
            return consumer(ops, j, result, depth + 1)
        if opcode in PASS and result and depth < 20:
            return consumer(ops, j, result, depth + 1)
        if opcode in ('FE_RESET_R', 'FE_RESET_RW'):
            return 'foreach-end'
        if opcode in ('ASSIGN', 'ASSIGN_REF') and used and used[0] == var and len(used) > 1:
            return 'fetch'
        if opcode.startswith('FRAMELESS_ICALL'):
            return 'arg'
        if opcode == 'OP_DATA' and j > 0:
            owner = ops[j - 1][1]
            if owner.startswith('FRAMELESS_ICALL'):
                return 'arg'
            if owner.endswith('_OP'):
                return statement_final('operator', ops, j)
        if opcode == 'FREE' and inside_expression(ops, j):
            # a match's subject, freed after the arm's value, which the rest of the expression goes on with
            return 'match-end'
        return statement_final(LABELS.get(opcode, 'operator'), ops, j)


def statement_final(label, ops, at):
    """label, marked when nothing after ops[at] uses a temporary of the statement (released at its end: the
    same moment)."""
    return label + '$' if label not in ('arg', 'return', 'yield', 'throw') \
        and not inside_expression(ops, at) else label


# opcodes that use a temporary living across statements (a loop's iterator, a switch's subject, `@`'s level)
ACROSS = {'FE_FETCH_R', 'FE_FETCH_RW', 'FE_FREE', 'FREE', 'END_SILENCE', 'CASE', 'CASE_STRICT', 'SWITCH_LONG',
          'SWITCH_STRING', 'MATCH', 'FAST_RET', 'DISCARD_EXCEPTION'}


def inside_expression(ops, at):
    """Whether code after ops[at] uses a temporary made before it, before control flow leaves the straight line
    (a statement's end leaves none behind)."""
    before = {r for r, _, _ in ops[:at] if r}
    for result, opcode, operands in ops[at + 1:at + 12]:
        if opcode in ACROSS:
            return False
        if any(v in before for v in operand_vars(operands)):
            return True
        if opcode in ('JMP', 'RETURN', 'GENERATOR_RETURN', 'THROW'):
            return False
    return False


def our_bodies(path, strip):
    bodies = {}
    events = None
    for line in open(path, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        if line.startswith('== '):
            file, _, name = line[3:].partition(' ')
            events = bodies.setdefault((re.sub(strip, '', file), closure_name(name, strip)), [])
            continue
        if events is None or '\t' not in line:
            continue
        origin, label = line.split('\t', 1)
        if label == '(unowned)':
            # a call compiled in place, its value no temporary: kept for the alignment, not compared
            events.append((origin, '*'))
            continue
        events.append((origin, OURS.get(label, 'stmt' if label.startswith('stmt:') else label)))
    return bodies


def show(php, ours, pattern):
    for key in sorted(set(php) | set(ours)):
        if not re.search(pattern, f'{key[0]} {key[1]}'):
            continue
        print(f'== {key[0]} {key[1]}')
        a, b = php.get(key, []), ours.get(key, [])
        for i in range(max(len(a), len(b))):
            x = f'{a[i][0]} {a[i][1]}' if i < len(a) else ''
            y = f'{b[i][0]} {b[i][1]}' if i < len(b) else ''
            print(f'  {x:40s} | {y}')


def main(args):
    examples = int(args[args.index('--examples') + 1]) if '--examples' in args else 3
    if '--show' in args:
        strip = args[args.index('--strip') + 1] if '--strip' in args else PREFIX
        show(php_bodies(args[0], strip), our_bodies(args[1], strip), args[args.index('--show') + 1])
        return
    strip = args[args.index('--strip') + 1] if '--strip' in args else PREFIX
    php = php_bodies(args[0], strip)
    ours = our_bodies(args[1], strip)
    both = sorted(set(php) & set(ours))
    print(f'bodies: php-src {len(php)}, ours {len(ours)}, both {len(both)}')
    same = 0
    unmatched_php = unmatched_ours = 0
    diffs = collections.Counter()
    where = collections.defaultdict(list)
    dynamic = collections.Counter()
    in_place = 0
    for key in both:
        a, b = php[key], ours[key]
        matcher = difflib.SequenceMatcher(a=[o for o, _ in a], b=[o for o, _ in b], autojunk=False)
        for tag, i1, i2, j1, j2 in matcher.get_opcodes():
            if tag != 'equal':
                unmatched_php += i2 - i1
                unmatched_ours += j2 - j1
                continue
            for (origin, pl), (_, ol) in zip(a[i1:i2], b[j1:j2]):
                if ol == '*':
                    in_place += 1
                elif ol == 'fetch?' and pl.rstrip('$') == 'fetch':
                    dynamic[origin.split(':')[0]] += 1
                    same += 1
                elif pl.rstrip('$') == ol or pl.rstrip('$') == 'typecheck' and ol in ('arg', 'operator') \
                        or pl.endswith('$') and ol == 'stmt' or pl.rstrip('$') == 'test' and ol == 'isset':
                    # (TYPE_CHECK is both is_null()'s and `=== null`'s; an opcode a statement ends with frees
                    # when the statement's end does)
                    same += 1
                else:
                    k = (pl.rstrip('$'), ol)
                    diffs[k] += 1
                    if len(where[k]) < examples:
                        where[k].append(f'{key[0]} {key[1]}: {origin}')
    total = same + sum(diffs.values()) + in_place
    print(f'temporaries aligned: {total}; released as php-src: {same}; elsewhere: {sum(diffs.values())}; '
          f'not aligned: php-src {unmatched_php}, ours {unmatched_ours}; calls compiled in place (no '
          f'temporary, not compared): {in_place}')
    if dynamic:
        print(f'borrowing reads (ours fetch?, php-src fetch: the same unless the value read is counted): '
              f'{sum(dynamic.values())}')
    print('-- php-src releases at -> ours at (count): examples')
    for (pl, ol), n in diffs.most_common():
        print(f'{n:7d}  {pl:12s} -> {ol}')
        for e in where[(pl, ol)]:
            print(f'           {e}')


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1:])
