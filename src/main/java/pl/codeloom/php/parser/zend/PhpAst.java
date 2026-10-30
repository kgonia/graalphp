/*
 * Hand-written: a port of php-ast 1.1.3's ast.c (ast_to_zval and the functions it calls) for AST version 120 on
 * PHP 8.5, with tools/ast-export.php's JSON conventions for leaf values. php-ast is under the BSD 3-Clause License
 * (its LICENSE).
 */
package pl.codeloom.php.parser.zend;

import pl.codeloom.php.parser.Ast;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static pl.codeloom.php.parser.zend.derived.PhpAstData.*;
import static pl.codeloom.php.parser.zend.derived.ZendConstants.*;

/**
 * php-src's AST (ZendAst) in the shape php-ast gives it, version 120, as {@link Ast}: what tools/ast-export.php
 * exports and AstReader reads. Values become what the export's JSON holds: a string is a String when it is valid
 * UTF-8 and its bytes otherwise, an integer a Long, a float a Double.
 */
public final class PhpAst {
    /** php-ast's flags for kinds it folds into BINARY_OP, UNARY_OP and ASSIGN_OP (ast.c). */
    private static final int AST_BINARY_IS_GREATER = 256;
    private static final int AST_BINARY_IS_GREATER_OR_EQUAL = 257;
    private static final int AST_BINARY_BOOL_OR = 258;
    private static final int AST_BINARY_BOOL_AND = 259;
    private static final int AST_BINARY_COALESCE = 260;
    private static final int AST_BINARY_PIPE = 261;
    private static final int AST_SILENCE = 260;
    private static final int AST_PLUS = 261;
    private static final int AST_MINUS = 262;

    /** ast_state_info_t's declIdCounter: decls are numbered in the order their conversion ends. */
    private long declIdCounter;
    private final FunctionBodies bodies;

    /** What a converter does with a function's body: convert it, or leave it to be parsed again when needed. */
    public interface FunctionBodies {
        /**
         * The body (its "stmts") of the function decl in its tree's place: null to convert it as php-ast does, or
         * a value standing for it (an unread body) when it is left unconverted, which then counts declsInBody
         * decl ids from firstDeclId on its own.
         */
        Object leave(ZendAstDecl decl, ZendAst stmts, long firstDeclId, long declsInBody);
    }

    private PhpAst(long firstDeclId, FunctionBodies bodies) {
        this.declIdCounter = firstDeclId;
        this.bodies = bodies;
    }

    /** The tree php-ast gives for ast, its decls numbered from 0, every body converted. */
    public static Object convert(ZendAst ast) {
        return new PhpAst(0, null).ast_to_zval(ast);
    }

    /** As {@link #convert(ZendAst)}, with decl ids from firstDeclId and bodies left to bodies' choice. */
    public static Object convert(ZendAst ast, long firstDeclId, FunctionBodies bodies) {
        return new PhpAst(firstDeclId, bodies).ast_to_zval(ast);
    }

    /** The value a function body's stmts list converts to, as its decl's "stmts" child (arrow functions wrapped). */
    public static Object convertBody(int declKind, ZendAst stmts, long firstDeclId, FunctionBodies bodies) {
        PhpAst converter = new PhpAst(firstDeclId, bodies);
        Object value = converter.ast_to_zval(stmts);
        if (declKind == ZEND_AST_ARROW_FUNC) {
            value = converter.virtual(ZEND_AST_RETURN, 0, zend_ast_get_lineno(stmts), value);
        }
        return value;
    }

    private static int zend_ast_get_lineno(ZendAst ast) {
        return ast.lineno;
    }

    private static boolean zend_ast_is_list(ZendAst ast) {
        return ((ast.kind >> ZEND_AST_IS_LIST_SHIFT) & 1) != 0;
    }

    private static boolean ast_kind_is_decl(int kind) {
        return kind == ZEND_AST_FUNC_DECL || kind == ZEND_AST_CLOSURE || kind == ZEND_AST_ARROW_FUNC
                || kind == ZEND_AST_PROPERTY_HOOK || kind == ZEND_AST_METHOD || kind == ZEND_AST_CLASS;
    }

