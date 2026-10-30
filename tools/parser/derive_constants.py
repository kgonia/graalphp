#!/usr/bin/env python3
"""Derives ZendConstants.java, php-src's constants the parser uses, from php-src's own headers (Zend/*.h).

- the AST kinds, the enum _zend_ast_kind of zend_ast.h, with php-src's numbering;
- zend_modifier_target (zend_compile.h);
- php-src's known strings (zend_string.h's ZEND_KNOWN_STRINGS) that the grammar's actions use;
- every #define the derived grammar's actions or the hand-written parser use (NAMES below, and each name the
  grammar mentions), with the #defines their values refer to.

Usage: derive_constants.py <php-src Zend dir> <derived .y> <out .java>
"""
import os
import re
import sys

#: Constants the hand-written parser classes use beyond those the grammar's actions mention.
NAMES = [
    'ZEND_ACC_PROTECTED', 'ZEND_ACC_PRIVATE', 'ZEND_ACC_ABSTRACT', 'ZEND_ACC_READONLY',
    'ZEND_ACC_PUBLIC_SET', 'ZEND_ACC_PROTECTED_SET', 'ZEND_ACC_PRIVATE_SET', 'ZEND_ACC_PPP_SET_MASK',
    'ZEND_ACC_VIRTUAL', 'IS_UNDEF', 'IS_FALSE', 'IS_TRUE', 'IS_VOID', 'IS_ITERABLE', 'IS_MIXED', 'IS_NEVER',
    'ZEND_CONST_EXPR_NEW_FETCH_TYPE_SHIFT',
]

#: The C integer suffixes a value may carry.
SUFFIX = re.compile(r'\b(0[xX][0-9a-fA-F]+|\d+)[uUlL]+\b')


def fail(message):
    sys.exit('derive_constants.py: ' + message)


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


def read_headers(zend):
    defines = {}
    for name in sorted(os.listdir(zend)):
        if not name.endswith('.h'):
            continue
        text = strip_comments(open(os.path.join(zend, name), encoding='latin-1').read())
        text = text.replace('\\\n', ' ')
        for m in re.finditer(r'^[ \t]*#[ \t]*define[ \t]+([A-Za-z_]\w*)[ \t]+(.+)$', text, flags=re.M):
            # the first definition wins: php-src's headers define each of these once
            defines.setdefault(m.group(1), (m.group(2).strip(), name))
    return defines


def read_enum(text, pattern):
    """(name, value) pairs of the enum whose head matches pattern, numbered as C numbers them."""
    m = re.search(pattern + r'\s*\{(.*?)\}', text, flags=re.S)
    if not m:
        fail('no enum ' + pattern)
    members = []
    previous = None
    for item in m.group(1).split(','):
        item = item.strip()
        if not item:
            continue
        if '=' in item:
            name, value = [p.strip() for p in item.split('=', 1)]
        else:
            name, value = item, (previous + ' + 1' if previous else '0')
        members.append((name, value))
        previous = name
    return members


def main(zend, grammar, target):
    defines = read_headers(zend)
    ast_h = strip_comments(open(os.path.join(zend, 'zend_ast.h'), encoding='latin-1').read())
    compile_h = strip_comments(open(os.path.join(zend, 'zend_compile.h'), encoding='latin-1').read())
    string_h = open(os.path.join(zend, 'zend_string.h'), encoding='latin-1').read()

    kinds = read_enum(ast_h, r'enum _zend_ast_kind')
    targets = read_enum(compile_h, r'typedef enum\s*')
    if not targets[0][0].startswith('ZEND_MODIFIER_TARGET_'):
        fail('the first enum of zend_compile.h is not zend_modifier_target')
    enum_names = {name for name, _ in kinds + targets}
    known = re.findall(r'_\((ZEND_STR_\w+),\s*"((?:[^"\\]|\\.)*)"\)', string_h)

    used = set(re.findall(r'\b(?:ZEND_[A-Z0-9_]+|IS_[A-Z0-9_]+|_IS_BOOL)\b', open(grammar).read()))
    wanted = sorted((used | set(NAMES)) - enum_names - {name for name, _ in known})
    emitted = []
    seen = set()

    def want(name):
        if name in seen or name in enum_names:
            return
        if name not in defines:
            fail('no #define for ' + name)
        value, header = defines[name]
        value = SUFFIX.sub(r'\1', value)
        if re.search(r'\(\s*(?:u?int\d+_t|zend_\w+|unsigned|long|int)\s*\)', value):
            fail('a cast in %s: %s' % (name, value))
        seen.add(name)
        for ref in re.findall(r'\b[A-Za-z_]\w*\b', value):
            if ref in defines or ref in enum_names:
                want(ref)
        emitted.append((name, value, header))

    for name in wanted:
        want(name)
    for _, value in kinds + targets:
        for ref in re.findall(r'\b[A-Za-z_]\w*\b', value):
            if ref in defines:
                want(ref)

    out = ['/*',
           ' * Derived from php-src 8.5.12\'s Zend headers by tools/parser/derive_constants.py: the constants the parser',
           ' * uses, under php-src\'s names and with php-src\'s values. DO NOT EDIT; regenerate with tools/parser/generate.sh.',
           ' * php-src is under the Zend Engine License 2.00 (its Zend/LICENSE).',
           ' */',
           'package pl.codeloom.php.parser.zend.derived;',
           '',
           '/** php-src\'s constants that the parser uses. */',
           'public final class ZendConstants {',
           '    private ZendConstants() {',
           '    }',
           '',
           '    // #defines, each after those it refers to']
    for name, value, header in emitted:
        out.append('    public static final int %s = %s; // %s' % (name, value, header))
    out.append('')
    out.append('    // enum _zend_ast_kind (zend_ast.h)')
    for name, value in kinds:
        out.append('    public static final int %s = %s;' % (name, SUFFIX.sub(r'\1', value)))
    out.append('')
    out.append('    // zend_modifier_target (zend_compile.h)')
    for name, value in targets:
        out.append('    public static final int %s = %s;' % (name, value))
    out.append('')
    out.append('    // ZEND_KNOWN_STRINGS (zend_string.h): ids for ZSTR_KNOWN')
    for i, (name, _) in enumerate(known):
        out.append('    public static final int %s = %d;' % (name, i))
    out.append('')
    out.append('    /** The known strings by id. */')
    out.append('    public static final String[] ZEND_KNOWN_STRINGS = {')
    for name, value in known:
        out.append('        "%s",' % value)
    out.append('    };')
    out.append('}')
    open(target, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        fail('usage: derive_constants.py <Zend dir> <derived .y> <out .java>')
    main(sys.argv[1], sys.argv[2], sys.argv[3])
