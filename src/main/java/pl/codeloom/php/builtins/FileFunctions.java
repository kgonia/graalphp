package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.Str;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The filesystem and stream functions: plain files and php://memory, php://temp, php://input, php://stdin,
 * php://stdout, php://stderr and php://output. A stream or directory handle is a {@link PhpResource}. Files are
 * reached through the context's {@link TruffleLanguage.Env} ({@link TruffleFile}), so the embedder's IO policy and
 * file system apply; the standard streams are the Env's.
 */
final class FileFunctions {
    private FileFunctions() {
    }

    /** php-src's stream chunk size: a stream's read buffer fills this much at a time. */
    private static final int CHUNK = 8192;
    private static final int SEEK_SET = 0;
    private static final int SEEK_CUR = 1;
    private static final int SEEK_END = 2;
    private static final byte[] EMPTY = new byte[0];

    /**
     * An open stream, as php-src's php_stream (main/streams/streams.c): a read buffer and a logical position over
     * the wrapper's raw operations. Reads fill the buffer a chunk at a time; fgets, fread, fseek, ftell, feof and
     * writes after reads behave as php-src's, including what stays buffered when the file changes underneath.
     */
    abstract static class Stream {
        final String mode;
        final String uri;
        private byte[] buffer = EMPTY;
        private int readPos;
        private int writePos;
        /** stream->position: ftell(); -1 after some failed seeks, as in php-src. */
        long position;
        /** stream->eof: set when a raw read returns nothing. */
        boolean eof;

        Stream(String mode, String uri) {
            this.mode = mode;
            this.uri = uri;
        }

        // ---- the wrapper's operations (php_stream_ops) ----

        /** read(2): the count read, 0 at the end of the data (setting eof), or -1 on an error. */
        abstract int rawRead(byte[] into, int offset, int length);

        /** write(2): the count written, or -1 on an error. */
        abstract int rawWrite(byte[] bytes, int offset, int length);

        /** lseek for SEEK_SET or SEEK_END: 0 with position updated, or -1. Only called when seekable. */
        int rawSeek(long offset, int whence) {
            return -1;
        }

        boolean seekable() {
            return false;
        }

        /** Reads bypass the buffer, as php-src's PHP_STREAM_FLAG_NO_BUFFER (memory and temp streams). */
        boolean unbuffered() {
            return false;
        }

        /** fread keeps reading to the length asked, as php-src does only for plain files, memory and temp. */
        boolean greedy() {
            return true;
        }

        void rawClose() throws IOException {
        }

        abstract String wrapperType();

        abstract String streamType();

        /** ftruncate(): true, false, or null if the stream can't be truncated ("Can't truncate this stream!"). */
        Boolean truncate(long size) {
            return null;
        }

        /** fstat(), or null when the stream has no stat. */
        Object stat() {
            return null;
        }

        /** flock() support: false for streams without locking (php://memory, php://output, ...). */
        boolean lockable() {
            return false;
        }

        // ---- php-src's buffered layer ----

        final boolean atEof() {
            return writePos - readPos <= 0 && eof;
        }

        final int unreadBytes() {
            return writePos - readPos;
        }

        /** _php_stream_fill_read_buffer: makes size bytes available if the wrapper has them; false on an error. */
        private boolean fill(int size) {
            if (writePos - readPos >= size) {
                return true;
            }
            if (buffer.length - writePos < CHUNK) {
                if (writePos > readPos) {
                    System.arraycopy(buffer, readPos, buffer, 0, writePos - readPos);
                }
                writePos -= readPos;
                readPos = 0;
            }
            if (buffer.length - writePos < CHUNK) {
                buffer = Arrays.copyOf(buffer, buffer.length + CHUNK);
            }
            int n = rawRead(buffer, writePos, buffer.length - writePos);
            if (n < 0) {
                return false;
            }
            writePos += n;
            return true;
        }

        /** _php_stream_read: up to size bytes, or null if the first read failed. */
        final byte[] read(int size) {
            byte[] out = new byte[Math.min(size, CHUNK)];
            int didread = 0;
            while (size > 0) {
                int available = writePos - readPos;
                if (available > 0) {
                    int n = Math.min(available, size);
                    out = room(out, didread + n);
                    System.arraycopy(buffer, readPos, out, didread, n);
                    readPos += n;
                    size -= n;
                    didread += n;
                }
                if (size == 0) {
                    break;
                }
                int got;
                if (unbuffered()) {
                    int want = Math.min(size, Math.max(CHUNK, out.length - didread));
                    out = room(out, didread + want);
                    got = rawRead(out, didread, want);
                    if (got < 0) {
                        if (didread == 0) {
                            return null;
                        }
                        break;
                    }
                } else {
                    if (!fill(size)) {
                        if (didread == 0) {
                            return null;
                        }
                        break;
                    }
                    got = Math.min(writePos - readPos, size);
                    if (got > 0) {
                        out = room(out, didread + got);
                        System.arraycopy(buffer, readPos, out, didread, got);
                        readPos += got;
                    }
                }
                if (got <= 0) {
                    break;
                }
                didread += got;
                size -= got;
                if (!greedy()) {
                    break;
                }
            }
            position += didread;
            return didread == out.length ? out : Arrays.copyOf(out, didread);
        }

        /** _php_stream_get_line: up to and including \n, at most limit bytes (-1: no limit); null if none. */
        final byte[] readLine(int limit) {
            byte[] line = EMPTY;
            int total = 0;
            for (;;) {
                int available = writePos - readPos;
                if (available > 0) {
                    int n = available;
                    boolean done = false;
                    for (int i = readPos; i < writePos; i++) {
                        if (buffer[i] == '\n') {
                            n = i - readPos + 1;
                            done = true;
                            break;
                        }
                    }
                    if (limit >= 0 && n >= limit - total) {
                        n = limit - total;
                        done = true;
                    }
                    line = room(line, total + n);
                    System.arraycopy(buffer, readPos, line, total, n);
                    position += n;
                    readPos += n;
                    total += n;
                    if (done) {
                        break;
                    }
                } else if (eof) {
                    break;
                } else {
                    fill(limit < 0 ? CHUNK : Math.min(limit - total, CHUNK));
                    if (writePos - readPos == 0) {
                        break;
                    }
                }
            }
            return total == 0 ? null : total == line.length ? line : Arrays.copyOf(line, total);
        }

        /** _php_stream_write: the count written, or -1 if nothing could be. */
        final int write(byte[] bytes) {
            if (bytes.length == 0) {
                return 0;
            }
            if (seekable() && readPos != writePos) {
                readPos = 0;
                writePos = 0;
                rawSeek(position, SEEK_SET);
            }
            int done = 0;
            while (done < bytes.length) {
                int n = rawWrite(bytes, done, bytes.length - done);
                if (n <= 0) {
                    return done == 0 ? n : done;
                }
                done += n;
                position += n;
            }
            return done;
        }

