package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.NativeClock;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * preg_* on the system PCRE2 through FFM (D7), mirroring ext/pcre/php_pcre.c: the same delimiter and modifier
 * parsing, a per-regex cache of compiled and JIT-ed patterns, the same empty-match stepping and error codes.
 */
public final class PcreFunctions {
    PcreFunctions() {
    }

    // pcre2.h
    private static final int CASELESS = 0x00000008;
    private static final int DOLLAR_ENDONLY = 0x00000010;
    private static final int DOTALL = 0x00000020;
    private static final int DUPNAMES = 0x00000040;
    private static final int EXTENDED = 0x00000080;
    private static final int MULTILINE = 0x00000400;
    private static final int NO_AUTO_CAPTURE = 0x00002000;
    private static final int UCP = 0x00020000;
    private static final int UNGREEDY = 0x00040000;
    private static final int UTF = 0x00080000;
    private static final int ANCHORED = 0x80000000;
    private static final int NO_UTF_CHECK = 0x40000000;
    private static final int NOTEMPTY_ATSTART = 0x00000008;
    private static final int JIT_COMPLETE = 1;
    private static final int INFO_CAPTURECOUNT = 4;
    private static final int INFO_NAMECOUNT = 17;
    private static final int INFO_NAMEENTRYSIZE = 18;
    private static final int INFO_NAMETABLE = 19;
    private static final int ERROR_NOMATCH = -1;

    // PHP's preg_* flags and preg_last_error() codes
    private static final long PATTERN_ORDER = 1;
    private static final long SET_ORDER = 2;
    private static final long OFFSET_CAPTURE = 256;
    private static final long UNMATCHED_AS_NULL = 512;
    private static final long SPLIT_NO_EMPTY = 1;
    private static final long SPLIT_DELIM_CAPTURE = 2;
    private static final long SPLIT_OFFSET_CAPTURE = 4;
    private static final String[] ERROR_MESSAGES = {"No error", "Internal error",
            "Backtrack limit exhausted", "Recursion limit exhausted",
            "Malformed UTF-8 characters, possibly incorrectly encoded",
            "The offset did not correspond to the beginning of a valid UTF-8 code point", "JIT stack limit exhausted"};

    /**
     * Compiled patterns shared by every context of the engine, by regex string (php-src's per-process pcre
     * cache, with its 4096-entry bound): a compiled pattern and its JIT code are read-only while matching, so
     * threads share them. Past the bound a pattern is compiled for the context alone and freed with it.
     */
    public static final class Patterns {
        private static final int LIMIT = 4096;
        private final java.util.concurrent.ConcurrentHashMap<TruffleString, Compiled> compiled =
                new java.util.concurrent.ConcurrentHashMap<>();
    }

    /** A pattern as compiled: the code, its group names, and how it was compiled. */
    private record Compiled(MemorySegment code, boolean jit, boolean utf, int groups, TruffleString[] names,
                            byte[] pattern, int options, boolean shared) {
    }

    /** This context's view of the compiled patterns, by regex string, with their match blocks. */
    private final Map<TruffleString, Regex> regexes = new HashMap<>();
    /** This context's match context and JIT stack (PCRE2 needs one stack per thread matching), made on first use. */
    private MemorySegment matchContext;
    private MemorySegment jitStack;
    /** Parsed preg_replace replacement strings. */
    private final Map<TruffleString, Template> templates = new HashMap<>();
    private final SubjectCache subjects = new SubjectCache();
    private int lastError;

    /** A compiled pattern with its group names and a reusable match block (php-src's mdata, guarded the same way). */
    private static final class Regex {
        final Compiled compiled;
        final MemorySegment code;
        /** JIT-compiled: matches without a UTF check go through pcre2_jit_match, as php-src does. */
        final boolean jit;
        final boolean utf;
        final int groups;
        final TruffleString[] names;
        final MemorySegment matchData;
        final MemorySegment ovector;
        boolean matchDataInUse;
        /** The pattern without delimiters and its compile options, for PcreRecorder. */
        byte[] pattern;
        int options;

        Regex(Compiled compiled) {
            this.compiled = compiled;
            this.code = compiled.code();
            this.jit = compiled.jit();
            this.utf = compiled.utf();
            this.groups = compiled.groups();
            this.names = compiled.names();
            this.pattern = compiled.pattern();
            this.options = compiled.options();
            this.matchData = Native.createMatchData(code);
            this.ovector = Native.ovector(matchData, groups);
        }
    }

    /**
     * Where subjects live in native memory, cached by the identity of their (never modified) byte arrays: the block
     * parser and the tag processor call preg_match on one document thousands of times at growing offsets, and it is
     * copied once. Entries own reusable native buffers, so a new subject costs a copy, not an allocation: a fresh
     * Arena.ofAuto segment per subject cost ~6 us for a 10 KB post (zeroing, cleaner, reference processing), thirty
     * times the match itself, and preg_replace chains produce a new subject per call. An entry in use by an outer
     * call (a preg_replace_callback callback calling preg_* again) is never reused. Small subjects (most calls)
     * skip the cache: they are copied into a scratch buffer per nesting depth, slot -1 - depth.
     *
     * <p>Buffers come from automatic arenas, never shared ones: a downcall acquires and releases a shared
     * arena's session around the call, which took a 13-byte match from 12 to 23 ns. They go with the context.
     */
    private static final class SubjectCache {
        private static final int LARGE = 4096;
        private MemorySegment[] scratch = new MemorySegment[4];
        private int depth;
        private byte[][] subjects = new byte[8][];
        private MemorySegment[] buffers = new MemorySegment[8];
        private int[] users = new int[8];
        private int next;

