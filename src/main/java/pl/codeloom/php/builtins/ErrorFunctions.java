package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpBailout;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * PHP's error handling: zend_error (a user error handler first), php_error_cb (the last error, the display and
 * the log, a fatal error's 500 and bailout), uncaught exceptions (the user exception handler, else
 * zend_exception_error), and the functions over them: error_reporting, set_error_handler, trigger_error,
 * error_get_last.
 */
public final class ErrorFunctions {
    public static final int E_ERROR = 1;
    public static final int E_WARNING = 2;
    public static final int E_PARSE = 4;
    public static final int E_NOTICE = 8;
    public static final int E_CORE_ERROR = 16;
    public static final int E_CORE_WARNING = 32;
    public static final int E_COMPILE_ERROR = 64;
    public static final int E_COMPILE_WARNING = 128;
    public static final int E_USER_ERROR = 256;
    public static final int E_USER_WARNING = 512;
    public static final int E_USER_NOTICE = 1024;
    /** No longer raised, still defined. */
    public static final int E_STRICT = 2048;
    public static final int E_RECOVERABLE_ERROR = 4096;
    public static final int E_DEPRECATED = 8192;
    public static final int E_USER_DEPRECATED = 16384;
    public static final int E_ALL = 30719;
    /** The exit status after a fatal error. */
    public static final int FATAL_EXIT_STATUS = 255;
    /** The errors that end the script, which `@` doesn't silence. */
    public static final int E_FATAL_ERRORS = E_ERROR | E_CORE_ERROR | E_COMPILE_ERROR | E_USER_ERROR
            | E_RECOVERABLE_ERROR | E_PARSE;
    private static final int E_CORE = E_CORE_ERROR | E_CORE_WARNING;
    /** Errors not safe to handle in user code: zend_error never passes them to the user error handler. */
    private static final int NOT_USER_HANDLED = E_ERROR | E_PARSE | E_CORE_ERROR | E_CORE_WARNING | E_COMPILE_ERROR
            | E_COMPILE_WARNING;

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ROOT);

    private ErrorFunctions() {
    }

    /** An error handler set_error_handler() replaced, with the levels it was set for. */
    private record SavedHandler(Object handler, int levels) {
    }

    /** The last error displayed or logged (PG(last_error_*)), which error_get_last() gives. */
    private record LastError(int type, TruffleString message, String file, int line) {
    }

    /** A context's error state: php_core_globals' and the executor's (the ini settings are the context's Ini). */
    public static final class State {
        Object errorHandler = PhpNull.NULL;
        int errorHandlerLevels = E_ALL;
        final Deque<SavedHandler> errorHandlers = new ArrayDeque<>();
        Object exceptionHandler = PhpNull.NULL;
        final Deque<Object> exceptionHandlers = new ArrayDeque<>();
        /** Null when there is none. */
        LastError lastError;
        /** EG(last_fatal_error_backtrace): where the last fatal error was raised (fatal_error_backtraces), or null. */
        PhpArray fatalBacktrace;
        /** FATAL_EXIT_STATUS after a fatal error; the script's exit status unless exit() gives another. */
        int exitStatus;

        public int exitStatus() {
            return exitStatus;
        }
    }

    // ---- raising ----

    /**
     * zend_error: the user error handler gets the error if it is set for its level and the error is safe to handle
     * in user code (the handler runs unset, so errors it raises take the default path); otherwise, or when the
     * handler returns false, php_error_cb. A fatal error has the backtrace of where it was raised.
     */
    @TruffleBoundary
    public static void raise(PhpContext context, int type, TruffleString message, SourceLocation at) {
        State state = context.errors();
        PhpArray backtrace = fatalBacktrace(context, type, 0);
        Object handler = state.errorHandler;
        if (handler == PhpNull.NULL || (state.errorHandlerLevels & type) == 0 || (type & NOT_USER_HANDLED) != 0) {
            report(context, type, message, at, backtrace, true);
            return;
        }
        state.errorHandler = PhpNull.NULL;
        Object handled;
        try {
            handled = Callables.call(context, handler, (long) type, message, Str.of(at.file()), (long) at.line());
        } finally {
            if (state.errorHandler == PhpNull.NULL) {
                state.errorHandler = handler;
            }
        }
        if (Boolean.FALSE.equals(PhpRef.deref(handled))) {
            report(context, type, message, at, backtrace, true);
        }
    }

    /**
     * zend_error_noreturn for a fatal error no user handler takes (E_ERROR, E_COMPILE_ERROR): reported, and the
     * script ends with the bailout the caller throws. The backtrace leaves out the innermost skip frames.
     */
    @TruffleBoundary
    public static PhpBailout fatal(PhpContext context, int type, TruffleString message, SourceLocation at, int skip) {
        assert (type & E_FATAL_ERRORS & NOT_USER_HANDLED) == type : type;
        report(context, type, message, at, fatalBacktrace(context, type, skip), false);
        return new PhpBailout();
    }

    /** EG(last_fatal_error_backtrace), cleared by every error and taken for a fatal one: where it was raised. */
    private static PhpArray fatalBacktrace(PhpContext context, int type, int skip) {
        State state = context.errors();
        state.fatalBacktrace = (type & E_FATAL_ERRORS) != 0 && context.ini().fatalErrorBacktraces()
                ? Backtrace.here(Backtrace.exceptionOptions(), 0, skip) : null;
        return state.fatalBacktrace;
    }

    /**
     * php_error_cb: records the last error, then displays and logs it as error_reporting, display_errors,
     * html_errors and log_errors say. A fatal error makes the exit status 255 and, when it isn't displayed, the
     * response a 500 (if its headers haven't gone out); then it ends the script unless bail is false (an uncaught
     * exception's report, E_DONT_BAIL).
     */
    @TruffleBoundary
    static void report(PhpContext context, int type, TruffleString message, SourceLocation at, PhpArray backtrace,
                       boolean bail) {
        State state = context.errors();
        Ini ini = context.ini();
        LastError last = state.lastError;
        boolean display = !ini.ignoreRepeatedErrors() || last == null || !Str.equal(last.message(), message)
                || !ini.ignoreRepeatedSource() && (last.line() != at.line() || !last.file().equals(at.file()));
        if (display) {
            state.lastError = new LastError(type, message, at.file(), at.line());
        }
        Ini.DisplayMode displayMode = ini.displayErrors();
        if (display && ((ini.errorReporting() & type) != 0 || (type & E_CORE) != 0)
                && (ini.logErrors() || displayMode != Ini.DisplayMode.OFF)) {
            String typeName = typeName(type);
            TruffleString trace = backtrace == null ? null : Backtrace.traceToString(backtrace, true);
            if (ini.logErrors()) {
                ByteBuilder line = new ByteBuilder();
                line.writeAscii("PHP " + typeName + ":  ");
                located(line, message, at, false, trace);
                log(context, line.toByteArray());
            }
            if (displayMode != Ini.DisplayMode.OFF) {
                ByteBuilder out = new ByteBuilder();
                if (displayMode == Ini.DisplayMode.STDERR && !ini.htmlErrors()) {
                    out.writeAscii(typeName + ": ");
                    located(out, message, at, false, trace);
                    out.write('\n');
                    context.writeError(out.toByteArray());
                } else {
                    writeUtf8(out, ini.errorPrependString());
                    if (!ini.htmlErrors()) {
                        out.writeAscii("\n" + typeName + ": ");
                        located(out, message, at, false, trace);
                        out.write('\n');
                    } else if (type == E_ERROR || type == E_PARSE) {
                        out.writeAscii("<br />\n<b>" + typeName + "</b>:  ");
                        located(out, Html.escapeForError(message), at, true, null);
                        out.write('\n');
                    } else {
                        out.writeAscii("<br />\n<b>" + typeName + "</b>:  ");
                        located(out, message, at, true, trace);
                        out.write('\n');
                    }
                    writeUtf8(out, ini.errorAppendString());
                    context.write(Str.of(out.toByteArray()));
                }
            }
        }
        if ((type & E_FATAL_ERRORS) != 0) {
            state.exitStatus = FATAL_EXIT_STATUS;
            CgiSapi.Response response = context.response();
            if (displayMode == Ini.DisplayMode.OFF && response != null && !response.isSent()
                    && response.status() == 200) {
                response.setStatus(500);
            }
            if (bail) {
                throw new PhpBailout();
            }
        }
    }

    /**
     * "{message} in {file} on line {line}", with HTML's markup around the file and line, then the trace (fatal
     * errors' with fatal_error_backtraces).
     */
    private static void located(ByteBuilder out, TruffleString message, SourceLocation at, boolean html,
                                TruffleString trace) {
        out.write(message);
        out.writeAscii(html ? " in <b>" : " in ");
        writeUtf8(out, at.file());
        out.writeAscii(html ? "</b> on line <b>" : " on line ");
        out.writeLong(at.line());
        if (html) {
            out.writeAscii("</b><br />");
        }
        if (trace != null) {
            out.writeAscii("\nStack trace:\n");
            out.write(trace);
        }
    }

    private static String typeName(int type) {
        return switch (type) {
            case E_ERROR, E_CORE_ERROR, E_COMPILE_ERROR, E_USER_ERROR -> "Fatal error";
            case E_RECOVERABLE_ERROR -> "Recoverable fatal error";
            case E_WARNING, E_CORE_WARNING, E_COMPILE_WARNING, E_USER_WARNING -> "Warning";
            case E_PARSE -> "Parse error";
            case E_NOTICE, E_USER_NOTICE -> "Notice";
            case E_DEPRECATED, E_USER_DEPRECATED -> "Deprecated";
            default -> "Unknown error";
        };
    }

    /**
     * php_log_err: appended to error_log's file with the time ("[24-Oct-2026 10:00:00 UTC] ..."), or, without
     * one or when it can't be opened, the SAPI's log, which the CLI and CGI SAPIs write to stderr.
     */
    private static void log(PhpContext context, byte[] line) {
        String path = context.ini().errorLog();
        if (path != null && !path.isEmpty()) {
            try {
                TruffleFile file = context.getEnv().getPublicTruffleFile(path);
                try (OutputStream out = file.newOutputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE)) {
                    ZonedDateTime now = ZonedDateTime.now(context.timezone());
                    ByteBuilder entry = new ByteBuilder();
                    entry.writeAscii("[" + LOG_TIME.format(now) + " " + context.timezone().getId() + "] ");
                    entry.write(line);
                    entry.write('\n');
                    out.write(entry.toByteArray());
                }
                return;
            } catch (IOException | SecurityException | UnsupportedOperationException e) {
                // php-src falls back to the SAPI's log
            }
        }
        ByteBuilder entry = new ByteBuilder();
        entry.write(line);
        entry.write('\n');
        context.writeError(entry.toByteArray());
    }

    // ---- uncaught exceptions ----

    /**
     * An exception the script didn't catch (zend_execute_script): the user exception handler gets it, else
     * zend_exception_error reports it as a fatal error. One the handler throws is reported as uncaught.
     */
    @TruffleBoundary
    public static void uncaught(PhpContext context, PhpThrowable exception) {
        State state = context.errors();
        try {
            PhpObject throwable = exception.toThrowable(context);
            if (state.exceptionHandler == PhpNull.NULL) {
                exceptionError(context, throwable);
                return;
            }
            Object handler = state.exceptionHandler;
            state.exceptionHandlers.push(handler);
            state.exceptionHandler = PhpNull.NULL;
            Callables.call(context, handler, throwable);
            if (state.exceptionHandler == PhpNull.NULL && !state.exceptionHandlers.isEmpty()) {
                state.exceptionHandler = state.exceptionHandlers.pop();
            }
        } catch (PhpThrowable thrown) {
            exceptionError(context, thrown.toThrowable(context));
        } catch (PhpBailout ended) {
            // reported where it was raised
        }
    }

    /** An exception thrown out of code the runtime called with no PHP frame below (a shutdown function). */
    @TruffleBoundary
    public static void uncaughtInternal(PhpContext context, PhpThrowable exception) {
        exceptionError(context, exception.toThrowable(context));
    }

    /**
     * zend_exception_error: the Throwable as its __toString() gives it (stored in its "string" property) is
     * reported as an uncaught fatal error, at its file and line, without a backtrace and without ending the script
     * where it is (the script has ended).
     */
    private static void exceptionError(PhpContext context, PhpObject throwable) {
        State state = context.errors();
        state.fatalBacktrace = null;
        try {
            Object string = PhpRef.deref(throwable.callMethod("__tostring"));
            if (string instanceof TruffleString text) {
                throwable.setProperty("string", text);
            } else {
                raise(context, E_WARNING, Str.of(throwable.getPhpClass().getName()
                        + "::__toString() must return a string"), SourceLocation.UNKNOWN);
            }
        } catch (PhpThrowable thrown) {
            PhpObject inner = thrown.toThrowable(context);
            boolean located = throwable.getPhpClass().isSubclassOf("exception")
                    || throwable.getPhpClass().isSubclassOf("error");
            raise(context, E_WARNING, Str.of("Uncaught " + inner.getPhpClass().getName()
                    + " in exception handling during call to " + throwable.getPhpClass().getName() + "::__toString()"),
                    located ? locationOf(inner) : SourceLocation.UNKNOWN);
        }
        ByteBuilder text = new ByteBuilder();
        text.writeAscii("Uncaught ");
        text.write(Convert.toStr(Builtins.property(throwable, "string")));
        text.writeAscii("\n  thrown");
        report(context, E_ERROR, Str.of(text.toByteArray()), locationOf(throwable), null, false);
    }

    private static SourceLocation locationOf(PhpObject throwable) {
        String file = Str.java(Convert.toStr(Builtins.property(throwable, "file")));
        return new SourceLocation(file.isEmpty() ? SourceLocation.UNKNOWN.file() : file,
                (int) Convert.toLong(Builtins.property(throwable, "line")));
    }

    // ---- the functions ----

    static void register(Registry r) {
        r.fn("error_reporting", (context, a) -> {
            long previous = context.ini().errorReporting();
            if (Args.has(a, 0) && Args.get(a, 0) != PhpNull.NULL) {
                context.ini().set("error_reporting", Integer.toString((int) Args.lng(a, 0, 0)));
            }
            return previous;
        });
        r.fn("set_error_handler", (context, a) -> {
            State state = context.errors();
            Object handler = Args.get(a, 0, PhpNull.NULL);
            if (handler != PhpNull.NULL) {
                Callables.check(context, handler, new Callables.Parameter("set_error_handler", 1, true));
            }
            Object previous = state.errorHandler;
            state.errorHandlers.push(new SavedHandler(state.errorHandler, state.errorHandlerLevels));
            state.errorHandler = handler;
            if (handler != PhpNull.NULL) {
                state.errorHandlerLevels = (int) Args.lng(a, 1, E_ALL);
            }
            return previous;
        });
        r.fn("restore_error_handler", (context, a) -> {
            State state = context.errors();
            SavedHandler previous = state.errorHandlers.poll();
            if (previous == null) {
                state.errorHandler = PhpNull.NULL;
            } else {
                state.errorHandler = previous.handler();
                state.errorHandlerLevels = previous.levels();
            }
            return Boolean.TRUE;
        });
        r.fn("set_exception_handler", (context, a) -> {
            State state = context.errors();
            Object handler = Args.get(a, 0, PhpNull.NULL);
            if (handler != PhpNull.NULL) {
                Callables.check(context, handler, new Callables.Parameter("set_exception_handler", 1, true));
            }
            Object previous = state.exceptionHandler;
            state.exceptionHandlers.push(state.exceptionHandler);
            state.exceptionHandler = handler;
            return previous;
        });
        r.fn("restore_exception_handler", (context, a) -> {
            State state = context.errors();
            Object previous = state.exceptionHandlers.poll();
            state.exceptionHandler = previous == null ? PhpNull.NULL : previous;
            return Boolean.TRUE;
        });
        r.fn("trigger_error", (context, a) -> triggerError(context, Args.str(a, 0), Args.lng(a, 1, E_USER_NOTICE)));
        r.fn("user_error", (context, a) -> triggerError(context, Args.str(a, 0), Args.lng(a, 1, E_USER_NOTICE)));
        r.fn("error_get_last", (context, a) -> lastError(context.errors()));
        r.fn("error_clear_last", (context, a) -> {
            State state = context.errors();
            state.lastError = null;
            state.fatalBacktrace = null;
            return PhpNull.NULL;
        });
    }

    /**
     * trigger_error(): raised at the caller's line. E_USER_ERROR is deprecated since PHP 8.4 (an E_DEPRECATED
     * first), and fatal unless a user error handler takes it.
     */
    @TruffleBoundary
    private static Object triggerError(PhpContext context, TruffleString message, long level) {
        if (level != E_USER_ERROR && level != E_USER_WARNING && level != E_USER_NOTICE && level != E_USER_DEPRECATED) {
            throw new PhpError("ValueError", "trigger_error(): Argument #2 ($error_level) must be one of E_USER_ERROR,"
                    + " E_USER_WARNING, E_USER_NOTICE, or E_USER_DEPRECATED");
        }
        SourceLocation at = SourceLocation.current();
        if (level == E_USER_ERROR) {
            raise(context, E_DEPRECATED, Str.of("Passing E_USER_ERROR to trigger_error() is deprecated since 8.4,"
                    + " throw an exception or call exit with a string message instead"), at);
        }
        raise(context, (int) level, message, at);
        return Boolean.TRUE;
    }

    private static Object lastError(State state) {
        LastError last = state.lastError;
        if (last == null) {
            return PhpNull.NULL;
        }
        PhpArray error = new PhpArray();
        error.put(Str.of("type"), (long) last.type());
        error.put(Str.of("message"), last.message());
        error.put(Str.of("file"), Str.of(last.file()));
        error.put(Str.of("line"), (long) last.line());
        if (state.fatalBacktrace != null) {
            error.put(Str.of("trace"), state.fatalBacktrace);
        }
        return error;
    }

    /** A string from the ini settings or the host (a path), as its UTF-8 bytes; nothing for null. */
    private static void writeUtf8(ByteBuilder out, String text) {
        if (text != null) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
