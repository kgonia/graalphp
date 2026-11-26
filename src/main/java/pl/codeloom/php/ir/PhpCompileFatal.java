package pl.codeloom.php.ir;

/** A PHP compile-time fatal exits the script and bypasses PHP catch blocks. */
public final class PhpCompileFatal extends RuntimeException {
    public final IrSourceSpan location;
    public final String trace;
    public PhpCompileFatal(String message, IrSourceSpan location, String trace) {
        super(message,null,false,false);
        this.location = location;
        this.trace = trace;
    }
    public String diagnostic() {
        return "\nFatal error: " + getMessage() + " in " + location.file() + " on line " + location.line()
                + "\nStack trace:\n" + trace + "\n";
    }
}
