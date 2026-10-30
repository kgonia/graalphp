#!/usr/bin/env python3
"""Derives the Java re2c scanner (src/main/grammar/zend_language_scanner.re) from php-src's
Zend/zend_language_scanner.l.

php-src's re2c block stays as it is: its definitions, its rules (conditions and regular expressions) in their order,
its "<!*> :=" setup and its re2c:yyfill:check. What changes:
- the C around the block (scanner state, helpers, compile entry points) is replaced by the Java class around it;
  the scanner's state and helpers are ported by hand in ZendScannerBase, which this class extends;
- re2c's Java API is configured at the top of the block (how to read a byte, back up, change condition, the
  YYFILL php-src defines as a macro);
- each rule's action becomes Java: an action made of php-src's scanner macros (RETURN_TOKEN, BEGIN, yyless, ...)
  is rewritten statement by statement below; an action with C beyond those (pointer loops over the input, numbers,
  strings, heredocs) calls its port in ZendScannerBase, named in PORTED with the SHA-256 of php-src's C, so a change
  of that C in php-src fails the derivation until the port follows it.

Usage: derive_scanner.py <php-src zend_language_scanner.l> <out .re>
"""
import hashlib
import re
import sys

#: Actions ported by hand in ZendScannerBase: the rule's header line -> (SHA-256 of its C body, the Java call).
PORTED = {
    '<ST_IN_SCRIPTING>{BNUM} {':
        ('2e968d41f96e4672fb7772741941f49816cd17d3b010e9ab915be9ce627f5ee8', 'return scan_bnum();'),
    '<ST_IN_SCRIPTING>{ONUM} {':
        ('00b69938ed5a02cd269934e5614303ebc9f510f8541c82588f51b2d16ad7a904', 'return scan_onum();'),
    '<ST_IN_SCRIPTING>{LNUM} {':
        ('94a6a1cccf69a13595b86e469b57d94557268480f36e5917ab793727d092235a', 'return scan_lnum();'),
    '<ST_IN_SCRIPTING>{HNUM} {':
        ('d09bcd28013b418476c56c0857451035d1c275142340bad3112192091e50c869', 'return scan_hnum();'),
    '<ST_VAR_OFFSET>[0]|([1-9][0-9]*) { /* Offset could be treated as a long */':
        ('3c1066ac14f9270140c90d6a9bb02b4522c78e2816dd5b22c2583715bd8b280a', 'return scan_var_offset_long();'),
    '<ST_IN_SCRIPTING>{DNUM}|{EXPONENT_DNUM} {':
        ('0e45c7b6d28df08923d9d832a50da746d3cc750c5df872f36d2e2b8ed800cdc5', 'return scan_dnum();'),
    '<INITIAL>{ANY_CHAR} {':
        ('4948f5532ff7b7486363db71fb60dd0728e3423938c60b30ca085d78e7c2b7b9', 'return scan_inline_html();'),
    '<ST_IN_SCRIPTING,ST_LOOKING_FOR_PROPERTY>"#"|"//" {':
        ('dda21b40bdb9a9b1ce172272a4e2d9ebc9a28a925ade7bcd8afb80da39788ece', 'return scan_line_comment();'),
    '<ST_IN_SCRIPTING,ST_LOOKING_FOR_PROPERTY>"/*"|"/**"{WHITESPACE} {':
        ('39400edd7d1a99dfca408fa677aad6b7509159a242178c72b8909c0de666cb18', 'return scan_block_comment();'),
    "<ST_IN_SCRIPTING>b?['] {":
        ('cb7824699b268ae216de4235c42ae5f607f591fffb811b49787be0d5623922cf', 'return scan_single_quoted();'),
    '<ST_IN_SCRIPTING>b?["] {':
        ('a5fa3587918d939a3e57ad18e590be19d476722ac086eb60ca1932784016a5b3', 'return scan_double_quoted();'),
    '<ST_IN_SCRIPTING>b?"<<<"{TABS_AND_SPACES}({LABEL}|([\']{LABEL}[\'])|(["]{LABEL}["])){NEWLINE} {':
        ('a39ad141d6dc03610c6f4918c8c23f33945f6d061105ed386234b0d2f7a4d9ea', 'return scan_heredoc_start();'),
    '<ST_END_HEREDOC>{ANY_CHAR} {':
        ('cb9570583224272efa6ab55b360fd9bd8da9b098ca24d9cde15286d52e6cc200', 'return scan_heredoc_end();'),
    '<ST_DOUBLE_QUOTES>{ANY_CHAR} {':
        ('c28909b66524c1852a469091d469cf83de98709e4a049e5e8fe18d0a8e0d7a0b', 'return scan_double_quotes_content();'),
    '<ST_BACKQUOTE>{ANY_CHAR} {':
        ('267594e8584c666a0e77b0c766430f7b7d32cce6b6c28d8f7cdb54396e7f8ade', 'return scan_backquote_content();'),
    '<ST_HEREDOC>{ANY_CHAR} {':
        ('8a4f80490f11e5369c8f7e06ca78dd361f12d3dc92a3290f6e8729906bfb6627', 'return scan_heredoc_content();'),
    '<ST_NOWDOC>{ANY_CHAR} {':
        ('9e1a9479db32897d9732f6492cef711dbcc27625d9ba261ea4598e3914212717', 'return scan_nowdoc_content();'),
}

