package pl.codeloom.php.b1.runtime;

/** Owns the thrown zval until CATCH transfers it, or a newer exception links it as previous. */
public final class B1Thrown extends RuntimeException {
    public final B1Object exception;

    public B1Thrown(Object value) {
        super(null, null, true, false);
        if (!(value instanceof B1Object obj) || !throwable(obj.type))
            throw new IllegalStateException("Can only throw an Exception in this PHP subset");
        exception = (B1Object) B1Object.retain(obj);
    }

    public static B1Thrown from(B1RequestContext ctx,Object value) {
        if (value instanceof B1Object obj && throwable(obj.type)) return new B1Thrown(obj);
        return ctx.error("Error","Can only throw objects that implement Throwable");
    }

    static boolean throwable(String type) { return switch (type) {
        case "exception", "closedgeneratorexception", "error", "typeerror", "argumentcounterror", "arithmeticerror", "divisionbyzeroerror", "valueerror", "fibererror", "parseerror", "compileerror" -> true;
        default -> false;
    }; }

    public static boolean matches(Throwable pending, String type) {
        return pending instanceof B1Thrown thrown && subtype(thrown.exception.type,type);
    }
    /** The built-in Throwable hierarchy: whether instances of the folded class {@code type} are {@code target}s. */
    static boolean subtype(String type, String target) {
        return type.equals(target) || target.equals("throwable")
                || target.equals("exception") && type.equals("closedgeneratorexception")
                || target.equals("error") && !type.equals("exception") && !type.equals("closedgeneratorexception")
                || target.equals("compileerror") && type.equals("parseerror")
                || target.equals("arithmeticerror") && type.equals("divisionbyzeroerror")
                || target.equals("typeerror") && type.equals("argumentcounterror");
    }

    public static Throwable chain(B1RequestContext ctx, Throwable next, Throwable previous) {
        if (previous == null || previous == next) return next;
        if (next instanceof B1Thrown newer && previous instanceof B1Thrown older) {
            var tail = pl.codeloom.php.ir.PhpExceptionChain.appendPoint(newer.exception, older.exception,
                    obj -> (B1Object) obj.read("previous"));
            if (tail != null) tail.write(ctx, "previous", older.exception);
            B1Object.release(ctx, older.exception);
        } else {
            next.addSuppressed(previous);
        }
        return next;
    }

    /** Releases a held value after an operation, chaining a throw from a destructor onto the operation's own
     * pending throw (PHP sets the earlier exception as the newer one's previous). Returns the throw to propagate. */
    static Throwable released(B1RequestContext ctx, Object value, Throwable pending) {
        try { B1Object.release(ctx, value); }
        catch (Throwable ex) { return chain(ctx, ex, pending); }
        return pending;
    }

    static void propagate(Throwable t) {
        if (t instanceof RuntimeException ex) throw ex;
        if (t instanceof Error ex) throw ex;
        if (t != null) throw new IllegalStateException(t);
    }
}
