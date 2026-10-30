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
   | Authors: Andi Gutmans <andi@php.net>                                 |
   |          Zeev Suraski <zeev@php.net>                                 |
   |          Nikita Popov <nikic@php.net>                                |
   +----------------------------------------------------------------------+
*/

%require "3.8.2"

/* Derived from php-src 8.5.12's Zend/zend_language_parser.y by tools/parser/derive_parser.py: php-src's rules
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


%expect 0

%precedence T_THROW
%precedence PREC_ARROW_FUNCTION
%precedence T_INCLUDE T_INCLUDE_ONCE T_REQUIRE T_REQUIRE_ONCE
%left T_LOGICAL_OR
%left T_LOGICAL_XOR
%left T_LOGICAL_AND
%precedence T_PRINT
%precedence T_YIELD
%precedence T_DOUBLE_ARROW
%precedence T_YIELD_FROM
%precedence '=' T_PLUS_EQUAL T_MINUS_EQUAL T_MUL_EQUAL T_DIV_EQUAL T_CONCAT_EQUAL T_MOD_EQUAL T_AND_EQUAL T_OR_EQUAL T_XOR_EQUAL T_SL_EQUAL T_SR_EQUAL T_POW_EQUAL T_COALESCE_EQUAL
%left '?' ':'
%right T_COALESCE
%left T_BOOLEAN_OR
%left T_BOOLEAN_AND
%left '|'
%left '^'
%left T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG
%nonassoc T_IS_EQUAL T_IS_NOT_EQUAL T_IS_IDENTICAL T_IS_NOT_IDENTICAL T_SPACESHIP
%nonassoc '<' T_IS_SMALLER_OR_EQUAL '>' T_IS_GREATER_OR_EQUAL
%left T_PIPE
%left '.'
%left T_SL T_SR
%left '+' '-'
%left '*' '/' '%'
%precedence '!'
%precedence T_INSTANCEOF
%precedence '~' T_INT_CAST T_DOUBLE_CAST T_STRING_CAST T_ARRAY_CAST T_OBJECT_CAST T_BOOL_CAST T_UNSET_CAST '@'
%right T_POW
%precedence T_CLONE

/* Resolve danging else conflict */
%precedence T_NOELSE
%precedence T_ELSEIF
%precedence T_ELSE

%token <ZendAst> T_LNUMBER   "integer"
%token <ZendAst> T_DNUMBER   "floating-point number"
%token <ZendAst> T_STRING    "identifier"
%token <ZendAst> T_NAME_FULLY_QUALIFIED "fully qualified name"
%token <ZendAst> T_NAME_RELATIVE "namespace-relative name"
%token <ZendAst> T_NAME_QUALIFIED "namespaced name"
%token <ZendAst> T_VARIABLE  "variable"
%token <ZendAst> T_INLINE_HTML
%token <ZendAst> T_ENCAPSED_AND_WHITESPACE  "string content"
%token <ZendAst> T_CONSTANT_ENCAPSED_STRING "quoted string"
%token <ZendAst> T_STRING_VARNAME "variable name"
%token <ZendAst> T_NUM_STRING "number"

%token <Integer> T_INCLUDE       "'include'"
%token <Integer> T_INCLUDE_ONCE  "'include_once'"
%token <Integer> T_EVAL          "'eval'"
%token <Integer> T_REQUIRE       "'require'"
%token <Integer> T_REQUIRE_ONCE  "'require_once'"
%token <Integer> T_LOGICAL_OR    "'or'"
%token <Integer> T_LOGICAL_XOR   "'xor'"
%token <Integer> T_LOGICAL_AND   "'and'"
%token <Integer> T_PRINT         "'print'"
%token <Integer> T_YIELD         "'yield'"
%token <Integer> T_YIELD_FROM    "'yield from'"
%token <Integer> T_INSTANCEOF    "'instanceof'"
%token <Integer> T_NEW           "'new'"
%token <Integer> T_CLONE         "'clone'"
%token <Integer> T_EXIT          "'exit'"
%token <Integer> T_IF            "'if'"
%token <Integer> T_ELSEIF        "'elseif'"
%token <Integer> T_ELSE          "'else'"
%token <Integer> T_ENDIF         "'endif'"
%token <Integer> T_ECHO          "'echo'"
%token <Integer> T_DO            "'do'"
%token <Integer> T_WHILE         "'while'"
%token <Integer> T_ENDWHILE      "'endwhile'"
%token <Integer> T_FOR           "'for'"
%token <Integer> T_ENDFOR        "'endfor'"
%token <Integer> T_FOREACH       "'foreach'"
%token <Integer> T_ENDFOREACH    "'endforeach'"
%token <Integer> T_DECLARE       "'declare'"
%token <Integer> T_ENDDECLARE    "'enddeclare'"
%token <Integer> T_AS            "'as'"
%token <Integer> T_SWITCH        "'switch'"
%token <Integer> T_ENDSWITCH     "'endswitch'"
%token <Integer> T_CASE          "'case'"
%token <Integer> T_DEFAULT       "'default'"
%token <Integer> T_MATCH         "'match'"
%token <Integer> T_BREAK         "'break'"
%token <Integer> T_CONTINUE      "'continue'"
%token <Integer> T_GOTO          "'goto'"
%token <Integer> T_FUNCTION      "'function'"
%token <Integer> T_FN            "'fn'"
%token <Integer> T_CONST         "'const'"
%token <Integer> T_RETURN        "'return'"
%token <Integer> T_TRY           "'try'"
%token <Integer> T_CATCH         "'catch'"
%token <Integer> T_FINALLY       "'finally'"
%token <Integer> T_THROW         "'throw'"
%token <Integer> T_USE           "'use'"
%token <Integer> T_INSTEADOF     "'insteadof'"
%token <Integer> T_GLOBAL        "'global'"
%token <Integer> T_STATIC        "'static'"
%token <Integer> T_ABSTRACT      "'abstract'"
%token <Integer> T_FINAL         "'final'"
%token <Integer> T_PRIVATE       "'private'"
%token <Integer> T_PROTECTED     "'protected'"
%token <Integer> T_PUBLIC        "'public'"
%token <Integer> T_PRIVATE_SET   "'private(set)'"
%token <Integer> T_PROTECTED_SET "'protected(set)'"
%token <Integer> T_PUBLIC_SET    "'public(set)'"
%token <Integer> T_READONLY      "'readonly'"
%token <Integer> T_VAR           "'var'"
%token <Integer> T_UNSET         "'unset'"
%token <Integer> T_ISSET         "'isset'"
%token <Integer> T_EMPTY         "'empty'"
%token <Integer> T_HALT_COMPILER "'__halt_compiler'"
%token <Integer> T_CLASS         "'class'"
%token <Integer> T_TRAIT         "'trait'"
%token <Integer> T_INTERFACE     "'interface'"
%token <Integer> T_ENUM          "'enum'"
%token <Integer> T_EXTENDS       "'extends'"
%token <Integer> T_IMPLEMENTS    "'implements'"
%token <Integer> T_NAMESPACE     "'namespace'"
%token <Integer> T_LIST            "'list'"
%token <Integer> T_ARRAY           "'array'"
%token <Integer> T_CALLABLE        "'callable'"
%token <Integer> T_LINE            "'__LINE__'"
%token <Integer> T_FILE            "'__FILE__'"
%token <Integer> T_DIR             "'__DIR__'"
%token <Integer> T_CLASS_C         "'__CLASS__'"
%token <Integer> T_TRAIT_C         "'__TRAIT__'"
%token <Integer> T_METHOD_C        "'__METHOD__'"
%token <Integer> T_FUNC_C          "'__FUNCTION__'"
%token <Integer> T_PROPERTY_C      "'__PROPERTY__'"
%token <Integer> T_NS_C            "'__NAMESPACE__'"

%token END 0 "end of file"
%token T_ATTRIBUTE    "'#['"
%token T_PLUS_EQUAL   "'+='"
%token T_MINUS_EQUAL  "'-='"
%token T_MUL_EQUAL    "'*='"
%token T_DIV_EQUAL    "'/='"
%token T_CONCAT_EQUAL "'.='"
%token T_MOD_EQUAL    "'%='"
%token T_AND_EQUAL    "'&='"
%token T_OR_EQUAL     "'|='"
%token T_XOR_EQUAL    "'^='"
%token T_SL_EQUAL     "'<<='"
%token T_SR_EQUAL     "'>>='"
%token T_COALESCE_EQUAL "'??='"
%token T_BOOLEAN_OR   "'||'"
%token T_BOOLEAN_AND  "'&&'"
%token T_IS_EQUAL     "'=='"
%token T_IS_NOT_EQUAL "'!='"
%token T_IS_IDENTICAL "'==='"
%token T_IS_NOT_IDENTICAL "'!=='"
%token T_IS_SMALLER_OR_EQUAL "'<='"
%token T_IS_GREATER_OR_EQUAL "'>='"
%token T_SPACESHIP "'<=>'"
%token T_SL "'<<'"
%token T_SR "'>>'"
%token T_INC "'++'"
%token T_DEC "'--'"
%token T_INT_CAST    "'(int)'"
%token T_DOUBLE_CAST "'(float)'"
%token T_STRING_CAST "'(string)'"
%token T_ARRAY_CAST  "'(array)'"
%token T_OBJECT_CAST "'(object)'"
%token T_BOOL_CAST   "'(bool)'"
%token T_UNSET_CAST  "'(unset)'"
%token T_VOID_CAST   "'(void)'"
%token T_OBJECT_OPERATOR "'->'"
%token T_NULLSAFE_OBJECT_OPERATOR "'?->'"
%token T_DOUBLE_ARROW    "'=>'"
%token T_COMMENT         "comment"
%token T_DOC_COMMENT     "doc comment"
%token T_OPEN_TAG        "open tag"
%token T_OPEN_TAG_WITH_ECHO "'<?='"
%token T_CLOSE_TAG       "'?>'"
%token T_WHITESPACE      "whitespace"
%token T_START_HEREDOC   "heredoc start"
%token T_END_HEREDOC     "heredoc end"
%token T_DOLLAR_OPEN_CURLY_BRACES "'${'"
%token T_CURLY_OPEN      "'{$'"
%token T_PAAMAYIM_NEKUDOTAYIM "'::'"
%token T_NS_SEPARATOR    "'\\'"
%token T_ELLIPSIS        "'...'"
%token T_COALESCE        "'??'"
%token T_POW             "'**'"
%token T_POW_EQUAL       "'**='"
%token T_PIPE         "'|>'"
/* We need to split the & token in two to avoid a shift/reduce conflict. For T1&$v and T1&T2,
 * with only one token lookahead, bison does not know whether to reduce T1 as a complete type,
 * or shift to continue parsing an intersection type. */
%token T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG     "'&'"
/* Bison warns on duplicate token literals, so use a different dummy value here.
 * It will be fixed up by zend_yytnamerr() later. */
