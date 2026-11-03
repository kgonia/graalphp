package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.ffi.NativeLibrary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.SourceLocation;
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
import java.util.Arrays;
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
        r.fn("exec", "011", (context, a) -> exec(context, a, "exec", 0));
        r.fn("system", "01", (context, a) -> exec(context, a, "system", 1));
        r.fn("passthru", "01", (context, a) -> exec(context, a, "passthru", 3));
        r.fn("shell_exec", (context, a) -> {
            Args.arity(a, "shell_exec", 1, 1);
            byte[] command = command(context, a, "shell_exec");
            Object[] result = popen(context, command);
            if (result == null) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("shell_exec(): Unable to execute '"
                        + new String(command, StandardCharsets.UTF_8) + "'"), SourceLocation.current());
                return Boolean.FALSE;
            }
            byte[] output = (byte[]) result[0];
            return output.length > 0 ? Str.of(output) : PhpNull.NULL;
        });
        // microtime.c's getrusage: RUSAGE_SELF, or RUSAGE_CHILDREN for 1
        r.fn("getrusage", (context, a) -> {
            Args.arity(a, "getrusage", 0, 1);
            long who = Args.has(a, 0) ? Args.integer(context, a, 0, "getrusage", "mode", "int") : 0;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment usage = arena.allocate(144, 8); // struct rusage: two timevals, 14 longs
                if ((int) Libc.GETRUSAGE.invokeExact(who == 1 ? -1 : 0, usage) == -1) {
                    return Boolean.FALSE;
                }
                PhpArray result = new PhpArray();
                String[] names = {"ru_oublock", "ru_inblock", "ru_msgsnd", "ru_msgrcv", "ru_maxrss", "ru_ixrss",
                    "ru_idrss", "ru_minflt", "ru_majflt", "ru_nsignals", "ru_nvcsw", "ru_nivcsw", "ru_nswap"};
                int[] fields = {9, 8, 10, 11, 1, 2, 3, 5, 6, 12, 13, 14, 7}; // after the timevals, from ru_maxrss=1
                for (int i = 0; i < names.length; i++) {
                    result.put(FileFunctions.key(names[i]), usage.get(ValueLayout.JAVA_LONG, 32 + (fields[i] - 1) * 8L));
                }
                result.put(FileFunctions.key("ru_utime.tv_usec"), usage.get(ValueLayout.JAVA_LONG, 8));
                result.put(FileFunctions.key("ru_utime.tv_sec"), usage.get(ValueLayout.JAVA_LONG, 0));
                result.put(FileFunctions.key("ru_stime.tv_usec"), usage.get(ValueLayout.JAVA_LONG, 24));
                result.put(FileFunctions.key("ru_stime.tv_sec"), usage.get(ValueLayout.JAVA_LONG, 16));
                return result;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
        r.fn("proc_open", "001", (context, a) -> ProcOpen.open(context, a));
        r.fn("proc_close", (context, a) -> {
            Args.arity(a, "proc_close", 1, 1);
            PhpResource resource = ProcOpen.resource(a, "proc_close");
            ProcOpen.Process process = (ProcOpen.Process) resource.getHandle();
            resource.close();
            return (long) process.close(context, true);
        });
        r.fn("proc_get_status", (context, a) -> {
            Args.arity(a, "proc_get_status", 1, 1);
            return ((ProcOpen.Process) ProcOpen.resource(a, "proc_get_status").getHandle()).status();
        });
        r.fn("proc_terminate", (context, a) -> {
            Args.arity(a, "proc_terminate", 1, 2);
            ProcOpen.Process process = (ProcOpen.Process) ProcOpen.resource(a, "proc_terminate").getHandle();
            long signal = Args.has(a, 1) ? Args.integer(context, a, 1, "proc_terminate", "signal", "int") : 15;
            try {
                return (int) Libc.KILL.invokeExact(process.pid, (int) signal) == 0;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
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
        return mblen(s, i, s.length);
    }

    /** mblen(s + i, end - i) in a UTF-8 locale: the character's length, 0 for NUL, -1 if it isn't one. */
    static int mblen(byte[] s, int i, int end) {
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
        if (i + count > end) {
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
                int pid = spawn(arena, command, environment, cwd, readEnd, 0);
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
         * posix_spawn(_PATH_BSHELL, {"sh", "-c", "--", command}) with the pipe's end as stdin or stdout (target), as
         * glibc 2.41's popen; the child's signal mask is emptied (a JVM thread's isn't php's). The pid, or -1.
         */
        private static int spawn(Arena arena, byte[] command, Map<String, String> environment, String cwd,
                                 int childEnd, int target) throws Throwable {
            MemorySegment actions = arena.allocate(256, 16);
            MemorySegment attributes = arena.allocate(1024, 16);
            MemorySegment signals = arena.allocate(256, 16);
            int ignored = (int) Libc.FILE_ACTIONS_INIT.invokeExact(actions);
            ignored = (int) Libc.SPAWNATTR_INIT.invokeExact(attributes);
            try {
                if ((int) Libc.FILE_ACTIONS_ADDDUP2.invokeExact(actions, childEnd, target) != 0) {
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

        /** popen(command, "r") read to the end, then pclose: the output and the wait status, or null. */
        static Object[] readFrom(byte[] command, Map<String, String> environment, String cwd) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment fds = arena.allocate(ValueLayout.JAVA_INT, 2);
                if ((int) Libc.PIPE2.invokeExact(fds, O_CLOEXEC) != 0) {
                    return null;
                }
                int readEnd = fds.getAtIndex(ValueLayout.JAVA_INT, 0);
                int writeEnd = fds.getAtIndex(ValueLayout.JAVA_INT, 1);
                int pid = spawn(arena, command, environment, cwd, writeEnd, 1);
                int ignored = (int) Libc.CLOSE.invokeExact(writeEnd);
                if (pid < 0) {
                    ignored = (int) Libc.CLOSE.invokeExact(readEnd);
                    return null;
                }
                ProcOpen.PipeStream pipe = new ProcOpen.PipeStream(readEnd, "r");
                ByteBuilder output = new ByteBuilder();
                byte[] chunk = new byte[8192];
                for (int n; (n = pipe.rawRead(chunk, 0, chunk.length)) > 0; ) {
                    output.write(chunk, 0, n);
                }
                pipe.rawClose();
                MemorySegment status = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment state = arena.allocate(Libc.CAPTURED);
                int waited;
                do {
                    waited = (int) Libc.WAITPID.invokeExact(state, pid, status, 0);
                } while (waited == -1 && (int) Libc.ERRNO.get(state, 0L) == EINTR);
                return new Object[]{output.toByteArray(), waited == -1 ? -1 : status.get(ValueLayout.JAVA_INT, 0)};
            } catch (Throwable t) {
                throw new IllegalStateException("popen failed", t);
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

    /** Z_PARAM_PATH_STR and a non-empty command. */
    private static byte[] command(PhpContext context, Object[] a, String function) {
        byte[] command = Str.bytes(Args.string(context, a, 0, function, "command", false));
        if (indexOf(command, (byte) 0, 0) >= 0) {
            throw new PhpError("ValueError", function + "(): Argument #1 ($command) must not contain any null bytes");
        }
        if (command.length == 0) {
            throw new PhpError("ValueError", function + "(): Argument #1 ($command) must not be empty");
        }
        return command;
    }

    /** popen(command, "r") read to its end and pclose()d: the output and the wait status; null if not started. */
    private static Object[] popen(PhpContext context, byte[] command) {
        if (context.response() == null) {
            context.flush();
        }
        return Spawn.readFrom(command, RuntimeFunctions.environment(context),
                context.getEnv().getCurrentWorkingDirectory().getPath());
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b >= '\t' && b <= '\r';
    }

    /**
     * php_exec_ex and php_exec: mode 0 exec (the last line), 1 system (the lines written), 3 passthru (the output
     * written); exec with &$output appends each line, its trailing whitespace stripped; the result code is
     * pclose's (the exit status, or the wait status of a child a signal ended).
     */
    @TruffleBoundary
    private static Object exec(PhpContext context, Object[] a, String function, int mode) {
        Args.arity(a, function, 1, mode == 0 ? 3 : 2);
        byte[] command = command(context, a, function);
        PhpArray lines = null;
        if (mode == 0 && Args.has(a, 1)) {
            pl.codeloom.php.runtime.PhpRef ref = Args.ref(a, 1);
            if (ref.get() instanceof PhpArray existing) {
                lines = existing.isShared() ? existing.copy() : existing;
            } else {
                lines = new PhpArray();
            }
            ref.set(lines);
            mode = 2;
        }
        Object[] result = popen(context, command);
        Object returned;
        int status;
        if (result == null) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(function + "(): Unable to fork ["
                    + new String(command, StandardCharsets.UTF_8) + "]"), SourceLocation.current());
            returned = Boolean.FALSE;
            status = -1;
        } else {
            byte[] output = (byte[]) result[0];
            int wstatus = (Integer) result[1];
            status = (wstatus & 0x7F) == 0 ? (wstatus >> 8) & 0xFF : wstatus;
            if (mode == 3) {
                context.write(Str.of(output));
                returned = PhpNull.NULL;
            } else {
                int last = 0;
                int lastLength = 0;
                for (int start = 0; start < output.length; ) {
                    int end = indexOf(output, (byte) '\n', start);
                    end = end < 0 ? output.length : end + 1;
                    int length = end - start;
                    if (mode == 1) {
                        context.write(Str.of(output, start, length));
                    }
                    while (length > 0 && isSpace(output[start + length - 1])) {
                        length--;
                    }
                    if (mode == 2) {
                        lines.append(Str.of(output, start, length));
                    }
                    last = start;
                    lastLength = length;
                    start = end;
                }
                returned = Str.of(output, last, lastLength);
            }
        }
        int codeArgument = mode == 1 || mode == 3 ? 1 : 2;
        if (Args.has(a, codeArgument)) {
            Args.ref(a, codeArgument).set((long) status);
        }
        return returned;
    }

    /** proc_open.c on Linux (its posix_spawn path): the descriptors, the child, the "process" resource. */
    static final class ProcOpen {
        private static final int O_RDONLY = 0;
        private static final int O_WRONLY = 1;
        private static final int O_RDWR = 2;
        private static final int O_CREAT = 0x40;
        private static final int O_EXCL = 0x80;
        private static final int O_TRUNC = 0x200;
        private static final int O_APPEND = 0x400;
        private static final int O_CLOEXEC = 0x80000;

        private ProcOpen() {
        }

        /** A descriptorspec_item: the child's fd number, the end it gets, the parent's end of a pipe (or -1). */
        private static final class Descriptor {
            final int index;
            int childEnd = -1;
            int parentEnd = -1;
            String mode;

            Descriptor(int index) {
                this.index = index;
            }
        }

        /** A php_process_handle. */
        static final class Process {
            final int pid;
            final TruffleString command;
            final List<PhpResource> pipes = new ArrayList<>();
            /** The wait status once waitpid saw the child exit (has_cached_exit_wait_status). */
            Integer cachedExit;

            Process(int pid, TruffleString command) {
                this.pid = pid;
                this.command = command;
            }

            /** waitpid_cached: the pid, 0 or -1, and the status in status[0]. */
            int waitpid(int[] status, int options) {
                if (cachedExit != null) {
                    status[0] = cachedExit;
                    return pid;
                }
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment wstatus = arena.allocate(ValueLayout.JAVA_INT);
                    MemorySegment state = arena.allocate(Libc.CAPTURED);
                    int waited;
                    do {
                        waited = (int) Libc.WAITPID.invokeExact(state, pid, wstatus, options);
                    } while (waited == -1 && (int) Libc.ERRNO.get(state, 0L) == Spawn.EINTR);
                    status[0] = wstatus.get(ValueLayout.JAVA_INT, 0);
                    if (waited > 0 && (status[0] & 0x7F) == 0) {
                        cachedExit = status[0];
                    }
                    return waited;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            }

            /** proc_open_rsrc_dtor: the pipes closed, then the exit code (waiting when asked), or -1. */
            int close(PhpContext context, boolean wait) {
                for (PhpResource pipe : pipes) {
                    if (pipe.getHandle() instanceof FileFunctions.Stream stream) {
                        context.builtinState().openStreams.remove(stream);
                        FileFunctions.closeStream(stream);
                        pipe.close();
                    }
                }
                int[] status = new int[1];
                if (waitpid(status, wait ? 0 : 1) <= 0) {
                    return -1;
                }
                return (status[0] & 0x7F) == 0 ? (status[0] >> 8) & 0xFF : status[0];
            }

            /** proc_get_status. */
            PhpArray status() {
                PhpArray result = new PhpArray();
                result.put(FileFunctions.key("command"), command);
                result.put(FileFunctions.key("pid"), (long) pid);
                int[] status = new int[1];
                int waited = waitpid(status, 1 | 2); // WNOHANG | WUNTRACED
                boolean running = true;
                boolean signaled = false;
                boolean stopped = false;
                long exitcode = -1;
                long termsig = 0;
                long stopsig = 0;
                int w = status[0];
                if (waited == pid) {
                    if ((w & 0x7F) == 0) {
                        running = false;
                        exitcode = (w >> 8) & 0xFF;
                    }
                    if ((w & 0x7F) != 0 && (w & 0x7F) != 0x7F) {
                        running = false;
                        signaled = true;
                        termsig = w & 0x7F;
                    }
                    if ((w & 0xFF) == 0x7F) {
                        stopped = true;
                        stopsig = (w >> 8) & 0xFF;
                    }
                } else if (waited == -1) {
                    running = false;
                }
                result.put(FileFunctions.key("cached"), cachedExit != null);
                result.put(FileFunctions.key("running"), running);
                result.put(FileFunctions.key("signaled"), signaled);
                result.put(FileFunctions.key("stopped"), stopped);
                result.put(FileFunctions.key("exitcode"), exitcode);
                result.put(FileFunctions.key("termsig"), termsig);
                result.put(FileFunctions.key("stopsig"), stopsig);
                return result;
            }
        }

        /** A pipe's parent end as php_stream_fopen_from_fd makes it: read(2) and write(2), not seekable. */
        static final class PipeStream extends FileFunctions.Stream {
            private final int fd;

            PipeStream(int fd, String mode) {
                super(mode, "");
                this.fd = fd;
            }

            @Override
            int rawRead(byte[] into, int offset, int length) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buffer = arena.allocate(Math.max(1, length));
                    MemorySegment state = arena.allocate(Libc.CAPTURED);
                    long n;
                    do {
                        n = (long) Libc.READ.invokeExact(state, fd, buffer, (long) length);
                    } while (n < 0 && (int) Libc.ERRNO.get(state, 0L) == Spawn.EINTR);
                    if (n <= 0) {
                        eof = true;
                        return n < 0 ? -1 : 0;
                    }
                    MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, into, offset, (int) n);
                    return (int) n;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            }

            @Override
            int rawWrite(byte[] bytes, int offset, int length) {
                try (Arena arena = Arena.ofConfined()) {
                    byte[] slice = Arrays.copyOfRange(bytes, offset, offset + length);
                    return Spawn.write(arena, fd, slice) == 0 ? length : -1;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            }

            @Override
            boolean greedy() {
                return false;
            }

            @Override
            void rawClose() {
                try {
                    int ignored = (int) Libc.CLOSE.invokeExact(fd);
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            }

            @Override
            String wrapperType() {
                return null;
            }

            @Override
            String streamType() {
                return "STDIO";
            }
        }

        static PhpResource resource(Object[] a, String function) {
            Object value = Args.get(a, 0, PhpNull.NULL);
            if (!(value instanceof PhpResource resource)) {
                throw Args.typeError(function, 1, "process", "resource", value);
            }
            if (!(resource.getHandle() instanceof Process)) {
                throw new PhpError("TypeError", function + "(): supplied resource is not a valid process resource");
            }
            return resource;
        }

        private static String strerror(int errno) {
            try {
                return ((MemorySegment) Libc.STRERROR.invokeExact(errno)).reinterpret(4096).getString(0);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        private static void warn(PhpContext context, String message) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("proc_open(): " + message),
                    SourceLocation.current());
        }

        @TruffleBoundary
        static Object open(PhpContext context, Object[] a) {
            Args.arity(a, "proc_open", 3, 6);
            Object commandArg = Args.get(a, 0);
            List<byte[]> argv = null;
            TruffleString command;
            if (commandArg instanceof PhpArray list) {
                if (list.size() == 0) {
                    throw new PhpError("ValueError", "proc_open(): Argument #1 ($command) must not be empty");
                }
                argv = new ArrayList<>();
                Object[] entries = list.entries();
                for (int i = 1; i < entries.length; i += 2) {
                    byte[] arg = Str.bytes(Convert.toStr(entries[i]));
                    if (i == 1 && arg.length == 0) {
                        throw new PhpError("ValueError", "First element must contain a non-empty program name");
                    }
                    if (indexOf(arg, (byte) 0, 0) >= 0) {
                        throw new PhpError("ValueError", "Command array element " + (i / 2 + 1)
                                + " contains a null byte");
                    }
                    argv.add(arg);
                }
                command = Str.of(argv.get(0));
            } else {
                command = Args.string(context, a, 0, "proc_open", "command", false);
            }
            Object spec = Args.get(a, 1);
            if (!(spec instanceof PhpArray specs)) {
                throw Args.typeError("proc_open", 2, "descriptor_spec", "array", spec);
            }
            TruffleString cwd = Args.get(a, 3, PhpNull.NULL) == PhpNull.NULL ? null
                    : Args.string(context, a, 3, "proc_open", "cwd", true);
            Object environmentArg = Args.get(a, 4, PhpNull.NULL);
            if (environmentArg != PhpNull.NULL && !(environmentArg instanceof PhpArray)) {
                throw Args.typeError("proc_open", 5, "env_vars", "?array", environmentArg);
            }
            List<byte[]> envp = new ArrayList<>();
            if (environmentArg instanceof PhpArray variables) {
                Object[] entries = variables.entries();
                for (int i = 0; i < entries.length; i += 2) {
                    byte[] value = Str.bytes(Convert.toStr(entries[i + 1]));
                    if (value.length > 0) {
                        envp.add(entries[i] instanceof TruffleString key && Str.length(key) > 0
                                ? concat(Str.bytes(key), (byte) '=', value) : value);
                    }
                }
            } else {
                for (Map.Entry<String, String> variable : RuntimeFunctions.environment(context).entrySet()) {
                    envp.add((variable.getKey() + "=" + variable.getValue()).getBytes(StandardCharsets.UTF_8));
                }
            }
            List<Descriptor> descriptors = new ArrayList<>();
            try (Arena arena = Arena.ofConfined()) {
                Object[] entries = specs.entries();
                for (int i = 0; i < entries.length; i += 2) {
                    if (!(entries[i] instanceof Long index)) {
                        throw new PhpError("ValueError", "proc_open(): Argument #2 ($descriptor_spec) must be an "
                                + "integer indexed array");
                    }
                    Descriptor descriptor = new Descriptor((int) (long) index);
                    if (entries[i + 1] instanceof PhpResource resource) {
                        int fd = resource.getHandle() instanceof PipeStream pipe ? pipe.fd
                                : resource.getHandle() instanceof FileFunctions.StdStream std ? std.fd() : -1;
                        if (fd < 0) {
                            warn(context, "Cannot represent a stream of type " + (resource.getHandle()
                                    instanceof FileFunctions.Stream stream ? stream.streamType() : "Unknown")
                                    + " as a File Descriptor");
                            return fail(descriptors);
                        }
                        descriptor.childEnd = (int) Libc.DUP.invokeExact(fd);
                    } else if (entries[i + 1] instanceof PhpArray item) {
                        if (!describe(context, arena, item, descriptor, descriptors)) {
                            return fail(descriptors);
                        }
                    } else {
                        throw new PhpError("ValueError", "proc_open(): Argument #2 ($descriptor_spec) must only "
                                + "contain arrays and streams");
                    }
                    descriptors.add(descriptor);
                }
                if (context.response() == null) {
                    context.flush();
                }
                MemorySegment actions = arena.allocate(256, 16);
                int ignored = (int) Libc.FILE_ACTIONS_INIT.invokeExact(actions);
                // the child's signal mask emptied, as Spawn's (a JVM thread's isn't php's)
                MemorySegment attributes = arena.allocate(1024, 16);
                MemorySegment signals = arena.allocate(256, 16);
                ignored = (int) Libc.SPAWNATTR_INIT.invokeExact(attributes);
                ignored = (int) Libc.SIGEMPTYSET.invokeExact(signals);
                ignored = (int) Libc.SPAWNATTR_SETSIGMASK.invokeExact(attributes, signals);
                ignored = (int) Libc.SPAWNATTR_SETFLAGS.invokeExact(attributes, Spawn.POSIX_SPAWN_SETSIGMASK);
                int pid;
                try {
                    for (Descriptor d : descriptors) {
                        if (d.parentEnd >= 0) {
                            ignored = (int) Libc.FILE_ACTIONS_ADDCLOSE.invokeExact(actions, d.parentEnd);
                        }
                        if (d.childEnd != d.index) {
                            ignored = (int) Libc.FILE_ACTIONS_ADDDUP2.invokeExact(actions, d.childEnd, d.index);
                            ignored = (int) Libc.FILE_ACTIONS_ADDCLOSE.invokeExact(actions, d.childEnd);
                        }
                    }
                    String directory = cwd != null ? Str.java(cwd)
                            : context.getEnv().getCurrentWorkingDirectory().getPath();
                    if ((cwd != null || !directory.equals(System.getProperty("user.dir"))) && AddChdir.AVAILABLE) {
                        int error = (int) Libc.FILE_ACTIONS_ADDCHDIR.invokeExact(actions,
                                Spawn.cString(arena, directory));
                        if (error != 0) {
                            warn(context, "posix_spawn_file_actions_addchdir_np() failed: " + strerror(error));
                        }
                    }
                    MemorySegment child = arena.allocate(ValueLayout.JAVA_INT);
                    int error = argv != null
                            ? (int) Libc.POSIX_SPAWNP.invokeExact(child, Spawn.cString(arena, argv.get(0)), actions,
                                    attributes, Spawn.pointers(arena, argv), Spawn.pointers(arena, envp))
                            : (int) Libc.POSIX_SPAWN.invokeExact(child, Spawn.cString(arena, "/bin/sh"), actions,
                                    attributes, Spawn.pointers(arena, List.of(
                                            "sh".getBytes(StandardCharsets.US_ASCII),
                                            "-c".getBytes(StandardCharsets.US_ASCII), Str.bytes(command))),
                                    Spawn.pointers(arena, envp));
                    if (error != 0) {
                        warn(context, "posix_spawn() failed: " + strerror(error));
                        return fail(descriptors);
                    }
                    pid = child.get(ValueLayout.JAVA_INT, 0);
                } finally {
                    ignored = (int) Libc.FILE_ACTIONS_DESTROY.invokeExact(actions);
                    ignored = (int) Libc.SPAWNATTR_DESTROY.invokeExact(attributes);
                }
                PhpArray pipes = new PhpArray();
                Process process = new Process(pid, command);
                for (Descriptor d : descriptors) {
                    ignored = (int) Libc.CLOSE.invokeExact(d.childEnd);
                    if (d.parentEnd >= 0) {
                        PhpResource pipe = FileFunctions.resource(context, new PipeStream(d.parentEnd, d.mode));
                        pipes.put((long) d.index, pipe);
                        process.pipes.add(pipe);
                    }
                }
                Args.ref(a, 2).set(pipes);
                return new PhpResource(context.nextResourceId(), "process", process);
            } catch (PhpError e) {
                fail(descriptors);
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("proc_open failed", t);
            }
        }

        private static byte[] concat(byte[] key, byte separator, byte[] value) {
            byte[] result = Arrays.copyOf(key, key.length + 1 + value.length);
            result[key.length] = separator;
            System.arraycopy(value, 0, result, key.length + 1, value.length);
            return result;
        }

        /** close_all_descriptors, then false. */
        private static Object fail(List<Descriptor> descriptors) {
            try {
                for (Descriptor d : descriptors) {
                    int ignored = d.childEnd >= 0 ? (int) Libc.CLOSE.invokeExact(d.childEnd) : 0;
                    ignored = d.parentEnd >= 0 ? (int) Libc.CLOSE.invokeExact(d.parentEnd) : 0;
                }
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            return Boolean.FALSE;
        }

        private static TruffleString parameter(PhpArray item, long index, String name) {
            Object value = item.get(index);
            if (value == null) {
                throw new PhpError("ValueError", "Missing " + name);
            }
            return Convert.toStr(value instanceof pl.codeloom.php.runtime.PhpRef ref ? ref.get() : value);
        }

        /** set_proc_descriptor_from_array: false after a warning. */
        private static boolean describe(PhpContext context, Arena arena, PhpArray item, Descriptor descriptor,
                                        List<Descriptor> descriptors) throws Throwable {
            String type = Str.java(parameter(item, 0, "handle qualifier"));
            switch (type) {
                case "pipe" -> {
                    String mode = Str.java(parameter(item, 1, "mode parameter for 'pipe'"));
                    MemorySegment fds = arena.allocate(ValueLayout.JAVA_INT, 2);
                    if ((int) Libc.PIPE2.invokeExact(fds, 0) != 0) {
                        warn(context, "Unable to create pipe");
                        return false;
                    }
                    boolean parentWrites = !mode.startsWith("w");
                    descriptor.parentEnd = fds.getAtIndex(ValueLayout.JAVA_INT, parentWrites ? 1 : 0);
                    descriptor.childEnd = fds.getAtIndex(ValueLayout.JAVA_INT, parentWrites ? 0 : 1);
                    descriptor.mode = parentWrites ? "w" : "r";
                    int ignored = (int) Libc.FCNTL.invokeExact(descriptor.parentEnd, 2, 1); // F_SETFD, FD_CLOEXEC
                    return true;
                }
                case "file" -> {
                    String file = Str.java(parameter(item, 1, "file name parameter for 'file'"));
                    String mode = Str.java(parameter(item, 2, "mode parameter for 'file'"));
                    char kind = mode.isEmpty() ? 0 : mode.charAt(0);
                    boolean plus = mode.indexOf('+') >= 0;
                    int flags = switch (kind) {
                        case 'r' -> plus ? O_RDWR : O_RDONLY;
                        case 'w' -> (plus ? O_RDWR : O_WRONLY) | O_CREAT | O_TRUNC;
                        case 'a' -> (plus ? O_RDWR : O_WRONLY) | O_CREAT | O_APPEND;
                        case 'x' -> (plus ? O_RDWR : O_WRONLY) | O_CREAT | O_EXCL;
                        case 'c' -> (plus ? O_RDWR : O_WRONLY) | O_CREAT;
                        default -> -1;
                    };
                    if (flags < 0) {
                        warn(context, "`" + mode + "' is not a valid mode for fopen");
                        return false;
                    }
                    String path = file.regionMatches(true, 0, "file://", 0, 7) ? file.substring(7) : file;
                    MemorySegment state = arena.allocate(Libc.CAPTURED);
                    descriptor.childEnd = (int) Libc.OPEN.invokeExact(state, Spawn.cString(arena,
                            context.getEnv().getCurrentWorkingDirectory().resolve(path).getPath()), flags, 0666);
                    if (descriptor.childEnd < 0) {
                        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("proc_open(" + file
                                + "): Failed to open stream: " + strerror((int) Libc.ERRNO.get(state, 0L))),
                                SourceLocation.current());
                        return false;
                    }
                    return true;
                }
                case "redirect" -> {
                    Object target = item.get(1L);
                    target = target instanceof pl.codeloom.php.runtime.PhpRef ref ? ref.get() : target;
                    if (target == null) {
                        throw new PhpError("ValueError", "Missing redirection target");
                    }
                    if (!(target instanceof Long t)) {
                        throw new PhpError("ValueError", "Redirection target must be of type int, "
                                + Args.valueName(target) + " given");
                    }
                    int redirectTo = -1;
                    for (Descriptor d : descriptors) {
                        if (d.index == t) {
                            redirectTo = d.childEnd;
                            break;
                        }
                    }
                    if (redirectTo < 0) {
                        if (t < 0 || t > 2) {
                            warn(context, "Redirection target " + t + " not found");
                            return false;
                        }
                        redirectTo = (int) (long) t;
                    }
                    descriptor.childEnd = (int) Libc.DUP.invokeExact(redirectTo);
                    return true;
                }
                case "null" -> {
                    MemorySegment state = arena.allocate(Libc.CAPTURED);
                    descriptor.childEnd = (int) Libc.OPEN.invokeExact(state, Spawn.cString(arena, "/dev/null"), O_RDWR,
                            0);
                    return true;
                }
                default -> {
                    warn(context, type + " is not a valid descriptor spec/mode");
                    return false;
                }
            }
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
        static final MethodHandle READ = NativeLibrary.LIBC.downcall("read",
                FunctionDescriptor.of(NativeLibrary.SIZE_T, INT, PTR, NativeLibrary.SIZE_T),
                Linker.Option.captureCallState("errno"));
        static final MethodHandle OPEN = NativeLibrary.LIBC.downcall("open", FunctionDescriptor.of(INT, PTR, INT,
                INT), Linker.Option.firstVariadicArg(2), Linker.Option.captureCallState("errno"));
        static final MethodHandle DUP = libc("dup", INT, INT);
        static final MethodHandle GETRUSAGE = libc("getrusage", INT, INT, PTR);
        static final MethodHandle FCNTL = NativeLibrary.LIBC.downcall("fcntl", FunctionDescriptor.of(INT, INT, INT,
                INT), Linker.Option.firstVariadicArg(2));
        static final MethodHandle KILL = libc("kill", INT, INT, INT);
        static final MethodHandle STRERROR = libc("strerror", PTR, INT);
        static final MethodHandle FILE_ACTIONS_ADDCLOSE = libc("posix_spawn_file_actions_addclose", INT, PTR, INT);
        static final MethodHandle POSIX_SPAWNP = libc("posix_spawnp", INT, PTR, PTR, PTR, PTR, PTR, PTR);
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
