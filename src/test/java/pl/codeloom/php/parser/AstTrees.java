package pl.codeloom.php.parser;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Structural comparison of php-ast trees, for tests: where two trees first differ. */
final class AstTrees {
    private AstTrees() {
    }

    /** Bodies read while comparing (see {@link #firstDifference}). */
    static long bodiesRead;

    /**
     * The path to the first difference between a and b, or null if they are the same tree. An unread body
     * ({@link AstBody}) on either side is read and compared as the tree it gives.
     */
    static String firstDifference(Object a, Object b) {
        return difference("", a, b);
    }

    private static String difference(String path, Object a, Object b) {
        if (a instanceof AstBody unread) {
            bodiesRead++;
            return difference(path + "(read)", unread.read(), b);
        }
        if (b instanceof AstBody unread) {
            bodiesRead++;
            return difference(path + "(read)", a, unread.read());
        }
        if (a instanceof Ast x && b instanceof Ast y) {
            if (!x.kind.equals(y.kind)) {
                return path + ".kind: " + x.kind + " != " + y.kind;
            }
            if (x.flags != y.flags) {
                return path + ".flags: " + x.flags + " != " + y.flags;
            }
            if (x.line != y.line) {
                return path + ".line: " + x.line + " != " + y.line;
            }
            if (x.endLine != y.endLine) {
                return path + ".endLine: " + x.endLine + " != " + y.endLine;
            }
            String list = list(path + "[" + x.kind + "]", x.listOrNull(), y.listOrNull());
            return list != null ? list : named(path + "[" + x.kind + "]", x.namedOrNull(), y.namedOrNull());
        }
        if (a instanceof byte[] x && b instanceof byte[] y) {
            return Arrays.equals(x, y) ? null : path + ": bytes differ";
        }
        if (a instanceof Double x && b instanceof Double y) {
            // bitwise, so NaN equals NaN and 0.0 differs from -0.0
            return Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(y) ? null : path + ": " + x + " != " + y;
        }
        if (a == null ? b == null : a.equals(b)) {
            return null;
        }
        return path + ": " + describe(a) + " != " + describe(b);
    }

    private static String list(String path, List<Object> a, List<Object> b) {
        if (a == null || b == null) {
            return a == b ? null : path + ": list children on one side only";
        }
        if (a.size() != b.size()) {
            return path + ".size: " + a.size() + " != " + b.size();
        }
        for (int i = 0; i < a.size(); i++) {
            String found = difference(path + "." + i, a.get(i), b.get(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String named(String path, Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null) {
            return a == b ? null : path + ": named children on one side only";
        }
        // the same names in the same (the export's) order
        if (!List.copyOf(a.keySet()).equals(List.copyOf(b.keySet()))) {
            return path + ".names: " + a.keySet() + " != " + b.keySet();
        }
        for (Map.Entry<String, Object> entry : a.entrySet()) {
            String found = difference(path + "." + entry.getKey(), entry.getValue(), b.get(entry.getKey()));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + " " + value;
    }
}
