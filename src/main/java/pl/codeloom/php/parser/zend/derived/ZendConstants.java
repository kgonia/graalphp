/*
 * Derived from php-src 8.5.12's Zend headers by tools/parser/derive_constants.py: the constants the parser
 * uses, under php-src's names and with php-src's values. DO NOT EDIT; regenerate with tools/parser/generate.sh.
 * php-src is under the Zend Engine License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend.derived;

/** php-src's constants that the parser uses. */
public final class ZendConstants {
    private ZendConstants() {
    }

    // #defines, each after those it refers to
    public static final int IS_ARRAY = 7; // zend_types.h
    public static final int IS_CALLABLE = 12; // zend_types.h
    public static final int IS_DOUBLE = 5; // zend_types.h
    public static final int IS_FALSE = 2; // zend_types.h
    public static final int IS_ITERABLE = 13; // zend_types.h
    public static final int IS_LONG = 4; // zend_types.h
    public static final int IS_MIXED = 16; // zend_types.h
    public static final int IS_NEVER = 17; // zend_types.h
    public static final int IS_NULL = 1; // zend_types.h
    public static final int IS_OBJECT = 8; // zend_types.h
    public static final int IS_STATIC = 15; // zend_types.h
    public static final int IS_STRING = 6; // zend_types.h
    public static final int IS_TRUE = 3; // zend_types.h
    public static final int IS_UNDEF = 0; // zend_types.h
    public static final int IS_VOID = 14; // zend_types.h
    public static final int ZEND_ACC_ABSTRACT = (1 <<  6); // zend_compile.h
    public static final int ZEND_ACC_ANON_CLASS = (1 <<  2); // zend_compile.h
    public static final int ZEND_ACC_ENUM = (1 << 28); // zend_compile.h
    public static final int ZEND_ACC_EXPLICIT_ABSTRACT_CLASS = (1 <<  6); // zend_compile.h
    public static final int ZEND_ACC_FINAL = (1 <<  5); // zend_compile.h
    public static final int ZEND_ACC_GENERATOR = (1 << 24); // zend_compile.h
    public static final int ZEND_ACC_INTERFACE = (1 <<  0); // zend_compile.h
    public static final int ZEND_ACC_NO_DYNAMIC_PROPERTIES = (1 << 13); // zend_compile.h
    public static final int ZEND_ACC_PUBLIC = (1 <<  0); // zend_compile.h
    public static final int ZEND_ACC_PROTECTED = (1 <<  1); // zend_compile.h
    public static final int ZEND_ACC_PRIVATE = (1 <<  2); // zend_compile.h
    public static final int ZEND_ACC_PPP_MASK = (ZEND_ACC_PUBLIC | ZEND_ACC_PROTECTED | ZEND_ACC_PRIVATE); // zend_compile.h
    public static final int ZEND_ACC_PUBLIC_SET = (1 << 10); // zend_compile.h
    public static final int ZEND_ACC_PROTECTED_SET = (1 << 11); // zend_compile.h
    public static final int ZEND_ACC_PRIVATE_SET = (1 << 12); // zend_compile.h
    public static final int ZEND_ACC_PPP_SET_MASK = (ZEND_ACC_PUBLIC_SET | ZEND_ACC_PROTECTED_SET | ZEND_ACC_PRIVATE_SET); // zend_compile.h
    public static final int ZEND_ACC_READONLY = (1 <<  7); // zend_compile.h
    public static final int ZEND_ACC_READONLY_CLASS = (1 << 16); // zend_compile.h
    public static final int ZEND_ACC_RETURN_REFERENCE = (1 << 12); // zend_compile.h
    public static final int ZEND_ACC_STATIC = (1 <<  4); // zend_compile.h
    public static final int ZEND_ACC_TRAIT = (1 <<  1); // zend_compile.h
    public static final int ZEND_ACC_VIRTUAL = (1 <<  9); // zend_compile.h
    public static final int ZEND_ADD = 1; // zend_vm_opcodes.h
    public static final int ZEND_ALT_CASE_SYNTAX = (1 << 1); // zend_compile.h
    public static final int ZEND_ARRAY_SYNTAX_LIST = 1; // zend_compile.h
    public static final int ZEND_ARRAY_SYNTAX_LONG = 2; // zend_compile.h
    public static final int ZEND_ARRAY_SYNTAX_SHORT = 3; // zend_compile.h
    public static final int ZEND_BIND_REF = 1; // zend_compile.h
    public static final int ZEND_BOOL_NOT = 14; // zend_vm_opcodes.h
    public static final int ZEND_BOOL_XOR = 15; // zend_vm_opcodes.h
    public static final int ZEND_BW_AND = 10; // zend_vm_opcodes.h
    public static final int ZEND_BW_NOT = 13; // zend_vm_opcodes.h
    public static final int ZEND_BW_OR = 9; // zend_vm_opcodes.h
    public static final int ZEND_BW_XOR = 11; // zend_vm_opcodes.h
    public static final int ZEND_CONCAT = 8; // zend_vm_opcodes.h
    public static final int ZEND_CONST_EXPR_NEW_FETCH_TYPE_SHIFT = 2; // zend_compile.h
    public static final int ZEND_DIV = 4; // zend_vm_opcodes.h
    public static final int ZEND_ENCAPS_VAR_DOLLAR_CURLY = (1 << 0); // zend_compile.h
    public static final int ZEND_ENCAPS_VAR_DOLLAR_CURLY_VAR_VAR = (1 << 1); // zend_compile.h
    public static final int ZEND_EVAL = (1<<0); // zend_compile.h
    public static final int ZEND_INCLUDE = (1<<1); // zend_compile.h
    public static final int ZEND_INCLUDE_ONCE = (1<<2); // zend_compile.h
    public static final int ZEND_IS_EQUAL = 18; // zend_vm_opcodes.h
    public static final int ZEND_IS_IDENTICAL = 16; // zend_vm_opcodes.h
    public static final int ZEND_IS_NOT_EQUAL = 19; // zend_vm_opcodes.h
    public static final int ZEND_IS_NOT_IDENTICAL = 17; // zend_vm_opcodes.h
    public static final int ZEND_IS_SMALLER = 20; // zend_vm_opcodes.h
    public static final int ZEND_IS_SMALLER_OR_EQUAL = 21; // zend_vm_opcodes.h
    public static final int ZEND_MOD = 5; // zend_vm_opcodes.h
    public static final int ZEND_MUL = 3; // zend_vm_opcodes.h
    public static final int ZEND_NAME_FQ = 0; // zend_compile.h
    public static final int ZEND_NAME_NOT_FQ = 1; // zend_compile.h
    public static final int ZEND_NAME_RELATIVE = 2; // zend_compile.h
    public static final int ZEND_PARAM_REF = (1<<3); // zend_compile.h
    public static final int ZEND_PARAM_VARIADIC = (1<<4); // zend_compile.h
    public static final int ZEND_PARENTHESIZED_ARROW_FUNC = 1; // zend_compile.h
    public static final int ZEND_PARENTHESIZED_CONDITIONAL = 1; // zend_compile.h
    public static final int ZEND_PARENTHESIZED_STATIC_PROP = 1; // zend_compile.h
    public static final int ZEND_POW = 12; // zend_vm_opcodes.h
    public static final int ZEND_REQUIRE = (1<<3); // zend_compile.h
    public static final int ZEND_REQUIRE_ONCE = (1<<4); // zend_compile.h
    public static final int ZEND_SL = 6; // zend_vm_opcodes.h
    public static final int ZEND_SPACESHIP = 170; // zend_vm_opcodes.h
    public static final int ZEND_SR = 7; // zend_vm_opcodes.h
    public static final int ZEND_SUB = 2; // zend_vm_opcodes.h
    public static final int ZEND_SYMBOL_CLASS = (1<<0); // zend_compile.h
    public static final int ZEND_SYMBOL_CONST = (1<<2); // zend_compile.h
    public static final int ZEND_SYMBOL_FUNCTION = (1<<1); // zend_compile.h
    public static final int ZEND_TYPE_NULLABLE = (1<<8); // zend_compile.h
    public static final int _IS_BOOL = 18; // zend_types.h
    public static final int ZEND_AST_SPECIAL_SHIFT = 6; // zend_ast.h
    public static final int ZEND_AST_IS_LIST_SHIFT = 7; // zend_ast.h
    public static final int ZEND_AST_NUM_CHILDREN_SHIFT = 8; // zend_ast.h

