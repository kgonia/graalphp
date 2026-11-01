package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.stmt.TryNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * ext/filter: a port of php-src's filter.c (filter_var(), filter_input() and their array forms), logical_filters.c
 * (the FILTER_VALIDATE_* filters), sanitizing_filters.c (FILTER_SANITIZE_*, FILTER_UNSAFE_RAW) and callback_filter.c.
 */
final class FilterFunctions {
    private FilterFunctions() {
    }

    // filter_private.h's flags
    private static final long REQUIRE_ARRAY = 0x1000000;
    private static final long REQUIRE_SCALAR = 0x2000000;
    private static final long FORCE_ARRAY = 0x4000000;
    private static final long NULL_ON_FAILURE = 0x8000000;
    private static final long THROW_ON_FAILURE = 0x10000000;
    private static final long FLAG_ALLOW_OCTAL = 0x0001;
    private static final long FLAG_ALLOW_HEX = 0x0002;
    private static final long FLAG_STRIP_LOW = 0x0004;
    private static final long FLAG_STRIP_HIGH = 0x0008;
    private static final long FLAG_ENCODE_LOW = 0x0010;
    private static final long FLAG_ENCODE_HIGH = 0x0020;
    private static final long FLAG_ENCODE_AMP = 0x0040;
    private static final long FLAG_NO_ENCODE_QUOTES = 0x0080;
    private static final long FLAG_EMPTY_STRING_NULL = 0x0100;
    private static final long FLAG_STRIP_BACKTICK = 0x0200;
    private static final long FLAG_ALLOW_FRACTION = 0x1000;
    private static final long FLAG_ALLOW_THOUSAND = 0x2000;
    private static final long FLAG_ALLOW_SCIENTIFIC = 0x4000;
    private static final long FLAG_PATH_REQUIRED = 0x040000;
    private static final long FLAG_QUERY_REQUIRED = 0x080000;
    private static final long FLAG_IPV4 = 0x00100000;
    private static final long FLAG_IPV6 = 0x00200000;
    private static final long FLAG_NO_RES_RANGE = 0x00400000;
    private static final long FLAG_NO_PRIV_RANGE = 0x00800000;
    private static final long FLAG_GLOBAL_RANGE = 0x20000000;
    private static final long FLAG_HOSTNAME = 0x100000;
    private static final long FLAG_EMAIL_UNICODE = 0x100000;

    // the filter ids
    private static final long VALIDATE_INT = 0x0101;
    private static final long VALIDATE_BOOL = 0x0102;
    private static final long VALIDATE_FLOAT = 0x0103;
    private static final long VALIDATE_REGEXP = 0x0110;
    private static final long VALIDATE_URL = 0x0111;
    private static final long VALIDATE_EMAIL = 0x0112;
    private static final long VALIDATE_IP = 0x0113;
    private static final long VALIDATE_MAC = 0x0114;
    private static final long VALIDATE_DOMAIN = 0x0115;
    private static final long VALIDATE_ALL = 0x0100;
    private static final long VALIDATE_LAST = 0x0115;
    private static final long DEFAULT = 0x0204;
    private static final long SANITIZE_STRING = 0x0201;
    private static final long SANITIZE_ENCODED = 0x0202;
    private static final long SANITIZE_SPECIAL_CHARS = 0x0203;
    private static final long SANITIZE_EMAIL = 0x0205;
    private static final long SANITIZE_URL = 0x0206;
    private static final long SANITIZE_NUMBER_INT = 0x0207;
    private static final long SANITIZE_NUMBER_FLOAT = 0x0208;
    private static final long SANITIZE_FULL_SPECIAL_CHARS = 0x020a;
    private static final long SANITIZE_ADD_SLASHES = 0x020b;
    private static final long SANITIZE_ALL = 0x0200;
    private static final long SANITIZE_LAST = 0x020b;
    private static final long CALLBACK = 0x0400;

    // the INPUT_* sources: main/php_variables.h's PARSE_*
    private static final long INPUT_POST = 0;
    private static final long INPUT_GET = 1;
    private static final long INPUT_COOKIE = 2;
    private static final long INPUT_ENV = 4;
    private static final long INPUT_SERVER = 5;

    // the keys of the options arrays
    private static final TruffleString KEY_DEFAULT = Str.of("default");
    private static final TruffleString KEY_FILTER = Str.of("filter");
    private static final TruffleString KEY_OPTIONS = Str.of("options");
    private static final TruffleString KEY_FLAGS = Str.of("flags");
    private static final TruffleString KEY_DECIMAL = Str.of("decimal");
    private static final TruffleString KEY_MAX_RANGE = Str.of("max_range");
    private static final TruffleString KEY_MIN_RANGE = Str.of("min_range");
    private static final TruffleString KEY_REGEXP = Str.of("regexp");
    private static final TruffleString KEY_SEPARATOR = Str.of("separator");
    private static final TruffleString KEY_THOUSAND = Str.of("thousand");
    private static final TruffleString KEY_URI_PARSER_CLASS = Str.of("uri_parser_class");

    private static final String FAILED_EXCEPTION = "Filter\\FilterFailedException";

    static void constants(Map<String, Object> constants) {
        Object[][] table = {
                {"INPUT_POST", INPUT_POST}, {"INPUT_GET", INPUT_GET}, {"INPUT_COOKIE", INPUT_COOKIE},
                {"INPUT_ENV", INPUT_ENV}, {"INPUT_SERVER", INPUT_SERVER}, {"FILTER_FLAG_NONE", 0L},
                {"FILTER_REQUIRE_SCALAR", REQUIRE_SCALAR}, {"FILTER_REQUIRE_ARRAY", REQUIRE_ARRAY},
                {"FILTER_FORCE_ARRAY", FORCE_ARRAY}, {"FILTER_NULL_ON_FAILURE", NULL_ON_FAILURE},
                {"FILTER_THROW_ON_FAILURE", THROW_ON_FAILURE}, {"FILTER_VALIDATE_INT", VALIDATE_INT},
                {"FILTER_VALIDATE_BOOLEAN", VALIDATE_BOOL}, {"FILTER_VALIDATE_BOOL", VALIDATE_BOOL},
                {"FILTER_VALIDATE_FLOAT", VALIDATE_FLOAT}, {"FILTER_VALIDATE_REGEXP", VALIDATE_REGEXP},
                {"FILTER_VALIDATE_DOMAIN", VALIDATE_DOMAIN}, {"FILTER_VALIDATE_URL", VALIDATE_URL},
                {"FILTER_VALIDATE_EMAIL", VALIDATE_EMAIL}, {"FILTER_VALIDATE_IP", VALIDATE_IP},
                {"FILTER_VALIDATE_MAC", VALIDATE_MAC}, {"FILTER_DEFAULT", DEFAULT}, {"FILTER_UNSAFE_RAW", DEFAULT},
                {"FILTER_SANITIZE_STRING", SANITIZE_STRING}, {"FILTER_SANITIZE_STRIPPED", SANITIZE_STRING},
                {"FILTER_SANITIZE_ENCODED", SANITIZE_ENCODED},
                {"FILTER_SANITIZE_SPECIAL_CHARS", SANITIZE_SPECIAL_CHARS},
                {"FILTER_SANITIZE_FULL_SPECIAL_CHARS", SANITIZE_FULL_SPECIAL_CHARS},
                {"FILTER_SANITIZE_EMAIL", SANITIZE_EMAIL}, {"FILTER_SANITIZE_URL", SANITIZE_URL},
                {"FILTER_SANITIZE_NUMBER_INT", SANITIZE_NUMBER_INT},
                {"FILTER_SANITIZE_NUMBER_FLOAT", SANITIZE_NUMBER_FLOAT},
                {"FILTER_SANITIZE_ADD_SLASHES", SANITIZE_ADD_SLASHES}, {"FILTER_CALLBACK", CALLBACK},
                {"FILTER_FLAG_ALLOW_OCTAL", FLAG_ALLOW_OCTAL}, {"FILTER_FLAG_ALLOW_HEX", FLAG_ALLOW_HEX},
                {"FILTER_FLAG_STRIP_LOW", FLAG_STRIP_LOW}, {"FILTER_FLAG_STRIP_HIGH", FLAG_STRIP_HIGH},
                {"FILTER_FLAG_STRIP_BACKTICK", FLAG_STRIP_BACKTICK}, {"FILTER_FLAG_ENCODE_LOW", FLAG_ENCODE_LOW},
                {"FILTER_FLAG_ENCODE_HIGH", FLAG_ENCODE_HIGH}, {"FILTER_FLAG_ENCODE_AMP", FLAG_ENCODE_AMP},
                {"FILTER_FLAG_NO_ENCODE_QUOTES", FLAG_NO_ENCODE_QUOTES},
                {"FILTER_FLAG_EMPTY_STRING_NULL", FLAG_EMPTY_STRING_NULL},
                {"FILTER_FLAG_ALLOW_FRACTION", FLAG_ALLOW_FRACTION},
                {"FILTER_FLAG_ALLOW_THOUSAND", FLAG_ALLOW_THOUSAND},
                {"FILTER_FLAG_ALLOW_SCIENTIFIC", FLAG_ALLOW_SCIENTIFIC},
                {"FILTER_FLAG_PATH_REQUIRED", FLAG_PATH_REQUIRED}, {"FILTER_FLAG_QUERY_REQUIRED", FLAG_QUERY_REQUIRED},
                {"FILTER_FLAG_IPV4", FLAG_IPV4}, {"FILTER_FLAG_IPV6", FLAG_IPV6},
                {"FILTER_FLAG_NO_RES_RANGE", FLAG_NO_RES_RANGE}, {"FILTER_FLAG_NO_PRIV_RANGE", FLAG_NO_PRIV_RANGE},
                {"FILTER_FLAG_GLOBAL_RANGE", FLAG_GLOBAL_RANGE}, {"FILTER_FLAG_HOSTNAME", FLAG_HOSTNAME},
                {"FILTER_FLAG_EMAIL_UNICODE", FLAG_EMAIL_UNICODE},
        };
        for (Object[] entry : table) {
            constants.put((String) entry[0], entry[1]);
        }
    }