        /** _php_stream_seek: 0 or -1. */
        final int seek(long offset, int whence) {
            if (!unbuffered()) {
                if (whence == SEEK_CUR && offset > 0 && offset <= writePos - readPos) {
                    readPos += (int) offset;
                    position += offset;
                    eof = false;
                    return 0;
                }
                if (whence == SEEK_SET && offset > position && offset <= position + writePos - readPos) {
                    readPos += (int) (offset - position);
                    position = offset;
                    eof = false;
                    return 0;
                }
            }
            if (seekable()) {
                long target = offset;
                int mode = whence;
                if (whence == SEEK_CUR) {
                    target = position + offset;
                    mode = SEEK_SET;
                }
                int result = rawSeek(target, mode);
                if (result == 0) {
                    eof = false;
                }
                readPos = 0;
                writePos = 0;
                return result;
            }
            if (whence == SEEK_CUR && offset >= 0) {
                long left = offset;
                while (left > 0) {
                    byte[] skipped = read((int) Math.min(left, 1024));
                    if (skipped == null || skipped.length == 0) {
                        return -1;
                    }
                    left -= skipped.length;
                }
                eof = false;
                return 0;
            }
            return -1;
        }

        /** _php_stream_copy_to_mem: up to maxLength bytes (-1: to the end). */
        final byte[] readAll(long maxLength) {
            if (maxLength == 0) {
                return EMPTY;
            }
            boolean small = maxLength > 0 && maxLength < 4 * CHUNK;
            byte[] out = EMPTY;
            int length = 0;
            while (maxLength < 0 || length < maxLength) {
                if (small && atEof()) {
                    break;
                }
                long want = maxLength < 0 ? Integer.MAX_VALUE - 8 - length : maxLength - length;
                byte[] got = read((int) Math.min(want, Integer.MAX_VALUE - 8 - length));
                if (got == null || got.length == 0) {
                    break;
                }
                out = room(out, length + got.length);
                System.arraycopy(got, 0, out, length, got.length);
                length += got.length;
            }
            return length == out.length ? out : Arrays.copyOf(out, length);
        }

        /** Discards what is buffered (the wrapper's data changed under it). */
        final void dropBuffer() {
            readPos = 0;
            writePos = 0;
        }

        final void close() throws IOException {
            buffer = EMPTY;
            rawClose();
        }

        private static byte[] room(byte[] array, int needed) {
            return needed <= array.length ? array : Arrays.copyOf(array, Math.max(needed, array.length * 2));
        }
    }

    /** A plain file (php-src's plain_wrapper): a channel from the file's {@link TruffleFile}. */
    static final class FileStream extends Stream {
        final TruffleFile file;
        final SeekableByteChannel channel;
        /** a+: the channel can't both read and append, so each write moves to the end first (O_APPEND). */
        private final boolean appendOnWrite;
        /** The flock() this stream holds: its file's lock state, or null; guarded by that state's monitor. */
        FileLocks.Lock lock;
        boolean lockExclusive;

        FileStream(TruffleFile file, SeekableByteChannel channel, boolean appendOnWrite, String mode, String uri) {
            super(mode, uri);
            this.file = file;
            this.channel = channel;
            this.appendOnWrite = appendOnWrite;
        }

        @Override
        int rawRead(byte[] into, int offset, int length) {
            try {
                int n = channel.read(ByteBuffer.wrap(into, offset, length));
                if (n <= 0) {
                    eof = true;
                    return 0;
                }
                return n;
            } catch (NonReadableChannelException e) {
                return -1; // EBADF: php-src leaves eof unset
            } catch (IOException e) {
                eof = true;
                return -1;
            }
        }

        @Override
        int rawWrite(byte[] bytes, int offset, int length) {
            try {
                if (appendOnWrite) {
                    channel.position(channel.size());
                }
                return channel.write(ByteBuffer.wrap(bytes, offset, length));
            } catch (NonWritableChannelException | IOException e) {
                return -1;
            }
        }

        @Override
        int rawSeek(long offset, int whence) {
            try {
                long target = whence == SEEK_SET ? offset : whence == SEEK_END ? channel.size() + offset : -1;
                if (target < 0) {
                    return -1;
                }
                channel.position(target);
                position = target;
                return 0;
            } catch (IOException e) {
                return -1;
            }
        }

        @Override
        boolean seekable() {
            return true;
        }

        @Override
        void rawClose() throws IOException {
            FileLocks.unlock(this);
            channel.close();
        }

        @Override
        String wrapperType() {
            return "plainfile";
        }

        @Override
        String streamType() {
            return "STDIO";
        }

        /** ftruncate(2): the file offset stays where it was, so a later write past the end leaves a hole. */
        @Override
        Boolean truncate(long size) {
            try {
                long offset = channel.position();
                long current = channel.size();
                if (size < current) {
                    channel.truncate(size);
                } else if (size > current) {
                    channel.position(size - 1);
                    channel.write(ByteBuffer.wrap(new byte[1]));
                }
                channel.position(offset);
                return Boolean.TRUE;
            } catch (NonWritableChannelException | IOException e) {
                return Boolean.FALSE;
            }
        }