    // enum _zend_ast_kind (zend_ast.h)
    public static final int ZEND_AST_ZVAL = 1 << ZEND_AST_SPECIAL_SHIFT;
    public static final int ZEND_AST_CONSTANT = ZEND_AST_ZVAL + 1;
    public static final int ZEND_AST_OP_ARRAY = ZEND_AST_CONSTANT + 1;
    public static final int ZEND_AST_ZNODE = ZEND_AST_OP_ARRAY + 1;
    public static final int ZEND_AST_FUNC_DECL = ZEND_AST_ZNODE + 1;
    public static final int ZEND_AST_CLOSURE = ZEND_AST_FUNC_DECL + 1;
    public static final int ZEND_AST_METHOD = ZEND_AST_CLOSURE + 1;
    public static final int ZEND_AST_CLASS = ZEND_AST_METHOD + 1;
    public static final int ZEND_AST_ARROW_FUNC = ZEND_AST_CLASS + 1;
    public static final int ZEND_AST_PROPERTY_HOOK = ZEND_AST_ARROW_FUNC + 1;
    public static final int ZEND_AST_ARG_LIST = 1 << ZEND_AST_IS_LIST_SHIFT;
    public static final int ZEND_AST_ARRAY = ZEND_AST_ARG_LIST + 1;
    public static final int ZEND_AST_ENCAPS_LIST = ZEND_AST_ARRAY + 1;
    public static final int ZEND_AST_EXPR_LIST = ZEND_AST_ENCAPS_LIST + 1;
    public static final int ZEND_AST_STMT_LIST = ZEND_AST_EXPR_LIST + 1;
    public static final int ZEND_AST_IF = ZEND_AST_STMT_LIST + 1;
    public static final int ZEND_AST_SWITCH_LIST = ZEND_AST_IF + 1;
    public static final int ZEND_AST_CATCH_LIST = ZEND_AST_SWITCH_LIST + 1;
    public static final int ZEND_AST_PARAM_LIST = ZEND_AST_CATCH_LIST + 1;
    public static final int ZEND_AST_CLOSURE_USES = ZEND_AST_PARAM_LIST + 1;
    public static final int ZEND_AST_PROP_DECL = ZEND_AST_CLOSURE_USES + 1;
    public static final int ZEND_AST_CONST_DECL = ZEND_AST_PROP_DECL + 1;
    public static final int ZEND_AST_CLASS_CONST_DECL = ZEND_AST_CONST_DECL + 1;
    public static final int ZEND_AST_NAME_LIST = ZEND_AST_CLASS_CONST_DECL + 1;
    public static final int ZEND_AST_TRAIT_ADAPTATIONS = ZEND_AST_NAME_LIST + 1;
    public static final int ZEND_AST_USE = ZEND_AST_TRAIT_ADAPTATIONS + 1;
    public static final int ZEND_AST_TYPE_UNION = ZEND_AST_USE + 1;
    public static final int ZEND_AST_TYPE_INTERSECTION = ZEND_AST_TYPE_UNION + 1;
    public static final int ZEND_AST_ATTRIBUTE_LIST = ZEND_AST_TYPE_INTERSECTION + 1;
    public static final int ZEND_AST_ATTRIBUTE_GROUP = ZEND_AST_ATTRIBUTE_LIST + 1;
    public static final int ZEND_AST_MATCH_ARM_LIST = ZEND_AST_ATTRIBUTE_GROUP + 1;
    public static final int ZEND_AST_MODIFIER_LIST = ZEND_AST_MATCH_ARM_LIST + 1;
    public static final int ZEND_AST_MAGIC_CONST = 0 << ZEND_AST_NUM_CHILDREN_SHIFT;
    public static final int ZEND_AST_TYPE = ZEND_AST_MAGIC_CONST + 1;
    public static final int ZEND_AST_CONSTANT_CLASS = ZEND_AST_TYPE + 1;
    public static final int ZEND_AST_CALLABLE_CONVERT = ZEND_AST_CONSTANT_CLASS + 1;
    public static final int ZEND_AST_VAR = 1 << ZEND_AST_NUM_CHILDREN_SHIFT;
    public static final int ZEND_AST_CONST = ZEND_AST_VAR + 1;
    public static final int ZEND_AST_UNPACK = ZEND_AST_CONST + 1;
    public static final int ZEND_AST_UNARY_PLUS = ZEND_AST_UNPACK + 1;
    public static final int ZEND_AST_UNARY_MINUS = ZEND_AST_UNARY_PLUS + 1;
    public static final int ZEND_AST_CAST = ZEND_AST_UNARY_MINUS + 1;
    public static final int ZEND_AST_CAST_VOID = ZEND_AST_CAST + 1;
    public static final int ZEND_AST_EMPTY = ZEND_AST_CAST_VOID + 1;
    public static final int ZEND_AST_ISSET = ZEND_AST_EMPTY + 1;
    public static final int ZEND_AST_SILENCE = ZEND_AST_ISSET + 1;
    public static final int ZEND_AST_SHELL_EXEC = ZEND_AST_SILENCE + 1;
    public static final int ZEND_AST_PRINT = ZEND_AST_SHELL_EXEC + 1;
    public static final int ZEND_AST_INCLUDE_OR_EVAL = ZEND_AST_PRINT + 1;
    public static final int ZEND_AST_UNARY_OP = ZEND_AST_INCLUDE_OR_EVAL + 1;
    public static final int ZEND_AST_PRE_INC = ZEND_AST_UNARY_OP + 1;
    public static final int ZEND_AST_PRE_DEC = ZEND_AST_PRE_INC + 1;
    public static final int ZEND_AST_POST_INC = ZEND_AST_PRE_DEC + 1;
    public static final int ZEND_AST_POST_DEC = ZEND_AST_POST_INC + 1;
    public static final int ZEND_AST_YIELD_FROM = ZEND_AST_POST_DEC + 1;
    public static final int ZEND_AST_CLASS_NAME = ZEND_AST_YIELD_FROM + 1;
    public static final int ZEND_AST_GLOBAL = ZEND_AST_CLASS_NAME + 1;
    public static final int ZEND_AST_UNSET = ZEND_AST_GLOBAL + 1;
    public static final int ZEND_AST_RETURN = ZEND_AST_UNSET + 1;
    public static final int ZEND_AST_LABEL = ZEND_AST_RETURN + 1;
    public static final int ZEND_AST_REF = ZEND_AST_LABEL + 1;
    public static final int ZEND_AST_HALT_COMPILER = ZEND_AST_REF + 1;
    public static final int ZEND_AST_ECHO = ZEND_AST_HALT_COMPILER + 1;
    public static final int ZEND_AST_THROW = ZEND_AST_ECHO + 1;
    public static final int ZEND_AST_GOTO = ZEND_AST_THROW + 1;
    public static final int ZEND_AST_BREAK = ZEND_AST_GOTO + 1;
    public static final int ZEND_AST_CONTINUE = ZEND_AST_BREAK + 1;
    public static final int ZEND_AST_PROPERTY_HOOK_SHORT_BODY = ZEND_AST_CONTINUE + 1;
    public static final int ZEND_AST_DIM = 2 << ZEND_AST_NUM_CHILDREN_SHIFT;
    public static final int ZEND_AST_PROP = ZEND_AST_DIM + 1;
    public static final int ZEND_AST_NULLSAFE_PROP = ZEND_AST_PROP + 1;
    public static final int ZEND_AST_STATIC_PROP = ZEND_AST_NULLSAFE_PROP + 1;
    public static final int ZEND_AST_CALL = ZEND_AST_STATIC_PROP + 1;
    public static final int ZEND_AST_CLASS_CONST = ZEND_AST_CALL + 1;
    public static final int ZEND_AST_ASSIGN = ZEND_AST_CLASS_CONST + 1;
    public static final int ZEND_AST_ASSIGN_REF = ZEND_AST_ASSIGN + 1;
    public static final int ZEND_AST_ASSIGN_OP = ZEND_AST_ASSIGN_REF + 1;
    public static final int ZEND_AST_BINARY_OP = ZEND_AST_ASSIGN_OP + 1;
    public static final int ZEND_AST_GREATER = ZEND_AST_BINARY_OP + 1;
    public static final int ZEND_AST_GREATER_EQUAL = ZEND_AST_GREATER + 1;
    public static final int ZEND_AST_AND = ZEND_AST_GREATER_EQUAL + 1;
    public static final int ZEND_AST_OR = ZEND_AST_AND + 1;
    public static final int ZEND_AST_ARRAY_ELEM = ZEND_AST_OR + 1;
    public static final int ZEND_AST_NEW = ZEND_AST_ARRAY_ELEM + 1;
    public static final int ZEND_AST_INSTANCEOF = ZEND_AST_NEW + 1;
    public static final int ZEND_AST_YIELD = ZEND_AST_INSTANCEOF + 1;
    public static final int ZEND_AST_COALESCE = ZEND_AST_YIELD + 1;
    public static final int ZEND_AST_ASSIGN_COALESCE = ZEND_AST_COALESCE + 1;
    public static final int ZEND_AST_STATIC = ZEND_AST_ASSIGN_COALESCE + 1;
    public static final int ZEND_AST_WHILE = ZEND_AST_STATIC + 1;
    public static final int ZEND_AST_DO_WHILE = ZEND_AST_WHILE + 1;
    public static final int ZEND_AST_IF_ELEM = ZEND_AST_DO_WHILE + 1;
    public static final int ZEND_AST_SWITCH = ZEND_AST_IF_ELEM + 1;
    public static final int ZEND_AST_SWITCH_CASE = ZEND_AST_SWITCH + 1;
    public static final int ZEND_AST_DECLARE = ZEND_AST_SWITCH_CASE + 1;
    public static final int ZEND_AST_USE_TRAIT = ZEND_AST_DECLARE + 1;
    public static final int ZEND_AST_TRAIT_PRECEDENCE = ZEND_AST_USE_TRAIT + 1;
    public static final int ZEND_AST_METHOD_REFERENCE = ZEND_AST_TRAIT_PRECEDENCE + 1;
    public static final int ZEND_AST_NAMESPACE = ZEND_AST_METHOD_REFERENCE + 1;
    public static final int ZEND_AST_USE_ELEM = ZEND_AST_NAMESPACE + 1;
    public static final int ZEND_AST_TRAIT_ALIAS = ZEND_AST_USE_ELEM + 1;
    public static final int ZEND_AST_GROUP_USE = ZEND_AST_TRAIT_ALIAS + 1;
    public static final int ZEND_AST_ATTRIBUTE = ZEND_AST_GROUP_USE + 1;
    public static final int ZEND_AST_MATCH = ZEND_AST_ATTRIBUTE + 1;
    public static final int ZEND_AST_MATCH_ARM = ZEND_AST_MATCH + 1;
    public static final int ZEND_AST_NAMED_ARG = ZEND_AST_MATCH_ARM + 1;
    public static final int ZEND_AST_PARENT_PROPERTY_HOOK_CALL = ZEND_AST_NAMED_ARG + 1;
    public static final int ZEND_AST_PIPE = ZEND_AST_PARENT_PROPERTY_HOOK_CALL + 1;
    public static final int ZEND_AST_METHOD_CALL = 3 << ZEND_AST_NUM_CHILDREN_SHIFT;
    public static final int ZEND_AST_NULLSAFE_METHOD_CALL = ZEND_AST_METHOD_CALL + 1;
    public static final int ZEND_AST_STATIC_CALL = ZEND_AST_NULLSAFE_METHOD_CALL + 1;
    public static final int ZEND_AST_CONDITIONAL = ZEND_AST_STATIC_CALL + 1;
    public static final int ZEND_AST_TRY = ZEND_AST_CONDITIONAL + 1;
    public static final int ZEND_AST_CATCH = ZEND_AST_TRY + 1;
    public static final int ZEND_AST_PROP_GROUP = ZEND_AST_CATCH + 1;
    public static final int ZEND_AST_CONST_ELEM = ZEND_AST_PROP_GROUP + 1;
    public static final int ZEND_AST_CLASS_CONST_GROUP = ZEND_AST_CONST_ELEM + 1;
    public static final int ZEND_AST_CONST_ENUM_INIT = ZEND_AST_CLASS_CONST_GROUP + 1;
    public static final int ZEND_AST_FOR = 4 << ZEND_AST_NUM_CHILDREN_SHIFT;
    public static final int ZEND_AST_FOREACH = ZEND_AST_FOR + 1;
    public static final int ZEND_AST_ENUM_CASE = ZEND_AST_FOREACH + 1;
    public static final int ZEND_AST_PROP_ELEM = ZEND_AST_ENUM_CASE + 1;
    public static final int ZEND_AST_PARAM = 6 << ZEND_AST_NUM_CHILDREN_SHIFT;

