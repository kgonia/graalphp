package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import static pl.codeloom.php.builtins.CurlConstants.value;
import static pl.codeloom.php.builtins.CurlNative.CURLE_OK;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * ext/curl (interface.c, multi.c, share.c, curl_file.c) over libcurl ({@link CurlNative}): CurlHandle,
 * CurlMultiHandle, CurlShareHandle and CurlSharePersistentHandle hold an {@link Easy}, {@link Multi} or
 * {@link Share} as php_curl, php_curlm and php_curlsh, and libcurl's callbacks reach the PHP callables through
 * upcalls ({@link Upcalls}).
 *
 * <p>php-src frees a handle when its refcount drops to zero; here a {@link Cleaner} notices the object is
 * unreachable and the context frees the native handle at its next curl call, or when it ends (php's request end).
 * A callback that throws leaves the exception pending, as EG(exception): libcurl carries on (the callbacks after
 * it don't run, as zend_call_function doesn't with an exception set) and the curl function throws it when libcurl
 * returns.
 */
final class CurlFunctions {
    private CurlFunctions() {
    }

    // php_curl's write and read methods (curl_private.h)
    static final int STDOUT = 0;
    static final int FILE = 1;
    static final int USER = 2;
    static final int DIRECT = 3;
    static final int RETURN = 4;
    static final int IGNORE = 7;

    /** CURLOPT_RETURNTRANSFER, CURLOPT_BINARYTRANSFER and CURLOPT_SAFE_UPLOAD: PHP's own options. */
    private static final long OPT_RETURNTRANSFER = 19913;
    private static final long OPT_BINARYTRANSFER = 19914;
    private static final long OPT_SAFE_UPLOAD = -1;
    /** CURLINFO_HEADER_OUT: an option (curl_setopt) and an info (curl_getinfo) of PHP's. */
    private static final long INFO_HEADER_OUT = 2;

    private static final Cleaner CLEANER = Cleaner.create();
    private static final AtomicLong IDS = new AtomicLong();
    /** The live handles by the id libcurl passes back to the callbacks as their userdata. */
    private static final Map<Long, WeakReference<Easy>> EASIES = new ConcurrentHashMap<>();
    private static final Map<Long, WeakReference<Multi>> MULTIS = new ConcurrentHashMap<>();
    private static final Map<Long, MimeSource> MIME_SOURCES = new ConcurrentHashMap<>();

    // ---- per-context state ----

    /** A context's curl state: EG(exception) from a callback, and the native handles to free. */
    static final class State {
        /** An exception a callback threw, thrown when libcurl returns to the curl function. */
        Throwable pending;
        /** The curl function running, as get_active_function_name() names it to warnings from callbacks. */
        String activeFunction = "curl_exec";
        /** Set while the context ends: callbacks no longer call PHP code. */
        boolean closing;
        /** The native handles not freed yet, in creation order. */
        private final Set<NativeHandle> live = new LinkedHashSet<>();
        /** Natives whose PHP object became unreachable (queued by the cleaner thread, freed on this one). */
        private final ConcurrentLinkedQueue<NativeHandle> released = new ConcurrentLinkedQueue<>();
        /** Share handles libcurl refused to clean up while easy handles used them, retried as those go. */
        private final List<ShareNative> deferredShares = new ArrayList<>();

        /** Frees the natives of the handles PHP code no longer reaches (php's free_obj, deferred). */
        void drain() {
            NativeHandle released;
            while ((released = this.released.poll()) != null) {
                free(released);
            }
        }

        private void free(NativeHandle handle) {
            if (live.remove(handle)) {
                handle.free(this);
                if (handle instanceof EasyNative && !deferredShares.isEmpty()) {
                    deferredShares.removeIf(share -> share.tryCleanup());
                }
            }
        }

        /** The request's end: every handle freed, the easy handles first, then the multi and share handles. */
        void close() {
            closing = true;
            released.clear();
            for (int order = 0; order < 3; order++) {
                for (NativeHandle handle : new ArrayList<>(live)) {
                    if (handle.order() == order) {
                        free(handle);
                    }
                }
            }
            deferredShares.removeIf(share -> share.tryCleanup());
            deferredShares.clear();
            pending = null;
        }

        /** Registers a handle's native part, freed when the PHP object is unreachable or the context ends. */
        void track(Object owner, NativeHandle handle) {
            live.add(handle);
            CLEANER.register(owner, () -> released.add(handle));
        }
    }

    /** What a PHP object owns in native memory; free() runs on the context's thread. */
    private abstract static class NativeHandle {
        /** The order the context frees handles in at its end: easy 0, multi 1, share 2. */
        abstract int order();

        abstract void free(State state);
    }

    // ---- php_curl ----

    /** php_curl_write: a write or header handler. */
    static final class Write {
        int method;
        Callables.Target fcc;
        /** The stream of PHP_CURL_FILE (php_curl_write.stream, whose fp it writes to). */
        PhpResource stream;
        /** smart_str buf: null until something is appended (PHP_CURL_RETURN). */
        ByteBuilder buf;

        void copyFrom(Write source) {
            method = source.method;
            stream = source.stream;
            fcc = source.fcc;
        }
    }

    /** php_curl_read. */
    static final class Read {
        int method;
        Callables.Target fcc;
        /** CURLOPT_INFILE's stream: fp and res in php_curl_read. */
        PhpResource stream;
    }

    /** What clones of a handle share and the last of them frees (php_curl's to_free and clone count). */
    static final class ToFree {
        /** The mime structures of CURLOPT_POSTFIELDS arrays (to_free->post). */
        final List<MemorySegment> post = new ArrayList<>();
        /** CURLFile parts' read state (to_free->stream). */
        final List<MimeSource> stream = new ArrayList<>();
        /** The slists: by option for an unshared handle, then appended for clones (to_free->slist). */
        final Map<Object, MemorySegment> slist = new LinkedHashMap<>();
        int clones = 1;
    }

    /** The native part of a CurlHandle: what curl_free_obj releases. */
    private static final class EasyNative extends NativeHandle {
        final long id;
        MemorySegment cp;
        final Arena arena;
        ToFree toFree;
        /** CURLOPT_STDERR's FILE* over a PHP stream (glibc's fopencookie), or null. */
        StderrFile stderr;

        EasyNative(long id, MemorySegment cp, Arena arena) {
            this.id = id;
            this.cp = cp;
            this.arena = arena;
        }

        @Override
        int order() {
            return 0;
        }

        @Override
        void free(State state) {
            EASIES.remove(id);
            if (cp != null) {
                CurlNative.easyCleanup(cp);
                cp = null;
            }
            // cURL destructors should be invoked only by the last curl handle
            if (--toFree.clones == 0) {
                for (MemorySegment mime : toFree.post) {
                    CurlNative.mimeFree(mime);
                }
                toFree.post.clear();
                for (MimeSource source : toFree.stream) {
                    MIME_SOURCES.remove(source.id);
                    source.close();
                }
                toFree.stream.clear();
                for (MemorySegment list : toFree.slist.values()) {
                    CurlNative.slistFreeAll(list);
                }
                toFree.slist.clear();
            }
            if (stderr != null) {
                stderr.close();
                stderr = null;
            }
            arena.close();
        }
    }

    /** php_curl: a CurlHandle's state. */
    static final class Easy {
        final long id;
        final State state;
        final PhpObject object;
        final EasyNative nat;
        MemorySegment cp;
        /** err.str: CURL_ERROR_SIZE + 1 bytes libcurl writes its message to (CURLOPT_ERRORBUFFER). */
        final MemorySegment errorBuffer;
        int errNo;
        final Write write = new Write();
        final Write writeHeader = new Write();
        final Read read = new Read();
        PhpResource stdErr;
        Callables.Target progress;
        Callables.Target xferinfo;
        Callables.Target fnmatch;
        Callables.Target debug;
        Callables.Target prereq;
        Callables.Target sshHostkey;
        /** header.str: the request headers CURLINFO_HEADER_OUT saw, or null. */
        byte[] headerOut;
        boolean inCallback;
        /** The CURLOPT_POSTFIELDS array with files, kept to rebuild the mime structure in a copy; or null. */
        PhpArray postfields;
        /** CURLOPT_PRIVATE's value, or null (undefined). */
        Object privateData;
        /** The CurlShareHandle of CURLOPT_SHARE, kept alive while this handle uses it. */
        Share share;

        Easy(State state, PhpObject object, MemorySegment cp) {
            this.id = IDS.incrementAndGet();
            this.state = state;
            this.object = object;
            this.cp = cp;
            Arena arena = Arena.ofShared();
            this.errorBuffer = arena.allocate(CurlNative.ERROR_SIZE + 1);
            this.nat = new EasyNative(id, cp, arena);
            this.nat.toFree = new ToFree();
        }

        MemorySegment userdata() {
            return MemorySegment.ofAddress(id);
        }

        /** The handle's libcurl pointer; a handle a server push left to libcurl has none. */
        void setCp(MemorySegment cp) {
            this.cp = cp;
            nat.cp = cp;
        }
    }

    /** php_curlm: a CurlMultiHandle's state. */
    static final class Multi extends NativeHandle {
        final long id = IDS.incrementAndGet();
        MemorySegment multi;
        /** The CurlHandle objects added (easyh). */
        final List<PhpObject> easyh = new ArrayList<>();
        Callables.Target serverPush;
        int errNo;
        State state;

        @Override
        int order() {
            return 1;
        }

        @Override
        void free(State state) {
            MULTIS.remove(id);
            if (multi != null) {
                CurlNative.multiCleanup(multi);
                multi = null;
            }
        }
    }

    /** The native part of a CurlShareHandle. */
    private static final class ShareNative extends NativeHandle {
        MemorySegment share;

        ShareNative(MemorySegment share) {
            this.share = share;
        }

        @Override
        int order() {
            return 2;
        }

        @Override
        void free(State state) {
            if (!tryCleanup()) {
                state.deferredShares.add(this);
            }
        }

        /** curl_share_cleanup; false while easy handles still use the share (CURLSHE_IN_USE), to retry. */
        boolean tryCleanup() {
            if (share == null) {
                return true;
            }
            if (CurlNative.shareCleanup(share) != CurlNative.CURLSHE_OK) {
                return false;
            }
            share = null;
            return true;
        }
    }

    /** php_curlsh. */
    static final class Share {
        final MemorySegment share;
        int errNo;

        Share(MemorySegment share) {
            this.share = share;
        }
    }

    /** CURLFile's mime_data_cb_arg: the file a part reads, opened on demand. */
    static final class MimeSource {
        final long id = IDS.incrementAndGet();
        final byte[] filename;
        FileFunctions.Stream stream;

        MimeSource(byte[] filename, FileFunctions.Stream stream) {
            this.filename = filename;
            this.stream = stream;
        }

        void close() {
            if (stream != null) {
                FileFunctions.closeStream(stream);
                stream = null;
            }
        }
    }

    // ---- registration ----

    static void register(Registry r) {
        r.fn("curl_close", CurlFunctions::curlClose);
        r.fn("curl_copy_handle", CurlFunctions::curlCopyHandle);
        r.fn("curl_errno", CurlFunctions::curlErrno);
        r.fn("curl_error", CurlFunctions::curlError);
        r.fn("curl_escape", (context, a) -> curlEscape(context, a, true));
        r.fn("curl_unescape", (context, a) -> curlEscape(context, a, false));
        r.fn("curl_multi_setopt", CurlFunctions::curlMultiSetopt);
        r.fn("curl_exec", CurlFunctions::curlExec);
        r.fn("curl_file_create", CurlFunctions::curlFileCreate);
        r.fn("curl_getinfo", CurlFunctions::curlGetinfo);
        r.fn("curl_init", CurlFunctions::curlInit);
        r.fn("curl_upkeep", CurlFunctions::curlUpkeep);
        r.fn("curl_multi_add_handle", CurlFunctions::curlMultiAddHandle);
        r.fn("curl_multi_get_handles", CurlFunctions::curlMultiGetHandles);
        r.fn("curl_multi_close", CurlFunctions::curlMultiClose);
        r.fn("curl_multi_errno", CurlFunctions::curlMultiErrno);
        r.fn("curl_multi_exec", "01", CurlFunctions::curlMultiExec);
        r.fn("curl_multi_getcontent", CurlFunctions::curlMultiGetcontent);
        r.fn("curl_multi_info_read", "01", CurlFunctions::curlMultiInfoRead);
        r.fn("curl_multi_init", CurlFunctions::curlMultiInit);
        r.fn("curl_multi_remove_handle", CurlFunctions::curlMultiRemoveHandle);
        r.fn("curl_multi_select", CurlFunctions::curlMultiSelect);
        r.fn("curl_multi_strerror", (context, a) -> strerror(context, a, "curl_multi_strerror", 1));
        r.fn("curl_pause", CurlFunctions::curlPause);
        r.fn("curl_reset", CurlFunctions::curlReset);
        r.fn("curl_setopt_array", CurlFunctions::curlSetoptArray);
        r.fn("curl_setopt", CurlFunctions::curlSetopt);
        r.fn("curl_share_close", CurlFunctions::curlShareClose);
        r.fn("curl_share_errno", CurlFunctions::curlShareErrno);
        r.fn("curl_share_init", CurlFunctions::curlShareInit);
        r.fn("curl_share_setopt", CurlFunctions::curlShareSetopt);
        r.fn("curl_share_strerror", (context, a) -> strerror(context, a, "curl_share_strerror", 2));
        r.fn("curl_share_init_persistent", CurlFunctions::curlShareInitPersistent);
        r.fn("curl_strerror", (context, a) -> strerror(context, a, "curl_strerror", 0));
        r.fn("curl_version", CurlFunctions::curlVersion);
    }

    /** The methods of CURLFile and CURLStringFile (curl_file.c), built once per language. */
    record Methods(List<PhpFunction> curlFile, List<PhpFunction> curlStringFile) {
    }

    static Methods methods(Registry r) {
        List<PhpFunction> curlFile = List.of(
                r.method("__construct", false, a -> {
                    PhpObject self = (PhpObject) a[PhpFunction.ARG_THIS];
                    curlFileConstruct(PhpContext.get(null), self, a, "CURLFile::__construct");
                    return PhpNull.NULL;
                }),
                r.method("getFilename", false, a -> curlFileProperty(a, "getFilename", "name")),
                r.method("getMimeType", false, a -> curlFileProperty(a, "getMimeType", "mime")),
                r.method("getPostFilename", false, a -> curlFileProperty(a, "getPostFilename", "postname")),
                r.method("setMimeType", false, a -> curlFileSetProperty(a, "setMimeType", "mime", "mime_type")),
                r.method("setPostFilename", false,
                        a -> curlFileSetProperty(a, "setPostFilename", "postname", "posted_filename")));
        List<PhpFunction> curlStringFile = List.of(
                r.method("__construct", false, a -> curlStringFileConstruct(PhpContext.get(null), a)));
        return new Methods(curlFile, curlStringFile);
    }

    /** MINIT's classes: the handles (final, not constructible with new, not serializable) and the file classes. */
    static void declareClasses(PhpContext context, Methods methods) {
        handleClass(context, "CurlHandle", "Cannot directly construct CurlHandle, use curl_init() instead",
                CurlFunctions::cloneHandle);
        handleClass(context, "CurlMultiHandle",
                "Cannot directly construct CurlMultiHandle, use curl_multi_init() instead", null);
        handleClass(context, "CurlShareHandle",
                "Cannot directly construct CurlShareHandle, use curl_share_init() instead", null);
        PhpClass persistent = handleClass(context, "CurlSharePersistentHandle",
                "Cannot directly construct CurlSharePersistentHandle, use curl_share_init_persistent() instead",
                null);
        persistent.addProperty(new PhpClass.PropertyDecl("options", PhpNull.NULL, PhpClass.PropertyDecl.PUBLIC));
        PhpClass curlFile = new PhpClass("CURLFile", null, false, false, context.getLanguage().rootShape(),
                "CURLFile");
        curlFile.addProperty(new PhpClass.PropertyDecl("name", Str.EMPTY, PhpClass.PropertyDecl.PUBLIC));
        curlFile.addProperty(new PhpClass.PropertyDecl("mime", Str.EMPTY, PhpClass.PropertyDecl.PUBLIC));
        curlFile.addProperty(new PhpClass.PropertyDecl("postname", Str.EMPTY, PhpClass.PropertyDecl.PUBLIC));
        for (PhpFunction method : methods.curlFile()) {
            curlFile.addMethod(method);
        }
        curlFile.setHandlers(new PhpClass.ObjectHandlers(null, null, false, false, true, false));
        context.declareClass(curlFile);
        PhpClass curlStringFile = new PhpClass("CURLStringFile", null, false, false,
                context.getLanguage().rootShape(), "CURLStringFile");
        curlStringFile.addProperty(new PhpClass.PropertyDecl("data", PhpNull.NULL, PhpClass.PropertyDecl.PUBLIC));
        curlStringFile.addProperty(new PhpClass.PropertyDecl("postname", PhpNull.NULL,
                PhpClass.PropertyDecl.PUBLIC));
        curlStringFile.addProperty(new PhpClass.PropertyDecl("mime", PhpNull.NULL, PhpClass.PropertyDecl.PUBLIC));
        for (PhpFunction method : methods.curlStringFile()) {
            curlStringFile.addMethod(method);
        }
        context.declareClass(curlStringFile);
    }

    private static PhpClass handleClass(PhpContext context, String name, String constructError,
                                        UnaryOperator<PhpObject> clone) {
        PhpClass phpClass = new PhpClass(name, null, false, false, context.getLanguage().rootShape(), name);
        // curl_cast_object: CurlHandle and CurlMultiHandle cast to int as their resources did; the share handles don't
        boolean castsToId = name.equals("CurlHandle") || name.equals("CurlMultiHandle");
        phpClass.setHandlers(new PhpClass.ObjectHandlers(constructError, clone, clone == null, castsToId, true, true));
        context.declareClass(phpClass);
        return phpClass;
    }

    /** The constants, for Builtins' table. */
    static void constants(Map<String, Object> into) {
        into.putAll(CurlConstants.VALUES);
    }

    // ---- parameter parsing (zend_parse_parameters' rules for the types ext/curl takes) ----

    private static void arity(Object[] a, String function, int min, int max) {
        int count = Args.count(a);
        if (count >= min && count <= max) {
            return;
        }
        String bound = min == max ? "exactly" : count < min ? "at least" : "at most";
        int expected = count < min ? min : max;
        throw new PhpError("ArgumentCountError", function + "() expects " + bound + " " + expected + " argument"
                + (expected == 1 ? "" : "s") + ", " + count + " given");
    }

    /** zend_zval_value_name: what a type error says was given. */
    static String valueName(Object value) {
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof PhpResource) {
            return "resource";
        }
        return Convert.debugType(value);
    }

    private static PhpError typeError(String function, int position, String name, String type, Object value) {
        return new PhpError("TypeError", function + "(): Argument #" + position + " ($" + name + ") must be of type "
                + type + ", " + valueName(value) + " given");
    }

    private static PhpObject handleOf(Object[] a, int index, String function, String name, String className) {
        Object value = Args.get(a, index, PhpNull.NULL);
        if (value instanceof PhpObject object && object.getPhpClass().getName().equals(className)) {
            return object;
        }
        throw typeError(function, index + 1, name, className, value);
    }

    private static Easy easy(Object[] a, int index, String function) {
        return (Easy) handleOf(a, index, function, "handle", "CurlHandle").getInternal();
    }

    private static Multi multi(Object[] a, String function) {
        return (Multi) handleOf(a, 0, function, "multi_handle", "CurlMultiHandle").getInternal();
    }

    private static Share share(Object[] a, String function) {
        return (Share) handleOf(a, 0, function, "share_handle", "CurlShareHandle").getInternal();
    }

    /** Z_PARAM_LONG(_OR_NULL) in coercive mode (zend_parse_arg_long_weak); null for a null the type allows. */
    private static Long longArg(PhpContext context, Object[] a, int index, String function, String name,
                                boolean nullable) {
        Object value = Args.get(a, index, PhpNull.NULL);
        String type = nullable ? "?int" : "int";
        if (value instanceof Long l) {
            return l;
        }
        if (value == PhpNull.NULL) {
            if (nullable) {
                return null;
            }
            deprecateNull(context, function, index, name, "int");
            return 0L;
        }
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (value instanceof Double d) {
            return doubleToLong(context, d, null, function, index, name, type);
        }
        if (value instanceof TruffleString s) {
            Object number = Convert.numericValue(s);
            if (number instanceof Long l) {
                return l;
            }
            if (number instanceof Double d) {
                return doubleToLong(context, d, s, function, index, name, type);
            }
        }
        throw typeError(function, index + 1, name, type, value);
    }

    private static long doubleToLong(PhpContext context, double d, TruffleString string, String function, int index,
                                     String name, String type) {
        if (!(d >= -9.223372036854775808E18 && d < 9.223372036854775807E18)) {
            throw typeError(function, index + 1, name, type, string != null ? string : d);
        }
        long l = (long) d;
        if (l != d) {
            String message = string != null
                    ? "Implicit conversion from float-string \"" + Str.java(string) + "\" to int loses precision"
                    : "Implicit conversion from float " + Convert.doubleToRepr(d) + " to int loses precision";
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of(message), SourceLocation.current());
        }
        return l;
    }

    /** Z_PARAM_DOUBLE in coercive mode. */
    private static double doubleArg(PhpContext context, Object[] a, int index, String function, String name) {
        Object value = Args.get(a, index, PhpNull.NULL);
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value == PhpNull.NULL) {
            deprecateNull(context, function, index, name, "float");
            return 0;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof TruffleString s && Convert.numericValue(s) instanceof Number n) {
            return n.doubleValue();
        }
        throw typeError(function, index + 1, name, "float", value);
    }

    /** Z_PARAM_STR(_OR_NULL) in coercive mode; null for a null the type allows. */
    private static TruffleString stringArg(PhpContext context, Object[] a, int index, String function, String name,
                                           boolean nullable) {
        Object value = Args.get(a, index, PhpNull.NULL);
        if (value instanceof TruffleString s) {
            return s;
        }
        if (value == PhpNull.NULL) {
            if (nullable) {
                return null;
            }
            deprecateNull(context, function, index, name, "string");
            return Str.EMPTY;
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return Convert.toStr(value);
        }
        if (value instanceof PhpObject object && object.getPhpClass().findMethod("__tostring") != null) {
            return object.toPhpString();
        }
        throw typeError(function, index + 1, name, nullable ? "?string" : "string", value);
    }

    /** Z_PARAM_PATH_STR: a string without NUL bytes. */
    private static TruffleString pathArg(PhpContext context, Object[] a, int index, String function, String name) {
        TruffleString path = stringArg(context, a, index, function, name, false);
        if (hasNul(Str.bytes(path))) {
            throw new PhpError("ValueError", function + "(): Argument #" + (index + 1) + " ($" + name
                    + ") must not contain any null bytes");
        }
        return path;
    }

    private static PhpArray arrayArg(Object[] a, int index, String function, String name) {
        Object value = Args.get(a, index, PhpNull.NULL);
        if (value instanceof PhpArray array) {
            return array;
        }
        throw typeError(function, index + 1, name, "array", value);
    }

    private static void deprecateNull(PhpContext context, String function, int index, String name, String type) {
        ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of(function + "(): Passing null to parameter #"
                + (index + 1) + " ($" + name + ") of type " + type + " is deprecated"), SourceLocation.current());
    }

    private static boolean hasNul(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static void warn(PhpContext context, String function, String message) {
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(function + "(): " + message),
                SourceLocation.current());
    }

    /** #[\Deprecated(since: '8.5', message: "as it has no effect since PHP 8.0")]: raised as the call starts. */
    private static void deprecatedFunction(PhpContext context, String function) {
        ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of("Function " + function
                + "() is deprecated since 8.5, as it has no effect since PHP 8.0"), SourceLocation.current());
    }

    private static State state(PhpContext context) {
        State state = context.builtinState().curl;
        state.drain();
        return state;
    }

    /** Throws the exception a callback raised while libcurl ran (EG(exception) seen as the function returns). */
    private static void rethrowPending(State state) {
        Throwable pending = state.pending;
        if (pending != null) {
            state.pending = null;
            if (pending instanceof RuntimeException e) {
                throw e;
            }
            if (pending instanceof Error e) {
                throw e;
            }
            throw new IllegalStateException(pending);
        }
    }

    // ---- handles ----

    /** init_curl_handle_into_zval: a CurlHandle object around a libcurl handle. */
    private static Easy newEasy(PhpContext context, State state, MemorySegment cp) {
        PhpObject object = context.getClass("CurlHandle").instantiateInternal();
        Easy ch = new Easy(state, object, cp);
        object.setInternal(ch);
        EASIES.put(ch.id, new WeakReference<>(ch));
        state.track(ch, ch.nat);
        return ch;
    }

    /** _php_curl_set_default_options. */
    private static void setDefaultOptions(PhpContext context, Easy ch) {
        MemorySegment cp = ch.cp;
        CurlNative.setoptLong(cp, CurlNative.OPT_NOPROGRESS, 1);
        CurlNative.setoptLong(cp, CurlNative.OPT_VERBOSE, 0);
        CurlNative.setoptPointer(cp, CurlNative.OPT_ERRORBUFFER, ch.errorBuffer);
        CurlNative.setoptPointer(cp, CurlNative.OPT_WRITEFUNCTION, CurlNative.Callbacks.WRITE);
        CurlNative.setoptPointer(cp, CurlNative.OPT_WRITEDATA, ch.userdata());
        CurlNative.setoptPointer(cp, CurlNative.OPT_READFUNCTION, CurlNative.Callbacks.READ);
        CurlNative.setoptPointer(cp, CurlNative.OPT_READDATA, ch.userdata());
        CurlNative.setoptPointer(cp, CurlNative.OPT_HEADERFUNCTION, CurlNative.Callbacks.WRITE_HEADER);
        CurlNative.setoptPointer(cp, CurlNative.OPT_HEADERDATA, ch.userdata());
        CurlNative.setoptLong(cp, CurlNative.OPT_DNS_CACHE_TIMEOUT, 120);
        CurlNative.setoptLong(cp, CurlNative.OPT_MAXREDIRS, 20); // prevent infinite redirects
        String cainfo = context.ini().get("openssl.cafile");
        if (cainfo == null || cainfo.isEmpty()) {
            cainfo = context.ini().get("curl.cainfo");
        }
        if (cainfo != null && !cainfo.isEmpty()) {
            CurlNative.setoptString(cp, CurlNative.OPT_CAINFO, cainfo.getBytes(StandardCharsets.UTF_8));
        }
        // php-src sets this in ZTS builds: contexts run on many threads, and the JVM keeps its signal handlers
        CurlNative.setoptLong(cp, CurlNative.OPT_NOSIGNAL, 1);
    }

    @TruffleBoundary
    private static Object curlInit(PhpContext context, Object[] a) {
        arity(a, "curl_init", 0, 1);
        TruffleString url = Args.has(a, 0) ? stringArg(context, a, 0, "curl_init", "url", true) : null;
        State state = state(context);
        MemorySegment cp = CurlNative.easyInit();
        if (cp.equals(MemorySegment.NULL)) {
            warn(context, "curl_init", "Could not initialize a new cURL handle");
            return Boolean.FALSE;
        }
        Easy ch = newEasy(context, state, cp);
        ch.write.method = STDOUT;
        ch.read.method = DIRECT;
        ch.writeHeader.method = IGNORE;
        setDefaultOptions(context, ch);
        if (url != null && !optionUrl(context, ch, Str.bytes(url), "curl_init")) {
            state.free(ch.nat);
            return Boolean.FALSE;
        }
        return ch.object;
    }

    /** curl_clone_obj: `clone $handle` and curl_copy_handle. */
    @TruffleBoundary
    private static PhpObject cloneHandle(PhpObject object) {
        PhpContext context = PhpContext.get(null);
        State state = state(context);
        Easy source = (Easy) object.getInternal();
        MemorySegment cp = CurlNative.easyDuphandle(source.cp);
        if (cp.equals(MemorySegment.NULL)) {
            throw new PhpError("Exception", "Failed to clone CurlHandle");
        }
        Easy clone = newEasy(context, state, cp);
        setupEasyCopyHandlers(clone, source);
        if (source.postfields != null && !buildMimeStructure(context, clone, source.postfields, "curl_copy_handle")) {
            throw new PhpError("Exception", "Failed to clone CurlHandle");
        }
        return clone.object;
    }

    /** _php_setup_easy_copy_handlers: the clone shares the source's handlers, streams and freed resources. */
    private static void setupEasyCopyHandlers(Easy ch, Easy source) {
        ch.write.copyFrom(source.write);
        ch.read.method = source.read.method;
        ch.read.stream = source.read.stream;
        ch.read.fcc = source.read.fcc;
        ch.writeHeader.copyFrom(source.writeHeader);
        MemorySegment cp = ch.cp;
        CurlNative.setoptPointer(cp, CurlNative.OPT_ERRORBUFFER, ch.errorBuffer);
        CurlNative.setoptPointer(cp, CurlNative.OPT_WRITEDATA, ch.userdata());
        CurlNative.setoptPointer(cp, CurlNative.OPT_READDATA, ch.userdata());
        CurlNative.setoptPointer(cp, CurlNative.OPT_HEADERDATA, ch.userdata());
        CurlNative.setoptPointer(cp, CurlNative.OPT_DEBUGDATA, ch.userdata());
        if (source.progress != null) {
            ch.progress = source.progress;
            CurlNative.setoptPointer(cp, CurlNative.OPT_PROGRESSDATA, ch.userdata());
        }
        if (source.xferinfo != null) {
            ch.xferinfo = source.xferinfo;
            CurlNative.setoptPointer(cp, CurlNative.OPT_XFERINFODATA, ch.userdata());
        }
        if (source.fnmatch != null) {
            ch.fnmatch = source.fnmatch;
            CurlNative.setoptPointer(cp, CurlNative.OPT_FNMATCH_DATA, ch.userdata());
        }
        if (source.debug != null) {
            ch.debug = source.debug;
            CurlNative.setoptPointer(cp, CurlNative.OPT_DEBUGDATA, ch.userdata());
        }
        if (source.prereq != null) {
            ch.prereq = source.prereq;
            CurlNative.setoptPointer(cp, CurlNative.OPT_PREREQDATA, ch.userdata());
        }
        if (source.sshHostkey != null) {
            ch.sshHostkey = source.sshHostkey;
            CurlNative.setoptPointer(cp, CurlNative.OPT_SSH_HOSTKEYDATA, ch.userdata());
        }
        ch.privateData = source.privateData;
        if (ch.privateData instanceof PhpArray array) {
            array.addHolder();
        }
        // keep track of cloned copies to avoid invoking curl destructors for every clone
        ch.nat.toFree = source.nat.toFree;
        source.nat.toFree.clones++;
    }

    @TruffleBoundary
    private static Object curlCopyHandle(PhpContext context, Object[] a) {
        arity(a, "curl_copy_handle", 1, 1);
        PhpObject object = handleOf(a, 0, "curl_copy_handle", "handle", "CurlHandle");
        try {
            return cloneHandle(object);
        } catch (PhpError e) {
            if (!"Exception".equals(e.getPhpClass())) {
                throw e;
            }
            warn(context, "curl_copy_handle", "Cannot duplicate cURL handle");
            return Boolean.FALSE;
        }
    }

    @TruffleBoundary
    private static Object curlClose(PhpContext context, Object[] a) {
        deprecatedFunction(context, "curl_close");
        arity(a, "curl_close", 1, 1);
        Easy ch = easy(a, 0, "curl_close");
        if (ch.inCallback) {
            throw new PhpError("curl_close(): Attempt to close cURL handle from a callback");
        }
        return PhpNull.NULL;
    }

    @TruffleBoundary
    private static Object curlReset(PhpContext context, Object[] a) {
        arity(a, "curl_reset", 1, 1);
        Easy ch = easy(a, 0, "curl_reset");
        state(context);
        if (ch.inCallback) {
            throw new PhpError("curl_reset(): Attempt to reset cURL handle from a callback");
        }
        CurlNative.easyReset(ch.cp);
        resetHandlers(ch);
        setDefaultOptions(context, ch);
        return PhpNull.NULL;
    }

    /** _php_curl_reset_handlers. */
    private static void resetHandlers(Easy ch) {
        ch.write.stream = null;
        ch.write.method = STDOUT;
        ch.writeHeader.stream = null;
        ch.writeHeader.method = IGNORE;
        ch.read.stream = null;
        ch.read.method = DIRECT;
        ch.stdErr = null;
        if (ch.nat.stderr != null) {
            ch.nat.stderr.close();
            ch.nat.stderr = null;
        }
        ch.progress = null;
        ch.xferinfo = null;
        ch.fnmatch = null;
        ch.debug = null;
        ch.prereq = null;
        ch.sshHostkey = null;
    }

    /** _php_curl_verify_handlers: a stream closed since it was set is dropped (with a warning if reporting). */
    static void verifyHandlers(PhpContext context, Easy ch, boolean report) {
        String function = ch.state.activeFunction;
        if (ch.stdErr != null && ch.stdErr.isClosed()) {
            if (report) {
                warn(context, function, "CURLOPT_STDERR resource has gone away, resetting to stderr");
            }
            ch.stdErr = null;
            CurlNative.setoptPointer(ch.cp, CurlNative.OPT_STDERR, MemorySegment.NULL);
            if (ch.nat.stderr != null) {
                ch.nat.stderr.close();
                ch.nat.stderr = null;
            }
        }
        if (ch.read.stream != null && ch.read.stream.isClosed()) {
            if (report) {
                warn(context, function, "CURLOPT_INFILE resource has gone away, resetting to default");
            }
            ch.read.stream = null;
            CurlNative.setoptPointer(ch.cp, CurlNative.OPT_READDATA, ch.userdata());
        }
        if (ch.writeHeader.stream != null && ch.writeHeader.stream.isClosed()) {
            if (report) {
                warn(context, function, "CURLOPT_WRITEHEADER resource has gone away, resetting to default");
            }
            ch.writeHeader.stream = null;
            ch.writeHeader.method = IGNORE;
            CurlNative.setoptPointer(ch.cp, CurlNative.OPT_HEADERDATA, ch.userdata());
        }
        if (ch.write.stream != null && ch.write.stream.isClosed()) {
            if (report) {
                warn(context, function, "CURLOPT_FILE resource has gone away, resetting to default");
            }
            ch.write.stream = null;
            ch.write.method = STDOUT;
            CurlNative.setoptPointer(ch.cp, CurlNative.OPT_WRITEDATA, ch.userdata());
        }
    }

    /** _php_curl_cleanup_handle: what the last transfer left. */
    private static void cleanupHandle(Easy ch) {
        ch.write.buf = null;
        ch.headerOut = null;
        ch.errorBuffer.fill((byte) 0);
        ch.errNo = 0;
    }

    @TruffleBoundary
    private static Object curlExec(PhpContext context, Object[] a) {
        arity(a, "curl_exec", 1, 1);
        Easy ch = easy(a, 0, "curl_exec");
        State state = state(context);
        state.activeFunction = "curl_exec";
        verifyHandlers(context, ch, true);
        cleanupHandle(ch);
        int error = CurlNative.easyPerform(ch.cp);
        ch.errNo = error;
        rethrowPending(state);
        if (error != CURLE_OK) {
            ch.write.buf = null;
            return Boolean.FALSE;
        }
        if (ch.write.method == RETURN && ch.write.buf != null) {
            return Str.of(ch.write.buf.toByteArray());
        }
        return ch.write.method == RETURN ? (Object) Str.EMPTY : (Object) Boolean.TRUE;
    }

    @TruffleBoundary
    private static Object curlErrno(PhpContext context, Object[] a) {
        arity(a, "curl_errno", 1, 1);
        return (long) easy(a, 0, "curl_errno").errNo;
    }

    @TruffleBoundary
    private static Object curlError(PhpContext context, Object[] a) {
        arity(a, "curl_error", 1, 1);
        Easy ch = easy(a, 0, "curl_error");
        if (ch.errNo == 0) {
            return Str.EMPTY;
        }
        ch.errorBuffer.set(ValueLayout.JAVA_BYTE, CurlNative.ERROR_SIZE, (byte) 0);
        byte[] message = CurlNative.cBytes(ch.errorBuffer);
        return Str.of(message.length > 0 ? message : CurlNative.easyStrerror(ch.errNo));
    }

    @TruffleBoundary
    private static Object strerror(PhpContext context, Object[] a, String function, int kind) {
        arity(a, function, 1, 1);
        int code = (int) (long) longArg(context, a, 0, function, "error_code", false);
        byte[] message = switch (kind) {
            case 0 -> CurlNative.easyStrerror(code);
            case 1 -> CurlNative.multiStrerror(code);
            default -> CurlNative.shareStrerror(code);
        };
        return message == null ? PhpNull.NULL : Str.of(message);
    }

    @TruffleBoundary
    private static Object curlEscape(PhpContext context, Object[] a, boolean escape) {
        String function = escape ? "curl_escape" : "curl_unescape";
        arity(a, function, 2, 2);
        Easy ch = easy(a, 0, function);
        byte[] string = Str.bytes(stringArg(context, a, 1, function, "string", false));
        byte[] result = escape ? CurlNative.escape(ch.cp, string) : CurlNative.unescape(ch.cp, string);
        return result == null ? (Object) Boolean.FALSE : Str.of(result);
    }

    @TruffleBoundary
    private static Object curlPause(PhpContext context, Object[] a) {
        arity(a, "curl_pause", 2, 2);
        Easy ch = easy(a, 0, "curl_pause");
        long flags = longArg(context, a, 1, "curl_pause", "flags", false);
        State state = state(context);
        state.activeFunction = "curl_pause";
        int result = CurlNative.pause(ch.cp, (int) flags);
        rethrowPending(state);
        return (long) result;
    }

    @TruffleBoundary
    private static Object curlUpkeep(PhpContext context, Object[] a) {
        arity(a, "curl_upkeep", 1, 1);
        Easy ch = easy(a, 0, "curl_upkeep");
        State state = state(context);
        int error = CurlNative.upkeep(ch.cp);
        ch.errNo = error;
        rethrowPending(state);
        return error == CURLE_OK;
    }

    // ---- curl_setopt ----

    @TruffleBoundary
    private static Object curlSetopt(PhpContext context, Object[] a) {
        arity(a, "curl_setopt", 3, 3);
        Easy ch = easy(a, 0, "curl_setopt");
        long option = longArg(context, a, 1, "curl_setopt", "option", false);
        state(context);
        return setopt(context, ch, option, Args.get(a, 2), false, "curl_setopt");
    }

    @TruffleBoundary
    private static Object curlSetoptArray(PhpContext context, Object[] a) {
        arity(a, "curl_setopt_array", 2, 2);
        Easy ch = easy(a, 0, "curl_setopt_array");
        PhpArray options = arrayArg(a, 1, "curl_setopt_array", "options");
        state(context);
        for (int p = options.firstPosition(); p >= 0; p = options.nextPosition(p)) {
            if (!(options.keyAt(p) instanceof Long option)) {
                throw new PhpError("ValueError", "curl_setopt_array(): Argument #2 ($options) contains an invalid "
                        + "cURL option");
            }
            if (!setopt(context, ch, option, PhpRef.deref(options.valueAt(p)), true, "curl_setopt_array")) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    private static Set<Long> options(String... names) {
        Set<Long> ids = new HashSet<>();
        for (String name : names) {
            ids.add(value(name));
        }
        return ids;
    }

    /** The long options of _php_curl_setopt (CURLOPT_SSL_VERIFYHOST is checked first). */
    private static final Set<Long> LONG_OPTIONS = options("CURLOPT_AUTOREFERER", "CURLOPT_BUFFERSIZE",
            "CURLOPT_CONNECTTIMEOUT", "CURLOPT_COOKIESESSION", "CURLOPT_CRLF", "CURLOPT_DNS_CACHE_TIMEOUT",
            "CURLOPT_FAILONERROR", "CURLOPT_FILETIME", "CURLOPT_FORBID_REUSE", "CURLOPT_FRESH_CONNECT",
            "CURLOPT_FTP_USE_EPRT", "CURLOPT_FTP_USE_EPSV", "CURLOPT_HEADER", "CURLOPT_HTTPGET",
            "CURLOPT_HTTPPROXYTUNNEL", "CURLOPT_HTTP_VERSION", "CURLOPT_INFILESIZE", "CURLOPT_LOW_SPEED_LIMIT",
            "CURLOPT_LOW_SPEED_TIME", "CURLOPT_MAXCONNECTS", "CURLOPT_MAXREDIRS", "CURLOPT_NETRC", "CURLOPT_NOBODY",
            "CURLOPT_NOPROGRESS", "CURLOPT_NOSIGNAL", "CURLOPT_PORT", "CURLOPT_POST", "CURLOPT_PROXYPORT",
            "CURLOPT_PROXYTYPE", "CURLOPT_PUT", "CURLOPT_RESUME_FROM", "CURLOPT_SSLVERSION",
            "CURLOPT_SSL_VERIFYPEER", "CURLOPT_TIMECONDITION", "CURLOPT_TIMEOUT", "CURLOPT_TIMEVALUE",
            "CURLOPT_TRANSFERTEXT", "CURLOPT_UNRESTRICTED_AUTH", "CURLOPT_UPLOAD", "CURLOPT_VERBOSE",
            "CURLOPT_HTTPAUTH", "CURLOPT_FTP_CREATE_MISSING_DIRS", "CURLOPT_PROXYAUTH",
            "CURLOPT_SERVER_RESPONSE_TIMEOUT", "CURLOPT_IPRESOLVE", "CURLOPT_MAXFILESIZE", "CURLOPT_TCP_NODELAY",
            "CURLOPT_FTPSSLAUTH", "CURLOPT_IGNORE_CONTENT_LENGTH", "CURLOPT_FTP_SKIP_PASV_IP",
            "CURLOPT_FTP_FILEMETHOD", "CURLOPT_CONNECT_ONLY", "CURLOPT_LOCALPORT", "CURLOPT_LOCALPORTRANGE",
            "CURLOPT_SSL_SESSIONID_CACHE", "CURLOPT_FTP_SSL_CCC", "CURLOPT_SSH_AUTH_TYPES",
            "CURLOPT_CONNECTTIMEOUT_MS", "CURLOPT_HTTP_CONTENT_DECODING", "CURLOPT_HTTP_TRANSFER_DECODING",
            "CURLOPT_TIMEOUT_MS", "CURLOPT_NEW_DIRECTORY_PERMS", "CURLOPT_NEW_FILE_PERMS", "CURLOPT_USE_SSL",
            "CURLOPT_APPEND", "CURLOPT_DIRLISTONLY", "CURLOPT_PROXY_TRANSFER_MODE", "CURLOPT_ADDRESS_SCOPE",
            "CURLOPT_CERTINFO", "CURLOPT_PROTOCOLS", "CURLOPT_REDIR_PROTOCOLS", "CURLOPT_SOCKS5_GSSAPI_NEC",
            "CURLOPT_TFTP_BLKSIZE", "CURLOPT_FTP_USE_PRET", "CURLOPT_RTSP_CLIENT_CSEQ", "CURLOPT_RTSP_REQUEST",
            "CURLOPT_RTSP_SERVER_CSEQ", "CURLOPT_WILDCARDMATCH", "CURLOPT_GSSAPI_DELEGATION",
            "CURLOPT_ACCEPTTIMEOUT_MS", "CURLOPT_SSL_OPTIONS", "CURLOPT_TCP_KEEPALIVE", "CURLOPT_TCP_KEEPIDLE",
            "CURLOPT_TCP_KEEPINTVL", "CURLOPT_SASL_IR", "CURLOPT_EXPECT_100_TIMEOUT_MS", "CURLOPT_SSL_ENABLE_ALPN",
            "CURLOPT_SSL_ENABLE_NPN", "CURLOPT_HEADEROPT", "CURLOPT_SSL_VERIFYSTATUS", "CURLOPT_PATH_AS_IS",
            "CURLOPT_SSL_FALSESTART", "CURLOPT_PIPEWAIT", "CURLOPT_STREAM_WEIGHT", "CURLOPT_TFTP_NO_OPTIONS",
            "CURLOPT_TCP_FASTOPEN", "CURLOPT_KEEP_SENDING_ON_ERROR", "CURLOPT_PROXY_SSL_OPTIONS",
            "CURLOPT_PROXY_SSL_VERIFYHOST", "CURLOPT_PROXY_SSL_VERIFYPEER", "CURLOPT_PROXY_SSLVERSION",
            "CURLOPT_SUPPRESS_CONNECT_HEADERS", "CURLOPT_SOCKS5_AUTH", "CURLOPT_SSH_COMPRESSION",
            "CURLOPT_HAPPY_EYEBALLS_TIMEOUT_MS", "CURLOPT_DNS_SHUFFLE_ADDRESSES", "CURLOPT_HAPROXYPROTOCOL",
            "CURLOPT_DISALLOW_USERNAME_IN_URL", "CURLOPT_UPKEEP_INTERVAL_MS", "CURLOPT_UPLOAD_BUFFERSIZE",
            "CURLOPT_HTTP09_ALLOWED", "CURLOPT_ALTSVC_CTRL", "CURLOPT_MAXAGE_CONN", "CURLOPT_MAIL_RCPT_ALLLOWFAILS",
            "CURLOPT_HSTS_CTRL", "CURLOPT_DOH_SSL_VERIFYHOST", "CURLOPT_DOH_SSL_VERIFYPEER",
            "CURLOPT_DOH_SSL_VERIFYSTATUS", "CURLOPT_MAXLIFETIME_CONN", "CURLOPT_MIME_OPTIONS",
            "CURLOPT_WS_OPTIONS", "CURLOPT_CA_CACHE_TIMEOUT", "CURLOPT_QUICK_EXIT", "CURLOPT_TCP_KEEPCNT",
            "CURLOPT_FOLLOWLOCATION");

    private static final Set<Long> STRING_OPTIONS = options("CURLOPT_CAINFO", "CURLOPT_CAPATH", "CURLOPT_COOKIE",
            "CURLOPT_EGDSOCKET", "CURLOPT_INTERFACE", "CURLOPT_PROXY", "CURLOPT_PROXYUSERPWD", "CURLOPT_REFERER",
            "CURLOPT_SSLCERTTYPE", "CURLOPT_SSLENGINE", "CURLOPT_SSLENGINE_DEFAULT", "CURLOPT_SSLKEY",
            "CURLOPT_SSLKEYPASSWD", "CURLOPT_SSLKEYTYPE", "CURLOPT_SSL_CIPHER_LIST", "CURLOPT_USERAGENT",
            "CURLOPT_COOKIELIST", "CURLOPT_FTP_ALTERNATIVE_TO_USER", "CURLOPT_SSH_HOST_PUBLIC_KEY_MD5",
            "CURLOPT_PROXYPASSWORD", "CURLOPT_PROXYUSERNAME", "CURLOPT_NOPROXY", "CURLOPT_SOCKS5_GSSAPI_SERVICE",
            "CURLOPT_MAIL_FROM", "CURLOPT_RTSP_STREAM_URI", "CURLOPT_RTSP_TRANSPORT", "CURLOPT_TLSAUTH_TYPE",
            "CURLOPT_TLSAUTH_PASSWORD", "CURLOPT_TLSAUTH_USERNAME", "CURLOPT_TRANSFER_ENCODING",
            "CURLOPT_DNS_SERVERS", "CURLOPT_MAIL_AUTH", "CURLOPT_LOGIN_OPTIONS", "CURLOPT_PINNEDPUBLICKEY",
            "CURLOPT_PROXY_SERVICE_NAME", "CURLOPT_SERVICE_NAME", "CURLOPT_DEFAULT_PROTOCOL", "CURLOPT_PRE_PROXY",
            "CURLOPT_PROXY_CAINFO", "CURLOPT_PROXY_CAPATH", "CURLOPT_PROXY_CRLFILE", "CURLOPT_PROXY_KEYPASSWD",
            "CURLOPT_PROXY_PINNEDPUBLICKEY", "CURLOPT_PROXY_SSL_CIPHER_LIST", "CURLOPT_PROXY_SSLCERT",
            "CURLOPT_PROXY_SSLCERTTYPE", "CURLOPT_PROXY_SSLKEY", "CURLOPT_PROXY_SSLKEYTYPE",
            "CURLOPT_PROXY_TLSAUTH_PASSWORD", "CURLOPT_PROXY_TLSAUTH_TYPE", "CURLOPT_PROXY_TLSAUTH_USERNAME",
            "CURLOPT_ABSTRACT_UNIX_SOCKET", "CURLOPT_REQUEST_TARGET", "CURLOPT_PROXY_TLS13_CIPHERS",
            "CURLOPT_TLS13_CIPHERS", "CURLOPT_ALTSVC", "CURLOPT_SASL_AUTHZID", "CURLOPT_PROXY_ISSUERCERT",
            "CURLOPT_SSL_EC_CURVES", "CURLOPT_AWS_SIGV4", "CURLOPT_SSH_HOST_PUBLIC_KEY_SHA256",
            "CURLOPT_PROTOCOLS_STR", "CURLOPT_REDIR_PROTOCOLS_STR");

    private static final Set<Long> NULLABLE_STRING_OPTIONS = options("CURLOPT_CUSTOMREQUEST", "CURLOPT_FTPPORT",
            "CURLOPT_RANGE", "CURLOPT_FTP_ACCOUNT", "CURLOPT_RTSP_SESSION_ID", "CURLOPT_ACCEPT_ENCODING",
            "CURLOPT_DNS_INTERFACE", "CURLOPT_DNS_LOCAL_IP4", "CURLOPT_DNS_LOCAL_IP6", "CURLOPT_XOAUTH2_BEARER",
            "CURLOPT_UNIX_SOCKET_PATH", "CURLOPT_DOH_URL", "CURLOPT_HSTS", "CURLOPT_KRBLEVEL", "CURLOPT_USERPWD",
            "CURLOPT_USERNAME", "CURLOPT_PASSWORD", "CURLOPT_SSL_SIGNATURE_ALGORITHMS");

    private static final Set<Long> FILE_HANDLE_OPTIONS = options("CURLOPT_FILE", "CURLOPT_INFILE", "CURLOPT_STDERR",
            "CURLOPT_WRITEHEADER");

    /** The linked list options, by the name php-src's type error gives them. */
    private static final Map<Long, String> SLIST_OPTIONS = namedOptions("CURLOPT_HTTP200ALIASES",
            "CURLOPT_HTTPHEADER", "CURLOPT_POSTQUOTE", "CURLOPT_PREQUOTE", "CURLOPT_QUOTE", "CURLOPT_TELNETOPTIONS",
            "CURLOPT_MAIL_RCPT", "CURLOPT_RESOLVE", "CURLOPT_PROXYHEADER", "CURLOPT_CONNECT_TO");

    private static final Set<Long> OFF_T_OPTIONS = options("CURLOPT_INFILESIZE_LARGE",
            "CURLOPT_MAX_RECV_SPEED_LARGE", "CURLOPT_MAX_SEND_SPEED_LARGE", "CURLOPT_MAXFILESIZE_LARGE",
            "CURLOPT_TIMEVALUE_LARGE");

    /** The options naming files, which open_basedir restricts. */
    private static final Set<Long> PATH_OPTIONS = options("CURLOPT_COOKIEFILE", "CURLOPT_COOKIEJAR",
            "CURLOPT_RANDOM_FILE", "CURLOPT_SSLCERT", "CURLOPT_NETRC_FILE", "CURLOPT_SSH_PRIVATE_KEYFILE",
            "CURLOPT_SSH_PUBLIC_KEYFILE", "CURLOPT_CRLFILE", "CURLOPT_ISSUERCERT", "CURLOPT_SSH_KNOWNHOSTS");

    private static final Set<Long> BLOB_OPTIONS = options("CURLOPT_ISSUERCERT_BLOB", "CURLOPT_PROXY_ISSUERCERT_BLOB",
            "CURLOPT_PROXY_SSLCERT_BLOB", "CURLOPT_PROXY_SSLKEY_BLOB", "CURLOPT_SSLCERT_BLOB", "CURLOPT_SSLKEY_BLOB",
            "CURLOPT_CAINFO_BLOB", "CURLOPT_PROXY_CAINFO_BLOB");

    private static Map<Long, String> namedOptions(String... names) {
        Map<Long, String> options = new HashMap<>();
        for (String name : names) {
            options.put(value(name), name);
        }
        return options;
    }

    private static final long OPT_SSL_VERIFYHOST = value("CURLOPT_SSL_VERIFYHOST");
    private static final long OPT_PROTOCOLS = value("CURLOPT_PROTOCOLS");
    private static final long OPT_REDIR_PROTOCOLS = value("CURLOPT_REDIR_PROTOCOLS");
    private static final long OPT_PROTOCOLS_STR = value("CURLOPT_PROTOCOLS_STR");
    private static final long OPT_REDIR_PROTOCOLS_STR = value("CURLOPT_REDIR_PROTOCOLS_STR");
    private static final long OPT_PRIVATE = value("CURLOPT_PRIVATE");
    private static final long OPT_URL = value("CURLOPT_URL");
    private static final long OPT_FILE = value("CURLOPT_FILE");
    private static final long OPT_INFILE = value("CURLOPT_INFILE");
    private static final long OPT_STDERR = value("CURLOPT_STDERR");
    private static final long OPT_WRITEHEADER = value("CURLOPT_WRITEHEADER");
    private static final long OPT_DNS_USE_GLOBAL_CACHE = value("CURLOPT_DNS_USE_GLOBAL_CACHE");
    private static final long OPT_POSTFIELDS = value("CURLOPT_POSTFIELDS");
    private static final long OPT_POSTREDIR = value("CURLOPT_POSTREDIR");
    private static final long OPT_SHARE = value("CURLOPT_SHARE");
    private static final long REDIR_POST_ALL = value("CURL_REDIR_POST_ALL");

    /**
     * _php_curl_setopt: an option set by its type, as interface.c's switch; arrayConfig is curl_setopt_array's,
     * whose errors name its options argument.
     */
    private static boolean setopt(PhpContext context, Easy ch, long option, Object value, boolean arrayConfig,
                                  String function) {
        int error = CURLE_OK;
        MemorySegment cp = ch.cp;
        int id = (int) option;
        if (option == CurlNative.OPT_WRITEFUNCTION) {
            return userHandler(context, ch.write, value, arrayConfig, function, "CURLOPT_WRITEFUNCTION", STDOUT, ch);
        } else if (option == CurlNative.OPT_HEADERFUNCTION) {
            return userHandler(context, ch.writeHeader, value, arrayConfig, function, "CURLOPT_HEADERFUNCTION",
                    IGNORE, ch);
        } else if (option == CurlNative.OPT_READFUNCTION) {
            Callables.Target target;
            try {
                target = callableHandler(context, value, arrayConfig, function, "CURLOPT_READFUNCTION");
            } catch (PhpError e) {
                ch.read.fcc = null;
                ch.read.method = DIRECT;
                throw e;
            }
            ch.read.fcc = target;
            if (target == null) {
                ch.read.method = DIRECT;
                return true;
            }
            ch.read.method = USER;
        } else if (option == CurlNative.OPT_PROGRESSFUNCTION) {
            ch.progress = callable(context, ch, value, arrayConfig, function, "CURLOPT_PROGRESSFUNCTION",
                    CurlNative.OPT_PROGRESSFUNCTION, CurlNative.Callbacks.PROGRESS, CurlNative.OPT_PROGRESSDATA);
        } else if (option == CurlNative.OPT_XFERINFOFUNCTION) {
            ch.xferinfo = callable(context, ch, value, arrayConfig, function, "CURLOPT_XFERINFOFUNCTION",
                    CurlNative.OPT_XFERINFOFUNCTION, CurlNative.Callbacks.XFERINFO, CurlNative.OPT_XFERINFODATA);
        } else if (option == CurlNative.OPT_FNMATCH_FUNCTION) {
            ch.fnmatch = callable(context, ch, value, arrayConfig, function, "CURLOPT_FNMATCH_FUNCTION",
                    CurlNative.OPT_FNMATCH_FUNCTION, CurlNative.Callbacks.FNMATCH, CurlNative.OPT_FNMATCH_DATA);
        } else if (option == CurlNative.OPT_DEBUGFUNCTION) {
            ch.debug = callable(context, ch, value, arrayConfig, function, "CURLOPT_DEBUGFUNCTION",
                    CurlNative.OPT_DEBUGFUNCTION, CurlNative.Callbacks.DEBUG, CurlNative.OPT_DEBUGDATA);
        } else if (option == CurlNative.OPT_PREREQFUNCTION) {
            ch.prereq = callable(context, ch, value, arrayConfig, function, "CURLOPT_PREREQFUNCTION",
                    CurlNative.OPT_PREREQFUNCTION, CurlNative.Callbacks.PREREQ, CurlNative.OPT_PREREQDATA);
        } else if (option == CurlNative.OPT_SSH_HOSTKEYFUNCTION) {
            ch.sshHostkey = callable(context, ch, value, arrayConfig, function, "CURLOPT_SSH_HOSTKEYFUNCTION",
                    CurlNative.OPT_SSH_HOSTKEYFUNCTION, CurlNative.Callbacks.SSH_HOSTKEY,
                    CurlNative.OPT_SSH_HOSTKEYDATA);
        } else if (option == OPT_SSL_VERIFYHOST && Convert.toLong(value) == 1) {
            ErrorFunctions.raise(context, ErrorFunctions.E_NOTICE, Str.of(function + "(): CURLOPT_SSL_VERIFYHOST no "
                    + "longer accepts the value 1, value 2 will be used instead"), SourceLocation.current());
            error = CurlNative.setoptLong(cp, id, 2);
        } else if (option == OPT_SSL_VERIFYHOST || LONG_OPTIONS.contains(option)) {
            long lval = Convert.toLong(value);
            if ((option == OPT_PROTOCOLS || option == OPT_REDIR_PROTOCOLS) && openBasedir(context) != null
                    && (lval & CurlNative.PROTO_FILE) != 0) {
                warn(context, function, "CURLPROTO_FILE cannot be activated when an open_basedir is set");
                return false;
            }
            error = CurlNative.setoptLong(cp, id, lval);
        } else if (option == OPT_SAFE_UPLOAD) {
            if (!Convert.toBool(value)) {
                throw new PhpError("ValueError", function + "(): Disabling safe uploads is no longer supported");
            }
        } else if (STRING_OPTIONS.contains(option)) {
            byte[] string = Str.bytes(Convert.toStr(value));
            if ((option == OPT_PROTOCOLS_STR || option == OPT_REDIR_PROTOCOLS_STR) && openBasedir(context) != null
                    && (containsIgnoreCase(string, "file") || containsIgnoreCase(string, "all"))) {
                warn(context, function, "The FILE protocol cannot be activated when an open_basedir is set");
                return false;
            }
            return optionString(ch, id, string, function);
        } else if (NULLABLE_STRING_OPTIONS.contains(option)) {
            if (value != PhpNull.NULL) {
                return optionString(ch, id, Str.bytes(Convert.toStr(value)), function);
            }
            error = CurlNative.setoptPointer(cp, id, MemorySegment.NULL);
        } else if (option == OPT_PRIVATE) {
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            ch.privateData = value;
            return true;
        } else if (option == OPT_URL) {
            return optionUrl(context, ch, Str.bytes(Convert.toStr(value)), function);
        } else if (FILE_HANDLE_OPTIONS.contains(option)) {
            return fileHandleOption(context, ch, option, value, function);
        } else if (SLIST_OPTIONS.containsKey(option)) {
            if (!(value instanceof PhpArray array)) {
                throw new PhpError("TypeError", function + "(): The " + SLIST_OPTIONS.get(option)
                        + " option must have an array value");
            }
            MemorySegment slist = MemorySegment.NULL;
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                byte[] entry = Str.bytes(Convert.toStr(PhpRef.deref(array.valueAt(p))));
                MemorySegment appended = CurlNative.slistAppend(slist, entry);
                if (appended.equals(MemorySegment.NULL)) {
                    CurlNative.slistFreeAll(slist);
                    warn(context, function, "Could not build curl_slist");
                    return false;
                }
                slist = appended;
            }
            if (!slist.equals(MemorySegment.NULL)) {
                ToFree toFree = ch.nat.toFree;
                if (toFree.clones == 1) {
                    MemorySegment old = toFree.slist.put(option, slist);
                    if (old != null) {
                        CurlNative.slistFreeAll(old);
                    }
                } else {
                    toFree.slist.put(new Object(), slist);
                }
            }
            error = CurlNative.setoptPointer(cp, id, slist);
        } else if (option == OPT_BINARYTRANSFER || option == OPT_DNS_USE_GLOBAL_CACHE) {
            // do nothing, just backward compatibility
        } else if (option == OPT_POSTFIELDS) {
            if (value instanceof PhpArray array) {
                if (array.size() == 0) {
                    // no need to build the mime structure for empty hashtables (curl/curl#6455)
                    try (Arena arena = Arena.ofConfined()) {
                        CurlNative.setoptPointer(cp, CurlNative.OPT_POSTFIELDS, arena.allocate(1));
                    }
                    error = CurlNative.setoptLong(cp, CurlNative.OPT_POSTFIELDSIZE, 0);
                } else {
                    return buildMimeStructure(context, ch, array, function);
                }
            } else {
                byte[] string = Str.bytes(Convert.toStr(value));
                // with curl 7.17.0 and later, we can use COPYPOSTFIELDS, but we have to provide size before
                error = CurlNative.setoptLong(cp, CurlNative.OPT_POSTFIELDSIZE, string.length);
                try (Arena arena = Arena.ofConfined()) {
                    error = CurlNative.setoptPointer(cp, CurlNative.OPT_COPYPOSTFIELDS,
                            CurlNative.cString(arena, string));
                }
            }
        } else if (option == OPT_RETURNTRANSFER) {
            ch.write.method = Convert.toBool(value) ? RETURN : STDOUT;
        } else if (OFF_T_OPTIONS.contains(option)) {
            error = CurlNative.setoptOffT(cp, id, Convert.toLong(value));
        } else if (option == OPT_POSTREDIR) {
            error = CurlNative.setoptLong(cp, id, Convert.toLong(value) & REDIR_POST_ALL);
        } else if (PATH_OPTIONS.contains(option)) {
            byte[] path = Str.bytes(Convert.toStr(value));
            if (path.length > 0 && !checkOpenBasedir(context, path, function)) {
                return false;
            }
            return optionString(ch, id, path, function);
        } else if (option == INFO_HEADER_OUT) {
            if (ch.debug != null) {
                throw new PhpError("ValueError", "CURLINFO_HEADER_OUT option must not be set when the "
                        + "CURLOPT_DEBUGFUNCTION option is set");
            }
            if (Convert.toBool(value)) {
                CurlNative.setoptPointer(cp, CurlNative.OPT_DEBUGFUNCTION, CurlNative.Callbacks.DEBUG);
                CurlNative.setoptPointer(cp, CurlNative.OPT_DEBUGDATA, ch.userdata());
                CurlNative.setoptLong(cp, CurlNative.OPT_VERBOSE, 1);
            } else {
                CurlNative.setoptPointer(cp, CurlNative.OPT_DEBUGFUNCTION, MemorySegment.NULL);
                CurlNative.setoptPointer(cp, CurlNative.OPT_DEBUGDATA, MemorySegment.NULL);
                CurlNative.setoptLong(cp, CurlNative.OPT_VERBOSE, 0);
            }
        } else if (option == OPT_SHARE) {
            if (value instanceof PhpObject object && object.getInternal() instanceof Share sh) {
                CurlNative.setoptPointer(cp, CurlNative.OPT_SHARE, sh.share);
                ch.share = sh;
            }
        } else if (BLOB_OPTIONS.contains(option)) {
            byte[] blob = Str.bytes(Convert.toStr(value));
            try (Arena arena = Arena.ofConfined()) {
                // struct curl_blob {data, len, flags}, copied by libcurl (CURL_BLOB_COPY)
                MemorySegment stblob = arena.allocate(24, 8);
                stblob.set(CurlNative.PTR, 0, CurlNative.bytes(arena, blob));
                stblob.set(ValueLayout.JAVA_LONG, 8, blob.length);
                stblob.set(ValueLayout.JAVA_INT, 16, CurlNative.BLOB_COPY);
                error = CurlNative.setoptPointer(cp, id, stblob);
            }
        } else {
            ch.errNo = CurlNative.CURLE_UNKNOWN_OPTION;
            throw new PhpError("ValueError", function + "(): Argument #2 ($" + (arrayConfig ? "options" : "option")
                    + ") " + (arrayConfig ? "must contain only valid cURL options" : "is not a valid cURL option"));
        }
        ch.errNo = error;
        return error == CURLE_OK;
    }

    /** HANDLE_CURL_OPTION_CALLABLE_PHP_CURL_USER: a write or header handler's callable. */
    private static boolean userHandler(PhpContext context, Write handler, Object value, boolean arrayConfig,
                                       String function, String name, int defaultMethod, Easy ch) {
        Callables.Target target;
        try {
            target = callableHandler(context, value, arrayConfig, function, name);
        } catch (PhpError e) {
            handler.fcc = null;
            handler.method = defaultMethod;
            throw e;
        }
        handler.fcc = target;
        if (target == null) {
            handler.method = defaultMethod;
            return true;
        }
        handler.method = USER;
        ch.errNo = CURLE_OK;
        return true;
    }

    /** HANDLE_CURL_OPTION_CALLABLE: the callable, with libcurl's function and data set to this handle's. */
    private static Callables.Target callable(PhpContext context, Easy ch, Object value, boolean arrayConfig,
                                             String function, String name, int functionOption, MemorySegment stub,
                                             int dataOption) {
        Callables.Target target = callableHandler(context, value, arrayConfig, function, name);
        CurlNative.setoptPointer(ch.cp, functionOption, stub);
        CurlNative.setoptPointer(ch.cp, dataOption, ch.userdata());
        return target;
    }

    /** php_curl_set_callable_handler: null for null; a value that isn't callable is a TypeError. */
    private static Callables.Target callableHandler(PhpContext context, Object value, boolean arrayConfig,
                                                    String function, String name) {
        if (value == PhpNull.NULL || value == null) {
            return null;
        }
        Callables.Target target = Callables.resolve(context, value);
        if (target == null) {
            throw new PhpError("TypeError", function + "(): Argument #" + (arrayConfig ? 2 : 3) + " ($"
                    + (arrayConfig ? "options" : "value") + ") must be a valid callback for option " + name + ", "
                    + Callables.invalidReason(context, value));
        }
        return target;
    }

    /** php_curl_option_str: a string option without NUL bytes. */
    private static boolean optionString(Easy ch, int option, byte[] value, String function) {
        if (hasNul(value)) {
            throw new PhpError("ValueError", function + "(): cURL option must not contain any null bytes");
        }
        int error = CurlNative.setoptString(ch.cp, option, value);
        ch.errNo = error;
        return error == CURLE_OK;
    }

    /** php_curl_option_url: CURLOPT_URL, with file:// disabled under open_basedir. */
    private static boolean optionUrl(PhpContext context, Easy ch, byte[] url, String function) {
        if (openBasedir(context) != null) {
            CurlNative.setoptLong(ch.cp, CurlNative.OPT_PROTOCOLS, CurlNative.PROTO_ALL & ~CurlNative.PROTO_FILE);
        }
        return optionString(ch, CurlNative.OPT_URL, url, function);
    }

    /** The file handle options: a PHP stream curl writes to or reads from, or null for the default. */
    private static boolean fileHandleOption(PhpContext context, Easy ch, long option, Object value,
                                            String function) {
        PhpResource what = null;
        FileFunctions.Stream stream = null;
        if (value != PhpNull.NULL) {
            if (!(value instanceof PhpResource resource)) {
                throw new PhpError("TypeError", function + "(): supplied argument is not a valid File-Handle "
                        + "resource");
            }
            if (resource.isClosed() || !(resource.getHandle() instanceof FileFunctions.Stream s)) {
                throw new PhpError("TypeError", function + "(): supplied resource is not a valid File-Handle "
                        + "resource");
            }
            // php_stream_cast(PHP_STREAM_AS_STDIO): glibc's fopencookie makes a FILE* of any stream but a directory
            if (s instanceof FileFunctions.Directory) {
                warn(context, function, "Cannot represent a stream of type dir as a STDIO FILE*");
                return false;
            }
            what = resource;
            stream = s;
        }
        boolean writable = stream != null && (stream.mode.charAt(0) != 'r'
                || stream.mode.length() > 1 && stream.mode.charAt(1) == '+');
        int error = CURLE_OK;
        if (option == OPT_FILE) {
            if (what == null) {
                ch.write.stream = null;
                ch.write.method = STDOUT;
            } else if (writable) {
                ch.write.stream = what;
                ch.write.method = FILE;
            } else {
                throw new PhpError("ValueError", function + "(): The provided file handle must be writable");
            }
        } else if (option == OPT_WRITEHEADER) {
            if (what == null) {
                ch.writeHeader.stream = null;
                ch.writeHeader.method = IGNORE;
            } else if (writable) {
                ch.writeHeader.stream = what;
                ch.writeHeader.method = FILE;
            } else {
                throw new PhpError("ValueError", function + "(): The provided file handle must be writable");
            }
        } else if (option == OPT_INFILE) {
            ch.read.stream = what;
        } else {
            if (what != null && !writable) {
                throw new PhpError("ValueError", function + "(): The provided file handle must be writable");
            }
            MemorySegment fp = MemorySegment.NULL;
            if (what != null) {
                StderrFile file = StderrFile.open(stream);
                if (file == null) {
                    throw new PhpError(function + "(): CURLOPT_STDERR is not supported on this platform: it needs "
                            + "glibc's fopencookie to give libcurl a FILE* over a PHP stream");
                }
                fp = file.fp;
                if (ch.nat.stderr != null) {
                    ch.nat.stderr.close();
                }
                ch.nat.stderr = file;
            }
            ch.stdErr = what;
            error = CurlNative.setoptPointer(ch.cp, CurlNative.OPT_STDERR, fp);
            if (what == null && ch.nat.stderr != null) {
                ch.nat.stderr.close();
                ch.nat.stderr = null;
            }
        }
        ch.errNo = error;
        return error == CURLE_OK;
    }

    // ---- CURLOPT_POSTFIELDS with an array: the mime structure ----

    /** build_mime_structure_from_hash: one part per field; CURLFile and CURLStringFile parts are files. */
    private static boolean buildMimeStructure(PhpContext context, Easy ch, PhpArray postfields, String function) {
        int error = CURLE_OK;
        MemorySegment mime = null;
        if (postfields.size() > 0) {
            mime = CurlNative.mimeInit(ch.cp);
            if (mime.equals(MemorySegment.NULL)) {
                return false;
            }
        }
        for (int p = postfields.firstPosition(); p >= 0; p = postfields.nextPosition(p)) {
            byte[] key = Str.bytes(Convert.toStr(postfields.keyAt(p)));
            Object current = PhpRef.deref(postfields.valueAt(p));
            if (current instanceof PhpObject file && file.getPhpClass().isSubclassOf("curlfile")) {
                // new-style file upload
                Object name = PhpRef.deref(file.getProperty("name"));
                if (!(name instanceof TruffleString postval)) {
                    warn(context, function, "Invalid filename for key " + Str.java(Str.of(key)));
                    continue;
                }
                byte[] filename = Str.bytes(postval);
                if (!checkOpenBasedir(context, filename, function)) {
                    CurlNative.mimeFree(mime);
                    return false;
                }
                byte[] type = nonEmptyString(file.getProperty("mime"));
                byte[] postname = nonEmptyString(file.getProperty("postname"));
                keepPostfields(ch, postfields);
                FileFunctions.Stream stream = FileFunctions.openStream(context, Str.java(postval), "rb");
                long filesize = -1;
                boolean seekable = true;
                if (stream != null) {
                    if (stream instanceof FileFunctions.FileStream) {
                        context.builtinState().openStreams.add(stream);
                    }
                    if (stream.stat() instanceof PhpArray stat && stat.get(Str.of("size")) instanceof Long size) {
                        filesize = size;
                    }
                } else {
                    seekable = false;
                }
                MemorySegment part = CurlNative.mimeAddpart(mime);
                if (part.equals(MemorySegment.NULL)) {
                    if (stream != null) {
                        FileFunctions.closeStream(stream);
                    }
                    CurlNative.mimeFree(mime);
                    return false;
                }
                MimeSource source = new MimeSource(filename, stream);
                MIME_SOURCES.put(source.id, source);
                int formError;
                if ((formError = CurlNative.mimeString(CurlNative.Library.MIME_NAME, part, key)) != CURLE_OK
                        || (formError = CurlNative.mimeDataCallback(part, filesize, seekable, source.id)) != CURLE_OK
                        || (formError = CurlNative.mimeString(CurlNative.Library.MIME_FILENAME, part,
                                postname != null ? postname : filename)) != CURLE_OK
                        || (formError = CurlNative.mimeString(CurlNative.Library.MIME_TYPE, part,
                                type != null ? type : OCTET_STREAM)) != CURLE_OK) {
                    error = formError;
                }
                ch.nat.toFree.stream.add(source);
                continue;
            }
            if (current instanceof PhpObject file && file.getPhpClass().isSubclassOf("curlstringfile")) {
                // new-style file upload from string
                byte[] filename = Str.bytes(Convert.toStr(PhpRef.deref(file.getProperty("postname"))));
                byte[] type = Str.bytes(Convert.toStr(PhpRef.deref(file.getProperty("mime"))));
                byte[] data = Str.bytes(Convert.toStr(PhpRef.deref(file.getProperty("data"))));
                keepPostfields(ch, postfields);
                MemorySegment part = CurlNative.mimeAddpart(mime);
                if (part.equals(MemorySegment.NULL)) {
                    CurlNative.mimeFree(mime);
                    return false;
                }
                int formError;
                if ((formError = CurlNative.mimeString(CurlNative.Library.MIME_NAME, part, key)) != CURLE_OK
                        || (formError = CurlNative.mimeData(part, data)) != CURLE_OK
                        || (formError = CurlNative.mimeString(CurlNative.Library.MIME_FILENAME, part, filename))
                        != CURLE_OK
                        || (formError = CurlNative.mimeString(CurlNative.Library.MIME_TYPE, part, type)) != CURLE_OK) {
                    error = formError;
                }
                continue;
            }
            if (current instanceof PhpArray values) {
                for (int q = values.firstPosition(); q >= 0; q = values.nextPosition(q)) {
                    addSimpleField(mime, key, PhpRef.deref(values.valueAt(q)));
                }
                continue;
            }
            int fieldError = addSimpleField(mime, key, current);
            if (fieldError != CURLE_OK) {
                error = fieldError;
            }
        }
        ch.errNo = error;
        if (error != CURLE_OK) {
            CurlNative.mimeFree(mime);
            return false;
        }
        ToFree toFree = ch.nat.toFree;
        if (toFree.clones == 1) {
            for (MemorySegment old : toFree.post) {
                CurlNative.mimeFree(old);
            }
            toFree.post.clear();
        }
        toFree.post.add(mime);
        error = CurlNative.setoptPointer(ch.cp, CurlNative.OPT_MIMEPOST, mime);
        ch.errNo = error;
        return error == CURLE_OK;
    }

    private static final byte[] OCTET_STREAM = "application/octet-stream".getBytes(
            StandardCharsets.US_ASCII);

    /** add_simple_field: a part named key whose data is the value as a string. */
    private static int addSimpleField(MemorySegment mime, byte[] key, Object value) {
        byte[] postval = Str.bytes(Convert.toStr(value));
        MemorySegment part = CurlNative.mimeAddpart(mime);
        if (part.equals(MemorySegment.NULL)) {
            return CurlNative.CURLE_OUT_OF_MEMORY;
        }
        int error = CurlNative.mimeString(CurlNative.Library.MIME_NAME, part, key);
        if (error == CURLE_OK) {
            error = CurlNative.mimeData(part, postval);
        }
        return error;
    }

    private static void keepPostfields(Easy ch, PhpArray postfields) {
        if (ch.postfields != postfields) {
            postfields.addHolder();
            ch.postfields = postfields;
        }
    }

    private static byte[] nonEmptyString(Object property) {
        Object value = PhpRef.deref(property);
        return value instanceof TruffleString s && Str.length(s) > 0 ? Str.bytes(s) : null;
    }

    // ---- open_basedir ----

    /** PG(open_basedir) when set and not empty, else null. */
    private static String openBasedir(PhpContext context) {
        String value = context.ini().get("open_basedir");
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * php_check_open_basedir: whether the path lies in one of open_basedir's directories (each resolved as
     * php_check_specific_open_basedir does: its real path, and the file's, or the absolute path where none
     * exists); a warning when it doesn't.
     */
    private static boolean checkOpenBasedir(PhpContext context, byte[] path, String function) {
        String basedir = openBasedir(context);
        if (basedir == null) {
            return true;
        }
        String name = new String(path, StandardCharsets.UTF_8);
        String resolvedName = resolve(context, name);
        if (resolvedName != null) {
            for (String allowed : basedir.split(":")) {
                if (allowed.isEmpty()) {
                    continue;
                }
                String resolvedBase = resolve(context, allowed);
                if (resolvedBase == null) {
                    continue;
                }
                if (allowed.endsWith("/") && !resolvedBase.endsWith("/")) {
                    resolvedBase += "/";
                }
                if (resolvedName.equals(resolvedBase) || resolvedName.startsWith(resolvedBase.endsWith("/")
                        ? resolvedBase : resolvedBase + "/")
                        || (resolvedName + "/").equals(resolvedBase)) {
                    return true;
                }
            }
        }
        warn(context, function, "open_basedir restriction in effect. File(" + name
                + ") is not within the allowed path(s): (" + basedir + ")");
        return false;
    }

    private static String resolve(PhpContext context, String name) {
        try {
            Path path = Path.of(context.getEnv().getPublicTruffleFile(name).getAbsoluteFile().getPath());
            Path real = Files.exists(path) ? path.toRealPath() : path.normalize();
            return real.toString().replace('\\', '/');
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(byte[] haystack, String needle) {
        return Str.lower(new String(haystack, StandardCharsets.ISO_8859_1)).contains(needle);
    }

    // ---- curl_getinfo ----

    private static final int INFO_EFFECTIVE_URL = (int) value("CURLINFO_EFFECTIVE_URL");
    private static final int INFO_CONTENT_TYPE = (int) value("CURLINFO_CONTENT_TYPE");
    private static final int INFO_CERTINFO = (int) value("CURLINFO_CERTINFO");
    private static final int INFO_PRIVATE = (int) value("CURLINFO_PRIVATE");

    /** curl_getinfo's array: the key and the CURLINFO, in php-src's order (content_type, certinfo apart). */
    private static final String[][] INFO_FIELDS = {
            {"url", "CURLINFO_EFFECTIVE_URL"}, {"content_type", "CURLINFO_CONTENT_TYPE"},
            {"http_code", "CURLINFO_HTTP_CODE"}, {"header_size", "CURLINFO_HEADER_SIZE"},
            {"request_size", "CURLINFO_REQUEST_SIZE"}, {"filetime", "CURLINFO_FILETIME"},
            {"ssl_verify_result", "CURLINFO_SSL_VERIFYRESULT"}, {"redirect_count", "CURLINFO_REDIRECT_COUNT"},
            {"total_time", "CURLINFO_TOTAL_TIME"}, {"namelookup_time", "CURLINFO_NAMELOOKUP_TIME"},
            {"connect_time", "CURLINFO_CONNECT_TIME"}, {"pretransfer_time", "CURLINFO_PRETRANSFER_TIME"},
            {"size_upload", "CURLINFO_SIZE_UPLOAD"}, {"size_download", "CURLINFO_SIZE_DOWNLOAD"},
            {"speed_download", "CURLINFO_SPEED_DOWNLOAD"}, {"speed_upload", "CURLINFO_SPEED_UPLOAD"},
            {"download_content_length", "CURLINFO_CONTENT_LENGTH_DOWNLOAD"},
            {"upload_content_length", "CURLINFO_CONTENT_LENGTH_UPLOAD"},
            {"starttransfer_time", "CURLINFO_STARTTRANSFER_TIME"}, {"redirect_time", "CURLINFO_REDIRECT_TIME"},
            {"redirect_url", "CURLINFO_REDIRECT_URL"}, {"primary_ip", "CURLINFO_PRIMARY_IP"},
            {"certinfo", "CURLINFO_CERTINFO"}, {"primary_port", "CURLINFO_PRIMARY_PORT"},
            {"local_ip", "CURLINFO_LOCAL_IP"}, {"local_port", "CURLINFO_LOCAL_PORT"},
            {"http_version", "CURLINFO_HTTP_VERSION"}, {"protocol", "CURLINFO_PROTOCOL"},
            {"ssl_verifyresult", "CURLINFO_PROXY_SSL_VERIFYRESULT"}, {"scheme", "CURLINFO_SCHEME"},
            {"appconnect_time_us", "CURLINFO_APPCONNECT_TIME_T"}, {"queue_time_us", "CURLINFO_QUEUE_TIME_T"},
            {"connect_time_us", "CURLINFO_CONNECT_TIME_T"}, {"namelookup_time_us", "CURLINFO_NAMELOOKUP_TIME_T"},
            {"pretransfer_time_us", "CURLINFO_PRETRANSFER_TIME_T"}, {"redirect_time_us", "CURLINFO_REDIRECT_TIME_T"},
            {"starttransfer_time_us", "CURLINFO_STARTTRANSFER_TIME_T"},
            {"posttransfer_time_us", "CURLINFO_POSTTRANSFER_TIME_T"}, {"total_time_us", "CURLINFO_TOTAL_TIME_T"},
            {"request_header", null}, {"effective_method", "CURLINFO_EFFECTIVE_METHOD"},
            {"capath", "CURLINFO_CAPATH"}, {"cainfo", "CURLINFO_CAINFO"}, {"used_proxy", "CURLINFO_USED_PROXY"},
            {"httpauth_used", "CURLINFO_HTTPAUTH_USED"}, {"proxyauth_used", "CURLINFO_PROXYAUTH_USED"},
            {"conn_id", "CURLINFO_CONN_ID"},
    };

    @TruffleBoundary
    private static Object curlGetinfo(PhpContext context, Object[] a) {
        arity(a, "curl_getinfo", 1, 2);
        Easy ch = easy(a, 0, "curl_getinfo");
        Long option = Args.has(a, 1) ? longArg(context, a, 1, "curl_getinfo", "option", true) : null;
        state(context);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment into = arena.allocate(8, 8);
            if (option == null) {
                PhpArray info = new PhpArray();
                for (String[] field : INFO_FIELDS) {
                    if (field[1] == null) {
                        if (ch.headerOut != null) {
                            info.put(Str.of(field[0]), Str.of(ch.headerOut));
                        }
                        continue;
                    }
                    int id = (int) value(field[1]);
                    into.fill((byte) 0);
                    if (CurlNative.getinfo(ch.cp, id, into) != CURLE_OK) {
                        continue;
                    }
                    if (id == INFO_CERTINFO) {
                        info.put(Str.of(field[0]), certinfo(context, into.get(CurlNative.PTR, 0)));
                        continue;
                    }
                    Object fieldValue = infoValue(id, into);
                    if (fieldValue == null) {
                        // CAAS gives "" for a NULL string; content_type is null then
                        fieldValue = id == INFO_CONTENT_TYPE ? PhpNull.NULL : Str.EMPTY;
                    }
                    info.put(Str.of(field[0]), fieldValue);
                }
                return info;
            }
            long requested = option;
            if (requested == INFO_HEADER_OUT) {
                return ch.headerOut != null ? (Object) Str.of(ch.headerOut) : Boolean.FALSE;
            }
            if (requested == INFO_CERTINFO) {
                if (CurlNative.getinfo(ch.cp, INFO_CERTINFO, into) != CURLE_OK) {
                    return Boolean.FALSE;
                }
                return certinfo(context, into.get(CurlNative.PTR, 0));
            }
            if (requested == INFO_PRIVATE) {
                return ch.privateData != null ? ch.privateData : Boolean.FALSE;
            }
            int id = (int) requested;
            switch (id & CurlNative.INFO_TYPEMASK) {
                case CurlNative.INFO_STRING, CurlNative.INFO_LONG, CurlNative.INFO_DOUBLE, CurlNative.INFO_OFF_T -> {
                    if (CurlNative.getinfo(ch.cp, id, into) != CURLE_OK) {
                        return Boolean.FALSE;
                    }
                    Object result = infoValue(id, into);
                    return result == null ? Boolean.FALSE : result;
                }
                case CurlNative.INFO_SLIST -> {
                    if (CurlNative.getinfo(ch.cp, id, into) != CURLE_OK) {
                        return Boolean.FALSE;
                    }
                    MemorySegment slist = into.get(CurlNative.PTR, 0);
                    PhpArray result = new PhpArray();
                    for (byte[] entry : CurlNative.slistStrings(slist)) {
                        result.append(Str.of(entry));
                    }
                    CurlNative.slistFreeAll(slist);
                    return result;
                }
                default -> {
                    return Boolean.FALSE;
                }
            }
        }
    }

    /** A CURLINFO's value from getinfo's out-parameter by its type; null for a NULL string. */
    private static Object infoValue(int id, MemorySegment into) {
        return switch (id & CurlNative.INFO_TYPEMASK) {
            case CurlNative.INFO_STRING -> {
                byte[] string = CurlNative.cBytes(into.get(CurlNative.PTR, 0));
                yield string == null ? null : Str.of(string);
            }
            case CurlNative.INFO_LONG -> CurlNative.readLong(into);
            case CurlNative.INFO_DOUBLE -> into.get(ValueLayout.JAVA_DOUBLE, 0);
            default -> into.get(ValueLayout.JAVA_LONG, 0);
        };
    }

    /** create_certinfo: per certificate, its "key:value" lines as an array. */
    private static PhpArray certinfo(PhpContext context, MemorySegment pointer) {
        PhpArray list = new PhpArray();
        if (pointer.equals(MemorySegment.NULL)) {
            return list;
        }
        MemorySegment ci = pointer.reinterpret(16);
        int count = ci.get(ValueLayout.JAVA_INT, 0);
        MemorySegment certs = ci.get(CurlNative.PTR, 8).reinterpret(8L * count);
        for (int i = 0; i < count; i++) {
            PhpArray certhash = new PhpArray();
            for (byte[] line : CurlNative.slistStrings(certs.get(CurlNative.PTR, 8L * i))) {
                // the key is what precedes ':' in the first 63 bytes
                int limit = Math.min(line.length, 63);
                int colon = -1;
                for (int j = 0; j < limit; j++) {
                    if (line[j] == ':') {
                        colon = j;
                        break;
                    }
                }
                if (colon < 0) {
                    warn(context, "curl_getinfo", "Could not extract hash key from certificate info");
                    continue;
                }
                certhash.put(ArrayKey.of(Str.of(line, 0, colon)),
                        Str.of(line, colon + 1, line.length - colon - 1));
            }
            list.append(certhash);
        }
        return list;
    }

    // ---- curl_version ----

    private static final String[] FEATURES = {"AsynchDNS", "CURL_VERSION_ASYNCHDNS", "CharConv", "CURL_VERSION_CONV",
            "Debug", "CURL_VERSION_DEBUG", "GSS-Negotiate", "CURL_VERSION_GSSNEGOTIATE", "IDN", "CURL_VERSION_IDN",
            "IPv6", "CURL_VERSION_IPV6", "krb4", "CURL_VERSION_KERBEROS4", "Largefile", "CURL_VERSION_LARGEFILE",
            "libz", "CURL_VERSION_LIBZ", "NTLM", "CURL_VERSION_NTLM", "NTLMWB", "CURL_VERSION_NTLM_WB",
            "SPNEGO", "CURL_VERSION_SPNEGO", "SSL", "CURL_VERSION_SSL", "SSPI", "CURL_VERSION_SSPI",
            "TLS-SRP", "CURL_VERSION_TLSAUTH_SRP", "HTTP2", "CURL_VERSION_HTTP2", "GSSAPI", "CURL_VERSION_GSSAPI",
            "KERBEROS5", "CURL_VERSION_KERBEROS5", "UNIX_SOCKETS", "CURL_VERSION_UNIX_SOCKETS",
            "PSL", "CURL_VERSION_PSL", "HTTPS_PROXY", "CURL_VERSION_HTTPS_PROXY", "MULTI_SSL",
            "CURL_VERSION_MULTI_SSL", "BROTLI", "CURL_VERSION_BROTLI", "ALTSVC", "CURL_VERSION_ALTSVC",
            "HTTP3", "CURL_VERSION_HTTP3", "UNICODE", "CURL_VERSION_UNICODE", "ZSTD", "CURL_VERSION_ZSTD",
            "HSTS", "CURL_VERSION_HSTS", "GSASL", "CURL_VERSION_GSASL"};

    @TruffleBoundary
    private static Object curlVersion(PhpContext context, Object[] a) {
        arity(a, "curl_version", 0, 0);
        MemorySegment d = CurlNative.versionInfo();
        if (d == null) {
            return Boolean.FALSE;
        }
        PhpArray result = new PhpArray();
        int age = d.get(ValueLayout.JAVA_INT, CurlNative.VERSION_AGE);
        int features = d.get(ValueLayout.JAVA_INT, CurlNative.VERSION_FEATURES);
        result.put(Str.of("version_number"), (long) d.get(ValueLayout.JAVA_INT, CurlNative.VERSION_NUM));
        result.put(Str.of("age"), (long) age);
        result.put(Str.of("features"), (long) features);
        PhpArray featureList = new PhpArray();
        for (int i = 0; i < FEATURES.length; i += 2) {
            featureList.put(Str.of(FEATURES[i]), (features & value(FEATURES[i + 1])) != 0);
        }
        result.put(Str.of("feature_list"), featureList);
        result.put(Str.of("ssl_version_number"), CurlNative.readLong(d.asSlice(CurlNative.VERSION_SSL_VERSION_NUM)));
        result.put(Str.of("version"), versionString(d, CurlNative.VERSION_VERSION));
        result.put(Str.of("host"), versionString(d, CurlNative.VERSION_HOST));
        result.put(Str.of("ssl_version"), versionString(d, CurlNative.VERSION_SSL_VERSION));
        result.put(Str.of("libz_version"), versionString(d, CurlNative.VERSION_LIBZ_VERSION));
        PhpArray protocols = new PhpArray();
        MemorySegment list = d.get(CurlNative.PTR, CurlNative.VERSION_PROTOCOLS).reinterpret(Long.MAX_VALUE);
        for (long i = 0; ; i += 8) {
            MemorySegment protocol = list.get(CurlNative.PTR, i);
            if (protocol.equals(MemorySegment.NULL)) {
                break;
            }
            protocols.append(Str.of(CurlNative.cBytes(protocol)));
        }
        result.put(Str.of("protocols"), protocols);
        if (age >= 1) {
            result.put(Str.of("ares"), versionString(d, CurlNative.VERSION_ARES));
            result.put(Str.of("ares_num"), (long) d.get(ValueLayout.JAVA_INT, CurlNative.VERSION_ARES_NUM));
        }
        if (age >= 2) {
            result.put(Str.of("libidn"), versionString(d, CurlNative.VERSION_LIBIDN));
        }
        if (age >= 3) {
            result.put(Str.of("iconv_ver_num"), (long) d.get(ValueLayout.JAVA_INT, CurlNative.VERSION_ICONV_VER_NUM));
            result.put(Str.of("libssh_version"), versionString(d, CurlNative.VERSION_LIBSSH_VERSION));
        }
        if (age >= 4) {
            result.put(Str.of("brotli_ver_num"),
                    (long) d.get(ValueLayout.JAVA_INT, CurlNative.VERSION_BROTLI_VER_NUM));
            result.put(Str.of("brotli_version"), versionString(d, CurlNative.VERSION_BROTLI_VERSION));
        }
        return result;
    }

    /** CAAS: the string field, "" for NULL. */
    private static TruffleString versionString(MemorySegment d, long offset) {
        byte[] string = CurlNative.cBytes(d.get(CurlNative.PTR, offset));
        return string == null ? Str.EMPTY : Str.of(string);
    }

    // ---- multi.c ----

    @TruffleBoundary
    private static Object curlMultiInit(PhpContext context, Object[] a) {
        arity(a, "curl_multi_init", 0, 0);
        State state = state(context);
        MemorySegment handle = CurlNative.multiInit();
        if (handle.equals(MemorySegment.NULL)) {
            throw new PhpError("curl_multi_init(): Could not initialize a new cURL multi handle");
        }
        PhpObject object = context.getClass("CurlMultiHandle").instantiateInternal();
        Multi mh = new Multi();
        mh.multi = handle;
        mh.state = state;
        object.setInternal(mh);
        MULTIS.put(mh.id, new WeakReference<>(mh));
        // the native part is the Multi itself, which holds no PHP values but its handles' objects: the cleaner
        // watches the PHP object
        state.track(object, mh);
        return object;
    }

    @TruffleBoundary
    private static Object curlMultiAddHandle(PhpContext context, Object[] a) {
        arity(a, "curl_multi_add_handle", 2, 2);
        Multi mh = multi(a, "curl_multi_add_handle");
        PhpObject object = handleOf(a, 1, "curl_multi_add_handle", "handle", "CurlHandle");
        Easy ch = (Easy) object.getInternal();
        State state = state(context);
        state.activeFunction = "curl_multi_add_handle";
        verifyHandlers(context, ch, true);
        cleanupHandle(ch);
        int error = CurlNative.multiAddHandle(mh.multi, ch.cp);
        mh.errNo = error;
        if (error == CurlNative.CURLM_OK) {
            mh.easyh.add(object);
        }
        return (long) error;
    }

    @TruffleBoundary
    private static Object curlMultiRemoveHandle(PhpContext context, Object[] a) {
        arity(a, "curl_multi_remove_handle", 2, 2);
        Multi mh = multi(a, "curl_multi_remove_handle");
        PhpObject object = handleOf(a, 1, "curl_multi_remove_handle", "handle", "CurlHandle");
        Easy ch = (Easy) object.getInternal();
        State state = state(context);
        int error = CurlNative.multiRemoveHandle(mh.multi, ch.cp);
        mh.errNo = error;
        if (error == CurlNative.CURLM_OK) {
            mh.easyh.remove(object);
        }
        rethrowPending(state);
        return (long) error;
    }

    @TruffleBoundary
    private static Object curlMultiGetHandles(PhpContext context, Object[] a) {
        arity(a, "curl_multi_get_handles", 1, 1);
        Multi mh = multi(a, "curl_multi_get_handles");
        PhpArray handles = new PhpArray();
        for (PhpObject object : mh.easyh) {
            handles.append(object);
        }
        return handles;
    }

    @TruffleBoundary
    private static Object curlMultiSelect(PhpContext context, Object[] a) {
        arity(a, "curl_multi_select", 1, 2);
        Multi mh = multi(a, "curl_multi_select");
        double timeout = Args.has(a, 1) ? doubleArg(context, a, 1, "curl_multi_select", "timeout") : 1.0;
        state(context);
        if (!(timeout >= 0.0 && timeout <= Integer.MAX_VALUE / 1000.0)) {
            throw new PhpError("ValueError", "curl_multi_select(): Argument #2 ($timeout) must be between 0 and "
                    + String.format(Locale.ROOT, "%f", Integer.MAX_VALUE / 1000.0));
        }
        int[] numfds = new int[1];
        int error = CurlNative.multiWait(mh.multi, (int) (timeout * 1000.0), numfds);
        if (error != CurlNative.CURLM_OK) {
            mh.errNo = error;
            return -1L;
        }
        return (long) numfds[0];
    }

    @TruffleBoundary
    private static Object curlMultiExec(PhpContext context, Object[] a) {
        arity(a, "curl_multi_exec", 2, 2);
        Multi mh = multi(a, "curl_multi_exec");
        PhpRef stillRunning = Args.ref(a, 1);
        State state = state(context);
        state.activeFunction = "curl_multi_exec";
        for (PhpObject object : new ArrayList<>(mh.easyh)) {
            verifyHandlers(context, (Easy) object.getInternal(), true);
        }
        int[] running = {(int) Convert.toLong(PhpRef.deref(stillRunning.get()))};
        int error = CurlNative.multiPerform(mh.multi, running);
        stillRunning.set((long) running[0]);
        mh.errNo = error;
        rethrowPending(state);
        return (long) error;
    }

    @TruffleBoundary
    private static Object curlMultiGetcontent(PhpContext context, Object[] a) {
        arity(a, "curl_multi_getcontent", 1, 1);
        Easy ch = easy(a, 0, "curl_multi_getcontent");
        if (ch.write.method == RETURN) {
            return ch.write.buf == null ? Str.EMPTY : Str.of(ch.write.buf.toByteArray());
        }
        return PhpNull.NULL;
    }

    @TruffleBoundary
    private static Object curlMultiInfoRead(PhpContext context, Object[] a) {
        arity(a, "curl_multi_info_read", 1, 2);
        Multi mh = multi(a, "curl_multi_info_read");
        state(context);
        int[] queued = new int[1];
        CurlNative.Message message = CurlNative.multiInfoRead(mh.multi, queued);
        if (message == null) {
            return Boolean.FALSE;
        }
        if (Args.has(a, 1)) {
            Args.ref(a, 1).set((long) queued[0]);
        }
        PhpArray result = new PhpArray();
        result.put(Str.of("msg"), (long) message.msg());
        result.put(Str.of("result"), (long) message.result());
        PhpObject object = findEasy(mh, message.easy());
        if (object != null) {
            // we must save result to be able to read error message
            ((Easy) object.getInternal()).errNo = message.result();
            result.put(Str.of("handle"), object);
        }
        return result;
    }

    /** _php_curl_multi_find_easy_handle. */
    private static PhpObject findEasy(Multi mh, MemorySegment easy) {
        for (PhpObject object : mh.easyh) {
            MemorySegment cp = ((Easy) object.getInternal()).cp;
            if (cp != null && cp.equals(easy)) {
                return object;
            }
        }
        return null;
    }

    @TruffleBoundary
    private static Object curlMultiClose(PhpContext context, Object[] a) {
        arity(a, "curl_multi_close", 1, 1);
        Multi mh = multi(a, "curl_multi_close");
        State state = state(context);
        state.activeFunction = "curl_multi_close";
        for (PhpObject object : mh.easyh) {
            Easy ch = (Easy) object.getInternal();
            verifyHandlers(context, ch, true);
            CurlNative.multiRemoveHandle(mh.multi, ch.cp);
        }
        mh.easyh.clear();
        rethrowPending(state);
        return PhpNull.NULL;
    }

    @TruffleBoundary
    private static Object curlMultiErrno(PhpContext context, Object[] a) {
        arity(a, "curl_multi_errno", 1, 1);
        return (long) multi(a, "curl_multi_errno").errNo;
    }

    private static final Set<Long> MULTI_LONG_OPTIONS = options("CURLMOPT_PIPELINING", "CURLMOPT_MAXCONNECTS",
            "CURLMOPT_CHUNK_LENGTH_PENALTY_SIZE", "CURLMOPT_CONTENT_LENGTH_PENALTY_SIZE",
            "CURLMOPT_MAX_HOST_CONNECTIONS", "CURLMOPT_MAX_PIPELINE_LENGTH", "CURLMOPT_MAX_TOTAL_CONNECTIONS",
            "CURLMOPT_MAX_CONCURRENT_STREAMS");
    private static final long MOPT_PIPELINING = value("CURLMOPT_PIPELINING");

    @TruffleBoundary
    private static Object curlMultiSetopt(PhpContext context, Object[] a) {
        arity(a, "curl_multi_setopt", 3, 3);
        Multi mh = multi(a, "curl_multi_setopt");
        long option = longArg(context, a, 1, "curl_multi_setopt", "option", false);
        Object value = Args.get(a, 2);
        state(context);
        int error;
        if (MULTI_LONG_OPTIONS.contains(option)) {
            long lval = Convert.toLong(value);
            if (option == MOPT_PIPELINING && (lval & 1) != 0) {
                warn(context, "curl_multi_setopt", "CURLPIPE_HTTP1 is no longer supported");
            }
            error = CurlNative.multiSetoptLong(mh.multi, (int) option, lval);
        } else if (option == CurlNative.MOPT_PUSHFUNCTION) {
            mh.serverPush = null;
            Callables.Target target = Callables.resolve(context, value);
            if (target == null) {
                throw new PhpError("TypeError", "curl_multi_setopt(): Argument #2 ($option) must be a valid callback "
                        + "for option CURLMOPT_PUSHFUNCTION, " + Callables.invalidReason(context, value));
            }
            mh.serverPush = target;
            error = CurlNative.multiSetoptPointer(mh.multi, CurlNative.MOPT_PUSHFUNCTION,
                    CurlNative.Callbacks.PUSH);
            if (error != CurlNative.CURLM_OK) {
                return Boolean.FALSE;
            }
            error = CurlNative.multiSetoptPointer(mh.multi, CurlNative.MOPT_PUSHDATA,
                    MemorySegment.ofAddress(mh.id));
        } else {
            mh.errNo = CurlNative.CURLM_UNKNOWN_OPTION;
            throw new PhpError("ValueError", "curl_multi_setopt(): Argument #2 ($option) is not a valid cURL multi "
                    + "option");
        }
        mh.errNo = error;
        return error == CurlNative.CURLM_OK;
    }

    /** _php_server_push_callback: a pushed stream becomes a CurlHandle the callable accepts or denies. */
    private static int serverPush(Multi mh, MemorySegment parentCp, MemorySegment easy, long numHeaders,
                                  MemorySegment pushHeaders) {
        int deny = (int) value("CURL_PUSH_DENY");
        PhpObject parentObject = findEasy(mh, parentCp);
        if (parentObject == null || mh.serverPush == null) {
            return deny;
        }
        PhpContext context = PhpContext.get(null);
        Easy parent = (Easy) parentObject.getInternal();
        Easy ch = newEasy(context, mh.state, easy);
        setupEasyCopyHandlers(ch, parent);
        PhpArray headers = new PhpArray();
        for (long i = 0; i < numHeaders; i++) {
            byte[] header = CurlNative.pushHeader(pushHeaders, i);
            headers.append(header == null ? PhpNull.NULL : Str.of(header));
        }
        Object result = callPhp(mh.state, mh.serverPush, parentObject, ch.object, headers);
        if (result != null && Convert.toLong(result) != deny) {
            mh.easyh.add(ch.object);
            return (int) value("CURL_PUSH_OK");
        }
        // libcurl frees a denied handle (php-src also leaves it alone when the callable threw: no double free)
        ch.setCp(null);
        return deny;
    }

    // ---- share.c ----

    @TruffleBoundary
    private static Object curlShareInit(PhpContext context, Object[] a) {
        arity(a, "curl_share_init", 0, 0);
        State state = state(context);
        PhpObject object = context.getClass("CurlShareHandle").instantiateInternal();
        Share sh = new Share(CurlNative.shareInit());
        object.setInternal(sh);
        state.track(object, new ShareNative(sh.share));
        return object;
    }

    @TruffleBoundary
    private static Object curlShareClose(PhpContext context, Object[] a) {
        deprecatedFunction(context, "curl_share_close");
        arity(a, "curl_share_close", 1, 1);
        share(a, "curl_share_close");
        return PhpNull.NULL;
    }

    @TruffleBoundary
    private static Object curlShareSetopt(PhpContext context, Object[] a) {
        arity(a, "curl_share_setopt", 3, 3);
        Share sh = share(a, "curl_share_setopt");
        long option = longArg(context, a, 1, "curl_share_setopt", "option", false);
        Object value = Args.get(a, 2);
        state(context);
        int error;
        if (option == value("CURLSHOPT_SHARE") || option == value("CURLSHOPT_UNSHARE")) {
            error = CurlNative.shareSetoptLong(sh.share, (int) option, Convert.toLong(value));
        } else {
            sh.errNo = CurlNative.CURLSHE_BAD_OPTION;
            throw new PhpError("ValueError", "curl_share_setopt(): Argument #2 ($option) is not a valid cURL share "
                    + "option");
        }
        sh.errNo = error;
        return error == CurlNative.CURLSHE_OK;
    }

    @TruffleBoundary
    private static Object curlShareErrno(PhpContext context, Object[] a) {
        arity(a, "curl_share_errno", 1, 1);
        return (long) share(a, "curl_share_errno").errNo;
    }

    /**
     * curl_share_init_persistent's handles, by the set of options: module globals, which ZTS php-src keeps per
     * thread, as here; they live as long as the thread.
     */
    private static final ThreadLocal<Map<Long, MemorySegment>> PERSISTENT_SHARES =
            ThreadLocal.withInitial(HashMap::new);

    @TruffleBoundary
    private static Object curlShareInitPersistent(PhpContext context, Object[] a) {
        arity(a, "curl_share_init_persistent", 1, 1);
        PhpArray options = arrayArg(a, 0, "curl_share_init_persistent", "share_options");
        state(context);
        if (options.size() == 0) {
            throw new PhpError("ValueError", "curl_share_init_persistent(): Argument #1 ($share_options) must not be "
                    + "empty");
        }
        long dns = value("CURL_LOCK_DATA_DNS");
        long ssl = value("CURL_LOCK_DATA_SSL_SESSION");
        long connect = value("CURL_LOCK_DATA_CONNECT");
        long psl = value("CURL_LOCK_DATA_PSL");
        long persistentId = 0;
        for (int p = options.firstPosition(); p >= 0; p = options.nextPosition(p)) {
            Object entry = PhpRef.deref(options.valueAt(p));
            Long option = tryGetLong(context, entry);
            if (option == null) {
                throw new PhpError("TypeError", "curl_share_init_persistent(): Argument #1 ($share_options) must "
                        + "contain only int values, " + valueName(entry) + " given");
            }
            if (option == value("CURL_LOCK_DATA_COOKIE")) {
                throw new PhpError("ValueError", "curl_share_init_persistent(): Argument #1 ($share_options) must "
                        + "not contain CURL_LOCK_DATA_COOKIE because sharing cookies across PHP requests is unsafe");
            } else if (option == dns) {
                persistentId |= 1;
            } else if (option == ssl) {
                persistentId |= 1 << 1;
            } else if (option == connect) {
                persistentId |= 1 << 2;
            } else if (option == psl) {
                persistentId |= 1 << 3;
            } else {
                throw new PhpError("ValueError", "curl_share_init_persistent(): Argument #1 ($share_options) must "
                        + "contain only CURL_LOCK_DATA_* constants");
            }
        }
        PhpObject object = context.getClass("CurlSharePersistentHandle").instantiateInternal();
        PhpArray shareOptions = new PhpArray();
        long[] order = {dns, ssl, connect, psl};
        for (int bit = 0; bit < 4; bit++) {
            if ((persistentId & (1L << bit)) != 0) {
                shareOptions.append(order[bit]);
            }
        }
        shareOptions.addHolder();
        object.setProperty("options", shareOptions);
        Map<Long, MemorySegment> persisted = PERSISTENT_SHARES.get();
        MemorySegment handle = persisted.get(persistentId);
        if (handle == null) {
            handle = CurlNative.shareInit();
            for (int p = shareOptions.firstPosition(); p >= 0; p = shareOptions.nextPosition(p)) {
                int error = CurlNative.shareSetoptLong(handle, CurlNative.SHOPT_SHARE,
                        (Long) shareOptions.valueAt(p));
                if (error != CurlNative.CURLSHE_OK) {
                    CurlNative.shareCleanup(handle);
                    throw new PhpError("Exception", "Could not construct persistent cURL share handle: "
                            + Str.java(Str.of(CurlNative.shareStrerror(error))));
                }
            }
            persisted.put(persistentId, handle);
        }
        object.setInternal(new Share(handle));
        return object;
    }

    /** zval_try_get_long: an int from a scalar, or null when it has none. */
    private static Long tryGetLong(PhpContext context, Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value == PhpNull.NULL) {
            return 0L;
        }
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (value instanceof Double d) {
            if (!(d >= -9.223372036854775808E18 && d < 9.223372036854775807E18)) {
                return null;
            }
            long l = (long) (double) d;
            if (l != d) {
                ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of("Implicit conversion from float "
                        + Convert.doubleToRepr(d) + " to int loses precision"), SourceLocation.current());
            }
            return l;
        }
        if (value instanceof TruffleString s) {
            Object number = Convert.numericValue(s);
            if (number == null) {
                Object leading = Convert.parseNumber(Str.readBytes(s), true);
                if (leading == null) {
                    return null;
                }
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("A non-numeric value encountered"),
                        SourceLocation.current());
                number = leading;
            }
            return number instanceof Long l ? l : Convert.doubleToLong((Double) number);
        }
        return null;
    }

    // ---- curl_file.c ----

    /** curlfile_ctor: CURLFile's properties from (filename, mime type, posted filename). */
    private static void curlFileConstruct(PhpContext context, PhpObject file, Object[] a, String function) {
        arity(a, function, 1, 3);
        TruffleString filename = pathArg(context, a, 0, function, "filename");
        TruffleString mime = Args.has(a, 1) ? stringArg(context, a, 1, function, "mime_type", true) : null;
        TruffleString postname = Args.has(a, 2) ? stringArg(context, a, 2, function, "posted_filename", true)
                : null;
        file.setProperty("name", filename);
        if (mime != null) {
            file.setProperty("mime", mime);
        }
        if (postname != null) {
            file.setProperty("postname", postname);
        }
    }

    @TruffleBoundary
    private static Object curlFileCreate(PhpContext context, Object[] a) {
        PhpObject file = context.getClass("CURLFile").instantiate();
        curlFileConstruct(context, file, a, "curl_file_create");
        return file;
    }

    @TruffleBoundary
    private static Object curlFileProperty(Object[] a, String method, String name) {
        arity(a, "CURLFile::" + method, 0, 0);
        Object value = PhpRef.deref(((PhpObject) a[PhpFunction.ARG_THIS]).getProperty(name));
        return value == null ? PhpNull.NULL : value;
    }

    @TruffleBoundary
    private static Object curlFileSetProperty(Object[] a, String method, String name, String parameter) {
        String function = "CURLFile::" + method;
        arity(a, function, 1, 1);
        TruffleString value = stringArg(PhpContext.get(null), a, 0, function, parameter, false);
        ((PhpObject) a[PhpFunction.ARG_THIS]).setProperty(name, value);
        return PhpNull.NULL;
    }

    @TruffleBoundary
    private static Object curlStringFileConstruct(PhpContext context, Object[] a) {
        String function = "CURLStringFile::__construct";
        arity(a, function, 2, 3);
        PhpObject self = (PhpObject) a[PhpFunction.ARG_THIS];
        TruffleString data = stringArg(context, a, 0, function, "data", false);
        TruffleString postname = stringArg(context, a, 1, function, "postname", false);
        TruffleString mime = Args.has(a, 2) ? stringArg(context, a, 2, function, "mime", false)
                : Str.of("application/octet-stream");
        self.setProperty("data", data);
        self.setProperty("postname", postname);
        self.setProperty("mime", mime);
        return PhpNull.NULL;
    }

    // ---- callbacks ----

    /**
     * php_curl_call_callback: the callable with in_callback set; null (an undefined return value) when it threw,
     * keeping the exception for the curl function to throw, or when an earlier one did.
     */
    private static Object callCallback(Easy ch, Callables.Target fcc, Object... args) {
        boolean wasInCallback = ch.inCallback;
        ch.inCallback = true;
        try {
            return callPhp(ch.state, fcc, args);
        } finally {
            ch.inCallback = wasInCallback;
        }
    }

    private static Object callPhp(State state, Callables.Target fcc, Object... args) {
        if (state.pending != null || state.closing) {
            return null;
        }
        try {
            Object result = PhpRef.deref(fcc.call(args));
            return result == null ? PhpNull.NULL : result;
        } catch (Throwable t) {
            state.pending = t;
            return null;
        }
    }

    private static Easy easyOf(MemorySegment userdata) {
        WeakReference<Easy> reference = EASIES.get(userdata.address());
        return reference == null ? null : reference.get();
    }

    private static Easy easyOrNull(MemorySegment userdata) {
        Easy ch = easyOf(userdata);
        return ch == null || ch.state.closing ? null : ch;
    }

    /** Writes a transfer's bytes to a PHP stream (fwrite to the stream's FILE*): the count written. */
    private static long writeStream(PhpResource resource, byte[] bytes) {
        if (resource == null || !(resource.getHandle() instanceof FileFunctions.Stream stream)) {
            return 0;
        }
        int written = stream.write(bytes);
        return Math.max(written, 0);
    }

    /** curl_write. */
    static long write(Easy ch, MemorySegment data, long length) {
        Write handler = ch.write;
        switch (handler.method) {
            case STDOUT -> PhpContext.get(null).write(Str.of(bytes(data, length)));
            case FILE -> {
                return writeStream(handler.stream, bytes(data, length));
            }
            case RETURN -> {
                if (length > 0) {
                    if (handler.buf == null) {
                        handler.buf = new ByteBuilder((int) Math.min(length * 2, 1 << 16));
                    }
                    handler.buf.write(bytes(data, length));
                }
            }
            case USER -> {
                Object result = callCallback(ch, handler.fcc, ch.object, Str.of(bytes(data, length)));
                if (result != null) {
                    verifyHandlers(PhpContext.get(null), ch, true);
                    length = Convert.toLong(result);
                }
            }
            default -> {
            }
        }
        return length;
    }

    /** curl_write_header. */
    static long writeHeader(Easy ch, MemorySegment data, long length) {
        Write handler = ch.writeHeader;
        switch (handler.method) {
            case STDOUT -> {
                // handle special case write when we're returning the entire transfer
                if (ch.write.method == RETURN && length > 0) {
                    if (ch.write.buf == null) {
                        ch.write.buf = new ByteBuilder();
                    }
                    ch.write.buf.write(bytes(data, length));
                } else {
                    PhpContext.get(null).write(Str.of(bytes(data, length)));
                }
            }
            case FILE -> {
                return writeStream(handler.stream, bytes(data, length));
            }
            case USER -> {
                Object result = callCallback(ch, handler.fcc, ch.object, Str.of(bytes(data, length)));
                if (result != null) {
                    verifyHandlers(PhpContext.get(null), ch, true);
                    length = Convert.toLong(result);
                }
            }
            case IGNORE -> {
                return length;
            }
            default -> {
                return -1;
            }
        }
        return length;
    }

    /** curl_read. */
    static long read(Easy ch, MemorySegment data, long size) {
        Read handler = ch.read;
        long length = 0;
        switch (handler.method) {
            case DIRECT -> {
                if (handler.stream != null && handler.stream.getHandle() instanceof FileFunctions.Stream stream) {
                    byte[] read = stream.read((int) Math.min(size, Integer.MAX_VALUE));
                    if (read != null) {
                        MemorySegment.copy(read, 0, data.reinterpret(size), ValueLayout.JAVA_BYTE, 0, read.length);
                        length = read.length;
                    }
                }
            }
            case USER -> {
                Object resource = handler.stream != null ? handler.stream : PhpNull.NULL;
                Object result = callCallback(ch, handler.fcc, ch.object, resource, (long) (int) size);
                if (result != null) {
                    verifyHandlers(PhpContext.get(null), ch, true);
                    if (result instanceof TruffleString string) {
                        byte[] bytes = Str.bytes(string);
                        length = Math.min(size, bytes.length);
                        MemorySegment.copy(bytes, 0, data.reinterpret(size), ValueLayout.JAVA_BYTE, 0, (int) length);
                    } else if (result instanceof Long l) {
                        length = l;
                    }
                }
            }
            default -> {
            }
        }
        return length;
    }

    private static byte[] bytes(MemorySegment data, long length) {
        return length <= 0 ? new byte[0] : data.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
    }

    private static byte[] cString(MemorySegment pointer) {
        byte[] bytes = CurlNative.cBytes(pointer);
        return bytes == null ? new byte[0] : bytes;
    }

    /**
     * The upcalls libcurl makes. Each finds its handle by the userdata id and catches everything: an exception may
     * not unwind through libcurl, so one is kept for the curl function to throw.
     */
    static final class Upcalls {
        private Upcalls() {
        }

        private static long failed(Easy ch, Throwable t, long result) {
            if (ch.state.pending == null) {
                ch.state.pending = t;
            }
            return result;
        }

        static long write(MemorySegment data, long size, long nmemb, MemorySegment userdata) {
            Easy ch = easyOrNull(userdata);
            if (ch == null) {
                return size * nmemb;
            }
            try {
                return CurlFunctions.write(ch, data, size * nmemb);
            } catch (Throwable t) {
                return failed(ch, t, 0);
            }
        }

        static long writeHeader(MemorySegment data, long size, long nmemb, MemorySegment userdata) {
            Easy ch = easyOrNull(userdata);
            if (ch == null) {
                return size * nmemb;
            }
            try {
                return CurlFunctions.writeHeader(ch, data, size * nmemb);
            } catch (Throwable t) {
                return failed(ch, t, 0);
            }
        }

        static long read(MemorySegment data, long size, long nmemb, MemorySegment userdata) {
            Easy ch = easyOrNull(userdata);
            if (ch == null) {
                return 0;
            }
            try {
                return CurlFunctions.read(ch, data, size * nmemb);
            } catch (Throwable t) {
                return failed(ch, t, CurlNative.READFUNC_ABORT);
            }
        }

        /** curl_progress. */
        static int progress(MemorySegment clientp, double dltotal, double dlnow, double ultotal, double ulnow) {
            Easy ch = easyOrNull(clientp);
            if (ch == null || ch.progress == null) {
                return 0;
            }
            try {
                Object result = callCallback(ch, ch.progress, ch.object, (long) dltotal, (long) dlnow,
                        (long) ultotal, (long) ulnow);
                if (result != null) {
                    verifyHandlers(PhpContext.get(null), ch, true);
                    if (Convert.toLong(result) != 0) {
                        return 1;
                    }
                }
                return 0;
            } catch (Throwable t) {
                return (int) failed(ch, t, 1);
            }
        }

        /** curl_xferinfo. */
        static int xferinfo(MemorySegment clientp, long dltotal, long dlnow, long ultotal, long ulnow) {
            Easy ch = easyOrNull(clientp);
            if (ch == null || ch.xferinfo == null) {
                return 0;
            }
            try {
                Object result = callCallback(ch, ch.xferinfo, ch.object, dltotal, dlnow, ultotal, ulnow);
                if (result != null) {
                    verifyHandlers(PhpContext.get(null), ch, true);
                    if (Convert.toLong(result) != 0) {
                        return 1;
                    }
                }
                return 0;
            } catch (Throwable t) {
                return (int) failed(ch, t, 1);
            }
        }

        /** curl_fnmatch. */
        static int fnmatch(MemorySegment ctx, MemorySegment pattern, MemorySegment string) {
            Easy ch = easyOrNull(ctx);
            if (ch == null || ch.fnmatch == null) {
                return CurlNative.FNMATCHFUNC_FAIL;
            }
            try {
                Object result = callCallback(ch, ch.fnmatch, ch.object, Str.of(cString(pattern)),
                        Str.of(cString(string)));
                if (result != null) {
                    verifyHandlers(PhpContext.get(null), ch, true);
                    return (int) Convert.toLong(result);
                }
                return CurlNative.FNMATCHFUNC_FAIL;
            } catch (Throwable t) {
                return (int) failed(ch, t, CurlNative.FNMATCHFUNC_FAIL);
            }
        }

        /**
         * curl_debug: keeps the request headers for CURLINFO_HEADER_OUT, and calls CURLOPT_DEBUGFUNCTION's
         * callable.
         */
        static int debug(MemorySegment handle, int type, MemorySegment data, long size, MemorySegment clientp) {
            Easy ch = easyOrNull(clientp);
            if (ch == null) {
                return 0;
            }
            try {
                if (type == INFO_HEADER_OUT) {
                    ch.headerOut = bytes(data, size);
                }
                if (ch.debug != null) {
                    callCallback(ch, ch.debug, ch.object, (long) type, Str.of(bytes(data, size)));
                }
                return 0;
            } catch (Throwable t) {
                return (int) failed(ch, t, 0);
            }
        }

        /** curl_prereqfunction. */
        static int prereq(MemorySegment clientp, MemorySegment primaryIp, MemorySegment localIp, int primaryPort,
                          int localPort) {
            Easy ch = easyOrNull(clientp);
            int ok = (int) value("CURL_PREREQFUNC_OK");
            if (ch == null || ch.prereq == null) {
                return ok;
            }
            try {
                Object result = callCallback(ch, ch.prereq, ch.object, Str.of(cString(primaryIp)),
                        Str.of(cString(localIp)), (long) primaryPort, (long) localPort);
                if (result == null) {
                    return ok;
                }
                verifyHandlers(PhpContext.get(null), ch, true);
                String message = "The CURLOPT_PREREQFUNCTION callback must return either CURL_PREREQFUNC_OK or "
                        + "CURL_PREREQFUNC_ABORT";
                if (result instanceof Long l) {
                    if (l == ok || l == value("CURL_PREREQFUNC_ABORT")) {
                        return (int) (long) l;
                    }
                    throw new PhpError("ValueError", message);
                }
                throw new PhpError("TypeError", message);
            } catch (Throwable t) {
                return (int) failed(ch, t, ok);
            }
        }

        /** curl_ssh_hostkeyfunction: a mismatch (cancelling the connection) unless the callable says otherwise. */
        static int sshHostkey(MemorySegment clientp, int keytype, MemorySegment key, long keylen) {
            Easy ch = easyOrNull(clientp);
            int mismatch = (int) value("CURLKHMATCH_MISMATCH");
            if (ch == null || ch.sshHostkey == null) {
                return mismatch;
            }
            try {
                Object result = callCallback(ch, ch.sshHostkey, ch.object, (long) keytype, Str.of(bytes(key, keylen)),
                        keylen);
                if (result == null) {
                    return mismatch;
                }
                verifyHandlers(PhpContext.get(null), ch, true);
                if (result instanceof Long l && (l == value("CURLKHMATCH_OK") || l == mismatch)) {
                    return (int) (long) l;
                }
                throw new PhpError("The CURLOPT_SSH_HOSTKEYFUNCTION callback must return either CURLKHMATCH_OK or "
                        + "CURLKHMATCH_MISMATCH");
            } catch (Throwable t) {
                return (int) failed(ch, t, mismatch);
            }
        }

        /** read_cb: a CURLFile part's bytes, its file opened again if a previous transfer closed it. */
        static long mimeRead(MemorySegment buffer, long size, long nitems, MemorySegment arg) {
            MimeSource source = MIME_SOURCES.get(arg.address());
            if (source == null) {
                return CurlNative.READFUNC_ABORT;
            }
            try {
                if (source.stream == null) {
                    PhpContext context = PhpContext.get(null);
                    source.stream = FileFunctions.openStream(context, new String(source.filename,
                            StandardCharsets.UTF_8), "rb");
                    if (source.stream == null) {
                        return CurlNative.READFUNC_ABORT;
                    }
                    if (source.stream instanceof FileFunctions.FileStream) {
                        context.builtinState().openStreams.add(source.stream);
                    }
                }
                long want = size * nitems;
                byte[] read = source.stream.read((int) Math.min(want, Integer.MAX_VALUE));
                if (read == null) {
                    source.close();
                    return CurlNative.READFUNC_ABORT;
                }
                MemorySegment.copy(read, 0, buffer.reinterpret(want), ValueLayout.JAVA_BYTE, 0, read.length);
                return read.length;
            } catch (Throwable t) {
                return CurlNative.READFUNC_ABORT;
            }
        }

        /** seek_cb. */
        static int mimeSeek(MemorySegment arg, long offset, int origin) {
            MimeSource source = MIME_SOURCES.get(arg.address());
            if (source == null || source.stream == null) {
                return CurlNative.SEEKFUNC_CANTSEEK;
            }
            try {
                return source.stream.seek(offset, origin) == 0 ? CurlNative.SEEKFUNC_OK : CurlNative.SEEKFUNC_CANTSEEK;
            } catch (Throwable t) {
                return CurlNative.SEEKFUNC_CANTSEEK;
            }
        }

        /** free_cb: the part's file closed. */
        static void mimeFree(MemorySegment arg) {
            MimeSource source = MIME_SOURCES.get(arg.address());
            if (source != null) {
                try {
                    source.close();
                } catch (Throwable t) {
                    // closing anyway
                }
            }
        }

        static int serverPush(MemorySegment parent, MemorySegment easy, long numHeaders, MemorySegment headers,
                              MemorySegment userp) {
            WeakReference<Multi> reference = MULTIS.get(userp.address());
            Multi mh = reference == null ? null : reference.get();
            int deny = (int) value("CURL_PUSH_DENY");
            if (mh == null || mh.state == null || mh.state.closing) {
                return deny;
            }
            try {
                return CurlFunctions.serverPush(mh, parent, easy, numHeaders, headers);
            } catch (Throwable t) {
                if (mh.state.pending == null) {
                    mh.state.pending = t;
                }
                return deny;
            }
        }
    }

    // ---- CURLOPT_STDERR: a FILE* over a PHP stream ----

    /**
     * What php_stream_cast(PHP_STREAM_AS_STDIO) gives libcurl for CURLOPT_STDERR: glibc's fopencookie over the
     * stream (as php-src makes for streams that aren't plain files), unbuffered, so libcurl's verbose text reaches the
     * stream as it is written.
     */
    static final class StderrFile {
        private static final Map<Long, FileFunctions.Stream> STREAMS = new ConcurrentHashMap<>();

        final long id = IDS.incrementAndGet();
        final MemorySegment fp;

        private StderrFile(MemorySegment fp) {
            this.fp = fp;
        }

        /** A FILE* writing to the stream; null where the C library has no fopencookie. */
        static StderrFile open(FileFunctions.Stream stream) {
            if (!Cookie.AVAILABLE) {
                return null;
            }
            long id = IDS.incrementAndGet();
            MemorySegment fp = Cookie.open(id);
            if (fp == null) {
                return null;
            }
            STREAMS.put(id, stream);
            StderrFile file = new StderrFile(fp);
            file.cookie = id;
            return file;
        }

        private long cookie;

        void close() {
            Cookie.close(fp);
            STREAMS.remove(cookie);
        }

        /** The cookie's write: the bytes to the PHP stream. */
        static long cookieWrite(MemorySegment cookie, MemorySegment buffer, long size) {
            FileFunctions.Stream stream = STREAMS.get(cookie.address());
            if (stream == null) {
                return -1;
            }
            try {
                int written = stream.write(bytes(buffer, size));
                return written < 0 ? -1 : written;
            } catch (Throwable t) {
                return -1;
            }
        }

        /** glibc's fopencookie, setvbuf and fclose, bound when the C library has them. */
        private static final class Cookie {
            static final boolean AVAILABLE;
            private static MethodHandle fopencookie;
            private static MethodHandle setvbuf;
            private static MethodHandle fclose;
            private static MemorySegment functions;

            static {
                boolean available = false;
                try {
                    Linker linker = Linker.nativeLinker();
                    SymbolLookup libc = linker.defaultLookup();
                    Optional<MemorySegment> open = libc.find("fopencookie");
                    if (open.isPresent()) {
                        StructLayout io = MemoryLayout.structLayout(
                                CurlNative.PTR.withName("read"), CurlNative.PTR.withName("write"),
                                CurlNative.PTR.withName("seek"), CurlNative.PTR.withName("close"));
                        fopencookie = linker.downcallHandle(open.get(), FunctionDescriptor.of(
                                CurlNative.PTR, CurlNative.PTR, CurlNative.PTR, io));
                        setvbuf = linker.downcallHandle(libc.find("setvbuf").orElseThrow(),
                                FunctionDescriptor.of(CurlNative.INT, CurlNative.PTR, CurlNative.PTR,
                                        CurlNative.INT, CurlNative.SIZE_T));
                        fclose = linker.downcallHandle(libc.find("fclose").orElseThrow(),
                                FunctionDescriptor.of(CurlNative.INT, CurlNative.PTR));
                        FunctionDescriptor writeType = FunctionDescriptor.of(
                                ValueLayout.JAVA_LONG, CurlNative.PTR, CurlNative.PTR, CurlNative.SIZE_T);
                        MemorySegment write = linker.upcallStub(MethodHandles.lookup().findStatic(
                                StderrFile.class, "cookieWrite", writeType.toMethodType()), writeType, Arena.global());
                        functions = Arena.global().allocate(io);
                        functions.set(CurlNative.PTR, 8, write);
                        available = true;
                    }
                } catch (Throwable t) {
                    available = false;
                }
                AVAILABLE = available;
            }

            static MemorySegment open(long id) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment fp = (MemorySegment) fopencookie.invokeExact(MemorySegment.ofAddress(id),
                            CurlNative.cString(arena, "w".getBytes(StandardCharsets.US_ASCII)),
                            functions);
                    if (fp.equals(MemorySegment.NULL)) {
                        return null;
                    }
                    int ignored = (int) setvbuf.invokeExact(fp, MemorySegment.NULL, 2, 0L); // _IONBF
                    return fp;
                } catch (Throwable t) {
                    throw CurlNative.failure(t);
                }
            }

            static void close(MemorySegment fp) {
                try {
                    int ignored = (int) fclose.invokeExact(fp);
                } catch (Throwable t) {
                    throw CurlNative.failure(t);
                }
            }
        }
    }
}
