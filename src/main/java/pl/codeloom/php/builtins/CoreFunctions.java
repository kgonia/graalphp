package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/** Types, constants, classes and objects, output, time, math and error handling. */
final class CoreFunctions {
    private CoreFunctions() {
    }

    static void register(Registry r) {

        // ---- types ----
        r.fn("strlen", (context, a) -> {
            Args.arity(a, "strlen", 1, 1);
            return (long) Str.length(Args.string(context, a, 0, "strlen", "string", false));
        });
        r.fn("count", a -> count(Args.get(a, 0), Args.lng(a, 1, 0)));
        r.fn("sizeof", a -> count(Args.get(a, 0), Args.lng(a, 1, 0)));
        r.fn("is_array", a -> Args.get(a, 0) instanceof PhpArray);
        r.fn("is_string", a -> Args.get(a, 0) instanceof TruffleString);
        r.fn("is_int", a -> Args.get(a, 0) instanceof Long);
        r.fn("is_integer", a -> Args.get(a, 0) instanceof Long);
        r.fn("is_long", a -> Args.get(a, 0) instanceof Long);
        r.fn("is_float", a -> Args.get(a, 0) instanceof Double);
        r.fn("is_double", a -> Args.get(a, 0) instanceof Double);
        r.fn("is_bool", a -> Args.get(a, 0) instanceof Boolean);
        r.fn("is_null", a -> Args.get(a, 0, PhpNull.NULL) == PhpNull.NULL);
        r.fn("is_object", a -> Args.get(a, 0) instanceof PhpObject || Args.get(a, 0) instanceof PhpClosure);
        r.fn("is_scalar", a -> {
            Object v = Args.get(a, 0);
            return v instanceof Long || v instanceof Double || v instanceof TruffleString || v instanceof Boolean;
        });
        r.fn("is_numeric", a -> {
            Args.arity(a, "is_numeric", 1, 1);
            Object v = Args.get(a, 0);
            return v instanceof Long || v instanceof Double
                    || v instanceof TruffleString s && Convert.numericValue(s) != null;
        });
        r.fn("is_iterable", a -> Args.get(a, 0) instanceof PhpArray
                || Args.get(a, 0) instanceof PhpObject o && o.getPhpClass().isSubclassOf("traversable"));
        r.fn("is_countable", a -> Args.get(a, 0) instanceof PhpArray
                || Args.get(a, 0) instanceof PhpObject o && o.getPhpClass().isSubclassOf("countable"));
        r.fn("is_callable", (context, a) -> Callables.resolve(context, Args.get(a, 0, PhpNull.NULL)) != null);
        r.fn("is_finite", a -> Double.isFinite(Convert.toDouble(Args.get(a, 0))));
        r.fn("is_nan", a -> Double.isNaN(Convert.toDouble(Args.get(a, 0))));
        r.fn("is_infinite", a -> Double.isInfinite(Convert.toDouble(Args.get(a, 0))));
        r.fn("gettype", a -> Str.of(Convert.typeName(Args.get(a, 0, PhpNull.NULL))));
        r.fn("get_debug_type", a -> Str.of(Convert.debugType(Args.get(a, 0, PhpNull.NULL))));
        r.fn("intval", a -> intval(Args.get(a, 0), Args.lng(a, 1, 10)));
        r.fn("floatval", a -> Convert.toDouble(Args.get(a, 0)));
        r.fn("strval", a -> Convert.toStr(Args.get(a, 0)));
        r.fn("boolval", a -> Convert.toBool(Args.get(a, 0)));
        r.fn("settype", "1", a -> {
            PhpRef ref = Args.ref(a, 0);
            String type = Str.lower(Str.java(Args.str(a, 1)));
            Object v = ref.get();
            switch (type) {
                case "int", "integer" -> ref.set(Convert.toLong(v));
                case "float", "double" -> ref.set(Convert.toDouble(v));
                case "string" -> ref.set(Convert.toStr(v));
                case "bool", "boolean" -> ref.set(Convert.toBool(v));
                case "array" -> ref.set(v instanceof PhpArray ? v : v == PhpNull.NULL ? new PhpArray()
                        : PhpArray.list(v));
                case "null" -> ref.set(PhpNull.NULL);
                case "resource" -> throw new PhpError("ValueError", "Cannot convert to resource type");
                default -> throw new PhpError("ValueError", "settype(): Argument #2 ($type) must be a valid type");
            }
            return true;
        });

        // ---- constants, functions, classes ----
        r.fn("define", (context, a) -> context.defineConstant(Str.java(Args.str(a, 0)), Args.get(a, 1)));
        // a literal name without a namespace or class is compiled in place (ConstantNodes.DefinedNode)
        r.fn("defined", (context, a) -> isDefined(context, Str.java(Args.str(a, 0))));
        r.fn("constant", (context, a) -> {
            String name = Str.java(Args.str(a, 0));
            int separator = name.indexOf("::");
            Object value;
            if (separator > 0) {
                PhpClass phpClass = context.getClass(name.substring(0, separator));
                String constantName = name.substring(separator + 2);
                value = phpClass.getConstant(constantName);
                if (value != null && !phpClass.canAccessConstant(constantName, callerScope(context))) {
                    throw phpClass.constantAccessError(constantName);
                }
            } else {
                value = context.findConstant(name);
            }
            if (value == null) {
                throw new PhpError("Undefined constant \"" + name + "\"");
            }
            String deprecation = separator > 0 ? null : context.getLanguage().builtins().deprecation(name);
            if (deprecation != null) {
                ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of(deprecation),
                        SourceLocation.current());
            }
            return value;
        });
        // compiled in place where a call names them; called by a name resolved at run time they refuse, as
        // zend_forbid_dynamic_call does
        for (String frameFunction : new String[] {"func_get_arg", "func_get_args", "func_num_args"}) {
            int arity = frameFunction.equals("func_get_arg") ? 1 : 0;
            r.fn(frameFunction, a -> {
                Args.arity(a, frameFunction, arity, arity);
                throw new PhpError("Error", "Cannot call " + frameFunction + "() dynamically");
            });
        }
        r.fn("function_exists", (context, a) -> context.findFunction(lowerName(Args.str(a, 0))) != null);
        r.fn("get_defined_functions", (context, a) -> {
            List<List<PhpFunction>> declared = context.declaredFunctions();
            PhpArray result = new PhpArray();
            result.put(ArrayKey.of(Str.of("internal")), functionNames(declared.get(0)));
            result.put(ArrayKey.of(Str.of("user")), functionNames(declared.get(1)));
            return result;
        });
        r.fn("get_declared_classes", (context, a) -> classNames(context, Kind.CLASS));
        r.fn("get_declared_interfaces", (context, a) -> classNames(context, Kind.INTERFACE));
        r.fn("get_declared_traits", (context, a) -> classNames(context, Kind.TRAIT));
        r.fn("class_exists", (context, a) -> classExists(context, Args.str(a, 0), Kind.CLASS, Args.bool(a, 1, true)));
        r.fn("interface_exists", (context, a) -> classExists(context, Args.str(a, 0), Kind.INTERFACE,
                Args.bool(a, 1, true)));
        r.fn("trait_exists", (context, a) -> classExists(context, Args.str(a, 0), Kind.TRAIT, Args.bool(a, 1, true)));
        r.fn("class_alias", (context, a) -> classAlias(context, Args.str(a, 0), Args.str(a, 1), Args.bool(a, 2, true)));
        r.fn("spl_autoload_register", (context, a) -> {
            Object callable = Args.get(a, 0, PhpNull.NULL);
            if (callable == PhpNull.NULL) {
                throw new PhpError("spl_autoload_register() without a callback (spl_autoload) is not supported");
            }
            Callables.check(context, callable, new Callables.Parameter("spl_autoload_register", 1, true));
            context.addAutoloader(callable, Args.bool(a, 2, false));
            return Boolean.TRUE;
        });
        r.fn("spl_autoload_unregister", (context, a) -> context.removeAutoloader(Args.get(a, 0)));
        r.fn("spl_autoload_functions", (context, a) -> PhpArray.list(context.autoloaders().toArray()));
        r.fn("method_exists", (context, a) -> methodExists(context, Args.get(a, 0), Args.str(a, 1)));
        r.fn("property_exists", (context, a) -> {
            Object target = Args.get(a, 0);
            String name = Str.key(Args.str(a, 1));
            if (target instanceof PhpObject o) {
                return o.getProperty(name) != null || hasDeclared(o.getPhpClass(), name);
            }
            if (!(target instanceof TruffleString className)) {
                throw Args.typeError("property_exists", 1, "object_or_class", "object|string", target);
            }
            // a class name is autoloaded (zend_lookup_class); a parent's private property isn't the class's
            PhpClass phpClass = context.findClassAutoloading(Str.java(className));
            return phpClass != null && hasDeclared(phpClass, name);
        });
        r.fn("get_class", a -> {
            Object target = Args.get(a, 0);
            if (target instanceof PhpObject o) {
                return Str.of(o.getPhpClass().getName());
            }
            if (target instanceof PhpClosure) {
                return Str.of("Closure");
            }
            if (target != null) {
                throw Args.typeError("get_class", 1, "object", "object", target);
            }
            throw new PhpError("get_class(): Argument #1 ($object) must be of type object");
        });
        r.fn("get_parent_class", (context, a) -> {
            Object target = Args.get(a, 0);
            // Z_PARAM_OBJ_OR_CLASS_NAME: a class name is autoloaded, and must name a class
            PhpClass phpClass = target instanceof PhpObject o ? o.getPhpClass()
                    : target instanceof TruffleString name ? context.findClassAutoloading(Str.java(name)) : null;
            if (phpClass == null && target != null && !(target instanceof PhpClosure)) {
                throw new PhpError("TypeError", "get_parent_class(): Argument #1 ($object_or_class) must be an object "
                        + "or a valid class name, " + Args.valueName(target) + " given");
            }
            return phpClass == null || phpClass.getParent() == null ? Boolean.FALSE
                    : Str.of(phpClass.getParent().getName());
        });
        r.fn("get_object_vars", a -> objectVars(Args.get(a, 0)));
        r.fn("get_mangled_object_vars", a -> {
            Object value = Args.get(a, 0);
            if (value instanceof PhpObject object) {
                return object.toArray();
            }
            if (value instanceof pl.codeloom.php.runtime.PhpClosure) {
                // a Closure has no properties
                return new PhpArray();
            }
            throw Args.typeError("get_mangled_object_vars", 1, "object", "object", value);
        });
        r.fn("spl_object_id", a -> (long) objectId(Args.get(a, 0)));
        r.fn("spl_object_hash", a -> Str.of(String.format("%032x", objectId(Args.get(a, 0)))));
        r.fn("is_a", (context, a) -> isA(context, Args.get(a, 0), Args.str(a, 1), Args.bool(a, 2, false), false));
        r.fn("is_subclass_of", (context, a) -> isA(context, Args.get(a, 0), Args.str(a, 1), Args.bool(a, 2, true),
                true));