        /** The entry holding the subject's native copy (copied in unless already there); release it after use. */
        int acquire(byte[] subject) {
            if (subject.length < LARGE) {
                return acquireScratch(subject);
            }
            for (int i = 0; i < subjects.length; i++) {
                if (subjects[i] == subject) {
                    users[i]++;
                    return i;
                }
            }
            int slot = unusedSlot();
            if (buffers[slot] == null || buffers[slot].byteSize() < subject.length) {
                long size = Math.max(256, Integer.highestOneBit(Math.max(1, subject.length)) * 2L);
                buffers[slot] = Arena.ofAuto().allocate(size);
            }
            MemorySegment.copy(subject, 0, buffers[slot], ValueLayout.JAVA_BYTE, 0, subject.length);
            subjects[slot] = subject;
            users[slot] = 1;
            return slot;
        }

        private int acquireScratch(byte[] subject) {
            if (depth == scratch.length) {
                scratch = Arrays.copyOf(scratch, depth * 2);
            }
            MemorySegment buffer = scratch[depth];
            if (buffer == null || buffer.byteSize() < subject.length) {
                buffer = Arena.ofAuto().allocate(LARGE);
                scratch[depth] = buffer;
            }
            MemorySegment.copy(subject, 0, buffer, ValueLayout.JAVA_BYTE, 0, subject.length);
            return -1 - depth++;
        }

        MemorySegment segment(int slot) {
            return slot >= 0 ? buffers[slot] : scratch[-1 - slot];
        }

        void release(int slot) {
            if (slot >= 0) {
                users[slot]--;
            } else {
                depth--;
            }
        }

        private int unusedSlot() {
            for (int k = 0; k < users.length; k++) {
                int i = (next + k) % users.length;
                if (users[i] == 0) {
                    next = (i + 1) % users.length;
                    return i;
                }
            }
            int slot = users.length;
            subjects = Arrays.copyOf(subjects, slot * 2);
            buffers = Arrays.copyOf(buffers, slot * 2);
            users = Arrays.copyOf(users, slot * 2);
            next = slot + 1;
            return slot;
        }
    }

    /** One preg call's view of a subject (its native copy) and the current match block. */
    private static final class Matcher implements AutoCloseable {
        final Regex regex;
        final byte[] subject;
        final MemorySegment nativeSubject;
        final int subjectSlot;
        final MemorySegment matchData;
        final MemorySegment ovector;
        final boolean ownsMatchData;
        final SubjectCache subjects;
        final MemorySegment matchContext;

        Matcher(PcreFunctions pcre, Regex regex, byte[] subject) {
            this.regex = regex;
            this.subject = subject;
            this.subjects = pcre.subjects;
            this.matchContext = pcre.matchContext();
            this.subjectSlot = subjects.acquire(subject);
            this.nativeSubject = subjects.segment(subjectSlot);
            if (regex.matchDataInUse) {
                ownsMatchData = true;
                matchData = Native.createMatchData(regex.code);
                ovector = Native.ovector(matchData, regex.groups);
            } else {
                ownsMatchData = false;
                regex.matchDataInUse = true;
                matchData = regex.matchData;
                ovector = regex.ovector;
            }
        }

        int match(long start, int options) {
            if (PcreRecorder.ENABLED) {
                PcreRecorder.match(regex, regex.pattern, regex.options, regex.jit, subject, subject.length, start,
                        options);
            }
            if (!NativeClock.ENABLED) {
                return Native.match(regex.code, regex.jit, nativeSubject, subject.length, start, options, matchData,
                        matchContext);
            }
            long t0 = System.nanoTime();
            int rc = Native.match(regex.code, regex.jit, nativeSubject, subject.length, start, options, matchData,
                    matchContext);
            long t1 = System.nanoTime();
            NativeClock.add(t1 - t0, System.nanoTime() - t1);
            return rc;
        }

        int start(int group) {
            return offset(2 * group);
        }

        int end(int group) {
            return offset(2 * group + 1);
        }

        private int offset(int index) {
            long value = ovector.getAtIndex(ValueLayout.JAVA_LONG, index);
            return value == -1 ? -1 : (int) value;
        }

        /** The match options for the first match on this subject: let PCRE2 validate UTF-8 once. */
        int firstOptions() {
            return regex.utf ? 0 : NO_UTF_CHECK;
        }

        /** calc_unit_len: one byte, or one UTF-8 character in /u mode. */
        int unitLength(int position) {
            if (!regex.utf) {
                return 1;
            }
            int end = position + 1;
            while (end < subject.length && (subject[end] & 0xC0) == 0x80) {
                end++;
            }
            return end - position;
        }

        @Override
        public void close() {
            if (ownsMatchData) {
                Native.free(Native.MATCH_DATA_FREE, matchData);
            } else {
                regex.matchDataInUse = false;
            }
            subjects.release(subjectSlot);
        }
    }

