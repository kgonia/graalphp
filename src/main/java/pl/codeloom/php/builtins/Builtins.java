package pl.codeloom.php.builtins;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

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

        private Table(Map<String, Object> constants, List<PhpFunction> functions, List<PhpFunction> throwableMethods) {
            this.constants = constants;
            this.functions = functions;
            this.throwableMethods = throwableMethods;
        }

        /** A predefined constant's value, or null: the same in every context, so the translator folds it. */
        public Object constant(String name) {
            return constants.get(name);
        }
    }

    public static Table build(PhpLanguage language) {
        Registry registry = new Registry(language);
        CoreFunctions.register(registry);
        StringFunctions.register(registry);
        ArrayFunctions.register(registry);
        PcreFunctions.register(registry);
        JsonFunctions.register(registry);
        MbFunctions.register(registry);
        return new Table(constants(), List.copyOf(registry.functions()), throwableMethods(registry));
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

        PhpClass exception = throwableClass(context, table, "Exception", throwable);
        PhpClass error = throwableClass(context, table, "Error", throwable);
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
        if (exception == null || error == null) {
            throw new IllegalStateException("exception classes missing");
        }
    }

    private static PhpClass declare(PhpContext context, String name, PhpClass parent, boolean isInterface) {
        PhpClass phpClass = new PhpClass(name, parent, isInterface, false, context.getLanguage().rootShape(), name);
        context.declareClass(phpClass);
        return phpClass;
    }

    /** Exception and Error: message, code and previous, with the usual getters. */
    private static PhpClass throwableClass(PhpContext context, Table table, String name, PhpClass throwable) {
        PhpClass phpClass = new PhpClass(name, null, false, false, context.getLanguage().rootShape(), name);
        phpClass.addInterface(throwable);
        phpClass.addProperty(new PhpClass.PropertyDecl("message", Str.EMPTY, false));
        phpClass.addProperty(new PhpClass.PropertyDecl("code", 0L, false));
        phpClass.addProperty(new PhpClass.PropertyDecl("file", Str.EMPTY, false));
        phpClass.addProperty(new PhpClass.PropertyDecl("line", 0L, false));
        phpClass.addProperty(new PhpClass.PropertyDecl("previous", PhpNull.NULL, false));
        for (PhpFunction method : table.throwableMethods) {
            phpClass.addMethod(method);
        }
        context.declareClass(phpClass);
        return phpClass;
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
                registry.method("getTrace", false, arguments -> new PhpArray()),
                registry.method("getTraceAsString", false, arguments -> Str.of("#0 {main}")));
    }

    private static Object property(Object[] arguments, String name) {
        Object value = ((PhpObject) arguments[PhpFunction.ARG_THIS]).getProperty(name);
        return value == null ? PhpNull.NULL : value;
    }
}