%token T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG "amp"
%token T_BAD_CHARACTER   "invalid character"

/* Token used to force a parse error from the lexer */
%token T_ERROR

%type <ZendAst> top_statement namespace_name name statement function_declaration_statement
%type <ZendAst> class_declaration_statement trait_declaration_statement legacy_namespace_name
%type <ZendAst> interface_declaration_statement interface_extends_list
%type <ZendAst> group_use_declaration inline_use_declarations inline_use_declaration
%type <ZendAst> mixed_group_use_declaration use_declaration unprefixed_use_declaration
%type <ZendAst> unprefixed_use_declarations const_decl inner_statement
%type <ZendAst> expr optional_expr while_statement for_statement foreach_variable
%type <ZendAst> foreach_statement declare_statement finally_statement unset_variable variable
%type <ZendAst> extends_from parameter optional_type_without_static argument argument_no_expr global_var
%type <ZendAst> static_var class_statement trait_adaptation trait_precedence trait_alias
%type <ZendAst> absolute_trait_method_reference trait_method_reference property echo_expr
%type <ZendAst> new_dereferenceable new_non_dereferenceable anonymous_class class_name class_name_reference simple_variable
%type <ZendAst> internal_functions_in_yacc
%type <ZendAst> scalar backticks_expr lexical_var function_call member_name property_name
%type <ZendAst> variable_class_name dereferenceable_scalar constant class_constant
%type <ZendAst> fully_dereferenceable array_object_dereferenceable
%type <ZendAst> callable_expr callable_variable static_member new_variable
%type <ZendAst> encaps_var encaps_var_offset isset_variables
%type <ZendAst> top_statement_list use_declarations const_list inner_statement_list if_stmt
%type <ZendAst> alt_if_stmt for_cond_exprs for_exprs switch_case_list global_var_list static_var_list
%type <ZendAst> echo_expr_list unset_variables catch_name_list catch_list optional_variable parameter_list class_statement_list
%type <ZendAst> implements_list case_list if_stmt_without_else
%type <ZendAst> non_empty_parameter_list argument_list non_empty_argument_list property_list
%type <ZendAst> class_const_list class_const_decl class_name_list trait_adaptations method_body non_empty_for_exprs
%type <ZendAst> ctor_arguments alt_if_stmt_without_else trait_adaptation_list lexical_vars
%type <ZendAst> lexical_var_list encaps_list
%type <ZendAst> array_pair non_empty_array_pair_list array_pair_list possible_array_pair
%type <ZendAst> isset_variable type return_type type_expr type_without_static
%type <ZendAst> identifier type_expr_without_static union_type_without_static_element union_type_without_static intersection_type_without_static
%type <ZendAst> inline_function union_type_element union_type intersection_type
%type <ZendAst> attributed_statement attributed_top_statement attributed_class_statement attributed_parameter
%type <ZendAst> attribute_decl attribute attributes attribute_group namespace_declaration_name
%type <ZendAst> match match_arm_list non_empty_match_arm_list match_arm match_arm_cond_list
%type <ZendAst> enum_declaration_statement enum_backing_type enum_case enum_case_expr
%type <ZendAst> function_name non_empty_member_modifiers
%type <ZendAst> property_hook property_hook_list optional_property_hook_list hooked_property property_hook_body
%type <ZendAst> optional_parameter_list clone_argument_list non_empty_clone_argument_list

%type <Integer> returns_ref function fn is_reference is_variadic property_modifiers property_hook_modifiers
%type <Integer> method_modifiers class_const_modifiers member_modifier optional_cpp_modifiers
%type <Integer> class_modifiers class_modifier anonymous_class_modifiers anonymous_class_modifiers_optional use_type backup_fn_flags

%type <Integer> backup_lex_pos
%type <byte[]> backup_doc_comment

%type <Integer> reserved_non_modifiers semi_reserved

%% /* Rules */

start:
	top_statement_list	{ CG.ast = $1; }
;

reserved_non_modifiers:
	  T_INCLUDE | T_INCLUDE_ONCE | T_EVAL | T_REQUIRE | T_REQUIRE_ONCE | T_LOGICAL_OR | T_LOGICAL_XOR | T_LOGICAL_AND
	| T_INSTANCEOF | T_NEW | T_CLONE | T_EXIT | T_IF | T_ELSEIF | T_ELSE | T_ENDIF | T_ECHO | T_DO | T_WHILE | T_ENDWHILE
	| T_FOR | T_ENDFOR | T_FOREACH | T_ENDFOREACH | T_DECLARE | T_ENDDECLARE | T_AS | T_TRY | T_CATCH | T_FINALLY
	| T_THROW | T_USE | T_INSTEADOF | T_GLOBAL | T_VAR | T_UNSET | T_ISSET | T_EMPTY | T_CONTINUE | T_GOTO
	| T_FUNCTION | T_CONST | T_RETURN | T_PRINT | T_YIELD | T_LIST | T_SWITCH | T_ENDSWITCH | T_CASE | T_DEFAULT | T_BREAK
	| T_ARRAY | T_CALLABLE | T_EXTENDS | T_IMPLEMENTS | T_NAMESPACE | T_TRAIT | T_INTERFACE | T_CLASS
	| T_CLASS_C | T_TRAIT_C | T_FUNC_C | T_METHOD_C | T_LINE | T_FILE | T_DIR | T_NS_C | T_FN | T_MATCH | T_ENUM
	| T_PROPERTY_C
;

semi_reserved:
	  reserved_non_modifiers
	| T_STATIC | T_ABSTRACT | T_FINAL | T_PRIVATE | T_PROTECTED | T_PUBLIC | T_READONLY
;

ampersand:
		T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG
	|	T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG
;

identifier:
		T_STRING { $$ = $1; }
	| 	semi_reserved  {
			zval zv = new zval();
			if (zend_lex_tstring(zv, $1) == FAILURE) { return YYABORT; }
			$$ = zend_ast_create_zval(zv);
		}
;

top_statement_list:
		top_statement_list top_statement { $$ = zend_ast_list_add($1, $2); }
	|	%empty { $$ = zend_ast_create_list(0, ZEND_AST_STMT_LIST); }
;

/* Name usable in a namespace declaration. */
namespace_declaration_name:
		identifier								{ $$ = $1; }
	|	T_NAME_QUALIFIED						{ $$ = $1; }
;

/* Name usable in "use" declarations (leading separator forbidden). */
namespace_name:
		T_STRING								{ $$ = $1; }
	|	T_NAME_QUALIFIED						{ $$ = $1; }
;

/* Name usable in "use" declarations (leading separator allowed). */
legacy_namespace_name:
		namespace_name							{ $$ = $1; }
	|	T_NAME_FULLY_QUALIFIED					{ $$ = $1; }
;

name:
		T_STRING									{ $$ = $1; ((ZendAst) $$).attr = ZEND_NAME_NOT_FQ; }
	|	T_NAME_QUALIFIED							{ $$ = $1; ((ZendAst) $$).attr = ZEND_NAME_NOT_FQ; }
	|	T_NAME_FULLY_QUALIFIED						{ $$ = $1; ((ZendAst) $$).attr = ZEND_NAME_FQ; }
	|	T_NAME_RELATIVE								{ $$ = $1; ((ZendAst) $$).attr = ZEND_NAME_RELATIVE; }
;

attribute_decl:
		class_name
			{ $$ = zend_ast_create(ZEND_AST_ATTRIBUTE, $1, null); }
	|	class_name argument_list
			{ $$ = zend_ast_create(ZEND_AST_ATTRIBUTE, $1, $2); }
;

attribute_group:
		attribute_decl
			{ $$ = zend_ast_create_list(1, ZEND_AST_ATTRIBUTE_GROUP, $1); }
	|	attribute_group ',' attribute_decl
			{ $$ = zend_ast_list_add($1, $3); }
;

attribute:
		T_ATTRIBUTE backup_doc_comment attribute_group possible_comma ']'	{ $$ = $3; CG.doc_comment = $2; }
;

attributes:
		attribute				{ $$ = zend_ast_create_list(1, ZEND_AST_ATTRIBUTE_LIST, $1); }
	|	attributes attribute	{ $$ = zend_ast_list_add($1, $2); }
;

attributed_statement:
		function_declaration_statement		{ $$ = $1; }
	|	class_declaration_statement			{ $$ = $1; }
	|	trait_declaration_statement			{ $$ = $1; }
	|	interface_declaration_statement		{ $$ = $1; }
	|	enum_declaration_statement			{ $$ = $1; }
;

attributed_top_statement:
		attributed_statement				{ $$ = $1; }
	|	T_CONST const_list ';'				{ $$ = $2; }
;

top_statement:
		statement							{ $$ = $1; }
	|	attributed_top_statement			{ $$ = $1; }
	|	attributes attributed_top_statement	{ $$ = zend_ast_with_attributes($2, $1); }
	|	T_HALT_COMPILER '(' ')' ';'
			{ $$ = zend_ast_create(ZEND_AST_HALT_COMPILER,
			      zend_ast_create_zval_from_long(zend_get_scanned_file_offset()));
			  zend_stop_lexing(); }
	|	T_NAMESPACE namespace_declaration_name ';'
			{ $$ = zend_ast_create(ZEND_AST_NAMESPACE, $2, null);
			  RESET_DOC_COMMENT(); }
	|	T_NAMESPACE namespace_declaration_name { RESET_DOC_COMMENT(); }
		'{' top_statement_list '}'
			{ $$ = zend_ast_create(ZEND_AST_NAMESPACE, $2, $5); }
	|	T_NAMESPACE { RESET_DOC_COMMENT(); }
		'{' top_statement_list '}'
			{ $$ = zend_ast_create(ZEND_AST_NAMESPACE, null, $4); }
	|	T_USE mixed_group_use_declaration ';'		{ $$ = $2; }
	|	T_USE use_type group_use_declaration ';'	{ $$ = $3; ((ZendAst) $$).attr = $2; }
	|	T_USE use_declarations ';'					{ $$ = $2; ((ZendAst) $$).attr = ZEND_SYMBOL_CLASS; }
	|	T_USE use_type use_declarations ';'			{ $$ = $3; ((ZendAst) $$).attr = $2; }
;

use_type:
	 	T_FUNCTION 		{ $$ = ZEND_SYMBOL_FUNCTION; }
	| 	T_CONST 		{ $$ = ZEND_SYMBOL_CONST; }
;

group_use_declaration:
		legacy_namespace_name T_NS_SEPARATOR '{' unprefixed_use_declarations possible_comma '}'
			{ $$ = zend_ast_create(ZEND_AST_GROUP_USE, $1, $4); }
;

mixed_group_use_declaration:
		legacy_namespace_name T_NS_SEPARATOR '{' inline_use_declarations possible_comma '}'
			{ $$ = zend_ast_create(ZEND_AST_GROUP_USE, $1, $4);}
;

possible_comma:
		%empty
	|	','