    private MemorySegment matchContext() {
        MemorySegment context = matchContext;
        if (context == null) {
            jitStack = Native.jitStack();
            context = Native.matchContext(jitStack);
            matchContext = context;
        }
        return context;
    }

    /** Frees this context's native memory: match blocks, the match context, patterns not shared. */
    void close() {
        for (Regex regex : regexes.values()) {
            Native.free(Native.MATCH_DATA_FREE, regex.matchData);
            if (!regex.compiled.shared()) {
                Native.free(Native.CODE_FREE, regex.code);
            }
        }
        regexes.clear();
        if (matchContext != null) {
            Native.free(Native.MATCH_CONTEXT_FREE, matchContext);
            Native.free(Native.JIT_STACK_FREE, jitStack);
            matchContext = null;
        }
    }

    /** This context's instance: its pattern caches and preg_last_error(). */
    private static PcreFunctions pcre(PhpContext context) {
        return context.builtinState().pcre;
    }

    /** Declares the preg_* functions; their state is the calling context's instance. */
    static void register(Registry r) {
        r.fn("preg_match", "001", (context, a) -> pcre(context).match(a, false));
        r.fn("preg_match_all", "001", (context, a) -> pcre(context).match(a, true));
        r.fn("preg_replace", "00001", (context, a) -> pcre(context).replace(context, a, false));
        r.fn("preg_replace_callback", "00001", (context, a) -> pcre(context).replace(context, a, true));
        r.fn("preg_split", (context, a) -> pcre(context).split(Args.str(a, 0), Args.str(a, 1), Args.lng(a, 2, -1), Args.lng(a, 3, 0)));
        r.fn("preg_quote", a -> quote(Args.str(a, 0), Args.get(a, 1)));
        r.fn("preg_grep", (context, a) -> pcre(context).grep(Args.str(a, 0), Args.array(a, 1, "preg_grep"), Args.lng(a, 2, 0)));
        r.fn("preg_last_error", (context, a) -> (long) pcre(context).lastError);
        r.fn("preg_last_error_msg", (context, a) -> Str.of(ERROR_MESSAGES[pcre(context).lastError]));
    }

    /** The measurement functions of bench/gate1/run.php (php.Debug). */
    static void registerDebug(Registry r) {
        r.fn("truffle_native_ns", a -> NativeClock.nanos());
        if (PcreRecorder.ENABLED) {
            r.fn("truffle_pcre_record", a -> {
                PcreRecorder.section(Str.java(Args.str(a, 0)));
                return PhpNull.NULL;
            });
        }
    }

    /** The parsed replacement string (cached; bounded, as one-off replacements would otherwise pile up). */
    private Template template(TruffleString source) {
        Template cached = templates.get(source);
        if (cached == null) {
            cached = Template.parse(Str.readBytes(source));
            if (templates.size() > 4096) {
                templates.clear();
            }
            templates.put(source, cached);
        }
        return cached;
    }

    // ---- compiling ----

    @TruffleBoundary
    private Regex compile(TruffleString regexString) {
        Regex cached = regexes.get(regexString);
        if (cached != null) {
            return cached;
        }
        Patterns patterns = PhpContext.get(null).getLanguage().pcrePatterns();
        Compiled compiled = patterns.compiled.get(regexString);
        if (compiled == null) {
            compiled = compileNew(regexString, patterns.compiled.size() < Patterns.LIMIT);
            if (compiled.shared()) {
                Compiled raced = patterns.compiled.putIfAbsent(regexString, compiled);
                if (raced != null) {
                    Native.free(Native.CODE_FREE, compiled.code());
                    compiled = raced;
                }
            }
        }
        Regex regex = new Regex(compiled);
        regexes.put(regexString, regex);
        return regex;
    }

    private static Compiled compileNew(TruffleString regexString, boolean shared) {
        byte[] regex = Str.readBytes(regexString);
        int p = 0;
        while (p < regex.length && isSpace(regex[p])) {
            p++;
        }
        if (p == regex.length) {
            throw new PhpError("Warning: preg_match(): Empty regular expression");
        }
        byte startDelimiter = regex[p];
        if (Character.isLetterOrDigit(startDelimiter) || startDelimiter == '\\' || startDelimiter == 0) {
            throw new PhpError("Warning: Delimiter must not be alphanumeric, backslash, or NUL byte");
        }
        int bracket = "([{<".indexOf(startDelimiter);
        byte endDelimiter = bracket >= 0 ? (byte) ")]}>".charAt(bracket) : startDelimiter;
        int patternStart = ++p;
        int depth = 1;
        while (p < regex.length) {
            if (regex[p] == '\\' && p + 1 < regex.length) {
                p++;
            } else if (regex[p] == endDelimiter && (bracket < 0 || --depth <= 0)) {
                break;
            } else if (bracket >= 0 && regex[p] == startDelimiter) {
                depth++;
            }
            p++;
        }
        if (p >= regex.length) {
            throw new PhpError("Warning: No ending delimiter '" + (char) endDelimiter + "' found");
        }
        int patternEnd = p++;
        int options = 0;
        for (; p < regex.length; p++) {
            switch (regex[p]) {
                case 'i' -> options |= CASELESS;
                case 'm' -> options |= MULTILINE;
                case 's' -> options |= DOTALL;
                case 'x' -> options |= EXTENDED;
                case 'A' -> options |= ANCHORED;
                case 'D' -> options |= DOLLAR_ENDONLY;
                case 'U' -> options |= UNGREEDY;
                case 'u' -> options |= UTF | UCP;
                case 'J' -> options |= DUPNAMES;
                case 'n' -> options |= NO_AUTO_CAPTURE;
                case 'S', 'X', ' ', '\n', '\r' -> {
                }
                default -> throw new PhpError("Warning: Unknown modifier '" + (char) regex[p] + "'");
            }
        }
        byte[] pattern = new byte[patternEnd - patternStart];
        System.arraycopy(regex, patternStart, pattern, 0, pattern.length);
        MemorySegment code = Native.compile(pattern, options);
        int groups = Native.info(code, INFO_CAPTURECOUNT) + 1;
        return new Compiled(code, Native.jitCompile(code), (options & UTF) != 0, groups, Native.names(code, groups),
                pattern, options, shared);
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b >= 9 && b <= 13;
    }

