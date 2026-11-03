package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.ffi.NativeLibrary;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.Str;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ext/standard's exec.c as far as the runtime has it: php_escape_shell_cmd (escapeshellcmd()), and the C library's
 * popen(command, "w") and pclose(), through which php_mail hands a message to sendmail. On Linux the child is
 * started as glibc's popen starts it (posix_spawn of /bin/sh), so its wait status, and with it a signal that ended
 * it, comes back whole; Windows, a development host, runs Git for Windows' sh through a ProcessBuilder.
 */
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
        r.fn("escapeshellarg", (context, a) -> {
            Args.arity(a, "escapeshellarg", 1, 1);
            byte[] arg = Str.bytes(Args.string(context, a, 0, "escapeshellarg", "arg", false));
            if (indexOf(arg, (byte) 0, 0) >= 0) {
                throw new PhpError("ValueError", "escapeshellarg(): Argument #1 ($arg) must not contain any null "
                        + "bytes");
            }
            if (arg.length > CommandLimit.MAX - 2 - 1) {
                throw new PhpError("ValueError", "Argument exceeds the allowed length of " + CommandLimit.MAX
                        + " bytes");
            }
            return Str.of(escapeShellArg(arg));
        });
    }

    /**
     * php_escape_shell_arg: the argument in single quotes, each quote in it closed, escaped and reopened ('\'');
     * multi-byte characters are copied whole and a byte that starts none is dropped, as in escapeShellCmd.
     */
    @TruffleBoundary
    static byte[] escapeShellArg(byte[] str) {
        ByteBuilder cmd = new ByteBuilder(str.length + 2);
        cmd.write('\'');
        for (int x = 0; x < str.length; x++) {
            int mbLen = mblen(str, x);
            if (mbLen < 0) {
                continue;
            }
            if (mbLen > 1) {
                cmd.write(str, x, mbLen);
                x += mbLen - 1;
                continue;
            }
            if (str[x] == '\'') {
                cmd.write('\'');
                cmd.write('\\');
                cmd.write('\'');
            }
            cmd.write(str[x]);
        }
        cmd.write('\'');
        return cmd.toByteArray();
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

    // ---- popen(command, "w") and pclose() ----

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private static final boolean LINUX = System.getProperty("os.name").startsWith("Linux");

    /** How pclose() saw the child end: its exit status, a signal, neither (WIFEXITED and WIFSIGNALED false). */
    enum Ending { EXITED, SIGNALED, OTHER }

    /**
     * popen() and pclose() as php_mail reads them: started false when popen() gave no stream, else how the child
     * ended with its exit status or signal, or (ending null) the errno of a pclose() that returned -1.
     */
    record Child(boolean started, Ending ending, int value, int errno) {
        static final Child NOT_STARTED = new Child(false, null, 0, 0);
    }

    /**
     * popen(command, "w"), the input written to the child's stdin, pclose(): the shell runs the command with the
     * context's environment in its working directory, its stdout and stderr the process's own (as a forked child
     * of php has them). The output written so far goes first, as php-cli's unbuffered stdout keeps the order.
     */
    @TruffleBoundary
    static Child pipeTo(PhpContext context, byte[] command, byte[] input) {
        if (context.response() == null) {
            context.flush();
        }
        Map<String, String> environment = RuntimeFunctions.environment(context);
        TruffleFile cwd = context.getEnv().getCurrentWorkingDirectory();
        if (LINUX) {
            return Spawn.pipeTo(command, input, environment, cwd.getPath());
        }
        return pipeThroughProcessBuilder(command, input, environment, cwd.getPath());
    }

    /** Elsewhere than Linux (Windows): the JDK's process, which reports an exit code only. */
    private static Child pipeThroughProcessBuilder(byte[] command, byte[] input, Map<String, String> environment,
                                                   String cwd) {
        ProcessBuilder builder = new ProcessBuilder(shell(), "-c", new String(command, StandardCharsets.UTF_8))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .directory(new java.io.File(cwd));
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            return Child.NOT_STARTED;
        }
        boolean written = true;
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(input);
        } catch (IOException e) {
            written = false; // the child gone, as Spawn.write's EPIPE
        }
        while (true) {
            try {
                int status = process.waitFor();
                return !written && status == 0 ? new Child(true, null, 0, Spawn.EPIPE)
                        : new Child(true, Ending.EXITED, status, 0);
            } catch (InterruptedException e) {
                // pclose waits on through EINTR
            }
        }
    }

    /** /bin/sh: on Windows Git for Windows' sh, where the runtime's other Unix pieces come from. */
    private static String shell() {
        if (!WINDOWS) {
            return "/bin/sh";
        }
        Path git = Path.of("C:/Program Files/Git/usr/bin/sh.exe");
        return Files.exists(git) ? git.toString() : "sh";
    }

    /** glibc's popen and pclose (libio/iopopen.c) over posix_spawn, pipe2 and waitpid. */
    private static final class Spawn {
        private static final int O_CLOEXEC = 0x80000;
        private static final short POSIX_SPAWN_SETSIGMASK = 0x08;
        private static final int EINTR = 4;
        static final int EPIPE = 32;

        static Child pipeTo(byte[] command, byte[] input, Map<String, String> environment, String cwd) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment fds = arena.allocate(ValueLayout.JAVA_INT, 2);
                if ((int) Libc.PIPE2.invokeExact(fds, O_CLOEXEC) != 0) {
                    return Child.NOT_STARTED;
                }
                int readEnd = fds.getAtIndex(ValueLayout.JAVA_INT, 0);
                int writeEnd = fds.getAtIndex(ValueLayout.JAVA_INT, 1);
                int pid = spawn(arena, command, environment, cwd, readEnd);
                int ignored = (int) Libc.CLOSE.invokeExact(readEnd);
                if (pid < 0) {
                    ignored = (int) Libc.CLOSE.invokeExact(writeEnd);
                    return Child.NOT_STARTED;
                }
                int writeErrno = write(arena, writeEnd, input);
                ignored = (int) Libc.CLOSE.invokeExact(writeEnd);
                Child child = waitFor(arena, pid);
                // pclose is fclose: a child that exited 0 still fails it when the last flush did
                if (writeErrno != 0 && child.ending() == Ending.EXITED && child.value() == 0) {
                    return new Child(true, null, 0, writeErrno);
                }
                return child;
            } catch (Throwable t) {
                throw new IllegalStateException("popen failed", t);
            }
        }

        /**
         * posix_spawn(_PATH_BSHELL, {"sh", "-c", "--", command}) with the pipe's read end as stdin, as glibc 2.41's
         * popen; the child's signal mask is emptied (a JVM thread's isn't php's). The pid, or -1.
         */
        private static int spawn(Arena arena, byte[] command, Map<String, String> environment, String cwd,
                                 int readEnd) throws Throwable {
            MemorySegment actions = arena.allocate(256, 16);
            MemorySegment attributes = arena.allocate(1024, 16);
            MemorySegment signals = arena.allocate(256, 16);
            int ignored = (int) Libc.FILE_ACTIONS_INIT.invokeExact(actions);
            ignored = (int) Libc.SPAWNATTR_INIT.invokeExact(attributes);
            try {
                if ((int) Libc.FILE_ACTIONS_ADDDUP2.invokeExact(actions, readEnd, 0) != 0) {
                    return -1;
                }
                // a context's working directory, when it isn't the process's own (a server's request in its
                // script's directory, as php-cgi chdir()s there)
                if (!cwd.equals(System.getProperty("user.dir")) && AddChdir.AVAILABLE) {
                    if ((int) Libc.FILE_ACTIONS_ADDCHDIR.invokeExact(actions, cString(arena, cwd)) != 0) {
                        return -1;
                    }
                }
                ignored = (int) Libc.SIGEMPTYSET.invokeExact(signals);
                ignored = (int) Libc.SPAWNATTR_SETSIGMASK.invokeExact(attributes, signals);
                ignored = (int) Libc.SPAWNATTR_SETFLAGS.invokeExact(attributes, POSIX_SPAWN_SETSIGMASK);
                List<byte[]> argv = List.of("sh".getBytes(StandardCharsets.US_ASCII),
                        "-c".getBytes(StandardCharsets.US_ASCII), "--".getBytes(StandardCharsets.US_ASCII), command);
                List<byte[]> envp = new ArrayList<>();
                for (Map.Entry<String, String> variable : environment.entrySet()) {
                    envp.add((variable.getKey() + "=" + variable.getValue()).getBytes(StandardCharsets.UTF_8));
                }
                MemorySegment pid = arena.allocate(ValueLayout.JAVA_INT);
                int error = (int) Libc.POSIX_SPAWN.invokeExact(pid, cString(arena, "/bin/sh"), actions, attributes,
                        pointers(arena, argv), pointers(arena, envp));
                return error == 0 ? pid.get(ValueLayout.JAVA_INT, 0) : -1;
            } finally {
                ignored = (int) Libc.FILE_ACTIONS_DESTROY.invokeExact(actions);
                ignored = (int) Libc.SPAWNATTR_DESTROY.invokeExact(attributes);
            }
        }

        /**
         * fprintf to the stream, then pclose's flush: the errno of the write that failed (the child gone: EPIPE), or
         * 0. stdio doesn't retry a failed write, and once the child has closed the pipe every later one fails too,
         * the last flush with them: its buffer always holds the message's last line separator.
         */
        private static int write(Arena arena, int fd, byte[] input) throws Throwable {
            MemorySegment buffer = arena.allocate(Math.max(1, input.length));
            MemorySegment.copy(input, 0, buffer, ValueLayout.JAVA_BYTE, 0, input.length);
            MemorySegment state = arena.allocate(Libc.CAPTURED);
            long offset = 0;
            while (offset < input.length) {
                long written = (long) Libc.WRITE.invokeExact(state, fd, buffer.asSlice(offset),
                        input.length - offset);
                if (written < 0) {
                    return (int) Libc.ERRNO.get(state, 0L);
                }
                offset += written;
            }
            return 0;
        }

        /** pclose: waitpid through EINTR, then the wait status's meaning. */
        private static Child waitFor(Arena arena, int pid) throws Throwable {
            MemorySegment status = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment state = arena.allocate(Libc.CAPTURED);
            while (true) {
                int waited = (int) Libc.WAITPID.invokeExact(state, pid, status, 0);
                if (waited != -1) {
                    break;
                }
                int errno = (int) Libc.ERRNO.get(state, 0L);
                if (errno != EINTR) {
                    return new Child(true, null, 0, errno);
                }
            }
            int wstatus = status.get(ValueLayout.JAVA_INT, 0);
            if ((wstatus & 0x7F) == 0) {
                return new Child(true, Ending.EXITED, (wstatus >> 8) & 0xFF, 0); // WIFEXITED, WEXITSTATUS
            }
            if ((wstatus & 0x7F) != 0x7F) {
                return new Child(true, Ending.SIGNALED, wstatus & 0x7F, 0); // WIFSIGNALED (0x7F: stopped), WTERMSIG
            }
            return new Child(true, Ending.OTHER, 0, 0);
        }

        private static MemorySegment cString(Arena arena, String value) {
            return cString(arena, value.getBytes(StandardCharsets.UTF_8));
        }

        private static MemorySegment cString(Arena arena, byte[] value) {
            MemorySegment segment = arena.allocate(value.length + 1L);
            MemorySegment.copy(value, 0, segment, ValueLayout.JAVA_BYTE, 0, value.length);
            return segment;
        }

        /** A NULL-terminated array of C strings (argv, envp). */
        private static MemorySegment pointers(Arena arena, List<byte[]> values) {
            MemorySegment array = arena.allocate(ValueLayout.ADDRESS, values.size() + 1L);
            for (int i = 0; i < values.size(); i++) {
                array.setAtIndex(ValueLayout.ADDRESS, i, cString(arena, values.get(i)));
            }
            return array;
        }
    }

    /** The script owner's uid (php_getuid): the main script's, else the process's (php -r, stdin). */
    static long scriptOwner(PhpContext context) {
        String script = mainScript(context);
        if (script != null) {
            try {
                Integer uid = context.getEnv().getPublicTruffleFile(script).getAttribute(TruffleFile.UNIX_UID);
                return Integer.toUnsignedLong(uid);
            } catch (IOException | UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
                // php_statpage falls back to getuid()
            }
        }
        if (WINDOWS) {
            return 0; // the CRT's stat() and getuid() of php on Windows
        }
        try {
            return Integer.toUnsignedLong((int) Libc.GETUID.invokeExact());
        } catch (Throwable t) {
            throw new IllegalStateException("getuid failed", t);
        }
    }

    /** SG(request_info).path_translated: the CLI's script argument, a CGI request's SCRIPT_FILENAME. */
    private static String mainScript(PhpContext context) {
        if (context.response() != null) {
            return context.getEnv().getEnvironment().get("SCRIPT_FILENAME");
        }
        String[] arguments = context.getEnv().getApplicationArguments();
        return arguments.length > 0 ? arguments[0] : null;
    }

    /**
     * The C library's process functions: declared with the image (NativeCallsFeature). On Windows the lookups find
     * nothing, and nothing calls them there.
     */
    private static final class Libc {
        private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
        private static final ValueLayout PTR = ValueLayout.ADDRESS;

        static final MethodHandle PIPE2 = libc("pipe2", INT, PTR, INT);
        static final MethodHandle CLOSE = libc("close", INT, INT);
        static final MethodHandle WRITE = NativeLibrary.LIBC.downcall("write",
                FunctionDescriptor.of(NativeLibrary.SIZE_T, INT, PTR, NativeLibrary.SIZE_T),
                Linker.Option.captureCallState("errno"));
        static final MethodHandle FILE_ACTIONS_INIT = libc("posix_spawn_file_actions_init", INT, PTR);
        static final MethodHandle FILE_ACTIONS_ADDDUP2 = libc("posix_spawn_file_actions_adddup2", INT, PTR, INT,
                INT);
        static final MethodHandle FILE_ACTIONS_ADDCHDIR = libc("posix_spawn_file_actions_addchdir_np", INT, PTR,
                PTR);
        static final MethodHandle FILE_ACTIONS_DESTROY = libc("posix_spawn_file_actions_destroy", INT, PTR);
        static final MethodHandle SPAWNATTR_INIT = libc("posix_spawnattr_init", INT, PTR);
        static final MethodHandle SPAWNATTR_SETFLAGS = libc("posix_spawnattr_setflags", INT, PTR,
                ValueLayout.JAVA_SHORT);
        static final MethodHandle SPAWNATTR_SETSIGMASK = libc("posix_spawnattr_setsigmask", INT, PTR, PTR);
        static final MethodHandle SPAWNATTR_DESTROY = libc("posix_spawnattr_destroy", INT, PTR);
        static final MethodHandle SIGEMPTYSET = libc("sigemptyset", INT, PTR);
        static final MethodHandle POSIX_SPAWN = libc("posix_spawn", INT, PTR, PTR, PTR, PTR, PTR, PTR);
        static final StructLayout CAPTURED = Linker.Option.captureStateLayout();
        static final VarHandle ERRNO = CAPTURED.varHandle(MemoryLayout.PathElement.groupElement("errno"));
        static final MethodHandle WAITPID = NativeLibrary.LIBC.downcall("waitpid",
                FunctionDescriptor.of(INT, INT, PTR, INT), Linker.Option.captureCallState("errno"));
        static final MethodHandle GETUID = NativeLibrary.LIBC.downcall("getuid", FunctionDescriptor.of(INT));
        static final MethodHandle SYSCONF = NativeLibrary.LIBC.downcall("sysconf",
                FunctionDescriptor.of(NativeLibrary.C_LONG, INT));
        private Libc() {
        }

        private static MethodHandle libc(String name, ValueLayout result, MemoryLayout... arguments) {
            return NativeLibrary.LIBC.downcall(name, FunctionDescriptor.of(result, arguments));
        }
    }

    /** Whether the C library has posix_spawn_file_actions_addchdir_np (glibc 2.29): looked up at run time. */
    private static final class AddChdir {
        static final boolean AVAILABLE = NativeLibrary.LIBC.has("posix_spawn_file_actions_addchdir_np");
    }
}
