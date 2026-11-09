package pl.codeloom.php.parser;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.bytecode.DefinedSite;
import pl.codeloom.php.bytecode.PhpBytecodeRootNode;
import pl.codeloom.php.nodes.expr.AssignNodes;
import pl.codeloom.php.parser.zend.ZendParser;
import pl.codeloom.php.nodes.expr.UnaryNodes;
import pl.codeloom.php.nodes.vars.ExtractNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpBailout;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Phases;
import pl.codeloom.php.runtime.PhpType;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Translates php-src's AST (as exported by php-ast, version 120) into Truffle nodes. One translator handles one
 * file's top level; function bodies are translated by a fresh translator on their first call.
 */
public final class Translator {
    // php-ast flag values (tools/ast-flags.php).
    private static final int MODIFIER_PUBLIC = 1;
    private static final int MODIFIER_STATIC = 16;
    private static final int MODIFIER_ABSTRACT = 64;
    private static final int MODIFIER_FINAL = 32;
    private static final int MODIFIER_READONLY = 128;
    private static final int PARAM_REF = 8;
    private static final int PARAM_VARIADIC = 16;
    /** Visibility, readonly or a set visibility on a constructor parameter: a promoted property. */
    private static final int PROMOTED = 7 | 128 | 1024 | 2048 | 4096;
    private static final int FUNC_RETURNS_REF = 4096;
    private static final int CLASS_INTERFACE = 1;
    private static final int CLASS_TRAIT = 2;
    private static final int CLASS_ABSTRACT = 64;
    private static final int CLASS_ENUM = 268435456;
    private static final int CLASS_READONLY = 65536;
    /** A property's modifiers PhpClass.PropertyDecl keeps: readonly and the set visibilities. */
    private static final int PROPERTY_MODIFIERS = MODIFIER_READONLY | 1024 | 2048 | 4096;
    private static final int ARRAY_ELEM_REF = 1;
    private static final int CLOSURE_USE_REF = 1;
    /** How PHP 8.4 names a closure: {closure:the enclosing function or file:its line}. */
    private static final String CLOSURE_PREFIX = "{closure:";

    /** The class whose members are being translated, for self::, parent:: and __CLASS__. */
    record ClassInfo(String name, String parentName, boolean isTrait) {
    }

    private final PhpLanguage language;
    private final String path;
    /** The PHP file's source, for root nodes' source sections (profilers, stack traces); null if unreadable. */
    private final Source source;
    private final boolean internal;
    private final ClassInfo classInfo;
    private final String functionName;
    private final PhpFunction function;
    /** The namespace and imports in effect: updated by a file's namespace and use statements as they come. */
    private Names names;
    /** In a class's members: the class's php-ast flags (interface, trait...). */
    private int classFlags;
    /** The lines of the file's arrow functions right of a `|>` without parentheses; read when first needed. */
    private Set<Integer> unparenthesizedArrowLines;

    /** internal: the file is the prelude's, which stands for php-src's internal code (RootNode.isInternal). */
    public Translator(PhpLanguage language, String path, Source source, boolean internal) {
        this(language, path, source, internal, null, "", null, Names.GLOBAL);
    }

    private Translator(PhpLanguage language, String path, Source source, boolean internal, ClassInfo classInfo,
                       String functionName, PhpFunction function, Names names) {
        this.language = language;
        this.path = path;
        this.source = source;
        this.internal = internal;
        this.classInfo = classInfo;
        this.functionName = functionName;
        this.function = function;
        this.names = names;
    }

    /**
     * A translator for one function's constant expressions (literals, folded constants, constant arrays, magic
     * constants), which the bytecode translator takes from the tree's translation.
     */
    static Translator forConstants(PhpLanguage language, String path, Source source, boolean internal,
                                   ClassInfo classInfo, String functionName, PhpFunction function, Names names) {
        return new Translator(language, path, source, internal, classInfo, functionName, function, names);
    }

    private PhpError unsupported(Ast node, String what) {
        return new PhpError("Unsupported " + what + " (" + node.kind + " at " + path + ":" + node.line + ")");
    }

    /**
     * A compile error zend_compile raises (zend_error_noreturn(E_COMPILE_ERROR, ...)) at a line of the code being
     * translated: reported, and the bailout returned ends the script. A file's top level is translated before it
     * runs, as php-src compiles it; a function's body when it is first called.
     */
    private PhpBailout compileError(int line, String message) {
        return ErrorFunctions.fatal(PhpContext.get(null), ErrorFunctions.E_COMPILE_ERROR, Str.of(message),
                new SourceLocation(path, line), 0);
    }

    // ---- files ----

    /** zend_compile_use: a class can't be imported under a reserved name. */
    void checkUse(int kind, Ast use, String name) {
        String alias = use.get("alias") instanceof String a ? a : name.substring(name.lastIndexOf('\\') + 1);
        if (kind == 1 && RESERVED_CLASS_NAMES.contains(Str.lower(alias))) {
            throw compileError(use.line, "Cannot use " + name + " as " + alias + " because '" + alias
                    + "' is a special class name");
        }
    }

    /** The file's strict_types declarations in their place (translateFile). */
    private final Set<Ast> topDeclares = Collections.newSetFromMap(new IdentityHashMap<>());

    /** A declare statement's strict_types element, or null. */
    static Ast strictTypes(Ast declare) {
        for (Object child : declare.node("declares").children()) {
            if ("strict_types".equalsIgnoreCase((String) ((Ast) child).get("name"))) {
                return (Ast) child;
            }
        }
        return null;
    }

    /**
     * A file's top level on bytecode (BytecodeTranslator.translateFile), after php-src's checks of the whole file:
     * strict_types, and the jumps of every op_array (JumpChecks). reread gives the file's tree again.
     */
    public PhpBytecodeRootNode translateFile(Ast file, Supplier<Ast> reread) {
        return translateFile(file, reread, false);
    }

    /** eval'd code (SourceFiles.eval): a file's top level whose value without `return` is null. */
    public PhpBytecodeRootNode translateEval(Ast file, Supplier<Ast> reread) {
        return translateFile(file, reread, true);
    }

    private PhpBytecodeRootNode translateFile(Ast file, Supplier<Ast> reread, boolean eval) {
        // zend_compile_declare's strict_types: first in the file (after other declares), no block, 0 or 1
        boolean first = true;
        for (Object child : file.children()) {
            if (child instanceof Ast statement && statement.is("AST_DECLARE")) {
                if (strictTypes(statement) != null) {
                    if (!first) {
                        throw compileError(statement.line,
                                "strict_types declaration must be the very first statement in the script");
                    }
                    if (statement.node("stmts") != null) {
                        throw compileError(statement.line, "strict_types declaration must not use block mode");
                    }
                    Object value = strictTypes(statement).get("value");
                    if (!Long.valueOf(0).equals(value) && !Long.valueOf(1).equals(value)) {
                        throw compileError(statement.line, "strict_types declaration must have 0 or 1 as its value");
                    }
                    if (Long.valueOf(1).equals(value)) {
                        language.declareStrict(path);
                    }
                    topDeclares.add(statement);
                }
            } else if (child != null) {
                first = false;
            }
        }
        if (!internal) {
            try {
                JumpChecks.file(file);
            } catch (JumpChecks.Failure failure) {
                throw compileError(failure.line, failure.getMessage());
            }
        }
        return BytecodeTranslator.translateFile(language, path, source, internal, file, reread, eval);
    }

    /** The names of the classes this file's compile has linked so far (lowercase), for {@link #topLevelClass}. */
    private final Set<String> linkedClasses = new HashSet<>();

    /** {@link #earlyBinding}: declared by its statement only. */
    static final int STATEMENT = 0;
    /** Linked by the compile, bound when the file is loaded; no statement. */
    static final int LINKED = 1;
    /** Linked if its parent's checks pass then, else bound late if its parent is declared, else by its statement. */
    static final int BOUND_LINKED = 2;
    /** Bound late if its parent is declared by then (ZEND_DECLARE_CLASS_DELAYED), else by its statement. */
    static final int BOUND_DELAYED = 3;

    /**
     * How a top-level class is bound, as php-src with opcache (the reference's CLI has it) binds it. The compile links
     * one that implements no interface and uses no trait (zend_compile_class_decl) if it has no parent or one linked
     * then: a built-in or a class linked earlier in this file (linkedClasses, ZEND_COMPILE_IGNORE_OTHER_FILES), and
     * if the name isn't a built-in's; it is bound when the file is loaded, and has no statement. Such a class with
     * another parent is bound at the load if the parent is declared by then (ZEND_DECLARE_CLASS_DELAYED), else by its
     * statement. Every other class is declared by its statement.
     */
    int earlyBinding(Ast node, ClassTemplate template, Set<String> linkedClasses) {
        String lower = Str.lower(template.getName());
        String parent = template.getParentName();
        if (!template.getInterfaceNames().isEmpty() || usesTrait(node) || (node.flags & CLASS_ENUM) != 0) {
            return STATEMENT;
        }
        if ((parent == null || linkedClasses.contains(Str.lower(parent)) || isInternalClass(parent))
                && !linkedClasses.contains(lower) && !isInternalClass(lower)) {
            linkedClasses.add(lower);
            // a check needing a class not declared yet leaves it to the delayed binding, then to its statement
            // (zend_try_early_bind's INHERITANCE_UNRESOLVED)
            return parent == null ? LINKED : BOUND_LINKED;
        }
        return parent != null ? BOUND_DELAYED : STATEMENT;
    }

    private static boolean usesTrait(Ast node) {
        for (Object member : node.node("stmts").children()) {
            if (member instanceof Ast ast && ast.is("AST_USE_TRAIT")) {
                return true;
            }
        }
        return false;
    }

    /** A built-in class (php-src's internal ones, which every compile sees), not one a script declared. */
    private boolean isInternalClass(String name) {
        String lower = Str.lower(name);
        if (language.preludeFile(lower) != null) {
            return true;
        }
        PhpClass phpClass = PhpContext.get(null).findClass(lower);
        return phpClass != null && !(phpClass.getDeclaration() instanceof ClassTemplate template
                && template.getDeclaredAt() != null);
    }

    /** Where a function or class is declared: null in internal code, as php-src's internal ones have no file. */
    private SourceLocation declaredAt(Ast node) {
        return internal ? null : new SourceLocation(path, node.line);
    }

    // ---- statements ----

    // ---- generators ----

    /** Whether the code yields: a yield in it, not in a function, closure or class nested in it. */
    static boolean containsYield(Object value) {
        if (!(value instanceof Ast node)) {
            return false;
        }
        switch (node.kind) {
            case "AST_YIELD":
            case "AST_YIELD_FROM":
                return true;
            case "AST_CLOSURE":
            case "AST_ARROW_FUNC":
            case "AST_FUNC_DECL":
            case "AST_CLASS":
            case "AST_METHOD":
                return false;
            default:
                break;
        }
        for (Object child : node.children()) {
            if (containsYield(child)) {
                return true;
            }
        }
        for (Object child : node.namedValues()) {
            if (containsYield(child)) {
                return true;
            }
        }
        return false;
    }

    /** A function or method call (zend_is_call): a function returning by reference gives its reference. */
    /**
     * Whether a place written (assigned, compound-assigned, incremented, unset) stands on a temporary: down its
     * chain of properties and elements, the base fetched for writing (zend_delayed_compile_var) must be a
     * variable, a static property or a call, else php-src's "Cannot use temporary expression in write context".
     */
    static boolean onTemporary(Object place) {
        Object base = place;
        while (base instanceof Ast node && (node.is("AST_PROP") || node.is("AST_NULLSAFE_PROP") || node.is("AST_DIM"))) {
            base = node.get("expr");
        }
        return base != place && !isCall(base)
                && !(base instanceof Ast node && (node.is("AST_VAR") || node.is("AST_STATIC_PROP")));
    }