    static void register(Registry r) {
        r.fn("filter_has_var", FilterFunctions::filterHasVar);
        r.fn("filter_input", FilterFunctions::filterInput);
        r.fn("filter_var", FilterFunctions::filterVar);
        r.fn("filter_input_array", FilterFunctions::filterInputArray);
        r.fn("filter_var_array", FilterFunctions::filterVarArray);
        r.fn("filter_list", a -> {
            Args.arity(a, "filter_list", 0, 0);
            PhpArray names = new PhpArray();
            for (Entry entry : FILTERS) {
                names.append(Str.of(entry.name()));
            }
            return names;
        });
        r.fn("filter_id", (context, a) -> {
            Args.arity(a, "filter_id", 1, 1);
            // strcmp: the name ends at its first NUL byte
            String name = cString(Args.string(context, a, 0, "filter_id", "name", false));
            for (Entry entry : FILTERS) {
                if (entry.name().equals(name)) {
                    return entry.id();
                }
            }
            return Boolean.FALSE;
        });
    }

    // ---- the filters ----

    /** A filter: the value (a string) filtered, or the marker FAILURE (FILTER_THROW_ON_FAILURE's failure). */
    @FunctionalInterface
    private interface Filter {
        Object apply(Call call, TruffleString value, long flags, Object options);
    }

    /** filter_list_entry. */
    private record Entry(String name, long id, Filter filter) {
    }

    /** filter.c's filter_list, in its order (filter_list() gives the names so). */
    private static final Entry[] FILTERS = {
            new Entry("int", VALIDATE_INT, FilterFunctions::validateInt),
            new Entry("boolean", VALIDATE_BOOL, FilterFunctions::validateBool),
            new Entry("float", VALIDATE_FLOAT, FilterFunctions::validateFloat),
            new Entry("validate_regexp", VALIDATE_REGEXP, FilterFunctions::validateRegexp),
            new Entry("validate_domain", VALIDATE_DOMAIN, FilterFunctions::validateDomain),
            new Entry("validate_url", VALIDATE_URL, FilterFunctions::validateUrl),
            new Entry("validate_email", VALIDATE_EMAIL, FilterFunctions::validateEmail),
            new Entry("validate_ip", VALIDATE_IP, FilterFunctions::validateIp),
            new Entry("validate_mac", VALIDATE_MAC, FilterFunctions::validateMac),
            new Entry("string", SANITIZE_STRING, FilterFunctions::sanitizeString),
            new Entry("stripped", SANITIZE_STRING, FilterFunctions::sanitizeString),
            new Entry("encoded", SANITIZE_ENCODED, FilterFunctions::sanitizeEncoded),
            new Entry("special_chars", SANITIZE_SPECIAL_CHARS, FilterFunctions::sanitizeSpecialChars),
            new Entry("full_special_chars", SANITIZE_FULL_SPECIAL_CHARS, FilterFunctions::sanitizeFullSpecialChars),
            new Entry("unsafe_raw", DEFAULT, FilterFunctions::unsafeRaw),
            new Entry("email", SANITIZE_EMAIL,
                    (call, value, flags, options) -> keepOnly(value, FilterFunctions.EMAIL_CHARS)),
            new Entry("url", SANITIZE_URL, (call, value, flags, options) -> keepOnly(value, FilterFunctions.URL_CHARS)),
            new Entry("number_int", SANITIZE_NUMBER_INT,
                    (call, value, flags, options) -> keepOnly(value, FilterFunctions.INT_CHARS)),
            new Entry("number_float", SANITIZE_NUMBER_FLOAT, FilterFunctions::sanitizeNumberFloat),
            new Entry("add_slashes", SANITIZE_ADD_SLASHES,
                    (call, value, flags, options) -> StringFunctions.addslashes(value)),
            new Entry("callback", CALLBACK, FilterFunctions::callback),
    };

    /** The marker a filter returns for RETURN_VALIDATION_FAILED's FAILURE (FILTER_THROW_ON_FAILURE). */
    private static final Object FAILURE = new Object();

    /** PHP_FILTER_ID_EXISTS. */
    private static boolean idExists(long id) {
        return id >= SANITIZE_ALL && id <= SANITIZE_LAST || id >= VALIDATE_ALL && id <= VALIDATE_LAST
                || id == CALLBACK;
    }

    /** php_find_filter: the filter of the id, else FILTER_DEFAULT's. */
    private static Entry find(long id) {
        for (Entry entry : FILTERS) {
            if (entry.id() == id) {
                return entry;
            }
        }
        return find(DEFAULT);
    }

    /**
     * One call of a filter function: its name (what get_active_function_name() gives the messages) and the
     * exception raised so far. php-src's filters go on after an exception (the next element of an array, the
     * next filter of filter_var_array()'s definition until it checks), which then reaches the caller when the
     * function returns: a later error has the earlier as its previous, a callback isn't called any more, and a
     * failed validation leaves the value alone (RETURN_VALIDATION_FAILED's EG(exception) case).
     */
    private static final class Call {
        final PhpContext context;
        final String function;
        PhpThrowable pending;

        Call(PhpContext context, String function) {
            this.context = context;
            this.function = function;
        }

        /** Where the user code is now: where php-src's errors are. */
        private SourceLocation here() {
            return SourceLocation.current();
        }

        /** An error php-src raises (zend_throw_*), placed as php-src's object is: where the user code is now. */
        void raise(PhpError error) {
            error.placeIfUnplaced(here());
            pend(error);
        }

        /**
         * zend_throw_exception_internal: the new exception takes the pending one as its previous. Their objects
         * are made now, so an error from a built-in callback, which no user code saw, is placed here first.
         */
        void pend(PhpThrowable thrown) {
            if (pending != null) {
                for (PhpThrowable error : new PhpThrowable[] {thrown, pending}) {
                    if (error instanceof PhpError runtimeError) {
                        runtimeError.placeIfUnplaced(here());
                    }
                }
                TryNode.setPrevious(context, thrown, pending);
            }
            pending = thrown;
        }

        /** php_error_docref(NULL, E_WARNING, ...): the function's warning. */
        void warn(String message) {
            warning(Str.of(function + "(): " + message));
        }

