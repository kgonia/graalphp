package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * var_dump, var_export and print_r, as php-src's php_var_dump, php_var_export_ex (ext/standard/var.c) and
 * zend_print_zval_r (Zend/zend.c). Strings are written as their raw bytes. Arrays and objects being printed are
 * tracked for recursion as php-src's recursion guards. Property visibility isn't printed (the runtime doesn't keep
 * protected and private apart), nor are uninitialized typed properties.
 */
final class Dump {
    private final ByteBuilder out = new ByteBuilder(64);
    /** The arrays and objects being printed, by identity. */
    private final Map<Object, Boolean> inProgress = new IdentityHashMap<>();

    private Dump() {
    }

    @TruffleBoundary
    static TruffleString varDump(Object value) {
        Dump dump = new Dump();
        dump.varDump(value, 1);
        return dump.result();
    }

    @TruffleBoundary
    static TruffleString varExport(Object value) {
        Dump dump = new Dump();
        dump.varExport(value, 1);
        return dump.result();
    }

    @TruffleBoundary
    static TruffleString printR(Object value) {
        Dump dump = new Dump();
        dump.printR(value, 0);
        return dump.result();
    }

    private TruffleString result() {
        return Str.of(out.toByteArray());
    }

    private void ascii(String text) {
        out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void utf8(String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private void spaces(int count) {
        for (int i = 0; i < count; i++) {
            out.write(' ');
        }
    }

    /** Starts guarding a container; false if it is already being printed. */
    private boolean enter(Object container) {
        return inProgress.put(container, Boolean.TRUE) == null;
    }

    private void leave(Object container) {
        inProgress.remove(container);
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
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                spaces(level + 1);
                dumpKey(array.keyAt(p));
                varDump(array.rawAt(p), level + 2);
            }
            leave(array);
            closeBrace(level);
        } else if (value instanceof PhpObject object) {
            if (!enter(object)) {
                ascii("*RECURSION*\n");
                return;
            }
            Object[] names = object.propertyNames();
            ascii(common + "object(");
            utf8(object.getPhpClass().getName());
            ascii(")#" + object.id() + " (" + names.length + ") {\n");
            for (Object name : names) {
                spaces(level + 1);
                ascii("[\"");
                utf8((String) name);
                ascii("\"]=>\n");
                varDump(object.getProperty((String) name), level + 2);
            }
            leave(object);
            closeBrace(level);
        } else if (value instanceof PhpClosure) {
            ascii(common + "object(Closure)#0 (0) {\n");
            closeBrace(level);
        } else if (value instanceof PhpResource resource) {
            ascii(common + "resource(" + resource.getId() + ") of type (" + resource.getType() + ")\n");
        } else {
            ascii(common + "UNKNOWN:0\n");
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
            leave(array);
            if (level > 1) {
                spaces(level - 1);
            }
            out.write(')');
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
            for (Object name : object.propertyNames()) {
                spaces(level + 2);
                exportString(Str.of((String) name));
                ascii(" => ");
                varExport(object.getProperty((String) name), level + 2);
                ascii(",\n");
            }
            leave(object);
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
            spaces(indent);
            ascii("(\n");
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                spaces(indent + 4);
                out.write('[');
                Object key = array.keyAt(p);
                if (key instanceof Long l) {
                    ascii(Long.toString(l));
                } else {
                    out.write((TruffleString) key);
                }
                ascii("] => ");
                printR(array.rawAt(p), indent + 8);
                out.write('\n');
            }
            leave(array);
            spaces(indent);
            ascii(")\n");
        } else if (value instanceof PhpObject object) {
            utf8(object.getPhpClass().getName());
            ascii(" Object\n");
            if (!enter(object)) {
                ascii(" *RECURSION*");
                return;
            }
            spaces(indent);
            ascii("(\n");
            for (Object name : object.propertyNames()) {
                spaces(indent + 4);
                out.write('[');
                utf8((String) name);
                ascii("] => ");
                printR(object.getProperty((String) name), indent + 8);
                out.write('\n');
            }
            leave(object);
            spaces(indent);
            ascii(")\n");
        } else if (value instanceof PhpClosure) {
            ascii("Closure Object\n");
            spaces(indent);
            ascii("(\n");
            spaces(indent);
            ascii(")\n");
        } else {
            out.write(Convert.toStr(value == null ? PhpNull.NULL : value));
        }
    }
}
