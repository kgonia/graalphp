/*
 * Hand-written: a port of the functions php-src 8.5.12's grammar actions call: zend_ast.c's node creation
 * (Zend/zend_ast.c, Zend/zend_ast.h) and zend_compile.c's helpers for modifiers, encoding declarations and number
 * strings (Zend/zend_compile.c). php-src is under the Zend Engine License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static pl.codeloom.php.parser.zend.derived.ZendConstants.*;
import static pl.codeloom.php.parser.zend.generated.ZendLanguageParser.Lexer.*;

/**
 * What the parser generated from php-src's grammar (ZendLanguageParser) extends: php-src's AST functions, named
 * as in php-src since the grammar's actions call them so, over the compiler globals {@link #CG} and the scanner
 * {@link #LANG_SCNG} it shares them with. As in php-src (ZEND_AST_SPEC), a node takes the line of its first child
 * that isn't null, else the current line; a list takes its first child's line unless that is past the current one.
 */
public abstract class ZendParserBase {
    public static final int SUCCESS = ZendScannerBase.SUCCESS;
    public static final int FAILURE = ZendScannerBase.FAILURE;
    protected static final int zend_ce_parse_error = ZendScannerBase.zend_ce_parse_error;
    protected static final int zend_ce_compile_error = ZendScannerBase.zend_ce_compile_error;
    private static final byte[] EMPTY = new byte[0];
    /** The most states the C parser's stack holds (Bison's yacc.c default, which php-src keeps). */
    protected static final int YYMAXDEPTH = 10000;

    /** The parser's stack reached YYMAXDEPTH: php-src's parser stops with "memory exhausted". */
    public static final class MemoryExhausted extends RuntimeException {
        public MemoryExhausted() {
            super("memory exhausted", null, false, false);
        }
    }

    protected CompilerGlobals CG;
    protected ZendScannerBase LANG_SCNG;

    /** Called by the generated constructor (%code init): the lexer brings the globals and the scanner. */
    protected final void bind(Object lexer) {
        ZendLexer zendLexer = (ZendLexer) lexer;
        this.CG = zendLexer.CG;
        this.LANG_SCNG = zendLexer.scanner;
    }

    // ---- zend_ast.c ----

    private static ZendAstZval zend_ast_create_zval_int(Object value, int attr, int lineno) {
        return new ZendAstZval(value, attr, lineno);
    }

    protected final ZendAst zend_ast_create_zval_with_lineno(zval zv, int lineno) {
        return zend_ast_create_zval_int(zv.value, 0, lineno);
    }

    protected final ZendAst zend_ast_create_zval_ex(zval zv, int attr) {
        return zend_ast_create_zval_int(zv.value, attr, CG.zend_lineno);
    }

    protected final ZendAst zend_ast_create_zval(zval zv) {
        return zend_ast_create_zval_int(zv.value, 0, CG.zend_lineno);
    }

    protected final ZendAst zend_ast_create_zval_from_str(byte[] str) {
        return zend_ast_create_zval_int(str, 0, CG.zend_lineno);
    }

    protected final ZendAst zend_ast_create_zval_from_long(long lval) {
        return zend_ast_create_zval_int(lval, 0, CG.zend_lineno);
    }

    protected final ZendAst zend_ast_create_fcc() {
        return new ZendAst(ZEND_AST_CALLABLE_CONVERT, 0, CG.zend_lineno, null);
    }

    protected final ZendAst zend_ast_create_class_const_or_name(ZendAst class_name, ZendAst name) {
        byte[] name_str = zend_ast_get_str(name);
        if (equalsIgnoreCase(name_str, "class")) {
            return zend_ast_create(ZEND_AST_CLASS_NAME, class_name);
        }
        return zend_ast_create(ZEND_AST_CLASS_CONST, class_name, name);
    }

