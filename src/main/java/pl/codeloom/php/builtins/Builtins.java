package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Predefined constants, built-in classes and built-in functions. The functions and methods are built once per
 * language ({@link Table}); each new context declares them, the constants and its own instances of the classes.
 */
public final class Builtins {
    private Builtins() {
    }

    /** The language-level built-in functions, and the methods that each context's Exception and Error get. */
    public static final class Table {
        private final Map<String, Object> constants;
        private final List<PhpFunction> functions;
        private final List<PhpFunction> throwableMethods;
        private final List<PhpFunction> generatorMethods;

        private Table(Map<String, Object> constants, List<PhpFunction> functions, List<PhpFunction> throwableMethods,
                      List<PhpFunction> generatorMethods) {
            this.constants = constants;
            this.functions = functions;
            this.throwableMethods = throwableMethods;
            this.generatorMethods = generatorMethods;
        }

        /** A predefined constant's value, or null: the same in every context, so the translator folds it. */
        public Object constant(String name) {
            return constants.get(name);
        }
    }

    /** The built-in functions; debug adds the runtime's own tools (DebugFunctions, the PCRE measurements). */
    public static Table build(PhpLanguage language, boolean debug) {
        Registry registry = new Registry(language);
        CoreFunctions.register(registry);
        ErrorFunctions.register(registry);
        StringFunctions.register(registry);
        ArrayFunctions.register(registry);
        PcreFunctions.register(registry);
        JsonFunctions.register(registry);
        MbFunctions.register(registry);
        RuntimeFunctions.register(registry);
        DateFunctions.register(registry);
        EncodingFunctions.register(registry);
        FileFunctions.register(registry);
        MysqliFunctions.register(registry);
        if (debug) {
            DebugFunctions.register(registry);
            PcreFunctions.registerDebug(registry);
        }
        return new Table(constants(), List.copyOf(registry.functions()), throwableMethods(registry),
                generatorMethods(registry));
    }

    public static void install(PhpContext context, Table table) {
        for (Map.Entry<String, Object> constant : table.constants.entrySet()) {
            context.defineConstant(constant.getKey(), constant.getValue());
        }
        classes(context, table);
        for (PhpFunction function : table.functions) {
            context.declareFunction(function);
        }
    }

