package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;

/**
 * var_dump, var_export and print_r, as php-src's php_var_dump, php_var_export_ex (ext/standard/var.c) and
 * zend_print_zval_r (Zend/zend.c). Strings are written as their raw bytes. Arrays and objects being printed carry
 * the context's recursion marks ({@link RecursionGuard}), which a var_dump that __debugInfo calls sees too.
 * Uninitialized typed properties aren't printed.
 */
final class Dump {
    private final ByteBuilder out = new ByteBuilder(64);
    private final PhpContext context;
    private final RecursionGuard guard;
    /** The mark this walk sets on an object: DEBUG for var_dump and print_r, EXPORT for var_export. */
    private final int objectMark;
    /** var_dump writes as it goes (php_printf): what it has so far goes out before user code runs. */
    private final boolean streaming;

    private Dump(PhpContext context, int objectMark, boolean streaming) {
        this.context = context;
        this.guard = context.builtinState().recursion;
        this.objectMark = objectMark;
        this.streaming = streaming;
    }

    /** var_dump of one value, written to the output. */
    @TruffleBoundary
    static void varDump(PhpContext context, Object value) {
        Dump dump = new Dump(context, RecursionGuard.DEBUG, true);
        int depth = dump.guard.depth();
        try {
            dump.varDump(value, 1);
        } finally {
            dump.guard.unwind(depth);
        }
        dump.flush();
    }

    @TruffleBoundary
    static TruffleString varExport(PhpContext context, Object value) {
        Dump dump = new Dump(context, RecursionGuard.EXPORT, false);
        int depth = dump.guard.depth();
        try {
            dump.varExport(value, 1);
        } finally {
            dump.guard.unwind(depth);
        }
        return dump.result();
    }

    @TruffleBoundary
    static TruffleString printR(PhpContext context, Object value) {
        Dump dump = new Dump(context, RecursionGuard.DEBUG, false);
        int depth = dump.guard.depth();
        try {
            dump.printR(value, 0);
        } finally {
            dump.guard.unwind(depth);
        }
        return dump.result();
    }

    /** var_dump's output so far, written out. */
    private void flush() {
        context.write(out.toStr());
        out.reset();
    }

