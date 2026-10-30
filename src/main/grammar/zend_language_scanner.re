/*
   +----------------------------------------------------------------------+
   | Zend Engine                                                          |
   +----------------------------------------------------------------------+
   | Copyright (c) Zend Technologies Ltd. (http://www.zend.com)           |
   +----------------------------------------------------------------------+
   | This source file is subject to version 2.00 of the Zend license,     |
   | that is bundled with this package in the file LICENSE, and is        |
   | available through the world-wide-web at the following url:           |
   | http://www.zend.com/license/2_00.txt.                                |
   | If you did not receive a copy of the Zend license and are unable to  |
   | obtain it through the world-wide-web, please send a note to          |
   | license@zend.com so we can mail you a copy immediately.              |
   +----------------------------------------------------------------------+
   | Authors: Marcus Boerger <helly@php.net>                              |
   |          Nuno Lopes <nlopess@php.net>                                |
   |          Scott MacVicar <scottmac@php.net>                           |
   | Flex version authors:                                                |
   |          Andi Gutmans <andi@php.net>                                 |
   |          Zeev Suraski <zeev@php.net>                                 |
   +----------------------------------------------------------------------+
*/

/* Derived from php-src 8.5.12's Zend/zend_language_scanner.l by tools/parser/derive_scanner.py: php-src's
 * re2c block with its actions' C rewritten as Java or calling their ports in ZendScannerBase (DO NOT EDIT;
 * regenerate with tools/parser/generate.sh). re2c 4.6's Java backend makes ZendLanguageScanner.java from it,
 * and tools/parser/split_dfa.py groups its DFA's states into methods. */
package pl.codeloom.php.parser.zend.generated;

import pl.codeloom.php.parser.zend.CompilerGlobals;
import pl.codeloom.php.parser.zend.ZendScannerBase;

import static pl.codeloom.php.parser.zend.derived.ZendConstants.*;
import static pl.codeloom.php.parser.zend.generated.ZendLanguageParser.Lexer.*;

/** php-src's scanner: its re2c rules, over ZendScannerBase's state. */
public final class ZendLanguageScanner extends ZendScannerBase {
    /*!max:re2c*/
    /*!conditions:re2c*/

    public ZendLanguageScanner(CompilerGlobals CG, byte[] script) {
        super(CG, script);
    }

