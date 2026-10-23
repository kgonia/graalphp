package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * The script's lifecycle and the CLI environment: exit, shutdown functions, clone, assert, the HTTP header
 * functions (no-ops in CLI, as in php-cli), the environment and PHP's self-description.
 */
final class RuntimeFunctions {
    private RuntimeFunctions() {
    }

    /** The extensions whose functions Truffle PHP provides (extension_loaded, get_loaded_extensions). */
    static final java.util.Set<String> EXTENSIONS = new java.util.TreeSet<>(java.util.List.of("core", "standard", "date",
            "pcre", "json", "mbstring", "ctype", "hash", "spl", "random", "mysqli"));

    static void register(Registry r) {
        // ---- lifecycle ----
        r.fn("exit", (context, a) -> exit(context, Args.get(a, 0, 0L)));
        r.fn("die", (context, a) -> exit(context, Args.get(a, 0, 0L)));
        r.fn("register_shutdown_function", (context, a) -> {
            context.addShutdownFunction(Args.get(a, 0), Arrays.copyOfRange(a, PhpFunction.ARG_FIRST + 1, a.length));
            return PhpNull.NULL;
        });
        r.fn("clone", (context, a) -> cloneObject(context, Args.get(a, 0), Args.get(a, 1, PhpNull.NULL)));
        // zend.assertions = -1, as in production: assert() is compiled out and returns true
        r.fn("assert", a -> Boolean.TRUE);
        r.fn("call_user_func", (context, a) -> Callables.call(context, Args.get(a, 0),
                Arrays.copyOfRange(a, PhpFunction.ARG_FIRST + 1, a.length)));
        r.fn("call_user_func_array", (context, a) -> Callables.call(context, Args.get(a, 0),
                positional(Args.array(a, 1, "call_user_func_array"))));
        r.fn("set_time_limit", a -> Boolean.TRUE);
        r.fn("ignore_user_abort", a -> 0L);
        r.fn("flush", (context, a) -> {
            context.flush();
            return PhpNull.NULL;
        });

        // ---- HTTP headers and cookies: the CGI SAPI's response; php-cli sends none ----
        r.fn("header", (context, a) -> {
            if (context.response() != null && !context.response().isSent()) {
                context.response().header(Str.java(Args.str(a, 0)), Args.bool(a, 1, true), (int) Args.lng(a, 2, 0));
            }
            return PhpNull.NULL;
        });
        r.fn("header_remove", (context, a) -> {
            if (context.response() != null && !context.response().isSent()) {
                context.response().remove(Args.has(a, 0) && Args.get(a, 0) != PhpNull.NULL
                        ? Str.java(Args.str(a, 0)) : null);
            }
            return PhpNull.NULL;
        });
        r.fn("headers_sent", (context, a) -> context.response() != null && context.response().isSent());
        r.fn("headers_list", (context, a) -> context.response() == null ? new PhpArray()
                : list(context.response().headers().toArray(new String[0])));
        r.fn("http_response_code", (context, a) -> {
            if (context.response() == null) {
                return Boolean.FALSE;
            }
            long previous = context.response().status();
            if (Args.has(a, 0) && Args.get(a, 0) != PhpNull.NULL && !context.response().isSent()) {
                context.response().setStatus((int) Args.lng(a, 0, 200));
            }
            return previous;
        });
        r.fn("setcookie", (context, a) -> cookie(context, a, false));
        r.fn("setrawcookie", (context, a) -> cookie(context, a, true));

        // ---- environment and self-description ----
        r.fn("getenv", (context, a) -> getenv(context, a));
        r.fn("phpversion", a -> Args.has(a, 0) ? (Object) Boolean.FALSE : Str.of("8.5.12"));
        r.fn("php_uname", a -> Str.of(Args.str(a, 0, Str.of("a")).equals(Str.of("s")) ? "Linux"
                : "Linux localhost 6.0 #1 SMP x86_64"));
        r.fn("get_loaded_extensions", a -> list(EXTENSIONS.toArray(new String[0])));
        r.fn("ini_get_all", a -> new PhpArray());
        r.fn("error_get_last", a -> PhpNull.NULL);
        r.fn("error_clear_last", a -> PhpNull.NULL);
        r.fn("error_log", (context, a) -> {
            System.err.println(Str.java(Args.str(a, 0)));
            return Boolean.TRUE;
        });
        r.fn("debug_backtrace", a -> new PhpArray());
        r.fn("get_defined_constants", (context, a) -> context.constantsArray());
        r.fn("gc_collect_cycles", a -> 0L);
        r.fn("gc_enable", a -> PhpNull.NULL);
        r.fn("memory_get_usage", a -> Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        r.fn("memory_get_peak_usage", a -> Runtime.getRuntime().totalMemory());
    }

    @TruffleBoundary
    private static Object exit(PhpContext context, Object status) {
        if (status instanceof TruffleString message) {
            context.write(message);
            context.exit(0);
        }
        context.exit((int) Convert.toLong(status));
        return PhpNull.NULL;
    }

    /** `clone $object`: a shallow copy (arrays copy on write), then __clone() on the copy. */
    @TruffleBoundary
    private static Object cloneObject(PhpContext context, Object value, Object withProperties) {
        if (!(value instanceof PhpObject object)) {
            throw new PhpError("clone(): Argument #1 ($object) must be of type object, "
                    + Convert.typeName(value) + " given");
        }
        PhpObject copy = new PhpObject(object.getPhpClass().getRootShape(), object.getPhpClass(),
                context.nextObjectId());
        for (Object name : object.propertyNames()) {
            Object property = object.getProperty((String) name);
            if (property instanceof PhpArray array) {
                array.addHolder();
            }
            copy.setProperty((String) name, property);
        }
        if (withProperties instanceof PhpArray properties) {
            for (int p = properties.firstPosition(); p >= 0; p = properties.nextPosition(p)) {
                copy.setProperty(Str.java(Convert.toStr(properties.keyAt(p))), properties.valueAt(p));
            }
        }
        if (copy.getPhpClass().findMethod("__clone") != null) {
            copy.callMethod("__clone");
        }
        return copy;
    }

    private static Object[] positional(PhpArray arguments) {
        Object[] values = new Object[arguments.size()];
        int i = 0;
        for (int p = arguments.firstPosition(); p >= 0; p = arguments.nextPosition(p)) {
            values[i++] = arguments.valueAt(p);
        }
        return values;
    }

    @TruffleBoundary
    private static Object getenv(PhpContext context, Object[] a) {
        Map<String, String> environment = context.getEnv().getEnvironment();
        if (!Args.has(a, 0) || Args.get(a, 0) == PhpNull.NULL) {
            PhpArray all = new PhpArray();
            for (Map.Entry<String, String> variable : new TreeMap<>(environment).entrySet()) {
                all.put(ArrayKey.of(Str.of(variable.getKey())), Str.of(variable.getValue()));
            }
            return all;
        }
        String value = environment.get(Str.java(Args.str(a, 0)));
        return value == null ? (Object) Boolean.FALSE : Str.of(value);
    }

    /** setcookie(name, value, expires_or_options, path, domain, secure, httponly). */
    @TruffleBoundary
    private static Object cookie(PhpContext context, Object[] a, boolean raw) {
        if (context.response() == null) {
            return Boolean.TRUE;
        }
        if (context.response().isSent()) {
            return Boolean.FALSE;
        }
        String name = Str.java(Args.str(a, 0));
        byte[] value = Str.bytes(Args.str(a, 1, Str.EMPTY));
        long expires = 0;
        String path = "";
        String domain = "";
        boolean secure = false;
        boolean httpOnly = false;
        String sameSite = "";
        if (Args.get(a, 2, PhpNull.NULL) instanceof PhpArray options) {
            for (int p = options.firstPosition(); p >= 0; p = options.nextPosition(p)) {
                Object option = options.valueAt(p);
                switch (Str.java(Convert.toStr(options.keyAt(p))).toLowerCase()) {
                    case "expires" -> expires = Convert.toLong(option);
                    case "path" -> path = Str.java(Convert.toStr(option));
                    case "domain" -> domain = Str.java(Convert.toStr(option));
                    case "secure" -> secure = Convert.toBool(option);
                    case "httponly" -> httpOnly = Convert.toBool(option);
                    case "samesite" -> sameSite = Str.java(Convert.toStr(option));
                    default -> throw new PhpError("ValueError: setcookie(): option \"" + options.keyAt(p)
                            + "\" is invalid");
                }
            }
        } else {
            expires = Args.lng(a, 2, 0);
            path = Str.java(Args.str(a, 3, Str.EMPTY));
            domain = Str.java(Args.str(a, 4, Str.EMPTY));
            secure = Args.bool(a, 5, false);
            httpOnly = Args.bool(a, 6, false);
        }
        context.response().header(CgiSapi.cookieHeader(name, value, raw, expires, path, domain, secure, httpOnly,
                sameSite), false, 0);
        return Boolean.TRUE;
    }

    private static PhpArray list(String... items) {
        PhpArray array = new PhpArray();
        for (String item : items) {
            array.append(Str.of(item));
        }
        return array;
    }
}
