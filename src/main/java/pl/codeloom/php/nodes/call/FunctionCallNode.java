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
import pl.codeloom.php.runtime.PhpRef;

/**
 * `name(args)`. The function is looked up in the calling context by the name's language-level id (an array
 * load), then called through an inline cache on its identity. Declarations are language-level PhpFunction
 * objects, so contexts running the same code find the same function and the call stays direct, which lets Graal
 * inline the callee. While the language serves one context, the function found first is kept as a compile-time
 * constant (functions can't be redeclared), and the lookup leaves compiled code.
 */
public final class FunctionCallNode extends ExprNode {
    @Child private ArgumentsNode args;
    @Child private Lookup lookup;
    @Child private Dispatch dispatch = FunctionCallNodeFactory.DispatchNodeGen.create();
    /** A call php-src compiles to an opcode instead (strlen, count...): stack traces list no frame for it. */
    private final boolean frameless;

    /** id is the lowercase name's {@link pl.codeloom.php.PhpLanguage#functionId}. */
    public FunctionCallNode(String displayName, int id, int fallbackId, ArgumentsNode args, boolean frameless) {
        this.args = args;
        this.lookup = FunctionCallNodeFactory.LookupNodeGen.create(displayName, id, fallbackId);
        this.frameless = frameless;
    }

    @Override
    public boolean isFrameless() {
        return frameless;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return PhpRef.deref(dispatch.execute(frame, lookup.execute(), args));
    }

    /** Finds the function in the calling context; with one context, once (functions can't be redeclared). */
    abstract static class Lookup extends Node {
        private final String displayName;
        private final int id;
        /** An unqualified name in a namespace falls back to the global function: its id, else -1. */
        private final int fallbackId;

        Lookup(String displayName, int id, int fallbackId) {
            this.displayName = displayName;
            this.id = id;
            this.fallbackId = fallbackId;
        }

        abstract PhpFunction execute();

        @Specialization(assumptions = "singleContext()")
        protected PhpFunction single(@Cached("find()") PhpFunction function) {
            return function;
        }

        @Specialization(replaces = "single")
        protected PhpFunction perContext() {
            return find();
        }

        protected PhpFunction find() {
            PhpContext context = PhpContext.get(this);
            PhpFunction function = context.functionAt(id);
            if (function == null && fallbackId >= 0) {
                function = context.functionAt(fallbackId);
            }
            if (function == null) {
                CompilerDirectives.transferToInterpreter();
                throw undefined();
            }
            return function;
        }

        @TruffleBoundary
        private PhpError undefined() {
            return new PhpError("Call to undefined function " + displayName + "()");
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }
    }

    /** Calls a function: directly while the site sees one function (nearly always), else indirectly. */
    @ReportPolymorphism
    public abstract static class Dispatch extends Node {
        public abstract Object execute(VirtualFrame frame, PhpFunction function, ArgumentsNode args);

        @Specialization(guards = "function == cachedFunction", limit = "1")
        protected Object direct(VirtualFrame frame, PhpFunction function, ArgumentsNode args,
                                @Cached("function") PhpFunction cachedFunction,
                                @Cached("create(cachedFunction.getCallTarget())") DirectCallNode call) {
            return call.call(args.build(frame, cachedFunction, null, null, null));
        }

        @Specialization(replaces = "direct")
        protected Object indirect(VirtualFrame frame, PhpFunction function, ArgumentsNode args,
                                  @Cached IndirectCallNode call) {
            return call.call(function.getCallTarget(), args.build(frame, function, null, null, null));
        }
    }
}