    private static Map<String, Object> constants() {
        Object[][] table = {
                {"PHP_EOL", Str.of("\n")}, {"PHP_INT_MAX", Long.MAX_VALUE}, {"PHP_INT_MIN", Long.MIN_VALUE},
                {"PHP_INT_SIZE", 8L}, {"PHP_FLOAT_EPSILON", Math.ulp(1.0)}, {"PHP_FLOAT_MAX", Double.MAX_VALUE},
                {"PHP_FLOAT_MIN", Double.MIN_NORMAL}, {"INF", Double.POSITIVE_INFINITY}, {"NAN", Double.NaN},
                {"PHP_VERSION", Str.of("8.5.12")}, {"PHP_MAJOR_VERSION", 8L}, {"PHP_MINOR_VERSION", 5L},
                {"PHP_RELEASE_VERSION", 12L}, {"PHP_VERSION_ID", 80512L}, {"PHP_OS", Str.of("Linux")},
                {"PHP_OS_FAMILY", Str.of("Linux")}, {"DIRECTORY_SEPARATOR", Str.of("/")},
                {"PATH_SEPARATOR", Str.of(":")}, {"M_PI", Math.PI},
                {"E_ERROR", 1L}, {"E_WARNING", 2L}, {"E_PARSE", 4L}, {"E_NOTICE", 8L}, {"E_CORE_ERROR", 16L},
                {"E_CORE_WARNING", 32L}, {"E_COMPILE_ERROR", 64L}, {"E_COMPILE_WARNING", 128L},
                {"DEBUG_BACKTRACE_PROVIDE_OBJECT", 1L}, {"DEBUG_BACKTRACE_IGNORE_ARGS", 2L},
                {"E_USER_ERROR", 256L}, {"E_USER_WARNING", 512L}, {"E_USER_NOTICE", 1024L}, {"E_STRICT", 2048L},
                {"E_RECOVERABLE_ERROR", 4096L}, {"E_DEPRECATED", 8192L}, {"E_USER_DEPRECATED", 16384L},
                {"E_ALL", 30719L},
                {"ENT_HTML_QUOTE_NONE", 0L}, {"ENT_HTML_QUOTE_SINGLE", 1L}, {"ENT_HTML_QUOTE_DOUBLE", 2L},
                {"ENT_COMPAT", 2L}, {"ENT_QUOTES", 3L}, {"ENT_NOQUOTES", 0L}, {"ENT_IGNORE", 4L},
                {"ENT_SUBSTITUTE", 8L}, {"ENT_DISALLOWED", 128L}, {"ENT_HTML401", 0L}, {"ENT_XML1", 16L},
                {"ENT_XHTML", 32L}, {"ENT_HTML5", 48L},
                {"HTML_SPECIALCHARS", 0L}, {"HTML_ENTITIES", 1L},
                {"PREG_PATTERN_ORDER", 1L}, {"PREG_SET_ORDER", 2L}, {"PREG_OFFSET_CAPTURE", 256L},
                {"PREG_UNMATCHED_AS_NULL", 512L}, {"PREG_SPLIT_NO_EMPTY", 1L}, {"PREG_SPLIT_DELIM_CAPTURE", 2L},
                {"PREG_SPLIT_OFFSET_CAPTURE", 4L}, {"PREG_GREP_INVERT", 1L}, {"PREG_NO_ERROR", 0L},
                {"PHP_URL_SCHEME", 0L}, {"PHP_URL_HOST", 1L}, {"PHP_URL_PORT", 2L}, {"PHP_URL_USER", 3L},
                {"PHP_URL_PASS", 4L}, {"PHP_URL_PATH", 5L}, {"PHP_URL_QUERY", 6L}, {"PHP_URL_FRAGMENT", 7L},
                {"PHP_QUERY_RFC1738", 1L}, {"PHP_QUERY_RFC3986", 2L},
                {"JSON_HEX_TAG", 1L}, {"JSON_HEX_AMP", 2L}, {"JSON_HEX_APOS", 4L}, {"JSON_HEX_QUOT", 8L},
                {"JSON_FORCE_OBJECT", 16L}, {"JSON_NUMERIC_CHECK", 32L}, {"JSON_UNESCAPED_SLASHES", 64L},
                {"JSON_PRETTY_PRINT", 128L}, {"JSON_UNESCAPED_UNICODE", 256L},
                {"JSON_PARTIAL_OUTPUT_ON_ERROR", 512L}, {"JSON_PRESERVE_ZERO_FRACTION", 1024L},
                {"JSON_UNESCAPED_LINE_TERMINATORS", 2048L}, {"JSON_OBJECT_AS_ARRAY", 1L},
                {"JSON_BIGINT_AS_STRING", 2L}, {"JSON_INVALID_UTF8_IGNORE", 1048576L},
                {"JSON_INVALID_UTF8_SUBSTITUTE", 2097152L}, {"JSON_THROW_ON_ERROR", 4194304L},
                {"JSON_ERROR_NONE", 0L}, {"JSON_ERROR_DEPTH", 1L}, {"JSON_ERROR_SYNTAX", 4L},
                {"JSON_ERROR_UTF8", 5L},
                {"SORT_REGULAR", 0L}, {"SORT_NUMERIC", 1L}, {"SORT_STRING", 2L}, {"SORT_LOCALE_STRING", 5L},
                {"SORT_NATURAL", 6L}, {"SORT_FLAG_CASE", 8L}, {"SORT_ASC", 4L}, {"SORT_DESC", 3L},
                {"COUNT_NORMAL", 0L}, {"COUNT_RECURSIVE", 1L},
                {"ARRAY_FILTER_USE_BOTH", 1L}, {"ARRAY_FILTER_USE_KEY", 2L},
                {"STR_PAD_LEFT", 0L}, {"STR_PAD_RIGHT", 1L}, {"STR_PAD_BOTH", 2L},
                {"PATHINFO_DIRNAME", 1L}, {"PATHINFO_BASENAME", 2L}, {"PATHINFO_EXTENSION", 4L},
                {"PATHINFO_FILENAME", 8L}, {"PHP_ROUND_HALF_UP", 1L}, {"PHP_ROUND_HALF_DOWN", 2L},
                {"PHP_ROUND_HALF_EVEN", 3L}, {"PHP_ROUND_HALF_ODD", 4L},
                {"MB_CASE_UPPER", 0L}, {"MB_CASE_LOWER", 1L}, {"MB_CASE_TITLE", 2L},
                {"LC_CTYPE", 0L}, {"LC_ALL", 6L},
                {"PHP_SAPI", Str.of("cli")}, {"PHP_BINARY", Str.of("/usr/local/bin/php")},
                {"PHP_MAXPATHLEN", 4096L}, {"PHP_DEBUG", 0L}, {"PHP_ZTS", 0L}, {"PHP_EXTRA_VERSION", Str.EMPTY},
                {"PHP_FLOAT_DIG", 15L}, {"M_E", Math.E}, {"M_SQRT2", Math.sqrt(2)},
                {"FILE_USE_INCLUDE_PATH", 1L}, {"FILE_IGNORE_NEW_LINES", 2L}, {"FILE_SKIP_EMPTY_LINES", 4L},
                {"FILE_APPEND", 8L}, {"FILE_NO_DEFAULT_CONTEXT", 16L}, {"LOCK_SH", 1L}, {"LOCK_EX", 2L},
                {"LOCK_UN", 3L}, {"LOCK_NB", 4L}, {"SEEK_SET", 0L}, {"SEEK_CUR", 1L}, {"SEEK_END", 2L},
                {"GLOB_MARK", 2L}, {"GLOB_NOSORT", 4L}, {"GLOB_NOCHECK", 16L}, {"GLOB_NOESCAPE", 64L},
                {"GLOB_ERR", 1L}, {"GLOB_BRACE", 1024L}, {"GLOB_ONLYDIR", 8192L}, {"GLOB_AVAILABLE_FLAGS", 9303L},
                {"SCANDIR_SORT_ASCENDING", 0L}, {"SCANDIR_SORT_DESCENDING", 1L}, {"SCANDIR_SORT_NONE", 2L},
                {"CASE_LOWER", 0L}, {"CASE_UPPER", 1L}, {"EXTR_OVERWRITE", 0L}, {"EXTR_SKIP", 1L},
                {"EXTR_PREFIX_SAME", 2L}, {"EXTR_PREFIX_ALL", 3L}, {"EXTR_IF_EXISTS", 6L}, {"EXTR_REFS", 256L},
                {"PHP_OUTPUT_HANDLER_STDFLAGS", 112L}, {"PHP_OUTPUT_HANDLER_CLEANABLE", 16L},
                {"PHP_OUTPUT_HANDLER_FLUSHABLE", 32L}, {"PHP_OUTPUT_HANDLER_REMOVABLE", 64L},
                {"CRYPT_BLOWFISH", 1L}, {"PASSWORD_DEFAULT", Str.of("2y")}, {"PASSWORD_BCRYPT", Str.of("2y")},
                {"PASSWORD_BCRYPT_DEFAULT_COST", 10L},
                {"INI_USER", 1L}, {"INI_PERDIR", 2L}, {"INI_SYSTEM", 4L}, {"INI_ALL", 7L},
                {"DATE_ATOM", Str.of("Y-m-d\\TH:i:sP")}, {"DATE_RFC2822", Str.of("D, d M Y H:i:s O")},
                {"DATE_RFC3339", Str.of("Y-m-d\\TH:i:sP")}, {"DATE_ISO8601", Str.of("Y-m-d\\TH:i:sO")},
                {"DATE_COOKIE", Str.of("l, d-M-Y H:i:s T")}, {"DATE_RSS", Str.of("D, d M Y H:i:s O")},
                {"DATE_W3C", Str.of("Y-m-d\\TH:i:sP")}, {"DATE_RFC7231", Str.of("D, d M Y H:i:s \\G\\M\\T")},
                {"PREG_INTERNAL_ERROR", 1L}, {"PREG_BACKTRACK_LIMIT_ERROR", 2L}, {"PREG_RECURSION_LIMIT_ERROR", 3L},
                {"PREG_BAD_UTF8_ERROR", 4L}, {"PREG_BAD_UTF8_OFFSET_ERROR", 5L}, {"PREG_JIT_STACKLIMIT_ERROR", 6L},
                {"JSON_ERROR_CTRL_CHAR", 3L}, {"JSON_ERROR_STATE_MISMATCH", 2L}, {"JSON_ERROR_RECURSION", 6L},
                {"JSON_ERROR_INF_OR_NAN", 7L}, {"JSON_ERROR_UNSUPPORTED_TYPE", 8L},
                {"MB_CASE_FOLD", 3L}, {"MB_CASE_UPPER_SIMPLE", 4L}, {"MB_CASE_LOWER_SIMPLE", 5L},
                {"MB_CASE_TITLE_SIMPLE", 6L}, {"MB_CASE_FOLD_SIMPLE", 7L},
                {"LC_NUMERIC", 1L}, {"LC_COLLATE", 3L}, {"LC_MONETARY", 4L}, {"LC_TIME", 2L}, {"LC_MESSAGES", 5L},
                {"MYSQLI_REPORT_OFF", 0L}, {"MYSQLI_REPORT_ERROR", 1L}, {"MYSQLI_REPORT_STRICT", 2L},
                {"MYSQLI_REPORT_INDEX", 4L}, {"MYSQLI_REPORT_ALL", 255L}, {"MYSQLI_ASSOC", 1L}, {"MYSQLI_NUM", 2L},
                {"MYSQLI_BOTH", 3L}, {"MYSQLI_STORE_RESULT", 0L}, {"MYSQLI_USE_RESULT", 1L},
                {"MYSQLI_CLIENT_COMPRESS", 32L}, {"MYSQLI_CLIENT_SSL", 2048L}, {"MYSQLI_CLIENT_FOUND_ROWS", 2L},
        };
        Map<String, Object> constants = new LinkedHashMap<>();
        for (Object[] entry : table) {
            constants.put((String) entry[0], entry[1]);
        }
        return Collections.unmodifiableMap(constants);
    }