    private static boolean ast_is_name(ZendAst ast, ZendAst parent, int i) {
        if (!(ast instanceof ZendAstZval zv) || !(zv.val instanceof byte[])) {
            return false;
        }
        int kind = parent.kind;
        if (kind == ZEND_AST_NAME_LIST || kind == ZEND_AST_TYPE_INTERSECTION || kind == ZEND_AST_TYPE_UNION) {
            return true;
        }
        return switch (i) {
            case 0 -> kind == ZEND_AST_CATCH || kind == ZEND_AST_CLASS || kind == ZEND_AST_PARAM
                    || kind == ZEND_AST_METHOD_REFERENCE || kind == ZEND_AST_CALL || kind == ZEND_AST_CONST
                    || kind == ZEND_AST_NEW || kind == ZEND_AST_STATIC_CALL || kind == ZEND_AST_CLASS_CONST
                    || kind == ZEND_AST_STATIC_PROP || kind == ZEND_AST_PROP_GROUP || kind == ZEND_AST_CLASS_NAME
                    || kind == ZEND_AST_ATTRIBUTE;
            case 2 -> kind == ZEND_AST_CLASS_CONST_GROUP;
            case 1 -> kind == ZEND_AST_INSTANCEOF;
            case 3 -> kind == ZEND_AST_FUNC_DECL || kind == ZEND_AST_CLOSURE || kind == ZEND_AST_ARROW_FUNC
                    || kind == ZEND_AST_METHOD;
            case 4 -> kind == ZEND_AST_CLASS;
            default -> false;
        };
    }

    /** Assumes ast_is_name. */
    private static boolean ast_is_type(ZendAst parent, int i) {
        int kind = parent.kind;
        if (kind == ZEND_AST_TYPE_INTERSECTION || kind == ZEND_AST_TYPE_UNION) {
            return true;
        }
        return switch (i) {
            case 0 -> kind == ZEND_AST_PARAM || kind == ZEND_AST_PROP_GROUP;
            case 2 -> kind == ZEND_AST_CLASS_CONST_GROUP;
            case 3 -> kind == ZEND_AST_CLOSURE || kind == ZEND_AST_FUNC_DECL || kind == ZEND_AST_ARROW_FUNC
                    || kind == ZEND_AST_METHOD;
            case 4 -> kind == ZEND_AST_CLASS;
            default -> false;
        };
    }

    private static boolean ast_is_var_name(ZendAst ast, ZendAst parent, int i) {
        return (parent.kind == ZEND_AST_STATIC && i == 0) || (parent.kind == ZEND_AST_CATCH && i == 1 && ast != null);
    }

    /** Whether this node may need statement list normalization. */
    private static boolean ast_should_normalize_list(ZendAst ast, ZendAst parent, int i) {
        if (ast != null && ast.kind == ZEND_AST_STMT_LIST) {
            return false;
        }
        int kind = parent.kind;
        return switch (i) {
            case 0 -> kind == ZEND_AST_DO_WHILE;
            // declare(); and declare() {} are not the same
            case 1 -> kind == ZEND_AST_DECLARE ? ast != null : kind == ZEND_AST_IF_ELEM || kind == ZEND_AST_WHILE;
            case 2 -> kind == ZEND_AST_CATCH;
            case 3 -> kind == ZEND_AST_FOR || kind == ZEND_AST_FOREACH;
            default -> false;
        };
    }

