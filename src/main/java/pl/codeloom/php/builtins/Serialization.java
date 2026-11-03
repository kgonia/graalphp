package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * serialize() and unserialize(), as php-src's ext/standard/var.c (php_var_serialize_intern and the functions
 * around it) and var_unserializer.re. Nested arrays and objects are walked on explicit stacks, not the Java one, so
 * the nesting php-src takes (unserialize_max_depth, 4096 by default) fits any thread.
 */
final class Serialization {
    private Serialization() {
    }

    /** __PHP_Incomplete_Class's property holding the missing class's name (MAGIC_MEMBER). */
    static final String MAGIC_MEMBER = "__PHP_Incomplete_Class_Name";
    private static final String INCOMPLETE_CLASS = "__PHP_Incomplete_Class";

    /**
     * BG(serialize_lock), BG(serialize) and BG(unserialize): a serialize() or unserialize() called while another
     * runs (from Serializable::serialize() or ::unserialize()) shares its numbering of values, unless a magic
     * method (__serialize, __sleep, __unserialize, __wakeup) or an autoloader is running: those calls lock it.
     */
    static final class State {
        int lock;
        SerializeData serializeData;
        int serializeLevel;
        UnserializeData unserializeData;
        int unserializeLevel;
    }

    private static State state(PhpContext context) {
        return context.builtinState().serialization;
    }

    private static void warning(PhpContext context, String message) {
        warning(context, Str.of(message));
    }