    /**
     * zend_std_get_debug_info: __debugInfo()'s array, an empty one for null (deprecated). Anything else, or an exception out of it,
     * is a fatal error (the exception reported first, as a warning).
     */
    private PhpArray debugInfo(PhpObject object) {
        if (streaming) {
            flush();
        }
        Object info;
        try {
            info = PhpRef.deref(object.callMethod("__debuginfo"));
        } catch (PhpThrowable pending) {
            throw ErrorFunctions.fatalAfterException(context, pending, ErrorFunctions.E_ERROR,
                    Str.of("__debuginfo() must return an array"), SourceLocation.current());
        }
        if (info instanceof PhpArray debug) {
            return debug;
        }
        if (info == PhpNull.NULL) {
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of("Returning null from "
                    + object.getPhpClass().getName() + "::__debugInfo() is deprecated, return an empty array instead"),
                    SourceLocation.current());
            return new PhpArray();
        }
        throw ErrorFunctions.fatal(context, ErrorFunctions.E_ERROR, Str.of("__debuginfo() must return an array"),
                SourceLocation.current(), 0);
    }

    private TruffleString result() {
        return out.toStr();
    }

    private void ascii(String text) {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void utf8(String text) {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private void spaces(int count) {
        for (int i = 0; i < count; i++) {
            out.write(' ');
        }
    }

    /** Marks a container as being printed; false if it already is (here or in a walk this one runs inside). */
    private boolean enter(Object container) {
        return guard.enter(container, container instanceof PhpArray ? RecursionGuard.ARRAY : objectMark);
    }

    /** Clears the innermost container's mark. */
    private void leave() {
        guard.leave();
    }

    // ---- var_dump ----

    private void varDump(Object raw, int level) {
        if (level > 1) {
            spaces(level - 1);
        }
        Object value = raw;
        String common = "";
        if (raw instanceof PhpRef ref) {
            value = ref.get();
            common = ref.isShared() ? "&" : "";
        }
        if (value instanceof Boolean b) {
            ascii(common + "bool(" + b + ")\n");
        } else if (value == PhpNull.NULL || value == null) {
            ascii(common + "NULL\n");
        } else if (value instanceof Long l) {
            ascii(common + "int(" + l + ")\n");
        } else if (value instanceof Double d) {
            ascii(common + "float(" + Convert.doubleToRepr(d) + ")\n");
        } else if (value instanceof TruffleString s) {
            ascii(common + "string(" + Str.length(s) + ") \"");
            out.write(s);
            ascii("\"\n");
        } else if (value instanceof PhpArray array) {
            if (!enter(array)) {
                ascii("*RECURSION*\n");
                return;
            }
            ascii(common + "array(" + array.size() + ") {\n");
            dumpEntries(array, level);
            leave();
            closeBrace(level);
        } else if (value instanceof PhpObject object && object.getPhpClass().isEnum()) {
            ascii(common + "enum(");
            utf8(object.getPhpClass().getName());
            ascii("::");
            out.write((TruffleString) object.getProperty("name"));
            ascii(")\n");
        } else if (value instanceof PhpObject object) {
            if (!enter(object)) {
                ascii("*RECURSION*\n");
                return;
            }
            if (object.getPhpClass().findMethod("__debuginfo") != null) {
                // zend_std_get_debug_info: __debugInfo()'s array stands for the properties
                PhpArray debug = debugInfo(object);
                ascii(common + "object(");
                utf8(object.getPhpClass().getName().split("\0", 2)[0]);
                ascii(")#" + object.id() + " (" + debug.size() + ") {\n");
                dumpEntries(debug, level);
                leave();
                closeBrace(level);
                return;
            }
            // a lazy object as it is (zend_lazy_object_debug_info): an initialized proxy shows its instance
            PhpObject.Lazy lazy = object.isInitializingLazy() ? null : object.lazy();
            if (lazy != null) {
                common += lazy.isProxy() ? "lazy proxy " : "lazy ghost ";
            }
            if (lazy != null && lazy.instance() != null) {
                ascii(common + "object(");
                utf8(object.getPhpClass().getName());
                ascii(")#" + object.id() + " (1) {\n");
                spaces(level + 1);
                ascii("[\"instance\"]=>\n");
                varDump(lazy.instance(), level + 2);
                leave();
                closeBrace(level);
                return;
            }
            // php_var_dump: the property table, with an uninitialized typed property's type in its place
            PhpClass phpClass = object.getPhpClass();
            Object[] table = object.propertyTable();
            ascii(common + "object(");
            utf8(phpClass.getName().split("\0", 2)[0]);
            ascii(")#" + object.id() + " (" + table.length / 2 + ") {\n");
            int t = 0;
            for (PhpClass.PropertyDecl decl : phpClass.getProperties()) {
                String mangled = phpClass.mangledName(decl.name());
                if (t < table.length && mangled.equals(table[t])) {
                    dumpProperty(mangled, table[t + 1], level);
                    t += 2;
                } else if (decl.type() != null && !(phpClass.findHooks(decl.name()) instanceof PhpClass.Hooks hooks
                        && hooks.virtual())) {
                    spaces(level + 1);
                    property(mangled, true);
                    ascii("]=>\n");
                    spaces(level + 1);
                    ascii("uninitialized(");
                    utf8(decl.type().text(null));
                    ascii(")\n");
                }
            }
            for (; t < table.length; t += 2) {
                dumpProperty((String) table[t], table[t + 1], level);
            }
            leave();
            closeBrace(level);
        } else if (value instanceof PhpClosure closure) {
            if (!enter(closure)) {
                ascii("*RECURSION*\n");
                return;
            }
            PhpArray debug = ClosureMethods.debugInfo(closure);
            ascii(common + "object(Closure)#" + closure.id() + " (" + debug.size() + ") {\n");
            dumpEntries(debug, level);
            leave();
            closeBrace(level);
        } else if (value instanceof PhpResource resource) {
            ascii(common + "resource(" + resource.getId() + ") of type (" + resource.getType() + ")\n");
        } else {
            ascii(common + "UNKNOWN:0\n");
        }
    }

    private void dumpEntries(PhpArray entries, int level) {
        for (int p = entries.firstPosition(); p >= 0; p = entries.nextPosition(p)) {
            spaces(level + 1);
            dumpKey(entries.keyAt(p));
            varDump(entries.rawAt(p), level + 2);
        }
    }

    private void dumpProperty(String key, Object value, int level) {
        spaces(level + 1);
        property(key, true);
        ascii("]=>\n");
        varDump(value, level + 2);
    }

    /** __debugInfo()'s array as a property table (zend_std_get_debug_info), its keys as strings. */
    private Object[] debugTable(PhpObject object) {
        PhpArray debug = debugInfo(object);
        Object[] table = new Object[debug.size() * 2];
        int i = 0;
        for (int p = debug.firstPosition(); p >= 0; p = debug.nextPosition(p)) {
            Object key = debug.keyAt(p);
            table[i++] = key instanceof Long l ? l.toString() : Str.key((TruffleString) key);
            table[i++] = debug.rawAt(p);
        }
        return table;
    }

    /**
     * A property table key after its "[", as php_object_property_dump (quoted) and print_hash write it: the name, then
     * ":protected" or the private one's ":Class:private".
     */
    private void property(String key, boolean quoted) {
        String q = quoted ? "[\"" : "[";
        int end = key.startsWith("\0") ? key.indexOf('\0', 1) : -1;
        ascii(q);
        out.write(Str.ofKey(end < 0 ? key : key.substring(end + 1)));
        q = quoted ? "\"" : "";
        ascii(q);
        if (end > 0) {
            String scope = key.substring(1, end);
            if (scope.equals("*")) {
                ascii(":protected");
            } else {
                ascii(":" + q);
                out.write(Str.ofKey(scope));
                ascii(q + ":private");
            }
        }
    }

    private void dumpKey(Object key) {
        if (key instanceof Long l) {
            ascii("[" + l + "]=>\n");
        } else {
            ascii("[\"");
            out.write((TruffleString) key);
            ascii("\"]=>\n");
        }
    }

    private void closeBrace(int level) {
        if (level > 1) {
            spaces(level - 1);
        }
        ascii("}\n");
    }

    // ---- var_export ----

    private void varExport(Object raw, int level) {
        Object value = PhpRef.deref(raw);
        if (value instanceof Boolean b) {
            ascii(b ? "true" : "false");
        } else if (value instanceof Long l) {
            // PHP_INT_MIN as a literal would parse as a float
            ascii(l == Long.MIN_VALUE ? (Long.MIN_VALUE + 1) + "-1" : Long.toString(l));
        } else if (value instanceof Double d) {
            ascii(Convert.doubleToExport(d));
        } else if (value instanceof TruffleString s) {
            exportString(s);
        } else if (value instanceof PhpArray array) {
            if (!enter(array)) {
                // php-src warns "var_export does not handle circular references"
                ascii("NULL");
                return;
            }
            if (level > 1) {
                out.write('\n');
                spaces(level - 1);
            }
            ascii("array (\n");
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                spaces(level + 1);
                Object key = array.keyAt(p);
                if (key instanceof Long l) {
                    ascii(Long.toString(l));
                } else {
                    exportString((TruffleString) key);
                }
                ascii(" => ");
                varExport(array.rawAt(p), level + 2);
                ascii(",\n");
            }
            leave();
            if (level > 1) {
                spaces(level - 1);
            }
            out.write(')');
        } else if (value instanceof PhpObject object && object.getPhpClass().isEnum()) {
            if (level > 1) {
                out.write('\n');
                spaces(level - 1);
            }
            out.write('\\');
            utf8(object.getPhpClass().getName());
            ascii("::");
            out.write((TruffleString) object.getProperty("name"));
        } else if (value instanceof PhpObject object) {
            if (!enter(object)) {
                ascii("NULL");
                return;
            }
            if (level > 1) {
                out.write('\n');
                spaces(level - 1);
            }
            boolean standard = object.getPhpClass().getLowerName().equals("stdclass");
            if (standard) {
                ascii("(object) array(\n");
            } else {
                out.write('\\');
                utf8(object.getPhpClass().getName());
                ascii("::__set_state(array(\n");
            }
            // a lazy object initialized; a class with hooks has its get hooks called (readableProperties)
            PhpObject real = object.initialized();
            Object[] table = real.getPhpClass().hasHooks() ? real.readableProperties(false).toArray()
                    : real.propertyTable();
            for (int i = 0; i < table.length; i += 2) {
                // the name unmangled
                String key = (String) table[i];
                spaces(level + 2);
                exportString(Str.ofKey(key.startsWith("\0") && key.indexOf('\0', 1) > 0
                        ? key.substring(key.indexOf('\0', 1) + 1) : key));
                ascii(" => ");
                varExport(table[i + 1], level + 2);
                ascii(",\n");
            }
            leave();
            if (level > 1) {
                spaces(level - 1);
            }
            ascii(standard ? ")" : "))");
        } else if (value instanceof PhpClosure) {
            if (level > 1) {
                out.write('\n');
                spaces(level - 1);
            }
            ascii("\\Closure::__set_state(array(\n");
            if (level > 1) {
                spaces(level - 1);
            }
            ascii("))");
        } else {
            ascii("NULL");
        }
    }

    /** A quoted string literal: ' and \ escaped, NUL bytes as ' . "\0" . ' (php_addcslashes, php_str_to_str). */
    private void exportString(TruffleString s) {
        out.write('\'');
        byte[] bytes = Str.readBytes(s);
        int length = Str.length(s);
        for (int i = 0; i < length; i++) {
            byte b = bytes[i];
            if (b == '\'' || b == '\\') {
                out.write('\\');
                out.write(b);
            } else if (b == 0) {
                ascii("' . \"\\0\" . '");
            } else {
                out.write(b);
            }
        }
        out.write('\'');
    }

    // ---- print_r ----

    private void printR(Object raw, int indent) {
        Object value = PhpRef.deref(raw);
        if (value instanceof PhpArray array) {
            ascii("Array\n");
            if (!enter(array)) {
                ascii(" *RECURSION*");
                return;
            }
            printEntries(array, indent);
            leave();
        } else if (value instanceof PhpObject object) {
            utf8(object.getPhpClass().getName());
            String backing = object.getPhpClass().backingType();
            ascii(!object.getPhpClass().isEnum() ? " Object\n" : backing == null ? " Enum\n" : " Enum:" + backing + "\n");
            // zend_print_zval_r_to_buf tests the mark before __debugInfo runs, and sets it only after
            if (guard.isProtected(object, objectMark)) {
                ascii(" *RECURSION*");
                return;
            }
            Object[] table = object.getPhpClass().findMethod("__debuginfo") != null ? debugTable(object)
                    : object.propertyTable();
            enter(object);
            spaces(indent);
            ascii("(\n");
            for (int i = 0; i < table.length; i += 2) {
                spaces(indent + 4);
                property((String) table[i], false);
                ascii("] => ");
                printR(table[i + 1], indent + 8);
                out.write('\n');
            }
            leave();
            spaces(indent);
            ascii(")\n");
        } else if (value instanceof PhpClosure closure) {
            ascii("Closure Object\n");
            if (!enter(closure)) {
                ascii(" *RECURSION*");
                return;
            }
            printEntries(ClosureMethods.debugInfo(closure), indent);
            leave();
        } else {
            out.write(Convert.toStr(value == null ? PhpNull.NULL : value));
        }
    }

    private void printEntries(PhpArray entries, int indent) {
        spaces(indent);
        ascii("(\n");
        for (int p = entries.firstPosition(); p >= 0; p = entries.nextPosition(p)) {
            spaces(indent + 4);
            out.write('[');
            Object key = entries.keyAt(p);
            if (key instanceof Long l) {
                ascii(Long.toString(l));
            } else {
                out.write((TruffleString) key);
            }
            ascii("] => ");
            printR(entries.rawAt(p), indent + 8);
            out.write('\n');
        }
        spaces(indent);
        ascii(")\n");
    }
}