    protected final ZendAst zend_ast_create_decl(int kind, int flags, int start_lineno, byte[] doc_comment,
                                                 byte[] name, ZendAst child0, ZendAst child1, ZendAst child2,
                                                 ZendAst child3, ZendAst child4) {
        return new ZendAstDecl(kind, flags, start_lineno, CG.zend_lineno, doc_comment, name,
                new ZendAst[]{child0, child1, child2, child3, child4});
    }

    /** zend_ast_create_va: kind's children (as many as the kind has), the line of the first one there is. */
    private ZendAst zend_ast_create_va(int kind, int attr, ZendAst... children) {
        if (children.length != kind >> ZEND_AST_NUM_CHILDREN_SHIFT) {
            throw new IllegalArgumentException("kind " + kind + " takes " + (kind >> ZEND_AST_NUM_CHILDREN_SHIFT)
                    + " children, not " + children.length);
        }
        int lineno = -1;
        for (ZendAst child : children) {
            if (child != null) {
                lineno = zend_ast_get_lineno(child);
                break;
            }
        }
        if (lineno == -1) {
            lineno = CG.zend_lineno;
        }
        return new ZendAst(kind, attr, lineno, children);
    }

    protected final ZendAst zend_ast_create(int kind) {
        return zend_ast_create_va(kind, 0);
    }

    protected final ZendAst zend_ast_create(int kind, ZendAst child) {
        return zend_ast_create_va(kind, 0, child);
    }

    protected final ZendAst zend_ast_create(int kind, ZendAst child1, ZendAst child2) {
        return zend_ast_create_va(kind, 0, child1, child2);
    }

    protected final ZendAst zend_ast_create(int kind, ZendAst child1, ZendAst child2, ZendAst child3) {
        return zend_ast_create_va(kind, 0, child1, child2, child3);
    }

    protected final ZendAst zend_ast_create(int kind, ZendAst child1, ZendAst child2, ZendAst child3,
                                            ZendAst child4) {
        return zend_ast_create_va(kind, 0, child1, child2, child3, child4);
    }

    protected final ZendAst zend_ast_create_ex(int kind, int attr) {
        return zend_ast_create_va(kind, attr);
    }

    protected final ZendAst zend_ast_create_ex(int kind, int attr, ZendAst child) {
        return zend_ast_create_va(kind, attr, child);
    }

    protected final ZendAst zend_ast_create_ex(int kind, int attr, ZendAst child1, ZendAst child2) {
        return zend_ast_create_va(kind, attr, child1, child2);
    }

    protected final ZendAst zend_ast_create_ex(int kind, int attr, ZendAst child1, ZendAst child2,
                                               ZendAst child3, ZendAst child4, ZendAst child5, ZendAst child6) {
        return zend_ast_create_va(kind, attr, child1, child2, child3, child4, child5, child6);
    }

    /** zend_ast_create_list_0 */
    protected final ZendAst zend_ast_create_list(int init_children, int kind) {
        ZendAstList list = new ZendAstList(kind, CG.zend_lineno);
        if (kind == ZEND_AST_STMT_LIST) {
            openedBy(list);
        }
        return list;
    }

    /** zend_ast_create_list_1 */
    protected final ZendAst zend_ast_create_list(int init_children, int kind, ZendAst child) {
        int lineno;
        ZendAstList list = new ZendAstList(kind, 0);
        list.children = 1;
        list.child[0] = child;
        if (child != null) {
            lineno = zend_ast_get_lineno(child);
            if (lineno > CG.zend_lineno) {
                lineno = CG.zend_lineno;
            }
        } else {
            lineno = CG.zend_lineno;
        }
        list.lineno = lineno;
        return list;
    }

