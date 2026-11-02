package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
    }

    private static State state(PhpContext context) {
        return context.builtinState().serialization;
    }

    private static void warning(PhpContext context, String message) {
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(message), SourceLocation.current());
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

        /** zval_get_tmp_string of a name __sleep returned. */
        private TruffleString stringOf(Object value) {
            if (value instanceof PhpArray) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("Array to string conversion"),
                        SourceLocation.current());
            }
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
