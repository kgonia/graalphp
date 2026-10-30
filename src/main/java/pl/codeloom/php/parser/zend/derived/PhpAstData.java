/*
 * Derived from php-ast 1.1.3's ast_data.c and php_ast.h by tools/parser/derive_php_ast.py: the names of
 * the tree's kinds and children as php-ast gives them for AST version 120 on PHP 8.5. DO NOT EDIT;
 * regenerate with tools/parser/generate.sh. php-ast is under the BSD 3-Clause License (its LICENSE).
 */
package pl.codeloom.php.parser.zend.derived;

import static pl.codeloom.php.parser.zend.derived.ZendConstants.*;

/** php-ast's names: of kinds and of the named children of each kind. */
public final class PhpAstData {
    private PhpAstData() {
    }

    // php-ast's own kinds and those it keeps (php_ast.h)
    public static final int AST_NAME = 2048;
    public static final int AST_CLOSURE_VAR = 2049;
    public static final int AST_NULLABLE_TYPE = 2050;
    public static final int ZEND_AST_EXIT = 0x1fd;
    public static final int ZEND_AST_CLONE = 0x1fc;
    public static final int ZEND_AST_LIST = ((1 << (ZEND_AST_IS_LIST_SHIFT + 1)) - 1);

    /** ast_kind_to_name: the kind's name, as php-ast exports it. */
    public static String kindName(int kind) {
        return switch (kind) {
            case ZEND_AST_ARG_LIST -> "AST_ARG_LIST";
            case ZEND_AST_LIST -> "AST_LIST";
            case ZEND_AST_ARRAY -> "AST_ARRAY";
            case ZEND_AST_ENCAPS_LIST -> "AST_ENCAPS_LIST";
            case ZEND_AST_EXPR_LIST -> "AST_EXPR_LIST";
            case ZEND_AST_STMT_LIST -> "AST_STMT_LIST";
            case ZEND_AST_IF -> "AST_IF";
            case ZEND_AST_SWITCH_LIST -> "AST_SWITCH_LIST";
            case ZEND_AST_CATCH_LIST -> "AST_CATCH_LIST";
            case ZEND_AST_PARAM_LIST -> "AST_PARAM_LIST";
            case ZEND_AST_CLOSURE_USES -> "AST_CLOSURE_USES";
            case ZEND_AST_PROP_DECL -> "AST_PROP_DECL";
            case ZEND_AST_CONST_DECL -> "AST_CONST_DECL";
            case ZEND_AST_CLASS_CONST_DECL -> "AST_CLASS_CONST_DECL";
            case ZEND_AST_NAME_LIST -> "AST_NAME_LIST";
            case ZEND_AST_TRAIT_ADAPTATIONS -> "AST_TRAIT_ADAPTATIONS";
            case ZEND_AST_USE -> "AST_USE";
            case ZEND_AST_TYPE_UNION -> "AST_TYPE_UNION";
            case ZEND_AST_TYPE_INTERSECTION -> "AST_TYPE_INTERSECTION";
            case ZEND_AST_ATTRIBUTE_LIST -> "AST_ATTRIBUTE_LIST";
            case ZEND_AST_ATTRIBUTE_GROUP -> "AST_ATTRIBUTE_GROUP";
            case ZEND_AST_MATCH_ARM_LIST -> "AST_MATCH_ARM_LIST";
            case AST_NAME -> "AST_NAME";
            case AST_CLOSURE_VAR -> "AST_CLOSURE_VAR";
            case AST_NULLABLE_TYPE -> "AST_NULLABLE_TYPE";
            case ZEND_AST_FUNC_DECL -> "AST_FUNC_DECL";
            case ZEND_AST_CLOSURE -> "AST_CLOSURE";
            case ZEND_AST_METHOD -> "AST_METHOD";
            case ZEND_AST_ARROW_FUNC -> "AST_ARROW_FUNC";
            case ZEND_AST_CLASS -> "AST_CLASS";
            case ZEND_AST_PROPERTY_HOOK -> "AST_PROPERTY_HOOK";
            case ZEND_AST_MAGIC_CONST -> "AST_MAGIC_CONST";
            case ZEND_AST_TYPE -> "AST_TYPE";
            case ZEND_AST_CALLABLE_CONVERT -> "AST_CALLABLE_CONVERT";
            case ZEND_AST_VAR -> "AST_VAR";
            case ZEND_AST_CONST -> "AST_CONST";
            case ZEND_AST_UNPACK -> "AST_UNPACK";
            case ZEND_AST_CAST -> "AST_CAST";
            case ZEND_AST_EMPTY -> "AST_EMPTY";
            case ZEND_AST_ISSET -> "AST_ISSET";
            case ZEND_AST_SHELL_EXEC -> "AST_SHELL_EXEC";
            case ZEND_AST_CLONE -> "AST_CLONE";
            case ZEND_AST_EXIT -> "AST_EXIT";
            case ZEND_AST_PRINT -> "AST_PRINT";
            case ZEND_AST_INCLUDE_OR_EVAL -> "AST_INCLUDE_OR_EVAL";
            case ZEND_AST_UNARY_OP -> "AST_UNARY_OP";
            case ZEND_AST_PRE_INC -> "AST_PRE_INC";
            case ZEND_AST_PRE_DEC -> "AST_PRE_DEC";
            case ZEND_AST_POST_INC -> "AST_POST_INC";
            case ZEND_AST_POST_DEC -> "AST_POST_DEC";
            case ZEND_AST_YIELD_FROM -> "AST_YIELD_FROM";
            case ZEND_AST_GLOBAL -> "AST_GLOBAL";
            case ZEND_AST_UNSET -> "AST_UNSET";
            case ZEND_AST_RETURN -> "AST_RETURN";
            case ZEND_AST_LABEL -> "AST_LABEL";
            case ZEND_AST_REF -> "AST_REF";
            case ZEND_AST_HALT_COMPILER -> "AST_HALT_COMPILER";
            case ZEND_AST_ECHO -> "AST_ECHO";
            case ZEND_AST_THROW -> "AST_THROW";
            case ZEND_AST_GOTO -> "AST_GOTO";
            case ZEND_AST_BREAK -> "AST_BREAK";
            case ZEND_AST_CONTINUE -> "AST_CONTINUE";
            case ZEND_AST_CLASS_NAME -> "AST_CLASS_NAME";
            case ZEND_AST_PROPERTY_HOOK_SHORT_BODY -> "AST_PROPERTY_HOOK_SHORT_BODY";
            case ZEND_AST_CLASS_CONST_GROUP -> "AST_CLASS_CONST_GROUP";
            case ZEND_AST_DIM -> "AST_DIM";
            case ZEND_AST_PROP -> "AST_PROP";
            case ZEND_AST_NULLSAFE_PROP -> "AST_NULLSAFE_PROP";
            case ZEND_AST_STATIC_PROP -> "AST_STATIC_PROP";
            case ZEND_AST_CALL -> "AST_CALL";
            case ZEND_AST_CLASS_CONST -> "AST_CLASS_CONST";
            case ZEND_AST_ASSIGN -> "AST_ASSIGN";
            case ZEND_AST_ASSIGN_REF -> "AST_ASSIGN_REF";
            case ZEND_AST_ASSIGN_OP -> "AST_ASSIGN_OP";
            case ZEND_AST_BINARY_OP -> "AST_BINARY_OP";
            case ZEND_AST_ARRAY_ELEM -> "AST_ARRAY_ELEM";
            case ZEND_AST_NEW -> "AST_NEW";
            case ZEND_AST_INSTANCEOF -> "AST_INSTANCEOF";
            case ZEND_AST_YIELD -> "AST_YIELD";
            case ZEND_AST_STATIC -> "AST_STATIC";
            case ZEND_AST_WHILE -> "AST_WHILE";
            case ZEND_AST_DO_WHILE -> "AST_DO_WHILE";
            case ZEND_AST_IF_ELEM -> "AST_IF_ELEM";
            case ZEND_AST_SWITCH -> "AST_SWITCH";
            case ZEND_AST_SWITCH_CASE -> "AST_SWITCH_CASE";
            case ZEND_AST_DECLARE -> "AST_DECLARE";
            case ZEND_AST_PROP_ELEM -> "AST_PROP_ELEM";
            case ZEND_AST_PROP_GROUP -> "AST_PROP_GROUP";
            case ZEND_AST_CONST_ELEM -> "AST_CONST_ELEM";
            case ZEND_AST_USE_TRAIT -> "AST_USE_TRAIT";
            case ZEND_AST_TRAIT_PRECEDENCE -> "AST_TRAIT_PRECEDENCE";
            case ZEND_AST_METHOD_REFERENCE -> "AST_METHOD_REFERENCE";
            case ZEND_AST_NAMESPACE -> "AST_NAMESPACE";
            case ZEND_AST_USE_ELEM -> "AST_USE_ELEM";
            case ZEND_AST_TRAIT_ALIAS -> "AST_TRAIT_ALIAS";
            case ZEND_AST_GROUP_USE -> "AST_GROUP_USE";
            case ZEND_AST_ATTRIBUTE -> "AST_ATTRIBUTE";
            case ZEND_AST_MATCH -> "AST_MATCH";
            case ZEND_AST_MATCH_ARM -> "AST_MATCH_ARM";
            case ZEND_AST_NAMED_ARG -> "AST_NAMED_ARG";
            case ZEND_AST_METHOD_CALL -> "AST_METHOD_CALL";
            case ZEND_AST_NULLSAFE_METHOD_CALL -> "AST_NULLSAFE_METHOD_CALL";
            case ZEND_AST_STATIC_CALL -> "AST_STATIC_CALL";
            case ZEND_AST_CONDITIONAL -> "AST_CONDITIONAL";
            case ZEND_AST_TRY -> "AST_TRY";
            case ZEND_AST_CATCH -> "AST_CATCH";
            case ZEND_AST_FOR -> "AST_FOR";
            case ZEND_AST_FOREACH -> "AST_FOREACH";
            case ZEND_AST_ENUM_CASE -> "AST_ENUM_CASE";
            case ZEND_AST_PARAM -> "AST_PARAM";
            default -> null;
        };
    }