#: re2c's Java API, configured at the top of php-src's block.
API = '''/* added by tools/parser/derive_scanner.py: re2c's Java API over ZendScannerBase's buffer (yy_input, with
 * ZEND_MMAP_AHEAD zero bytes after yy_limit) and php-src's YYFILL, which ends the scan past that padding */
re2c:api = generic;
re2c:define:YYCTYPE = "int";
re2c:define:YYPEEK = "(yy_input[yy_cursor] & 0xff)";
re2c:define:YYSKIP = "++yy_cursor;";
re2c:define:YYBACKUP = "yy_marker = yy_cursor;";
re2c:define:YYRESTORE = "yy_cursor = yy_marker;";
re2c:define:YYGETCONDITION = "yy_state";
re2c:define:YYSETCONDITION = "yy_state = @@;";
re2c:define:YYFILL = "if ((yy_cursor + @@) >= (yy_limit + ZEND_MMAP_AHEAD)) { return 0; }";
re2c:cond:enumprefix = "yyc";
'''

#: Statement rewrites of php-src's scanner macros, in order.
REWRITES = [
    # the epilogue's labels, as methods returning the token (or RESTART for "goto restart")
    (r'RETURN_TOKEN_WITH_IDENT\((.+?)\);', r'return emit_token_with_ident(\1);'),
    (r'RETURN_TOKEN_WITH_STR\((.+?), (.+?)\);', r'return emit_token_with_str(\1, \2);'),
    (r'RETURN_TOKEN_WITH_VAL\((.+?)\);', r'return emit_token_with_val(\1);'),
    (r'RETURN_OR_SKIP_TOKEN\((.+?)\);', r'return PARSER_MODE() ? skip_token(\1) : emit_token(\1);'),
    (r'RETURN_EXIT_NESTING_TOKEN\((.+?)\);',
     r'return exit_nesting(\1) != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token(\1);'),
    (r'RETURN_END_TOKEN;',
     r'return check_nesting_at_end() != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token(END);'),
    (r'RETURN_TOKEN\((.+?)\);', r'return emit_token(\1);'),
    (r'goto restart;', r'return RESTART;'),
    (r'goto (return_whitespace|inline_char_handler);', r'return \1();'),
    # conditions: php-src's STATE(name) is yyc##name
    (r'\bBEGIN\((\w+)\)', r'BEGIN(yyc\1)'),
    (r'\byy_push_state\((\w+)\)', r'yy_push_state(yyc\1)'),
    # the scanner's globals and the input: offsets into yy_input
    (r'&SCNG\((\w+)\)', r'\1'),
    (r'\bSCNG\((\w+)\)', r'\1'),
    (r'\bCG\((\w+)\)', r'CG.\1'),
    (r'\bEG\(exception\)', r'CG.exception != null'),
    # yytext[i] reads a byte of the token (yytext(i)); bare yytext is the token's offset
    (r'\(zend_uchar\)\*\(yytext\)', r'yytext(0)'),
    (r'\byytext\[(.+?)\]', r'yytext(\1)'),
    (r'\byytext\b(?!\()', r'yy_text'),
    (r'\byyleng\b', r'yy_leng'),
    (r'\bYYCURSOR\b', r'yy_cursor'),
    (r'\bYYLIMIT\b', r'yy_limit'),
    (r'sizeof\(("(?:[^"\\]|\\.)*")\) - 1', r'\1.length()'),
]