        // ---- output ----
        r.fn("ob_start", (context, a) -> {
            Object handler = Args.get(a, 0);
            if (handler != null && handler != PhpNull.NULL && Callables.resolve(context, handler) == null) {
                // php-src warns "ob_start(): function ... not found" and starts no buffer
                return false;
            }
            context.startBuffer(handler == null || handler == PhpNull.NULL ? null : handler,
                    Args.count(a) > 2 ? Convert.toLong(Args.get(a, 2))
                            : PhpContext.CLEANABLE | PhpContext.FLUSHABLE | PhpContext.REMOVABLE);
            return true;
        });
        r.fn("ob_get_contents", (context, a) -> {
            TruffleString contents = context.bufferContents();
            return contents == null ? Boolean.FALSE : contents;
        });
        r.fn("ob_get_clean", (context, a) -> {
            TruffleString contents = context.bufferContents();
            if (contents == null || !context.bufferAllows(PhpContext.REMOVABLE)) {
                return Boolean.FALSE;
            }
            context.endBuffer();
            return contents;
        });
        r.fn("ob_end_clean", (context, a) -> context.bufferAllows(PhpContext.REMOVABLE) && context.endBuffer());
        r.fn("ob_clean", (context, a) -> {
            if (!context.bufferAllows(PhpContext.CLEANABLE)) {
                return false;
            }
            context.cleanBuffer();
            return true;
        });
        r.fn("ob_end_flush", (context, a) -> context.bufferAllows(PhpContext.REMOVABLE) && context.flushBuffer());
        r.fn("ob_get_flush", (context, a) -> {
            TruffleString contents = context.bufferContents();
            if (contents == null || !context.bufferAllows(PhpContext.REMOVABLE)) {
                return Boolean.FALSE;
            }
            context.flushBuffer();
            return contents;
        });
        r.fn("ob_flush", (context, a) -> {
            if (!context.bufferAllows(PhpContext.FLUSHABLE)) {
                return false;
            }
            context.flushBufferContents();
            return true;
        });
        r.fn("ob_get_level", (context, a) -> (long) context.bufferLevel());
        r.fn("ob_get_length", (context, a) -> {
            TruffleString contents = context.bufferContents();
            return contents == null ? (Object) Boolean.FALSE : (long) Str.length(contents);
        });
        r.fn("ob_implicit_flush", a -> PhpNull.NULL);
        r.fn("ob_list_handlers", (context, a) -> {
            PhpArray handlers = new PhpArray();
            for (String name : context.bufferHandlerNames()) {
                handlers.append(Str.of(name));
            }
            return handlers;
        });
        r.fn("var_dump", (context, a) -> {
            for (int i = 0; i < Args.count(a); i++) {
                context.write(Dump.varDump(Args.get(a, i)));
            }
            return PhpNull.NULL;
        });
        r.fn("var_export", (context, a) -> {
            TruffleString text = Dump.varExport(Args.get(a, 0));
            if (Args.bool(a, 1, false)) {
                return text;
            }
            context.write(text);
            return PhpNull.NULL;
        });
        r.fn("print_r", (context, a) -> {
            TruffleString text = Dump.printR(Args.get(a, 0));
            if (Args.bool(a, 1, false)) {
                return text;
            }
            context.write(text);
            return true;
        });

