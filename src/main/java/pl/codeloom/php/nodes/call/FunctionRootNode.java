package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.RootCallTarget;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ControlFlow;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LargeCode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.nodes.vars.FrameSlots;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpType;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.function.IntFunction;

/**
 * A user function's body. Binds parameters (with coercive scalar typing) and closure captures to frame slots,
 * runs the body, and on exit releases the arrays its locals held, so copy-on-write sees exact holder counts for
 * locals.
 */
public final class FunctionRootNode extends RootNode implements SourceLocation.Code, Backtrace.Callee, LargeCode {

    /** Whether this root is {@link LargeCode large}: 0 not counted yet, 1 no, 2 yes. */
    private byte large;

    @Override
    public boolean isLarge() {
        if (large == 0) {
            large = LargeCode.countLarge(this) ? (byte) 2 : (byte) 1;
        }
        return large == 2;
    }
    private final String name;
    private final String path;
    private final boolean internal;
    @CompilationFinal(dimensions = 1) private final PhpFunction.Param[] params;
    @CompilationFinal(dimensions = 1) private final int[] paramSlots;
    @Children private final ExprNode[] defaults;
    @CompilationFinal(dimensions = 1) private final int[] captureSlots;
    @CompilationFinal(dimensions = 1) private final boolean[] captureByRef;
    private final int localCount;
    /**
     * What each local has held at the function's end so far (releaseLocals): RELEASED_ARRAY, RELEASED_REF, or both.
     */
    @CompilationFinal(dimensions = 1) private final byte[] releasedSlots;
    private static final byte RELEASED_ARRAY = 1;
    private static final byte RELEASED_REF = 2;
    /** The type the returned value is checked against, or null. */
    private final PhpType returnType;
    @Child private StmtNode body;
    /** A generator function's body (run by the Generator), or null; its frame slot for the Generator's state. */
    @CompilationFinal private RootCallTarget generatorBody;
    @CompilationFinal private int generatorSlot;
    /**
     * `function &f()` (not a generator): every return gives a {@link PhpRef}, also the implicit one at the closing
     * brace (endLine), which raises php-src's notice as returning a value does.
     */
    @CompilationFinal private boolean returnsRef;
    private int endLine;

    private final SourceSection sourceSection;

    public FunctionRootNode(PhpLanguage language, FrameDescriptor frame, String name, PhpFunction.Param[] params,
                            int[] paramSlots, ExprNode[] defaults, int[] captureSlots, boolean[] captureByRef,
                            PhpType returnType, StmtNode body, String path, boolean internal,
                            SourceSection sourceSection) {
        super(language, frame);
        this.sourceSection = sourceSection;
        this.internal = internal;
        this.name = name;
        this.path = path;
        this.params = params;
        this.paramSlots = paramSlots;
        this.defaults = defaults;
        this.captureSlots = captureSlots;
        this.captureByRef = captureByRef;
        this.localCount = frame.getNumberOfSlots();
        this.releasedSlots = new byte[localCount];
        this.returnType = returnType;
        this.body = body;
    }

    /** Makes this a function returning by reference; endLine is the line of its closing brace. */
    public void setReturnsRef(int endLine) {
        this.returnsRef = true;
        this.endLine = endLine;
    }

    /** Makes this a generator function: a call binds the parameters and returns a Generator running body. */
    public void setGeneratorBody(RootCallTarget body, int slot) {
        this.generatorBody = body;
        this.generatorSlot = slot;
    }

    /** A generator function's body, or null. */
    public RootCallTarget getGeneratorBody() {
        return generatorBody;
    }

