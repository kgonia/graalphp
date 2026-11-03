package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** ext/standard array functions. Sorts are stable, as php-src's zend_sort is since PHP 8.0. */
final class ArrayFunctions {
    private ArrayFunctions() {
    }

    /** A key/value pair taken out of an array for sorting. */
    private record Entry(Object key, Object value) {
    }

    static void register(Registry r) {
        r.fn("array_keys", a -> keys(Args.array(a, 0, "array_keys"), Args.has(a, 1) ? Args.get(a, 1) : null,
                Args.bool(a, 2, false)));
        r.fn("array_values", a -> values(Args.array(a, 0, "array_values")));
        r.fn("array_merge", a -> merge(a));
        r.fn("array_map", (context, a) -> {
            Args.arity(a, "array_map", 2, Integer.MAX_VALUE);
            return map(context, a);
        });
        r.fn("array_filter", (context, a) -> filter(context, Args.array(a, 0, "array_filter"), Args.get(a, 1),
                Args.lng(a, 2, 0)));
        r.fn("array_key_exists", a -> keyExists(Args.get(a, 0), Args.get(a, 1)));
        r.fn("key_exists", a -> keyExists(Args.get(a, 0), Args.get(a, 1)));
        r.fn("in_array", a -> search(Args.get(a, 0), Args.array(a, 1, "in_array", "haystack"), Args.bool(a, 2, false)) != null);
        r.fn("array_search", a -> {
            Object key = search(Args.get(a, 0), Args.array(a, 1, "array_search", "haystack"), Args.bool(a, 2, false));
            return key == null ? Boolean.FALSE : key;
        });
        r.fn("array_slice", a -> slice(Args.array(a, 0, "array_slice"), Args.lng(a, 1, 0),
                StringFunctions.lengthArg(a, 2), Args.bool(a, 3, false)));
        r.fn("array_splice", "1", a -> splice(a));
        r.fn("array_unique", a -> unique(Args.array(a, 0, "array_unique")));
        r.fn("array_diff", a -> diffOrIntersect(a, true));
        r.fn("array_intersect", a -> diffOrIntersect(a, false));
        r.fn("array_diff_key", a -> diffKeys(a, true));
        r.fn("array_intersect_key", a -> diffKeys(a, false));
        r.fn("array_fill", a -> {
            long start = Args.lng(a, 0, 0);
            long count = Args.lng(a, 1, 0);
            PhpArray result = new PhpArray();
            for (long i = 0; i < count; i++) {
                result.put(start + i, Args.get(a, 2));
            }
            return result;
        });
        r.fn("array_fill_keys", a -> {
            PhpArray keys = Args.array(a, 0, "array_fill_keys", "keys");
            PhpArray result = new PhpArray();
            for (int p = keys.firstPosition(); p >= 0; p = keys.nextPosition(p)) {
                result.put(stringKey(keys.valueAt(p)), Args.get(a, 1));
            }
            return result;
        });
        r.fn("array_column", (context, a) -> {
            Args.arity(a, "array_column", 2, 3);
            PhpArray rows = Args.array(a, 0, "array_column");
            return column(context, rows, stringOrLong(context, a, 1, "column_key"),
                    Args.has(a, 2) ? stringOrLong(context, a, 2, "index_key") : null);
        });
        r.fn("array_pop", "1", a -> pop(Args.ref(a, 0)));
        r.fn("array_push", "1", a -> {
            PhpArray array = writable(Args.ref(a, 0), "array_push");
            for (int i = 1; i < Args.count(a); i++) {
                array.append(Args.get(a, i));
            }
            return (long) array.size();
        });
        r.fn("array_shift", "1", a -> shift(Args.ref(a, 0)));
        r.fn("array_unshift", "1", a -> unshift(a));
        r.fn("array_key_first", a -> {
            PhpArray array = Args.array(a, 0, "array_key_first");
            int p = array.firstPosition();
            return p < 0 ? PhpNull.NULL : array.keyAt(p);
        });
        r.fn("array_key_last", a -> {
            PhpArray array = Args.array(a, 0, "array_key_last");
            int p = array.lastPosition();
            return p < 0 ? PhpNull.NULL : array.keyAt(p);
        });
        r.fn("array_first", a -> {
            PhpArray array = Args.array(a, 0, "array_first");
            int p = array.firstPosition();
            return p < 0 ? PhpNull.NULL : array.valueAt(p);
        });
        r.fn("array_last", a -> {
            PhpArray array = Args.array(a, 0, "array_last");
            int p = array.lastPosition();
            return p < 0 ? PhpNull.NULL : array.valueAt(p);
        });
        r.fn("array_any", (context, a) -> anyAll(context, Args.array(a, 0, "array_any"), Args.get(a, 1), true));
        r.fn("array_all", (context, a) -> anyAll(context, Args.array(a, 0, "array_all"), Args.get(a, 1), false));
        r.fn("array_find", (context, a) -> find(context, Args.array(a, 0, "array_find"), Args.get(a, 1), false));
        r.fn("array_find_key", (context, a) -> find(context, Args.array(a, 0, "array_find_key"), Args.get(a, 1), true));
        r.fn("array_is_list", a -> {
            PhpArray array = Args.array(a, 0, "array_is_list");
            long expected = 0;
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                if (!(array.keyAt(p) instanceof Long k) || k != expected++) {
                    return false;
                }
            }
            return true;
        });
        r.fn("array_flip", a -> {
            PhpArray array = Args.array(a, 0, "array_flip");
            PhpArray result = new PhpArray();
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object value = array.valueAt(p);
                if (value instanceof Long || value instanceof TruffleString) {
                    result.put(ArrayKey.of(value), array.keyAt(p));
                } else {
                    ErrorFunctions.raise(PhpContext.get(null), ErrorFunctions.E_WARNING, Str.of("array_flip(): Can "
                            + "only flip string and integer values, entry skipped"), SourceLocation.current());
                }
            }
            return result;
        });
        r.fn("array_reverse", a -> reverse(Args.array(a, 0, "array_reverse"), Args.bool(a, 1, false)));
        r.fn("array_combine", a -> {
            PhpArray keys = Args.array(a, 0, "array_combine", "keys");
            PhpArray values = Args.array(a, 1, "array_combine", "values");
            if (keys.size() != values.size()) {
                throw new PhpError("ValueError", "array_combine(): Argument #1 ($keys) and argument #2 ($values) "
                        + "must have the same number of elements");
            }
            PhpArray result = new PhpArray();
            int q = values.firstPosition();
            for (int p = keys.firstPosition(); p >= 0; p = keys.nextPosition(p)) {
                result.put(stringKey(keys.valueAt(p)), values.valueAt(q));
                q = values.nextPosition(q);
            }
            return result;
        });
        r.fn("array_pad", a -> {
            PhpArray array = Args.array(a, 0, "array_pad");
            long size = Args.lng(a, 1, 0);
            Object value = Args.get(a, 2);
            int missing = (int) (Math.abs(size) - array.size());
            if (missing <= 0) {
                return array;
            }
            PhpArray result = new PhpArray();
            if (size < 0) {
                for (int i = 0; i < missing; i++) {
                    result.append(value);
                }
            }
            appendAll(result, array);
            if (size > 0) {
                for (int i = 0; i < missing; i++) {
                    result.append(value);
                }
            }
            return result;
        });
        r.fn("array_sum", a -> {
            Object total = 0L;
            PhpArray array = Args.array(a, 0, "array_sum");
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                total = pl.codeloom.php.runtime.Operators.add(null, total, Convert.toNumber(array.valueAt(p)));
            }
            return total;
        });
        r.fn("array_count_values", a -> {
            PhpArray array = Args.array(a, 0, "array_count_values");
            PhpArray result = new PhpArray();
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object value = array.valueAt(p);
                if (!(value instanceof Long || value instanceof TruffleString)) {
                    ErrorFunctions.raise(PhpContext.get(null), ErrorFunctions.E_WARNING, Str.of("array_count_values(): "
                            + "Can only count string and integer values, entry skipped"), SourceLocation.current());
                    continue;
                }
                Object key = ArrayKey.of(value);
                Object current = result.get(key);
                result.put(key, current == null ? 1L : (Long) current + 1);
            }
            return result;
        });
        r.fn("array_walk", "1", (context, a) -> {
            Args.arity(a, "array_walk", 2, 3);
            PhpArray array = writable(Args.ref(a, 0), "array_walk");
            Callables.Target callback = Callables.parameter(context, Args.get(a, 1),
                    new Callables.Parameter("array_walk", 2, false));
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                if (Args.has(a, 2)) {
                    callback.call(array.refAtPosition(p), array.keyAt(p), Args.get(a, 2));
                } else {
                    callback.call(array.refAtPosition(p), array.keyAt(p));
                }
            }
            return true;
        });
        r.fn("range", a -> range(Args.get(a, 0), Args.get(a, 1), Args.get(a, 2, 1L)));
        r.fn("array_reduce", (context, a) -> {
            PhpArray array = Args.array(a, 0, "array_reduce");
            Callables.Target callback = Callables.parameter(context, Args.get(a, 1),
                    new Callables.Parameter("array_reduce", 2, false));
            Object carry = Args.get(a, 2, PhpNull.NULL);
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                carry = callback.call(carry, array.valueAt(p));
            }
            return carry;
        });
        r.fn("array_walk_recursive", "1", (context, a) -> {
            Args.arity(a, "array_walk_recursive", 2, 3);
            PhpArray array = writable(Args.ref(a, 0), "array_walk_recursive");
            Callables.Target callback = Callables.parameter(context, Args.get(a, 1),
                    new Callables.Parameter("array_walk_recursive", 2, false));
            walkRecursive(array, callback, Args.has(a, 2) ? Args.get(a, 2) : null);
            return true;
        });
        r.fn("array_replace", a -> replace(a, false));
        r.fn("array_replace_recursive", a -> replace(a, true));
        r.fn("array_merge_recursive", a -> {
            PhpArray result = new PhpArray();
            for (int i = 0; i < Args.count(a); i++) {
                mergeRecursive(result, Args.array(a, i, "array_merge_recursive", null));
            }
            return result;
        });
        r.fn("array_change_key_case", a -> changeKeyCase(Args.array(a, 0, "array_change_key_case"),
                Args.lng(a, 1, 0) == 1));
        r.fn("array_chunk", a -> chunk(Args.array(a, 0, "array_chunk"), Args.lng(a, 1, 0), Args.bool(a, 2, false)));
        r.fn("array_product", a -> {
            PhpArray array = Args.array(a, 0, "array_product");
            Object product = 1L;
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                product = Operators.mul(null, product, Convert.toNumber(array.valueAt(p)));
            }
            return product;
        });
        r.fn("array_diff_assoc", a -> diffAssoc(a, true));
        r.fn("array_intersect_assoc", a -> diffAssoc(a, false));
        r.fn("array_rand", a -> rand(Args.array(a, 0, "array_rand"), Args.lng(a, 1, 1)));
        r.fn("shuffle", "1", a -> {
            PhpRef ref = Args.ref(a, 0);
            List<Object> values = new ArrayList<>();
            PhpArray array = writable(ref, "shuffle");
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                values.add(array.valueAt(p));
            }
            Collections.shuffle(values);
            ref.set(PhpArray.list(values.toArray()));
            return true;
        });
        r.fn("iterator_to_array", a -> iteratorToArray(Args.get(a, 0), Args.bool(a, 1, true)));

        // ---- sorting ----
        r.fn("sort", "1", (context, a) -> sort(context, a, false, false, false, false));
        r.fn("rsort", "1", (context, a) -> sort(context, a, false, false, true, false));
        r.fn("usort", "1", (context, a) -> sort(context, a, true, false, false, false));
        r.fn("asort", "1", (context, a) -> sort(context, a, false, false, false, true));
        r.fn("arsort", "1", (context, a) -> sort(context, a, false, false, true, true));
        r.fn("uasort", "1", (context, a) -> sort(context, a, true, false, false, true));
        r.fn("ksort", "1", (context, a) -> sort(context, a, false, true, false, true));
        r.fn("krsort", "1", (context, a) -> sort(context, a, false, true, true, true));
        r.fn("uksort", "1", (context, a) -> sort(context, a, true, true, false, true));

        // ---- internal pointer ----
        r.fn("current", a -> current(Args.get(a, 0)));
        r.fn("pos", a -> current(Args.get(a, 0)));
        r.fn("key", a -> {
            PhpArray array = Args.array(a, 0, "key");
            int p = array.pointer();
            return array.isValidPosition(p) ? array.keyAt(p) : PhpNull.NULL;
        });
        r.fn("next", "1", a -> move(Args.ref(a, 0), 1));
        r.fn("prev", "1", a -> move(Args.ref(a, 0), -1));
        r.fn("reset", "1", a -> move(Args.ref(a, 0), 0));
        r.fn("end", "1", a -> move(Args.ref(a, 0), 2));
    }

    // ---- helpers ----

    @TruffleBoundary
    private static void walkRecursive(PhpArray array, Callables.Target callback, Object extra) {
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            PhpRef element = array.refAtPosition(p);
            if (element.get() instanceof PhpArray) {
                walkRecursive(element.arrayForWrite(), callback, extra);
            } else if (extra != null) {
                callback.call(element, array.keyAt(p), extra);
            } else {
                callback.call(element, array.keyAt(p));
            }
        }
    }

    @TruffleBoundary
    private static PhpArray replace(Object[] a, boolean recursive) {
        String function = recursive ? "array_replace_recursive" : "array_replace";
        PhpArray result = Args.array(a, 0, function).copy();
        for (int i = 1; i < Args.count(a); i++) {
            replaceInto(result, Args.array(a, i, function), recursive);
        }
        return result;
    }

    private static void replaceInto(PhpArray target, PhpArray source, boolean recursive) {
        for (int p = source.firstPosition(); p >= 0; p = source.nextPosition(p)) {
            Object key = source.keyAt(p);
            Object value = source.valueAt(p);
            if (recursive && value instanceof PhpArray replacement && target.get(key) instanceof PhpArray current) {
                PhpArray merged = current.copy();
                replaceInto(merged, replacement, true);
                target.put(key, merged);
            } else {
                target.put(key, value);
            }
        }
    }

    @TruffleBoundary
    private static void mergeRecursive(PhpArray target, PhpArray source) {
        for (int p = source.firstPosition(); p >= 0; p = source.nextPosition(p)) {
            Object key = source.keyAt(p);
            Object value = source.valueAt(p);
            Object current = key instanceof Long ? null : target.get(key);
            if (key instanceof Long) {
                target.appendCopied(source.copiedAt(p));
            } else if (current == null) {
                target.putCopied(key, source.copiedAt(p));
            } else {
                PhpArray merged = current instanceof PhpArray existing ? existing.copy() : PhpArray.list(current);
                if (value instanceof PhpArray nested) {
                    mergeRecursive(merged, nested);
                } else {
                    merged.append(value);
                }
                // a reference there is replaced by the array, not written through (ZVAL_UNREF or ZVAL_DUP)
                target.replace(key, merged);
            }
        }
    }

    @TruffleBoundary
    private static PhpArray changeKeyCase(PhpArray array, boolean upper) {
        PhpArray result = PhpArray.ofCapacity(array.size());
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            Object key = array.keyAt(p);
            if (key instanceof TruffleString name) {
                // php-src's zend_string_tolower/toupper: ASCII letters of the bytes, the rest kept as they are
                byte[] bytes = Str.bytes(name);
                for (int i = 0; i < bytes.length; i++) {
                    byte b = bytes[i];
                    if (upper ? b >= 'a' && b <= 'z' : b >= 'A' && b <= 'Z') {
                        bytes[i] = (byte) (upper ? b - ('a' - 'A') : b + ('a' - 'A'));
                    }
                }
                key = ArrayKey.of(Str.of(bytes));
            }
            result.put(key, array.valueAt(p));
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray chunk(PhpArray array, long length, boolean preserveKeys) {
        if (length < 1) {
            throw new PhpError("ValueError", "array_chunk(): Argument #2 ($length) must be greater than 0");
        }
        PhpArray result = new PhpArray();
        PhpArray chunk = null;
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            if (chunk == null || chunk.size() == length) {
                chunk = new PhpArray();
                result.append(chunk);
            }
            if (preserveKeys) {
                chunk.putCopied(array.keyAt(p), array.copiedAt(p));
            } else {
                chunk.appendCopied(array.copiedAt(p));
            }
        }
        return result;
    }

    /** Values compared as strings, keys strictly: array_diff_assoc keeps the first array's pairs absent elsewhere. */
    @TruffleBoundary
    private static PhpArray diffAssoc(Object[] a, boolean diff) {
        String function = diff ? "array_diff_assoc" : "array_intersect_assoc";
        PhpArray first = Args.array(a, 0, function);
        PhpArray result = new PhpArray();
        for (int p = first.firstPosition(); p >= 0; p = first.nextPosition(p)) {
            Object key = first.keyAt(p);
            TruffleString value = Convert.toStr(first.valueAt(p));
            boolean inAll = true;
            boolean inAny = false;
            for (int i = 1; i < Args.count(a); i++) {
                Object other = Args.array(a, i, function).get(key);
                boolean found = other != null && Str.compare(Convert.toStr(other), value) == 0;
                inAll &= found;
                inAny |= found;
            }
            if (diff ? !inAny : inAll) {
                result.putCopied(key, first.copiedAt(p));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static Object rand(PhpArray array, long count) {
        if (array.size() == 0) {
            throw new PhpError("ValueError", "array_rand(): Argument #1 ($array) cannot be empty");
        }
        if (count < 1 || count > array.size()) {
            throw new PhpError("ValueError", "array_rand(): Argument #2 ($num) must be between 1 and the number of "
                    + "elements in argument #1 ($array)");
        }
        List<Object> keys = new ArrayList<>();
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            keys.add(array.keyAt(p));
        }
        if (count == 1) {
            return keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
        }
        List<Object> picked = new ArrayList<>(keys);
        Collections.shuffle(picked);
        Set<Object> chosen = new HashSet<>(picked.subList(0, (int) count));
        PhpArray result = new PhpArray();
        for (Object key : keys) {
            if (chosen.contains(key)) {
                result.append(key);
            }
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray iteratorToArray(Object iterable, boolean preserveKeys) {
        if (iterable instanceof PhpArray array) {
            return preserveKeys ? array.copy() : values(array);
        }
        Object iterator = iterable;
        while (iterator instanceof PhpObject object && object.getPhpClass().isSubclassOf("iteratoraggregate")) {
            iterator = object.callMethod("getiterator");
        }
        if (!(iterator instanceof PhpObject object)) {
            throw new PhpError("TypeError", "iterator_to_array(): Argument #1 ($iterator) must be of type "
                    + "Traversable|array, " + Convert.typeName(iterable) + " given");
        }
        PhpArray result = new PhpArray();
        object.callMethod("rewind");
        while (Convert.toBool(object.callMethod("valid"))) {
            Object value = object.callMethod("current");
            if (preserveKeys) {
                result.put(ArrayKey.of(object.callMethod("key")), value);
            } else {
                result.append(value);
            }
            object.callMethod("next");
        }
        return result;
    }

    /** The by-reference array argument of function, writable; anything else is php-src's TypeError. */
    private static PhpArray writable(PhpRef ref, String function) {
        if (!(ref.get() instanceof PhpArray)) {
            throw new PhpError("TypeError", function + "(): Argument #1 ($array) must be of type array, "
                    + Args.valueName(ref.get()) + " given");
        }
        return ref.arrayForWrite();
    }

    private static void appendAll(PhpArray target, PhpArray source) {
        for (int p = source.firstPosition(); p >= 0; p = source.nextPosition(p)) {
            target.appendCopied(source.copiedAt(p));
        }
    }

    @TruffleBoundary
    private static PhpArray keys(PhpArray array, Object filter, boolean strict) {
        PhpArray result = PhpArray.ofCapacity(array.size());
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            if (filter == null || (strict ? Convert.strictEquals(array.valueAt(p), filter)
                    : Convert.looseEquals(array.valueAt(p), filter))) {
                result.append(array.keyAt(p));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray values(PhpArray array) {
        PhpArray result = PhpArray.ofCapacity(array.size());
        appendAll(result, array);
        return result;
    }

    /**
     * array_merge as php_array_merge_wrapper: the result made at the elements' count; with two arrays of which one
     * is empty, the other itself when merging would only copy it (a list, or string keys only), as php-src
     * returns it (shared, so a write through either copies, and with its internal pointer).
     */
    @TruffleBoundary
    private static PhpArray merge(Object[] a) {
        int arguments = Args.count(a);
        int count = 0;
        boolean packed = true;
        boolean longs = true;
        for (int i = 0; i < arguments; i++) {
            PhpArray array = Args.array(a, i, "array_merge", null);
            count += array.size();
            packed &= array.isPacked();
            longs &= array.holdsLongs() || array.size() == 0;
        }
        if (arguments == 2) {
            PhpArray first = Args.array(a, 0, "array_merge", null);
            PhpArray second = Args.array(a, 1, "array_merge");
            PhpArray only = first.size() == 0 ? second : second.size() == 0 ? first : null;
            if (only != null && (only.isPacked() || onlyStringKeys(only))) {
                return only;
            }
        }
        PhpArray result = count == 0 ? new PhpArray() : !packed ? PhpArray.ofHashCapacity(count)
                : longs ? PhpArray.ofLongCapacity(count) : PhpArray.ofCapacity(count);
        for (int i = 0; i < arguments; i++) {
            PhpArray array = Args.array(a, i, "array_merge", null);
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object key = array.keyAt(p);
                if (key instanceof Long) {
                    result.appendCopied(array.copiedAt(p));
                } else {
                    result.putCopied(key, array.copiedAt(p));
                }
            }
        }
        return result;
    }

    private static boolean onlyStringKeys(PhpArray array) {
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            if (array.keyAt(p) instanceof Long) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    private static PhpArray map(PhpContext context, Object[] a) {
        Callables.Target callback = Args.get(a, 0) == PhpNull.NULL ? null
                : Callables.parameter(context, Args.get(a, 0), new Callables.Parameter("array_map", 1, true));
        int arrays = Args.count(a) - 1;
        if (arrays == 1) {
            PhpArray array = Args.array(a, 1, "array_map", "array");
            if (callback == null) {
                // the array itself (ZVAL_COPY), its references and internal pointer included
                return array;
            }
            PhpArray result = PhpArray.ofCapacity(array.size());
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object value = callback == null ? array.valueAt(p) : callback.call(array.valueAt(p));
                result.put(array.keyAt(p), value);
            }
            return result;
        }
        PhpArray[] inputs = new PhpArray[arrays];
        int[] positions = new int[arrays];
        int longest = 0;
        for (int i = 0; i < arrays; i++) {
            inputs[i] = Args.array(a, i + 1, "array_map", i == 0 ? "array" : null);
            positions[i] = inputs[i].firstPosition();
            longest = Math.max(longest, inputs[i].size());
        }
        PhpArray result = new PhpArray();
        for (int n = 0; n < longest; n++) {
            Object[] values = new Object[arrays];
            for (int i = 0; i < arrays; i++) {
                if (positions[i] >= 0) {
                    values[i] = callback == null ? inputs[i].copiedAt(positions[i]) : inputs[i].valueAt(positions[i]);
                    positions[i] = inputs[i].nextPosition(positions[i]);
                } else {
                    values[i] = PhpNull.NULL;
                }
            }
            if (callback == null) {
                PhpArray zipped = PhpArray.ofCapacity(arrays);
                for (Object value : values) {
                    zipped.appendCopied(value);
                }
                result.append(zipped);
            } else {
                result.append(callback.call(values));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray filter(PhpContext context, PhpArray array, Object callable, long mode) {
        Callables.Target callback = callable == null || callable == PhpNull.NULL ? null
                : Callables.parameter(context, callable, new Callables.Parameter("array_filter", 2, true));
        PhpArray result = new PhpArray();
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            Object key = array.keyAt(p);
            Object value = array.valueAt(p);
            boolean keep;
            if (callback == null) {
                keep = Convert.toBool(value);
            } else if (mode == 2) {
                keep = Convert.toBool(callback.call(key));
            } else if (mode == 1) {
                keep = Convert.toBool(callback.call(value, key));
            } else {
                keep = Convert.toBool(callback.call(value));
            }
            if (keep) {
                result.putCopied(key, array.copiedAt(p));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static boolean keyExists(Object key, Object container) {
        if (container instanceof PhpArray array) {
            return array.containsKey(ArrayKey.of(key == null ? PhpNull.NULL : key));
        }
        if (container instanceof PhpObject object) {
            return object.getProperty(Str.key(Convert.toStr(key))) != null;
        }
        throw new PhpError("TypeError", "array_key_exists(): Argument #2 ($array) must be of type array");
    }

    @TruffleBoundary
    private static Object search(Object needle, PhpArray array, boolean strict) {
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            Object value = array.valueAt(p);
            if (strict ? Convert.strictEquals(value, needle) : Convert.looseEquals(value, needle)) {
                return array.keyAt(p);
            }
        }
        return null;
    }

    @TruffleBoundary
    private static PhpArray slice(PhpArray array, long offset, Long length, boolean preserveKeys) {
        int n = array.size();
        long start = offset < 0 ? Math.max(0, n + offset) : Math.min(offset, n);
        long end = length == null ? n : length < 0 ? Math.max(start, n + length) : Math.min(n, start + length);
        PhpArray result = new PhpArray();
        int index = 0;
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p), index++) {
            if (index < start) {
                continue;
            }
            if (index >= end) {
                break;
            }
            Object key = array.keyAt(p);
            if (key instanceof Long && !preserveKeys) {
                result.appendCopied(array.copiedAt(p));
            } else {
                result.putCopied(key, array.copiedAt(p));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray splice(Object[] a) {
        PhpRef ref = Args.ref(a, 0);
        PhpArray array = writable(ref, "array_splice");
        int n = array.size();
        long offset = Args.lng(a, 1, 0);
        Long length = StringFunctions.lengthArg(a, 2);
        long start = offset < 0 ? Math.max(0, n + offset) : Math.min(offset, n);
        long end = length == null ? n : length < 0 ? Math.max(start, n + length) : Math.min(n, start + length);
        Object replacement = Args.get(a, 3);
        PhpArray removed = new PhpArray();
        PhpArray result = new PhpArray();
        int index = 0;
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p), index++) {
            if (index == start) {
                insertReplacement(result, replacement);
            }
            Object key = array.keyAt(p);
            Object raw = array.rawAt(p);
            if (index >= start && index < end) {
                removed.append(PhpRef.deref(raw));
                continue;
            }
            if (key instanceof Long) {
                result.append(PhpRef.deref(raw));
            } else {
                result.put(key, PhpRef.deref(raw));
            }
        }
        if (start >= index) {
            insertReplacement(result, replacement);
        }
        ref.set(result);
        return removed;
    }

    private static void insertReplacement(PhpArray result, Object replacement) {
        if (replacement == null) {
            return;
        }
        if (replacement instanceof PhpArray items) {
            appendAll(result, items);
        } else {
            result.append(replacement);
        }
    }

    @TruffleBoundary
    private static PhpArray unique(PhpArray array) {
        // SORT_STRING: values compare by their string form; TruffleString hashes and compares bytes
        Set<TruffleString> seen = new HashSet<>();
        PhpArray result = new PhpArray();
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            Object value = array.valueAt(p);
            if (seen.add(Convert.toStr(value))) {
                result.putCopied(array.keyAt(p), array.copiedAt(p));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray diffOrIntersect(Object[] a, boolean diff) {
        PhpArray first = Args.array(a, 0, diff ? "array_diff" : "array_intersect");
        List<Set<TruffleString>> others = new ArrayList<>();
        for (int i = 1; i < Args.count(a); i++) {
            Set<TruffleString> set = new HashSet<>();
            PhpArray other = Args.array(a, i, diff ? "array_diff" : "array_intersect");
            for (int p = other.firstPosition(); p >= 0; p = other.nextPosition(p)) {
                set.add(Convert.toStr(other.valueAt(p)));
            }
            others.add(set);
        }
        PhpArray result = new PhpArray();
        for (int p = first.firstPosition(); p >= 0; p = first.nextPosition(p)) {
            TruffleString text = Convert.toStr(first.valueAt(p));
            boolean keep;
            if (diff) {
                keep = others.stream().noneMatch(set -> set.contains(text));
            } else {
                keep = others.stream().allMatch(set -> set.contains(text));
            }
            if (keep) {
                result.put(first.keyAt(p), first.valueAt(p));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray diffKeys(Object[] a, boolean diff) {
        PhpArray first = Args.array(a, 0, "array_diff_key");
        PhpArray result = new PhpArray();
        for (int p = first.firstPosition(); p >= 0; p = first.nextPosition(p)) {
            Object key = first.keyAt(p);
            boolean inAll = true;
            boolean inAny = false;
            for (int i = 1; i < Args.count(a); i++) {
                boolean present = Args.array(a, i, "array_diff_key").containsKey(key);
                inAll &= present;
                inAny |= present;
            }
            if (diff ? !inAny : inAll) {
                result.put(key, first.valueAt(p));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray column(PhpContext context, PhpArray rows, Object columnKey, Object indexKey) {
        PhpArray result = new PhpArray();
        for (int p = rows.firstPosition(); p >= 0; p = rows.nextPosition(p)) {
            Object row = rows.valueAt(p);
            Object value = columnKey == null ? row : field(row, columnKey);
            if (value == null) {
                continue;
            }
            Object index = indexKey == null ? null : field(row, indexKey);
            if (index == null) {
                result.append(value);
            } else {
                result.put(zvalKey(context, index), value);
            }
        }
        return result;
    }

    /** Z_PARAM_STR_OR_LONG_OR_NULL: a TruffleString, a Long, or null. */
    private static Object stringOrLong(PhpContext context, Object[] a, int index, String name) {
        Object value = Args.get(a, index, PhpNull.NULL);
        if (value == PhpNull.NULL || value instanceof Long || value instanceof TruffleString) {
            return value == PhpNull.NULL ? null : value;
        }
        if (value instanceof Double || value instanceof Boolean) {
            return Args.integer(context, a, index, "array_column", name, "string|int|null");
        }
        if (value instanceof PhpObject object && object.getPhpClass().findMethod("__tostring") != null) {
            return object.toPhpString();
        }
        throw Args.typeError("array_column", index + 1, name, "string|int|null", value);
    }

    /** array_column_fetch_prop: an array's element, or an object's property that exists or __isset says is set. */
    private static Object field(Object row, Object key) {
        if (row instanceof PhpArray array) {
            return array.get(ArrayKey.of(key));
        }
        if (row instanceof PhpObject object) {
            String name = Str.key(Convert.toStr(key));
            boolean visible = object.getProperty(name) != null && object.getPhpClass().canAccessProperty(name, null);
            if (!visible) {
                Object isset = object.magicProperty("__isset", name);
                if (isset == PhpObject.NO_MAGIC || !Convert.toBool(PhpRef.deref(isset))) {
                    return null;
                }
                Object value = object.magicProperty("__get", name);
                return value == PhpObject.NO_MAGIC ? null : PhpRef.deref(value);
            }
            return PhpRef.deref(object.getProperty(name));
        }
        return null;
    }

    /** array_set_zval_key's key: a resource as its id (warned), else as an array offset. */
    private static Object zvalKey(PhpContext context, Object key) {
        if (key instanceof PhpResource resource) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("Resource ID#" + resource.getId()
                    + " used as offset, casting to integer (" + resource.getId() + ")"), SourceLocation.current());
            return (long) resource.getId();
        }
        return ArrayKey.of(key);
    }

    /** An int key, else the value as a string key (zval_get_string, then zend_symtable_update). */
    private static Object stringKey(Object key) {
        return key instanceof Long ? key : ArrayKey.of(Convert.toStr(key));
    }

    @TruffleBoundary
    private static Object pop(PhpRef ref) {
        return ref.get() instanceof PhpArray ? ref.arrayForWrite().pop() : PhpNull.NULL;
    }

    @TruffleBoundary
    private static Object shift(PhpRef ref) {
        if (!(ref.get() instanceof PhpArray array) || array.size() == 0) {
            return PhpNull.NULL;
        }
        int first = array.firstPosition();
        Object value = array.valueAt(first);
        PhpArray result = new PhpArray();
        for (int p = array.nextPosition(first); p >= 0; p = array.nextPosition(p)) {
            Object key = array.keyAt(p);
            if (key instanceof Long) {
                result.append(PhpRef.deref(array.rawAt(p)));
            } else {
                result.put(key, PhpRef.deref(array.rawAt(p)));
            }
        }
        if (value instanceof PhpArray nested) {
            nested.addHolder();
        }
        ref.set(result);
        if (value instanceof PhpArray nested) {
            nested.removeHolder();
        }
        return value;
    }

    @TruffleBoundary
    private static long unshift(Object[] a) {
        PhpRef ref = Args.ref(a, 0);
        PhpArray array = writable(ref, "array_unshift");
        PhpArray result = new PhpArray();
        for (int i = 1; i < Args.count(a); i++) {
            result.append(Args.get(a, i));
        }
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            Object key = array.keyAt(p);
            if (key instanceof Long) {
                result.append(array.valueAt(p));
            } else {
                result.put(key, array.valueAt(p));
            }
        }
        ref.set(result);
        return result.size();
    }

    @TruffleBoundary
    private static Object anyAll(PhpContext context, PhpArray array, Object callable, boolean any) {
        Callables.Target callback = Callables.parameter(context, callable,
                new Callables.Parameter(any ? "array_any" : "array_all", 2, false));
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            boolean match = Convert.toBool(callback.call(array.valueAt(p), array.keyAt(p)));
            if (match == any) {
                return any;
            }
        }
        return !any;
    }

    @TruffleBoundary
    private static Object find(PhpContext context, PhpArray array, Object callable, boolean wantKey) {
        Callables.Target callback = Callables.parameter(context, callable,
                new Callables.Parameter(wantKey ? "array_find_key" : "array_find", 2, false));
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            if (Convert.toBool(callback.call(array.valueAt(p), array.keyAt(p)))) {
                return wantKey ? array.keyAt(p) : array.valueAt(p);
            }
        }
        return PhpNull.NULL;
    }

    @TruffleBoundary
    private static PhpArray reverse(PhpArray array, boolean preserveKeys) {
        PhpArray result = new PhpArray();
        for (int p = array.lastPosition(); p >= 0; p = array.previousPosition(p)) {
            Object key = array.keyAt(p);
            if (key instanceof Long && !preserveKeys) {
                result.append(array.valueAt(p));
            } else {
                result.put(key, array.valueAt(p));
            }
        }
        return result;
    }

    /** php-src's HT_MAX_SIZE on 64-bit platforms: the most elements an array can have. */
    private static final long MAX_ARRAY_SIZE = 0x40000000L;

    /** range() bounds must be finite (php-src 8.3). */
    private static void checkFinite(Object value, int position, String name) {
        if (value instanceof Double d && (d.isInfinite() || d.isNaN())) {
            throw new PhpError("ValueError", "range(): Argument #" + position + " ($" + name
                    + ") must be a finite number, " + (d.isNaN() ? "NAN" : "INF") + " provided");
        }
    }

    @TruffleBoundary
    private static PhpArray range(Object start, Object end, Object stepValue) {
        PhpArray result = new PhpArray();
        if (start instanceof TruffleString s && end instanceof TruffleString e && Str.length(s) >= 1
                && Str.length(e) >= 1 && Convert.numericValue(s) == null) {
            int from = Str.byteAt(s, 0);
            int to = Str.byteAt(e, 0);
            int step = (int) Math.max(1, Math.abs(Convert.toLong(stepValue)));
            for (int c = from; from <= to ? c <= to : c >= to; c += from <= to ? step : -step) {
                result.append(Str.singleByte(c));
            }
            return result;
        }
        Object a = Convert.toNumber(start);
        Object b = Convert.toNumber(end);
        Object stepNumber = Convert.toNumber(stepValue);
        checkFinite(a, 1, "start");
        checkFinite(b, 2, "end");
        if (a instanceof Long x && b instanceof Long y && stepNumber instanceof Long st) {
            long step = Math.max(1, Math.abs(st));
            // php-src's RANGE_CHECK_LONG_INIT_ARRAY: the element count, unsigned, against HT_MAX_SIZE
            long size = Long.divideUnsigned(x <= y ? y - x : x - y, step);
            if (Long.compareUnsigned(size, MAX_ARRAY_SIZE - 1) >= 0) {
                throw new PhpError("ValueError", "The supplied range exceeds the maximum array size by "
                        + Long.toUnsignedString(size - MAX_ARRAY_SIZE + 1) + " elements: start=" + x + ", end=" + y
                        + ", step=" + step + ". Calculated size: " + Long.toUnsignedString(size)
                        + ". Maximum size: " + MAX_ARRAY_SIZE + ".");
            }
            for (long v = x; x <= y ? v <= y : v >= y; v += x <= y ? step : -step) {
                result.append(v);
            }
            return result;
        }
        double x = ((Number) a).doubleValue();
        double y = ((Number) b).doubleValue();
        double step = Math.abs(((Number) stepNumber).doubleValue());
        double size = Math.floor(Math.abs(y - x) / step);
        if (size >= MAX_ARRAY_SIZE - 1) {
            throw new PhpError("ValueError", "The supplied range exceeds the maximum array size by "
                    + String.format("%.0f", size - MAX_ARRAY_SIZE + 1) + " elements: start="
                    + String.format("%.1f", x) + ", end=" + String.format("%.1f", y) + ", step="
                    + String.format("%.1f", step) + ". Calculated size: " + String.format("%.0f", size)
                    + ". Maximum size: " + MAX_ARRAY_SIZE + ".");
        }
        long count = (long) size;
        for (long i = 0; i <= count; i++) {
            result.append(x <= y ? x + i * step : x - i * step);
        }
        return result;
    }

    /** A bucket being sorted: its key, raw value and original position (Z_EXTRA, for the stable fallback). */
    private record Sorted(Object key, Object value, int order) {
    }

    /**
     * sort, rsort, asort, arsort, ksort, krsort and the u* variants: zend_hash_sort with php_get_data_compare_func's
     * or php_get_key_compare_func's comparison, or the callback's (php_array_user_compare), ties in their original
     * order (RETURN_STABLE_SORT), through zend_sort so the comparisons happen in php-src's order.
     */
    @TruffleBoundary
    private static Object sort(PhpContext context, Object[] a, boolean user, boolean byKey, boolean reverse,
                               boolean keepKeys) {
        String name = user ? (byKey ? "uksort" : keepKeys ? "uasort" : "usort")
                : (reverse ? (byKey ? "krsort" : keepKeys ? "arsort" : "rsort")
                : (byKey ? "ksort" : keepKeys ? "asort" : "sort"));
        Args.arity(a, name, user ? 2 : 1, 2);
        PhpRef ref = Args.ref(a, 0);
        if (!(ref.get() instanceof PhpArray array)) {
            throw Args.typeError(name, 1, "array", "array", ref.get());
        }
        Comparator<Sorted> compare;
        if (user) {
            Callables.Target callback = Callables.parameter(context, Args.get(a, 1),
                    new Callables.Parameter(name, 2, false));
            if (array.size() == 0) {
                return true;
            }
            boolean[] deprecated = {false};
            compare = (x, y) -> {
                Object first = byKey ? x.key() : PhpRef.deref(x.value());
                Object second = byKey ? y.key() : PhpRef.deref(y.value());
                Object result = PhpRef.deref(callback.call(first, second));
                if (result instanceof Boolean b) {
                    if (!deprecated[0]) {
                        deprecated[0] = true;
                        ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of(name + "(): Returning bool "
                                + "from comparison function is deprecated, return an integer less than, equal to, "
                                + "or greater than zero"), SourceLocation.current());
                    }
                    if (!b) {
                        // retried with the operands swapped
                        return -Long.signum(Convert.toLong(PhpRef.deref(callback.call(second, first))));
                    }
                }
                return Long.signum(Convert.toLong(result));
            };
        } else {
            long flags = Args.has(a, 1) ? Args.integer(context, a, 1, name, "flags", "int") : 0;
            Comparator<Sorted> base = byKey ? keyCompare(flags) : dataCompare(flags);
            compare = reverse ? (x, y) -> -base.compare(x, y) : base;
        }
        Comparator<Sorted> unstable = compare;
        Comparator<Sorted> stable = (x, y) -> {
            int result = unstable.compare(x, y);
            return result != 0 ? result : Integer.compare(x.order(), y.order());
        };
        Sorted[] buckets = new Sorted[array.size()];
        int n = 0;
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            buckets[n] = new Sorted(array.keyAt(p), array.rawAt(p), n);
            n++;
        }
        zendSort(buckets, 0, n, stable);
        PhpArray result = PhpArray.ofCapacity(n);
        for (Sorted bucket : buckets) {
            Object key = keepKeys ? bucket.key() : (Object) result.nextIndex();
            if (bucket.value() instanceof PhpRef r) {
                result.putRef(key, r);
            } else {
                result.put(key, bucket.value());
            }
        }
        ref.set(result);
        return true;
    }

    /** php_get_data_compare_func's comparisons (unstable, ascending). */
    private static Comparator<Sorted> dataCompare(long flags) {
        boolean fold = (flags & 8) != 0;
        return switch ((int) (flags & ~8L)) {
            case 1 -> (x, y) -> threeWay(Convert.toDouble(PhpRef.deref(x.value())),
                    Convert.toDouble(PhpRef.deref(y.value())));
            case 2, 5 -> (x, y) -> stringCompare(Convert.toStr(PhpRef.deref(x.value())),
                    Convert.toStr(PhpRef.deref(y.value())), fold && (flags & 7) == 2);
            case 6 -> (x, y) -> StringFunctions.naturalCompare(Str.bytes(Convert.toStr(PhpRef.deref(x.value()))),
                    Str.bytes(Convert.toStr(PhpRef.deref(y.value()))), fold);
            default -> (x, y) -> Convert.compare(PhpRef.deref(x.value()), PhpRef.deref(y.value()));
        };
    }

    /** php_get_key_compare_func's comparisons (unstable, ascending). */
    private static Comparator<Sorted> keyCompare(long flags) {
        boolean fold = (flags & 8) != 0;
        return switch ((int) (flags & ~8L)) {
            case 1 -> (x, y) -> x.key() instanceof Long l && y.key() instanceof Long m ? (l > m ? 1 : -1)
                    : threeWay(keyDouble(x.key()), keyDouble(y.key()));
            case 2, 5 -> (x, y) -> stringCompare(Convert.toStr(x.key()), Convert.toStr(y.key()), fold && (flags & 7) == 2);
            case 6 -> (x, y) -> StringFunctions.naturalCompare(Str.bytes(Convert.toStr(x.key())),
                    Str.bytes(Convert.toStr(y.key())), fold);
            default -> (x, y) -> x.key() instanceof Long l && y.key() instanceof Long m ? (l > m ? 1 : -1)
                    : Convert.compare(x.key(), y.key());
        };
    }

    /** zend_strtod of a string key, an int key as a double. */
    private static double keyDouble(Object key) {
        return key instanceof Long l ? (double) l : Convert.toDouble(Convert.parseNumber(Str.readBytes(
                (TruffleString) key), true) instanceof Object number ? number : 0L);
    }

    /** ZEND_THREEWAY_COMPARE. */
    private static int threeWay(double a, double b) {
        return a == b ? 0 : a < b ? -1 : 1;
    }

    /** zend_binary_strcmp, or zend_binary_strcasecmp_l (ASCII case folded, the C locale's). */
    private static int stringCompare(TruffleString a, TruffleString b, boolean fold) {
        byte[] x = Str.readBytes(a);
        byte[] y = Str.readBytes(b);
        int lx = Str.length(a);
        int ly = Str.length(b);
        for (int i = 0; i < Math.min(lx, ly); i++) {
            int c = x[i] & 0xFF;
            int d = y[i] & 0xFF;
            if (fold) {
                c = c >= 'A' && c <= 'Z' ? c + 32 : c;
                d = d >= 'A' && d <= 'Z' ? d + 32 : d;
            }
            if (c != d) {
                return c - d;
            }
        }
        return Integer.compare(lx, ly);
    }

    // ---- zend_sort (Zend/zend_sort.c): insertion sort up to 16 elements, else a pivoted partition ----

    private static <T> void swap(T[] a, int i, int j) {
        T t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private static <T> void sort3(T[] a, int i, int j, int k, Comparator<T> cmp) {
        if (!(cmp.compare(a[i], a[j]) > 0)) {
            if (!(cmp.compare(a[j], a[k]) > 0)) {
                return;
            }
            swap(a, j, k);
            if (cmp.compare(a[i], a[j]) > 0) {
                swap(a, i, j);
            }
            return;
        }
        if (!(cmp.compare(a[k], a[j]) > 0)) {
            swap(a, i, k);
            return;
        }
        swap(a, i, j);
        if (cmp.compare(a[j], a[k]) > 0) {
            swap(a, j, k);
        }
    }

    private static <T> void sort4(T[] a, int i, int j, int k, int l, Comparator<T> cmp) {
        sort3(a, i, j, k, cmp);
        if (cmp.compare(a[k], a[l]) > 0) {
            swap(a, k, l);
            if (cmp.compare(a[j], a[k]) > 0) {
                swap(a, j, k);
                if (cmp.compare(a[i], a[j]) > 0) {
                    swap(a, i, j);
                }
            }
        }
    }

    private static <T> void sort5(T[] a, int i, int j, int k, int l, int m, Comparator<T> cmp) {
        sort4(a, i, j, k, l, cmp);
        if (cmp.compare(a[l], a[m]) > 0) {
            swap(a, l, m);
            if (cmp.compare(a[k], a[l]) > 0) {
                swap(a, k, l);
                if (cmp.compare(a[j], a[k]) > 0) {
                    swap(a, j, k);
                    if (cmp.compare(a[i], a[j]) > 0) {
                        swap(a, i, j);
                    }
                }
            }
        }
    }

    /** zend_insert_sort; elements move by successive swaps, as php-src's swp calls. */
    private static <T> void insertSort(T[] a, int start, int n, Comparator<T> cmp) {
        switch (n) {
            case 0, 1 -> {
            }
            case 2 -> {
                if (cmp.compare(a[start], a[start + 1]) > 0) {
                    swap(a, start, start + 1);
                }
            }
            case 3 -> sort3(a, start, start + 1, start + 2, cmp);
            case 4 -> sort4(a, start, start + 1, start + 2, start + 3, cmp);
            case 5 -> sort5(a, start, start + 1, start + 2, start + 3, start + 4, cmp);
            default -> {
                int end = start + n;
                int sentry = start + 6;
                for (int i = start + 1; i < sentry; i++) {
                    int j = i - 1;
                    if (!(cmp.compare(a[j], a[i]) > 0)) {
                        continue;
                    }
                    while (j != start) {
                        j--;
                        if (!(cmp.compare(a[j], a[i]) > 0)) {
                            j++;
                            break;
                        }
                    }
                    for (int k = i; k > j; k--) {
                        swap(a, k, k - 1);
                    }
                }
                for (int i = sentry; i < end; i++) {
                    int j = i - 1;
                    if (!(cmp.compare(a[j], a[i]) > 0)) {
                        continue;
                    }
                    while (true) {
                        j -= 2;
                        if (!(cmp.compare(a[j], a[i]) > 0)) {
                            j++;
                            if (!(cmp.compare(a[j], a[i]) > 0)) {
                                j++;
                            }
                            break;
                        }
                        if (j == start) {
                            break;
                        }
                        if (j == start + 1) {
                            j--;
                            if (cmp.compare(a[i], a[j]) > 0) {
                                j++;
                            }
                            break;
                        }
                    }
                    for (int k = i; k > j; k--) {
                        swap(a, k, k - 1);
                    }
                }
            }
        }
    }

    /** zend_sort. */
    private static <T> void zendSort(T[] a, int start, int n, Comparator<T> cmp) {
        while (true) {
            if (n <= 16) {
                insertSort(a, start, n, cmp);
                return;
            }
            int end = start + n;
            int offset = n >> 1;
            int pivot = start + offset;
            if ((n >> 10) != 0) {
                int delta = offset >> 1;
                sort5(a, start, start + delta, pivot, pivot + delta, end - 1, cmp);
            } else {
                sort3(a, start, pivot, end - 1, cmp);
            }
            swap(a, start + 1, pivot);
            pivot = start + 1;
            int i = pivot + 1;
            int j = end - 1;
            partition:
            while (true) {
                while (cmp.compare(a[pivot], a[i]) > 0) {
                    i++;
                    if (i == j) {
                        break partition;
                    }
                }
                j--;
                if (j == i) {
                    break;
                }
                while (cmp.compare(a[j], a[pivot]) > 0) {
                    j--;
                    if (j == i) {
                        break partition;
                    }
                }
                swap(a, i, j);
                i++;
                if (i == j) {
                    break;
                }
            }
            swap(a, pivot, i - 1);
            if ((i - 1) - start < end - i) {
                zendSort(a, start, i - start - 1, cmp);
                n = end - i;
                start = i;
            } else {
                zendSort(a, i, end - i, cmp);
                n = i - start - 1;
            }
        }
    }

    private static Object current(Object value) {
        if (!(value instanceof PhpArray array)) {
            return Boolean.FALSE;
        }
        int p = array.pointer();
        return array.isValidPosition(p) ? array.valueAt(p) : Boolean.FALSE;
    }

    /** next (1), prev (-1), reset (0), end (2): moves the internal pointer, returns the new current value. */
    @TruffleBoundary
    private static Object move(PhpRef ref, int how) {
        if (!(ref.get() instanceof PhpArray)) {
            return Boolean.FALSE;
        }
        // the pointer is part of the array: separate it first, as php-src does for these by-reference functions
        PhpArray array = ref.arrayForWrite();
        int p = array.pointer();
        switch (how) {
            case 1 -> p = array.isValidPosition(p) ? array.nextPosition(p) : -1;
            case -1 -> p = array.isValidPosition(p) ? array.previousPosition(p) : -1;
            case 0 -> p = array.firstPosition();
            default -> p = array.lastPosition();
        }
        array.setPointer(p);
        return array.isValidPosition(p) ? array.valueAt(p) : Boolean.FALSE;
    }
}