    /** One token, or RESTART for php-src's "goto restart" (lex_scan scans again). */
    @Override
    protected int scan() {
        yy_text = yy_cursor;
        // @dfa-begin
/*!re2c
/* added by tools/parser/derive_scanner.py: re2c's Java API over ZendScannerBase's buffer (yy_input, with
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
re2c:yyfill:check = 0;
LNUM	[0-9]+(_[0-9]+)*
DNUM	({LNUM}?"."{LNUM})|({LNUM}"."{LNUM}?)
EXPONENT_DNUM	(({LNUM}|{DNUM})[eE][+-]?{LNUM})
HNUM	"0x"[0-9a-fA-F]+(_[0-9a-fA-F]+)*
BNUM	"0b"[01]+(_[01]+)*
ONUM	"0o"[0-7]+(_[0-7]+)*
LABEL	[a-zA-Z_\x80-\xff][a-zA-Z0-9_\x80-\xff]*
WHITESPACE [ \n\r\t]+
TABS_AND_SPACES [ \t]*
TOKENS [;:,.|^&+-/*=%!~$<>?@]
ANY_CHAR [^]
NEWLINE ("\r"|"\n"|"\r\n")
OPTIONAL_WHITESPACE [ \n\r\t]*
/* We don't use re2c with bounds checking, we just return 0 bytes if we read past the input.
 * If we use wildcard matching for comments, we can read past the input, which crashes
 * once we try to report a syntax error because the 0 bytes are not actually part of
 * the token. We prevent this by not allowing 0 bytes, which already aren't valid anyway. */
MULTI_LINE_COMMENT "/*"([^*\x00]*"*"+)([^*/\x00][^*\x00]*"*"+)*"/"
SINGLE_LINE_COMMENT "//"[^\x00\n\r]*[\n\r]
HASH_COMMENT "#"(([^[\x00][^\x00\n\r]*[\n\r])|[\n\r])
WHITESPACE_OR_COMMENTS ({WHITESPACE}|{MULTI_LINE_COMMENT}|{SINGLE_LINE_COMMENT}|{HASH_COMMENT})+
OPTIONAL_WHITESPACE_OR_COMMENTS ({WHITESPACE}|{MULTI_LINE_COMMENT}|{SINGLE_LINE_COMMENT}|{HASH_COMMENT})*

/* compute yyleng before each rule */
<!*> := yy_leng = yy_cursor - yy_text;

<ST_IN_SCRIPTING>"exit" {
	return emit_token_with_ident(T_EXIT);
}

<ST_IN_SCRIPTING>"die" {
	return emit_token_with_ident(T_EXIT);
}

<ST_IN_SCRIPTING>"fn" {
	return emit_token_with_ident(T_FN);
}

<ST_IN_SCRIPTING>"function" {
	return emit_token_with_ident(T_FUNCTION);
}

<ST_IN_SCRIPTING>"const" {
	return emit_token_with_ident(T_CONST);
}

<ST_IN_SCRIPTING>"return" {
	return emit_token_with_ident(T_RETURN);
}

<ST_IN_SCRIPTING>"#[" {
	enter_nesting('[');
	return emit_token(T_ATTRIBUTE);
}

<ST_IN_SCRIPTING>"yield"{WHITESPACE_OR_COMMENTS}"from"[^a-zA-Z0-9_\x80-\xff] {
	yyless(yy_leng - 1);
	HANDLE_NEWLINES(yy_text, yy_leng);
	return emit_token_with_ident(T_YIELD_FROM);
}

<ST_IN_SCRIPTING>"yield" {
	return emit_token_with_ident(T_YIELD);
}

<ST_IN_SCRIPTING>"try" {
	return emit_token_with_ident(T_TRY);
}

<ST_IN_SCRIPTING>"catch" {
	return emit_token_with_ident(T_CATCH);
}

<ST_IN_SCRIPTING>"finally" {
	return emit_token_with_ident(T_FINALLY);
}

<ST_IN_SCRIPTING>"throw" {
	return emit_token_with_ident(T_THROW);
}

<ST_IN_SCRIPTING>"if" {
	return emit_token_with_ident(T_IF);
}

<ST_IN_SCRIPTING>"elseif" {
	return emit_token_with_ident(T_ELSEIF);
}

<ST_IN_SCRIPTING>"endif" {
	return emit_token_with_ident(T_ENDIF);
}

<ST_IN_SCRIPTING>"else" {
	return emit_token_with_ident(T_ELSE);
}

<ST_IN_SCRIPTING>"while" {
	return emit_token_with_ident(T_WHILE);
}

<ST_IN_SCRIPTING>"endwhile" {
	return emit_token_with_ident(T_ENDWHILE);
}

<ST_IN_SCRIPTING>"do" {
	return emit_token_with_ident(T_DO);
}

<ST_IN_SCRIPTING>"for" {
	return emit_token_with_ident(T_FOR);
}

<ST_IN_SCRIPTING>"endfor" {
	return emit_token_with_ident(T_ENDFOR);
}

<ST_IN_SCRIPTING>"foreach" {
	return emit_token_with_ident(T_FOREACH);
}

<ST_IN_SCRIPTING>"endforeach" {
	return emit_token_with_ident(T_ENDFOREACH);
}

<ST_IN_SCRIPTING>"declare" {
	return emit_token_with_ident(T_DECLARE);
}

<ST_IN_SCRIPTING>"enddeclare" {
	return emit_token_with_ident(T_ENDDECLARE);
}

<ST_IN_SCRIPTING>"instanceof" {
	return emit_token_with_ident(T_INSTANCEOF);
}

<ST_IN_SCRIPTING>"as" {
	return emit_token_with_ident(T_AS);
}

<ST_IN_SCRIPTING>"switch" {
	return emit_token_with_ident(T_SWITCH);
}

<ST_IN_SCRIPTING>"match" {
	return emit_token_with_ident(T_MATCH);
}

<ST_IN_SCRIPTING>"endswitch" {
	return emit_token_with_ident(T_ENDSWITCH);
}

<ST_IN_SCRIPTING>"case" {
	return emit_token_with_ident(T_CASE);
}

<ST_IN_SCRIPTING>"default" {
	return emit_token_with_ident(T_DEFAULT);
}

<ST_IN_SCRIPTING>"break" {
	return emit_token_with_ident(T_BREAK);
}

<ST_IN_SCRIPTING>"continue" {
	return emit_token_with_ident(T_CONTINUE);
}

<ST_IN_SCRIPTING>"goto" {
	return emit_token_with_ident(T_GOTO);
}

<ST_IN_SCRIPTING>"echo" {
	return emit_token_with_ident(T_ECHO);
}

<ST_IN_SCRIPTING>"print" {
	return emit_token_with_ident(T_PRINT);
}

<ST_IN_SCRIPTING>"class" {
	return emit_token_with_ident(T_CLASS);
}

<ST_IN_SCRIPTING>"interface" {
	return emit_token_with_ident(T_INTERFACE);
}

<ST_IN_SCRIPTING>"trait" {
	return emit_token_with_ident(T_TRAIT);
}

/*
 * The enum keyword must be followed by whitespace and another identifier.
 * This avoids the BC break of using enum in classes, namespaces, functions and constants.
 */
<ST_IN_SCRIPTING>"enum"{WHITESPACE_OR_COMMENTS}("extends"|"implements") {
	yyless(4);
	return emit_token_with_str(T_STRING, 0);
}
<ST_IN_SCRIPTING>"enum"{WHITESPACE_OR_COMMENTS}[a-zA-Z_\x80-\xff] {
	yyless(4);
	return emit_token_with_ident(T_ENUM);
}

<ST_IN_SCRIPTING>"extends" {
	return emit_token_with_ident(T_EXTENDS);
}

<ST_IN_SCRIPTING>"implements" {
	return emit_token_with_ident(T_IMPLEMENTS);
}

<ST_IN_SCRIPTING>"->" {
	yy_push_state(yycST_LOOKING_FOR_PROPERTY);
	return emit_token(T_OBJECT_OPERATOR);
}

<ST_IN_SCRIPTING>"?->" {
	yy_push_state(yycST_LOOKING_FOR_PROPERTY);
	return emit_token(T_NULLSAFE_OBJECT_OPERATOR);
}

<ST_IN_SCRIPTING,ST_LOOKING_FOR_PROPERTY>{WHITESPACE}+ {
	return return_whitespace();
}

<ST_LOOKING_FOR_PROPERTY>"->" {
	return emit_token(T_OBJECT_OPERATOR);
}

<ST_LOOKING_FOR_PROPERTY>"?->" {
	return emit_token(T_NULLSAFE_OBJECT_OPERATOR);
}

<ST_LOOKING_FOR_PROPERTY>{LABEL} {
	yy_pop_state();
	return emit_token_with_str(T_STRING, 0);
}

<ST_IN_SCRIPTING>"::" {
	return emit_token(T_PAAMAYIM_NEKUDOTAYIM);
}

<ST_IN_SCRIPTING>"..." {
	return emit_token(T_ELLIPSIS);
}

<ST_IN_SCRIPTING>"??" {
	return emit_token(T_COALESCE);
}

<ST_IN_SCRIPTING>"new" {
	return emit_token_with_ident(T_NEW);
}

<ST_IN_SCRIPTING>"clone" {
	return emit_token_with_ident(T_CLONE);
}

<ST_IN_SCRIPTING>"var" {
	return emit_token_with_ident(T_VAR);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("int"){TABS_AND_SPACES}")" {
	return emit_token(T_INT_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("integer"){TABS_AND_SPACES}")" {
	if (PARSER_MODE()) {
		zend_error(E_DEPRECATED, "Non-canonical cast (integer) is deprecated, use the (int) cast instead");
		if (CG.exception != null) {
			return emit_token(T_ERROR);
		}
	}
	return emit_token(T_INT_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("float"){TABS_AND_SPACES}")" {
	return emit_token(T_DOUBLE_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("double"){TABS_AND_SPACES}")" {
	if (PARSER_MODE()) {
		zend_error(E_DEPRECATED, "Non-canonical cast (double) is deprecated, use the (float) cast instead");
		if (CG.exception != null) {
			return emit_token(T_ERROR);
		}
	}
	return emit_token(T_DOUBLE_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}"real"{TABS_AND_SPACES}")" {
	if (PARSER_MODE()) {
		zend_throw_exception(zend_ce_parse_error, "The (real) cast has been removed, use (float) instead", 0);
		return emit_token(T_ERROR);
	}
	return emit_token(T_DOUBLE_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("string"){TABS_AND_SPACES}")" {
	return emit_token(T_STRING_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("binary"){TABS_AND_SPACES}")" {
	if (PARSER_MODE()) {
		zend_error(E_DEPRECATED, "Non-canonical cast (binary) is deprecated, use the (string) cast instead");
		if (CG.exception != null) {
			return emit_token(T_ERROR);
		}
	}
	return emit_token(T_STRING_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}"array"{TABS_AND_SPACES}")" {
	return emit_token(T_ARRAY_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}"object"{TABS_AND_SPACES}")" {
	return emit_token(T_OBJECT_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("bool"){TABS_AND_SPACES}")" {
	return emit_token(T_BOOL_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("boolean"){TABS_AND_SPACES}")" {
	if (PARSER_MODE()) {
		zend_error(E_DEPRECATED, "Non-canonical cast (boolean) is deprecated, use the (bool) cast instead");
		if (CG.exception != null) {
			return emit_token(T_ERROR);
		}
	}
	return emit_token(T_BOOL_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("unset"){TABS_AND_SPACES}")" {
	return emit_token(T_UNSET_CAST);
}

<ST_IN_SCRIPTING>"("{TABS_AND_SPACES}("void"){TABS_AND_SPACES}")" {
	return emit_token(T_VOID_CAST);
}

<ST_IN_SCRIPTING>"eval" {
	return emit_token_with_ident(T_EVAL);
}

<ST_IN_SCRIPTING>"include" {
	return emit_token_with_ident(T_INCLUDE);
}

<ST_IN_SCRIPTING>"include_once" {
	return emit_token_with_ident(T_INCLUDE_ONCE);
}

<ST_IN_SCRIPTING>"require" {
	return emit_token_with_ident(T_REQUIRE);
}

<ST_IN_SCRIPTING>"require_once" {
	return emit_token_with_ident(T_REQUIRE_ONCE);
}

<ST_IN_SCRIPTING>"namespace" {
	return emit_token_with_ident(T_NAMESPACE);
}

<ST_IN_SCRIPTING>"use" {
	return emit_token_with_ident(T_USE);
}

<ST_IN_SCRIPTING>"insteadof" {
    return emit_token_with_ident(T_INSTEADOF);
}

<ST_IN_SCRIPTING>"global" {
	return emit_token_with_ident(T_GLOBAL);
}

<ST_IN_SCRIPTING>"isset" {
	return emit_token_with_ident(T_ISSET);
}

<ST_IN_SCRIPTING>"empty" {
	return emit_token_with_ident(T_EMPTY);
}

<ST_IN_SCRIPTING>"__halt_compiler" {
	return emit_token_with_ident(T_HALT_COMPILER);
}

<ST_IN_SCRIPTING>"static" {
	return emit_token_with_ident(T_STATIC);
}

<ST_IN_SCRIPTING>"abstract" {
	return emit_token_with_ident(T_ABSTRACT);
}

<ST_IN_SCRIPTING>"final" {
	return emit_token_with_ident(T_FINAL);
}

<ST_IN_SCRIPTING>"private" {
	return emit_token_with_ident(T_PRIVATE);
}

<ST_IN_SCRIPTING>"protected" {
	return emit_token_with_ident(T_PROTECTED);
}

<ST_IN_SCRIPTING>"public(set)" {
	return emit_token_with_ident(T_PUBLIC_SET);
}

<ST_IN_SCRIPTING>"protected(set)" {
	return emit_token_with_ident(T_PROTECTED_SET);
}

<ST_IN_SCRIPTING>"private(set)" {
	return emit_token_with_ident(T_PRIVATE_SET);
}

<ST_IN_SCRIPTING>"public" {
	return emit_token_with_ident(T_PUBLIC);
}

<ST_IN_SCRIPTING>"readonly" {
	return emit_token_with_ident(T_READONLY);
}

<ST_IN_SCRIPTING>"unset" {
	return emit_token_with_ident(T_UNSET);
}

<ST_IN_SCRIPTING>"=>" {
	return emit_token(T_DOUBLE_ARROW);
}

<ST_IN_SCRIPTING>"list" {
	return emit_token_with_ident(T_LIST);
}

<ST_IN_SCRIPTING>"array" {
	return emit_token_with_ident(T_ARRAY);
}

<ST_IN_SCRIPTING>"callable" {
	return emit_token_with_ident(T_CALLABLE);
}

<ST_IN_SCRIPTING>"++" {
	return emit_token(T_INC);
}

<ST_IN_SCRIPTING>"--" {
	return emit_token(T_DEC);
}

<ST_IN_SCRIPTING>"===" {
	return emit_token(T_IS_IDENTICAL);
}

<ST_IN_SCRIPTING>"!==" {
	return emit_token(T_IS_NOT_IDENTICAL);
}

<ST_IN_SCRIPTING>"==" {
	return emit_token(T_IS_EQUAL);
}

<ST_IN_SCRIPTING>"!="|"<>" {
	return emit_token(T_IS_NOT_EQUAL);
}

<ST_IN_SCRIPTING>"<=>" {
	return emit_token(T_SPACESHIP);
}

<ST_IN_SCRIPTING>"<=" {
	return emit_token(T_IS_SMALLER_OR_EQUAL);
}

<ST_IN_SCRIPTING>">=" {
	return emit_token(T_IS_GREATER_OR_EQUAL);
}

<ST_IN_SCRIPTING>"+=" {
	return emit_token(T_PLUS_EQUAL);
}

<ST_IN_SCRIPTING>"-=" {
	return emit_token(T_MINUS_EQUAL);
}

<ST_IN_SCRIPTING>"*=" {
	return emit_token(T_MUL_EQUAL);
}

<ST_IN_SCRIPTING>"**" {
	return emit_token(T_POW);
}

<ST_IN_SCRIPTING>"**=" {
	return emit_token(T_POW_EQUAL);
}

<ST_IN_SCRIPTING>"/=" {
	return emit_token(T_DIV_EQUAL);
}

<ST_IN_SCRIPTING>".=" {
	return emit_token(T_CONCAT_EQUAL);
}

<ST_IN_SCRIPTING>"%=" {
	return emit_token(T_MOD_EQUAL);
}

<ST_IN_SCRIPTING>"<<=" {
	return emit_token(T_SL_EQUAL);
}

<ST_IN_SCRIPTING>">>=" {
	return emit_token(T_SR_EQUAL);
}

<ST_IN_SCRIPTING>"&=" {
	return emit_token(T_AND_EQUAL);
}

<ST_IN_SCRIPTING>"|=" {
	return emit_token(T_OR_EQUAL);
}

<ST_IN_SCRIPTING>"^=" {
	return emit_token(T_XOR_EQUAL);
}

<ST_IN_SCRIPTING>"??=" {
	return emit_token(T_COALESCE_EQUAL);
}

<ST_IN_SCRIPTING>"|>" {
	return emit_token(T_PIPE);
}

<ST_IN_SCRIPTING>"||" {
	return emit_token(T_BOOLEAN_OR);
}

<ST_IN_SCRIPTING>"&&" {
	return emit_token(T_BOOLEAN_AND);
}

<ST_IN_SCRIPTING>"OR" {
	return emit_token_with_ident(T_LOGICAL_OR);
}

<ST_IN_SCRIPTING>"AND" {
	return emit_token_with_ident(T_LOGICAL_AND);
}

<ST_IN_SCRIPTING>"XOR" {
	return emit_token_with_ident(T_LOGICAL_XOR);
}

<ST_IN_SCRIPTING>"<<" {
	return emit_token(T_SL);
}

<ST_IN_SCRIPTING>">>" {
	return emit_token(T_SR);
}

<ST_IN_SCRIPTING>"&"{OPTIONAL_WHITESPACE_OR_COMMENTS}("$"|"...") {
	yyless(1);
	return emit_token(T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG);
}

<ST_IN_SCRIPTING>"&" {
	return emit_token(T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG);
}

<ST_IN_SCRIPTING>"]"|")" {
	/* Check that ] and ) match up properly with a preceding [ or ( */
	return exit_nesting(yytext(0)) != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token(yytext(0));
}

<ST_IN_SCRIPTING>"["|"(" {
	enter_nesting(yytext(0));
	return emit_token(yytext(0));
}

<ST_IN_SCRIPTING>{TOKENS} {
	return emit_token(yytext(0));
}


<ST_IN_SCRIPTING>"{" {
	yy_push_state(yycST_IN_SCRIPTING);
	enter_nesting('{');
	return emit_token('{');
}


<ST_DOUBLE_QUOTES,ST_BACKQUOTE,ST_HEREDOC>"${" {
	yy_push_state(yycST_LOOKING_FOR_VARNAME);
	enter_nesting('{');
	return emit_token(T_DOLLAR_OPEN_CURLY_BRACES);
}

<ST_IN_SCRIPTING>"}" {
	RESET_DOC_COMMENT();
	if (!zend_stack_is_empty(state_stack)) {
		yy_pop_state();
	}
	return exit_nesting('}') != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token('}');
}


<ST_LOOKING_FOR_VARNAME>{LABEL}[[}] {
	yyless(yy_leng - 1);
	yy_pop_state();
	yy_push_state(yycST_IN_SCRIPTING);
	return emit_token_with_str(T_STRING_VARNAME, 0);
}


<ST_LOOKING_FOR_VARNAME>{ANY_CHAR} {
	yyless(0);
	yy_pop_state();
	yy_push_state(yycST_IN_SCRIPTING);
	return RESTART;
}

<ST_IN_SCRIPTING>{BNUM} {
	return scan_bnum();
}

<ST_IN_SCRIPTING>{ONUM} {
	return scan_onum();
}

<ST_IN_SCRIPTING>{LNUM} {
	return scan_lnum();
}

<ST_IN_SCRIPTING>{HNUM} {
	return scan_hnum();
}

<ST_VAR_OFFSET>[0]|([1-9][0-9]*) { /* Offset could be treated as a long */
	return scan_var_offset_long();
}

<ST_VAR_OFFSET>{LNUM}|{HNUM}|{BNUM}|{ONUM} { /* Offset must be treated as a string */
	if (yy_leng == 1) {
		ZVAL_INTERNED_STR(zendlval, ZSTR_CHAR(yytext(0)));
	} else {
		ZVAL_STRINGL(zendlval, yy_text, yy_leng);
	}
	return emit_token_with_val(T_NUM_STRING);
}

<ST_IN_SCRIPTING>{DNUM}|{EXPONENT_DNUM} {
	return scan_dnum();
}

<ST_IN_SCRIPTING>"__CLASS__" {
	return emit_token_with_ident(T_CLASS_C);
}

<ST_IN_SCRIPTING>"__TRAIT__" {
	return emit_token_with_ident(T_TRAIT_C);
}

<ST_IN_SCRIPTING>"__FUNCTION__" {
	return emit_token_with_ident(T_FUNC_C);
}

<ST_IN_SCRIPTING>"__PROPERTY__" {
	return emit_token_with_ident(T_PROPERTY_C);
}

<ST_IN_SCRIPTING>"__METHOD__" {
	return emit_token_with_ident(T_METHOD_C);
}

<ST_IN_SCRIPTING>"__LINE__" {
	return emit_token_with_ident(T_LINE);
}

<ST_IN_SCRIPTING>"__FILE__" {
	return emit_token_with_ident(T_FILE);
}

<ST_IN_SCRIPTING>"__DIR__" {
	return emit_token_with_ident(T_DIR);
}

<ST_IN_SCRIPTING>"__NAMESPACE__" {
	return emit_token_with_ident(T_NS_C);
}

<SHEBANG>"#!" .* {NEWLINE} {
	CG.zend_lineno++;
	BEGIN(yycINITIAL);
	return RESTART;
}

<SHEBANG>{ANY_CHAR} {
	yyless(0);
	BEGIN(yycINITIAL);
	return RESTART;
}

<INITIAL>"<?=" {
	BEGIN(yycST_IN_SCRIPTING);
	if (PARSER_MODE()) {
		/* We'll reject this as an identifier in zend_lex_tstring. */
		return emit_token_with_ident(T_ECHO);
	}
	return emit_token(T_OPEN_TAG_WITH_ECHO);
}


<INITIAL>"<?php"([ \t]|{NEWLINE}) {
	HANDLE_NEWLINE(yytext(yy_leng-1));
	BEGIN(yycST_IN_SCRIPTING);
	return PARSER_MODE() ? skip_token(T_OPEN_TAG) : emit_token(T_OPEN_TAG);
}

<INITIAL>"<?php" {
	/* Allow <?php followed by end of file. */
	if (yy_cursor == yy_limit) {
		BEGIN(yycST_IN_SCRIPTING);
		return PARSER_MODE() ? skip_token(T_OPEN_TAG) : emit_token(T_OPEN_TAG);
	}
	/* Degenerate case: <?phpX is interpreted as <? phpX with short tags. */
	if (CG.short_tags) {
		yyless(2);
		BEGIN(yycST_IN_SCRIPTING);
		return PARSER_MODE() ? skip_token(T_OPEN_TAG) : emit_token(T_OPEN_TAG);
	}
	return inline_char_handler();
}

<INITIAL>"<?" {
	if (CG.short_tags) {
		BEGIN(yycST_IN_SCRIPTING);
		return PARSER_MODE() ? skip_token(T_OPEN_TAG) : emit_token(T_OPEN_TAG);
	} else {
		return inline_char_handler();
	}
}

<INITIAL>{ANY_CHAR} {
	return scan_inline_html();
}


/* Make sure a label character follows "->" or "?->", otherwise there is no property
 * and "->"/"?->" will be taken literally
 */
<ST_DOUBLE_QUOTES,ST_HEREDOC,ST_BACKQUOTE>"$"{LABEL}"->"[a-zA-Z_\x80-\xff] {
	yyless(yy_leng - 3);
	yy_push_state(yycST_LOOKING_FOR_PROPERTY);
	return emit_token_with_str(T_VARIABLE, 1);
}

<ST_DOUBLE_QUOTES,ST_HEREDOC,ST_BACKQUOTE>"$"{LABEL}"?->"[a-zA-Z_\x80-\xff] {
	yyless(yy_leng - 4);
	yy_push_state(yycST_LOOKING_FOR_PROPERTY);
	return emit_token_with_str(T_VARIABLE, 1);
}

/* A [ always designates a variable offset, regardless of what follows
 */
<ST_DOUBLE_QUOTES,ST_HEREDOC,ST_BACKQUOTE>"$"{LABEL}"[" {
	yyless(yy_leng - 1);
	yy_push_state(yycST_VAR_OFFSET);
	return emit_token_with_str(T_VARIABLE, 1);
}

<ST_IN_SCRIPTING,ST_DOUBLE_QUOTES,ST_HEREDOC,ST_BACKQUOTE,ST_VAR_OFFSET>"$"{LABEL} {
	return emit_token_with_str(T_VARIABLE, 1);
}

<ST_VAR_OFFSET>"]" {
	yy_pop_state();
	return emit_token(']');
}

<ST_VAR_OFFSET>{TOKENS}|[[(){}"`] {
	/* Only '[' or '-' can be valid, but returning other tokens will allow a more explicit parse error */
	return emit_token(yytext(0));
}

<ST_VAR_OFFSET>[ \n\r\t\\'#] {
	/* Invalid rule to return a more explicit parse error with proper line number */
	yyless(0);
	yy_pop_state();
	ZVAL_NULL(zendlval);
	return emit_token_with_val(T_ENCAPSED_AND_WHITESPACE);
}

<ST_IN_SCRIPTING>"namespace"("\\"{LABEL})+ {
	return emit_token_with_str(T_NAME_RELATIVE, "namespace\\".length());
}

<ST_IN_SCRIPTING>{LABEL}("\\"{LABEL})+ {
	return emit_token_with_str(T_NAME_QUALIFIED, 0);
}

<ST_IN_SCRIPTING>"\\"{LABEL}("\\"{LABEL})* {
	return emit_token_with_str(T_NAME_FULLY_QUALIFIED, 1);
}

<ST_IN_SCRIPTING>"\\" {
	return emit_token(T_NS_SEPARATOR);
}

<ST_IN_SCRIPTING,ST_VAR_OFFSET>{LABEL} {
	return emit_token_with_str(T_STRING, 0);
}


<ST_IN_SCRIPTING,ST_LOOKING_FOR_PROPERTY>"#"|"//" {
	return scan_line_comment();
}

<ST_IN_SCRIPTING,ST_LOOKING_FOR_PROPERTY>"/*"|"/**"{WHITESPACE} {
	return scan_block_comment();
}

<ST_LOOKING_FOR_PROPERTY>{ANY_CHAR} {
	yyless(0);
	yy_pop_state();
	return RESTART;
}

<ST_IN_SCRIPTING>"?>"{NEWLINE}? {
	BEGIN(yycINITIAL);
	if (yytext(yy_leng-1) != '>') {
		CG.increment_lineno = 1;
	}
	if (PARSER_MODE()) {
		return emit_token(';');  /* implicit ';' at php-end tag */
	}
	return emit_token(T_CLOSE_TAG);
}


<ST_IN_SCRIPTING>b?['] {
	return scan_single_quoted();
}


<ST_IN_SCRIPTING>b?["] {
	return scan_double_quoted();
}


<ST_IN_SCRIPTING>b?"<<<"{TABS_AND_SPACES}({LABEL}|([']{LABEL}['])|(["]{LABEL}["])){NEWLINE} {
	return scan_heredoc_start();
}


<ST_IN_SCRIPTING>[`] {
	BEGIN(yycST_BACKQUOTE);
	return emit_token('`');
}


<ST_END_HEREDOC>{ANY_CHAR} {
	return scan_heredoc_end();
}


<ST_DOUBLE_QUOTES,ST_BACKQUOTE,ST_HEREDOC>"{$" {
	yy_push_state(yycST_IN_SCRIPTING);
	yyless(1);
	enter_nesting('{');
	return emit_token(T_CURLY_OPEN);
}


<ST_DOUBLE_QUOTES>["] {
	BEGIN(yycST_IN_SCRIPTING);
	return emit_token('"');
}

<ST_BACKQUOTE>[`] {
	BEGIN(yycST_IN_SCRIPTING);
	return emit_token('`');
}


<ST_DOUBLE_QUOTES>{ANY_CHAR} {
	return scan_double_quotes_content();
}


<ST_BACKQUOTE>{ANY_CHAR} {
	return scan_backquote_content();
}


<ST_HEREDOC>{ANY_CHAR} {
	return scan_heredoc_content();
}


<ST_NOWDOC>{ANY_CHAR} {
	return scan_nowdoc_content();
}


<ST_IN_SCRIPTING,ST_VAR_OFFSET>{ANY_CHAR} {
	if (yy_cursor > yy_limit) {
		return check_nesting_at_end() != SUCCESS && PARSER_MODE() ? emit_token(T_ERROR) : emit_token(END);
	}

	return emit_token(T_BAD_CHARACTER);
}

*/
        // @dfa-end
    }

    // @dfa-methods
}
