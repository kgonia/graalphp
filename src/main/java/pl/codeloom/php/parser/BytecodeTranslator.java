package pl.codeloom.php.parser;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.StackValue;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.bytecode.CallInfo;
import pl.codeloom.php.bytecode.LiteralSite;
import pl.codeloom.php.bytecode.PhpBytecodeRootNode;
import pl.codeloom.php.bytecode.PhpBytecodeRootNodeGen;
import pl.codeloom.php.bytecode.PropSite;
import pl.codeloom.php.nodes.intrinsics.Intrinsics;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Translates a function's body into Bytecode DSL bytecode ({@link PhpBytecodeRootNode}), for the constructs it
 * covers; a function using anything else stays on the tree ({@link Translator}), which a report
 * (-Dphp.bytecode.report=true) lists with the reason.
 *
 * <p>The DSL keeps the parser (this object, through the lambda it gives the DSL) as long as the function's
 * bytecode, to run it again when a location's line is first asked for ({@code ensureSourceInformation}). So it
 * holds no tree: it reads the body from the function's {@link AstBody} each time it runs, and every run emits the
 * same bytecode. It numbers locals in the order it meets them, and an object it makes for a place in the body (a
 * constant array) is made by the first run and taken by later ones in the same order.
 */
public final class BytecodeTranslator {
    private static final String REPORT_MODE = System.getProperty("php.bytecode.report", "false");
    /**
     * -Dphp.bytecode.report=all also lists every construct that keeps a function on the tree, not only the first
     * (see {@link #collect}). A native image reads these properties at build time.
     */
    private static final boolean REPORT_ALL = REPORT_MODE.equals("all");
    private static final boolean REPORT = REPORT_ALL || REPORT_MODE.equals("true");
    /** How many translations {@link #collect} tries for one function before it gives up. */
    private static final int COLLECT_LIMIT = 500;
    /** A function whose bytecode is printed at its first return (php.bytecode.dump=name), for debugging. */
    private static final String DUMP = System.getProperty("php.bytecode.dump");

