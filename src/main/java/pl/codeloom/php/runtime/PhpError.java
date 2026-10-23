package pl.codeloom.php.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/**
 * A PHP Throwable the runtime itself raises (TypeError, ValueError, JsonException...): a catch block sees an
 * object of that class, made when it catches. Uncaught, it ends the script as a fatal error.
 */
public final class PhpError extends AbstractTruffleException {
    private static final long serialVersionUID = 1L;

    /** The class the catch block sees, as declared: "Error", "TypeError", "JsonException"... */
    private final String phpClass;
    /** The message without the class; getMessage() is what an uncaught error prints, "TypeError: ...". */
    private final String phpMessage;
    private final long code;

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
}
