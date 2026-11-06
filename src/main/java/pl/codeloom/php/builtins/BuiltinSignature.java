package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * An internal function's or method's parameters as php-src's arginfo declares them, for named arguments
 * (zend_get_arg_offset_by_name over internal_function.arg_info, zend_handle_undef_args' defaults): their names, by
 * reference, variadic, and each optional one's default. From named-params.txt (tools/builtin-named-params.php, the
 * reference PHP's Reflection), read on the first named call of a built-in, not at start-up.
 */
public final class BuiltinSignature {
    /** A required parameter's default: none ("Argument #n ($x) not passed"). */
    public static final Object REQUIRED = new Object();
    /** An optional parameter whose default php-src doesn't know ("must be passed explicitly"). */
    public static final Object UNKNOWN = new Object();

    /** An enum case as a default (round()'s RoundingMode::HalfAwayFromZero): looked up when a hole takes it. */
    public record EnumCase(String className, String caseName) {
    }

    /**
     * The built-ins that take named arguments their variadic parameter doesn't name and pass them on to the callable
     * they call (Z_PARAM_VARIADIC_WITH_NAMED): any other variadic built-in refuses them.
     */
    private static final java.util.Set<String> FORWARDING = java.util.Set.of("call_user_func", "closure::__invoke",
            "closure::call", "reflectionfunction::invoke", "reflectionmethod::invoke", "reflectionclass::newinstance",
            "fiber::start");

    private final String[] names;
    private final boolean[] byRef;
    private final Object[] defaults;
    private final boolean variadic;
    private final boolean forwarding;

    private BuiltinSignature(String[] names, boolean[] byRef, Object[] defaults, boolean variadic,
                             boolean forwarding) {
        this.names = names;
        this.byRef = byRef;
        this.defaults = defaults;
        this.variadic = variadic;
        this.forwarding = forwarding;
    }

    /** The parameters' names, the variadic one's last. */
    public String[] names() {
        return names;
    }

    /** How many parameters a name can bind: num_args, the variadic one left out. */
    public int declared() {
        return variadic ? names.length - 1 : names.length;
    }

    public boolean isVariadic() {
        return variadic;
    }

    /** Whether the variadic parameter's extra names go on to the callable (Z_PARAM_VARIADIC_WITH_NAMED). */
    public boolean isForwarding() {
        return forwarding;
    }

    public boolean isByRef(int index) {
        return index < byRef.length ? byRef[index] : variadic && byRef[byRef.length - 1];
    }

    /** The index of the parameter called name (case-sensitively), not the variadic one; -1 for none. */
    public int indexOf(String name) {
        for (int i = 0; i < declared(); i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Parameter index's name, or null past the declared ones. */
    public String name(int index) {
        return index < names.length ? names[index] : null;
    }

    /**
     * Parameter index's default for a hole (zend_get_default_from_internal_arg_info): a fresh value (an array is
     * a copy), an {@link EnumCase}, {@link #REQUIRED} or {@link #UNKNOWN}.
     */
    public Object defaultValue(int index) {
        Object value = index < defaults.length ? defaults[index] : REQUIRED;
        return value instanceof PhpArray array ? array.copy() : value;
    }

    /** The signature of the built-in key names (lower case "name" or "class::name"), or null if php-src has none. */
    @TruffleBoundary
    public static BuiltinSignature of(String key) {
        return Table.SIGNATURES.get(key);
    }

    private static final class Table {
        static final Map<String, BuiltinSignature> SIGNATURES = load();

        private static Map<String, BuiltinSignature> load() {
            Map<String, BuiltinSignature> map = new HashMap<>();
            String table = new String(StringFunctions.resource("named-params.txt"), StandardCharsets.US_ASCII);
            for (String line : table.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                int count = parts.length - 1;
                String[] names = new String[count];
                boolean[] byRef = new boolean[count];
                Object[] defaults = new Object[count];
                boolean variadic = false;
                for (int i = 0; i < count; i++) {
                    String text = parts[i + 1];
                    if (text.startsWith("&") || text.startsWith("~")) {
                        byRef[i] = true;
                        text = text.substring(1);
                    }
                    if (text.startsWith("...")) {
                        variadic = true;
                        text = text.substring(3);
                    }
                    int equals = text.indexOf('=');
                    if (equals < 0) {
                        names[i] = text;
                        defaults[i] = REQUIRED;
                        continue;
                    }
                    names[i] = text.substring(0, equals);
                    String value = text.substring(equals + 1);
                    defaults[i] = value.equals("?") ? UNKNOWN : new Unserializer(urlDecode(value)).value();
                }
                String key = parts[0].toLowerCase(java.util.Locale.ROOT);
                map.put(key, new BuiltinSignature(names, byRef, defaults, variadic, FORWARDING.contains(key)));
            }
            return map;
        }

        private static byte[] urlDecode(String text) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '%') {
                    out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                    i += 2;
                } else {
                    out.write(c);
                }
            }
            return out.toByteArray();
        }
    }

    /** The serialize() forms arginfo defaults take: null, bool, int, float, string, an enum case, arrays of them. */
    private static final class Unserializer {
        private final byte[] bytes;
        private int at;

        Unserializer(byte[] bytes) {
            this.bytes = bytes;
        }

        Object value() {
            char type = (char) bytes[at];
            at += 2;
            switch (type) {
                case 'N':
                    return PhpNull.NULL;
                case 'b':
                    return until(';').equals("1");
                case 'i':
                    return Long.parseLong(until(';'));
                case 'd': {
                    String number = until(';');
                    return switch (number) {
                        case "INF" -> Double.POSITIVE_INFINITY;
                        case "-INF" -> Double.NEGATIVE_INFINITY;
                        case "NAN" -> Double.NaN;
                        default -> Double.parseDouble(number);
                    };
                }
                case 's': {
                    int length = Integer.parseInt(until(':'));
                    at++; // "
                    Object string = Str.of(java.util.Arrays.copyOfRange(bytes, at, at + length));
                    at += length + 2; // ";
                    return string;
                }
                case 'E': {
                    // E:len:"Class:Case";
                    int length = Integer.parseInt(until(':'));
                    at++;
                    String text = new String(bytes, at, length, StandardCharsets.UTF_8);
                    at += length + 2;
                    int colon = text.lastIndexOf(':');
                    return new EnumCase(text.substring(0, colon), text.substring(colon + 1));
                }
                case 'a': {
                    int count = Integer.parseInt(until(':'));
                    at++; // {
                    PhpArray array = new PhpArray();
                    for (int i = 0; i < count; i++) {
                        Object key = value();
                        array.put(key, value());
                    }
                    at++; // }
                    return array;
                }
                default:
                    throw new IllegalStateException("named-params.txt: " + type);
            }
        }

        private String until(char end) {
            int start = at;
            while (bytes[at] != end) {
                at++;
            }
            String text = new String(bytes, start, at - start, StandardCharsets.US_ASCII);
            at++;
            return text;
        }
    }
}
