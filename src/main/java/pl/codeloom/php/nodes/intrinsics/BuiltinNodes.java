package pl.codeloom.php.nodes.intrinsics;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleStringBuilder;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.StringFunctions;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.call.CallableDispatchNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * Hot built-ins as Truffle DSL nodes (D10 step 2), compiled in place of the call: arguments are child nodes,
 * specializations cover the common argument types with cached TruffleString nodes (the vectorized searches
 * among them), and nothing on those paths is a boundary, so partial evaluation sees through the whole call.
 * Every other case delegates to the function's regular built-in (BuiltinNode.callFull), which keeps the full
 * PHP semantics in one place. The translator fills missing optional arguments with their defaults, so each
 * node has a fixed arity; by-reference array arguments come through a {@link WritableArrayNode}.
 */
public final class BuiltinNodes {
    private BuiltinNodes() {
    }

    /** A built-in function compiled in place. */
    @NodeChild(value = "arguments", type = ExprNode[].class)
    public abstract static class BuiltinNode extends ExprNode {
        /**
         * The regular built-in for the cases the specializations leave out, found in the current context: its Java
         * body is called directly, as its call target would (a call through the target from here costs a frame
         * and the call profiling on top).
         */
        @TruffleBoundary
        protected final Object callFull(String name, Object... values) {
            PhpFunction function = PhpContext.get(this).findFunction(name);
            Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.length];
            System.arraycopy(values, 0, arguments, PhpFunction.ARG_FIRST, values.length);
            return PhpRef.deref(function.getBuiltin().call(arguments));
        }

        protected static boolean isString(Object value) {
            return value instanceof TruffleString;
        }

