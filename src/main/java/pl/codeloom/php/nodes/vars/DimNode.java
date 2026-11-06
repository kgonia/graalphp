package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.nodes.call.CallNode;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * `$container[key]` and `$container[]`: array elements, string offsets (read) and ArrayAccess objects.
 * A literal key is normalized once by the translator (constantKey); other keys are normalized per access. Element
 * reads and writes are {@link ElementNodes}: DSL nodes that dispatch on the container and the array's storage.
 */
public final class DimNode {
    @TruffleBoundary
    public static void letGo(PhpArray held) {
        held.removeHolder();
    }

    /**
     * `$o[key] = value` (key null for `$o[]`) where the container's arrayForWrite gave no array: an ArrayAccess
     * object's offsetSet(), called from site; anything else is an error.
     */
    public static void writeToObject(Node site, Object target, Object key, Object value) {
        if (target instanceof PhpObject object && object.isArrayAccess()) {
            object.callMethod(site, "offsetset", key, value);
            return;
        }
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Cannot use object of type " + Operators.zvalTypeName(target) + " as array");
    }

    /**
     * A write fetch of an ArrayAccess element (read_dimension(BP_VAR_W)): offsetGet's result, a reference's array
     * made writable, an object as it is; any other value with php-src's notice, as a temporary the write doesn't
     * reach.
     */
    @TruffleBoundary
    public static Object overloadedForWrite(Node site, PhpObject object, Object offset) {
        Object element = object.callMethodRef(site, "offsetget", offset);
        if (element instanceof PhpRef ref) {
            PhpArray array = Places.refArrayForWrite(ref);
            return array != null ? array : ref.get();
        }
        if (element instanceof PhpObject) {
            return element;
        }
        overloadedNotice(site, object);
        return element instanceof PhpArray array ? array.copy() : new PhpArray();
    }

    /**
     * `&$obj[key]` (key null for `[]`) of an ArrayAccess object: the reference `&offsetGet` returns, else a
     * temporary's with php-src's notice; an Error for a container that is none.
     */
    @TruffleBoundary
    public static PhpRef overloadedRef(Node site, Object container, Object offset) {
        if (!(container instanceof PhpObject object) || !object.isArrayAccess()) {
            throw new PhpError(container instanceof PhpObject
                    ? "Cannot use object of type " + Operators.zvalTypeName(container) + " as array"
                    : "Indirect modification of overloaded element");
        }
        Object element = object.callMethodRef(site, "offsetget", offset);
        if (element instanceof PhpRef ref) {
            return ref;
        }
        if (!(element instanceof PhpObject)) {
            overloadedNotice(site, object);
        }
        return new PhpRef(element);
    }

    private static void overloadedNotice(Node site, PhpObject object) {
        CallNode.notice(site, "Indirect modification of overloaded element of " + object.getPhpClass().getName()
                + " has no effect");
    }

    /**
     * isset() of an element of a string (an offset in range) or an ArrayAccess object (offsetExists(), called from
     * site); false for other values.
     */
    public static boolean isSetIn(Node site, Object value, Object offsetValue) {
        if (value instanceof TruffleString string) {
            if (offsetValue instanceof TruffleString s && Convert.numericValue(s) == null) {
                return false;
            }
            long offset = Convert.toLong(offsetValue);
            int length = string.byteLength(Str.ENC);
            return offset < 0 ? -offset <= length : offset < length;
        }
        if (value instanceof PhpObject object && object.isArrayAccess()) {
            return Convert.toBool(object.callMethod(site, "offsetexists", offsetValue));
        }
        return false;
    }

}
