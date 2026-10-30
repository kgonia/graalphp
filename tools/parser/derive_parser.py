#!/usr/bin/env python3
"""Derives the Java grammar (src/main/grammar/zend_language_parser.y) from php-src's Zend/zend_language_parser.y.

The rules, tokens, precedences and the shape of every action stay php-src's. What changes:
- the C prologue becomes Bison's Java declarations (the parser extends the hand-written ZendParserBase, which has
  php-src's AST functions and compiler globals under php-src's names, so an action calls what it calls in C);
- semantic value types become Java classes (<ast> ZendAst, <num> and <ident> and <ptr> Integer, <str> byte[]);
- each action's C becomes Java by the rewrites below, statement shapes php-src's grammar uses; anything left that
  isn't Java fails the derivation (and javac checks the rest);
- php-src's zend_yytnamerr (its syntax error wording) is ported by hand in ZendLexer, so the epilogue is dropped.

Usage: derive_parser.py <php-src zend_language_parser.y> <out .y>
"""
import re
import sys

TYPES = {'ast': 'ZendAst', 'num': 'Integer', 'ident': 'Integer', 'ptr': 'Integer', 'str': 'byte[]'}

JAVA_PROLOGUE = '''/* Derived from php-src 8.5.12's Zend/zend_language_parser.y by tools/parser/derive_parser.py: php-src's rules
 * and actions, the actions' C rewritten as Java (DO NOT EDIT; regenerate with tools/parser/generate.sh). Bison 3.8.2's
 * Java skeleton makes ZendLanguageParser.java from it. */

%language "Java"
%define api.package {pl.codeloom.php.parser.zend.generated}
%define api.parser.class {ZendLanguageParser}
%define api.parser.public
%define api.parser.final
%define api.parser.extends {ZendParserBase}
%define api.value.type {Object}
/* php-src's parse.error verbose with its own zend_yytnamerr: ZendLexer.reportSyntaxError words the message */
%define parse.error custom

/* the lexer brings the compiler globals and the scanner the actions use (ZendParserBase.bind) */
%code init {
    bind(yylexer);
}

%code imports {
import pl.codeloom.php.parser.zend.ZendAst;
import pl.codeloom.php.parser.zend.ZendAstDecl;
import pl.codeloom.php.parser.zend.ZendParserBase;
import pl.codeloom.php.parser.zend.zval;
import static pl.codeloom.php.parser.zend.derived.ZendConstants.*;
import static pl.codeloom.php.parser.zend.generated.ZendLanguageParser.Lexer.*;
}
'''


def fail(message):
    sys.exit('derive_parser.py: ' + message)


def split_sections(text):
    """The prologue, rules and epilogue: the text around the two %% lines."""
    parts = re.split(r'^%%(?: /\* Rules \*/)?$', text, flags=re.M)
    if len(parts) != 3:
        fail('expected two %% lines')
    return parts


def derive_prologue(prologue):
    out = []
    # php-src's licence comment stays first
    licence = re.search(r'/\*.*?\*/', prologue, flags=re.S).group(0)
    out.append(licence + '\n')
    out.append(JAVA_PROLOGUE)
    lines = prologue[prologue.index(licence) + len(licence):].split('\n')
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith('%require') or not line.strip() and not out[-1].strip():
            i += 1
            continue
        if line.startswith('%code'):
            # C includes and the zend_yytnamerr declaration: replaced by the Java declarations above
            depth = line.count('{') - line.count('}')
            while depth > 0:
                i += 1
                depth += lines[i].count('{') - lines[i].count('}')
            i += 1
            continue
        if re.match(r'%define (api\.prefix|api\.pure|api\.value\.type|parse\.error)\b', line):
            # C-only (api.prefix, api.pure) or replaced above
            i += 1
            continue
        if line.startswith('%destructor'):
            # C frees its arena-less nodes on error; Java has the garbage collector
            i += 1
            continue
        line = re.sub(r'<(\w+)>', lambda m: '<' + TYPES[m.group(1)] + '>', line)
        out.append(line)
        i += 1
    if '%require' in prologue:
        out.insert(1, '%require "3.8.2"\n')
    return '\n'.join(out)


def symbol_types(prologue):
    """Nonterminal and token name -> php-src's type tag, from %type and %token."""
    types = {}
    for m in re.finditer(r'^%(?:type|token)\s+<(\w+)>\s+(.*)$', prologue, flags=re.M):
        for name in re.findall(r'\b[A-Za-z_]\w*\b', re.sub(r'"[^"]*"', '', m.group(2))):
            types[name] = m.group(1)
    return types


def read_action(text, i):
    """The end of the action whose '{' is at text[i], skipping C strings, chars and comments."""
    depth = 0
    while i < len(text):
        c = text[i]
        if text.startswith('/*', i):
            i = text.index('*/', i) + 2
            continue
        if c in '"\'':
            j = i + 1
            while text[j] != c:
                j += 2 if text[j] == '\\' else 1
            i = j + 1
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    fail('unterminated action')


