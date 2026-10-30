#!/bin/sh
# Runs in the truffle-php-parsergen image (tools/parser/Dockerfile): makes the parser's derived and generated
# sources from php-src's and php-ast's files, under the directory given (the repository, or a copy to check).
# Paths stay relative to that directory, as Bison writes the grammar's path into its output.
# Usage: generate-in-container.sh <root>
set -eu
tools=$(cd "$(dirname "$0")" && pwd)
php=/opt/src/php-src/Zend
ast=/opt/src/php-ast
cd "$1"
grammar=src/main/grammar
zend=src/main/java/pl/codeloom/php/parser/zend
mkdir -p "$grammar" "$zend/derived" "$zend/generated"

# derived by script from php-src and php-ast
python3 "$tools/derive_parser.py" "$php/zend_language_parser.y" "$grammar/zend_language_parser.y"
python3 "$tools/derive_scanner.py" "$php/zend_language_scanner.l" "$grammar/zend_language_scanner.re"
python3 "$tools/derive_constants.py" "$php" "$grammar/zend_language_parser.y" "$zend/derived/ZendConstants.java"
python3 "$tools/derive_php_ast.py" "$ast" "$zend/derived/PhpAstData.java"

# generated: Bison's Java skeleton, re2c's Java backend (php-src's flags: --case-inverted -cbF; -d is its debug
# output, -t its C header), and the DFA's states grouped into methods
bison -Wall -Werror -o "$zend/generated/ZendLanguageParser.java" "$grammar/zend_language_parser.y"
python3 "$tools/post_bison.py" "$zend/generated/ZendLanguageParser.java" "$zend/generated/ZendLanguageParser.java"
re2c --lang java --case-inverted -cbF --no-generation-date -W -Werror \
    -o "$zend/generated/ZendLanguageScanner.java.re2c" "$grammar/zend_language_scanner.re"
python3 "$tools/split_dfa.py" "$zend/generated/ZendLanguageScanner.java.re2c" "$zend/generated/ZendLanguageScanner.java"
rm "$zend/generated/ZendLanguageScanner.java.re2c"