    /** lookup_builtin_type (adopted from zend_compile.c by php-ast): a type name's IS_* code, or 0. */
    private static int lookup_builtin_type(byte[] name) {
        String lower = ascii(name).toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "int" -> IS_LONG;
            case "float" -> IS_DOUBLE;
            case "string" -> IS_STRING;
            case "bool" -> _IS_BOOL;
            case "void" -> IS_VOID;
            case "iterable" -> IS_ITERABLE;
            case "object" -> IS_OBJECT;
            case "null" -> IS_NULL;
            case "false" -> IS_FALSE;
            case "mixed" -> IS_MIXED;
            case "never" -> IS_NEVER;
            case "true" -> IS_TRUE;
            default -> 0;
        };
    }

    /** The bytes as chars one to one (only compared with ASCII words). */
    private static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /** The children ast_get_children gives: a decl's five, a list's, or the kind's number. */
    private static int childCount(ZendAst ast) {
        if (ast_kind_is_decl(ast.kind)) {
            return 5;
        }
        if (zend_ast_is_list(ast)) {
            return ((ZendAstList) ast).children;
        }
        return ast.kind >> ZEND_AST_NUM_CHILDREN_SHIFT;
    }

    /** A node's children while they are being filled: named when any child has a name, else positional. */
    private static final class Children {
        List<Object> list;
        Map<String, Object> named;

        void add(String name, Object value) {
            if (name != null) {
                if (named == null) {
                    named = new LinkedHashMap<>();
                }
                named.put(name, value);
            } else {
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.add(value);
            }
        }

        Ast node(String kind, int flags, int line, int endLine) {
            // tools/ast-export.php writes a node's children as an object when they have names, else as a list
            return named != null ? new Ast(kind, flags, line, endLine, null, named)
                    : new Ast(kind, flags, line, endLine, list != null ? list : new ArrayList<>(), null);
        }
    }

    /** ast_create_virtual_node_ex: a node php-ast makes that php-src's tree doesn't have. */
    private Ast virtual(int kind, int attr, int lineno, Object... children) {
        Children ht = new Children();
        for (int i = 0; i < children.length; i++) {
            ht.add(childName(kind, i), children[i]);
        }
        return ht.node(kindName(kind), attr, lineno, 0);
    }

    /** ast_create_virtual_node: one child, converted, whose line the node takes. */
    private Ast virtual(int kind, int attr, ZendAst child) {
        return virtual(kind, attr, zend_ast_get_lineno(child), ast_to_zval(child));
    }

    private Object ast_name_to_zval(ZendAst child, ZendAst ast, int i) {
        ZendAstZval name = (ZendAstZval) child;
        boolean is_nullable = false;
        int attr = child.attr;
        if ((attr & ZEND_TYPE_NULLABLE) != 0) {
            is_nullable = true;
            attr &= ~ZEND_TYPE_NULLABLE;
        }
        byte[] str = (byte[]) name.val;
        if (attr == ZEND_NAME_FQ && str.length > 0 && str[0] == '\\') {
            // ensure there is no leading \ for fully-qualified names, as in ('\bar')()
            str = java.util.Arrays.copyOfRange(str, 1, str.length);
        }
        int type;
        Object result;
        if (attr == ZEND_NAME_NOT_FQ && ast_is_type(ast, i) && (type = lookup_builtin_type(str)) != 0) {
            // convert "int" etc. type hints to TYPE nodes
            result = virtual(ZEND_AST_TYPE, type, zend_ast_get_lineno(child));
        } else {
            result = virtual(AST_NAME, attr, zend_ast_get_lineno(child), string(str));
        }
        if (is_nullable) {
            // an explicit AST_NULLABLE_TYPE node
            result = virtual(AST_NULLABLE_TYPE, 0, zend_ast_get_lineno(child), result);
        }
        return result;
    }

    private void ast_fill_children_ht(Children ht, ZendAst ast) {
        boolean is_list = zend_ast_is_list(ast);
        int count = childCount(ast);
        int ast_kind = ast.kind;
        for (int i = 0; i < count; ++i) {
            ZendAst child = ast.child[i];
            String child_name = !is_list ? childName(ast_kind, i) : null;
            Object child_zv;

            if (ast_kind == ZEND_AST_STMT_LIST) {
                if (child != null && child.kind == ZEND_AST_STMT_LIST) {
                    ast_fill_children_ht(ht, child);
                    continue;
                }
                if (child == null) {
                    continue;
                }
            }
            if (ast_kind == ZEND_AST_PROPERTY_HOOK && (i == 1 || i == 3)) {
                // property hooks don't have uses/returnType but they do have params/stmts/attributes
                continue;
            }

            if (ast_is_name(child, ast, i)) {
                child_zv = ast_name_to_zval(child, ast, i);
            } else if (child != null && child.kind == ZEND_AST_TYPE && (child.attr & ZEND_TYPE_NULLABLE) != 0) {
                child.attr &= ~ZEND_TYPE_NULLABLE;
                child_zv = virtual(AST_NULLABLE_TYPE, 0, child);
            } else if (ast_kind == ZEND_AST_CLOSURE_USES) {
                child_zv = virtual(AST_CLOSURE_VAR, child.attr, child);
            } else if (ast_is_var_name(child, ast, i)) {
                child_zv = virtual(ZEND_AST_VAR, 0, child);
            } else if (ast_should_normalize_list(child, ast, i)) {
                if (child != null) {
                    child_zv = virtual(ZEND_AST_STMT_LIST, 0, zend_ast_get_lineno(child), ast_to_zval(child));
                } else {
                    child_zv = virtual(ZEND_AST_STMT_LIST, 0, zend_ast_get_lineno(ast));
                }
            } else if (i == 1 && (ast_kind == ZEND_AST_FUNC_DECL || ast_kind == ZEND_AST_METHOD)) {
                // skip "uses", only relevant for closures
                continue;
            } else if (i == 1 && ast_kind == ZEND_AST_ARROW_FUNC) {
                // skip "uses" since it is always empty
                continue;
            } else if (i == 2 && child != null && bodies != null && isFunction(ast_kind)
                    && (child_zv = leave((ZendAstDecl) ast, child)) != null) {
                // a body left unconverted, as unread (its value stands for what follows below)
                ht.add(child_name, child_zv);
                continue;
            } else {
                child_zv = ast_to_zval(child);
            }
            if (child_name != null) {
                if (ast_kind == ZEND_AST_ARROW_FUNC && i == 2) {
                    // imitate the native arrow function structure of php 7.4-8.1 for stmts
                    child_zv = virtual(ZEND_AST_RETURN, 0, zend_ast_get_lineno(child), child_zv);
                }
                ht.add(child_name, child_zv);
            } else {
                ht.add(null, child_zv);
            }
        }

        if (ast_kind_is_decl(ast_kind)) {
            ht.add("__declId", declIdCounter++);
        }
    }

    private static boolean isFunction(int kind) {
        return kind == ZEND_AST_FUNC_DECL || kind == ZEND_AST_METHOD || kind == ZEND_AST_CLOSURE
                || kind == ZEND_AST_ARROW_FUNC;
    }

    /** A body bodies leaves unconverted, the decl ids in it counted as if converted; or null. */
    private Object leave(ZendAstDecl decl, ZendAst stmts) {
        long decls = countDecls(stmts);
        Object unread = bodies.leave(decl, stmts, declIdCounter, decls);
        if (unread != null) {
            declIdCounter += decls;
        }
        return unread;
    }

    /** The decls in a subtree, each of which takes a decl id when converted. */
    private static long countDecls(ZendAst ast) {
        if (ast == null || ast.child == null) {
            return 0;
        }
        long count = ast_kind_is_decl(ast.kind) ? 1 : 0;
        int children = ast instanceof ZendAstList list ? list.children : ast.child.length;
        for (int i = 0; i < children; i++) {
            count += countDecls(ast.child[i]);
        }
        return count;
    }

    private Object ast_to_zval(ZendAst ast) {
        if (ast == null) {
            return null;
        }
        if (ast instanceof ZendAstZval zv) {
            return value(zv.val);
        }

        int kind = ast.kind;
        int attr = ast.attr;
        switch (kind) {
            case ZEND_AST_GREATER -> {
                kind = ZEND_AST_BINARY_OP;
                attr = AST_BINARY_IS_GREATER;
            }
            case ZEND_AST_GREATER_EQUAL -> {
                kind = ZEND_AST_BINARY_OP;
                attr = AST_BINARY_IS_GREATER_OR_EQUAL;
            }
            case ZEND_AST_OR -> {
                kind = ZEND_AST_BINARY_OP;
                attr = AST_BINARY_BOOL_OR;
            }
            case ZEND_AST_AND -> {
                kind = ZEND_AST_BINARY_OP;
                attr = AST_BINARY_BOOL_AND;
            }
            case ZEND_AST_COALESCE -> {
                kind = ZEND_AST_BINARY_OP;
                attr = AST_BINARY_COALESCE;
            }
            case ZEND_AST_PIPE -> {
                kind = ZEND_AST_BINARY_OP;
                attr = AST_BINARY_PIPE;
            }
            case ZEND_AST_SILENCE -> {
                kind = ZEND_AST_UNARY_OP;
                attr = AST_SILENCE;
            }
            case ZEND_AST_UNARY_PLUS -> {
                kind = ZEND_AST_UNARY_OP;
                attr = AST_PLUS;
            }
            case ZEND_AST_UNARY_MINUS -> {
                kind = ZEND_AST_UNARY_OP;
                attr = AST_MINUS;
            }
            case ZEND_AST_ASSIGN_COALESCE -> {
                kind = ZEND_AST_ASSIGN_OP;
                attr = AST_BINARY_COALESCE;
            }
            case ZEND_AST_CAST_VOID -> {
                kind = ZEND_AST_CAST;
                attr = IS_VOID;
            }
            default -> {
            }
        }

        Children children = new Children();
        int endLine = 0;
        int flags;
        if (ast instanceof ZendAstDecl decl) {
            flags = decl.flags;
            if (kind == ZEND_AST_CLASS) {
                flags &= ~ZEND_ACC_NO_DYNAMIC_PROPERTIES;
            }
            endLine = decl.end_lineno;
            if (kind != ZEND_AST_CLOSURE && kind != ZEND_AST_ARROW_FUNC) {
                // closures and arrow functions have no name (version 110 on)
                children.add("name", decl.name != null ? string(decl.name) : null);
            }
            children.add("docComment", decl.doc_comment != null ? string(decl.doc_comment) : null);
        } else {
            flags = attr;
        }
        // the folded kinds keep their children and their names as the kind they became
        int originalKind = ast.kind;
        ast.kind = kind;
        try {
            ast_fill_children_ht(children, ast);
        } finally {
            ast.kind = originalKind;
        }
        return children.node(kindName(kind), flags, zend_ast_get_lineno(ast), endLine);
    }

    /** A leaf value as the export has it: a string as String when valid UTF-8, else its bytes. */
    private static Object value(Object val) {
        return val instanceof byte[] bytes ? string(bytes) : val;
    }

    /** A PHP string: a String when its bytes are valid UTF-8 (mb_check_encoding), else the bytes. */
    public static Object string(byte[] bytes) {
        boolean ascii = true;
        for (byte b : bytes) {
            if (b < 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return bytes;
        }
    }
}
