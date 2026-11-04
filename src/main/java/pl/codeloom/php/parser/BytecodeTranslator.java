package pl.codeloom.php.parser;

import com.oracle.truffle.api.CallTarget;
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
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.builtins.Registry;
import pl.codeloom.php.bytecode.CallInfo;
import pl.codeloom.php.bytecode.ClassSite;
import pl.codeloom.php.bytecode.DefinedSite;
import pl.codeloom.php.bytecode.IncludeSite;
import pl.codeloom.php.bytecode.LiteralSite;
import pl.codeloom.php.bytecode.MethodSite;
import pl.codeloom.php.bytecode.NamedSite;
import pl.codeloom.php.bytecode.PhpBytecodeRootNode;
import pl.codeloom.php.bytecode.PhpBytecodeRootNodeGen;
import pl.codeloom.php.bytecode.PropSite;
import pl.codeloom.php.bytecode.StaticPropSite;
import pl.codeloom.php.nodes.expr.UnaryNodes.CastNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.nodes.vars.GlobalsDimNode;
import pl.codeloom.php.nodes.vars.VariableVariableNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpType;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    /** php-src's fetch types of a written property's object (writtenObject). */
    /** `(void)`'s cast type (php-ast's IS_VOID). */
    private static final int CAST_VOID = 14;
    private static final int BP_VAR_W = 1;
    private static final int BP_VAR_RW = 2;
    private static final int BP_VAR_UNSET = 5;
    /** A function whose bytecode is printed at its first return (php.bytecode.dump=name), for debugging. */
    private static final String DUMP = System.getProperty("php.bytecode.dump");
    /**
     * For finding a function the bytecode runs differently by halving: php.bytecode.only=BITS:VALUE keeps on the
     * tree every function whose name's hash doesn't end in VALUE's BITS bits.
     */
    private static final String ONLY = System.getProperty("php.bytecode.only");

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
    /** The namespace and imports in effect: the function's, or as a file's statements change them. */
    private Names names;
    private final Names declaredNames;
    /** A generator function's body: a call returns the Generator, which runs the body (GeneratorStart). */
    private final boolean generator;
    /** The body the first run translates, read by the tree translator already; later runs read their own. */
    private Ast firstBody;
    /** A file's top level: its statements read again for a later run of the parser; null for a function. */
    private Supplier<Ast> reread;
    /** eval'd code: a file's top level whose value without `return` is null, not 1. */
    private boolean eval;
    /** A constant expression's root ({@link Thunk}): the expression; null for a function or a file. */
    private Ast thunk;
    /** A function's defaults target ({@link #defaults}): declaration has only the parameter list. */
    private boolean defaults;
    /** Whether the code has a loop, a foreach or a goto label (RunOnceCode), as the first run found. */
    private boolean loops;
    /** What the first run made per place in the body, in the order it met them; later runs take them. */
    private final List<Object> made = new ArrayList<>();
    /** The site numbers of the body's calls by an unqualified name in a namespace (Translator.fallbackSite). */
    private final Map<String, Integer> fallbackSites = new HashMap<>();

    private int fallbackSite(String lowerName) {
        return fallbackSites.computeIfAbsent(lowerName, name -> language.nextFallbackSite());
    }
    /** The variables' locals, made by the first run (later runs number them alike). */
    private LocalAccessor[] variableLocals;
    /** The variables' names, in the order of their locals. */
    private String[] variableNames;
    /** The hidden local keeping the call's variables by name (an include, extract()), or null if none needs it. */
    private LocalAccessor scopeLocal;
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
        this.declaredNames = names;
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
        if (ONLY != null) {
            int bits = Integer.parseInt(ONLY.split(":")[0]);
            int value = Integer.parseInt(ONLY.split(":")[1]);
            if ((display.hashCode() & ((1 << bits) - 1)) != value) {
                report("tree     " + display + ": php.bytecode.only");
                return null;
            }
        }
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
        root.initialize(display, path, internal, translator.functionSection(), params, parameterLocals,
                Translator.checkedReturn(function, generator), translator.variableLocals,
                translator.foreachStates.toArray(new LocalAccessor[0]),
                owner == null ? null : owner.name());
        root.initializeNames(translator.variableNames, translator.scopeLocal);
        if (generator) {
            root.initializeGenerator(function.returnsRef(), hasFinally(body == null ? declaration.body() : body));
        } else if (function.returnsRef()) {
            root.initializeReturnsRef();
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



    /**
     * A file's top level (or eval'd code) on bytecode: its statements, the declarations php-src binds when it
     * compiles the file first. reread gives the file's top level again for a later run of the parser, from what the
     * file's function bodies keep anyway (the export's bytes or the script's text), so the root keeps no tree.
     */
    static PhpBytecodeRootNode translateFile(PhpLanguage language, String path, Source source, boolean internal,
                                             Ast file, Supplier<Ast> reread, boolean eval) {
        BytecodeTranslator translator = new BytecodeTranslator(language, path, source, internal, null, file, null,
                null, path, new PhpFunction.Param[0], new String[0], new boolean[0], Names.GLOBAL, false);
        translator.reread = reread;
        translator.eval = eval;
        PhpBytecodeRootNode root;
        try {
            root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, translator::parse).getNode(0);
        } catch (Unsupported unsupported) {
            throw new PhpError("Unsupported " + unsupported.getMessage() + " in " + path);
        }
        root.initialize(path, path, internal, source == null ? null : source.createSection(0, source.getLength()),
                new PhpFunction.Param[0], new int[0], null, new LocalAccessor[0],
                translator.foreachStates.toArray(new LocalAccessor[0]), null);
        root.initializeFile(translator.loops);
        return root;
    }

    /**
     * A constant expression that doesn't fold (a class constant, a property default, an enum case's value), as
     * ThunkRootNode was: bound to each context's class, evaluated there on first use with the class as static
     * (ARG_STATIC) and as its scope. Its bytecode is made when it is first evaluated: most of a class's constants
     * never are in a request.
     */
    static final class Thunk implements ClassTemplate.Deferred {
        private final PhpLanguage language;
        private final String path;
        private final Source source;
        private final boolean internal;
        private final Ast expression;
        private final Translator.ClassInfo owner;
        private final Names names;
        private volatile CallTarget target;

        Thunk(PhpLanguage language, String path, Source source, boolean internal, Ast expression,
              Translator.ClassInfo owner, Names names) {
            this.language = language;
            this.path = path;
            this.source = source;
            this.internal = internal;
            this.expression = expression;
            this.owner = owner;
            this.names = names;
        }

        @Override
        public PhpClass.Thunk bind(PhpClass staticClass) {
            // the array, not call(Node, Object...): a leading null would be taken for the call location
            return () -> target().call(new Object[] {null, staticClass, null});
        }

        private CallTarget target() {
            CallTarget made = target;
            if (made == null) {
                synchronized (this) {
                    made = target;
                    if (made == null) {
                        String display = (owner == null ? "" : owner.name() + "::") + "{constant expression}";
                        BytecodeTranslator translator = new BytecodeTranslator(language, path, source, internal, null,
                                null, owner, null, display, new PhpFunction.Param[0], new String[0], new boolean[0],
                                names, false);
                        translator.thunk = expression;
                        PhpBytecodeRootNode root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT,
                                translator::parse).getNode(0);
                        root.initialize(display, path, internal, translator.functionSection(),
                                new PhpFunction.Param[0], new int[0], null, new LocalAccessor[0],
                                translator.foreachStates.toArray(new LocalAccessor[0]),
                                owner == null ? null : owner.name());
                        root.initializeThunk();
                        made = root.getCallTarget();
                        target = made;
                    }
                }
            }
            return made;
        }
    }

    /**
     * What makes a user function's defaults target (PhpFunction.getDefaultsTarget): it keeps the declaration's
     * parameter list and line only, not its body.
     */
    static Supplier<RootCallTarget> defaults(PhpLanguage language, String path, Source source, boolean internal,
                                             Ast declaration, Translator.ClassInfo owner, PhpFunction function,
                                             String display, PhpFunction.Param[] params, Names names) {
        Ast paramList = declaration.node("params");
        if (paramList == null) {
            return null;
        }
        Ast header = new Ast(declaration.kind, declaration.flags, declaration.line, declaration.endLine, null,
                Map.of("params", paramList));
        return () -> defaultsTarget(language, path, source, internal, header, owner, function, display, params, names);
    }

    /**
     * The defaults target of a function: a root that fills the arguments a call by name left out, run on the call's
     * frame arguments after every argument is sent and before the function's own target (php-src's
     * zend_handle_undef_args in a fake frame of the callee). Made on the first call that leaves one out.
     */
    private static RootCallTarget defaultsTarget(PhpLanguage language, String path, Source source, boolean internal,
                                                 Ast header, Translator.ClassInfo owner, PhpFunction function,
                                                 String display, PhpFunction.Param[] params, Names names) {
        BytecodeTranslator translator = new BytecodeTranslator(language, path, source, internal, header, null, owner,
                function, display, params, new String[0], new boolean[0], names, false);
        translator.defaults = true;
        PhpBytecodeRootNode root;
        try {
            root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, translator::parse).getNode(0);
        } catch (Unsupported unsupported) {
            throw new PhpError("Unsupported " + unsupported.getMessage() + " in the defaults of " + display);
        }
        root.initialize(display, path, internal, translator.functionSection(), params, new int[0], null,
                new LocalAccessor[0], new LocalAccessor[0], owner == null ? null : owner.name());
        root.initializeDefaults();
        return root.getCallTarget();
    }

    private static void report(String line) {
        if (REPORT) {
            System.err.println("php.bytecode " + line);
        }
    }

    private SourceSection functionSection() {
        int line = declaration != null ? declaration.line : thunk.line;
        return source == null || line < 1 || line > source.getLineCount() ? null : source.createSection(line);
    }

    /** One run of the parser: the DSL makes the first, and another whenever it needs the function's sources. */
    private void parse(PhpBytecodeRootNodeGen.Builder builder) {
        names = declaredNames;
        lastRun = new Run(builder);
        if (thunk != null) {
            lastRun.thunkRoot(thunk);
            firstRun = false;
            return;
        }
        if (defaults) {
            lastRun.defaultsRoot();
            firstRun = false;
            return;
        }
        Ast body = firstBody != null ? firstBody : reread != null ? reread.get() : declaration.body();
        firstBody = null;
        if (reread != null) {
            lastRun.fileRoot(body);
        } else {
            lastRun.root(body);
        }
        firstRun = false;
    }


    /** The state of one run: the builder, the locals, the loops around the code being emitted. */
    private final class Run {
        private final PhpBytecodeRootNodeGen.Builder b;
        private Translator constants;
        private final Map<String, BytecodeLocal> variables = new HashMap<>();
        private final List<Loop> loops = new ArrayList<>();
        /** In a finally block, how many of loops are outside it: break and continue may not leave it. */
        private int loopsOutsideFinally;
        /** The labels of the statement lists around the code being emitted, outermost first (goto's targets). */
        private final List<LabelScope> labelScopes = new ArrayList<>();
        /** In a finally block, how many of labelScopes are outside it: a goto may not leave it either. */
        private int labelScopesOutsideFinally;
        /**
         * The labels inside an if or a block, where a goto from outside may jump in: each one's number, which such a
         * goto leaves in entering for the ifs and lists on the way to take it there (see entries).
         */
        private final Map<String, Long> entries = new HashMap<>();
        private BytecodeLocal entering;
        /**
         * Values evaluated once into a local, by the node standing for each where it is used: the objects of `?->`s
         * being short-circuited (in the rest of the chain), the keys of a place evaluated before its value (onceKey).
         */
        private final Map<Ast, BytecodeLocal> nullsafeObjects = new java.util.IdentityHashMap<>();
        /** How many variables of its own (hiddenVariable) the run has made. */
        private int hiddenVariables;
        /** The objects of written properties (delayedProp): read as isset() reads them, as php-src's BP_VAR_W. */
        private final Set<Ast> writeFetched = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        /** A generator's local holding its PhpGenerator. */
        private BytecodeLocal generatorLocal;
        /** A file's top-level classes declared by their statements, and the local of each bound at the load. */
        private final Map<Ast, Object[]> topClasses = new java.util.IdentityHashMap<>();
        /** A file's own declare statements (its children), whose strict_types Translator.translateFile checked. */
        private final Set<Ast> topDeclares = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        /** The file's tree, the list whose declares are its own. */
        private Ast top;
        /** The local keeping the call's variables by name, made where the body first needs it. */
        private BytecodeLocal callScope;
        /** The next object of made to take, or to make. */
        private int madeIndex;
        /** While a parameter's default is translated: its `->` and `[]` check their objects (ConstantObject). */
        private boolean constantExpression;
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

        /**
         * php-src's compile error at node's line (Translator.compileError: reported, the bailout ends the script);
         * report=all's runs leaving nodes out only note it.
         */
        private RuntimeException compileError(Ast node, String message) {
            if (skip != null) {
                return new Unsupported(message, node);
            }
            return ErrorFunctions.fatal(PhpContext.get(null), ErrorFunctions.E_COMPILE_ERROR, Str.of(message),
                    new SourceLocation(path, node.line), 0);
        }

        /** A function returning by reference (`function &f()`); a generator's `&` is for its yields. */
        private boolean returnsRef() {
            return function != null && function.returnsRef() && !generator;
        }

        void root(Ast body) {
            b.beginRoot();
            // the variables first, in a fixed order: parameters, captures, then the body's
            Set<String> names = new LinkedHashSet<>();
            for (PhpFunction.Param param : params) {
                names.add(param.name());
            }
            if (names.size() != params.length) {
                Set<String> seen = new java.util.HashSet<>();
                for (PhpFunction.Param param : params) {
                    if (!seen.add(param.name())) {
                        throw compileError(declaration, "Redefinition of parameter $" + param.name());
                    }
                }
            }
            for (String name : names) {
                if (name.equals("this")) {
                    throw compileError(declaration, "Cannot use $this as parameter");
                }
                if (!plainVariable(name)) {
                    throw compileError(declaration, "Cannot re-assign auto-global variable " + name);
                }
            }
            Set<String> bound = new java.util.HashSet<>();
            for (String capture : captures) {
                if (capture.equals("this")) {
                    throw compileError(declaration, "Cannot use $this as lexical variable");
                }
                if (!plainVariable(capture)) {
                    throw compileError(declaration, "Cannot use auto-global as lexical variable");
                }
                if (!bound.add(capture)) {
                    throw compileError(declaration, "Cannot use variable $" + capture + " twice");
                }
                if (!names.add(capture)) {
                    throw compileError(declaration, "Cannot use lexical variable $" + capture + " as a parameter name");
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
                variableNames = names.toArray(new String[0]);
            }
            // without the source text (an export whose file is gone) there are no lines, as in internal code
            if (source != null) {
                b.beginSource(source);
                beginLine(declaration.line);
            }
            prologue();
            entries(body);
            if (entering != null) {
                b.beginStoreLocal(entering);
                b.emitLoadConstant(0L);
                b.endStoreLocal();
            }
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
            if (Translator.checkedReturn(function, generator) != null) {
                // the TypeError is at the closing brace
                beginLine(declaration.endLine);
                b.emitNoneReturned();
                endLine(declaration.endLine);
            } else if (returnsRef()) {
                b.emitImplicitReturnRef(declaration.endLine);
            } else {
                b.emitLoadConstant(PhpNull.NULL);
            }
            b.endReturn();
            if (source != null) {
                endLine(declaration.line);
                b.endSource();
            }
            b.endRoot();
        }

        /**
         * A file's top level (Translator.translateFile's FileRootNode): the functions declared at the top level and
         * the classes php-src binds when it loads the file first (Translator.earlyBinding), then the statements, the
         * top level's braced namespaces and blocks among them in place; the value is `return`'s, else 1.
         */
        void fileRoot(Ast file) {
            b.beginRoot();
            if (source != null) {
                b.beginSource(source);
                b.beginSourceSection(0, source.getLength());
            }
            List<Object> body = new ArrayList<>();
            List<Runnable> early = new ArrayList<>();
            List<Runnable> delayed = new ArrayList<>();
            Set<String> linked = new java.util.HashSet<>();
            top = file;
            BytecodeLocal[] bound = topStatements(file, body, early, delayed, linked);
            if (source != null) {
                b.beginSourceSectionUnavailable();
            }
            for (BytecodeLocal local : bound) {
                b.beginStoreLocal(local);
                b.emitLoadConstant(Boolean.FALSE);
                b.endStoreLocal();
            }
            if (source != null) {
                b.endSourceSectionUnavailable();
            }
            for (Runnable declaration : early) {
                declaration.run();
            }
            for (Runnable declaration : delayed) {
                declaration.run();
            }
            names = declaredNames;
            constants = Translator.forConstants(language, path, source, internal, owner, display, function, names);
            Ast statements = new Ast("AST_STMT_LIST", 0, file.line, file.endLine, body, null);
            entries(statements);
            if (entering != null) {
                b.beginStoreLocal(entering);
                b.emitLoadConstant(0L);
                b.endStoreLocal();
            }
            statements(statements);
            b.beginReturn();
            b.emitLoadConstant(eval ? PhpNull.NULL : (Object) 1L);
            b.endReturn();
            if (source != null) {
                b.endSourceSection();
                b.endSource();
            }
            b.endRoot();
        }

        /** A constant expression's root ({@link Thunk}): its value, evaluated as a constant expression. */
        void thunkRoot(Ast expression) {
            b.beginRoot();
            if (source != null) {
                b.beginSource(source);
            }
            beginLine(expression.line);
            b.beginReturn();
            constantExpression = true;
            expression(expression);
            constantExpression = false;
            b.endReturn();
            endLine(expression.line);
            if (source != null) {
                b.endSource();
            }
            b.endRoot();
        }

        /**
         * A function's defaults target ({@link BytecodeTranslator#defaults}): each parameter before a variadic one
         * that the call left out, in order (zend_handle_undef_args), gets its default, evaluated as the prologue
         * evaluates it (a constant expression in the function's scope), or is php-src's ArgumentCountError "not
         * passed", which ends the call; each at the parameter's line (its RECV's), in a frame showing the callee.
         */
        void defaultsRoot() {
            b.beginRoot();
            if (source != null) {
                b.beginSource(source);
            }
            Ast paramList = declaration.node("params");
            for (int i = 0; i < params.length; i++) {
                if (params[i].variadic()) {
                    continue;
                }
                Ast param = (Ast) paramList.child(i);
                beginLine(param.line);
                b.beginIfThen();
                b.emitIsNotPassed(i);
                Object defaultValue = param.get("default");
                if (defaultValue == null) {
                    b.emitNotPassed(i);
                } else {
                    b.beginSetArgument(i);
                    if (constantShaped(defaultValue)) {
                        constant(defaultValue, param);
                    } else {
                        constantExpression = true;
                        expression(defaultValue);
                        constantExpression = false;
                    }
                    b.endSetArgument();
                }
                b.endIfThen();
                endLine(param.line);
            }
            b.beginReturn();
            b.emitLoadConstant(PhpNull.NULL);
            b.endReturn();
            if (source != null) {
                b.endSource();
            }
            b.endRoot();
        }

        /** Where a statement changes the namespace or the imports in effect: the names from there on. */
        private Ast namesChange(Ast at) {
            return new Ast("NAMES", 0, at.line, at.line, null, Map.of("names", names));
        }

        /** The declarations' translator for the names now in effect. */
        private void namesChanged() {
            constants = Translator.forConstants(language, path, source, internal, owner, display, function, names);
        }

        /**
         * A file's top-level statements (zend_compile_top_stmt) into body, in order, with a NAMES node where a
         * namespace or use statement changes the names; the functions they declare (made by the first run) into
         * early, the classes bound at the load into early and delayed. Each class bound at the load with a parent
         * has a local the binding sets, which its statement tests. The locals, in the order of their classes.
         */
        private BytecodeLocal[] topStatements(Ast list, List<Object> body, List<Runnable> early,
                                              List<Runnable> delayed, Set<String> linked) {
            List<BytecodeLocal> bound = new ArrayList<>();
            for (Object child : list.children()) {
                if (!(child instanceof Ast statement)) {
                    continue;
                }
                switch (statement.kind) {
                    case "AST_FUNC_DECL" -> {
                        PhpFunction declared = (PhpFunction) made(() -> constants.declareFunction(statement, null));
                        early.add(() -> {
                            beginLine(statement.line);
                            b.emitDeclareFunction(declared, true);
                            endLine(statement.line);
                        });
                    }
                    case "AST_CLASS" -> {
                        ClassTemplate template = (ClassTemplate) made(() -> constants.classTemplate(statement,
                                names.declared((String) statement.get("name"))));
                        int binding = (int) made(() -> constants.earlyBinding(statement, template, linked));
                        BytecodeLocal local = binding >= Translator.BOUND_LINKED ? b.createLocal() : null;
                        if (local != null) {
                            bound.add(local);
                        }
                        if (binding == Translator.LINKED || binding == Translator.BOUND_LINKED) {
                            early.add(() -> earlyClass(statement, template, local, false));
                        }
                        if (binding >= Translator.BOUND_LINKED) {
                            delayed.add(() -> earlyClass(statement, template, local, true));
                        }
                        if (binding != Translator.LINKED) {
                            topClasses.put(statement, new Object[] {template, local});
                            body.add(statement);
                        }
                    }
                    case "AST_STMT_LIST" -> bound.addAll(List.of(topStatements(statement, body, early, delayed,
                            linked)));
                    case "AST_NAMESPACE" -> {
                        names = Names.namespace((String) statement.get("name"));
                        namesChanged();
                        body.add(namesChange(statement));
                        if (statement.get("stmts") != null) {
                            bound.addAll(List.of(topStatements(statement.node("stmts"), body, early, delayed,
                                    linked)));
                            names = Names.GLOBAL;
                            namesChanged();
                            body.add(namesChange(statement));
                        }
                    }
                    case "AST_USE", "AST_GROUP_USE" -> {
                        boolean group = statement.is("AST_GROUP_USE");
                        Ast uses = group ? statement.node("uses") : statement;
                        for (Object element : uses.children()) {
                            Ast use = (Ast) element;
                            int kind = !group ? statement.flags : use.flags != 0 ? use.flags : uses.flags;
                            String name = group ? statement.get("prefix") + "\\" + use.get("name")
                                    : (String) use.get("name");
                            constants.checkUse(kind, use, name);
                            names = names.use(kind, name, (String) use.get("alias"));
                        }
                        namesChanged();
                        body.add(namesChange(statement));
                    }
                    default -> {
                        if (statement.is("AST_DECLARE") && list == top) {
                            topDeclares.add(statement);
                        }
                        body.add(statement);
                    }
                }
            }
            return bound.toArray(new BytecodeLocal[0]);
        }

        /**
         * A class bound when its file is loaded (DeclareNodes.EarlyClassNode), if late only when its name is free and
         * its parent declared; the class's local (null for none) set when it was.
         */
        private void earlyClass(Ast statement, ClassTemplate template, BytecodeLocal local, boolean late) {
            beginLine(statement.line);
            if (local == null) {
                b.emitEarlyClass(template, late);
            } else {
                b.beginIfThen();
                b.emitEarlyClass(template, late);
                b.beginStoreLocal(local);
                b.emitLoadConstant(Boolean.TRUE);
                b.endStoreLocal();
                b.endIfThen();
            }
            endLine(statement.line);
        }

        /** Binds the parameters and captures (FunctionRootNode.bindArguments), in no statement. */
        private void prologue() {
            if (source != null) {
                b.beginSourceSectionUnavailable();
            }
            int[] arity = internal ? Registry.arity(display) : null;
            if (arity != null) {
                b.emitCheckArity(Registry.declaredName(display), arity[0], arity[1]);
            }
            Ast paramList = declaration.node("params");
            for (int i = 0; i < params.length; i++) {
                Ast param = (Ast) paramList.child(i);
                BytecodeLocal local = variables.get(params[i].name());
                // a parameter's errors are at its own line, its RECV opline's
                if (params[i].variadic()) {
                    beginLine(param.line);
                    b.emitBindVariadic(local, i);
                    endLine(param.line);
                    continue;
                }
                b.beginBindParameter(local, i);
                if (params[i].declared() != null) {
                    beginLine(param.line);
                    b.beginCheckParameter(i);
                }
                Object defaultValue = param.get("default");
                if (defaultValue == null) {
                    b.emitArgument(i);
                } else {
                    b.beginConditional();
                    b.emitArgumentGiven(i);
                    b.emitArgument(i);
                    // a default that isn't a literal (a class constant, a constant, new) is evaluated when the
                    // argument is missing, after the parameters before it are bound (FunctionRootNode)
                    if (constantShaped(defaultValue)) {
                        constant(defaultValue, param);
                    } else {
                        beginLine(param.line);
                        constantExpression = true;
                        expression(defaultValue);
                        constantExpression = false;
                        endLine(param.line);
                    }
                    b.endConditional();
                }
                if (params[i].declared() != null) {
                    b.endCheckParameter();
                    endLine(param.line);
                }
                b.endBindParameter();
            }
            for (int i = 0; i < captures.length; i++) {
                b.emitBindCapture(variables.get(captures[i]), i, captureByRef[i]);
            }
            // a constructor's promoted parameters, stored before its body runs (Translator's PromoteNodes)
            if (owner != null && "__construct".equalsIgnoreCase((String) declaration.get("name"))) {
                for (int i = 0; i < params.length; i++) {
                    if (Translator.isPromoted((Ast) paramList.child(i))) {
                        b.beginPromote(Str.sourceKey(params[i].name()));
                        b.emitReadVar(variables.get(params[i].name()));
                        b.endPromote();
                    }
                }
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
            LabelScope scope = labelScope(list);
            if (scope != null) {
                labeledStatements(list, scope);
                return;
            }
            b.beginBlock();
            for (Object child : list.children()) {
                if (child instanceof Ast statement) {
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
            refuseNullsafeWrite(node);
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
                case "AST_SWITCH":
                    switchStatement(node);
                    break;
                case "AST_BREAK":
                case "AST_CONTINUE": {
                    int depth = node.get("depth") instanceof Long d ? d.intValue() : 1;
                    String keyword = node.is("AST_CONTINUE") ? "continue" : "break";
                    if (depth < 1) {
                        throw compileError(node, "'" + keyword + "' operator accepts only positive integers");
                    }
                    if (loops.isEmpty()) {
                        throw compileError(node, "'" + keyword + "' not in the 'loop' or 'switch' context");
                    }
                    if (depth > loops.size()) {
                        throw compileError(node, "Cannot '" + keyword + "' " + depth + " level"
                                + (depth == 1 ? "" : "s"));
                    }
                    if (loops.size() - depth < loopsOutsideFinally) {
                        throw compileError(node, "jump out of a finally block is disallowed");
                    }
                    Loop loop = loops.get(loops.size() - depth);
                    b.emitBranch(node.is("AST_CONTINUE") ? loop.continueLabel() : loop.breakLabel());
                    break;
                }
                case "AST_RETURN":
                    returnValue(node.get("expr"), node);
                    break;
                case "AST_GOTO":
                    gotoStatement(node);
                    break;
                case "AST_UNSET":
                    if (refPlace(node.node("var")) instanceof RefPlace place) {
                        place.unset();
                    } else if (node.node("var").is("AST_PROP")) {
                        unsetProp(node.node("var"));
                    } else if (node.node("var").is("AST_DIM")) {
                        nestedUnset(node.node("var"));
                    } else {
                        b.emitUnsetVar(variable(node.node("var"), "unset of"));
                    }
                    break;
                case "AST_TRY":
                    tryStatement(node);
                    break;
                case "AST_GLOBAL":
                    globalStatement(node);
                    break;
                case "AST_STATIC":
                    staticStatement(node);
                    break;
                case "AST_FUNC_DECL":
                    // declared when the statement runs (DeclareFunctionNode); made by the first run
                    b.emitDeclareFunction((PhpFunction) made(() -> constants.declareFunction(node, null)), false);
                    break;
                case "AST_CLASS": {
                    Object[] top = topClasses.get(node);
                    if (top == null) {
                        b.emitDeclareClass((ClassTemplate) made(() -> constants.classTemplate(node,
                                names.declared((String) node.get("name")))));
                    } else if (top[1] == null) {
                        b.emitDeclareClass((ClassTemplate) top[0]);
                    } else {
                        // unless bound at the load (ZEND_DECLARE_CLASS_DELAYED's binding)
                        b.beginIfThen();
                        b.beginNot();
                        b.emitLoadLocal((BytecodeLocal) top[1]);
                        b.endNot();
                        b.emitDeclareClass((ClassTemplate) top[0]);
                        b.endIfThen();
                    }
                    break;
                }
                case "NAMES":
                    names = (Names) node.get("names");
                    namesChanged();
                    break;
                case "AST_CONST_DECL":
                    constDeclaration(node);
                    break;
                case "AST_DECLARE":
                    // a file's own were checked before (Translator.translateFile)
                    if (!topDeclares.contains(node) && Translator.strictTypes(node) != null) {
                        throw compileError(node,
                                "strict_types declaration must be the very first statement in the script");
                    }
                    break;
                case "AST_HALT_COMPILER":
                    // the scanner stopped at it; php-src's compile error elsewhere than the top level
                    if (reread == null) {
                        throw compileError(node, "__HALT_COMPILER() can only be used from the outermost scope");
                    }
                    break;
                default:
                    expressionStatement(node);
            }
        }

        /** An expression whose value is dropped: a variable's assignment or step without the result. */
        private void expressionStatement(Object value) {
            if (value instanceof Ast node && node.get("var") instanceof Ast target && isVariable(target)) {
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

        /**
         * `goto label`: a branch to the label in the innermost enclosing statement list naming it, or to the
         * statement there the label is inside of, an if or a block (php-src jumps in; the tree's goto doesn't). A
         * label inside a loop, a switch or a try stays the tree's.
         */
        private void gotoStatement(Ast node) {
            String name = (String) node.get("label");
            for (int i = labelScopes.size() - 1; i >= 0; i--) {
                LabelScope scope = labelScopes.get(i);
                Integer index = scope.indexes().get(name);
                if (index == null) {
                    continue;
                }
                if (i < labelScopesOutsideFinally) {
                    throw compileError(node, "jump out of a finally block is disallowed");
                }
                if (scope.nested().contains(name)) {
                    // into an if or a block: the label's number shows the way in (labeledStatements, ifChain)
                    b.beginStoreLocal(entering);
                    b.emitLoadConstant(entries.get(name));
                    b.endStoreLocal();
                }
                if (scope.emitted().contains(name)) {
                    // backward: the list's loop goes round, and its top branches on to the label
                    b.beginStoreLocal(scope.target());
                    b.emitLoadConstant((long) index);
                    b.endStoreLocal();
                    b.emitBranch(scope.again());
                } else {
                    b.emitBranch(scope.labels()[index]);
                }
                return;
            }
            throw new Unsupported("goto into a statement list around it", node);
        }

        /** A statement list's goto labels, or null when it has none. */
        private LabelScope labelScope(Ast list) {
            Map<String, Integer> indexes = new HashMap<>();
            Set<String> nested = new java.util.HashSet<>();
            boolean backward = false;
            for (Object child : list.children()) {
                if (child instanceof Ast statement) {
                    if (statement.is("AST_LABEL")) {
                        indexes.put((String) statement.get("name"), indexes.size());
                        continue;
                    }
                    for (String name : labelsInside(statement)) {
                        indexes.put(name, indexes.size());
                        nested.add(name);
                    }
                    if (!indexes.isEmpty()) {
                        backward |= gotoTo(statement, indexes.keySet());
                    }
                }
            }
            return indexes.isEmpty() ? null : new LabelScope(indexes, new BytecodeLabel[indexes.size()],
                    new java.util.HashSet<>(), nested, backward ? b.createLocal() : null, null);
        }

        /**
         * The labels a goto may jump in to inside statement, an if or a block, at any depth through ifs and blocks
         * (zend_resolve_goto_label forbids only loops, switches and finally); none for any other statement.
         */
        private Set<String> labelsInside(Ast statement) {
            Set<String> labels = new LinkedHashSet<>();
            if (statement.is("AST_IF")) {
                for (Object clause : statement.children()) {
                    labels.addAll(labelsInside(((Ast) clause).node("stmts")));
                }
            } else if (statement.is("AST_STMT_LIST")) {
                for (Object child : statement.children()) {
                    if (child instanceof Ast inner) {
                        if (inner.is("AST_LABEL")) {
                            labels.add((String) inner.get("name"));
                        } else {
                            labels.addAll(labelsInside(inner));
                        }
                    }
                }
            } else if (statement.is("AST_TRY")) {
                // into a try block or a catch block (into a finally block is JumpChecks' compile error)
                labels.addAll(labelsInside(statement.node("try")));
                for (Ast catchBlock : catchBlocks(statement)) {
                    labels.addAll(labelsInside(catchBlock));
                }
            }
            return labels;
        }

        /** A try statement's catch blocks' statements, in order. */
        private List<Ast> catchBlocks(Ast tryStatement) {
            List<Ast> blocks = new ArrayList<>();
            Ast catches = tryStatement.node("catches");
            for (int i = 0; catches != null && i < catches.size(); i++) {
                blocks.add(((Ast) catches.child(i)).node("stmts"));
            }
            return blocks;
        }

        /**
         * Numbers the labels a goto may jump in to (labelsInside) in the statement lists of code, outside nested
         * functions and classes; with any, entering holds the number of the one being jumped to, else 0.
         */
        private void entries(Object code) {
            if (!(code instanceof Ast node) || node.is("AST_CLOSURE") || node.is("AST_ARROW_FUNC")
                    || node.is("AST_FUNC_DECL") || node.is("AST_CLASS")) {
                return;
            }
            if (node.is("AST_STMT_LIST")) {
                for (Object child : node.children()) {
                    if (child instanceof Ast statement && !statement.is("AST_LABEL")) {
                        for (String name : labelsInside(statement)) {
                            entries.putIfAbsent(name, (long) entries.size() + 1);
                        }
                    }
                }
            }
            for (Object child : node.children()) {
                entries(child);
            }
            Map<String, Object> named = node.namedOrNull();
            if (named != null) {
                for (Object child : named.values()) {
                    entries(child);
                }
            }
            if (entering == null && !entries.isEmpty()) {
                entering = b.createLocal();
            }
        }

        /** Whether code has a goto to one of the labels, not counting nested functions and classes. */
        private boolean gotoTo(Object code, Set<String> labels) {
            if (!(code instanceof Ast node)) {
                return false;
            }
            switch (node.kind) {
                case "AST_GOTO":
                    return labels.contains((String) node.get("label"));
                case "AST_CLOSURE", "AST_ARROW_FUNC", "AST_FUNC_DECL", "AST_CLASS":
                    return false;
                default:
                    for (Object child : node.children()) {
                        if (gotoTo(child, labels)) {
                            return true;
                        }
                    }
                    Map<String, Object> named = node.namedOrNull();
                    if (named != null) {
                        for (Object child : named.values()) {
                            if (gotoTo(child, labels)) {
                                return true;
                            }
                        }
                    }
                    return false;
            }
        }

        /**
         * A statement list with goto labels (GotoNodes.LabeledBlockNode): a goto in it, or in a statement nested in
         * it, branches to the label's place. The DSL branches only forward, so a list a goto jumps back in is a
         * loop that runs once: a backward goto sets the label's number and goes round, and the loop's top branches
         * on to that label.
         */
        private void labeledStatements(Ast list, LabelScope plain) {
            b.beginBlock();
            BytecodeLabel done = null;
            if (plain.target() != null) {
                done = b.createLabel();
                b.beginStoreLocal(plain.target());
                b.emitLoadConstant(-1L);
                b.endStoreLocal();
                b.beginWhile();
                b.emitLoadConstant(Boolean.TRUE);
                b.beginBlock();
            }
            LabelScope scope = new LabelScope(plain.indexes(), plain.labels(), plain.emitted(), plain.nested(),
                    plain.target(), plain.target() == null ? null : b.createLabel());
            for (int i = 0; i < scope.labels().length; i++) {
                scope.labels()[i] = b.createLabel();
            }
            if (scope.target() != null) {
                for (int i = 0; i < scope.labels().length; i++) {
                    b.beginIfThen();
                    b.beginIdentical();
                    b.emitLoadLocal(scope.target());
                    b.emitLoadConstant((long) i);
                    b.endIdentical();
                    b.emitBranch(scope.labels()[i]);
                    b.endIfThen();
                }
            }
            // a goto from outside jumping in to a label here, or inside a statement here: on to it
            for (Map.Entry<String, Integer> label : scope.indexes().entrySet()) {
                if (entries.containsKey(label.getKey())) {
                    b.beginIfThen();
                    b.beginIdentical();
                    b.emitLoadLocal(entering);
                    b.emitLoadConstant(entries.get(label.getKey()));
                    b.endIdentical();
                    b.beginBlock();
                    if (!scope.nested().contains(label.getKey())) {
                        b.beginStoreLocal(entering);
                        b.emitLoadConstant(0L);
                        b.endStoreLocal();
                    }
                    b.emitBranch(scope.labels()[label.getValue()]);
                    b.endBlock();
                    b.endIfThen();
                }
            }
            labelScopes.add(scope);
            BytecodeTranslator.this.loops = true;
            for (Object child : list.children()) {
                if (child instanceof Ast statement) {
                    if (statement.is("AST_LABEL")) {
                        String name = (String) statement.get("name");
                        b.emitLabel(scope.labels()[scope.indexes().get(name)]);
                        scope.emitted().add(name);
                        continue;
                    }
                    for (String name : labelsInside(statement)) {
                        b.emitLabel(scope.labels()[scope.indexes().get(name)]);
                        scope.emitted().add(name);
                    }
                    statement(statement);
                }
            }
            labelScopes.remove(labelScopes.size() - 1);
            if (scope.target() != null) {
                b.emitBranch(done);
                b.emitLabel(scope.again());
                b.endBlock();
                b.endWhile();
                b.emitLabel(done);
            }
            b.endBlock();
        }

        private void returnValue(Object value, Ast node) {
            Ast declared = declaration == null ? null : declaration.node("returnType");
            if (declared != null && !generator) {
                // zend_emit_return_type_check's compile errors
                String kind = owner != null ? "method" : "function";
                boolean isVoid = declared.is("AST_TYPE") && declared.flags == 14;
                if (isVoid && value != null) {
                    throw compileError(node, "A void " + kind + " must not return a value"
                            + (Translator.isNullConstant(value)
                            ? " (did you mean \"return;\" instead of \"return null;\"?)" : ""));
                }
                if (declared.is("AST_TYPE") && declared.flags == 17) {
                    throw compileError(node, "A never-returning " + kind + " must not return");
                }
                if (value == null && !isVoid) {
                    throw compileError(node, "A " + kind + " with return type must return a value"
                            + (function.getDeclaredReturn().has(PhpType.NULL)
                            ? " (did you mean \"return null;\" instead of \"return;\"?)" : ""));
                }
            }
            b.beginReturn();
            boolean typed = function != null && Translator.checkedReturn(function, generator) != null;
            if (typed) {
                b.beginCheckReturn();
            }
            if (returnsRef()) {
                // zend_compile_return in a function returning by reference (Translator.returnStatement): a place
                // gives its reference, a call the reference its callee returned, anything else its value
                if (value instanceof Ast ast && isRefPlace(ast)) {
                    refOf(ast);
                } else {
                    b.beginReturnedRef(!isThis(value));
                    if (value == null) {
                        b.emitLoadConstant(PhpNull.NULL);
                    } else if (Translator.isCall(value)) {
                        callForRef((Ast) value);
                    } else {
                        expression(value);
                    }
                    b.endReturnedRef();
                }
            } else if (value == null) {
                b.emitLoadConstant(PhpNull.NULL);
            } else {
                expression(value);
            }
            if (typed) {
                b.endCheckReturn();
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
            // a goto jumping in to a label in this clause takes it, one to a later clause passes it by
            Set<String> into = entering == null ? Set.of() : labelsInside(element.node("stmts"));
            Set<String> later = new LinkedHashSet<>();
            for (int i = index + 1; entering != null && i < node.size(); i++) {
                later.addAll(labelsInside(((Ast) node.child(i)).node("stmts")));
            }
            if (!into.isEmpty()) {
                b.beginOr();
                enteringOneOf(into);
            }
            if (!later.isEmpty()) {
                b.beginAnd();
                b.beginNot();
                enteringOneOf(later);
                b.endNot();
            }
            condition(condition);
            if (!later.isEmpty()) {
                b.endAnd();
            }
            if (!into.isEmpty()) {
                b.endOr();
            }
            statements(element.node("stmts"));
            if (more) {
                ifChain(node, index + 1);
                b.endIfThenElse();
            } else {
                b.endIfThen();
            }
        }

        /** Whether entering holds the number of one of labels. */
        private void enteringOneOf(Set<String> labels) {
            b.beginOr();
            for (String label : labels) {
                b.beginIdentical();
                b.emitLoadLocal(entering);
                b.emitLoadConstant(entries.get(label));
                b.endIdentical();
            }
            b.emitLoadConstant(Boolean.FALSE);
            b.endOr();
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
            loopBody(outer, body, null, afterContinue);
        }

        /** A loop's body after what a step does first (a foreach binding its targets), then afterContinue. */
        private void loopBody(Loop outer, Ast body, Runnable first, Runnable afterContinue) {
            b.beginBlock();
            Loop loop = new Loop(outer.breakLabel(), b.createLabel());
            BytecodeTranslator.this.loops = true;
            if (first != null) {
                first.run();
            }
            loops.add(loop);
            statements(body);
            loops.remove(loops.size() - 1);
            b.emitLabel(loop.continueLabel());
            if (afterContinue != null) {
                afterContinue.run();
            }
            b.endBlock();
        }

        /**
         * switch (LoopNodes.SwitchNode): the subject once, then each case's value in order, compared to it loosely
         * (`==`, ZEND_CASE) until one is equal; the bodies follow one another (fall-through) from that case's, or
         * from default's (wherever it is) when none is. The switch is a level for break and continue, a continue
         * targeting it acting as break.
         */
        private void switchStatement(Ast node) {
            Ast list = node.node("stmts");
            List<Ast> cases = new ArrayList<>();
            int defaultIndex = -1;
            for (int i = 0; list != null && i < list.size(); i++) {
                Ast switchCase = (Ast) list.child(i);
                if (switchCase.get("cond") == null) {
                    if (defaultIndex >= 0) {
                        throw compileError(switchCase, "Switch statements may only contain one default clause");
                    }
                    defaultIndex = i;
                }
                cases.add(switchCase);
            }
            b.beginBlock();
            BytecodeLocal subject = b.createLocal();
            b.beginStoreLocal(subject);
            expression(node.get("cond"));
            b.endStoreLocal();
            BytecodeLabel end = b.createLabel();
            List<BytecodeLabel> bodies = new ArrayList<>();
            for (int i = 0; i < cases.size(); i++) {
                bodies.add(b.createLabel());
            }
            for (int i = 0; i < cases.size(); i++) {
                Object condition = cases.get(i).get("cond");
                if (condition == null) {
                    continue;
                }
                b.beginIfThen();
                b.beginEqual();
                b.emitLoadLocal(subject);
                expression(condition);
                b.endEqual();
                b.emitBranch(bodies.get(i));
                b.endIfThen();
            }
            b.emitBranch(defaultIndex >= 0 ? bodies.get(defaultIndex) : end);
            loops.add(new Loop(end, end));
            for (int i = 0; i < cases.size(); i++) {
                b.emitLabel(bodies.get(i));
                statements(cases.get(i).node("stmts"));
            }
            loops.remove(loops.size() - 1);
            b.emitLabel(end);
            b.endBlock();
        }

        /**
         * foreach by value (ForeachNode): the loop holds the array until it ends, however it ends. A value target
         * that is a list() or a place other than a variable takes the element from a hidden local, then the key
         * (zend_compile_foreach assigns them in that order).
         */
        private void foreach(Ast node) {
            Ast value = node.node("value");
            if (value.is("AST_REF") || value.is("AST_ARRAY") && Translator.listHasRefs(value)) {
                foreachByRef(node);
                return;
            }
            Ast keyTarget = node.node("key");
            checkForeachKey(keyTarget);
            boolean direct = isVariable(value) && (keyTarget == null || isVariable(keyTarget));
            if (value.is("AST_ARRAY")) {
                checkList(value);
            }
            BytecodeLocal valueVariable = direct ? variable(value, "foreach into") : b.createLocal();
            BytecodeLocal keyVariable = keyTarget == null ? null
                    : direct ? variable(keyTarget, "foreach key") : b.createLocal();
            Runnable bind = direct ? null : () -> {
                if (value.is("AST_ARRAY")) {
                    destructure(value, valueVariable);
                } else {
                    assignFrom(value, valueVariable, value);
                }
                b.emitUnsetVar(valueVariable);
                if (keyTarget != null) {
                    assignFrom(keyTarget, keyVariable, keyTarget);
                }
            };
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
            loopBody(loop, node.node("stmts"), bind, null);
            b.endWhile();
            b.endTryFinally();
            b.emitLabel(loop.breakLabel());
            b.endBlock();
        }

        /**
         * foreach by reference (ForeachNode.executeByRef): the subject's reference (a variable or a property holding
         * an array becomes one; an element is made one; a call gives the reference its callee returned; anything
         * else is a temporary), which the loop holds; each step binds the value target to the next element's
         * reference, then assigns the key. A Generator yielding by reference gives its references.
         */
        /** zend_compile_foreach's compile errors for its key. */
        private void checkForeachKey(Ast keyTarget) {
            if (keyTarget != null && keyTarget.is("AST_REF")) {
                throw compileError(keyTarget, "Key element cannot be a reference");
            }
            if (keyTarget != null && keyTarget.is("AST_ARRAY")) {
                throw compileError(keyTarget, "Cannot use list as key element");
            }
        }

        private void foreachByRef(Ast node) {
            // `as &$v`, or `as [&$a, $b]` (zend_propagate_list_refs makes the foreach by reference)
            Ast value = node.node("value").is("AST_REF") ? node.node("value").node("var") : node.node("value");
            Ast keyTarget = node.node("key");
            if (value.is("AST_ARRAY")) {
                checkList(value);
            }
            checkForeachKey(keyTarget);
            boolean direct = isVariable(value) && (keyTarget == null || isVariable(keyTarget));
            BytecodeLocal valueVariable = direct ? variable(value, "foreach into") : b.createLocal();
            BytecodeLocal keyVariable = keyTarget == null ? null
                    : direct ? variable(keyTarget, "foreach key") : b.createLocal();
            Runnable bind = direct ? null : () -> {
                if (value.is("AST_ARRAY")) {
                    Ast held = hiddenVariable(value);
                    bindFrom(held, valueVariable);
                    destructureRefs(value, held);
                    b.emitUnsetVar(variable(held, "list"));
                } else {
                    bindFrom(value, valueVariable);
                }
                b.emitUnsetVar(valueVariable);
                if (keyTarget != null) {
                    assignFrom(keyTarget, keyVariable, keyTarget);
                }
            };
            b.beginBlock();
            BytecodeLocal state = b.createLocal();
            if (firstRun) {
                foreachStates.add(LocalAccessor.constantOf(state));
            }
            b.beginForeachRefStart(state);
            foreachRefSubject(node.get("expr"));
            b.endForeachRefStart();
            Loop loop = new Loop(b.createLabel(), null);
            b.beginTryFinally(() -> b.emitForeachRefEnd(state));
            b.beginWhile();
            b.beginForeachRefNext(valueVariable, keyVariable == null ? valueVariable : keyVariable,
                    keyVariable != null);
            b.emitLoadLocal(state);
            b.endForeachRefNext();
            loopBody(loop, node.node("stmts"), bind, null);
            b.endWhile();
            b.endTryFinally();
            b.emitLabel(loop.breakLabel());
            b.endBlock();
        }

        /** The subject of a foreach by reference, as ForeachRefStart takes it. */
        private void foreachRefSubject(Object subject) {
            if (subject instanceof Ast ast) {
                if (isVariable(ast)) {
                    b.emitForeachRefVar(variable(ast, "foreach by reference over"));
                    return;
                }
                if (refPlace(ast) instanceof RefPlace place) {
                    // FE_RESET_RW reads a global without creating it: a missing one isn't iterable; `$$name`
                    // is fetched for W, which makes it
                    place.ref(place.variable);
                    return;
                }
                if (ast.is("AST_DIM")) {
                    refOf(ast);
                    return;
                }
                if (ast.is("AST_PROP")) {
                    b.beginBlock();
                    Ast property = delayedProp(ast, VariableVariableNode.W);
                    if (!(property.get("prop") instanceof String)) {
                        b.beginForeachRefPropNamed(propSite(property, null));
                        writtenObject(property.get("expr"), BP_VAR_W);
                        propName(property.get("prop"));
                        b.endForeachRefPropNamed();
                    } else {
                        b.beginForeachRefProp(writtenPropSite(property));
                        writtenObject(property.get("expr"), BP_VAR_W);
                        b.endForeachRefProp();
                    }
                    b.endBlock();
                    return;
                }
                if (Translator.isCall(ast)) {
                    callForRef(ast);
                    return;
                }
            }
            expression(subject);
        }

        /** `target = &local`, the reference in a local (a foreach's value), bound to a place. */
        private void bindFrom(Ast target, BytecodeLocal local) {
            Map<String, Object> parts = new java.util.LinkedHashMap<>();
            parts.put("var", target);
            parts.put("expr", standIn(local, target));
            assignRef(new Ast("AST_ASSIGN_REF", 0, target.line, target.endLine, null, parts));
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
            List<LabelScope> labelsAround = new ArrayList<>(labelScopes);
            Runnable finallyCode = () -> {
                List<Loop> inner = new ArrayList<>(loops);
                List<LabelScope> innerLabels = new ArrayList<>(labelScopes);
                int outside = loopsOutsideFinally;
                int labelsOutside = labelScopesOutsideFinally;
                loops.clear();
                loops.addAll(around);
                loopsOutsideFinally = around.size();
                labelScopes.clear();
                labelScopes.addAll(labelsAround);
                labelScopesOutsideFinally = labelsAround.size();
                statements(finallyBody);
                loopsOutsideFinally = outside;
                labelScopesOutsideFinally = labelsOutside;
                loops.clear();
                loops.addAll(inner);
                labelScopes.clear();
                labelScopes.addAll(innerLabels);
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
            tryCatchEntered(node, catches, catchBlocks(node), 0);
        }

        /**
         * A goto jumping in to a catch block runs that block, as after its exception was caught, and goes on after
         * the try (the DSL can't branch into a handler); any other way in, the try statement.
         */
        private void tryCatchEntered(Ast node, Ast catches, List<Ast> blocks, int from) {
            for (int i = from; entering != null && i < blocks.size(); i++) {
                Set<String> into = labelsInside(blocks.get(i));
                if (into.isEmpty()) {
                    continue;
                }
                // (inside the finally's TryCatchOtherwise when there is one: the finally block runs after it)
                b.beginIfThenElse();
                enteringOneOf(into);
                statements(blocks.get(i));
                b.beginBlock();
                tryCatchEntered(node, catches, blocks, i + 1);
                b.endBlock();
                b.endIfThenElse();
                return;
            }
            tryCatchBlocks(node, catches);
        }

        private void tryCatchBlocks(Ast node, Ast catches) {
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
            Ast var = handler.node("var");
            if (var != null && isThis(var)) {
                throw compileError(var, "Cannot re-assign $this");
            }
            RefPlace kept = var == null ? null : refPlace(var);
            BytecodeLocal target = var == null || kept != null ? null : variable(var, "catch into");
            b.beginIfThenElse();
            b.beginCatchMatches(types);
            b.emitLoadLocal(thrown);
            b.endCatchMatches();
            b.beginBlock();
            if (target != null) {
                b.beginWriteVarStatement(target);
                b.emitLoadLocal(thrown);
                b.endWriteVarStatement();
            } else if (kept != null) {
                b.beginWriteRef();
                b.emitLoadLocal(thrown);
                kept.ref(true);
                b.endWriteRef();
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
            if (value instanceof Ast fetched && writeFetched.contains(fetched)) {
                readIfSet(fetched);
                return;
            }
            if (constantShaped(value)) {
                constant(value, value instanceof Ast ast ? ast : null);
                return;
            }
            Ast node = (Ast) value;
            Ast nullsafe = Translator.deepestNullsafe(node);
            if (nullsafe != null) {
                nullsafeChain(node, nullsafe, false);
                return;
            }
            refuseNullsafeWrite(node);
            switch (node.kind) {
                case "BYTECODE_OBJECT":
                    b.emitLoadLocal(nullsafeObjects.get(node));
                    break;
                case "AST_VAR":
                    if (isThis(node)) {
                        b.emitReadThis();
                    } else if (isGlobals(node)) {
                        b.emitGlobalsArray();
                    } else if (refPlace(node) instanceof RefPlace place) {
                        place.read();
                    } else {
                        b.emitReadVar(variable(node, "read of"));
                    }
                    break;
                case "AST_STATIC_PROP":
                    refPlace(node).read();
                    break;
                case "AST_PROP":
                    readProp(node);
                    break;
                case "AST_ISSET":
                    isset(node.get("var"));
                    break;
                case "AST_EMPTY":
                    empty(node.get("expr"));
                    break;
                case "AST_DIM":
                    if (node.get("dim") == null) {
                        throw compileError(node, "Cannot use [] for reading");
                    }
                    if (isGlobals(node.get("expr"))) {
                        refPlace(node).read();
                        break;
                    }
                    b.beginReadElement();
                    constantObject(node.get("expr"), true);
                    key(node.get("dim"));
                    b.endReadElement();
                    break;
                case "AST_ASSIGN":
                    assignment(node);
                    break;
                case "AST_ASSIGN_REF":
                    assignRef(node);
                    break;
                case "AST_ASSIGN_OP":
                    if (node.flags == 260) {
                        coalesceAssignment(node);
                        break;
                    }
                    if (isThis(node.node("var"))) {
                        // $this isn't written (it is no CV): the operation on its value
                        b.beginCompoundRef(node.flags);
                        expression(node.get("expr"));
                        thisAsRef();
                        b.endCompoundRef();
                        break;
                    }
                    if (refPlace(node.node("var")) instanceof RefPlace place) {
                        // CompoundAssignNode: the value, then the place read and written
                        b.beginBlock();
                        place.prepare();
                        b.beginCompoundRef(node.flags);
                        expression(node.get("expr"));
                        place.refForUpdate();
                        b.endCompoundRef();
                        b.endBlock();
                        break;
                    }
                    if (node.node("var").is("AST_DIM")) {
                        nestedCompound(node.node("var"), node.get("expr"), node.flags);
                        break;
                    }
                    if (node.node("var").is("AST_PROP")) {
                        b.beginBlock();
                        Ast target = writtenProp(node.node("var"), VariableVariableNode.RW);
                        if (!(target.get("prop") instanceof String)) {
                            b.beginCompoundPropNamed(propSite(target, null), node.flags);
                            expression(node.get("expr"));
                            writtenObject(target.get("expr"), BP_VAR_RW);
                            propName(target.get("prop"));
                            b.endCompoundPropNamed();
                            b.endBlock();
                            break;
                        }
                        b.beginCompoundProp(writtenPropSite(target), node.flags);
                        expression(node.get("expr"));
                        writtenObject(target.get("expr"), BP_VAR_RW);
                        b.endCompoundProp();
                        b.endBlock();
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
                    if (isThis(node.node("var"))) {
                        b.beginIncDecRef(increment, prefix);
                        thisAsRef();
                        b.endIncDecRef();
                        break;
                    }
                    if (refPlace(node.node("var")) instanceof RefPlace place) {
                        b.beginIncDecRef(increment, prefix);
                        place.refForUpdate();
                        b.endIncDecRef();
                        break;
                    }
                    if (node.node("var").is("AST_DIM")) {
                        nestedIncDec(node.node("var"), increment, prefix);
                        break;
                    }
                    if (node.node("var").is("AST_PROP")) {
                        b.beginBlock();
                        Ast target = writtenProp(node.node("var"), VariableVariableNode.RW);
                        if (!(target.get("prop") instanceof String)) {
                            b.beginIncDecPropNamed(propSite(target, null), increment, prefix);
                            writtenObject(target.get("expr"), BP_VAR_RW);
                            propName(target.get("prop"));
                            b.endIncDecPropNamed();
                            b.endBlock();
                            break;
                        }
                        b.beginIncDecProp(writtenPropSite(target), increment, prefix);
                        writtenObject(target.get("expr"), BP_VAR_RW);
                        b.endIncDecProp();
                        b.endBlock();
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
                case "AST_ENCAPS_LIST":
                    interpolation(node);
                    break;
                case "AST_CAST":
                    cast(node);
                    break;
                case "AST_PRINT":
                    b.beginPrint();
                    expression(node.get("expr"));
                    b.endPrint();
                    break;
                case "AST_CONST":
                    globalConstant(node);
                    break;
                case "AST_CALL":
                    call(node, false);
                    break;
                case "AST_METHOD_CALL":
                    methodCall(node, false);
                    break;
                case "AST_STATIC_CALL":
                    staticCall(node, false);
                    break;
                case "AST_NEW":
                    newObject(node);
                    break;
                case "AST_CLASS_CONST":
                    classConstant(node);
                    break;
                case "AST_CLASS_NAME":
                    className(node);
                    break;
                case "AST_INSTANCEOF":
                    instanceOf(node);
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
                case "AST_CLOSURE":
                case "AST_ARROW_FUNC":
                    closure(node);
                    break;
                case "AST_INCLUDE_OR_EVAL":
                    include(node);
                    break;
                case "AST_SHELL_EXEC":
                    // zend_compile_shell_exec: a call of shell_exec(), deprecated where it is compiled
                    if (firstRun) {
                        ErrorFunctions.raise(PhpContext.get(null), ErrorFunctions.E_DEPRECATED,
                                Str.of("The backtick (`) operator is deprecated, use shell_exec() instead"),
                                new SourceLocation(path, node.line));
                    }
                    call(new Ast("AST_CALL", 0, node.line, node.endLine, null, Map.of(
                            "expr", new Ast("AST_NAME", 0, node.line, node.line, null, Map.of("name", "shell_exec")),
                            "args", new Ast("AST_ARG_LIST", 0, node.line, node.line,
                                    java.util.Collections.singletonList(node.get("expr")), null))), false);
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
            Ast secondDefault = Translator.secondDefaultArm(node);
            if (secondDefault != null) {
                throw compileError(secondDefault, "Match expressions may only contain one default arm");
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
                throw compileError(node, "The \"yield\" expression can only be used inside a function");
            }
            b.beginResumed(generatorLocal);
            b.beginYieldValue(generatorLocal);
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
         * What a generator yielding by reference yields (ZEND_YIELD with ZEND_ACC_RETURN_REFERENCE): a variable's,
         * an element's or a property's reference, the reference a call's callee returned; anything else is yielded
         * as a value, with php-src's notice.
         */
        private void yieldedReference(Object value) {
            if (value instanceof Ast ast && ast.is("AST_VAR") && !PhpContext.SUPERGLOBALS.contains(ast.get("name"))) {
                b.emitRefVar(variable(ast, "yield by reference of"));
                return;
            }
            if (value instanceof Ast ast && (ast.is("AST_VAR") || ast.is("AST_DIM") || ast.is("AST_PROP")
                    || ast.is("AST_STATIC_PROP"))) {
                refOf(ast);
                return;
            }
            if (Translator.isCall(value)) {
                b.beginYieldedRef();
                callForRef((Ast) value);
                b.endYieldedRef();
                return;
            }
            b.beginNotReferenceYielded();
            expression(value);
            b.endNotReferenceYielded();
        }

        /** `yield from source`: the generator takes source's elements; the result is the yield from's value. */
        private void yieldFrom(Ast node) {
            if (generatorLocal == null) {
                throw compileError(node, "The \"yield\" expression can only be used inside a function");
            }
            b.beginResumed(generatorLocal);
            b.beginYieldFrom(generatorLocal);
            expression(node.get("expr"));
            b.endYieldFrom();
            b.endResumed();
        }

        /** An array key: a literal one normalized once, as DimNode's constant key. */
        /**
         * A key: a constant string or int normalized once, as php-src's compiler does (zend_handle_numeric_dim);
         * a float, bool or null converts where it is used, which warns for a string offset.
         */
        private void key(Object dim) {
            if (constantShaped(dim)) {
                Object constant = constantValue(dim);
                if (constant instanceof TruffleString || constant instanceof Long) {
                    b.emitLoadConstant(ArrayKey.of(constant));
                    return;
                }
            }
            expression(dim);
        }

        private void assignment(Ast node) {
            Ast target = node.node("var");
            if (target.is("AST_ARRAY")) {
                listAssignment(target, node.get("expr"));
                return;
            }
            if (Translator.onTemporary(target)) {
                throw compileError(target, "Cannot use temporary expression in write context");
            }
            if (refPlace(target) instanceof RefPlace place) {
                // StaticPropNode, GlobalVarNode, GlobalsDimNode write: the value, then the place's reference
                b.beginBlock();
                place.prepare();
                b.beginWriteRef();
                expression(node.get("expr"));
                place.ref(true);
                b.endWriteRef();
                b.endBlock();
                return;
            }
            if (target.is("AST_PROP")) {
                writeProp(target, node.get("expr"));
                return;
            }
            if (target.is("AST_DIM") && target.node("expr").is("AST_PROP") && plainKey(target.get("dim"))) {
                writePropElement(target, node.get("expr"));
                return;
            }
            if (target.is("AST_VAR")) {
                b.beginWriteVar(variable(target, "assignment to"));
                expression(node.get("expr"));
                b.endWriteVar();
                return;
            }
            if (target.is("AST_DIM") && isVariable(target.node("expr"))) {
                BytecodeLocal container = variable(target.node("expr"), "element of");
                if (target.get("dim") == null) {
                    b.beginAppendElement(container);
                    expression(node.get("expr"));
                    b.endAppendElement();
                    return;
                }
                // a computed key first, then the value (php-src's delayed fetch)
                b.beginBlock();
                Object dim = onceKey(target.get("dim"));
                b.beginWriteElement(container);
                expression(node.get("expr"));
                key(dim);
                b.endWriteElement();
                b.endBlock();
                return;
            }
            if (target.is("AST_DIM")) {
                nestedWrite(target, node.get("expr"));
                return;
            }
            throw new Unsupported("assignment target", target);
        }

        // ---- closures ----

        /**
         * A closure or arrow function (Translator.closure, ClosureNode): its function is made once for this place
         * (a reparse takes the same); the variables it captures by value (an array gains it as a holder) or by
         * reference, then $this unless it is static, and the class it is written in as its scope.
         */
        private void closure(Ast node) {
            Translator.ClosureParts parts = (Translator.ClosureParts) made(
                    () -> constants.closureParts(node, name -> reread != null || variables.containsKey(name)));
            String[] captures = parts.captures();
            BytecodeLocal[] locals = new BytecodeLocal[captures.length];
            RefPlace[] places = new RefPlace[captures.length];
            for (int i = 0; i < captures.length; i++) {
                // zend_compile_closure_uses' compile errors
                if (captures[i].equals("this")) {
                    throw compileError(node, "Cannot use $this as lexical variable");
                }
                if (!plainVariable(captures[i])) {
                    throw compileError(node, "Cannot use auto-global as lexical variable");
                }
                locals[i] = variables.get(captures[i]);
                if (locals[i] == null) {
                    // a file's variable
                    places[i] = refPlace(new Ast("AST_VAR", 0, node.line, node.line, null,
                            Map.of("name", captures[i])));
                }
            }
            b.beginMakeClosure(parts.function(), parts.byRef(), parts.isStatic());
            if (inClosure()) {
                // a closure declared in a closure gets its scope, which bindTo may have changed
                b.emitClosureScope(ClassRefNode.SCOPE);
            } else if (owner == null) {
                b.emitLoadConstant(PhpNull.NULL);
            } else {
                b.emitResolveClass(classSite(owner.name()));
            }
            for (int i = 0; i < locals.length; i++) {
                if (places[i] != null) {
                    if (parts.byRef()[i]) {
                        places[i].ref(true);
                    } else if (node.is("AST_ARROW_FUNC")) {
                        String captured = captures[i];
                        b.beginVariableValue(VariableVariableNode.DEFINED);
                        b.emitLoadConstant(made(() -> Str.of(captured)));
                        b.endVariableValue();
                    } else {
                        places[i].read();
                    }
                } else if (parts.byRef()[i]) {
                    b.emitRefVar(locals[i]);
                } else if (node.is("AST_ARROW_FUNC")) {
                    b.emitReadVarIfDefined(locals[i]);
                } else {
                    b.emitReadVar(locals[i]);
                }
            }
            b.endMakeClosure();
        }

        // ---- references ----

        /** A variable, element or property (zend_is_variable) whose reference `&` takes; no $this or $GLOBALS. */
        private boolean isRefPlace(Ast node) {
            return switch (node.kind) {
                case "AST_VAR" -> !isThis(node) && !"GLOBALS".equals(node.get("name"));
                case "AST_DIM", "AST_PROP", "AST_STATIC_PROP", "BYTECODE_OBJECT" -> true;
                default -> false;
            };
        }

        /**
         * A place's reference (LValueNode.makeRef), made one if it isn't: a variable's, a nested element's (the
         * containers made writable down to it, a missing element created null), a property's (of a variable or a
         * property chain, by a constant name). A key that isn't a constant or a variable is evaluated first.
         */
        private void refOf(Ast place) {
            if (isThis(place)) {
                thisAsRef();
                return;
            }
            if (refPlace(place) instanceof RefPlace kept) {
                kept.ref(true);
                return;
            }
            switch (place.kind) {
                case "AST_VAR" -> b.emitRefVar(variable(place, "reference to"));
                // a reference a foreach by reference bound a hidden local to
                case "BYTECODE_OBJECT" -> b.emitRefVar(nullsafeObjects.get(place));
                // `place = &f()` (assignRef): the reference the callee returned, else its value in one
                case "BYTECODE_CALL_REF" -> {
                    b.beginAssignedRef();
                    callForRef(place.node("expr"));
                    b.endAssignedRef();
                }
                case "AST_DIM" -> {
                    b.beginBlock();
                    ElementPlace element = elementPlace(place, "assignment to");
                    boolean append = element.appends();
                    List<Object> keys = placeKeys(element, !append);
                    if (element.ref() != null) {
                        b.beginRefRefNested(append);
                        element.ref().ref(true);
                        emitKeys(keys);
                        b.endRefRefNested();
                    } else if (element.variable() != null) {
                        b.beginRefNested(element.variable(), append);
                        emitKeys(keys);
                        b.endRefNested();
                    } else {
                        b.beginRefPropNested(element.site(), append);
                        expression(element.property().get("expr"));
                        propertyKeys(element, keys);
                        b.endRefPropNested();
                    }
                    b.endBlock();
                }
                case "AST_PROP" -> {
                    b.beginBlock();
                    Ast property = delayedProp(place, VariableVariableNode.W);
                    if (property.get("prop") instanceof String name) {
                        b.beginRefProp(propSite(property, name));
                        writtenObject(property.get("expr"), BP_VAR_W);
                        b.endRefProp();
                    } else {
                        b.beginRefPropNamed(propSite(property, null));
                        writtenObject(property.get("expr"), BP_VAR_W);
                        propName(property.get("prop"));
                        b.endRefPropNamed();
                    }
                    b.endBlock();
                }
                default -> throw new Unsupported("reference to", place);
            }
        }

        /** $this's value in a reference of its own: what `&$this` and an operation writing $this work on. */
        private void thisAsRef() {
            b.beginReturnedRef(false);
            b.emitReadThis();
            b.endReturnedRef();
        }

        /** A call whose result goes where a returned reference is kept (CallNode.executeRef). */
        private void callForRef(Ast call) {
            switch (call.kind) {
                case "AST_CALL" -> call(call, true);
                case "AST_METHOD_CALL" -> methodCall(call, true);
                case "AST_STATIC_CALL" -> staticCall(call, true);
                default -> throw new Unsupported("reference to the result of", call);
            }
        }

        /**
         * `target = &source` (RefAssignNode, zend_compile_assign_ref): the target's keys that aren't constants or
         * variables first, then the source's reference, then the target becomes it; the value is the reference's.
         * `target = &f()` (CallRefAssignNode) binds a variable to the reference the callee returned, else assigns
         * the value with php-src's notice.
         */
        private void assignRef(Ast node) {
            Ast target = node.node("var");
            Object source = node.get("expr");
            if (Translator.isCall(source) && isVariable(target)) {
                b.beginBindVarToResult(variable(target, "assignment to"));
                callForRef((Ast) source);
                b.endBindVarToResult();
                return;
            }
            if (Translator.isCall(source)) {
                // any other place bound to the reference the callee returned, else given its value (the notice)
                source = new Ast("BYTECODE_CALL_REF", 0, node.line, node.endLine, null, Map.of("expr", source));
            } else if (isGlobals(source)) {
                throw compileError(node, "Cannot acquire reference to $GLOBALS");
            } else if (!(source instanceof Ast ast) || !isRefPlace(ast) && !isThis(ast)) {
                throw compileError(node, "Cannot assign reference to non referenceable value");
            }
            Ast place = (Ast) source;
            if (refPlace(target) instanceof RefPlace kept) {
                // a class or a key of the target first (zend_delayed_compile_var), then the source's reference
                b.beginBlock();
                kept.prepare();
                kept.bind(() -> refOf(place));
                b.endBlock();
                return;
            }
            switch (target.kind) {
                case "AST_VAR" -> {
                    b.beginBindVar(variable(target, "assignment to"));
                    refOf(place);
                    b.endBindVar();
                }
                case "AST_DIM" -> {
                    b.beginBlock();
                    ElementPlace element = elementPlace(target, "assignment to");
                    boolean append = element.appends();
                    List<Object> keys = placeKeys(element, !append);
                    if (element.ref() != null) {
                        b.beginBindRefNested(append);
                        refOf(place);
                        element.ref().ref(true);
                        emitKeys(keys);
                        b.endBindRefNested();
                    } else if (element.variable() != null) {
                        b.beginBindNested(element.variable(), append);
                        refOf(place);
                        emitKeys(keys);
                        b.endBindNested();
                    } else {
                        b.beginBindPropNested(element.site(), append);
                        refOf(place);
                        expression(element.property().get("expr"));
                        propertyKeys(element, keys);
                        b.endBindPropNested();
                    }
                    b.endBlock();
                }
                case "AST_PROP" -> {
                    b.beginBlock();
                    Ast property = delayedProp(target, VariableVariableNode.W);
                    if (!(property.get("prop") instanceof String)) {
                        b.beginBindPropNamed(propSite(property, null));
                        refOf(place);
                        writtenObject(property.get("expr"), BP_VAR_W);
                        propName(property.get("prop"));
                        b.endBindPropNamed();
                        b.endBlock();
                        return;
                    }
                    b.beginBindProp(writtenPropSite(property));
                    refOf(place);
                    writtenObject(property.get("expr"), BP_VAR_W);
                    b.endBindProp();
                    b.endBlock();
                }
                default -> throw new Unsupported("reference assigned to", target);
            }
        }

        /** `global $x` (GlobalNode): the variable bound to the global's reference. */
        private void globalStatement(Ast node) {
            Ast target = node.node("var");
            if (!(target.get("name") instanceof String name)) {
                useCallScope();
                b.beginGlobalVariable();
                expression(target.get("name"));
                b.endGlobalVariable();
                return;
            }
            if (name.equals("this")) {
                throw compileError(target, "Cannot use $this as global variable");
            }
            if (refPlace(target) instanceof RefPlace place) {
                // a file's variable bound to the global (the same reference in the global scope)
                b.beginBlock();
                place.bind(() -> b.emitGlobalRef(name, true, false));
                b.endBlock();
                return;
            }
            b.emitGlobal(variable(target, "global"), name);
        }

        /** `const NAME = value;` at the top level (Translator's DefineNode): each constant defined in order. */
        private void constDeclaration(Ast node) {
            for (Object child : node.children()) {
                Ast element = (Ast) child;
                if (!element.is("AST_CONST_ELEM")) {
                    continue; // #[attributes] on the declaration
                }
                String name = names.declared((String) element.get("name"));
                constants.checkConstantExpression(element.get("value"), true, element.line);
                b.beginDefineConstant(name);
                constantExpression = true;
                expression(element.get("value"));
                constantExpression = false;
                b.endDefineConstant();
            }
        }

        /**
         * `static $x = init;` (StaticVarNode): the variable bound to the function's static variable, which the
         * first run makes from the initializer.
         */
        private void staticStatement(Ast node) {
            Ast target = node.node("var");
            String name = (String) target.get("name");
            if (name.equals("this")) {
                throw compileError(target, "Cannot use $this as static variable");
            }
            RefPlace place = refPlace(target);
            // a file's static variable: kept per file as a function's, then the file's variable bound to it
            PhpFunction function = place == null ? BytecodeTranslator.this.function
                    : (PhpFunction) made(() -> new PhpFunction(path, new PhpFunction.Param[0], false, false, true,
                    false, null));
            BytecodeLocal local = place == null ? variable(target, "static") : b.createLocal();
            if (place != null) {
                b.beginBlock();
            }
            b.beginIfThen();
            b.beginNot();
            b.emitStaticBind(local, function, name);
            b.endNot();
            b.beginStaticCreate(local, function, name);
            if (node.get("default") == null) {
                b.emitLoadConstant(PhpNull.NULL);
            } else {
                expression(node.get("default"));
            }
            b.endStaticCreate();
            b.endIfThen();
            if (place != null) {
                place.bind(() -> b.emitRefVar(local));
                b.endBlock();
            }
        }

        // ---- list() ----

        /**
         * `[$a, 'k' => $b, [$c]] = value` (ListAssignNode, zend_compile_list_assign): the value first, then the
         * elements in order, each destructured from it (see {@link #destructure}); the value is the result. When a
         * target writes the variable the value is read from (zend_list_has_assign_to_self), the value is held while
         * the list is assigned, as php-src's copy of it, so those writes copy the array first.
         */
        private void listAssignment(Ast list, Object value) {
            checkList(list);
            if (Translator.listHasRefs(list)) {
                listAssignmentRefs(list, value);
                return;
            }
            b.beginBlock();
            BytecodeLocal source = b.createLocal();
            boolean hold = assignsTo(list, value);
            b.beginStoreLocal(source);
            if (hold) {
                b.beginHoldValue();
            }
            expression(value);
            if (hold) {
                b.endHoldValue();
            }
            b.endStoreLocal();
            if (hold) {
                b.beginTryFinally(() -> {
                    b.beginReleaseValue();
                    b.emitLoadLocal(source);
                    b.endReleaseValue();
                });
                destructure(list, source);
                b.endTryFinally();
            } else {
                destructure(list, source);
            }
            b.emitLoadLocal(source);
            b.endBlock();
        }

        /**
         * php-src's compile errors for a list() target, and the references in one, which aren't covered yet: the
         * function stays on the tree.
         */
        private void checkList(Ast list) {
            boolean keyed = false;
            boolean unkeyed = false;
            boolean any = false;
            for (Object child : list.children()) {
                if (!(child instanceof Ast element)) {
                    continue;
                }
                any = true;
                if (element.is("AST_UNPACK")) {
                    throw compileError(element, "Spread operator is not supported in assignments");
                }
                if (element.get("key") == null) {
                    unkeyed = true;
                } else {
                    keyed = true;
                }
                if (element.node("value").is("AST_ARRAY")) {
                    checkList(element.node("value"));
                }
            }
            if (!any) {
                throw compileError(list, "Cannot use empty list");
            }
            if (keyed && unkeyed) {
                throw compileError(list, "Cannot mix keyed and unkeyed array entries in assignments");
            }
        }

        /**
         * `[&$a, $b] = place` (zend_compile_assign of a list with references): the place's reference first (MAKE_REF;
         * a call's result made one), then each element in order from it: one by reference bound to the element's
         * reference (made if missing), a nested list with references from the element's, any other assigned from
         * the element read; the value is the place's.
         */
        private void listAssignmentRefs(Ast list, Object value) {
            if (!(value instanceof Ast source) || !isRefPlace(source) && !Translator.isCall(source)) {
                throw compileError(list, "Cannot assign reference to non referenceable value");
            }
            b.beginBlock();
            Ast held = hiddenVariable(list);
            assignRef(new Ast("AST_ASSIGN_REF", 0, list.line, list.endLine, null, Map.of("var", held,
                    "expr", source)));
            destructureRefs(list, held);
            BytecodeLocal result = b.createLocal();
            b.beginStoreLocal(result);
            b.emitReadVar(variable(held, "list"));
            b.endStoreLocal();
            b.emitUnsetVar(variable(held, "list"));
            b.emitLoadLocal(result);
            b.endBlock();
        }

        /** A variable of the translator's own, a local in any code (a file's too), unset after its use. */
        private Ast hiddenVariable(Ast near) {
            String name = "\0list" + hiddenVariables++;
            variables.put(name, b.createLocal());
            return new Ast("AST_VAR", 0, near.line, near.endLine, null, Map.of("name", name));
        }

        /** The elements of a list with references from the reference the hidden variable held is bound to. */
        private void destructureRefs(Ast list, Ast held) {
            long position = 0;
            for (Object child : list.children()) {
                if (!(child instanceof Ast element)) {
                    position++;
                    continue;
                }
                Object key = element.get("key") != null ? element.get("key") : (Object) position++;
                Ast target = element.node("value");
                Ast dim = new Ast("AST_DIM", 0, element.line, element.endLine, null, Map.of("expr", held,
                        "dim", key));
                if ((element.flags & ARRAY_ELEM_REF) != 0 || target.is("AST_ARRAY") && Translator.listHasRefs(target)) {
                    Ast bound = (element.flags & ARRAY_ELEM_REF) != 0 ? target : hiddenVariable(target);
                    assignRef(new Ast("AST_ASSIGN_REF", 0, element.line, element.endLine, null, Map.of("var", bound,
                            "expr", dim)));
                    if (bound != target) {
                        destructureRefs(target, bound);
                        b.emitUnsetVar(variable(bound, "list"));
                    }
                    continue;
                }
                BytecodeLocal item = b.createLocal();
                b.beginStoreLocal(item);
                b.beginListElement();
                b.emitReadVar(variable(held, "list"));
                key(key);
                b.endListElement();
                b.endStoreLocal();
                if (target.is("AST_ARRAY")) {
                    destructure(target, item);
                } else {
                    assignFrom(target, item, element);
                }
            }
        }

        /**
         * The elements of a list() from the value in source, in order (ListAssignNode.assign): an element's key (a
         * positional one counts from 0, skipped entries too), the element read (ListElement: null when missing or
         * the value isn't an array, an ArrayAccess object's offsetGet()), then assigned to its target, a nested
         * list destructuring it in turn.
         */
        private void destructure(Ast list, BytecodeLocal source) {
            b.beginBlock();
            long position = 0;
            for (Object child : list.children()) {
                if (!(child instanceof Ast element)) {
                    position++;
                    continue;
                }
                Object key = element.get("key");
                BytecodeLocal item = b.createLocal();
                b.beginStoreLocal(item);
                b.beginListElement();
                b.emitLoadLocal(source);
                if (key == null) {
                    b.emitLoadConstant(position++);
                } else {
                    key(key);
                }
                b.endListElement();
                b.endStoreLocal();
                Ast target = element.node("value");
                if (target.is("AST_ARRAY")) {
                    destructure(target, item);
                } else {
                    assignFrom(target, item, element);
                }
            }
            b.endBlock();
        }

        /** `target = value` where the value is in a local already (a list's element, a foreach's value or key). */
        private void assignFrom(Ast target, BytecodeLocal value, Ast near) {
            Map<String, Object> parts = new java.util.LinkedHashMap<>();
            parts.put("var", target);
            parts.put("expr", standIn(value, near));
            assignment(new Ast("AST_ASSIGN", 0, target.line, target.endLine, null, parts));
        }

        /** A node standing for a value in a local where an expression is expected (see expressionBody). */
        private Ast standIn(BytecodeLocal local, Ast near) {
            Ast stand = new Ast("BYTECODE_OBJECT", 0, near == null ? 0 : near.line, near == null ? 0 : near.endLine,
                    null, null);
            nullsafeObjects.put(stand, local);
            return stand;
        }

        /**
         * zend_list_has_assign_to_self: whether a target of the list (down nested lists, elements and properties)
         * is the variable the value is.
         */
        private boolean assignsTo(Ast list, Object value) {
            if (!(value instanceof Ast variable && variable.is("AST_VAR") && variable.get("name") instanceof String name)) {
                return false;
            }
            for (Object child : list.children()) {
                if (!(child instanceof Ast element)) {
                    continue;
                }
                Ast target = element.node("value");
                if (target.is("AST_ARRAY")) {
                    if (assignsTo(target, value)) {
                        return true;
                    }
                    continue;
                }
                while (target.is("AST_DIM") || target.is("AST_PROP") || target.is("AST_STATIC_PROP")) {
                    if (!(target.get(target.is("AST_STATIC_PROP") ? "class" : "expr") instanceof Ast inner)) {
                        break;
                    }
                    target = inner;
                }
                if (target.is("AST_VAR") && name.equals(target.get("name"))) {
                    return true;
                }
            }
            return false;
        }

        private void binary(Ast node) {
            switch (node.flags) {
                case 261 -> {
                    // `|>` (Translator.pipeCall): the operand into a local, then the call with it
                    if (node.get("right") instanceof Ast arrow && arrow.is("AST_ARROW_FUNC")
                            && constants.unparenthesizedArrow(arrow)) {
                        throw compileError(node, "Arrow functions on the right hand side of |> must be parenthesized");
                    }
                    b.beginBlock();
                    BytecodeLocal operand = b.createLocal();
                    b.beginStoreLocal(operand);
                    expression(node.get("left"));
                    b.endStoreLocal();
                    Ast operandNode = new Ast("BYTECODE_OBJECT", 0, node.line, node.endLine, null, null);
                    nullsafeObjects.put(operandNode, operand);
                    expression(Translator.pipeCall(node, operandNode));
                    b.endBlock();
                    return;
                }
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
                case 260 -> {
                    // ?? (CoalesceNode): a place on the left read as isset() reads it
                    b.beginCoalesce();
                    readIfSet(node.get("left"));
                    expression(node.get("right"));
                    b.endCoalesce();
                    return;
                }
                case 15 -> {
                    // `xor` (LogicNodes.XorNode): both operands evaluated, then compared as booleans
                    b.beginNotIdentical();
                    condition(node.get("left"));
                    condition(node.get("right"));
                    b.endNotIdentical();
                    return;
                }
                case 8 -> {
                    concat(concatParts(node));
                    return;
                }
                default -> {
                }
            }
            beginBinary(node);
            expression(node.get("left"));
            expression(node.get("right"));
            endBinary(node);
        }

        /**
         * The operands of `a . b . c ...` as the reference's opcache leaves them (Translator.concatParts): a left
         * operand that is a concatenation extends the chain, unless it folds to a literal; an empty literal is
         * dropped; an operand cast to a string is the value itself; a nested concatenation of two operands or more
         * is a ConcatOperands.
         */
        private List<Object> concatParts(Ast node) {
            List<Object> parts = new ArrayList<>();
            if (node.get("left") instanceof Ast left && left.is("AST_BINARY_OP") && left.flags == 8
                    && !constantShaped(left)) {
                parts.addAll(concatParts(left));
            } else {
                addConcatPart(parts, node.get("left"));
            }
            addConcatPart(parts, node.get("right"));
            return parts;
        }

        private void addConcatPart(List<Object> parts, Object operand) {
            if (constantShaped(operand)) {
                Object value = constantValue(operand);
                if (value != PhpNull.NULL && value != Boolean.FALSE
                        && !(value instanceof TruffleString s && s.isEmpty())) {
                    parts.add(operand);
                }
                return;
            }
            Ast ast = (Ast) operand;
            if (ast.is("AST_CAST") && ast.flags == CastNode.TO_STRING) {
                addConcatPart(parts, ast.get("expr"));
            } else if (ast.is("AST_BINARY_OP") && ast.flags == 8) {
                List<Object> nested = concatParts(ast);
                if (nested.size() <= 1) {
                    parts.addAll(nested);
                } else {
                    parts.add(new ConcatOperands(nested));
                }
            } else if (ast.is("AST_ENCAPS_LIST") && Translator.interpolated(ast).size() == 1) {
                addConcatPart(parts, Translator.interpolated(ast).get(0));
            } else {
                parts.add(operand);
            }
        }

        /** A nested concatenation's parts, as one operand. */
        private record ConcatOperands(List<Object> parts) {
        }

        /** A concatenation of parts (concatParts): none is "", one converts as a cast, two are a Concat. */
        private void concat(List<Object> parts) {
            switch (parts.size()) {
                case 0 -> b.emitLoadConstant(Str.EMPTY);
                case 1 -> {
                    b.beginCastString();
                    concatPart(parts.get(0));
                    b.endCastString();
                }
                case 2 -> {
                    b.beginConcat();
                    concatPart(parts.get(0));
                    concatPart(parts.get(1));
                    b.endConcat();
                }
                default -> concatChain(parts);
            }
        }

        private void concatPart(Object part) {
            if (part instanceof ConcatOperands nested) {
                concat(nested.parts());
            } else {
                expression(part);
            }
        }

        /**
         * Three or more operands, or a two-part interpolation (ConcatChainNode): one join, where a `.` per pair
         * compiled a concatenation each.
         * As php-src's chain of ZEND_CONCAT, the first two are evaluated, then converted (the second waits in a
         * local), and each next one is evaluated and converted in turn.
         */
        private void concatChain(List<Object> parts) {
            BytecodeLocal second = b.createLocal();
            b.beginInterpolate();
            b.beginInterpolationPart();
            b.beginFirstOfTwo();
            concatPart(parts.get(0));
            b.beginBlock();
            b.beginStoreLocal(second);
            concatPart(parts.get(1));
            b.endStoreLocal();
            b.emitLoadConstant(PhpNull.NULL);
            b.endBlock();
            b.endFirstOfTwo();
            b.endInterpolationPart();
            b.beginInterpolationPart();
            b.emitLoadLocal(second);
            b.endInterpolationPart();
            for (int i = 2; i < parts.size(); i++) {
                b.beginInterpolationPart();
                concatPart(parts.get(i));
                b.endInterpolationPart();
            }
            b.endInterpolate();
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
                case 260 -> silence(node.get("expr"));
                default -> throw new Unsupported("unary operator " + node.flags, node);
            }
        }

        /**
         * `@expr` (SilenceNode, BEGIN_SILENCE / END_SILENCE): error_reporting saved into a local and reduced to the
         * fatal errors, the expression's value into another; the finally restores error_reporting on every way out,
         * an exception's too (php-src's live range of the saved value).
         */
        private void silence(Object operand) {
            b.beginBlock();
            BytecodeLocal saved = b.createLocal();
            BytecodeLocal value = b.createLocal();
            b.beginStoreLocal(saved);
            b.emitSilenceStart();
            b.endStoreLocal();
            b.beginTryFinally(() -> {
                b.beginSilenceEnd();
                b.emitLoadLocal(saved);
                b.endSilenceEnd();
            });
            b.beginStoreLocal(value);
            expression(operand);
            b.endStoreLocal();
            b.endTryFinally();
            b.emitLoadLocal(value);
            b.endBlock();
        }

        /**
         * A non-constant array literal (ArrayLiteralNode): its elements in order, each a value with or without a
         * key, a place's reference (`&$x`; `&$this` is its value, as the tree takes it), or an array's elements
         * unpacked (`...$a`).
         */
        private void arrayLiteral(Ast node) {
            for (Object child : node.children()) {
                if (!(child instanceof Ast)) {
                    throw compileError(node, "Cannot use empty array elements in arrays");
                }
            }
            List<Object> elements = node.children();
            int count = elements.size();
            // ArrayLiteralNode's constantKeys and recordHashes: an int or string literal key normalized once (a key
            // the compiled code would otherwise normalize out of line, then insert by every path a key can take),
            // and when every element is a value under a distinct such key, one a string, each key's hash
            Object[] keys = new Object[count];
            int[] hashes = new int[count];
            boolean record = true;
            boolean stringKey = false;
            Set<Object> distinct = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Ast element = (Ast) elements.get(i);
                Object key = element.get("key");
                Object constant = key == null || element.is("AST_UNPACK") || !constantShaped(key) ? null
                        : constantValue(key);
                if (constant instanceof Long || constant instanceof TruffleString) {
                    keys[i] = ArrayKey.of(constant);
                    hashes[i] = PhpArray.hashOf(keys[i]);
                    stringKey |= keys[i] instanceof TruffleString;
                }
                record &= keys[i] != null && !literalRef(element) && distinct.add(keys[i]);
            }
            boolean isRecord = record && stringKey;
            LiteralSite site = (LiteralSite) made(() -> new LiteralSite(count, isRecord));
            // LiteralEnd(Literal...(Literal...(LiteralStart, e1), e2)...): each element goes in as it is evaluated
            b.beginLiteralEnd(site);
            for (int i = count - 1; i >= 0; i--) {
                Ast element = (Ast) elements.get(i);
                boolean keyed = element.get("key") != null;
                if (element.is("AST_UNPACK")) {
                    b.beginLiteralSpread();
                } else if (literalRef(element)) {
                    if (keyed) {
                        b.beginLiteralPutRef();
                    } else {
                        b.beginLiteralAppendRef();
                    }
                } else if (isRecord) {
                    b.beginLiteralAdd(keys[i], hashes[i]);
                } else if (keys[i] != null) {
                    b.beginLiteralPutKey(keys[i]);
                } else if (keyed) {
                    b.beginLiteralPut();
                } else {
                    b.beginLiteralAppend();
                }
            }
            b.emitLiteralStart(site);
            for (int i = 0; i < count; i++) {
                Ast element = (Ast) elements.get(i);
                boolean keyed = element.get("key") != null;
                if (element.is("AST_UNPACK")) {
                    expression(element.get("expr"));
                    b.endLiteralSpread();
                    continue;
                }
                if (literalRef(element)) {
                    if (keyed) {
                        expression(element.get("key"));
                    }
                    if (!(element.get("value") instanceof Ast place) || !isRefPlace(place)) {
                        throw compileError(element, Translator.isCall(element.get("value"))
                                ? "Can't use function return value in write context"
                                : "Cannot assign reference to non referenceable value");
                    }
                    refOf(place);
                    if (keyed) {
                        b.endLiteralPutRef();
                    } else {
                        b.endLiteralAppendRef();
                    }
                    continue;
                }
                if (keyed && keys[i] == null) {
                    expression(element.get("key"));
                }
                expression(element.get("value"));
                if (isRecord) {
                    b.endLiteralAdd();
                } else if (keys[i] != null) {
                    b.endLiteralPutKey();
                } else if (keyed) {
                    b.endLiteralPut();
                } else {
                    b.endLiteralAppend();
                }
            }
            b.endLiteralEnd();
        }

        /** An element taken by reference (`&$x`); `&$this` is taken as its value (Translator.arrayLiteral). */
        private boolean literalRef(Ast element) {
            return !element.is("AST_UNPACK") && (element.flags & ARRAY_ELEM_REF) != 0 && !isThis(element.get("value"));
        }

        /**
         * `"text {$expr} $var"` and heredoc (Translator.interpolation): one part is a cast; two expressions a
         * concatenation (FAST_CONCAT: both evaluated, then converted), joined as a chain is; anything else a rope
         * (InterpolationNode): each part converted to a string as it is evaluated, a literal part as it is, then
         * one join, which is FAST_CONCAT's order too when one of two parts is a literal.
         */
        private void interpolation(Ast node) {
            List<Object> kept = Translator.interpolated(node);
            if (kept.size() == 1) {
                concat(kept);
                return;
            }
            if (Translator.twoExpressions(kept)) {
                concatChain(kept);
                return;
            }
            b.beginInterpolate();
            for (Object part : kept) {
                if (part instanceof String || part instanceof byte[]) {
                    constant(part, node);
                    continue;
                }
                b.beginInterpolationPart();
                expression(part);
                b.endInterpolationPart();
            }
            b.endInterpolate();
        }

        /** `(int) x`... (UnaryNodes.CastNode); `(unset)` no longer parses. */
        private void cast(Ast node) {
            if (node.flags == CAST_VOID) {
                // (void) expr; (8.5, a statement only): the value dropped
                b.beginBlock();
                expression(node.get("expr"));
                b.emitLoadConstant(PhpNull.NULL);
                b.endBlock();
                return;
            }
            switch (node.flags) {
                case CastNode.TO_LONG -> b.beginCastLong();
                case CastNode.TO_DOUBLE -> b.beginCastDouble();
                case CastNode.TO_STRING -> b.beginCastString();
                case CastNode.TO_BOOL -> b.beginToBool();
                case CastNode.TO_ARRAY -> b.beginCastArray();
                case CastNode.TO_OBJECT -> b.beginCastObject();
                default -> throw new Unsupported("cast " + node.flags, node);
            }
            expression(node.get("expr"));
            switch (node.flags) {
                case CastNode.TO_LONG -> b.endCastLong();
                case CastNode.TO_DOUBLE -> b.endCastDouble();
                case CastNode.TO_STRING -> b.endCastString();
                case CastNode.TO_BOOL -> b.endToBool();
                case CastNode.TO_ARRAY -> b.endCastArray();
                default -> b.endCastObject();
            }
        }

        /**
         * A global constant the translation doesn't fold (Translator.constant's GlobalConstNode: a user's, or a
         * predefined one in a namespace that may declare its own), looked up when it runs.
         */
        private void globalConstant(Ast node) {
            Ast nameNode = node.node("name");
            String[] resolved = names.constantName((String) nameNode.get("name"), nameNode.flags);
            String deprecation = language.builtins().deprecation(resolved[1] != null ? resolved[1] : resolved[0]);
            if (deprecation != null) {
                b.emitDeprecatedConstant(resolved[0], language.constantId(resolved[0]),
                        resolved[1] == null ? -1 : language.constantId(resolved[1]),
                        (TruffleString) made(() -> Str.of(deprecation)), node.line);
                return;
            }
            b.emitGlobalConstant(resolved[0], language.constantId(resolved[0]),
                    resolved[1] == null ? -1 : language.constantId(resolved[1]));
        }

        // ---- $this and properties ----

        /**
         * A property access's site: its name (null when computed), the class the code is written in, and how the
         * calling scope is found (PropNode's, as Translator.place makes it).
         */
        private PropSite propSite(Ast node, String name) {
            boolean scopeInFrame = inClosure();
            boolean thisInMethod = isThis(node.get("expr")) && !scopeInFrame;
            // a hook is named "$property::get" or "$property::set"
            String hookOf = function != null && function.getName().startsWith("$")
                    ? function.getName().substring(1, function.getName().lastIndexOf("::")) : null;
            return (PropSite) made(() -> new PropSite(name == null ? null : Str.sourceKey(name),
                    owner == null ? null : Str.lower(owner.name()), scopeInFrame, thisInMethod,
                    hookOf == null ? null : Str.sourceKey(hookOf)));
        }

        /**
         * A chain read with a `?->` (ZEND_JMP_NULL): its object is evaluated into a local; null short-circuits the
         * rest of the chain, its value null, else the rest runs on the object, the `?->` as `->` (with further
         * `?->`s of its own). The object's expression is the chain up to it, which short-circuits the same way.
         * quiet: in isset(), empty() and `??` (BP_VAR_IS), the object and the rest read as isset() reads them.
         */
        private void nullsafeChain(Ast node, Ast nullsafe, boolean quiet) {
            if (Translator.convertInChain(node, nullsafe) instanceof Ast convert) {
                throw compileError(convert, "Cannot combine nullsafe operator with Closure creation");
            }
            b.beginBlock();
            BytecodeLocal object = b.createLocal();
            b.beginStoreLocal(object);
            if (quiet) {
                readIfSet(nullsafe.get("expr"));
            } else {
                expression(nullsafe.get("expr"));
            }
            b.endStoreLocal();
            b.beginConditional();
            b.beginIsNull();
            b.emitLoadLocal(object);
            b.endIsNull();
            b.emitLoadConstant(PhpNull.NULL);
            Ast objectNode = new Ast("BYTECODE_OBJECT", 0, nullsafe.line, nullsafe.endLine, null, null);
            nullsafeObjects.put(objectNode, object);
            Ast chain = Translator.withObject(node, nullsafe, objectNode);
            if (quiet) {
                readIfSet(chain);
            } else {
                expression(chain);
            }
            b.endConditional();
            b.endBlock();
        }

        /**
         * A write or a reference through `?->` (php-src's compile errors, which the tree raises): the function
         * stays on the tree.
         */
        private void refuseNullsafeWrite(Ast node) {
            Object[] targets = switch (node.kind) {
                case "AST_ASSIGN", "AST_ASSIGN_REF", "AST_ASSIGN_OP", "AST_ASSIGN_COALESCE", "AST_PRE_INC",
                     "AST_PRE_DEC", "AST_POST_INC", "AST_POST_DEC", "AST_UNSET" -> new Object[] {node.get("var")};
                case "AST_FOREACH" -> new Object[] {node.get("value"), node.get("key")};
                default -> new Object[0];
            };
            boolean written = false;
            for (Object target : targets) {
                // zend_ensure_writable_variable: a call's value first
                if (target instanceof Ast call && call.is("AST_CALL")) {
                    throw compileError(node, "Can't use function return value in write context");
                }
                if (target instanceof Ast call && (call.is("AST_METHOD_CALL") || call.is("AST_NULLSAFE_METHOD_CALL")
                        || call.is("AST_STATIC_CALL"))) {
                    throw compileError(node, "Can't use method return value in write context");
                }
                written |= nullsafeTarget(target);
            }
            if (written) {
                // zend_ensure_writable_variable
                throw compileError(node, "Can't use nullsafe operator in write context");
            }
            boolean referenced = switch (node.kind) {
                case "AST_ASSIGN" -> node.node("var").is("AST_ARRAY") && Translator.listHasRefs(node.node("var"))
                        && Translator.deepestNullsafe(node.get("expr")) != null;
                case "AST_ASSIGN_REF" -> Translator.deepestNullsafe(node.get("expr")) != null;
                case "AST_RETURN" -> returnsRef() && !Translator.isCall(node.get("expr"))
                        && Translator.deepestNullsafe(node.get("expr")) != null;
                default -> false;
            };
            if (referenced) {
                // zend_assert_not_short_circuited
                throw compileError(node, "Cannot take reference of a nullsafe chain");
            }
        }

        /** A written place, or a list's, with a `?->` on its chain. */
        private static boolean nullsafeTarget(Object target) {
            if (target instanceof Ast list && list.is("AST_ARRAY")) {
                for (Object element : list.children()) {
                    if (element instanceof Ast elem && nullsafeTarget(elem.get("value"))) {
                        return true;
                    }
                }
                return false;
            }
            if (target instanceof Ast ref && ref.is("AST_REF")) {
                return nullsafeTarget(ref.get("var"));
            }
            return Translator.deepestNullsafe(target) != null;
        }

        /** `$obj->name`, `$obj->$name` (PropNode.execute): the object, then the name. */
        private void readProp(Ast node) {
            if (node.get("prop") instanceof String name) {
                b.beginReadProp(propSite(node, name));
                constantObject(node.get("expr"), false);
                b.endReadProp();
                return;
            }
            b.beginReadPropNamed(propSite(node, null));
            expression(node.get("expr"));
            propName(node.get("prop"));
            b.endReadPropNamed();
        }

        /** The object of `->` (element false) or `[]`, checked in a constant expression (ConstantObject). */
        private void constantObject(Object object, boolean element) {
            if (!constantExpression || !(object instanceof Ast)) {
                expression(object);
                return;
            }
            b.beginConstantObject(element);
            expression(object);
            b.endConstantObject();
        }

        /**
         * The object of a write to one of its properties or elements, fetched as php-src fetches it down the chain:
         * a property for writing (BP_VAR_W), for a compound assignment (BP_VAR_RW) or for unset() (BP_VAR_UNSET),
         * PropNode.readForWriteOn and readForUnsetOn; anything else is evaluated.
         */
        private void writtenObject(Object object, int fetch) {
            if (!(object instanceof Ast node && node.is("AST_PROP") && node.get("prop") instanceof String name)) {
                // a variable or an element under unset() quietly (BP_VAR_UNSET)
                if (fetch == BP_VAR_UNSET) {
                    readIfSet(object);
                } else {
                    expression(object);
                }
            } else if (fetch == BP_VAR_UNSET) {
                b.beginReadPropForUnset(propSite(node, name));
                writtenObject(node.get("expr"), fetch);
                b.endReadPropForUnset();
            } else {
                b.beginReadPropForWrite(propSite(node, name), fetch == BP_VAR_RW);
                writtenObject(node.get("expr"), fetch);
                b.endReadPropForWrite();
            }
        }

        private void propName(Object prop) {
            b.beginPropName();
            expression(prop);
            b.endPropName();
        }

        /**
         * The site of a property written by a compound assignment, an increment, an element write or a reference
         * (a place {@link #delayedProp} gave): a constant name; a computed one stays on the tree.
         */
        private PropSite writtenPropSite(Ast target) {
            if (!(target.get("prop") instanceof String name)) {
                throw new Unsupported("write to a property by a computed name", target);
            }
            return propSite(target, name);
        }

        /**
         * A property written, read and written, or bound (zend_delayed_compile_prop): what php-src evaluates before
         * the value is evaluated now into hidden locals, the object's parts (see {@link #delayedObject}) then a
         * computed name; the node returned stands for the place, its object the chain fetched as the write runs.
         * Emits into the block the caller opened. `?->` on the way is php-src's compile error.
         */
        private Ast delayedProp(Ast target, int fetch) {
            return delayedProp(target, fetch, fetch == VariableVariableNode.RW);
        }

        /**
         * delayedProp for a write (BP_VAR_W, update false), whose object php-src fetches quietly (an unset variable
         * or a missing element warns only for the Error that follows), or for a read-modify-write or an argument
         * (update), whose object reads warn; a `$$name` on the way fetched by fetch.
         */
        private Ast delayedProp(Ast target, int fetch, boolean update) {
            if (Translator.deepestNullsafe(target) != null) {
                throw compileError(target, "Can't use nullsafe operator in write context");
            }
            Object object = delayedObject(target.get("expr"), fetch);
            // a property is read as a read does (FETCH_OBJ_W falls back to __get), no warning being implemented;
            // a `$$name` is fetched by fetch (delayedObject)
            if (!update && object instanceof Ast fetched && (fetched.is("AST_DIM")
                    || (fetched.is("AST_VAR") && (isVariable(fetched)
                    || refPlace(fetched) != null && !isVariableVariable(fetched))))) {
                writeFetched.add(fetched);
                // every element down the chain is fetched for write (FETCH_DIM_W)
                for (Object inner = fetched.get("expr"); inner instanceof Ast dim && dim.is("AST_DIM");
                     inner = dim.get("expr")) {
                    writeFetched.add(dim);
                }
            }
            Object prop = target.get("prop") instanceof String ? target.get("prop") : onceKey(target.get("prop"));
            return withParts(target, "expr", object, "prop", prop);
        }

        /** delayedProp of a place written: Translator.onTemporary's compile error first. */
        private Ast writtenProp(Ast target, int fetch) {
            if (Translator.onTemporary(target)) {
                throw compileError(target, "Cannot use temporary expression in write context");
            }
            return delayedProp(target, fetch);
        }

        /**
         * The object of a property place as php-src fetches it (zend_delayed_compile_var): a variable, a property,
         * an element or a static property is read as the write runs, after the value, down a chain whose keys and
         * computed names are evaluated now; anything else (a call, `new`) is evaluated now. A `$$name` is fetched
         * as the place is (fetch: W, RW or UNSET), its name evaluated now unless a constant or a variable.
         */
        private Object delayedObject(Object object, int fetch) {
            if (!(object instanceof Ast ast)) {
                return object;
            }
            switch (ast.kind) {
                case "AST_VAR":
                    return isVariableVariable(ast) ? new Ast("AST_VAR", fetch, ast.line, ast.endLine, null,
                            Map.of("name", onceKey(ast.get("name")))) : ast;
                case "AST_STATIC_PROP", "BYTECODE_OBJECT":
                    return ast;
                case "AST_PROP":
                    return delayedProp(ast, fetch);
                case "AST_DIM": {
                    Object container = delayedObject(ast.get("expr"), fetch);
                    if (ast.get("dim") == null) {
                        // FETCH_DIM_W's new element, the object of the property (ElementForWrite)
                        Ast appended = withParts(ast, "expr", container, "dim", null);
                        writeFetched.add(appended);
                        return appended;
                    }
                    return withParts(ast, "expr", container, "dim", onceKey(ast.get("dim")));
                }
                default:
                    return onceKey(ast);
            }
        }

        /** A copy of node with two of its parts replaced; node itself when they are the same. */
        private Ast withParts(Ast node, String first, Object firstValue, String second, Object secondValue) {
            if (node.get(first) == firstValue && node.get(second) == secondValue) {
                return node;
            }
            Map<String, Object> parts = new java.util.LinkedHashMap<>(node.namedOrNull());
            parts.put(first, firstValue);
            parts.put(second, secondValue);
            return new Ast(node.kind, node.flags, node.line, node.endLine, null, parts);
        }

        /**
         * `$obj->name = value` (AssignNode on a PropNode), in php-src's order (zend_compile_assign): what it
         * evaluates before the value (a call as the object, a computed name; see {@link #delayedProp}), the value,
         * then the object's chain and the write; a computed name is converted as the write runs.
         */
        private void writeProp(Ast target, Object value) {
            b.beginBlock();
            Ast place = writtenProp(target, VariableVariableNode.W);
            if (place.get("prop") instanceof String name) {
                b.beginWriteProp(propSite(place, name));
                expression(value);
                writtenObject(place.get("expr"), BP_VAR_W);
                b.endWriteProp();
            } else {
                b.beginWritePropNamed(propSite(place, null));
                expression(value);
                propName(place.get("prop"));
                writtenObject(place.get("expr"), BP_VAR_W);
                b.endWritePropNamed();
            }
            b.endBlock();
        }

        /**
         * `$obj->name[key] = value`, `$obj->name[] = value` (DimNode.write on a PropNode): the value, the object, the
         * key; a key that is a constant or a variable, which the tree evaluates after making the property writable.
         */
        private void writePropElement(Ast target, Object value) {
            b.beginBlock();
            Ast property = writtenProp(target.node("expr"), VariableVariableNode.W);
            Object dim = target.get("dim");
            if (!(property.get("prop") instanceof String)) {
                // `$obj->$name[...] = value`: the name, evaluated now unless a variable, converted after the value
                if (dim == null) {
                    b.beginAppendPropElementNamed(propSite(property, null));
                    expression(value);
                    propName(property.get("prop"));
                    writtenObject(property.get("expr"), BP_VAR_W);
                    b.endAppendPropElementNamed();
                } else {
                    b.beginWritePropElementNamed(propSite(property, null));
                    expression(value);
                    propName(property.get("prop"));
                    writtenObject(property.get("expr"), BP_VAR_W);
                    key(dim);
                    b.endWritePropElementNamed();
                }
                b.endBlock();
                return;
            }
            PropSite site = writtenPropSite(property);
            if (dim == null) {
                b.beginAppendPropElement(site);
                expression(value);
                writtenObject(property.get("expr"), BP_VAR_W);
                b.endAppendPropElement();
            } else {
                b.beginWritePropElement(site);
                expression(value);
                writtenObject(property.get("expr"), BP_VAR_W);
                key(dim);
                b.endWritePropElement();
            }
            b.endBlock();
        }

        /**
         * `unset($obj->name)` (PropNode.unset): the object, read as isset() reads it (BP_VAR_UNSET), then the name
         * (a constant or a variable).
         */
        private void unsetProp(Ast target) {
            if (Translator.onTemporary(target)) {
                throw compileError(target, "Cannot use temporary expression in write context");
            }
            if (isVariableVariable(target.get("expr"))) {
                target = withParts(target, "expr", new Ast("AST_VAR", VariableVariableNode.UNSET, target.line,
                        target.endLine, null, Map.of("name", target.node("expr").get("name"))), "prop",
                        target.get("prop"));
            }
            Object prop = target.get("prop");
            if (prop instanceof String name) {
                b.beginUnsetProp(propSite(target, name));
                writtenObject(target.get("expr"), BP_VAR_UNSET);
                b.endUnsetProp();
                return;
            }
            // a name that isn't a variable first (zend_delayed_compile_prop), then the object
            b.beginBlock();
            Object name = prop instanceof Ast ast && ast.is("AST_VAR") ? prop : onceKey(prop);
            b.beginUnsetPropNamed(propSite(target, null));
            writtenObject(target.get("expr"), BP_VAR_UNSET);
            propName(name);
            b.endUnsetPropNamed();
            b.endBlock();
        }

        /**
         * isset() of one place (TestNodes.IssetNode; php-ast makes `isset($a, $b)` an &&): $this, a variable, an
         * element or a property, the place's container read as readIfSet reads it (a property's through __isset);
         * a property's computed name only from a variable.
         */
        private void isset(Object tested) {
            if (isThis(tested)) {
                b.emitIssetThis();
                return;
            }
            if (isGlobals(tested)) {
                // zend_compile_isset_or_empty: $GLOBALS is set
                b.emitLoadConstant(Boolean.TRUE);
                return;
            }
            if (Translator.deepestNullsafe(tested) != null) {
                if (Translator.isCall(tested)) {
                    throw compileError((Ast) tested, "Cannot use isset() on the result of an expression"
                            + " (you can use \"null !== expression\" instead)");
                }
                b.beginIsSetValue();
                readIfSet(tested);
                b.endIsSetValue();
                return;
            }
            if (refPlace(tested) instanceof RefPlace place) {
                b.beginIsSetValue();
                place.tested();
                b.endIsSetValue();
                return;
            }
            if (tested instanceof Ast target && target.is("AST_VAR")) {
                b.beginIsSetValue();
                b.emitReadVarIfSet(variable(target, "isset of"));
                b.endIsSetValue();
                return;
            }
            if (tested instanceof Ast target && target.is("AST_DIM")) {
                if (target.get("dim") == null) {
                    throw compileError(target, "Cannot use [] for reading");
                }
                b.beginIssetElement();
                readIfSet(target.get("expr"));
                key(target.get("dim"));
                b.endIssetElement();
                return;
            }
            if (!(tested instanceof Ast target) || !target.is("AST_PROP")) {
                // zend_compile_isset_or_empty: a call (anything else doesn't parse)
                throw compileError(tested instanceof Ast ast ? ast : null, "Cannot use isset() on the result of an "
                        + "expression (you can use \"null !== expression\" instead)");
            }
            Object prop = target.get("prop");
            if (prop instanceof String name) {
                b.beginIssetProp(propSite(target, name));
                readIfSet(target.get("expr"));
                b.endIssetProp();
                return;
            }
            // the object, then the name (ZEND_ISSET_ISEMPTY_PROP_OBJ's operands, as PropNode.isSet reads them)
            b.beginIssetPropNamed(propSite(target, null));
            readIfSet(target.get("expr"));
            propName(prop);
            b.endIssetPropNamed();
        }

        /**
         * empty(x) (TestNodes.EmptyNode): a place read as isset() reads it (null when unset), any other value as it
         * is, then its falsiness; empty($this) is !isset($this).
         */
        private void empty(Object tested) {
            if (isGlobals(tested)) {
                // zend_compile_isset_or_empty: $GLOBALS is not empty
                b.emitLoadConstant(Boolean.FALSE);
                return;
            }
            b.beginNot();
            if (isThis(tested)) {
                b.emitIssetThis();
            } else {
                b.beginToBool();
                if (isVariableVariable(tested)) {
                    refPlace(tested).tested();
                } else {
                    readIfSet(tested);
                }
                b.endToBool();
            }
            b.endNot();
        }

        /**
         * A place as isset(), empty() and `??` read it (LValueNode.readIfSet), null when it isn't set: a variable's
         * value, an element of its container read so (DimNode.readIfSet), a property through __isset then __get
         * (PropNode.readIfSet); anything else (a call, $this) is evaluated as a value.
         */
        private void readIfSet(Object place) {
            Ast nullsafe = Translator.deepestNullsafe(place);
            if (nullsafe != null) {
                nullsafeChain((Ast) place, nullsafe, true);
                return;
            }
            if (place instanceof Ast ast && !isThis(ast)) {
                switch (ast.kind) {
                    case "AST_DIM" -> {
                        if (ast.get("dim") == null && !writeFetched.contains(ast)) {
                            throw compileError(ast, "Cannot use [] for reading");
                        }
                        if (isGlobals(ast.get("expr"))) {
                            refPlace(ast).readIfSet();
                            return;
                        }
                        if (writeFetched.contains(ast)) {
                            // `[]` only as the object of a property written (delayedObject)
                            b.beginElementForWrite();
                            readIfSet(ast.get("expr"));
                            if (ast.get("dim") == null) {
                                b.emitLoadConstant(PhpBytecodeRootNode.APPEND);
                            } else {
                                key(ast.get("dim"));
                            }
                            b.endElementForWrite();
                            return;
                        }
                        b.beginElementIfSet();
                        readIfSet(ast.get("expr"));
                        key(ast.get("dim"));
                        b.endElementIfSet();
                        return;
                    }
                    case "AST_PROP" -> {
                        if (!(ast.get("prop") instanceof String name)) {
                            // the object, then the name, as PropNode.readIfSet evaluates them
                            b.beginReadPropIfSetNamed(propSite(ast, null));
                            readIfSet(ast.get("expr"));
                            propName(ast.get("prop"));
                            b.endReadPropIfSetNamed();
                            return;
                        }
                        b.beginReadPropIfSet(propSite(ast, name));
                        readIfSet(ast.get("expr"));
                        b.endReadPropIfSet();
                        return;
                    }
                    case "AST_STATIC_PROP" -> {
                        refPlace(ast).readIfSet();
                        return;
                    }
                    case "AST_VAR" -> {
                        if (isVariable(ast)) {
                            b.emitReadVarIfSet(variable(ast, "isset of"));
                            return;
                        }
                        if (refPlace(ast) instanceof RefPlace kept) {
                            kept.readIfSet();
                            return;
                        }
                    }
                    default -> {
                    }
                }
            }
            expression(place);
        }

        /**
         * `place ??= value` (CoalesceAssignNode): the place read as isset() reads it; unless it is set, the value
         * is evaluated and assigned. An element's key that isn't a constant or a variable is evaluated once, before
         * (as php-src's delayed fetch); a property's object must be a variable or a property chain.
         */
        private void coalesceAssignment(Ast node) {
            Ast target = node.node("var");
            Object value = node.get("expr");
            if (refPlace(target) instanceof RefPlace place) {
                // CoalesceAssignNode: read as isset() reads it, else the value, then the place's reference
                b.beginBlock();
                place.prepare();
                b.beginCoalesce();
                place.readIfSet();
                b.beginWriteRef();
                expression(value);
                place.ref(true);
                b.endWriteRef();
                b.endCoalesce();
                b.endBlock();
                return;
            }
            if (target.is("AST_VAR")) {
                BytecodeLocal variable = variable(target, "??= to");
                b.beginCoalesce();
                b.emitReadVarIfSet(variable);
                b.beginWriteVar(variable);
                expression(value);
                b.endWriteVar();
                b.endCoalesce();
                return;
            }
            if (target.is("AST_PROP")) {
                // the object's parts evaluated once (php-src's memoized ones), its chain read as isset() reads it,
                // then fetched again for the write
                b.beginBlock();
                Ast place = delayedProp(target, VariableVariableNode.W);
                b.beginCoalesce();
                if (place.get("prop") instanceof String name) {
                    b.beginReadPropIfSet(propSite(place, name));
                    readIfSet(place.get("expr"));
                    b.endReadPropIfSet();
                    b.beginWriteProp(propSite(place, name));
                    expression(value);
                    writtenObject(place.get("expr"), BP_VAR_W);
                    b.endWriteProp();
                } else {
                    b.beginReadPropIfSetNamed(propSite(place, null));
                    readIfSet(place.get("expr"));
                    propName(place.get("prop"));
                    b.endReadPropIfSetNamed();
                    b.beginWritePropNamed(propSite(place, null));
                    expression(value);
                    propName(place.get("prop"));
                    writtenObject(place.get("expr"), BP_VAR_W);
                    b.endWritePropNamed();
                }
                b.endCoalesce();
                b.endBlock();
                return;
            }
            if (target.is("AST_DIM")) {
                if (lastDimAppends(target)) {
                    throw compileError(target, "Cannot use [] for reading");
                }
                b.beginBlock();
                ElementPlace place = elementPlace(target, "??= to");
                List<Object> keys = placeKeys(place, true);
                b.beginCoalesce();
                emitReadIfSet(place, keys);
                emitWrite(place, keys, value, false);
                b.endCoalesce();
                b.endBlock();
                return;
            }
            throw new Unsupported("??= target", target);
        }

        /**
         * A key a place evaluates more than once, or after the assigned value (php-src's delayed fetch): a constant
         * or a variable as it is, anything else evaluated now into a hidden local, which stands for it.
         */
        private Object onceKey(Object dim) {
            if (plainKey(dim)) {
                return dim;
            }
            BytecodeLocal temp = b.createLocal();
            b.beginStoreLocal(temp);
            expression(dim);
            b.endStoreLocal();
            Ast stand = new Ast("BYTECODE_OBJECT", 0, dim instanceof Ast ast ? ast.line : 0,
                    dim instanceof Ast ast ? ast.endLine : 0, null, null);
            nullsafeObjects.put(stand, temp);
            return stand;
        }

        // ---- nested elements ----

        /**
         * A place `base[k1]...[kn]` written, read and written, or unset: its base, a variable or a property (of a
         * variable or a property chain, by a constant name), and its keys from the base down, null for an `[]`.
         */
        private record ElementPlace(BytecodeLocal variable, Ast property, PropSite site, RefPlace ref,
                                    List<Object> dims) {
            boolean appends() {
                return dims.get(dims.size() - 1) == null;
            }
        }

        /**
         * The place an element chain is: a variable's, or a property's whose object's parts php-src evaluates first
         * are evaluated now ({@link #delayedProp}), into the block the caller opened.
         */
        private ElementPlace elementPlace(Ast target, String what) {
            List<Object> dims = new ArrayList<>();
            if (!what.equals("argument") && Translator.onTemporary(target)) {
                throw compileError(target, "Cannot use temporary expression in write context");
            }
            Ast base = target;
            // down to the base; `$GLOBALS[name]` is a place of its own (a global), the base of the keys below it
            while (base.is("AST_DIM") && !isGlobals(base.get("expr"))) {
                dims.add(0, base.get("dim"));
                base = base.node("expr");
            }
            for (int i = 0; i < dims.size() - 1; i++) {
                if (dims.get(i) != null) {
                    continue;
                }
                // zend_delayed_compile_dim: `[]` is a write's (W, RW: a new element) and no read's
                // an argument's: a new element by reference, else the send's Error "Cannot use [] for reading"
                switch (what) {
                    case "unset of" -> throw compileError(target, "Cannot use [] for unsetting");
                    case "??= to" -> throw compileError(target, "Cannot use [] for reading");
                    default -> {
                    }
                }
            }
            if (refPlace(base) instanceof RefPlace kept) {
                return new ElementPlace(null, null, null, kept, dims);
            }
            if (base.is("AST_VAR") && !isThis(base)) {
                return new ElementPlace(variable(base, what + " an element of"), null, null, null, dims);
            }
            if (base.is("AST_PROP")) {
                Ast property = delayedProp(base, switch (what) {
                    case "compound assignment to", "increment of" -> VariableVariableNode.RW;
                    case "unset of" -> VariableVariableNode.UNSET;
                    default -> VariableVariableNode.W;
                }, what.equals("compound assignment to") || what.equals("increment of") || what.equals("argument"));
                // a computed name is the first of the keys the operation is given (placeName)
                return new ElementPlace(null, property, propSite(property,
                        property.get("prop") instanceof String name ? name : null), null, dims);
            }
            // a call's result (php-src's VAR: the reference a function returned, else the value; ZEND_SEPARATE
            // drops only a reference nothing else holds) or $this, kept as a variable the chain is written in
            BytecodeLocal held = b.createLocal();
            b.beginStoreLocal(held);
            if (isThis(base)) {
                thisAsRef();
            } else if (Translator.isCall(base)) {
                b.beginReturnedRef(false);
                callForRef(base);
                b.endReturnedRef();
            } else {
                throw new IllegalStateException(what + " an element of " + base.kind);
            }
            b.endStoreLocal();
            return new ElementPlace(held, null, null, null, dims);
        }

        /** `place[]`: an append, which php-src refuses to read (its compile error "Cannot use [] for reading"). */
        private boolean lastDimAppends(Ast target) {
            return target.is("AST_DIM") && target.get("dim") == null;
        }

        /**
         * The keys of an element place, in order: a constant or a variable as it is (read when the write runs, as
         * php-src reads a CV), any other key evaluated now into a hidden local (php-src evaluates a dim before the
         * value assigned, zend_delayed_compile_dim); an `[]` on the way as ElementChain's APPEND.
         */
        private List<Object> placeKeys(ElementPlace place, boolean withLast) {
            if (place.ref() != null) {
                place.ref().prepare();
            }
            List<Object> keys = new ArrayList<>();
            int count = withLast ? place.dims().size() : place.dims().size() - 1;
            for (int i = 0; i < count; i++) {
                Object dim = place.dims().get(i);
                keys.add(dim == null ? null : onceKey(dim));
            }
            return keys;
        }

        /** A property element place's keys, after its name when that is computed (placeName). */
        private void propertyKeys(ElementPlace place, List<Object> keys) {
            if (!(place.property().get("prop") instanceof String)) {
                propName(place.property().get("prop"));
            }
            emitKeys(keys);
        }

        private void emitKeys(List<Object> keys) {
            for (Object dim : keys) {
                if (dim == null) {
                    b.emitLoadConstant(PhpBytecodeRootNode.APPEND);
                } else {
                    key(dim);
                }
            }
        }

        /**
         * `base[k1]...[kn] = value` and `base[k1]...[] = value` (DimNode.write down a chain): the keys that aren't
         * constants or variables, then the value, then the containers made writable (ElementChain).
         */
        private void nestedWrite(Ast target, Object value) {
            b.beginBlock();
            ElementPlace place = elementPlace(target, "assignment to");
            boolean append = place.appends();
            emitWrite(place, placeKeys(place, !append), value, append);
            b.endBlock();
        }

        /** The write of a nested element once its keys are evaluated: the value, a property's object, the keys. */
        private void emitWrite(ElementPlace place, List<Object> keys, Object value, boolean append) {
            if (place.ref() != null) {
                b.beginWriteRefNested(append);
                expression(value);
                place.ref().ref(true);
                emitKeys(keys);
                b.endWriteRefNested();
            } else if (place.variable() != null) {
                b.beginWriteNested(place.variable(), append);
                expression(value);
                emitKeys(keys);
                b.endWriteNested();
            } else {
                b.beginWritePropNested(place.site(), append);
                expression(value);
                writtenObject(place.property().get("expr"), BP_VAR_W);
                propertyKeys(place, keys);
                b.endWritePropNested();
            }
        }

        /**
         * A nested element read as isset() reads it, once its keys are evaluated: ElementIfSet down from the
         * variable's value or the property read as readIfSet reads it.
         */
        private void emitReadIfSet(ElementPlace place, List<Object> keys) {
            for (int i = 0; i < keys.size(); i++) {
                b.beginElementIfSet();
            }
            if (place.ref() != null) {
                place.ref().readIfSet();
            } else if (place.variable() != null) {
                b.emitReadVarIfSet(place.variable());
            } else if (!(place.property().get("prop") instanceof String)) {
                b.beginReadPropIfSetNamed(place.site());
                readIfSet(place.property().get("expr"));
                propName(place.property().get("prop"));
                b.endReadPropIfSetNamed();
            } else {
                b.beginReadPropIfSet(place.site());
                readIfSet(place.property().get("expr"));
                b.endReadPropIfSet();
            }
            for (Object dim : keys) {
                key(dim);
                b.endElementIfSet();
            }
        }

        /** `base[k1]...[kn] op= value` (CompoundAssignNode on a DimNode): the keys, the value, then the element. */
        private void nestedCompound(Ast target, Object value, int op) {
            b.beginBlock();
            ElementPlace place = elementPlace(target, "compound assignment to");
            List<Object> keys = placeKeys(place, true);
            if (place.ref() != null) {
                b.beginCompoundRefNested(op);
                expression(value);
                place.ref().refForUpdate();
                emitKeys(keys);
                b.endCompoundRefNested();
            } else if (place.variable() != null) {
                b.beginCompoundNested(place.variable(), op);
                expression(value);
                emitKeys(keys);
                b.endCompoundNested();
            } else {
                b.beginCompoundPropNested(place.site(), op);
                expression(value);
                writtenObject(place.property().get("expr"), BP_VAR_RW);
                propertyKeys(place, keys);
                b.endCompoundPropNested();
            }
            b.endBlock();
        }

        /** `++base[k1]...[kn]`, `base[k]--`... (IncDecNode on a DimNode). */
        private void nestedIncDec(Ast target, boolean increment, boolean prefix) {
            b.beginBlock();
            ElementPlace place = elementPlace(target, "increment of");
            List<Object> keys = placeKeys(place, true);
            if (place.ref() != null) {
                b.beginIncDecRefNested(increment, prefix);
                place.ref().refForUpdate();
                emitKeys(keys);
                b.endIncDecRefNested();
            } else if (place.variable() != null) {
                b.beginIncDecNested(place.variable(), increment, prefix);
                emitKeys(keys);
                b.endIncDecNested();
            } else {
                b.beginIncDecPropNested(place.site(), increment, prefix);
                writtenObject(place.property().get("expr"), BP_VAR_RW);
                propertyKeys(place, keys);
                b.endIncDecPropNested();
            }
            b.endBlock();
        }

        /** `unset(base[k1]...[kn])` (DimNode.unset). */
        private void nestedUnset(Ast target) {
            if (lastDimAppends(target)) {
                throw compileError(target, "Cannot use [] for unsetting");
            }
            b.beginBlock();
            ElementPlace place = elementPlace(target, "unset of");
            List<Object> keys = placeKeys(place, true);
            if (place.ref() != null) {
                // DimNode.unset reads the chain: a global that doesn't exist isn't created
                b.beginUnsetRefNested();
                place.ref().ref(false);
                emitKeys(keys);
                b.endUnsetRefNested();
            } else if (place.variable() != null) {
                b.beginUnsetNested(place.variable());
                emitKeys(keys);
                b.endUnsetNested();
            } else {
                b.beginUnsetPropNested(place.site());
                writtenObject(place.property().get("expr"), BP_VAR_UNSET);
                propertyKeys(place, keys);
                b.endUnsetPropNested();
            }
            b.endBlock();
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
            if (callee == null) {
                // a value called, as `|>` calls its callable (Translator.pipeCall)
                callValue(node, node.get("expr"), byRefContext);
                return;
            }
            // a string callee naming a method, 'A::m'(), calls it as the value would be called
            boolean method = callee.is("AST_NAME") && ((String) callee.get("name")).contains("::");
            Object callable = method ? made(() -> Str.of((String) callee.get("name"))) : callee;
            if (Translator.isCallableConvert(node)) {
                if (!callee.is("AST_NAME") || method) {
                    b.beginConvertValue();
                    calleeValue(callable);
                    b.endConvertValue();
                    return;
                }
                String[] resolved = names.functionName((String) callee.get("name"), callee.flags);
                int site = (int) made(() -> resolved[1] == null ? -1 : fallbackSite(Str.lower(resolved[0])));
                b.emitConvertFunction(resolved[0], language.functionId(Str.lower(resolved[0])),
                        resolved[1] == null ? -1 : language.functionId(Str.lower(resolved[1])), site);
                return;
            }
            if (!callee.is("AST_NAME") || method) {
                callValue(node, callable, byRefContext);
                return;
            }
            String[] resolved = names.functionName((String) callee.get("name"), callee.flags);
            String name = resolved[1] != null ? resolved[1] : resolved[0].indexOf('\\') < 0 ? resolved[0] : "";
            String lower = Str.lower(name);
            if (resolved[1] != null && FRAME_FUNCTIONS.contains(lower)) {
                // unqualified in a namespace, php-src resolves the name at run time: the built-in compiled in place
                // while the call finds it, else the namespace's function (FunctionCallNode.lookUp)
                CallInfo info = (CallInfo) made(() -> new CallInfo(resolved[0],
                        language.functionId(Str.lower(resolved[0])), language.functionId(lower),
                        fallbackSite(Str.lower(resolved[0])), false));
                b.beginConditional();
                b.emitFallsBack(info);
                if (!frameFunction(node, lower, args, false)) {
                    functionCall(info, args, byRefContext);
                }
                functionCall(info, args, byRefContext);
                b.endConditional();
                return;
            }
            if (frameFunction(node, lower, args, resolved[1] == null)) {
                return;
            }
            boolean special = resolved[1] == null && constants.compiledSpecially(Str.lower(resolved[0]), args);
            if (special && lower.equals("defined")) {
                // ZEND_DEFINED in place
                Object site = made(() -> constants.definedSite(args));
                if (site instanceof DefinedSite defined) {
                    b.emitDefined(defined);
                } else {
                    b.emitLoadConstant(site);
                }
                return;
            }
            // the other built-ins the tree compiles in place (Intrinsics) are called as the built-ins they stand
            // for: none reads the caller's frame; a call php-src compiles into an opcode of its own makes no frame
            // in traces (CallInfo's frameless)
            if ((lower.equals("count") || lower.equals("sizeof")) && args.size() == 1 && special) {
                b.beginCount();
                expression(args.child(0));
                b.endCount();
                return;
            }
            CallInfo info = (CallInfo) made(() -> new CallInfo(resolved[0], language.functionId(Str.lower(resolved[0])),
                    resolved[1] == null ? -1 : language.functionId(Str.lower(resolved[1])),
                    resolved[1] == null ? -1 : fallbackSite(Str.lower(resolved[0])), special));
            functionCall(info, args, byRefContext);
        }

        /** The call of the function info names, looked up first, then each argument evaluated. */
        private void functionCall(CallInfo info, Ast args, boolean byRefContext) {
            NamedSite named = namedSite(args, false);
            if (named != null) {
                b.beginCallNamed(info, named, byRefContext);
                b.beginBindStackValue();
                b.emitResolveFunction(info);
                StackValue callee = b.endBindStackValue();
                namedArguments(args, named, callee);
                b.endCallNamed();
                return;
            }
            if (unpacks(args)) {
                b.beginCallSpread(info, byRefContext);
                b.beginBindStackValue();
                b.emitResolveFunction(info);
                StackValue spreadCallee = b.endBindStackValue();
                spreadArguments(args, spreadCallee);
                b.endCallSpread();
                return;
            }
            boolean needsCallee = argumentsNeedCallee(args);
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

        private void calleeValue(Object value) {
            if (value instanceof TruffleString constant) {
                b.emitLoadConstant(constant);
            } else {
                expression(value);
            }
        }

        /** The names {@link #frameFunction} compiles in place. */
        private static final Set<String> FRAME_FUNCTIONS = Set.of("func_get_args", "func_num_args",
                "call_user_func", "call_user_func_array", "get_class", "get_called_class", "func_get_arg", "compact",
                "extract", "get_object_vars", "get_defined_vars");

        /**
         * The built-ins the tree compiles itself (Translator.call): func_get_args(), func_num_args(),
         * call_user_func(), call_user_func_array(), get_class() and get_called_class() without arguments; false
         * for any other call. compiled: the name was resolved at compile time.
         */
        private boolean frameFunction(Ast node, String lower, Ast args, boolean compiled) {
            switch (lower) {
                case "func_get_args", "func_num_args" -> {
                    if (args.size() != 0) {
                        // php-src compiles a call with arguments as any other: the built-in's ArgumentCountError
                        return false;
                    }
                    if (lower.equals("func_get_args")) {
                        b.emitFuncGetArgs();
                    } else {
                        b.emitFuncNumArgs();
                    }
                    return true;
                }
                case "call_user_func" -> {
                    if (args.size() == 0 || hasUnpackOrNames(args)) {
                        // php-src compiles it as a call of the built-in (zend_compile_func_cuf)
                        return false;
                    }
                    b.beginCallUserFunc(compiled);
                    for (Object arg : args.children()) {
                        expression(arg);
                    }
                    b.endCallUserFunc();
                    return true;
                }
                case "call_user_func_array" -> {
                    if (args.size() != 2 || hasUnpackOrNames(args)) {
                        // php-src compiles it as a call of the built-in (zend_compile_func_cufa)
                        return false;
                    }
                    b.beginCallUserFuncArray(compiled);
                    expression(args.child(0));
                    expression(args.child(1));
                    b.endCallUserFuncArray();
                    return true;
                }
                case "get_class", "get_called_class" -> {
                    if (args.size() != 0) {
                        return false;
                    }
                    if (owner == null) {
                        // the scope or the called class the code runs with, else php-src's Error
                        if (lower.equals("get_class")) {
                            b.emitScopeClassName();
                        } else {
                            b.emitCalledClassName();
                        }
                        return true;
                    }
                    if (lower.equals("get_class")) {
                        // php-src 8.5's deprecation, which the tree's constant doesn't raise
                        b.beginBlock();
                        b.emitDeprecation("Calling get_class() without arguments is deprecated");
                        b.emitLoadConstant(made(() -> Str.of(owner.name())));
                        b.endBlock();
                    } else {
                        b.beginClassName();
                        b.emitResolveLateStatic(classSite(owner.name()));
                        b.endClassName();
                    }
                    return true;
                }
                case "func_get_arg" -> {
                    if (args.size() != 1 || hasUnpackOrNames(args)) {
                        // the built-in, called as any function (the tree's): its ArgumentCountError
                        return false;
                    }
                    b.beginFuncGetArg();
                    expression(args.child(0));
                    b.endFuncGetArg();
                    return true;
                }
                case "compact" -> {
                    if (args.size() == 0 || hasUnpackOrNames(args)) {
                        // the built-in's ArgumentCountError
                        return false;
                    }
                    compact(args);
                    return true;
                }
                case "extract" -> {
                    if (args.size() == 0 || args.size() > 3 || hasUnpackOrNames(args)) {
                        return false;
                    }
                    useCallScope();
                    b.beginExtract();
                    boolean byRef = constants.extractTakesRef(args);
                    for (Object arg : args.children()) {
                        if (byRef) {
                            refOf((Ast) arg);
                            byRef = false;
                        } else {
                            expression(arg);
                        }
                    }
                    b.endExtract();
                    return true;
                }
                case "get_object_vars" -> {
                    if (args.size() != 1 || hasUnpackOrNames(args)) {
                        return false;
                    }
                    b.beginObjectVars();
                    expression(args.child(0));
                    b.endObjectVars();
                    return true;
                }
                case "get_defined_vars" -> {
                    if (args.size() != 0) {
                        return false;
                    }
                    useCallScope();
                    b.emitDefinedVars();
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        /**
         * compact() (Translator's CompactNode, DynamicCompactNode): names given as literals are read where the
         * function has their variables, else looked up by name (an include's or extract()'s variable, $this); names
         * computed at run time are all looked up by name.
         */
        private void compact(Ast args) {
            List<String> literal = new ArrayList<>();
            if (!constants.compactNames(args, literal)) {
                b.beginCompactDynamic();
                for (Object arg : args.children()) {
                    expression(arg);
                }
                b.endCompactDynamic();
                return;
            }
            for (int i = literal.size() - 1; i >= 0; i--) {
                String name = literal.get(i);
                TruffleString key = (TruffleString) made(() -> language.intern(Str.of(name)));
                BytecodeLocal local = variables.get(name);
                if (local != null) {
                    b.beginCompactVar(local, key);
                } else {
                    b.beginCompactName(key);
                }
            }
            b.emitCompactStart();
            for (String name : literal) {
                if (variables.containsKey(name)) {
                    b.endCompactVar();
                } else {
                    b.endCompactName();
                }
            }
        }

        /** The call's variables by name are kept in a hidden local, made where the body first needs them. */
        private void useCallScope() {
            if (callScope == null && reread == null) {
                callScope = b.createLocal();
                if (firstRun) {
                    scopeLocal = LocalAccessor.constantOf(callScope);
                }
            }
        }

        /**
         * include / require (_once) (Translator's IncludeNode): the path, then the file run in the call's
         * variables. eval stays on the tree, which doesn't cover it either.
         */
        private void include(Ast node) {
            String kind = switch (node.flags) {
                case EXEC_EVAL -> "eval";
                case EXEC_INCLUDE_ONCE -> "include_once";
                case EXEC_REQUIRE -> "require";
                case EXEC_REQUIRE_ONCE -> "require_once";
                default -> "include";
            };
            useCallScope();
            if (node.flags == EXEC_EVAL) {
                b.beginEval((IncludeSite) made(() -> new IncludeSite(kind)));
                expression(node.get("expr"));
                b.endEval();
                return;
            }
            b.beginInclude((IncludeSite) made(() -> new IncludeSite(kind)));
            expression(node.get("expr"));
            b.endInclude();
        }

        private boolean hasUnpackOrNames(Ast args) {
            for (Object arg : args.children()) {
                if (arg instanceof Ast ast && (ast.is("AST_UNPACK") || ast.is("AST_NAMED_ARG")
                        || ast.is("AST_CALLABLE_CONVERT"))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * `$f(args)`, `'Cls::m'(args)`, `[$obj, 'm'](args)` (CallableCallNode): the callable resolved first
         * (INIT_DYNAMIC_CALL), then the arguments as its function's parameters take them (SEND_VAR_EX), then the
         * call.
         */
        private void callValue(Ast node, Object calleeValue, boolean keepRef) {
            Ast args = node.node("args");
            NamedSite named = namedSite(args, false);
            if (named != null) {
                b.beginCallValueNamed(named, keepRef);
                b.beginBindStackValue();
                b.beginResolveCallable();
                calleeValue(calleeValue);
                b.endResolveCallable();
                StackValue target = b.endBindStackValue();
                namedCallableArguments(args, named, target);
                b.endCallValueNamed();
                return;
            }
            boolean spread = unpacks(args);
            boolean needsCallee = spread || argumentsNeedCallee(args);
            if (spread) {
                b.beginCallValueSpread(keepRef);
            } else {
                b.beginCallValue(keepRef);
            }
            b.beginBindStackValue();
            b.beginResolveCallable();
            calleeValue(calleeValue);
            b.endResolveCallable();
            StackValue target = b.endBindStackValue();
            callableArguments(args, target, needsCallee, spread);
            if (spread) {
                b.endCallValueSpread();
            } else {
                b.endCallValue();
            }
        }

        /** The function operand of a CallValueNamed (TargetFunction) and its arguments. */
        private void namedCallableArguments(Ast args, NamedSite named, StackValue target) {
            b.beginBindStackValue();
            b.beginTargetFunction();
            b.emitLoadStackValue(target);
            b.endTargetFunction();
            StackValue callee = b.endBindStackValue();
            namedArguments(args, named, callee);
        }

        /** The function operand of a CallValue (its arguments' callee) and the arguments, a list if spread. */
        private void callableArguments(Ast args, StackValue target, boolean needsCallee, boolean spread) {
            StackValue calleeValue = callee(needsCallee, () -> {
                b.beginTargetFunction();
                b.emitLoadStackValue(target);
                b.endTargetFunction();
            });
            arguments(args, calleeValue, spread);
        }

        /** A call's arguments sent once its callee is known: each an operand, or the list a call unpacking takes. */
        private void arguments(Ast args, StackValue callee, boolean spread) {
            if (spread) {
                spreadArguments(args, callee);
                return;
            }
            for (int i = 0; i < args.size(); i++) {
                argument(args.child(i), i, callee);
            }
        }

        /**
         * Whether a call unpacks arguments (`f($a, ...$b)`), checked as php-src's zend_compile_args does: no
         * positional argument after an unpacked one (its compile error); named arguments stay on the tree, whose
         * translation doesn't cover them either.
         */
        private boolean unpacks(Ast args) {
            boolean unpacked = false;
            for (Object arg : args.children()) {
                if (arg instanceof Ast ast && (ast.is("AST_NAMED_ARG") || ast.is("AST_CALLABLE_CONVERT"))) {
                    throw new Unsupported("argument", ast);
                }
                if (isUnpack(arg)) {
                    unpacked = true;
                } else if (unpacked) {
                    throw compileError(arg instanceof Ast ast ? ast : args,
                            "Cannot use positional argument after argument unpacking");
                }
            }
            return unpacked;
        }

        private static boolean isUnpack(Object arg) {
            return arg instanceof Ast ast && ast.is("AST_UNPACK");
        }

        /**
         * The names of a call's arguments, or null for a call without any (its bytecode is a positional call's).
         * php-src's zend_compile_args rejects a positional argument or an unpacking after a name (the file's check,
         * JumpChecks, reports them first); names after an unpacking are bound when the call runs, not covered here.
         * construct: a `new`'s.
         */
        private NamedSite namedSite(Ast args, boolean construct) {
            int first = -1;
            boolean unpacked = false;
            for (int i = 0; i < args.size(); i++) {
                Object arg = args.child(i);
                Ast at = arg instanceof Ast ast ? ast : args;
                if (at.is("AST_NAMED_ARG")) {
                    first = first < 0 ? i : first;
                } else if (isUnpack(arg)) {
                    if (first >= 0) {
                        throw compileError(at, "Cannot use argument unpacking after named arguments");
                    }
                    unpacked = true;
                } else if (unpacked) {
                    throw compileError(at, "Cannot use positional argument after argument unpacking");
                } else if (first >= 0) {
                    throw compileError(at, "Cannot use positional argument after named argument");
                }
            }
            if (first < 0) {
                return null;
            }
            if (unpacked) {
                throw new Unsupported("named argument after unpacking", (Ast) args.child(first));
            }
            int count = args.size();
            int positional = first;
            boolean constant = constantExpression;
            return (NamedSite) made(() -> {
                TruffleString[] argumentNames = new TruffleString[count];
                for (int i = positional; i < count; i++) {
                    argumentNames[i] = language.intern(Str.of((String) ((Ast) args.child(i)).get("name")));
                }
                return new NamedSite(argumentNames, construct, constant);
            });
        }

        /** A call's arguments sent: the positional ones before the first name as any call's, then the named ones. */
        private void namedArguments(Ast args, NamedSite named, StackValue callee) {
            for (int i = 0; i < args.size(); i++) {
                if (i < named.positional()) {
                    argument(args.child(i), i, callee);
                } else {
                    namedArgument(((Ast) args.child(i)).get("expr"), named, i, callee);
                }
            }
        }

        /**
         * A named argument's value, sent as php-src sends it (zend_compile_args with a name, then
         * zend_handle_named_arg at the SEND): a variable bound before it is read (SendVarNamed); a value or a call's
         * result once evaluated; a place fetched as its parameter takes it (NamedTarget), then bound (SendPlaceNamed).
         */
        private void namedArgument(Object value, NamedSite named, int argument, StackValue callee) {
            if (value instanceof Ast chain && (chain.is("BYTECODE_OBJECT")
                    || Translator.deepestNullsafe(chain) != null)) {
                if (Translator.isCall(chain)) {
                    b.beginSendCallNamed(named, argument);
                    b.emitLoadStackValue(callee);
                    expression(chain);
                    b.endSendCallNamed();
                } else {
                    b.beginSendValueNamed(named, argument);
                    b.emitLoadStackValue(callee);
                    expression(chain);
                    b.endSendValueNamed();
                }
                return;
            }
            if (plainArgument(value) || value instanceof Ast assign && assign.is("AST_ASSIGN_REF")
                    && !(isVariable(assign.node("var")) || assign.node("var").is("AST_VAR")
                    && assign.node("var").get("name") instanceof String)) {
                // $this, and a reference assigned to an element or a property (sent as its value, as positionally)
                b.beginSendPlaceNamed(named, argument);
                b.emitLoadStackValue(callee);
                expression(value);
                b.endSendPlaceNamed();
                return;
            }
            if (value instanceof Ast assign && assign.is("AST_ASSIGN_REF")) {
                // ASSIGN_REF's result is the reference: the assignment, then its variable as the argument
                b.beginBlock();
                assignRef(assign);
                namedArgument(assign.node("var"), named, argument, callee);
                b.endBlock();
                return;
            }
            if (valueArgument(value)) {
                b.beginSendValueNamed(named, argument);
                b.emitLoadStackValue(callee);
                expression(value);
                b.endSendValueNamed();
                return;
            }
            Ast ast = (Ast) value;
            NamedSend send = new NamedSend(named, argument);
            if (refPlace(ast) instanceof RefPlace kept) {
                b.beginSendPlaceNamed(named, argument);
                b.emitLoadStackValue(callee);
                b.beginBlock();
                kept.prepare();
                b.beginConditional();
                argByRef(0, callee, send);
                kept.ref(true);
                kept.read();
                b.endConditional();
                b.endBlock();
                b.endSendPlaceNamed();
            } else if (ast.is("AST_VAR")) {
                b.beginSendVarNamed(variable(ast, "argument"), named, argument);
                b.emitLoadStackValue(callee);
                b.endSendVarNamed();
            } else if (ast.is("AST_DIM") || ast.is("AST_PROP")) {
                b.beginSendPlaceNamed(named, argument);
                b.emitLoadStackValue(callee);
                placeArgument(ast, 0, callee, send);
                b.endSendPlaceNamed();
            } else {
                b.beginSendCallNamed(named, argument);
                b.emitLoadStackValue(callee);
                callArgument(ast);
                b.endSendCallNamed();
            }
        }

        /**
         * The arguments of a call that unpacks, as the list its call takes (SpreadStart, ArgumentsNode's spread):
         * the arguments before the first `...` sent as any call's, at their constant positions; then each unpacked
         * array's elements, by reference where they land on the callee's by-reference parameters (a variable's
         * array separated for it, SpreadVar).
         */
        private void spreadArguments(Ast args, StackValue callee) {
            int count = args.size();
            for (int i = count - 1; i >= 0; i--) {
                Object arg = args.child(i);
                if (!isUnpack(arg)) {
                    b.beginSpreadAdd();
                } else if (((Ast) arg).get("expr") instanceof Ast unpacked && isVariable(unpacked)) {
                    b.beginSpreadVar(variable(unpacked, "unpacking of"));
                } else if (namedPlace(((Ast) arg).get("expr"))) {
                    b.beginSpreadRef();
                } else {
                    b.beginSpreadValue();
                }
            }
            b.emitSpreadStart();
            for (int i = 0; i < count; i++) {
                Object arg = args.child(i);
                if (!isUnpack(arg)) {
                    argument(arg, i, callee);
                    b.endSpreadAdd();
                    continue;
                }
                b.emitLoadStackValue(callee);
                Object unpacked = ((Ast) arg).get("expr");
                if (unpacked instanceof Ast ast && isVariable(ast)) {
                    b.endSpreadVar();
                } else if (namedPlace(unpacked)) {
                    refPlace(unpacked).ref(true);
                    b.endSpreadRef();
                } else {
                    expression(unpacked);
                    b.endSpreadValue();
                }
            }
        }

        /** A file's variable or a superglobal: a variable by a constant name kept in a reference (RefPlace). */
        private boolean namedPlace(Object value) {
            return value instanceof Ast ast && ast.is("AST_VAR") && ast.get("name") instanceof String
                    && refPlace(ast) != null;
        }

        /** Whether a call's arguments need the callee (some may go by reference); no unpacking or names. */
        private boolean argumentsNeedCallee(Ast args) {
            boolean needsCallee = false;
            for (Object arg : args.children()) {
                if (arg instanceof Ast ast && (ast.is("AST_UNPACK") || ast.is("AST_NAMED_ARG")
                        || ast.is("AST_CALLABLE_CONVERT"))) {
                    throw new Unsupported("argument", ast);
                }
                needsCallee |= !plainArgument(arg);
            }
            return needsCallee;
        }

        /**
         * The callee resolving instruction a call's arguments ask about their parameters, kept on the operand stack
         * for them when they need it (as {@link #call}'s).
         */
        private StackValue callee(boolean needed, Runnable resolve) {
            if (!needed) {
                resolve.run();
                return null;
            }
            b.beginBindStackValue();
            resolve.run();
            return b.endBindStackValue();
        }

        /**
         * `$obj->name(args)` (MethodCallNode): the receiver, then its method (INIT_METHOD_CALL), then the arguments
         * as the method's parameters take them.
         */
        private void methodCall(Ast node, boolean byRefContext) {
            if (Translator.isCallableConvert(node)) {
                // `$obj->name(...)` (CallableConvertNodes.MethodNode): the object, then the name
                b.beginConvertMethod();
                expression(node.get("expr"));
                Object method = node.get("method");
                if (method instanceof String constant) {
                    b.emitLoadConstant(made(() -> Str.of(constant)));
                } else {
                    expression(method);
                }
                b.endConvertMethod();
                return;
            }
            if (!(node.get("method") instanceof String name)) {
                // `$obj->$name(args)` (DynamicMethodCallNode): the callable [$obj, name], then the arguments
                Ast args = node.node("args");
                NamedSite named = namedSite(args, false);
                if (named != null) {
                    b.beginCallValueNamed(named, byRefContext);
                    b.beginBindStackValue();
                    b.beginResolveDynamicMethod();
                    expression(node.get("expr"));
                    expression(node.get("method"));
                    b.endResolveDynamicMethod();
                    StackValue target = b.endBindStackValue();
                    namedCallableArguments(args, named, target);
                    b.endCallValueNamed();
                    return;
                }
                boolean spread = unpacks(args);
                boolean needsCallee = spread || argumentsNeedCallee(args);
                if (spread) {
                    b.beginCallValueSpread(byRefContext);
                } else {
                    b.beginCallValue(byRefContext);
                }
                b.beginBindStackValue();
                b.beginResolveDynamicMethod();
                expression(node.get("expr"));
                expression(node.get("method"));
                b.endResolveDynamicMethod();
                StackValue target = b.endBindStackValue();
                callableArguments(args, target, needsCallee, spread);
                if (spread) {
                    b.endCallValueSpread();
                } else {
                    b.endCallValue();
                }
                return;
            }
            Ast args = node.node("args");
            NamedSite named = namedSite(args, false);
            if (named != null) {
                MethodSite site = (MethodSite) made(() -> new MethodSite(name, false));
                b.beginCallMethodNamed(site, named, byRefContext);
                b.beginBindStackValue();
                expression(node.get("expr"));
                StackValue receiver = b.endBindStackValue();
                b.beginBindStackValue();
                b.beginResolveMethod(site);
                b.emitLoadStackValue(receiver);
                b.endResolveMethod();
                StackValue callee = b.endBindStackValue();
                namedArguments(args, named, callee);
                b.endCallMethodNamed();
                return;
            }
            boolean spread = unpacks(args);
            boolean needsCallee = spread || argumentsNeedCallee(args);
            MethodSite site = (MethodSite) made(() -> new MethodSite(name, false));
            if (spread) {
                b.beginCallMethodSpread(site, byRefContext);
            } else {
                b.beginCallMethod(site, byRefContext);
            }
            b.beginBindStackValue();
            expression(node.get("expr"));
            StackValue receiver = b.endBindStackValue();
            StackValue calleeValue = callee(needsCallee, () -> {
                b.beginResolveMethod(site);
                b.emitLoadStackValue(receiver);
                b.endResolveMethod();
            });
            arguments(args, calleeValue, spread);
            if (spread) {
                b.endCallMethodSpread();
            } else {
                b.endCallMethod();
            }
        }

        /**
         * `Cls::name(args)`, `self::`, `parent::`, `static::` (StaticCallNode): the class, then its method
         * (INIT_STATIC_METHOD_CALL), then the arguments; self, parent and static forward the called class.
         */
        private void staticCall(Ast node, boolean byRefContext) {
            Ast classNode = node.node("class");
            if (Translator.isParentHookCall(node)) {
                parentHook(node, Translator.parentHookProperty(node), (String) node.get("method"));
                return;
            }
            String lower = classNode != null && classNode.is("AST_NAME") ? Str.lower(classNameOf(classNode)) : "";
            boolean forwarding = lower.equals("self") || lower.equals("parent") || lower.equals("static");
            if (!(node.get("method") instanceof String name)) {
                staticCallByName(node, forwarding, byRefContext);
                return;
            }
            if (Translator.isCallableConvert(node)) {
                // `Cls::name(...)` (CallableConvertNodes.StaticMethodNode)
                b.beginConvertStatic(name, forwarding);
                classRef(node.get("class"));
                b.endConvertStatic();
                return;
            }
            Ast args = node.node("args");
            NamedSite named = namedSite(args, false);
            if (named != null) {
                MethodSite site = (MethodSite) made(() -> new MethodSite(name, forwarding));
                b.beginCallStaticNamed(site, named, byRefContext);
                b.beginBindStackValue();
                classRef(node.get("class"));
                StackValue phpClass = b.endBindStackValue();
                b.beginBindStackValue();
                b.beginResolveStaticMethod(site);
                b.emitLoadStackValue(phpClass);
                b.endResolveStaticMethod();
                StackValue callee = b.endBindStackValue();
                namedArguments(args, named, callee);
                b.endCallStaticNamed();
                return;
            }
            boolean spread = unpacks(args);
            boolean needsCallee = spread || argumentsNeedCallee(args);
            MethodSite site = (MethodSite) made(() -> new MethodSite(name, forwarding));
            if (spread) {
                b.beginCallStaticSpread(site, byRefContext);
            } else {
                b.beginCallStatic(site, byRefContext);
            }
            b.beginBindStackValue();
            classRef(node.get("class"));
            StackValue phpClass = b.endBindStackValue();
            StackValue calleeValue = callee(needsCallee, () -> {
                b.beginResolveStaticMethod(site);
                b.emitLoadStackValue(phpClass);
                b.endResolveStaticMethod();
            });
            arguments(args, calleeValue, spread);
            if (spread) {
                b.endCallStaticSpread();
            } else {
                b.endCallStatic();
            }
        }

        /**
         * `parent::$property::get()` or `::set(...)` (zend_compile_parent_property_hook_call): only in that hook of
         * that property; its arguments by value.
         */
        private void parentHook(Ast node, String property, String method) {
            if (Translator.isCallableConvert(node)) {
                throw compileError(node, "Cannot create Closure for parent property hook call");
            }
            String hook = function == null ? "" : function.getName();
            String error = Translator.parentHookError(node, hook);
            if (error != null) {
                throw compileError(node, error);
            }
            String kind = hook.substring(hook.lastIndexOf("::") + 2);
            PropSite site = (PropSite) made(() -> new PropSite(Str.sourceKey(property), Str.lower(owner.name()),
                    false, false, null));
            b.beginParentHook(site, kind.equals("set"));
            for (Object arg : node.node("args").children()) {
                if (arg instanceof Ast ast && (ast.is("AST_UNPACK") || ast.is("AST_NAMED_ARG"))) {
                    throw new Unsupported("unpacked or named argument of a parent hook call", ast);
                }
                expression(arg);
            }
            b.endParentHook();
        }

        /**
         * `Cls::$name(args)` (StaticCallNode with a name expression): the class, then the name, then what they call
         * (ResolveStaticCall) as a callable's call; `Cls::$name(...)` its closure.
         */
        private void staticCallByName(Ast node, boolean forwarding, boolean byRefContext) {
            if (Translator.isCallableConvert(node)) {
                b.beginConvertStaticNamed(forwarding);
                classRef(node.get("class"));
                expression(node.get("method"));
                b.endConvertStaticNamed();
                return;
            }
            Ast args = node.node("args");
            NamedSite named = namedSite(args, false);
            if (named != null) {
                b.beginCallValueNamed(named, byRefContext);
                b.beginBindStackValue();
                b.beginResolveStaticCall(forwarding);
                classRef(node.get("class"));
                expression(node.get("method"));
                b.endResolveStaticCall();
                StackValue target = b.endBindStackValue();
                namedCallableArguments(args, named, target);
                b.endCallValueNamed();
                return;
            }
            boolean spread = unpacks(args);
            boolean needsCallee = spread || argumentsNeedCallee(args);
            if (spread) {
                b.beginCallValueSpread(byRefContext);
            } else {
                b.beginCallValue(byRefContext);
            }
            b.beginBindStackValue();
            b.beginResolveStaticCall(forwarding);
            classRef(node.get("class"));
            expression(node.get("method"));
            b.endResolveStaticCall();
            StackValue target = b.endBindStackValue();
            callableArguments(args, target, needsCallee, spread);
            if (spread) {
                b.endCallValueSpread();
            } else {
                b.endCallValue();
            }
        }

        /**
         * `new Cls(args)` (NewNode, ZEND_NEW): the class, the object with its defaults, its constructor, then the
         * arguments as the constructor's parameters take them and its call; without a constructor the arguments are
         * evaluated by value and dropped (zend_pass_function). The object is the value.
         */
        private void newObject(Ast node) {
            if (Translator.isCallableConvert(node)) {
                throw compileError(node, "Cannot create Closure for new expression");
            }
            ClassTemplate anonymous = null;
            if (node.get("class") instanceof Ast classNode && classNode.is("AST_CLASS")) {
                // `new class(...) {...}` (NewAnonymousClassNode): declared the first time it runs
                anonymous = (ClassTemplate) made(() -> constants.anonymousClass(classNode));
                b.beginBlock();
                b.emitDeclareAnonymousClass(anonymous);
            }
            Ast args = node.node("args");
            NamedSite named = namedSite(args, true);
            if (named != null) {
                b.beginConstructNamed(named);
                b.beginBindStackValue();
                b.beginInstantiate();
                if (anonymous != null) {
                    b.emitResolveClass(classSite(anonymous.getName()));
                } else {
                    classRef(node.get("class"));
                }
                b.endInstantiate();
                StackValue object = b.endBindStackValue();
                b.beginBindStackValue();
                b.beginResolveConstructor();
                b.emitLoadStackValue(object);
                b.endResolveConstructor();
                StackValue callee = b.endBindStackValue();
                namedArguments(args, named, callee);
                b.endConstructNamed();
                if (anonymous != null) {
                    b.endBlock();
                }
                return;
            }
            boolean spread = unpacks(args);
            boolean needsCallee = spread || argumentsNeedCallee(args);
            if (spread) {
                b.beginConstructSpread();
            } else {
                b.beginConstruct();
            }
            b.beginBindStackValue();
            b.beginInstantiate();
            if (anonymous != null) {
                b.emitResolveClass(classSite(anonymous.getName()));
            } else {
                classRef(node.get("class"));
            }
            b.endInstantiate();
            StackValue object = b.endBindStackValue();
            StackValue calleeValue = callee(needsCallee, () -> {
                b.beginResolveConstructor();
                b.emitLoadStackValue(object);
                b.endResolveConstructor();
            });
            arguments(args, calleeValue, spread);
            if (spread) {
                b.endConstructSpread();
            } else {
                b.endConstruct();
            }
            if (anonymous != null) {
                b.endBlock();
            }
        }

        /**
         * A class reference (Translator.classRef), as a PhpClass: a name (self and parent as the enclosing class's
         * names), static's late-static-binding class, or an expression's (an object's class or a name).
         */
        private void classRef(Object classNode) {
            if (!(classNode instanceof Ast ast) || !ast.is("AST_NAME")) {
                b.beginClassOf();
                expression(classNode);
                b.endClassOf();
                return;
            }
            String name = classNameOf(ast);
            switch (Str.lower(name)) {
                case "self" -> {
                    if (inClosure()) {
                        b.emitClosureScope(ClassRefNode.SELF);
                    } else {
                        b.emitResolveClass(classSite(enclosingClass(ast).name()));
                    }
                }
                case "parent" -> {
                    if (inClosure()) {
                        b.emitClosureScope(ClassRefNode.PARENT);
                    } else {
                        b.emitResolveClass(classSite(parentClass(ast)));
                    }
                }
                // outside a class, a closure's called class is the scope it is bound to, if any
                case "static" -> b.emitResolveLateStatic(owner == null && inClosure()
                        ? (ClassSite) made(ClassSite::noScope) : classSite(enclosingClass(ast).name()));
                default -> b.emitResolveClass(classSite(name));
            }
        }

        private String classNameOf(Ast name) {
            return names.className((String) name.get("name"), name.flags);
        }

        private ClassSite classSite(String name) {
            return (ClassSite) made(() -> new ClassSite(name, language.classId(Str.lower(name))));
        }

        /** Whether the function is a closure: self and parent are its bound scope's, found when it runs. */
        private boolean inClosure() {
            return reread != null || function != null && function.getName().startsWith(CLOSURE_PREFIX);
        }

        /** The class the code is written in, for self and static (the tree's requireClass). */
        private Translator.ClassInfo enclosingClass(Ast node) {
            if (owner == null) {
                // zend_ensure_valid_class_fetch_type, where the scope is known: a function outside a class
                String which = node.is("AST_NAME") ? Str.lower(classNameOf(node)) : "self";
                throw compileError(node, "Cannot use \"" + which + "\" when no class scope is active");
            }
            return owner;
        }

        private String parentClass(Ast node) {
            String parent = enclosingClass(node).parentName();
            if (parent == null) {
                throw compileError(node, "Cannot use \"parent\" when current class scope has no parent");
            }
            return parent;
        }

        /** `Cls::NAME` (ClassConstNode): a named class's value may stay, static::'s is looked up per call. */
        private void classConstant(Ast node) {
            String constantName = (String) node.get("const");
            if (constantName.equalsIgnoreCase("class")) {
                className(node);
                return;
            }
            Object classNode = node.get("class");
            boolean cacheable = classNode instanceof Ast named && named.is("AST_NAME")
                    && !classNameOf(named).equalsIgnoreCase("static")
                    && !(inClosure() && Str.lower(classNameOf(named)).matches("self|parent"));
            b.beginClassConstant(constantName, cacheable);
            classRef(classNode);
            b.endClassConstant();
        }

        /** `Cls::class` (Translator.classNameExpr): a name as written, self's and parent's; static's per call. */
        private void className(Ast node) {
            Object classNode = node.get("class");
            if (!(classNode instanceof Ast ast) || !ast.is("AST_NAME")) {
                b.beginObjectClassName();
                expression(classNode);
                b.endObjectClassName();
                return;
            }
            String name = classNameOf(ast);
            switch (Str.lower(name)) {
                case "self" -> {
                    if (inClosure()) {
                        b.emitClosureScope(ClassRefNode.SELF_NAME);
                    } else {
                        b.emitLoadConstant(made(() -> Str.of(enclosingClass(ast).name())));
                    }
                }
                case "parent" -> {
                    if (inClosure()) {
                        b.emitClosureScope(ClassRefNode.PARENT_NAME);
                    } else {
                        b.emitLoadConstant(made(() -> Str.of(parentClass(ast))));
                    }
                }
                case "static" -> {
                    if (inClosure()) {
                        b.emitClosureScope(ClassRefNode.STATIC_NAME);
                    } else {
                        b.beginClassName();
                        b.emitResolveLateStatic(classSite(enclosingClass(ast).name()));
                        b.endClassName();
                    }
                }
                default -> b.emitLoadConstant(made(() -> Str.of(name)));
            }
        }

        /**
         * `$x instanceof Name` (InstanceofNode, the class need not exist; self is the enclosing class) and
         * `$x instanceof $class` (DynamicInstanceofNode). The tree takes static as self and parent as a class
         * named "parent": those stay on the tree.
         */
        private void instanceOf(Ast node) {
            Ast classNode = node.node("class");
            if (!classNode.is("AST_NAME")) {
                b.beginInstanceOfValue();
                expression(node.get("expr"));
                expression(classNode);
                b.endInstanceOfValue();
                return;
            }
            String name = classNameOf(classNode);
            String lower = Str.lower(name);
            if (lower.equals("static") || lower.equals("parent") || lower.equals("self") && inClosure()) {
                // the class resolved as the code runs (ZEND_FETCH_CLASS_STATIC, _PARENT, a closure's scope)
                b.beginInstanceOfValue();
                expression(node.get("expr"));
                classRef(classNode);
                b.endInstanceOfValue();
                return;
            }
            if (lower.equals("self")) {
                name = enclosingClass(node).name();
            }
            b.beginInstanceOf(Str.lower(name));
            expression(node.get("expr"));
            b.endInstanceOf();
        }

        /**
         * An argument passed as its value whatever the callee's parameter: $this (SEND_VAR_EX of FETCH_THIS, which a
         * by-reference parameter binds as a new reference).
         */
        private boolean plainArgument(Object arg) {
            return arg instanceof Ast ast && isThis(ast);
        }

        /**
         * An argument php-src sends with SEND_VAL_EX: neither a place nor an IS_VAR result (a call, `new`, include,
         * yield); $GLOBALS is a value, a built-in compiled in place a temporary.
         */
        private boolean valueArgument(Object arg) {
            if (!(arg instanceof Ast ast) || isGlobals(ast)) {
                return true;
            }
            if (ast.is("AST_VAR") || ast.is("AST_DIM") || ast.is("AST_PROP") || ast.is("AST_STATIC_PROP")
                    || ast.is("BYTECODE_OBJECT") || Translator.deepestNullsafe(ast) != null) {
                return false;
            }
            return Translator.isCall(ast) ? constants.compiledInPlace(ast) : !Translator.isVarResult(ast);
        }

        private void argument(Object arg, int index, StackValue callee) {
            if (arg instanceof Ast chain && (chain.is("BYTECODE_OBJECT") || Translator.deepestNullsafe(chain) != null)) {
                // a `?->` chain, or the operand of `|>`: a call's result (SEND_VAR_NO_REF), else a value, which a
                // by-reference parameter refuses (SEND_VAL_EX)
                if (Translator.isCall(chain)) {
                    b.beginSendCall(index);
                    b.emitLoadStackValue(callee);
                    expression(chain);
                    b.endSendCall();
                } else {
                    b.beginSendValue(index);
                    b.emitLoadStackValue(callee);
                    expression(chain);
                    b.endSendValue();
                }
                return;
            }
            if (plainArgument(arg)) {
                expression(arg);
                return;
            }
            if (arg instanceof Ast assign && assign.is("AST_ASSIGN_REF")) {
                if (isVariable(assign.node("var")) || assign.node("var").is("AST_VAR")
                        && assign.node("var").get("name") instanceof String) {
                    // ASSIGN_REF's result is the reference, which SEND_VAR_NO_REF passes: the assignment, then its
                    // variable (a file's or a global by name too) as the argument
                    b.beginBlock();
                    assignRef(assign);
                    argument(assign.node("var"), index, callee);
                    b.endBlock();
                } else {
                    // ponytail: a reference assigned to an element or a property is sent as its value, unchecked
                    expression(arg);
                }
                return;
            }
            if (valueArgument(arg)) {
                b.beginSendValue(index);
                b.emitLoadStackValue(callee);
                expression(arg);
                b.endSendValue();
                return;
            }
            Ast ast = (Ast) arg;
            if (refPlace(ast) instanceof RefPlace kept) {
                // ArgumentsNode: the place's reference for a by-reference parameter, else its value
                b.beginBlock();
                kept.prepare();
                b.beginConditional();
                argByRef(index, callee);
                kept.ref(true);
                kept.read();
                b.endConditional();
                b.endBlock();
            } else if (ast.is("AST_VAR")) {
                b.beginSendVar(variable(ast, "argument"), index);
                b.emitLoadStackValue(callee);
                b.endSendVar();
            } else if (ast.is("AST_DIM") || ast.is("AST_PROP")) {
                placeArgument(ast, index, callee, null);
            } else {
                // a call, `new`, include or yield (SEND_VAR_NO_REF)
                b.beginSendCall(index);
                b.emitLoadStackValue(callee);
                callArgument(ast);
                b.endSendCall();
            }
        }

        /** A call, `new`, include or yield as an argument: a call's result as it may return a reference. */
        private void callArgument(Ast ast) {
            if (ast.is("AST_CALL")) {
                call(ast, true);
            } else if (ast.is("AST_METHOD_CALL")) {
                methodCall(ast, true);
            } else if (ast.is("AST_STATIC_CALL")) {
                staticCall(ast, true);
            } else {
                expression(ast);
            }
        }

        /**
         * An element or a property as argument index: its reference when the parameter is by reference, else its
         * value (FETCH_*_FUNC_ARG). named: a named argument's, whose send asks NamedTarget about the parameter.
         */
        private void placeArgument(Ast ast, int index, StackValue callee, NamedSend named) {
            if (ast.is("AST_DIM") && temporaryBase(ast) != null) {
                temporaryElementArgument(ast, temporaryBase(ast), index, callee, named);
            } else if (ast.is("AST_DIM") && isVariable(ast.node("expr")) && ast.get("dim") != null) {
                b.beginSendElement(variable(ast.node("expr"), "argument element of"), index);
                sendCallee(callee, named);
                key(ast.get("dim"));
                b.endSendElement();
            } else if (ast.is("AST_DIM")) {
                // a nested element, or an element of a property: its reference or its value; `[]` last takes a
                // reference only, else the tree's Error "Cannot use [] for reading"
                b.beginBlock();
                ElementPlace place = elementPlace(ast, "argument");
                boolean append = place.appends();
                List<Object> keys = placeKeys(place, !append);
                if (place.ref() != null && append) {
                    // a new element's reference, else the Error (SendTemporary on the place's reference)
                    b.beginSendTemporary(index, true, true);
                    b.emitLoadStackValue(callee);
                    place.ref().ref(true);
                    emitKeys(keys);
                    b.endSendTemporary();
                } else if (place.ref() != null) {
                    // the element's reference (DimNode.makeRef) or its value (DimNode.execute down the chain)
                    b.beginConditional();
                    argByRef(index, callee, named);
                    b.beginRefRefNested(false);
                    place.ref().ref(true);
                    emitKeys(keys);
                    b.endRefRefNested();
                    for (int i = 0; i < keys.size(); i++) {
                        b.beginReadElement();
                    }
                    place.ref().read();
                    for (Object dim : keys) {
                        key(dim);
                        b.endReadElement();
                    }
                    b.endConditional();
                } else if (place.variable() != null) {
                    b.beginSendNested(place.variable(), index, append);
                    sendCallee(callee, named);
                    emitKeys(keys);
                    b.endSendNested();
                } else {
                    b.beginSendPropNested(place.site(), index, append);
                    sendCallee(callee, named);
                    writtenObject(place.property().get("expr"), BP_VAR_W);
                    propertyKeys(place, keys);
                    b.endSendPropNested();
                }
                b.endBlock();
            } else if (ast.is("AST_PROP") && ast.get("prop") instanceof String name) {
                b.beginSendProp(propSite(ast, name), index);
                sendCallee(callee, named);
                expression(ast.get("expr"));
                b.endSendProp();
            } else if (ast.is("AST_PROP")) {
                // `$obj->$name`: the object, then the name (FETCH_OBJ_FUNC_ARG's operands)
                b.beginSendPropNamed(propSite(ast, null), index);
                sendCallee(callee, named);
                expression(ast.get("expr"));
                propName(ast.get("prop"));
                b.endSendPropNamed();
            }
        }

        private void argByRef(int index, StackValue callee) {
            argByRef(index, callee, null);
        }

        private void argByRef(int index, StackValue callee, NamedSend named) {
            b.beginArgByRef(index);
            sendCallee(callee, named);
            b.endArgByRef();
        }

        /**
         * The callee a send asks about its parameter: the call's, or for a named argument the stand-in its binding
         * gives (NamedTarget).
         */
        private void sendCallee(StackValue callee, NamedSend named) {
            if (named == null) {
                b.emitLoadStackValue(callee);
                return;
            }
            b.beginNamedTarget(named.site(), named.argument());
            b.emitLoadStackValue(callee);
            b.endNamedTarget();
        }

        // ---- static properties, superglobals and $GLOBALS: places kept in a reference ----

        /**
         * A place whose storage is a reference (Translator.place's StaticPropNode, GlobalVarNode, GlobalsDimNode):
         * `Cls::$name`, of a class reference (a name, self, parent, static, an expression); a global by name, a
         * superglobal or `$GLOBALS['name']` by a constant key (php-src's ZEND_FETCH_GLOBAL of a CONST);
         * `$GLOBALS[key]` by a computed key, which names the global when the place is used; or `$$key`, the call's
         * variable the key names when the place is used (VariableVariableNode).
         */
        private final class RefPlace {
            private final Object classNode;
            private final StaticPropSite site;
            private final String global;
            private Object key;
            /** `$$key`, and the fetch type its read has (R, or what the object of a property place gave it). */
            private final boolean variable;
            private final int fetch;
            /** A class that is an expression, evaluated by prepare (zend_compile_class_ref fetches it at once). */
            private BytecodeLocal classLocal;
            private boolean prepared;

            RefPlace(Object classNode, StaticPropSite site, String global, Object key) {
                this(classNode, site, global, key, false, VariableVariableNode.R);
            }

            RefPlace(Object classNode, StaticPropSite site, String global, Object key, boolean variable, int fetch) {
                this.classNode = classNode;
                this.site = site;
                this.global = global;
                this.key = key;
                this.variable = variable;
                this.fetch = fetch;
                if (variable) {
                    useCallScope();
                }
            }

            /** `Cls::$$key` fetched as mode says (StaticPropNamed). */
            private void staticPropNamed(int mode) {
                b.beginStaticPropNamed(site, mode);
                emitClass();
                propName(key);
                b.endStaticPropNamed();
            }

            /** `$$key` fetched as type says (VariableValue). */
            private void variableValue(int type) {
                b.beginVariableValue(type);
                expression(key);
                b.endVariableValue();
            }

            /**
             * Before a value assigned to the place, or a second use of it: what php-src evaluates first, a class
             * that is an expression, and a key that isn't a constant or a variable (zend_delayed_compile_var).
             */
            void prepare() {
                if (prepared) {
                    return;
                }
                prepared = true;
                if (site != null && !(classNode instanceof Ast name && name.is("AST_NAME"))) {
                    classLocal = b.createLocal();
                    b.beginStoreLocal(classLocal);
                    classRef(classNode);
                    b.endStoreLocal();
                }
                if (key != null) {
                    key = onceKey(key);
                }
            }

            private void emitClass() {
                if (classLocal != null) {
                    b.emitLoadLocal(classLocal);
                } else {
                    classRef(classNode);
                }
            }

            /** The place's value (execute): a missing global is null, a missing static property an Error. */
            void read() {
                if (variable) {
                    variableValue(fetch);
                } else if (site != null && key != null) {
                    staticPropNamed(VariableVariableNode.R);
                } else if (site != null) {
                    b.beginReadStaticProp(site);
                    emitClass();
                    b.endReadStaticProp();
                } else {
                    readGlobal(false);
                }
            }

            /** A global's value: a missing one warns unless quiet. */
            private void readGlobal(boolean quiet) {
                if (global != null) {
                    b.emitGlobalValue(global, quiet);
                } else {
                    b.beginGlobalsElementValue(quiet);
                    expression(key);
                    b.endGlobalsElementValue();
                }
            }

            /** The value as isset() reads it (readIfSet): null for a missing static property or global, quietly. */
            void readIfSet() {
                if (variable) {
                    variableValue(VariableVariableNode.IS);
                    return;
                }
                if (site == null) {
                    readGlobal(true);
                    return;
                }
                if (key != null) {
                    staticPropNamed(VariableVariableNode.IS);
                    return;
                }
                b.beginStaticPropIfSet(site);
                emitClass();
                b.endStaticPropIfSet();
            }

            /** isset($place), empty($place): as readIfSet, but `$$key` is looked up only (ISSET_ISEMPTY_VAR). */
            void tested() {
                if (variable) {
                    variableValue(VariableVariableNode.ISSET);
                } else {
                    readIfSet();
                }
            }

            /** A place read and written (`op=`, `++`): its reference, an undefined `$$key` made after the warning. */
            void refForUpdate() {
                if (variable) {
                    b.beginVariableRef(VariableVariableNode.RW);
                    expression(key);
                    b.endVariableRef();
                } else {
                    ref(true, true);
                }
            }

            /**
             * The place's reference (makeRef); a missing global made (null) when create says so, else null; a
             * `$$key` fetched for W, else for UNSET.
             */
            void ref(boolean create) {
                ref(create, false);
            }

            private void ref(boolean create, boolean update) {
                if (variable) {
                    b.beginVariableRef(create ? VariableVariableNode.W : VariableVariableNode.UNSET);
                    expression(key);
                    b.endVariableRef();
                } else if (site != null && key != null) {
                    staticPropNamed(VariableVariableNode.W);
                } else if (site != null) {
                    b.beginStaticPropRef(site);
                    emitClass();
                    b.endStaticPropRef();
                } else if (global != null) {
                    b.emitGlobalRef(global, create, update);
                } else {
                    b.beginGlobalsElementRef(create, update);
                    expression(key);
                    b.endGlobalsElementRef();
                }
            }

            void unset() {
                if (variable) {
                    b.beginUnsetVariable();
                    expression(key);
                    b.endUnsetVariable();
                    return;
                }
                if (site != null && key != null) {
                    staticPropNamed(VariableVariableNode.UNSET);
                    return;
                }
                if (site != null) {
                    b.beginUnsetStaticProp(site);
                    emitClass();
                    b.endUnsetStaticProp();
                    return;
                }
                b.beginUnsetGlobal();
                name();
                b.endUnsetGlobal();
            }

            /** `place = &source` (bindRef): the source's reference, then the place bound to it. */
            void bind(Runnable source) {
                if (variable) {
                    b.beginBindVariable();
                    source.run();
                    expression(key);
                    b.endBindVariable();
                    return;
                }
                if (site != null && key != null) {
                    b.beginBindStaticPropNamed();
                    source.run();
                    emitClass();
                    propName(key);
                    b.endBindStaticPropNamed();
                    return;
                }
                if (site != null) {
                    b.beginBindStaticProp(site);
                    source.run();
                    emitClass();
                    b.endBindStaticProp();
                    return;
                }
                b.beginBindGlobal();
                source.run();
                name();
                b.endBindGlobal();
            }

            /** A global's name, as UnsetGlobal and BindGlobal take it. */
            private void name() {
                if (global != null) {
                    b.emitLoadConstant(made(() -> Str.of(global)));
                } else {
                    expression(key);
                }
            }
        }

        /** The place kept in a reference that node is, or null for any other node. */
        private RefPlace refPlace(Object value) {
            if (!(value instanceof Ast node)) {
                return null;
            }
            switch (node.kind) {
                case "AST_STATIC_PROP" -> {
                    // a computed name is the place's key, evaluated after the class
                    String name = node.get("prop") instanceof String constant ? Str.sourceKey(constant) : null;
                    boolean scopeInFrame = inClosure();
                    StaticPropSite site = (StaticPropSite) made(() -> new StaticPropSite(name,
                            owner == null ? null : Str.lower(owner.name()), scopeInFrame));
                    return new RefPlace(node.get("class"), site, null, name == null ? node.get("prop") : null);
                }
                case "AST_VAR" -> {
                    if (!(node.get("name") instanceof String name)) {
                        return new RefPlace(null, null, null, node.get("name"), true, node.flags);
                    }
                    if (reread != null && plainVariable(name) && !name.startsWith("\0")) {
                        // a file's variable: the global, or the including call's variable, by name
                        return new RefPlace(null, null, null, name, true, node.flags);
                    }
                    return PhpContext.SUPERGLOBALS.contains(name) ? new RefPlace(null, null, name, null) : null;
                }
                case "AST_DIM" -> {
                    if (!isGlobals(node.get("expr"))) {
                        return null;
                    }
                    Object dim = node.get("dim");
                    if (dim == null) {
                        // zend_delayed_compile_dim (a read's "Cannot use [] for reading" comes first)
                        throw compileError(node, "Cannot append to $GLOBALS");
                    }
                    String name = constantGlobalName(dim);
                    return name != null ? new RefPlace(null, null, name, null) : new RefPlace(null, null, null, dim);
                }
                default -> {
                    return null;
                }
            }
        }

        /** The global a constant string or int key of $GLOBALS names (GlobalsDimNode.toName), else null. */
        private String constantGlobalName(Object dim) {
            if (!constantShaped(dim)) {
                return null;
            }
            Object constant = constantValue(dim);
            return constant instanceof TruffleString || constant instanceof Long ? GlobalsDimNode.toName(constant)
                    : null;
        }

        /**
         * The base of an element chain that is no variable (zend_is_variable): a call's result, a constant, a literal
         * or `new` (not $this, whose ArrayAccess the tree has no place for); null for a variable, a property or a
         * static property.
         */
        private Object temporaryBase(Ast dim) {
            Object base = dim;
            while (base instanceof Ast ast && ast.is("AST_DIM")) {
                base = ast.get("expr");
            }
            if (base instanceof Ast ast && (ast.is("AST_VAR") || ast.is("AST_PROP") || ast.is("AST_NULLSAFE_PROP")
                    || ast.is("AST_STATIC_PROP"))) {
                return null;
            }
            return base;
        }

        /**
         * `f()[k1]...[kn]` as an argument (FETCH_DIM_FUNC_ARG on a temporary, SendTemporary): the base and the keys
         * evaluated, then the value read down the chain, or for a by-reference parameter the element's reference in
         * a separated copy of a call's array, through the reference a call returned (zend_separate_if_call_and_write);
         * php-src can't write to any other temporary ("Cannot use temporary expression in write context").
         */
        private void temporaryElementArgument(Ast ast, Object base, int index, StackValue callee, NamedSend named) {
            List<Object> dims = new ArrayList<>();
            for (Object link = ast; link != base; link = ((Ast) link).get("expr")) {
                dims.add(0, ((Ast) link).get("dim"));
            }
            boolean append = dims.get(dims.size() - 1) == null;
            boolean call = Translator.isCall(base) && !constants.compiledInPlace((Ast) base);
            b.beginSendTemporary(index, append, call);
            sendCallee(callee, named);
            if (call) {
                callForRef((Ast) base);
            } else {
                expression(base);
            }
            emitKeys(append ? dims.subList(0, dims.size() - 1) : dims);
            b.endSendTemporary();
        }

        // ---- variables and constants ----

        /** A plain variable's local; $this, superglobals and variable variables aren't covered. */
        private BytecodeLocal variable(Ast node, String what) {
            if (isThis(node)) {
                // zend_compile_assign's (and the other writes') compile error
                throw compileError(node, what.equals("unset of") ? "Cannot unset $this" : "Cannot re-assign $this");
            }
            if (!node.is("AST_VAR") || !(node.get("name") instanceof String name)) {
                throw new Unsupported(what + " a place other than a variable", node);
            }
            BytecodeLocal local = variables.get(name);
            if (local == null) {
                throw new Unsupported(what + " $" + name, node);
            }
            return local;
        }

        /** `$$name`, `${expr}`: a variable by a name that isn't a constant (no CV). */
        private boolean isVariableVariable(Object node) {
            return node instanceof Ast ast && ast.is("AST_VAR") && !(ast.get("name") instanceof String);
        }

        /** A plain variable (a CV): no $this, $GLOBALS or superglobal, no variable variable. */
        private boolean isVariable(Ast node) {
            return node.is("AST_VAR") && node.get("name") instanceof String name
                    && (reread == null && plainVariable(name) || name.startsWith("\0"));
        }

        /** A key read where it is used, as php-src reads a constant or a CV operand: a constant or a variable. */
        private boolean plainKey(Object dim) {
            return dim == null || constantShaped(dim)
                    || (dim instanceof Ast ast && ast.is("AST_VAR") && !isThis(ast) && ast.get("name") instanceof String);
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
            return constants.fold(value);
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

    /** A named argument's send: its site and its position among the call's arguments. */
    private record NamedSend(NamedSite site, int argument) {
    }

    /**
     * A statement list's goto labels: their numbers by name, their places, those emitted so far (a goto to one is
     * backward), those inside a statement of the list (an if or a block; their place is before it), and for a
     * list a goto jumps back in, the local holding the label to go on to and the branch round.
     */
    private record LabelScope(Map<String, Integer> indexes, BytecodeLabel[] labels, Set<String> emitted,
                              Set<String> nested, BytecodeLocal target, BytecodeLabel again) {
    }

    // php-ast flag values (Translator's)
    private static final int ARRAY_ELEM_REF = 1;
    private static final int EXEC_EVAL = 1;
    private static final int EXEC_INCLUDE_ONCE = 4;
    private static final int EXEC_REQUIRE = 8;
    private static final int EXEC_REQUIRE_ONCE = 16;
    private static final int PROMOTED = 7 | 128 | 1024 | 2048 | 4096;

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

    /** `$GLOBALS` itself (Translator.isGlobals). */
    private static boolean isGlobals(Object value) {
        return value instanceof Ast node && node.is("AST_VAR") && "GLOBALS".equals(node.get("name"));
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
            case "AST_CLOSURE":
                // the variables a closure's use list names are the enclosing function's (php-src's CVs)
                if (ast.get("uses") instanceof Ast uses) {
                    for (Object use : uses.children()) {
                        if (use instanceof Ast variable && variable.get("name") instanceof String name
                                && plainVariable(name)) {
                            names.add(name);
                        }
                    }
                }
                return;
            case "AST_ARROW_FUNC", "AST_FUNC_DECL", "AST_CLASS", "AST_METHOD":
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