    /** ast_kind_child_name: the name of a node's child by position, or null (lists, unnamed). */
    public static String childName(int kind, int child) {
        switch (kind) {
            case AST_NAME, AST_CLOSURE_VAR, ZEND_AST_VAR, ZEND_AST_CONST, ZEND_AST_LABEL:
                return switch (child) {
                    case 0 -> "name";
                    default -> null;
                };
            case AST_NULLABLE_TYPE:
                return switch (child) {
                    case 0 -> "type";
                    default -> null;
                };
            case ZEND_AST_FUNC_DECL, ZEND_AST_CLOSURE, ZEND_AST_METHOD, ZEND_AST_ARROW_FUNC, ZEND_AST_PROPERTY_HOOK:
                return switch (child) {
                    case 0 -> "params";
                    case 1 -> "uses";
                    case 2 -> "stmts";
                    case 3 -> "returnType";
                    case 4 -> "attributes";
                    default -> null;
                };
            case ZEND_AST_CLASS:
                return switch (child) {
                    case 0 -> "extends";
                    case 1 -> "implements";
                    case 2 -> "stmts";
                    case 3 -> "attributes";
                    case 4 -> "type";
                    default -> null;
                };
            case ZEND_AST_MAGIC_CONST, ZEND_AST_TYPE, ZEND_AST_CALLABLE_CONVERT:
                return null;
            case ZEND_AST_UNPACK, ZEND_AST_CAST, ZEND_AST_EMPTY, ZEND_AST_SHELL_EXEC, ZEND_AST_CLONE, ZEND_AST_EXIT, ZEND_AST_PRINT, ZEND_AST_INCLUDE_OR_EVAL, ZEND_AST_UNARY_OP, ZEND_AST_YIELD_FROM, ZEND_AST_RETURN, ZEND_AST_ECHO, ZEND_AST_THROW, ZEND_AST_PROPERTY_HOOK_SHORT_BODY:
                return switch (child) {
                    case 0 -> "expr";
                    default -> null;
                };
            case ZEND_AST_ISSET, ZEND_AST_PRE_INC, ZEND_AST_PRE_DEC, ZEND_AST_POST_INC, ZEND_AST_POST_DEC, ZEND_AST_GLOBAL, ZEND_AST_UNSET, ZEND_AST_REF:
                return switch (child) {
                    case 0 -> "var";
                    default -> null;
                };
            case ZEND_AST_HALT_COMPILER:
                return switch (child) {
                    case 0 -> "offset";
                    default -> null;
                };
            case ZEND_AST_GOTO:
                return switch (child) {
                    case 0 -> "label";
                    default -> null;
                };
            case ZEND_AST_BREAK, ZEND_AST_CONTINUE:
                return switch (child) {
                    case 0 -> "depth";
                    default -> null;
                };
            case ZEND_AST_CLASS_NAME:
                return switch (child) {
                    case 0 -> "class";
                    default -> null;
                };
            case ZEND_AST_CLASS_CONST_GROUP:
                return switch (child) {
                    case 0 -> "const";
                    case 1 -> "attributes";
                    case 2 -> "type";
                    default -> null;
                };
            case ZEND_AST_DIM:
                return switch (child) {
                    case 0 -> "expr";
                    case 1 -> "dim";
                    default -> null;
                };
            case ZEND_AST_PROP, ZEND_AST_NULLSAFE_PROP:
                return switch (child) {
                    case 0 -> "expr";
                    case 1 -> "prop";
                    default -> null;
                };
            case ZEND_AST_STATIC_PROP:
                return switch (child) {
                    case 0 -> "class";
                    case 1 -> "prop";
                    default -> null;
                };
            case ZEND_AST_CALL:
                return switch (child) {
                    case 0 -> "expr";
                    case 1 -> "args";
                    default -> null;
                };
            case ZEND_AST_CLASS_CONST:
                return switch (child) {
                    case 0 -> "class";
                    case 1 -> "const";
                    default -> null;
                };
            case ZEND_AST_ASSIGN, ZEND_AST_ASSIGN_REF, ZEND_AST_ASSIGN_OP:
                return switch (child) {
                    case 0 -> "var";
                    case 1 -> "expr";
                    default -> null;
                };
            case ZEND_AST_BINARY_OP:
                return switch (child) {
                    case 0 -> "left";
                    case 1 -> "right";
                    default -> null;
                };
            case ZEND_AST_ARRAY_ELEM, ZEND_AST_YIELD:
                return switch (child) {
                    case 0 -> "value";
                    case 1 -> "key";
                    default -> null;
                };
            case ZEND_AST_NEW, ZEND_AST_ATTRIBUTE:
                return switch (child) {
                    case 0 -> "class";
                    case 1 -> "args";
                    default -> null;
                };
            case ZEND_AST_INSTANCEOF:
                return switch (child) {
                    case 0 -> "expr";
                    case 1 -> "class";
                    default -> null;
                };
            case ZEND_AST_STATIC:
                return switch (child) {
                    case 0 -> "var";
                    case 1 -> "default";
                    default -> null;
                };
            case ZEND_AST_WHILE, ZEND_AST_IF_ELEM, ZEND_AST_SWITCH, ZEND_AST_SWITCH_CASE, ZEND_AST_MATCH:
                return switch (child) {
                    case 0 -> "cond";
                    case 1 -> "stmts";
                    default -> null;
                };
            case ZEND_AST_DO_WHILE:
                return switch (child) {
                    case 0 -> "stmts";
                    case 1 -> "cond";
                    default -> null;
                };
            case ZEND_AST_DECLARE:
                return switch (child) {
                    case 0 -> "declares";
                    case 1 -> "stmts";
                    default -> null;
                };
            case ZEND_AST_PROP_ELEM:
                return switch (child) {
                    case 0 -> "name";
                    case 1 -> "default";
                    case 2 -> "docComment";
                    case 3 -> "hooks";
                    default -> null;
                };
            case ZEND_AST_PROP_GROUP:
                return switch (child) {
                    case 0 -> "type";
                    case 1 -> "props";
                    case 2 -> "attributes";
                    default -> null;
                };
            case ZEND_AST_CONST_ELEM:
                return switch (child) {
                    case 0 -> "name";
                    case 1 -> "value";
                    case 2 -> "docComment";
                    default -> null;
                };
            case ZEND_AST_USE_TRAIT:
                return switch (child) {
                    case 0 -> "traits";
                    case 1 -> "adaptations";
                    default -> null;
                };
            case ZEND_AST_TRAIT_PRECEDENCE:
                return switch (child) {
                    case 0 -> "method";
                    case 1 -> "insteadof";
                    default -> null;
                };
            case ZEND_AST_METHOD_REFERENCE:
                return switch (child) {
                    case 0 -> "class";
                    case 1 -> "method";
                    default -> null;
                };
            case ZEND_AST_NAMESPACE:
                return switch (child) {
                    case 0 -> "name";
                    case 1 -> "stmts";
                    default -> null;
                };
            case ZEND_AST_USE_ELEM:
                return switch (child) {
                    case 0 -> "name";
                    case 1 -> "alias";
                    default -> null;
                };
            case ZEND_AST_TRAIT_ALIAS:
                return switch (child) {
                    case 0 -> "method";
                    case 1 -> "alias";
                    default -> null;
                };
            case ZEND_AST_GROUP_USE:
                return switch (child) {
                    case 0 -> "prefix";
                    case 1 -> "uses";
                    default -> null;
                };
            case ZEND_AST_MATCH_ARM:
                return switch (child) {
                    case 0 -> "cond";
                    case 1 -> "expr";
                    default -> null;
                };
            case ZEND_AST_NAMED_ARG:
                return switch (child) {
                    case 0 -> "name";
                    case 1 -> "expr";
                    default -> null;
                };
            case ZEND_AST_METHOD_CALL, ZEND_AST_NULLSAFE_METHOD_CALL:
                return switch (child) {
                    case 0 -> "expr";
                    case 1 -> "method";
                    case 2 -> "args";
                    default -> null;
                };
            case ZEND_AST_STATIC_CALL:
                return switch (child) {
                    case 0 -> "class";
                    case 1 -> "method";
                    case 2 -> "args";
                    default -> null;
                };
            case ZEND_AST_CONDITIONAL:
                return switch (child) {
                    case 0 -> "cond";
                    case 1 -> "true";
                    case 2 -> "false";
                    default -> null;
                };
            case ZEND_AST_TRY:
                return switch (child) {
                    case 0 -> "try";
                    case 1 -> "catches";
                    case 2 -> "finally";
                    default -> null;
                };
            case ZEND_AST_CATCH:
                return switch (child) {
                    case 0 -> "class";
                    case 1 -> "var";
                    case 2 -> "stmts";
                    default -> null;
                };
            case ZEND_AST_FOR:
                return switch (child) {
                    case 0 -> "init";
                    case 1 -> "cond";
                    case 2 -> "loop";
                    case 3 -> "stmts";
                    default -> null;
                };
            case ZEND_AST_FOREACH:
                return switch (child) {
                    case 0 -> "expr";
                    case 1 -> "value";
                    case 2 -> "key";
                    case 3 -> "stmts";
                    default -> null;
                };
            case ZEND_AST_ENUM_CASE:
                return switch (child) {
                    case 0 -> "name";
                    case 1 -> "expr";
                    case 2 -> "docComment";
                    case 3 -> "attributes";
                    default -> null;
                };
            case ZEND_AST_PARAM:
                return switch (child) {
                    case 0 -> "type";
                    case 1 -> "name";
                    case 2 -> "default";
                    case 3 -> "attributes";
                    case 4 -> "docComment";
                    case 5 -> "hooks";
                    default -> null;
                };
            default:
                return null;
        }
    }
}