    private static void classes(PhpContext context, Table table) {
        PhpClass traversable = declare(context, "Traversable", null, true);
        PhpClass iterator = declare(context, "Iterator", null, true);
        iterator.addInterface(traversable);
        PhpClass aggregate = declare(context, "IteratorAggregate", null, true);
        aggregate.addInterface(traversable);
        declare(context, "ArrayAccess", null, true);
        declare(context, "Countable", null, true);
        declare(context, "JsonSerializable", null, true);
        PhpClass stringable = declare(context, "Stringable", null, true);
        PhpClass throwable = declare(context, "Throwable", null, true);
        throwable.addInterface(stringable);
        declare(context, "stdClass", null, false);
        PhpClass generator = new PhpClass("Generator", null, false, false, context.getLanguage().rootShape(),
                "Generator");
        generator.addInterface(iterator);
        for (PhpFunction method : table.generatorMethods) {
            generator.addMethod(method);
        }
        context.declareClass(generator);
        declare(context, "mysqli", null, false);
        declare(context, "mysqli_result", null, false);

        throwableClass(context, table, "Exception", throwable);
        throwableClass(context, table, "Error", throwable);
        String[][] hierarchy = {
                {"ErrorException", "Exception"}, {"LogicException", "Exception"},
                {"BadFunctionCallException", "LogicException"}, {"BadMethodCallException", "BadFunctionCallException"},
                {"DomainException", "LogicException"}, {"InvalidArgumentException", "LogicException"},
                {"LengthException", "LogicException"}, {"OutOfRangeException", "LogicException"},
                {"RuntimeException", "Exception"}, {"OutOfBoundsException", "RuntimeException"},
                {"OverflowException", "RuntimeException"}, {"RangeException", "RuntimeException"},
                {"UnderflowException", "RuntimeException"}, {"UnexpectedValueException", "RuntimeException"},
                {"JsonException", "Exception"},
                {"TypeError", "Error"}, {"ValueError", "Error"}, {"ArithmeticError", "Error"},
                {"DivisionByZeroError", "ArithmeticError"}, {"ArgumentCountError", "TypeError"},
                {"UnhandledMatchError", "Error"},
        };
        for (String[] pair : hierarchy) {
            PhpClass phpClass = new PhpClass(pair[0], context.getClass(pair[1]), false, false,
                    context.getLanguage().rootShape(), pair[0]);
            context.declareClass(phpClass);
        }
    }

