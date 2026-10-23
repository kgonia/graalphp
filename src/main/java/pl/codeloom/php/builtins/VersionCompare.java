package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.PhpError;

import java.util.ArrayList;
import java.util.List;

/** version_compare(), following php-src's ext/standard/versioning.c. */
final class VersionCompare {
    private VersionCompare() {
    }

    @TruffleBoundary
    static int compare(String a, String b) {
        List<String> left = canonical(a);
        List<String> right = canonical(b);
        int n = Math.max(left.size(), right.size());
        for (int i = 0; i < n; i++) {
            if (i >= left.size()) {
                return isNumber(right.get(i)) ? -1 : compare("#", right.get(i));
            }
            if (i >= right.size()) {
                return isNumber(left.get(i)) ? 1 : compare(left.get(i), "#");
            }
            int c = comparePart(left.get(i), right.get(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Lowercase-insensitive separators ('-', '_', '+') become '.', and digit/non-digit boundaries split. */
    private static List<String> canonical(String version) {
        StringBuilder out = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '-' || c == '_' || c == '+') {
                c = '.';
            }
            if (out.length() > 0 && c != '.' && previous != '.'
                    && Character.isDigit(c) != Character.isDigit(previous)) {
                out.append('.');
            }
            out.append(c);
            previous = c;
        }
        List<String> parts = new ArrayList<>();
        for (String part : out.toString().split("\\.")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static boolean isNumber(String part) {
        return !part.isEmpty() && Character.isDigit(part.charAt(0));
    }

    private static int comparePart(String a, String b) {
        boolean na = isNumber(a);
        boolean nb = isNumber(b);
        if (na && nb) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        if (!na && !nb) {
            return Integer.compare(order(a), order(b));
        }
        return na ? compare("#", b) : compare(a, "#");
    }

    /** compare_special_version_forms: names in matching order, each with its rank. */
    private static final String[] FORMS = {"dev", "alpha", "a", "beta", "b", "RC", "rc", "#", "pl", "p"};
    private static final int[] RANKS = {0, 1, 1, 2, 2, 3, 3, 4, 5, 5};

    /** The rank of the first form the part starts with, case-sensitively as php-src's strncmp; -1 for none. */
    private static int order(String special) {
        for (int i = 0; i < FORMS.length; i++) {
            if (special.startsWith(FORMS[i])) {
                return RANKS[i];
            }
        }
        return -1;
    }

    @TruffleBoundary
    static boolean test(int c, String operator) {
        switch (operator) {
            case "<", "lt":
                return c < 0;
            case "<=", "le":
                return c <= 0;
            case ">", "gt":
                return c > 0;
            case ">=", "ge":
                return c >= 0;
            case "==", "eq":
                return c == 0;
            case "!=", "<>", "ne":
                return c != 0;
            default:
                throw new PhpError("ValueError: version_compare(): Argument #3 ($operator) must be a valid comparison"
                        + " operator");
        }
    }
}