;

inline_use_declarations:
		inline_use_declarations ',' inline_use_declaration
			{ $$ = zend_ast_list_add($1, $3); }
	|	inline_use_declaration
			{ $$ = zend_ast_create_list(1, ZEND_AST_USE, $1); }
;

unprefixed_use_declarations:
		unprefixed_use_declarations ',' unprefixed_use_declaration
			{ $$ = zend_ast_list_add($1, $3); }
	|	unprefixed_use_declaration
			{ $$ = zend_ast_create_list(1, ZEND_AST_USE, $1); }
;

use_declarations:
		use_declarations ',' use_declaration
			{ $$ = zend_ast_list_add($1, $3); }
	|	use_declaration
			{ $$ = zend_ast_create_list(1, ZEND_AST_USE, $1); }
;

inline_use_declaration:
		unprefixed_use_declaration { $$ = $1; ((ZendAst) $$).attr = ZEND_SYMBOL_CLASS; }
	|	use_type unprefixed_use_declaration { $$ = $2; ((ZendAst) $$).attr = $1; }
;

unprefixed_use_declaration:
		namespace_name
			{ $$ = zend_ast_create(ZEND_AST_USE_ELEM, $1, null); }
	|	namespace_name T_AS T_STRING
			{ $$ = zend_ast_create(ZEND_AST_USE_ELEM, $1, $3); }
;

use_declaration:
		legacy_namespace_name
			{ $$ = zend_ast_create(ZEND_AST_USE_ELEM, $1, null); }
	|	legacy_namespace_name T_AS T_STRING
			{ $$ = zend_ast_create(ZEND_AST_USE_ELEM, $1, $3); }
;

const_list:
		const_list ',' const_decl { $$ = zend_ast_list_add($1, $3); }
	|	const_decl { $$ = zend_ast_create_list(1, ZEND_AST_CONST_DECL, $1); }
;

inner_statement_list:
		inner_statement_list inner_statement
			{ $$ = zend_ast_list_add($1, $2); }
	|	%empty
			{ $$ = zend_ast_create_list(0, ZEND_AST_STMT_LIST); }
;


inner_statement:
		statement { $$ = $1; }
	|	attributed_statement					{ $$ = $1; }
	|	attributes attributed_statement		{ $$ = zend_ast_with_attributes($2, $1); }
	|	T_HALT_COMPILER '(' ')' ';'
			{ $$ = null; zend_throw_exception(zend_ce_compile_error,
			      "__HALT_COMPILER() can only be used from the outermost scope", 0); return YYERROR; }
;


statement:
		'{' inner_statement_list '}' { $$ = $2; }
	|	if_stmt { $$ = $1; }
	|	alt_if_stmt { $$ = $1; }
	|	T_WHILE '(' expr ')' while_statement
			{ $$ = zend_ast_create(ZEND_AST_WHILE, $3, $5); }
	|	T_DO statement T_WHILE '(' expr ')' ';'
			{ $$ = zend_ast_create(ZEND_AST_DO_WHILE, $2, $5); }
	|	T_FOR '(' for_exprs ';' for_cond_exprs ';' for_exprs ')' for_statement
			{ $$ = zend_ast_create(ZEND_AST_FOR, $3, $5, $7, $9); }
	|	T_SWITCH '(' expr ')' switch_case_list
			{ $$ = zend_ast_create(ZEND_AST_SWITCH, $3, $5); }
	|	T_BREAK optional_expr ';'		{ $$ = zend_ast_create(ZEND_AST_BREAK, $2); }
	|	T_CONTINUE optional_expr ';'	{ $$ = zend_ast_create(ZEND_AST_CONTINUE, $2); }
	|	T_RETURN optional_expr ';'		{ $$ = zend_ast_create(ZEND_AST_RETURN, $2); }
	|	T_GLOBAL global_var_list ';'	{ $$ = $2; }
	|	T_STATIC static_var_list ';'	{ $$ = $2; }
	|	T_ECHO echo_expr_list ';'		{ $$ = $2; }
	|	T_INLINE_HTML { $$ = zend_ast_create(ZEND_AST_ECHO, $1); }
	|	expr ';' { $$ = $1; }
	|	T_UNSET '(' unset_variables possible_comma ')' ';' { $$ = $3; }
	|	T_FOREACH '(' expr T_AS foreach_variable ')' foreach_statement
			{ $$ = zend_ast_create(ZEND_AST_FOREACH, $3, $5, null, $7); }
	|	T_FOREACH '(' expr T_AS foreach_variable T_DOUBLE_ARROW foreach_variable ')'
		foreach_statement
			{ $$ = zend_ast_create(ZEND_AST_FOREACH, $3, $7, $5, $9); }
	|	T_DECLARE '(' const_list ')'
			{ if (!zend_handle_encoding_declaration($3)) { return YYERROR; } }
		declare_statement
			{ $$ = zend_ast_create(ZEND_AST_DECLARE, $3, $6); }
	|	';'	/* empty statement */ { $$ = null; }
	|	T_TRY '{' inner_statement_list '}' catch_list finally_statement
			{ $$ = zend_ast_create(ZEND_AST_TRY, $3, $5, $6); }
	|	T_GOTO T_STRING ';' { $$ = zend_ast_create(ZEND_AST_GOTO, $2); }
	|	T_STRING ':' { $$ = zend_ast_create(ZEND_AST_LABEL, $1); }
	|	T_VOID_CAST expr ';' { $$ = zend_ast_create(ZEND_AST_CAST_VOID, $2); }
;

catch_list:
		%empty
			{ $$ = zend_ast_create_list(0, ZEND_AST_CATCH_LIST); }
	|	catch_list T_CATCH '(' catch_name_list optional_variable ')' '{' inner_statement_list '}'
			{ $$ = zend_ast_list_add($1, zend_ast_create(ZEND_AST_CATCH, $4, $5, $8)); }
;

catch_name_list:
		class_name { $$ = zend_ast_create_list(1, ZEND_AST_NAME_LIST, $1); }
	|	catch_name_list '|' class_name { $$ = zend_ast_list_add($1, $3); }
;

optional_variable:
		%empty { $$ = null; }
	|	T_VARIABLE { $$ = $1; }
;

finally_statement:
		%empty { $$ = null; }
	|	T_FINALLY '{' inner_statement_list '}' { $$ = $3; }
;

unset_variables:
		unset_variable { $$ = zend_ast_create_list(1, ZEND_AST_STMT_LIST, $1); }
	|	unset_variables ',' unset_variable { $$ = zend_ast_list_add($1, $3); }
;

unset_variable:
		variable { $$ = zend_ast_create(ZEND_AST_UNSET, $1); }
;

function_name:
		T_STRING { $$ = $1; }
	|	T_READONLY {
			zval zv = new zval();
			if (zend_lex_tstring(zv, $1) == FAILURE) { return YYABORT; }
			$$ = zend_ast_create_zval(zv);
		}
;

function_declaration_statement:
	function returns_ref function_name backup_doc_comment '(' parameter_list ')' return_type
	backup_fn_flags '{' inner_statement_list '}' backup_fn_flags
		{ $$ = zend_ast_create_decl(ZEND_AST_FUNC_DECL, $2 | $13, $1, $4,
		      zend_ast_get_str($3), $6, null, $11, $8, null); CG.extra_fn_flags = $9; }
;

is_reference:
		%empty	{ $$ = 0; }
	|	T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG	{ $$ = ZEND_PARAM_REF; }
;

is_variadic:
		%empty { $$ = 0; }
	|	T_ELLIPSIS  { $$ = ZEND_PARAM_VARIADIC; }
;

class_declaration_statement:
		class_modifiers T_CLASS { $<Integer>$ = CG.zend_lineno; }
		T_STRING extends_from implements_list backup_doc_comment '{' class_statement_list '}'
			{ $$ = zend_ast_create_decl(ZEND_AST_CLASS, $1, $<Integer>3, $7, zend_ast_get_str($4), $5, $6, $9, null, null); }
	|	T_CLASS { $<Integer>$ = CG.zend_lineno; }
		T_STRING extends_from implements_list backup_doc_comment '{' class_statement_list '}'
			{ $$ = zend_ast_create_decl(ZEND_AST_CLASS, 0, $<Integer>2, $6, zend_ast_get_str($3), $4, $5, $8, null, null); }
;

class_modifiers:
		class_modifier 					{ $$ = $1; }
	|	class_modifiers class_modifier
			{ $$ = zend_add_class_modifier($1, $2); if ((((Integer) $$) == 0)) { return YYERROR; } }
;

anonymous_class_modifiers:
		class_modifier
			{ $$ = zend_add_anonymous_class_modifier(0, $1); if ((((Integer) $$) == 0)) { return YYERROR; } }
	|	anonymous_class_modifiers class_modifier
			{ $$ = zend_add_anonymous_class_modifier($1, $2); if ((((Integer) $$) == 0)) { return YYERROR; } }
;

anonymous_class_modifiers_optional:
		%empty				{ $$ = 0; }
	|	anonymous_class_modifiers	{ $$ = $1; }
;

class_modifier:
		T_ABSTRACT 		{ $$ = ZEND_ACC_EXPLICIT_ABSTRACT_CLASS; }
	|	T_FINAL 		{ $$ = ZEND_ACC_FINAL; }
	|	T_READONLY 		{ $$ = ZEND_ACC_READONLY_CLASS|ZEND_ACC_NO_DYNAMIC_PROPERTIES; }
;

trait_declaration_statement:
		T_TRAIT { $<Integer>$ = CG.zend_lineno; }
		T_STRING backup_doc_comment '{' class_statement_list '}'
			{ $$ = zend_ast_create_decl(ZEND_AST_CLASS, ZEND_ACC_TRAIT, $<Integer>2, $4, zend_ast_get_str($3), null, null, $6, null, null); }
;

interface_declaration_statement:
		T_INTERFACE { $<Integer>$ = CG.zend_lineno; }
		T_STRING interface_extends_list backup_doc_comment '{' class_statement_list '}'
			{ $$ = zend_ast_create_decl(ZEND_AST_CLASS, ZEND_ACC_INTERFACE, $<Integer>2, $5, zend_ast_get_str($3), null, $4, $7, null, null); }
;

enum_declaration_statement:
		T_ENUM { $<Integer>$ = CG.zend_lineno; }
		T_STRING enum_backing_type implements_list backup_doc_comment '{' class_statement_list '}'
			{ $$ = zend_ast_create_decl(ZEND_AST_CLASS, ZEND_ACC_ENUM|ZEND_ACC_FINAL, $<Integer>2, $6, zend_ast_get_str($3), null, $5, $8, null, $4); }
;

enum_backing_type:
		%empty	{ $$ = null; }
	|	':' type_expr { $$ = $2; }
;

enum_case:
		T_CASE backup_doc_comment identifier enum_case_expr ';'
			{ $$ = zend_ast_create(ZEND_AST_ENUM_CASE, $3, $4, (($2 != null) ? zend_ast_create_zval_from_str($2) : null), null); }