    private static PhpClass declare(PhpContext context, String name, PhpClass parent, boolean isInterface) {
        PhpClass phpClass = new PhpClass(name, parent, isInterface, false, context.getLanguage().rootShape(), name);
        context.declareClass(phpClass);
        return phpClass;
    }

    /** Exception and Error: their properties in php-src's order, with the usual getters. */
    private static void throwableClass(PhpContext context, Table table, String name, PhpClass throwable) {
        PhpClass phpClass = new PhpClass(name, null, false, false, context.getLanguage().rootShape(), name);
        phpClass.addInterface(throwable);
        phpClass.addProperty(new PhpClass.PropertyDecl("message", Str.EMPTY, false));
        phpClass.addProperty(new PhpClass.PropertyDecl("string", Str.EMPTY, false));
        phpClass.addProperty(new PhpClass.PropertyDecl("code", 0L, false));
        phpClass.addProperty(new PhpClass.PropertyDecl("file", Str.EMPTY, false));
        phpClass.addProperty(new PhpClass.PropertyDecl("line", 0L, false));
        phpClass.addProperty(new PhpClass.PropertyDecl("trace", new PhpArray(), false));
        phpClass.addProperty(new PhpClass.PropertyDecl("previous", PhpNull.NULL, false));
        for (PhpFunction method : table.throwableMethods) {
            phpClass.addMethod(method);
        }
        context.declareClass(phpClass);
    }