    // zend_modifier_target (zend_compile.h)
    public static final int ZEND_MODIFIER_TARGET_PROPERTY = 0;
    public static final int ZEND_MODIFIER_TARGET_METHOD = ZEND_MODIFIER_TARGET_PROPERTY + 1;
    public static final int ZEND_MODIFIER_TARGET_CONSTANT = ZEND_MODIFIER_TARGET_METHOD + 1;
    public static final int ZEND_MODIFIER_TARGET_CPP = ZEND_MODIFIER_TARGET_CONSTANT + 1;
    public static final int ZEND_MODIFIER_TARGET_PROPERTY_HOOK = ZEND_MODIFIER_TARGET_CPP + 1;

    // ZEND_KNOWN_STRINGS (zend_string.h): ids for ZSTR_KNOWN
    public static final int ZEND_STR_FILE = 0;
    public static final int ZEND_STR_LINE = 1;
    public static final int ZEND_STR_FUNCTION = 2;
    public static final int ZEND_STR_CLASS = 3;
    public static final int ZEND_STR_OBJECT = 4;
    public static final int ZEND_STR_TYPE = 5;
    public static final int ZEND_STR_OBJECT_OPERATOR = 6;
    public static final int ZEND_STR_PAAMAYIM_NEKUDOTAYIM = 7;
    public static final int ZEND_STR_ARGS = 8;
    public static final int ZEND_STR_UNKNOWN = 9;
    public static final int ZEND_STR_UNKNOWN_CAPITALIZED = 10;
    public static final int ZEND_STR_EXIT = 11;
    public static final int ZEND_STR_CLONE = 12;
    public static final int ZEND_STR_EVAL = 13;
    public static final int ZEND_STR_INCLUDE = 14;
    public static final int ZEND_STR_REQUIRE = 15;
    public static final int ZEND_STR_INCLUDE_ONCE = 16;
    public static final int ZEND_STR_REQUIRE_ONCE = 17;
    public static final int ZEND_STR_SCALAR = 18;
    public static final int ZEND_STR_ERROR_REPORTING = 19;
    public static final int ZEND_STR_STATIC = 20;
    public static final int ZEND_STR_THIS = 21;
    public static final int ZEND_STR_VALUE = 22;
    public static final int ZEND_STR_KEY = 23;
    public static final int ZEND_STR_MAGIC_INVOKE = 24;
    public static final int ZEND_STR_PREVIOUS = 25;
    public static final int ZEND_STR_CODE = 26;
    public static final int ZEND_STR_MESSAGE = 27;
    public static final int ZEND_STR_SEVERITY = 28;
    public static final int ZEND_STR_STRING = 29;
    public static final int ZEND_STR_TRACE = 30;
    public static final int ZEND_STR_SCHEME = 31;
    public static final int ZEND_STR_HOST = 32;
    public static final int ZEND_STR_PORT = 33;
    public static final int ZEND_STR_USER = 34;
    public static final int ZEND_STR_USERNAME = 35;
    public static final int ZEND_STR_PASS = 36;
    public static final int ZEND_STR_PASSWORD = 37;
    public static final int ZEND_STR_PATH = 38;
    public static final int ZEND_STR_QUERY = 39;
    public static final int ZEND_STR_FRAGMENT = 40;
    public static final int ZEND_STR_NULL = 41;
    public static final int ZEND_STR_BOOLEAN = 42;
    public static final int ZEND_STR_INTEGER = 43;
    public static final int ZEND_STR_DOUBLE = 44;
    public static final int ZEND_STR_ARRAY = 45;
    public static final int ZEND_STR_RESOURCE = 46;
    public static final int ZEND_STR_CLOSED_RESOURCE = 47;
    public static final int ZEND_STR_NAME = 48;
    public static final int ZEND_STR_ARGV = 49;
    public static final int ZEND_STR_ARGC = 50;
    public static final int ZEND_STR_ARRAY_CAPITALIZED = 51;
    public static final int ZEND_STR_BOOL = 52;
    public static final int ZEND_STR_INT = 53;
    public static final int ZEND_STR_FLOAT = 54;
    public static final int ZEND_STR_CALLABLE = 55;
    public static final int ZEND_STR_ITERABLE = 56;
    public static final int ZEND_STR_VOID = 57;
    public static final int ZEND_STR_NEVER = 58;
    public static final int ZEND_STR_FALSE = 59;
    public static final int ZEND_STR_TRUE = 60;
    public static final int ZEND_STR_NULL_LOWERCASE = 61;
    public static final int ZEND_STR_MIXED = 62;
    public static final int ZEND_STR_TRAVERSABLE = 63;
    public static final int ZEND_STR_SELF = 64;
    public static final int ZEND_STR_PARENT = 65;
    public static final int ZEND_STR_SLEEP = 66;
    public static final int ZEND_STR_WAKEUP = 67;
    public static final int ZEND_STR_CASES = 68;
    public static final int ZEND_STR_FROM = 69;
    public static final int ZEND_STR_TRYFROM = 70;
    public static final int ZEND_STR_TRYFROM_LOWERCASE = 71;
    public static final int ZEND_STR_AUTOGLOBAL_SERVER = 72;
    public static final int ZEND_STR_AUTOGLOBAL_ENV = 73;
    public static final int ZEND_STR_AUTOGLOBAL_REQUEST = 74;
    public static final int ZEND_STR_COUNT = 75;
    public static final int ZEND_STR_SENSITIVEPARAMETER = 76;
    public static final int ZEND_STR_CONST_EXPR_PLACEHOLDER = 77;
    public static final int ZEND_STR_DEPRECATED_CAPITALIZED = 78;
    public static final int ZEND_STR_SINCE = 79;
    public static final int ZEND_STR_GET = 80;
    public static final int ZEND_STR_SET = 81;
    public static final int ZEND_STR_8_DOT_0 = 82;
    public static final int ZEND_STR_8_DOT_1 = 83;
    public static final int ZEND_STR_8_DOT_2 = 84;
    public static final int ZEND_STR_8_DOT_3 = 85;
    public static final int ZEND_STR_8_DOT_4 = 86;
    public static final int ZEND_STR_8_DOT_5 = 87;