;

enum_case_expr:
		%empty	{ $$ = null; }
	|	'=' expr { $$ = $2; }
;

extends_from:
		%empty				{ $$ = null; }
	|	T_EXTENDS class_name	{ $$ = $2; }
;

interface_extends_list:
		%empty			        { $$ = null; }
	|	T_EXTENDS class_name_list	{ $$ = $2; }
;

implements_list:
		%empty		        		{ $$ = null; }
	|	T_IMPLEMENTS class_name_list	{ $$ = $2; }
;

foreach_variable:
		variable			{ $$ = $1; }
	|	ampersand variable	{ $$ = zend_ast_create(ZEND_AST_REF, $2); }
	|	T_LIST '(' array_pair_list ')' { $$ = $3; ((ZendAst) $$).attr = ZEND_ARRAY_SYNTAX_LIST; }
	|	'[' array_pair_list ']' { $$ = $2; ((ZendAst) $$).attr = ZEND_ARRAY_SYNTAX_SHORT; }
;

for_statement:
		statement { $$ = $1; }
	|	':' inner_statement_list T_ENDFOR ';' { $$ = $2; }
;

foreach_statement:
		statement { $$ = $1; }
	|	':' inner_statement_list T_ENDFOREACH ';' { $$ = $2; }
;

declare_statement:
		statement { $$ = $1; }
	|	':' inner_statement_list T_ENDDECLARE ';' { $$ = $2; }
;

switch_case_list:
		'{' case_list '}'					{ $$ = $2; }
	|	'{' ';' case_list '}'				{ $$ = $3; }
	|	':' case_list T_ENDSWITCH ';'		{ $$ = $2; }
	|	':' ';' case_list T_ENDSWITCH ';'	{ $$ = $3; }
;

case_list:
		%empty { $$ = zend_ast_create_list(0, ZEND_AST_SWITCH_LIST); }
	|	case_list T_CASE expr ':' inner_statement_list
			{ $$ = zend_ast_list_add($1, zend_ast_create(ZEND_AST_SWITCH_CASE, $3, $5)); }
	|	case_list T_CASE expr ';' inner_statement_list
			{ $$ = zend_ast_list_add($1, zend_ast_create_ex(ZEND_AST_SWITCH_CASE, ZEND_ALT_CASE_SYNTAX, $3, $5)); }
	|	case_list T_DEFAULT ':' inner_statement_list
			{ $$ = zend_ast_list_add($1, zend_ast_create(ZEND_AST_SWITCH_CASE, null, $4)); }
	|	case_list T_DEFAULT ';' inner_statement_list
			{ $$ = zend_ast_list_add($1, zend_ast_create_ex(ZEND_AST_SWITCH_CASE, ZEND_ALT_CASE_SYNTAX, null, $4)); }
;


match:
		T_MATCH '(' expr ')' '{' match_arm_list '}'
			{ $$ = zend_ast_create(ZEND_AST_MATCH, $3, $6); }
;

match_arm_list:
		%empty { $$ = zend_ast_create_list(0, ZEND_AST_MATCH_ARM_LIST); }
	|	non_empty_match_arm_list possible_comma { $$ = $1; }
;

non_empty_match_arm_list:
		match_arm { $$ = zend_ast_create_list(1, ZEND_AST_MATCH_ARM_LIST, $1); }
	|	non_empty_match_arm_list ',' match_arm { $$ = zend_ast_list_add($1, $3); }
;

match_arm:
		match_arm_cond_list possible_comma T_DOUBLE_ARROW expr
			{ $$ = zend_ast_create(ZEND_AST_MATCH_ARM, $1, $4); }
	|	T_DEFAULT possible_comma T_DOUBLE_ARROW expr
			{ $$ = zend_ast_create(ZEND_AST_MATCH_ARM, null, $4); }
;

match_arm_cond_list:
		expr { $$ = zend_ast_create_list(1, ZEND_AST_EXPR_LIST, $1); }
	|	match_arm_cond_list ',' expr { $$ = zend_ast_list_add($1, $3); }
;


while_statement:
		statement { $$ = $1; }
	|	':' inner_statement_list T_ENDWHILE ';' { $$ = $2; }
;


if_stmt_without_else:
		T_IF '(' expr ')' statement
			{ $$ = zend_ast_create_list(1, ZEND_AST_IF,
			      zend_ast_create(ZEND_AST_IF_ELEM, $3, $5)); }
	|	if_stmt_without_else T_ELSEIF '(' expr ')' statement
			{ $$ = zend_ast_list_add($1,
			      zend_ast_create(ZEND_AST_IF_ELEM, $4, $6)); }
;

if_stmt:
		if_stmt_without_else %prec T_NOELSE { $$ = $1; }
	|	if_stmt_without_else T_ELSE statement
			{ $$ = zend_ast_list_add($1, zend_ast_create(ZEND_AST_IF_ELEM, null, $3)); }
;

alt_if_stmt_without_else:
		T_IF '(' expr ')' ':' inner_statement_list
			{ $$ = zend_ast_create_list(1, ZEND_AST_IF,
			      zend_ast_create(ZEND_AST_IF_ELEM, $3, $6)); }
	|	alt_if_stmt_without_else T_ELSEIF '(' expr ')' ':' inner_statement_list
			{ $$ = zend_ast_list_add($1,
			      zend_ast_create(ZEND_AST_IF_ELEM, $4, $7)); }
;

alt_if_stmt:
		alt_if_stmt_without_else T_ENDIF ';' { $$ = $1; }
	|	alt_if_stmt_without_else T_ELSE ':' inner_statement_list T_ENDIF ';'
			{ $$ = zend_ast_list_add($1,
			      zend_ast_create(ZEND_AST_IF_ELEM, null, $4)); }
;

parameter_list:
		non_empty_parameter_list possible_comma { $$ = $1; }
	|	%empty	{ $$ = zend_ast_create_list(0, ZEND_AST_PARAM_LIST); }
;


non_empty_parameter_list:
		attributed_parameter
			{ $$ = zend_ast_create_list(1, ZEND_AST_PARAM_LIST, $1); }
	|	non_empty_parameter_list ',' attributed_parameter
			{ $$ = zend_ast_list_add($1, $3); }
;

attributed_parameter:
		attributes parameter	{ $$ = zend_ast_with_attributes($2, $1); }
	|	parameter				{ $$ = $1; }
;

optional_cpp_modifiers:
		%empty
			{ $$ = 0; }
	|	non_empty_member_modifiers
			{ $$ = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_CPP, $1);
			  if ((((Integer) $$) == 0)) { return YYERROR; } }
;

parameter:
		optional_cpp_modifiers optional_type_without_static
		is_reference is_variadic T_VARIABLE backup_doc_comment optional_property_hook_list
			{ $$ = zend_ast_create_ex(ZEND_AST_PARAM, $1 | $3 | $4, $2, $5, null,
					null, ($6 != null) ? zend_ast_create_zval_from_str($6) : null, $7); }
	|	optional_cpp_modifiers optional_type_without_static
		is_reference is_variadic T_VARIABLE backup_doc_comment '=' expr optional_property_hook_list
			{ $$ = zend_ast_create_ex(ZEND_AST_PARAM, $1 | $3 | $4, $2, $5, $8,
					null, ($6 != null) ? zend_ast_create_zval_from_str($6) : null, $9); }
;

optional_type_without_static:
		%empty	{ $$ = null; }
	|	type_expr_without_static	{ $$ = $1; }
;

type_expr:
		type				{ $$ = $1; }
	|	'?' type			{ $$ = $2; ((ZendAst) $$).attr |= ZEND_TYPE_NULLABLE; }
	|	union_type			{ $$ = $1; }
	|	intersection_type	{ $$ = $1; }
;

type:
		type_without_static	{ $$ = $1; }
	|	T_STATIC			{ $$ = zend_ast_create_ex(ZEND_AST_TYPE, IS_STATIC); }
;

union_type_element:
                type { $$ = $1; }
        |        '(' intersection_type ')' { $$ = $2; }
;

union_type:
		union_type_element '|' union_type_element
			{ $$ = zend_ast_create_list(2, ZEND_AST_TYPE_UNION, $1, $3); }
	|	union_type '|' union_type_element
			{ $$ = zend_ast_list_add($1, $3); }
;

intersection_type:
		type T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG type       { $$ = zend_ast_create_list(2, ZEND_AST_TYPE_INTERSECTION, $1, $3); }
	|	intersection_type T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG type { $$ = zend_ast_list_add($1, $3); }
;

/* Duplicate the type rules without "static",
 * to avoid conflicts with "static" modifier for properties. */

type_expr_without_static:
		type_without_static			{ $$ = $1; }
	|	'?' type_without_static		{ $$ = $2; ((ZendAst) $$).attr |= ZEND_TYPE_NULLABLE; }
	|	union_type_without_static	{ $$ = $1; }
	|	intersection_type_without_static	{ $$ = $1; }
;

type_without_static:
		T_ARRAY		{ $$ = zend_ast_create_ex(ZEND_AST_TYPE, IS_ARRAY); }
	|	T_CALLABLE	{ $$ = zend_ast_create_ex(ZEND_AST_TYPE, IS_CALLABLE); }
	|	name		{ $$ = $1; }
;

union_type_without_static_element:
                type_without_static { $$ = $1; }
        |        '(' intersection_type_without_static ')' { $$ = $2; }
;

union_type_without_static:
		union_type_without_static_element '|' union_type_without_static_element
			{ $$ = zend_ast_create_list(2, ZEND_AST_TYPE_UNION, $1, $3); }
	|	union_type_without_static '|' union_type_without_static_element
			{ $$ = zend_ast_list_add($1, $3); }
;

intersection_type_without_static:
		type_without_static T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG type_without_static
			{ $$ = zend_ast_create_list(2, ZEND_AST_TYPE_INTERSECTION, $1, $3); }
	|	intersection_type_without_static T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG type_without_static
			{ $$ = zend_ast_list_add($1, $3); }
;

return_type:
		%empty	{ $$ = null; }
	|	':' type_expr	{ $$ = $2; }
;

argument_list:
		'(' ')'	{ $$ = zend_ast_create_list(0, ZEND_AST_ARG_LIST); }
	|	'(' non_empty_argument_list possible_comma ')' { $$ = $2; }
	|	'(' T_ELLIPSIS ')' { $$ = zend_ast_create_fcc(); }
;

non_empty_argument_list:
		argument
			{ $$ = zend_ast_create_list(1, ZEND_AST_ARG_LIST, $1); }
	|	non_empty_argument_list ',' argument
			{ $$ = zend_ast_list_add($1, $3); }
;

