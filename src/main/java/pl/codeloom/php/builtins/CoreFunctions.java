package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
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
            String type = Str.java(Args.str(a, 1)).toLowerCase();
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
        r.fn("defined", (context, a) -> context.findConstant(Str.java(Args.str(a, 0))) != null);
        r.fn("constant", (context, a) -> {
            String name = Str.java(Args.str(a, 0));
            int separator = name.indexOf("::");
            Object value = separator > 0 ? context.getClass(name.substring(0, separator))
                    .getConstant(name.substring(separator + 2)) : context.findConstant(name);
            if (value == null) {
                throw new PhpError("Undefined constant \"" + name + "\"");
            }
            return value;
        });
        r.fn("function_exists", (context, a) -> context.findFunction(lowerName(Args.str(a, 0))) != null);
        r.fn("class_exists", (context, a) -> classExists(context, Args.str(a, 0), false, Args.bool(a, 1, true)));
        r.fn("interface_exists", (context, a) -> classExists(context, Args.str(a, 0), true, Args.bool(a, 1, true)));
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
        r.fn("method_exists", (context, a) -> {
            Object target = Args.get(a, 0);
            PhpClass phpClass = target instanceof PhpObject o ? o.getPhpClass()
                    : context.findClass(lowerName(Convert.toStr(target)));
            return phpClass != null && phpClass.findMethod(Str.java(Args.str(a, 1)).toLowerCase()) != null;
        });
        r.fn("property_exists", (context, a) -> {
            Object target = Args.get(a, 0);
            String name = Str.java(Args.str(a, 1));
            if (target instanceof PhpObject o) {
                return o.getProperty(name) != null || hasDeclared(o.getPhpClass(), name);
            }
            PhpClass phpClass = context.findClass(lowerName(Convert.toStr(target)));
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
            PhpClass phpClass = target instanceof PhpObject o ? o.getPhpClass()
                    : target == null ? null : context.findClass(lowerName(Convert.toStr(target)));
            return phpClass == null || phpClass.getParent() == null ? Boolean.FALSE
                    : Str.of(phpClass.getParent().getName());
        });
        r.fn("get_object_vars", a -> objectVars(Args.get(a, 0)));
        r.fn("spl_object_id", a -> (long) objectId(Args.get(a, 0)));
        r.fn("spl_object_hash", a -> Str.of(String.format("%032x", objectId(Args.get(a, 0)))));
        r.fn("is_a", (context, a) -> isA(context, Args.get(a, 0), Args.str(a, 1), Args.bool(a, 2, false)));
        r.fn("is_subclass_of", (context, a) -> {
            Object target = Args.get(a, 0);
            String parent = lowerName(Args.str(a, 1));
            PhpClass phpClass = target instanceof PhpObject o ? o.getPhpClass()
                    : context.findClass(lowerName(Convert.toStr(target)));
            return phpClass != null && !phpClass.getLowerName().equals(parent) && phpClass.isSubclassOf(parent);
        });

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
        r.fn("error_reporting", a -> 30719L);
        r.fn("set_error_handler", (context, a) -> {
            Object handler = Args.get(a, 0, PhpNull.NULL);
            if (handler != PhpNull.NULL) {
                Callables.check(context, handler, new Callables.Parameter("set_error_handler", 1, true));
            }
            return context.swapErrorHandler(handler);
        });
        r.fn("restore_error_handler", a -> true);
        r.fn("trigger_error", (context, a) -> triggerError(context, Args.str(a, 0), Args.lng(a, 1, 1024)));
        r.fn("user_error", (context, a) -> triggerError(context, Args.str(a, 0), Args.lng(a, 1, 1024)));
        r.fn("extension_loaded", a -> RuntimeFunctions.EXTENSIONS.contains(Str.java(Args.str(a, 0)).toLowerCase()));
        r.fn("ini_get", a -> Boolean.FALSE);
        r.fn("ini_set", a -> Boolean.FALSE);
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
            long micros = System.currentTimeMillis() * 1000;
            if (Args.bool(a, 0, false)) {
                return micros / 1e6;
            }
            return Str.of(String.format("%.8f %d", (micros % 1_000_000) / 1e6, micros / 1_000_000));
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
        r.fn("sqrt", a -> Math.sqrt(Convert.toDouble(Args.get(a, 0))));
        r.fn("log", a -> Args.has(a, 1) ? Math.log(Convert.toDouble(Args.get(a, 0)))
                / Math.log(Convert.toDouble(Args.get(a, 1))) : Math.log(Convert.toDouble(Args.get(a, 0))));
        r.fn("log10", a -> Math.log10(Convert.toDouble(Args.get(a, 0))));
        r.fn("log2", a -> Math.log(Convert.toDouble(Args.get(a, 0))) / Math.log(2));
        r.fn("exp", a -> Math.exp(Convert.toDouble(Args.get(a, 0))));
        r.fn("pi", a -> Math.PI);
        r.fn("bindec", a -> Long.parseUnsignedLong("0" + Str.java(Args.str(a, 0)).replaceAll("[^01]", ""), 2));
        r.fn("mt_getrandmax", a -> 2147483647L);
        r.fn("getrandmax", a -> 2147483647L);
        r.fn("lcg_value", a -> Math.random());
        r.fn("pow", a -> pl.codeloom.php.runtime.Operators.pow(Args.get(a, 0), Args.get(a, 1)));
        r.fn("intdiv", a -> {
            long divisor = Args.lng(a, 1, 1);
            if (divisor == 0) {
                throw new PhpError("DivisionByZeroError: Division by zero");
            }
            return Args.lng(a, 0, 0) / divisor;
        });
        r.fn("fmod", a -> Convert.toDouble(Args.get(a, 0)) % Convert.toDouble(Args.get(a, 1)));
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

        // ---- files (read-only, for the benchmark driver) ----
        r.fn("file_exists", a -> Files.exists(Path.of(Str.java(Args.str(a, 0)))));
        r.fn("is_file", a -> Files.isRegularFile(Path.of(Str.java(Args.str(a, 0)))));
        r.fn("is_dir", a -> Files.isDirectory(Path.of(Str.java(Args.str(a, 0)))));
        r.fn("realpath", a -> {
            Path path = Path.of(Str.java(Args.str(a, 0)));
            return Files.exists(path) ? (Object) Str.of(path.toAbsolutePath().normalize().toString()) : Boolean.FALSE;
        });
        r.fn("file_get_contents", (context, a) -> Str.java(Args.str(a, 0)).equals("php://input")
                ? Str.of(context.requestBody()) : readFile(Str.java(Args.str(a, 0))));
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
        throw new PhpError("TypeError: count(): Argument #1 ($value) must be of type Countable|array, "
                + Convert.typeName(value == null ? PhpNull.NULL : value) + " given");
    }

    /**
     * trigger_error(): the error handler gets it if one is set (and doesn't return false); otherwise E_USER_ERROR
     * is fatal and the other levels are not reported (this runtime displays no diagnostics).
     */
    @TruffleBoundary
    private static Object triggerError(PhpContext context, TruffleString message, long level) {
        if (level != 256 && level != 512 && level != 1024 && level != 16384) {
            throw new PhpError("ValueError: trigger_error(): Argument #2 ($error_level) must be one of E_USER_ERROR,"
                    + " E_USER_WARNING, E_USER_NOTICE, or E_USER_DEPRECATED");
        }
        Object handler = context.errorHandler();
        if (handler != PhpNull.NULL
                && !Boolean.FALSE.equals(pl.codeloom.php.runtime.Callables.call(context, handler, level, message,
                        Str.EMPTY, 0L))) {
            return Boolean.TRUE;
        }
        if (level == 256) {
            throw new PhpError(Str.java(message));
        }
        return Boolean.TRUE;
    }

    @TruffleBoundary
    private static String lowerName(TruffleString name) {
        String text = Str.java(name);
        return (text.startsWith("\\") ? text.substring(1) : text).toLowerCase();
    }

    private static boolean classExists(PhpContext context, TruffleString name, boolean wantInterface,
                                       boolean autoload) {
        PhpClass phpClass = autoload ? context.findClassAutoloading(Str.java(name)) : context.findClass(lowerName(name));
        return phpClass != null && phpClass.isInterface() == wantInterface;
    }

    @TruffleBoundary
    private static boolean hasDeclared(PhpClass phpClass, String name) {
        for (PhpClass.PropertyDecl decl : phpClass.getProperties()) {
            if (decl.name().equals(name)) {
                return true;
            }
        }
        return phpClass.getStaticProperty(name) != null;
    }

    /** get_object_vars from outside the class: public properties, in order. */
    @TruffleBoundary
    private static PhpArray objectVars(Object value) {
        PhpArray result = new PhpArray();
        if (value instanceof PhpObject object) {
            for (Object name : object.propertyNames()) {
                String property = (String) name;
                if (object.getPhpClass().isPublicProperty(property)) {
                    result.put(pl.codeloom.php.runtime.ArrayKey.of(Str.of(property)),
                            PhpRef.deref(object.getProperty(property)));
                }
            }
        }
        return result;
    }

    private static int objectId(Object value) {
        if (value instanceof PhpObject object) {
            return object.id();
        }
        return System.identityHashCode(value);
    }

    private static boolean isA(PhpContext context, Object value, TruffleString className, boolean allowString) {
        String lower = lowerName(className);
        if (value instanceof PhpObject object) {
            return object.getPhpClass().isSubclassOf(lower);
        }
        if (allowString && value instanceof TruffleString name) {
            PhpClass phpClass = context.findClass(lowerName(name));
            return phpClass != null && phpClass.isSubclassOf(lower);
        }
        return false;
    }

    @TruffleBoundary
    private static Object intval(Object value, long base) {
        if (base != 10 && value instanceof TruffleString s) {
            String text = Str.java(s).trim().toLowerCase();
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
            throw new PhpError("ValueError: " + (max ? "max" : "min") + "(): Argument #1 ($value) must contain at "
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

    @TruffleBoundary
    private static Object readFile(String path) {
        try {
            return Str.of(Files.readAllBytes(Path.of(path)));
        } catch (java.io.IOException e) {
            return Boolean.FALSE;
        }
    }
}