        @Override
        Object stat() {
            try {
                return FileFunctions.stat(file, true);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        boolean lockable() {
            return true;
        }
    }

    /**
     * flock()'s advisory locks, kept as flock(2) keeps them: per open stream, shared (LOCK_SH) or exclusive
     * (LOCK_EX); converting a lock drops it before taking the new one, so a failed LOCK_NB upgrade leaves none, as
     * on Linux; closing the stream releases it.
     *
     * <p>php-src's processes exclude each other through the kernel. The contexts of one JVM are threads of one
     * process, and FileChannel locks belong to the whole JVM, so the lock state lives here, JVM-wide as the kernel's
     * is machine-wide. For other processes the JVM holds one OS lock per locked file while it has holders, shared or
     * exclusive as they are, on one byte far past the data: Windows enforces a locked range on reads and writes,
     * where flock() is advisory. Other Truffle PHP processes see that lock; php-src processes on Linux don't, since
     * Java's locks are fcntl locks there, which flock(2) ignores.
     */
    static final class FileLocks {
        static final int LOCKED = 0;
        static final int WOULD_BLOCK = 1;
        static final int FAILED = -1;
        private static final long OS_LOCK_POSITION = Long.MAX_VALUE - 1;
        /** The locked (or waited-for) files by canonical path. */
        private static final ConcurrentHashMap<String, Lock> FILES = new ConcurrentHashMap<>();

        private FileLocks() {
        }

        /** One file's lock state. Its monitor guards it and its holders' lock fields; waiters wait on it. */
        static final class Lock {
            private final String key;
            private FileStream exclusive;
            /** Identity set: streams don't override equals. */
            private final Set<FileStream> shared = new HashSet<>();
            private int waiting;
            private boolean removed;
            /** The channel holding the JVM's OS lock on the file, or null. */
            private FileChannel osChannel;

            Lock(String key) {
                this.key = key;
            }

            private boolean unheld() {
                return exclusive == null && shared.isEmpty();
            }
        }

        /** flock(LOCK_SH or LOCK_EX, with LOCK_NB if nonBlocking): LOCKED, WOULD_BLOCK or FAILED. */
        @TruffleBoundary
        static int lock(FileStream stream, boolean exclusive, boolean nonBlocking) {
            String key = key(stream.file);
            if (stream.lock != null && !stream.lock.key.equals(key)) {
                unlock(stream); // the file was renamed since it was locked
            }
            for (;;) {
                Lock lock = FILES.computeIfAbsent(key, Lock::new);
                synchronized (lock) {
                    if (lock.removed) {
                        continue;
                    }
                    lock.waiting++;
                    try {
                        return acquire(lock, stream, exclusive, nonBlocking);
                    } finally {
                        lock.waiting--;
                        forgetIfUnused(lock);
                    }
                }
            }
        }

        /** flock(LOCK_UN), and fclose: releases what the stream holds, if anything. */
        @TruffleBoundary
        static void unlock(FileStream stream) {
            Lock lock = stream.lock;
            if (lock == null) {
                return;
            }
            synchronized (lock) {
                if (stream.lock == lock) {
                    release(lock, stream);
                }
                forgetIfUnused(lock);
            }
        }

        private static int acquire(Lock lock, FileStream stream, boolean exclusive, boolean nonBlocking) {
            if (stream.lock == lock) {
                if (stream.lockExclusive == exclusive) {
                    return LOCKED;
                }
                release(lock, stream);
            }
            while (lock.exclusive != null || exclusive && !lock.shared.isEmpty()) {
                if (nonBlocking) {
                    return WOULD_BLOCK;
                }
                TruffleSafepoint.setBlockedThreadInterruptible(null, Object::wait, lock);
            }
            if (lock.unheld()) {
                int result = lockOs(lock, exclusive, nonBlocking);
                if (result != LOCKED) {
                    return result;
                }
            }
            if (exclusive) {
                lock.exclusive = stream;
            } else {
                lock.shared.add(stream);
            }
            stream.lock = lock;
            stream.lockExclusive = exclusive;
            return LOCKED;
        }

        private static void release(Lock lock, FileStream stream) {
            if (lock.exclusive == stream) {
                lock.exclusive = null;
            } else {
                lock.shared.remove(stream);
            }
            stream.lock = null;
            if (lock.unheld()) {
                closeOs(lock);
            }
            lock.notifyAll();
        }

        /**
         * Takes the file's OS lock for the JVM's first holder. TruffleFile's channels don't expose FileChannel's
         * locks, so the lock channel is opened on the host path of a file the stream already opened through the
         * Env (no data is read or written through it). A shared lock reads the file, an exclusive one needs it
         * writable; without that, or when the path is not on the host file system (a virtual file system), the lock
         * is the JVM's alone.
         */
        private static int lockOs(Lock lock, boolean exclusive, boolean nonBlocking) {
            FileChannel channel;
            try {
                channel = FileChannel.open(Path.of(lock.key), exclusive
                        ? EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                        : EnumSet.of(StandardOpenOption.READ));
            } catch (IOException | UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
                return LOCKED;
            }
            try {
                FileLock os = nonBlocking ? channel.tryLock(OS_LOCK_POSITION, 1, !exclusive)
                        : channel.lock(OS_LOCK_POSITION, 1, !exclusive);
                if (os == null) {
                    channel.close();
                    return WOULD_BLOCK;
                }
                lock.osChannel = channel;
                return LOCKED;
            } catch (OverlappingFileLockException e) {
                // the JVM holds this file's OS lock under another path (a link): the JVM-wide state excludes
                closeQuietly(channel);
                return LOCKED;
            } catch (IOException e) {
                closeQuietly(channel);
                return FAILED;
            }
        }

        private static void closeOs(Lock lock) {
            if (lock.osChannel != null) {
                closeQuietly(lock.osChannel); // releases the OS lock
                lock.osChannel = null;
            }
        }

        private static void forgetIfUnused(Lock lock) {
            if (lock.waiting == 0 && lock.unheld() && !lock.removed) {
                lock.removed = true;
                FILES.remove(lock.key, lock);
            }
        }

        private static String key(TruffleFile file) {
            try {
                return file.getCanonicalFile().getPath();
            } catch (IOException | SecurityException e) {
                return file.getAbsoluteFile().normalize().getPath();
            }
        }

        private static void closeQuietly(SeekableByteChannel channel) {
            try {
                channel.close();
            } catch (IOException e) {
                // nothing more to release
            }
        }
    }

    /**
     * php://memory and php://temp (php-src's memory streams; a temp stream never spills to a file here), and the
     * read-only php://input over the request body.
     */
    static final class MemoryStream extends Stream {
        private final String type;
        private final boolean readOnly;
        private final boolean append;
        private byte[] data;
        private int size;
        /** ms->fpos: the memory's own offset, which the buffered layer's position may differ from. */
        private int offset;

        MemoryStream(String type, String mode, String uri, boolean readOnly, boolean append, byte[] contents) {
            super(mode, uri);
            this.type = type;
            this.readOnly = readOnly;
            this.append = append;
            this.data = contents;
            this.size = contents.length;
        }

        /** php://memory or php://temp opened with the fopen mode: read-only without w, a or +; a appends. */
        static MemoryStream open(String type, String fopenMode, String uri) {
            boolean append = fopenMode.indexOf('a') >= 0;
            boolean readOnly = !append && fopenMode.indexOf('w') < 0 && fopenMode.indexOf('+') < 0;
            return new MemoryStream(type, readOnly ? "rb" : append ? "a+b" : "w+b", uri, readOnly, append, EMPTY);
        }

        @Override
        int rawRead(byte[] into, int at, int length) {
            if (offset >= size) {
                eof = true;
                return 0;
            }
            int n = Math.min(length, size - offset);
            System.arraycopy(data, offset, into, at, n);
            offset += n;
            return n;
        }

        @Override
        int rawWrite(byte[] bytes, int at, int length) {
            if (readOnly) {
                return -1;
            }
            if (append) {
                offset = size;
            }
            if (offset + length > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, offset + length));
            }
            if (offset > size) {
                Arrays.fill(data, size, offset, (byte) 0);
            }
            System.arraycopy(bytes, at, data, offset, length);
            offset += length;
            size = Math.max(size, offset);
            return length;
        }

        /** php_stream_memory_seek: past the end is allowed; before the start fails (SEEK_END also resets). */
        @Override
        int rawSeek(long target, int whence) {
            if (whence == SEEK_SET) {
                if (target < 0 || target > Integer.MAX_VALUE - 8) {
                    return -1;
                }
                offset = (int) target;
            } else if (whence == SEEK_END) {
                if (target < 0 && -target > size) {
                    offset = 0;
                    position = -1;
                    return -1;
                }
                offset = (int) (size + target);
            } else {
                return -1;
            }
            position = offset;
            return 0;
        }

        @Override
        boolean seekable() {
            return true;
        }

        @Override
        boolean unbuffered() {
            return !type.equals("Input");
        }

        @Override
        String wrapperType() {
            return "PHP";
        }