def translate_action(code, lhs_tag, rhs_tags):
    """php-src's C action -> Java. lhs_tag: the tag of $$ (None if untyped); rhs_tags: position -> tag."""
    java = code

    # explicit tags: $<num>$, $<num>2
    java = re.sub(r'\$<(\w+)>', lambda m: '$<' + TYPES[m.group(1)] + '>', java)
    # zend_parser_stack_elem's members become Java types; a pointer cast to zend_ast_decl is a class cast
    java = java.replace('(zend_ast_decl *)', '(ZendAstDecl)')
    # locals: "zend_ast *name =", "uint32_t name =", "zval zv;" (an out-parameter, so a holder)
    java = re.sub(r'\bzend_ast \*(\w+) =', r'ZendAst \1 =', java)
    java = re.sub(r'\buint32_t (\w+) =', r'int \1 =', java)
    java = re.sub(r'\bzval (\w+);', r'zval \1 = new zval();', java)
    java = re.sub(r'&(zv)\b', r'\1', java)
    # C's NULL, member access, compiler and scanner globals
    java = re.sub(r'\bNULL\b', 'null', java)
    java = java.replace('->', '.')
    java = re.sub(r'\bCG\((\w+)\)', r'CG.\1', java)
    java = re.sub(r'\bLANG_SCNG\((\w+)\)', r'LANG_SCNG.\1', java)
    # Bison's control macros are statements returning from the action in Java
    java = re.sub(r'\b(YYABORT|YYERROR|YYACCEPT);', r'return \1;', java)
    # "(void) zendnerrs;" silences a C warning about Bison's error count
    java = re.sub(r'\s*\(void\) zendnerrs;', '', java)

    # C truthiness: of $$ by its type, of a %type <str> value (a doc comment or NULL), of an int local
    lhs = TYPES.get(lhs_tag)

    def truth(expr, tag):
        return '(' + expr + (' != null' if TYPES[tag] != 'Integer' else ' != 0') + ')'

    def falsity(expr, tag):
        return '(' + expr + (' == null' if TYPES[tag] != 'Integer' else ' == 0') + ')'

    if lhs:
        cast = '((' + lhs + ') $$)'
        # !($$ & MASK) and !$$
        java = re.sub(r'!\(\$\$ & (\w+)\)', lambda m: '((' + cast + ' & ' + m.group(1) + ') == 0)', java)
        java = re.sub(r'!\$\$', lambda m: falsity(cast, lhs_tag), java)
        # $$ |= X
        java = re.sub(r'\$\$ \|= ([^;]+);', lambda m: '$$ = ' + cast + ' | ' + m.group(1) + ';', java)
        # any other read of $$: a member access or a comparison
        java = re.sub(r'\$\$(?=\.)', cast, java)
    java = re.sub(r'!(\w+)\)', lambda m: '(' + m.group(1) + ' == 0))'
                  if re.search(r'\bint ' + m.group(1) + r' =', java) else m.group(0), java)
    java = re.sub(r'\$(\d+) \?', lambda m: truth('$' + m.group(1), rhs_tags[int(m.group(1))]) + ' ?', java)

    leftover = re.findall(r'(\b(?:zend_string|zend_ast_list|size_t|unsigned|char)\b|&\w|\*\w+\s*=|\(void\))', java)
    if leftover:
        fail('C left in action %r: %r' % (code, leftover))
    return java


def derive_rules(rules, types):
    out = []
    i = 0
    lhs = None
    rhs = []  # the symbols of the current alternative so far, for $N's tags
    while i < len(rules):
        c = rules[i]
        if rules.startswith('/*', i):
            j = rules.index('*/', i) + 2
            out.append(rules[i:j])
            i = j
            continue
        if c == "'":
            j = rules.index("'", i + 1)
            if rules[i + 1] == '\\':
                j = rules.index("'", i + 3)
            out.append(rules[i:j + 1])
            rhs.append(rules[i:j + 1])
            i = j + 1
            continue
        if c == '{':
            j = read_action(rules, i)
            # the action's $$: the rule's type, unless more symbols follow (a mid-rule action, tagged explicitly)
            mid_rule = re.match(r'\s*(?:/\*.*?\*/\s*)*[|;]', rules[j:], flags=re.S) is None
            tag = None if mid_rule else types.get(lhs)
            rhs_tags = {}
            for n, symbol in enumerate(rhs, start=1):
                rhs_tags[n] = types.get(symbol)
            out.append(translate_action(rules[i:j], tag, rhs_tags))
            rhs.append('{action}')
            i = j
            continue
        m = re.match(r'[A-Za-z_][A-Za-z0-9_]*', rules[i:])
        if m:
            word = m.group(0)
            after = rules[i + len(word):]
            if re.match(r'\s*:', after) and not rules[:i].rstrip().endswith('%prec'):
                lhs = word
                rhs = []
            else:
                rhs.append(word)
            out.append(word)
            i += len(word)
            continue
        if c == '|':
            rhs = []
        elif c == '%':
            m = re.match(r'%(empty|prec)', rules[i:])
            if m:
                out.append(m.group(0))
                if m.group(1) == 'prec':
                    # the precedence symbol isn't a symbol of the rule
                    k = i + len(m.group(0))
                    sym = re.match(r'\s*(\'[^\']*\'|\w+)', rules[k:])
                    out.append(sym.group(0))
                    i = k + len(sym.group(0))
                else:
                    i += len(m.group(0))
                continue
        elif c == ';':
            rhs = []
        out.append(c)
        i += 1
    return ''.join(out)


def main(source, target):
    text = open(source, encoding='utf-8').read()
    prologue, rules, epilogue = split_sections(text)
    types = symbol_types(prologue)
    result = derive_prologue(prologue).rstrip('\n') + '\n\n%% /* Rules */' + derive_rules(rules, types)
    result = result.rstrip('\n') + '\n\n%%\n\n/* php-src\'s epilogue, zend_yytnamerr, is ported in ZendLexer. */\n'
    open(target, 'w', encoding='utf-8', newline='\n').write(result)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        fail('usage: derive_parser.py <zend_language_parser.y> <out .y>')
    main(sys.argv[1], sys.argv[2])