    /** pcre_handle_exec_error: maps a PCRE2 match error to preg_last_error(). */
    private void execError(int rc) {
        if (rc == -47) {
            lastError = 2;
        } else if (rc == -53) {
            lastError = 3;
        } else if (rc <= -3 && rc >= -23) {
            lastError = 4;
        } else if (rc == -36) {
            lastError = 5;
        } else if (rc == -46) {
            lastError = 6;
        } else {
            lastError = 1;
        }
    }

    // ---- match values ----

    private static Object matchValue(Matcher m, int group, long flags) {
        int start = m.start(group);
        if (start < 0) {
            return (flags & UNMATCHED_AS_NULL) != 0 ? PhpNull.NULL : Str.EMPTY;
        }
        return Str.of(m.subject, start, m.end(group) - start);
    }

    private static Object entry(Matcher m, int group, boolean present, long flags) {
        Object value = present ? matchValue(m, group, flags)
                : (flags & UNMATCHED_AS_NULL) != 0 ? PhpNull.NULL : Str.EMPTY;
        if ((flags & OFFSET_CAPTURE) == 0) {
            return value;
        }
        return PhpArray.list(value, present ? (long) m.start(group) : -1L);
    }

    /** populate_subpat_array: groups up to the last one that matched, plus all of them with UNMATCHED_AS_NULL. */
    private static PhpArray groups(Matcher m, int count, long flags) {
        PhpArray result = new PhpArray();
        int last = (flags & UNMATCHED_AS_NULL) != 0 ? m.regex.groups : count;
        for (int i = 0; i < last; i++) {
            Object value = entry(m, i, i < count, flags);
            if (m.regex.names[i] != null) {
                result.put(m.regex.names[i], value);
            }
            result.append(value);
        }
        return result;
    }

    // ---- preg_match / preg_match_all ----

    @TruffleBoundary
    private Object match(Object[] a, boolean global) {
        Regex regex = compile(Args.str(a, 0));
        byte[] subject = Str.readBytes(Args.str(a, 1));
        PhpRef matchesRef = Args.has(a, 2) ? Args.ref(a, 2) : null;
        long flags = Args.lng(a, 3, 0);
        long offset = Args.lng(a, 4, 0);
        long order = PATTERN_ORDER;
        if (global && (flags & 0xFF) != 0) {
            order = flags & 0xFF;
        }
        flags &= ~0xFFL;
        lastError = 0;
        if (offset < 0) {
            offset = Math.max(0, subject.length + offset);
        }
        if (offset > subject.length) {
            lastError = 1;
            if (matchesRef != null) {
                matchesRef.set(new PhpArray());
            }
            return Boolean.FALSE;
        }
        PhpArray[] sets = null;
        PhpArray setOrder = null;
        PhpArray single = null;
        if (global && order == PATTERN_ORDER) {
            sets = new PhpArray[regex.groups];
            for (int i = 0; i < sets.length; i++) {
                sets[i] = new PhpArray();
            }
        } else if (global) {
            setOrder = new PhpArray();
        }
        long matched = 0;
        try (Matcher m = new Matcher(this, regex, subject)) {
            long start = offset;
            int rc = m.match(start, m.firstOptions());
            while (true) {
                if (rc >= 0) {
                    matched++;
                    if (matchesRef != null) {
                        if (sets != null) {
                            for (int i = 0; i < sets.length; i++) {
                                sets[i].append(entry(m, i, i < rc, flags));
                            }
                        } else if (setOrder != null) {
                            setOrder.append(groups(m, rc, flags));
                        } else {
                            single = groups(m, rc, flags);
                        }
                    }
                    if (!global) {
                        break;
                    }
                    int matchStart = m.start(0);
                    start = m.end(0);
                    if (start == matchStart) {
                        rc = m.match(start, NO_UTF_CHECK | NOTEMPTY_ATSTART | ANCHORED);
                        if (rc >= 0) {
                            continue;
                        }
                        if (rc != ERROR_NOMATCH) {
                            execError(rc);
                            break;
                        }
                        if (start >= subject.length) {
                            break;
                        }
                        start += m.unitLength((int) start);
                    }
                } else if (rc == ERROR_NOMATCH) {
                    break;
                } else {
                    execError(rc);
                    break;
                }
                rc = m.match(start, NO_UTF_CHECK);
            }
        }
        if (matchesRef != null) {
            if (sets != null) {
                PhpArray result = new PhpArray();
                for (int i = 0; i < sets.length; i++) {
                    if (regex.names[i] != null) {
                        result.put(regex.names[i], sets[i]);
                    }
                    result.append(sets[i]);
                }
                matchesRef.set(result);
            } else {
                matchesRef.set(setOrder != null ? setOrder : single != null ? single : new PhpArray());
            }
        }
        return lastError == 0 ? (Object) matched : Boolean.FALSE;
    }