        /** zend_error(E_WARNING, ...). */
        void warning(TruffleString text) {
            if (pending == null) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, text, here());
            } else {
                ErrorFunctions.raiseWithPendingException(context, ErrorFunctions.E_WARNING, text, here());
            }
        }

        /** The function's result, unless an exception is pending: that ends the call. */
        Object result(Object value) {
            if (pending != null) {
                throw pending;
            }
            return value;
        }
    }

    /** RETURN_VALIDATION_FAILED: false, null with FILTER_NULL_ON_FAILURE, the value as is once an exception is out. */
    private static Object failed(Call call, Object value, long flags) {
        if (call.pending != null) {
            return value;
        }
        if ((flags & THROW_ON_FAILURE) != 0) {
            return FAILURE;
        }
        return (flags & NULL_ON_FAILURE) != 0 ? PhpNull.NULL : Boolean.FALSE;
    }

    // ---- php_zval_filter and php_filter_call ----

    /** php_zval_filter: one scalar through the filter; options is the filter's option array, or a callback. */
    private static Object zvalFilter(Call call, Object value, long filter, long flags, Object options) {
        Entry entry = find(filter);
        String objectClass = classWithoutToString(value);
        if (objectClass != null) {
            // #49274, #67167: an object that can't be a string fails, as null with FILTER_NULL_ON_FAILURE
            if ((flags & THROW_ON_FAILURE) != 0) {
                call.raise(new PhpError(FAILED_EXCEPTION, "filter validation failed: object of type " + objectClass
                        + " has no __toString() method"));
                return PhpNull.NULL;
            }
            value = (flags & NULL_ON_FAILURE) != 0 ? PhpNull.NULL : Boolean.FALSE;
        } else {
            TruffleString string;
            if (value instanceof PhpObject && call.pending != null) {
                // __toString() isn't called while an exception is out, and the object converts to ""
                string = Str.EMPTY;
            } else {
                try {
                    string = Convert.toStr(value);
                } catch (PhpThrowable e) {
                    // convert_to_string: __toString() threw, and the value is ""
                    call.pend(e);
                    string = Str.EMPTY;
                }
            }
            value = entry.filter().apply(call, string, flags, options);
            if (value == FAILURE) {
                call.raise(new PhpError(FAILED_EXCEPTION, concat("filter validation failed: filter " + entry.name()
                        + " not satisfied by '", cBytes(string), "'")));
                return PhpNull.NULL;
            }
        }
        if (options instanceof PhpArray array && ((flags & NULL_ON_FAILURE) != 0 ? value == PhpNull.NULL
                : value == Boolean.FALSE)) {
            Object fallback = array.get(KEY_DEFAULT);
            if (fallback != null) {
                value = PhpRef.deref(fallback);
            }
        }
        return value;
    }

    /** The class of an object that has no __toString() (php-src fails it), or null. */
    private static String classWithoutToString(Object value) {
        if (value instanceof PhpObject object) {
            return object.getPhpClass().findMethod("__tostring") == null ? object.getPhpClass().getName() : null;
        }
        if (value instanceof PhpClosure) {
            return "Closure";
        }
        if (value instanceof PhpGenerator) {
            return "Generator";
        }
        return null;
    }

    /**
     * php_filter_call: the value (already the caller's own copy) through the filter, which filterArgs (the options
     * array) or filterArgsLong (the flags, or the filter when filter is -1) select; flags are the caller's.
     */
    private static Object filterCall(Call call, Object filtered, long filter, PhpArray filterArgs,
                                     long filterArgsLong, long filterFlags, int optionsArg) {
        Object options = null;
        if (filterArgs == null) {
            if (filter != -1) {
                filterFlags = filterArgsLong;
                if ((filterFlags & (REQUIRE_ARRAY | FORCE_ARRAY)) == 0) {
                    filterFlags |= REQUIRE_SCALAR;
                }
            } else {
                filter = filterArgsLong;
            }
        } else {
            Object option = filterArgs.get(KEY_FILTER);
            if (option != null) {
                filter = zvalGetLong(call, option);
            }
            option = PhpRef.deref(filterArgs.get(KEY_OPTIONS));
            if (option != null) {
                if (filter != CALLBACK) {
                    if (option instanceof PhpArray) {
                        options = option;
                    }
                } else {
                    options = option;
                    filterFlags = 0;
                }
            }
            option = filterArgs.get(KEY_FLAGS);
            if (option != null) {
                filterFlags = zvalGetLong(call, option);
                if ((filterFlags & (REQUIRE_ARRAY | FORCE_ARRAY)) == 0) {
                    filterFlags |= REQUIRE_SCALAR;
                }
            }
        }
        if ((filterFlags & NULL_ON_FAILURE) != 0 && (filterFlags & THROW_ON_FAILURE) != 0) {
            call.raise(bothOnFailure(call.function, optionsArg));
            return PhpNull.NULL;
        }
        if (filtered instanceof PhpArray array) {
            if ((filterFlags & REQUIRE_SCALAR) != 0) {
                if ((filterFlags & THROW_ON_FAILURE) != 0) {
                    call.raise(new PhpError(FAILED_EXCEPTION,
                            "filter validation failed: not a scalar value (got an array)"));
                    return PhpNull.NULL;
                }
                return (filterFlags & NULL_ON_FAILURE) != 0 ? PhpNull.NULL : Boolean.FALSE;
            }
            filterRecursive(call, array, filter, filterFlags, options,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            return array;
        }
        if ((filterFlags & REQUIRE_ARRAY) != 0) {
            if ((filterFlags & THROW_ON_FAILURE) != 0) {
                call.raise(new PhpError(FAILED_EXCEPTION, "filter validation failed: not an array (got "
                        + Args.valueName(filtered) + ")"));
                return PhpNull.NULL;
            }
            return (filterFlags & NULL_ON_FAILURE) != 0 ? PhpNull.NULL : Boolean.FALSE;
        }
        Object result = zvalFilter(call, filtered, filter, filterFlags, options);
        // an exception isn't wrapped in an array
        if (call.pending != null) {
            return result;
        }
        return (filterFlags & FORCE_ARRAY) != 0 ? PhpArray.list(result) : result;
    }

    private static PhpError bothOnFailure(String function, int optionsArg) {
        return new PhpError("ValueError", function + "(): Argument #" + optionsArg
                + " ($options) cannot use both FILTER_NULL_ON_FAILURE and FILTER_THROW_ON_FAILURE");
    }

    /**
     * php_zval_filter_recursive on an array the caller owns: each element filtered in place, as php-src does, so an
     * element that is a reference changes what it refers to; a nested array is separated first (SEPARATE_ARRAY),
     * and one already being walked (a reference cycle) is left alone.
     */
    private static void filterRecursive(Call call, PhpArray array, long filter, long flags, Object options,
                                        Set<PhpArray> walking) {
        if (!walking.add(array)) {
            return;
        }
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            Object element = array.rawAt(p);
            if (element instanceof PhpRef ref) {
                if (ref.get() instanceof PhpArray) {
                    filterRecursive(call, ref.arrayForWrite(), filter, flags, options, walking);
                } else {
                    ref.set(zvalFilter(call, ref.get(), filter, flags, options));
                }
            } else if (element instanceof PhpArray) {
                filterRecursive(call, array.arrayForWrite(array.keyAt(p)), filter, flags, options, walking);
            } else {
                array.put(array.keyAt(p), zvalFilter(call, element, filter, flags, options));
            }
        }
        walking.remove(array);
    }

    /** ZVAL_DUP: an array is copied (its elements are filtered in place), anything else is the value itself. */
    private static Object dup(Object value) {
        return value instanceof PhpArray array ? array.copy() : value;
    }

    /** zval_get_long: as (int), but an object php-src can't cast warns and is 1. */
    private static long zvalGetLong(Call call, Object value) {
        value = PhpRef.deref(value);
        String objectClass = classOfObject(value);
        if (objectClass != null && !(value instanceof PhpObject object && object.getPhpClass().getHandlers() != null
                && object.getPhpClass().getHandlers().castsToId())) {
            call.warning(Str.of("Object of class " + objectClass + " could not be converted to int"));
            return 1;
        }
        return Convert.toLong(value);
    }

    /** zval_get_double, likewise. */
    private static double zvalGetDouble(Call call, Object value) {
        value = PhpRef.deref(value);
        String objectClass = classOfObject(value);
        if (objectClass != null) {
            call.warning(Str.of("Object of class " + objectClass + " could not be converted to float"));
            return 1;
        }
        return Convert.toDouble(value);
    }

    /** An object's class name; null for any other value. */
    private static String classOfObject(Object value) {
        if (value instanceof PhpObject object) {
            return object.getPhpClass().getName();
        }
        if (value instanceof PhpClosure) {
            return "Closure";
        }
        if (value instanceof PhpGenerator) {
            return "Generator";
        }
        return null;
    }

    // ---- the functions ----

    /** Z_PARAM_ARRAY_HT_OR_LONG's array, or null (the int is then arrayOrLong's). */
    private static PhpArray arrayOrNull(Object[] a, int index) {
        return Args.get(a, index) instanceof PhpArray array ? array : null;
    }

    private static long arrayOrLong(PhpContext context, Object[] a, int index, String function, String name,
                                    long missing) {
        if (!Args.has(a, index)) {
            return missing;
        }
        return Args.get(a, index) instanceof PhpArray ? 0
                : Args.integer(context, a, index, function, name, "array|int");
    }

    @TruffleBoundary
    private static Object filterVar(PhpContext context, Object[] a) {
        Args.arity(a, "filter_var", 1, 3);
        Object data = Args.get(a, 0);
        long filter = Args.has(a, 1) ? Args.integer(context, a, 1, "filter_var", "filter", "int") : DEFAULT;
        long argsLong = arrayOrLong(context, a, 2, "filter_var", "options", 0);
        PhpArray args = arrayOrNull(a, 2);
        Call call = new Call(context, "filter_var");
        if (!idExists(filter)) {
            call.warn("Unknown filter with ID " + filter);
            return Boolean.FALSE;
        }
        return call.result(filterCall(call, dup(data), filter, args, argsLong, REQUIRE_SCALAR, 3));
    }

    @TruffleBoundary
    private static Object filterVarArray(PhpContext context, Object[] a) {
        Args.arity(a, "filter_var_array", 1, 3);
        if (!(Args.get(a, 0) instanceof PhpArray input)) {
            throw Args.typeError("filter_var_array", 1, "array", "array", Args.get(a, 0));
        }
        long op = arrayOrLong(context, a, 1, "filter_var_array", "options", DEFAULT);
        PhpArray definition = arrayOrNull(a, 1);
        boolean addEmpty = !Args.has(a, 2) || Args.bool(context, a, 2, "filter_var_array", "add_empty");
        Call call = new Call(context, "filter_var_array");
        if (definition == null && !idExists(op)) {
            call.warn("Unknown filter with ID " + op);
            return Boolean.FALSE;
        }
        return call.result(arrayHandler(call, input, definition, op, addEmpty));
    }

    /** php_filter_array_handler: the input through one filter, or through the definition's filter per key. */
    private static Object arrayHandler(Call call, PhpArray input, PhpArray definition, long op, boolean addEmpty) {
        if (definition == null) {
            return filterCall(call, input.copy(), -1, null, op, REQUIRE_ARRAY, 2);
        }
        PhpArray result = new PhpArray();
        for (int p = definition.firstPosition(); p >= 0; p = definition.nextPosition(p)) {
            if (!(definition.keyAt(p) instanceof TruffleString key)) {
                throw new PhpError("TypeError", call.function
                        + "(): Argument #2 ($options) must contain only string keys");
            }
            if (Str.length(key) == 0) {
                throw new PhpError("ValueError", call.function
                        + "(): Argument #2 ($options) cannot contain empty keys");
            }
            Object value = input.get(key);
            if (value == null) {
                if (addEmpty) {
                    result.put(key, PhpNull.NULL);
                }
                continue;
            }
            Object copy = dup(PhpRef.deref(value));
            // not dereferenced: a reference to an array is an int (1) here, as in php-src
            Object element = definition.rawAt(p);
            PhpArray args = element instanceof PhpArray array ? array : null;
            long filter = 0;
            if (args == null) {
                filter = zvalGetLong(call, element);
                if (!idExists(filter)) {
                    call.warn("Unknown filter with ID " + filter);
                }
                filter = zvalGetLong(call, element);
            }
            copy = filterCall(call, copy, -1, args, filter, REQUIRE_SCALAR, 2);
            if (call.pending != null) {
                throw call.pending;
            }
            result.put(key, copy);
        }
        return result;
    }

    // ---- the request's input ----

    /**
     * What filter_input() and filter_has_var() read: the variables as the SAPI registered them at the start of the
     * request (the copies php-src's input filter keeps, IF_G(get_array)...), whatever the script does to the
     * superglobals later. An array no variable came in for is null (php-src leaves it undefined), as INPUT_GET is
     * under the CLI.
     */
    static final class Input {
        PhpArray get;
        PhpArray post;
        PhpArray cookie;
        /** $_SERVER as the SAPI made it: INPUT_SERVER is the part of it that went through the input filter. */
        private PhpArray server;
        private boolean cgi;
        private PhpArray serverInput;
        /** PG(http_globals)[TRACK_VARS_ENV]: $_ENV as made. */
        private PhpArray env;

        /** Holds a superglobal's array as php-src's copy does: the superglobal is copied before it is written. */
        static PhpArray kept(PhpArray array) {
            array.addHolder();
            return array;
        }

        void globals(PhpArray server, PhpArray env, boolean cgi) {
            this.server = kept(server);
            this.env = kept(env);
            this.cgi = cgi;
        }

        /**
         * The CLI passes PHP_SELF, SCRIPT_NAME, SCRIPT_FILENAME, PATH_TRANSLATED and DOCUMENT_ROOT through the input
         * filter (sapi_cli_register_variables), not the environment; under the CGI SAPI the request's variables
         * pass through it, as php-fpm's FastCGI parameters do, but not REQUEST_TIME and REQUEST_TIME_FLOAT.
         */
        PhpArray server() {
            if (serverInput == null && server != null) {
                PhpArray input = new PhpArray();
                if (cgi) {
                    for (int p = server.firstPosition(); p >= 0; p = server.nextPosition(p)) {
                        if (serverInput(server.keyAt(p))) {
                            input.put(server.keyAt(p), server.valueAt(p));
                        }
                    }
                } else {
                    for (String name : CLI_SERVER_INPUT) {
                        Object value = server.get(Str.of(name));
                        if (value != null) {
                            input.put(Str.of(name), value);
                        }
                    }
                }
                serverInput = input;
            }
            return serverInput;
        }

        /** Whether the $_SERVER element is one the SAPI passed through the input filter. */
        boolean serverInput(Object key) {
            String name = key instanceof TruffleString s ? Str.java(s) : "";
            if (cgi) {
                return !name.equals("REQUEST_TIME") && !name.equals("REQUEST_TIME_FLOAT");
            }
            for (String input : CLI_SERVER_INPUT) {
                if (input.equals(name)) {
                    return true;
                }
            }
            return false;
        }

        private static final String[] CLI_SERVER_INPUT = {"PHP_SELF", "SCRIPT_NAME", "SCRIPT_FILENAME",
                "PATH_TRANSLATED", "DOCUMENT_ROOT"};
    }

    /** php_filter_get_storage. */
    private static PhpArray storage(PhpContext context, long type, String function, String name) {
        Input input = context.builtinState().filterInput;
        if (type == INPUT_GET) {
            return input.get;
        } else if (type == INPUT_POST) {
            return input.post;
        } else if (type == INPUT_COOKIE) {
            return input.cookie;
        } else if (type == INPUT_SERVER) {
            return input.server();
        } else if (type == INPUT_ENV) {
            return input.env;
        }
        throw new PhpError("ValueError", function + "(): Argument #1 ($" + name + ") must be an INPUT_* constant");
    }

    @TruffleBoundary
    private static Object filterInput(PhpContext context, Object[] a) {
        Args.arity(a, "filter_input", 2, 4);
        long type = Args.integer(context, a, 0, "filter_input", "type", "int");
        TruffleString name = Args.string(context, a, 1, "filter_input", "var_name", false);
        long filter = Args.has(a, 2) ? Args.integer(context, a, 2, "filter_input", "filter", "int") : DEFAULT;
        long argsLong = arrayOrLong(context, a, 3, "filter_input", "options", 0);
        PhpArray args = arrayOrNull(a, 3);
        Call call = new Call(context, "filter_input");
        if (!idExists(filter)) {
            call.warn("Unknown filter with ID " + filter);
            return Boolean.FALSE;
        }
        PhpArray input = storage(context, type, "filter_input", "type");
        Object value = lookup(input, name);
        if (value == null) {
            long flags = argsLong;
            if (args != null) {
                Object option = args.get(KEY_FLAGS);
                flags = option != null ? zvalGetLong(call, option) : 0;
            }
            if ((flags & NULL_ON_FAILURE) != 0 && (flags & THROW_ON_FAILURE) != 0) {
                throw bothOnFailure("filter_input", 4);
            }
            if ((flags & THROW_ON_FAILURE) != 0) {
                throw new PhpError(FAILED_EXCEPTION, concat("input value '", cBytes(name), "' not found"));
            }
            // FILTER_THROW_ON_FAILURE goes before the default
            if (args != null && PhpRef.deref(args.get(KEY_OPTIONS)) instanceof PhpArray options) {
                Object fallback = options.get(KEY_DEFAULT);
                if (fallback != null) {
                    return PhpRef.deref(fallback);
                }
            }
            // FILTER_NULL_ON_FAILURE swaps the answers: a missing input is false then, a failed one null
            return (flags & NULL_ON_FAILURE) != 0 ? Boolean.FALSE : PhpNull.NULL;
        }
        return call.result(filterCall(call, dup(PhpRef.deref(value)), filter, args, argsLong, REQUIRE_SCALAR, 4));
    }

    @TruffleBoundary
    private static Object filterHasVar(PhpContext context, Object[] a) {
        Args.arity(a, "filter_has_var", 2, 2);
        long type = Args.integer(context, a, 0, "filter_has_var", "input_type", "int");
        TruffleString name = Args.string(context, a, 1, "filter_has_var", "var_name", false);
        PhpArray input = storage(context, type, "filter_has_var", "input_type");
        return lookup(input, name) != null;
    }

    @TruffleBoundary
    private static Object filterInputArray(PhpContext context, Object[] a) {
        Args.arity(a, "filter_input_array", 1, 3);
        long type = Args.integer(context, a, 0, "filter_input_array", "type", "int");
        long op = arrayOrLong(context, a, 1, "filter_input_array", "options", DEFAULT);
        PhpArray definition = arrayOrNull(a, 1);
        boolean addEmpty = !Args.has(a, 2) || Args.bool(context, a, 2, "filter_input_array", "add_empty");
        Call call = new Call(context, "filter_input_array");
        if (definition == null && !idExists(op)) {
            call.warn("Unknown filter with ID " + op);
            return Boolean.FALSE;
        }
        PhpArray input = storage(context, type, "filter_input_array", "type");
        if (input == null) {
            return PhpNull.NULL;
        }
        return call.result(arrayHandler(call, input, definition, op, addEmpty));
    }

    /**
     * The element under the name as zend_hash_find looks it up, a string key: a numeric name ("1") is never a key of
     * a PHP array (such elements are under int keys), so it finds nothing.
     */
    private static Object lookup(PhpArray array, TruffleString name) {
        Object key = ArrayKey.of(name);
        return array == null || key instanceof Long ? null : array.get(key);
    }

    // ---- logical_filters.c ----

    /** A byte of the string, or 0 past its end: C reads the terminating NUL there. */
    private static int at(byte[] s, int i) {
        return i >= 0 && i < s.length ? s[i] & 0xFF : 0;
    }

    /** PHP_FILTER_TRIM_DEFAULT's whitespace. */
    private static boolean isTrimmed(int c) {
        return c == ' ' || c == '\t' || c == '\r' || c == 0x0B || c == '\n';
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlnum(int c) {
        return isDigit(c) || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static int hexValue(int c) {
        if (isDigit(c)) {
            return c - '0';
        } else if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** An option of the filter's option array (FETCH_*_OPTION's lookup), or null. */
    private static Object option(Object options, TruffleString key) {
        return options instanceof PhpArray array ? array.get(key) : null;
    }

    /** FETCH_STRING_OPTION: a string option (through a reference), or null. */
    private static TruffleString stringOption(Object options, TruffleString key) {
        return PhpRef.deref(option(options, key)) instanceof TruffleString s ? s : null;
    }

    /** The start and length of the string without PHP_FILTER_TRIM_DEFAULT's whitespace at either end. */
    private static int[] trim(byte[] s) {
        int p = 0;
        int length = s.length;
        while (length > 0 && isTrimmed(at(s, p))) {
            p++;
            length--;
        }
        if (length > 0) {
            while (isTrimmed(at(s, p + length - 1))) {
                length--;
            }
        }
        return new int[] {p, length};
    }

    /** php_filter_parse_int on s[from, from + length): the value in out[0]; false when it isn't an int. */
    private static boolean parseInt(byte[] s, int from, int length, long[] out) {
        int p = from;
        int end = from + length;
        boolean negative = false;
        if (at(s, p) == '-') {
            negative = true;
            p++;
        } else if (at(s, p) == '+') {
            p++;
        }
        if (at(s, p) == '0' && p + 1 == end) {
            // +0 and -0
            return true;
        }
        long value;
        if (p < end && at(s, p) >= '1' && at(s, p) <= '9') {
            value = (negative ? -1 : 1) * (at(s, p++) - '0');
        } else {
            return false;
        }
        if (end - p > 19) {
            // too long for an int
            return false;
        }
        while (p < end) {
            if (!isDigit(at(s, p))) {
                return false;
            }
            int digit = at(s, p++) - '0';
            if (!negative && value <= (Long.MAX_VALUE - digit) / 10) {
                value = value * 10 + digit;
            } else if (negative && value >= (Long.MIN_VALUE + digit) / 10) {
                value = value * 10 - digit;
            } else {
                return false;
            }
        }
        out[0] = value;
        return true;
    }

    /** php_filter_parse_octal and _hex: unsigned, failing on overflow; the bits as an int in out[0]. */
    private static boolean parseUnsigned(byte[] s, int from, int length, int radix, long[] out) {
        long value = 0;
        for (int p = from; p < from + length; p++) {
            int c = at(s, p);
            int digit = radix == 8 ? (c >= '0' && c <= '7' ? c - '0' : -1) : hexValue(c);
            if (digit < 0) {
                return false;
            }
            if (Long.compareUnsigned(value, Long.divideUnsigned(-1L, radix)) > 0) {
                return false;
            }
            value *= radix;
            if (Long.compareUnsigned(value, -1L - digit) > 0) {
                return false;
            }
            value += digit;
        }
        out[0] = value;
        return true;
    }

    /** FILTER_VALIDATE_INT (php_filter_int): decimal, and with the flags 0x hexadecimal and 0/0o octal. */
    private static Object validateInt(Call call, TruffleString value, long flags, Object options) {
        Object min = option(options, KEY_MIN_RANGE);
        long minRange = min != null ? zvalGetLong(call, min) : 0;
        Object max = option(options, KEY_MAX_RANGE);
        long maxRange = max != null ? zvalGetLong(call, max) : 0;
        byte[] s = Str.readBytes(value);
        if (s.length == 0) {
            return failed(call, value, flags);
        }
        int[] trimmed = trim(s);
        int p = trimmed[0];
        int length = trimmed[1];
        if (length < 1) {
            return failed(call, value, flags);
        }
        long[] number = {0};
        boolean error = false;
        if (at(s, p) == '0') {
            p++;
            length--;
            if ((flags & FLAG_ALLOW_HEX) != 0 && (at(s, p) == 'x' || at(s, p) == 'X')) {
                p++;
                length--;
                if (length == 0) {
                    return failed(call, value, flags);
                }
                error = !parseUnsigned(s, p, length, 16, number);
            } else if ((flags & FLAG_ALLOW_OCTAL) != 0) {
                // the explicit octal prefix, 0o
                if (at(s, p) == 'o' || at(s, p) == 'O') {
                    p++;
                    length--;
                    if (length == 0) {
                        return failed(call, value, flags);
                    }
                }
                error = !parseUnsigned(s, p, length, 8, number);
            } else if (length != 0) {
                error = true;
            }
        } else {
            error = !parseInt(s, p, length, number);
        }
        if (error || min != null && number[0] < minRange || max != null && number[0] > maxRange) {
            return failed(call, value, flags);
        }
        return number[0];
    }

    /** FILTER_VALIDATE_BOOL (php_filter_boolean): true for 1/true/on/yes, false for 0/false/off/no and "". */
    private static Object validateBool(Call call, TruffleString value, long flags, Object options) {
        byte[] s = Str.readBytes(value);
        int[] trimmed = trim(s);
        String word = new String(s, trimmed[0], trimmed[1], StandardCharsets.ISO_8859_1);
        Boolean result = switch (trimmed[1]) {
            case 0 -> Boolean.FALSE;
            case 1 -> word.equals("1") ? Boolean.TRUE : word.equals("0") ? Boolean.FALSE : null;
            case 2 -> word.equalsIgnoreCase("on") ? Boolean.TRUE : word.equalsIgnoreCase("no") ? Boolean.FALSE : null;
            case 3 -> word.equalsIgnoreCase("yes") ? Boolean.TRUE
                    : word.equalsIgnoreCase("off") ? Boolean.FALSE : null;
            case 4 -> word.equalsIgnoreCase("true") ? Boolean.TRUE : null;
            case 5 -> word.equalsIgnoreCase("false") ? Boolean.FALSE : null;
            default -> null;
        };
        return result != null ? result : failed(call, value, flags);
    }

    /**
     * FILTER_VALIDATE_FLOAT (php_filter_float): a number with the "decimal" separator, groups of three digits
     * between the "thousand" separators with FILTER_FLAG_ALLOW_THOUSAND, and an exponent; finite.
     */
    private static Object validateFloat(Call call, TruffleString value, long flags, Object options) {
        byte[] s = Str.readBytes(value);
        int[] trimmed = trim(s);
        int p = trimmed[0];
        int length = trimmed[1];
        if (length < 1) {
            return failed(call, value, flags);
        }
        int end = p + length;
        int decimalSeparator = '.';
        TruffleString decimal = stringOption(options, KEY_DECIMAL);
        if (decimal != null) {
            if (Str.length(decimal) != 1) {
                call.raise(new PhpError("ValueError", call.function
                        + "(): \"decimal\" option must be one character long"));
                return failed(call, value, flags);
            }
            decimalSeparator = Str.byteAt(decimal, 0);
        }
        byte[] thousands;
        TruffleString thousand = stringOption(options, KEY_THOUSAND);
        if (thousand != null) {
            if (Str.length(thousand) < 1) {
                call.raise(new PhpError("ValueError", call.function + "(): \"thousand\" option must not be empty"));
                return failed(call, value, flags);
            }
            thousands = Str.readBytes(thousand);
        } else {
            thousands = new byte[] {'\'', ',', '.'};
        }
        Object min = option(options, KEY_MIN_RANGE);
        double minRange = min != null ? zvalGetDouble(call, min) : 0;
        Object max = option(options, KEY_MAX_RANGE);
        double maxRange = max != null ? zvalGetDouble(call, max) : 0;

        // the number without its thousand separators and with '.' for the decimal one
        ByteBuilder number = new ByteBuilder(length + 1);
        if (p < end && (at(s, p) == '+' || at(s, p) == '-')) {
            number.write(at(s, p++));
        }
        boolean first = true;
        while (true) {
            int digits = 0;
            while (p < end && isDigit(at(s, p))) {
                digits++;
                number.write(at(s, p++));
            }
            if (p == end || at(s, p) == decimalSeparator || at(s, p) == 'e' || at(s, p) == 'E') {
                if (!first && digits != 3) {
                    return failed(call, value, flags);
                }
                if (at(s, p) == decimalSeparator) {
                    number.write('.');
                    p++;
                    while (p < end && isDigit(at(s, p))) {
                        number.write(at(s, p++));
                    }
                }
                if (at(s, p) == 'e' || at(s, p) == 'E') {
                    number.write(at(s, p++));
                    if (p < end && (at(s, p) == '+' || at(s, p) == '-')) {
                        number.write(at(s, p++));
                    }
                    while (p < end && isDigit(at(s, p))) {
                        number.write(at(s, p++));
                    }
                }
                break;
            }
            if ((flags & FLAG_ALLOW_THOUSAND) != 0 && cContains(thousands, at(s, p))) {
                if (first ? digits < 1 || digits > 3 : digits != 3) {
                    return failed(call, value, flags);
                }
                first = false;
                p++;
            } else {
                return failed(call, value, flags);
            }
        }
        if (p != end) {
            return failed(call, value, flags);
        }
        byte[] text = number.toByteArray();
        Object parsed = Convert.parseNumber(text, false);
        if (parsed instanceof Long l) {
            if (min != null && l < minRange || max != null && l > maxRange) {
                return failed(call, value, flags);
            }
            return (double) l;
        } else if (parsed instanceof Double d) {
            // an underflow ("1e-400") or an overflow fails
            if (d == 0 && text.length > 1 && hasNonZeroDigit(text) || !Double.isFinite(d)) {
                return failed(call, value, flags);
            }
            if (min != null && d < minRange || max != null && d > maxRange) {
                return failed(call, value, flags);
            }
            return d;
        }
        return failed(call, value, flags);
    }

    /** strchr(set, c): the C string set holds c, its terminator included (so c == 0 is in it). */
    private static boolean cContains(byte[] set, int c) {
        for (byte b : set) {
            if (b == 0) {
                break;
            }
            if ((b & 0xFF) == c) {
                return true;
            }
        }
        return c == 0;
    }

    private static boolean hasNonZeroDigit(byte[] text) {
        for (byte b : text) {
            if (b >= '1' && b <= '9') {
                return true;
            }
        }
        return false;
    }

    /** FILTER_VALIDATE_REGEXP (php_filter_validate_regexp): the value, if the "regexp" option matches it. */
    private static Object validateRegexp(Call call, TruffleString value, long flags, Object options) {
        TruffleString regexp = stringOption(options, KEY_REGEXP);
        if (regexp == null) {
            call.raise(new PhpError("ValueError", call.function + "(): \"regexp\" option is missing"));
            return failed(call, value, flags);
        }
        return match(call, regexp, value) ? value : failed(call, value, flags);
    }

    /** pcre_get_compiled_regex (with its warning for a bad pattern) and one pcre2_match. */
    private static boolean match(Call call, TruffleString regexp, TruffleString value) {
        String[] warning = new String[1];
        int matched = call.context.builtinState().pcre.filterMatch(regexp, value, warning);
        if (matched < 0) {
            call.warn(warning[0]);
        }
        return matched > 0;
    }

    /** php_filter_validate_domain_ex. */
    private static boolean validDomain(byte[] s, long flags) {
        boolean hostname = (flags & FLAG_HOSTNAME) != 0;
        int length = s.length;
        int end = length;
        // a trailing dot is ignored
        if (length > 0 && s[length - 1] == '.') {
            end = length - 1;
            length--;
        }
        // 253 characters at most, the final dot not included
        if (length > 253) {
            return false;
        }
        // the first character must be alphanumeric
        if (at(s, 0) == '.' || hostname && !isAlnum(at(s, 0))) {
            return false;
        }
        int label = 1;
        for (int p = 0; p < end; p++) {
            if (s[p] == '.') {
                // a label starts and ends with an alphanumeric character
                if (at(s, p + 1) == '.' || hostname && (!isAlnum(at(s, p - 1)) || !isAlnum(at(s, p + 1)))) {
                    return false;
                }
                label = 1;
            } else {
                if (label > 63 || hostname && (s[p] != '-' || at(s, p + 1) == 0) && !isAlnum(at(s, p))) {
                    return false;
                }
                label++;
            }
        }
        return true;
    }

    /** FILTER_VALIDATE_DOMAIN. */
    private static Object validateDomain(Call call, TruffleString value, long flags, Object options) {
        return validDomain(Str.readBytes(value), flags) ? value : failed(call, value, flags);
    }

    /**
     * is_userinfo_valid: unreserved and sub-delims characters, ':', and %-escapes (as php-src checks them, a digit
     * then a hex digit). strchr also finds a NUL byte in its set, and for a string shorter than 3 bytes the bound
     * (an unsigned length - 3) always holds.
     */
    private static boolean validUserinfo(byte[] s) {
        int p = 0;
        while (p < s.length) {
            int c = s[p] & 0xFF;
            if (isAlnum(c) || c == 0 || "-._~!$&'()*+,;=:".indexOf(c) >= 0) {
                p++;
            } else if (c == '%' && (s.length < 3 || p <= s.length - 3) && isDigit(at(s, p + 1))
                    && hexValue(at(s, p + 2)) >= 0) {
                p += 3;
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * FILTER_VALIDATE_URL (php_filter_validate_url) with parse_url's parser (php_url_parse_ex2): no byte
     * FILTER_SANITIZE_URL would drop, a scheme, a host (but for mailto:, news: and file:) that for http(s) is a
     * host name or a bracketed IPv6 address, valid user info, and the path and query the flags require.
     */
    private static Object validateUrl(Call call, TruffleString value, long flags, Object options) {
        int oldLength = Str.length(value);
        TruffleString url = keepOnly(value, URL_CHARS);
        if (Str.length(url) != oldLength) {
            return failed(call, url, flags);
        }
        TruffleString parser = stringOption(options, KEY_URI_PARSER_CLASS);
        if (parser != null && !Str.java(parser).equals("parse_url")) {
            String name = Str.java(parser);
            if (name.equals("Uri\\Rfc3986\\Uri") || name.equals("Uri\\WhatWg\\Url")) {
                // ext/uri's parsers (uriparser's RFC 3986 one, lexbor's WHATWG one): Truffle PHP has no ext/uri
                throw new PhpError("Error", call.function + "(): \"uri_parser_class\" " + name
                        + " needs ext/uri, which Truffle PHP doesn't implement");
            }
            call.raise(new PhpError("ValueError", call.function
                    + "(): \"uri_parser_class\" option has invalid value"));
            return failed(call, url, flags);
        }
        Object[] parts = UrlFunctions.parse(Str.readBytes(url));
        if (parts == null) {
            return failed(call, url, flags);
        }
        String scheme = parts[UrlFunctions.SCHEME] instanceof TruffleString s ? latin1(s) : null;
        TruffleString host = (TruffleString) parts[UrlFunctions.HOST];
        if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            if (host == null) {
                return failed(call, url, flags);
            }
            // an IPv6 address in square brackets is a host name, and so is a domain (loosely, an IPv4 address)
            byte[] h = Str.readBytes(host);
            boolean ipv6 = h.length >= 2 && h[0] == '[' && h[h.length - 1] == ']'
                    && validIpv6(h, 1, h.length - 2, null);
            if (!ipv6 && !validDomain(h, FLAG_HOSTNAME)) {
                return failed(call, url, flags);
            }
        }
        if (scheme == null || host == null && !scheme.equals("mailto") && !scheme.equals("news")
                && !scheme.equals("file") || (flags & FLAG_PATH_REQUIRED) != 0 && parts[UrlFunctions.PATH] == null
                || (flags & FLAG_QUERY_REQUIRED) != 0 && parts[UrlFunctions.QUERY] == null) {
            return failed(call, url, flags);
        }
        if (parts[UrlFunctions.USER] instanceof TruffleString user && !validUserinfo(Str.readBytes(user))
                || parts[UrlFunctions.PASS] instanceof TruffleString pass && !validUserinfo(Str.readBytes(pass))) {
            return failed(call, url, flags);
        }
        return url;
    }

    private static String latin1(TruffleString s) {
        return new String(Str.readBytes(s), StandardCharsets.ISO_8859_1);
    }

    /*
     * The e-mail patterns: Michael Rushton's (Copyright © Michael Rushton 2009-10, http://squiloople.com/, "Feel
     * free to use and redistribute this code. But please keep this copyright notice."), which php-src narrowed to
     * routable addresses. The first (FILTER_FLAG_EMAIL_UNICODE) also takes non-ASCII letters and digits.
     */
    private static final TruffleString EMAIL_UNICODE = Str.of("/^(?!(?:(?:\\x22?\\x5C[\\x00-\\x7E]\\x22?)|(?:\\x22?[^\\x5C\\x22]\\x22?)){255,})(?!(?:(?:\\x22?\\x5C[\\x00-\\x7E]\\x22?)|(?:\\x22?[^\\x5C\\x22]\\x22?)){65,}@)(?:(?:[\\x21\\x23-\\x27\\x2A\\x2B\\x2D\\x2F-\\x39\\x3D\\x3F\\x5E-\\x7E\\pL\\pN]+)|(?:\\x22(?:[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x21\\x23-\\x5B\\x5D-\\x7F\\pL\\pN]|(?:\\x5C[\\x00-\\x7F]))*\\x22))(?:\\.(?:(?:[\\x21\\x23-\\x27\\x2A\\x2B\\x2D\\x2F-\\x39\\x3D\\x3F\\x5E-\\x7E\\pL\\pN]+)|(?:\\x22(?:[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x21\\x23-\\x5B\\x5D-\\x7F\\pL\\pN]|(?:\\x5C[\\x00-\\x7F]))*\\x22)))*@(?:(?:(?!.*[^.]{64,})(?:(?:(?:xn--)?[a-z0-9]+(?:-+[a-z0-9]+)*\\.){1,126}){1,}(?:(?:[a-z][a-z0-9]*)|(?:(?:xn--)[a-z0-9]+))(?:-+[a-z0-9]+)*)|(?:\\[(?:(?:IPv6:(?:(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){7})|(?:(?!(?:.*[a-f0-9][:\\]]){7,})(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,5})?::(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,5})?)))|(?:(?:IPv6:(?:(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){5}:)|(?:(?!(?:.*[a-f0-9]:){5,})(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,3})?::(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,3}:)?)))?(?:(?:25[0-5])|(?:2[0-4][0-9])|(?:1[0-9]{2})|(?:[1-9]?[0-9]))(?:\\.(?:(?:25[0-5])|(?:2[0-4][0-9])|(?:1[0-9]{2})|(?:[1-9]?[0-9]))){3}))\\]))$/iDu");
    private static final TruffleString EMAIL = Str.of("/^(?!(?:(?:\\x22?\\x5C[\\x00-\\x7E]\\x22?)|(?:\\x22?[^\\x5C\\x22]\\x22?)){255,})(?!(?:(?:\\x22?\\x5C[\\x00-\\x7E]\\x22?)|(?:\\x22?[^\\x5C\\x22]\\x22?)){65,}@)(?:(?:[\\x21\\x23-\\x27\\x2A\\x2B\\x2D\\x2F-\\x39\\x3D\\x3F\\x5E-\\x7E]+)|(?:\\x22(?:[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x21\\x23-\\x5B\\x5D-\\x7F]|(?:\\x5C[\\x00-\\x7F]))*\\x22))(?:\\.(?:(?:[\\x21\\x23-\\x27\\x2A\\x2B\\x2D\\x2F-\\x39\\x3D\\x3F\\x5E-\\x7E]+)|(?:\\x22(?:[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x21\\x23-\\x5B\\x5D-\\x7F]|(?:\\x5C[\\x00-\\x7F]))*\\x22)))*@(?:(?:(?!.*[^.]{64,})(?:(?:(?:xn--)?[a-z0-9]+(?:-+[a-z0-9]+)*\\.){1,126}){1,}(?:(?:[a-z][a-z0-9]*)|(?:(?:xn--)[a-z0-9]+))(?:-+[a-z0-9]+)*)|(?:\\[(?:(?:IPv6:(?:(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){7})|(?:(?!(?:.*[a-f0-9][:\\]]){7,})(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,5})?::(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,5})?)))|(?:(?:IPv6:(?:(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){5}:)|(?:(?!(?:.*[a-f0-9]:){5,})(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,3})?::(?:[a-f0-9]{1,4}(?::[a-f0-9]{1,4}){0,3}:)?)))?(?:(?:25[0-5])|(?:2[0-4][0-9])|(?:1[0-9]{2})|(?:[1-9]?[0-9]))(?:\\.(?:(?:25[0-5])|(?:2[0-4][0-9])|(?:1[0-9]{2})|(?:[1-9]?[0-9]))){3}))\\]))$/iD");

    /** FILTER_VALIDATE_EMAIL (php_filter_validate_email): 320 bytes at most (RFC 2821), and the pattern. */
    private static Object validateEmail(Call call, TruffleString value, long flags, Object options) {
        if (Str.length(value) > 320) {
            return failed(call, value, flags);
        }
        TruffleString pattern = (flags & FLAG_EMAIL_UNICODE) != 0 ? EMAIL_UNICODE : EMAIL;
        return match(call, pattern, value) ? value : failed(call, value, flags);
    }

    /** _php_filter_validate_ipv4 on s[from, from + length): four decimal parts without leading zeros, into ip. */
    private static boolean validIpv4(byte[] s, int from, int length, int[] ip) {
        int end = from + length;
        int p = from;
        int n = 0;
        while (p < end) {
            if (s[p] < '0' || s[p] > '9') {
                return false;
            }
            boolean leadingZero = s[p] == '0';
            int m = 1;
            int num = s[p++] - '0';
            while (p < end && s[p] >= '0' && s[p] <= '9') {
                num = num * 10 + (s[p++] - '0');
                if (num > 255 || ++m > 3) {
                    return false;
                }
            }
            // a leading 0 would make an octal number, which isn't supported
            if (leadingZero && (num != 0 || m > 1)) {
                return false;
            }
            ip[n++] = num;
            if (n == 4) {
                return p == end;
            } else if (p >= end || s[p++] != '.') {
                return false;
            }
        }
        return false;
    }

    /** _php_filter_validate_ipv6 on s[from, from + length); ip, unless null, gets the eight blocks. */
    private static boolean validIpv6(byte[] s, int from, int length, int[] ip) {
        if (indexOf(s, from, from + length, ':') < 0) {
            return false;
        }
        int compressedPos = -1;
        int blocks = 0;
        int[] ip4 = new int[4];
        // an IPv4 address at the end
        int ipv4 = indexOf(s, from, from + length, '.');
        if (ipv4 >= 0) {
            while (ipv4 > from && s[ipv4 - 1] != ':') {
                ipv4--;
            }
            if (!validIpv4(s, ipv4, length - (ipv4 - from), ip4)) {
                return false;
            }
            length = ipv4 - from;
            if (length < 2) {
                return false;
            }
            // the ':' before it stays out, unless it ends a "::"
            if (s[ipv4 - 2] != ':') {
                length--;
            }
            blocks = 2;
        }
        int end = from + length;
        int p = from;
        parse:
        while (p < end) {
            if (s[p] == ':') {
                if (++p >= end) {
                    // a trailing ':' needs a ':' before it
                    return false;
                }
                if (s[p] == ':') {
                    if (compressedPos >= 0) {
                        return false;
                    }
                    if (ip != null && blocks < 8) {
                        ip[blocks] = -1;
                    }
                    // "::" is one or more 16-bit 0 blocks
                    compressedPos = blocks++;
                    if (++p == end) {
                        if (blocks > 8) {
                            return false;
                        }
                        break parse;
                    }
                } else if (p - 1 == from) {
                    // a leading ':' needs another after it
                    return false;
                }
            }
            int num = 0;
            int n = 0;
            while (p < end && hexValue(s[p] & 0xFF) >= 0) {
                num = 16 * num + hexValue(s[p] & 0xFF);
                n++;
                p++;
            }
            if (ip != null && blocks < 8) {
                ip[blocks] = num;
            }
            if (n < 1 || n > 4) {
                return false;
            }
            if (++blocks > 8) {
                return false;
            }
        }
        if (ip != null && ipv4 >= 0) {
            for (int i = 0; i < 5; i++) {
                ip[i] = 0;
            }
            ip[5] = 0xffff;
            ip[6] = 256 * ip4[0] + ip4[1];
            ip[7] = 256 * ip4[2] + ip4[3];
        } else if (ip != null && compressedPos >= 0 && blocks <= 8) {
            int offset = 8 - blocks;
            for (int i = 7; i > compressedPos + offset; i--) {
                ip[i] = ip[i - offset];
            }
            for (int i = compressedPos + offset; i >= compressedPos; i--) {
                ip[i] = 0;
            }
        }
        return compressedPos >= 0 && blocks <= 8 || blocks == 8;
    }

    private static int indexOf(byte[] s, int from, int to, int b) {
        for (int i = from; i < to; i++) {
            if (s[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /**
     * ipv4_get_status_flags, from RFC 6890's tables (with its errata): {global, reserved, private} for an address in
     * a special block, null for any other.
     */
    private static boolean[] ipv4Status(int[] ip) {
        boolean global = false;
        boolean reserved = false;
        boolean isPrivate = false;
        if (ip[0] == 0) {
            // RFC 0791 - This network
            reserved = true;
        } else if (ip[0] == 10) {
            // RFC 1918 - Private Use
            isPrivate = true;
        } else if (ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127) {
            // RFC 6598 - Shared Address Space
        } else if (ip[0] == 127) {
            // RFC 1122 - Loopback
            reserved = true;
        } else if (ip[0] == 169 && ip[1] == 254) {
            // RFC 3927 - Link Local
            reserved = true;
        } else if (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) {
            // RFC 1918 - Private Use
            isPrivate = true;
        } else if (ip[0] == 192 && ip[1] == 0 && ip[2] == 0) {
            // RFC 6890 - IETF Protocol Assignments, RFC 6333's DS-Lite among them
        } else if (ip[0] == 192 && ip[1] == 0 && ip[2] == 2) {
            // RFC 5737 - Documentation
        } else if (ip[0] == 192 && ip[1] == 88 && ip[2] == 99) {
            // RFC 3068 - 6to4 Relay Anycast
            global = true;
        } else if (ip[0] == 192 && ip[1] == 168) {
            // RFC 1918 - Private Use
            isPrivate = true;
        } else if (ip[0] == 198 && ip[1] >= 18 && ip[1] <= 19) {
            // RFC 2544 - Benchmarking
        } else if (ip[0] == 198 && ip[1] == 51 && ip[2] == 100 || ip[0] == 203 && ip[1] == 0 && ip[2] == 113) {
            // RFC 5737 - Documentation
        } else if (ip[0] >= 240 && ip[1] <= 255) {
            // RFC 1122 - Reserved, RFC 0919's limited broadcast among them
            reserved = true;
        } else {
            return null;
        }
        return new boolean[] {global, reserved, isPrivate};
    }

    /** ipv6_get_status_flags, likewise. */
    private static boolean[] ipv6Status(int[] ip) {
        boolean global = false;
        boolean reserved = false;
        boolean isPrivate = false;
        boolean zeroes = ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] == 0 && ip[4] == 0;
        if (zeroes && ip[5] == 0 && ip[6] == 0 && (ip[7] == 0 || ip[7] == 1)) {
            // RFC 4291 - Unspecified Address, Loopback Address
            reserved = true;
        } else if (ip[0] == 0x0064 && ip[1] == 0xff9b) {
            // RFC 6052 - IPv4-IPv6 Translation
            global = true;
        } else if (zeroes && ip[5] == 0xffff) {
            // RFC 4291 - IPv4-mapped Address
            reserved = true;
        } else if (ip[0] == 0x0100 && ip[1] == 0 && ip[2] == 0 && ip[3] == 0) {
            // RFC 6666 - Discard-Only Address Block
        } else if (ip[0] == 0x2001 && ip[1] <= 0x01ff) {
            // RFC 4380 - TEREDO, RFC 2928 - IETF Protocol Assignments (RFC 5180's Benchmarking, RFC 4843's ORCHID)
        } else if (ip[0] == 0x2001 && ip[1] == 0x0db8 || ip[0] == 0x2002) {
            // RFC 3849 - Documentation, RFC 3056 - 6to4
        } else if (ip[0] >= 0xfc00 && ip[0] <= 0xfdff) {
            // RFC 4193 - Unique-Local
            isPrivate = true;
        } else if (ip[0] >= 0xfe80 && ip[0] <= 0xfebf) {
            // RFC 4291 - Linked-Scoped Unicast
            reserved = true;
        } else {
            return null;
        }
        return new boolean[] {global, reserved, isPrivate};
    }

    /**
     * FILTER_VALIDATE_IP (php_filter_validate_ip): an IPv4 or IPv6 address (a ':' makes it IPv6, else a '.' IPv4)
     * of the families the flags allow, outside the ranges they exclude.
     */
    private static Object validateIp(Call call, TruffleString value, long flags, Object options) {
        byte[] s = Str.readBytes(value);
        boolean ipv6;
        if (indexOf(s, 0, s.length, ':') >= 0) {
            ipv6 = true;
        } else if (indexOf(s, 0, s.length, '.') >= 0) {
            ipv6 = false;
        } else {
            return failed(call, value, flags);
        }
        boolean allowIpv4 = (flags & FLAG_IPV4) != 0;
        boolean allowIpv6 = (flags & FLAG_IPV6) != 0;
        if (allowIpv4 != allowIpv6 && ipv6 == allowIpv4) {
            return failed(call, value, flags);
        }
        int[] ip = new int[8];
        boolean[] status;
        if (ipv6) {
            if (!validIpv6(s, 0, s.length, ip)) {
                return failed(call, value, flags);
            }
            status = ipv6Status(ip);
        } else {
            if (!validIpv4(s, 0, s.length, ip)) {
                return failed(call, value, flags);
            }
            status = ipv4Status(ip);
        }
        if (status == null) {
            // no special block
            return value;
        }
        if ((flags & FLAG_GLOBAL_RANGE) != 0 && !status[0] || (flags & FLAG_NO_PRIV_RANGE) != 0 && status[2]
                || (flags & FLAG_NO_RES_RANGE) != 0 && status[1]) {
            return failed(call, value, flags);
        }
        return value;
    }

    /** FILTER_VALIDATE_MAC (php_filter_validate_mac): 01-23-45-67-89-ab, 01:23:45:67:89:ab or 0123.4567.89ab. */
    private static Object validateMac(Call call, TruffleString value, long flags, Object options) {
        byte[] s = Str.readBytes(value);
        TruffleString expected = stringOption(options, KEY_SEPARATOR);
        if (expected != null && Str.length(expected) != 1) {
            call.raise(new PhpError("ValueError", call.function
                    + "(): \"separator\" option must be one character long"));
            return failed(call, value, flags);
        }
        int tokens;
        int length;
        int separator;
        if (s.length == 14) {
            // EUI-64: three groups of four hexadecimal digits, separated by dots
            tokens = 3;
            length = 4;
            separator = '.';
        } else if (s.length == 17 && (s[2] == '-' || s[2] == ':')) {
            // IEEE 802: six groups of two, separated by hyphens or colons
            tokens = 6;
            length = 2;
            separator = s[2];
        } else {
            return failed(call, value, flags);
        }
        if (expected != null && separator != Str.byteAt(expected, 0)) {
            return failed(call, value, flags);
        }
        long[] ignored = new long[1];
        for (int i = 0; i < tokens; i++) {
            int offset = i * (length + 1);
            if (i < tokens - 1 && s[offset + length] != separator
                    || !parseUnsigned(s, offset, length, 16, ignored)) {
                return failed(call, value, flags);
            }
        }
        return value;
    }

    // ---- sanitizing_filters.c ----

    private static final String ALPHA_DIGITS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    /** RFC 1738's section 5: the safe, extra, national, punctuation and reserved characters. */
    private static final boolean[] URL_CHARS = charSet(ALPHA_DIGITS + "$-_.+" + "!*'()," + "{}|\\^~[]`"
            + "<>#%\"" + ";/?:@&=");

    private static boolean[] charSet(String chars) {
        boolean[] set = new boolean[256];
        for (int i = 0; i < chars.length(); i++) {
            set[chars.charAt(i)] = true;
        }
        return set;
    }

    /** filter_map_apply: the bytes in the set. */
    private static TruffleString keepOnly(TruffleString value, boolean[] set) {
        byte[] s = Str.readBytes(value);
        ByteBuilder out = new ByteBuilder(s.length);
        for (byte b : s) {
            if (set[b & 0xFF]) {
                out.write(b);
            }
        }
        return out.size() == s.length ? value : Str.of(out.toByteArray());
    }

    /** php_filter_strip: FILTER_FLAG_STRIP_LOW (below 32), _HIGH (127 and up) and _BACKTICK. */
    private static byte[] strip(byte[] s, long flags) {
        if ((flags & (FLAG_STRIP_LOW | FLAG_STRIP_HIGH | FLAG_STRIP_BACKTICK)) == 0) {
            return s;
        }
        ByteBuilder out = new ByteBuilder(s.length);
        for (byte b : s) {
            int c = b & 0xFF;
            if (!(c >= 127 && (flags & FLAG_STRIP_HIGH) != 0 || c < 32 && (flags & FLAG_STRIP_LOW) != 0
                    || c == '`' && (flags & FLAG_STRIP_BACKTICK) != 0)) {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    /** php_filter_encode_html: the bytes in the set as decimal character references (&#39;). */
    private static TruffleString encodeHtml(byte[] s, boolean[] encode) {
        ByteBuilder out = new ByteBuilder(s.length + 16);
        for (byte b : s) {
            int c = b & 0xFF;
            if (encode[c]) {
                out.writeAscii("&#" + c + ";");
            } else {
                out.write(b);
            }
        }
        return Str.of(out.toByteArray());
    }

    /** The bytes FILTER_FLAG_ENCODE_AMP, _LOW and _HIGH add to an encoding set. */
    private static void encodeFlags(boolean[] encode, long flags) {
        if ((flags & FLAG_ENCODE_AMP) != 0) {
            encode['&'] = true;
        }
        if ((flags & FLAG_ENCODE_LOW) != 0) {
            Arrays.fill(encode, 0, 32, true);
        }
        if ((flags & FLAG_ENCODE_HIGH) != 0) {
            Arrays.fill(encode, 127, 256, true);
        }
    }

    /** FILTER_UNSAFE_RAW (php_filter_unsafe_raw): the value, stripped and encoded as the flags ask. */
    private static Object unsafeRaw(Call call, TruffleString value, long flags, Object options) {
        int length = Str.length(value);
        if (flags != 0 && length > 0) {
            byte[] s = strip(Str.readBytes(value), flags);
            boolean[] encode = new boolean[256];
            encodeFlags(encode, flags);
            return encodeHtml(s, encode);
        } else if ((flags & FLAG_EMPTY_STRING_NULL) != 0 && length == 0) {
            return PhpNull.NULL;
        }
        return value;
    }

    /** RFC 822's section 6. */
    private static final boolean[] EMAIL_CHARS = charSet(ALPHA_DIGITS + "!#$%&'*+-=?^_`{|}~@.[]");
    private static final boolean[] INT_CHARS = charSet("+-0123456789");
    private static final boolean[] URL_UNENCODED = charSet(ALPHA_DIGITS + "-._");
    private static final byte[] HEX_DIGITS = "0123456789ABCDEF".getBytes(StandardCharsets.ISO_8859_1);

    /** FILTER_SANITIZE_STRING (php_filter_string): quotes encoded, the tags stripped. */
    private static Object sanitizeString(Call call, TruffleString value, long flags, Object options) {
        byte[] s = strip(Str.readBytes(value), flags);
        boolean[] encode = new boolean[256];
        if ((flags & FLAG_NO_ENCODE_QUOTES) == 0) {
            encode['\''] = true;
            encode['"'] = true;
        }
        encodeFlags(encode, flags);
        // the tags stripped, as php_strip_tags_ex with tag spaces allowed does, and NUL bytes with them
        TruffleString stripped = StripTags.strip(encodeHtml(s, encode), PhpNull.NULL, true);
        if (Str.length(stripped) == 0) {
            return (flags & FLAG_EMPTY_STRING_NULL) != 0 ? PhpNull.NULL : Str.EMPTY;
        }
        return stripped;
    }

    /** FILTER_SANITIZE_ENCODED (php_filter_encoded): URL-encoded, all but letters, digits and "-._". */
    private static Object sanitizeEncoded(Call call, TruffleString value, long flags, Object options) {
        byte[] s = strip(Str.readBytes(value), flags);
        ByteBuilder out = new ByteBuilder(s.length * 3);
        for (byte b : s) {
            int c = b & 0xFF;
            if (URL_UNENCODED[c]) {
                out.write(b);
            } else {
                out.write('%');
                out.write(HEX_DIGITS[c >> 4]);
                out.write(HEX_DIGITS[c & 15]);
            }
        }
        return Str.of(out.toByteArray());
    }

    /** FILTER_SANITIZE_SPECIAL_CHARS (php_filter_special_chars): '"<>& and the bytes below 32 encoded. */
    private static Object sanitizeSpecialChars(Call call, TruffleString value, long flags, Object options) {
        byte[] s = strip(Str.readBytes(value), flags);
        boolean[] encode = new boolean[256];
        encode['\''] = true;
        encode['"'] = true;
        encode['<'] = true;
        encode['>'] = true;
        encode['&'] = true;
        // NUL and the other low bytes FILTER_FLAG_STRIP_LOW left
        Arrays.fill(encode, 0, 32, true);
        if ((flags & FLAG_ENCODE_HIGH) != 0) {
            Arrays.fill(encode, 127, 256, true);
        }
        return encodeHtml(s, encode);
    }

    /**
     * FILTER_SANITIZE_FULL_SPECIAL_CHARS (php_filter_full_special_chars): htmlentities() in default_charset, with
     * the quotes unless FILTER_FLAG_NO_ENCODE_QUOTES, existing entities left as they are.
     */
    private static Object sanitizeFullSpecialChars(Call call, TruffleString value, long flags, Object options) {
        long quotes = (flags & FLAG_NO_ENCODE_QUOTES) == 0 ? ENT_QUOTES : ENT_NOQUOTES;
        return Html.escape(value, true, quotes, null, false);
    }

    private static final long ENT_QUOTES = 3;
    private static final long ENT_NOQUOTES = 0;

    /** FILTER_SANITIZE_NUMBER_FLOAT: digits, signs, and '.', ',' and "eE" as the flags allow. */
    private static Object sanitizeNumberFloat(Call call, TruffleString value, long flags, Object options) {
        boolean[] set = INT_CHARS.clone();
        if ((flags & FLAG_ALLOW_FRACTION) != 0) {
            set['.'] = true;
        }
        if ((flags & FLAG_ALLOW_THOUSAND) != 0) {
            set[','] = true;
        }
        if ((flags & FLAG_ALLOW_SCIENTIFIC) != 0) {
            set['e'] = true;
            set['E'] = true;
        }
        return keepOnly(value, set);
    }

    // ---- callback_filter.c ----

    /** FILTER_CALLBACK: the callable in "options", called with the value; null when it throws. */
    private static Object callback(Call call, TruffleString value, long flags, Object options) {
        Callables.Target target = options == null ? null : Callables.resolve(call.context, options);
        if (target == null) {
            call.raise(new PhpError("TypeError", call.function + "(): Option must be a valid callback"));
            return PhpNull.NULL;
        }
        if (call.pending != null) {
            // zend_call_function calls nothing while an exception is out
            return PhpNull.NULL;
        }
        try {
            return target.call(value);
        } catch (PhpThrowable e) {
            call.pend(e);
            return PhpNull.NULL;
        }
    }

    // ---- C strings ----

    /** What %s prints of a zend_string: the bytes up to the first NUL. */
    private static TruffleString cBytes(TruffleString s) {
        byte[] bytes = Str.readBytes(s);
        int nul = indexOf(bytes, 0, bytes.length, 0);
        return nul < 0 ? s : Str.of(bytes, 0, nul);
    }

    private static String cString(TruffleString s) {
        return Str.java(cBytes(s));
    }

    private static TruffleString concat(String before, TruffleString middle, String after) {
        return Str.concat(Str.concat(Str.of(before), middle), Str.of(after));
    }
}
