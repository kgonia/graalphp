package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;

/**
 * Calls a method once it is resolved: directly while the site sees one method, whatever the classes it was found
 * in, else indirectly. Methods are language-level (the same PhpFunction in every context, ClassTemplate), so the
 * entries serve every context. $this and the called class are the call's own: the receiver (or the caller's $this
 * for a static-call syntax) and the late-static-binding class, so nothing of the class the method was found in is
 * kept. A static method gets no $this; __call and __callStatic get the $this the caller gives.
 */
@ReportPolymorphism
public abstract class MethodDispatchNode extends Node {
    /** For __call and __callStatic: the method name as called, passed with the arguments as an array. */
    protected final TruffleString magicName;

    protected MethodDispatchNode(TruffleString magicName) {
        this.magicName = magicName;
    }

    /** A dispatch with the call's arguments as they are. */
    public static MethodDispatchNode create() {
        return MethodDispatchNodeGen.create(null);
    }

    /** A dispatch to __call or __callStatic standing in for a missing method called as calledName. */
    public static MethodDispatchNode createMagic(TruffleString calledName) {
        return MethodDispatchNodeGen.create(calledName);
    }

    public abstract Object execute(VirtualFrame frame, PhpFunction method, Object self, PhpClass staticClass,
                                   ArgumentsNode args);

    @Specialization(guards = "method == cachedMethod", limit = "4")
    protected Object direct(VirtualFrame frame, PhpFunction method, Object self, PhpClass staticClass,
                            ArgumentsNode args,
                            @Cached("method") PhpFunction cachedMethod,
                            @Cached("create(cachedMethod.getCallTarget())") DirectCallNode call) {
        return call.call(arguments(frame, cachedMethod, self, staticClass, args));
    }

    @Specialization(replaces = "direct")
    protected Object indirect(VirtualFrame frame, PhpFunction method, Object self, PhpClass staticClass,
                              ArgumentsNode args,
                              @Cached IndirectCallNode call) {
        return call.call(method.getCallTarget(), arguments(frame, method, self, staticClass, args));
    }

    private Object[] arguments(VirtualFrame frame, PhpFunction method, Object self, PhpClass staticClass,
                               ArgumentsNode args) {
        if (magicName != null) {
            return ArgumentsNode.frameArguments(self, staticClass, null,
                    Callables.magicValues(magicName, args.values(frame)));
        }
        return args.build(frame, method, method.isStatic() ? null : self, staticClass, null);
    }
}