    // ---- preg_replace / preg_replace_callback ----

    @TruffleBoundary
    private Object replace(PhpContext context, Object[] a, boolean callback) {
        Object pattern = Args.get(a, 0);
        Object replacement = Args.get(a, 1);
        Object subject = Args.get(a, 2);
        long limit = Args.lng(a, 3, -1);
        long flags = callback ? Args.lng(a, 5, 0) : 0;
        if (callback) {
            Callables.check(context, replacement, new Callables.Parameter("preg_replace_callback", 2, false));
        }
        if (limit < 0) {
            limit = Long.MAX_VALUE;
        }
        long[] count = {0};
        lastError = 0;
        Object result;
        if (subject instanceof PhpArray subjects) {
            PhpArray out = new PhpArray();
            for (int p = subjects.firstPosition(); p >= 0; p = subjects.nextPosition(p)) {
                TruffleString replaced = replaceOne(context, pattern, replacement, callback,
                        Convert.toStr(subjects.valueAt(p)), limit, flags, count);
                if (replaced != null) {
                    out.put(subjects.keyAt(p), replaced);
                }
            }
            result = out;
        } else {
            TruffleString replaced = replaceOne(context, pattern, replacement, callback, Convert.toStr(subject),
                    limit, flags, count);
            result = replaced == null ? PhpNull.NULL : replaced;
        }
        if (Args.has(a, 4)) {
            Args.ref(a, 4).set(count[0]);
        }
        return result;
    }