    private static List<PhpFunction> throwableMethods(Registry registry) {
        return List.of(
                registry.method("__construct", false, arguments -> {
                    PhpObject self = (PhpObject) arguments[PhpFunction.ARG_THIS];
                    if (Args.has(arguments, 0)) {
                        self.setProperty("message", Args.str(arguments, 0));
                    }
                    if (Args.has(arguments, 1)) {
                        self.setProperty("code", Args.get(arguments, 1));
                    }
                    if (Args.has(arguments, 2)) {
                        self.setProperty("previous", Args.get(arguments, 2));
                    }
                    return PhpNull.NULL;
                }),
                registry.method("getMessage", false, arguments -> property(arguments, "message")),
                registry.method("getCode", false, arguments -> property(arguments, "code")),
                registry.method("getPrevious", false, arguments -> property(arguments, "previous")),
                registry.method("getFile", false, arguments -> property(arguments, "file")),
                registry.method("getLine", false, arguments -> property(arguments, "line")),
                registry.method("getTrace", false, arguments -> property(arguments, "trace")),
                registry.method("getTraceAsString", false,
                        arguments -> traceString((PhpObject) arguments[PhpFunction.ARG_THIS])),
                registry.method("__toString", false,
                        arguments -> describe((PhpObject) arguments[PhpFunction.ARG_THIS])));
    }