    private static void warning(PhpContext context, TruffleString message) {
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, message, SourceLocation.current());
    }

    /** A class name as a C string prints it (%s): up to its first NUL, as an anonymous class's name. */
    private static String printed(String name) {
        int nul = name.indexOf('\0');
        return nul < 0 ? name : name.substring(0, nul);
    }

    // ---- serialize ----

    /**
     * php_serialize_data: the values that may be met again (objects, and references something else also holds),
     * by identity, with the number serialize gave them, -1 for an object whose Serializable::serialize() returned
     * null; n counts every value written. A reference to an object counts as the object.
     */
    static final class SerializeData {
        /** Made when the first object or shared reference is met. */
        IdentityHashMap<Object, Long> seen;
        long n;
    }

    /** php_var_serialize_init. */
    private static SerializeData serializeInit(State state) {
        if (state.lock > 0 || state.serializeLevel == 0) {
            SerializeData data = new SerializeData();
            if (state.lock == 0) {
                state.serializeData = data;
                state.serializeLevel = 1;
            }
            return data;
        }
        state.serializeLevel++;
        return state.serializeData;
    }

    /** php_var_serialize_destroy. */
    private static void serializeDestroy(State state) {
        if (state.lock == 0 && --state.serializeLevel == 0) {
            state.serializeData = null;
        }
    }

    @TruffleBoundary
    static TruffleString serialize(PhpContext context, Object value) {
        State state = state(context);
        SerializeData data = serializeInit(state);
        Serializer serializer = new Serializer(context, state, data);
        try {
            serializer.run(PhpRef.deref(value));
        } finally {
            serializer.release();
            serializeDestroy(state);
        }
        return Str.of(serializer.out.toByteArray());
    }

    /** An array or object being written: its keys and (raw) values, the closing brace pending. */
    private static final class Frame {
        /** An array walked in place, held for the walk (a write to it from a magic method copies it). */
        final PhpArray array;
        int position;
        /** Otherwise the entries: key, value, key, value... (int or string keys) */
        final Object[] entries;
        int next;
        /** An incomplete class's object: its first MAGIC_MEMBER property isn't written. */
        boolean skipClassName;

        Frame(PhpArray array) {
            this.array = array;
            this.position = array.firstPosition();
            this.entries = null;
        }

        Frame(Object[] entries, boolean skipClassName) {
            this.array = null;
            this.entries = entries;
            this.skipClassName = skipClassName;
        }
    }

    private static final class Serializer {
        final PhpContext context;
        final State state;
        final SerializeData data;
        final ByteBuilder out = new ByteBuilder();
        final ArrayDeque<Frame> open = new ArrayDeque<>();
        final long precision;

        Serializer(PhpContext context, State state, SerializeData data) {
            this.context = context;
            this.state = state;
            this.data = data;
            this.precision = context.builtinState().ini().serializePrecision();
        }

        /** php_var_serialize_intern of the root, and of everything nested in it. */
        void run(Object root) {
            value(root);
            while (!open.isEmpty()) {
                Frame frame = open.peek();
                if (frame.array != null) {
                    PhpArray array = frame.array;
                    int position = frame.position;
                    if (position < 0) {
                        close();
                        continue;
                    }
                    frame.position = array.nextPosition(position);
                    key(array.keyAt(position));
                    Object element = unshared(array.rawAt(position));
                    if (element == array) {
                        // the array itself (through a reference only it holds): its own number, then null
                        data.n++;
                        out.writeAscii("N;");
                    } else {
                        value(element);
                    }
                } else {
                    Object[] entries = frame.entries;
                    if (frame.next >= entries.length) {
                        close();
                        continue;
                    }
                    Object key = entries[frame.next++];
                    Object element = entries[frame.next++];
                    if (frame.skipClassName && key instanceof TruffleString name
                            && MAGIC_MEMBER.equals(Str.key(name))) {
                        frame.skipClassName = false;
                        continue;
                    }
                    key(key);
                    value(unshared(element));
                }
            }
        }

        private void close() {
            Frame frame = open.pop();
            if (frame.array != null) {
                frame.array.removeHolder();
            }
            out.write('}');
        }

        /** Lets go of the arrays still walked when an exception ends the walk. */
        void release() {
            while (!open.isEmpty()) {
                Frame frame = open.pop();
                if (frame.array != null) {
                    frame.array.removeHolder();
                }
            }
        }

        /** A reference nothing else holds is written as its value (Z_REFCOUNT_P(data) == 1). */
        private static Object unshared(Object element) {
            return element instanceof PhpRef ref && !ref.isShared() ? ref.get() : element;
        }

        /**
         * php_add_var_hash: counts the value; an object, or a reference, met before gives its number (0 the first
         * time).
         */
        private long number(Object value) {
            data.n++;
            Object key;
            boolean isRef = value instanceof PhpRef;
            if (isRef) {
                Object referenced = ((PhpRef) value).get();
                key = referenced instanceof PhpObject || referenced instanceof PhpClosure ? referenced : value;
            } else if (value instanceof PhpObject || value instanceof PhpClosure) {
                key = value;
            } else {
                return 0;
            }
            if (data.seen == null) {
                data.seen = new IdentityHashMap<>();
            }
            Long seen = data.seen.get(key);
            if (seen != null) {
                // a reference is counted once
                if (isRef && seen != -1) {
                    data.n--;
                }
                return seen;
            }
            data.seen.put(key, data.n);
            return 0;
        }

        private void key(Object key) {
            if (key instanceof Long index) {
                out.writeAscii("i:");
                out.writeLong(index);
                out.write(';');
            } else {
                string((TruffleString) key);
            }
        }

        private void string(TruffleString s) {
            out.writeAscii("s:");
            out.writeLong(s.byteLength(Str.ENC));
            out.writeAscii(":\"");
            out.write(s);
            out.writeAscii("\";");
        }

        private void className(String name) {
            byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeAscii("O:");
            out.writeLong(bytes.length);
            out.writeAscii(":\"");
            out.write(bytes);
            out.writeAscii("\":");
        }

        private void className(TruffleString name) {
            out.writeAscii("O:");
            out.writeLong(name.byteLength(Str.ENC));
            out.writeAscii(":\"");
            out.write(name);
            out.writeAscii("\":");
        }

        /** Writes a value: scalars at once, an array's or object's header (its elements follow from run). */
        private void value(Object struc) {
            long already = number(struc);
            if (already == -1) {
                // a reference to an object whose serialization failed
                out.writeAscii("N;");
                return;
            }
            if (already > 0) {
                out.writeAscii(struc instanceof PhpRef ? "R:" : "r:");
                out.writeLong(already);
                out.write(';');
                return;
            }
            Object value = PhpRef.deref(struc);
            if (value instanceof Boolean b) {
                out.writeAscii(b ? "b:1;" : "b:0;");
            } else if (value == PhpNull.NULL || value == null) {
                out.writeAscii("N;");
            } else if (value instanceof Long l) {
                out.writeAscii("i:");
                out.writeLong(l);
                out.write(';');
            } else if (value instanceof Double d) {
                out.writeAscii("d:");
                out.writeAscii(gcvt(d, (int) precision));
                out.write(';');
            } else if (value instanceof TruffleString s) {
                string(s);
            } else if (value instanceof PhpArray array) {
                out.writeAscii("a:");
                out.writeLong(array.size());
                out.writeAscii(":{");
                array.addHolder();
                open.push(new Frame(array));
            } else if (value instanceof PhpObject object) {
                object(object);
            } else if (value instanceof PhpClosure) {
                throw new PhpError("Exception", "Serialization of 'Closure' is not allowed");
            } else {
                // a resource
                out.writeAscii("i:0;");
            }
        }

        private void object(PhpObject object) {
            PhpClass phpClass = object.getPhpClass();
            if (isNotSerializable(phpClass)) {
                throw new PhpError("Exception", "Serialization of '" + printed(phpClass.getName())
                        + "' is not allowed");
            }
            if (phpClass.findMethod("__serialize") != null) {
                Object result = callLocked("__serialize", object);
                if (!(result instanceof PhpArray array)) {
                    throw new PhpError("TypeError", phpClass.getName() + "::__serialize() must return an array");
                }
                writeClassName(object);
                out.writeLong(array.size());
                out.writeAscii(":{");
                open.push(new Frame(entries(array), false));
                return;
            }
            if (isSerializable(phpClass)) {
                custom(object);
                return;
            }
            boolean incomplete = isIncompleteClass(phpClass);
            if (!incomplete && phpClass.findMethod("__sleep") != null) {
                Object names = callLocked("__sleep", object);
                if (!(names instanceof PhpArray array)) {
                    warning(context, "serialize(): " + phpClass.getName() + "::__sleep() should return an array "
                            + "only containing the names of instance-variables to serialize");
                    out.writeAscii("N;");
                    return;
                }
                Object[] entries = sleepProperties(object, array);
                writeClassName(object);
                out.writeLong(entries.length / 2);
                out.writeAscii(":{");
                open.push(new Frame(entries, false));
                return;
            }
            writeClassName(object);
            Object[] table = object.propertyTable();
            Object[] entries = new Object[table.length];
            for (int i = 0; i < table.length; i += 2) {
                entries[i] = Str.ofKey((String) table[i]);
                entries[i + 1] = table[i + 1];
            }
            long count = entries.length / 2;
            if (count > 0 && incomplete) {
                count--;
            }
            out.writeLong(count);
            out.writeAscii(":{");
            open.push(new Frame(entries, incomplete));
        }

        /**
         * php_var_serialize_class_name: "O:len:"Class":"; an incomplete class's object is written under the name
         * it was read with.
         */
        private void writeClassName(PhpObject object) {
            PhpClass phpClass = object.getPhpClass();
            if (isIncompleteClass(phpClass)) {
                if (PhpRef.deref(object.getProperty(MAGIC_MEMBER)) instanceof TruffleString name) {
                    className(name);
                } else {
                    className(INCOMPLETE_CLASS);
                }
            } else {
                className(phpClass.getName());
            }
        }

        /**
         * The Serializable interface (zend_user_serialize): "C:len:"Class":datalen:{data}" with what serialize()
         * returns; null writes null, and the object's later occurrences are null too.
         */
        private void custom(PhpObject object) {
            PhpClass phpClass = object.getPhpClass();
            Object result = PhpRef.deref(object.callMethod("serialize"));
            if (result instanceof TruffleString payload) {
                byte[] name = phpClass.getName().getBytes(StandardCharsets.UTF_8);
                out.writeAscii("C:");
                out.writeLong(name.length);
                out.writeAscii(":\"");
                out.write(name);
                out.writeAscii("\":");
                out.writeLong(payload.byteLength(Str.ENC));
                out.writeAscii(":{");
                out.write(payload);
                out.write('}');
                return;
            }
            if (result != PhpNull.NULL) {
                throw new PhpError("Exception", phpClass.getName() + "::serialize() must return a string or NULL");
            }
            if (data.seen.containsKey(object)) {
                data.seen.put(object, -1L);
            }
            out.writeAscii("N;");
        }

        /** A magic method, called with serialize locked: a serialize() it calls numbers its values apart. */
        private Object callLocked(String method, PhpObject object) {
            state.lock++;
            try {
                return PhpRef.deref(object.callMethod(method));
            } finally {
                state.lock--;
            }
        }

        /**
         * php_var_serialize_get_sleep_props: the properties __sleep named, in its order, each found under its
         * name, then mangled as the object's class's private property, then as a protected one; as key, value...
         */
        private Object[] sleepProperties(PhpObject object, PhpArray names) {
            PhpClass phpClass = object.getPhpClass();
            Object[] table = object.propertyTable();
            Map<String, Object> properties = new LinkedHashMap<>();
            for (int i = 0; i < table.length; i += 2) {
                properties.putIfAbsent((String) table[i], table[i + 1]);
            }
            Map<String, Object> chosen = new LinkedHashMap<>();
            String privatePrefix = "\0" + PhpClass.nameKey(phpClass.getName()) + "\0";
            for (int p = names.firstPosition(); p >= 0; p = names.nextPosition(p)) {
                Object nameValue = names.valueAt(p);
                if (!(nameValue instanceof TruffleString)) {
                    warning(context, "serialize(): " + phpClass.getName() + "::__sleep() should return an array "
                            + "only containing the names of instance-variables to serialize");
                }
                String name = Str.key(stringOf(nameValue));
                if (addSleepProperty(chosen, properties, name, name)
                        || addSleepProperty(chosen, properties, privatePrefix + name, name)
                        || addSleepProperty(chosen, properties, "\0*\0" + name, name)) {
                    continue;
                }
                warning(context, "serialize(): \"" + printed(Str.keyText(name))
                        + "\" returned as member variable from __sleep() but does not exist");
            }
            Object[] entries = new Object[chosen.size() * 2];
            int i = 0;
            for (Map.Entry<String, Object> entry : chosen.entrySet()) {
                entries[i++] = Str.ofKey(entry.getKey());
                entries[i++] = entry.getValue();
            }
            return entries;
        }

        /** zval_get_tmp_string of a name __sleep returned (an array warns, Convert.arrayToStr). */
        private TruffleString stringOf(Object value) {
            return Convert.toStr(value);
        }

        /** php_var_serialize_try_add_sleep_prop: false if the object has no such property. */
        private boolean addSleepProperty(Map<String, Object> chosen, Map<String, Object> properties, String key,
                                         String name) {
            Object value = properties.get(key);
            if (value == null) {
                return false;
            }
            if (chosen.containsKey(key)) {
                warning(context, "serialize(): \"" + printed(Str.keyText(name))
                        + "\" is returned from __sleep() multiple times");
                return true;
            }
            chosen.put(key, value);
            return true;
        }

        /** An array's entries (raw values): what __serialize returned, walked as it was. */
        private static Object[] entries(PhpArray array) {
            Object[] entries = new Object[array.size() * 2];
            int i = 0;
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                entries[i++] = array.keyAt(p);
                entries[i++] = array.rawAt(p);
            }
            return entries;
        }
    }

    /**
     * zend_gcvt(value, serialize_precision, '.', 'E'): the shortest round-trip digits at -1 (zend_dtoa's mode 0),
     * else that many significant digits (mode 2, at least one). INF and NAN are cut to precision characters, as
     * zend_gcvt's snprintf cuts them.
     */
    static String gcvt(double d, int precision) {
        if (!Double.isFinite(d)) {
            String text = Double.isNaN(d) ? "NAN" : d > 0 ? "INF" : "-INF";
            int limit = precision < 0 ? 17 : precision;
            return text.length() > limit ? text.substring(0, limit) : text;
        }
        if (precision != 0) {
            return Convert.gcvt(d, precision, 'E');
        }
        // zend_dtoa gives one digit (mode 2 with ndigits 0), which zend_gcvt lays out with ndigit 0: the E style
        // from the units digit up
        String digit;
        int decimalPoint;
        if (d == 0) {
            digit = "0";
            decimalPoint = 1;
        } else {
            BigDecimal rounded = new BigDecimal(Math.abs(d)).round(new MathContext(1, RoundingMode.HALF_EVEN));
            digit = rounded.unscaledValue().toString().substring(0, 1);
            decimalPoint = rounded.precision() - rounded.scale();
        }
        String sign = d < 0 || d == 0 && 1 / d < 0 ? "-" : "";
        if (decimalPoint > 0 || decimalPoint < -3) {
            int exponent = decimalPoint - 1;
            return sign + digit + ".0E" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
        }
        return sign + "0." + "0".repeat(-decimalPoint) + digit;
    }

    // ---- unserialize ----

    /** HT_MAX_SIZE (64-bit): no array or object holds more elements. */
    private static final long HT_MAX_SIZE = 0x40000000L;

    /**
     * php_unserialize_data. The slots values were read into, in order (var_push), which R:n and r:n name: a
     * container (an array, an object, or a call's {@link Root}) and the key in it (an array key, a property name);
     * a failed unserialize() forbids its slots (container null) to the calls sharing the data. delayed: the
     * __unserialize() and __wakeup() calls made when the outermost call ends, as object, data (null for
     * __wakeup)...
     */
    static final class UnserializeData {
        Object[] containers = new Object[16];
        Object[] keys = new Object[16];
        int used;
        final ArrayList<Object> delayed = new ArrayList<>();
        /** allowed_classes: null for all, else the allowed names in lower case (as property keys). */
        Set<String> allowedClasses;
        long maxDepth;
        long curDepth;
        /** Whether an R: made a slot a reference: until then no element read is one. */
        boolean refsMade;

        void push(Object container, Object key) {
            if (used == containers.length) {
                containers = Arrays.copyOf(containers, used * 2);
                keys = Arrays.copyOf(keys, used * 2);
            }
            containers[used] = container;
            keys[used++] = key;
        }

        /** php_var_unserialize's failure: the slots from first on can't be referred to any more. */
        void forbid(int first) {
            Arrays.fill(containers, first, used, null);
        }
    }

    /** The slot a call's value is read into (its return_value). */
    private static final class Root {
        Object value = PhpNull.NULL;
    }

    /** php_var_unserialize_init. */
    private static UnserializeData unserializeInit(PhpContext context, State state) {
        if (state.lock > 0 || state.unserializeLevel == 0) {
            UnserializeData data = new UnserializeData();
            data.maxDepth = context.builtinState().ini().unserializeMaxDepth();
            if (state.lock == 0) {
                state.unserializeData = data;
                state.unserializeLevel = 1;
            }
            return data;
        }
        state.unserializeLevel++;
        return state.unserializeData;
    }

    /**
     * php_var_unserialize_destroy: the outermost call (or one of its own, while locked) makes the delayed calls,
     * unless an exception is under way (zend_call_function then calls nothing).
     */
    private static void unserializeDestroy(State state, UnserializeData data, boolean call) {
        if (state.lock == 0) {
            if (--state.unserializeLevel > 0) {
                return;
            }
            state.unserializeData = null;
        }
        if (call) {
            ArrayList<Object> delayed = data.delayed;
            for (int i = 0; i < delayed.size(); i += 2) {
                PhpObject object = (PhpObject) delayed.get(i);
                Object argument = delayed.get(i + 1);
                state.lock++;
                try {
                    if (argument == null) {
                        object.callMethod("__wakeup");
                    } else {
                        object.callMethod("__unserialize", argument);
                    }
                } finally {
                    state.lock--;
                }
            }
        }
    }

    /**
     * php_unserialize_with_options: false for empty data, and for malformed data with a warning saying where; data
     * after the value only warns. The options apply to this call (a nested call without them keeps the outer
     * call's allowed_classes).
     */
    @TruffleBoundary
    static Object unserialize(PhpContext context, TruffleString input, PhpArray options) {
        byte[] buf = Str.readBytes(input);
        if (buf.length == 0) {
            return Boolean.FALSE;
        }
        State state = state(context);
        UnserializeData data = unserializeInit(context, state);
        Set<String> previousAllowed = data.allowedClasses;
        long previousMaxDepth = data.maxDepth;
        long previousCurDepth = data.curDepth;
        boolean completed = false;
        Object result = Boolean.FALSE;
        try {
            if (options != null) {
                applyOptions(data, options);
            }
            Root root = new Root();
            int first = data.used;
            Reader reader = new Reader(context, state, data, buf);
            boolean read;
            try {
                read = reader.read(root);
            } catch (Throwable exception) {
                data.forbid(first);
                throw exception;
            }
            if (!read) {
                data.forbid(first);
                warning(context, "unserialize(): Error at offset " + reader.p + " of " + buf.length + " bytes");
            } else {
                if (reader.p < buf.length) {
                    warning(context, "unserialize(): Extra data starting at offset " + reader.p + " of "
                            + buf.length + " bytes");
                }
                result = root.value;
            }
            completed = true;
        } finally {
            data.allowedClasses = previousAllowed;
            data.maxDepth = previousMaxDepth;
            data.curDepth = previousCurDepth;
            unserializeDestroy(state, data, completed);
        }
        if (result instanceof PhpRef ref) {
            // the value an R: made the root a reference to: returned as a value (zend_unwrap_reference)
            result = ref.get();
            ref.unbind();
        }
        return result;
    }

    /** The allowed_classes and max_depth options, checked as php_unserialize_with_options checks them. */
    private static void applyOptions(UnserializeData data, PhpArray options) {
        Object classes = options.get(ArrayKey.of(Str.of("allowed_classes")));
        if (classes != null && !(classes instanceof PhpArray) && !(classes instanceof Boolean)) {
            throw new PhpError("TypeError", "unserialize(): Option \"allowed_classes\" must be of type array|bool, "
                    + Args.valueName(classes) + " given");
        }
        Set<String> allowed = null;
        if (classes instanceof PhpArray || Boolean.FALSE.equals(classes)) {
            allowed = new HashSet<>();
        }
        if (classes instanceof PhpArray names) {
            for (int p = names.firstPosition(); p >= 0; p = names.nextPosition(p)) {
                Object entry = names.valueAt(p);
                if (!(entry instanceof TruffleString) && !(entry instanceof PhpObject)) {
                    throw new PhpError("TypeError", "unserialize(): Option \"allowed_classes\" must be an array of "
                            + "class names, " + Args.valueName(entry) + " given");
                }
                byte[] name = Str.bytes(Convert.toStr(entry));
                if (!isValidClassName(name, 0, name.length)) {
                    throw new PhpError("ValueError", Str.concat(Str.of("unserialize(): Option \"allowed_classes\" "
                            + "must be an array of class names, \""), Str.concat(cString(name), Str.of("\" given"))));
                }
                allowed.add(lowerKey(name, 0, name.length));
            }
        }
        data.allowedClasses = allowed;
        Object depth = options.get(ArrayKey.of(Str.of("max_depth")));
        if (depth != null) {
            if (!(depth instanceof Long maxDepth)) {
                throw new PhpError("TypeError", "unserialize(): Option \"max_depth\" must be of type int, "
                        + Args.valueName(depth) + " given");
            }
            if (maxDepth < 0) {
                throw new PhpError("ValueError", "unserialize(): Option \"max_depth\" must be greater than or equal "
                        + "to 0");
            }
            data.maxDepth = maxDepth;
            // a nested call with its own max_depth counts from zero
            data.curDepth = 0;
        }
    }

    /** zend_is_valid_class_name: 0-9 a-z A-Z _ \ and the bytes 0x80-0xff. */
    private static boolean isValidClassName(byte[] s, int from, int to) {
        for (int i = from; i < to; i++) {
            int c = s[i] & 0xFF;
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c == '\\'
                    || c >= 0x80)) {
                return false;
            }
        }
        return true;
    }

    /** zend_string_tolower of the bytes, as a property key (ISO-8859-1 chars). */
    private static String lowerKey(byte[] s, int from, int to) {
        char[] chars = new char[to - from];
        for (int i = from; i < to; i++) {
            int c = s[i] & 0xFF;
            chars[i - from] = (char) (c >= 'A' && c <= 'Z' ? c + 32 : c);
        }
        return new String(chars);
    }

    /** The bytes as a C string prints them (%s): up to the first NUL. */
    private static TruffleString cString(byte[] s) {
        int length = 0;
        while (length < s.length && s[length] != 0) {
            length++;
        }
        return Str.of(s, 0, length);
    }

    /** An array or object being read: its elements come as key, value, key, value... */
    private static final class Container {
        static final int ARRAY = 0;
        /** An object's properties (process_nested_object_data). */
        static final int OBJECT = 1;
        /** The data array of an object with __unserialize(). */
        static final int DATA = 2;

        final int kind;
        final PhpArray array;
        final PhpObject object;
        final PhpClass phpClass;
        /** OBJECT: the class has __wakeup(), called once the object is read. */
        final boolean wakeup;
        /** The elements still to read. */
        long elements;
        /** A value was just read into this container: the next element must follow a ';' or '}'. */
        boolean check;

        Container(int kind, PhpArray array, PhpObject object, PhpClass phpClass, boolean wakeup, long elements) {
            this.kind = kind;
            this.array = array;
            this.object = object;
            this.phpClass = phpClass;
            this.wakeup = wakeup;
            this.elements = elements;
        }
    }

    /**
     * var_unserializer.re's scanner and php_var_unserialize_internal. Each rule leaves p where php-src's leaves
     * *p, which the "Error at offset" warning reports. Bytes past the input read as the NUL php-src's strings end
     * with.
     */
    private static final class Reader {
        static final int FAIL = 0;
        static final int OK = 1;
        /** The value is an array or object whose elements follow: a Container was opened for them. */
        static final int OPENED = 2;
        /** What a key that didn't parse reads as. */
        static final Object NO_KEY = new Object();

        final PhpContext context;
        final State state;
        final UnserializeData data;
        final byte[] s;
        final int max;
        int p;
        final ArrayDeque<Container> frames = new ArrayDeque<>();

        Reader(PhpContext context, State state, UnserializeData data, byte[] s) {
            this.context = context;
            this.state = state;
            this.data = data;
            this.s = s;
            this.max = s.length;
        }

        private int at(int i) {
            return i >= 0 && i < max ? s[i] & 0xFF : 0;
        }

        /** php_var_unserialize: the value at p into root, with everything nested in it; false if malformed. */
        boolean read(Root root) {
            int result = value(root, null);
            while (result != FAIL) {
                Container top = frames.peek();
                if (top == null) {
                    return true;
                }
                if (top.check) {
                    top.check = false;
                    if (top.elements > 0 && at(p - 1) != ';' && at(p - 1) != '}') {
                        p--;
                        return false;
                    }
                }
                if (top.elements == 0) {
                    // process_nested_*_data is done, then finish_nested_data
                    data.curDepth--;
                    frames.pop();
                    if (!finishNested()) {
                        return false;
                    }
                    if (top.kind == Container.DATA) {
                        delay(top.object, top.array);
                    } else if (top.wakeup) {
                        delay(top.object, null);
                    }
                    Container parent = frames.peek();
                    if (parent != null) {
                        parent.check = true;
                    }
                    continue;
                }
                top.elements--;
                result = element(top);
                if (result == OK) {
                    top.check = true;
                }
            }
            return false;
        }

        private void delay(PhpObject object, PhpArray arguments) {
            data.delayed.add(object);
            data.delayed.add(arguments);
        }

        private boolean finishNested() {
            if (p >= max || s[p] != '}') {
                return false;
            }
            p++;
            return true;
        }

        /** process_nested_*_data's start: the depth limit. */
        private boolean enterDepth() {
            if (data.maxDepth > 0 && data.curDepth >= data.maxDepth) {
                warning(context, "unserialize(): Maximum depth of " + data.maxDepth + " exceeded. The depth limit "
                        + "can be changed using the max_depth unserialize() option or the unserialize_max_depth ini "
                        + "setting");
                return false;
            }
            data.curDepth++;
            return true;
        }

        /** One element of the container: its key, then its value into the slot the key names. */
        private int element(Container frame) {
            Object key = key();
            if (key == NO_KEY) {
                return FAIL;
            }
            if (frame.kind != Container.OBJECT) {
                if (key instanceof Long) {
                    return value(frame.array, key);
                }
                if (key instanceof TruffleString name) {
                    return value(frame.array, ArrayKey.ofString(name));
                }
                return FAIL;
            }
            TruffleString name;
            if (key instanceof TruffleString string) {
                name = string;
            } else if (key instanceof Long index) {
                // object properties have no int names
                name = Str.of(Long.toString(index));
            } else {
                return FAIL;
            }
            String property = propertyFor(frame.phpClass, Str.key(name));
            return property == null ? FAIL : value(frame.object, property);
        }

        // ---- slots ----

        private void store(Object container, Object key, Object value) {
            if (container instanceof PhpArray array) {
                if (data.refsMade) {
                    array.replace(key, value);
                } else {
                    array.put(key, value);
                }
            } else if (container instanceof PhpObject object) {
                setProperty(object, (String) key, value);
            } else {
                ((Root) container).value = value;
            }
        }

        private static void setProperty(PhpObject object, String name, Object value) {
            Object old = object.getProperty(name);
            if (value instanceof PhpArray array) {
                array.addHolder();
            } else if (value instanceof PhpRef ref) {
                ref.bind();
            }
            object.setProperty(name, value);
            if (old instanceof PhpArray array && old != value) {
                array.removeHolder();
            } else if (old instanceof PhpRef ref) {
                ref.unbind();
            }
        }

        private static Object raw(Object container, Object key) {
            if (container instanceof PhpArray array) {
                return array.getRaw(key);
            }
            if (container instanceof PhpObject object) {
                return object.getProperty((String) key);
            }
            return ((Root) container).value;
        }

        /** ZVAL_NEW_REF on a slot: the reference it holds, made if it holds a value. */
        private static PhpRef makeRef(Object container, Object key) {
            if (container instanceof PhpArray array) {
                return array.refAt(key);
            }
            Object old = raw(container, key);
            if (old instanceof PhpRef ref) {
                return ref;
            }
            PhpRef ref = new PhpRef(old == null ? PhpNull.NULL : old);
            if (old instanceof PhpArray array) {
                array.removeHolder();
            }
            ref.bind();
            if (container instanceof PhpObject object) {
                object.setProperty((String) key, ref);
            } else {
                ((Root) container).value = ref;
            }
            return ref;
        }

        private static void storeRef(Object container, Object key, PhpRef ref) {
            if (container instanceof PhpArray array) {
                array.putRef(key, ref);
            } else if (container instanceof PhpObject object) {
                setProperty(object, (String) key, ref);
            } else {
                ref.bind();
                ((Root) container).value = ref;
            }
        }

        // ---- php_var_unserialize_internal ----

        /** The value at p, read into the slot (pushed first, unless it is an R:). */
        private int value(Object container, Object key) {
            int start = p;
            if (start >= max) {
                return FAIL;
            }
            int type = s[start] & 0xFF;
            if (type != 'R') {
                data.push(container, key);
            }
            switch (type) {
                case 'R':
                case 'r':
                    return reference(container, key, start, type == 'R');
                case 'a':
                    return array(container, key, start);
                case 'O':
                case 'C':
                    return object(container, key, start, type == 'C');
                case 'E':
                    return enumCase(start);
                case '}':
                    unexpectedEnd();
                    return FAIL;
                default: {
                    Object scalar = scalar(start);
                    if (scalar == NO_KEY) {
                        return FAIL;
                    }
                    store(container, key, scalar);
                    return OK;
                }
            }
        }

        /** An array or object key: the rules without var_hash, which fail for anything but a scalar. */
        private Object key() {
            int start = p;
            if (start >= max) {
                return NO_KEY;
            }
            switch (s[start]) {
                case 'R':
                case 'r': {
                    int end = uivToken(start, ';');
                    if (end >= 0) {
                        p = end;
                    }
                    return NO_KEY;
                }
                case 'a': {
                    int end = uivToken(start, ':');
                    if (end >= 0 && at(end) == '{') {
                        parseIv(start + 2);
                        p = end + 1;
                    }
                    return NO_KEY;
                }
                case '}':
                    unexpectedEnd();
                    return NO_KEY;
                default:
                    return scalar(start);
            }
        }

        private void unexpectedEnd() {
            warning(context, "unserialize(): Unexpected end of serialized data");
        }

        /** The end of `X:` uiv followed by the byte after, or -1 if that doesn't match. */
        private int uivToken(int start, char after) {
            if (at(start + 1) != ':') {
                return -1;
            }
            int end = digitsEnd(start + 2);
            return end > start + 2 && at(end) == after ? end + 1 : -1;
        }

        /** The end of `X:` uiv `:"`, or -1 if that doesn't match. */
        private int quotedToken(int start) {
            int end = uivToken(start, ':');
            return end >= 0 && at(end) == '"' ? end + 1 : -1;
        }

        private int digitsEnd(int i) {
            while (at(i) >= '0' && at(i) <= '9') {
                i++;
            }
            return i;
        }

        /** parse_uiv: the digits as a size_t (wrapping as it does). */
        private long parseUiv(int i) {
            long result = 0;
            while (at(i) >= '0' && at(i) <= '9') {
                result = result * 10 + (at(i) - '0');
                i++;
            }
            return result;
        }

        private long parseIv(int i) {
            return parseIv2(i, null);
        }

        /**
         * parse_iv2: an optionally signed decimal, leading zeros skipped; out of range warns and gives the
         * nearest bound. end[0] receives where the digits end.
         */
        private long parseIv2(int i, int[] end) {
            boolean negative = false;
            if (at(i) == '-') {
                negative = true;
                i++;
            } else if (at(i) == '+') {
                i++;
            }
            while (at(i) == '0') {
                i++;
            }
            int start = i;
            long result = 0;
            while (at(i) >= '0' && at(i) <= '9') {
                result = result * 10 + (at(i) - '0');
                i++;
            }
            if (end != null) {
                end[0] = i;
            }
            long limit = negative ? Long.MIN_VALUE : Long.MAX_VALUE;
            if (i - start > 19 || Long.compareUnsigned(result, limit) > 0) {
                warning(context, "unserialize(): Numerical result out of range");
                return negative ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            return negative ? -result : result;
        }

        /** N, b, i, d, s and S; NO_KEY (p unchanged) for anything else, or what doesn't match a rule. */
        private Object scalar(int start) {
            switch (at(start)) {
                case 'N':
                    if (at(start + 1) == ';') {
                        p = start + 2;
                        return PhpNull.NULL;
                    }
                    return NO_KEY;
                case 'b':
                    if (at(start + 1) == ':' && (at(start + 2) == '0' || at(start + 2) == '1')
                            && at(start + 3) == ';') {
                        p = start + 4;
                        return at(start + 2) == '1';
                    }
                    return NO_KEY;
                case 'i': {
                    if (at(start + 1) != ':') {
                        return NO_KEY;
                    }
                    int digits = at(start + 2) == '+' || at(start + 2) == '-' ? start + 3 : start + 2;
                    int end = digitsEnd(digits);
                    if (end == digits || at(end) != ';') {
                        return NO_KEY;
                    }
                    p = end + 1;
                    return parseIv(start + 2);
                }
                case 'd':
                    return floating(start);
                case 's':
                    return string(start);
                case 'S':
                    return escapedString(start);
                default:
                    return NO_KEY;
            }
        }

        /** "d:" ("NAN" | "-"? "INF") ";" and "d:" (iv | nv | nvexp) ";", read by zend_strtod. */
        private Object floating(int start) {
            if (at(start + 1) != ':') {
                return NO_KEY;
            }
            int i = start + 2;
            if (at(i) == 'N' && at(i + 1) == 'A' && at(i + 2) == 'N' && at(i + 3) == ';') {
                p = i + 4;
                return Double.NaN;
            }
            int inf = at(i) == '-' ? i + 1 : i;
            if (at(inf) == 'I' && at(inf + 1) == 'N' && at(inf + 2) == 'F' && at(inf + 3) == ';') {
                p = inf + 4;
                return inf == i ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            }
            int j = at(i) == '+' || at(i) == '-' ? i + 1 : i;
            int intEnd = digitsEnd(j);
            int k = intEnd;
            if (at(k) == '.') {
                int fractionEnd = digitsEnd(k + 1);
                if (intEnd == j && fractionEnd == k + 1) {
                    return NO_KEY;
                }
                k = fractionEnd;
            } else if (intEnd == j) {
                return NO_KEY;
            }
            if (at(k) == 'e' || at(k) == 'E') {
                int e = at(k + 1) == '+' || at(k + 1) == '-' ? k + 2 : k + 1;
                int exponentEnd = digitsEnd(e);
                if (exponentEnd > e) {
                    k = exponentEnd;
                }
            }
            if (at(k) != ';') {
                return NO_KEY;
            }
            p = k + 1;
            return Double.parseDouble(new String(s, i, k - i, StandardCharsets.ISO_8859_1));
        }

        private Object string(int start) {
            int cursor = quotedToken(start);
            if (cursor < 0) {
                return NO_KEY;
            }
            long length = parseUiv(start + 2);
            if (Long.compareUnsigned(max - cursor, length) < 0) {
                p = start + 2;
                return NO_KEY;
            }
            int from = cursor;
            cursor += (int) length;
            if (at(cursor) != '"') {
                p = cursor;
                return NO_KEY;
            }
            if (at(cursor + 1) != ';') {
                p = cursor + 1;
                return NO_KEY;
            }
            p = cursor + 2;
            return Str.of(s, from, (int) length);
        }

        /** "S:": a string whose bytes may be written as \xx (unserialize_str); deprecated. */
        private Object escapedString(int start) {
            int cursor = quotedToken(start);
            if (cursor < 0) {
                return NO_KEY;
            }
            long length = parseUiv(start + 2);
            if (Long.compareUnsigned(max - cursor, length) < 0) {
                p = start + 2;
                return NO_KEY;
            }
            byte[] bytes = new byte[(int) length];
            for (int i = 0; i < length; i++) {
                if (cursor >= max) {
                    return NO_KEY;
                }
                if (s[cursor] != '\\') {
                    bytes[i] = s[cursor];
                } else {
                    int ch = 0;
                    for (int j = 0; j < 2; j++) {
                        cursor++;
                        int digit = Character.digit(at(cursor), 16);
                        if (digit < 0) {
                            return NO_KEY;
                        }
                        ch = (ch << 4) + digit;
                    }
                    bytes[i] = (byte) ch;
                }
                cursor++;
            }
            if (at(cursor) != '"') {
                p = cursor;
                return NO_KEY;
            }
            if (at(cursor + 1) != ';') {
                p = cursor + 1;
                return NO_KEY;
            }
            p = cursor + 2;
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED,
                    Str.of("unserialize(): Unserializing the 'S' format is deprecated"), SourceLocation.current());
            return Str.of(bytes);
        }

        /** "R:" uiv ";" (the slot becomes a reference to the one named) and "r:" uiv ";" (the object there). */
        private int reference(Object container, Object key, int start, boolean isRef) {
            int end = uivToken(start, ';');
            if (end < 0) {
                return FAIL;
            }
            p = end;
            long id = parseUiv(start + 2) - 1;
            if (id < 0 || id >= data.used || data.containers[(int) id] == null) {
                return FAIL;
            }
            Object referred = data.containers[(int) id];
            Object referredKey = data.keys[(int) id];
            if (referred == container && Objects.equals(referredKey, key)) {
                return FAIL;
            }
            if (isRef) {
                storeRef(container, key, makeRef(referred, referredKey));
                data.refsMade = true;
                return OK;
            }
            Object value = PhpRef.deref(raw(referred, referredKey));
            if (!(value instanceof PhpObject)) {
                return FAIL;
            }
            store(container, key, value);
            return OK;
        }

        /** "a:" uiv ":" "{": the array, its elements read through a Container. */
        private int array(Object container, Object key, int start) {
            int end = uivToken(start, ':');
            if (end < 0 || at(end) != '{') {
                return FAIL;
            }
            long elements = parseIv(start + 2);
            p = end + 1;
            if (elements < 0 || elements >= HT_MAX_SIZE || elements > (max - p) / 2) {
                return FAIL;
            }
            PhpArray array = new PhpArray();
            store(container, key, array);
            if (elements == 0) {
                return finishNested() ? OK : FAIL;
            }
            if (!enterDepth()) {
                return FAIL;
            }
            frames.push(new Container(Container.ARRAY, array, null, null, false, elements));
            return OPENED;
        }

        /** object ":" uiv ":" ["]: an O: object (its properties, or its __unserialize data, follow) or a C: one. */
        private int object(Object container, Object key, int start, boolean custom) {
            int cursor = quotedToken(start);
            if (cursor < 0) {
                return FAIL;
            }
            long length = parseUiv(start + 2);
            if (Long.compareUnsigned(max - cursor, length) < 0 || length == 0) {
                p = start + 2;
                return FAIL;
            }
            int name = cursor;
            cursor += (int) length;
            if (at(cursor) != '"') {
                p = cursor;
                return FAIL;
            }
            if (at(cursor + 1) != ':') {
                p = cursor + 1;
                return FAIL;
            }
            if (s[name] == 0 || s[name] == '\\') {
                // runtime definition keys, and names starting with a namespace separator
                return FAIL;
            }
            byte[] nameBytes = Arrays.copyOfRange(s, name, cursor);
            PhpClass phpClass = findClass(nameBytes);
            if (phpClass == null) {
                return FAIL;
            }
            boolean incomplete = isIncompleteClass(phpClass);
            p = cursor;
            if (isNotSerializable(phpClass)) {
                throw new PhpError("Exception", "Unserialization of '" + printed(phpClass.getName())
                        + "' is not allowed");
            }
            if (custom) {
                return custom(container, key, phpClass, incomplete, nameBytes);
            }
            if (p >= max - 2) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("Bad unserialize data"),
                        SourceLocation.current());
                return FAIL;
            }
            int[] end = new int[1];
            long elements = parseIv2(p + 2, end);
            p = end[0];
            if (elements < 0 || elements > (max - cursor) / 2) {
                return FAIL;
            }
            cursor = p;
            if (at(cursor) != ':') {
                return FAIL;
            }
            if (at(cursor + 1) != '{') {
                p = cursor + 1;
                return FAIL;
            }
            p += 2;
            boolean hasUnserialize = !incomplete && phpClass.findMethod("__unserialize") != null;
            if (isSerializable(phpClass) && !hasUnserialize) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("Erroneous data format for "
                        + "unserializing '" + phpClass.getName() + "'"), SourceLocation.current());
                return FAIL;
            }
            PhpObject object = phpClass.instantiate();
            store(container, key, object);
            if (incomplete) {
                setProperty(object, MAGIC_MEMBER, Str.of(nameBytes));
            }
            return objectCommon(object, phpClass, elements, hasUnserialize);
        }

        /**
         * The class an O: or C: names: declared, autoloaded, or defined by unserialize_callback_func;
         * __PHP_Incomplete_Class for one that isn't, or that allowed_classes leaves out; null (with p unchanged)
         * for a name no class can have.
         */
        private PhpClass findClass(byte[] name) {
            String lower = lowerKey(name, 0, name.length);
            if (data.allowedClasses != null && !data.allowedClasses.contains(lower)) {
                return isValidClassName(name, 0, name.length) ? incompleteClass() : null;
            }
            String className = new String(name, StandardCharsets.UTF_8);
            PhpClass declared = context.findClass(Str.lower(className));
            if (declared != null && !declared.getName().contains("@anonymous")) {
                return declared;
            }
            if (!isValidClassName(name, 0, name.length)) {
                return null;
            }
            PhpClass phpClass = autoload(className);
            if (phpClass != null) {
                return phpClass;
            }
            String callback = context.builtinState().ini().unserializeCallbackFunc();
            if (callback == null || callback.isEmpty()) {
                return incompleteClass();
            }
            if (pl.codeloom.php.runtime.Callables.resolve(context, Str.of(callback)) == null) {
                // zend_call_function's error for a callable it can't call
                throw new PhpError("Invalid callback " + callback + ", "
                        + pl.codeloom.php.runtime.Callables.invalidReason(context, Str.of(callback)));
            }
            state.lock++;
            try {
                pl.codeloom.php.runtime.Callables.call(context, Str.of(callback), Str.of(name));
            } finally {
                state.lock--;
            }
            phpClass = autoload(className);
            if (phpClass == null) {
                warning(context, "unserialize(): Function " + callback + "() hasn't defined the class it was "
                        + "called for");
                return incompleteClass();
            }
            return phpClass;
        }

        /** zend_lookup_class, locked: an autoloader's serialize() or unserialize() runs apart. */
        private PhpClass autoload(String className) {
            state.lock++;
            try {
                return context.findClassAutoloading(className);
            } finally {
                state.lock--;
            }
        }

        private PhpClass incompleteClass() {
            return context.getClass(INCOMPLETE_CLASS);
        }

        /**
         * object_common: the properties (or the __unserialize data) read through a Container; __wakeup() or
         * __unserialize() is called once the outermost unserialize() is done.
         */
        private int objectCommon(PhpObject object, PhpClass phpClass, long elements, boolean hasUnserialize) {
            int kind;
            PhpArray array = null;
            boolean wakeup = false;
            if (hasUnserialize) {
                if (elements >= HT_MAX_SIZE) {
                    return FAIL;
                }
                kind = Container.DATA;
                array = new PhpArray();
            } else {
                if (elements >= HT_MAX_SIZE - object.getShape().getPropertyCount()) {
                    return FAIL;
                }
                kind = Container.OBJECT;
                wakeup = !isIncompleteClass(phpClass) && phpClass.findMethod("__wakeup") != null;
            }
            if (!enterDepth()) {
                return FAIL;
            }
            if (elements == 0) {
                data.curDepth--;
                if (!finishNested()) {
                    return FAIL;
                }
                if (hasUnserialize) {
                    delay(object, array);
                } else if (wakeup) {
                    delay(object, null);
                }
                return OK;
            }
            frames.push(new Container(kind, array, object, phpClass, wakeup, elements));
            return OPENED;
        }

        /**
         * object_custom, for C:len:"Class":datalen:{data}: Serializable::unserialize() gets the data at once (and
         * may call unserialize() on it, which shares this call's numbering); a class without it is made empty.
         */
        private int custom(Object container, Object key, PhpClass phpClass, boolean incomplete, byte[] name) {
            int[] end = new int[1];
            long length = parseIv2(p + 2, end);
            p = end[0];
            if (max - p < 2 || at(p) != ':') {
                return FAIL;
            }
            if (at(p + 1) != '{') {
                p += 1;
                return FAIL;
            }
            p += 2;
            if (length < 0 || max - p <= length) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("Insufficient data for "
                        + "unserializing - " + length + " required, " + (max - p) + " present"),
                        SourceLocation.current());
                return FAIL;
            }
            int payload = p;
            if (at(payload + (int) length) != '}') {
                p += (int) length;
                return FAIL;
            }
            PhpObject object;
            if (!isSerializable(phpClass)) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("Class " + phpClass.getName()
                        + " has no unserializer"), SourceLocation.current());
                object = phpClass.instantiate();
                store(container, key, object);
            } else {
                object = phpClass.instantiate();
                store(container, key, object);
                object.callMethod("unserialize", Str.of(s, payload, (int) length));
            }
            p = payload + (int) length + 1;
            if (incomplete) {
                setProperty(object, MAGIC_MEMBER, Str.of(name));
            }
            return OK;
        }

        /** "E:" uiv ":" ["]: an enum case; the runtime has no enums, so the class found is never one. */
        private int enumCase(int start) {
            int cursor = quotedToken(start);
            if (cursor < 0) {
                return FAIL;
            }
            long length = parseUiv(start + 2);
            if (Long.compareUnsigned(max - cursor, length) < 0 || length == 0) {
                p = start + 2;
                return FAIL;
            }
            int from = cursor;
            cursor += (int) length;
            if (at(cursor) != '"') {
                p = cursor;
                return FAIL;
            }
            if (at(cursor + 1) != ';') {
                p = cursor + 1;
                return FAIL;
            }
            int colon = -1;
            for (int i = from; i < cursor; i++) {
                if (s[i] == ':') {
                    colon = i;
                    break;
                }
            }
            if (colon < 0) {
                warning(context, Str.concat(Str.of("unserialize(): Invalid enum name '"),
                        Str.concat(cString(Arrays.copyOfRange(s, from, cursor)), Str.of("' (missing colon)"))));
                return FAIL;
            }
            if (!isValidClassName(s, from, colon)) {
                return FAIL;
            }
            byte[] enumName = Arrays.copyOfRange(s, from, colon);
            PhpClass phpClass = autoload(new String(enumName, StandardCharsets.UTF_8));
            TruffleString printedName = Str.of(enumName);
            warning(context, Str.concat(Str.of("unserialize(): Class '"), Str.concat(printedName,
                    Str.of(phpClass == null ? "' not found" : "' is not an enum"))));
            return FAIL;
        }

        private void notice(String message) {
            ErrorFunctions.raise(context, ErrorFunctions.E_NOTICE, Str.of(message), SourceLocation.current());
        }

        /**
         * process_nested_object_data's lookup of a property key: the declared property whose table key it is,
         * else what is_property_visibility_changed renames it to (a declared property its unmangled name finds,
         * a mangled one of this class or "*"), else the key as a dynamic property; null when the key is a broken
         * mangled name (with php-src's notice).
         */
        private String propertyFor(PhpClass phpClass, String key) {
            String declared = phpClass.propertyOfMangledName(key);
            if (declared != null) {
                return declared;
            }
            if (!phpClass.hasPropertyInfo()) {
                return key;
            }
            String className = null;
            String property = key;
            if (!key.isEmpty() && key.charAt(0) == '\0') {
                int length = key.length();
                if (length < 3 || key.charAt(1) == '\0') {
                    notice("Illegal member variable name");
                    return null;
                }
                int classLength = strnlen(key, 1, length - 2);
                if (classLength >= length - 2 || key.charAt(classLength + 1) != '\0') {
                    notice("Corrupt member variable name");
                    return null;
                }
                className = key.substring(1, 1 + classLength);
                int anonymousLength = strnlen(key, classLength + 2, length - classLength - 2);
                if (classLength + anonymousLength + 2 != length) {
                    classLength += anonymousLength + 1;
                }
                property = key.substring(classLength + 2);
            }
            boolean lookUp = className == null || className.equals("*")
                    || asciiEqualsIgnoreCase(className, PhpClass.nameKey(printed(phpClass.getName())));
            if (lookUp) {
                if (phpClass.findProperty(property) != null) {
                    return property;
                }
                if (phpClass.isStaticProperty(property)) {
                    // a static property has no slot: the renamed key becomes a dynamic property
                    return property;
                }
            }
            return key;
        }

        /** strcasecmp's equality: ASCII letters in either case. */
        private static boolean asciiEqualsIgnoreCase(String a, String b) {
            if (a.length() != b.length()) {
                return false;
            }
            for (int i = 0; i < a.length(); i++) {
                char x = a.charAt(i);
                char y = b.charAt(i);
                int lowerX = x >= 'A' && x <= 'Z' ? x + 32 : x;
                int lowerY = y >= 'A' && y <= 'Z' ? y + 32 : y;
                if (lowerX != lowerY) {
                    return false;
                }
            }
            return true;
        }

        /** strnlen(key + from, limit): the chars before a NUL, at most limit. */
        private static int strnlen(String key, int from, int limit) {
            int n = 0;
            while (n < limit && from + n < key.length() && key.charAt(from + n) != '\0') {
                n++;
            }
            return n;
        }
    }

    // ---- class traits serialization looks at ----

    /** ZEND_ACC_NOT_SERIALIZABLE: a built-in class that refuses it, or an anonymous class. */
    static boolean isNotSerializable(PhpClass phpClass) {
        return phpClass.getHandlers() != null && phpClass.getHandlers().notSerializable()
                || phpClass.getName().contains("@anonymous");
    }

    /** ce->serialize: the class implements Serializable. */
    static boolean isSerializable(PhpClass phpClass) {
        return phpClass.isDeclaredSubclassOf("serializable");
    }

    static boolean isIncompleteClass(PhpClass phpClass) {
        return phpClass.getLowerName().equals("__php_incomplete_class");
    }
}
