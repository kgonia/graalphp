package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.ffi.NativeLibrary;
import pl.codeloom.php.runtime.ByteBuilder;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * main/php_syslog.c: php_syslog() through the C library's openlog() and syslog(), with syslog.ident, syslog.facility
 * and syslog.filter. Windows' C library has no syslog (php-src writes the event log there): nothing is logged.
 */
final class Syslog {
    private Syslog() {
    }

    /** PHP_SYSLOG_FILTER_*: what php_syslog_str passes through as is. */
    static final int FILTER_ALL = 0;
    static final int FILTER_NO_CTRL = 1;
    static final int FILTER_ASCII = 2;
    static final int FILTER_RAW = 3;

    static final int LOG_NOTICE = 5;

    /** OnSetFacility: syslog.facility's value as a facility (Linux's numbers), or -1 for a name it doesn't know. */
    static int facility(String name) {
        if (name == null) {
            return -1;
        }
        return switch (name) {
            case "LOG_AUTH", "auth", "security" -> 4 << 3;
            case "LOG_AUTHPRIV", "authpriv" -> 10 << 3;
            case "LOG_CRON", "cron" -> 9 << 3;
            case "LOG_DAEMON", "daemon" -> 3 << 3;
            case "LOG_FTP", "ftp" -> 11 << 3;
            case "LOG_KERN", "kern" -> 0;
            case "LOG_LPR", "lpr" -> 6 << 3;
            case "LOG_MAIL", "mail" -> 2 << 3;
            case "LOG_NEWS", "news" -> 7 << 3;
            case "LOG_SYSLOG", "syslog" -> 5 << 3;
            case "user", "LOG_USER" -> 1 << 3;
            case "LOG_UUCP", "uucp" -> 8 << 3;
            case "LOG_LOCAL0", "local0" -> 16 << 3;
            case "LOG_LOCAL1", "local1" -> 17 << 3;
            case "LOG_LOCAL2", "local2" -> 18 << 3;
            case "LOG_LOCAL3", "local3" -> 19 << 3;
            case "LOG_LOCAL4", "local4" -> 20 << 3;
            case "LOG_LOCAL5", "local5" -> 21 << 3;
            case "LOG_LOCAL6", "local6" -> 22 << 3;
            case "LOG_LOCAL7", "local7" -> 23 << 3;
            default -> -1; // glibc has no LOG_INTERNAL_MARK, so "mark" isn't one
        };
    }

    /** OnSetLogFilter: syslog.filter's value, or -1. */
    static int filter(String name) {
        if (name == null) {
            return -1;
        }
        return switch (name) {
            case "all" -> FILTER_ALL;
            case "no-ctrl" -> FILTER_NO_CTRL;
            case "ascii" -> FILTER_ASCII;
            case "raw" -> FILTER_RAW;
            default -> -1;
        };
    }

    /**
     * php_syslog(priority, "%s", message): openlog() with syslog.ident and syslog.facility first, once a request
     * (PG(have_called_openlog)); then php_syslog_str, which unless syslog.filter is raw sends a line per LF and
     * writes the bytes it doesn't pass as \xNN.
     */
    @TruffleBoundary
    static void log(PhpContext context, int priority, byte[] message) {
        if (!Available.SYSLOG) {
            return;
        }
        Ini ini = context.ini();
        BuiltinState state = context.builtinState();
        if (!state.calledOpenlog) {
            openlog(ini.syslogIdent(), ini.syslogFacility());
            state.calledOpenlog = true;
        }
        int filter = ini.syslogFilter();
        if (filter == FILTER_RAW) {
            syslog(priority, message, message.length);
            return;
        }
        ByteBuilder line = new ByteBuilder(message.length);
        for (byte b : message) {
            int c = b & 0xFF;
            if (c >= 0x20 && c <= 0x7E) {
                line.write(c);
            } else if (c >= 0x80 && filter != FILTER_ASCII) {
                line.write(c);
            } else if (c == '\n') {
                syslog(priority, line.toByteArray(), line.size());
                line.reset();
            } else if (c < 0x20 && filter == FILTER_ALL) {
                line.write(c);
            } else {
                line.writeAscii("\\x");
                line.write(Character.forDigit(c >> 4, 16));
                line.write(Character.forDigit(c & 0xF, 16));
            }
        }
        syslog(priority, line.toByteArray(), line.size());
    }

    private static void openlog(String ident, int facility) {
        try {
            Libc.OPENLOG.invokeExact(Idents.of(ident), 0, facility);
        } catch (Throwable t) {
            throw new IllegalStateException("openlog failed", t);
        }
    }

    /** syslog(priority, "%.*s", length, bytes): the bytes up to the first NUL, as %s reads them. */
    private static void syslog(int priority, byte[] bytes, int length) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocate(length + 1L);
            MemorySegment.copy(bytes, 0, text, ValueLayout.JAVA_BYTE, 0, length);
            MemorySegment format = arena.allocate(3);
            format.setString(0, "%s", StandardCharsets.US_ASCII);
            Libc.SYSLOG.invokeExact(priority, format, text);
        } catch (Throwable t) {
            throw new IllegalStateException("syslog failed", t);
        }
    }

    /**
     * openlog() keeps the ident's pointer, so each ident is a C string for the rest of the process: native state, made
     * at run time.
     */
    private static final class Idents {
        private static final Map<String, MemorySegment> IDENTS = new ConcurrentHashMap<>();

        static MemorySegment of(String ident) {
            if (ident == null) {
                return MemorySegment.NULL;
            }
            return IDENTS.computeIfAbsent(ident, name -> {
                byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
                MemorySegment segment = Arena.global().allocate(bytes.length + 1L);
                MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
                return segment;
            });
        }
    }

    /** openlog and syslog (variadic: the format's one string), declared with the image (NativeCallsFeature). */
    private static final class Libc {
        static final MethodHandle OPENLOG = NativeLibrary.LIBC.downcall("openlog",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        static final MethodHandle SYSLOG = NativeLibrary.LIBC.downcall("syslog",
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                Linker.Option.firstVariadicArg(2));
        private Libc() {
        }
    }

    /** Whether the C library has syslog: asked at the first log (run time), not while the image is built. */
    private static final class Available {
        static final boolean SYSLOG = NativeLibrary.LIBC.has("syslog");
    }
}
