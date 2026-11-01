package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpContext;

/**
 * A PHP Throwable the runtime itself raises (TypeError, ValueError, JsonException...): a catch block sees an
 * object of that class, made when it is first caught. Uncaught, it ends the script as a fatal error. Its trace is
 * taken when it is raised, as zend_throw_error's object takes it.
 */
public final class PhpError extends PhpThrowable {
    private static final long serialVersionUID = 1L;

    /** The class the catch block sees, as declared: "Error", "TypeError", "JsonException"... */
    private final String phpClass;
    /** The message without the class; getMessage() is what an uncaught error prints, "TypeError: ...". */
    private final String phpMessage;
    private final long code;
    /** The calls leading to where the error was raised. */
    private final transient PhpArray trace;
    /** The innermost statement the error left, recorded on the way out: the object's file and line. */
    private transient Node origin;
    /**
     * The statement of a function on bytecode the error left, when it left one before origin's: where it surfaced.
     * Only the first instruction in a statement counts (one in a prologue, binding parameters, doesn't).
     */
    private transient SourceLocation statement;
    /** The Throwable object, once a catch or the uncaught-error handler asked for it. */
    private transient PhpObject throwable;

    /** An Error. */
    public PhpError(String message) {
        this("Error", message, 0);
    }

    public PhpError(String phpClass, String message) {
        this(phpClass, message, 0);
    }

    public PhpError(String phpClass, String message, long code) {
        super(fullMessage(phpClass, message));
        this.phpClass = phpClass;
        this.phpMessage = message;
        this.code = code;
        this.trace = Backtrace.forThrowable();
    }

    @TruffleBoundary
    private static String fullMessage(String phpClass, String message) {
        return phpClass.equals("Error") ? message : phpClass + ": " + message;
    }

    public String getPhpClass() {
        return phpClass;
    }

    public String getPhpMessage() {
        return phpMessage;
    }

    public long getCode() {
        return code;
    }

    /** Records the statement of user code the error surfaced in, unless an inner one already did. */
    @TruffleBoundary
    public void surfacedAt(Node statement) {
        if (origin == null && SourceLocation.isUserCode(statement.getRootNode())) {
            origin = statement;
        }
    }

    /**
     * surfacedAt for a function on bytecode: the instruction at bytecodeIndex, if it is in a statement. Telling
     * builds the function's sources (once: they are kept), which the error's location needs anyway; frames further
     * out are then skipped.
     */
    @TruffleBoundary
    public void surfacedAt(BytecodeNode bytecode, int bytecodeIndex) {
        if (origin == null && statement == null
                && SourceLocation.isUserCode((RootNode) bytecode.getBytecodeRootNode())) {
            statement = SourceLocation.ofStatement(bytecode.getBytecodeLocation(bytecodeIndex));
        }
    }

    /** Where the error surfaced: the innermost statement it left, as surfacedAt recorded it. */
    private SourceLocation location() {
        if (statement != null) {
            return statement;
        }
        return origin == null ? SourceLocation.UNKNOWN : SourceLocation.of(origin);
    }

    /** The object a catch block sees, made once: of the error's class, where the error surfaced. */
    @Override
    @TruffleBoundary
    public PhpObject toThrowable(PhpContext context) {
        if (throwable == null) {
            PhpObject object = context.getClass(phpClass)
                    .instantiate(location(), trace);
            object.setProperty("message", Str.of(phpMessage));
            if (code != 0) {
                object.setProperty("code", code);
            }
            throwable = object;
        }
        return throwable;
    }
}
