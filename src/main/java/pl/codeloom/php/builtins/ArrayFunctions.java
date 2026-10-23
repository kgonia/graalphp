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
        r.fn("array_map", (context, a) -> map(context, a));
        r.fn("array_filter", (context, a) -> filter(context, Args.array(a, 0, "array_filter"), Args.get(a, 1),
                Args.lng(a, 2, 0)));
        r.fn("array_key_exists", a -> keyExists(Args.get(a, 0), Args.get(a, 1)));
        r.fn("key_exists", a -> keyExists(Args.get(a, 0), Args.get(a, 1)));
        r.fn("in_array", a -> search(Args.get(a, 0), Args.array(a, 1, "in_array"), Args.bool(a, 2, false)) != null);
        r.fn("array_search", a -> {
            Object key = search(Args.get(a, 0), Args.array(a, 1, "array_search"), Args.bool(a, 2, false));
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
            PhpArray keys = Args.array(a, 0, "array_fill_keys");
            PhpArray result = new PhpArray();
            for (int p = keys.firstPosition(); p >= 0; p = keys.nextPosition(p)) {
                result.put(ArrayKey.of(keys.valueAt(p)), Args.get(a, 1));
            }
            return result;
        });
        r.fn("array_column", a -> column(Args.array(a, 0, "array_column"), Args.get(a, 1), Args.get(a, 2)));
        r.fn("array_pop", "1", a -> pop(Args.ref(a, 0)));
        r.fn("array_push", "1", a -> {
            PhpArray array = writable(Args.ref(a, 0));
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
                result.put(ArrayKey.of(array.valueAt(p)), array.keyAt(p));
            }
            return result;
        });
        r.fn("array_reverse", a -> reverse(Args.array(a, 0, "array_reverse"), Args.bool(a, 1, false)));
        r.fn("array_combine", a -> {
            PhpArray keys = Args.array(a, 0, "array_combine");
            PhpArray values = Args.array(a, 1, "array_combine");
            if (keys.size() != values.size()) {
                throw new PhpError("ValueError: array_combine(): Argument #1 ($keys) and argument #2 ($values) "
                        + "must have the same number of elements");
            }
            PhpArray result = new PhpArray();
            int q = values.firstPosition();
            for (int p = keys.firstPosition(); p >= 0; p = keys.nextPosition(p)) {
                result.put(ArrayKey.of(keys.valueAt(p)), values.valueAt(q));
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
                total = pl.codeloom.php.runtime.Operators.add(total, Convert.toNumber(array.valueAt(p)));
            }
            return total;
        });
        r.fn("array_count_values", a -> {
            PhpArray array = Args.array(a, 0, "array_count_values");
            PhpArray result = new PhpArray();
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object key = ArrayKey.of(array.valueAt(p));
                Object current = result.get(key);
                result.put(key, current == null ? 1L : (Long) current + 1);
            }
            return result;
        });
        r.fn("array_walk", "1", (context, a) -> {
            PhpArray array = writable(Args.ref(a, 0));
            Callables.check(context, Args.get(a, 1), new Callables.Parameter("array_walk", 2, false));
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                if (Args.has(a, 2)) {
                    Callables.call(context, Args.get(a, 1), array.refAtPosition(p), array.keyAt(p), Args.get(a, 2));
                } else {
                    Callables.call(context, Args.get(a, 1), array.refAtPosition(p), array.keyAt(p));
                }
            }
            return true;
        });
        r.fn("range", a -> range(Args.get(a, 0), Args.get(a, 1), Args.get(a, 2, 1L)));
        r.fn("array_reduce", (context, a) -> {
            PhpArray array = Args.array(a, 0, "array_reduce");
            Callables.check(context, Args.get(a, 1), new Callables.Parameter("array_reduce", 2, false));
            Object carry = Args.get(a, 2, PhpNull.NULL);
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                carry = Callables.call(context, Args.get(a, 1), carry, array.valueAt(p));
            }
            return carry;
        });
        r.fn("array_walk_recursive", "1", (context, a) -> {
            PhpArray array = writable(Args.ref(a, 0));
            Callables.check(context, Args.get(a, 1), new Callables.Parameter("array_walk_recursive", 2, false));
            walkRecursive(context, array, Args.get(a, 1), Args.has(a, 2) ? Args.get(a, 2) : null);
            return true;
        });
        r.fn("array_replace", a -> replace(a, false));
        r.fn("array_replace_recursive", a -> replace(a, true));
        r.fn("array_merge_recursive", a -> {
            PhpArray result = new PhpArray();
            for (int i = 0; i < Args.count(a); i++) {
                mergeRecursive(result, Args.array(a, i, "array_merge_recursive"));
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
                product = Operators.mul(product, Convert.toNumber(array.valueAt(p)));
            }
            return product;
        });
        r.fn("array_diff_assoc", a -> diffAssoc(a, true));
        r.fn("array_intersect_assoc", a -> diffAssoc(a, false));
        r.fn("array_rand", a -> rand(Args.array(a, 0, "array_rand"), Args.lng(a, 1, 1)));
        r.fn("shuffle", "1", a -> {
            PhpRef ref = Args.ref(a, 0);
            List<Object> values = new ArrayList<>();
            PhpArray array = writable(ref);
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                values.add(array.valueAt(p));
            }
            Collections.shuffle(values);
            ref.set(PhpArray.list(values.toArray()));
            return true;
        });
        r.fn("iterator_to_array", a -> iteratorToArray(Args.get(a, 0), Args.bool(a, 1, true)));

        // ---- sorting ----
        r.fn("sort", "1", (context, a) -> sort(context, Args.ref(a, 0), null, false, false, false));
        r.fn("rsort", "1", (context, a) -> sort(context, Args.ref(a, 0), null, false, true, false));
        r.fn("usort", "1", (context, a) -> sort(context, Args.ref(a, 0), Args.get(a, 1), false, false, false));
        r.fn("asort", "1", (context, a) -> sort(context, Args.ref(a, 0), null, false, false, true));
        r.fn("arsort", "1", (context, a) -> sort(context, Args.ref(a, 0), null, false, true, true));
        r.fn("uasort", "1", (context, a) -> sort(context, Args.ref(a, 0), Args.get(a, 1), false, false, true));
        r.fn("ksort", "1", (context, a) -> sort(context, Args.ref(a, 0), null, true, false, true));
        r.fn("krsort", "1", (context, a) -> sort(context, Args.ref(a, 0), null, true, true, true));
        r.fn("uksort", "1", (context, a) -> sort(context, Args.ref(a, 0), Args.get(a, 1), true, false, true));

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
    private static void walkRecursive(PhpContext context, PhpArray array, Object callback, Object extra) {
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            PhpRef element = array.refAtPosition(p);
            if (element.get() instanceof PhpArray) {
                walkRecursive(context, element.arrayForWrite(), callback, extra);
            } else if (extra != null) {
                Callables.call(context, callback, element, array.keyAt(p), extra);
            } else {
                Callables.call(context, callback, element, array.keyAt(p));
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
                target.append(value);
            } else if (current == null) {
                target.put(key, value);
            } else {
                PhpArray merged = current instanceof PhpArray existing ? existing.copy() : PhpArray.list(current);
                if (value instanceof PhpArray nested) {
                    mergeRecursive(merged, nested);
                } else {
                    merged.append(value);
                }
                target.put(key, merged);
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
            throw new PhpError("ValueError: array_chunk(): Argument #2 ($length) must be greater than 0");
        }
        PhpArray result = new PhpArray();
        PhpArray chunk = null;
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            if (chunk == null || chunk.size() == length) {
                chunk = new PhpArray();
                result.append(chunk);
            }
            if (preserveKeys) {
                chunk.put(array.keyAt(p), array.valueAt(p));
            } else {
                chunk.append(array.valueAt(p));
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
                result.put(key, first.valueAt(p));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static Object rand(PhpArray array, long count) {
        if (array.size() == 0) {
            throw new PhpError("ValueError: array_rand(): Argument #1 ($array) cannot be empty");
        }
        if (count < 1 || count > array.size()) {
            throw new PhpError("ValueError: array_rand(): Argument #2 ($num) must be between 1 and the number of "
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
            throw new PhpError("TypeError: iterator_to_array(): Argument #1 ($iterator) must be of type "
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

    private static PhpArray writable(PhpRef ref) {
        if (!(ref.get() instanceof PhpArray)) {
            throw new PhpError("TypeError: Argument #1 ($array) must be of type array");
        }
        return ref.arrayForWrite();
    }

    private static void appendAll(PhpArray target, PhpArray source) {
        for (int p = source.firstPosition(); p >= 0; p = source.nextPosition(p)) {
            target.append(source.valueAt(p));
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

    @TruffleBoundary
    private static PhpArray merge(Object[] a) {
        PhpArray result = new PhpArray();
        for (int i = 0; i < Args.count(a); i++) {
            PhpArray array = Args.array(a, i, "array_merge");
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object key = array.keyAt(p);
                if (key instanceof Long) {
                    result.append(array.valueAt(p));
                } else {
                    result.put(key, array.valueAt(p));
                }
            }
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray map(PhpContext context, Object[] a) {
        Object callback = Args.get(a, 0);
        if (callback != PhpNull.NULL) {
            Callables.check(context, callback, new Callables.Parameter("array_map", 1, true));
        }
        int arrays = Args.count(a) - 1;
        if (arrays == 1) {
            PhpArray array = Args.array(a, 1, "array_map");
            PhpArray result = PhpArray.ofCapacity(array.size());
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object value = callback == PhpNull.NULL ? array.valueAt(p)
                        : Callables.call(context, callback, array.valueAt(p));
                result.put(array.keyAt(p), value);
            }
            return result;
        }
        PhpArray[] inputs = new PhpArray[arrays];
        int[] positions = new int[arrays];
        int longest = 0;
        for (int i = 0; i < arrays; i++) {
            inputs[i] = Args.array(a, i + 1, "array_map");
            positions[i] = inputs[i].firstPosition();
            longest = Math.max(longest, inputs[i].size());
        }
        PhpArray result = new PhpArray();
        for (int n = 0; n < longest; n++) {
            Object[] values = new Object[arrays];
            for (int i = 0; i < arrays; i++) {
                if (positions[i] >= 0) {
                    values[i] = inputs[i].valueAt(positions[i]);
                    positions[i] = inputs[i].nextPosition(positions[i]);
                } else {
                    values[i] = PhpNull.NULL;
                }
            }
            result.append(callback == PhpNull.NULL ? PhpArray.list(values) : Callables.call(context, callback, values));
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray filter(PhpContext context, PhpArray array, Object callback, long mode) {
        if (callback != null && callback != PhpNull.NULL) {
            Callables.check(context, callback, new Callables.Parameter("array_filter", 2, true));
        }
        PhpArray result = new PhpArray();
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            Object key = array.keyAt(p);
            Object value = array.valueAt(p);
            boolean keep;
            if (callback == null || callback == PhpNull.NULL) {
                keep = Convert.toBool(value);
            } else if (mode == 2) {
                keep = Convert.toBool(Callables.call(context, callback, key));
            } else if (mode == 1) {
                keep = Convert.toBool(Callables.call(context, callback, value, key));
            } else {
                keep = Convert.toBool(Callables.call(context, callback, value));
            }
            if (keep) {
                result.put(key, value);
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
            return object.getProperty(Str.java(Convert.toStr(key))) != null;
        }
        throw new PhpError("TypeError: array_key_exists(): Argument #2 ($array) must be of type array");
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
                result.append(array.valueAt(p));
            } else {
                result.put(key, array.valueAt(p));
            }
        }
        return result;
    }

    @TruffleBoundary
    private static PhpArray splice(Object[] a) {
        PhpRef ref = Args.ref(a, 0);
        PhpArray array = writable(ref);
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
                result.put(array.keyAt(p), value);
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
    private static PhpArray column(PhpArray rows, Object columnKey, Object indexKey) {
        PhpArray result = new PhpArray();
        for (int p = rows.firstPosition(); p >= 0; p = rows.nextPosition(p)) {
            Object row = rows.valueAt(p);
            Object value;
            if (columnKey == null || columnKey == PhpNull.NULL) {
                value = row;
            } else {
                value = field(row, columnKey);
                if (value == null) {
                    continue;
                }
            }
            Object index = indexKey == null || indexKey == PhpNull.NULL ? null : field(row, indexKey);
            if (index == null) {
                result.append(value);
            } else {
                result.put(ArrayKey.of(index), value);
            }
        }
        return result;
    }

    private static Object field(Object row, Object key) {
        if (row instanceof PhpArray array) {
            return array.get(ArrayKey.of(key));
        }
        if (row instanceof PhpObject object) {
            return PhpRef.deref(object.getProperty(Str.java(Convert.toStr(key))));
        }
        return null;
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
        PhpArray array = writable(ref);
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
    private static Object anyAll(PhpContext context, PhpArray array, Object callback, boolean any) {
        Callables.check(context, callback, new Callables.Parameter(any ? "array_any" : "array_all", 2, false));
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            boolean match = Convert.toBool(Callables.call(context, callback, array.valueAt(p), array.keyAt(p)));
            if (match == any) {
                return any;
            }
        }
        return !any;
    }

    @TruffleBoundary
    private static Object find(PhpContext context, PhpArray array, Object callback, boolean wantKey) {
        Callables.check(context, callback,
                new Callables.Parameter(wantKey ? "array_find_key" : "array_find", 2, false));
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            if (Convert.toBool(Callables.call(context, callback, array.valueAt(p), array.keyAt(p)))) {
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
            throw new PhpError("ValueError: range(): Argument #" + position + " ($" + name
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
                throw new PhpError("ValueError: The supplied range exceeds the maximum array size by "
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
            throw new PhpError("ValueError: The supplied range exceeds the maximum array size by "
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

    @TruffleBoundary
    private static Object sort(PhpContext context, PhpRef ref, Object callback, boolean byKey, boolean reverse,
                               boolean keepKeys) {
        String name = callback != null ? (byKey ? "uksort" : keepKeys ? "uasort" : "usort")
                : (reverse ? (byKey ? "krsort" : keepKeys ? "arsort" : "rsort")
                : (byKey ? "ksort" : keepKeys ? "asort" : "sort"));
        if (!(ref.get() instanceof PhpArray array)) {
            throw new PhpError("TypeError: " + name + "(): Argument #1 ($array) must be of type array, "
                    + Convert.debugType(ref.get()) + " given");
        }
        List<Entry> entries = new ArrayList<>(array.size());
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            entries.add(new Entry(array.keyAt(p), array.rawAt(p)));
        }
        Comparator<Entry> order;
        if (callback != null) {
            Callables.check(context, callback,
                    new Callables.Parameter(name, 2, false));
            order = (x, y) -> (int) Math.signum(Convert.toDouble(Convert.toNumber(Callables.call(context, callback,
                    byKey ? x.key() : PhpRef.deref(x.value()), byKey ? y.key() : PhpRef.deref(y.value())))));
        } else if (byKey) {
            order = (x, y) -> Convert.compare(x.key(), y.key());
        } else {
            order = (x, y) -> Convert.compare(PhpRef.deref(x.value()), PhpRef.deref(y.value()));
        }
        if (reverse) {
            order = order.reversed();
        }
        entries.sort(order);
        PhpArray result = PhpArray.ofCapacity(entries.size());
        for (Entry entry : entries) {
            Object key = keepKeys ? entry.key() : (Object) result.nextIndex();
            if (entry.value() instanceof PhpRef r) {
                result.putRef(key, r);
            } else {
                result.put(key, entry.value());
            }
        }
        ref.set(result);
        return true;
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
