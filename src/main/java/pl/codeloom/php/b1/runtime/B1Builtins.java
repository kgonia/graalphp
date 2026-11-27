package pl.codeloom.php.b1.runtime;

/** Direct entry points for global builtins called by name. PHP forbids redeclaring a builtin, so a direct call
 * site can bind to its implementation statically. Each method keeps the builtin's observable behaviour: the common
 * case runs inline, every other case (diagnostics, coercions, errors) goes through the registered builtin. */
public final class B1Builtins {
    private B1Builtins() {}

    // The direct operands are borrowed from the caller's variables. Off the fast path a builtin can run PHP code
    // (__toString, a destructor through a coercion) which may drop those variables: the call counts its operands,
    // as the arguments of a call frame would be.
    private static Object slow(B1RequestContext ctx, String name, Object a) {
        B1Object.retain(a);
        Object result = null;
        Throwable pending = null;
        try { result = B1RequestContext.function(ctx, name).invoke1(ctx, null, a); }
        catch (Throwable ex) { pending = ex; }
        return settled(ctx, result, B1Thrown.released(ctx, a, pending));
    }
    private static Object slow(B1RequestContext ctx, String name, Object a, Object b) {
        B1Object.retain(a);
        B1Object.retain(b);
        Object result = null;
        Throwable pending = null;
        try { result = B1RequestContext.function(ctx, name).invoke2(ctx, null, a, b); }
        catch (Throwable ex) { pending = ex; }
        pending = B1Thrown.released(ctx, a, pending);
        return settled(ctx, result, B1Thrown.released(ctx, b, pending));
    }
    /** The call's result, or, when the call or an operand's destructor threw, the chained throw (the counted
     * result is released first). */
    private static Object settled(B1RequestContext ctx, Object result, Throwable pending) {
        if (pending == null) return result;
        B1Thrown.propagate(B1Thrown.released(ctx, result, pending));
        throw new AssertionError(pending);
    }

    public static boolean isString(Object a) { return a instanceof B1Bytes || a instanceof String; }
    public static boolean isObject(Object a) { return a instanceof B1Object || a instanceof B1Closure; }
    public static Object is_array(B1RequestContext ctx, Object a) { return a instanceof B1Array ? Boolean.TRUE : Boolean.FALSE; }
    public static Object is_string(B1RequestContext ctx, Object a) { return a instanceof B1Bytes || a instanceof String ? Boolean.TRUE : Boolean.FALSE; }
    public static Object is_int(B1RequestContext ctx, Object a) { return a instanceof Long ? Boolean.TRUE : Boolean.FALSE; }
    public static Object is_null(B1RequestContext ctx, Object a) { return a == null ? Boolean.TRUE : Boolean.FALSE; }
    public static Object is_object(B1RequestContext ctx, Object a) {
        return a instanceof B1Object || a instanceof B1Closure ? Boolean.TRUE : Boolean.FALSE;
    }
    public static Object count(B1RequestContext ctx, Object a) {
        if (a instanceof B1Array array) return (long)array.size();
        return slow(ctx, "count", a);
    }
    public static Object sizeof(B1RequestContext ctx, Object a) {
        if (a instanceof B1Array array) return (long)array.size();
        return slow(ctx, "sizeof", a);
    }
    public static Object strlen(B1RequestContext ctx, Object a) {
        if (a instanceof B1Bytes text) return (long)text.length();
        return slow(ctx, "strlen", a);
    }
    public static Object array_key_exists(B1RequestContext ctx, Object key, Object array) {
        if (array instanceof B1Array a && (key instanceof Long || key instanceof B1Bytes))
            return a.getOrMissing(ctx.arrayKey(key)) != B1Array.MISSING ? Boolean.TRUE : Boolean.FALSE;
        return slow(ctx, "array_key_exists", key, array);
    }
    public static Object trim(B1RequestContext ctx, Object a) {
        if (a instanceof B1Bytes text) return text.trim();
        return slow(ctx, "trim", a);
    }
    public static Object ucfirst(B1RequestContext ctx, Object a) {
        if (a instanceof B1Bytes text) return text.ucfirst();
        return slow(ctx, "ucfirst", a);
    }
    public static Object str_replace(B1RequestContext ctx, Object search, Object replace, Object subject) {
        if (search instanceof B1Bytes s && replace instanceof B1Bytes r && subject instanceof B1Bytes text && s.length() > 0)
            return text.replace(s.raw(), r.raw());
        B1Object.retain(search);
        B1Object.retain(replace);
        B1Object.retain(subject);
        Object result = null;
        Throwable pending = null;
        try { result = B1RequestContext.function(ctx, "str_replace").invoke(ctx, null, new Object[]{search, replace, subject}); }
        catch (Throwable ex) { pending = ex; }
        pending = B1Thrown.released(ctx, search, pending);
        pending = B1Thrown.released(ctx, replace, pending);
        return settled(ctx, result, B1Thrown.released(ctx, subject, pending));
    }
    /** current() and key() read the array by value; the result is a counted copy. */
    public static Object current(B1RequestContext ctx, Object a) {
        if (a instanceof B1Array array) return B1Object.retain(array.current());
        return slow(ctx, "current", a);
    }
    public static Object key(B1RequestContext ctx, Object a) {
        if (a instanceof B1Array array) return array.currentKey();
        return slow(ctx, "key", a);
    }
    /** next(), reset(), array_pop() and array_unshift() take the array by reference. */
    public static Object next(B1RequestContext ctx, Object place) {
        if (place instanceof B1Ref ref && ref.get() instanceof B1Array) return B1Object.retain(ref.writable(ctx).advance());
        return slow(ctx, "next", place);
    }
    public static Object reset(B1RequestContext ctx, Object place) {
        if (place instanceof B1Ref ref && ref.get() instanceof B1Array) return B1Object.retain(ref.writable(ctx).rewind());
        return slow(ctx, "reset", place);
    }
    public static Object array_pop(B1RequestContext ctx, Object place) {
        if (place instanceof B1Ref ref && ref.get() instanceof B1Array) return ref.writable(ctx).pop(ctx);
        return slow(ctx, "array_pop", place);
    }
    public static Object array_unshift(B1RequestContext ctx, Object place, Object value) {
        // The value is passed by value: counted before the array is separated, so array_unshift($a, $a) (or a
        // reference to $a) separates instead of prepending the array to itself.
        if (place instanceof B1Ref ref && ref.get() instanceof B1Array) {
            B1Object.retain(value);
            try { return ref.writable(ctx).unshift(ctx, value); }
            finally { B1Object.release(ctx, value); }
        }
        return slow(ctx, "array_unshift", place, value);
    }
    public static Object get_object_vars(B1RequestContext ctx, Object a) {
        if (a instanceof B1Object obj) return obj.publicProperties(ctx);
        return slow(ctx, "get_object_vars", a);
    }
}