    /** The known strings by id. */
    public static final String[] ZEND_KNOWN_STRINGS = {
        "file",
        "line",
        "function",
        "class",
        "object",
        "type",
        "->",
        "::",
        "args",
        "unknown",
        "Unknown",
        "exit",
        "clone",
        "eval",
        "include",
        "require",
        "include_once",
        "require_once",
        "scalar",
        "error_reporting",
        "static",
        "this",
        "value",
        "key",
        "__invoke",
        "previous",
        "code",
        "message",
        "severity",
        "string",
        "trace",
        "scheme",
        "host",
        "port",
        "user",
        "username",
        "pass",
        "password",
        "path",
        "query",
        "fragment",
        "NULL",
        "boolean",
        "integer",
        "double",
        "array",
        "resource",
        "resource (closed)",
        "name",
        "argv",
        "argc",
        "Array",
        "bool",
        "int",
        "float",
        "callable",
        "iterable",
        "void",
        "never",
        "false",
        "true",
        "null",
        "mixed",
        "Traversable",
        "self",
        "parent",
        "__sleep",
        "__wakeup",
        "cases",
        "from",
        "tryFrom",
        "tryfrom",
        "_SERVER",
        "_ENV",
        "_REQUEST",
        "count",
        "SensitiveParameter",
        "[constant expression]",
        "Deprecated",
        "since",
        "get",
        "set",
        "8.0",
        "8.1",
        "8.2",
        "8.3",
        "8.4",
        "8.5",
    };
}