/* `clone_argument_list` is necessary to resolve a parser ambiguity (shift-reduce conflict)
 * of `clone($expr)`, which could either be parsed as a function call with `$expr` as the first
 * argument or as a use of the `clone` language construct with an expression with useless
 * parenthesis. Both would be valid and result in the same AST / the same semantics.
 * `clone_argument_list` is defined in a way that an `expr` in the first position needs to
 * be followed by a `,` which is not valid syntax for a parenthesized `expr`, ensuring
 * that calling `clone()` with a single unnamed parameter is handled by the language construct
 * syntax.
 */
clone_argument_list:
		'(' ')'	{ $$ = zend_ast_create_list(0, ZEND_AST_ARG_LIST); }
	|	'(' non_empty_clone_argument_list possible_comma ')' { $$ = $2; }
	|	'(' expr ',' ')' { $$ = zend_ast_create_list(1, ZEND_AST_ARG_LIST, $2); }
	|	'(' T_ELLIPSIS ')' { $$ = zend_ast_create_fcc(); }
;

non_empty_clone_argument_list:
		expr ',' argument
			{ $$ = zend_ast_create_list(2, ZEND_AST_ARG_LIST, $1, $3); }
	|	argument_no_expr
			{ $$ = zend_ast_create_list(1, ZEND_AST_ARG_LIST, $1); }
	|	non_empty_clone_argument_list ',' argument
			{ $$ = zend_ast_list_add($1, $3); }
;

argument_no_expr:
		identifier ':' expr
			{ $$ = zend_ast_create(ZEND_AST_NAMED_ARG, $1, $3); }
	|	T_ELLIPSIS expr	{ $$ = zend_ast_create(ZEND_AST_UNPACK, $2); }
;

argument:
		expr { $$ = $1; }
	|	argument_no_expr { $$ = $1; }
;

global_var_list:
		global_var_list ',' global_var { $$ = zend_ast_list_add($1, $3); }
	|	global_var { $$ = zend_ast_create_list(1, ZEND_AST_STMT_LIST, $1); }
;

global_var:
	simple_variable
		{ $$ = zend_ast_create(ZEND_AST_GLOBAL, zend_ast_create(ZEND_AST_VAR, $1)); }
;


static_var_list:
		static_var_list ',' static_var { $$ = zend_ast_list_add($1, $3); }
	|	static_var { $$ = zend_ast_create_list(1, ZEND_AST_STMT_LIST, $1); }
;

static_var:
		T_VARIABLE			{ $$ = zend_ast_create(ZEND_AST_STATIC, $1, null); }
	|	T_VARIABLE '=' expr	{ $$ = zend_ast_create(ZEND_AST_STATIC, $1, $3); }
;

class_statement_list:
		class_statement_list class_statement
			{ $$ = zend_ast_list_add($1, $2); }
	|	%empty
			{ $$ = zend_ast_create_list(0, ZEND_AST_STMT_LIST); }
;


attributed_class_statement:
		property_modifiers optional_type_without_static property_list ';'
			{ $$ = zend_ast_create(ZEND_AST_PROP_GROUP, $2, $3, null);
			  ((ZendAst) $$).attr = $1; }
	|	property_modifiers optional_type_without_static hooked_property
			{ $$ = zend_ast_create(ZEND_AST_PROP_GROUP, $2, zend_ast_create_list(1, ZEND_AST_PROP_DECL, $3), null);
			  ((ZendAst) $$).attr = $1; }
	|	class_const_modifiers T_CONST class_const_list ';'
			{ $$ = zend_ast_create(ZEND_AST_CLASS_CONST_GROUP, $3, null, null);
			  ((ZendAst) $$).attr = $1; }
	|	class_const_modifiers T_CONST type_expr class_const_list ';'
			{ $$ = zend_ast_create(ZEND_AST_CLASS_CONST_GROUP, $4, null, $3);
			  ((ZendAst) $$).attr = $1; }
	|	method_modifiers function returns_ref identifier backup_doc_comment '(' parameter_list ')'
		return_type backup_fn_flags method_body backup_fn_flags
			{ $$ = zend_ast_create_decl(ZEND_AST_METHOD, $3 | $1 | $12, $2, $5,
				  zend_ast_get_str($4), $7, null, $11, $9, null); CG.extra_fn_flags = $10; }
	|	enum_case { $$ = $1; }
;

class_statement:
		attributed_class_statement { $$ = $1; }
	|	attributes attributed_class_statement { $$ = zend_ast_with_attributes($2, $1); }
	|	T_USE class_name_list trait_adaptations
			{ $$ = zend_ast_create(ZEND_AST_USE_TRAIT, $2, $3); }
;

class_name_list:
		class_name { $$ = zend_ast_create_list(1, ZEND_AST_NAME_LIST, $1); }
	|	class_name_list ',' class_name { $$ = zend_ast_list_add($1, $3); }
;

trait_adaptations:
		';'								{ $$ = null; }
	|	'{' '}'							{ $$ = null; }
	|	'{' trait_adaptation_list '}'	{ $$ = $2; }
;

trait_adaptation_list:
		trait_adaptation
			{ $$ = zend_ast_create_list(1, ZEND_AST_TRAIT_ADAPTATIONS, $1); }
	|	trait_adaptation_list trait_adaptation
			{ $$ = zend_ast_list_add($1, $2); }
;

trait_adaptation:
		trait_precedence ';'	{ $$ = $1; }
	|	trait_alias ';'			{ $$ = $1; }
;

trait_precedence:
	absolute_trait_method_reference T_INSTEADOF class_name_list
		{ $$ = zend_ast_create(ZEND_AST_TRAIT_PRECEDENCE, $1, $3); }
;

trait_alias:
		trait_method_reference T_AS T_STRING
			{ $$ = zend_ast_create(ZEND_AST_TRAIT_ALIAS, $1, $3); }
	|	trait_method_reference T_AS reserved_non_modifiers
			{ zval zv = new zval();
			  if (zend_lex_tstring(zv, $3) == FAILURE) { return YYABORT; }
			  $$ = zend_ast_create(ZEND_AST_TRAIT_ALIAS, $1, zend_ast_create_zval(zv)); }
	|	trait_method_reference T_AS member_modifier identifier
			{ int modifiers = zend_modifier_token_to_flag(ZEND_MODIFIER_TARGET_METHOD, $3);
			  $$ = zend_ast_create_ex(ZEND_AST_TRAIT_ALIAS, modifiers, $1, $4);
			  /* identifier nonterminal can cause allocations, so we need to free the node */
			  if ((modifiers == 0)) { zend_ast_destroy($$); return YYERROR; } }
	|	trait_method_reference T_AS member_modifier
			{ int modifiers = zend_modifier_token_to_flag(ZEND_MODIFIER_TARGET_METHOD, $3);
			  $$ = zend_ast_create_ex(ZEND_AST_TRAIT_ALIAS, modifiers, $1, null);
			  /* identifier nonterminal can cause allocations, so we need to free the node */
			  if ((modifiers == 0)) { zend_ast_destroy($$); return YYERROR; } }
;

trait_method_reference:
		identifier
			{ $$ = zend_ast_create(ZEND_AST_METHOD_REFERENCE, null, $1); }
	|	absolute_trait_method_reference { $$ = $1; }
;

absolute_trait_method_reference:
	class_name T_PAAMAYIM_NEKUDOTAYIM identifier
		{ $$ = zend_ast_create(ZEND_AST_METHOD_REFERENCE, $1, $3); }
;

method_body:
		';' /* abstract method */		{ $$ = null; }
	|	'{' inner_statement_list '}'	{ $$ = $2; }
;

property_modifiers:
		non_empty_member_modifiers
			{ $$ = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_PROPERTY, $1);
			  if ((((Integer) $$) == 0)) { return YYERROR; } }
	|	T_VAR
			{ $$ = ZEND_ACC_PUBLIC; }
;

method_modifiers:
		%empty
			{ $$ = ZEND_ACC_PUBLIC; }
	|	non_empty_member_modifiers
			{ $$ = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_METHOD, $1);
			  if ((((Integer) $$) == 0)) { return YYERROR; }
			  if (((((Integer) $$) & ZEND_ACC_PPP_MASK) == 0)) { $$ = ((Integer) $$) | ZEND_ACC_PUBLIC; } }
;

class_const_modifiers:
		%empty
			{ $$ = ZEND_ACC_PUBLIC; }
	|	non_empty_member_modifiers
			{ $$ = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_CONSTANT, $1);
			  if ((((Integer) $$) == 0)) { return YYERROR; }
			  if (((((Integer) $$) & ZEND_ACC_PPP_MASK) == 0)) { $$ = ((Integer) $$) | ZEND_ACC_PUBLIC; } }
;

non_empty_member_modifiers:
		member_modifier
			{ $$ = zend_ast_create_list(1, ZEND_AST_MODIFIER_LIST, zend_ast_create_zval_from_long($1)); }
	|	non_empty_member_modifiers member_modifier
			{ $$ = zend_ast_list_add($1, zend_ast_create_zval_from_long($2)); }
;

member_modifier:
		T_PUBLIC				{ $$ = T_PUBLIC; }
	|	T_PROTECTED				{ $$ = T_PROTECTED; }
	|	T_PRIVATE				{ $$ = T_PRIVATE; }
	|	T_PUBLIC_SET			{ $$ = T_PUBLIC_SET; }
	|	T_PROTECTED_SET			{ $$ = T_PROTECTED_SET; }
	|	T_PRIVATE_SET			{ $$ = T_PRIVATE_SET; }
	|	T_STATIC				{ $$ = T_STATIC; }
	|	T_ABSTRACT				{ $$ = T_ABSTRACT; }
	|	T_FINAL					{ $$ = T_FINAL; }
	|	T_READONLY				{ $$ = T_READONLY; }
;

property_list:
		property_list ',' property { $$ = zend_ast_list_add($1, $3); }
	|	property { $$ = zend_ast_create_list(1, ZEND_AST_PROP_DECL, $1); }
;

property:
		T_VARIABLE backup_doc_comment
			{ $$ = zend_ast_create(ZEND_AST_PROP_ELEM, $1, null, (($2 != null) ? zend_ast_create_zval_from_str($2) : null), null); }
	|	T_VARIABLE '=' expr backup_doc_comment
			{ $$ = zend_ast_create(ZEND_AST_PROP_ELEM, $1, $3, (($4 != null) ? zend_ast_create_zval_from_str($4) : null), null); }
;

hooked_property:
		T_VARIABLE backup_doc_comment '{' property_hook_list '}'
			{ $$ = zend_ast_create(ZEND_AST_PROP_ELEM, $1, null, (($2 != null) ? zend_ast_create_zval_from_str($2) : null), $4); }
	|	T_VARIABLE '=' expr backup_doc_comment '{' property_hook_list '}'
			{ $$ = zend_ast_create(ZEND_AST_PROP_ELEM, $1, $3, (($4 != null) ? zend_ast_create_zval_from_str($4) : null), $6); }
;

