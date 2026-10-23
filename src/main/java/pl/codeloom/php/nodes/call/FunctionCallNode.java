package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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
    private final String displayName;
    private final int id;
    /** An unqualified name in a namespace falls back to the global function: its id, else -1. */
    private final int fallbackId;
    @Child private ArgumentsNode args;
    @Child private Dispatch dispatch = FunctionCallNodeFactory.DispatchNodeGen.create();
    @CompilationFinal private PhpFunction singleContextFunction;

    /** id is the lowercase name's {@link pl.codeloom.php.PhpLanguage#functionId}. */
    public FunctionCallNode(String displayName, int id, int fallbackId, ArgumentsNode args) {
        this.displayName = displayName;
        this.id = id;
        this.fallbackId = fallbackId;
        this.args = args;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpFunction function = singleContextFunction;
        if (function == null || !PhpLanguage.get(this).singleContext().isValid()) {
            function = lookup();
        }
        return PhpRef.deref(dispatch.execute(frame, function, args));
    }

    private PhpFunction lookup() {
        PhpContext context = PhpContext.get(this);
        PhpFunction function = context.functionAt(id);
        if (function == null && fallbackId >= 0) {
            function = context.functionAt(fallbackId);
        }
        if (function == null) {
            CompilerDirectives.transferToInterpreter();
            throw undefined();
        }
        if (singleContextFunction == null && PhpLanguage.get(this).singleContext().isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            singleContextFunction = function;
        }
        return function;
    }

    @TruffleBoundary
    private PhpError undefined() {
        return new PhpError("Call to undefined function " + displayName + "()");
    }

    /** Calls a function: directly while the site sees one function (nearly always), else indirectly. */
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
