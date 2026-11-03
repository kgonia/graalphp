package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.stmt.TryNode;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;

/**
 * ext/standard/mail.c: mail(), which checks and cleans its arguments (php_mail_build_headers for an array of
 * headers), and php_mail, which logs the call (mail.log: a file, or syslog), adds X-PHP-Originating-Script
 * (mail.add_x_header) and writes the message to sendmail_path run through the shell (ExecFunctions.pipeTo).
 */
final class MailFunctions {
    private MailFunctions() {
    }

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LF = {'\n'};
    /** EX_TEMPFAIL (sysexits.h): sendmail queued the message, which php_mail counts as sent. */
    private static final int EX_TEMPFAIL = 75;

    static void register(Registry r) {
        r.fn("mail", (context, a) -> mail(context, a));
    }

    /** PHP_FUNCTION(mail). */
    @TruffleBoundary
    private static Object mail(PhpContext context, Object[] a) {
        Args.arity(a, "mail", 3, 5);
        byte[] to = path(context, a, 0, "to");
        byte[] subject = path(context, a, 1, "subject");
        byte[] message = path(context, a, 2, "message");
        PhpArray headerArray = null;
        byte[] headers = null;
        if (Args.has(a, 3)) {
            Object value = Args.get(a, 3);
            if (value instanceof PhpArray array) {
                headerArray = array;
            } else {
                headers = Str.bytes(arrayOrString(context, value));
            }
        }
        byte[] extra = Args.has(a, 4) ? path(context, a, 4, "additional_params") : null;

        if (headers != null) {
            if (indexOf(headers, 0) >= 0) {
                throw new PhpError("ValueError", "mail(): Argument #4 ($additional_headers) must not contain any "
                        + "null bytes");
            }
            headers = rtrim(headers);
        } else if (headerArray != null) {
            headers = buildHeaders(context, headerArray);
        }
        to = headerText(to);
        subject = headerText(subject);

        // mail.force_extra_parameters stands in for the argument; an over-long one is a ValueError, pending while
        // php_mail still logs the call
        String forced = context.ini().get("mail.force_extra_parameters");
        byte[] parameters = forced != null ? Str.bytes(Str.of(forced)) : extra;
        PhpError pending = null;
        if (parameters != null) {
            parameters = ExecFunctions.escapeShellCmd(parameters);
            if (parameters == null) {
                pending = ExecFunctions.commandTooLong();
                parameters = new byte[0];
            }
        }
        return send(context, to, subject, message, headers != null && headers.length > 0 ? headers : null,
                parameters, pending);
    }

    /** Z_PARAM_PATH: a string without NUL bytes. */
    private static byte[] path(PhpContext context, Object[] a, int index, String name) {
        byte[] value = Str.bytes(Args.string(context, a, index, "mail", name, false));
        if (indexOf(value, 0) >= 0) {
            throw new PhpError("ValueError", "mail(): Argument #" + (index + 1) + " ($" + name
                    + ") must not contain any null bytes");
        }
        return value;
    }

    /** Z_PARAM_ARRAY_HT_OR_STR's string branch (zend_parse_arg_str_weak), for a value that isn't an array. */
    private static TruffleString arrayOrString(PhpContext context, Object value) {
        if (value instanceof TruffleString s) {
            return s;
        }
        if (value == PhpNull.NULL) {
            Args.deprecateNull(context, "mail", 3, "additional_headers", "array|string");
            return Str.EMPTY;
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return Convert.toStr(value);
        }
        if (value instanceof PhpObject object && object.getPhpClass().findMethod("__tostring") != null) {
            return object.toPhpString();
        }
        throw Args.typeError("mail", 4, "additional_headers", "array|string", value);
    }

    /** php_trim(headers, NULL, 0, 2): trailing " \t\n\r\v\0" off. */
    private static byte[] rtrim(byte[] s) {
        int end = s.length;
        while (end > 0 && (s[end - 1] == ' ' || s[end - 1] == '\t' || s[end - 1] == '\n' || s[end - 1] == '\r'
                || s[end - 1] == 0x0B || s[end - 1] == 0)) {
            end--;
        }
        return end == s.length ? s : java.util.Arrays.copyOf(s, end);
    }