    /** A construct the translator doesn't cover: the function stays on the tree. */
    static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unsupported(String what, Ast node) {
            super(what + (node == null ? "" : " (" + node.kind + " at line " + node.line + ")"), null, false,
                    false);
        }
    }

    private final PhpLanguage language;
    private final String path;
    private final Source source;
    private final boolean internal;
    /** The function's declaration (AST_FUNC_DECL, AST_METHOD, AST_CLOSURE or AST_ARROW_FUNC); its body is unread. */
    private final Ast declaration;
    private final Translator.ClassInfo owner;
    private final PhpFunction function;
    private final String display;
    private final PhpFunction.Param[] params;
    private final String[] captures;
    private final boolean[] captureByRef;
    private final Names names;
    /** A generator function's body: a call returns the Generator, which runs the body (GeneratorStart). */
    private final boolean generator;
    /** The body the first run translates, read by the tree translator already; later runs read their own. */
    private Ast firstBody;
    /** What the first run made per place in the body, in the order it met them; later runs take them. */
    private final List<Object> made = new ArrayList<>();
    /** The variables' locals, made by the first run (later runs number them alike). */
    private LocalAccessor[] variableLocals;
    /** The foreach loops' state locals, as the first run met them. */
    private final List<LocalAccessor> foreachStates = new ArrayList<>();
    private boolean firstRun = true;
    /** report=all ({@link #collect}): the nodes this translation leaves out, by {@link Run#key}; else null. */
    private Set<String> skip;
    /** report=all: the last run, whose dispatch stack says where a construct failed. */
    private Run lastRun;

    private BytecodeTranslator(PhpLanguage language, String path, Source source, boolean internal, Ast declaration,
                               Ast body, Translator.ClassInfo owner, PhpFunction function, String display,
                               PhpFunction.Param[] params, String[] captures, boolean[] captureByRef, Names names,
                               boolean generator) {
        this.language = language;
        this.path = path;
        this.source = source;
        this.internal = internal;
        this.declaration = declaration;
        this.firstBody = body;
        this.owner = owner;
        this.function = function;
        this.display = display;
        this.params = params;
        this.captures = captures;
        this.captureByRef = captureByRef;
        this.names = names;
        this.generator = generator;
    }

    /**
     * The function's call target on bytecode, or null when its body uses something the translator doesn't cover.
     * A generator function's (generator) returns the Generator, which runs the body on continuations.
     */
    static RootCallTarget translate(PhpLanguage language, String path, Source source, boolean internal,
                                    Ast declaration, Ast body, Translator.ClassInfo owner, PhpFunction function,
                                    String display, PhpFunction.Param[] params, String[] captures,
                                    boolean[] captureByRef, Names names, boolean generator) {
        BytecodeTranslator translator = new BytecodeTranslator(language, path, source, internal, declaration, body,
                owner, function, display, params, captures, captureByRef, names, generator);
        BytecodeParser<PhpBytecodeRootNodeGen.Builder> parser = translator::parse;
        BytecodeRootNodes<PhpBytecodeRootNode> nodes;
        try {
            nodes = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, parser);
        } catch (Unsupported unsupported) {
            report("tree     " + display + ": " + unsupported.getMessage());
            if (REPORT_ALL) {
                List<String> reasons = new ArrayList<>();
                if (generator) {
                    reasons.add("generator");
                }
                reasons.addAll(collect(language, path, source, internal, declaration, owner, function, display,
                        params, captures, captureByRef, names, generator));
                report("all      " + display + ": " + String.join(" | ", reasons));
            }
            return null;
        }
        PhpBytecodeRootNode root = nodes.getNode(0);
        int[] parameterLocals = new int[params.length];
        for (int i = 0; i < params.length; i++) {
            parameterLocals[i] = i;
        }
        Ast returnType = declaration.node("returnType");
        root.initialize(display, path, internal, translator.functionSection(), params, parameterLocals,
                Translator.coerceType(returnType), Translator.isNullable(returnType), translator.variableLocals,
                translator.foreachStates.toArray(new LocalAccessor[0]),
                owner == null ? null : owner.name());
        if (generator) {
            root.initializeGenerator(function.returnsRef(), hasFinally(body == null ? declaration.body() : body));
        }
        report("bytecode " + display + (generator ? " (generator)" : ""));
        if (display.equals(DUMP)) {
            root.dumpAtExit();
        }
        return root.getCallTarget();
    }

    /**
     * Every construct that keeps a function on the tree, for report=all. The function is translated again and
     * again. Each time it leaves out the node the last construct failed in (the innermost expression or statement
     * being translated) and translates that node's parts alone in its place, until a translation gets through. The
     * bytecode made on the way is thrown away: the function stays on the tree either way. A failure outside every
     * statement (its parameters, a reference return) ends the search, marked "(function)".
     */
    private static List<String> collect(PhpLanguage language, String path, Source source, boolean internal,
                                        Ast declaration, Translator.ClassInfo owner, PhpFunction function,
                                        String display, PhpFunction.Param[] params, String[] captures,
                                        boolean[] captureByRef, Names names, boolean generator) {
        Set<String> skip = new java.util.HashSet<>();
        Set<String> reasons = new LinkedHashSet<>();
        for (int attempt = 0; attempt < COLLECT_LIMIT; attempt++) {
            BytecodeTranslator translator = new BytecodeTranslator(language, path, source, internal, declaration,
                    null, owner, function, display, params, captures, captureByRef, names, generator);
            translator.skip = skip;
            try {
                PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, translator::parse);
                return new ArrayList<>(reasons);
            } catch (Unsupported unsupported) {
                String at = translator.lastRun == null ? null : translator.lastRun.dispatched.peekLast();
                if (at == null || !skip.add(at)) {
                    reasons.add(unsupported.getMessage() + " (function)");
                    return new ArrayList<>(reasons);
                }
                reasons.add(unsupported.getMessage());
            }
        }
        reasons.add("(more)");
        return new ArrayList<>(reasons);
    }



    private static void report(String line) {
        if (REPORT) {
            System.err.println("php.bytecode " + line);
        }
    }

    private SourceSection functionSection() {
        int line = declaration.line;
        return source == null || line < 1 || line > source.getLineCount() ? null : source.createSection(line);
    }

    /** One run of the parser: the DSL makes the first, and another whenever it needs the function's sources. */
    private void parse(PhpBytecodeRootNodeGen.Builder builder) {
        Ast body = firstBody != null ? firstBody : declaration.body();
        firstBody = null;
        lastRun = new Run(builder);
        lastRun.root(body);
        firstRun = false;
    }

    /** The state of one run: the builder, the locals, the loops around the code being emitted. */
    private final class Run {
        private final PhpBytecodeRootNodeGen.Builder b;
        private final Translator constants;
        private final Map<String, BytecodeLocal> variables = new HashMap<>();
        private final List<Loop> loops = new ArrayList<>();
        /** In a finally block, how many of loops are outside it: break and continue may not leave it. */
        private int loopsOutsideFinally;
        /** A generator's local holding its PhpGenerator. */
        private BytecodeLocal generatorLocal;
        /** The next object of made to take, or to make. */
        private int madeIndex;
        /** report=all: the expressions and statements being translated, outermost first, by {@link #key}. */
        private final java.util.ArrayDeque<String> dispatched = new java.util.ArrayDeque<>();
        /** report=all: how many nodes of a kind on a line this run has met, for {@link #key}. */
        private final Map<String, Integer> met = new HashMap<>();

        Run(PhpBytecodeRootNodeGen.Builder builder) {
            this.b = builder;
            this.constants = Translator.forConstants(language, path, source, internal, owner, display, function,
                    names);
        }

        // ---- the function ----

        void root(Ast body) {
            if (function.returnsRef() && !generator) {
                throw new Unsupported("function returning by reference", declaration);
            }
            b.beginRoot();
            // the variables first, in a fixed order: parameters, captures, then the body's
            Set<String> names = new LinkedHashSet<>();
            for (PhpFunction.Param param : params) {
                names.add(param.name());
            }
            if (names.size() != params.length) {
                throw new Unsupported("parameters with the same name", declaration);
            }
            for (String capture : captures) {
                names.add(capture);
            }
            for (String name : names) {
                if (!plainVariable(name)) {
                    // PHP refuses these at compile time; the tree has its own handling of the names
                    throw new Unsupported("$" + name + " as a parameter or capture", declaration);
                }
            }
            collectVariables(body, names);
            List<LocalAccessor> locals = new ArrayList<>();
            for (String name : names) {
                BytecodeLocal local = b.createLocal(name, null);
                variables.put(name, local);
                locals.add(LocalAccessor.constantOf(local));
            }
            if (variableLocals == null) {
                variableLocals = locals.toArray(new LocalAccessor[0]);
            }
            if (source != null) {
                b.beginSource(source);
                beginLine(declaration.line);
            } else if (!internal) {
                throw new Unsupported("no source text for lines", declaration);
            }
            prologue();
            if (declaration.is("AST_ARROW_FUNC")) {
                if (body.is("AST_RETURN")) {
                    statement(body);
                } else {
                    returnValue(body, body);
                }
            } else {
                statements(body);
            }
            b.beginReturn();
            b.emitLoadConstant(PhpNull.NULL);
            b.endReturn();
            if (source != null) {
                endLine(declaration.line);
                b.endSource();
            }
            b.endRoot();
        }

        /** Binds the parameters and captures (FunctionRootNode.bindArguments), in no statement. */
        private void prologue() {
            if (source != null) {
                b.beginSourceSectionUnavailable();
            }
            Ast paramList = declaration.node("params");
            for (int i = 0; i < params.length; i++) {
                Ast param = (Ast) paramList.child(i);
                if ((param.flags & PROMOTED) != 0) {
                    throw new Unsupported("promoted constructor parameter", param);
                }
                BytecodeLocal local = variables.get(params[i].name());
                if (params[i].variadic()) {
                    b.emitBindVariadic(local, i);
                    continue;
                }
                b.beginBindParameter(local, i);
                Object defaultValue = param.get("default");
                if (defaultValue == null) {
                    b.emitArgument(i);
                } else {
                    b.beginConditional();
                    b.emitArgumentGiven(i);
                    b.emitArgument(i);
                    constant(defaultValue, param);
                    b.endConditional();
                }
                b.endBindParameter();
            }
            for (int i = 0; i < captures.length; i++) {
                b.emitBindCapture(variables.get(captures[i]), i, captureByRef[i]);
            }
            if (generator) {
                generatorLocal = b.createLocal();
                b.emitGeneratorStart(generatorLocal);
            }
            if (source != null) {
                b.endSourceSectionUnavailable();
            }
        }

        /** A statement's source section: its line, as the tree's statements carry it (StmtNode.setLine). */
        private void beginLine(int line) {
            if (source != null) {
                b.beginSourceSection(lineStart(line), lineLength(line));
            }
        }

        private void endLine(int line) {
            if (source != null) {
                b.endSourceSection();
            }
        }

        private int lineStart(int line) {
            return line >= 1 && line <= source.getLineCount() ? source.getLineStartOffset(line) : 0;
        }

        private int lineLength(int line) {
            return line >= 1 && line <= source.getLineCount() ? source.getLineLength(line) : 0;
        }

        // ---- statements ----

        private void statements(Ast list) {
            if (list == null) {
                return;
            }
            if (!list.is("AST_STMT_LIST")) {
                statement(list);
                return;
            }
            b.beginBlock();
            for (Object child : list.children()) {
                if (child instanceof Ast statement) {
                    if (statement.is("AST_LABEL")) {
                        if (skip != null) {
                            String key = key(statement);
                            if (skip.contains(key)) {
                                continue;
                            }
                            dispatched.addLast(key);
                        }
                        throw new Unsupported("goto label", statement);
                    }
                    statement(statement);
                }
            }
            b.endBlock();
        }

        /** A statement in its line's section (the tree's statement() sets every line but a list's). */
        private void statement(Ast node) {
            if (node.is("AST_STMT_LIST")) {
                statements(node);
                return;
            }
            String key = null;
            if (skip != null) {
                key = key(node);
                if (skip.contains(key)) {
                    explore(node, false);
                    return;
                }
                dispatched.addLast(key);
            }
            beginLine(node.line);
            statementBody(node);
            endLine(node.line);
            if (key != null) {
                dispatched.removeLast();
            }
        }

        // ---- report=all: leaving a node out ----

        /** A node's place in a run: its kind, line, and how many such nodes the run met before it there. */
        private String key(Ast node) {
            String place = node.kind + ":" + node.line;
            return place + ":" + met.merge(place, 1, Integer::sum);
        }

        /** A node left out: its parts translated alone in its place, then a null if it was a value. */
        private void explore(Ast node, boolean value) {
            b.beginBlock();
            exploreParts(node);
            if (value) {
                b.emitLoadConstant(PhpNull.NULL);
            }
            b.endBlock();
        }

        private void exploreParts(Ast node) {
            for (Object part : node.children()) {
                explorePart(part, false);
            }
            Map<String, Object> named = node.namedOrNull();
            if (named != null) {
                for (Map.Entry<String, Object> part : named.entrySet()) {
                    explorePart(part.getValue(), part.getKey().equals("stmts"));
                }
            }
        }

        /** A part: statements as statements, the lists and pieces of a construct by their parts, others as values. */
        private void explorePart(Object part, boolean statementList) {
            if (!(part instanceof Ast ast)) {
                return;
            }
            switch (ast.kind) {
                // names and types, and bodies of their own (another function's locals)
                case "AST_NAME", "AST_NAME_LIST", "AST_PARAM_LIST", "AST_CLOSURE_USES", "AST_TYPE", "AST_NULLABLE_TYPE",
                     "AST_TYPE_UNION", "AST_TYPE_INTERSECTION", "AST_ATTRIBUTE_LIST", "AST_CLOSURE", "AST_ARROW_FUNC",
                     "AST_CLASS", "AST_FUNC_DECL" -> {
                }
                case "AST_ARG_LIST", "AST_EXPR_LIST", "AST_ARRAY_ELEM", "AST_UNPACK", "AST_NAMED_ARG", "AST_IF_ELEM",
                     "AST_SWITCH_LIST", "AST_SWITCH_CASE", "AST_CATCH_LIST", "AST_CATCH", "AST_MATCH_ARM_LIST",
                     "AST_MATCH_ARM", "AST_STATIC_VAR", "AST_PROP_ELEM", "AST_CONST_ELEM" -> exploreParts(ast);
                default -> {
                    if (statementList || ast.is("AST_STMT_LIST")) {
                        statements(ast);
                    } else {
                        expression(ast);
                    }
                }
            }
        }

        private void statementBody(Ast node) {
            switch (node.kind) {
                case "AST_ECHO":
                    b.beginEcho();
                    expression(node.get("expr"));
                    b.endEcho();
                    break;
                case "AST_IF":
                    ifChain(node, 0);
                    break;
                case "AST_WHILE":
                    whileLoop(node);
                    break;
                case "AST_DO_WHILE":
                    doWhileLoop(node);
                    break;
                case "AST_FOR":
                    forLoop(node);
                    break;
                case "AST_FOREACH":
                    foreach(node);
                    break;
                case "AST_BREAK":
                case "AST_CONTINUE": {
                    int depth = node.get("depth") instanceof Long d ? d.intValue() : 1;
                    if (depth < 1 || depth > loops.size()) {
                        throw new Unsupported("break out of " + depth + " levels", node);
                    }
                    if (loops.size() - depth < loopsOutsideFinally) {
                        // php-src's compile error "jump out of a finally block is disallowed"
                        throw new Unsupported("break or continue out of a finally block", node);
                    }
                    Loop loop = loops.get(loops.size() - depth);
                    b.emitBranch(node.is("AST_CONTINUE") ? loop.continueLabel() : loop.breakLabel());
                    break;
                }
                case "AST_RETURN":
                    returnValue(node.get("expr"), node);
                    break;
                case "AST_UNSET":
                    if (node.node("var").is("AST_PROP")) {
                        unsetProp(node.node("var"));
                    } else {
                        b.emitUnsetVar(variable(node.node("var"), "unset of"));
                    }
                    break;
                case "AST_TRY":
                    tryStatement(node);
                    break;
                default:
                    expressionStatement(node);
            }
        }

        /** An expression whose value is dropped: a variable's assignment or step without the result. */
        private void expressionStatement(Object value) {
            if (value instanceof Ast node && node.get("var") instanceof Ast target && target.is("AST_VAR")) {
                switch (node.kind) {
                    case "AST_ASSIGN" -> {
                        b.beginWriteVarStatement(variable(target, "assignment to"));
                        expression(node.get("expr"));
                        b.endWriteVarStatement();
                        return;
                    }
                    case "AST_ASSIGN_OP" -> {
                        if (node.flags != 260) {
                            b.beginCompoundVarStatement(variable(target, "compound assignment to"), node.flags);
                            expression(node.get("expr"));
                            b.endCompoundVarStatement();
                            return;
                        }
                    }
                    case "AST_PRE_INC", "AST_PRE_DEC", "AST_POST_INC", "AST_POST_DEC" -> {
                        b.emitIncDecVarStatement(variable(target, "increment of"),
                                node.is("AST_PRE_INC") || node.is("AST_POST_INC"));
                        return;
                    }
                    default -> {
                    }
                }
            }
            expression(value);
        }

        private void returnValue(Object value, Ast node) {
            b.beginReturn();
            if (value == null) {
                b.emitLoadConstant(PhpNull.NULL);
            } else {
                expression(value);
            }
            b.endReturn();
        }

        /** if / elseif / else: the chain is one statement, the `if`'s (as the tree's nested IfNodes). */
        private void ifChain(Ast node, int index) {
            Ast element = (Ast) node.child(index);
            Object condition = element.get("cond");
            if (condition == null) {
                statements(element.node("stmts"));
                return;
            }
            boolean more = index + 1 < node.size();
            if (more) {
                b.beginIfThenElse();
            } else {
                b.beginIfThen();
            }
            condition(condition);
            statements(element.node("stmts"));
            if (more) {
                ifChain(node, index + 1);
                b.endIfThenElse();
            } else {
                b.endIfThen();
            }
        }

        private void whileLoop(Ast node) {
            b.beginBlock();
            Loop loop = new Loop(b.createLabel(), null);
            b.beginWhile();
            condition(node.get("cond"));
            loopBody(loop, node.node("stmts"), null);
            b.endWhile();
            b.emitLabel(loop.breakLabel());
            b.endBlock();
        }

        /** do { body } while (cond): the body, then the condition, which `continue` goes to. */
        private void doWhileLoop(Ast node) {
            b.beginBlock();
            Loop loop = new Loop(b.createLabel(), null);
            b.beginWhile();
            b.emitLoadConstant(Boolean.TRUE);
            // the condition decides the branch itself: as `if (!(a && b)) break`, the negated short circuit made
            // compiled code deoptimize at every exit
            loopBody(loop, node.node("stmts"), () -> {
                b.beginIfThenElse();
                condition(node.get("cond"));
                b.beginBlock();
                b.endBlock();
                b.emitBranch(loop.breakLabel());
                b.endIfThenElse();
            });
            b.endWhile();
            b.emitLabel(loop.breakLabel());
            b.endBlock();
        }

        /** for (init; cond; step): lists whose last condition decides; `continue` goes to the step. */
        private void forLoop(Ast node) {
            b.beginBlock();
            Ast init = node.node("init");
            if (init != null) {
                for (Object expression : init.children()) {
                    expressionStatement(expression);
                }
            }
            Loop loop = new Loop(b.createLabel(), null);
            b.beginWhile();
            Ast conditions = node.node("cond");
            if (conditions == null || conditions.size() == 0) {
                b.emitLoadConstant(Boolean.TRUE);
            } else {
                b.beginBlock();
                for (int i = 0; i < conditions.size() - 1; i++) {
                    expression(conditions.child(i));
                }
                condition(conditions.child(conditions.size() - 1));
                b.endBlock();
            }
            Ast step = node.node("loop");
            loopBody(loop, node.node("stmts"), step == null ? null : () -> {
                for (Object expression : step.children()) {
                    expressionStatement(expression);
                }
            });
            b.endWhile();
            b.emitLabel(loop.breakLabel());
            b.endBlock();
        }

        /** A loop's body, then (after the label `continue` goes to) what a step of the loop does next. */
        private void loopBody(Loop outer, Ast body, Runnable afterContinue) {
            b.beginBlock();
            Loop loop = new Loop(outer.breakLabel(), b.createLabel());
            loops.add(loop);
            statements(body);
            loops.remove(loops.size() - 1);
            b.emitLabel(loop.continueLabel());
            if (afterContinue != null) {
                afterContinue.run();
            }
            b.endBlock();
        }

        /** foreach by value into variables (ForeachNode): the loop holds the array until it ends, however it ends. */
        private void foreach(Ast node) {
            Ast value = node.node("value");
            if (value.is("AST_REF")) {
                throw new Unsupported("foreach by reference", node);
            }
            BytecodeLocal valueVariable = variable(value, "foreach into");
            BytecodeLocal keyVariable = node.get("key") == null ? null : variable(node.node("key"), "foreach key");
            b.beginBlock();
            BytecodeLocal state = b.createLocal();
            BytecodeLocal position = b.createLocal();
            if (firstRun) {
                foreachStates.add(LocalAccessor.constantOf(state));
            }
            b.beginForeachStart(state, position);
            expression(node.get("expr"));
            b.endForeachStart();
            Loop loop = new Loop(b.createLabel(), null);
            b.beginTryFinally(() -> b.emitForeachEnd(state, position));
            b.beginWhile();
            b.beginForeachNext(position, valueVariable, keyVariable == null ? valueVariable : keyVariable,
                    keyVariable != null);
            b.emitLoadLocal(state);
            b.endForeachNext();
            loopBody(loop, node.node("stmts"), null);
            b.endWhile();
            b.endTryFinally();
            b.emitLabel(loop.breakLabel());
            b.endBlock();
        }

        /**
         * try / catch / finally (TryNode). The finally block is the otherwise of a TryCatchOtherwise, emitted at
         * each way out of the try and its catch blocks (the end, return, break, continue); an exception leaving them
         * runs it in the catch, unless it is a bailout, then goes on, and an exception the finally block throws
         * takes the pending one as its previous. A return in the finally block drops what was pending, as the DSL's
         * early exits do; php-src refuses a break or continue out of it.
         */
        private void tryStatement(Ast node) {
            Ast finallyBody = node.node("finally");
            if (finallyBody == null) {
                tryCatch(node);
                return;
            }
            // the loops in the finally block are its own, wherever it is emitted
            List<Loop> around = new ArrayList<>(loops);
            Runnable finallyCode = () -> {
                List<Loop> inner = new ArrayList<>(loops);
                int outside = loopsOutsideFinally;
                loops.clear();
                loops.addAll(around);
                loopsOutsideFinally = around.size();
                statements(finallyBody);
                loopsOutsideFinally = outside;
                loops.clear();
                loops.addAll(inner);
            };
            b.beginTryCatchOtherwise(finallyCode);
            tryCatch(node);
            b.beginBlock();
            BytecodeLocal pending = b.createLocal();
            b.beginStoreLocal(pending);
            b.emitLoadException();
            b.endStoreLocal();
            b.beginIfThen();
            b.beginFinallyRuns();
            b.emitLoadLocal(pending);
            b.endFinallyRuns();
            b.beginTryCatch();
            finallyCode.run();
            b.beginThrowFromFinally();
            b.emitLoadException();
            b.emitLoadLocal(pending);
            b.endThrowFromFinally();
            b.endTryCatch();
            b.endIfThen();
            b.beginRethrow();
            b.emitLoadLocal(pending);
            b.endRethrow();
            b.endBlock();
            b.endTryCatchOtherwise();
        }

        /**
         * The try block and its catch blocks (TryNode.tryCatch): the exception, a runtime error made into its PHP
         * object, goes to the first catch block one of whose classes it is an instance of; with none, it goes on.
         */
        private void tryCatch(Ast node) {
            Ast catches = node.node("catches");
            if (catches == null || catches.size() == 0) {
                statements(node.node("try"));
                return;
            }
            b.beginTryCatch();
            statements(node.node("try"));
            b.beginBlock();
            BytecodeLocal exception = b.createLocal();
            BytecodeLocal thrown = b.createLocal();
            b.beginStoreLocal(exception);
            b.emitLoadException();
            b.endStoreLocal();
            b.beginStoreLocal(thrown);
            b.beginCaughtThrowable();
            b.emitLoadLocal(exception);
            b.endCaughtThrowable();
            b.endStoreLocal();
            catchClauses(catches, 0, exception, thrown);
            b.endBlock();
            b.endTryCatch();
        }

        private void catchClauses(Ast catches, int index, BytecodeLocal exception, BytecodeLocal thrown) {
            if (index == catches.size()) {
                b.beginRethrow();
                b.emitLoadLocal(exception);
                b.endRethrow();
                return;
            }
            Ast handler = (Ast) catches.child(index);
            Ast classes = handler.node("class");
            String[] types = (String[]) made(() -> {
                String[] lower = new String[classes.size()];
                for (int i = 0; i < lower.length; i++) {
                    Ast name = (Ast) classes.child(i);
                    lower[i] = Str.lower(names.className((String) name.get("name"), name.flags));
                }
                return lower;
            });
            BytecodeLocal target = handler.get("var") == null ? null : variable(handler.node("var"), "catch into");
            b.beginIfThenElse();
            b.beginCatchMatches(types);
            b.emitLoadLocal(thrown);
            b.endCatchMatches();
            b.beginBlock();
            if (target != null) {
                b.beginWriteVarStatement(target);
                b.emitLoadLocal(thrown);
                b.endWriteVarStatement();
            }
            statements(handler.node("stmts"));
            b.endBlock();
            catchClauses(catches, index + 1, exception, thrown);
            b.endIfThenElse();
        }

        // ---- expressions ----

        /** A condition: the value's truthiness, unless it is a boolean already. */
        private void condition(Object value) {
            if (isBooleanValued(value)) {
                expression(value);
                return;
            }
            b.beginToBool();
            expression(value);
            b.endToBool();
        }

        private boolean isBooleanValued(Object value) {
            if (!(value instanceof Ast node)) {
                return false;
            }
            return switch (node.kind) {
                case "AST_BINARY_OP" -> switch (node.flags) {
                    case 16, 17, 18, 19, 20, 21, 256, 257, 258, 259 -> true;
                    default -> false;
                };
                case "AST_UNARY_OP" -> node.flags == 14;
                default -> false;
            };
        }

        void expression(Object value) {
            if (skip == null || !(value instanceof Ast node)) {
                expressionBody(value);
                return;
            }
            String key = key(node);
            if (skip.contains(key)) {
                explore(node, true);
                return;
            }
            dispatched.addLast(key);
            expressionBody(value);
            dispatched.removeLast();
        }

        private void expressionBody(Object value) {
            if (constantShaped(value)) {
                constant(value, value instanceof Ast ast ? ast : null);
                return;
            }
            Ast node = (Ast) value;
            switch (node.kind) {
                case "AST_VAR":
                    if (isThis(node)) {
                        b.emitReadThis();
                    } else {
                        b.emitReadVar(variable(node, "read of"));
                    }
                    break;
                case "AST_PROP":
                    readProp(node);
                    break;
                case "AST_ISSET":
                    isset(node.get("var"));
                    break;
                case "AST_DIM":
                    if (node.get("dim") == null) {
                        throw new Unsupported("[] for reading", node);
                    }
                    b.beginReadElement();
                    expression(node.get("expr"));
                    key(node.get("dim"));
                    b.endReadElement();
                    break;
                case "AST_ASSIGN":
                    assignment(node);
                    break;
                case "AST_ASSIGN_OP":
                    if (node.flags == 260) {
                        throw new Unsupported("??=", node);
                    }
                    if (node.node("var").is("AST_PROP")) {
                        Ast target = node.node("var");
                        b.beginCompoundProp(writtenPropSite(target), node.flags);
                        expression(node.get("expr"));
                        expression(target.get("expr"));
                        b.endCompoundProp();
                        break;
                    }
                    b.beginCompoundVar(variable(node.node("var"), "compound assignment to"), node.flags);
                    expression(node.get("expr"));
                    b.endCompoundVar();
                    break;
                case "AST_PRE_INC":
                case "AST_PRE_DEC":
                case "AST_POST_INC":
                case "AST_POST_DEC": {
                    boolean increment = node.is("AST_PRE_INC") || node.is("AST_POST_INC");
                    boolean prefix = node.is("AST_PRE_INC") || node.is("AST_PRE_DEC");
                    if (node.node("var").is("AST_PROP")) {
                        Ast target = node.node("var");
                        b.beginIncDecProp(writtenPropSite(target), increment, prefix);
                        expression(target.get("expr"));
                        b.endIncDecProp();
                        break;
                    }
                    b.emitIncDecVar(variable(node.node("var"), "increment of"), increment, prefix);
                    break;
                }
                case "AST_BINARY_OP":
                    binary(node);
                    break;
                case "AST_UNARY_OP":
                    unary(node);
                    break;
                case "AST_CONDITIONAL":
                    if (node.get("true") == null) {
                        b.beginElvis();
                        expression(node.get("cond"));
                        expression(node.get("false"));
                        b.endElvis();
                    } else {
                        b.beginConditional();
                        condition(node.get("cond"));
                        expression(node.get("true"));
                        expression(node.get("false"));
                        b.endConditional();
                    }
                    break;
                case "AST_ARRAY":
                    arrayLiteral(node);
                    break;
                case "AST_CALL":
                    call(node, false);
                    break;
                case "AST_MATCH":
                    match(node);
                    break;
                case "AST_YIELD":
                    yieldValue(node);
                    break;
                case "AST_YIELD_FROM":
                    yieldFrom(node);
                    break;
                case "AST_THROW":
                    b.beginThrow();
                    expression(node.get("expr"));
                    b.endThrow();
                    break;
                default:
                    throw new Unsupported("expression", node);
            }
        }

        /**
         * match (MatchNode): the subject in a local, then the arms in order, each a branch on its conditions compared
         * to the subject (`===`, until one is identical); after the last, the default arm or UnhandledMatchError.
         */
        private void match(Ast node) {
            if (Translator.secondDefaultArm(node) != null) {
                // a compile error, which the tree's translation raises
                throw new Unsupported("match with two default arms", node);
            }
            Ast armList = node.node("stmts");
            List<Ast> arms = new ArrayList<>();
            Ast defaultArm = null;
            List<Object> folded = new ArrayList<>();
            for (int i = 0; armList != null && i < armList.size(); i++) {
                Ast arm = (Ast) armList.child(i);
                if (arm.get("cond") == null) {
                    defaultArm = arm;
                    continue;
                }
                arms.add(arm);
                for (Object condition : arm.node("cond").children()) {
                    folded.add(constantShaped(condition) ? constantValue(condition)
                            : constants.foldedArithmetic(condition));
                }
            }
            int errorLine = defaultArm == null ? Translator.matchErrorLine(node, folded) : 0;
            b.beginBlock();
            BytecodeLocal subject = b.createLocal();
            b.beginStoreLocal(subject);
            expression(node.get("cond"));
            b.endStoreLocal();
            matchArms(arms, 0, subject, defaultArm, errorLine);
            b.endBlock();
        }

        private void matchArms(List<Ast> arms, int index, BytecodeLocal subject, Ast defaultArm, int errorLine) {
            if (index == arms.size()) {
                if (defaultArm != null) {
                    expression(defaultArm.get("expr"));
                } else {
                    b.beginMatchError(errorLine);
                    b.emitLoadLocal(subject);
                    b.endMatchError();
                }
                return;
            }
            Ast arm = arms.get(index);
            List<Object> conditions = arm.node("cond").children();
            b.beginConditional();
            if (conditions.size() > 1) {
                b.beginOr();
            }
            for (Object condition : conditions) {
                b.beginIdentical();
                b.emitLoadLocal(subject);
                expression(condition);
                b.endIdentical();
            }
            if (conditions.size() > 1) {
                b.endOr();
            }
            expression(arm.get("expr"));
            matchArms(arms, index + 1, subject, defaultArm, errorLine);
            b.endConditional();
        }

        // ---- generators ----

        /**
         * `yield`, `yield value`, `yield key => value` (zend_compile_yield): the key, then the value; in a generator
         * yielding by reference, a variable's reference. The result is what the generator is resumed with.
         */
        private void yieldValue(Ast node) {
            if (generatorLocal == null) {
                throw new Unsupported("yield outside a generator", node);
            }
            b.beginResumed();
            b.emitLoadLocal(generatorLocal);
            b.beginYieldValue();
            b.emitLoadLocal(generatorLocal);
            Object key = node.get("key");
            if (key == null) {
                b.emitLoadConstant(PhpGenerator.SUSPENDED);
            } else {
                expression(key);
            }
            Object value = node.get("value");
            if (value == null) {
                b.emitLoadConstant(PhpNull.NULL);
            } else if (function.returnsRef()) {
                yieldedReference(value);
            } else {
                expression(value);
            }
            b.endYieldValue();
            b.endResumed();
        }

        /**
         * What a generator yielding by reference yields (ZEND_YIELD with ZEND_ACC_RETURN_REFERENCE): a variable's
         * reference; anything else is yielded as a value, with php-src's notice.
         */
        private void yieldedReference(Object value) {
            if (value instanceof Ast ast && ast.is("AST_VAR")) {
                b.emitRefVar(variable(ast, "yield by reference of"));
                return;
            }
            if (value instanceof Ast ast && (ast.is("AST_DIM") || ast.is("AST_PROP") || ast.is("AST_STATIC_PROP")
                    || Translator.isCall(ast))) {
                throw new Unsupported("yield by reference of an element, property or call", ast);
            }
            b.beginNotReferenceYielded();
            expression(value);
            b.endNotReferenceYielded();
        }

        /** `yield from source`: the generator takes source's elements; the result is the yield from's value. */
        private void yieldFrom(Ast node) {
            if (generatorLocal == null) {
                throw new Unsupported("yield from outside a generator", node);
            }
            b.beginResumed();
            b.emitLoadLocal(generatorLocal);
            b.beginYieldFrom();
            b.emitLoadLocal(generatorLocal);
            expression(node.get("expr"));
            b.endYieldFrom();
            b.endResumed();
        }

        /** An array key: a literal one normalized once, as DimNode's constant key. */
        private void key(Object dim) {
            if (constantShaped(dim)) {
                Object constant = constantValue(dim);
                if (!(constant instanceof PhpArray)) {
                    b.emitLoadConstant(ArrayKey.of(constant));
                    return;
                }
            }
            expression(dim);
        }

        private void assignment(Ast node) {
            Ast target = node.node("var");
            if (target.is("AST_PROP")) {
                writeProp(target, node.get("expr"));
                return;
            }
            if (target.is("AST_DIM") && target.node("expr").is("AST_PROP")) {
                writePropElement(target, node.get("expr"));
                return;
            }
            if (target.is("AST_VAR")) {
                b.beginWriteVar(variable(target, "assignment to"));
                expression(node.get("expr"));
                b.endWriteVar();
                return;
            }
            if (target.is("AST_DIM") && target.node("expr").is("AST_VAR")) {
                BytecodeLocal container = variable(target.node("expr"), "element of");
                if (target.get("dim") == null) {
                    b.beginAppendElement(container);
                    expression(node.get("expr"));
                    b.endAppendElement();
                } else {
                    b.beginWriteElement(container);
                    expression(node.get("expr"));
                    key(target.get("dim"));
                    b.endWriteElement();
                }
                return;
            }
            throw new Unsupported("assignment target", target);
        }

        private void binary(Ast node) {
            switch (node.flags) {
                case 259 -> {
                    b.beginAnd();
                    expression(node.get("left"));
                    expression(node.get("right"));
                    b.endAnd();
                    return;
                }
                case 258 -> {
                    b.beginOr();
                    expression(node.get("left"));
                    expression(node.get("right"));
                    b.endOr();
                    return;
                }
                case 15, 260 -> throw new Unsupported("binary operator " + node.flags, node);
                default -> {
                }
            }
            beginBinary(node);
            expression(node.get("left"));
            expression(node.get("right"));
            endBinary(node);
        }

        private void beginBinary(Ast node) {
            switch (node.flags) {
                case 1 -> b.beginAdd();
                case 2 -> b.beginSub();
                case 3 -> b.beginMul();
                case 4 -> b.beginDiv();
                case 5 -> b.beginMod();
                case 12 -> b.beginPow();
                case 8 -> b.beginConcat();
                case 9 -> b.beginBitOr();
                case 10 -> b.beginBitAnd();
                case 11 -> b.beginBitXor();
                case 6 -> b.beginShiftLeft();
                case 7 -> b.beginShiftRight();
                case 16 -> b.beginIdentical();
                case 17 -> b.beginNotIdentical();
                case 18 -> b.beginEqual();
                case 19 -> b.beginNotEqual();
                case 20 -> b.beginLess();
                case 21 -> b.beginLessEqual();
                case 256 -> b.beginGreater();
                case 257 -> b.beginGreaterEqual();
                case 170 -> b.beginSpaceship();
                default -> throw new Unsupported("binary operator " + node.flags, node);
            }
        }

        private void endBinary(Ast node) {
            switch (node.flags) {
                case 1 -> b.endAdd();
                case 2 -> b.endSub();
                case 3 -> b.endMul();
                case 4 -> b.endDiv();
                case 5 -> b.endMod();
                case 12 -> b.endPow();
                case 8 -> b.endConcat();
                case 9 -> b.endBitOr();
                case 10 -> b.endBitAnd();
                case 11 -> b.endBitXor();
                case 6 -> b.endShiftLeft();
                case 7 -> b.endShiftRight();
                case 16 -> b.endIdentical();
                case 17 -> b.endNotIdentical();
                case 18 -> b.endEqual();
                case 19 -> b.endNotEqual();
                case 20 -> b.endLess();
                case 21 -> b.endLessEqual();
                case 256 -> b.endGreater();
                case 257 -> b.endGreaterEqual();
                case 170 -> b.endSpaceship();
                default -> throw new Unsupported("binary operator " + node.flags, node);
            }
        }

        private void unary(Ast node) {
            switch (node.flags) {
                case 14 -> {
                    b.beginNot();
                    condition(node.get("expr"));
                    b.endNot();
                }
                case 13 -> {
                    b.beginBitNot();
                    expression(node.get("expr"));
                    b.endBitNot();
                }
                case 261 -> {
                    b.beginPlus();
                    expression(node.get("expr"));
                    b.endPlus();
                }
                case 262 -> {
                    b.beginNegate();
                    expression(node.get("expr"));
                    b.endNegate();
                }
                default -> throw new Unsupported("unary operator " + node.flags, node);
            }
        }

        /** A non-constant array literal: a list of values, or every element keyed; no references or spreads. */
        private void arrayLiteral(Ast node) {
            boolean keyed = false;
            boolean unkeyed = false;
            for (Object child : node.children()) {
                if (!(child instanceof Ast element) || element.is("AST_UNPACK")
                        || (element.flags & ARRAY_ELEM_REF) != 0) {
                    throw new Unsupported("array element", node);
                }
                if (element.get("key") == null) {
                    unkeyed = true;
                } else {
                    keyed = true;
                }
            }
            if (keyed && unkeyed) {
                throw new Unsupported("array with keyed and unkeyed elements", node);
            }
            List<Object> elements = node.children();
            LiteralSite site = (LiteralSite) made(() -> new LiteralSite(elements.size()));
            // LiteralEnd(Literal...(Literal...(LiteralStart, e1), e2)...): each element goes in as it is evaluated
            b.beginLiteralEnd(site);
            for (int i = 0; i < elements.size(); i++) {
                if (keyed) {
                    b.beginLiteralPut();
                } else {
                    b.beginLiteralAppend();
                }
            }
            b.emitLiteralStart(site);
            for (Object child : elements) {
                Ast element = (Ast) child;
                if (keyed) {
                    expression(element.get("key"));
                    expression(element.get("value"));
                    b.endLiteralPut();
                } else {
                    expression(element.get("value"));
                    b.endLiteralAppend();
                }
            }
            b.endLiteralEnd();
        }

        // ---- $this and properties ----

        /**
         * A property access's site: its name (null when computed), the class the code is written in, and how the
         * calling scope is found (PropNode's, as Translator.place makes it).
         */
        private PropSite propSite(Ast node, String name) {
            boolean scopeInFrame = function.getName().startsWith(CLOSURE_PREFIX);
            boolean thisInMethod = isThis(node.get("expr")) && !scopeInFrame;
            return (PropSite) made(() -> new PropSite(name == null ? null : Str.sourceKey(name),
                    owner == null ? null : Str.lower(owner.name()), scopeInFrame, thisInMethod));
        }

        /** `$obj->name`, `$obj->$name` (PropNode.execute): the object, then the name. */
        private void readProp(Ast node) {
            if (node.get("prop") instanceof String name) {
                b.beginReadProp(propSite(node, name));
                expression(node.get("expr"));
                b.endReadProp();
                return;
            }
            b.beginReadPropNamed(propSite(node, null));
            expression(node.get("expr"));
            propName(node.get("prop"));
            b.endReadPropNamed();
        }

        private void propName(Object prop) {
            b.beginPropName();
            expression(prop);
            b.endPropName();
        }

        /**
         * The site of a property written by a compound assignment, an increment or an element write, whose object
         * is evaluated after the value (php-src delays fetching it): a constant name, and an object that is a
         * variable, $this or a property of one; other forms stay on the tree.
         */
        private PropSite writtenPropSite(Ast target) {
            if (!(target.get("prop") instanceof String name)) {
                throw new Unsupported("write to a property by a computed name", target);
            }
            if (!delayedObject(target.get("expr"))) {
                throw new Unsupported("write to a property of an expression", target);
            }
            return propSite(target, name);
        }

        /** An object php-src fetches as the write runs (zend_delayed_compile_var): a variable or a property chain. */
        private boolean delayedObject(Object object) {
            if (!(object instanceof Ast ast)) {
                return false;
            }
            if (ast.is("AST_VAR")) {
                return ast.get("name") instanceof String;
            }
            return ast.is("AST_PROP") && ast.get("prop") instanceof String && delayedObject(ast.get("expr"));
        }

        /**
         * `$obj->name = value` (AssignNode on a PropNode): the value, then the name, then the object, as the tree
         * evaluates them; with a delayed object (a variable, a property chain) that is php-src's order too.
         */
        private void writeProp(Ast target, Object value) {
            if (!delayedObject(target.get("expr"))) {
                throw new Unsupported("write to a property of an expression", target);
            }
            if (target.get("prop") instanceof String name) {
                b.beginWriteProp(propSite(target, name));
                expression(value);
                expression(target.get("expr"));
                b.endWriteProp();
                return;
            }
            b.beginWritePropNamed(propSite(target, null));
            expression(value);
            propName(target.get("prop"));
            expression(target.get("expr"));
            b.endWritePropNamed();
        }

        /**
         * `$obj->name[key] = value`, `$obj->name[] = value` (DimNode.write on a PropNode): the value, the object, the
         * key; a key that is a constant or a variable, which the tree evaluates after making the property writable.
         */
        private void writePropElement(Ast target, Object value) {
            Ast property = target.node("expr");
            PropSite site = writtenPropSite(property);
            Object dim = target.get("dim");
            if (dim == null) {
                b.beginAppendPropElement(site);
                expression(value);
                expression(property.get("expr"));
                b.endAppendPropElement();
                return;
            }
            if (!constantShaped(dim) && !(dim instanceof Ast key && key.is("AST_VAR"))) {
                throw new Unsupported("element of a property by a computed key", target);
            }
            b.beginWritePropElement(site);
            expression(value);
            expression(property.get("expr"));
            key(dim);
            b.endWritePropElement();
        }

        /** `unset($obj->name)` (PropNode.unset): the object, then the name (a constant or a variable). */
        private void unsetProp(Ast target) {
            Object prop = target.get("prop");
            if (prop instanceof String name) {
                b.beginUnsetProp(propSite(target, name));
                expression(target.get("expr"));
                b.endUnsetProp();
                return;
            }
            if (!(prop instanceof Ast ast && ast.is("AST_VAR"))) {
                throw new Unsupported("unset of a property by a computed name", target);
            }
            b.beginUnsetPropNamed(propSite(target, null));
            expression(target.get("expr"));
            propName(prop);
            b.endUnsetPropNamed();
        }

        /**
         * isset($this), isset($obj->name) (TestNodes.IssetNode with one place): the object as the tree's readIfSet
         * reads it, a property of it through __isset; a computed name only from a variable.
         */
        private void isset(Object tested) {
            if (isThis(tested)) {
                b.emitIssetThis();
                return;
            }
            if (!(tested instanceof Ast target) || !target.is("AST_PROP")) {
                throw new Unsupported("isset of", tested instanceof Ast ast ? ast : null);
            }
            Object prop = target.get("prop");
            if (prop instanceof String name) {
                b.beginIssetProp(propSite(target, name));
                issetObject(target.get("expr"));
                b.endIssetProp();
                return;
            }
            if (!(prop instanceof Ast ast && ast.is("AST_VAR"))) {
                throw new Unsupported("isset of a property by a computed name", target);
            }
            b.beginIssetPropNamed(propSite(target, null));
            issetObject(target.get("expr"));
            propName(prop);
            b.endIssetPropNamed();
        }

        /** The object of a property isset() tests: a property of one read as readIfSet; a variable or a value. */
        private void issetObject(Object object) {
            if (object instanceof Ast ast) {
                if (ast.is("AST_PROP") && ast.get("prop") instanceof String name) {
                    b.beginReadPropIfSet(propSite(ast, name));
                    issetObject(ast.get("expr"));
                    b.endReadPropIfSet();
                    return;
                }
                if (ast.is("AST_PROP") || ast.is("AST_NULLSAFE_PROP") || ast.is("AST_DIM")
                        || ast.is("AST_STATIC_PROP")) {
                    throw new Unsupported("isset of a property of", ast);
                }
            }
            expression(object);
        }

        // ---- calls ----

        /**
         * `name(args)` (Translator.call's FunctionCallNode): the function is looked up first, then each argument
         * is evaluated, a variable or element by reference where the callee's parameter is by reference.
         * byRefContext: the result goes where a callee's returned reference is kept (an argument).
         */
        private void call(Ast node, boolean byRefContext) {
            Ast callee = node.node("expr");
            Ast args = node.node("args");
            if (!callee.is("AST_NAME") || ((String) callee.get("name")).contains("::")
                    || Translator.isCallableConvert(node)) {
                throw new Unsupported("call of a callable value", node);
            }
            String[] resolved = names.functionName((String) callee.get("name"), callee.flags);
            String name = resolved[1] != null ? resolved[1] : resolved[0].indexOf('\\') < 0 ? resolved[0] : "";
            String lower = Str.lower(name);
            switch (lower) {
                case "func_get_args", "func_num_args", "call_user_func", "call_user_func_array", "get_class",
                     "get_called_class", "compact", "extract", "get_object_vars", "get_defined_vars" ->
                        throw new Unsupported(lower + "()", node);
                default -> {
                }
            }
            boolean special = resolved[1] == null && constants.compiledSpecially(Str.lower(resolved[0]), args);
            if (!name.isEmpty() && Intrinsics.handles(lower)) {
                if ((lower.equals("count") || lower.equals("sizeof")) && args.size() == 1 && special) {
                    b.beginCount();
                    expression(args.child(0));
                    b.endCount();
                    return;
                }
                throw new Unsupported("built-in compiled in place: " + lower, node);
            }
            CallInfo info = (CallInfo) made(() -> new CallInfo(resolved[0], language.functionId(Str.lower(resolved[0])),
                    resolved[1] == null ? -1 : language.functionId(Str.lower(resolved[1])), special));
            boolean needsCallee = false;
            for (Object arg : args.children()) {
                if (arg instanceof Ast ast && (ast.is("AST_UNPACK") || ast.is("AST_NAMED_ARG")
                        || ast.is("AST_CALLABLE_CONVERT"))) {
                    throw new Unsupported("argument", ast);
                }
                needsCallee |= !plainArgument(arg);
            }
            if (byRefContext) {
                b.beginCallRef(info);
            } else {
                b.beginCall(info);
            }
            // an argument that may go by reference asks the callee about its parameter: the callee stays on the
            // operand stack for it
            StackValue calleeValue = null;
            if (needsCallee) {
                b.beginBindStackValue();
                b.emitResolveFunction(info);
                calleeValue = b.endBindStackValue();
            } else {
                b.emitResolveFunction(info);
            }
            for (int i = 0; i < args.size(); i++) {
                argument(args.child(i), i, calleeValue);
            }
            if (byRefContext) {
                b.endCallRef();
            } else {
                b.endCall();
            }
        }

        /** An argument whose value is passed whatever the callee's parameter: no place and no call. */
        private boolean plainArgument(Object arg) {
            if (!(arg instanceof Ast ast)) {
                return true;
            }
            if (isThis(ast)) {
                // passed as a value, as the tree's arguments pass it
                return true;
            }
            if (ast.is("AST_VAR") || ast.is("AST_DIM") || ast.is("AST_PROP") || ast.is("AST_STATIC_PROP")) {
                return false;
            }
            return !(Translator.isCall(ast) && !constants.compiledInPlace(ast));
        }

        private void argument(Object arg, int index, StackValue callee) {
            if (plainArgument(arg)) {
                expression(arg);
                return;
            }
            Ast ast = (Ast) arg;
            if (ast.is("AST_VAR")) {
                b.beginSendVar(variable(ast, "argument"), index);
                b.emitLoadStackValue(callee);
                b.endSendVar();
            } else if (ast.is("AST_DIM") && ast.node("expr").is("AST_VAR") && ast.get("dim") != null) {
                b.beginSendElement(variable(ast.node("expr"), "argument element of"), index);
                b.emitLoadStackValue(callee);
                key(ast.get("dim"));
                b.endSendElement();
            } else if (ast.is("AST_PROP") && ast.get("prop") instanceof String name) {
                b.beginSendProp(propSite(ast, name), index);
                b.emitLoadStackValue(callee);
                expression(ast.get("expr"));
                b.endSendProp();
            } else if (ast.is("AST_CALL")) {
                b.beginSendCall(index);
                b.emitLoadStackValue(callee);
                call(ast, true);
                b.endSendCall();
            } else {
                throw new Unsupported("argument", ast);
            }
        }

        // ---- variables and constants ----

        /** A plain variable's local; $this, superglobals and variable variables aren't covered. */
        private BytecodeLocal variable(Ast node, String what) {
            if (!node.is("AST_VAR") || !(node.get("name") instanceof String name)) {
                throw new Unsupported(what + " a place other than a variable", node);
            }
            BytecodeLocal local = variables.get(name);
            if (local == null) {
                throw new Unsupported(what + " $" + name, node);
            }
            return local;
        }

        /** Whether the tree translates an expression to a constant: literals and what folds from them. */
        private boolean constantShaped(Object value) {
            if (!(value instanceof Ast node)) {
                return true;
            }
            return switch (node.kind) {
                case "AST_CONST", "AST_MAGIC_CONST" -> constantValue(node) != null;
                case "AST_ARRAY" -> {
                    for (Object child : node.children()) {
                        if (!(child instanceof Ast element) || element.is("AST_UNPACK")
                                || (element.flags & ARRAY_ELEM_REF) != 0
                                || !constantShaped(element.get("key")) || !constantShaped(element.get("value"))) {
                            yield false;
                        }
                    }
                    yield true;
                }
                case "AST_UNARY_OP" -> (node.flags == 262 || node.flags == 261)
                        && constantShaped(node.get("expr")) && constantValue(node) != null;
                case "AST_BINARY_OP" -> node.flags == 8 && constantShaped(node.get("left"))
                        && constantShaped(node.get("right")) && constantValue(node) != null;
                default -> false;
            };
        }

        /**
         * The value the tree's translation of a constant-shaped expression has (null when it isn't one), made
         * once per place: a constant array is frozen, and every run shares it.
         */
        private Object constantValue(Object value) {
            try {
                return constants.expression(value).constantValue();
            } catch (PhpError notTranslated) {
                // the tree's translation fails the same way, at the function's first call
                throw new Unsupported(notTranslated.getMessage(), value instanceof Ast ast ? ast : null);
            }
        }

        private void constant(Object value, Ast node) {
            if (!constantShaped(value)) {
                throw new Unsupported("default value", node);
            }
            Object constant = made(() -> constantValue(value));
            b.emitLoadConstant(constant);
        }

        /** The object the first run made at this point of the body; made now on the first run. */
        private Object made(Supplier<Object> make) {
            Object object;
            if (madeIndex < made.size()) {
                object = made.get(madeIndex);
            } else {
                object = make.get();
                made.add(object);
            }
            madeIndex++;
            return object;
        }
    }

    /** A loop's labels: break goes after the loop, continue to its next step. */
    private record Loop(BytecodeLabel breakLabel, BytecodeLabel continueLabel) {
    }

    // php-ast flag values (Translator's)
    private static final int ARRAY_ELEM_REF = 1;
    private static final int PROMOTED = 7 | 128;

    /** Whether a body has a finally block, outside the functions and classes declared in it. */
    private static boolean hasFinally(Object node) {
        if (!(node instanceof Ast ast)) {
            return false;
        }
        switch (ast.kind) {
            case "AST_CLOSURE", "AST_ARROW_FUNC", "AST_FUNC_DECL", "AST_CLASS", "AST_METHOD":
                return false;
            case "AST_TRY":
                if (ast.get("finally") != null) {
                    return true;
                }
                break;
            default:
                break;
        }
        for (Object child : ast.children()) {
            if (hasFinally(child)) {
                return true;
            }
        }
        for (Object child : ast.namedValues()) {
            if (hasFinally(child)) {
                return true;
            }
        }
        return false;
    }

    /** The name prefix of closures (Translator's): their calling scope is the bound one. */
    private static final String CLOSURE_PREFIX = "{closure:";

    private static boolean isThis(Object value) {
        return value instanceof Ast node && node.is("AST_VAR") && "this".equals(node.get("name"));
    }

    /** Whether a variable of this name is a local: $this, $GLOBALS and the superglobals are not. */
    private static boolean plainVariable(String name) {
        return !name.equals("this") && !name.equals("GLOBALS") && !PhpContext.SUPERGLOBALS.contains(name);
    }

    /** The variables a body names, in the order it names them; nested functions and classes have their own. */
    private static void collectVariables(Object node, Set<String> names) {
        if (!(node instanceof Ast ast)) {
            return;
        }
        switch (ast.kind) {
            case "AST_CLOSURE", "AST_ARROW_FUNC", "AST_FUNC_DECL", "AST_CLASS", "AST_METHOD":
                return;
            case "AST_VAR":
                if (ast.get("name") instanceof String name && plainVariable(name)) {
                    names.add(name);
                }
                break;
            default:
                break;
        }
        for (Object child : ast.children()) {
            collectVariables(child, names);
        }
        for (Object child : ast.namedValues()) {
            collectVariables(child, names);
        }
    }
}
