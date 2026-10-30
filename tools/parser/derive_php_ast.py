#!/usr/bin/env python3
"""Derives PhpAstData.java from php-ast 1.1.3's ast_data.c and php_ast.h: the tree shape's names.

- php-ast's own kinds (AST_NAME, AST_CLOSURE_VAR, AST_NULLABLE_TYPE) and the kinds it keeps for PHP 8.5
  (ZEND_AST_CLONE, ZEND_AST_EXIT, ZEND_AST_LIST), from php_ast.h with its #if PHP_VERSION_ID evaluated for 8.5;
- ast_kind_to_name: a kind's name ("AST_CALL");
- ast_kind_child_name: a child's name by kind and position ("expr", "args"), null for list kinds.

Usage: derive_php_ast.py <php-ast dir> <out .java>
"""
import os
import re
import sys

PHP_VERSION_ID = 80512


def fail(message):
    sys.exit('derive_php_ast.py: ' + message)


def php_ast_defines(header):
    """The #defines of php_ast.h that apply to PHP_VERSION_ID, in order."""
    defines = []
    # (active, condition) per open #if; a condition other than on PHP_VERSION_ID (the include guard, PHP_WIN32,
    # ZTS) holds AST defines in neither branch, so both branches count as active
    active = [(True, None)]
    for line in header.split('\n'):
        m = re.match(r'\s*#\s*if\s+PHP_VERSION_ID\s*(<|>=)\s*(\d+)', line)
        if m:
            holds = PHP_VERSION_ID < int(m.group(2)) if m.group(1) == '<' else PHP_VERSION_ID >= int(m.group(2))
            active.append((active[-1][0] and holds, holds))
            continue
        if re.match(r'\s*#\s*if', line):
            active.append((active[-1][0], None))
            continue
        if re.match(r'\s*#\s*endif', line):
            active.pop()
            continue
        if re.match(r'\s*#\s*else', line):
            _, holds = active.pop()
            active.append((active[-1][0] and (holds is None or not holds), holds))
            continue
        m = re.match(r'\s*#\s*define\s+((?:AST|ZEND_AST)_\w+)\s+(.+?)\s*(?://.*)?$', line)
        if m and active[-1][0]:
            defines.append((m.group(1), m.group(2)))
    return defines


def function_body(source, signature):
    start = source.index(signature)
    i = source.index('{', start)
    depth = 0
    for j in range(i, len(source)):
        if source[j] == '{':
            depth += 1
        elif source[j] == '}':
            depth -= 1
            if depth == 0:
                return source[i + 1:j]
    fail('unterminated ' + signature)


def main(php_ast, target):
    header = open(os.path.join(php_ast, 'php_ast.h'), encoding='utf-8').read()
    data = open(os.path.join(php_ast, 'ast_data.c'), encoding='utf-8').read()
    strings = dict(re.findall(r'X\((\w+), "([^"]*)"\)',
                              open(os.path.join(php_ast, 'ast_str_defs.h'), encoding='utf-8').read()))

    names = re.findall(r'case (\w+): return "(\w+)";', function_body(data, 'const char *ast_kind_to_name('))

    child_body = function_body(data, 'zend_string *ast_kind_child_name(')
    groups = []
    labels = []
    for m in re.finditer(r'case (\w+):|switch \(child\) \{(.*?)\}|return NULL;', child_body, flags=re.S):
        if m.group(1):
            labels.append(m.group(1))
        elif m.group(2) is not None:
            children = re.findall(r'case (\d+): return AST_STR\(str_(\w+)\);', m.group(2))
            groups.append((labels, [(int(n), strings[s]) for n, s in children]))
            labels = []
        elif labels:
            groups.append((labels, []))
            labels = []

    out = ['/*',
           ' * Derived from php-ast 1.1.3\'s ast_data.c and php_ast.h by tools/parser/derive_php_ast.py: the names of',
           ' * the tree\'s kinds and children as php-ast gives them for AST version 120 on PHP 8.5. DO NOT EDIT;',
           ' * regenerate with tools/parser/generate.sh. php-ast is under the BSD 3-Clause License (its LICENSE).',
           ' */',
           'package pl.codeloom.php.parser.zend.derived;',
           '',
           'import static pl.codeloom.php.parser.zend.derived.ZendConstants.*;',
           '',
           '/** php-ast\'s names: of kinds and of the named children of each kind. */',
           'public final class PhpAstData {',
           '    private PhpAstData() {',
           '    }',
           '',
           '    // php-ast\'s own kinds and those it keeps (php_ast.h)']
    for name, value in php_ast_defines(header):
        out.append('    public static final int %s = %s;' % (name, value))
    out.append('')
    out.append('    /** ast_kind_to_name: the kind\'s name, as php-ast exports it. */')
    out.append('    public static String kindName(int kind) {')
    out.append('        return switch (kind) {')
    for kind, name in names:
        out.append('            case %s -> "%s";' % (kind, name))
    out.append('            default -> null;')
    out.append('        };')
    out.append('    }')
    out.append('')
    out.append('    /** ast_kind_child_name: the name of a node\'s child by position, or null (lists, unnamed). */')
    out.append('    public static String childName(int kind, int child) {')
    out.append('        switch (kind) {')
    for labels, children in groups:
        out.append('            case %s:' % ', '.join(labels))
        if children:
            out.append('                return switch (child) {')
            for n, s in children:
                out.append('                    case %d -> "%s";' % (n, s))
            out.append('                    default -> null;')
            out.append('                };')
        else:
            out.append('                return null;')
    out.append('            default:')
    out.append('                return null;')
    out.append('        }')
    out.append('    }')
    out.append('}')
    open(target, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        fail('usage: derive_php_ast.py <php-ast dir> <out .java>')
    main(sys.argv[1], sys.argv[2])
