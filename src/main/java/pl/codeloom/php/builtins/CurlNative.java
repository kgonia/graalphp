package pl.codeloom.php.builtins;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * libcurl, bound by FFM as ext/curl links it (D13): the easy, multi and share interfaces, mime, slists, and the
 * callbacks as upcalls into {@link CurlFunctions}. Set the library with -Dphp.curl=<path>; the default is the
 * platform's name (Git's mingw64 build on Windows). Without the library the curl extension isn't loaded.
 */
final class CurlNative {
    private CurlNative() {
    }

    /**
     * Whether libcurl loaded: ext/curl's functions, classes and constants exist only then. A library named with
     * -Dphp.curl that doesn't load is reported.
     */
    static boolean available() {
        try {
            return Library.GLOBAL_INIT_RESULT == 0;
        } catch (LinkageError | RuntimeException e) {
            if (System.getProperty("php.curl") != null) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                System.err.println("warning: libcurl (-Dphp.curl) not loaded, no curl extension: " + cause);
            }
            return false;
        }
    }

    // ---- libcurl's ABI: values from curl/curl.h that PHP doesn't expose as constants ----

    static final int CURLE_OK = 0;
    static final int CURLE_UNKNOWN_OPTION = 48;
    static final int CURLE_OUT_OF_MEMORY = 27;
    static final int CURLM_OK = 0;
    static final int CURLM_UNKNOWN_OPTION = 6;
    static final int CURLSHE_OK = 0;
    static final int CURLSHE_BAD_OPTION = 1;
    /** CURL_ERROR_SIZE: the error buffer's size (php_curl's err.str has one more byte). */
    static final int ERROR_SIZE = 256;

    static final int OPT_WRITEDATA = 10001;
    static final int OPT_READDATA = 10009;
    static final int OPT_ERRORBUFFER = 10010;
    static final int OPT_POSTFIELDS = 10015;
    static final int OPT_HEADERDATA = 10029;
    static final int OPT_STDERR = 10037;
    static final int OPT_PROGRESSDATA = 10057;
    static final int OPT_XFERINFODATA = 10057;
    static final int OPT_DEBUGDATA = 10095;
    static final int OPT_SHARE = 10100;
    static final int OPT_COPYPOSTFIELDS = 10165;
    static final int OPT_FNMATCH_DATA = 10202;
    static final int OPT_MIMEPOST = 10269;
    static final int OPT_PREREQDATA = 10313;
    static final int OPT_SSH_HOSTKEYDATA = 10317;
    static final int OPT_WRITEFUNCTION = 20011;
    static final int OPT_READFUNCTION = 20012;
    static final int OPT_PROGRESSFUNCTION = 20056;
    static final int OPT_HEADERFUNCTION = 20079;
    static final int OPT_DEBUGFUNCTION = 20094;
    static final int OPT_FNMATCH_FUNCTION = 20200;
    static final int OPT_XFERINFOFUNCTION = 20219;
    static final int OPT_PREREQFUNCTION = 20312;
    static final int OPT_SSH_HOSTKEYFUNCTION = 20316;
    static final int OPT_POSTFIELDSIZE = 60;
    static final int OPT_NOPROGRESS = 43;
    static final int OPT_VERBOSE = 41;
    static final int OPT_DNS_CACHE_TIMEOUT = 92;
    static final int OPT_MAXREDIRS = 68;
    static final int OPT_NOSIGNAL = 99;
    static final int OPT_CAINFO = 10065;
    static final int OPT_URL = 10002;
    static final int OPT_PROTOCOLS = 181;
    static final int MOPT_PUSHFUNCTION = 20014;
    static final int MOPT_PUSHDATA = 10015;
    static final int SHOPT_SHARE = 1;

    /** CURLPROTO_ALL and CURLPROTO_FILE, for open_basedir's restriction of file://. */
    static final long PROTO_ALL = ~0L & 0xFFFFFFFFL;
    static final long PROTO_FILE = 1L << 10;
    /** CURL_READFUNC_ABORT, CURL_SEEKFUNC_OK and CURL_SEEKFUNC_CANTSEEK, for CURLFile's mime callbacks. */
    static final long READFUNC_ABORT = 0x10000000L;
    static final int SEEKFUNC_OK = 0;
    static final int SEEKFUNC_CANTSEEK = 2;
    /** CURL_FNMATCHFUNC_FAIL. */
    static final int FNMATCHFUNC_FAIL = 2;
    /** CURL_BLOB_COPY. */
    static final int BLOB_COPY = 1;
    /** CURLINFO_STRING ... CURLINFO_OFF_T, and the mask of a CURLINFO's type. */
    static final int INFO_STRING = 0x100000;
    static final int INFO_LONG = 0x200000;
    static final int INFO_DOUBLE = 0x300000;
    static final int INFO_SLIST = 0x400000;
    static final int INFO_OFF_T = 0x600000;
    static final int INFO_TYPEMASK = 0xf00000;
    /** CURLVERSION_NOW for the headers PHP 8.5 was built with (libcurl 8.14: CURLVERSION_TWELFTH). */
    static final int VERSION_NOW = 11;

    static final ValueLayout.OfLong SIZE_T = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfLong OFF_T = ValueLayout.JAVA_LONG;
    static final AddressLayout PTR = ValueLayout.ADDRESS;
    static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    /** C long: 64 bits on Linux, 32 on Windows. */
    static final ValueLayout LONG = (ValueLayout) Linker.nativeLinker().canonicalLayouts().get("long");

    /** The library and its functions, loaded on first use. */
    static final class Library {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup(libraryPath(), Arena.global());
        private static final Linker.Option VARIADIC_THIRD = Linker.Option.firstVariadicArg(2);

        static final MethodHandle GLOBAL_INIT = handle("curl_global_init", INT, LONG);
        static final MethodHandle EASY_INIT = handle("curl_easy_init", PTR);
        static final MethodHandle EASY_CLEANUP = voidHandle("curl_easy_cleanup", PTR);
        static final MethodHandle EASY_DUPHANDLE = handle("curl_easy_duphandle", PTR, PTR);
        static final MethodHandle EASY_RESET = voidHandle("curl_easy_reset", PTR);
        static final MethodHandle EASY_PERFORM = handle("curl_easy_perform", INT, PTR);
        static final MethodHandle EASY_SETOPT_LONG = variadic("curl_easy_setopt", INT, PTR, INT, LONG);
        static final MethodHandle EASY_SETOPT_PTR = variadic("curl_easy_setopt", INT, PTR, INT, PTR);
        static final MethodHandle EASY_SETOPT_OFF_T = variadic("curl_easy_setopt", INT, PTR, INT, OFF_T);
        static final MethodHandle EASY_GETINFO = variadic("curl_easy_getinfo", INT, PTR, INT, PTR);
        static final MethodHandle EASY_STRERROR = handle("curl_easy_strerror", PTR, INT);
        static final MethodHandle EASY_ESCAPE = handle("curl_easy_escape", PTR, PTR, PTR, INT);
        static final MethodHandle EASY_UNESCAPE = handle("curl_easy_unescape", PTR, PTR, PTR, INT, PTR);
        static final MethodHandle EASY_PAUSE = handle("curl_easy_pause", INT, PTR, INT);
        static final MethodHandle EASY_UPKEEP = handle("curl_easy_upkeep", INT, PTR);
        static final MethodHandle EASY_OPTION_BY_ID = handle("curl_easy_option_by_id", PTR, INT);
        static final MethodHandle FREE = voidHandle("curl_free", PTR);
        static final MethodHandle VERSION_INFO = handle("curl_version_info", PTR, INT);
        static final MethodHandle SLIST_APPEND = handle("curl_slist_append", PTR, PTR, PTR);
        static final MethodHandle SLIST_FREE_ALL = voidHandle("curl_slist_free_all", PTR);
        static final MethodHandle MIME_INIT = handle("curl_mime_init", PTR, PTR);
        static final MethodHandle MIME_FREE = voidHandle("curl_mime_free", PTR);
        static final MethodHandle MIME_ADDPART = handle("curl_mime_addpart", PTR, PTR);
        static final MethodHandle MIME_NAME = handle("curl_mime_name", INT, PTR, PTR);
        static final MethodHandle MIME_DATA = handle("curl_mime_data", INT, PTR, PTR, SIZE_T);
        static final MethodHandle MIME_DATA_CB = handle("curl_mime_data_cb", INT, PTR, OFF_T, PTR, PTR, PTR, PTR);
        static final MethodHandle MIME_FILENAME = handle("curl_mime_filename", INT, PTR, PTR);
        static final MethodHandle MIME_TYPE = handle("curl_mime_type", INT, PTR, PTR);
        static final MethodHandle MULTI_INIT = handle("curl_multi_init", PTR);
        static final MethodHandle MULTI_CLEANUP = handle("curl_multi_cleanup", INT, PTR);
        static final MethodHandle MULTI_ADD_HANDLE = handle("curl_multi_add_handle", INT, PTR, PTR);
        static final MethodHandle MULTI_REMOVE_HANDLE = handle("curl_multi_remove_handle", INT, PTR, PTR);
        static final MethodHandle MULTI_PERFORM = handle("curl_multi_perform", INT, PTR, PTR);
        static final MethodHandle MULTI_WAIT = handle("curl_multi_wait", INT, PTR, PTR, INT, INT, PTR);
        static final MethodHandle MULTI_INFO_READ = handle("curl_multi_info_read", PTR, PTR, PTR);
        static final MethodHandle MULTI_STRERROR = handle("curl_multi_strerror", PTR, INT);
        static final MethodHandle MULTI_SETOPT_LONG = variadic("curl_multi_setopt", INT, PTR, INT, LONG);
        static final MethodHandle MULTI_SETOPT_PTR = variadic("curl_multi_setopt", INT, PTR, INT, PTR);
        static final MethodHandle PUSHHEADER_BYNUM = handle("curl_pushheader_bynum", PTR, PTR, SIZE_T);
        static final MethodHandle SHARE_INIT = handle("curl_share_init", PTR);
        static final MethodHandle SHARE_CLEANUP = handle("curl_share_cleanup", INT, PTR);
        static final MethodHandle SHARE_SETOPT_LONG = variadic("curl_share_setopt", INT, PTR, INT, LONG);
        static final MethodHandle SHARE_STRERROR = handle("curl_share_strerror", PTR, INT);

        /** curl_global_init(CURL_GLOBAL_DEFAULT), as PHP_MINIT(curl); libcurl's init is thread-safe since 7.84. */
        static final int GLOBAL_INIT_RESULT = globalInit();

        private static String libraryPath() {
            String configured = System.getProperty("php.curl");
            if (configured != null) {
                return configured;
            }
            if (System.getProperty("os.name").startsWith("Windows")) {
                Path git = Path.of("C:/Program Files/Git/mingw64/bin/libcurl-4.dll");
                return Files.exists(git) ? git.toString() : "libcurl-4.dll";
            }
            return "libcurl.so.4";
        }

        private static MemorySegment find(String name) {
            return LOOKUP.find(name).orElseThrow(() -> new IllegalStateException("libcurl symbol missing: " + name));
        }

        private static MethodHandle handle(String name, MemoryLayout result, MemoryLayout... arguments) {
            return LINKER.downcallHandle(find(name), FunctionDescriptor.of(result, arguments));
        }

        private static MethodHandle voidHandle(String name, MemoryLayout... arguments) {
            return LINKER.downcallHandle(find(name), FunctionDescriptor.ofVoid(arguments));
        }

        /** A variadic function (curl_easy_setopt, curl_easy_getinfo...) called with one argument after the two. */
        private static MethodHandle variadic(String name, MemoryLayout result, MemoryLayout... arguments) {
            return LINKER.downcallHandle(find(name), FunctionDescriptor.of(result, arguments), VARIADIC_THIRD);
        }

        private static int globalInit() {
            try {
                return (int) GLOBAL_INIT.invoke(cLong(3)); // CURL_GLOBAL_DEFAULT
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }

    // ---- the callbacks: upcall stubs, made once, whose userdata is a handle's id (see CurlFunctions) ----

    static final class Callbacks {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

        /** size_t (char *, size_t, size_t, void *): curl_write, curl_write_header, curl_read and read_cb. */
        private static final FunctionDescriptor DATA = FunctionDescriptor.of(SIZE_T, PTR, SIZE_T, SIZE_T, PTR);

        static final MemorySegment WRITE = stub("write", DATA);
        static final MemorySegment WRITE_HEADER = stub("writeHeader", DATA);
        static final MemorySegment READ = stub("read", DATA);
        static final MemorySegment PROGRESS = stub("progress", FunctionDescriptor.of(INT, PTR,
                ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
        static final MemorySegment XFERINFO = stub("xferinfo", FunctionDescriptor.of(INT, PTR, OFF_T, OFF_T, OFF_T,
                OFF_T));
        static final MemorySegment FNMATCH = stub("fnmatch", FunctionDescriptor.of(INT, PTR, PTR, PTR));
        static final MemorySegment DEBUG = stub("debug", FunctionDescriptor.of(INT, PTR, INT, PTR, SIZE_T, PTR));
        static final MemorySegment PREREQ = stub("prereq", FunctionDescriptor.of(INT, PTR, PTR, PTR, INT, INT));
        static final MemorySegment SSH_HOSTKEY = stub("sshHostkey", FunctionDescriptor.of(INT, PTR, INT, PTR,
                SIZE_T));
        static final MemorySegment MIME_READ = stub("mimeRead", DATA);
        static final MemorySegment MIME_SEEK = stub("mimeSeek", FunctionDescriptor.of(INT, PTR, OFF_T, INT));
        static final MemorySegment MIME_FREE = stub("mimeFree", FunctionDescriptor.ofVoid(PTR));
        static final MemorySegment PUSH = stub("serverPush", FunctionDescriptor.of(INT, PTR, PTR, SIZE_T, PTR, PTR));

        private static MemorySegment stub(String method, FunctionDescriptor descriptor) {
            try {
                MethodType type = descriptor.toMethodType();
                MethodHandle target = LOOKUP.findStatic(CurlFunctions.Upcalls.class, method, type);
                return LINKER.upcallStub(target, descriptor, Arena.global());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---- calls: a failed downcall is a bug in the binding, not a PHP error ----

    static RuntimeException failure(Throwable t) {
        if (t instanceof Error e) {
            throw e;
        }
        return t instanceof RuntimeException e ? e : new IllegalStateException(t);
    }

    static MemorySegment easyInit() {
        try {
            return (MemorySegment) Library.EASY_INIT.invokeExact();
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static void easyCleanup(MemorySegment cp) {
        try {
            Library.EASY_CLEANUP.invokeExact(cp);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static MemorySegment easyDuphandle(MemorySegment cp) {
        try {
            return (MemorySegment) Library.EASY_DUPHANDLE.invokeExact(cp);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static void easyReset(MemorySegment cp) {
        try {
            Library.EASY_RESET.invokeExact(cp);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int easyPerform(MemorySegment cp) {
        try {
            return (int) Library.EASY_PERFORM.invokeExact(cp);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** curl_easy_setopt with a long (a C long: PHP passes a zend_long, which libcurl reads as long). */
    static int setoptLong(MemorySegment cp, int option, long value) {
        try {
            return (int) Library.EASY_SETOPT_LONG.invoke(cp, option, cLong(value));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int setoptPointer(MemorySegment cp, int option, MemorySegment value) {
        try {
            return (int) Library.EASY_SETOPT_PTR.invokeExact(cp, option, value);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int setoptOffT(MemorySegment cp, int option, long value) {
        try {
            return (int) Library.EASY_SETOPT_OFF_T.invokeExact(cp, option, value);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** A string option: the bytes NUL-terminated (libcurl copies them). */
    static int setoptString(MemorySegment cp, int option, byte[] value) {
        try (Arena arena = Arena.ofConfined()) {
            return setoptPointer(cp, option, cString(arena, value));
        }
    }

    static int getinfo(MemorySegment cp, int info, MemorySegment into) {
        try {
            return (int) Library.EASY_GETINFO.invokeExact(cp, info, into);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static byte[] easyStrerror(int code) {
        try {
            return cBytes((MemorySegment) Library.EASY_STRERROR.invokeExact(code));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static byte[] multiStrerror(int code) {
        try {
            return cBytes((MemorySegment) Library.MULTI_STRERROR.invokeExact(code));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static byte[] shareStrerror(int code) {
        try {
            return cBytes((MemorySegment) Library.SHARE_STRERROR.invokeExact(code));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** curl_easy_escape: the escaped bytes, or null. */
    static byte[] escape(MemorySegment cp, byte[] value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) Library.EASY_ESCAPE.invokeExact(cp, bytes(arena, value),
                    value.length);
            return takeString(result, -1);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** curl_easy_unescape: the unescaped bytes, or null. */
    static byte[] unescape(MemorySegment cp, byte[] value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment length = arena.allocate(INT);
            MemorySegment result = (MemorySegment) Library.EASY_UNESCAPE.invokeExact(cp, bytes(arena, value),
                    value.length, length);
            return takeString(result, length.get(INT, 0));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** A string libcurl allocated, copied and then freed with curl_free; length -1: NUL-terminated. */
    private static byte[] takeString(MemorySegment result, int length) throws Throwable {
        if (result.equals(MemorySegment.NULL)) {
            return null;
        }
        byte[] bytes = length < 0 ? cBytes(result) : result.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
        Library.FREE.invokeExact(result);
        return bytes;
    }

    static int pause(MemorySegment cp, int bitmask) {
        try {
            return (int) Library.EASY_PAUSE.invokeExact(cp, bitmask);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int upkeep(MemorySegment cp) {
        try {
            return (int) Library.EASY_UPKEEP.invokeExact(cp);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** curl_easy_option_by_id's name ("URL" for CURLOPT_URL), or null for an unknown option. */
    static String optionName(int option) {
        try {
            MemorySegment found = (MemorySegment) Library.EASY_OPTION_BY_ID.invokeExact(option);
            if (found.equals(MemorySegment.NULL)) {
                return null;
            }
            return found.reinterpret(8).get(PTR, 0).reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static MemorySegment versionInfo() {
        try {
            MemorySegment info = (MemorySegment) Library.VERSION_INFO.invokeExact(VERSION_NOW);
            return info.equals(MemorySegment.NULL) ? null : info.reinterpret(VERSION_INFO_SIZE);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** curl_version_info_data's fields up to brotli_version (age 4), at their LP64 and LLP64 offsets alike. */
    static final long VERSION_AGE = 0;
    static final long VERSION_VERSION = 8;
    static final long VERSION_NUM = 16;
    static final long VERSION_HOST = 24;
    static final long VERSION_FEATURES = 32;
    static final long VERSION_SSL_VERSION = 40;
    static final long VERSION_SSL_VERSION_NUM = 48;
    static final long VERSION_LIBZ_VERSION = 56;
    static final long VERSION_PROTOCOLS = 64;
    static final long VERSION_ARES = 72;
    static final long VERSION_ARES_NUM = 80;
    static final long VERSION_LIBIDN = 88;
    static final long VERSION_ICONV_VER_NUM = 96;
    static final long VERSION_LIBSSH_VERSION = 104;
    static final long VERSION_BROTLI_VER_NUM = 112;
    static final long VERSION_BROTLI_VERSION = 120;
    private static final long VERSION_INFO_SIZE = 128;

    /** curl_slist_append: the new list head, or NULL (the old list is then still the caller's). */
    static MemorySegment slistAppend(MemorySegment list, byte[] value) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) Library.SLIST_APPEND.invokeExact(list, cString(arena, value));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static void slistFreeAll(MemorySegment list) {
        try {
            Library.SLIST_FREE_ALL.invokeExact(list);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** A curl_slist's strings, in order. */
    static java.util.List<byte[]> slistStrings(MemorySegment list) {
        java.util.List<byte[]> strings = new java.util.ArrayList<>();
        MemorySegment node = list;
        while (!node.equals(MemorySegment.NULL)) {
            MemorySegment entry = node.reinterpret(16);
            strings.add(cBytes(entry.get(PTR, 0)));
            node = entry.get(PTR, 8);
        }
        return strings;
    }

    static MemorySegment mimeInit(MemorySegment cp) {
        try {
            return (MemorySegment) Library.MIME_INIT.invokeExact(cp);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static void mimeFree(MemorySegment mime) {
        try {
            Library.MIME_FREE.invokeExact(mime);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static MemorySegment mimeAddpart(MemorySegment mime) {
        try {
            return (MemorySegment) Library.MIME_ADDPART.invokeExact(mime);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** curl_mime_name, curl_mime_filename or curl_mime_type: a NUL-terminated string the part copies. */
    static int mimeString(MethodHandle function, MemorySegment part, byte[] value) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) function.invokeExact(part, cString(arena, value));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int mimeData(MemorySegment part, byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) Library.MIME_DATA.invokeExact(part, bytes(arena, data), (long) data.length);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int mimeDataCallback(MemorySegment part, long size, boolean seekable, long id) {
        MemorySegment seek = seekable ? Callbacks.MIME_SEEK : MemorySegment.NULL;
        try {
            return (int) Library.MIME_DATA_CB.invokeExact(part, size, Callbacks.MIME_READ, seek, Callbacks.MIME_FREE,
                    MemorySegment.ofAddress(id));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static MemorySegment multiInit() {
        try {
            return (MemorySegment) Library.MULTI_INIT.invokeExact();
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int multiCleanup(MemorySegment multi) {
        try {
            return (int) Library.MULTI_CLEANUP.invokeExact(multi);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int multiAddHandle(MemorySegment multi, MemorySegment cp) {
        try {
            return (int) Library.MULTI_ADD_HANDLE.invokeExact(multi, cp);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int multiRemoveHandle(MemorySegment multi, MemorySegment cp) {
        try {
            return (int) Library.MULTI_REMOVE_HANDLE.invokeExact(multi, cp);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** curl_multi_perform; running is the in-out count. */
    static int multiPerform(MemorySegment multi, int[] running) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(INT);
            count.set(INT, 0, running[0]);
            int result = (int) Library.MULTI_PERFORM.invokeExact(multi, count);
            running[0] = count.get(INT, 0);
            return result;
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** curl_multi_wait without extra fds; numfds receives the count. */
    static int multiWait(MemorySegment multi, int timeoutMs, int[] numfds) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(INT);
            int result = (int) Library.MULTI_WAIT.invokeExact(multi, MemorySegment.NULL, 0, timeoutMs, count);
            numfds[0] = count.get(INT, 0);
            return result;
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** A CURLMsg read off the queue: {msg, easy_handle, data.result}; queued receives the messages left. */
    record Message(int msg, MemorySegment easy, int result) {
    }

    static Message multiInfoRead(MemorySegment multi, int[] queued) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(INT);
            MemorySegment message = (MemorySegment) Library.MULTI_INFO_READ.invokeExact(multi, count);
            queued[0] = count.get(INT, 0);
            if (message.equals(MemorySegment.NULL)) {
                return null;
            }
            MemorySegment fields = message.reinterpret(24);
            return new Message(fields.get(INT, 0), fields.get(PTR, 8), fields.get(INT, 16));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int multiSetoptLong(MemorySegment multi, int option, long value) {
        try {
            return (int) Library.MULTI_SETOPT_LONG.invoke(multi, option, cLong(value));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int multiSetoptPointer(MemorySegment multi, int option, MemorySegment value) {
        try {
            return (int) Library.MULTI_SETOPT_PTR.invokeExact(multi, option, value);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static byte[] pushHeader(MemorySegment headers, long index) {
        try {
            return cBytes((MemorySegment) Library.PUSHHEADER_BYNUM.invokeExact(headers, index));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static MemorySegment shareInit() {
        try {
            return (MemorySegment) Library.SHARE_INIT.invokeExact();
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int shareCleanup(MemorySegment share) {
        try {
            return (int) Library.SHARE_CLEANUP.invokeExact(share);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    static int shareSetoptLong(MemorySegment share, int option, long value) {
        try {
            return (int) Library.SHARE_SETOPT_LONG.invoke(share, option, cLong(value));
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    // ---- memory ----

    /** A value of C's long type: the zend_long itself on LP64, its low 32 bits where long is 32 bits. */
    private static Object cLong(long value) {
        return LONG.byteSize() == 8 ? (Object) value : (Object) (int) value;
    }

    /** Reads a C long. */
    static long readLong(MemorySegment segment) {
        return LONG.byteSize() == 8 ? segment.get(ValueLayout.JAVA_LONG, 0) : segment.get(ValueLayout.JAVA_INT, 0);
    }

    static MemorySegment cString(Arena arena, byte[] value) {
        MemorySegment segment = arena.allocate(value.length + 1L);
        MemorySegment.copy(value, 0, segment, ValueLayout.JAVA_BYTE, 0, value.length);
        return segment;
    }

    static MemorySegment bytes(Arena arena, byte[] value) {
        MemorySegment segment = arena.allocate(Math.max(1, value.length));
        MemorySegment.copy(value, 0, segment, ValueLayout.JAVA_BYTE, 0, value.length);
        return segment;
    }

    /** A NUL-terminated string's bytes, or null for NULL. */
    static byte[] cBytes(MemorySegment pointer) {
        if (pointer.equals(MemorySegment.NULL)) {
            return null;
        }
        MemorySegment string = pointer.reinterpret(Long.MAX_VALUE);
        long length = 0;
        while (string.get(ValueLayout.JAVA_BYTE, length) != 0) {
            length++;
        }
        return string.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
    }
}