    /** zend_ast_create_list_2 */
    protected final ZendAst zend_ast_create_list(int init_children, int kind, ZendAst child1, ZendAst child2) {
        int lineno;
        ZendAstList list = new ZendAstList(kind, 0);
        list.children = 2;
        list.child[0] = child1;
        list.child[1] = child2;
        if (child1 != null) {
            lineno = zend_ast_get_lineno(child1);
            if (lineno > CG.zend_lineno) {
                lineno = CG.zend_lineno;
            }
        } else if (child2 != null) {
            lineno = zend_ast_get_lineno(child2);
            if (lineno > CG.zend_lineno) {
                lineno = CG.zend_lineno;
            }
        } else {
            list.children = 0;
            lineno = CG.zend_lineno;
        }
        list.lineno = lineno;
        return list;
    }

    /** A statement list a '{' just scanned opens: where, for a function body to be parsed again on its own. */
    private void openedBy(ZendAstList list) {
        ZendScannerBase scanner = LANG_SCNG;
        if (scanner.yy_leng == 1 && scanner.yy_input[scanner.yy_text] == '{') {
            list.braceOffset = scanner.yy_text;
            list.braceLine = CG.zend_lineno;
            list.braceDocComment = CG.doc_comment;
        }
    }

    protected final ZendAst zend_ast_list_add(ZendAst ast, ZendAst op) {
        ZendAstList list = (ZendAstList) ast;
        if (list.children == list.child.length) {
            list.child = Arrays.copyOf(list.child, list.children * 2);
        }
        list.child[list.children++] = op;
        return list;
    }

    protected final ZendAst zend_ast_list_rtrim(ZendAst ast) {
        ZendAstList list = (ZendAstList) ast;
        if (list.children != 0 && list.child[list.children - 1] == null) {
            list.children--;
        }
        return ast;
    }

    protected final ZendAst zend_ast_create_binary_op(int opcode, ZendAst op0, ZendAst op1) {
        return zend_ast_create_ex(ZEND_AST_BINARY_OP, opcode, op0, op1);
    }

    protected final ZendAst zend_ast_create_assign_op(int opcode, ZendAst op0, ZendAst op1) {
        return zend_ast_create_ex(ZEND_AST_ASSIGN_OP, opcode, op0, op1);
    }

    protected final ZendAst zend_ast_create_cast(int type, ZendAst op0) {
        return zend_ast_create_ex(ZEND_AST_CAST, type, op0);
    }

    /**
     * zend_ast_create_concat_op: two values concatenated at compile time when php-src's concat_function would
     * (strings and numbers, never an error), as a value on the current line; else a BINARY_OP.
     */
    protected final ZendAst zend_ast_create_concat_op(ZendAst op0, ZendAst op1) {
        if (op0 instanceof ZendAstZval zv0 && op1 instanceof ZendAstZval zv1) {
            byte[] left = zval_get_string(zv0.val);
            byte[] right = zval_get_string(zv1.val);
            byte[] result = Arrays.copyOf(left, left.length + right.length);
            System.arraycopy(right, 0, result, left.length, right.length);
            return zend_ast_create_zval_int(result, 0, CG.zend_lineno);
        }
        return zend_ast_create_binary_op(ZEND_CONCAT, op0, op1);
    }

