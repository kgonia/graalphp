package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Records the PCRE2 calls of a run for bench/gate1/pcre-replay.c, which replays them against the same pcre2lib and
 * times the regex work alone. That time is the same on both engines (they call the same matcher in the same
 * sequence, as the preg wrappers follow php-src), so it is Gate 1's native time without timing native calls from
 * Java, whose clock and transitions inflated the estimate.
 *
 * <p>On with -Dphp.pcre2.record=file. truffle_pcre_record(label) starts a section, truffle_pcre_record('') ends it;
 * only matches inside a section are written. Big-endian records: 'K' label, 'E'; 'P' id options jit pattern, before
 * a pattern's first recorded match; 'S' id subject, likewise; 'M' pattern subject start options.
 */
final class PcreRecorder {
    static final boolean ENABLED = System.getProperty("php.pcre2.record") != null;

    private static DataOutputStream out;
    private static boolean recording;
    private static final Map<Object, Integer> PATTERNS = new IdentityHashMap<>();
    private static final Map<ByteBuffer, Integer> SUBJECTS = new HashMap<>();

    private PcreRecorder() {
    }

    /** Starts a section with this label, or ends the current one for an empty label. */
    @TruffleBoundary
    static synchronized void section(String label) {
        try {
            if (out == null) {
                out = new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream(System.getProperty("php.pcre2.record")), 1 << 16));
                Runtime.getRuntime().addShutdownHook(new Thread(PcreRecorder::close));
            }
            if (recording) {
                out.writeByte('E');
            }
            recording = !label.isEmpty();
            if (recording) {
                out.writeByte('K');
                writeBytes(label.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One match of regex (identified by the object) compiled from pattern with options. */
    @TruffleBoundary
    static synchronized void match(Object regex, byte[] pattern, int options, boolean jit, byte[] subject,
                                   long length, long start, int matchOptions) {
        if (!recording) {
            return;
        }
        try {
            Integer patternId = PATTERNS.get(regex);
            if (patternId == null) {
                patternId = PATTERNS.size();
                PATTERNS.put(regex, patternId);
                out.writeByte('P');
                out.writeInt(patternId);
                out.writeInt(options);
                out.writeByte(jit ? 1 : 0);
                writeBytes(pattern);
            }
            ByteBuffer key = ByteBuffer.wrap(subject, 0, (int) length);
            Integer subjectId = SUBJECTS.get(key);
            if (subjectId == null) {
                subjectId = SUBJECTS.size();
                byte[] copy = new byte[(int) length];
                System.arraycopy(subject, 0, copy, 0, copy.length);
                SUBJECTS.put(ByteBuffer.wrap(copy), subjectId);
                out.writeByte('S');
                out.writeInt(subjectId);
                writeBytes(copy);
            }
            out.writeByte('M');
            out.writeInt(patternId);
            out.writeInt(subjectId);
            out.writeLong(start);
            out.writeInt(matchOptions);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeBytes(byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static synchronized void close() {
        try {
            if (recording) {
                out.writeByte('E');
            }
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