    private TruffleString replaceOne(PhpContext context, Object pattern, Object replacement, boolean callback,
                                            TruffleString subject, long limit, long flags, long[] count) {
        if (!(pattern instanceof PhpArray patterns)) {
            return replaceWith(context, compile(Convert.toStr(pattern)), replacement, callback, subject, limit, flags,
                    count);
        }
        PhpArray replacements = !callback && replacement instanceof PhpArray array ? array : null;
        int r = replacements == null ? -1 : replacements.firstPosition();
        TruffleString current = subject;
        for (int p = patterns.firstPosition(); p >= 0; p = patterns.nextPosition(p)) {
            Object with = replacement;
            if (replacements != null) {
                with = r >= 0 ? replacements.valueAt(r) : Str.EMPTY;
                r = r >= 0 ? replacements.nextPosition(r) : -1;
            }
            current = replaceWith(context, compile(Convert.toStr(patterns.valueAt(p))), with, callback, current, limit,
                    flags, count);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** php_pcre_replace_impl and its callback twin; null on a match error. */
    private TruffleString replaceWith(PhpContext context, Regex regex, Object replacement, boolean callback,
                                             TruffleString subjectString, long limit, long flags, long[] count) {
        byte[] subject = Str.readBytes(subjectString);
        Template template = callback ? null : template(Convert.toStr(replacement));
        ByteBuilder out = null;
        try (Matcher m = new Matcher(this, regex, subject)) {
            int lastEnd = 0;
            long start = 0;
            int rc = m.match(start, m.firstOptions());
            while (true) {
                if (rc >= 0 && limit > 0) {
                    if (out == null) {
                        out = ByteBuilder.borrow(subject.length);
                    }
                    count[0]++;
                    int matchStart = m.start(0);
                    int matchEnd = m.end(0);
                    out.write(subject, lastEnd, matchStart - lastEnd);
                    if (callback) {
                        Object value = Callables.call(context, replacement, groups(m, rc, flags));
                        out.write(Convert.toStr(value));
                    } else {
                        template.expand(out, m, rc);
                    }
                    limit--;
                    start = lastEnd = matchEnd;
                    if (matchStart == matchEnd) {
                        rc = m.match(start, NO_UTF_CHECK | NOTEMPTY_ATSTART | ANCHORED);
                        if (rc >= 0 && limit > 0) {
                            continue;
                        }
                        if (rc == ERROR_NOMATCH || rc >= 0) {
                            if (start >= subject.length) {
                                break;
                            }
                            start += m.unitLength((int) start);
                        } else {
                            execError(rc);
                            return null;
                        }
                    }
                } else if (rc == ERROR_NOMATCH || rc >= 0) {
                    break;
                } else {
                    execError(rc);
                    return null;
                }
                rc = m.match(start, NO_UTF_CHECK);
            }
            if (out == null) {
                return subjectString;
            }
            out.write(subject, lastEnd, subject.length - lastEnd);
            return Str.of(out.toByteArray());
        } finally {
            if (out != null) {
                out.release();
            }
        }
    }

    /**
     * A replacement template parsed once: literal chunks and group references (\n, $n, ${n}, n up to 99, with
     * php-src's preg_get_backref rules; a backslash before \ or $ makes it literal). ops holds a chunk length
     * (>= 0, the next bytes of text) or -(group + 1).
     */
    private static final class Template {
        final byte[] text;
        final int[] ops;

        private Template(byte[] text, int[] ops) {
            this.text = text;
            this.ops = ops;
        }

        static Template parse(byte[] template) {
            ByteBuilder text = new ByteBuilder(template.length);
            List<Integer> ops = new ArrayList<>();
            int chunkStart = 0;
            int i = 0;
            boolean lastWasBackslash = false;
            while (i < template.length) {
                byte c = template[i];
                if (c == '\\' || c == '$') {
                    if (lastWasBackslash) {
                        // \\ or \$: the escaping backslash (already in text) becomes this character
                        text.setLast(c);
                        i++;
                        lastWasBackslash = false;
                        continue;
                    }
                    int group = -1;
                    int j = i + 1;
                    boolean brace = c == '$' && j < template.length && template[j] == '{';
                    if (brace) {
                        j++;
                    }
                    if (i + 1 < template.length && j < template.length && isDigit(template[j])) {
                        group = template[j++] - '0';
                        if (j < template.length && isDigit(template[j])) {
                            group = group * 10 + template[j++] - '0';
                        }
                        if (brace) {
                            if (j < template.length && template[j] == '}') {
                                j++;
                            } else {
                                group = -1;
                            }
                        }
                    }
                    if (group >= 0) {
                        ops.add(text.size() - chunkStart);
                        chunkStart = text.size();
                        ops.add(-(group + 1));
                        i = j;
                        lastWasBackslash = false;
                        continue;
                    }
                }
                text.write(c);
                i++;
                lastWasBackslash = c == '\\';
            }
            ops.add(text.size() - chunkStart);
            int[] opArray = new int[ops.size()];
            for (int k = 0; k < opArray.length; k++) {
                opArray[k] = ops.get(k);
            }
            return new Template(text.toByteArray(), opArray);
        }

        void expand(ByteBuilder out, Matcher m, int count) {
            int position = 0;
            for (int op : ops) {
                if (op >= 0) {
                    out.write(text, position, op);
                    position += op;
                } else {
                    int group = -op - 1;
                    if (group < count && m.start(group) >= 0) {
                        out.write(m.subject, m.start(group), m.end(group) - m.start(group));
                    }
                }
            }
        }
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    // ---- preg_split, preg_quote, preg_grep ----

    @TruffleBoundary
    private Object split(TruffleString pattern, TruffleString subjectString, long limit, long flags) {
        Regex regex = compile(pattern);
        byte[] subject = Str.readBytes(subjectString);
        boolean noEmpty = (flags & SPLIT_NO_EMPTY) != 0;
        boolean delimCapture = (flags & SPLIT_DELIM_CAPTURE) != 0;
        boolean offsetCapture = (flags & SPLIT_OFFSET_CAPTURE) != 0;
        if (limit == 0) {
            limit = -1;
        }
        lastError = 0;
        PhpArray result = new PhpArray();
        int lastMatch = 0;
        try (Matcher m = new Matcher(this, regex, subject)) {
            long start = 0;
            int options = m.firstOptions();
            boolean retried = false;
            int rc = 0;
            while (limit == -1 || limit > 1) {
                if (!retried) {
                    rc = m.match(start, options);
                    options = NO_UTF_CHECK;
                }
                retried = false;
                if (rc == ERROR_NOMATCH) {
                    break;
                }
                if (rc < 0) {
                    execError(rc);
                    return Boolean.FALSE;
                }
                int matchStart = m.start(0);
                int matchEnd = m.end(0);
                if (!noEmpty || matchStart != lastMatch) {
                    addPiece(result, subject, lastMatch, matchStart, offsetCapture);
                    if (limit != -1) {
                        limit--;
                    }
                }
                if (delimCapture) {
                    for (int i = 1; i < rc; i++) {
                        int s = m.start(i);
                        int e = m.end(i);
                        if (!noEmpty || s != e) {
                            addPiece(result, subject, Math.max(s, 0), Math.max(e, 0), offsetCapture);
                        }
                    }
                }
                start = lastMatch = matchEnd;
                if (matchStart == matchEnd) {
                    if (limit != -1 && limit <= 1) {
                        break;
                    }
                    rc = m.match(start, NO_UTF_CHECK | NOTEMPTY_ATSTART | ANCHORED);
                    if (rc >= 0) {
                        retried = true;
                        continue;
                    }
                    if (rc != ERROR_NOMATCH) {
                        execError(rc);
                        return Boolean.FALSE;
                    }
                    if (start >= subject.length) {
                        break;
                    }
                    start += m.unitLength((int) start);
                }
            }
        }
        if (!noEmpty || lastMatch < subject.length) {
            addPiece(result, subject, lastMatch, subject.length, offsetCapture);
        }
        return result;
    }

    private static void addPiece(PhpArray result, byte[] subject, int from, int to, boolean offsetCapture) {
        TruffleString piece = Str.of(subject, from, to - from);
        result.append(offsetCapture ? PhpArray.list(piece, (long) from) : piece);
    }

    @TruffleBoundary
    private static TruffleString quote(TruffleString text, Object delimiter) {
        byte[] bytes = Str.readBytes(text);
        int delim = -1;
        if (delimiter != null && delimiter != PhpNull.NULL) {
            TruffleString d = Convert.toStr(delimiter);
            if (Str.length(d) > 0) {
                delim = Str.byteAt(d, 0);
            }
        }
        ByteBuilder out = new ByteBuilder(bytes.length + 8);
        for (byte b : bytes) {
            if (b == 0) {
                out.writeBytes(new byte[]{'\\', '0', '0', '0'});
                continue;
            }
            if (".\\+*?[^]$(){}=!<>|:-#".indexOf(b) >= 0 || (b & 0xFF) == delim) {
                out.write('\\');
            }
            out.write(b);
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    private PhpArray grep(TruffleString pattern, PhpArray input, long flags) {
        Regex regex = compile(pattern);
        boolean invert = (flags & 1) != 0;
        lastError = 0;
        PhpArray result = new PhpArray();
        for (int p = input.firstPosition(); p >= 0; p = input.nextPosition(p)) {
            Object value = input.valueAt(p);
            int rc;
            try (Matcher m = new Matcher(this, regex, Str.readBytes(Convert.toStr(value)))) {
                rc = m.match(0, m.firstOptions());
            }
            if (rc < 0 && rc != ERROR_NOMATCH) {
                execError(rc);
                break;
            }
            if ((rc >= 0) != invert) {
                result.put(input.keyAt(p), value);
            }
        }
        return result;
    }

    // ---- the PCRE2 library ----

    /** Downcalls into libpcre2-8. Set the library with -Dphp.pcre2=<path>; the default is the platform's name. */
    private static final class Native {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup(libraryPath(), Arena.global());
        private static final MethodHandle COMPILE = handle("pcre2_compile_8", ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        private static final MethodHandle JIT_COMPILE = handle("pcre2_jit_compile_8", ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        private static final MethodHandle MATCH_DATA = handle("pcre2_match_data_create_from_pattern_8",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        /*
         * The match downcalls are ordinary ones on subjects copied to native memory (SubjectCache). Critical
         * downcalls were measured slower on WordPress (wiki/gate-1.md): on native subjects wpautop and wptexturize
         * lost ~40% (the critical stub skips vzeroupper, so PCRE2's SSE code runs with dirty upper vector halves);
         * on the Java arrays themselves preg_split and preg_match_all lost 2-3x (the GC pins the array per call).
         */
        private static final FunctionDescriptor MATCH_SIGNATURE = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        private static final MethodHandle MATCH = LINKER.downcallHandle(find("pcre2_match_8"), MATCH_SIGNATURE);
        /** pcre2_jit_match: the JIT fast path without pcre2_match's argument checks, as php-src calls it. */
        private static final MethodHandle JIT_MATCH = LINKER.downcallHandle(find("pcre2_jit_match_8"),
                MATCH_SIGNATURE);
        private static final MethodHandle OVECTOR = handle("pcre2_get_ovector_pointer_8", ValueLayout.ADDRESS,
                ValueLayout.ADDRESS);
        private static final MethodHandle PATTERN_INFO = handle("pcre2_pattern_info_8", ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        private static final MethodHandle ERROR_MESSAGE = handle("pcre2_get_error_message_8", ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
        private static final MethodHandle MATCH_CONTEXT = handle("pcre2_match_context_create_8", ValueLayout.ADDRESS,
                ValueLayout.ADDRESS);
        private static final MethodHandle SET_MATCH_LIMIT = handle("pcre2_set_match_limit_8", ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        private static final MethodHandle SET_DEPTH_LIMIT = handle("pcre2_set_depth_limit_8", ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        private static final MethodHandle JIT_STACK = handle("pcre2_jit_stack_create_8", ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
        private static final MethodHandle JIT_STACK_ASSIGN = LINKER.downcallHandle(find("pcre2_jit_stack_assign_8"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        static final MethodHandle CODE_FREE = freeHandle("pcre2_code_free_8");
        static final MethodHandle MATCH_DATA_FREE = freeHandle("pcre2_match_data_free_8");
        static final MethodHandle MATCH_CONTEXT_FREE = freeHandle("pcre2_match_context_free_8");
        static final MethodHandle JIT_STACK_FREE = freeHandle("pcre2_jit_stack_free_8");

        private static String libraryPath() {
            String configured = System.getProperty("php.pcre2");
            if (configured != null) {
                return configured;
            }
            if (System.getProperty("os.name").startsWith("Windows")) {
                Path git = Path.of("C:/Program Files/Git/mingw64/bin/libpcre2-8-0.dll");
                return Files.exists(git) ? git.toString() : "libpcre2-8-0.dll";
            }
            return "libpcre2-8.so.0";
        }

        private static MemorySegment find(String name) {
            return LIBRARY.find(name).orElseThrow(() -> new IllegalStateException("PCRE2 symbol missing: " + name));
        }

        private static MethodHandle handle(String name, ValueLayout result, ValueLayout... arguments) {
            return LINKER.downcallHandle(find(name), FunctionDescriptor.of(result, arguments));
        }

        private static MethodHandle freeHandle(String name) {
            return LINKER.downcallHandle(find(name), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        }

        static void free(MethodHandle free, MemorySegment pointer) {
            try {
                free.invokeExact(pointer);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /** php-src's 32K..192K JIT stack; one per context, as only one thread matches with it at a time. */
        static MemorySegment jitStack() {
            try {
                return (MemorySegment) JIT_STACK.invokeExact(32L * 1024, 192L * 1024, MemorySegment.NULL);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /** A match context with pcre.backtrack_limit and pcre.recursion_limit's defaults, matching on stack. */
        static MemorySegment matchContext(MemorySegment stack) {
            try {
                MemorySegment context = (MemorySegment) MATCH_CONTEXT.invokeExact(MemorySegment.NULL);
                int ignored = (int) SET_MATCH_LIMIT.invokeExact(context, 1_000_000);
                ignored = (int) SET_DEPTH_LIMIT.invokeExact(context, 100_000);
                JIT_STACK_ASSIGN.invokeExact(context, MemorySegment.NULL, stack);
                return context;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        static MemorySegment compile(byte[] pattern, int options) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment source = arena.allocate(Math.max(1, pattern.length));
                MemorySegment.copy(pattern, 0, source, ValueLayout.JAVA_BYTE, 0, pattern.length);
                MemorySegment errorCode = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment errorOffset = arena.allocate(ValueLayout.JAVA_LONG);
                MemorySegment code = (MemorySegment) COMPILE.invokeExact(source, (long) pattern.length, options,
                        errorCode, errorOffset, MemorySegment.NULL);
                if (code.equals(MemorySegment.NULL)) {
                    MemorySegment message = arena.allocate(256);
                    int ignored = (int) ERROR_MESSAGE.invokeExact(errorCode.get(ValueLayout.JAVA_INT, 0), message,
                            256L);
                    throw new PhpError("Warning: Compilation failed: " + message.getString(0) + " at offset "
                            + errorOffset.get(ValueLayout.JAVA_LONG, 0));
                }
                return code;
            } catch (PhpError e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /** JIT-compiles the pattern; false (with a warning) when JIT isn't available, so matching is interpreted. */
        static boolean jitCompile(MemorySegment code) {
            try {
                int rc = (int) JIT_COMPILE.invokeExact(code, JIT_COMPLETE);
                if (rc != 0) {
                    System.err.println("warning: pcre2_jit_compile failed (" + rc + "), matching interpreted");
                }
                return rc == 0;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        static int info(MemorySegment code, int what) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment where = arena.allocate(ValueLayout.JAVA_INT);
                int rc = (int) PATTERN_INFO.invokeExact(code, what, where);
                if (rc != 0) {
                    throw new IllegalStateException("pcre2_pattern_info " + what + " failed: " + rc);
                }
                return where.get(ValueLayout.JAVA_INT, 0);
            } catch (Throwable t) {
                throw t instanceof RuntimeException e ? e : new IllegalStateException(t);
            }
        }

        /** Group names by group number from the name table (entries: 2-byte big-endian number, NUL-ended name). */
        static TruffleString[] names(MemorySegment code, int groups) {
            TruffleString[] names = new TruffleString[groups];
            int count = info(code, INFO_NAMECOUNT);
            if (count == 0) {
                return names;
            }
            int entrySize = info(code, INFO_NAMEENTRYSIZE);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment where = arena.allocate(ValueLayout.ADDRESS);
                int ignored = (int) PATTERN_INFO.invokeExact(code, INFO_NAMETABLE, where);
                MemorySegment table = where.get(ValueLayout.ADDRESS, 0).reinterpret((long) count * entrySize);
                for (int i = 0; i < count; i++) {
                    long base = (long) i * entrySize;
                    int group = (table.get(ValueLayout.JAVA_BYTE, base) & 0xFF) << 8
                            | table.get(ValueLayout.JAVA_BYTE, base + 1) & 0xFF;
                    names[group] = Str.of(table.getString(base + 2));
                }
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            return names;
        }

        static MemorySegment createMatchData(MemorySegment code) {
            try {
                return (MemorySegment) MATCH_DATA.invokeExact(code, MemorySegment.NULL);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        static MemorySegment ovector(MemorySegment matchData, int groups) {
            try {
                MemorySegment pointer = (MemorySegment) OVECTOR.invokeExact(matchData);
                return pointer.reinterpret(16L * groups);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /**
         * One match. Like php-src, a JIT-compiled pattern matched with no options but NO_UTF_CHECK takes
         * pcre2_jit_match; the first UTF-checked match and anchored retries after an empty match take pcre2_match.
         */
        static int match(MemorySegment code, boolean jit, MemorySegment subject, long length, long start,
                         int options, MemorySegment matchData, MemorySegment matchContext) {
            try {
                if (jit && options == NO_UTF_CHECK) {
                    return (int) JIT_MATCH.invokeExact(code, subject, length, start, options, matchData,
                            matchContext);
                }
                return (int) MATCH.invokeExact(code, subject, length, start, options, matchData, matchContext);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }
}