#: C that is left after the rewrites means the action needs a hand port.
NOT_JAVA = re.compile(r'\bgoto\b|\bchar\b|\bsize_t\b|\bint\s+\w+\s*[=;]|->|\*\w|\bmemchr\b|\bestrndup\b|\berrno\b')


def fail(message):
    sys.exit('derive_scanner.py: ' + message)


def block_bounds(lines):
    start = lines.index('/*!re2c')
    end = next(i for i in range(start, len(lines)) if lines[i] == '*/')
    return start, end


def translate(header, body):
    """A rule's action as Java: its port's call, or php-src's macros rewritten."""
    c = '\n'.join(body)
    digest = hashlib.sha256(c.encode('latin-1')).hexdigest()
    if header in PORTED:
        expected, call = PORTED[header]
        if expected != digest:
            fail('the C action of %s is not the one ported (SHA-256 %s, ported %s): update its port in '
                 'ZendScannerBase and the hash in PORTED' % (header, digest, expected or 'none'))
        return ['\t' + call]
    java = []
    for line in body:
        for pattern, replacement in REWRITES:
            line = re.sub(pattern, replacement, line)
        java.append(line)
    text = '\n'.join(java)
    if NOT_JAVA.search(re.sub(r'/\*.*?\*/', '', text)):
        fail('the action of %s has C beyond the scanner macros; port it in ZendScannerBase and name it in '
             'PORTED (SHA-256 %s):\n%s' % (header, digest, c))
    return java


def main(source, target):
    lines = open(source, encoding='latin-1').read().split('\n')
    start, end = block_bounds(lines)
    licence = '\n'.join(lines[:lines.index('*/') + 1])

    block = [API.rstrip('\n')]
    used = set()
    i = start + 1
    while i < end:
        line = lines[i]
        if line.startswith('<!*>'):
            block.append('<!*> := yy_leng = yy_cursor - yy_text;')
            if line != '<!*> := yyleng = YYCURSOR - SCNG(yy_text);':
                fail('unexpected setup rule: ' + line)
            i += 1
            continue
        if line.startswith('<'):
            j = i + 1
            while lines[j] != '}':
                j += 1
            block.append(line)
            block += translate(line, lines[i + 1:j])
            block.append('}')
            used.add(line)
            i = j + 1
            continue
        block.append(line)
        i += 1
    unused = set(PORTED) - used
    if unused:
        fail('ported actions of rules php-src no longer has: %s' % sorted(unused))

    out = [licence, '',
           '/* Derived from php-src 8.5.12\'s Zend/zend_language_scanner.l by tools/parser/derive_scanner.py: php-src\'s',
           ' * re2c block with its actions\' C rewritten as Java or calling their ports in ZendScannerBase (DO NOT EDIT;',
           ' * regenerate with tools/parser/generate.sh). re2c 4.6\'s Java backend makes ZendLanguageScanner.java from it,',
           ' * and tools/parser/split_dfa.py groups its DFA\'s states into methods. */',
           'package pl.codeloom.php.parser.zend.generated;',
           '',
           'import pl.codeloom.php.parser.zend.CompilerGlobals;',
           'import pl.codeloom.php.parser.zend.ZendScannerBase;',
           '',
           'import static pl.codeloom.php.parser.zend.derived.ZendConstants.*;',
           'import static pl.codeloom.php.parser.zend.generated.ZendLanguageParser.Lexer.*;',
           '',
           '/** php-src\'s scanner: its re2c rules, over ZendScannerBase\'s state. */',
           'public final class ZendLanguageScanner extends ZendScannerBase {',
           '    /*!max:re2c*/',
           '    /*!conditions:re2c*/',
           '',
           '    public ZendLanguageScanner(CompilerGlobals CG, byte[] script) {',
           '        super(CG, script);',
           '    }',
           '',
           '    /** One token, or RESTART for php-src\'s "goto restart" (lex_scan scans again). */',
           '    @Override',
           '    protected int scan() {',
           '        yy_text = yy_cursor;',
           '        // @dfa-begin',
           '/*!re2c'] + block + ['*/',
           '        // @dfa-end',
           '    }',
           '',
           '    // @dfa-methods',
           '}']
    open(target, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        fail('usage: derive_scanner.py <zend_language_scanner.l> <out .re>')
    main(sys.argv[1], sys.argv[2])