    static boolean isCall(Object value) {
        return value instanceof Ast node && (node.is("AST_CALL") || node.is("AST_METHOD_CALL")
                || node.is("AST_NULLSAFE_METHOD_CALL") || node.is("AST_STATIC_CALL"));
    }

    /** `new`, include or eval, yield: besides calls, the expressions whose result is php-src's IS_VAR. */
    static boolean isVarResult(Object value) {
        return value instanceof Ast node && (node.is("AST_NEW") || node.is("AST_INCLUDE_OR_EVAL")
                || node.is("AST_YIELD"));
    }

    // ---- functions ----

    /** Builds a PhpFunction whose body is translated on first call. */
    PhpFunction declareFunction(Ast node, ClassInfo owner) {
        String name = owner == null ? names.declared((String) node.get("name")) : (String) node.get("name");
        Names declaredNames = names;
        Ast paramList = node.node("params");
        PhpFunction.Param[] params = params(node);
        int flags = node.flags;
        boolean isStatic = (flags & MODIFIER_STATIC) != 0;
        boolean isPublic = (flags & 7) == 0 || (flags & MODIFIER_PUBLIC) != 0;
        boolean isAbstract = (flags & MODIFIER_ABSTRACT) != 0 || node.get("stmts") == null;
        int kind = owner == null ? Phases.FUNCTION : Phases.METHOD;
        if (!isAbstract) {
            Phases.declared(kind);
        }
        PhpFunction[] self = new PhpFunction[1];
        self[0] = new PhpFunction(name, params, (flags & FUNC_RETURNS_REF) != 0, isStatic, isPublic, isAbstract,
                () -> timedBody(kind, () -> translateBody(node, owner, self[0], name, params, new String[0],
                        new boolean[0], declaredNames)));
        self[0].setDefaults(BytecodeTranslator.defaults(language, path, source, internal, node, owner, self[0],
                owner == null ? name : owner.name() + "::" + name, params, declaredNames));
        self[0].setDeclaredAt(declaredAt(node));
        int modifiers = 0;
        if (owner != null) {
            // a method without a visibility modifier is public; an interface's methods are abstract
            modifiers = flags & (PhpFunction.ACC_PUBLIC | PhpFunction.ACC_PROTECTED | PhpFunction.ACC_PRIVATE
                    | PhpFunction.ACC_STATIC | PhpFunction.ACC_FINAL | PhpFunction.ACC_ABSTRACT);
            if ((modifiers & 7) == 0) {
                modifiers |= PhpFunction.ACC_PUBLIC;
            }
            if (isAbstract) {
                modifiers |= PhpFunction.ACC_ABSTRACT;
            }
        }
        Ast returnType = node.node("returnType");
        self[0].setDeclaredReturn(returnType == null ? null : builtType(returnType, false));
        self[0].setDeclaration(modifiers, typeString(node.node("returnType"), false),
                node.get("docComment") instanceof String doc ? doc : null, node.endLine);
        PhpType declared = self[0].getDeclaredReturn();
        if (declared != null && declared.has(PhpType.VOID | PhpType.NEVER) && node.get("stmts") != null) {
            checkReturns(node.body(), declared, owner != null);
        }
        return self[0];
    }

    /**
     * zend_compile_return's errors in a void or never function, and its being a generator: php-src finds them
     * compiling the file, so a void or never function's body (otherwise read when first called) is read here.
     */
    private void checkReturns(Ast body, PhpType type, boolean method) {
        String kind = method ? "method" : "function";
        List<Ast> returns = new ArrayList<>();
        collect(body, Set.of("AST_RETURN"), returns);
        for (Ast found : returns) {
            if (type.has(PhpType.NEVER)) {
                throw compileError(found.line, "A never-returning " + kind + " must not return");
            }
            if (found.get("expr") != null) {
                throw compileError(found.line, "A void " + kind + " must not return a value"
                        + (isNullConstant(found.get("expr"))
                        ? " (did you mean \"return;\" instead of \"return null;\"?)" : ""));
            }
        }
        generatorReturn(body, type);
    }

    /** zend_mark_function_as_generator: a generator's return type must take a Generator. */
    private void generatorReturn(Ast body, PhpType type) {
        List<Ast> yields = new ArrayList<>();
        collect(body, Set.of("AST_YIELD", "AST_YIELD_FROM"), yields);
        if (yields.isEmpty() || type.has(PhpType.OBJECT)) {
            return;
        }
        for (String name : type.classNames()) {
            String lower = Str.lower(name);
            if (lower.equals("traversable") || lower.equals("iterator") || lower.equals("generator")) {
                return;
            }
        }
        throw compileError(yields.get(0).line, "Generator return type must be a supertype of Generator, "
                + type.text(null) + " given");
    }

    /** The nodes of the kinds in the code, outside the functions and classes nested in it, in order. */
    private static void collect(Object value, Set<String> kinds, List<Ast> found) {
        if (!(value instanceof Ast node)) {
            return;
        }
        if (kinds.contains(node.kind)) {
            found.add(node);
        }
        switch (node.kind) {
            case "AST_CLOSURE", "AST_ARROW_FUNC", "AST_FUNC_DECL", "AST_CLASS", "AST_METHOD":
                return;
            default:
                break;
        }
        for (Object child : node.children()) {
            collect(child, kinds, found);
        }
        for (Object child : node.namedValues()) {
            collect(child, kinds, found);
        }
    }

    /** A declaration's parameters; its types' compile errors, the return type's too, come here. */
    private PhpFunction.Param[] params(Ast declaration) {
        closureTypes = declaration.is("AST_CLOSURE") || declaration.is("AST_ARROW_FUNC");
        try {
            declaredType(declaration.node("returnType"), false);
            Ast paramList = declaration.node("params");
            PhpFunction.Param[] params = new PhpFunction.Param[paramList.size()];
            for (int i = 0; i < params.length; i++) {
                Ast param = (Ast) paramList.child(i);
                checkConstantExpression(param.get("default"), true, param.line);
                Ast type = param.node("type");
                Object defaultValue = param.get("default") == null ? null : param.get("default");
                boolean nullable = isNullable(type) || isNullConstant(defaultValue);
                PhpType declared = declaredType(type, isNullConstant(defaultValue));
                if (declared != null && declared.has(PhpType.VOID | PhpType.NEVER)) {
                    throw compileError(type.line, (declared.has(PhpType.VOID) ? "void" : "never")
                            + " cannot be used as a parameter type");
                }
                if (declared != null) {
                    validDefault(declared, literalBit(defaultValue), param.line,
                            "default value for parameter $" + param.get("name"));
                }
                params[i] = new PhpFunction.Param((String) param.get("name"), (param.flags & PARAM_REF) != 0,
                        (param.flags & PARAM_VARIADIC) != 0, defaultValue == null ? null : defaultText(defaultValue),
                        declared, nullable, typeString(type, isNullConstant(defaultValue)));
            }
            return params;
        } finally {
            closureTypes = false;
        }
    }

    // the type bits, in zend_type_to_string's order of the built-in types
    private static final int TYPE_STATIC = 1;
    private static final int TYPE_CALLABLE = 2;
    private static final int TYPE_ITERABLE = 4;
    private static final int TYPE_OBJECT = 8;
    private static final int TYPE_ARRAY = 16;
    private static final int TYPE_STRING = 32;
    private static final int TYPE_INT = 64;
    private static final int TYPE_FLOAT = 128;
    private static final int TYPE_FALSE = 256;
    private static final int TYPE_TRUE = 512;
    private static final int TYPE_VOID = 1024;
    private static final int TYPE_NEVER = 2048;
    private static final int TYPE_NULL = 4096;
    private static final int TYPE_MIXED = 8192;
    private static final String[] TYPE_KEYWORDS = {"static", "callable", "iterable", "object", "array", "string",
        "int", "float"};

    /**
     * A declared type as zend_type_to_string writes it: the class names (and bracketed intersections) as written,
     * resolved, then the built-in types in php-src's order, with null as "?T" when the rest is one type. self and
     * parent stay as written (Reflection resolves them against the declaring class). impliedNull: a parameter
     * whose default is null, which php-src makes nullable.
     */
    String typeString(Ast type, boolean impliedNull) {
        if (type == null) {
            return null;
        }
        List<String> classes = new ArrayList<>();
        int mask = typeParts(type, classes, impliedNull ? TYPE_NULL : 0);
        if ((mask & TYPE_MIXED) != 0) {
            return "mixed";
        }
        List<String> parts = new ArrayList<>(classes);
        for (int bit = 0; bit < TYPE_KEYWORDS.length; bit++) {
            if ((mask & (1 << bit)) != 0) {
                parts.add(TYPE_KEYWORDS[bit]);
            }
        }
        if ((mask & (TYPE_FALSE | TYPE_TRUE)) == (TYPE_FALSE | TYPE_TRUE)) {
            parts.add("bool");
        } else if ((mask & TYPE_FALSE) != 0) {
            parts.add("false");
        } else if ((mask & TYPE_TRUE) != 0) {
            parts.add("true");
        }
        if ((mask & TYPE_VOID) != 0) {
            parts.add("void");
        }
        if ((mask & TYPE_NEVER) != 0) {
            parts.add("never");
        }
        String text = String.join("|", parts);
        if ((mask & TYPE_NULL) == 0) {
            return text;
        }
        if (text.isEmpty()) {
            return "null";
        }
        return parts.size() == 1 && text.indexOf('&') < 0 ? "?" + text : text + "|null";
    }

    private int typeParts(Ast type, List<String> classes, int mask) {
        switch (type.kind) {
            case "AST_NULLABLE_TYPE":
                return typeParts(type.node("type"), classes, mask | TYPE_NULL);
            case "AST_TYPE_UNION": {
                int union = mask;
                for (Object child : type.children()) {
                    Ast part = (Ast) child;
                    if (part.is("AST_TYPE_INTERSECTION")) {
                        classes.add("(" + intersection(part) + ")");
                    } else {
                        union = typeParts(part, classes, union);
                    }
                }
                return union;
            }
            case "AST_TYPE_INTERSECTION":
                classes.add(intersection(type));
                return mask;
            case "AST_TYPE":
                return mask | switch (type.flags) {
                    case 1 -> TYPE_NULL;
                    case 2 -> TYPE_FALSE;
                    case 3 -> TYPE_TRUE;
                    case 4 -> TYPE_INT;
                    case 5 -> TYPE_FLOAT;
                    case 6 -> TYPE_STRING;
                    case 7 -> TYPE_ARRAY;
                    case 8 -> TYPE_OBJECT;
                    case 12 -> TYPE_CALLABLE;
                    case 13 -> TYPE_ITERABLE;
                    case 14 -> TYPE_VOID;
                    case 15 -> TYPE_STATIC;
                    case 16 -> TYPE_MIXED;
                    case 17 -> TYPE_NEVER;
                    case 18 -> TYPE_FALSE | TYPE_TRUE;
                    default -> 0;
                };
            default:
                classes.add(className(type));
                return mask;
        }
    }

    private String intersection(Ast type) {
        List<String> parts = new ArrayList<>();
        for (Object child : type.children()) {
            parts.add(className((Ast) child));
        }
        return String.join("&", parts);
    }

    static boolean isNullable(Ast type) {
        return type != null && type.is("AST_NULLABLE_TYPE");
    }

    static boolean isNullConstant(Object value) {
        return value instanceof Ast ast && ast.is("AST_CONST") && "null".equalsIgnoreCase(ast.node("name") == null
                ? null : (String) ast.node("name").get("name"));
    }

    /**
     * The type values are checked against (zend_compile_typename), or null for none: self and parent resolved
     * against the declaring class, iterable as Traversable|array. impliedNull: a null default makes it nullable.
     */
    PhpType declaredType(Ast type, boolean impliedNull) {
        if (type == null) {
            return null;
        }
        validateType(type);
        return builtType(type, impliedNull);
    }

