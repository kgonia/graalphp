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

    static boolean throwable(String type) { return switch (type) {
        case "exception", "error", "typeerror", "argumentcounterror", "divisionbyzeroerror", "valueerror", "parseerror", "compileerror" -> true;
        default -> false;
    }; }

    public static boolean matches(Throwable pending, String type) {
        return pending instanceof B1Thrown thrown && (thrown.exception.type.equals(type) || type.equals("throwable")
                || type.equals("error") && !thrown.exception.type.equals("exception")
                || type.equals("compileerror") && thrown.exception.type.equals("parseerror")
                || type.equals("typeerror") && thrown.exception.type.equals("argumentcounterror"));
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

    static void propagate(Throwable t) {
        if (t instanceof RuntimeException ex) throw ex;
        if (t instanceof Error ex) throw ex;
        if (t != null) throw new IllegalStateException(t);
    }
}
