package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.Str;

import java.util.Arrays;

/**
 * A stream context, as php-src's php_stream_context (main/streams/streams.c): options by wrapper, then by option
 * name, and the "stream-context" resource that names it. As php_stream_context_alloc, making one registers its
 * resource. The options array is held as a variable holds an array, so what stream_context_get_options() returns
 * shares it copy-on-write, and a change separates it first (SEPARATE_ARRAY): scripts never alias it.
 *
 * <p>php-src stores the names with zend_hash_str_update, as C strings: a name ends at its first NUL byte, and a
 * numeric name stays a string key there; here keys are normalized as every array's are, so "80" becomes 80.
 */
final class StreamContext {
    final PhpResource resource;
    private PhpArray options = new PhpArray();

    private StreamContext(PhpContext context) {
        options.addHolder();
        resource = new PhpResource(context.nextResourceId(), "stream-context", this);
    }

    /** php_stream_context_alloc. */
    static StreamContext alloc(PhpContext context) {
        return new StreamContext(context);
    }

    /** php_stream_context_get_default: FG(default_context), made on first use; null with nocontext. */
    static StreamContext defaultContext(PhpContext context, boolean nocontext) {
        if (nocontext) {
            return null;
        }
        BuiltinState state = context.builtinState();
        if (state.defaultStreamContext == null) {
            state.defaultStreamContext = alloc(context);
        }
        return state.defaultStreamContext;
    }

    /**
     * php_stream_context_from_zval for a function's context parameter (Z_PARAM_RESOURCE_OR_NULL): the context
     * given, or the default one (none with nocontext).
     */
    static StreamContext fromArgument(PhpContext context, Object[] a, int index, String function, String parameter,
                                      boolean nocontext) {
        Object value = PhpRef.deref(Args.get(a, index, PhpNull.NULL));
        if (value == PhpNull.NULL) {
            return defaultContext(context, nocontext);
        }
        if (!(value instanceof PhpResource resource)) {
            throw new PhpError("TypeError", function + "(): Argument #" + (index + 1) + " ($" + parameter
                    + ") must be of type resource or null, " + typeName(value) + " given");
        }
        if (resource.getHandle() instanceof StreamContext streamContext) {
            return streamContext;
        }
        throw new PhpError("TypeError", function + "(): supplied resource is not a valid Stream-Context resource");
    }

    /** The options, for stream_context_get_options(): shared copy-on-write with the caller. */
    PhpArray options() {
        return options;
    }

    /** php_stream_context_get_option: the option's value, or null if it isn't set. */
    Object option(String wrapper, String name) {
        if (!(PhpRef.deref(options.get(key(Str.bytes(Str.of(wrapper))))) instanceof PhpArray wrapperOptions)) {
            return null;
        }
        Object value = wrapperOptions.get(key(Str.bytes(Str.of(name))));
        return value == null ? null : PhpRef.deref(value);
    }

    /** php_stream_context_set_option: the value (dereferenced) under the wrapper's options, made if missing. */
    @TruffleBoundary
    void setOption(byte[] wrapper, byte[] name, Object value) {
        if (options.isShared()) {
            PhpArray copy = options.copy();
            copy.addHolder();
            options.removeHolder();
            options = copy;
        }
        PhpArray wrapperOptions = options.arrayForWrite(key(wrapper));
        wrapperOptions.put(key(name), PhpRef.deref(value));
    }

    /**
     * parse_context_options: each [wrapper => [option => value]]; a wrapper's options that form a list are
     * skipped, as are int option names. Throws php-src's ValueError for anything else.
     */
    @TruffleBoundary
    void setOptions(PhpArray given) {
        for (int p = given.firstPosition(); p >= 0; p = given.nextPosition(p)) {
            Object wrapper = given.keyAt(p);
            Object value = PhpRef.deref(given.rawAt(p));
            if (wrapper instanceof Long || !(value instanceof PhpArray wrapperOptions)) {
                throw new PhpError("ValueError", "Options should have the form [\"wrappername\"][\"optionname\"] = "
                        + "$value");
            }
            if (wrapperOptions.isPacked()) {
                continue;
            }
            for (int q = wrapperOptions.firstPosition(); q >= 0; q = wrapperOptions.nextPosition(q)) {
                if (!(wrapperOptions.keyAt(q) instanceof Long)) {
                    setOption(Str.bytes(Convert.toStr(wrapper)), Str.bytes(Convert.toStr(wrapperOptions.keyAt(q))),
                            wrapperOptions.rawAt(q));
                }
            }
        }
    }

    // ---- the functions (ext/standard/streamsfuncs.c) ----

