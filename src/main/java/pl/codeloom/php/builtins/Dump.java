package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/** var_dump, var_export and print_r formatting (debugging aids; byte-exactness with php-src not required). */
final class Dump {
    private Dump() {
    }

    @TruffleBoundary
    static String varDump(Object value, int indent) {
        String pad = " ".repeat(indent);
        if (value instanceof Long l) {
            return pad + "int(" + l + ")\n";
        }
        if (value instanceof Double d) {
            return pad + "float(" + Convert.doubleToRepr(d).replace(".0E", "E") + ")\n";
        }
        if (value instanceof TruffleString s) {
            return pad + "string(" + Str.length(s) + ") \"" + Str.java(s) + "\"\n";
        }
        if (value instanceof Boolean b) {
            return pad + "bool(" + b + ")\n";
        }
        if (value == PhpNull.NULL || value == null) {
            return pad + "NULL\n";
        }
        if (value instanceof PhpArray array) {
            StringBuilder out = new StringBuilder(pad + "array(" + array.size() + ") {\n");
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                Object key = array.keyAt(p);
                out.append(pad).append("  [").append(key instanceof Long ? key : "\"" + Str.java((TruffleString) key)
                        + "\"").append("]=>\n");
                out.append(varDump(array.valueAt(p), indent + 2));
            }
            return out.append(pad).append("}\n").toString();
        }
        if (value instanceof PhpObject object) {
            Object[] names = object.propertyNames();
            StringBuilder out = new StringBuilder(pad + "object(" + object.getPhpClass().getName() + ")#" + object.id()
                    + " (" + names.length + ") {\n");
            for (Object name : names) {
                out.append(pad).append("  [\"").append(name).append("\"]=>\n");
                out.append(varDump(PhpRef.deref(object.getProperty((String) name)), indent + 2));
            }
            return out.append(pad).append("}\n").toString();
        }
        if (value instanceof PhpClosure) {
            return pad + "object(Closure)#0 (0) {\n" + pad + "}\n";
        }
        return pad + value + "\n";
    }

    @TruffleBoundary
    static String varExport(Object value, int indent) {
        if (value instanceof TruffleString s) {
            return "'" + Str.java(s).replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        if (value instanceof Double d) {
            return Convert.doubleToRepr(d);
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value == PhpNull.NULL || value == null) {
            return "NULL";
        }
        if (value instanceof PhpArray array) {
            String pad = " ".repeat(indent);
            StringBuilder out = new StringBuilder("array (\n");
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                out.append(pad).append("  ").append(varExport(array.keyAt(p), indent + 2)).append(" => ");
                Object element = array.valueAt(p);
                if (element instanceof PhpArray || element instanceof PhpObject) {
                    out.append("\n").append(pad).append("  ");
                }
                out.append(varExport(element, indent + 2)).append(",\n");
            }
            return out.append(pad).append(")").toString();
        }
        return String.valueOf(Convert.toStr(value));
    }

    @TruffleBoundary
    static String printR(Object value, int indent) {
        if (value instanceof PhpArray array) {
            String pad = " ".repeat(indent);
            StringBuilder out = new StringBuilder("Array\n" + pad + "(\n");
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                out.append(pad).append("    [").append(Str.java(Convert.toStr(array.keyAt(p)))).append("] => ")
                        .append(printR(array.valueAt(p), indent + 8)).append("\n");
            }
            return out.append(pad).append(")\n").toString();
        }
        if (value instanceof PhpObject object) {
            String pad = " ".repeat(indent);
            StringBuilder out = new StringBuilder(object.getPhpClass().getName() + " Object\n" + pad + "(\n");
            for (Object name : object.propertyNames()) {
                out.append(pad).append("    [").append(name).append("] => ")
                        .append(printR(PhpRef.deref(object.getProperty((String) name)), indent + 8)).append("\n");
            }
            return out.append(pad).append(")\n").toString();
        }
        return Str.java(Convert.toStr(value == null ? PhpNull.NULL : value));
    }
}