property_hook_list:
		%empty { $$ = zend_ast_create_list(0, ZEND_AST_STMT_LIST); }
	|	property_hook_list property_hook { $$ = zend_ast_list_add($1, $2); }
	|	property_hook_list attributes property_hook {
			$$ = zend_ast_list_add($1, zend_ast_with_attributes($3, $2));
		}
;

optional_property_hook_list:
		%empty	{ $$ = null; }
	|	'{' property_hook_list '}'	{ $$ = $2; }
;

property_hook_modifiers:
		%empty { $$ = 0; }
	|	non_empty_member_modifiers {
			$$ = zend_modifier_list_to_flags(ZEND_MODIFIER_TARGET_PROPERTY_HOOK, $1);
			if ((((Integer) $$) == 0)) { return YYERROR; }
		}
;

property_hook:
		property_hook_modifiers returns_ref T_STRING
		backup_doc_comment { $<Integer>$ = CG.zend_lineno; }
		optional_parameter_list backup_fn_flags property_hook_body backup_fn_flags {
			$$ = zend_ast_create_decl(
				ZEND_AST_PROPERTY_HOOK, $1 | $2 | $9, $<Integer>5, $4, zend_ast_get_str($3),
				$6, null, $8, null, null);
			CG.extra_fn_flags = $7;
		}
;

property_hook_body:
		';' { $$ = null; }
	|	'{' inner_statement_list '}' { $$ = $2; }
	|	T_DOUBLE_ARROW expr ';'
			{ $$ = zend_ast_create(ZEND_AST_PROPERTY_HOOK_SHORT_BODY, $2); }
;

optional_parameter_list:
		%empty { $$ = null; }
	|	'(' parameter_list ')' { $$ = $2; }
;

class_const_list:
		class_const_list ',' class_const_decl { $$ = zend_ast_list_add($1, $3); }
	|	class_const_decl { $$ = zend_ast_create_list(1, ZEND_AST_CLASS_CONST_DECL, $1); }
;

class_const_decl:
		T_STRING '=' expr backup_doc_comment { $$ = zend_ast_create(ZEND_AST_CONST_ELEM, $1, $3, (($4 != null) ? zend_ast_create_zval_from_str($4) : null)); }
	|	semi_reserved '=' expr backup_doc_comment {
			zval zv = new zval();
			if (zend_lex_tstring(zv, $1) == FAILURE) { return YYABORT; }
			$$ = zend_ast_create(ZEND_AST_CONST_ELEM, zend_ast_create_zval(zv), $3, (($4 != null) ? zend_ast_create_zval_from_str($4) : null));
		}
;

const_decl:
	T_STRING '=' expr backup_doc_comment { $$ = zend_ast_create(ZEND_AST_CONST_ELEM, $1, $3, (($4 != null) ? zend_ast_create_zval_from_str($4) : null)); }
;

echo_expr_list:
		echo_expr_list ',' echo_expr { $$ = zend_ast_list_add($1, $3); }
	|	echo_expr { $$ = zend_ast_create_list(1, ZEND_AST_STMT_LIST, $1); }
;
echo_expr:
	expr { $$ = zend_ast_create(ZEND_AST_ECHO, $1); }
;

for_cond_exprs:
		%empty			{ $$ = null; }
	|	non_empty_for_exprs ',' expr { $$ = zend_ast_list_add($1, $3); }
	|	expr { $$ = zend_ast_create_list(1, ZEND_AST_EXPR_LIST, $1); }
;

for_exprs:
		%empty			{ $$ = null; }
	|	non_empty_for_exprs	{ $$ = $1; }
;

non_empty_for_exprs:
		non_empty_for_exprs ',' expr { $$ = zend_ast_list_add($1, $3); }
	|	non_empty_for_exprs ',' T_VOID_CAST expr { $$ = zend_ast_list_add($1, zend_ast_create(ZEND_AST_CAST_VOID, $4)); }
	|	T_VOID_CAST expr { $$ = zend_ast_create_list(1, ZEND_AST_EXPR_LIST, zend_ast_create(ZEND_AST_CAST_VOID, $2)); }
	|	expr { $$ = zend_ast_create_list(1, ZEND_AST_EXPR_LIST, $1); }
;

anonymous_class:
		anonymous_class_modifiers_optional T_CLASS { $<Integer>$ = CG.zend_lineno; } ctor_arguments
		extends_from implements_list backup_doc_comment '{' class_statement_list '}' {
			ZendAst decl = zend_ast_create_decl(
				ZEND_AST_CLASS, ZEND_ACC_ANON_CLASS | $1, $<Integer>3, $7, null,
				$5, $6, $9, null, null);
			$$ = zend_ast_create(ZEND_AST_NEW, decl, $4);
		}
;

new_dereferenceable:
		T_NEW class_name_reference argument_list
			{ $$ = zend_ast_create(ZEND_AST_NEW, $2, $3); }
	|	T_NEW anonymous_class
			{ $$ = $2; }
	|	T_NEW attributes anonymous_class
			{ zend_ast_with_attributes($3.child[0], $2); $$ = $3; }
;

new_non_dereferenceable:
		T_NEW class_name_reference
			{ $$ = zend_ast_create(ZEND_AST_NEW, $2, zend_ast_create_list(0, ZEND_AST_ARG_LIST)); }
;

expr:
		variable
			{ $$ = $1; }
	|	T_LIST '(' array_pair_list ')' '=' expr
			{ $3.attr = ZEND_ARRAY_SYNTAX_LIST; $$ = zend_ast_create(ZEND_AST_ASSIGN, $3, $6); }
	|	'[' array_pair_list ']' '=' expr
			{ $2.attr = ZEND_ARRAY_SYNTAX_SHORT; $$ = zend_ast_create(ZEND_AST_ASSIGN, $2, $5); }
	|	variable '=' expr
			{ $$ = zend_ast_create(ZEND_AST_ASSIGN, $1, $3); }
	|	variable '=' ampersand variable
			{ $$ = zend_ast_create(ZEND_AST_ASSIGN_REF, $1, $4); }
	|	T_CLONE clone_argument_list {
			ZendAst name = zend_ast_create_zval_from_str(ZSTR_KNOWN(ZEND_STR_CLONE));
			name.attr = ZEND_NAME_FQ;
			$$ = zend_ast_create(ZEND_AST_CALL, name, $2);
		}
	|	T_CLONE expr {
			ZendAst name = zend_ast_create_zval_from_str(ZSTR_KNOWN(ZEND_STR_CLONE));
			name.attr = ZEND_NAME_FQ;
			$$ = zend_ast_create(ZEND_AST_CALL, name, zend_ast_create_list(1, ZEND_AST_ARG_LIST, $2));
		}
	|	variable T_PLUS_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_ADD, $1, $3); }
	|	variable T_MINUS_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_SUB, $1, $3); }
	|	variable T_MUL_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_MUL, $1, $3); }
	|	variable T_POW_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_POW, $1, $3); }
	|	variable T_DIV_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_DIV, $1, $3); }
	|	variable T_CONCAT_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_CONCAT, $1, $3); }
	|	variable T_MOD_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_MOD, $1, $3); }
	|	variable T_AND_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_BW_AND, $1, $3); }
	|	variable T_OR_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_BW_OR, $1, $3); }
	|	variable T_XOR_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_BW_XOR, $1, $3); }
	|	variable T_SL_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_SL, $1, $3); }
	|	variable T_SR_EQUAL expr
			{ $$ = zend_ast_create_assign_op(ZEND_SR, $1, $3); }
	|	variable T_COALESCE_EQUAL expr
			{ $$ = zend_ast_create(ZEND_AST_ASSIGN_COALESCE, $1, $3); }
	|	variable T_INC { $$ = zend_ast_create(ZEND_AST_POST_INC, $1); }
	|	T_INC variable { $$ = zend_ast_create(ZEND_AST_PRE_INC, $2); }
	|	variable T_DEC { $$ = zend_ast_create(ZEND_AST_POST_DEC, $1); }
	|	T_DEC variable { $$ = zend_ast_create(ZEND_AST_PRE_DEC, $2); }
	|	expr T_BOOLEAN_OR expr
			{ $$ = zend_ast_create(ZEND_AST_OR, $1, $3); }
	|	expr T_BOOLEAN_AND expr
			{ $$ = zend_ast_create(ZEND_AST_AND, $1, $3); }
	|	expr T_LOGICAL_OR expr
			{ $$ = zend_ast_create(ZEND_AST_OR, $1, $3); }
	|	expr T_LOGICAL_AND expr
			{ $$ = zend_ast_create(ZEND_AST_AND, $1, $3); }
	|	expr T_LOGICAL_XOR expr
			{ $$ = zend_ast_create_binary_op(ZEND_BOOL_XOR, $1, $3); }
	|	expr '|' expr	{ $$ = zend_ast_create_binary_op(ZEND_BW_OR, $1, $3); }
	|	expr T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG expr	{ $$ = zend_ast_create_binary_op(ZEND_BW_AND, $1, $3); }
	|	expr T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG expr	{ $$ = zend_ast_create_binary_op(ZEND_BW_AND, $1, $3); }
	|	expr '^' expr	{ $$ = zend_ast_create_binary_op(ZEND_BW_XOR, $1, $3); }
	|	expr '.' expr 	{ $$ = zend_ast_create_concat_op($1, $3); }
	|	expr '+' expr 	{ $$ = zend_ast_create_binary_op(ZEND_ADD, $1, $3); }
	|	expr '-' expr 	{ $$ = zend_ast_create_binary_op(ZEND_SUB, $1, $3); }
	|	expr '*' expr	{ $$ = zend_ast_create_binary_op(ZEND_MUL, $1, $3); }
	|	expr T_POW expr	{ $$ = zend_ast_create_binary_op(ZEND_POW, $1, $3); }
	|	expr '/' expr	{ $$ = zend_ast_create_binary_op(ZEND_DIV, $1, $3); }
	|	expr '%' expr 	{ $$ = zend_ast_create_binary_op(ZEND_MOD, $1, $3); }
	| 	expr T_SL expr	{ $$ = zend_ast_create_binary_op(ZEND_SL, $1, $3); }
	|	expr T_SR expr	{ $$ = zend_ast_create_binary_op(ZEND_SR, $1, $3); }
	|	'+' expr %prec '~' { $$ = zend_ast_create(ZEND_AST_UNARY_PLUS, $2); }
	|	'-' expr %prec '~' { $$ = zend_ast_create(ZEND_AST_UNARY_MINUS, $2); }
	|	'!' expr { $$ = zend_ast_create_ex(ZEND_AST_UNARY_OP, ZEND_BOOL_NOT, $2); }
	|	'~' expr { $$ = zend_ast_create_ex(ZEND_AST_UNARY_OP, ZEND_BW_NOT, $2); }
	|	expr T_IS_IDENTICAL expr
			{ $$ = zend_ast_create_binary_op(ZEND_IS_IDENTICAL, $1, $3); }
	|	expr T_IS_NOT_IDENTICAL expr
			{ $$ = zend_ast_create_binary_op(ZEND_IS_NOT_IDENTICAL, $1, $3); }
	|	expr T_IS_EQUAL expr
			{ $$ = zend_ast_create_binary_op(ZEND_IS_EQUAL, $1, $3); }
	|	expr T_IS_NOT_EQUAL expr
			{ $$ = zend_ast_create_binary_op(ZEND_IS_NOT_EQUAL, $1, $3); }
	|	expr T_PIPE expr
			{ $$ = zend_ast_create(ZEND_AST_PIPE, $1, $3); }
	|	expr '<' expr
			{ $$ = zend_ast_create_binary_op(ZEND_IS_SMALLER, $1, $3); }
	|	expr T_IS_SMALLER_OR_EQUAL expr
			{ $$ = zend_ast_create_binary_op(ZEND_IS_SMALLER_OR_EQUAL, $1, $3); }
	|	expr '>' expr
			{ $$ = zend_ast_create(ZEND_AST_GREATER, $1, $3); }
	|	expr T_IS_GREATER_OR_EQUAL expr
			{ $$ = zend_ast_create(ZEND_AST_GREATER_EQUAL, $1, $3); }
	|	expr T_SPACESHIP expr
			{ $$ = zend_ast_create_binary_op(ZEND_SPACESHIP, $1, $3); }
	|	expr T_INSTANCEOF class_name_reference
			{ $$ = zend_ast_create(ZEND_AST_INSTANCEOF, $1, $3); }
	|	'(' expr ')' {
			$$ = $2;
			if (((ZendAst) $$).kind == ZEND_AST_CONDITIONAL) ((ZendAst) $$).attr = ZEND_PARENTHESIZED_CONDITIONAL;
			if (((ZendAst) $$).kind == ZEND_AST_ARROW_FUNC) ((ZendAst) $$).attr = ZEND_PARENTHESIZED_ARROW_FUNC;
		}
	|	new_dereferenceable { $$ = $1; }
	|	new_non_dereferenceable { $$ = $1; }
	|	expr '?' expr ':' expr
			{ $$ = zend_ast_create(ZEND_AST_CONDITIONAL, $1, $3, $5); }
	|	expr '?' ':' expr
			{ $$ = zend_ast_create(ZEND_AST_CONDITIONAL, $1, null, $4); }
	|	expr T_COALESCE expr
			{ $$ = zend_ast_create(ZEND_AST_COALESCE, $1, $3); }
	|	internal_functions_in_yacc { $$ = $1; }
	|	T_INT_CAST expr		{ $$ = zend_ast_create_cast(IS_LONG, $2); }
	|	T_DOUBLE_CAST expr	{ $$ = zend_ast_create_cast(IS_DOUBLE, $2); }
	|	T_STRING_CAST expr	{ $$ = zend_ast_create_cast(IS_STRING, $2); }
	|	T_ARRAY_CAST expr	{ $$ = zend_ast_create_cast(IS_ARRAY, $2); }
	|	T_OBJECT_CAST expr	{ $$ = zend_ast_create_cast(IS_OBJECT, $2); }
	|	T_BOOL_CAST expr	{ $$ = zend_ast_create_cast(_IS_BOOL, $2); }
	|	T_UNSET_CAST expr	{ $$ = zend_ast_create_cast(IS_NULL, $2); }
	|	T_EXIT ctor_arguments {
			ZendAst name = zend_ast_create_zval_from_str(ZSTR_KNOWN(ZEND_STR_EXIT));
			name.attr = ZEND_NAME_FQ;
			$$ = zend_ast_create(ZEND_AST_CALL, name, $2);
		}
	|	'@' expr			{ $$ = zend_ast_create(ZEND_AST_SILENCE, $2); }
	|	scalar { $$ = $1; }
	|	'`' backticks_expr '`' { $$ = zend_ast_create(ZEND_AST_SHELL_EXEC, $2); }
	|	T_PRINT expr { $$ = zend_ast_create(ZEND_AST_PRINT, $2); }
	|	T_YIELD { $$ = zend_ast_create(ZEND_AST_YIELD, null, null); CG.extra_fn_flags |= ZEND_ACC_GENERATOR; }
	|	T_YIELD expr { $$ = zend_ast_create(ZEND_AST_YIELD, $2, null); CG.extra_fn_flags |= ZEND_ACC_GENERATOR; }
	|	T_YIELD expr T_DOUBLE_ARROW expr { $$ = zend_ast_create(ZEND_AST_YIELD, $4, $2); CG.extra_fn_flags |= ZEND_ACC_GENERATOR; }
	|	T_YIELD_FROM expr { $$ = zend_ast_create(ZEND_AST_YIELD_FROM, $2); CG.extra_fn_flags |= ZEND_ACC_GENERATOR; }
	|	T_THROW expr { $$ = zend_ast_create(ZEND_AST_THROW, $2); }
	|	inline_function { $$ = $1; }
	|	attributes inline_function { $$ = zend_ast_with_attributes($2, $1); }
	|	T_STATIC inline_function { $$ = $2; ((ZendAstDecl) $$).flags |= ZEND_ACC_STATIC; }
	|	attributes T_STATIC inline_function
			{ $$ = zend_ast_with_attributes($3, $1); ((ZendAstDecl) $$).flags |= ZEND_ACC_STATIC; }
	|	match { $$ = $1; }
