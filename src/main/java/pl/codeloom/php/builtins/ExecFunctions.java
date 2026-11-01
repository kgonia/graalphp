package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.ffi.NativeLibrary;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.Str;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** ext/standard's exec.c as far as the runtime has it: php_escape_shell_cmd (escapeshellcmd()). */
final class ExecFunctions {
    private ExecFunctions() {
    }

    static void register(Registry r) {
        r.fn("escapeshellcmd", (context, a) -> {
            Args.arity(a, "escapeshellcmd", 1, 1);
            byte[] command = Str.bytes(Args.string(context, a, 0, "escapeshellcmd", "command", false));
            if (command.length == 0) {
                return Str.EMPTY;
            }
            if (indexOf(command, (byte) 0, 0) >= 0) {
                throw new PhpError("ValueError", "escapeshellcmd(): Argument #1 ($command) must not contain any null "
                        + "bytes");
            }
            byte[] escaped = escapeShellCmd(command);
            if (escaped == null) {
                throw commandTooLong();
            }
            return Str.of(escaped);
        });
    }

    // ---- php_escape_shell_cmd ----

    /**
     * php_escape_shell_cmd: a backslash before each character a shell would take as syntax, and before a quote that
     * has no partner later on (a paired one stays). Multi-byte characters of the C.UTF-8 LC_CTYPE php-src sets at
     * startup are copied whole; a byte that starts none (mblen() fails) is dropped. Null when the command is longer
     * than cmd_max_len allows, where php-src raises {@link #commandTooLong()}.
     */
    @TruffleBoundary
    static byte[] escapeShellCmd(byte[] str) {
        int l = str.length;
        if (l > CommandLimit.MAX - 2 - 1) {
            return null;
        }
        ByteBuilder cmd = new ByteBuilder(2 * l);
        int p = -1; // the quote that pairs with the one before it (php-src's p), or none
        for (int x = 0; x < l; x++) {
            int mbLen = mblen(str, x);
            if (mbLen < 0) {
                continue;
            }
            if (mbLen > 1) {
                cmd.write(str, x, mbLen);
                x += mbLen - 1;
                continue;
            }
            byte c = str[x];
            switch (c) {
                case '"', '\'' -> {
                    if (p < 0) {
                        p = indexOf(str, c, x + 1);
                        if (p < 0) {
                            cmd.write('\\');
                        }
                    } else if (str[p] == c) {
                        p = -1;
                    } else {
                        cmd.write('\\');
                    }
                    cmd.write(c);
                }
                case '#', '&', ';', '`', '|', '*', '?', '~', '<', '>', '^', '(', ')', '[', ']', '{', '}', '$', '\\',
                     '\n', (byte) 0xFF -> {
                    cmd.write('\\');
                    cmd.write(c);
                }
                default -> cmd.write(c);
            }
        }
        return cmd.toByteArray();
    }

    /** What php_escape_shell_cmd raises for a command it can't take. */
    static PhpError commandTooLong() {
        return new PhpError("ValueError", "Command exceeds the allowed length of " + CommandLimit.MAX + " bytes");
    }

    /**
     * glibc's mblen() in C.UTF-8 (php_mblen of a non-ZTS build): the length of the character at i, -1 for an invalid
     * or incomplete one. glibc's decoder takes the 5- and 6-byte forms (up to 0x7FFFFFFF) and refuses overlong forms
     * and surrogates.
     */
    private static int mblen(byte[] s, int i) {
        int ch = s[i] & 0xFF;
        if (ch < 0x80) {
            return ch == 0 ? 0 : 1;
        }
        int count;
        if (ch >= 0xC2 && ch < 0xE0) {
            count = 2;
            ch &= 0x1F;
        } else if ((ch & 0xF0) == 0xE0) {
            count = 3;
            ch &= 0x0F;
        } else if ((ch & 0xF8) == 0xF0) {
            count = 4;
            ch &= 0x07;
        } else if ((ch & 0xFC) == 0xF8) {
            count = 5;
            ch &= 0x03;
        } else if ((ch & 0xFE) == 0xFC) {
            count = 6;
            ch &= 0x01;
        } else {
            return -1;
        }
        if (i + count > s.length) {
            return -1;
        }
        for (int k = 1; k < count; k++) {
            int trail = s[i + k] & 0xFF;
            if ((trail & 0xC0) != 0x80) {
                return -1;
            }
            ch = (ch << 6) | (trail & 0x3F);
        }
        if ((count > 2 && (ch >>> (5 * count - 4)) == 0) || (ch >= 0xD800 && ch <= 0xDFFF)) {
            return -1;
        }
        return count;
    }

    private static int indexOf(byte[] s, byte b, int from) {
        for (int i = from; i < s.length; i++) {
            if (s[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /**
     * exec.c's cmd_max_len: sysconf(_SC_ARG_MAX), as its MINIT reads it. Windows has no sysconf: there the runtime
     * is Linux's php too (its shell is sh), with Linux's ARG_MAX for the default 8 MB stack.
     */
    private static final class CommandLimit {
        static final long MAX = limit();

        private static long limit() {
            if (WINDOWS) {
                return 2097152;
            }
            try {
                long max = (long) Libc.SYSCONF.invokeExact(0); // _SC_ARG_MAX
                return max == -1 ? 4096 : max; // _POSIX_ARG_MAX
            } catch (Throwable t) {
                throw new IllegalStateException("sysconf failed", t);
            }
        }
    }

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    /** The C library's sysconf: declared with the image (NativeCallsFeature). Nothing calls it on Windows. */
    private static final class Libc {
        static final MethodHandle SYSCONF = NativeLibrary.LIBC.downcall("sysconf",
                FunctionDescriptor.of(NativeLibrary.C_LONG, ValueLayout.JAVA_INT));

        private Libc() {
        }
    }
}