    /**
     * The to and subject arguments as php_mail gets them: trailing whitespace (isspace) off, and each control
     * character a space, except a folding CRLF and the whitespace after it (SKIP_LONG_HEADER_SEP). In C.UTF-8 the
     * bytes from 0x80 aren't control characters.
     */
    private static byte[] headerText(byte[] s) {
        int length = s.length;
        while (length > 0 && isSpace(s[length - 1])) {
            length--;
        }
        byte[] r = java.util.Arrays.copyOf(s, length);
        for (int i = 0; i < length; i++) {
            if ((r[i] >= 0 && r[i] < 0x20) || r[i] == 0x7F) {
                if (r[i] == '\r' && at(r, i + 1) == '\n' && (at(r, i + 2) == ' ' || at(r, i + 2) == '\t')) {
                    i += 2;
                    while (at(r, i + 1) == ' ' || at(r, i + 1) == '\t') {
                        i++;
                    }
                    continue;
                }
                r[i] = ' ';
            }
        }
        return r;
    }

    /** C's isspace in the C locale. */
    private static boolean isSpace(byte c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    // ---- php_mail_build_headers ----

    /**
     * php_mail_build_headers: "Name: value" lines joined by CRLF, an array value giving a line per element; null for
     * no header. An error in an element doesn't stop the rest of its header's elements, so its errors chain
     * (zend_throw_exception_internal); the next header isn't looked at.
     */
    private static byte[] buildHeaders(PhpContext context, PhpArray headers) {
        ByteBuilder s = new ByteBuilder();
        Pending pending = new Pending(context);
        for (int p = headers.firstPosition(); p >= 0; p = headers.nextPosition(p)) {
            Object key = headers.keyAt(p);
            if (!(key instanceof TruffleString keyString)) {
                throw new PhpError("TypeError", "Header name cannot be numeric, " + key + " given");
            }
            byte[] name = Str.bytes(keyString);
            Object value = headers.valueAt(p);
            // https://tools.ietf.org/html/rfc2822#section-3.6
            String lower = asciiLower(name);
            switch (lower) {
                case "orig-date", "from", "sender", "reply-to", "cc", "bcc", "message-id", "references",
                     "in-reply-to" -> {
                    if (value instanceof TruffleString) {
                        header(s, pending, name, value);
                    } else if (value instanceof PhpArray) {
                        pending.add(new PhpError("TypeError", "Header \"" + lower
                                + "\" must be of type string, array given"));
                    } else {
                        pending.add(notArrayOrString(name, value));
                    }
                }
                case "to" -> pending.add(new PhpError("ValueError",
                        "The additional headers cannot contain the \"To\" header"));
                case "subject" -> pending.add(new PhpError("ValueError",
                        "The additional headers cannot contain the \"Subject\" header"));
                default -> {
                    if (value instanceof TruffleString) {
                        header(s, pending, name, value);
                    } else if (value instanceof PhpArray array) {
                        headerList(s, pending, name, array);
                    } else {
                        pending.add(notArrayOrString(name, value));
                    }
                }
            }
            pending.throwIfAny();
        }
        if (s.size() == 0) {
            return null;
        }
        s.truncate(s.size() - 2); // the last CRLF
        return s.toByteArray();
    }

    /** php_mail_build_headers_elem: one "Name: value" line, or the error its name or value gives. */
    private static void header(ByteBuilder s, Pending pending, byte[] name, Object value) {
        if (value instanceof PhpArray array) {
            headerList(s, pending, name, array);
            return;
        }
        if (!(value instanceof TruffleString text)) {
            pending.add(notArrayOrString(name, value));
            return;
        }
        if (!validName(name)) {
            pending.add(error("ValueError", "Header name \"", name, "\" contains invalid characters"));
            return;
        }
        byte[] bytes = Str.bytes(text);
        String problem = switch (valueProblem(bytes)) {
            case LF_ONLY -> "\" contains LF character that is not allowed in the header";
            case CR_ONLY -> "\" contains CR character that is not allowed in the header";
            case CRLF_SEPARATOR -> "\" contains CRLF characters that are used as a line separator and are not allowed "
                    + "in the header";
            case NUL -> "\" contains NULL character that is not allowed in the header";
            case NONE -> null;
        };
        if (problem != null) {
            pending.add(error("ValueError", "Header \"", name, problem));
            return;
        }
        s.write(name);
        s.writeAscii(": ");
        s.write(bytes);
        s.write(CRLF);
    }

    /** php_mail_build_headers_elems: a line per element of a list of strings. */
    private static void headerList(ByteBuilder s, Pending pending, byte[] name, PhpArray values) {
        for (int p = values.firstPosition(); p >= 0; p = values.nextPosition(p)) {
            if (values.keyAt(p) instanceof TruffleString key) {
                ByteBuilder message = new ByteBuilder();
                message.writeAscii("Header \"");
                message.write(cString(name));
                message.writeAscii("\" must only contain numeric keys, \"");
                message.write(cString(Str.bytes(key)));
                message.writeAscii("\" found");
                pending.add(new PhpError("TypeError", Str.of(message.toByteArray())));
                return;
            }
            Object value = values.valueAt(p);
            if (!(value instanceof TruffleString)) {
                pending.add(error("TypeError", "Header \"", name, "\" must only contain values of type string, "
                        + Args.valueName(value) + " found"));
                return;
            }
            header(s, pending, name, value);
        }
    }

    private static PhpError notArrayOrString(byte[] name, Object value) {
        return error("TypeError", "Header \"", name, "\" must be of type array|string, " + Args.valueName(value)
                + " given");
    }

    /** An error quoting a header name, as %s prints it: up to its first NUL byte. */
    private static PhpError error(String phpClass, String before, byte[] name, String after) {
        ByteBuilder message = new ByteBuilder();
        message.writeAscii(before);
        message.write(cString(name));
        message.write(after.getBytes(StandardCharsets.UTF_8));
        return new PhpError(phpClass, Str.of(message.toByteArray()));
    }

    private static byte[] cString(byte[] s) {
        int nul = indexOf(s, 0);
        return nul < 0 ? s : java.util.Arrays.copyOf(s, nul);
    }

    /** php_mail_build_headers_check_field_name (RFC 2822 2.2): printable ASCII other than ':'. */
    private static boolean validName(byte[] name) {
        for (byte c : name) {
            if (c < 33 || c > 126 || c == ':') { // a signed char: bytes from 0x80 are below 33
                return false;
            }
        }
        return true;
    }

    private enum ValueProblem { NONE, LF_ONLY, CR_ONLY, CRLF_SEPARATOR, NUL }

    /**
     * php_mail_build_headers_check_field_value (RFC 2822 2.2.1, 2.2.3): a line break is only allowed as folding,
     * CRLF or (as many environments fold) LF followed by a space or a tab.
     */
    private static ValueProblem valueProblem(byte[] value) {
        int len = 0;
        while (len < value.length) {
            if (value[len] == '\r') {
                if (at(value, len + 1) != '\n') {
                    return ValueProblem.CR_ONLY;
                }
                if (value.length - len >= 3 && (value[len + 2] == ' ' || value[len + 2] == '\t')) {
                    len += 3;
                    continue;
                }
                return ValueProblem.CRLF_SEPARATOR;
            }
            if (value[len] == '\n') {
                if (value.length - len >= 2 && (value[len + 1] == ' ' || value[len + 1] == '\t')) {
                    len += 2;
                    continue;
                }
                return ValueProblem.LF_ONLY;
            }
            if (value[len] == 0) {
                return ValueProblem.NUL;
            }
            len++;
        }
        return ValueProblem.NONE;
    }

    /**
     * The exception php-src has pending while php_mail_build_headers goes on: a later one takes it as its previous.
     */
    private static final class Pending {
        private final PhpContext context;
        private PhpError error;

        Pending(PhpContext context) {
            this.context = context;
        }

        void add(PhpError thrown) {
            if (error != null) {
                SourceLocation here = SourceLocation.current();
                thrown.placeIfUnplaced(here);
                error.placeIfUnplaced(here);
                TryNode.setPrevious(context, thrown, error);
            }
            error = thrown;
        }

        void throwIfAny() {
            if (error != null) {
                throw error;
            }
        }
    }

    // ---- php_mail ----

    /** php_mail: true when sendmail took the message (exit status 0, or EX_TEMPFAIL). */
    private static boolean send(PhpContext context, byte[] to, byte[] subject, byte[] message, byte[] headers,
                                byte[] parameters, PhpError pending) {
        Ini ini = context.ini();
        SourceLocation at = SourceLocation.current();
        // zend_get_executed_filename and _lineno
        String file = at == SourceLocation.UNKNOWN ? "[no active file]" : at.file();
        String mailLog = ini.mailLog();
        if (mailLog != null) {
            ByteBuilder line = new ByteBuilder();
            line.write(("mail() on [" + file + ":" + at.line() + "]: To: ").getBytes(StandardCharsets.UTF_8));
            line.write(to);
            line.writeAscii(" -- Headers: ");
            if (headers != null) {
                line.write(headers);
            }
            line.writeAscii(" -- Subject: ");
            line.write(subject);
            byte[] logLine = line.toByteArray();
            if (headers != null) {
                // a log line is one line (only once there are headers, as php-src has it)
                for (int i = 0; i < logLine.length; i++) {
                    if (logLine[i] == '\r' || logLine[i] == '\n') {
                        logLine[i] = ' ';
                    }
                }
            }
            if (mailLog.equals("syslog")) {
                Syslog.log(context, Syslog.LOG_NOTICE, logLine);
            } else {
                ByteBuilder entry = new ByteBuilder();
                entry.write('[');
                entry.write(DateFunctions.formatLocal(context, "d-M-Y H:i:s e", System.currentTimeMillis() / 1000));
                entry.writeAscii("] ");
                entry.write(logLine);
                entry.write('\n'); // PHP_EOL
                logToFile(context, mailLog, entry.toByteArray(), pending != null);
            }
        }
        if (pending != null) {
            throw pending;
        }

        byte[] separator = lineSeparator(ini);
        byte[] header = headers;
        if (ini.mailAddXHeader()) {
            ByteBuilder x = new ByteBuilder();
            x.writeAscii("X-PHP-Originating-Script: ");
            x.writeLong(ExecFunctions.scriptOwner(context));
            x.write(':');
            x.write(basename(file.getBytes(StandardCharsets.UTF_8)));
            if (headers != null) {
                x.write(separator);
                x.write(headers);
            }
            header = x.toByteArray();
        }
        if (header != null && multipleCrlf(header)) {
            warn(context, "Multiple or malformed newlines found in additional_header");
            return false;
        }
        String sendmailPath = ini.get("sendmail_path");
        if (sendmailPath == null) {
            return false;
        }
        ByteBuilder command = new ByteBuilder();
        command.write(sendmailPath.getBytes(StandardCharsets.UTF_8));
        if (parameters != null) {
            command.write(' ');
            command.write(parameters);
        }

        ByteBuilder input = new ByteBuilder();
        input.writeAscii("To: ");
        input.write(to);
        input.write(separator);
        input.writeAscii("Subject: ");
        input.write(subject);
        input.write(separator);
        if (header != null) {
            input.write(header);
            input.write(separator);
        }
        input.write(separator);
        if ("lf".equals(ini.mailCrLfMode())) {
            for (int i = 0; i < message.length; i++) {
                if (message[i] == '\r' && i + 1 < message.length && message[i + 1] == '\n') {
                    continue; // the CRLF's LF follows
                }
                input.write(message[i]);
            }
        } else {
            input.write(message);
        }
        input.write(separator);

        ExecFunctions.Child child = ExecFunctions.pipeTo(context, command.toByteArray(), input.toByteArray());
        if (!child.started()) {
            warn(context, "Could not execute mail delivery program '" + sendmailPath + "'");
            return false;
        }
        if (child.ending() == null) {
            warn(context, "Sendmail pclose failed " + child.errno() + " (" + strerror(child.errno()) + ")");
            return false;
        }
        switch (child.ending()) {
            case SIGNALED -> {
                warn(context, "Sendmail killed by signal " + child.value() + " (" + strsignal(child.value()) + ")");
                return false;
            }
            case OTHER -> {
                warn(context, "Sendmail did not exit");
                return false;
            }
            default -> {
                int status = child.value();
                if (status != 0 && status != EX_TEMPFAIL) {
                    warn(context, "Sendmail exited with non-zero exit code " + status);
                    return false;
                }
                return true;
            }
        }
    }

    /**
     * php_mail's line separator: CRLF, or LF for mail.cr_lf_mode lf, mixed and os (a Unix one), or for
     * mail.mixed_lf_and_crlf. An empty mode passes OnUpdateMailCrLfMode but has no branch in php_mail (a
     * ZEND_ASSERT, assumed away in a release build): PHP 8.5.12 gives it LF, as the other branches.
     */
    private static byte[] lineSeparator(Ini ini) {
        String mode = ini.mailCrLfMode();
        if (mode != null && !mode.equals("crlf")) {
            return LF;
        }
        return ini.mailMixedLfAndCrlf() ? LF : CRLF;
    }

    /**
     * php_mail_log_to_file: the line appended to the file, opened as php_stream_open_wrapper(name, "a") with
     * REPORT_ERRORS (and without open_basedir's check); while an exception is pending the stream layer reports
     * nothing (php_stream_display_wrapper_errors).
     */
    private static void logToFile(PhpContext context, String name, byte[] entry, boolean exceptionPending) {
        if (name.regionMatches(true, 0, "php://", 0, 6)) {
            FileFunctions.Stream stream = FileFunctions.openStream(context, name, "a");
            if (stream != null) {
                stream.write(entry);
                FileFunctions.closeStream(stream);
            }
            return;
        }
        String path = name.regionMatches(true, 0, "file://", 0, 7) ? name.substring(7) : name;
        try {
            TruffleFile log = context.getEnv().getPublicTruffleFile(path);
            try (SeekableByteChannel channel = log.newByteChannel(EnumSet.of(StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND))) {
                ByteBuffer buffer = ByteBuffer.wrap(entry);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        } catch (IOException e) {
            if (!exceptionPending) {
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING,
                        Str.of("mail(" + name + "): Failed to open stream: " + reason(e)), SourceLocation.current());
            }
        }
    }

    /** strerror of the errno an open failed with, as the JDK reports it. */
    static String reason(IOException e) {
        if (e instanceof NoSuchFileException) {
            return "No such file or directory";
        }
        if (e instanceof AccessDeniedException) {
            return "Permission denied";
        }
        if (e instanceof FileSystemException failure && failure.getReason() != null) {
            return failure.getReason();
        }
        return e.getMessage();
    }

    /**
     * php_mail_detect_multiple_crlf: a header block that starts with a line break (or anything but a name's
     * character), or has an empty line, or a line break at its end.
     */
    private static boolean multipleCrlf(byte[] hdr) {
        if (hdr.length == 0) {
            return false;
        }
        if (hdr[0] < 33 || hdr[0] > 126 || hdr[0] == ':') {
            return true;
        }
        int i = 0;
        while (i < hdr.length) {
            if (hdr[i] == '\r') {
                int next = at(hdr, i + 1);
                if (next == 0 || next == '\r' || (next == '\n' && (at(hdr, i + 2) == 0 || at(hdr, i + 2) == '\n'
                        || at(hdr, i + 2) == '\r'))) {
                    return true;
                }
                i += 2;
            } else if (hdr[i] == '\n') {
                int next = at(hdr, i + 1);
                if (next == 0 || next == '\r' || next == '\n') {
                    return true;
                }
                i += 2;
            } else {
                i++;
            }
        }
        return false;
    }

    /** php_basename(file): the last path component (no trailing slashes); Windows' paths split at '\\' too. */
    private static byte[] basename(byte[] path) {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        int end = path.length;
        while (end > 0 && separator(path[end - 1], windows)) {
            end--;
        }
        int start = end;
        while (start > 0 && !separator(path[start - 1], windows)) {
            start--;
        }
        return java.util.Arrays.copyOfRange(path, start, end);
    }

    private static boolean separator(byte b, boolean windows) {
        return b == '/' || (windows && b == '\\');
    }

    /** glibc's strsignal (in the C locale php's messages are in). */
    private static String strsignal(int signal) {
        String[] names = {null, "Hangup", "Interrupt", "Quit", "Illegal instruction", "Trace/breakpoint trap",
            "Aborted", "Bus error", "Floating point exception", "Killed", "User defined signal 1",
            "Segmentation fault", "User defined signal 2", "Broken pipe", "Alarm clock", "Terminated", "Stack fault",
            "Child exited", "Continued", "Stopped (signal)", "Stopped", "Stopped (tty input)",
            "Stopped (tty output)", "Urgent I/O condition", "CPU time limit exceeded", "File size limit exceeded",
            "Virtual timer expired", "Profiling timer expired", "Window changed", "I/O possible", "Power failure",
            "Bad system call"};
        if (signal > 0 && signal < names.length) {
            return names[signal];
        }
        if (signal >= 34 && signal <= 64) { // SIGRTMIN (glibc keeps 32 and 33) to SIGRTMAX
            return "Real-time signal " + (signal - 34);
        }
        return "Unknown signal " + signal;
    }

    /** glibc's strerror for what pclose can fail with: waitpid's errors, and those of writing to a pipe. */
    private static String strerror(int errno) {
        return switch (errno) {
            case 4 -> "Interrupted system call";
            case 5 -> "Input/output error";
            case 9 -> "Bad file descriptor";
            case 10 -> "No child processes";
            case 11 -> "Resource temporarily unavailable";
            case 22 -> "Invalid argument";
            case 28 -> "No space left on device";
            case 32 -> "Broken pipe";
            default -> "Unknown error " + errno;
        };
    }

    /** php_error_docref(NULL, E_WARNING, ...): the function's warning. */
    private static void warn(PhpContext context, String message) {
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("mail(): " + message),
                SourceLocation.current());
    }

    private static String asciiLower(byte[] s) {
        char[] chars = new char[s.length];
        for (int i = 0; i < s.length; i++) {
            int c = s[i] & 0xFF;
            chars[i] = (char) (c >= 'A' && c <= 'Z' ? c + 32 : c);
        }
        return new String(chars);
    }

    private static int indexOf(byte[] s, int b) {
        for (int i = 0; i < s.length; i++) {
            if (s[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /** The byte at i, or 0 past the end (C's terminating NUL). */
    private static int at(byte[] s, int i) {
        return i < s.length ? s[i] & 0xFF : 0;
    }
}