    /** zval_get_string of a value the parser makes: a string, a number, or null. */
    private static byte[] zval_get_string(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof Long l) {
            return Long.toString(l).getBytes(StandardCharsets.US_ASCII);
        }
        if (value instanceof Double d) {
            // zend_double_to_str: precision=14, PHP's default (and the runtime's)
            return pl.codeloom.php.runtime.Convert.doubleToString(d).getBytes(StandardCharsets.US_ASCII);
        }
        return EMPTY;
    }

    protected final ZendAst zend_ast_with_attributes(ZendAst ast, ZendAst attr) {
        switch (ast.kind) {
            case ZEND_AST_FUNC_DECL, ZEND_AST_CLOSURE, ZEND_AST_METHOD, ZEND_AST_ARROW_FUNC, ZEND_AST_PROPERTY_HOOK ->
                    ast.child[4] = attr;
            case ZEND_AST_CLASS -> ast.child[3] = attr;
            case ZEND_AST_PROP_GROUP -> ast.child[2] = attr;
            case ZEND_AST_PARAM, ZEND_AST_ENUM_CASE -> ast.child[3] = attr;
            case ZEND_AST_CLASS_CONST_GROUP -> ast.child[1] = attr;
            // since constants are already stored in a list, the attributes are added to that list
            case ZEND_AST_CONST_DECL -> ast = zend_ast_list_add(ast, attr);
            default -> throw new IllegalStateException("attributes on kind " + ast.kind);
        }
        return ast;
    }

    protected static byte[] zend_ast_get_str(ZendAst ast) {
        return (byte[]) ((ZendAstZval) ast).val;
    }

    protected static int zend_ast_get_lineno(ZendAst ast) {
        return ast.lineno;
    }

    /** php-src frees a node an action gave up; the garbage collector does here. */
    protected static void zend_ast_destroy(Object ast) {
    }

    // ---- strings and values ----

    protected static byte[] ZSTR_KNOWN(int id) {
        return ZEND_KNOWN_STRINGS[id].getBytes(StandardCharsets.US_ASCII);
    }

    protected static byte[] ZSTR_EMPTY_ALLOC() {
        return EMPTY;
    }

    protected static void ZVAL_INTERNED_STR(zval zv, byte[] str) {
        zv.value = str;
    }

    private static boolean equalsIgnoreCase(byte[] bytes, String lower) {
        if (bytes.length != lower.length()) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i];
            if (c >= 'A' && c <= 'Z') {
                c += 'a' - 'A';
            }
            if (c != lower.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // ---- the scanner, as the grammar's actions reach it ----

    protected final int zend_lex_tstring(zval zv, int ident) {
        return LANG_SCNG.zend_lex_tstring(zv, ident);
    }

    protected final int zend_get_scanned_file_offset() {
        return LANG_SCNG.zend_get_scanned_file_offset();
    }

    protected final void zend_stop_lexing() {
        LANG_SCNG.zend_stop_lexing();
    }

    protected final void RESET_DOC_COMMENT() {
        CG.doc_comment = null;
    }

    protected final void zend_throw_exception(int ce, String message, int code) {
        CG.zend_throw_exception(ce == zend_ce_parse_error, message);
    }

    // ---- zend_compile.c ----

    private static String zend_modifier_token_to_string(int token) {
        return switch (token) {
            case T_PUBLIC -> "public";
            case T_PROTECTED -> "protected";
            case T_PRIVATE -> "private";
            case T_STATIC -> "static";
            case T_FINAL -> "final";
            case T_READONLY -> "readonly";
            case T_ABSTRACT -> "abstract";
            case T_PUBLIC_SET -> "public(set)";
            case T_PROTECTED_SET -> "protected(set)";
            case T_PRIVATE_SET -> "private(set)";
            default -> throw new IllegalStateException("not a modifier: " + token);
        };
    }

    protected final int zend_modifier_token_to_flag(int target, int token) {
        switch (token) {
            case T_PUBLIC:
                if (target != ZEND_MODIFIER_TARGET_PROPERTY_HOOK) {
                    return ZEND_ACC_PUBLIC;
                }
                break;
            case T_PROTECTED:
                if (target != ZEND_MODIFIER_TARGET_PROPERTY_HOOK) {
                    return ZEND_ACC_PROTECTED;
                }
                break;
            case T_PRIVATE:
                if (target != ZEND_MODIFIER_TARGET_PROPERTY_HOOK) {
                    return ZEND_ACC_PRIVATE;
                }
                break;
            case T_READONLY:
                if (target == ZEND_MODIFIER_TARGET_PROPERTY || target == ZEND_MODIFIER_TARGET_CPP) {
                    return ZEND_ACC_READONLY;
                }
                break;
            case T_ABSTRACT:
                if (target == ZEND_MODIFIER_TARGET_METHOD || target == ZEND_MODIFIER_TARGET_PROPERTY) {
                    return ZEND_ACC_ABSTRACT;
                }
                break;
            case T_FINAL:
                return ZEND_ACC_FINAL;
            case T_STATIC:
                if (target == ZEND_MODIFIER_TARGET_PROPERTY || target == ZEND_MODIFIER_TARGET_METHOD) {
                    return ZEND_ACC_STATIC;
                }
                break;
            case T_PUBLIC_SET:
                if (target == ZEND_MODIFIER_TARGET_PROPERTY || target == ZEND_MODIFIER_TARGET_CPP) {
                    return ZEND_ACC_PUBLIC_SET;
                }
                break;
            case T_PROTECTED_SET:
                if (target == ZEND_MODIFIER_TARGET_PROPERTY || target == ZEND_MODIFIER_TARGET_CPP) {
                    return ZEND_ACC_PROTECTED_SET;
                }
                break;
            case T_PRIVATE_SET:
                if (target == ZEND_MODIFIER_TARGET_PROPERTY || target == ZEND_MODIFIER_TARGET_CPP) {
                    return ZEND_ACC_PRIVATE_SET;
                }
                break;
            default:
                break;
        }
        String member = switch (target) {
            case ZEND_MODIFIER_TARGET_PROPERTY -> "property";
            case ZEND_MODIFIER_TARGET_METHOD -> "method";
            case ZEND_MODIFIER_TARGET_CONSTANT -> "class constant";
            case ZEND_MODIFIER_TARGET_CPP -> "parameter";
            case ZEND_MODIFIER_TARGET_PROPERTY_HOOK -> "property hook";
            default -> throw new IllegalStateException("modifier target " + target);
        };
        zend_throw_exception(zend_ce_compile_error,
                "Cannot use the " + zend_modifier_token_to_string(token) + " modifier on a " + member, 0);
        return 0;
    }

    protected final int zend_modifier_list_to_flags(int target, ZendAst modifiers) {
        int flags = 0;
        ZendAstList modifier_list = (ZendAstList) modifiers;
        for (int i = 0; i < modifier_list.children; i++) {
            int token = (int) (long) (Long) ((ZendAstZval) modifier_list.child[i]).val;
            int new_flag = zend_modifier_token_to_flag(target, token);
            if (new_flag == 0) {
                return 0;
            }
            // don't error immediately for duplicate flags, the errors of zend_add_member_modifier come first
            boolean duplicate_flag = (flags & new_flag) != 0;
            flags = zend_add_member_modifier(flags, new_flag, target);
            if (flags == 0) {
                return 0;
            }
            if (duplicate_flag) {
                zend_throw_exception(zend_ce_compile_error,
                        "Multiple " + zend_modifier_token_to_string(token) + " modifiers are not allowed", 0);
                return 0;
            }
        }
        return flags;
    }

    protected final int zend_add_class_modifier(int flags, int new_flag) {
        int new_flags = flags | new_flag;
        if ((flags & ZEND_ACC_EXPLICIT_ABSTRACT_CLASS) != 0 && (new_flag & ZEND_ACC_EXPLICIT_ABSTRACT_CLASS) != 0) {
            zend_throw_exception(zend_ce_compile_error, "Multiple abstract modifiers are not allowed", 0);
            return 0;
        }
        if ((flags & ZEND_ACC_FINAL) != 0 && (new_flag & ZEND_ACC_FINAL) != 0) {
            zend_throw_exception(zend_ce_compile_error, "Multiple final modifiers are not allowed", 0);
            return 0;
        }
        if ((flags & ZEND_ACC_READONLY_CLASS) != 0 && (new_flag & ZEND_ACC_READONLY_CLASS) != 0) {
            zend_throw_exception(zend_ce_compile_error, "Multiple readonly modifiers are not allowed", 0);
            return 0;
        }
        if ((new_flags & ZEND_ACC_EXPLICIT_ABSTRACT_CLASS) != 0 && (new_flags & ZEND_ACC_FINAL) != 0) {
            zend_throw_exception(zend_ce_compile_error, "Cannot use the final modifier on an abstract class", 0);
            return 0;
        }
        return new_flags;
    }

    protected final int zend_add_anonymous_class_modifier(int flags, int new_flag) {
        int new_flags = flags | new_flag;
        if ((new_flag & ZEND_ACC_EXPLICIT_ABSTRACT_CLASS) != 0) {
            zend_throw_exception(zend_ce_compile_error, "Cannot use the abstract modifier on an anonymous class", 0);
            return 0;
        }
        if ((new_flag & ZEND_ACC_FINAL) != 0) {
            zend_throw_exception(zend_ce_compile_error, "Cannot use the final modifier on an anonymous class", 0);
            return 0;
        }
        if ((flags & ZEND_ACC_READONLY_CLASS) != 0 && (new_flag & ZEND_ACC_READONLY_CLASS) != 0) {
            zend_throw_exception(zend_ce_compile_error, "Multiple readonly modifiers are not allowed", 0);
            return 0;
        }
        return new_flags;
    }

    private int zend_add_member_modifier(int flags, int new_flag, int target) {
        int new_flags = flags | new_flag;
        if ((flags & ZEND_ACC_PPP_MASK) != 0 && (new_flag & ZEND_ACC_PPP_MASK) != 0) {
            zend_throw_exception(zend_ce_compile_error, "Multiple access type modifiers are not allowed", 0);
            return 0;
        }
        if ((new_flags & ZEND_ACC_ABSTRACT) != 0 && (new_flags & ZEND_ACC_FINAL) != 0) {
            if (target == ZEND_MODIFIER_TARGET_METHOD) {
                zend_throw_exception(zend_ce_compile_error,
                        "Cannot use the final modifier on an abstract method", 0);
                return 0;
            }
            if (target == ZEND_MODIFIER_TARGET_PROPERTY) {
                zend_throw_exception(zend_ce_compile_error,
                        "Cannot use the final modifier on an abstract property", 0);
                return 0;
            }
        }
        if (target == ZEND_MODIFIER_TARGET_PROPERTY || target == ZEND_MODIFIER_TARGET_CPP) {
            if ((flags & ZEND_ACC_PPP_SET_MASK) != 0 && (new_flag & ZEND_ACC_PPP_SET_MASK) != 0) {
                zend_throw_exception(zend_ce_compile_error,
                        "Multiple access type modifiers are not allowed", 0);
                return 0;
            }
        }
        return new_flags;
    }

    /** zend_handle_encoding_declaration, with php-src's zend.multibyte off (its default). */
    protected final boolean zend_handle_encoding_declaration(ZendAst ast) {
        ZendAstList declares = (ZendAstList) ast;
        for (int i = 0; i < declares.children; ++i) {
            ZendAst declare_ast = declares.child[i];
            ZendAst name_ast = declare_ast.child[0];
            ZendAst value_ast = declare_ast.child[1];
            byte[] name = zend_ast_get_str(name_ast);
            if (equalsIgnoreCase(name, "encoding")) {
                if (value_ast.kind != ZEND_AST_ZVAL) {
                    zend_throw_exception(zend_ce_compile_error, "Encoding must be a literal", 0);
                    return false;
                }
                CG.zend_error(CompilerGlobals.E_COMPILE_WARNING,
                        "declare(encoding=...) ignored because Zend multibyte feature is turned off by settings");
            }
        }
        return true;
    }

    protected final ZendAst zend_negate_num_string(ZendAst ast) {
        ZendAstZval zv = (ZendAstZval) ast;
        if (zv.val instanceof Long l) {
            if (l == 0) {
                zv.val = "-0".getBytes(StandardCharsets.US_ASCII);
            } else {
                zv.val = -l;
            }
        } else {
            byte[] str = (byte[]) zv.val;
            byte[] negated = new byte[str.length + 1];
            negated[0] = '-';
            System.arraycopy(str, 0, negated, 1, str.length);
            zv.val = negated;
        }
        return ast;
    }
}