        @Override
        String streamType() {
            return type;
        }

        @Override
        Boolean truncate(long newSize) {
            if (type.equals("Input")) {
                return null;
            }
            if (readOnly || newSize > Integer.MAX_VALUE - 8) {
                return Boolean.FALSE;
            }
            int target = (int) newSize;
            if (target <= size) {
                offset = Math.min(offset, target);
            } else {
                if (target > data.length) {
                    data = Arrays.copyOf(data, target);
                }
                Arrays.fill(data, size, target, (byte) 0);
            }
            size = target;
            return Boolean.TRUE;
        }

        /** php_stream_memory_stat: a regular file of the memory's size, rw (or r) for everyone, no times. */
        @Override
        Object stat() {
            if (type.equals("Input")) {
                return null;
            }
            long[] values = {0xC, 0, (readOnly ? 0444 : 0666) | 0100000, 1, 0, 0, -1, size, 0, 0, 0, -1, -1};
            return statArray(values);
        }
    }

    /** php://stdin, php://stdout and php://stderr: the context's standard streams (unseekable, as pipes). */
    static final class StdStream extends Stream {
        private final PhpContext context;
        private final int fd;

        StdStream(PhpContext context, int fd, String mode, String uri) {
            super(mode, uri);
            this.context = context;
            this.fd = fd;
        }

        @Override
        int rawRead(byte[] into, int offset, int length) {
            if (fd != 0) {
                return -1;
            }
            try {
                int n = context.getEnv().in().read(into, offset, length);
                if (n <= 0) {
                    eof = true;
                    return 0;
                }
                return n;
            } catch (IOException e) {
                eof = true;
                return -1;
            }
        }

        /**
         * php://stdout bypasses output buffering, as php-src's write to fd 1; what echo wrote goes out first. Under
         * php-cli, which writes echo unbuffered, that holds for stderr too (under CGI stderr is the server's log, and
         * flushing would send the headers).
         */
        @Override
        int rawWrite(byte[] bytes, int offset, int length) {
            if (fd == 0) {
                return -1;
            }
            try {
                if (fd == 1 || context.response() == null) {
                    context.flush();
                }
                OutputStream out = fd == 1 ? context.getEnv().out() : context.getEnv().err();
                out.write(bytes, offset, length);
                out.flush();
                return length;
            } catch (IOException e) {
                return -1;
            }
        }

        @Override
        boolean greedy() {
            return false;
        }

        @Override
        String wrapperType() {
            return "PHP";
        }

        @Override
        String streamType() {
            return "STDIO";
        }

        @Override
        Boolean truncate(long size) {
            return Boolean.FALSE;
        }

        @Override
        boolean lockable() {
            return true;
        }
    }

    /** php://output: writes go through the output layer (output buffers, then the response). */
    static final class OutputStreamWrapper extends Stream {
        private final PhpContext context;

        OutputStreamWrapper(PhpContext context, String uri) {
            super("wb", uri);
            this.context = context;
        }

        @Override
        int rawRead(byte[] into, int offset, int length) {
            eof = true;
            return -1;
        }

        @Override
        int rawWrite(byte[] bytes, int offset, int length) {
            context.write(Str.of(Arrays.copyOfRange(bytes, offset, offset + length)));
            return length;
        }

        @Override
        boolean greedy() {
            return false;
        }

        @Override
        String wrapperType() {
            return "PHP";
        }

        @Override
        String streamType() {
            return "Output";
        }
    }

    /**
     * opendir's handle: the entries, read in order by readdir. php-src's directory streams are streams too, whose
     * byte reads and writes fail and whose seek to 0 is rewinddir.
     */
    static final class Directory extends Stream {
        final List<String> entries;
        int next;

        Directory(List<String> entries, String uri) {
            super("r", uri);
            this.entries = entries;
        }

        @Override
        int rawRead(byte[] into, int offset, int length) {
            return -1;
        }

        @Override
        int rawWrite(byte[] bytes, int offset, int length) {
            return -1;
        }

        @Override
        int rawSeek(long offset, int whence) {
            if (offset != 0 || whence != SEEK_SET) {
                return -1;
            }
            next = 0;
            position = 0;
            return 0;
        }

        @Override
        boolean seekable() {
            return true;
        }

        @Override
        String wrapperType() {
            return "plainfile";
        }

        @Override
        String streamType() {
            return "dir";
        }
    }