        // ---- errors and configuration ----
        r.fn("extension_loaded", a -> RuntimeFunctions.EXTENSIONS.contains(Str.lower(Str.java(Args.str(a, 0)))));
        r.fn("version_compare", a -> {
            int c = VersionCompare.compare(Str.java(Args.str(a, 0)), Str.java(Args.str(a, 1)));
            if (!Args.has(a, 2)) {
                return (long) c;
            }
            return VersionCompare.test(c, Str.java(Args.str(a, 2)));
        });

        // ---- time ----
        r.fn("hrtime", a -> {
            long nanos = System.nanoTime();
            if (Args.bool(a, 0, false)) {
                return nanos;
            }
            return PhpArray.list(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
        });
        r.fn("time", a -> System.currentTimeMillis() / 1000);
        r.fn("microtime", a -> {
            long micros = pl.codeloom.php.runtime.WallClock.micros();
            if (Args.bool(a, 0, false)) {
                return micros / 1e6;
            }
            return Str.of(String.format(Locale.ROOT, "%.8f %d", (micros % 1_000_000) / 1e6, micros / 1_000_000));
        });
        // ext/standard/basic_functions.c: the request's thread sleeps (php_sleep, usleep, nanosleep)
        r.fn("sleep", a -> {
            long seconds = Args.lng(a, 0, 0);
            if (seconds < 0) {
                throw new PhpError("ValueError", "sleep(): Argument #1 ($seconds) must be greater than or equal to 0");
            }
            sleepNanos(seconds * 1_000_000_000L);
            return 0L;
        });
        r.fn("usleep", a -> {
            long micros = Args.lng(a, 0, 0);
            if (micros < 0) {
                throw new PhpError("ValueError",
                        "usleep(): Argument #1 ($microseconds) must be greater than or equal to 0");
            }
            sleepNanos(micros * 1000L);
            return PhpNull.NULL;
        });
        r.fn("time_nanosleep", a -> {
            long seconds = Args.lng(a, 0, 0);
            long nanos = Args.lng(a, 1, 0);
            if (seconds < 0) {
                throw new PhpError("ValueError",
                        "time_nanosleep(): Argument #1 ($seconds) must be greater than or equal to 0");
            }
            if (nanos < 0) {
                throw new PhpError("ValueError",
                        "time_nanosleep(): Argument #2 ($nanoseconds) must be greater than or equal to 0");
            }
            if (nanos > 999_999_999L) {
                // nanosleep's EINVAL
                throw new PhpError("ValueError",
                        "Nanoseconds was not in the range 0 to 999 999 999 or seconds was negative");
            }
            sleepNanos(seconds * 1_000_000_000L + nanos);
            return true;
        });
        r.fn("time_sleep_until", (context, a) -> {
            double until = Convert.toDouble(Args.get(a, 0));
            double now = pl.codeloom.php.runtime.WallClock.micros() / 1e6;
            if (until < now) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("time_sleep_until(): Argument #1"
                        + " ($timestamp) must be greater than or equal to the current time"),
                        pl.codeloom.php.runtime.SourceLocation.current());
                return false;
            }
            sleepNanos((long) ((until - now) * 1e9));
            return true;
        });

        // ---- math ----
        r.fn("abs", (context, a) -> {
            Object v = Args.number(context, a, 0, "abs", "num");
            if (v instanceof Long l) {
                return l == Long.MIN_VALUE ? -(double) l : (Object) Math.abs(l);
            }
            return Math.abs((Double) v);
        });
        r.fn("max", a -> minMax(a, true));
        r.fn("min", a -> minMax(a, false));
        r.fn("floor", (context, a) -> Math.floor(Convert.toDouble(Args.number(context, a, 0, "floor", "num"))));
        r.fn("ceil", (context, a) -> Math.ceil(Convert.toDouble(Args.number(context, a, 0, "ceil", "num"))));
        r.fn("round", (context, a) -> {
            Object value = Args.number(context, a, 0, "round", "num");
            long precision = Args.has(a, 1) ? Args.integer(context, a, 1, "round", "precision", "int") : 0;
            long mode = Args.has(a, 2) ? Args.integer(context, a, 2, "round", "mode", "RoundingMode|int") : 1;
            if (mode < 1 || mode > 8) {
                throw new PhpError("ValueError", "round(): Argument #3 ($mode) must be a valid rounding mode "
                        + "(RoundingMode::*)");
            }
            int places = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, precision));
            return value instanceof Long l && places >= 0 ? (double) l
                    : round(Convert.toDouble(value), places, (int) mode);
        });
        r.fn("sqrt", a -> Math.sqrt(Convert.toDouble(Args.get(a, 0))));
        r.fn("log", a -> Args.has(a, 1) ? Math.log(Convert.toDouble(Args.get(a, 0)))
                / Math.log(Convert.toDouble(Args.get(a, 1))) : Math.log(Convert.toDouble(Args.get(a, 0))));
        r.fn("log10", a -> Math.log10(Convert.toDouble(Args.get(a, 0))));
        r.fn("log2", a -> Math.log(Convert.toDouble(Args.get(a, 0))) / Math.log(2));
        r.fn("exp", a -> Math.exp(Convert.toDouble(Args.get(a, 0))));
        r.fn("pi", a -> Math.PI);
        r.fn("bindec", (context, a) -> baseToNumber(context, Args.string(context, a, 0, "bindec", "binary_string",
                false), 2));
        r.fn("mt_getrandmax", a -> 2147483647L);
        r.fn("getrandmax", a -> 2147483647L);
        r.fn("lcg_value", a -> Math.random());
        r.fn("pow", a -> {
            Args.arity(a, "pow", 2, 2);
            return pl.codeloom.php.runtime.Operators.pow(null, Args.get(a, 0), Args.get(a, 1));
        });
        r.fn("intdiv", a -> {
            long divisor = Args.lng(a, 1, 1);
            if (divisor == 0) {
                throw new PhpError("DivisionByZeroError", "Division by zero");
            }
            long dividend = Args.lng(a, 0, 0);
            if (dividend == Long.MIN_VALUE && divisor == -1) {
                throw new PhpError("ArithmeticError", "Division of PHP_INT_MIN by -1 is not an integer");
            }
            return dividend / divisor;
        });
        r.fn("fmod", a -> Convert.toDouble(Args.get(a, 0)) % Convert.toDouble(Args.get(a, 1)));
        r.fn("dechex", (context, a) -> Str.of(Long.toHexString(Args.integer(context, a, 0, "dechex", "num", "int"))));
        r.fn("hexdec", (context, a) -> baseToNumber(context, Args.string(context, a, 0, "hexdec", "hex_string", false),
                16));
        r.fn("decbin", (context, a) -> Str.of(Long.toBinaryString(Args.integer(context, a, 0, "decbin", "num",
                "int"))));
        r.fn("decoct", (context, a) -> Str.of(Long.toOctalString(Args.integer(context, a, 0, "decoct", "num",
                "int"))));
        r.fn("octdec", (context, a) -> baseToNumber(context, Args.string(context, a, 0, "octdec", "octal_string",
                false), 8));
        r.fn("mt_rand", a -> Args.has(a, 1)
                ? Args.lng(a, 0, 0) + (long) (Math.random() * (Args.lng(a, 1, 0) - Args.lng(a, 0, 0) + 1))
                : (long) (Math.random() * Integer.MAX_VALUE));
        r.fn("rand", a -> Args.has(a, 1)
                ? Args.lng(a, 0, 0) + (long) (Math.random() * (Args.lng(a, 1, 0) - Args.lng(a, 0, 0) + 1))
                : (long) (Math.random() * Integer.MAX_VALUE));

        // ---- hashing ----
        r.fn("crc32", a -> crc32(Args.str(a, 0)));
        r.fn("md5", a -> EncodingFunctions.digest("MD5", Str.readBytes(Args.str(a, 0)), Args.bool(a, 1, false)));
        r.fn("sha1", a -> EncodingFunctions.digest("SHA-1", Str.readBytes(Args.str(a, 0)), Args.bool(a, 1, false)));

    }

    /** The thread sleeps; an interrupt (the server stopping) ends the sleep early, as a signal does in PHP. */
    @TruffleBoundary
    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * defined(): zend_get_constant_ex(name, the caller's scope, ZEND_FETCH_CLASS_SILENT) != NULL. "A::B" is a class
     * constant, its class autoloaded and its value evaluated; a namespaced name's namespace is caseless; true, false
     * and null are defined in any case. A literal name without a namespace or class doesn't come here.
     */
    @TruffleBoundary
    /** The scope (lowercase class, or null) of the PHP code calling a built-in. */
    private static String callerScope(PhpContext context) {
        PhpClass self = Callables.callerClass(context, "self");
        return self == null ? null : self.getLowerName();
    }

    private static boolean isDefined(PhpContext context, String constantName) {
        String name = constantName.startsWith("\\") ? constantName.substring(1) : constantName;
        int colon = name.lastIndexOf(':');
        if (colon > 1 && name.charAt(colon - 1) == ':') {
            String className = name.substring(0, colon - 1);
            String lower = Str.lower(className);
            PhpClass phpClass;
            if (lower.equals("self") || lower.equals("parent") || lower.equals("static")) {
                phpClass = Callables.callerClass(context, lower);
                if (phpClass == null) {
                    PhpClass self = lower.equals("parent") ? Callables.callerClass(context, "self") : null;
                    throw new PhpError(self != null ? "Cannot access \"parent\" when current class scope has no parent"
                            : "Cannot access \"" + lower + "\" when no class scope is active");
                }
            } else {
                phpClass = context.findClassAutoloading(className);
            }
            // a trait's constants are reached through the classes that use it only (zend_get_class_constant_ex)
            String member = name.substring(colon + 1);
            return phpClass != null && !(phpClass.getDeclaration() instanceof ClassTemplate template
                    && template.isTrait()) && phpClass.getConstant(member) != null
                    && phpClass.canAccessConstant(member, callerScope(context));
        }
        if (context.findConstant(name) != null) {
            return true;
        }
        // zend_get_special_const, for a name without a namespace
        String lower = Str.lower(name);
        return lower.equals("true") || lower.equals("false") || lower.equals("null");
    }

    @TruffleBoundary
    private static Object count(Object value, long mode) {
        if (value instanceof PhpArray array) {
            if (mode == 1) {
                long total = 0;
                for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                    total++;
                    Object element = array.valueAt(p);
                    if (element instanceof PhpArray) {
                        total += (Long) count(element, 1);
                    }
                }
                return total;
            }
            return (long) array.size();
        }
        if (value instanceof PhpObject object && object.getPhpClass().isSubclassOf("countable")) {
            return Convert.toLong(object.callMethod("count"));
        }
        throw new PhpError("TypeError", "count(): Argument #1 ($value) must be of type Countable|array, "
                + Args.valueName(value == null ? PhpNull.NULL : value) + " given");
    }

    @TruffleBoundary
    private static String lowerName(TruffleString name) {
        String text = Str.java(name);
        return Str.lower(text.startsWith("\\") ? text.substring(1) : text);
    }

    /** The names zend_assert_valid_class_name refuses (reserved_class_names), unqualified, in lower case. */
    private static final java.util.Set<String> RESERVED_CLASS_NAMES = java.util.Set.of("bool", "false", "float",
            "int", "null", "parent", "self", "static", "string", "true", "void", "never", "iterable", "object", "mixed",
            "array", "callable");

    /**
     * class_alias(): the class (autoloaded unless told not to) under one more name. A name in use warns as
     * zend_class_redeclaration_error_ex does, naming the aliased class's declaration; a reserved one is a compile
     * error.
     */
    @TruffleBoundary
    private static boolean classAlias(PhpContext context, TruffleString className, TruffleString aliasName,
                                      boolean autoload) {
        String name = Str.java(className);
        PhpClass phpClass = autoload ? context.findClassAutoloading(name) : context.findClass(lowerName(className));
        if (phpClass == null) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("Class \"" + name + "\" not found"),
                    pl.codeloom.php.runtime.SourceLocation.current());
            return false;
        }
        String alias = Str.java(aliasName);
        String lowerAlias = lowerName(aliasName);
        if (RESERVED_CLASS_NAMES.contains(lowerAlias.substring(lowerAlias.lastIndexOf('\\') + 1))) {
            throw ErrorFunctions.fatal(context, ErrorFunctions.E_COMPILE_ERROR, Str.of("Cannot use \"" + lowerAlias
                    + "\" as a class alias as it is reserved"), pl.codeloom.php.runtime.SourceLocation.current(), 0);
        }
        if (lowerAlias.equals("_")) {
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED,
                    Str.of("Using \"_\" as a class alias is deprecated since 8.4"),
                    pl.codeloom.php.runtime.SourceLocation.current());
        }
        if (context.declareAlias(lowerAlias, phpClass)) {
            return true;
        }
        Kind kind = kindOf(phpClass);
        String type = kind == Kind.TRAIT ? "trait" : kind == Kind.INTERFACE ? "interface" : "class";
        pl.codeloom.php.runtime.SourceLocation declared = phpClass.getDeclaration()
                instanceof pl.codeloom.php.runtime.ClassTemplate template ? template.getDeclaredAt() : null;
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("Cannot redeclare " + type + " " + alias
                + (declared == null ? "" : " (previously declared in " + declared.file() + ":" + declared.line() + ")")),
                pl.codeloom.php.runtime.SourceLocation.current());
        return false;
    }

    /** class_exists_impl: the class, interface or trait under the name, which only the asked kind satisfies. */
    private static boolean classExists(PhpContext context, TruffleString name, Kind kind, boolean autoload) {
        PhpClass phpClass = autoload ? context.findClassAutoloading(Str.java(name)) : context.findClass(lowerName(name));
        return phpClass != null && kindOf(phpClass) == kind;
    }

    private static Kind kindOf(PhpClass phpClass) {
        boolean trait = phpClass.getDeclaration() instanceof pl.codeloom.php.runtime.ClassTemplate template
                && template.isTrait();
        return trait ? Kind.TRAIT : phpClass.isInterface() ? Kind.INTERFACE : Kind.CLASS;
    }

    /** get_defined_functions()' lists: names in lowercase, as php-src keys its function table. */
    @TruffleBoundary
    private static PhpArray functionNames(List<PhpFunction> functions) {
        PhpArray names = new PhpArray();
        for (PhpFunction function : functions) {
            names.append(Str.of(Str.lower(function.getName())));
        }
        return names;
    }

    private enum Kind { CLASS, INTERFACE, TRAIT }

    /** get_declared_classes(), _interfaces() or _traits(): the names as declared. */
    @TruffleBoundary
    private static PhpArray classNames(PhpContext context, Kind kind) {
        PhpArray names = new PhpArray();
        for (java.util.Map.Entry<String, PhpClass> entry : context.declaredClasses()) {
            if (kindOf(entry.getValue()) == kind) {
                names.append(Str.of(entry.getKey()));
            }
        }
        return names;
    }

    @TruffleBoundary
    private static boolean hasDeclared(PhpClass phpClass, String name) {
        for (PhpClass.PropertyDecl decl : phpClass.getProperties()) {
            if (decl.name().equals(name)) {
                return decl.visibility() != PhpClass.PropertyDecl.PRIVATE || decl.declaringClass() == null
                        || decl.declaringClass().equals(phpClass.getLowerName());
            }
        }
        return phpClass.getStaticProperty(name) != null;
    }

    /**
     * get_object_vars called as a callback, without the caller's scope: the public properties. A direct call is
     * translated to {@link pl.codeloom.php.nodes.vars.ObjectVarsNode}, which has it.
     */
    @TruffleBoundary
    private static PhpArray objectVars(Object value) {
        return value instanceof PhpObject object ? object.visibleProperties(null) : new PhpArray();
    }

    private static int objectId(Object value) {
        if (value instanceof PhpObject object) {
            return object.id();
        }
        return System.identityHashCode(value);
    }

    /**
     * is_a_impl: is_a() and is_subclass_of(). A class name given as the value (allow_string) is autoloaded, the
     * class tested against isn't; is_subclass_of() is false for the class itself.
     */
    private static boolean isA(PhpContext context, Object value, TruffleString className, boolean allowString,
                               boolean onlySubclass) {
        PhpClass instance;
        if (allowString && value instanceof TruffleString name) {
            instance = context.findClassAutoloading(Str.java(name));
            if (instance == null) {
                return false;
            }
        } else if (value instanceof PhpObject object) {
            instance = object.getPhpClass();
        } else if (value instanceof PhpClosure) {
            instance = context.findClass("closure");
        } else {
            return false;
        }
        if (!onlySubclass && instance.getName().equals(Str.java(className))) {
            return true;
        }
        PhpClass target = context.findClass(lowerName(className));
        if (target == null || onlySubclass && target == instance) {
            return false;
        }
        return instance.isSubclassOf(target.getLowerName());
    }

    /**
     * method_exists(): a class name is autoloaded (zend_lookup_class); a parent's private method isn't the named
     * class's, while an object has every method it has; Closure has __invoke.
     */
    @TruffleBoundary
    private static boolean methodExists(PhpContext context, Object target, TruffleString methodName) {
        PhpClass phpClass;
        if (target instanceof PhpObject object) {
            phpClass = object.getPhpClass();
        } else if (target instanceof PhpClosure) {
            phpClass = context.findClass("closure");
        } else if (target instanceof TruffleString name) {
            phpClass = context.findClassAutoloading(Str.java(name));
            if (phpClass == null) {
                return false;
            }
        } else {
            throw Args.typeError("method_exists", 1, "object_or_class", "object|string", target);
        }
        String lower = Str.lower(Str.java(methodName));
        PhpFunction method = phpClass.findMethod(lower);
        if (method != null) {
            return !(target instanceof TruffleString) || (method.getModifiers() & PhpFunction.ACC_PRIVATE) == 0
                    || phpClass.getName().equals(method.getClassName());
        }
        return phpClass.getLowerName().equals("closure") && lower.equals("__invoke");
    }

    @TruffleBoundary
    private static Object intval(Object value, long base) {
        if (base != 10 && value instanceof TruffleString s) {
            String text = Str.lower(Str.java(s).trim());
            boolean negative = text.startsWith("-");
            if (negative || text.startsWith("+")) {
                text = text.substring(1);
            }
            if (base == 16 && text.startsWith("0x") || base == 8 && text.startsWith("0o")
                    || base == 2 && text.startsWith("0b")) {
                text = text.substring(2);
            }
            int end = 0;
            while (end < text.length() && Character.digit(text.charAt(end), (int) base) >= 0) {
                end++;
            }
            if (end == 0) {
                return 0L;
            }
            long result = Long.parseLong(text.substring(0, end), (int) base);
            return negative ? -result : result;
        }
        return Convert.toLong(value);
    }

    @TruffleBoundary
    private static Object minMax(Object[] arguments, boolean max) {
        Object[] values;
        if (Args.count(arguments) == 1 && Args.get(arguments, 0) instanceof PhpArray array) {
            values = new Object[array.size()];
            int i = 0;
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                values[i++] = array.valueAt(p);
            }
        } else {
            values = new Object[Args.count(arguments)];
            for (int i = 0; i < values.length; i++) {
                values[i] = Args.get(arguments, i);
            }
        }
        if (values.length == 0) {
            throw new PhpError("ValueError", (max ? "max" : "min") + "(): Argument #1 ($value) must contain at "
                    + "least one element");
        }
        Object best = values[0];
        for (int i = 1; i < values.length; i++) {
            int c = Convert.compare(values[i], best);
            if (max ? c > 0 : c < 0) {
                best = values[i];
            }
        }
        return best;
    }

    /** php_intpow10. */
    private static double intpow10(int power) {
        return power < 0 || power > 22 ? Math.pow(10.0, power) : POWERS_OF_TEN[power];
    }

    private static final double[] POWERS_OF_TEN = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
        1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    /** _php_math_round: to places decimal places in one of the PHP_ROUND_* modes (1 to 8). */
    @TruffleBoundary
    static double round(double value, int places, int mode) {
        if (!Double.isFinite(value) || value == 0.0 || places == 0 && value == Math.rint(value)) {
            return value;
        }
        places = Math.max(places, Integer.MIN_VALUE + 1);
        double exponent = intpow10(Math.abs(places));
        double tmp;
        double tmp2;
        if (value >= 0.0) {
            tmp = Math.floor(places > 0 ? value * exponent : value / exponent);
            tmp2 = tmp + 1.0;
        } else {
            tmp = Math.ceil(places > 0 ? value * exponent : value / exponent);
            tmp2 = tmp - 1.0;
        }
        if ((places > 0 ? tmp2 / exponent : tmp2 * exponent) == value) {
            tmp = tmp2;
        }
        if (Math.abs(tmp) >= 1e16) {
            return value;
        }
        tmp = roundHelper(tmp, value, exponent, places, mode);
        if (Math.abs(places) < 23) {
            return places > 0 ? tmp / exponent : tmp * exponent;
        }
        // snprintf("%15fe%d") and zend_strtod: the nearest double to the decimal
        double parsed = new BigDecimal(String.format(Locale.ROOT, "%.6f", tmp)).scaleByPowerOfTen(-places)
                .doubleValue();
        return Double.isFinite(parsed) ? parsed : value;
    }

    /** php_round_helper: integral rounded in the mode, value its unscaled original. */
    private static double roundHelper(double integral, double value, double exponent, int places, int mode) {
        double abs = Math.abs(value);
        double halfUp = integral + Math.copySign(0.5, integral);
        double half = Math.abs(places > 0 ? halfUp / exponent : halfUp * exponent);
        double zero = Math.abs(places > 0 ? integral / exponent : integral * exponent);
        double away = integral + Math.copySign(1.0, integral);
        return switch (mode) {
            case 1 -> abs >= half ? away : integral;
            case 2 -> abs > half ? away : integral;
            case 3 -> abs > half || abs == half && integral % 2.0 != 0 ? away : integral;
            case 4 -> abs > half || abs == half && integral % 2.0 == 0 ? away : integral;
            case 5 -> value > 0.0 && abs > zero ? integral + 1.0 : integral;
            case 6 -> value < 0.0 && abs > zero ? integral - 1.0 : integral;
            case 7 -> integral;
            default -> abs > zero ? away : integral;
        };
    }

    /**
     * _php_math_basetozval: the base's digits after whitespace and a 0x/0o/0b prefix, others ignored (deprecated),
     * an int until it overflows into a float.
     */
    @TruffleBoundary
    static Object baseToNumber(PhpContext context, TruffleString string, int base) {
        byte[] s = Str.readBytes(string);
        int i = 0;
        int end = Str.length(string);
        while (i < end && isCSpace(s[i])) {
            i++;
        }
        while (i < end && isCSpace(s[end - 1])) {
            end--;
        }
        if (end - i >= 2 && s[i] == '0') {
            int prefix = s[i + 1] | 0x20;
            if (base == 16 && prefix == 'x' || base == 8 && prefix == 'o' || base == 2 && prefix == 'b') {
                i += 2;
            }
        }
        long cutoff = Long.MAX_VALUE / base;
        long cutlim = Long.MAX_VALUE % base;
        long num = 0;
        double fnum = 0;
        boolean isFloat = false;
        boolean invalid = false;
        for (; i < end; i++) {
            int c = s[i];
            c = c >= '0' && c <= '9' ? c - '0' : c >= 'A' && c <= 'Z' ? c - 'A' + 10 : c >= 'a' && c <= 'z'
                    ? c - 'a' + 10 : 36;
            if (c >= base) {
                invalid = true;
                continue;
            }
            if (!isFloat) {
                if (num < cutoff || num == cutoff && c <= cutlim) {
                    num = num * base + c;
                    continue;
                }
                fnum = num;
                isFloat = true;
            }
            fnum = fnum * base + c;
        }
        if (invalid) {
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of("Invalid characters passed for "
                    + "attempted conversion, these have been ignored"), SourceLocation.current());
        }
        return isFloat ? (Object) fnum : (Object) num;
    }

    /** C's isspace in the C locale. */
    private static boolean isCSpace(byte b) {
        return b == ' ' || b >= '\t' && b <= '\r';
    }

    @TruffleBoundary
    private static long crc32(TruffleString s) {
        CRC32 crc = new CRC32();
        crc.update(Str.readBytes(s));
        return crc.getValue();
    }

}
