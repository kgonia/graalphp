package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
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
        super(phpClass.equals("Error") ? message : phpClass + ": " + message);
        this.phpClass = phpClass;
        this.phpMessage = message;
        this.code = code;
        this.trace = Backtrace.forThrowable();
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

    /** The object a catch block sees, made once: of the error's class, where the error surfaced. */
    @Override
    @TruffleBoundary
    public PhpObject toThrowable(PhpContext context) {
        if (throwable == null) {
            PhpObject object = context.getClass(phpClass)
                    .instantiate(origin == null ? SourceLocation.UNKNOWN : SourceLocation.of(origin), trace);
            object.setProperty("message", Str.of(phpMessage));
            if (code != 0) {
                object.setProperty("code", code);
            }
            throwable = object;
        }
        return throwable;
    }
}
