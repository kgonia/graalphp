package pl.codeloom.php.parser;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.FileRootNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.nodes.ThunkRootNode;
import pl.codeloom.php.nodes.call.ArgumentsNode;
import pl.codeloom.php.nodes.call.CallableCallNode;
import pl.codeloom.php.nodes.call.CallableConvertNodes;
import pl.codeloom.php.nodes.call.ClosureNode;
import pl.codeloom.php.nodes.call.DynamicMethodCallNode;
import pl.codeloom.php.nodes.call.FrameArgNode;
import pl.codeloom.php.nodes.call.FrameArgsNode;
import pl.codeloom.php.nodes.call.FunctionCallNode;
import pl.codeloom.php.nodes.call.FunctionRootNode;
import pl.codeloom.php.nodes.call.MethodCallNode;
import pl.codeloom.php.nodes.call.NewNode;
import pl.codeloom.php.nodes.call.StaticCallNode;
import pl.codeloom.php.nodes.expr.ArithmeticNodesFactory;
import pl.codeloom.php.nodes.expr.ArrayLiteralNode;
import pl.codeloom.php.nodes.expr.AssignNodes;
import pl.codeloom.php.nodes.expr.CompareNodes;
import pl.codeloom.php.nodes.expr.ConstNode;
import pl.codeloom.php.nodes.expr.ConstantNodes;
import pl.codeloom.php.nodes.expr.ConcatChainNode;
import pl.codeloom.php.nodes.expr.IncludeNode;
import pl.codeloom.php.nodes.expr.InterpolationNode;
import pl.codeloom.php.nodes.expr.LogicNodes;
import pl.codeloom.php.nodes.expr.MatchNode;
import pl.codeloom.php.nodes.expr.TemporaryNode;
import pl.codeloom.php.parser.zend.ZendParser;
import pl.codeloom.php.nodes.expr.TestNodes;
import pl.codeloom.php.nodes.expr.ThrowExprNode;
import pl.codeloom.php.nodes.expr.UnaryNodes;
import pl.codeloom.php.nodes.intrinsics.Intrinsics;
import pl.codeloom.php.nodes.stmt.BasicStmts;
import pl.codeloom.php.nodes.stmt.DeclareNodes;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.nodes.stmt.GeneratorNodes;
import pl.codeloom.php.nodes.stmt.GotoNodes;
import pl.codeloom.php.nodes.stmt.LoopNodes;
import pl.codeloom.php.nodes.stmt.TryNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.nodes.vars.CompactNode;
import pl.codeloom.php.nodes.vars.DimNode;
import pl.codeloom.php.nodes.vars.DynamicCompactNode;
import pl.codeloom.php.nodes.vars.ExtractNode;
import pl.codeloom.php.nodes.vars.FileVarNode;
import pl.codeloom.php.nodes.vars.ObjectVarsNode;
import pl.codeloom.php.nodes.vars.GlobalVarNode;
import pl.codeloom.php.nodes.vars.GlobalsArrayNode;
import pl.codeloom.php.nodes.vars.GlobalsDimNode;
import pl.codeloom.php.nodes.vars.ListAssignNode;
import pl.codeloom.php.nodes.vars.LocalVarNode;
import pl.codeloom.php.nodes.vars.PropNode;
import pl.codeloom.php.nodes.vars.StaticPropNode;
import pl.codeloom.php.nodes.vars.ThisNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpBailout;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Phases;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
    private static final int PARAM_REF = 8;
    private static final int PARAM_VARIADIC = 16;
    /** Visibility or readonly on a constructor parameter: a promoted property. */
    private static final int PROMOTED = 7 | 128;
    private static final int FUNC_RETURNS_REF = 4096;
    private static final int CLASS_INTERFACE = 1;
    private static final int CLASS_TRAIT = 2;
    private static final int CLASS_ABSTRACT = 64;
    private static final int ARRAY_ELEM_REF = 1;
    private static final int CLOSURE_USE_REF = 1;
    private static final int ARRAY_SYNTAX_LIST = 1;
    private static final int EXEC_EVAL = 1;
    private static final int EXEC_INCLUDE_ONCE = 4;
    private static final int EXEC_REQUIRE = 8;
    private static final int EXEC_REQUIRE_ONCE = 16;
    private static final int BINARY_PIPE = 261;
    /** How PHP 8.4 names a closure: {closure:the enclosing function or file:its line}. */
    private static final String CLOSURE_PREFIX = "{closure:";

    /** The class whose members are being translated, for self::, parent:: and __CLASS__. */
    record ClassInfo(String name, String parentName) {
    }

    /** A function's local variables: name to frame slot. */
    private static final class Scope {
        final FrameDescriptor.Builder frame = FrameDescriptor.newBuilder().defaultValueIllegal();
        final Map<String, Integer> slots = new HashMap<>();
        /** The slot keeping the call's IncludeScope (one per call, shared by its includes); -1 until needed. */
        int includeScopeSlot = -1;

        int slot(String name) {
            Integer slot = slots.get(name);
            if (slot == null) {
                slot = frame.addSlot(FrameSlotKind.Illegal, name, null);
                slots.put(name, slot);
            }
            return slot;
        }
    }

    private final PhpLanguage language;
    private final String path;
    /** The PHP file's source, for root nodes' source sections (profilers, stack traces); null if unreadable. */
    private final Source source;
    private final boolean internal;
    private final ClassInfo classInfo;
    private final String functionName;
    private final PhpFunction function;
    private final Scope scope;
    /** In a generator function's body: the frame slot of its Generator state; -1 elsewhere. */
    private int generatorSlot = -1;
    /** In the body of a function returning by reference (not a generator, whose `&` is for its yields). */
    private boolean returnsRef;
    /** Numbers hidden temporaries ("\0key0", ...), which no PHP variable name can clash with. */
    private int hiddenCounter;
    /** Frame slots of a file's top-level code: only hidden ones (its variables are globals, not slots). */
    private final Scope fileSlots = new Scope();
    /** The namespace and imports in effect: updated by a file's namespace and use statements as they come. */
    private Names names;
    /** The lines of the file's arrow functions right of a `|>` without parentheses; read when first needed. */
    private Set<Integer> unparenthesizedArrowLines;

    /** internal: the file is the prelude's, which stands for php-src's internal code (RootNode.isInternal). */
    public Translator(PhpLanguage language, String path, Source source, boolean internal) {
        this(language, path, source, internal, null, "", null, null, Names.GLOBAL);
    }

    private Translator(PhpLanguage language, String path, Source source, boolean internal, ClassInfo classInfo,
                       String functionName, PhpFunction function, Scope scope, Names names) {
        this.language = language;
        this.path = path;
        this.source = source;
        this.internal = internal;
        this.classInfo = classInfo;
        this.functionName = functionName;
        this.function = function;
        this.scope = scope;
        this.names = names;
    }

    /**
     * A translator for one function's constant expressions (literals, folded constants, constant arrays, magic
     * constants), which the bytecode translator takes from the tree's translation.
     */
    static Translator forConstants(PhpLanguage language, String path, Source source, boolean internal,
                                   ClassInfo classInfo, String functionName, PhpFunction function, Names names) {
        return new Translator(language, path, source, internal, classInfo, functionName, function, new Scope(),
                names);
    }

    SourceSection lineSection(int line) {
        return source == null || line < 1 || line > source.getLineCount() ? null : source.createSection(line);
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

    /**
     * A file's top-level statements: function declarations are hoisted into the prologue, namespace and use
     * statements change how the following names resolve, and a braced namespace's statements come in place.
     */
    private void fileStatements(Ast list, List<StmtNode> prologue, List<StmtNode> body) {
        for (Object child : list.children()) {
            if (!(child instanceof Ast statement)) {
                continue;
            }
            switch (statement.kind) {
                case "AST_FUNC_DECL": {
                    StmtNode declaration = new DeclareNodes.DeclareFunctionNode(declareFunction(statement, null), true);
                    declaration.setLine(statement.line);
                    prologue.add(declaration);
                    break;
                }
                case "AST_NAMESPACE":
                    names = Names.namespace((String) statement.get("name"));
                    if (statement.get("stmts") != null) {
                        fileStatements(statement.node("stmts"), prologue, body);
                        names = Names.GLOBAL;
                    }
                    break;
                case "AST_USE":
                    for (Object element : statement.children()) {
                        Ast use = (Ast) element;
                        names = names.use(statement.flags, (String) use.get("name"), (String) use.get("alias"));
                    }
                    break;
                case "AST_GROUP_USE": {
                    String prefix = (String) statement.get("prefix");
                    Ast uses = statement.node("uses");
                    for (Object element : uses.children()) {
                        Ast use = (Ast) element;
                        int kind = use.flags != 0 ? use.flags : uses.flags;
                        names = names.use(kind, prefix + "\\" + use.get("name"), (String) use.get("alias"));
                    }
                    break;
                }
                default:
                    body.add(statement(statement));
            }
        }
    }

    public FileRootNode translateFile(Ast file) {
        List<StmtNode> prologue = new ArrayList<>();
        List<StmtNode> body = new ArrayList<>();
        fileStatements(file, prologue, body);
        return new FileRootNode(language, fileSlots.frame.build(), path, internal, block(prologue), block(body),
                source == null ? null : source.createSection(0, source.getLength()));
    }

    /** Where a function or class is declared: null in internal code, as php-src's internal ones have no file. */
    private SourceLocation declaredAt(Ast node) {
        return internal ? null : new SourceLocation(path, node.line);
    }

    /** A statement list, also of one: a block records which statement a runtime error left (StmtNode.surfaced). */
    private static StmtNode block(List<StmtNode> statements) {
        return new BasicStmts.BlockNode(statements.toArray(new StmtNode[0]));
    }

    // ---- statements ----

    private StmtNode statements(Ast list) {
        if (list == null) {
            return new BasicStmts.NopNode();
        }
        if (!list.is("AST_STMT_LIST")) {
            return block(List.of(statement(list)));
        }
        if (generatorSlot >= 0 && containsYield(list)) {
            return resumableStatement(list);
        }
        List<StmtNode> result = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        for (Object child : list.children()) {
            if (child instanceof Ast statement) {
                if (statement.is("AST_LABEL")) {
                    labels.add((String) statement.get("name"));
                    targets.add(result.size());
                    continue;
                }
                result.add(statement(statement));
            }
        }
        if (!labels.isEmpty()) {
            return new GotoNodes.LabeledBlockNode(result.toArray(new StmtNode[0]), labels.toArray(new String[0]),
                    targets.stream().mapToInt(Integer::intValue).toArray());
        }
        return block(result);
    }

    /** A statement, tagged with its source line for line-level tools. */
    private StmtNode statement(Ast node) {
        StmtNode statement = statementNode(node);
        if (!node.is("AST_STMT_LIST") && statement.getClass() != BasicStmts.BlockNode.class) {
            statement.setLine(node.line);
        }
        return statement;
    }

    private StmtNode statementNode(Ast node) {
        if (generatorSlot >= 0 && containsYield(node)) {
            StmtNode resumable = resumableStatement(node);
            if (resumable != null) {
                return resumable;
            }
        }
        switch (node.kind) {
            case "AST_STMT_LIST":
                return statements(node);
            case "AST_ECHO":
                return new BasicStmts.EchoNode(expression(node.get("expr")));
            case "AST_IF":
                return ifChain(node, 0);
            case "AST_WHILE":
                return new LoopNodes.WhileNode(expression(node.get("cond")), statements(node.node("stmts")), true);
            case "AST_DO_WHILE":
                return new LoopNodes.WhileNode(expression(node.get("cond")), statements(node.node("stmts")), false);
            case "AST_FOR":
                return new LoopNodes.ForNode(expressionList(node.node("init")), expressionList(node.node("cond")),
                        expressionList(node.node("loop")), statements(node.node("stmts")));
            case "AST_FOREACH":
                return foreach(node);
            case "AST_SWITCH":
                return switchStatement(node);
            case "AST_GOTO":
                return new GotoNodes.GotoNode((String) node.get("label"));
            case "AST_LABEL":
                return new BasicStmts.NopNode();
            case "AST_BREAK":
            case "AST_CONTINUE":
                return new BasicStmts.BreakNode(node.get("depth") instanceof Long depth ? depth.intValue() : 1,
                        node.is("AST_CONTINUE"));
            case "AST_RETURN":
                return returnStatement(node.get("expr"));
            case "AST_GLOBAL":
                return globalStatement(node);
            case "AST_STATIC":
                return staticStatement(node);
            case "AST_UNSET":
                if (isGlobalsElement(node.get("var")) && node.node("var").get("dim") == null) {
                    throw compileError(node.line, "Cannot use [] for unsetting");
                }
                return new BasicStmts.UnsetNode(place(node.get("var")));
            case "AST_THROW":
                return new BasicStmts.ThrowNode(expression(node.get("expr")));
            case "AST_TRY":
                return tryStatement(node);
            case "AST_FUNC_DECL": {
                PhpFunction declared = declareFunction(node, null);
                return new DeclareNodes.DeclareFunctionNode(declared, false);
            }
            case "AST_CLASS":
                return classDeclaration(node);
            case "AST_CONST_DECL":
                return constDeclaration(node);
            case "AST_DECLARE":
                return new BasicStmts.NopNode();
            case "AST_INLINE_HTML":
                return new BasicStmts.EchoNode(new ConstNode(literal(node.get("value"))));
            default:
                return new BasicStmts.ExprStmtNode(expression(node));
        }
    }

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

    /**
     * The resumable variant of a statement on the path to a yield (see {@link GeneratorNodes}); null for a
     * statement that is simply evaluated again on resume (an expression, echo, return).
     */
    private StmtNode resumableStatement(Ast node) {
        switch (node.kind) {
            case "AST_STMT_LIST": {
                List<StmtNode> result = new ArrayList<>();
                for (Object child : node.children()) {
                    if (child instanceof Ast statement) {
                        result.add(statement(statement));
                    }
                }
                return new GeneratorNodes.BlockNode(result.toArray(new StmtNode[0]), hiddenSlot("block"));
            }
            case "AST_IF":
                return resumableIf(node, 0);
            case "AST_WHILE":
            case "AST_DO_WHILE":
                return new GeneratorNodes.WhileNode(expression(node.get("cond")), statements(node.node("stmts")),
                        node.is("AST_WHILE"), hiddenSlot("while"));
            case "AST_FOR":
                return new GeneratorNodes.ForNode(expressionList(node.node("init")),
                        expressionList(node.node("cond")), expressionList(node.node("loop")),
                        statements(node.node("stmts")), hiddenSlot("for"));
            case "AST_FOREACH": {
                Ast value = node.node("value");
                if (value.is("AST_REF") || value.is("AST_ARRAY")) {
                    throw unsupported(node, "foreach by reference or into list() around a yield");
                }
                return new GeneratorNodes.ForeachNode(expression(node.get("expr")),
                        node.get("key") == null ? null : place(node.get("key")), place(value),
                        statements(node.node("stmts")), hiddenSlot("foreach"), hiddenSlot("subject"),
                        hiddenSlot("position"));
            }
            case "AST_TRY":
                return resumableTry(node);
            case "AST_SWITCH":
                throw unsupported(node, "yield inside switch");
            default:
                return null;
        }
    }

    /**
     * `return expr;`. In a function returning by reference (zend_compile_return), a variable, element or property
     * returns its reference and a call the reference its callee returned; anything else returns its value.
     */
    private StmtNode returnStatement(Object expr) {
        if (!returnsRef) {
            return new BasicStmts.ReturnNode(expr == null ? null : expression(expr));
        }
        if (expr instanceof Ast ast && (isPlace(ast) || ast.is("AST_NULLSAFE_PROP")) && !isThis(ast)
                && !isGlobals(ast)) {
            if (deepestNullsafe(ast) != null) {
                throw compileError(ast.line, "Cannot take reference of a nullsafe chain");
            }
            return new BasicStmts.ReturnRefNode(place(ast));
        }
        return new BasicStmts.ReturnRefValueNode(expr == null ? null : expression(expr), !isThis(expr));
    }

    /** A variable, element or property (zend_is_variable): what `&` can take the reference of. */
    private static boolean isPlace(Ast node) {
        return node.is("AST_VAR") || node.is("AST_DIM") || node.is("AST_PROP") || node.is("AST_STATIC_PROP");
    }

    /** A function or method call (zend_is_call): a function returning by reference gives its reference. */
    static boolean isCall(Object value) {
        return value instanceof Ast node && (node.is("AST_CALL") || node.is("AST_METHOD_CALL")
                || node.is("AST_NULLSAFE_METHOD_CALL") || node.is("AST_STATIC_CALL"));
    }

    private StmtNode resumableIf(Ast node, int index) {
        if (index >= node.size()) {
            return null;
        }
        Ast element = (Ast) node.child(index);
        Object condition = element.get("cond");
        StmtNode body = statements(element.node("stmts"));
        if (condition == null) {
            return body;
        }
        return new GeneratorNodes.IfNode(expression(condition), body, resumableIf(node, index + 1),
                hiddenSlot("if"));
    }

    private StmtNode resumableTry(Ast node) {
        StmtNode body = statements(node.node("try"));
        Ast catches = node.node("catches");
        int count = catches == null ? 0 : catches.size();
        String[][] types = new String[count][];
        LValueNode[] targets = new LValueNode[count];
        StmtNode[] bodies = new StmtNode[count];
        for (int i = 0; i < count; i++) {
            Ast handler = (Ast) catches.child(i);
            Ast classes = handler.node("class");
            types[i] = new String[classes.size()];
            for (int j = 0; j < classes.size(); j++) {
                types[i][j] = Str.lower(className((Ast) classes.child(j)));
            }
            targets[i] = handler.get("var") == null ? null : place(handler.get("var"));
            bodies[i] = statements(handler.node("stmts"));
        }
        StmtNode finallyBody = node.get("finally") == null ? null : statements(node.node("finally"));
        return new GeneratorNodes.TryNode(body, types, targets, bodies, finallyBody, hiddenSlot("try"),
                hiddenSlot("leaving"));
    }

    private StmtNode ifChain(Ast node, int index) {
        if (index >= node.size()) {
            return null;
        }
        Ast element = (Ast) node.child(index);
        Object condition = element.get("cond");
        StmtNode body = statements(element.node("stmts"));
        if (condition == null) {
            return body;
        }
        return new BasicStmts.IfNode(expression(condition), body, ifChain(node, index + 1));
    }

    private StmtNode foreach(Ast node) {
        ExprNode subject = expression(node.get("expr"));
        Ast value = node.node("value");
        boolean byRef = value.is("AST_REF");
        if (byRef) {
            value = value.node("var");
        }
        // zend_compile_foreach: the value's target, the key's, then the body
        ListAssignNode list = value.is("AST_ARRAY") ? listTarget(value) : null;
        LValueNode target = list == null ? place(value) : null;
        LValueNode key = node.get("key") == null ? null : place(node.get("key"));
        StmtNode body = statements(node.node("stmts"));
        return new ForeachNode(subject, key, target, list, body, byRef && list == null, hiddenSlot("array"),
                hiddenSlot("position"), lexicalClass());
    }

    private StmtNode switchStatement(Ast node) {
        Ast list = node.node("stmts");
        ExprNode[] cases = new ExprNode[list.size()];
        StmtNode[] bodies = new StmtNode[list.size()];
        int defaultIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            Ast switchCase = (Ast) list.child(i);
            Object condition = switchCase.get("cond");
            if (condition == null) {
                defaultIndex = i;
            } else {
                cases[i] = expression(condition);
            }
            bodies[i] = statements(switchCase.node("stmts"));
        }
        return new LoopNodes.SwitchNode(expression(node.get("cond")), cases, bodies, defaultIndex);
    }

    private StmtNode globalStatement(Ast node) {
        Ast variable = node.node("var");
        String name = variable.get("name") instanceof String s ? s : null;
        if (name == null) {
            throw unsupported(node, "dynamic global variable name");
        }
        if (scope == null) {
            return new BasicStmts.GlobalNode(new FileVarNode(name), name, true);
        }
        return new BasicStmts.GlobalNode(new LocalVarNode(scope.slot(name), name), name, false);
    }

    private StmtNode staticStatement(Ast node) {
        String name = (String) node.node("var").get("name");
        if (scope == null || function == null) {
            throw unsupported(node, "static variable outside a function");
        }
        ExprNode initial = node.get("default") == null ? null : expression(node.get("default"));
        return new BasicStmts.StaticVarNode(new LocalVarNode(scope.slot(name), name), initial, function, name);
    }

    private StmtNode tryStatement(Ast node) {
        StmtNode body = statements(node.node("try"));
        Ast catches = node.node("catches");
        int count = catches == null ? 0 : catches.size();
        String[][] types = new String[count][];
        LValueNode[] targets = new LValueNode[count];
        StmtNode[] bodies = new StmtNode[count];
        for (int i = 0; i < count; i++) {
            Ast handler = (Ast) catches.child(i);
            Ast classes = handler.node("class");
            types[i] = new String[classes.size()];
            for (int j = 0; j < classes.size(); j++) {
                types[i][j] = Str.lower(className((Ast) classes.child(j)));
            }
            targets[i] = handler.get("var") == null ? null : place(handler.get("var"));
            bodies[i] = statements(handler.node("stmts"));
        }
        StmtNode finallyBody = node.get("finally") == null ? null : statements(node.node("finally"));
        return new TryNode(body, types, targets, bodies, finallyBody);
    }

    private StmtNode constDeclaration(Ast node) {
        List<StmtNode> result = new ArrayList<>();
        for (Object child : node.children()) {
            Ast element = (Ast) child;
            if (!element.is("AST_CONST_ELEM")) {
                continue; // #[attributes] on the declaration
            }
            String name = names.declared((String) element.get("name"));
            ExprNode value = expression(element.get("value"));
            result.add(new BasicStmts.ExprStmtNode(new DefineNode(name, value)));
        }
        return block(result);
    }

    /** `const NAME = value;` at top level. */
    private static final class DefineNode extends ExprNode {
        private final String name;
        @Child private ExprNode value;

        DefineNode(String name, ExprNode value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            Object result = value.execute(frame);
            PhpContext.get(this).defineConstant(name, result);
            return result;
        }
    }

    // ---- functions ----

    /** Builds a PhpFunction whose body is translated on first call. */
    PhpFunction declareFunction(Ast node, ClassInfo owner) {
        String name = owner == null ? names.declared((String) node.get("name")) : (String) node.get("name");
        Names declaredNames = names;
        Ast paramList = node.node("params");
        PhpFunction.Param[] params = params(paramList);
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
        self[0].setDeclaration(modifiers, typeString(node.node("returnType"), false),
                node.get("docComment") instanceof String doc ? doc : null, node.endLine);
        return self[0];
    }

    private PhpFunction.Param[] params(Ast paramList) {
        PhpFunction.Param[] params = new PhpFunction.Param[paramList.size()];
        for (int i = 0; i < params.length; i++) {
            Ast param = (Ast) paramList.child(i);
            Ast type = param.node("type");
            Object defaultValue = param.get("default") == null ? null : param.get("default");
            boolean nullable = isNullable(type) || isNullConstant(defaultValue);
            params[i] = new PhpFunction.Param((String) param.get("name"), (param.flags & PARAM_REF) != 0,
                    (param.flags & PARAM_VARIADIC) != 0, defaultValue == null ? null : (Object) Boolean.TRUE,
                    coerceType(type), nullable, typeString(type, isNullConstant(defaultValue)));
        }
        return params;
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

    private static boolean isNullConstant(Object value) {
        return value instanceof Ast ast && ast.is("AST_CONST") && "null".equalsIgnoreCase(ast.node("name") == null
                ? null : (String) ast.node("name").get("name"));
    }

    /** The scalar type a declared type coerces to: 's', 'i', 'f', 'b', or 0 for anything else. */
    static char coerceType(Ast type) {
        if (type == null) {
            return 0;
        }
        if (type.is("AST_NULLABLE_TYPE")) {
            return coerceType(type.node("type"));
        }
        if (!type.is("AST_TYPE")) {
            return 0;
        }
        switch (type.flags) {
            case 6:
                return 's';
            case 4:
                return 'i';
            case 5:
                return 'f';
            case 18:
                return 'b';
            default:
                return 0;
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
        boolean generator = !node.is("AST_ARROW_FUNC") && containsYield(stmts);
        if (language.bytecode()) {
            RootCallTarget bytecode = BytecodeTranslator.translate(language, path, source, internal, node, stmts,
                    owner, target, display, params, captures, captureByRef, declaredNames, generator);
            if (bytecode != null) {
                return bytecode;
            }
        }
        Scope bodyScope = new Scope();
        Translator body = new Translator(language, path, source, internal, owner, display, target, bodyScope,
                declaredNames);
        Ast paramList = node.node("params");
        int[] paramSlots = new int[params.length];
        ExprNode[] defaults = new ExprNode[params.length];
        for (int i = 0; i < params.length; i++) {
            paramSlots[i] = bodyScope.slot(params[i].name());
            Object defaultValue = ((Ast) paramList.child(i)).get("default");
            defaults[i] = defaultValue == null ? null : body.expression(defaultValue);
        }
        int[] captureSlots = new int[captures.length];
        for (int i = 0; i < captures.length; i++) {
            captureSlots[i] = bodyScope.slot(captures[i]);
        }
        if (generator) {
            body.generatorSlot = bodyScope.slot("\0generator");
        }
        body.returnsRef = target.returnsRef() && !generator;
        StmtNode statements;
        if (node.is("AST_ARROW_FUNC")) {
            Ast returned = stmts.is("AST_RETURN") ? stmts : null;
            statements = returned != null ? body.statement(returned) : body.returnStatement(stmts);
        } else {
            statements = body.statements(stmts);
        }
        if (owner != null && name.equalsIgnoreCase("__construct")) {
            List<StmtNode> promoted = new ArrayList<>();
            for (int i = 0; i < params.length; i++) {
                if ((((Ast) paramList.child(i)).flags & PROMOTED) != 0) {
                    promoted.add(new BasicStmts.PromoteNode(Str.sourceKey(params[i].name()),
                            new LocalVarNode(paramSlots[i], params[i].name())));
                }
            }
            if (!promoted.isEmpty()) {
                promoted.add(statements);
                statements = new BasicStmts.BlockNode(promoted.toArray(new StmtNode[0]));
            }
        }
        Ast returnType = node.node("returnType");
        FrameDescriptor descriptor = bodyScope.frame.build();
        if (generator) {
            FunctionRootNode root = new FunctionRootNode(language, descriptor, display, params, paramSlots,
                    defaults, captureSlots, captureByRef, (char) 0, false, new BasicStmts.NopNode(), path, internal,
                    lineSection(node.line));
            root.setScope(owner == null ? null : owner.name());
            root.setGeneratorBody(new GeneratorNodes.BodyRootNode(language, descriptor, statements, root)
                    .getCallTarget(), body.generatorSlot);
            return root.getCallTarget();
        }
        FunctionRootNode root = new FunctionRootNode(language, descriptor, display, params,
                paramSlots, defaults, captureSlots, captureByRef, coerceType(returnType), isNullable(returnType),
                statements, path, internal, lineSection(node.line));
        root.setScope(owner == null ? null : owner.name());
        if (body.returnsRef) {
            root.setReturnsRef(node.endLine);
        }
        return root.getCallTarget();
    }

    private ExprNode closure(Ast node) {
        ClosureParts parts = closureParts(node, name -> scope == null || scope.slots.containsKey(name));
        ExprNode[] captureNodes = new ExprNode[parts.captures().length];
        for (int i = 0; i < captureNodes.length; i++) {
            captureNodes[i] = variable(parts.captures()[i]);
        }
        ClassRefNode scopeRef = classInfo == null ? null : ClassRefNode.named(classInfo.name());
        return new ClosureNode(parts.function(), captureNodes, parts.byRef(), parts.isStatic(), scopeRef);
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
        PhpFunction.Param[] params = params(node.node("params"));
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
        self[0].setDeclaredAt(declaredAt(node));
        self[0].setDeclaration(0, typeString(node.node("returnType"), false),
                node.get("docComment") instanceof String doc ? doc : null, node.endLine);
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
                        : expression(initial).constantValue());
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

    private StmtNode classDeclaration(Ast node) {
        return new DeclareNodes.DeclareClassNode(classTemplate(node, names.declared((String) node.get("name"))));
    }

    /** `new class(...) {...}`, named as php-src names it: "class@anonymous", NUL, then file:line$n. */
    private ExprNode newAnonymousClass(Ast node, Ast classNode) {
        ClassTemplate template = anonymousClass(classNode);
        return new DeclareNodes.NewAnonymousClassNode(template,
                new NewNode(ClassRefNode.named(template.getName()), arguments(node.node("args"))));
    }

    /** An anonymous class's template, under a name of its own (the bytecode translator's too). */
    ClassTemplate anonymousClass(Ast classNode) {
        String parent = classNode.get("extends") == null ? "class" : className(classNode.node("extends"));
        String name = parent + "@anonymous\0" + path + ":" + classNode.line + "$"
                + Integer.toHexString(language.nextAnonymousClass());
        return classTemplate(classNode, name);
    }

    ClassTemplate classTemplate(Ast node, String name) {
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
        ClassInfo info = new ClassInfo(name, parentName);
        Translator inClass = new Translator(language, path, source, internal, info, "", null, null, names);
        List<ClassTemplate.Member> members = new ArrayList<>();
        String unsupported = null;
        try {
            for (Object child : node.node("stmts").children()) {
                inClass.addMember(members, (Ast) child, name, info);
            }
        } catch (PhpError error) {
            // the file still loads; the declaration fails if it runs, after resolving its parent and interfaces
            unsupported = error.getMessage();
        }
        ClassTemplate template = new ClassTemplate(name, parentName, interfaces, isInterface, isAbstract, members,
                unsupported);
        template.setDeclaredAt(declaredAt(node));
        template.setDeclaration(node.flags, node.get("docComment") instanceof String doc ? doc : null, node.endLine);
        return template;
    }

    /** A trait: its members are translated for each class that uses it, on that class's first declaration. */
    private ClassTemplate traitTemplate(Ast node, String name) {
        Ast statements = node.node("stmts");
        Names declaredNames = names;
        ClassTemplate.TraitBody body = (className, parentName) -> {
            ClassInfo info = new ClassInfo(className, parentName);
            Translator inClass = new Translator(language, path, source, internal, info, "", null, null,
                    declaredNames);
            List<ClassTemplate.Member> members = new ArrayList<>();
            for (Object child : statements.children()) {
                inClass.addMember(members, (Ast) child, className, info);
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
                            adaptation.get("alias") instanceof String alias ? alias : null));
                }
            }
        }
        return new ClassTemplate.TraitUse(traits, aliases, exclusions);
    }

    private void addMember(List<ClassTemplate.Member> members, Ast member, String className, ClassInfo info) {
        switch (member.kind) {
            case "AST_METHOD": {
                PhpFunction method = declareFunction(member, info);
                List<PhpClass.PropertyDecl> promoted = new ArrayList<>();
                if (method.getName().equalsIgnoreCase("__construct")) {
                    for (Object child : member.node("params").children()) {
                        Ast param = (Ast) child;
                        if ((param.flags & PROMOTED) != 0) {
                            promoted.add(new PhpClass.PropertyDecl(Str.sourceKey((String) param.get("name")),
                                    PhpNull.NULL, PhpClass.PropertyDecl.visibilityOf(param.flags)));
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
                    members.add(new ClassTemplate.Constant((String) element.get("name"),
                            constantOrDeferred(element.get("value"))));
                }
                break;
            }
            case "AST_PROP_GROUP": {
                boolean isStatic = (member.flags & MODIFIER_STATIC) != 0;
                int visibility = PhpClass.PropertyDecl.visibilityOf(member.flags);
                for (Object child : member.node("props").children()) {
                    Ast element = (Ast) child;
                    String propertyName = Str.sourceKey((String) element.get("name"));
                    Object initial = element.get("default") == null ? PhpNull.NULL
                            : constantOrDeferred(element.get("default"));
                    members.add(new ClassTemplate.Property(propertyName, initial, visibility, isStatic));
                }
                break;
            }
            case "AST_USE_TRAIT":
                members.add(traitUse(member));
                break;
            default:
                throw unsupported(member, "class member");
        }
    }

    /** A constant expression's value if it folds, else its initializer, evaluated per class on first use. */
    private Object constantOrDeferred(Object expression) {
        ExprNode node = expression(expression);
        Object value = node.constantValue();
        if (value != null) {
            return value;
        }
        return new ThunkRootNode(language, node);
    }

    /** A class name as written, resolved against the namespace and imports (self, parent, static unchanged). */
    private String className(Ast name) {
        return names.className((String) name.get("name"), name.flags);
    }

    /** A class reference by name: self and parent resolve to the enclosing class names, static to LSB. */
    private ClassRefNode classRef(Object nameNode) {
        if (!(nameNode instanceof Ast ast) || !ast.is("AST_NAME")) {
            return ClassRefNode.dynamic(expression(nameNode));
        }
        String name = className(ast);
        switch (Str.lower(name)) {
            case "self":
                return ClassRefNode.named(requireClass(ast).name());
            case "parent":
                return ClassRefNode.named(requireClass(ast).parentName());
            case "static":
                return ClassRefNode.lateStatic(classInfo == null ? null : classInfo.name());
            default:
                return ClassRefNode.named(name);
        }
    }

    private ClassInfo requireClass(Ast node) {
        if (classInfo == null) {
            throw unsupported(node, "self/parent outside a class");
        }
        return classInfo;
    }

    // ---- expressions ----

    private ExprNode[] expressionList(Ast list) {
        if (list == null) {
            return new ExprNode[0];
        }
        ExprNode[] result = new ExprNode[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = expression(list.child(i));
        }
        return result;
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

    ExprNode expression(Object value) {
        if (value instanceof String || value instanceof byte[]) {
            return new ConstNode(literal(value));
        }
        if (value instanceof Long || value instanceof Double) {
            return new ConstNode(value);
        }
        if (value == null) {
            return new ConstNode(PhpNull.NULL);
        }
        Ast node = (Ast) value;
        Ast nullsafe = deepestNullsafe(node);
        if (nullsafe != null) {
            return nullsafeChain(node, nullsafe, false);
        }
        switch (node.kind) {
            case "TEMPORARY":
                return new TemporaryNode.Read(node.flags);
            case "AST_VAR":
            case "AST_DIM":
            case "AST_PROP":
            case "AST_STATIC_PROP":
                if (isThis(node)) {
                    return new ThisNode();
                }
                if (isGlobals(node)) {
                    // $GLOBALS read as a whole: a value, not a place
                    return new GlobalsArrayNode();
                }
                return place(node);
            case "AST_CONST":
                return constant(node);
            case "AST_CLASS_CONST":
                return classConstant(node);
            case "AST_CLASS_NAME":
                return classNameExpr(node);
            case "AST_MAGIC_CONST":
                return magicConstant(node);
            case "AST_ARRAY":
                return arrayLiteral(node);
            case "AST_ENCAPS_LIST":
                return interpolation(node);
            case "AST_ASSIGN":
                return assignment(node);
            case "AST_ASSIGN_REF": {
                LValueNode target = place(node.get("var"));
                if (deepestNullsafe(node.get("expr")) != null) {
                    throw compileError(node.line, "Cannot take reference of a nullsafe chain");
                }
                if (isCall(node.get("expr"))) {
                    return new AssignNodes.CallRefAssignNode(target, expression(node.get("expr")));
                }
                if (isGlobals(node.get("expr"))) {
                    throw compileError(node.line, "Cannot acquire reference to $GLOBALS");
                }
                return new AssignNodes.RefAssignNode(target, place(node.get("expr")));
            }
            case "AST_ASSIGN_OP":
                return compoundAssignment(node);
            case "AST_BINARY_OP":
                return binary(node);
            case "AST_UNARY_OP":
                return unary(node);
            case "AST_PRE_INC":
            case "AST_PRE_DEC":
            case "AST_POST_INC":
            case "AST_POST_DEC": {
                boolean increment = node.is("AST_PRE_INC") || node.is("AST_POST_INC");
                boolean prefix = node.is("AST_PRE_INC") || node.is("AST_PRE_DEC");
                return readWritten(node.get("var"), target -> AssignNodes.IncDecNode.create(target, increment,
                        prefix));
            }
            case "AST_CONDITIONAL":
                return new LogicNodes.TernaryNode(expression(node.get("cond")),
                        node.get("true") == null ? null : expression(node.get("true")), expression(node.get("false")));
            case "AST_ISSET":
            case "AST_EMPTY": {
                Object tested = node.get(node.is("AST_ISSET") ? "var" : "expr");
                // zend_compile_isset_or_empty: $GLOBALS is set and not empty; $GLOBALS[] is refused
                if (isGlobals(tested)) {
                    return new ConstNode(node.is("AST_ISSET"));
                }
                if (isGlobalsElement(tested) && ((Ast) tested).get("dim") == null) {
                    throw compileError(node.line, "Cannot use [] for reading");
                }
                if (node.is("AST_ISSET")) {
                    if (deepestNullsafe(tested) != null) {
                        if (isCall(tested)) {
                            throw compileError(node.line, "Cannot use isset() on the result of an expression"
                                    + " (you can use \"null !== expression\" instead)");
                        }
                        return new TestNodes.IssetNode(new LValueNode[]{(LValueNode) quiet(tested)});
                    }
                    return isThis(tested) ? new ThisNode.IsSetNode()
                            : new TestNodes.IssetNode(new LValueNode[]{place(tested)});
                }
                return isThis(tested) ? new LogicNodes.NotNode(new ThisNode.IsSetNode())
                        : new TestNodes.EmptyNode(quiet(tested));
            }
            case "AST_CAST":
                return UnaryNodes.CastNode.create(expression(node.get("expr")), node.flags);
            case "AST_INSTANCEOF": {
                Ast classNode = node.node("class");
                if (!classNode.is("AST_NAME")) {
                    return new TestNodes.DynamicInstanceofNode(expression(node.get("expr")), expression(classNode));
                }
                String name = className(classNode);
                if (name.equalsIgnoreCase("self") || name.equalsIgnoreCase("static")) {
                    name = requireClass(node).name();
                }
                return new TestNodes.InstanceofNode(expression(node.get("expr")), Str.lower(name));
            }
            case "AST_CALL":
                return call(node);
            case "AST_METHOD_CALL":
                if (isCallableConvert(node)) {
                    return node.get("method") instanceof String method
                            ? new CallableConvertNodes.MethodNode(expression(node.get("expr")), method, null)
                            : new CallableConvertNodes.MethodNode(expression(node.get("expr")), null,
                                    expression(node.get("method")));
                }
                if (!(node.get("method") instanceof String)) {
                    return new DynamicMethodCallNode(placeOrExpression(node.get("expr")), expression(node.get("method")),
                            arguments(node.node("args")));
                }
                return new MethodCallNode(expression(node.get("expr")), methodName(node), arguments(node.node("args")));
            case "AST_STATIC_CALL": {
                Ast classNode = node.node("class");
                String name = classNode != null && classNode.is("AST_NAME") ? Str.lower(className(classNode)) : "";
                boolean forwarding = name.equals("self") || name.equals("parent") || name.equals("static");
                if (isCallableConvert(node)) {
                    if (!(node.get("method") instanceof String method)) {
                        throw unsupported(node, "dynamic static method name");
                    }
                    return new CallableConvertNodes.StaticMethodNode(classRef(node.get("class")), method, forwarding);
                }
                return new StaticCallNode(classRef(node.get("class")), methodName(node), arguments(node.node("args")),
                        forwarding);
            }
            case "AST_YIELD":
                if (generatorSlot < 0) {
                    throw unsupported(node, "yield outside a function");
                }
                return new GeneratorNodes.YieldNode(node.get("key") == null ? null : expression(node.get("key")),
                        node.get("value") == null ? null : expression(node.get("value")), hiddenSlot("yield"),
                        generatorSlot);
            case "AST_YIELD_FROM":
                if (generatorSlot < 0) {
                    throw unsupported(node, "yield from outside a function");
                }
                return new GeneratorNodes.YieldFromNode(expression(node.get("expr")), hiddenSlot("yield"),
                        hiddenSlot("source"), hiddenSlot("position"), generatorSlot);
            case "AST_NEW":
                if (isCallableConvert(node)) {
                    throw compileError(node.line, "Cannot create Closure for new expression");
                }
                if (node.get("class") instanceof Ast classNode && classNode.is("AST_CLASS")) {
                    return newAnonymousClass(node, classNode);
                }
                return new NewNode(classRef(node.get("class")), arguments(node.node("args")));
            case "AST_CLOSURE":
            case "AST_ARROW_FUNC":
                return closure(node);
            case "AST_INCLUDE_OR_EVAL": {
                if (node.flags == EXEC_EVAL) {
                    throw unsupported(node, "eval");
                }
                String kind = switch (node.flags) {
                    case EXEC_INCLUDE_ONCE -> "include_once";
                    case EXEC_REQUIRE -> "require";
                    case EXEC_REQUIRE_ONCE -> "require_once";
                    default -> "include";
                };
                if (scope == null) {
                    return new IncludeNode(expression(node.get("expr")), kind, null, -1, null);
                }
                if (scope.includeScopeSlot < 0) {
                    scope.includeScopeSlot = hiddenSlot("includeScope");
                }
                return new IncludeNode(expression(node.get("expr")), kind, scope.slots, scope.includeScopeSlot,
                        lexicalClass());
            }
            case "AST_PRINT":
                return new LogicNodes.TernaryNode(new ConstNode(Boolean.TRUE),
                        new PrintNode(expression(node.get("expr"))), new ConstNode(1L));
            case "AST_THROW":
                return new ThrowExprNode(new BasicStmts.ThrowNode(expression(node.get("expr"))));
            case "AST_MATCH":
                return match(node);
            default:
                throw unsupported(node, "expression");
        }
    }

    /**
     * zend_compile_match: the subject, then (after the check that there is at most one default arm) every arm's
     * conditions in order, then the bodies.
     */
    private ExprNode match(Ast node) {
        ExprNode subject = expression(node.get("cond"));
        Ast armList = node.node("stmts");
        int armCount = armList == null ? 0 : armList.size();
        Ast secondDefault = secondDefaultArm(node);
        if (secondDefault != null) {
            throw compileError(secondDefault.line, "Match expressions may only contain one default arm");
        }
        List<ExprNode> conditions = new ArrayList<>();
        List<Integer> armOf = new ArrayList<>();
        List<Object> constants = new ArrayList<>();
        int defaultArm = -1;
        for (int i = 0; i < armCount; i++) {
            Ast arm = (Ast) armList.child(i);
            if (arm.get("cond") == null) {
                defaultArm = i;
                continue;
            }
            for (Object condition : arm.node("cond").children()) {
                ExprNode translated = expression(condition);
                Object constant = translated.constantValue();
                constants.add(constant != null ? constant : foldedArithmetic(condition));
                conditions.add(translated);
                armOf.add(i);
            }
        }
        ExprNode[] bodies = new ExprNode[armCount];
        for (int i = 0; i < armCount; i++) {
            bodies[i] = expression(((Ast) armList.child(i)).get("expr"));
        }
        return new MatchNode(subject, conditions.toArray(new ExprNode[0]),
                armOf.stream().mapToInt(Integer::intValue).toArray(), bodies, defaultArm,
                defaultArm < 0 ? matchErrorLine(node, constants) : 0);
    }

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

    private Object foldedOperand(Object value) {
        if (!(value instanceof Ast node)) {
            return value instanceof Long || value instanceof Double ? value : null;
        }
        return switch (node.kind) {
            case "AST_CONST", "AST_UNARY_OP", "AST_MAGIC_CONST" -> expression(node).constantValue();
            case "AST_BINARY_OP" -> foldedArithmetic(node);
            default -> null;
        };
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

    /** print: echoes and evaluates to 1. */
    private static final class PrintNode extends ExprNode {
        @Child private StmtNode echo;

        PrintNode(ExprNode value) {
            this.echo = new BasicStmts.EchoNode(value);
        }

        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            echo.execute(frame);
            return 1L;
        }
    }

    private String methodName(Ast node) {
        if (node.get("method") instanceof String name) {
            return name;
        }
        throw unsupported(node, "dynamic method name");
    }

    private static boolean isThis(Object value) {
        return value instanceof Ast node && node.is("AST_VAR") && "this".equals(node.get("name"));
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

    /**
     * A chain read with a `?->` (ZEND_JMP_NULL): its object evaluated into a hidden slot, the rest of the chain on
     * it, the `?->` as `->` (with further `?->`s of its own); quiet in isset(), empty() and `??` (BP_VAR_IS).
     */
    private ExprNode nullsafeChain(Ast node, Ast nullsafe, boolean quiet) {
        if (convertInChain(node, nullsafe) instanceof Ast convert) {
            throw compileError(convert.line, "Cannot combine nullsafe operator with Closure creation");
        }
        ExprNode object = expression(nullsafe.get("expr"));
        int slot = hiddenSlot("nullsafe");
        Ast chain = withObject(node, nullsafe, temporary(slot, node));
        return quiet ? new TemporaryNode.IfSet(object, quiet(chain), slot)
                : new TemporaryNode(object, expression(chain), slot, true);
    }

    /** A node standing for the value a TemporaryNode keeps in a hidden slot. */
    private static Ast temporary(int slot, Ast at) {
        return new Ast("TEMPORARY", slot, at.line, at.endLine, null, null);
    }

    /** An operand isset(), empty() or `??` reads quietly: a `?->` chain's parts too. */
    private ExprNode quiet(Object value) {
        Ast nullsafe = deepestNullsafe(value);
        return nullsafe != null ? nullsafeChain((Ast) value, nullsafe, true) : expression(value);
    }

    // ---- places ----

    LValueNode place(Object value) {
        Ast node = (Ast) value;
        if (deepestNullsafe(node) != null) {
            // zend_ensure_writable_variable
            throw compileError(node.line, "Can't use nullsafe operator in write context");
        }
        switch (node.kind) {
            case "AST_VAR": {
                if (!(node.get("name") instanceof String name)) {
                    throw unsupported(node, "variable variable");
                }
                if (name.equals("this")) {
                    throw unsupported(node, "assignment to $this");
                }
                if (name.equals("GLOBALS")) {
                    // zend_ensure_writable_variable
                    throw compileError(node.line,
                            "$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax");
                }
                return (LValueNode) variable(name);
            }
            case "AST_DIM": {
                Object dim = node.get("dim");
                if (isGlobals(node.get("expr"))) {
                    // zend_delayed_compile_dim: the global variable the key names
                    if (dim == null) {
                        throw compileError(node.line, "Cannot append to $GLOBALS");
                    }
                    return new GlobalsDimNode(expression(dim));
                }
                ExprNode container = isThis(node.get("expr")) ? new ThisNode() : placeOrExpression(node.get("expr"));
                return element(container, dim == null ? null : expression(dim));
            }
            case "AST_PROP": {
                ExprNode object = placeOrExpression(node.get("expr"));
                Object prop = node.get("prop");
                if (prop instanceof String name) {
                    return new PropNode(object, Str.sourceKey(name), null, lexicalClass(), scopeInFrame());
                }
                return new PropNode(object, null, expression(prop), lexicalClass(), scopeInFrame());
            }
            case "AST_STATIC_PROP": {
                if (!(node.get("prop") instanceof String name)) {
                    throw unsupported(node, "dynamic static property name");
                }
                return new StaticPropNode(classRef(node.get("class")), Str.sourceKey(name), lexicalClass(),
                        scopeInFrame());
            }
            default:
                throw unsupported(node, "assignment target");
        }
    }

    /** An element of an array (key null for `[]`), its key normalized once when it is a constant. */
    private static DimNode element(ExprNode container, ExprNode key) {
        if (key == null) {
            return new DimNode(container, null, null);
        }
        Object constant = key.constantValue();
        Object normalized = constant == null || constant instanceof PhpArray ? null : ArrayKey.of(constant);
        return new DimNode(container, key, normalized);
    }

    /** `$GLOBALS` itself (is_globals_fetch). */
    private static boolean isGlobals(Object value) {
        return value instanceof Ast node && node.is("AST_VAR") && "GLOBALS".equals(node.get("name"));
    }

    /** `$GLOBALS[...]` (is_global_var_fetch): a global variable, named by the key. */
    private static boolean isGlobalsElement(Object value) {
        return value instanceof Ast node && node.is("AST_DIM") && isGlobals(node.get("expr"));
    }

    /** $this, a place, or any other expression (as a container of a dimension or property). */
    private ExprNode placeOrExpression(Object value) {
        if (value instanceof Ast ast && ast.is("AST_VAR") && "this".equals(ast.get("name"))) {
            return new ThisNode();
        }
        return expression(value);
    }

    /** A frame slot for a node's own state, in the function's frame or the file's; no PHP variable can name it. */
    private int hiddenSlot(String purpose) {
        return (scope != null ? scope : fileSlots).slot("\0" + purpose + hiddenCounter++);
    }

    /** The class (lowercase) the code being translated is written in, or null outside classes. */
    private String lexicalClass() {
        return classInfo == null ? null : Str.lower(classInfo.name());
    }

    /** Whether the calling scope comes from the frame: in a closure or a file's top-level code. */
    private boolean scopeInFrame() {
        return scope == null || function != null && function.getName().startsWith(CLOSURE_PREFIX);
    }

    private ExprNode variable(String name) {
        if (name.equals("this")) {
            return new ThisNode();
        }
        if (name.equals("GLOBALS")) {
            return new GlobalsArrayNode();
        }
        if (PhpContext.SUPERGLOBALS.contains(name)) {
            return new GlobalVarNode(name);
        }
        return scope == null ? new FileVarNode(name) : new LocalVarNode(scope.slot(name), name);
    }

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

    private ListAssignNode listTarget(Ast list) {
        int count = list.size();
        ExprNode[] keys = new ExprNode[count];
        LValueNode[] targets = new LValueNode[count];
        ListAssignNode[] nested = new ListAssignNode[count];
        for (int i = 0; i < count; i++) {
            Object child = list.child(i);
            if (child == null) {
                continue;
            }
            Ast element = (Ast) child;
            if ((element.flags & ARRAY_ELEM_REF) != 0) {
                throw unsupported(element, "reference in list()");
            }
            keys[i] = element.get("key") == null ? null : expression(element.get("key"));
            Ast target = element.node("value");
            if (target.is("AST_ARRAY")) {
                nested[i] = listTarget(target);
            } else {
                if (deepestNullsafe(target) != null) {
                    // zend_verify_list_assign_target
                    throw compileError(target.line, "Assignments can only happen to writable values");
                }
                targets[i] = place(target);
            }
        }
        return new ListAssignNode(keys, targets, nested);
    }

    private ExprNode assignment(Ast node) {
        Ast target = node.node("var");
        if (target.is("AST_ARRAY")) {
            // zend_compile_assign: a list with references takes them from a variable or a call only
            if (listHasRefs(target) && !(node.get("expr") instanceof Ast value
                    && (isPlace(value) || value.is("AST_NULLSAFE_PROP") || isCall(value)))) {
                throw compileError(node.line, "Cannot assign reference to non referenceable value");
            }
            if (listHasRefs(target) && deepestNullsafe(node.get("expr")) != null) {
                throw compileError(node.line, "Cannot take reference of a nullsafe chain");
            }
            return new AssignNodes.ListAssignExprNode(listTarget(target), expression(node.get("expr")));
        }
        return AssignNodes.AssignNode.create(place(target), expression(node.get("expr")));
    }

    private ExprNode compoundAssignment(Ast node) {
        return readWritten(node.get("var"), target -> {
            if (!target.isPure()) {
                throw unsupported(node, "compound assignment to a place with side effects");
            }
            return AssignNodes.CompoundAssignNode.create(target, expression(node.get("expr")), node.flags);
        });
    }

    /**
     * An operation that reads a place and writes it (`op=`, `??=`, `++`): it evaluates the place twice, so every
     * array key on the way to it that has side effects (`$a[f()][g()]`, `$GLOBALS[f()]`) is evaluated once before,
     * in order, into a hidden variable, as php-src evaluates the keys once (zend_delayed_compile_dim).
     */
    private ExprNode readWritten(Object var, Function<LValueNode, ExprNode> operation) {
        List<ExprNode> keys = new ArrayList<>();
        LValueNode target = keysEvaluatedOnce(var, keys);
        ExprNode result = operation.apply(target);
        for (int i = keys.size() - 1; i >= 0; i--) {
            result = new AssignNodes.SequenceNode(keys.get(i), result);
        }
        return result;
    }

    /** The place, with its keys that have side effects moved into hidden variables (their assignments to keys). */
    private LValueNode keysEvaluatedOnce(Object value, List<ExprNode> keys) {
        if (!(value instanceof Ast node) || !node.is("AST_DIM") || node.get("dim") == null) {
            return place(value);
        }
        Object containerAst = node.get("expr");
        boolean globals = isGlobals(containerAst);
        ExprNode container = null;
        if (!globals) {
            container = isThis(containerAst) ? new ThisNode()
                    : containerAst instanceof Ast inner && inner.is("AST_DIM") ? keysEvaluatedOnce(inner, keys)
                    : placeOrExpression(containerAst);
        }
        ExprNode key = expression(node.get("dim"));
        if (!isPureKey(key)) {
            String temp = "\0key" + hiddenCounter++;
            keys.add(AssignNodes.AssignNode.create((LValueNode) variable(temp), key));
            key = variable(temp);
        }
        return globals ? new GlobalsDimNode(key) : element(container, key);
    }

    /** A key that evaluating again gives the same and does nothing else: a constant or a variable. */
    private static boolean isPureKey(ExprNode key) {
        return key.constantValue() != null || key instanceof LocalVarNode || key instanceof FileVarNode
                || key instanceof GlobalVarNode;
    }

    // ---- operators ----

    private ExprNode binary(Ast node) {
        if (node.flags == BINARY_PIPE) {
            Object right = node.get("right");
            if (right instanceof Ast arrow && arrow.is("AST_ARROW_FUNC") && unparenthesizedArrow(arrow)) {
                throw compileError(node.line, "Arrow functions on the right hand side of |> must be parenthesized");
            }
            ExprNode operand = expression(node.get("left"));
            int slot = hiddenSlot("pipe");
            return new TemporaryNode(operand, expression(pipeCall(node, temporary(slot, node))), slot, false);
        }
        ExprNode left = node.flags == 260 ? quiet(node.get("left")) : expression(node.get("left"));
        ExprNode right = expression(node.get("right"));
        switch (node.flags) {
            case 1:
                return ArithmeticNodesFactory.AddNodeGen.create(left, right);
            case 2:
                return ArithmeticNodesFactory.SubNodeGen.create(left, right);
            case 3:
                return ArithmeticNodesFactory.MulNodeGen.create(left, right);
            case 4:
                return ArithmeticNodesFactory.DivNodeGen.create(left, right);
            case 5:
                return ArithmeticNodesFactory.ModNodeGen.create(left, right);
            case 12:
                return ArithmeticNodesFactory.PowNodeGen.create(left, right);
            case 8:
                return foldConcat(left, right);
            case 9:
                return ArithmeticNodesFactory.BitOrNodeGen.create(left, right);
            case 10:
                return ArithmeticNodesFactory.BitAndNodeGen.create(left, right);
            case 11:
                return ArithmeticNodesFactory.BitXorNodeGen.create(left, right);
            case 6:
                return ArithmeticNodesFactory.ShiftLeftNodeGen.create(left, right);
            case 7:
                return ArithmeticNodesFactory.ShiftRightNodeGen.create(left, right);
            case 16:
                return CompareNodes.IdenticalNode.create(left, right, false);
            case 17:
                return CompareNodes.IdenticalNode.create(left, right, true);
            case 18:
                return CompareNodes.EqualNode.create(left, right, false);
            case 19:
                return CompareNodes.EqualNode.create(left, right, true);
            case 20:
                return CompareNodes.LessNode.create(left, right, false, false);
            case 21:
                return CompareNodes.LessNode.create(left, right, true, false);
            case 256:
                return CompareNodes.LessNode.create(left, right, false, true);
            case 257:
                return CompareNodes.LessNode.create(left, right, true, true);
            case 170:
                return CompareNodes.SpaceshipNode.create(left, right);
            case 259:
                return new LogicNodes.AndOrNode(left, right, false);
            case 258:
                return new LogicNodes.AndOrNode(left, right, true);
            case 15:
                return new LogicNodes.XorNode(left, right);
            case 260:
                return new LogicNodes.CoalesceNode(left, right);
            default:
                throw unsupported(node, "binary operator " + node.flags);
        }
    }

    /**
     * Concatenation of two literals folds to a literal (common in long split strings); a chain of three or more
     * operands is one ConcatChainNode. concatParts keeps each concatenation's operands, so the next `.` extends it.
     */
    private ExprNode foldConcat(ExprNode left, ExprNode right) {
        if (left.constantValue() instanceof TruffleString a && right.constantValue() instanceof TruffleString b) {
            return new ConstNode(language.intern(Str.concat(a, b)));
        }
        List<ExprNode> leftParts = concatParts.get(left);
        List<ExprNode> parts = new ArrayList<>();
        if (leftParts != null) {
            parts.addAll(leftParts);
        } else {
            parts.add(left);
        }
        parts.add(right);
        ExprNode concat = parts.size() == 2 ? ArithmeticNodesFactory.ConcatNodeGen.create(left, right)
                : new ConcatChainNode(parts.toArray(new ExprNode[0]));
        concatParts.put(concat, parts);
        return concat;
    }

    /** The operands of the concatenations made so far (foldConcat), by node identity. */
    private final Map<ExprNode, List<ExprNode>> concatParts = new IdentityHashMap<>();

    private ExprNode unary(Ast node) {
        ExprNode operand = expression(node.get("expr"));
        switch (node.flags) {
            case 14:
                return new LogicNodes.NotNode(operand);
            case 13:
                return UnaryNodes.BitNotNode.create(operand);
            case 260:
                return new UnaryNodes.SilenceNode(operand);
            case 261:
                return UnaryNodes.PlusNode.create(operand);
            case 262: {
                Object constant = operand.constantValue();
                if (constant instanceof Long l && l != Long.MIN_VALUE) {
                    return new ConstNode(-l);
                }
                if (constant instanceof Double d) {
                    return new ConstNode(-d);
                }
                return UnaryNodes.NegateNode.create(operand);
            }
            default:
                throw unsupported(node, "unary operator " + node.flags);
        }
    }

    // ---- constants and literals ----

    private ExprNode constant(Ast node) {
        Ast nameNode = node.node("name");
        String[] resolved = names.constantName((String) nameNode.get("name"), nameNode.flags);
        // true, false and null are global; a namespace can't declare them
        String name = resolved[1] != null ? resolved[1] : resolved[0];
        switch (Str.lower(name)) {
            case "true":
                return new ConstNode(Boolean.TRUE);
            case "false":
                return new ConstNode(Boolean.FALSE);
            case "null":
                return new ConstNode(PhpNull.NULL);
            default:
                break;
        }
        String deprecation = language.builtins().deprecation(name);
        if (deprecation != null) {
            return new ConstantNodes.DeprecatedConstNode(resolved[0], language.constantId(resolved[0]),
                    resolved[1] == null ? -1 : language.constantId(resolved[1]), deprecation, node.line);
        }
        // a predefined constant folds where the name can't mean a namespace's own (`namespace N; const INI_ALL = 0;`)
        Object known = resolved[1] == null ? language.builtins().constant(name) : null;
        if (known != null) {
            return new ConstNode(known);
        }
        return new ConstantNodes.GlobalConstNode(resolved[0], language.constantId(resolved[0]),
                resolved[1] == null ? -1 : language.constantId(resolved[1]));
    }

    private ExprNode classConstant(Ast node) {
        String constantName = (String) node.get("const");
        Object classNode = node.get("class");
        if (constantName.equalsIgnoreCase("class")) {
            return classNameExpr(node);
        }
        boolean cacheable = !(classNode instanceof Ast ast && ast.is("AST_NAME")
                && className(ast).equalsIgnoreCase("static")) && classNode instanceof Ast named
                && named.is("AST_NAME");
        return new ConstantNodes.ClassConstNode(classRef(classNode), constantName, cacheable);
    }

    private ExprNode classNameExpr(Ast node) {
        Object classNode = node.get("class");
        if (classNode instanceof Ast ast && ast.is("AST_NAME")) {
            String name = className(ast);
            switch (Str.lower(name)) {
                case "self":
                    return new ConstNode(Str.of(requireClass(node).name()));
                case "parent":
                    return new ConstNode(Str.of(requireClass(node).parentName()));
                case "static":
                    return new ConstantNodes.ClassNameNode(classRef(classNode));
                default:
                    return new ConstNode(Str.of(name));
            }
        }
        return new ConstantNodes.ObjectClassNameNode(expression(classNode));
    }

    private ExprNode magicConstant(Ast node) {
        switch (node.flags) {
            case 346:
                return new ConstNode((long) node.line);
            case 347:
                return new ConstNode(Str.of(path));
            case 348:
                return new ConstNode(Str.of(Path.of(path).getParent().toString()));
            case 349:
                return new ConstNode(Str.of(classInfo == null ? "" : classInfo.name()));
            case 351: {
                // a closure's __METHOD__ is its name, without its scope's class
                int closure = functionName.indexOf(CLOSURE_PREFIX);
                return new ConstNode(Str.of(closure > 0 ? functionName.substring(closure) : functionName));
            }
            case 352: {
                String name = functionName.contains("::") ? functionName.substring(functionName.indexOf("::") + 2)
                        : functionName;
                return new ConstNode(Str.of(name));
            }
            case 354:
                return new ConstNode(Str.of(names.namespace()));
            default:
                throw unsupported(node, "magic constant " + node.flags);
        }
    }

    private ExprNode arrayLiteral(Ast node) {
        int count = node.size();
        ExprNode[] keys = new ExprNode[count];
        ExprNode[] values = new ExprNode[count];
        byte[] kinds = new byte[count];
        boolean constant = true;
        for (int i = 0; i < count; i++) {
            Ast element = (Ast) node.child(i);
            if (element == null) {
                throw unsupported(node, "empty array element");
            }
            if (element.is("AST_UNPACK")) {
                kinds[i] = ArrayLiteralNode.SPREAD;
                values[i] = expression(element.get("expr"));
                constant = false;
                continue;
            }
            keys[i] = element.get("key") == null ? null : expression(element.get("key"));
            // array(&$this) (WordPress's do_action_ref_array): $this can't be rebound, so a copy behaves the same
            boolean refToThis = element.get("value") instanceof Ast value && value.is("AST_VAR")
                    && "this".equals(value.get("name"));
            if ((element.flags & ARRAY_ELEM_REF) != 0 && !refToThis) {
                kinds[i] = ArrayLiteralNode.REF;
                values[i] = place(element.get("value"));
                constant = false;
            } else {
                values[i] = expression(element.get("value"));
                constant &= values[i].constantValue() != null && (keys[i] == null || keys[i].constantValue() != null);
            }
        }
        if (constant) {
            boolean ints = true;
            for (int i = 0; i < count; i++) {
                ints &= values[i].constantValue() instanceof Long;
            }
            // an int list starts in the int strategy; anything else is sized for its elements
            PhpArray array = ints ? new PhpArray() : PhpArray.ofCapacity(count);
            for (int i = 0; i < count; i++) {
                Object item = values[i].constantValue();
                if (keys[i] == null) {
                    array.append(item);
                } else {
                    array.put(ArrayKey.of(keys[i].constantValue()), item);
                }
            }
            return new ConstNode(array);
        }
        return new ArrayLiteralNode(keys, values, kinds);
    }

    private ExprNode interpolation(Ast node) {
        ExprNode[] parts = new ExprNode[node.size()];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = expression(node.child(i));
        }
        return new InterpolationNode(parts);
    }

    // ---- calls ----

    private ArgumentsNode arguments(Ast list) {
        int count = list == null ? 0 : list.size();
        ExprNode[] args = new ExprNode[count];
        boolean[] spread = new boolean[count];
        boolean[] calls = new boolean[count];
        for (int i = 0; i < count; i++) {
            Object arg = list.child(i);
            calls[i] = sentAsCall(arg);
            if (arg instanceof Ast ast && ast.is("AST_UNPACK")) {
                spread[i] = true;
                args[i] = expression(ast.get("expr"));
            } else if (arg instanceof Ast ast && ast.is("AST_NAMED_ARG")) {
                throw unsupported(ast, "named argument");
            } else if (arg instanceof Ast ast && ast.is("AST_CALLABLE_CONVERT")) {
                throw unsupported(ast, "first-class callable syntax");
            } else if (arg instanceof Ast ast && (ast.is("AST_VAR") || ast.is("AST_DIM") || ast.is("AST_PROP")
                    || ast.is("AST_STATIC_PROP")) && !"this".equals(ast.get("name")) && !isGlobals(ast)
                    && deepestNullsafe(ast) == null) {
                args[i] = place(arg);
            } else {
                args[i] = expression(arg);
            }
        }
        return new ArgumentsNode(args, spread, calls);
    }

    /**
     * An argument php-src sends as a call's result (SEND_VAR_NO_REF), its notice where a reference was due: a
     * call (but a built-in compiled in place) or an expression giving a VAR (`new`, `$GLOBALS`, `$a = &$b`, an
     * include, a yield).
     */
    boolean sentAsCall(Object arg) {
        if (!(arg instanceof Ast ast)) {
            return false;
        }
        return isCall(ast) ? !compiledInPlace(ast) : ast.is("AST_NEW") || ast.is("AST_ASSIGN_REF")
                || ast.is("AST_INCLUDE_OR_EVAL") || ast.is("AST_YIELD") || isGlobals(ast);
    }

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

    private ExprNode call(Ast node) {
        Ast callee = node.node("expr");
        Ast args = node.node("args");
        if (callee == null) {
            // a value called, as `|>` calls its callable (pipeCall)
            return new CallableCallNode(expression(node.get("expr")), arguments(args), null, null, true);
        }
        // a string callee naming a method, 'A::m'(), calls it as the value would be called
        boolean method = callee.is("AST_NAME") && ((String) callee.get("name")).contains("::");
        ExprNode value = method ? new ConstNode(Str.of((String) callee.get("name"))) : null;
        if (isCallableConvert(node)) {
            if (!callee.is("AST_NAME") || method) {
                return new CallableConvertNodes.ValueNode(method ? value : expression(callee));
            }
            String[] resolved = names.functionName((String) callee.get("name"), callee.flags);
            return new CallableConvertNodes.FunctionNode(resolved[0], language.functionId(Str.lower(resolved[0])),
                    resolved[1] == null ? -1 : language.functionId(Str.lower(resolved[1])));
        }
        if (!callee.is("AST_NAME") || method) {
            return new CallableCallNode(method ? value : expression(callee), arguments(args), null, null, true);
        }
        String[] resolved = names.functionName((String) callee.get("name"), callee.flags);
        // the global name, for the built-ins the translator handles itself (null: a namespaced function)
        String name = resolved[1] != null ? resolved[1] : resolved[0].indexOf('\\') < 0 ? resolved[0] : "";
        switch (Str.lower(name)) {
            case "func_get_args":
                return new FrameArgsNode(false);
            case "func_num_args":
                return new FrameArgsNode(true);
            case "func_get_arg": {
                ExprNode[] positionArgs = expressionList(args);
                if (positionArgs.length != 1) {
                    break;
                }
                int[] paramSlots = null;
                if (scope != null && function != null) {
                    List<Integer> slots = new ArrayList<>();
                    for (PhpFunction.Param param : function.getParams()) {
                        if (!param.variadic()) {
                            slots.add(scope.slot(param.name()));
                        }
                    }
                    paramSlots = slots.stream().mapToInt(Integer::intValue).toArray();
                }
                return new FrameArgNode(positionArgs[0], paramSlots);
            }
            // a name resolved at compile time is compiled into the call itself (zend_compile_func_cuf), whose
            // result is the callee's; the built-in function, called by a name resolved at run time, returns a value
            case "call_user_func": {
                ArgumentsNode rest = arguments(dropFirst(args));
                return new CallableCallNode(expression(args.child(0)), rest, null,
                        new Callables.Parameter("call_user_func", 1, false), resolved[1] == null);
            }
            case "call_user_func_array":
                return new CallableCallNode(expression(args.child(0)), new ArgumentsNode(new ExprNode[0],
                        new boolean[0], new boolean[0]), expression(args.child(1)),
                        new Callables.Parameter("call_user_func_array", 1, false),
                        resolved[1] == null && args.size() == 2);
            case "get_class":
            case "get_called_class":
                if (args.size() == 0) {
                    return name.equalsIgnoreCase("get_class") && classInfo != null
                            ? new ConstNode(Str.of(classInfo.name()))
                            : new ConstantNodes.ClassNameNode(ClassRefNode.lateStatic(classInfo == null ? null
                            : classInfo.name()));
                }
                break;
            case "compact": {
                List<String> variableNames = new ArrayList<>();
                if (compactNames(args, variableNames)) {
                    TruffleString[] keys = new TruffleString[variableNames.size()];
                    LValueNode[] variables = new LValueNode[keys.length];
                    for (int i = 0; i < keys.length; i++) {
                        keys[i] = language.intern(Str.of(variableNames.get(i)));
                        variables[i] = (LValueNode) variable(variableNames.get(i));
                    }
                    return new CompactNode(keys, variables);
                }
                return new DynamicCompactNode(expressionList(args), scope == null ? null : scope.slots);
            }
            case "extract": {
                ExprNode[] extractArgs = expressionList(args);
                if (extractArgs.length == 0) {
                    break;
                }
                if (scope != null && scope.includeScopeSlot < 0) {
                    scope.includeScopeSlot = hiddenSlot("includeScope");
                }
                return new ExtractNode(extractArgs[0], extractArgs.length > 1 ? extractArgs[1] : null,
                        extractArgs.length > 2 ? extractArgs[2] : null, scope == null ? null : scope.slots,
                        scope == null ? -1 : scope.includeScopeSlot, lexicalClass());
            }
            case "get_object_vars": {
                ExprNode[] objectArgs = expressionList(args);
                if (objectArgs.length != 1) {
                    break;
                }
                return new ObjectVarsNode(objectArgs[0], lexicalClass());
            }
            case "get_defined_vars":
                throw unsupported(node, name + "()");
            default:
                break;
        }
        // unqualified in a namespace, the name is resolved at run time: php-src compiles nothing in
        boolean special = resolved[1] == null && compiledSpecially(Str.lower(resolved[0]), args);
        if (special && Str.lower(resolved[0]).equals("defined")) {
            return defined(definedName(args.child(0)));
        }
        ExprNode intrinsic = name.isEmpty() ? null : intrinsic(Str.lower(name), args, special);
        if (intrinsic != null) {
            return intrinsic;
        }
        return new FunctionCallNode(resolved[0], language.functionId(Str.lower(resolved[0])),
                resolved[1] == null ? -1 : language.functionId(Str.lower(resolved[1])), arguments(args), special);
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
     * defined('NAME') compiled in place (ZEND_DEFINED). As zend_compile_func_defined, a name zend_try_ct_eval_const
     * knows at compile time is true: true, false and null in any case, and the predefined constants.
     */
    private ExprNode defined(String name) {
        String lower = Str.lower(name);
        if (lower.equals("true") || lower.equals("false") || lower.equals("null")
                || language.builtins().constant(name) != null) {
            return new ConstNode(Boolean.TRUE);
        }
        return new ConstantNodes.DefinedNode(language.constantId(name));
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

    /** The in-place node for a small built-in (see {@link Intrinsics}), or null for a regular call. */
    private ExprNode intrinsic(String lowerName, Ast args, boolean compiledSpecially) {
        if (!Intrinsics.handles(lowerName)) {
            return null;
        }
        int count = args == null ? 0 : args.size();
        ExprNode[] nodes = new ExprNode[count];
        for (int i = 0; i < count; i++) {
            Object arg = args.child(i);
            if (arg instanceof Ast ast && (ast.is("AST_UNPACK") || ast.is("AST_NAMED_ARG")
                    || ast.is("AST_CALLABLE_CONVERT"))) {
                return null;
            }
            if (Intrinsics.byRef(lowerName, i)) {
                if (!(arg instanceof Ast ast && (ast.is("AST_VAR") || ast.is("AST_DIM") || ast.is("AST_PROP")
                        || ast.is("AST_STATIC_PROP")) && !"this".equals(ast.get("name"))
                        && deepestNullsafe(ast) == null)) {
                    return null;
                }
                nodes[i] = place(arg);
            } else {
                nodes[i] = expression(arg);
            }
        }
        return Intrinsics.create(lowerName, nodes, Intrinsics.callsBack(lowerName) ? hiddenSlot("callback") : -1,
                compiledSpecially);
    }

    private static Ast dropFirst(Ast list) {
        List<Object> rest = new ArrayList<>(list.children().subList(1, list.size()));
        return new Ast(list.kind, list.flags, list.line, list.endLine, rest, null);
    }
}