    private static TruffleString traceString(PhpObject exception) {
        if (!(PhpRef.deref(property(exception, "trace")) instanceof PhpArray trace)) {
            throw new PhpError("TypeError", "Trace is not an array");
        }
        return Backtrace.toString(trace, true);
    }

    /**
     * Exception::__toString: the exception and those before it (getPrevious), the first one first, each with its
     * class, message, file, line and trace, joined by "Next".
     */
    @TruffleBoundary
    static TruffleString describe(PhpObject exception) {
        List<byte[]> parts = new java.util.ArrayList<>();
        Object current = exception;
        while (current instanceof PhpObject e && e.getPhpClass().isThrowable()) {
            ByteBuilder part = new ByteBuilder();
            part.write(Str.of(e.getPhpClass().getName()));
            byte[] message = Str.bytes(Convert.toStr(property(e, "message")));
            if (message.length > 0) {
                part.write(": ".getBytes(StandardCharsets.ISO_8859_1));
                part.write(message);
                String name = e.getPhpClass().getLowerName();
                if ((name.equals("typeerror") || name.equals("argumentcounterror"))
                        && new String(message, StandardCharsets.ISO_8859_1).contains(", called in ")) {
                    part.write(" and defined".getBytes(StandardCharsets.ISO_8859_1));
                }
            }
            part.write(" in ".getBytes(StandardCharsets.ISO_8859_1));
            part.write(Convert.toStr(property(e, "file")));
            part.write(':');
            part.writeLong(Convert.toLong(property(e, "line")));
            part.write("\nStack trace:\n".getBytes(StandardCharsets.ISO_8859_1));
            part.write(traceString(e));
            parts.add(part.toByteArray());
            current = property(e, "previous");
        }
        ByteBuilder text = new ByteBuilder();
        for (int i = parts.size() - 1; i >= 0; i--) {
            text.write(parts.get(i));
            if (i > 0) {
                text.write("\n\nNext ".getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        return Str.of(text.toByteArray());
    }

    private static Object property(PhpObject object, String name) {
        Object value = object.getProperty(name);
        return value == null ? PhpNull.NULL : value;
    }

    /** Generator: the Iterator methods plus send, throw and getReturn, on the object's PhpGenerator. */
    private static List<PhpFunction> generatorMethods(Registry registry) {
        return List.of(
                registry.method("current", false, arguments -> generator(arguments).current()),
                registry.method("key", false, arguments -> generator(arguments).key()),
                registry.method("next", false, arguments -> {
                    generator(arguments).next();
                    return PhpNull.NULL;
                }),
                registry.method("valid", false, arguments -> generator(arguments).valid()),
                registry.method("rewind", false, arguments -> {
                    generator(arguments).rewind();
                    return PhpNull.NULL;
                }),
                registry.method("send", false, arguments -> generator(arguments).send(Args.get(arguments, 0))),
                registry.method("getReturn", false, arguments -> generator(arguments).getReturn()),
                registry.method("throw", false, arguments -> generator(arguments).throwInto(
                        new pl.codeloom.php.runtime.PhpException((PhpObject) Args.get(arguments, 0)))));
    }

    private static pl.codeloom.php.runtime.PhpGenerator generator(Object[] arguments) {
        return (pl.codeloom.php.runtime.PhpGenerator) ((PhpObject) arguments[PhpFunction.ARG_THIS]).getInternal();
    }

    private static Object property(Object[] arguments, String name) {
        Object value = ((PhpObject) arguments[PhpFunction.ARG_THIS]).getProperty(name);
        return value == null ? PhpNull.NULL : value;
    }
}
