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
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/** Types, constants, classes and objects, output, time, math and error handling. */
final class CoreFunctions {
    private CoreFunctions() {
    }

    static void register(Registry r) {

        // ---- types ----
        r.fn("strlen", a -> (long) Str.length(Args.str(a, 0)));
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
            Object v = Args.get(a, 0);
            return v instanceof Long || v instanceof Double
                    || v instanceof TruffleString s && Convert.numericValue(s) != null;
        });
        r.fn("is_iterable", a -> Args.get(a, 0) instanceof PhpArray
                || Args.get(a, 0) instanceof PhpObject o && o.getPhpClass().isSubclassOf("traversable"));
        r.fn("is_countable", a -> Args.get(a, 0) instanceof PhpArray
                || Args.get(a, 0) instanceof PhpObject o && o.getPhpClass().isSubclassOf("countable"));
        r.fn("is_callable", (context, a) -> Callables.resolve(context, Args.get(a, 0, PhpNull.NULL)) != null);
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
                default -> throw new PhpError("settype(): Invalid type");
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
            Object value = separator > 0 ? context.getClass(name.substring(0, separator))
                    .getConstant(name.substring(separator + 2)) : context.findConstant(name);
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
        r.fn("get_class_methods", (context, a) -> {
            Args.arity(a, "get_class_methods", 1, 1);
            Object target = Args.get(a, 0);
            PhpClass phpClass = target instanceof PhpObject o ? o.getPhpClass()
                    : target instanceof PhpClosure ? context.findClass("closure")
                    : target instanceof TruffleString name ? context.findClassAutoloading(Str.java(name)) : null;
            if (phpClass == null) {
                throw new PhpError("TypeError", "get_class_methods(): Argument #1 ($object_or_class) must be an "
                        + "object or a valid class name, " + Args.valueName(target) + " given");
            }
            return classMethods(context, phpClass);
        });
        r.fn("get_class_vars", (context, a) -> {
            Args.arity(a, "get_class_vars", 1, 1);
            TruffleString name = Convert.toStr(Args.get(a, 0));
            PhpClass phpClass = context.findClassAutoloading(Str.java(name));
            if (phpClass == null) {
                throw new PhpError("TypeError", "get_class_vars(): Argument #1 ($class) must be a valid class name, "
                        + Str.java(name) + " given");
            }
            return classVars(context, phpClass);
        });
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
        r.fn("abs", a -> {
            Object v = Convert.toNumber(Args.get(a, 0));
            if (v instanceof Long l) {
                return l == Long.MIN_VALUE ? -(double) l : (Object) Math.abs(l);
            }
            return Math.abs((Double) v);
        });
        r.fn("max", a -> minMax(a, true));
        r.fn("min", a -> minMax(a, false));
        r.fn("floor", a -> Math.floor(Convert.toDouble(Args.get(a, 0))));
        r.fn("ceil", a -> Math.ceil(Convert.toDouble(Args.get(a, 0))));
        r.fn("round", a -> round(Convert.toNumber(Args.get(a, 0)), Args.lng(a, 1, 0)));
        // math.c: the C library's functions on Z_PARAM_DOUBLE arguments. Math's (HotSpot's intrinsics) round as glibc's
        // more often than StrictMath's (fdlibm's), which stands in where Math has no intrinsic
        math(r, "sin", Math::sin);
        math(r, "cos", Math::cos);
        math(r, "tan", Math::tan);
        math(r, "asin", Math::asin);
        math(r, "acos", Math::acos);
        math(r, "atan", Math::atan);
        math(r, "sinh", StrictMath::sinh);
        math(r, "cosh", StrictMath::cosh);
        math(r, "tanh", StrictMath::tanh);
        math(r, "asinh", CoreFunctions::asinh);
        math(r, "acosh", CoreFunctions::acosh);
        math(r, "atanh", CoreFunctions::atanh);
        math(r, "exp", Math::exp);
        math(r, "expm1", StrictMath::expm1);
        math(r, "log1p", StrictMath::log1p);
        math(r, "log10", Math::log10);
        math(r, "sqrt", Math::sqrt);
        math(r, "deg2rad", x -> x / 180.0 * Math.PI);
        math(r, "rad2deg", x -> x / Math.PI * 180);
        math(r, "is_finite", x -> Double.isFinite(x));
        math(r, "is_nan", x -> Double.isNaN(x));
        math(r, "is_infinite", x -> Double.isInfinite(x));
        math2(r, "atan2", "y", "x", StrictMath::atan2);
        math2(r, "hypot", "x", "y", StrictMath::hypot);
        math2(r, "fmod", "num1", "num2", (x, y) -> x % y);
        // C's pow, where 1 to any power and -1 to an infinite one are 1 (Java's give NaN)
        math2(r, "fpow", "num", "exponent", (x, y) -> x == 1.0 || x == -1.0 && Double.isInfinite(y) ? 1.0
                : Math.pow(x, y));
        r.fn("pi", (context, a) -> {
            Args.arity(a, "pi", 0, 0);
            return Math.PI;
        });
        r.fn("log", (context, a) -> {
            Args.arity(a, "log", 1, 2);
            double num = Args.dbl(context, a, 0, "log", "num");
            if (!Args.has(a, 1)) {
                return Math.log(num);
            }
            double base = Args.dbl(context, a, 1, "log", "base");
            if (base == 2.0) {
                return log2(num);
            }
            if (base == 10.0) {
                return Math.log10(num);
            }
            if (base == 1.0) {
                return Double.NaN;
            }
            if (base <= 0.0) {
                throw new PhpError("ValueError", "log(): Argument #2 ($base) must be greater than 0");
            }
            return Math.log(num) / Math.log(base);
        });
        r.fn("bindec", a -> Long.parseUnsignedLong("0" + Str.java(Args.str(a, 0)).replaceAll("[^01]", ""), 2));
        r.fn("mt_getrandmax", a -> 2147483647L);
        r.fn("getrandmax", a -> 2147483647L);
        r.fn("lcg_value", a -> Math.random());
        r.fn("pow", a -> pl.codeloom.php.runtime.Operators.pow(null, Args.get(a, 0), Args.get(a, 1)));
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
        r.fn("fdiv", (context, a) -> {
            Args.arity(a, "fdiv", 2, 2);
            return Args.dbl(context, a, 0, "fdiv", "num1") / Args.dbl(context, a, 1, "fdiv", "num2");
        });
        r.fn("dechex", a -> Str.of(Long.toHexString(Args.lng(a, 0, 0))));
        r.fn("hexdec", a -> hexdec(Args.str(a, 0)));
        r.fn("decbin", a -> Str.of(Long.toBinaryString(Args.lng(a, 0, 0))));
        r.fn("decoct", a -> Str.of(Long.toOctalString(Args.lng(a, 0, 0))));
        r.fn("octdec", a -> Long.parseLong(Str.java(Args.str(a, 0)).replaceAll("[^0-7]", "0"), 8));
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
            return phpClass != null && !(phpClass.getDeclaration() instanceof ClassTemplate template
                    && template.isTrait()) && phpClass.getConstant(name.substring(colon + 1)) != null;
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

    /**
     * get_class_methods(): the names of the methods the calling scope may call, in function table order (a private
     * one from its class, a protected one from a class related to the one declaring it: zend_check_protected).
     */
    @TruffleBoundary
    private static PhpArray classMethods(PhpContext context, PhpClass phpClass) {
        PhpClass scope = Callables.callerClass(context, "self");
        PhpArray result = new PhpArray();
        for (Map.Entry<String, String> entry : ReflectionFunctions.orderedMethods(context, phpClass).entrySet()) {
            PhpFunction method = phpClass.findMethod(entry.getKey());
            if (method == null) {
                continue;
            }
            int modifiers = method.getModifiers();
            PhpClass declaring = ReflectionFunctions.declaringClass(phpClass, entry.getKey(), method);
            if (visible(modifiers & PhpFunction.ACC_PROTECTED, modifiers & PhpFunction.ACC_PRIVATE, declaring,
                    scope)) {
                result.append(Str.of(entry.getValue()));
            }
        }
        return result;
    }

    private static boolean visible(int isProtected, int isPrivate, PhpClass declaring, PhpClass scope) {
        if (isPrivate != 0) {
            return declaring == scope;
        }
        return isProtected == 0 || scope != null && (declaring.isSubclassOf(scope.getLowerName())
                || scope.isSubclassOf(declaring.getLowerName()));
    }

    /** A declared property as properties_info has it: visibility, static, and the class it belongs to. */
    private record PropertyInfo(int visibility, boolean isStatic, PhpClass declaring) {
    }

    /**
     * get_class_vars(): the defaults of the properties the calling scope may access, instance ones then static
     * ones, each in properties_info order.
     */
    @TruffleBoundary
    private static PhpArray classVars(PhpContext context, PhpClass phpClass) {
        PhpClass scope = Callables.callerClass(context, "self");
        Map<String, PropertyInfo> infos = new LinkedHashMap<>();
        propertyInfos(context, phpClass, phpClass, infos);
        phpClass.declaredDefaults();
        PhpArray result = new PhpArray();
        for (boolean statics : new boolean[] {false, true}) {
            for (Map.Entry<String, PropertyInfo> entry : infos.entrySet()) {
                PropertyInfo info = entry.getValue();
                if (info.isStatic() != statics || !visible(info.visibility() & 1, info.visibility() & 2,
                        info.declaring(), scope)) {
                    continue;
                }
                Object value = statics ? phpClass.getStaticDefault(entry.getKey())
                        : phpClass.findProperty(entry.getKey()).defaultValue();
                result.put(ArrayKey.of(Str.of(entry.getKey())), value == null ? PhpNull.NULL : value);
            }
        }
        return result;
    }

    /**
     * properties_info's order: the class's own properties (promoted ones where the constructor is), its traits'
     * (which belong to the class), then the parent's that it doesn't redeclare. A built-in class's, as declared.
     */
    private static void propertyInfos(PhpContext context, PhpClass phpClass, PhpClass declaring,
                                      Map<String, PropertyInfo> infos) {
        ClassTemplate template = ReflectionFunctions.template(phpClass);
        if (template == null) {
            for (PhpClass.PropertyDecl decl : phpClass.getProperties()) {
                PhpClass owner = decl.declaringClass() == null ? phpClass : context.findClass(decl.declaringClass());
                infos.putIfAbsent(decl.name(), new PropertyInfo(decl.visibility(), false, owner));
            }
            return;
        }
        for (ClassTemplate.Member member : template.ownMembers()) {
            if (member instanceof ClassTemplate.Property property) {
                infos.putIfAbsent(property.name(), new PropertyInfo(property.visibility(), property.isStatic(),
                        declaring));
            } else if (member instanceof ClassTemplate.Method method) {
                for (PhpClass.PropertyDecl decl : method.promoted()) {
                    infos.putIfAbsent(decl.name(), new PropertyInfo(decl.visibility(), false, declaring));
                }
            }
        }
        for (ClassTemplate.Member member : template.ownMembers()) {
            if (member instanceof ClassTemplate.TraitUse use) {
                for (String trait : use.traits()) {
                    PhpClass traitClass = context.findClass(Str.lower(trait));
                    if (traitClass != null) {
                        propertyInfos(context, traitClass, declaring, infos);
                    }
                }
            }
        }
        if (phpClass.getParent() != null && phpClass == declaring) {
            propertyInfos(context, phpClass.getParent(), phpClass.getParent(), infos);
        }
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

    /** round(): PHP rounds half away from zero, using the value's shortest decimal representation. */
    @TruffleBoundary
    private static Object round(Object number, long precision) {
        if (number instanceof Long l && precision >= 0) {
            return (double) l;
        }
        double value = ((Number) number).doubleValue();
        if (!Double.isFinite(value)) {
            return value;
        }
        BigDecimal decimal = new BigDecimal(Double.toString(value)).setScale((int) precision, RoundingMode.HALF_UP);
        return decimal.doubleValue();
    }

    @TruffleBoundary
    private static Object hexdec(TruffleString s) {
        String digits = Str.java(s).replaceAll("[^0-9A-Fa-f]", "");
        if (digits.isEmpty()) {
            return 0L;
        }
        BigDecimal value = new BigDecimal(new java.math.BigInteger(digits, 16));
        if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
            return value.longValue();
        }
        return value.doubleValue();
    }

    @TruffleBoundary
    private static long crc32(TruffleString s) {
        CRC32 crc = new CRC32();
        crc.update(Str.readBytes(s));
        return crc.getValue();
    }


    /** A math.c function of one Z_PARAM_DOUBLE $num. */
    private static void math(Registry r, String name, java.util.function.DoubleFunction<Object> f) {
        r.fn(name, (context, a) -> {
            Args.arity(a, name, 1, 1);
            return f.apply(Args.dbl(context, a, 0, name, "num"));
        });
    }

    private static void math2(Registry r, String name, String first, String second,
                              java.util.function.DoubleBinaryOperator f) {
        r.fn(name, (context, a) -> {
            Args.arity(a, name, 2, 2);
            return f.applyAsDouble(Args.dbl(context, a, 0, name, first), Args.dbl(context, a, 1, name, second));
        });
    }

    private static final double LN2 = 6.93147180559945286227e-01;

    /** fdlibm's s_asinh.c (glibc's). */
    private static double asinh(double x) {
        double t = Math.abs(x);
        double w;
        if (!Double.isFinite(x)) {
            return x + x;
        }
        if (t < 0x1p-28) {
            return x;
        }
        if (t > 0x1p28) {
            w = Math.log(t) + LN2;
        } else if (t > 2.0) {
            w = Math.log(2.0 * t + 1.0 / (StrictMath.sqrt(x * x + 1.0) + t));
        } else {
            double t2 = x * x;
            w = StrictMath.log1p(t + t2 / (1.0 + StrictMath.sqrt(1.0 + t2)));
        }
        return x > 0 ? w : -w;
    }

    /** log2: the exponent, plus the logarithm of the mantissa (scaled into [sqrt(2)/2, sqrt(2)]). */
    private static double log2(double x) {
        if (!(x > 0) || Double.isInfinite(x)) {
            return Math.log(x) / LN2;
        }
        int e = Math.getExponent(x);
        if (e < Double.MIN_EXPONENT) {
            return log2(x * 0x1p54) - 54;
        }
        double m = Math.scalb(x, -e);
        if (m > 1.4142135623730951) {
            m /= 2;
            e++;
        }
        return e + Math.log(m) / LN2;
    }

    /** fdlibm's e_acosh.c. */
    private static double acosh(double x) {
        if (x < 1) {
            return Double.NaN;
        }
        if (x >= 0x1p28) {
            return Double.isFinite(x) ? Math.log(x) + LN2 : x + x;
        }
        if (x == 1.0) {
            return 0.0;
        }
        if (x > 2.0) {
            return Math.log(2.0 * x - 1.0 / (x + StrictMath.sqrt(x * x - 1.0)));
        }
        double t = x - 1.0;
        return StrictMath.log1p(t + StrictMath.sqrt(2.0 * t + t * t));
    }

    /** fdlibm's e_atanh.c (glibc's). */
    private static double atanh(double x) {
        double xa = Math.abs(x);
        double t;
        if (xa < 0.5) {
            if (xa < 0x1p-28) {
                return x;
            }
            t = xa + xa;
            t = 0.5 * StrictMath.log1p(t + t * xa / (1.0 - xa));
        } else if (xa < 1.0) {
            t = 0.5 * StrictMath.log1p((xa + xa) / (1.0 - xa));
        } else if (xa > 1.0 || Double.isNaN(x)) {
            return Double.NaN;
        } else {
            return x / 0.0;
        }
        return Math.copySign(t, x);
    }
}
