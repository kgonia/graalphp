package pl.codeloom.php.parser;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.FileRootNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.nodes.ThunkRootNode;
import pl.codeloom.php.nodes.call.ArgumentsNode;
import pl.codeloom.php.nodes.call.CallableCallNode;
import pl.codeloom.php.nodes.call.ClosureNode;
import pl.codeloom.php.nodes.call.DynamicMethodCallNode;
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
import pl.codeloom.php.nodes.expr.IncludeNode;
import pl.codeloom.php.nodes.expr.InterpolationNode;
import pl.codeloom.php.nodes.expr.LogicNodes;
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
import pl.codeloom.php.nodes.vars.GlobalVarNode;
import pl.codeloom.php.nodes.vars.GlobalsArrayNode;
import pl.codeloom.php.nodes.vars.GlobalsDimNode;
import pl.codeloom.php.nodes.vars.ListAssignNode;
import pl.codeloom.php.nodes.vars.LocalVarNode;
import pl.codeloom.php.nodes.vars.PropNode;
import pl.codeloom.php.nodes.vars.StaticPropNode;
import pl.codeloom.php.nodes.vars.ThisNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** The class whose members are being translated, for self::, parent:: and __CLASS__. */
    private record ClassInfo(String name, String parentName) {
    }

    /** A function's local variables: name to frame slot. */
    private static final class Scope {
        final FrameDescriptor.Builder frame = FrameDescriptor.newBuilder().defaultValueIllegal();
        final Map<String, Integer> slots = new HashMap<>();

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
    private final ClassInfo classInfo;
    private final String functionName;
    private final PhpFunction function;
    private final Scope scope;
    /** In a generator function's body: the frame slot of its Generator state; -1 elsewhere. */
    private int generatorSlot = -1;
    /** Numbers hidden temporaries ("\0key0", ...), which no PHP variable name can clash with. */
    private int hiddenCounter;
    /** Frame slots of a file's top-level code: only hidden ones (its variables are globals, not slots). */
    private final Scope fileSlots = new Scope();
    /** The namespace and imports in effect: updated by a file's namespace and use statements as they come. */
    private Names names;

    public Translator(PhpLanguage language, String path, Source source) {
        this(language, path, source, null, "", null, null, Names.GLOBAL);
    }

    private Translator(PhpLanguage language, String path, Source source, ClassInfo classInfo,
                       String functionName, PhpFunction function, Scope scope, Names names) {
        this.language = language;
        this.path = path;
        this.source = source;
        this.classInfo = classInfo;
        this.functionName = functionName;
        this.function = function;
        this.scope = scope;
        this.names = names;
    }

    private SourceSection lineSection(int line) {
        return source == null || line < 1 || line > source.getLineCount() ? null : source.createSection(line);
    }

    private PhpError unsupported(Ast node, String what) {
        return new PhpError("Unsupported " + what + " (" + node.kind + " at " + path + ":" + node.line + ")");
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
                case "AST_FUNC_DECL":
                    prologue.add(new DeclareNodes.DeclareFunctionNode(declareFunction(statement, null)));
                    break;
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
        return new FileRootNode(language, fileSlots.frame.build(), path, block(prologue), block(body),
                source == null ? null : source.createSection(0, source.getLength()));
    }

    private static StmtNode block(List<StmtNode> statements) {
        if (statements.size() == 1) {
            return statements.get(0);
        }
        return new BasicStmts.BlockNode(statements.toArray(new StmtNode[0]));
    }

    // ---- statements ----

    private StmtNode statements(Ast list) {
        if (list == null) {
            return new BasicStmts.NopNode();
        }
        if (!list.is("AST_STMT_LIST")) {
            return statement(list);
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
                return new BasicStmts.ReturnNode(node.get("expr") == null ? null : expression(node.get("expr")));
            case "AST_GLOBAL":
                return globalStatement(node);
            case "AST_STATIC":
                return staticStatement(node);
            case "AST_UNSET":
                return new BasicStmts.UnsetNode(place(node.get("var")));
            case "AST_THROW":
                return new BasicStmts.ThrowNode(expression(node.get("expr")));
            case "AST_TRY":
                return tryStatement(node);
            case "AST_FUNC_DECL": {
                PhpFunction declared = declareFunction(node, null);
                return new DeclareNodes.DeclareFunctionNode(declared);
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
    private static boolean containsYield(Object value) {
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
                types[i][j] = className((Ast) classes.child(j)).toLowerCase();
            }
            targets[i] = handler.get("var") == null ? null : place(handler.get("var"));
            bodies[i] = statements(handler.node("stmts"));
        }
        StmtNode finallyBody = node.get("finally") == null ? null : statements(node.node("finally"));
        return new GeneratorNodes.TryNode(body, types, targets, bodies, finallyBody, hiddenSlot("try"));
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
            if (!(subject instanceof LValueNode)) {
                throw unsupported(node, "foreach by reference over an expression");
            }
        }
        LValueNode key = node.get("key") == null ? null : place(node.get("key"));
        StmtNode body = statements(node.node("stmts"));
        if (value.is("AST_ARRAY")) {
            return new ForeachNode(subject, key, null, listTarget(value), body, false, hiddenSlot("array"),
                    hiddenSlot("position"));
        }
        return new ForeachNode(subject, key, place(value), null, body, byRef, hiddenSlot("array"),
                hiddenSlot("position"));
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
            return new BasicStmts.NopNode();
        }
        return new BasicStmts.GlobalNode(new LocalVarNode(scope.slot(name), name), name);
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
                types[i][j] = className((Ast) classes.child(j)).toLowerCase();
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
    private PhpFunction declareFunction(Ast node, ClassInfo owner) {
        String name = owner == null ? names.declared((String) node.get("name")) : (String) node.get("name");
        Names declaredNames = names;
        Ast paramList = node.node("params");
        PhpFunction.Param[] params = params(paramList);
        int flags = node.flags;
        boolean isStatic = (flags & MODIFIER_STATIC) != 0;
        boolean isPublic = (flags & 7) == 0 || (flags & MODIFIER_PUBLIC) != 0;
        boolean isAbstract = (flags & MODIFIER_ABSTRACT) != 0 || node.get("stmts") == null;
        PhpFunction[] self = new PhpFunction[1];
        self[0] = new PhpFunction(name, params, (flags & FUNC_RETURNS_REF) != 0, isStatic, isPublic, isAbstract,
                () -> translateBody(node, owner, self[0], name, params, new String[0], new boolean[0],
                        declaredNames));
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
                    coerceType(type), nullable);
        }
        return params;
    }

    private static boolean isNullable(Ast type) {
        return type != null && type.is("AST_NULLABLE_TYPE");
    }

    private static boolean isNullConstant(Object value) {
        return value instanceof Ast ast && ast.is("AST_CONST") && "null".equalsIgnoreCase(ast.node("name") == null
                ? null : (String) ast.node("name").get("name"));
    }

    /** The scalar type a declared type coerces to: 's', 'i', 'f', 'b', or 0 for anything else. */
    private static char coerceType(Ast type) {
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

    private RootCallTarget translateBody(Ast node, ClassInfo owner, PhpFunction target, String name,
                                         PhpFunction.Param[] params, String[] captures, boolean[] captureByRef,
                                         Names declaredNames) {
        Scope bodyScope = new Scope();
        String display = owner == null ? name : owner.name() + "::" + name;
        Translator body = new Translator(language, path, source, owner, display, target, bodyScope, declaredNames);
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
        boolean generator = !node.is("AST_ARROW_FUNC") && containsYield(node.get("stmts"));
        if (generator) {
            body.generatorSlot = bodyScope.slot("\0generator");
        }
        StmtNode statements;
        if (node.is("AST_ARROW_FUNC")) {
            Ast stmts = node.node("stmts");
            Ast returned = stmts.is("AST_RETURN") ? stmts : null;
            statements = returned != null ? body.statement(returned)
                    : new BasicStmts.ReturnNode(body.expression(stmts));
        } else {
            statements = body.statements(node.node("stmts"));
        }
        if (owner != null && name.equalsIgnoreCase("__construct")) {
            List<StmtNode> promoted = new ArrayList<>();
            for (int i = 0; i < params.length; i++) {
                if ((((Ast) paramList.child(i)).flags & PROMOTED) != 0) {
                    promoted.add(new BasicStmts.PromoteNode(params[i].name().intern(),
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
                    defaults, captureSlots, captureByRef, (char) 0, false, new BasicStmts.NopNode(),
                    lineSection(node.line));
            root.setGeneratorBody(new GeneratorNodes.BodyRootNode(language, descriptor, statements, display)
                    .getCallTarget(), body.generatorSlot);
            return root.getCallTarget();
        }
        FunctionRootNode root = new FunctionRootNode(language, descriptor, display, params,
                paramSlots, defaults, captureSlots, captureByRef, coerceType(returnType), isNullable(returnType),
                statements, lineSection(node.line));
        return root.getCallTarget();
    }

    private ExprNode closure(Ast node) {
        boolean arrow = node.is("AST_ARROW_FUNC");
        PhpFunction.Param[] params = params(node.node("params"));
        List<String> captureNames = new ArrayList<>();
        List<Boolean> captureRefs = new ArrayList<>();
        if (arrow) {
            Set<String> used = new LinkedHashSet<>();
            collectVariables(node.get("stmts"), used);
            for (PhpFunction.Param param : params) {
                used.remove(param.name());
            }
            used.remove("this");
            for (String name : used) {
                if (scope == null || scope.slots.containsKey(name)) {
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
        ExprNode[] captureNodes = new ExprNode[names.length];
        for (int i = 0; i < names.length; i++) {
            byRef[i] = captureRefs.get(i);
            captureNodes[i] = variable(names[i]);
        }
        String name = "{closure:" + Path.of(path).getFileName() + ":" + node.line + "}";
        ClassInfo owner = classInfo;
        Names declaredNames = this.names;
        PhpFunction[] self = new PhpFunction[1];
        self[0] = new PhpFunction(name, params, false, (node.flags & MODIFIER_STATIC) != 0, true, false,
                () -> translateBody(node, owner, self[0], name, params, names, byRef, declaredNames));
        ClassRefNode scopeRef = classInfo == null ? null : ClassRefNode.named(classInfo.name());
        return new ClosureNode(self[0], captureNodes, byRef, (node.flags & MODIFIER_STATIC) != 0, scopeRef);
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
            collectVariables(ast.get(key), names);
        }
    }

    // ---- classes ----

    private StmtNode classDeclaration(Ast node) {
        return new DeclareNodes.DeclareClassNode(classTemplate(node, names.declared((String) node.get("name"))));
    }

    /** Numbers the anonymous classes, as php-src's runtime-definition keys keep their names apart. */
    private static final java.util.concurrent.atomic.AtomicInteger ANONYMOUS_CLASSES =
            new java.util.concurrent.atomic.AtomicInteger();

    /** `new class(...) {...}`, named as php-src names it: "class@anonymous", NUL, then file:line$n. */
    private ExprNode newAnonymousClass(Ast node, Ast classNode) {
        String parent = classNode.get("extends") == null ? "class" : className(classNode.node("extends"));
        String name = parent + "@anonymous\0" + path + ":" + classNode.line + "$"
                + Integer.toHexString(ANONYMOUS_CLASSES.getAndIncrement());
        ClassTemplate template = classTemplate(classNode, name);
        return new DeclareNodes.NewAnonymousClassNode(template,
                new NewNode(ClassRefNode.named(name), arguments(node.node("args"))));
    }

    private ClassTemplate classTemplate(Ast node, String name) {
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
        Translator inClass = new Translator(language, path, source, info, "", null, null, names);
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
        return new ClassTemplate(name, parentName, interfaces, isInterface, isAbstract, members, unsupported);
    }

    /** A trait: its members are translated for each class that uses it, on that class's first declaration. */
    private ClassTemplate traitTemplate(Ast node, String name) {
        Ast statements = node.node("stmts");
        Names declaredNames = names;
        ClassTemplate.TraitBody body = (className, parentName) -> {
            ClassInfo info = new ClassInfo(className, parentName);
            Translator inClass = new Translator(language, path, source, info, "", null, null, declaredNames);
            List<ClassTemplate.Member> members = new ArrayList<>();
            for (Object child : statements.children()) {
                inClass.addMember(members, (Ast) child, className, info);
            }
            return members;
        };
        return new ClassTemplate(name, null, List.of(), false, true, List.of(), null, body);
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
                String method = ((String) reference.get("method")).toLowerCase();
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
                            boolean isPublic = (param.flags & 6) == 0;
                            promoted.add(new PhpClass.PropertyDecl(((String) param.get("name")).intern(),
                                    PhpNull.NULL, isPublic));
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
                boolean isPublic = (member.flags & 7) == 0 || (member.flags & MODIFIER_PUBLIC) != 0;
                for (Object child : member.node("props").children()) {
                    Ast element = (Ast) child;
                    String propertyName = ((String) element.get("name")).intern();
                    Object initial = element.get("default") == null ? PhpNull.NULL
                            : constantOrDeferred(element.get("default"));
                    members.add(new ClassTemplate.Property(propertyName, initial, isPublic, isStatic));
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
        switch (name.toLowerCase()) {
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

    private static TruffleString literal(Object value) {
        if (value instanceof String s) {
            return Str.intern(Str.of(s));
        }
        if (value instanceof byte[] bytes) {
            return Str.intern(Str.of(bytes));
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
        switch (node.kind) {
            case "AST_VAR":
            case "AST_DIM":
            case "AST_PROP":
            case "AST_STATIC_PROP":
                if (node.is("AST_VAR") && "this".equals(node.get("name"))) {
                    return new ThisNode();
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
            case "AST_ASSIGN_REF":
                return new AssignNodes.RefAssignNode(place(node.get("var")), place(node.get("expr")));
            case "AST_ASSIGN_OP":
                return compoundAssignment(node);
            case "AST_BINARY_OP":
                return binary(node);
            case "AST_UNARY_OP":
                return unary(node);
            case "AST_PRE_INC":
                return AssignNodes.IncDecNode.create(place(node.get("var")), true, true);
            case "AST_PRE_DEC":
                return AssignNodes.IncDecNode.create(place(node.get("var")), false, true);
            case "AST_POST_INC":
                return AssignNodes.IncDecNode.create(place(node.get("var")), true, false);
            case "AST_POST_DEC":
                return AssignNodes.IncDecNode.create(place(node.get("var")), false, false);
            case "AST_CONDITIONAL":
                return new LogicNodes.TernaryNode(expression(node.get("cond")),
                        node.get("true") == null ? null : expression(node.get("true")), expression(node.get("false")));
            case "AST_ISSET":
                return new TestNodes.IssetNode(new LValueNode[]{place(node.get("var"))});
            case "AST_EMPTY":
                return new TestNodes.EmptyNode(expression(node.get("expr")));
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
                return new TestNodes.InstanceofNode(expression(node.get("expr")), name.toLowerCase());
            }
            case "AST_CALL":
                return call(node);
            case "AST_METHOD_CALL":
                if (!(node.get("method") instanceof String)) {
                    return new DynamicMethodCallNode(placeOrExpression(node.get("expr")), expression(node.get("method")),
                            arguments(node.node("args")));
                }
                return new MethodCallNode(expression(node.get("expr")), methodName(node), arguments(node.node("args")));
            case "AST_STATIC_CALL": {
                Ast classNode = node.node("class");
                String name = classNode != null && classNode.is("AST_NAME") ? className(classNode).toLowerCase() : "";
                boolean forwarding = name.equals("self") || name.equals("parent") || name.equals("static");
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
                if (node.get("class") instanceof Ast classNode && classNode.is("AST_CLASS")) {
                    return newAnonymousClass(node, classNode);
                }
                return new NewNode(classRef(node.get("class")), arguments(node.node("args")));
            case "AST_CLOSURE":
            case "AST_ARROW_FUNC":
                return closure(node);
            case "AST_INCLUDE_OR_EVAL": {
                if (node.flags == 1) {
                    throw unsupported(node, "eval");
                }
                return new IncludeNode(expression(node.get("expr")), node.flags == 4 || node.flags == 16);
            }
            case "AST_PRINT":
                return new LogicNodes.TernaryNode(new ConstNode(Boolean.TRUE),
                        new PrintNode(expression(node.get("expr"))), new ConstNode(1L));
            case "AST_THROW":
                return new ThrowExprNode(new BasicStmts.ThrowNode(expression(node.get("expr"))));
            default:
                throw unsupported(node, "expression");
        }
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

    // ---- places ----

    LValueNode place(Object value) {
        Ast node = (Ast) value;
        switch (node.kind) {
            case "AST_VAR": {
                if (!(node.get("name") instanceof String name)) {
                    throw unsupported(node, "variable variable");
                }
                if (name.equals("this")) {
                    throw unsupported(node, "assignment to $this");
                }
                return (LValueNode) variable(name);
            }
            case "AST_DIM": {
                Object dim = node.get("dim");
                if (node.node("expr").is("AST_VAR") && "GLOBALS".equals(node.node("expr").get("name"))
                        && dim != null) {
                    return new GlobalsDimNode(expression(dim));
                }
                ExprNode container = node.node("expr").is("AST_VAR") && "this".equals(node.node("expr").get("name"))
                        ? new ThisNode() : placeOrExpression(node.get("expr"));
                if (dim == null) {
                    return new DimNode(container, null, null);
                }
                ExprNode key = expression(dim);
                Object constant = key.constantValue();
                Object normalized = constant == null || constant instanceof PhpArray ? null : ArrayKey.of(constant);
                return new DimNode(container, key, normalized);
            }
            case "AST_PROP":
            case "AST_NULLSAFE_PROP": {
                ExprNode object = placeOrExpression(node.get("expr"));
                Object prop = node.get("prop");
                if (prop instanceof String name) {
                    return new PropNode(object, name.intern(), null);
                }
                return new PropNode(object, null, expression(prop));
            }
            case "AST_STATIC_PROP": {
                if (!(node.get("prop") instanceof String name)) {
                    throw unsupported(node, "dynamic static property name");
                }
                return new StaticPropNode(classRef(node.get("class")), name);
            }
            default:
                throw unsupported(node, "assignment target");
        }
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
        return scope == null ? new GlobalVarNode(name) : new LocalVarNode(scope.slot(name), name);
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
                targets[i] = place(target);
            }
        }
        return new ListAssignNode(keys, targets, nested);
    }

    private ExprNode assignment(Ast node) {
        Ast target = node.node("var");
        if (target.is("AST_ARRAY")) {
            return new AssignNodes.ListAssignExprNode(listTarget(target), expression(node.get("expr")));
        }
        return AssignNodes.AssignNode.create(place(target), expression(node.get("expr")));
    }

    private ExprNode compoundAssignment(Ast node) {
        LValueNode target = place(node.get("var"));
        if (target.isPure()) {
            return AssignNodes.CompoundAssignNode.create(target, expression(node.get("expr")), node.flags);
        }
        // `$a[f()] op= v` reads and writes the element: evaluate the key once, into a hidden variable
        Ast dim = node.node("var");
        if (dim.is("AST_DIM") && dim.get("dim") != null) {
            String temp = "\0key" + hiddenCounter++;
            LValueNode tempWrite = (LValueNode) variable(temp);
            ExprNode keyInit = AssignNodes.AssignNode.create(tempWrite, expression(dim.get("dim")));
            LValueNode element = new DimNode(placeOrExpression(dim.get("expr")), variable(temp), null);
            if (element.isPure()) {
                return new AssignNodes.SequenceNode(keyInit,
                        AssignNodes.CompoundAssignNode.create(element, expression(node.get("expr")), node.flags));
            }
        }
        throw unsupported(node, "compound assignment to a place with side effects");
    }

    // ---- operators ----

    private ExprNode binary(Ast node) {
        ExprNode left = expression(node.get("left"));
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

    /** Concatenation of two literals folds to a literal (common in long split strings). */
    private static ExprNode foldConcat(ExprNode left, ExprNode right) {
        if (left.constantValue() instanceof TruffleString a && right.constantValue() instanceof TruffleString b) {
            return new ConstNode(Str.intern(Str.concat(a, b)));
        }
        return ArithmeticNodesFactory.ConcatNodeGen.create(left, right);
    }

    private ExprNode unary(Ast node) {
        ExprNode operand = expression(node.get("expr"));
        switch (node.flags) {
            case 14:
                return new LogicNodes.NotNode(operand);
            case 13:
                return UnaryNodes.BitNotNode.create(operand);
            case 260:
                return operand;
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
        switch (name.toLowerCase()) {
            case "true":
                return new ConstNode(Boolean.TRUE);
            case "false":
                return new ConstNode(Boolean.FALSE);
            case "null":
                return new ConstNode(PhpNull.NULL);
            default:
                break;
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
            switch (name.toLowerCase()) {
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
        return new ConstantNodes.ClassNameNode(classRef(classNode));
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
            case 351:
                return new ConstNode(Str.of(functionName));
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
        for (int i = 0; i < count; i++) {
            Object arg = list.child(i);
            if (arg instanceof Ast ast && ast.is("AST_UNPACK")) {
                spread[i] = true;
                args[i] = expression(ast.get("expr"));
            } else if (arg instanceof Ast ast && ast.is("AST_NAMED_ARG")) {
                throw unsupported(ast, "named argument");
            } else if (arg instanceof Ast ast && ast.is("AST_CALLABLE_CONVERT")) {
                throw unsupported(ast, "first-class callable syntax");
            } else if (arg instanceof Ast ast && (ast.is("AST_VAR") || ast.is("AST_DIM") || ast.is("AST_PROP")
                    || ast.is("AST_STATIC_PROP")) && !"this".equals(ast.get("name"))) {
                args[i] = place(arg);
            } else {
                args[i] = expression(arg);
            }
        }
        return new ArgumentsNode(args, spread);
    }

    private ExprNode call(Ast node) {
        Ast callee = node.node("expr");
        Ast args = node.node("args");
        if (!callee.is("AST_NAME")) {
            return new CallableCallNode(expression(callee), arguments(args), null);
        }
        String[] resolved = names.functionName((String) callee.get("name"), callee.flags);
        // the global name, for the built-ins the translator handles itself (null: a namespaced function)
        String name = resolved[1] != null ? resolved[1] : resolved[0].indexOf('\\') < 0 ? resolved[0] : "";
        switch (name.toLowerCase()) {
            case "func_get_args":
                return new FrameArgsNode(false);
            case "func_num_args":
                return new FrameArgsNode(true);
            case "call_user_func": {
                ArgumentsNode rest = arguments(dropFirst(args));
                return new CallableCallNode(expression(args.child(0)), rest, null);
            }
            case "call_user_func_array":
                return new CallableCallNode(expression(args.child(0)), new ArgumentsNode(new ExprNode[0],
                        new boolean[0]), expression(args.child(1)));
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
                        keys[i] = Str.intern(Str.of(variableNames.get(i)));
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
                return new ExtractNode(extractArgs[0], extractArgs.length > 1 ? extractArgs[1] : null,
                        extractArgs.length > 2 ? extractArgs[2] : null, scope == null ? null : scope.slots);
            }
            case "get_defined_vars":
                throw unsupported(node, name + "()");
            default:
                break;
        }
        ExprNode intrinsic = name.isEmpty() ? null : intrinsic(name.toLowerCase(), args);
        if (intrinsic != null) {
            return intrinsic;
        }
        return new FunctionCallNode(resolved[0], language.functionId(resolved[0].toLowerCase()),
                resolved[1] == null ? -1 : language.functionId(resolved[1].toLowerCase()), arguments(args));
    }

    /** compact()'s variable names when they are string literals or arrays of them; false otherwise. */
    private boolean compactNames(Ast list, List<String> out) {
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
    private ExprNode intrinsic(String lowerName, Ast args) {
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
                        || ast.is("AST_STATIC_PROP")) && !"this".equals(ast.get("name")))) {
                    return null;
                }
                nodes[i] = place(arg);
            } else {
                nodes[i] = expression(arg);
            }
        }
        return Intrinsics.create(lowerName, nodes);
    }

    private static Ast dropFirst(Ast list) {
        List<Object> rest = new ArrayList<>(list.children().subList(1, list.size()));
        return new Ast(list.kind, list.flags, list.line, list.endLine, rest, null);
    }
}