    static void register(Registry r) {
        // ---- streams ----
        r.fn("fopen", (context, a) -> open(context, Str.java(Args.str(a, 0)), Str.java(Args.str(a, 1))));
        r.fn("fclose", (context, a) -> {
            Stream stream = stream(a, "fclose");
            if (stream instanceof Directory) {
                return Boolean.FALSE; // "cannot close the provided stream, as it must not be manually closed"
            }
            PhpResource resource = resource(a, 0, "fclose", "stream");
            context.builtinState().openStreams.remove(stream);
            closeStream(stream);
            resource.close();
            return Boolean.TRUE;
        });
        r.fn("fread", a -> {
            Stream stream = stream(a, "fread");
            long length = Args.lng(a, 1, 0);
            if (length <= 0) {
                throw new PhpError("ValueError: fread(): Argument #2 ($length) must be greater than 0");
            }
            byte[] bytes = stream.read((int) Math.min(length, Integer.MAX_VALUE - 8));
            return bytes == null ? (Object) Boolean.FALSE : Str.of(bytes);
        });
        r.fn("fwrite", a -> write(a, "fwrite"));
        r.fn("fputs", a -> write(a, "fputs"));
        r.fn("fgets", a -> {
            Stream stream = stream(a, "fgets");
            int limit = -1;
            if (Args.has(a, 1) && Args.get(a, 1) != PhpNull.NULL) {
                long length = Args.lng(a, 1, 0);
                if (length <= 0) {
                    throw new PhpError("ValueError: fgets(): Argument #2 ($length) must be greater than 0");
                }
                limit = (int) Math.min(length - 1, Integer.MAX_VALUE - 8);
            }
            byte[] line = stream.readLine(limit);
            return line == null ? (Object) Boolean.FALSE : Str.of(line);
        });
        r.fn("fgetc", a -> {
            byte[] c = stream(a, "fgetc").read(1);
            return c == null || c.length == 0 ? (Object) Boolean.FALSE : Str.of(c);
        });
        r.fn("feof", a -> stream(a, "feof").atEof());
        r.fn("fseek", a -> (long) stream(a, "fseek").seek(Args.lng(a, 1, 0), (int) Args.lng(a, 2, SEEK_SET)));
        r.fn("ftell", a -> {
            long position = stream(a, "ftell").position;
            return position < 0 ? (Object) Boolean.FALSE : (Object) position;
        });
        r.fn("rewind", a -> stream(a, "rewind").seek(0, SEEK_SET) == 0);
        r.fn("fflush", a -> {
            stream(a, "fflush");
            return Boolean.TRUE;
        });
        r.fn("flock", "001", a -> flock(a));
        r.fn("ftruncate", a -> {
            long size = Args.lng(a, 1, 0);
            if (size < 0) {
                throw new PhpError("ValueError: ftruncate(): Argument #2 ($size) must be greater than or equal to 0");
            }
            Stream stream = stream(a, "ftruncate");
            Boolean result = stream.truncate(size);
            return result == null ? Boolean.FALSE : result;
        });
        r.fn("stream_get_contents", a -> {
            long length = Args.has(a, 1) && Args.get(a, 1) != PhpNull.NULL ? Args.lng(a, 1, -1) : -1;
            if (length < -1) {
                throw new PhpError("ValueError: stream_get_contents(): Argument #2 ($length) must be greater than or "
                        + "equal to -1");
            }
            Stream stream = stream(a, "stream_get_contents");
            long offset = Args.lng(a, 2, -1);
            if (offset >= 0) {
                long position = stream.position;
                int result = 0;
                if (position >= 0 && offset > position) {
                    result = stream.seek(offset - position, SEEK_CUR);
                } else if (offset < position) {
                    result = stream.seek(offset, SEEK_SET);
                }
                if (result != 0) {
                    return Boolean.FALSE; // "Failed to seek to position %d in the stream"
                }
            }
            return Str.of(stream.readAll(length));
        });
        r.fn("stream_get_meta_data", a -> meta(stream(a, "stream_get_meta_data")));
        r.fn("stream_set_timeout", a -> Boolean.TRUE);
        r.fn("stream_set_blocking", a -> Boolean.TRUE);
        r.fn("stream_context_create", (context, a) -> new PhpResource(context.nextResourceId(), "stream-context",
                Args.get(a, 0, new PhpArray())));
        r.fn("stream_context_get_options", a -> {
            Object options = resource(a, 0, "stream_context_get_options", "stream_or_context").getHandle();
            return options instanceof PhpArray array ? array : new PhpArray();
        });
        r.fn("stream_get_wrappers", a -> list(List.of("php", "file")));
        r.fn("stream_is_local", a -> Boolean.TRUE);
        r.fn("is_resource", a -> Args.get(a, 0) instanceof PhpResource resource && !resource.isClosed());
        r.fn("get_resource_type", a -> Str.of(resource(a, 0, "get_resource_type", "resource", false).getType()));
        r.fn("get_resource_id", a -> (long) resource(a, 0, "get_resource_id", "resource", false).getId());

        // ---- whole files ----
        r.fn("file_put_contents", (context, a) -> putContents(context, Str.java(Args.str(a, 0)), Args.get(a, 1),
                Args.lng(a, 2, 0)));
        r.fn("file", (context, a) -> lines(context, Str.java(Args.str(a, 0)), Args.lng(a, 1, 0)));
        r.fn("readfile", (context, a) -> {
            byte[] bytes = readAll(context, Str.java(Args.str(a, 0)));
            if (bytes == null) {
                return Boolean.FALSE;
            }
            context.write(Str.of(bytes));
            return (long) bytes.length;
        });
        r.fn("md5_file", (context, a) -> digestFile(context, "MD5", a));
        r.fn("sha1_file", (context, a) -> digestFile(context, "SHA-1", a));

        // ---- metadata ----
        r.fn("is_readable", (context, a) -> test(context, a, file -> file.isReadable()));
        r.fn("is_writable", (context, a) -> test(context, a, file -> file.isWritable()));
        r.fn("is_writeable", (context, a) -> test(context, a, file -> file.isWritable()));
        r.fn("is_executable", (context, a) -> test(context, a, file -> file.isRegularFile() && file.isExecutable()));
        r.fn("is_link", (context, a) -> test(context, a, file -> file.isSymbolicLink()));
        r.fn("filesize", (context, a) -> io(() -> file(context, a, 0).size()));
        r.fn("filemtime", (context, a) -> io(() -> seconds(file(context, a, 0).getLastModifiedTime())));
        r.fn("fileatime", (context, a) -> io(() -> seconds(file(context, a, 0).getLastAccessTime())));
        r.fn("filectime", (context, a) -> io(() -> seconds(file(context, a, 0).getLastModifiedTime())));
        r.fn("fileperms", (context, a) -> io(() -> mode(file(context, a, 0), true)));
        r.fn("stat", (context, a) -> io(() -> stat(file(context, a, 0), true)));
        r.fn("lstat", (context, a) -> io(() -> stat(file(context, a, 0), false)));
        r.fn("fstat", a -> {
            Object stat = stream(a, "fstat").stat();
            return stat == null ? Boolean.FALSE : stat;
        });
        r.fn("clearstatcache", a -> PhpNull.NULL);
        r.fn("disk_free_space", (context, a) -> io(() -> (double) file(context, a, 0).getFileStoreInfo()
                .getUsableSpace()));

        // ---- changes ----
        r.fn("touch", (context, a) -> io(() -> touch(file(context, a, 0), a)));
        r.fn("unlink", (context, a) -> io(() -> {
            TruffleFile file = file(context, a, 0);
            if (!file.exists(LinkOption.NOFOLLOW_LINKS) || file.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                return Boolean.FALSE;
            }
            file.delete();
            return Boolean.TRUE;
        }));
        r.fn("mkdir", (context, a) -> io(() -> mkdir(file(context, a, 0), Args.lng(a, 1, 0777),
                Args.bool(a, 2, false))));
        r.fn("rmdir", (context, a) -> io(() -> {
            TruffleFile file = file(context, a, 0);
            if (!file.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                return Boolean.FALSE;
            }
            file.delete();
            return Boolean.TRUE;
        }));
        r.fn("rename", (context, a) -> io(() -> {
            file(context, a, 0).move(file(context, a, 1), StandardCopyOption.REPLACE_EXISTING);
            return Boolean.TRUE;
        }));
        r.fn("copy", (context, a) -> io(() -> copy(file(context, a, 0), file(context, a, 1))));
        r.fn("chmod", (context, a) -> io(() -> {
            file(context, a, 0).setPosixPermissions(permissions(Args.lng(a, 1, 0)));
            return Boolean.TRUE;
        }));
        r.fn("umask", a -> 18L);
        r.fn("sys_get_temp_dir", a -> Str.of(System.getProperty("java.io.tmpdir").replaceAll("[/\\\\]$", "")));
        r.fn("tempnam", (context, a) -> io(() -> Str.of(context.getEnv().createTempFile(file(context, a, 0),
                Str.java(Args.str(a, 1, Str.EMPTY)), "").getPath())));
        r.fn("tmpfile", (context, a) -> io(() -> tmpfile(context)));

        // ---- directories ----
        r.fn("scandir", (context, a) -> io(() -> {
            long order = Args.lng(a, 1, 0);
            List<String> names = entries(file(context, a, 0), order != 2);
            if (order == 1) {
                Collections.reverse(names);
            }
            return list(names);
        }));
        r.fn("glob", (context, a) -> glob(context, Str.java(Args.str(a, 0)), Args.lng(a, 1, 0)));
        r.fn("opendir", (context, a) -> io(() -> new PhpResource(context.nextResourceId(), "stream",
                new Directory(entries(file(context, a, 0), false), Str.java(Args.str(a, 0))))));
        r.fn("readdir", a -> {
            Directory directory = directory(a, "readdir");
            return directory.next < directory.entries.size()
                    ? (Object) Str.of(directory.entries.get(directory.next++)) : Boolean.FALSE;
        });
        r.fn("rewinddir", a -> {
            directory(a, "rewinddir").seek(0, SEEK_SET);
            return PhpNull.NULL;
        });
        r.fn("closedir", a -> {
            directory(a, "closedir");
            resource(a, 0, "closedir", "dir_handle").close();
            return PhpNull.NULL;
        });
        r.fn("getcwd", (context, a) -> Str.of(context.getEnv().getCurrentWorkingDirectory().getPath()));
    }