;


inline_function:
		function returns_ref backup_doc_comment '(' parameter_list ')' lexical_vars return_type
		backup_fn_flags '{' inner_statement_list '}' backup_fn_flags
			{ $$ = zend_ast_create_decl(ZEND_AST_CLOSURE, $2 | $13, $1, $3,
				  null,
				  $5, $7, $11, $8, null); CG.extra_fn_flags = $9; }
	|	fn returns_ref backup_doc_comment '(' parameter_list ')' return_type
		T_DOUBLE_ARROW backup_fn_flags backup_lex_pos expr backup_fn_flags
			{ $$ = zend_ast_create_decl(ZEND_AST_ARROW_FUNC, $2 | $12, $1, $3,
				  null, $5, null, $11, $7, null);
				  CG.extra_fn_flags = $9; }
;

fn:
	T_FN { $$ = CG.zend_lineno; }
;

function:
	T_FUNCTION { $$ = CG.zend_lineno; }
;

backup_doc_comment:
	%empty { $$ = CG.doc_comment; CG.doc_comment = null; }
;

backup_fn_flags:
	%prec PREC_ARROW_FUNCTION %empty { $$ = CG.extra_fn_flags; CG.extra_fn_flags = 0; }
;

backup_lex_pos:
	%empty { $$ = LANG_SCNG.yy_text; }
;

returns_ref:
		%empty	{ $$ = 0; }
	|	ampersand	{ $$ = ZEND_ACC_RETURN_REFERENCE; }
;

lexical_vars:
		%empty { $$ = null; }
	|	T_USE '(' lexical_var_list possible_comma ')' { $$ = $3; }
;

lexical_var_list:
		lexical_var_list ',' lexical_var { $$ = zend_ast_list_add($1, $3); }
	|	lexical_var { $$ = zend_ast_create_list(1, ZEND_AST_CLOSURE_USES, $1); }
;

lexical_var:
		T_VARIABLE		{ $$ = $1; }
	|	ampersand T_VARIABLE	{ $$ = $2; ((ZendAst) $$).attr = ZEND_BIND_REF; }
;

function_call:
		name argument_list
			{ $$ = zend_ast_create(ZEND_AST_CALL, $1, $2); }
	|	T_READONLY argument_list {
			zval zv = new zval();
			if (zend_lex_tstring(zv, $1) == FAILURE) { return YYABORT; }
			$$ = zend_ast_create(ZEND_AST_CALL, zend_ast_create_zval(zv), $2);
		}
	|	class_name T_PAAMAYIM_NEKUDOTAYIM member_name argument_list
			{ $$ = zend_ast_create(ZEND_AST_STATIC_CALL, $1, $3, $4); }
	|	variable_class_name T_PAAMAYIM_NEKUDOTAYIM member_name argument_list
			{ $$ = zend_ast_create(ZEND_AST_STATIC_CALL, $1, $3, $4); }
	|	callable_expr { $<Integer>$ = CG.zend_lineno; } argument_list {
			$$ = zend_ast_create(ZEND_AST_CALL, $1, $3);
			((ZendAst) $$).lineno = $<Integer>2;
		}
;

class_name:
		T_STATIC
			{ zval zv = new zval(); ZVAL_INTERNED_STR(zv, ZSTR_KNOWN(ZEND_STR_STATIC));
			  $$ = zend_ast_create_zval_ex(zv, ZEND_NAME_NOT_FQ); }
	|	name { $$ = $1; }
;

class_name_reference:
		class_name		{ $$ = $1; }
	|	new_variable	{ $$ = $1; }
	|	'(' expr ')'	{ $$ = $2; }
;

backticks_expr:
		%empty
			{ $$ = zend_ast_create_zval_from_str(ZSTR_EMPTY_ALLOC()); }
	|	T_ENCAPSED_AND_WHITESPACE { $$ = $1; }
	|	encaps_list { $$ = $1; }
;


ctor_arguments:
		%empty	{ $$ = zend_ast_create_list(0, ZEND_AST_ARG_LIST); }
	|	argument_list { $$ = $1; }
;


dereferenceable_scalar:
		T_ARRAY '(' array_pair_list ')'	{ $$ = $3; ((ZendAst) $$).attr = ZEND_ARRAY_SYNTAX_LONG; }
	|	'[' array_pair_list ']'			{ $$ = $2; ((ZendAst) $$).attr = ZEND_ARRAY_SYNTAX_SHORT; }
	|	T_CONSTANT_ENCAPSED_STRING		{ $$ = $1; }
	|	'"' encaps_list '"'			 	{ $$ = $2; }
;

scalar:
		T_LNUMBER 	{ $$ = $1; }
	|	T_DNUMBER 	{ $$ = $1; }
	|	T_START_HEREDOC T_ENCAPSED_AND_WHITESPACE T_END_HEREDOC { $$ = $2; }
	|	T_START_HEREDOC T_END_HEREDOC
			{ $$ = zend_ast_create_zval_from_str(ZSTR_EMPTY_ALLOC()); }
	|	T_START_HEREDOC encaps_list T_END_HEREDOC { $$ = $2; }
	|	dereferenceable_scalar	{ $$ = $1; }
	|	constant				{ $$ = $1; }
	|	class_constant			{ $$ = $1; }
;

constant:
		name		{ $$ = zend_ast_create(ZEND_AST_CONST, $1); }
	|	T_LINE 		{ $$ = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_LINE); }
	|	T_FILE 		{ $$ = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_FILE); }
	|	T_DIR   	{ $$ = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_DIR); }
	|	T_TRAIT_C	{ $$ = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_TRAIT_C); }
	|	T_METHOD_C	{ $$ = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_METHOD_C); }
	|	T_FUNC_C	{ $$ = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_FUNC_C); }
	|	T_PROPERTY_C	{ $$ = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_PROPERTY_C); }
	|	T_NS_C		{ $$ = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_NS_C); }
	|	T_CLASS_C	{ $$ = zend_ast_create_ex(ZEND_AST_MAGIC_CONST, T_CLASS_C); }
