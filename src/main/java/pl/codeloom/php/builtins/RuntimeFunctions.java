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

import java.io.IOException;
import java.io.OutputStream;
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

    private static final Callables.Parameter CALL_USER_FUNC = new Callables.Parameter("call_user_func", 1, false);
    private static final Callables.Parameter CALL_USER_FUNC_ARRAY =
            new Callables.Parameter("call_user_func_array", 1, false);

    static void register(Registry r) {
        // ---- lifecycle ----
        r.fn("exit", (context, a) -> exit(context, Args.get(a, 0, 0L)));
        r.fn("die", (context, a) -> exit(context, Args.get(a, 0, 0L)));
        r.fn("register_shutdown_function", (context, a) -> {
            Callables.check(context, Args.get(a, 0), new Callables.Parameter("register_shutdown_function", 1, false));
            context.addShutdownFunction(Args.get(a, 0), Arrays.copyOfRange(a, PhpFunction.ARG_FIRST + 1, a.length));
            return PhpNull.NULL;
        });
        r.fn("clone", (context, a) -> cloneObject(context, Args.get(a, 0), Args.get(a, 1, PhpNull.NULL)));
        // zend.assertions = -1, as in production: assert() is compiled out and returns true
        r.fn("assert", a -> Boolean.TRUE);
        r.fn("call_user_func", (context, a) -> {
            Callables.check(context, Args.get(a, 0), CALL_USER_FUNC);
            return Callables.call(context, Args.get(a, 0), Arrays.copyOfRange(a, PhpFunction.ARG_FIRST + 1, a.length));
        });
        r.fn("call_user_func_array", (context, a) -> {
            Callables.check(context, Args.get(a, 0), CALL_USER_FUNC_ARRAY);
            return Callables.call(context, Args.get(a, 0), positional(Args.array(a, 1, "call_user_func_array")));
        });
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
                : PhpArray.ofStrings(context.response().headers().toArray(new String[0])));
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
        r.fn("get_loaded_extensions", a -> PhpArray.ofStrings(EXTENSIONS.toArray(new String[0])));
        r.fn("ini_get_all", a -> new PhpArray());
        r.fn("error_get_last", a -> PhpNull.NULL);
        r.fn("error_clear_last", a -> PhpNull.NULL);
        r.fn("error_log", (context, a) -> errorLog(context, Str.bytes(Args.str(a, 0))));
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

    /** error_log() with no error_log ini: the SAPI's logger, which in php-cli writes the message and \n to stderr. */
    @TruffleBoundary
    private static Object errorLog(PhpContext context, byte[] message) {
        try {
            if (context.response() == null) {
                context.flush(); // php-cli writes echo unbuffered, so it comes before the message
            }
            OutputStream err = context.getEnv().err();
            err.write(message);
            err.write('\n');
            err.flush();
            return Boolean.TRUE;
        } catch (IOException e) {
            return Boolean.FALSE;
        }
    }

    /** `clone $object`:a shallow copy (arrays copy on write), then __clone() on the copy. */
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
                copy.setProperty(Str.key(Convert.toStr(properties.keyAt(p))), properties.valueAt(p));
            }
        }
        if (copy.getPhpClass().findMethod("__clone") != null) {
            copy.callMethod("__clone");
        }
        return copy;
    }

    /** The arguments in order; an element that is a reference (`array(&$x)`) is passed as one, to by-ref params. */
    private static Object[] positional(PhpArray arguments) {
        Object[] values = new Object[arguments.size()];
        int i = 0;
        for (int p = arguments.firstPosition(); p >= 0; p = arguments.nextPosition(p)) {
            values[i++] = arguments.rawAt(p);
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

    /**
     * setcookie()/setrawcookie(name, value = "", expires_or_options = 0, path = "", domain = "", secure = false,
     * httponly = false): php-src's php_setcookie_common. The options array takes expires, path, domain, secure,
     * httponly, samesite and partitioned (case-insensitive); the parts are checked before any header is set, under
     * php-cli too (which sends no headers).
     */
    @TruffleBoundary
    private static Object cookie(PhpContext context, Object[] a, boolean raw) {
        String function = raw ? "setrawcookie" : "setcookie";
        CgiSapi.Cookie cookie = new CgiSapi.Cookie(Str.bytes(Args.str(a, 0)),
                Args.has(a, 1) ? Str.bytes(Args.str(a, 1)) : null);
        if (Args.get(a, 2, PhpNull.NULL) instanceof PhpArray options) {
            if (Args.count(a) > 3) {
                throw new PhpError("ArgumentCountError", function + "(): Expects exactly 3 arguments when "
                        + "argument #3 ($expires_or_options) is an array");
            }
            for (int p = options.firstPosition(); p >= 0; p = options.nextPosition(p)) {
                if (!(options.keyAt(p) instanceof TruffleString key)) {
                    throw new PhpError("ValueError", function + "(): option array cannot have numeric keys");
                }
                Object option = options.valueAt(p);
                switch (Str.lower(Str.java(key))) {
                    case "expires" -> cookie.expires = Convert.toLong(option);
                    case "path" -> cookie.path = Str.bytes(Convert.toStr(option));
                    case "domain" -> cookie.domain = Str.bytes(Convert.toStr(option));
                    case "secure" -> cookie.secure = Convert.toBool(option);
                    case "httponly" -> cookie.httpOnly = Convert.toBool(option);
                    case "samesite" -> cookie.sameSite = Str.bytes(Convert.toStr(option));
                    case "partitioned" -> cookie.partitioned = Convert.toBool(option);
                    default -> throw new PhpError("ValueError", function + "(): option \"" + Str.java(key)
                            + "\" is invalid");
                }
            }
        } else {
            cookie.expires = Args.lng(a, 2, 0);
            cookie.path = Args.has(a, 3) ? Str.bytes(Args.str(a, 3)) : null;
            cookie.domain = Args.has(a, 4) ? Str.bytes(Args.str(a, 4)) : null;
            cookie.secure = Args.bool(a, 5, false);
            cookie.httpOnly = Args.bool(a, 6, false);
        }
        String header = cookie.header(function, !raw);
        if (header.indexOf('\0') >= 0 || header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0) {
            return Boolean.FALSE; // sapi_header_op: "Header may not contain NUL bytes" (or a new line)
        }
        if (context.response() == null) {
            return Boolean.TRUE;
        }
        if (context.response().isSent()) {
            return Boolean.FALSE;
        }
        context.response().header(header, false, 0);
        return Boolean.TRUE;
    }

}