    // ---- helpers ----

    interface IoBody {
        Object run() throws IOException;
    }

    interface FileTest {
        boolean test(TruffleFile file);
    }

    /**
     * Runs an I/O operation; a failure is PHP's false (the warning is not reported). SecurityException is the
     * embedder's IO policy refusing the access; IllegalArgumentException a path the file system can't represent.
     */
    @TruffleBoundary
    private static Object io(IoBody body) {
        try {
            return body.run();
        } catch (IOException | UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
            return Boolean.FALSE;
        }
    }

    @TruffleBoundary
    private static Object test(PhpContext context, Object[] a, FileTest test) {
        try {
            return test.test(file(context, a, 0));
        } catch (UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
            return Boolean.FALSE;
        }
    }

    @TruffleBoundary
    private static TruffleFile file(PhpContext context, Object[] a, int index) {
        return file(context, Str.java(Args.str(a, index)));
    }

    private static TruffleFile file(PhpContext context, String name) {
        return context.getEnv().getPublicTruffleFile(name);
    }

    private static long seconds(FileTime time) {
        return time.toMillis() / 1000;
    }

    private static Object key(String name) {
        return ArrayKey.of(Str.of(name));
    }

    /** The argument as an open resource, with php-src's TypeErrors. */
    private static PhpResource resource(Object[] a, int index, String function, String parameter) {
        return resource(a, index, function, parameter, true);
    }

    private static PhpResource resource(Object[] a, int index, String function, String parameter, boolean open) {
        Object value = Args.get(a, index, PhpNull.NULL);
        if (!(value instanceof PhpResource resource)) {
            throw new PhpError("TypeError: " + function + "(): Argument #" + (index + 1) + " ($" + parameter
                    + ") must be of type resource, " + typeName(value) + " given");
        }
        if (open && resource.isClosed()) {
            throw new PhpError("TypeError: " + function + "(): Argument #" + (index + 1) + " ($" + parameter
                    + ") must be an open stream resource");
        }
        return resource;
    }

    private static Stream stream(Object[] a, String function) {
        if (resource(a, 0, function, "stream").getHandle() instanceof Stream stream) {
            return stream;
        }
        throw new PhpError("TypeError: " + function + "(): supplied resource is not a valid stream resource");
    }

    private static Directory directory(Object[] a, String function) {
        if (resource(a, 0, function, "dir_handle").getHandle() instanceof Directory directory) {
            return directory;
        }
        throw new PhpError("TypeError: " + function + "(): Argument #1 ($dir_handle) must be a valid Directory "
                + "resource");
    }

    /** zend_zval_type_name, for TypeError messages. */
    private static String typeName(Object value) {
        if (value instanceof PhpObject object) {
            return object.getPhpClass().getName();
        }
        return switch (Convert.typeName(value)) {
            case "integer" -> "int";
            case "double" -> "float";
            case "boolean" -> "bool";
            case "NULL" -> "null";
            default -> Convert.typeName(value);
        };
    }

    /**
     * flock(stream, operation, &would_block): php_flock_common. LOCK_SH = 1, LOCK_EX = 2, LOCK_UN = 3, LOCK_NB = 4;
     * would_block becomes 1 when LOCK_NB found the lock taken. Streams without locking return false.
     */
    private static Object flock(Object[] a) {
        Stream stream = stream(a, "flock");
        long operation = Args.lng(a, 1, 0);
        int action = (int) (operation & 3);
        if (action == 0) {
            throw new PhpError("ValueError: flock(): Argument #2 ($operation) must be one of LOCK_SH, LOCK_EX, "
                    + "or LOCK_UN");
        }
        PhpRef wouldBlock = Args.has(a, 2) ? Args.ref(a, 2) : null;
        if (wouldBlock != null) {
            wouldBlock.set(0L);
        }
        if (!stream.lockable()) {
            return Boolean.FALSE;
        }
        if (!(stream instanceof FileStream file)) {
            return Boolean.TRUE; // a standard stream: flock(2) on a pipe or terminal succeeds
        }
        if (action == 3) {
            FileLocks.unlock(file);
            return Boolean.TRUE;
        }
        int result = FileLocks.lock(file, action == 2, (operation & 4) != 0);
        if (result == FileLocks.WOULD_BLOCK && wouldBlock != null) {
            wouldBlock.set(1L);
        }
        return result == FileLocks.LOCKED;
    }

    /** fwrite(stream, data, length = null). */
    private static Object write(Object[] a, String function) {
        Stream stream = stream(a, function);
        byte[] bytes = Str.bytes(Convert.toStr(Args.get(a, 1)));
        if (Args.has(a, 2) && Args.get(a, 2) != PhpNull.NULL) {
            bytes = Arrays.copyOf(bytes, (int) Math.min(bytes.length, Math.max(0, Args.lng(a, 2, 0))));
        }
        if (bytes.length == 0) {
            return 0L;
        }
        int written = stream.write(bytes);
        return written < 0 ? (Object) Boolean.FALSE : (Object) (long) written;
    }