    private PhpType builtType(Ast type, boolean impliedNull) {
        List<String> text = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        List<String[]> intersections = new ArrayList<>();
        int mask = typeBits(type, text, classes, intersections) | (impliedNull ? PhpType.NULL : 0);
        if (type.is("AST_TYPE_INTERSECTION")) {
            // a pure intersection is written without brackets
            text.set(0, text.get(0).substring(1, text.get(0).length() - 1));
        }
        return new PhpType(mask, classes.toArray(new String[0]), intersections.toArray(new String[0][]),
                text.toArray(new String[0]));
    }

    /** In a closure's declaration, whose class scope is known only when it runs (zend_is_scope_known). */
    private boolean closureTypes;

    /** zend_compile_typename's compile errors: redundant and misplaced types, self and parent with no class. */
    private void validateType(Ast type) {
        boolean nullable = type.is("AST_NULLABLE_TYPE");
        Ast inner = nullable ? type.node("type") : type;
        List<Ast> parts = new ArrayList<>();
        if (inner.is("AST_TYPE_UNION")) {
            for (Object child : inner.children()) {
                parts.add((Ast) child);
            }
        } else {
            parts.add(inner);
        }
        int mask = 0;
        List<String> names = new ArrayList<>();
        List<String[]> intersections = new ArrayList<>();
        boolean classType = false;
        for (Ast part : parts) {
            if (part.is("AST_TYPE_INTERSECTION")) {
                String[] members = intersectionMembers(part);
                for (String name : names) {
                    redundantIntersection(part, members, new String[] {name}, name);
                }
                for (String[] other : intersections) {
                    redundantIntersection(part, members, other, String.join("&", other));
                    redundantIntersection(part, other, members, String.join("&", members));
                }
                intersections.add(members);
                classType = true;
                continue;
            }
            int bits = typeBits(part, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            if (parts.size() > 1 && (bits & PhpType.MIXED) == PhpType.MIXED) {
                throw compileError(part.line, "Type mixed can only be used as a standalone type");
            }
            if ((mask & bits) != 0) {
                throw compileError(part.line, "Duplicate type " + maskText(mask & bits) + " is redundant");
            }
            if ((mask & PhpType.TRUE) != 0 && bits == PhpType.FALSE || (mask & PhpType.FALSE) != 0
                    && bits == PhpType.TRUE) {
                throw compileError(part.line, "Type contains both true and false, bool must be used instead");
            }
            mask |= bits;
            String name = part.is("AST_TYPE") ? part.flags == 13 ? "Traversable" : null : className(part);
            if (!part.is("AST_TYPE")) {
                checkTypeName(part, name);
                checkClassScope(part, name);
                classType = true;
                // duplicates compare resolved names: self|self is "Duplicate type Foo"
                name = resolvedClassName(part);
            }
            if (name != null) {
                for (String seen : names) {
                    if (seen.equalsIgnoreCase(name)) {
                        throw compileError(part.line, "Duplicate type " + name + " is redundant");
                    }
                }
                for (String[] intersection : intersections) {
                    redundantIntersection(part, intersection, new String[] {name}, name);
                }
                names.add(name);
            }
        }
        if (nullable && (mask & PhpType.MIXED) == PhpType.MIXED) {
            throw compileError(type.line, "Type mixed cannot be marked as nullable since mixed already includes null");
        }
        if (nullable && (mask & PhpType.VOID) != 0) {
            throw compileError(type.line, "Void can only be used as a standalone type");
        }
        if (nullable && (mask & PhpType.NULL) != 0) {
            throw compileError(type.line, "null cannot be marked as nullable");
        }
        if ((mask & PhpType.OBJECT) != 0 && (classType || (mask & PhpType.STATIC) != 0)) {
            throw compileError(type.line, "Type " + builtType(type, false).text(null)
                    + " contains both object and a class type, which is redundant");
        }
        if ((mask & PhpType.VOID) != 0 && (mask != PhpType.VOID || !names.isEmpty() || !intersections.isEmpty())) {
            throw compileError(type.line, "Void can only be used as a standalone type");
        }
        if ((mask & PhpType.NEVER) != 0 && (mask != PhpType.NEVER || !names.isEmpty()
                || !intersections.isEmpty())) {
            throw compileError(type.line, "never can only be used as a standalone type");
        }
        if ((mask & PhpType.STATIC) != 0 && classInfo == null && !closureTypes) {
            throw compileError(type.line, "Cannot use \"static\" when no class scope is active");
        }
    }

    private String[] intersectionMembers(Ast intersection) {
        List<String> members = new ArrayList<>();
        for (Object child : intersection.children()) {
            Ast part = (Ast) child;
            if (part.is("AST_TYPE")) {
                String text = part.flags == 13 ? "Traversable|array"
                        : maskText(typeBits(part, new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
                throw compileError(part.line, "Type " + text + " cannot be part of an intersection type");
            }
            String name = className(part);
            // self and parent only where they resolve at compile time (zend_is_scope_known)
            if ((name.equalsIgnoreCase("self") || name.equalsIgnoreCase("parent"))
                    && (classInfo == null || classInfo.isTrait() || closureTypes)) {
                throw compileError(part.line, "Type " + name + " cannot be part of an intersection type");
            }
            for (String seen : members) {
                if (seen.equalsIgnoreCase(name)) {
                    throw compileError(part.line, "Duplicate type " + name + " is redundant");
                }
            }
            members.add(name);
        }
        return members.toArray(new String[0]);
    }

    /** "Type A&B is redundant as it is more restrictive than type A": wider's classes all in narrower. */
    private void redundantIntersection(Ast at, String[] narrower, String[] wider, String widerText) {
        for (String name : wider) {
            boolean found = false;
            for (String member : narrower) {
                found |= member.equalsIgnoreCase(name);
            }
            if (!found) {
                return;
            }
        }
        throw compileError(at.line, "Type " + String.join("&", narrower) + (narrower.length == wider.length
                ? " is redundant with type " : " is redundant as it is more restrictive than type ") + widerText);
    }

    /** zend_compile_single_typename's name checks: built-in types unqualified, no reserved class name. */
    private void checkTypeName(Ast part, String resolved) {
        String raw = Str.lower((String) part.get("name"));
        if (part.flags != NAME_NOT_FQ && BUILTIN_TYPE_NAMES.contains(raw)) {
            throw compileError(part.line, "Type declaration '" + raw + "' must be unqualified");
        }
        if (part.flags == NAME_FQ && (raw.equals("self") || raw.equals("parent") || raw.equals("static"))) {
            throw compileError(part.line, "'\\" + part.get("name") + "' is an invalid class name");
        }
        String last = Str.lower(resolved.substring(resolved.lastIndexOf('\\') + 1));
        if (resolved.indexOf('\\') >= 0 && RESERVED_CLASS_NAMES.contains(last)) {
            throw compileError(part.line, "Cannot use \"" + resolved + "\" as a type name as it is reserved");
        }
    }

    private static final int NAME_FQ = 0;
    private static final int NAME_NOT_FQ = 1;
    /** zend_lookup_builtin_type_by_name's names, and static. */
    private static final Set<String> BUILTIN_TYPE_NAMES = Set.of("null", "true", "false", "int", "float", "string",
            "bool", "void", "never", "iterable", "object", "mixed", "static");

    /** zend_ensure_valid_class_fetch_type: self and parent need a class, where the scope is known. */
    private void checkClassScope(Ast at, String name) {
        boolean self = name.equalsIgnoreCase("self");
        if (!self && !name.equalsIgnoreCase("parent") || closureTypes) {
            return;
        }
        if (classInfo == null) {
            throw compileError(at.line, "Cannot use \"" + Str.lower(name) + "\" when no class scope is active");
        }
        if (!self && classInfo.parentName() == null && !classInfo.isTrait()) {
            throw compileError(at.line, "Cannot use \"parent\" when current class scope has no parent");
        }
    }

    private static String maskText(int mask) {
        return new PhpType(mask, new String[0], new String[0][], new String[0]).text(null);
    }

    private int typeBits(Ast type, List<String> text, List<String> classes, List<String[]> intersections) {
        switch (type.kind) {
            case "AST_NULLABLE_TYPE":
                return PhpType.NULL | typeBits(type.node("type"), text, classes, intersections);
            case "AST_TYPE_UNION": {
                int mask = 0;
                for (Object child : type.children()) {
                    mask |= typeBits((Ast) child, text, classes, intersections);
                }
                return mask;
            }
            case "AST_TYPE_INTERSECTION": {
                List<String> parts = new ArrayList<>();
                for (Object child : type.children()) {
                    parts.add(resolvedClassName((Ast) child));
                }
                text.add("(" + String.join("&", parts) + ")");
                intersections.add(parts.toArray(new String[0]));
                return 0;
            }
            case "AST_TYPE":
                switch (type.flags) {
                    case 1: return PhpType.NULL;
                    case 2: return PhpType.FALSE;
                    case 3: return PhpType.TRUE;
                    case 4: return PhpType.INT;
                    case 5: return PhpType.FLOAT;
                    case 6: return PhpType.STRING;
                    case 7: return PhpType.ARRAY;
                    case 8: return PhpType.OBJECT;
                    case 12: return PhpType.CALLABLE;
                    case 13:
                        text.add("Traversable");
                        classes.add("Traversable");
                        return PhpType.ARRAY;
                    case 14: return PhpType.VOID;
                    case 15: return PhpType.STATIC;
                    case 16: return PhpType.MIXED;
                    case 17: return PhpType.NEVER;
                    case 18: return PhpType.BOOL;
                    default: return 0;
                }
            default: {
                String name = resolvedClassName(type);
                text.add(name);
                classes.add(name);
                return 0;
            }
        }
    }

    /** A class name in a type: self and parent as the declaring class's names. */
    private String resolvedClassName(Ast type) {
        String name = className(type);
        if (classInfo != null && name.equalsIgnoreCase("self")) {
            return classInfo.name();
        }
        if (classInfo != null && classInfo.parentName() != null && name.equalsIgnoreCase("parent")) {
            return classInfo.parentName();
        }
        return name;
    }

    /** A function's return type as its returns are checked: none for void (always null) and generators. */
    static PhpType checkedReturn(PhpFunction function, boolean generator) {
        PhpType type = function.getDeclaredReturn();
        return type == null || type.has(PhpType.VOID) || generator ? null : type;
    }

    /**
     * A parameter's default as zend_get_function_declaration writes it: a literal, a string's first 10 bytes,
     * [] or [...], a constant's name, else <expression>.
     */
    private static String defaultText(Object value) {
        if (value instanceof Long || value instanceof Double) {
            return Convert.toStr(value).toString();
        }
        if (value instanceof String || value instanceof byte[]) {
            String text = value instanceof String s ? s
                    : new String((byte[]) value, java.nio.charset.StandardCharsets.ISO_8859_1);
            return "'" + (text.length() > 10 ? text.substring(0, 10) + "..." : text) + "'";
        }
        Ast ast = (Ast) value;
        switch (ast.kind) {
            case "AST_CONST": {
                String name = (String) ast.node("name").get("name");
                String lower = Str.lower(name);
                return lower.equals("null") || lower.equals("true") || lower.equals("false") ? lower : name;
            }
            case "AST_ARRAY":
                return ast.size() == 0 ? "[]" : "[...]";
            case "AST_CLASS_CONST":
                return ast.node("class").get("name") + "::" + ast.get("const");
            case "AST_UNARY_OP":
                if (ast.get("expr") instanceof Long || ast.get("expr") instanceof Double) {
                    return (ast.flags == 262 ? "-" : "") + defaultText(ast.get("expr"));
                }
                return "<expression>";
            default:
                return "<expression>";
        }
    }

    /** A body's translation, counted and timed as one of the kind's (Phases). */
    private static RootCallTarget timedBody(int kind, Supplier<RootCallTarget> translate) {
        Phases.translated(kind);
        int outer = Phases.enter(Phases.TRANSLATE);
        try {
            return translate.get();
        } finally {
            Phases.exit(outer);
        }
    }

    private RootCallTarget translateBody(Ast node, ClassInfo owner, PhpFunction target, String name,
                                         PhpFunction.Param[] params, String[] captures, boolean[] captureByRef,
                                         Names declaredNames) {
        String display = owner == null ? name : owner.name() + "::" + name;
        Ast stmts = node.body();
        // an arrow function too: its body is a return of its expression, a generator's return when it yields
        boolean generator = containsYield(stmts);
        if (generator && target.getDeclaredReturn() != null) {
            generatorReturn(stmts, target.getDeclaredReturn());
        }
        return BytecodeTranslator.translate(language, path, source, internal, node, stmts, owner, target, display,
                params, captures, captureByRef, declaredNames, generator);
    }

    /**
     * A closure's or arrow function's function, made where its declaration is translated, and the variables of
     * the enclosing code it captures (by reference where byRef says): a closure's use list, or every variable an
     * arrow function's body names (nested arrow functions' too) that the enclosing code has (enclosingVariable),
     * but its parameters and $this.
     */
    record ClosureParts(PhpFunction function, String[] captures, boolean[] byRef, boolean isStatic) {
    }

    ClosureParts closureParts(Ast node, Predicate<String> enclosingVariable) {
        boolean arrow = node.is("AST_ARROW_FUNC");
        PhpFunction.Param[] params = params(node);
        // read for the captures and $this here, and again when the closure is first called
        Ast stmts = node.body();
        List<String> captureNames = new ArrayList<>();
        List<Boolean> captureRefs = new ArrayList<>();
        if (arrow) {
            Set<String> used = new LinkedHashSet<>();
            collectVariables(stmts, used);
            for (PhpFunction.Param param : params) {
                used.remove(param.name());
            }
            used.remove("this");
            for (String name : used) {
                // find_implicit_binds: auto-globals ($GLOBALS, the superglobals) are no captures
                if (enclosingVariable.test(name) && !name.equals("GLOBALS")
                        && !PhpContext.SUPERGLOBALS.contains(name)) {
                    captureNames.add(name);
                    captureRefs.add(false);
                }
            }
        } else if (node.node("uses") != null) {
            for (Object child : node.node("uses").children()) {
                Ast use = (Ast) child;
                captureNames.add((String) use.get("name"));
                captureRefs.add((use.flags & CLOSURE_USE_REF) != 0);
            }
        }
        String[] names = captureNames.toArray(new String[0]);
        boolean[] byRef = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            byRef[i] = captureRefs.get(i);
        }
        // php-src's name: after the file at top level, the enclosing closure, or Class::function() it's declared in
        String enclosing = function == null ? path
                : function.getName().startsWith(CLOSURE_PREFIX) ? function.getName() : functionName + "()";
        String name = CLOSURE_PREFIX + enclosing + ":" + node.line + "}";
        ClassInfo owner = classInfo;
        Names declaredNames = this.names;
        PhpFunction[] self = new PhpFunction[1];
        Phases.declared(Phases.CLOSURE);
        self[0] = new PhpFunction(name, params, (node.flags & FUNC_RETURNS_REF) != 0,
                (node.flags & MODIFIER_STATIC) != 0, true, false,
                () -> timedBody(Phases.CLOSURE, () -> translateBody(node, owner, self[0], name, params, names, byRef,
                        declaredNames)));
        self[0].setDefaults(BytecodeTranslator.defaults(language, path, source, internal, node, owner, self[0],
                owner == null ? name : owner.name() + "::" + name, params, declaredNames));
        self[0].setDeclaredAt(declaredAt(node));
        Ast returnType = node.node("returnType");
        self[0].setDeclaredReturn(returnType == null ? null : builtType(returnType, false));
        self[0].setDeclaration(0, typeString(node.node("returnType"), false),
                node.get("docComment") instanceof String doc ? doc : null, node.endLine);
        PhpType declared = self[0].getDeclaredReturn();
        if (declared != null && declared.has(PhpType.VOID | PhpType.NEVER) && !arrow) {
            checkReturns(stmts, declared, false);
        }
        Set<String> bodyVariables = new HashSet<>();
        collectVariables(stmts, bodyVariables);
        self[0].setUsesThis(bodyVariables.contains("this"));
        List<String> variables = new ArrayList<>(List.of(names));
        List<Object> initials = new ArrayList<>();
        staticVariables(stmts, variables, initials);
        self[0].setClosureVariables(variables.toArray(new String[0]), initials.toArray());
        return new ClosureParts(self[0], names, byRef, (node.flags & MODIFIER_STATIC) != 0);
    }

    /**
     * The `static` variables a closure's body declares (not its nested functions' and classes'), after its `use`
     * ones, and their initial values where php-src evaluates them at compile time (a constant expression).
     */
    private void staticVariables(Object node, List<String> names, List<Object> initials) {
        if (!(node instanceof Ast ast) || ast.is("AST_CLOSURE") || ast.is("AST_ARROW_FUNC") || ast.is("AST_FUNC_DECL")
                || ast.is("AST_CLASS")) {
            return;
        }
        if (ast.is("AST_STATIC") && ast.node("var") != null && ast.node("var").get("name") instanceof String name) {
            if (!names.contains(name)) {
                names.add(name);
                Object initial = ast.get("default");
                initials.add(initial instanceof Ast expr && !(expr.is("AST_ARRAY") || expr.is("AST_CONST")
                        || expr.is("AST_UNARY_OP") || expr.is("AST_BINARY_OP")) ? null
                        : fold(initial));
            }
            return;
        }
        for (Object child : ast.children()) {
            staticVariables(child, names, initials);
        }
        for (Object child : ast.namedValues()) {
            staticVariables(child, names, initials);
        }
    }

    /** Variable names used in an arrow function body (including nested arrow functions, which capture too). */
    private static void collectVariables(Object node, Set<String> names) {
        if (!(node instanceof Ast ast)) {
            return;
        }
        if (ast.is("AST_VAR") && ast.get("name") instanceof String name) {
            names.add(name);
        }
        if (ast.is("AST_CLOSURE")) {
            if (ast.node("uses") != null) {
                for (Object use : ast.node("uses").children()) {
                    names.add((String) ((Ast) use).get("name"));
                }
            }
            return;
        }
        for (Object child : ast.children()) {
            collectVariables(child, names);
        }
        for (String key : new String[]{"expr", "var", "left", "right", "cond", "true", "false", "args", "dim",
                "stmts", "class", "method", "prop", "key", "value", "name", "default"}) {
            // a nested arrow function's body is unread (AstBody) until asked for
            collectVariables(key.equals("stmts") ? ast.body() : ast.get(key), names);
        }
    }

    // ---- classes ----

    /** An anonymous class's template, under a name of its own (the bytecode translator's too). */
    ClassTemplate anonymousClass(Ast classNode) {
        String parent = classNode.get("extends") == null ? "class" : className(classNode.node("extends"));
        String name = parent + "@anonymous\0" + path + ":" + classNode.line + "$"
                + Integer.toHexString(language.nextAnonymousClass());
        return classTemplate(classNode, name);
    }

    /** zend_reserved_class_names. */
    private static final Set<String> RESERVED_CLASS_NAMES = Set.of("bool", "false", "float", "int", "null",
            "parent", "self", "static", "string", "true", "void", "never", "iterable", "object", "mixed");

    ClassTemplate classTemplate(Ast node, String name) {
        String unqualified = Str.lower(name.substring(name.lastIndexOf('\\') + 1));
        if (node.get("name") != null && RESERVED_CLASS_NAMES.contains(unqualified)) {
            throw compileError(node.line, "Cannot use \"" + node.get("name") + "\" as a class name as it is reserved");
        }
        if ((node.flags & CLASS_TRAIT) != 0) {
            return traitTemplate(node, name);
        }
        String parentName = node.get("extends") == null ? null : className(node.node("extends"));
        List<String> interfaces = new ArrayList<>();
        if (node.node("implements") != null) {
            for (Object child : node.node("implements").children()) {
                interfaces.add(className((Ast) child));
            }
        }
        boolean isInterface = (node.flags & CLASS_INTERFACE) != 0;
        boolean isAbstract = (node.flags & CLASS_ABSTRACT) != 0;
        String backingType = null;
        if ((node.flags & ClassTemplate.ENUM) != 0 && node.node("type") != null) {
            backingType = typeString(node.node("type"), false);
            if (!backingType.equals("int") && !backingType.equals("string")) {
                throw compileError(node.line, "Enum backing type must be int or string, " + backingType + " given");
            }
        }
        if ((node.flags & ClassTemplate.READONLY) != 0 && hasAttribute(node, "AllowDynamicProperties")) {
            throw compileError(node.line, "Cannot apply #[\\AllowDynamicProperties] to readonly class "
                    + name.split("\0")[0]);
        }
        ClassInfo info = new ClassInfo(name, parentName, false);
        Translator inClass = new Translator(language, path, source, internal, info, "", null, names);
        inClass.classFlags = node.flags;
        List<ClassTemplate.Member> members = new ArrayList<>();
        String unsupported = null;
        try {
            for (Object child : node.node("stmts").children()) {
                inClass.addMember(members, (Ast) child, name, info, node.flags, backingType);
            }
        } catch (PhpError error) {
            // the file still loads; the declaration fails if it runs, after resolving its parent and interfaces
            unsupported = error.getMessage();
        }
        // add_stringable_interface: declaring __toString implements Stringable
        for (ClassTemplate.Member member : members) {
            if (member instanceof ClassTemplate.Method method && method.function().getName().equalsIgnoreCase(
                    "__tostring") && interfaces.stream().noneMatch("Stringable"::equalsIgnoreCase)) {
                interfaces.add("Stringable");
            }
        }
        ClassTemplate template = new ClassTemplate(name, parentName, interfaces, isInterface, isAbstract, members,
                unsupported);
        template.setDeclaredAt(declaredAt(node));
        template.setDeclaration(node.flags, node.get("docComment") instanceof String doc ? doc : null, node.endLine);
        template.setBackingType(backingType);
        return template;
    }

    /** Whether the declaration has an attribute of the class (by its resolved name, case-insensitively). */
    private boolean hasAttribute(Ast node, String className) {
        if (node.node("attributes") == null) {
            return false;
        }
        for (Object group : node.node("attributes").children()) {
            for (Object attribute : ((Ast) group).children()) {
                if (className(((Ast) attribute).node("class")).equalsIgnoreCase(className)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A trait: its members are translated for each class that uses it, on that class's first declaration. */
    private ClassTemplate traitTemplate(Ast node, String name) {
        Ast statements = node.node("stmts");
        Names declaredNames = names;
        ClassTemplate.TraitBody body = (className, parentName) -> {
            ClassInfo info = new ClassInfo(className, parentName, true);
            Translator inClass = new Translator(language, path, source, internal, info, "", null, declaredNames);
            inClass.classFlags = CLASS_TRAIT;
            List<ClassTemplate.Member> members = new ArrayList<>();
            for (Object child : statements.children()) {
                inClass.addMember(members, (Ast) child, className, info, 0, null);
            }
            return members;
        };
        ClassTemplate template = new ClassTemplate(name, null, List.of(), false, true, List.of(), null, body);
        template.setDeclaredAt(declaredAt(node));
        template.setDeclaration(node.flags, node.get("docComment") instanceof String doc ? doc : null, node.endLine);
        return template;
    }

    private ClassTemplate.TraitUse traitUse(Ast node) {
        List<String> traits = new ArrayList<>();
        for (Object child : node.node("traits").children()) {
            traits.add(className((Ast) child));
        }
        List<ClassTemplate.Alias> aliases = new ArrayList<>();
        List<ClassTemplate.Exclusion> exclusions = new ArrayList<>();
        if (node.node("adaptations") != null) {
            for (Object child : node.node("adaptations").children()) {
                Ast adaptation = (Ast) child;
                Ast reference = adaptation.node("method");
                String trait = reference.get("class") == null ? null : className(reference.node("class"));
                String method = Str.lower((String) reference.get("method"));
                if (adaptation.is("AST_TRAIT_PRECEDENCE")) {
                    List<String> excluded = new ArrayList<>();
                    for (Object other : adaptation.node("insteadof").children()) {
                        excluded.add(className((Ast) other));
                    }
                    exclusions.add(new ClassTemplate.Exclusion(trait, method, excluded));
                } else {
                    aliases.add(new ClassTemplate.Alias(trait, method,
                            adaptation.get("alias") instanceof String alias ? alias : null, adaptation.flags
                                    & (PhpFunction.ACC_PUBLIC | PhpFunction.ACC_PROTECTED | PhpFunction.ACC_PRIVATE
                                    | PhpFunction.ACC_FINAL)));
                }
            }
        }
        return new ClassTemplate.TraitUse(traits, aliases, exclusions);
    }

    /**
     * A class member; classFlags and backingType are the class's (php-ast's flags; an enum's "int" or "string"),
     * 0 and null for a trait's.
     */
    private void addMember(List<ClassTemplate.Member> members, Ast member, String className, ClassInfo info,
                           int classFlags, String backingType) {
        boolean isEnum = (classFlags & ClassTemplate.ENUM) != 0;
        int readonlyClass = (classFlags & ClassTemplate.READONLY) != 0 ? MODIFIER_READONLY : 0;
        switch (member.kind) {
            case "AST_METHOD": {
                String methodName = (String) member.get("name");
                if (isEnum && (member.flags & MODIFIER_ABSTRACT) != 0) {
                    throw compileError(member.line, "Enum method " + className + "::" + methodName
                            + "() must not be abstract");
                }
                if (isEnum && (methodName.equalsIgnoreCase("cases") || backingType != null
                        && (methodName.equalsIgnoreCase("from") || methodName.equalsIgnoreCase("tryFrom")))) {
                    throw compileError(member.line, "Cannot redeclare " + className + "::" + methodName + "()");
                }
                PhpFunction method = declareFunction(member, info);
                magicReturnType(member, className, method.getName());
                List<ClassTemplate.Property> promoted = new ArrayList<>();
                if (method.getName().equalsIgnoreCase("__construct")) {
                    for (Object child : member.node("params").children()) {
                        Ast param = (Ast) child;
                        if (isPromoted(param)) {
                            String name = (String) param.get("name");
                            String propertyName = Str.sourceKey(name);
                            checkSetVisibility(param.flags, param.node("type"), className, name, param.line);
                            int modifiers = param.flags & PROPERTY_MODIFIERS | readonlyClass;
                            // a hooked property stays untyped: its variance and state are its hooks'
                            PhpType type = param.get("hooks") != null ? null
                                    : propertyType(param.node("type"), className, propertyName);
                            if ((modifiers & MODIFIER_READONLY) != 0 && param.node("type") == null) {
                                throw compileError(param.line, "Readonly property " + className + "::$" + name
                                        + " must have type");
                            }
                            promoted.add(new ClassTemplate.Property(propertyName, type == null ? PhpNull.NULL : null,
                                    PhpClass.PropertyDecl.visibilityOf(param.flags), false, modifiers, type,
                                    param.flags, propertyHooks(param.node("hooks"), name, param.node("type"),
                                            param.flags, false, className, info, param.line), false));
                        }
                    }
                }
                method.setClassName(className);
                members.add(new ClassTemplate.Method(method, promoted));
                break;
            }
            case "AST_CLASS_CONST_GROUP": {
                for (Object child : member.node("const").children()) {
                    Ast element = (Ast) child;
                    String constantName = (String) element.get("name");
                    String what = "class constant " + className + "::" + constantName;
                    PhpType type = declaredType(member.node("type"), false);
                    if (type != null && !type.isMixed() && type.has(PhpType.CALLABLE | PhpType.VOID | PhpType.NEVER)) {
                        throw compileError(member.line, "Class constant " + className + "::" + constantName
                                + " cannot have type " + type.text(null));
                    }
                    Object value = constantOrDeferred(element.get("value"));
                    if (type != null && !(value instanceof ClassTemplate.Deferred)) {
                        value = validDefault(type, value, member.line, "value for " + what);
                    }
                    members.add(new ClassTemplate.Constant(constantName, value,
                            PhpClass.PropertyDecl.visibilityOf(member.flags), type));
                }
                break;
            }
            case "AST_PROP_GROUP": {
                if (isEnum) {
                    throw compileError(member.line, "Enum " + className + " cannot include properties");
                }
                int flags = member.flags;
                boolean isStatic = (flags & MODIFIER_STATIC) != 0;
                int visibility = PhpClass.PropertyDecl.visibilityOf(flags);
                // zend_compile_prop_decl
                if ((flags & MODIFIER_FINAL) != 0 && visibility == PhpClass.PropertyDecl.PRIVATE) {
                    throw compileError(member.line, "Property cannot be both final and private");
                }
                if ((classFlags & CLASS_INTERFACE) != 0) {
                    if ((flags & MODIFIER_FINAL) != 0) {
                        throw compileError(member.line, "Property in interface cannot be final");
                    }
                    if (visibility != PhpClass.PropertyDecl.PUBLIC) {
                        throw compileError(member.line, "Property in interface cannot be protected or private");
                    }
                    if ((flags & MODIFIER_ABSTRACT) != 0) {
                        throw compileError(member.line, "Property in interface cannot be explicitly abstract. "
                                + "All interface members are implicitly abstract");
                    }
                }
                int modifiers = flags & PROPERTY_MODIFIERS | readonlyClass;
                for (Object child : member.node("props").children()) {
                    Ast element = (Ast) child;
                    checkSetVisibility(flags, member.node("type"), className, (String) element.get("name"),
                            element.line);
                    String propertyName = Str.sourceKey((String) element.get("name"));
                    // a hooked property stays untyped: its variance and state are its hooks'
                    boolean hooked = element.get("hooks") != null;
                    PhpType type = hooked ? null : propertyType(member.node("type"), className, propertyName);
                    if ((modifiers & MODIFIER_READONLY) != 0) {
                        // zend_compile_prop_decl's readonly checks
                        String what = "Readonly property " + className + "::$" + element.get("name");
                        if (member.node("type") == null) {
                            throw compileError(element.line, what + " must have type");
                        }
                        if (element.get("default") != null) {
                            throw compileError(element.line, what + " cannot have default value");
                        }
                        if (isStatic) {
                            throw compileError(element.line, "Static property " + className + "::$"
                                    + element.get("name") + " cannot be readonly");
                        }
                    }
                    Object initial = element.get("default") == null ? type == null ? PhpNull.NULL : null
                            : constantOrDeferred(element.get("default"));
                    if (type != null && initial != null && !(initial instanceof ClassTemplate.Deferred)) {
                        initial = validDefault(type, initial, member.line, "default value for property " + className
                                + "::$" + Str.keyText(propertyName));
                    }
                    members.add(new ClassTemplate.Property(propertyName, initial, visibility, isStatic, modifiers,
                            type, flags, propertyHooks(element.node("hooks"), (String) element.get("name"),
                                    member.node("type"), flags, isStatic, className, info, element.line),
                            element.get("default") != null));
                }
                break;
            }
            case "AST_ENUM_CASE": {
                String caseName = (String) member.get("name");
                if (!isEnum) {
                    throw compileError(member.line, "Case can only be used in enums");
                }
                if (backingType == null && member.get("expr") != null) {
                    throw compileError(member.line, "Case " + caseName + " of non-backed enum " + className
                            + " must not have a value");
                }
                if (backingType != null && member.get("expr") == null) {
                    throw compileError(member.line, "Case " + caseName + " of backed enum " + className
                            + " must have a value");
                }
                members.add(new ClassTemplate.Case(caseName,
                        member.get("expr") == null ? null : constantOrDeferred(member.get("expr"))));
                break;
            }
            case "AST_USE_TRAIT":
                members.add(traitUse(member));
                break;
            default:
                throw unsupported(member, "class member");
        }
    }

    /**
     * `parent::$name::get()` or `::set()`, a parent property hook's call (zend_compile_parent_property_hook_call),
     * but not `(parent::$name)::get()`, a static property's (ZEND_PARENTHESIZED_STATIC_PROP).
     */
    static boolean isParentHookCall(Ast call) {
        return call.node("class") instanceof Ast prop && prop.is("AST_STATIC_PROP") && (prop.flags & 1) == 0
                && prop.get("prop") != null && !(prop.get("prop") instanceof Ast)
                && prop.node("class") instanceof Ast parent && parent.is("AST_NAME")
                && "parent".equalsIgnoreCase((String) parent.get("name")) && call.get("method") instanceof String method
                && (method.equalsIgnoreCase("get") || method.equalsIgnoreCase("set"));
    }

    /** A parent hook call's property name, as written: `parent::${0}::get()`'s is "0". */
    static String parentHookProperty(Ast call) {
        return String.valueOf(call.node("class").get("prop"));
    }

    /** php-src's compile error of a parent hook call in the function named function, or null. */
    static String parentHookError(Ast call, String function) {
        String property = parentHookProperty(call);
        String method = (String) call.get("method");
        String prefix = "Must not use parent::$" + property + "::" + method + "() ";
        if (!function.startsWith("$")) {
            return prefix + "outside a property hook";
        }
        String active = function.substring(1, function.lastIndexOf("::"));
        String kind = function.substring(function.lastIndexOf("::") + 2);
        return !active.equals(property) ? prefix + "in a different property ($" + active + ")"
                : !kind.equalsIgnoreCase(method) ? prefix + "in a different property hook (" + kind + ")" : null;
    }

    /** A hook body's parent hook calls checked at the class's compilation, as php-src does (not in nested functions). */
    private void checkParentHooks(Object node, String function) {
        if (!(node instanceof Ast ast) || ast.is("AST_CLOSURE") || ast.is("AST_ARROW_FUNC") || ast.is("AST_CLASS")
                || ast.is("AST_FUNC_DECL")) {
            return;
        }
        if (ast.is("AST_STATIC_CALL") && isParentHookCall(ast)) {
            String error = parentHookError(ast, function);
            if (error != null) {
                throw compileError(ast.line, error);
            }
        }
        for (Object child : ast.children()) {
            checkParentHooks(child, function);
        }
        for (Object child : ast.namedValues()) {
            checkParentHooks(child, function);
        }
    }

    /** zend_compile_prop_decl's checks of a set visibility (private(set), protected(set), public(set)). */
    private void checkSetVisibility(int flags, Ast type, String className, String name, int line) {
        int set = (flags & (1024 | 2048 | 4096)) >> 10;
        if (set == 0) {
            return;
        }
        if (type == null) {
            throw compileError(line, "Property with asymmetric visibility " + className + "::$" + name
                    + " must have type");
        }
        // public 1, protected 2, private 4, for both
        int read = (flags & 4) != 0 ? 4 : (flags & 2) != 0 ? 2 : 1;
        if (set < read) {
            throw compileError(line, "Visibility of property " + className + "::$" + name
                    + " must not be weaker than set visibility");
        }
    }

    /** A constructor parameter that declares a property: a visibility, readonly or final modifier, or hooks. */
    static boolean isPromoted(Ast param) {
        return (param.flags & (PROMOTED | MODIFIER_FINAL)) != 0 || param.get("hooks") != null;
    }

    /**
     * A property's hooks (zend_compile_property_hooks), each a method named "$name::get" or "$name::set" as
     * php-src makes it: a short get body returns its expression, a short set body assigns it to the property, a set
     * hook without a parameter list takes $value of the property's type, and a get hook returns that type. Null for
     * a property without; php-src's compile errors.
     */
    private PhpClass.Hooks propertyHooks(Ast hookList, String name, Ast type, int flags, boolean isStatic,
                                         String className, ClassInfo info, int line) {
        boolean isInterface = (classFlags & CLASS_INTERFACE) != 0;
        String property = className + "::$" + name;
        if (hookList == null) {
            if (isInterface) {
                throw compileError(line, "Interfaces may only include hooked properties");
            }
            if ((flags & MODIFIER_ABSTRACT) != 0) {
                throw compileError(line, "Only hooked properties may be declared abstract");
            }
            return null;
        }
        if ((flags & MODIFIER_READONLY) != 0 || (classFlags & CLASS_READONLY) != 0) {
            throw compileError(line, "Hooked properties cannot be readonly");
        }
        if (hookList.size() == 0) {
            throw compileError(line, "Property hook list must not be empty");
        }
        boolean isPrivate = PhpClass.PropertyDecl.visibilityOf(flags) == PhpClass.PropertyDecl.PRIVATE;
        boolean virtual = true;
        boolean anyAbstract = false;
        PhpFunction get = null;
        PhpFunction set = null;
        for (Object child : hookList.children()) {
            Ast hook = (Ast) child;
            Object body = hook.get("stmts");
            if (isStatic) {
                throw compileError(hook.line, "Cannot declare hooks for static property");
            }
            if ((hook.flags & MODIFIER_FINAL) != 0 && isPrivate) {
                throw compileError(hook.line, "Property hook cannot be both final and private");
            }
            boolean isAbstract = isInterface || (flags & MODIFIER_ABSTRACT) != 0 && body == null;
            if (isAbstract) {
                if (body != null) {
                    throw compileError(hook.line, "Abstract property hook cannot have body");
                }
                if (isPrivate) {
                    throw compileError(hook.line, "Property hook cannot be both abstract and private");
                }
                if ((hook.flags & MODIFIER_FINAL) != 0) {
                    throw compileError(hook.line, "Property hook cannot be both abstract and final");
                }
                anyAbstract = true;
            } else if (body == null) {
                throw compileError(hook.line, "Non-abstract property hook must have a body");
            }
            String hookName = (String) hook.get("name");
            String kind = Str.lower(hookName);
            if (!kind.equals("get") && !kind.equals("set")) {
                throw compileError(hook.line, "Unknown hook \"" + hookName + "\" for property " + property
                        + ", expected \"get\" or \"set\"");
            }
            Ast params = hook.node("params");
            boolean shortBody = body instanceof Ast ast && ast.is("AST_PROPERTY_HOOK_SHORT_BODY");
            if (body != null && (shortBody && kind.equals("set") || usesProperty(body, name))) {
                virtual = false;
            }
            if (kind.equals("get")) {
                if (params != null) {
                    throw compileError(hook.line, "get hook of property " + property
                            + " must not have a parameter list");
                }
                params = new Ast("AST_PARAM_LIST", 0, hook.line, hook.line, List.of(), null);
            } else if (params != null) {
                if (params.size() != 1) {
                    throw compileError(hook.line, hookName + " hook of property " + property
                            + " must accept exactly one parameters");
                }
                Ast param = (Ast) params.child(0);
                String of = " of " + hookName + " hook " + property;
                if ((param.flags & PARAM_REF) != 0) {
                    throw compileError(hook.line, "Parameter $" + param.get("name") + of
                            + " must not be pass-by-reference");
                }
                if ((param.flags & PARAM_VARIADIC) != 0) {
                    throw compileError(hook.line, "Parameter $" + param.get("name") + of + " must not be variadic");
                }
                if (param.get("default") != null) {
                    throw compileError(hook.line, "Parameter $" + param.get("name") + of
                            + " must not have a default value");
                }
                if ((type != null) != (param.get("type") != null)) {
                    throw compileError(hook.line, "Type of parameter $" + param.get("name") + " of hook " + property
                            + "::set must be compatible with property type");
                }
            } else {
                Map<String, Object> value = new java.util.LinkedHashMap<>();
                value.put("type", type);
                value.put("name", "value");
                params = new Ast("AST_PARAM_LIST", 0, hook.line, hook.line,
                        List.of(new Ast("AST_PARAM", 0, hook.line, hook.line, null, value)), null);
            }
            if (shortBody) {
                Object value = ((Ast) body).get("expr");
                Ast statement;
                if (kind.equals("get")) {
                    statement = new Ast("AST_RETURN", 0, hook.line, hook.line, null, Map.of("expr", value));
                } else {
                    Ast self = new Ast("AST_VAR", 0, hook.line, hook.line, null, Map.of("name", "this"));
                    Map<String, Object> target = new java.util.LinkedHashMap<>();
                    target.put("expr", self);
                    target.put("prop", name);
                    Map<String, Object> assign = new java.util.LinkedHashMap<>();
                    assign.put("var", new Ast("AST_PROP", 0, hook.line, hook.line, null, target));
                    assign.put("expr", value);
                    statement = new Ast("AST_ASSIGN", 0, hook.line, hook.line, null, assign);
                }
                body = new Ast("AST_STMT_LIST", 0, hook.line, hook.line, List.of(statement), null);
            }
            checkParentHooks(body, "$" + name + "::" + kind);
            Map<String, Object> method = new java.util.LinkedHashMap<>();
            method.put("name", "$" + name + "::" + kind);
            method.put("docComment", hook.get("docComment"));
            method.put("params", params);
            method.put("stmts", body);
            method.put("returnType", kind.equals("get") ? type : null);
            // non-private hooks are public (zend_compile_property_hooks)
            int methodFlags = (hook.flags & (MODIFIER_FINAL | FUNC_RETURNS_REF)) | (isPrivate ? 4 : MODIFIER_PUBLIC)
                    | (isAbstract ? MODIFIER_ABSTRACT : 0);
            PhpFunction function = declareFunction(new Ast("AST_METHOD", methodFlags, hook.line, hook.endLine, null,
                    method), info);
            function.setClassName(className);
            if (kind.equals("get") ? get != null : set != null) {
                throw compileError(hook.line, "Cannot redeclare property hook \"" + hookName + "\"");
            }
            if (kind.equals("get")) {
                get = function;
            } else {
                set = function;
            }
        }
        if (isInterface) {
            virtual = true;
        }
        if (virtual && (flags & (1024 | 2048 | 4096)) != 0 && (get == null) != (set == null)) {
            throw compileError(line, (get != null ? "Read-only" : "Write-only") + " virtual property " + property
                    + " must not specify asymmetric visibility");
        }
        // zend_verify_hooked_property's checks that don't depend on a parent
        if ((flags & MODIFIER_ABSTRACT) != 0 && !anyAbstract) {
            throw compileError(line, "Abstract property " + property + " must specify at least one abstract hook");
        }
        return new PhpClass.Hooks(get, set, virtual);
    }

    /**
     * Whether a hook's body uses the property's backing value, `$this->name` (zend_property_hook_find_property_usage),
     * not looking into nested functions and classes.
     */
    private static boolean usesProperty(Object node, String name) {
        if (!(node instanceof Ast ast)) {
            return false;
        }
        if ((ast.is("AST_PROP") || ast.is("AST_NULLSAFE_PROP")) && ast.get("expr") instanceof Ast object
                && object.is("AST_VAR") && "this".equals(object.get("name")) && name.equals(ast.get("prop"))) {
            return true;
        }
        if (ast.is("AST_CLOSURE") || ast.is("AST_ARROW_FUNC") || ast.is("AST_CLASS") || ast.is("AST_FUNC_DECL")
                || ast.is("AST_METHOD")) {
            return false;
        }
        for (Object child : ast.children()) {
            if (usesProperty(child, name)) {
                return true;
            }
        }
        for (Object child : ast.namedValues()) {
            if (usesProperty(child, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * zend_compile_const_expr's checks: only zend_is_allowed_in_const_expr's kinds, `new` where the context takes
     * it (not in a class constant or property default), no object cast, only static closures, calls only as first
     * class callables.
     */
    void checkConstantExpression(Object expression, boolean newAllowed, int line) {
        String error = constantExpressionError(expression, newAllowed);
        if (error != null) {
            throw compileError(expression instanceof Ast ast ? ast.line : line, error);
        }
    }

    private String constantExpressionError(Object expression, boolean newAllowed) {
        if (!(expression instanceof Ast ast)) {
            return null;
        }
        switch (ast.kind) {
            case "AST_CLOSURE":
                return (ast.flags & MODIFIER_STATIC) == 0 ? "Closures in constant expressions must be static"
                        : ast.get("uses") != null ? "Cannot use(...) variables in constant expression" : null;
            case "AST_NEW":
                if (!newAllowed) {
                    return "New expressions are not supported in this context";
                }
                break;
            case "AST_CAST":
                if (ast.flags == TYPE_OBJECT) {
                    return "Object casts are not supported in this context";
                }
                break;
            case "AST_CALL", "AST_STATIC_CALL": {
                if (!isCallableConvert(ast)) {
                    return "Constant expression contains invalid operations";
                }
                boolean call = ast.is("AST_CALL");
                if (!(ast.get(call ? "expr" : "class") instanceof Ast name) || !name.is("AST_NAME")) {
                    return call ? "Cannot use dynamic function name in constant expression"
                            : "Cannot use dynamic class name in constant expression";
                }
                if (!call && !(ast.get("method") instanceof String)) {
                    return "Cannot use dynamic method name in constant expression";
                }
                if (!call && className(name).equalsIgnoreCase("static")) {
                    return "\"static\" is not allowed in compile-time constants";
                }
                break;
            }
            case "AST_NAME", "AST_BINARY_OP", "AST_GREATER", "AST_GREATER_EQUAL", "AST_AND", "AST_OR",
                 "AST_UNARY_OP", "AST_UNARY_PLUS", "AST_UNARY_MINUS", "AST_CONDITIONAL", "AST_DIM", "AST_ARRAY",
                 "AST_ARRAY_ELEM", "AST_UNPACK", "AST_CONST", "AST_CLASS_CONST", "AST_CLASS_NAME", "AST_MAGIC_CONST",
                 "AST_COALESCE", "AST_ARG_LIST", "AST_NAMED_ARG", "AST_PROP", "AST_NULLSAFE_PROP",
                 "AST_CALLABLE_CONVERT":
                break;
            default:
                return "Constant expression contains invalid operations";
        }
        for (Object child : ast.children()) {
            String error = constantExpressionError(child, newAllowed);
            if (error != null) {
                return error;
            }
        }
        for (Object child : ast.namedValues()) {
            String error = constantExpressionError(child, newAllowed);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    /** A property's type (zend_compile_prop_decl): no void, never or callable. */
    private PhpType propertyType(Ast ast, String className, String propertyName) {
        PhpType type = declaredType(ast, false);
        if (type != null && type.has(PhpType.VOID | PhpType.NEVER | PhpType.CALLABLE)) {
            throw compileError(ast.line, "Property " + className + "::$" + Str.keyText(propertyName)
                    + " cannot have type " + type.text(null));
        }
        return type;
    }

    /**
     * zend_is_valid_default_value: a constant default must be of the type (an int is one of a float, as a float);
     * what names it: "default value for property C::$p", "value for class constant C::X"...
     */
    private Object validDefault(PhpType type, Object value, int line, String what) {
        int bit = value instanceof Long ? PhpType.INT : value instanceof Double ? PhpType.FLOAT
                : value instanceof TruffleString ? PhpType.STRING : value instanceof Boolean b
                ? (b ? PhpType.TRUE : PhpType.FALSE) : value == PhpNull.NULL ? PhpType.NULL
                : value instanceof PhpArray ? PhpType.ARRAY : 0;
        validDefault(type, bit, line, what);
        return bit == PhpType.INT && !type.has(PhpType.INT) ? (Object) (double) (long) (Long) value : value;
    }

    private void validDefault(PhpType type, int bit, int line, String what) {
        if (bit == 0 || type.has(bit) || bit == PhpType.INT && type.has(PhpType.FLOAT)) {
            return;
        }
        if (bit == PhpType.NULL && !type.isIntersection() && what.startsWith("default value for property")) {
            throw compileError(line, "Default value for property of type " + type.text(null)
                    + " may not be null. Use the nullable type " + type.withNull().text(null)
                    + " to allow null default value");
        }
        String name = bit == PhpType.INT ? "int" : bit == PhpType.FLOAT ? "float" : bit == PhpType.STRING ? "string"
                : bit == PhpType.ARRAY ? "array" : bit == PhpType.NULL ? "null" : "bool";
        throw compileError(line, "Cannot use " + name + " as " + what + " of type " + type.text(null));
    }

    /** The type bit of a parameter's default as written, if it is a literal (0 for an expression to evaluate). */
    private static int literalBit(Object value) {
        if (value instanceof Long) {
            return PhpType.INT;
        }
        if (value instanceof Double) {
            return PhpType.FLOAT;
        }
        if (value instanceof String || value instanceof byte[]) {
            return PhpType.STRING;
        }
        if (value instanceof Ast ast && ast.is("AST_CONST") && ast.node("name") != null) {
            String name = Str.lower((String) ast.node("name").get("name"));
            return name.equals("true") ? PhpType.TRUE : name.equals("false") ? PhpType.FALSE : 0;
        }
        if (value instanceof Ast ast && ast.is("AST_ARRAY") && ast.size() == 0) {
            return PhpType.ARRAY;
        }
        if (value instanceof Ast ast && ast.is("AST_UNARY_OP") && (ast.get("expr") instanceof Long
                || ast.get("expr") instanceof Double)) {
            return literalBit(ast.get("expr"));
        }
        return 0;
    }

    /** zend_check_magic_method_return_type: a magic method's declared return type must be php-src's. */
    private void magicReturnType(Ast method, String className, String name) {
        Ast type = method.node("returnType");
        if (type == null) {
            return;
        }
        String lower = Str.lower(name);
        if (lower.equals("__construct") || lower.equals("__destruct")) {
            throw compileError(method.line, "Method " + className + "::" + name + "() cannot declare a return type");
        }
        int required = switch (lower) {
            case "__clone", "__set", "__unset", "__wakeup", "__unserialize" -> PhpType.VOID;
            case "__isset" -> PhpType.BOOL;
            case "__tostring" -> PhpType.STRING;
            case "__debuginfo" -> PhpType.ARRAY | PhpType.NULL;
            case "__serialize", "__sleep" -> PhpType.ARRAY;
            case "__set_state" -> PhpType.OBJECT;
            default -> 0;
        };
        PhpType declared = builtType(type, false);
        if (required == 0 || declared.has(PhpType.NEVER)) {
            return;
        }
        int extra = declared.mask & ~required;
        boolean complex = declared.hasClasses() || (extra & PhpType.STATIC) != 0;
        if ((extra & ~PhpType.STATIC) != 0 || complex && required != PhpType.OBJECT) {
            throw compileError(method.line, className + "::" + name + "(): Return type must be "
                    + maskText(required) + " when declared");
        }
    }

    /**
     * The value an expression has at compile time, or null when it has none (it is evaluated where it is used):
     * literals; true, false and null; a predefined constant whose name can't mean a namespace's own; magic
     * constants; `-` of a number; a concatenation whose parts all fold (null, false and "" dropping out, as
     * zend_compile_binary_op's); a constant array (frozen: every place that stores it copies before writing, like
     * php-src's immutable arrays); `Name::class`, self's and parent's.
     */
    Object fold(Object value) {
        if (value instanceof String || value instanceof byte[]) {
            return literal(value);
        }
        if (value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value == null) {
            return PhpNull.NULL;
        }
        Ast node = (Ast) value;
        switch (node.kind) {
            case "AST_CONST": {
                Ast nameNode = node.node("name");
                String[] resolved = names.constantName((String) nameNode.get("name"), nameNode.flags);
                // true, false and null are global; a namespace can't declare them
                String name = resolved[1] != null ? resolved[1] : resolved[0];
                switch (Str.lower(name)) {
                    case "true":
                        return Boolean.TRUE;
                    case "false":
                        return Boolean.FALSE;
                    case "null":
                        return PhpNull.NULL;
                    default:
                        break;
                }
                if (language.builtins().deprecation(name) != null || resolved[1] != null) {
                    return null;
                }
                return language.builtins().constant(name);
            }
            case "AST_MAGIC_CONST":
                return magicValue(node);
            case "AST_UNARY_OP": {
                Object operand = node.flags == 262 ? fold(node.get("expr")) : null;
                if (operand instanceof Long l && l != Long.MIN_VALUE) {
                    return -l;
                }
                return operand instanceof Double d ? -d : null;
            }
            case "AST_BINARY_OP": {
                if (node.flags != 8) {
                    return null;
                }
                StringBuilder parts = new StringBuilder();
                return foldConcat(node, parts) ? language.intern(Str.of(parts.toString())) : null;
            }
            case "AST_ARRAY":
                return foldArray(node);
            case "AST_CLASS_CONST":
                if (!((String) node.get("const")).equalsIgnoreCase("class")) {
                    return null;
                }
                return foldClassName(node);
            case "AST_CLASS_NAME":
                return foldClassName(node);
            default:
                return null;
        }
    }

    /** A concatenation's parts (concatParts) appended to text; false when one doesn't fold to a scalar. */
    private boolean foldConcat(Object operand, StringBuilder text) {
        if (operand instanceof Ast ast && ast.is("AST_CAST") && ast.flags == UnaryNodes.CastNode.TO_STRING) {
            return foldConcat(ast.get("expr"), text);
        }
        if (operand instanceof Ast ast && ast.is("AST_BINARY_OP") && ast.flags == 8) {
            return foldConcat(ast.get("left"), text) && foldConcat(ast.get("right"), text);
        }
        if (operand instanceof Ast ast && ast.is("AST_ENCAPS_LIST") && interpolated(ast).size() == 1) {
            return foldConcat(interpolated(ast).get(0), text);
        }
        Object value = fold(operand);
        if (value == null || value instanceof PhpArray) {
            return false;
        }
        text.append(Str.java(Convert.toStr(value)));
        return true;
    }

    /** A constant array literal's value, frozen; null when an element isn't constant. */
    private Object foldArray(Ast node) {
        int count = node.size();
        Object[] keys = new Object[count];
        Object[] values = new Object[count];
        boolean ints = true;
        for (int i = 0; i < count; i++) {
            if (!(node.child(i) instanceof Ast element) || element.is("AST_UNPACK")
                    || (element.flags & ARRAY_ELEM_REF) != 0) {
                return null;
            }
            keys[i] = element.get("key") == null ? null : fold(element.get("key"));
            values[i] = fold(element.get("value"));
            if (values[i] == null || element.get("key") != null && keys[i] == null) {
                return null;
            }
            ints &= values[i] instanceof Long;
        }
        // an int list starts in the int strategy; anything else is sized for its elements
        PhpArray array = ints ? new PhpArray() : PhpArray.ofCapacity(count);
        for (int i = 0; i < count; i++) {
            if (keys[i] == null) {
                array.append(values[i]);
            } else {
                array.put(ArrayKey.of(keys[i]), values[i]);
            }
        }
        array.freeze();
        return array;
    }

    /** `Name::class` as written, self's and parent's; null for static's and an expression's. */
    private Object foldClassName(Ast node) {
        if (!(node.get("class") instanceof Ast ast) || !ast.is("AST_NAME")) {
            return null;
        }
        String name = className(ast);
        return switch (Str.lower(name)) {
            case "self" -> Str.of(requireClass(node).name());
            case "parent" -> Str.of(requireClass(node).parentName());
            case "static" -> null;
            default -> Str.of(name);
        };
    }

    /** A magic constant's value (zend_try_ct_eval_magic_const). */
    private Object magicValue(Ast node) {
        switch (node.flags) {
            case 346:
                return (long) node.line;
            case 347:
                return Str.of(path);
            case 348:
                return Str.of(Path.of(path).getParent().toString());
            case 349:
                return Str.of(classInfo == null ? "" : classInfo.name());
            case 351: {
                // a closure's __METHOD__ is its name, without its scope's class
                int closure = functionName.indexOf(CLOSURE_PREFIX);
                return Str.of(closure > 0 ? functionName.substring(closure) : functionName);
            }
            case 352:
                return Str.of(functionName.contains("::") ? functionName.substring(functionName.indexOf("::") + 2)
                        : functionName);
            case 353: {
                // __PROPERTY__: in a property hook ("$name::get"), the property's name
                String hook = function == null ? "" : function.getName();
                return Str.of(hook.startsWith("$") ? hook.substring(1, hook.lastIndexOf("::")) : "");
            }
            case 354:
                return Str.of(names.namespace());
            default:
                throw unsupported(node, "magic constant " + node.flags);
        }
    }

    /** A constant expression's value if it folds, else its initializer, evaluated per class on first use. */
    private Object constantOrDeferred(Object expression) {
        checkConstantExpression(expression, false, 0);
        Object value = fold(expression);
        if (value != null) {
            return value;
        }
        return new BytecodeTranslator.Thunk(language, path, source, internal, (Ast) expression, classInfo, names);
    }

    /** A class name as written, resolved against the namespace and imports (self, parent, static unchanged). */
    private String className(Ast name) {
        return names.className((String) name.get("name"), name.flags);
    }

    // ---- expressions ----

    /**
     * The int or float zend_eval_const_expr folds an arithmetic expression of constants to (zend_try_ct_eval_binary_op),
     * which the tree computes when it runs; null if it isn't one, or the operation would raise an error, which
     * php-src leaves to run time.
     */
    Object foldedArithmetic(Object value) {
        if (!(value instanceof Ast node) || !node.is("AST_BINARY_OP")) {
            return null;
        }
        switch (node.flags) {
            case 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12:
                break;
            default:
                return null;
        }
        Object left = foldedOperand(node.get("left"));
        Object right = left == null ? null : foldedOperand(node.get("right"));
        if (!(left instanceof Long || left instanceof Double) || !(right instanceof Long || right instanceof Double)) {
            return null;
        }
        try {
            return AssignNodes.CompoundAssignNode.apply(null, node.flags, left, right);
        } catch (PhpError | ArithmeticException error) {
            return null;
        }
    }

    /** A match's second default arm, which zend_compile_match refuses; null if it has at most one. */
    static Ast secondDefaultArm(Ast match) {
        Ast armList = match.node("stmts");
        boolean seen = false;
        for (int i = 0; armList != null && i < armList.size(); i++) {
            Ast arm = (Ast) armList.child(i);
            if (arm.get("cond") == null) {
                if (seen) {
                    return arm;
                }
                seen = true;
            }
        }
        return null;
    }

    /**
     * The line of a match's ZEND_MATCH_ERROR (a match without a default arm): CG(zend_lineno) when it is emitted,
     * after the last expression compiled before it. constants has each condition's value when it is a constant
     * expression, else null, in order. can_match_use_jumptable folds the conditions (zend_eval_const_expr) until
     * one isn't an int or string constant: a folded one is a new zval at the line of the moment, the subject's.
     * With a jump table (two or more conditions, all int or string constants) the subject is the last expression
     * compiled; else the last condition.
     */
    static int matchErrorLine(Ast match, List<Object> constants) {
        int subjectLine = lastLine(match.get("cond"), match.line);
        Ast armList = match.node("stmts");
        Object lastCondition = null;
        int lastArmLine = match.line;
        boolean lastFolded = false;
        boolean scanning = true;
        int index = 0;
        for (int i = 0; armList != null && i < armList.size(); i++) {
            Ast arm = (Ast) armList.child(i);
            if (arm.get("cond") == null) {
                continue;
            }
            for (Object condition : arm.node("cond").children()) {
                Object constant = constants.get(index++);
                lastCondition = condition;
                lastArmLine = arm.line;
                lastFolded = scanning && condition instanceof Ast && constant != null;
                scanning &= constant instanceof Long || constant instanceof TruffleString;
            }
        }
        if (lastCondition == null || scanning && index >= 2 || lastFolded) {
            return subjectLine;
        }
        return lastLine(lastCondition, lastArmLine);
    }

    /**
     * The line CG(zend_lineno) is at once an expression is compiled: that of the last expression compiled in it
     * (zend_compile_expr sets it to each one's line), a closure's last line; a literal has the line around it.
     */
    private static int lastLine(Object value, int around) {
        if (!(value instanceof Ast node)) {
            return around;
        }
        if (node.is("AST_CLOSURE") || node.is("AST_ARROW_FUNC")) {
            return node.endLine > 0 ? node.endLine : node.line;
        }
        int line = node.line > 0 ? node.line : around;
        for (Object part : node.namedValues()) {
            line = lastLine(part, line);
        }
        for (Object part : node.children()) {
            line = lastLine(part, line);
        }
        return line;
    }

    // ---- `?->` chains ----

    /** The child of a chain's link its object comes from: a static member's class, else the expression. */
    private static String chainLink(Ast link) {
        return link.is("AST_STATIC_PROP") || link.is("AST_STATIC_CALL") ? "class" : "expr";
    }

    /**
     * The deepest `?->` on a chain's object path (zend_ast_is_short_circuited: elements, properties, method calls
     * and static members), which is evaluated first; null for a node that isn't such a chain or has none.
     */
    static Ast deepestNullsafe(Object value) {
        Ast found = null;
        for (Object link = value; link instanceof Ast ast; link = ast.get(chainLink(ast))) {
            switch (ast.kind) {
                case "AST_NULLSAFE_PROP", "AST_NULLSAFE_METHOD_CALL" -> found = ast;
                case "AST_PROP", "AST_METHOD_CALL", "AST_DIM", "AST_STATIC_PROP", "AST_STATIC_CALL" -> {
                }
                default -> {
                    return found;
                }
            }
        }
        return found;
    }

    /** A `->m(...)` the `?->` short-circuits (zend_compile_method_call's compile error), or null. */
    static Ast convertInChain(Ast node, Ast nullsafe) {
        for (Ast link = node; ; link = link.node(chainLink(link))) {
            if ((link.is("AST_METHOD_CALL") || link.is("AST_NULLSAFE_METHOD_CALL")) && isCallableConvert(link)) {
                return link;
            }
            if (link == nullsafe) {
                return null;
            }
        }
    }

    /** A copy of the chain from node down to nullsafe, which becomes a plain access to object. */
    static Ast withObject(Ast node, Ast nullsafe, Ast object) {
        Map<String, Object> parts = new LinkedHashMap<>(node.namedOrNull());
        String kind = node.kind;
        if (node == nullsafe) {
            parts.put("expr", object);
            kind = node.is("AST_NULLSAFE_PROP") ? "AST_PROP" : "AST_METHOD_CALL";
        } else {
            parts.put(chainLink(node), withObject(node.node(chainLink(node)), nullsafe, object));
        }
        return new Ast(kind, node.flags, node.line, node.endLine, null, parts);
    }

    // ---- places ----

    /** zend_propagate_list_refs: whether an element of the list, or of a list in it, is taken by reference. */
    static boolean listHasRefs(Ast list) {
        boolean refs = false;
        for (Object child : list.children()) {
            if (child instanceof Ast element) {
                refs |= element.node("value") != null && element.node("value").is("AST_ARRAY")
                        ? listHasRefs(element.node("value")) : (element.flags & ARRAY_ELEM_REF) != 0;
            }
        }
        return refs;
    }

    // ---- operators ----

    // ---- constants and literals ----

    // ---- calls ----

    /**
     * `operand |> callable` as php-src compiles it (zend_compile_pipe), the operand a value evaluated before:
     * `f(...)` (but assert's), `A::m(...)` and `$o->m(...)` become their call with the operand as the argument;
     * any other callable is evaluated and called with it.
     */
    static Ast pipeCall(Ast pipe, Ast operand) {
        Object right = pipe.get("right");
        List<Object> list = new ArrayList<>();
        list.add(operand);
        Ast args = new Ast("AST_ARG_LIST", 0, pipe.line, pipe.endLine, list, null);
        Map<String, Object> parts;
        if (right instanceof Ast call && isCallableConvert(call) && (call.is("AST_STATIC_CALL")
                || call.is("AST_METHOD_CALL") || call.is("AST_CALL") && !"assert".equalsIgnoreCase(call.text("expr")))) {
            parts = new LinkedHashMap<>(call.namedOrNull());
            parts.put("args", args);
            return new Ast(call.kind, call.flags, call.line, call.endLine, null, parts);
        }
        parts = new LinkedHashMap<>();
        parts.put("expr", right);
        parts.put("args", args);
        int line = right instanceof Ast ast ? ast.line : pipe.line;
        return new Ast("AST_CALL", 0, line, line, null, parts);
    }

    /**
     * Whether an arrow function right of a `|>` isn't parenthesized (zend_compile_pipe's compile error): php-ast
     * doesn't export ZEND_PARENTHESIZED_ARROW_FUNC, so the file's tokens are scanned for `|>` before `fn`.
     */
    boolean unparenthesizedArrow(Ast arrow) {
        if (unparenthesizedArrowLines == null) {
            unparenthesizedArrowLines = source == null ? Set.of() : ZendParser.unparenthesizedPipeArrows(
                    source.getCharacters().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return unparenthesizedArrowLines.contains(arrow.line);
    }

    /** `f(...)`: the call's arguments are the first-class callable marker, not a list. */
    static boolean isCallableConvert(Ast call) {
        return call.get("args") instanceof Ast args && args.is("AST_CALLABLE_CONVERT");
    }

    /**
     * Whether extract()'s array goes by reference: its parameter prefers one (ZEND_SEND_PREFER_REF), which a
     * variable or other place takes, and only EXTR_REFS uses (php_extract_ref_*): unless the flags are a constant
     * without it, a place is passed by reference ($GLOBALS is a value, ZEND_FETCH_GLOBALS).
     */
    boolean extractTakesRef(Ast args) {
        if (args.size() < 2 || !(args.child(0) instanceof Ast array) || !(array.is("AST_DIM") || array.is("AST_PROP")
                || array.is("AST_STATIC_PROP") || array.is("AST_VAR") && array.get("name") instanceof String name
                && !name.equals("this") && !name.equals("GLOBALS"))) {
            return false;
        }
        Object flags = args.child(1);
        if (flags instanceof Ast ast && (ast.is("AST_CONST") || ast.is("AST_BINARY_OP"))) {
            flags = fold(ast);
        }
        return !(flags instanceof Long value) || (value & ExtractNode.REFS) != 0;
    }

    /**
     * A call php-src compiles into an opcode giving a temporary (zend_try_compile_special_func), not a call's result:
     * as an argument it is sent as a value (SEND_VAL_EX), never as a call's result (SEND_VAR_NO_REF).
     */
    boolean compiledInPlace(Ast call) {
        if (isCallableConvert(call)) {
            return true; // a closure (ZEND_CALLABLE_CONVERT)
        }
        Ast callee = call.node("expr");
        if (!call.is("AST_CALL") || !callee.is("AST_NAME")) {
            return false;
        }
        String[] resolved = names.functionName((String) callee.get("name"), callee.flags);
        String name = Str.lower(resolved[0]);
        Ast args = call.node("args");
        return resolved[1] == null && (compiledSpecially(name, args) || (name.equals("func_get_args")
                || name.equals("func_num_args") || name.equals("get_called_class")) && args.size() == 0);
    }

    /**
     * Whether php-src compiles a call to this built-in into an opcode of its own, which makes no frame
     * (zend_try_compile_special_func): for a name resolved at compile time, without unpacking or named arguments.
     */
    boolean compiledSpecially(String name, Ast args) {
        int count = args.size();
        for (Object arg : args.children()) {
            if (arg instanceof Ast ast && (ast.is("AST_UNPACK") || ast.is("AST_NAMED_ARG"))) {
                return false;
            }
        }
        return switch (name) {
            case "strlen", "is_null", "is_bool", "is_long", "is_int", "is_integer", "is_float", "is_double",
                 "is_string", "is_array", "is_object", "is_resource", "is_scalar", "boolval", "intval", "floatval",
                 "doubleval", "strval", "count", "sizeof", "gettype", "clone" -> count == 1;
            case "get_class" -> count <= 1;
            case "array_key_exists" -> count == 2;
            case "defined" -> count == 1 && definedName(args.child(0)) instanceof String constant
                    && constant.indexOf('\\') < 0 && constant.indexOf(':') < 0;
            case "chr" -> count == 1 && args.child(0) instanceof Long;
            case "ord" -> count == 1 && args.child(0) instanceof String;
            case "sprintf" -> count >= 1 && args.child(0) instanceof String format && format.length() < 256
                    && sprintfPlaceholders(format) == count - 1;
            default -> false;
        };
    }

    /**
     * The name a literal argument of defined() gives (zend_compile_func_defined: any literal, as zval_get_string
     * converts it, precision 14 for a float), or null for an argument that isn't a literal.
     */
    private static String definedName(Object arg) {
        if (arg instanceof String name) {
            return name;
        }
        if (arg instanceof Long l) {
            return Long.toString(l);
        }
        return arg instanceof Double d ? Convert.doubleToString(d) : null;
    }

    /**
     * defined('NAME') compiled in place on bytecode (ZEND_DEFINED, the call compiledSpecially): true for a name
     * zend_try_ct_eval_const knows at compile time, else the DefinedSite testing the name.
     */
    Object definedSite(Ast args) {
        String name = definedName(args.child(0));
        String lower = Str.lower(name);
        if (lower.equals("true") || lower.equals("false") || lower.equals("null")
                || language.builtins().constant(name) != null) {
            return Boolean.TRUE;
        }
        return new DefinedSite(language.constantId(name));
    }

    /** zend_compile_func_sprintf's test: the number of %s and %d in a format of only those (and %%), else -1. */
    private static int sprintfPlaceholders(String format) {
        int count = 0;
        for (int i = format.indexOf('%'); i >= 0; i = format.indexOf('%', i + 2)) {
            if (i + 1 == format.length()) {
                return -1;
            }
            char c = format.charAt(i + 1);
            if (c == 's' || c == 'd') {
                count++;
            } else if (c != '%') {
                return -1;
            }
        }
        return count;
    }

    /** compact()'s variable names when they are string literals or arrays of them; false otherwise. */
    boolean compactNames(Ast list, List<String> out) {
        for (Object child : list.children()) {
            if (child instanceof String name) {
                out.add(name);
            } else if (child instanceof Ast ast && ast.is("AST_ARRAY")) {
                for (Object element : ast.children()) {
                    if (!(element instanceof Ast item) || !(item.get("value") instanceof String name)) {
                        return false;
                    }
                    out.add(name);
                }
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an interpolation's parts are two expressions, whose FAST_CONCAT evaluates both before converting
     * either; with a literal part, converting each as it is evaluated (a rope's order) is the same.
     */
    static boolean twoExpressions(List<Object> parts) {
        return parts.size() == 2 && parts.get(0) instanceof Ast && parts.get(1) instanceof Ast;
    }

    /** The parts of an interpolated string that php-src's rope keeps: all but empty literals. */
    static List<Object> interpolated(Ast node) {
        List<Object> parts = new ArrayList<>();
        for (Object part : node.children()) {
            if (!(part instanceof String text && text.isEmpty()) && !(part instanceof byte[] bytes
                    && bytes.length == 0)) {
                parts.add(part);
            }
        }
        return parts;
    }

    private ClassInfo requireClass(Ast node) {
        if (classInfo == null) {
            throw unsupported(node, "self/parent outside a class");
        }
        return classInfo;
    }

    private Object foldedOperand(Object value) {
        if (!(value instanceof Ast node)) {
            return value instanceof Long || value instanceof Double ? value : null;
        }
        return switch (node.kind) {
            case "AST_CONST", "AST_UNARY_OP", "AST_MAGIC_CONST" -> fold(node);
            case "AST_BINARY_OP" -> foldedArithmetic(node);
            default -> null;
        };
    }

    private TruffleString literal(Object value) {
        if (value instanceof String s) {
            return language.intern(Str.of(s));
        }
        if (value instanceof byte[] bytes) {
            return language.intern(Str.of(bytes));
        }
        throw new PhpError("not a string literal: " + value);
    }
}