    static void register(Registry r) {
        r.fn("stream_context_create", (context, a) -> {
            StreamContext created = alloc(context);
            Object options = PhpRef.deref(Args.get(a, 0, PhpNull.NULL));
            if (options != PhpNull.NULL) {
                created.setOptions(Args.array(a, 0, "stream_context_create"));
            }
            Object params = PhpRef.deref(Args.get(a, 1, PhpNull.NULL));
            if (params != PhpNull.NULL) {
                created.setParams(Args.array(a, 1, "stream_context_create"));
            }
            return created.resource;
        });
        r.fn("stream_context_get_options", (context, a) ->
                decode(context, a, "stream_context_get_options").options());
        r.fn("stream_context_set_option", (context, a) -> setOption(context, a));
        r.fn("stream_context_set_options", (context, a) -> {
            StreamContext target = decode(context, a, "stream_context_set_options");
            target.setOptions(Args.array(a, 1, "stream_context_set_options"));
            return Boolean.TRUE;
        });
        r.fn("stream_context_get_default", (context, a) -> {
            StreamContext target = defaultContext(context, false);
            if (PhpRef.deref(Args.get(a, 0, PhpNull.NULL)) != PhpNull.NULL) {
                target.setOptions(Args.array(a, 0, "stream_context_get_default"));
            }
            return target.resource;
        });
        r.fn("stream_context_set_default", (context, a) -> {
            PhpArray options = Args.array(a, 0, "stream_context_set_default");
            StreamContext target = defaultContext(context, false);
            target.setOptions(options);
            return target.resource;
        });
    }

    /**
     * parse_context_params: "options" as parse_context_options. A "notification" callback isn't taken: no
     * stream here reports progress (php_stream_notify_*), so it would never be called.
     */
    private void setParams(PhpArray params) {
        Object options = params.get(ArrayKey.of(Str.of("options")));
        if (options != null) {
            if (!(PhpRef.deref(options) instanceof PhpArray array)) {
                throw new PhpError("TypeError", "Invalid stream/context parameter");
            }
            setOptions(array);
        }
    }

    /**
     * decode_context_param: a context resource's context, or a stream's (one is made for a stream that has
     * none); php-src's TypeErrors otherwise.
     */
    static StreamContext decode(PhpContext context, Object[] a, String function) {
        Object value = PhpRef.deref(Args.get(a, 0, PhpNull.NULL));
        if (!(value instanceof PhpResource resource)) {
            throw new PhpError("TypeError", function + "(): Argument #1 ($stream_or_context) must be of type "
                    + "resource, " + typeName(value) + " given");
        }
        if (resource.getHandle() instanceof StreamContext streamContext) {
            return streamContext;
        }
        if (resource.getHandle() instanceof FileFunctions.Stream stream) {
            if (stream.context == null) {
                stream.context = alloc(context);
            }
            return stream.context;
        }
        throw new PhpError("TypeError", function + "(): Argument #1 ($stream_or_context) must be a valid "
                + "stream/context");
    }

    /** stream_context_set_option($context, $wrapper_or_options, $option_name = null, $value = <none>). */
    @TruffleBoundary
    private static Object setOption(PhpContext context, Object[] a) {
        if (Args.count(a) == 2) {
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of("Calling stream_context_set_option() "
                    + "with 2 arguments is deprecated, use stream_context_set_options() instead"),
                    pl.codeloom.php.runtime.SourceLocation.current());
        }
        StreamContext target = decode(context, a, "stream_context_set_option");
        Object wrapperOrOptions = PhpRef.deref(Args.get(a, 1));
        Object optionName = PhpRef.deref(Args.get(a, 2, PhpNull.NULL));
        boolean hasValue = Args.has(a, 3);
        if (wrapperOrOptions instanceof PhpArray options) {
            if (optionName != PhpNull.NULL) {
                throw new PhpError("ValueError", "stream_context_set_option(): Argument #3 ($option_name) must be "
                        + "null when argument #2 ($wrapper_or_options) is an array");
            }
            if (hasValue) {
                throw new PhpError("ValueError", "stream_context_set_option(): Argument #4 ($value) cannot be "
                        + "provided when argument #2 ($wrapper_or_options) is an array");
            }
            target.setOptions(options);
            return Boolean.TRUE;
        }
        byte[] wrapper = Str.bytes(Convert.toStr(wrapperOrOptions));
        if (optionName == PhpNull.NULL) {
            throw new PhpError("ValueError", "stream_context_set_option(): Argument #3 ($option_name) cannot be null "
                    + "when argument #2 ($wrapper_or_options) is a string");
        }
        if (!hasValue) {
            throw new PhpError("ValueError", "stream_context_set_option(): Argument #4 ($value) must be provided "
                    + "when argument #2 ($wrapper_or_options) is a string");
        }
        target.setOption(wrapper, Str.bytes(Convert.toStr(optionName)), Args.get(a, 3));
        return Boolean.TRUE;
    }

    /** A C string's key: the name up to its first NUL byte. */
    private static Object key(byte[] name) {
        for (int i = 0; i < name.length; i++) {
            if (name[i] == 0) {
                return ArrayKey.of(Str.of(Arrays.copyOf(name, i)));
            }
        }
        return ArrayKey.of(Str.of(name));
    }

    static String typeName(Object value) {
        if (value instanceof PhpObject object) {
            return object.getPhpClass().getName();
        }
        return switch (Convert.typeName(value)) {
            case "integer" -> "int";
            case "double" -> "float";
            case "boolean" -> "bool";
            case "NULL" -> "null";
            default -> Convert.typeName(value);
        };
    }
}