    @TruffleBoundary
    static void closeStream(Stream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // php-src's fclose returns true whatever close(2) says
        }
    }

    private static PhpArray meta(Stream stream) {
        PhpArray meta = new PhpArray();
        if (!stream.streamType().equals("TEMP")) {
            meta.put(key("timed_out"), Boolean.FALSE);
            meta.put(key("blocked"), Boolean.TRUE);
            meta.put(key("eof"), stream.atEof());
        }
        meta.put(key("wrapper_type"), Str.of(stream.wrapperType()));
        meta.put(key("stream_type"), Str.of(stream.streamType()));
        meta.put(key("mode"), Str.of(stream.mode));
        meta.put(key("unread_bytes"), (long) stream.unreadBytes());
        meta.put(key("seekable"), stream.seekable());
        meta.put(key("uri"), Str.of(stream.uri));
        return meta;
    }

    /** fopen(): a php:// stream or a plain file; false if it can't be opened. */
    @TruffleBoundary
    private static Object open(PhpContext context, String name, String mode) {
        Stream stream = openStream(context, name, mode);
        return stream == null ? Boolean.FALSE : resource(context, stream);
    }

    /** A stream's resource; the context closes the stream at its end if the script doesn't. */
    private static PhpResource resource(PhpContext context, Stream stream) {
        if (stream instanceof FileStream) {
            context.builtinState().openStreams.add(stream);
        }
        return new PhpResource(context.nextResourceId(), "stream", stream);
    }

    private static Stream openStream(PhpContext context, String name, String mode) {
        if (name.regionMatches(true, 0, "php://", 0, 6)) {
            String target = name.substring(6).toLowerCase(Locale.ROOT);
            return switch (target) {
                case "memory" -> MemoryStream.open("MEMORY", mode, name);
                case "temp" -> MemoryStream.open("TEMP", mode, name);
                case "input" -> new MemoryStream("Input", "rb", name, true, false, context.requestBody());
                case "stdin" -> new StdStream(context, 0, mode, name);
                case "stdout" -> new StdStream(context, 1, mode, name);
                case "stderr" -> new StdStream(context, 2, mode, name);
                case "output" -> new OutputStreamWrapper(context, name);
                default -> target.startsWith("temp/") ? MemoryStream.open("TEMP", mode, name) : null;
            };
        }
        String path = name;
        if (name.regionMatches(true, 0, "file://", 0, 7)) {
            path = name.substring(7);
        } else if (name.contains("://")) {
            return null;
        }
        try {
            return openFile(file(context, path), mode, name);
        } catch (IOException | UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * php_stream_parse_fopen_modes: r, w (truncate), a (append), x (create, failing if the file exists: atomic, as
     * O_EXCL), c (create, no truncation); + adds the other direction. Null for an unknown mode.
     */
    private static Stream openFile(TruffleFile file, String mode, String uri) throws IOException {
        char kind = mode.isEmpty() ? 0 : mode.charAt(0);
        boolean plus = mode.indexOf('+') >= 0;
        Set<OpenOption> options = new HashSet<>();
        switch (kind) {
            case 'r' -> {
                if (!file.isRegularFile()) {
                    return null;
                }
            }
            case 'w' -> {
                options.add(StandardOpenOption.CREATE);
                options.add(StandardOpenOption.TRUNCATE_EXISTING);
            }
            case 'a' -> options.add(StandardOpenOption.CREATE);
            case 'x' -> options.add(StandardOpenOption.CREATE_NEW);
            case 'c' -> options.add(StandardOpenOption.CREATE);
            default -> {
                return null;
            }
        }
        boolean appendOnWrite = false;
        if (plus) {
            options.add(StandardOpenOption.READ);
            options.add(StandardOpenOption.WRITE);
            appendOnWrite = kind == 'a';
        } else if (kind == 'r') {
            options.add(StandardOpenOption.READ);
        } else {
            options.add(StandardOpenOption.WRITE);
            if (kind == 'a') {
                options.add(StandardOpenOption.APPEND);
            }
        }
        return new FileStream(file, file.newByteChannel(options), appendOnWrite, mode, uri);
    }

    /** tmpfile(): a new file in the temporary directory, "r+b", deleted when closed. */
    private static Object tmpfile(PhpContext context) throws IOException {
        TruffleFile file = context.getEnv().createTempFile(null, "php", null);
        Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.DELETE_ON_CLOSE);
        return resource(context, new FileStream(file, file.newByteChannel(options), false, "r+b", file.getPath()));
    }

    @TruffleBoundary
    private static byte[] readAll(PhpContext context, String name) {
        try {
            return file(context, name).readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * file_put_contents(): opens with "wb" ("ab" with FILE_APPEND = 8) through the stream layer, so php:// targets
     * work too; data may be a string, an array of strings or a stream. LOCK_EX (2) waits for an exclusive lock
     * before writing, so it opens with "cb" and truncates once locked, and only plain files can be locked. False if
     * the file can't be opened, locked or written.
     */
    @TruffleBoundary
    private static Object putContents(PhpContext context, String name, Object data, long flags) {
        boolean append = (flags & 8) != 0;
        boolean lock = (flags & 2) != 0;
        if (lock && !append && name.contains("://") && !name.regionMatches(true, 0, "file://", 0, 7)) {
            return Boolean.FALSE; // "Exclusive locks may only be set for regular files"
        }
        Stream stream = openStream(context, name, append ? "ab" : lock ? "cb" : "wb");
        if (stream == null) {
            return Boolean.FALSE;
        }
        try {
            if (lock) {
                if (!(stream instanceof FileStream file) || FileLocks.lock(file, true, false) != FileLocks.LOCKED) {
                    return Boolean.FALSE; // "Exclusive locks are not supported for this stream"
                }
                if (!append && !Boolean.TRUE.equals(file.truncate(0))) {
                    return Boolean.FALSE;
                }
            }
            long written = 0;
            if (data instanceof PhpResource resource && resource.getHandle() instanceof Stream source) {
                byte[] bytes = source.readAll(-1);
                if (bytes.length > 0 && stream.write(bytes) != bytes.length) {
                    return Boolean.FALSE;
                }
                written = bytes.length;
            } else if (data instanceof PhpArray array) {
                for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                    byte[] bytes = Str.bytes(Convert.toStr(array.valueAt(p)));
                    if (bytes.length > 0 && stream.write(bytes) != bytes.length) {
                        return Boolean.FALSE;
                    }
                    written += bytes.length;
                }
            } else {
                byte[] bytes = Str.bytes(Convert.toStr(data));
                if (bytes.length > 0 && stream.write(bytes) != bytes.length) {
                    return Boolean.FALSE;
                }
                written = bytes.length;
            }
            return written;
        } finally {
            closeStream(stream);
        }
    }

    /**
     * file(): the lines with their \n. FILE_IGNORE_NEW_LINES (2) drops the \n and a \r before it (not on a last
     * line without \n); with it, FILE_SKIP_EMPTY_LINES (4) leaves out the empty lines. As php-src's file().
     */
    @TruffleBoundary
    private static Object lines(PhpContext context, String name, long flags) {
        Stream stream = openStream(context, name, "rb");
        if (stream == null) {
            return Boolean.FALSE;
        }
        byte[] bytes;
        try {
            bytes = stream.readAll(-1);
        } finally {
            closeStream(stream);
        }
        boolean ignoreNewLines = (flags & 2) != 0;
        boolean skipEmpty = (flags & 4) != 0;
        PhpArray lines = new PhpArray();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != '\n') {
                continue;
            }
            if (!ignoreNewLines) {
                lines.append(Str.of(Arrays.copyOfRange(bytes, start, i + 1)));
            } else {
                int end = i > 0 && bytes[i - 1] == '\r' ? i - 1 : i;
                if (!skipEmpty || end > start) {
                    lines.append(Str.of(Arrays.copyOfRange(bytes, start, Math.max(start, end))));
                }
            }
            start = i + 1;
        }
        if (start < bytes.length) {
            lines.append(Str.of(Arrays.copyOfRange(bytes, start, bytes.length)));
        }
        return lines;
    }

    @TruffleBoundary
    private static Object digestFile(PhpContext context, String algorithm, Object[] a) {
        byte[] bytes = readAll(context, Str.java(Args.str(a, 0)));
        if (bytes == null) {
            return Boolean.FALSE;
        }
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(bytes);
            return Args.bool(a, 1, false) ? Str.of(digest) : Str.of(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** touch(filename, mtime = now, atime = mtime): creates the file if it doesn't exist. */
    private static Object touch(TruffleFile file, Object[] a) throws IOException {
        if (!file.exists()) {
            try {
                file.createFile();
            } catch (FileAlreadyExistsException e) {
                // created meanwhile: touch it
            }
        }
        long mtime = Args.has(a, 1) && Args.get(a, 1) != PhpNull.NULL ? Args.lng(a, 1, 0) * 1000
                : System.currentTimeMillis();
        long atime = Args.has(a, 2) && Args.get(a, 2) != PhpNull.NULL ? Args.lng(a, 2, 0) * 1000 : mtime;
        file.setLastModifiedTime(FileTime.fromMillis(mtime));
        file.setLastAccessTime(FileTime.fromMillis(atime));
        return Boolean.TRUE;
    }

    /** mkdir(directory, permissions = 0777, recursive = false): false if it exists; permissions pass the umask. */
    private static Object mkdir(TruffleFile file, long permissions, boolean recursive) throws IOException {
        if (file.exists()) {
            return Boolean.FALSE;
        }
        FileAttribute<?>[] attributes = {PosixFilePermissions.asFileAttribute(permissions(permissions))};
        try {
            create(file, recursive, attributes);
        } catch (UnsupportedOperationException e) {
            create(file, recursive);
        }
        return Boolean.TRUE;
    }

    private static void create(TruffleFile file, boolean recursive, FileAttribute<?>... attributes)
            throws IOException {
        if (recursive) {
            file.createDirectories(attributes);
        } else {
            file.createDirectory(attributes);
        }
    }

    /** copy(): the source's bytes into the target, created or truncated; neither may be a directory. */
    private static Object copy(TruffleFile from, TruffleFile to) throws IOException {
        if (from.isDirectory() || to.isDirectory()) {
            return Boolean.FALSE;
        }
        try (InputStream in = from.newInputStream();
             OutputStream out = to.newOutputStream()) {
            in.transferTo(out);
        }
        return Boolean.TRUE;
    }

    private static long mode(TruffleFile file, boolean follow) throws IOException {
        LinkOption[] options = follow ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
        long type = file.isDirectory(options) ? 0040000 : file.isSymbolicLink() && !follow ? 0120000 : 0100000;
        try {
            long bits = 0;
            for (PosixFilePermission permission : file.getPosixPermissions(options)) {
                bits |= 1L << (8 - permission.ordinal());
            }
            return type | bits;
        } catch (UnsupportedOperationException e) {
            return type | 0644;
        }
    }

    private static Object stat(TruffleFile file, boolean follow) throws IOException {
        LinkOption[] options = follow ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
        if (!file.exists(options)) {
            return Boolean.FALSE;
        }
        long size = file.isDirectory(options) ? 4096 : file.size(options);
        long mtime = seconds(file.getLastModifiedTime(options));
        long[] values = {0, 0, mode(file, follow), 1, 0, 0, 0, size, mtime, mtime, mtime, 4096, (size + 511) / 512};
        return statArray(values);
    }

    private static final String[] STAT_KEYS = {"dev", "ino", "mode", "nlink", "uid", "gid", "rdev", "size", "atime",
            "mtime", "ctime", "blksize", "blocks"};

    /** stat()'s array: the 13 values by index, then by name. */
    private static PhpArray statArray(long[] values) {
        PhpArray stat = new PhpArray();
        for (int i = 0; i < values.length; i++) {
            stat.put((long) i, values[i]);
        }
        for (int i = 0; i < values.length; i++) {
            stat.put(key(STAT_KEYS[i]), values[i]);
        }
        return stat;
    }

    private static Set<PosixFilePermission> permissions(long mode) {
        StringBuilder text = new StringBuilder();
        String letters = "rwxrwxrwx";
        for (int i = 0; i < 9; i++) {
            text.append((mode & (1L << (8 - i))) != 0 ? letters.charAt(i) : '-');
        }
        return PosixFilePermissions.fromString(text.toString());
    }

    /** A directory's entries with . and ..: sorted (scandir), or in the file system's order (readdir). */
    private static List<String> entries(TruffleFile directory, boolean sorted) throws IOException {
        List<String> names = new ArrayList<>();
        names.add(".");
        names.add("..");
        try (DirectoryStream<TruffleFile> stream = directory.newDirectoryStream()) {
            for (TruffleFile entry : stream) {
                names.add(entry.getName());
            }
        }
        if (sorted) {
            Collections.sort(names);
        }
        return names;
    }

    /** glob(): GLOB_MARK = 2, GLOB_NOSORT = 4, GLOB_ONLYDIR = 0x2000 (Linux), GLOB_BRACE = 0x400. */
    @TruffleBoundary
    private static Object glob(PhpContext context, String pattern, long flags) {
        int slash = pattern.lastIndexOf('/');
        String directory = slash < 0 ? "." : slash == 0 ? "/" : pattern.substring(0, slash);
        String filePattern = pattern.substring(slash + 1);
        List<String> found = new ArrayList<>();
        if (directory.matches(".*[*?\\[].*")) {
            Object parents = glob(context, directory, 0x2000);
            if (parents instanceof PhpArray array) {
                for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                    Object sub = glob(context, Str.java((TruffleString) array.valueAt(p)) + "/" + filePattern, flags);
                    if (sub instanceof PhpArray subArray) {
                        for (int q = subArray.firstPosition(); q >= 0; q = subArray.nextPosition(q)) {
                            found.add(Str.java((TruffleString) subArray.valueAt(q)));
                        }
                    }
                }
            }
            return list(found);
        }
        String syntax = (flags & 0x400) != 0 ? filePattern : filePattern.replace("{", "\\{").replace("}", "\\}");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + syntax);
        try (DirectoryStream<TruffleFile> stream = file(context, directory).newDirectoryStream()) {
            for (TruffleFile entry : stream) {
                String name = entry.getName();
                if (name.startsWith(".") && !filePattern.startsWith(".")) {
                    continue;
                }
                if (!matcher.matches(Path.of(name))) {
                    continue;
                }
                boolean isDirectory = entry.isDirectory();
                if ((flags & 0x2000) != 0 && !isDirectory) {
                    continue;
                }
                String prefix = slash < 0 ? "" : pattern.substring(0, slash + 1);
                found.add(prefix + name + ((flags & 2) != 0 && isDirectory ? "/" : ""));
            }
        } catch (IOException | RuntimeException e) {
            return new PhpArray();
        }
        if ((flags & 4) == 0) {
            Collections.sort(found);
        }
        return list(found);
    }

    private static PhpArray list(List<String> items) {
        PhpArray array = new PhpArray();
        for (String item : items) {
            array.append(Str.of(item));
        }
        return array;
    }
}