    /**
     * Binds the arguments, runs the body and releases the locals however the call ends (a return, the end of the
     * body, an exception, also one thrown while binding). A generator function's frame outlives the call: the
     * Generator runs the body on it later, so its locals aren't released here.
     */
    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        if (generatorBody != null) {
            bindArguments(frame, arguments);
            PhpGenerator generator = new PhpGenerator(frame.materialize(), generatorBody);
            frame.setObject(generatorSlot, generator);
            return generator.newObject(PhpContext.get(this));
        }
        Object result = null;
        // the locals are released after the try, not in a finally: javac copies a finally block onto the normal
        // and the exceptional exit, and compiled code got two copies of releaseLocals
        RuntimeException runtimeException = null;
        Error error = null;
        try {
            bindArguments(frame, arguments);
            body.execute(frame);
            if (returnsRef) {
                result = implicitReturnRef();
            }
        } catch (ControlFlow.Return ret) {
            result = ret.value;
        } catch (RuntimeException e) {
            runtimeException = e;
        } catch (Error e) {
            error = e;
        }
        releaseLocals(frame);
        if (runtimeException != null) {
            throw runtimeException;
        }
        if (error != null) {
            throw error;
        }
        if (returnType == null) {
            return result == null ? PhpNull.NULL : result;
        }
        if (result == null) {
            throw noneReturned(name, returnType, calledClass(arguments));
        }
        if (returnsRef) {
            PhpRef ref = (PhpRef) result;
            ref.set(checkReturn(name, returnType, ref.get(), calledClass(arguments), path, null));
            return ref;
        }
        return checkReturn(name, returnType, result, calledClass(arguments), path, null);
    }

    static PhpClass calledClass(Object[] arguments) {
        return arguments[PhpFunction.ARG_STATIC] instanceof PhpClass phpClass ? phpClass : null;
    }

    /** ZEND_VERIFY_RETURN_TYPE for the value function returns, in the mode of its file (path). */
    public static Object checkReturn(String function, PhpType type, Object value, PhpClass calledClass,
                                     String path, Node site) {
        Object accepted = type.fast(value);
        return accepted != null ? accepted : returnSlow(function, type, value, calledClass, path, site);
    }

    @TruffleBoundary
    public static Object returnSlow(String function, PhpType type, Object value, PhpClass calledClass,
                                    String path, Node site) {
        Object accepted = type.slow(value, PhpContext.get(null).getLanguage().isStrict(path), calledClass, site);
        if (accepted != null) {
            return accepted;
        }
        throw new PhpError("TypeError", returnName(function) + "(): Return value must be of type "
                + type.text(calledClass) + ", " + PhpType.valueName(value) + " returned");
    }

    /** zend_verify_return_error's "class::function": an anonymous class's name up to its NUL. */
    private static String returnName(String function) {
        int nul = function.indexOf('\0');
        return nul < 0 ? function : function.substring(0, nul) + function.substring(function.lastIndexOf("::"));
    }

    /** The end of a function with a return type: a never-returning one must not get there either. */
    @TruffleBoundary
    public static PhpError noneReturned(String function, PhpType type, PhpClass calledClass) {
        if (type.has(PhpType.NEVER)) {
            return new PhpError("TypeError", returnName(function) + "(): never-returning "
                    + (function.contains("::") ? "method" : "function") + " must not implicitly return");
        }
        return new PhpError("TypeError", returnName(function) + "(): Return value must be of type "
                + type.text(calledClass) + ", none returned");
    }

    /**
     * zend_verify_recv_arg_type for what fast checks leave: in the mode of the calling code (a built-in's
     * callback in coercive mode), else the TypeError naming the call's place in user code.
     */
    @TruffleBoundary
    public static Object argumentSlow(String function, PhpFunction.Param param, int index, Object value,
                                      PhpClass calledClass, Node site) {
        SourceLocation caller = null;
        boolean strict = false;
        PhpLanguage language = PhpContext.get(null).getLanguage();
        if (!language.noStrictFiles().isValid()) {
            caller = Backtrace.userCaller();
            strict = caller != null && language.isStrict(caller.file());
        }
        Object accepted = param.declared().slow(value, strict, calledClass, site);
        if (accepted != null) {
            return accepted;
        }
        if (caller == null) {
            caller = Backtrace.userCaller();
        }
        // a variadic parameter's arguments go unnamed (get_function_arg_name)
        // the active function's name as %s writes it: an anonymous class's method up to its NUL
        throw new PhpError("TypeError", function.split("\0", 2)[0] + "(): Argument #" + (index + 1)
                + (param.variadic() ? "" : " ($" + param.name() + ")") + " must be of type "
                + param.declared().text(calledClass) + ", " + PhpType.valueName(value)
                + " given" + (caller == null ? "" : ", called in " + caller.file() + " on line " + caller.line()));
    }

    /** A typed parameter's value: the reference's content for a by-reference one, converted in it. */
    public static Object checkArgument(String function, PhpFunction.Param param, int index, Object value,
                                       PhpClass calledClass, Node site) {
        if (value instanceof PhpRef ref && !param.byRef()) {
            value = ref.get();
        } else if (value instanceof PhpRef ref) {
            Object content = ref.get();
            Object accepted = param.declared().fast(content);
            if (accepted != content) {
                ref.set(accepted != null ? accepted : argumentSlow(function, param, index, content, calledClass, site));
            }
            return ref;
        }
        Object accepted = param.declared().fast(value);
        return accepted != null ? accepted : argumentSlow(function, param, index, value, calledClass, site);
    }

    /** The end of a function returning by reference: php-src's final ZEND_RETURN_BY_REF of null, with its notice. */
    @TruffleBoundary
    private PhpRef implicitReturnRef() {
        CallNode.notice(this, CallNode.RETURNED_VALUE, new SourceLocation(path, endLine));
        return PhpRef.unbound(PhpNull.NULL);
    }

    private void bindArguments(VirtualFrame frame, Object[] arguments) {
        bindParameters(frame, arguments);
        if (captureSlots.length > 0) {
            bindCaptures(frame, (PhpClosure) arguments[PhpFunction.ARG_CLOSURE]);
        }
    }

    @ExplodeLoop
    private void bindParameters(VirtualFrame frame, Object[] arguments) {
        int given = arguments.length - PhpFunction.ARG_FIRST;
        for (int i = 0; i < params.length; i++) {
            PhpFunction.Param param = params[i];
            int slot = paramSlots[i];
            if (param.variadic()) {
                FrameSlots.setObject(frame, slot, collectVariadic(arguments, i, given, param.byRef()));
                continue;
            }
            Object value;
            if (i < given) {
                value = arguments[PhpFunction.ARG_FIRST + i];
            } else if (defaults[i] != null) {
                value = defaults[i].execute(frame);
            } else {
                throw tooFewArguments(given);
            }
            if (param.byRef()) {
                PhpRef ref = value instanceof PhpRef passed ? passed : new PhpRef(value);
                if (param.declared() != null) {
                    checkArgument(name, param, i, ref, calledClass(arguments), null);
                }
                ref.bind();
                FrameSlots.setObject(frame, slot, ref);
                continue;
            }
            value = PhpRef.deref(value);
            if (param.declared() != null) {
                value = checkArgument(name, param, i, value, calledClass(arguments), null);
            }
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            FrameSlots.set(frame, slot, value);
        }
    }

    private PhpError tooFewArguments(int given) {
        return tooFewArguments(name, params, given);
    }

    /**
     * zend_missing_arg_error for a call of the function name that passed given arguments: where user code made
     * the call, and "exactly" when every parameter is required. The required ones are those up to the last
     * parameter without a default (required_num_args), the variadic one isn't counted (num_args).
     */
    @TruffleBoundary
    public static PhpError tooFewArguments(String name, PhpFunction.Param[] params, int given) {
        int required = 0;
        int count = 0;
        for (int i = 0; i < params.length; i++) {
            if (params[i].variadic()) {
                continue;
            }
            count++;
            if (params[i].defaultValue() == null) {
                required = i + 1;
            }
        }
        SourceLocation caller = pl.codeloom.php.runtime.Backtrace.userCaller();
        String where = caller == null ? "" : " in " + caller.file() + " on line " + caller.line();
        return new PhpError("ArgumentCountError", "Too few arguments to function " + name + "(), " + given
                + " passed" + where + " and " + (required == count ? "exactly" : "at least") + " " + required
                + " expected");
    }

    /** Not a boundary: inlined into the caller, the array can be escape-analyzed away (apply_filters' ...$args). */
    public static PhpArray collectVariadic(Object[] arguments, int from, int given, boolean byRef) {
        PhpArray rest = PhpArray.ofCapacity(given - from);
        for (int i = from; i < given; i++) {
            Object value = arguments[PhpFunction.ARG_FIRST + i];
            if (byRef) {
                // &...$rest: each element is the argument's reference
                rest.putRef((long) (i - from), value instanceof PhpRef ref ? ref : new PhpRef(value));
            } else {
                rest.append(PhpRef.deref(value));
            }
        }
        rest.addHolder();
        return rest;
    }

    @ExplodeLoop
    private void bindCaptures(VirtualFrame frame, PhpClosure closure) {
        for (int i = 0; i < captureSlots.length; i++) {
            Object captured = closure.getCaptured(i);
            if (captured == null) {
                // an arrow function's capture of an unset variable (ClosureNode): left unset
                continue;
            }
            if (!captureByRef[i] && captured instanceof PhpArray array) {
                array.addHolder();
            } else if (captured instanceof PhpRef ref) {
                ref.bind();
            }
            FrameSlots.setObject(frame, captureSlots[i], captured);
        }
    }

    /**
     * The end of the call: an array a local holds loses it as a holder, a reference is unbound. Compiled code
     * releases only what each slot has held at a function's end so far, an array, a reference or both; anything
     * else deoptimizes once and is added. Inlined for every local, the release was one of the largest parts of big
     * compiled functions.
     */
    @ExplodeLoop
    private void releaseLocals(VirtualFrame frame) {
        for (int slot = 0; slot < localCount; slot++) {
            if (frame.isObject(slot)) {
                Object value = frame.getObject(slot);
                if (value instanceof PhpArray array) {
                    released(slot, RELEASED_ARRAY);
                    array.removeHolder();
                } else if (value instanceof PhpRef ref) {
                    released(slot, RELEASED_REF);
                    ref.unbind();
                }
            }
        }
    }

    private void released(int slot, byte kind) {
        if ((releasedSlots[slot] & kind) == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            releasedSlots[slot] |= kind;
        }
    }

    /** The class the function is declared in (a method's, a trait's user), or null; and its lowercase name. */
    private String className;
    private String scopeName;

    public void setScope(String className) {
        this.className = className;
        this.scopeName = className == null ? null : Str.lower(className);
    }

    /** What self:: means in this function's code: callables named "self::m" or "parent::m" resolve with it. */
    public String getScopeName() {
        return scopeName;
    }

    @Override
    public boolean describe(Frame frame, PhpArray entry, int options, Backtrace.CallSite site) {
        describeCall(frame, entry, options);
        return true;
    }

    /**
     * A trace entry's function, class and arguments for a call on frame (also the generator body's, on the
     * generator's frame): a method by its name in its class, a closure in the class it is bound to; the
     * arguments passed, a parameter's as its variable is now (php-src's debug_backtrace_get_args).
     */
    public void describeCall(Frame frame, PhpArray entry, int options) {
        describeCall(name, className, params, frame.getArguments(), i -> frame.getValue(paramSlots[i]), entry,
                options);
    }

    /**
     * describeCall for any function's frame: its display name, the class it is declared in (or null), its
     * parameters, the frame's arguments and the current value of the variable of parameter i (null when unset).
     */
    public static void describeCall(String name, String className, PhpFunction.Param[] params, Object[] arguments,
                                    IntFunction<Object> parameter, PhpArray entry, int options) {
        String function = className != null && name.startsWith(className + "::")
                ? name.substring(className.length() + 2) : name;
        String scope = className;
        PhpObject self = null;
        PhpArray args = new PhpArray();
        if (arguments[PhpFunction.ARG_CLOSURE] instanceof PhpClosure closure) {
            scope = closure.getScope() == null ? null : closure.getScope().getName();
        }
        if (arguments[PhpFunction.ARG_THIS] instanceof PhpObject object) {
            self = object;
            if (scope == null) {
                scope = object.getPhpClass().getName();
            }
        }
        if ((options & Backtrace.IGNORE_ARGS) == 0) {
            int declared = params.length > 0 && params[params.length - 1].variadic() ? params.length - 1
                    : params.length;
            for (int i = 0; i < arguments.length - PhpFunction.ARG_FIRST; i++) {
                Object value = i < declared ? parameter.apply(i)
                        : arguments[PhpFunction.ARG_FIRST + i];
                value = PhpRef.deref(value);
                args.append(value == null ? PhpNull.NULL : value);
            }
        }
        Backtrace.describeCall(entry, function, scope, self, options, args);
    }

    /** Splitting: a function called with different types from different sites gets a copy per caller profile. */
    @Override
    public boolean isCloningAllowed() {
        return true;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    /** A prelude function or method: php-src's internal code, which tools and traces leave out. */
    @Override
    public boolean isInternal() {
        return internal;
    }

    @Override
    public String getPath() {
        return path;
    }
}