;

class_constant:
		class_name T_PAAMAYIM_NEKUDOTAYIM identifier
			{ $$ = zend_ast_create_class_const_or_name($1, $3); }
	|	variable_class_name T_PAAMAYIM_NEKUDOTAYIM identifier
			{ $$ = zend_ast_create_class_const_or_name($1, $3); }
	|	class_name T_PAAMAYIM_NEKUDOTAYIM '{' expr '}'
			{ $$ = zend_ast_create(ZEND_AST_CLASS_CONST, $1, $4); }
	|	variable_class_name T_PAAMAYIM_NEKUDOTAYIM '{' expr '}'
			{ $$ = zend_ast_create(ZEND_AST_CLASS_CONST, $1, $4); }
;

optional_expr:
		%empty	{ $$ = null; }
	|	expr		{ $$ = $1; }
;

variable_class_name:
		fully_dereferenceable { $$ = $1; }
;

fully_dereferenceable:
		variable				{ $$ = $1; }
	|	'(' expr ')' {
			$$ = $2;
			if (((ZendAst) $$).kind == ZEND_AST_STATIC_PROP) ((ZendAst) $$).attr = ZEND_PARENTHESIZED_STATIC_PROP;
		}
	|	dereferenceable_scalar	{ $$ = $1; }
	|	class_constant			{ $$ = $1; }
	|	new_dereferenceable		{ $$ = $1; }
;

array_object_dereferenceable:
		fully_dereferenceable	{ $$ = $1; }
	|	constant				{ $$ = $1; }
;

callable_expr:
		callable_variable		{ $$ = $1; }
	|	'(' expr ')'			{ $$ = $2; }
	|	dereferenceable_scalar	{ $$ = $1; }
	|	new_dereferenceable		{ $$ = $1; }
;

callable_variable:
		simple_variable
			{ $$ = zend_ast_create(ZEND_AST_VAR, $1); }
	|	array_object_dereferenceable '[' optional_expr ']'
			{ $$ = zend_ast_create(ZEND_AST_DIM, $1, $3); }
	|	array_object_dereferenceable T_OBJECT_OPERATOR property_name argument_list
			{ $$ = zend_ast_create(ZEND_AST_METHOD_CALL, $1, $3, $4); }
	|	array_object_dereferenceable T_NULLSAFE_OBJECT_OPERATOR property_name argument_list
			{ $$ = zend_ast_create(ZEND_AST_NULLSAFE_METHOD_CALL, $1, $3, $4); }
	|	function_call { $$ = $1; }
;

variable:
		callable_variable
			{ $$ = $1; }
	|	static_member
			{ $$ = $1; }
	|	array_object_dereferenceable T_OBJECT_OPERATOR property_name
			{ $$ = zend_ast_create(ZEND_AST_PROP, $1, $3); }
	|	array_object_dereferenceable T_NULLSAFE_OBJECT_OPERATOR property_name
			{ $$ = zend_ast_create(ZEND_AST_NULLSAFE_PROP, $1, $3); }
;

simple_variable:
		T_VARIABLE			{ $$ = $1; }
	|	'$' '{' expr '}'	{ $$ = $3; }
	|	'$' simple_variable	{ $$ = zend_ast_create(ZEND_AST_VAR, $2); }
;

static_member:
		class_name T_PAAMAYIM_NEKUDOTAYIM simple_variable
			{ $$ = zend_ast_create(ZEND_AST_STATIC_PROP, $1, $3); }
	|	variable_class_name T_PAAMAYIM_NEKUDOTAYIM simple_variable
			{ $$ = zend_ast_create(ZEND_AST_STATIC_PROP, $1, $3); }
;

new_variable:
		simple_variable
			{ $$ = zend_ast_create(ZEND_AST_VAR, $1); }
	|	new_variable '[' optional_expr ']'
			{ $$ = zend_ast_create(ZEND_AST_DIM, $1, $3); }
	|	new_variable T_OBJECT_OPERATOR property_name
			{ $$ = zend_ast_create(ZEND_AST_PROP, $1, $3); }
	|	new_variable T_NULLSAFE_OBJECT_OPERATOR property_name
			{ $$ = zend_ast_create(ZEND_AST_NULLSAFE_PROP, $1, $3); }
	|	class_name T_PAAMAYIM_NEKUDOTAYIM simple_variable
			{ $$ = zend_ast_create(ZEND_AST_STATIC_PROP, $1, $3); }
	|	new_variable T_PAAMAYIM_NEKUDOTAYIM simple_variable
			{ $$ = zend_ast_create(ZEND_AST_STATIC_PROP, $1, $3); }
;

member_name:
		identifier { $$ = $1; }
	|	'{' expr '}'	{ $$ = $2; }
	|	simple_variable	{ $$ = zend_ast_create(ZEND_AST_VAR, $1); }
;

property_name:
		T_STRING { $$ = $1; }
	|	'{' expr '}'	{ $$ = $2; }
	|	simple_variable	{ $$ = zend_ast_create(ZEND_AST_VAR, $1); }
;

array_pair_list:
		non_empty_array_pair_list
			{ /* allow single trailing comma */ $$ = zend_ast_list_rtrim($1); }
;

possible_array_pair:
		%empty { $$ = null; }
	|	array_pair  { $$ = $1; }
;

non_empty_array_pair_list:
		non_empty_array_pair_list ',' possible_array_pair
			{ $$ = zend_ast_list_add($1, $3); }
	|	possible_array_pair
			{ $$ = zend_ast_create_list(1, ZEND_AST_ARRAY, $1); }
;

array_pair:
		expr T_DOUBLE_ARROW expr
			{ $$ = zend_ast_create(ZEND_AST_ARRAY_ELEM, $3, $1); }
	|	expr
			{ $$ = zend_ast_create(ZEND_AST_ARRAY_ELEM, $1, null); }
	|	expr T_DOUBLE_ARROW ampersand variable
			{ $$ = zend_ast_create_ex(ZEND_AST_ARRAY_ELEM, 1, $4, $1); }
	|	ampersand variable
			{ $$ = zend_ast_create_ex(ZEND_AST_ARRAY_ELEM, 1, $2, null); }
	|	T_ELLIPSIS expr
			{ $$ = zend_ast_create(ZEND_AST_UNPACK, $2); }
	|	expr T_DOUBLE_ARROW T_LIST '(' array_pair_list ')'
			{ $5.attr = ZEND_ARRAY_SYNTAX_LIST;
			  $$ = zend_ast_create(ZEND_AST_ARRAY_ELEM, $5, $1); }
	|	T_LIST '(' array_pair_list ')'
			{ $3.attr = ZEND_ARRAY_SYNTAX_LIST;
			  $$ = zend_ast_create(ZEND_AST_ARRAY_ELEM, $3, null); }
;

encaps_list:
		encaps_list encaps_var
			{ $$ = zend_ast_list_add($1, $2); }
	|	encaps_list T_ENCAPSED_AND_WHITESPACE
			{ $$ = zend_ast_list_add($1, $2); }
	|	encaps_var
			{ $$ = zend_ast_create_list(1, ZEND_AST_ENCAPS_LIST, $1); }
	|	T_ENCAPSED_AND_WHITESPACE encaps_var
			{ $$ = zend_ast_create_list(2, ZEND_AST_ENCAPS_LIST, $1, $2); }
;

encaps_var:
		T_VARIABLE
			{ $$ = zend_ast_create(ZEND_AST_VAR, $1); }
	|	T_VARIABLE '[' encaps_var_offset ']'
			{ $$ = zend_ast_create(ZEND_AST_DIM,
			      zend_ast_create(ZEND_AST_VAR, $1), $3); }
	|	T_VARIABLE T_OBJECT_OPERATOR T_STRING
			{ $$ = zend_ast_create(ZEND_AST_PROP,
			      zend_ast_create(ZEND_AST_VAR, $1), $3); }
	|	T_VARIABLE T_NULLSAFE_OBJECT_OPERATOR T_STRING
			{ $$ = zend_ast_create(ZEND_AST_NULLSAFE_PROP,
			      zend_ast_create(ZEND_AST_VAR, $1), $3); }
	|	T_DOLLAR_OPEN_CURLY_BRACES expr '}'
			{ $$ = zend_ast_create_ex(ZEND_AST_VAR, ZEND_ENCAPS_VAR_DOLLAR_CURLY_VAR_VAR, $2); }
	|	T_DOLLAR_OPEN_CURLY_BRACES T_STRING_VARNAME '}'
			{ $$ = zend_ast_create_ex(ZEND_AST_VAR, ZEND_ENCAPS_VAR_DOLLAR_CURLY, $2); }
	|	T_DOLLAR_OPEN_CURLY_BRACES T_STRING_VARNAME '[' expr ']' '}'
			{ $$ = zend_ast_create_ex(ZEND_AST_DIM, ZEND_ENCAPS_VAR_DOLLAR_CURLY,
			      zend_ast_create(ZEND_AST_VAR, $2), $4); }
	|	T_CURLY_OPEN variable '}' { $$ = $2; }
;

encaps_var_offset:
		T_STRING			{ $$ = $1; }
	|	T_NUM_STRING		{ $$ = $1; }
	|	'-' T_NUM_STRING 	{ $$ = zend_negate_num_string($2); }
	|	T_VARIABLE			{ $$ = zend_ast_create(ZEND_AST_VAR, $1); }
;


internal_functions_in_yacc:
		T_ISSET '(' isset_variables possible_comma ')' { $$ = $3; }
	|	T_EMPTY '(' expr ')' { $$ = zend_ast_create(ZEND_AST_EMPTY, $3); }
	|	T_INCLUDE expr
			{ $$ = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_INCLUDE, $2); }
	|	T_INCLUDE_ONCE expr
			{ $$ = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_INCLUDE_ONCE, $2); }
	|	T_EVAL '(' expr ')'
			{ $$ = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_EVAL, $3); }
	|	T_REQUIRE expr
			{ $$ = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_REQUIRE, $2); }
	|	T_REQUIRE_ONCE expr
			{ $$ = zend_ast_create_ex(ZEND_AST_INCLUDE_OR_EVAL, ZEND_REQUIRE_ONCE, $2); }
;

isset_variables:
		isset_variable { $$ = $1; }
	|	isset_variables ',' isset_variable
			{ $$ = zend_ast_create(ZEND_AST_AND, $1, $3); }
;

isset_variable:
		expr { $$ = zend_ast_create(ZEND_AST_ISSET, $1); }
;

%%

/* php-src's epilogue, zend_yytnamerr, is ported in ZendLexer. */
