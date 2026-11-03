package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;

/**
 * `name(args)`. The function is looked up in the calling context by the name's language-level id (an array
 * load), then called through an inline cache on its identity. Declarations are language-level PhpFunction
 * objects, so contexts running the same code find the same function and the call stays direct, which lets Graal
 * inline the callee. While the language serves one context, the function found first is kept as a compile-time
 * constant (functions can't be redeclared), and the lookup leaves compiled code.
 */
public final class FunctionCallNode extends CallNode {
    @Child private ArgumentsNode args;
    @Child private Lookup lookup;
    @Child private Dispatch dispatch;
    /** A call php-src compiles to an opcode instead (strlen, count...): stack traces list no frame for it. */
    private final boolean frameless;

    /**
     * id is the lowercase name's {@link pl.codeloom.php.PhpLanguage#functionId}; site, for a call with a fallback,
     * a number of its own ({@link PhpLanguage#nextFallbackSite}), else -1.
     */
    public FunctionCallNode(String displayName, int id, int fallbackId, int site, ArgumentsNode args,
                            boolean frameless) {
        this.args = args;
        this.lookup = FunctionCallNodeFactory.LookupNodeGen.create(displayName, id, fallbackId, site);
        this.dispatch = FunctionCallNodeFactory.DispatchNodeGen.create(displayName);
        this.frameless = frameless;
    }

    @Override
    public boolean isFrameless() {
        return frameless;
    }

    @Override
    public Object executeRef(VirtualFrame frame) {
        return dispatch.execute(frame, lookup.execute(), args);
    }

    /**
     * Finds the function in the calling context; with one context, once (functions can't be redeclared). With
     * several, a built-in found by a name with no namespace fallback is kept too: every context declares the
     * language's built-ins (the same PhpFunction objects) before it runs any code, and none can be redeclared, so
     * it is the function this name finds in any context. A per-context lookup costs about 300 bytes of machine
     * code at each call site.
     */
    abstract static class Lookup extends Node {
        private final String displayName;
        private final int id;
        /** An unqualified name in a namespace falls back to the global function: its id, else -1. */
        private final int fallbackId;
        private final int site;

        Lookup(String displayName, int id, int fallbackId, int site) {
            this.displayName = displayName;
            this.id = id;
            this.fallbackId = fallbackId;
            this.site = site;
        }

        abstract PhpFunction execute();

        @Specialization(assumptions = "singleContext()")
        protected PhpFunction single(@Cached("find()") PhpFunction function) {
            return function;
        }

        @Specialization(guards = "builtin != null", replaces = "single")
        protected PhpFunction builtin(@Cached("builtinOrNull()") PhpFunction builtin) {
            return builtin;
        }

        /** The function, or null when the context doesn't declare it: the call's dispatch fails on it. */
        @Specialization(replaces = {"single", "builtin"})
        protected PhpFunction perContext() {
            return lookUp(PhpContext.get(this), id, fallbackId, site);
        }

        protected PhpFunction find() {
            return FunctionCallNode.find(PhpContext.get(this), displayName, id, fallbackId, site);
        }

        protected PhpFunction builtinOrNull() {
            return FunctionCallNode.builtinOrNull(PhpContext.get(this), displayName, id, fallbackId);
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }
    }

    /**
     * The function a call by name calls in context: by the name's id, else (an unqualified name in a namespace)
     * the global function's; an error if neither is declared.
     */
    public static PhpFunction find(PhpContext context, String displayName, int id, int fallbackId, int site) {
        PhpFunction function = lookUp(context, id, fallbackId, site);
        if (function == null) {
            CompilerDirectives.transferToInterpreter();
            throw undefined(displayName);
        }
        return function;
    }

    /**
     * The function, or null. An unqualified name in a namespace (fallbackId) finds the namespace's function, else the
     * global one, and its site keeps the one it found first for the request (INIT_NS_FCALL_BY_NAME's cache slot),
     * though the namespace declares one later.
     */
    public static PhpFunction lookUp(PhpContext context, int id, int fallbackId, int site) {
        if (fallbackId < 0) {
            return context.functionAt(id);
        }
        PhpFunction function = context.fallbackFunction(site);
        if (function == null) {
            function = context.functionAt(id);
            if (function == null) {
                function = context.functionAt(fallbackId);
            }
            if (function != null) {
                context.keepFallbackFunction(site, function);
            }
        }
        return function;
    }

    /**
     * The built-in a call by name calls in every context, or null: a user function, or a name in a namespace,
     * whose function a context may yet declare (see Lookup).
     */
    public static PhpFunction builtinOrNull(PhpContext context, String displayName, int id, int fallbackId) {
        if (fallbackId >= 0) {
            return null;
        }
        PhpFunction function = find(context, displayName, id, fallbackId, -1);
        return function.getBuiltin() != null ? function : null;
    }

    /**
     * A built-in compiled in place (Translator.call) for an unqualified name in a namespace, which php-src resolves
     * at run time: run while the call finds the global function ({@link #lookUp}), else the call, of the
     * namespace's function.
     */
    public static final class FallbackNode extends ExprNode {
        @Child private ExprNode builtin;
        @Child private ExprNode call;
        private final int id;
        private final int fallbackId;
        private final int site;

        public FallbackNode(ExprNode builtin, ExprNode call, int id, int fallbackId, int site) {
            this.builtin = builtin;
            this.call = call;
            this.id = id;
            this.fallbackId = fallbackId;
            this.site = site;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            PhpContext context = PhpContext.get(this);
            return lookUp(context, id, fallbackId, site) == context.functionAt(fallbackId) ? builtin.execute(frame)
                    : call.execute(frame);
        }
    }

    @TruffleBoundary
    private static PhpError undefined(String displayName) {
        return new PhpError("Call to undefined function " + displayName + "()");
    }

    /**
     * Calls a function: directly while the site sees one function (nearly always), else indirectly. A function
     * the context doesn't declare comes as null (Lookup.perContext): it fails the direct call's identity test,
     * and the error is raised here, out of compiled code, with no test of its own at each call site.
     */
    @ReportPolymorphism
    public abstract static class Dispatch extends Node {
        private final String displayName;

        Dispatch(String displayName) {
            this.displayName = displayName;
        }

        public abstract Object execute(VirtualFrame frame, PhpFunction function, ArgumentsNode args);

        @Specialization(guards = "function == cachedFunction", limit = "1")
        protected Object direct(VirtualFrame frame, PhpFunction function, ArgumentsNode args,
                                @Cached("declared(function)") PhpFunction cachedFunction,
                                @Cached("create(cachedFunction.getCallTarget())") DirectCallNode call) {
            return call.call(args.build(frame, cachedFunction, null, null, null));
        }

        @Specialization(replaces = "direct")
        protected Object indirect(VirtualFrame frame, PhpFunction function, ArgumentsNode args,
                                  @Cached IndirectCallNode call) {
            PhpFunction callee = declared(function);
            return call.call(callee.getCallTarget(), args.build(frame, callee, null, null, null));
        }

        protected PhpFunction declared(PhpFunction function) {
            if (function == null) {
                CompilerDirectives.transferToInterpreter();
                throw undefined(displayName);
            }
            return function;
        }
    }
}
