package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.vars.PropNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
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
    static final java.util.Set<String> EXTENSIONS = new java.util.TreeSet<>(java.util.List.of("core", "standard",
            "date", "pcre", "json", "mbstring", "ctype", "hash", "spl", "random", "mysqli", "curl", "filter"));

    private static final Callables.Parameter CALL_USER_FUNC = new Callables.Parameter("call_user_func", 1, false);
    private static final Callables.Parameter CALL_USER_FUNC_ARRAY =
            new Callables.Parameter("call_user_func_array", 1, false);

    /** php_get_uname on Linux: uname(2)'s fields, as /proc/sys/kernel gives them. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String uname(char mode) {
        String[] fields = new String[5];
        String[] files = {"ostype", "hostname", "osrelease", "version"};
        for (int i = 0; i < 4; i++) {
            try {
                fields[i] = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/sys/kernel/" + files[i])).strip();
            } catch (java.io.IOException | RuntimeException e) {
                fields[i] = i == 0 ? "Linux" : "";
            }
        }
        String arch = System.getProperty("os.arch");
        fields[4] = arch.equals("amd64") ? "x86_64" : arch.equals("aarch64") ? "aarch64" : arch;
        return switch (mode) {
            case 's' -> fields[0];
            case 'n' -> fields[1];
            case 'r' -> fields[2];
            case 'v' -> fields[3];
            case 'm' -> fields[4];
            default -> String.join(" ", fields);
        };
    }

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
            return Callables.parameter(context, Args.get(a, 0), CALL_USER_FUNC)
                    .call(Arrays.copyOfRange(a, PhpFunction.ARG_FIRST + 1, a.length));
        });
        r.fn("call_user_func_array", (context, a) -> {
            Callables.Target target = Callables.parameter(context, Args.get(a, 0), CALL_USER_FUNC_ARRAY);
            return target.call(positional(Args.array(a, 1, "call_user_func_array", "args")));
        });
        r.fn("set_time_limit", a -> Boolean.TRUE);
        r.fn("getmypid", a -> ProcessHandle.current().pid());
        r.fn("ignore_user_abort", a -> 0L);
        // PG(connection_status): CONNECTION_NORMAL, the CLI's (a request's connection isn't watched)
        r.fn("php_sapi_name", (context, a) -> {
            Args.arity(a, "php_sapi_name", 0, 0);
            return Str.of(context.response() != null ? "cgi-fcgi" : "cli");
        });
        r.fn("ini_parse_quantity", (context, a) -> {
            Args.arity(a, "ini_parse_quantity", 1, 1);
            long[] result = new long[1];
            String error = Ini.parseQuantity(Str.java(Args.string(context, a, 0, "ini_parse_quantity", "shorthand",
                    false)), result);
            if (error != null) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(error), SourceLocation.current());
            }
            return result[0];
        });
        r.fn("connection_status", (context, a) -> {
            Args.arity(a, "connection_status", 0, 0);
            return 0L;
        });
        r.fn("connection_aborted", (context, a) -> {
            Args.arity(a, "connection_aborted", 0, 0);
            return 0L;
        });
        r.fn("flush", (context, a) -> {
            context.flush();
            return PhpNull.NULL;
        });

        // ---- HTTP headers and cookies: the CGI SAPI's response; php-cli sends none ----
        r.fn("header", (context, a) -> {
            if (context.response() != null && headersNotSent(context, "Cannot modify header information")) {
                context.response().header(Str.java(Args.str(a, 0)), Args.bool(a, 1, true), (int) Args.lng(a, 2, 0));
            }
            return PhpNull.NULL;
        });
        r.fn("header_remove", (context, a) -> {
            if (context.response() != null && headersNotSent(context, "Cannot modify header information")) {
                context.response().remove(Args.has(a, 0) && Args.get(a, 0) != PhpNull.NULL
                        ? Str.java(Args.str(a, 0)) : null);
            }
            return PhpNull.NULL;
        });
        // headers_sent(&$filename, &$line): where the output that sent them started
        r.fn("headers_sent", "11", (context, a) -> {
            boolean sent = context.response() != null && context.response().isSent();
            SourceLocation start = sent ? context.response().outputStart() : null;
            if (Args.has(a, 0)) {
                Args.ref(a, 0).set(start != null ? Str.of(start.file()) : Str.EMPTY);
            }
            if (Args.has(a, 1)) {
                Args.ref(a, 1).set(start != null ? (long) start.line() : 0L);
            }
            return sent;
        });
        r.fn("headers_list", (context, a) -> context.response() == null ? new PhpArray()
                : PhpArray.ofStrings(context.response().headers().toArray(new String[0])));
        r.fn("http_response_code", (context, a) -> {
            if (context.response() == null) {
                return Boolean.FALSE;
            }
            long previous = context.response().status();
            if (Args.has(a, 0) && Args.get(a, 0) != PhpNull.NULL) {
                // PHP_FUNCTION(http_response_code): no new code once the headers are out
                if (context.response().isSent()) {
                    SourceLocation start = context.response().outputStart();
                    ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(start != null
                            ? "http_response_code(): Cannot set response code - headers already sent (output started at " + start.file()
                            + ":" + start.line() + ")" : "http_response_code(): Cannot set response code - headers already sent"),
                            SourceLocation.current());
                    return Boolean.FALSE;
                }
                context.response().setStatus((int) Args.lng(a, 0, 200));
            }
            return previous;
        });
        r.fn("setcookie", (context, a) -> cookie(context, a, false));
        r.fn("setrawcookie", (context, a) -> cookie(context, a, true));

        // ---- environment and self-description ----
        r.fn("getenv", (context, a) -> getenv(context, a));
        r.fn("putenv", (context, a) -> putenv(context, a));
        r.fn("phpversion", a -> Args.has(a, 0) ? (Object) Boolean.FALSE : Str.of("8.5.12"));
        r.fn("php_uname", (context, a) -> {
            String mode = Args.has(a, 0) ? Str.java(Args.string(context, a, 0, "php_uname", "mode", false)) : "a";
            if (mode.length() != 1) {
                throw new PhpError("ValueError", "php_uname(): Argument #1 ($mode) must be a single character");
            }
            if ("amnrsv".indexOf(mode.charAt(0)) < 0) {
                throw new PhpError("ValueError", "php_uname(): Argument #1 ($mode) must be one of \"a\", \"m\", "
                        + "\"n\", \"r\", \"s\", or \"v\"");
            }
            return Str.of(uname(mode.charAt(0)));
        });
        r.fn("get_loaded_extensions", a -> PhpArray.ofStrings(EXTENSIONS.toArray(new String[0])));
        r.fn("error_log", (context, a) -> errorLog(context, Str.bytes(Args.str(a, 0))));
        // the trace without debug_backtrace's own frame (php-src's skip_last)
        r.fn("debug_backtrace", a -> Backtrace.here((int) Args.lng(a, 0, Backtrace.PROVIDE_OBJECT),
                (int) Args.lng(a, 1, 0), 1));
        r.fn("debug_print_backtrace", (context, a) -> {
            context.write(Backtrace.traceToString(Backtrace.here((int) Args.lng(a, 0, 0), (int) Args.lng(a, 1, 0), 1),
                    false));
            return PhpNull.NULL;
        });
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

    /**
     * clone(): a shallow copy (arrays copy on write), __clone() on the copy, then each of withProperties written
     * as the calling scope writes it (zend_objects_clone_obj_with), readonly ones writable once more.
     */
    @TruffleBoundary
    private static Object cloneObject(PhpContext context, Object value, Object withProperties) {
        if (!(value instanceof PhpObject object)) {
            throw new PhpError("TypeError", "clone(): Argument #1 ($object) must be of type object, "
                    + Convert.debugType(value) + " given");
        }
        if (withProperties != PhpNull.NULL && !(withProperties instanceof PhpArray)) {
            throw new PhpError("TypeError", "clone(): Argument #2 ($withProperties) must be of type array, "
                    + Convert.debugType(withProperties) + " given");
        }
        PhpClass.ObjectHandlers handlers = object.getPhpClass().getHandlers();
        if (handlers != null && handlers.uncloneable()) {
            throw new PhpError("Trying to clone an uncloneable object of class " + object.getPhpClass().getName());
        }
        PhpFunction cloner = object.getPhpClass().findMethod("__clone");
        if (cloner != null && !cloner.isPublic()) {
            // ZEND_CLONE checks __clone from the executing function's scope
            PhpClass self = Callables.callerClass(context, "self");
            String scope = self == null ? null : self.getLowerName();
            if (!object.getPhpClass().canCall(cloner, "__clone", scope)) {
                throw object.getPhpClass().callError(cloner.getName(), scope);
            }
        }
        if (handlers != null && handlers.cloneObject() != null) {
            // a built-in class's clone_obj
            return handlers.cloneObject().apply(object);
        }
        PhpObject copy = new PhpObject(object.getPhpClass().getRootShape(), object.getPhpClass(),
                context.nextObjectId());
        for (Object name : object.propertyNames()) {
            Object property = object.getProperty((String) name);
            // zval_add_ref: a reference only this property holds is copied as its value, a shared one is shared
            if (property instanceof PhpRef ref) {
                if (ref.isShared()) {
                    ref.bind();
                } else {
                    property = ref.get();
                }
            }
            if (property instanceof PhpArray array) {
                array.addHolder();
            }
            copy.initProperty((String) name, property);
        }
        if (copy.getPhpClass().findMethod("__clone") != null) {
            reinitable(copy, () -> copy.callMethod("__clone"));
        }
        if (withProperties instanceof PhpArray properties && properties.size() > 0) {
            PhpClass scope = Callables.callerClass(context, "self");
            PropNode write = PropNode.forOperands(null, scope == null ? null : scope.getLowerName(), false, false,
                    true);
            reinitable(copy, () -> {
                for (int p = properties.firstPosition(); p >= 0; p = properties.nextPosition(p)) {
                    Object property = properties.rawAt(p);
                    if (property instanceof PhpRef ref) {
                        if (ref.isShared()) {
                            throw new PhpError("Cannot assign by reference when cloning with updated properties");
                        }
                        property = ref.get();
                    }
                    write.writeOn(null, copy, Str.key(Convert.toStr(properties.keyAt(p))), property);
                }
                return null;
            });
        }
        return copy;
    }

    /** Runs code while each readonly property of the copy may be set once more (IS_PROP_REINITABLE). */
    private static void reinitable(PhpObject copy, java.util.function.Supplier<Object> code) {
        for (Object name : copy.propertyNames()) {
            if (copy.isReadonly((String) name)) {
                copy.mark("reinit:" + name);
            }
        }
        try {
            code.get();
        } finally {
            for (Object name : copy.propertyNames()) {
                copy.unmark("reinit:" + name);
            }
        }
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
    /** The environment variables, as putenv() left them. */
    static Map<String, String> environment(PhpContext context) {
        Map<String, String> changed = context.builtinState().environment;
        return changed != null ? changed : context.getEnv().getEnvironment();
    }

    /** putenv(): "NAME=value" sets, "NAME" unsets. */
    @TruffleBoundary
    private static Object putenv(PhpContext context, Object[] a) {
        Args.arity(a, "putenv", 1, 1);
        String setting = Str.java(Args.string(context, a, 0, "putenv", "assignment", false));
        if (setting.isEmpty() || setting.charAt(0) == '=') {
            throw new PhpError("ValueError", "putenv(): Argument #1 ($assignment) must have a valid syntax");
        }
        BuiltinState state = context.builtinState();
        if (state.environment == null) {
            state.environment = new java.util.HashMap<>(context.getEnv().getEnvironment());
        }
        int equals = setting.indexOf('=');
        if (equals < 0) {
            state.environment.remove(setting);
        } else {
            state.environment.put(setting.substring(0, equals), setting.substring(equals + 1));
        }
        return Boolean.TRUE;
    }

    private static Object getenv(PhpContext context, Object[] a) {
        Map<String, String> environment = environment(context);
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
        if (!headersNotSent(context, "Cannot modify header information")) {
            return Boolean.FALSE;
        }
        context.response().header(header, false, 0);
        return Boolean.TRUE;
    }

    /**
     * sapi_header_op's check: once the headers are sent, a warning where the output started (php_output_header kept
     * it), and the header isn't changed.
     */
    @TruffleBoundary
    private static boolean headersNotSent(PhpContext context, String what) {
        if (!context.response().isSent()) {
            return true;
        }
        SourceLocation start = context.response().outputStart();
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(start != null
                ? what + " - headers already sent by (output started at " + start.file() + ":" + start.line() + ")"
                : what + " - headers already sent"), SourceLocation.current());
        return false;
    }

}