        protected static boolean isNull(Object value) {
            return value == PhpNull.NULL;
        }
    }

    /**
     * The array of a by-reference array argument, made exclusive for writing (array_pop($a), array_push($a, ...)),
     * or the argument's value when it isn't an array. Created only for pure places, which can be read twice.
     */
    public static final class WritableArrayNode extends ExprNode {
        @Child private LValueNode place;

        public WritableArrayNode(LValueNode place) {
            this.place = place;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object value = place.execute(frame);
            return value instanceof PhpArray ? place.arrayForWrite(frame) : value;
        }
    }

    @TruffleBoundary
    static PhpError arrayTypeError(String function, Object value) {
        return new PhpError("TypeError: " + function + "(): Argument #1 ($array) must be of type array, "
                + Convert.typeName(value) + " given");
    }

    // ---- strings ----

    /** strtolower / strtoupper: a vectorized scan for a letter to change; most strings have none. */
    public abstract static class CaseNode extends BuiltinNode {
        private static final TruffleString.CodePointSet UPPERCASE = TruffleString.CodePointSet.fromRanges(
                new int[] {'A', 'Z'}, Str.ENC);
        private static final TruffleString.CodePointSet LOWERCASE = TruffleString.CodePointSet.fromRanges(
                new int[] {'a', 'z'}, Str.ENC);
        private final boolean upper;

        protected CaseNode(boolean upper) {
            this.upper = upper;
        }

        @Specialization
        protected TruffleString string(TruffleString s, @Cached TruffleString.ByteIndexOfCodePointSetNode find) {
            if (find.execute(s, 0, s.byteLength(Str.ENC), upper ? LOWERCASE : UPPERCASE) < 0) {
                return s;
            }
            return convert(s, upper);
        }

        @TruffleBoundary
        private static TruffleString convert(TruffleString s, boolean upper) {
            return upper ? StringFunctions.upper(s) : StringFunctions.lower(s);
        }

        @Fallback
        protected Object other(Object value) {
            return callFull(upper ? "strtoupper" : "strtolower", value);
        }
    }

    /** trim / ltrim / rtrim with the default character set; a string with nothing to trim is returned as is. */
    public abstract static class TrimNode extends BuiltinNode {
        /** 1: left, 2: right, 3: both. */
        private final int mode;

        protected TrimNode(int mode) {
            this.mode = mode;
        }

        @Specialization
        protected TruffleString string(TruffleString s, @Cached TruffleString.GetInternalByteArrayNode bytes,
                                       @Cached TruffleString.SubstringByteIndexNode substring) {
            InternalByteArray internal = bytes.execute(s, Str.ENC);
            byte[] array = internal.getArray();
            int offset = internal.getOffset();
            int length = internal.getLength();
            int start = 0;
            int end = length;
            if ((mode & 1) != 0) {
                while (start < end && isTrimmed(array[offset + start])) {
                    start++;
                }
            }
            if ((mode & 2) != 0) {
                while (end > start && isTrimmed(array[offset + end - 1])) {
                    end--;
                }
            }
            if (start == 0 && end == length) {
                return s;
            }
            return substring.execute(s, start, end - start, Str.ENC, true);
        }

        /** php-src's default: " \n\r\t\v\0". */
        private static boolean isTrimmed(byte b) {
            return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == 0x0B || b == 0;
        }

        @Fallback
        protected Object other(Object value) {
            return callFull(mode == 3 ? "trim" : mode == 1 ? "ltrim" : "rtrim", value);
        }
    }

    /** ucfirst: unchanged unless the first byte is a lowercase ASCII letter. */
    public abstract static class UcfirstNode extends BuiltinNode {
        @Specialization
        protected TruffleString string(TruffleString s, @Cached TruffleString.ReadByteNode read) {
            if (s.byteLength(Str.ENC) == 0) {
                return s;
            }
            int first = read.execute(s, 0, Str.ENC);
            return first >= 'a' && first <= 'z' ? upperFirst(s) : s;
        }

        @TruffleBoundary
        private static TruffleString upperFirst(TruffleString s) {
            return StringFunctions.caseFirst(s, true);
        }

        @Fallback
        protected Object other(Object value) {
            return callFull("ucfirst", value);
        }
    }

    /**
     * str_replace(string, string, string): a vectorized search first; a subject without the search string (the
     * usual case) is returned without copying. Arrays and the replacing itself go to the regular built-in.
     */
    public abstract static class StrReplaceNode extends BuiltinNode {
        @Specialization
        protected Object strings(TruffleString search, TruffleString replacement, TruffleString subject,
                                 @Cached TruffleString.ByteIndexOfStringNode index) {
            int length = subject.byteLength(Str.ENC);
            int searchLength = search.byteLength(Str.ENC);
            if (searchLength == 0 || searchLength > length || index.execute(subject, search, 0, length, Str.ENC) < 0) {
                return subject;
            }
            return callFull("str_replace", search, replacement, subject);
        }

        @Fallback
        protected Object other(Object search, Object replacement, Object subject) {
            return callFull("str_replace", search, replacement, subject);
        }
    }

    // ---- arrays ----

    /**
     * in_array(string, array[, strict]): string elements compare by bytes, plus PHP's numeric comparison when
     * the needle is a numeric string; other element types use the full loose or strict comparison.
     */
    public abstract static class InArrayNode extends BuiltinNode {
        @Specialization
        protected boolean string(TruffleString needle, PhpArray haystack, boolean strict,
                                 @Cached TruffleString.EqualNode equal) {
            boolean numericNeedle = !strict && isNumeric(needle);
            for (int p = haystack.firstPosition(); p >= 0; p = haystack.nextPosition(p)) {
                Object value = haystack.valueAt(p);
                if (value instanceof TruffleString element) {
                    if (equal.execute(element, needle, Str.ENC)
                            || numericNeedle && Convert.looseEquals(element, needle)) {
                        return true;
                    }
                } else if (!strict && Convert.looseEquals(value, needle)) {
                    return true;
                }
            }
            return false;
        }

        @TruffleBoundary
        private static boolean isNumeric(TruffleString s) {
            return Convert.numericValue(s) != null;
        }

        @Fallback
        protected Object other(Object needle, Object haystack, Object strict) {
            return callFull("in_array", needle, haystack, strict);
        }
    }

    /** array_pop($a): packed arrays pop inline. */
    public abstract static class ArrayPopNode extends BuiltinNode {
        @Specialization
        protected Object array(PhpArray array) {
            return array.pop();
        }

        @Fallback
        protected Object other(Object value) {
            CompilerDirectives.transferToInterpreter();
            throw arrayTypeError("array_pop", value);
        }
    }

    /** array_push($a, $value): one value, appended in place. */
    public abstract static class ArrayPushNode extends BuiltinNode {
        @Specialization
        protected long array(PhpArray array, Object value) {
            // array_push($a, $a) pushes the array as it was, not the array that now contains itself
            array.append(value == array ? array.copy() : value);
            return array.size();
        }

        @Fallback
        protected Object other(Object target, Object value) {
            CompilerDirectives.transferToInterpreter();
            throw arrayTypeError("array_push", target);
        }
    }

    /**
     * array_slice of a packed list with int bounds and renumbered keys (WP_Hook's argument trimming), built
     * inline; other arrays and arguments go to the regular built-in.
     */
    public abstract static class ArraySliceNode extends BuiltinNode {
        @Specialization(guards = {"array.isPacked()", "isLengthOrNull(length)", "preserveKeys == false"})
        protected PhpArray packed(PhpArray array, long offset, Object length, boolean preserveKeys) {
            int n = array.size();
            long start = offset < 0 ? Math.max(0, n + offset) : Math.min(offset, n);
            long end;
            if (length instanceof Long count) {
                end = count < 0 ? Math.max(start, n + count) : Math.min(n, start + count);
            } else {
                end = n;
            }
            PhpArray result = new PhpArray();
            for (int p = (int) start; p < end; p++) {
                result.append(array.valueAt(p));
            }
            return result;
        }

        protected static boolean isLengthOrNull(Object length) {
            return length instanceof Long || length == PhpNull.NULL;
        }

        @Fallback
        protected Object other(Object array, Object offset, Object length, Object preserveKeys) {
            return callFull("array_slice", array, offset, length, preserveKeys);
        }
    }

    /** array_unshift($a, $value): one value; a packed list shifts its elements in place. */
    public abstract static class ArrayUnshiftNode extends BuiltinNode {
        @Specialization
        protected long array(PhpArray array, Object value) {
            array.prepend(value == array ? array.copy() : value);
            return array.size();
        }

        @Fallback
        protected Object other(Object target, Object value) {
            CompilerDirectives.transferToInterpreter();
            throw arrayTypeError("array_unshift", target);
        }
    }

    /** array_keys($a): a packed array's keys are 0..n-1, built unboxed. */
    public abstract static class ArrayKeysNode extends BuiltinNode {
        @Specialization
        protected PhpArray array(PhpArray array) {
            PhpArray result = new PhpArray();
            if (array.isPacked()) {
                for (int i = 0; i < array.size(); i++) {
                    result.appendLong(i);
                }
            } else {
                for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                    result.append(array.keyAt(p));
                }
            }
            return result;
        }

        @Fallback
        protected Object other(Object value) {
            return callFull("array_keys", value);
        }
    }

    /** implode(string, array): pieces appended to a TruffleStringBuilder, ints formatted without a detour. */
    public abstract static class ImplodeNode extends BuiltinNode {
        @Specialization
        protected TruffleString join(TruffleString glue, PhpArray pieces,
                                     @Cached TruffleStringBuilder.AppendStringNode appendString,
                                     @Cached TruffleStringBuilder.AppendLongNumberNode appendLong,
                                     @Cached TruffleStringBuilder.ToStringNode toString) {
            TruffleStringBuilder out = TruffleStringBuilder.create(Str.ENC);
            int first = pieces.firstPosition();
            for (int p = first; p >= 0; p = pieces.nextPosition(p)) {
                if (p != first) {
                    appendString.execute(out, glue);
                }
                Object piece = pieces.valueAt(p);
                if (piece instanceof TruffleString text) {
                    appendString.execute(out, text);
                } else if (piece instanceof Long number) {
                    appendLong.execute(out, number);
                } else {
                    appendString.execute(out, toStr(piece));
                }
            }
            return toString.execute(out);
        }

        @TruffleBoundary
        private static TruffleString toStr(Object value) {
            return Convert.toStr(value);
        }

        @Fallback
        protected Object other(Object glue, Object pieces) {
            return callFull("implode", glue, pieces);
        }
    }

    /** explode(separator, string) without a limit: a vectorized search for each piece. */
    public abstract static class ExplodeNode extends BuiltinNode {
        protected static final long NO_LIMIT = Long.MAX_VALUE;

        @Specialization(guards = {"limit == NO_LIMIT", "hasBytes(separator)"})
        protected PhpArray split(TruffleString separator, TruffleString s, long limit,
                                 @Cached TruffleString.ByteIndexOfStringNode index,
                                 @Cached TruffleString.SubstringByteIndexNode substring) {
            int separatorLength = separator.byteLength(Str.ENC);
            int length = s.byteLength(Str.ENC);
            PhpArray result = new PhpArray();
            int from = 0;
            while (from + separatorLength <= length) {
                int found = index.execute(s, separator, from, length, Str.ENC);
                if (found < 0) {
                    break;
                }
                result.append(substring.execute(s, from, found - from, Str.ENC, false));
                from = found + separatorLength;
            }
            result.append(substring.execute(s, from, length - from, Str.ENC, false));
            return result;
        }

        protected static boolean hasBytes(TruffleString s) {
            return s.byteLength(Str.ENC) > 0;
        }

        @Fallback
        protected Object other(Object separator, Object s, Object limit) {
            return callFull("explode", separator, s, limit);
        }
    }

    /**
     * array_filter($a[, $callback]): the callback goes through an inline cache of direct calls, which Graal can
     * inline, where the regular built-in calls it from behind a boundary.
     */
    public abstract static class ArrayFilterNode extends BuiltinNode {
        @Specialization(guards = {"isNull(callback)", "mode == 0"})
        protected PhpArray truthy(PhpArray array, Object callback, long mode) {
            PhpArray result = new PhpArray();
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object value = array.valueAt(p);
                if (Convert.toBool(value)) {
                    result.put(array.keyAt(p), value);
                }
            }
            return result;
        }

        static final Callables.Parameter CALLBACK = new Callables.Parameter("array_filter", 2, true);

        @Specialization(guards = {"!isNull(callback)", "mode == 0"})
        protected PhpArray callback(PhpArray array, Object callback, long mode,
                                    @Cached("create(CALLBACK)") CallableDispatchNode dispatch) {
            if (array.size() == 0) {
                Callables.check(PhpContext.get(this), callback, CALLBACK);
            }
            PhpArray result = new PhpArray();
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object value = array.valueAt(p);
                if (Convert.toBool(dispatch.execute(callback, new Object[] {value}))) {
                    result.put(array.keyAt(p), value);
                }
            }
            return result;
        }

        @Fallback
        protected Object other(Object array, Object callback, Object mode) {
            return callFull("array_filter", array, callback, mode);
        }
    }

    /** array_map($callback, $a) with one array: keys kept, the callback through an inline cache of direct calls. */
    public abstract static class ArrayMapNode extends BuiltinNode {
        static final Callables.Parameter CALLBACK = new Callables.Parameter("array_map", 1, true);

        @Specialization(guards = "!isNull(callback)")
        protected PhpArray map(Object callback, PhpArray array,
                               @Cached("create(CALLBACK)") CallableDispatchNode dispatch) {
            if (array.size() == 0) {
                Callables.check(PhpContext.get(this), callback, CALLBACK);
            }
            PhpArray result = new PhpArray();
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                result.put(array.keyAt(p), dispatch.execute(callback, new Object[] {array.valueAt(p)}));
            }
            return result;
        }

        @Fallback
        protected Object other(Object callback, Object array) {
            return callFull("array_map", callback, array);
        }
    }

    // ---- the first intrinsics (count, strlen, ord, substr, strpos, str_contains...), on the DSL ----

    /** count($a) of an array; Countable objects and errors go to the regular built-in. */
    public abstract static class CountNode extends BuiltinNode {
        @Specialization
        protected long array(PhpArray array) {
            return array.size();
        }

        @Fallback
        protected Object other(Object value) {
            return callFull("count", value);
        }
    }

    public abstract static class StrlenNode extends BuiltinNode {
        @Specialization
        protected long string(TruffleString s) {
            return s.byteLength(Str.ENC);
        }

        @Fallback
        protected Object other(Object value) {
            return callFull("strlen", value);
        }
    }

    public abstract static class OrdNode extends BuiltinNode {
        @Specialization
        protected long string(TruffleString s, @Cached TruffleString.ReadByteNode readByte) {
            return s.byteLength(Str.ENC) == 0 ? 0 : readByte.execute(s, 0, Str.ENC);
        }

        @Fallback
        protected Object other(Object value) {
            return callFull("ord", value);
        }
    }

    /** substr(string, int[, ?int]): a lazy view of the bytes (no copy), with php-src's offset and length rules. */
    public abstract static class SubstrNode extends BuiltinNode {
        @Specialization(guards = "isLengthOrNull(length)")
        protected TruffleString string(TruffleString s, long from, Object length,
                                       @Cached TruffleString.SubstringByteIndexNode substring) {
            int n = s.byteLength(Str.ENC);
            long start = from < 0 ? Math.max(0, n + from) : from;
            if (start > n) {
                return Str.EMPTY;
            }
            long end;
            if (length instanceof Long count) {
                if (count < 0) {
                    end = n + count;
                    if (end < start) {
                        return Str.EMPTY;
                    }
                } else {
                    end = Math.min(n, start + count);
                }
            } else {
                end = n;
            }
            if (start == 0 && end == n) {
                return s;
            }
            return substring.execute(s, (int) start, (int) (end - start), Str.ENC, true);
        }

        protected static boolean isLengthOrNull(Object length) {
            return length instanceof Long || length == PhpNull.NULL;
        }

        @Fallback
        protected Object other(Object s, Object from, Object length) {
            return callFull("substr", s, from, length);
        }
    }

    /** strpos($haystack, $needle): the byte offset of the first occurrence, or false. */
    public abstract static class StrposNode extends BuiltinNode {
        @Specialization
        protected Object strings(TruffleString haystack, TruffleString needle,
                                 @Cached TruffleString.ByteIndexOfStringNode indexOf) {
            int length = haystack.byteLength(Str.ENC);
            int needleLength = needle.byteLength(Str.ENC);
            int index = needleLength == 0 ? 0
                    : needleLength > length ? -1 : indexOf.execute(haystack, needle, 0, length, Str.ENC);
            return index < 0 ? (Object) Boolean.FALSE : (long) index;
        }

        @Fallback
        protected Object other(Object haystack, Object needle) {
            return callFull("strpos", haystack, needle);
        }
    }

    /** str_contains / str_starts_with / str_ends_with. */
    public abstract static class ContainsNode extends BuiltinNode {
        public static final int CONTAINS = 0;
        public static final int STARTS = 1;
        public static final int ENDS = 2;

        private final int mode;

        protected ContainsNode(int mode) {
            this.mode = mode;
        }

        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Specialization
        protected boolean strings(TruffleString haystack, TruffleString needle,
                                  @Cached TruffleString.ByteIndexOfStringNode indexOf,
                                  @Cached TruffleString.RegionEqualByteIndexNode regionEqual) {
            int length = haystack.byteLength(Str.ENC);
            int needleLength = needle.byteLength(Str.ENC);
            if (needleLength == 0) {
                return true;
            }
            if (needleLength > length) {
                return false;
            }
            return switch (mode) {
                case CONTAINS -> indexOf.execute(haystack, needle, 0, length, Str.ENC) >= 0;
                case STARTS -> regionEqual.execute(haystack, 0, needle, 0, needleLength, Str.ENC);
                default -> regionEqual.execute(haystack, length - needleLength, needle, 0, needleLength, Str.ENC);
            };
        }

        @Fallback
        protected Object other(Object haystack, Object needle) {
            return callFull(mode == CONTAINS ? "str_contains" : mode == STARTS ? "str_starts_with" : "str_ends_with",
                    haystack, needle);
        }
    }

    /** array_key_exists($key, $array) for int and string keys of arrays. */
    public abstract static class KeyExistsNode extends BuiltinNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Specialization
        protected boolean intKey(long key, PhpArray array) {
            return array.containsKey(key);
        }

        @Specialization
        protected boolean stringKey(TruffleString key, PhpArray array) {
            return array.containsKey(normalize(key));
        }

        @TruffleBoundary
        private static Object normalize(TruffleString key) {
            return ArrayKey.of(key);
        }

        @Fallback
        protected Object other(Object key, Object container) {
            return callFull("array_key_exists", key, container);
        }
    }
}
