package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.Str;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The filesystem and stream functions: plain files and php://memory, php://temp, php://stdout, php://output and
 * php://stderr. A stream or directory handle is a {@link PhpResource}.
 */
final class FileFunctions {
    private FileFunctions() {
    }

    /** An open stream: a file, an in-memory buffer or one of the process's standard streams. */
    abstract static class Stream {
        boolean eof;

        abstract byte[] read(int length) throws IOException;

        abstract int write(byte[] bytes) throws IOException;

        abstract boolean seek(long offset, int whence) throws IOException;

        abstract long tell() throws IOException;

        void close() throws IOException {
        }

        /** fgets: up to and including the next \n, or length - 1 bytes. */
        byte[] readLine(int max) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (line.size() < max) {
                byte[] next = read(1);
                if (next.length == 0) {
                    break;
                }
                line.write(next[0]);
                if (next[0] == '\n') {
                    break;
                }
            }
            return line.toByteArray();
        }
    }

    static final class FileStream extends Stream {
        final RandomAccessFile file;
        final boolean append;

        FileStream(RandomAccessFile file, boolean append) {
            this.file = file;
            this.append = append;
        }

        @Override
        byte[] read(int length) throws IOException {
            byte[] buffer = new byte[(int) Math.min(length, Math.max(0, file.length() - file.getFilePointer()))];
            int n = buffer.length == 0 ? -1 : file.read(buffer);
            if (n < length) {
                eof = true;
            }
            return n <= 0 ? new byte[0] : n == buffer.length ? buffer : Arrays.copyOf(buffer, n);
        }

        @Override
        int write(byte[] bytes) throws IOException {
            if (append) {
                file.seek(file.length());
            }
            file.write(bytes);
            return bytes.length;
        }

        @Override
        boolean seek(long offset, int whence) throws IOException {
            long base = whence == 1 ? file.getFilePointer() : whence == 2 ? file.length() : 0;
            file.seek(base + offset);
            eof = false;
            return true;
        }

        @Override
        long tell() throws IOException {
            return file.getFilePointer();
        }

        @Override
        void close() throws IOException {
            file.close();
        }
    }

    static final class MemoryStream extends Stream {
        byte[] data = new byte[0];
        int size;
        int position;

        @Override
        byte[] read(int length) {
            int n = Math.min(length, size - position);
            if (n < length) {
                eof = true;
            }
            byte[] out = Arrays.copyOfRange(data, position, position + Math.max(n, 0));
            position += Math.max(n, 0);
            return out;
        }

        @Override
        int write(byte[] bytes) {
            if (position + bytes.length > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, position + bytes.length));
            }
            System.arraycopy(bytes, 0, data, position, bytes.length);
            position += bytes.length;
            size = Math.max(size, position);
            return bytes.length;
        }

        @Override
        boolean seek(long offset, int whence) {
            long target = (whence == 1 ? position : whence == 2 ? size : 0) + offset;
            if (target < 0) {
                return false;
            }
            position = (int) target;
            eof = false;
            return true;
        }

        @Override
        long tell() {
            return position;
        }
    }

    /** php://stdout and php://output write to the script's output; php://stderr and php://stdin are the process's. */
    static final class StdStream extends Stream {
        final PhpContext context;
        final int fd;

        StdStream(PhpContext context, int fd) {
            this.context = context;
            this.fd = fd;
        }

        @Override
        byte[] read(int length) throws IOException {
            if (fd != 0) {
                return new byte[0];
            }
            byte[] bytes = System.in.readNBytes(length);
            if (bytes.length < length) {
                eof = true;
            }
            return bytes;
        }

        @Override
        int write(byte[] bytes) {
            if (fd == 2) {
                System.err.write(bytes, 0, bytes.length);
                System.err.flush();
            } else if (fd == 1) {
                context.write(Str.of(bytes));
            }
            return bytes.length;
        }

        @Override
        boolean seek(long offset, int whence) {
            return false;
        }

        @Override
        long tell() {
            return 0;
        }
    }

    /** opendir's handle: the entries, read in order. */
    static final class Directory {
        final List<String> entries;
        int position;

        Directory(List<String> entries) {
            this.entries = entries;
        }
    }

    static void register(Registry r) {
        // ---- streams ----
        r.fn("fopen", (context, a) -> open(context, Str.java(Args.str(a, 0)), Str.java(Args.str(a, 1))));
        r.fn("fclose", a -> {
            PhpResource resource = resource(a, 0);
            try {
                ((Stream) resource.getHandle()).close();
            } catch (IOException e) {
                return Boolean.FALSE;
            }
            resource.close();
            return Boolean.TRUE;
        });
        r.fn("fread", a -> io(() -> Str.of(stream(a, 0).read((int) Args.lng(a, 1, 8192)))));
        r.fn("fwrite", a -> io(() -> (long) stream(a, 0).write(writeBytes(a))));
        r.fn("fputs", a -> io(() -> (long) stream(a, 0).write(writeBytes(a))));
        r.fn("fgets", a -> io(() -> {
            byte[] line = stream(a, 0).readLine(Args.has(a, 1) ? (int) Args.lng(a, 1, 0) - 1 : Integer.MAX_VALUE);
            return line.length == 0 ? (Object) Boolean.FALSE : Str.of(line);
        }));
        r.fn("fgetc", a -> io(() -> {
            byte[] c = stream(a, 0).read(1);
            return c.length == 0 ? (Object) Boolean.FALSE : Str.of(c);
        }));
        r.fn("feof", a -> stream(a, 0).eof);
        r.fn("fseek", a -> io(() -> stream(a, 0).seek(Args.lng(a, 1, 0), (int) Args.lng(a, 2, 0)) ? 0L : -1L));
        r.fn("ftell", a -> io(() -> stream(a, 0).tell()));
        r.fn("rewind", a -> io(() -> stream(a, 0).seek(0, 0)));
        r.fn("fflush", a -> Boolean.TRUE);
        r.fn("flock", a -> Boolean.TRUE);
        r.fn("ftruncate", a -> io(() -> {
            if (stream(a, 0) instanceof FileStream file) {
                file.file.setLength(Args.lng(a, 1, 0));
                return Boolean.TRUE;
            }
            if (stream(a, 0) instanceof MemoryStream memory) {
                memory.size = (int) Math.min(memory.size, Args.lng(a, 1, 0));
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }));
        r.fn("stream_get_contents", a -> io(() -> {
            Stream stream = stream(a, 0);
            long length = Args.has(a, 1) && Args.get(a, 1) != PhpNull.NULL ? Args.lng(a, 1, -1) : -1;
            if (Args.has(a, 2) && Args.lng(a, 2, -1) >= 0) {
                stream.seek(Args.lng(a, 2, 0), 0);
            }
            return Str.of(stream.read(length < 0 ? Integer.MAX_VALUE - 8 : (int) length));
        }));
        r.fn("stream_get_meta_data", a -> {
            Stream stream = stream(a, 0);
            PhpArray meta = new PhpArray();
            meta.put(key("timed_out"), Boolean.FALSE);
            meta.put(key("blocked"), Boolean.TRUE);
            meta.put(key("eof"), stream.eof);
            meta.put(key("wrapper_type"), Str.of(stream instanceof FileStream ? "plainfile" : "PHP"));
            meta.put(key("stream_type"), Str.of(stream instanceof MemoryStream ? "MEMORY" : "STDIO"));
            meta.put(key("mode"), Str.of("r+"));
            meta.put(key("unread_bytes"), 0L);
            meta.put(key("seekable"), !(stream instanceof StdStream));
            return meta;
        });
        r.fn("stream_set_timeout", a -> Boolean.TRUE);
        r.fn("stream_set_blocking", a -> Boolean.TRUE);
        r.fn("stream_context_create", (context, a) -> new PhpResource(context.nextResourceId(), "stream-context",
                Args.get(a, 0, new PhpArray())));
        r.fn("stream_context_get_options", a -> {
            Object options = resource(a, 0).getHandle();
            return options instanceof PhpArray array ? array : new PhpArray();
        });
        r.fn("stream_get_wrappers", a -> list(List.of("php", "file")));
        r.fn("stream_is_local", a -> Boolean.TRUE);
        r.fn("is_resource", a -> Args.get(a, 0) instanceof PhpResource resource && !resource.isClosed());
        r.fn("get_resource_type", a -> Str.of(resource(a, 0).getType()));
        r.fn("get_resource_id", a -> (long) resource(a, 0).getId());

        // ---- whole files ----
        r.fn("file_put_contents", (context, a) -> {
            String name = Str.java(Args.str(a, 0));
            if (name.equals("php://stdout") || name.equals("php://output") || name.equals("php://stderr")) {
                PhpResource stream = (PhpResource) open(context, name, "w");
                return io(() -> (long) ((Stream) stream.getHandle()).write(
                        Str.bytes(Convert.toStr(Args.get(a, 1)))));
            }
            return putContents(name, Args.get(a, 1), Args.lng(a, 2, 0));
        });
        r.fn("file", a -> lines(Str.java(Args.str(a, 0)), Args.lng(a, 1, 0)));
        r.fn("readfile", (context, a) -> {
            byte[] bytes = readAll(Str.java(Args.str(a, 0)));
            if (bytes == null) {
                return Boolean.FALSE;
            }
            context.write(Str.of(bytes));
            return (long) bytes.length;
        });
        r.fn("md5_file", a -> digestFile("MD5", a));
        r.fn("sha1_file", a -> digestFile("SHA-1", a));

        // ---- metadata ----
        r.fn("is_readable", a -> Files.isReadable(path(a, 0)));
        r.fn("is_writable", a -> Files.isWritable(path(a, 0)));
        r.fn("is_writeable", a -> Files.isWritable(path(a, 0)));
        r.fn("is_executable", a -> Files.isRegularFile(path(a, 0)) && Files.isExecutable(path(a, 0)));
        r.fn("is_link", a -> Files.isSymbolicLink(path(a, 0)));
        r.fn("filesize", a -> io(() -> Files.size(path(a, 0))));
        r.fn("filemtime", a -> io(() -> Files.getLastModifiedTime(path(a, 0)).toMillis() / 1000));
        r.fn("fileatime", a -> io(() -> Files.getLastModifiedTime(path(a, 0)).toMillis() / 1000));
        r.fn("filectime", a -> io(() -> Files.getLastModifiedTime(path(a, 0)).toMillis() / 1000));
        r.fn("fileperms", a -> io(() -> mode(path(a, 0), true)));
        r.fn("stat", a -> io(() -> stat(path(a, 0), true)));
        r.fn("lstat", a -> io(() -> stat(path(a, 0), false)));
        r.fn("fstat", a -> {
            if (stream(a, 0) instanceof MemoryStream memory) {
                PhpArray stat = new PhpArray();
                stat.put(key("size"), (long) memory.size);
                return stat;
            }
            return Boolean.FALSE;
        });
        r.fn("clearstatcache", a -> PhpNull.NULL);
        r.fn("disk_free_space", a -> io(() -> (double) Files.getFileStore(path(a, 0)).getUsableSpace()));

        // ---- changes ----
        r.fn("touch", a -> io(() -> {
            Path path = path(a, 0);
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            long time = Args.has(a, 1) && Args.get(a, 1) != PhpNull.NULL ? Args.lng(a, 1, 0) * 1000
                    : System.currentTimeMillis();
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(time));
            return Boolean.TRUE;
        }));
        r.fn("unlink", a -> io(() -> Files.deleteIfExists(path(a, 0))));
        r.fn("mkdir", a -> io(() -> {
            Path path = path(a, 0);
            if (Files.exists(path)) {
                return Boolean.FALSE;
            }
            if (Args.bool(a, 2, false)) {
                Files.createDirectories(path);
            } else {
                Files.createDirectory(path);
            }
            return Boolean.TRUE;
        }));
        r.fn("rmdir", a -> io(() -> Files.deleteIfExists(path(a, 0))));
        r.fn("rename", a -> io(() -> {
            Files.move(path(a, 0), path(a, 1), StandardCopyOption.REPLACE_EXISTING);
            return Boolean.TRUE;
        }));
        r.fn("copy", a -> io(() -> {
            Files.copy(path(a, 0), path(a, 1), StandardCopyOption.REPLACE_EXISTING);
            return Boolean.TRUE;
        }));
        r.fn("chmod", a -> io(() -> {
            Files.setPosixFilePermissions(path(a, 0), permissions(Args.lng(a, 1, 0)));
            return Boolean.TRUE;
        }));
        r.fn("umask", a -> 18L);
        r.fn("sys_get_temp_dir", a -> Str.of(System.getProperty("java.io.tmpdir").replaceAll("[/\\\\]$", "")));
        r.fn("tempnam", a -> io(() -> Str.of(Files.createTempFile(path(a, 0), Str.java(Args.str(a, 1, Str.EMPTY)),
                "").toString())));
        r.fn("tmpfile", (context, a) -> new PhpResource(context.nextResourceId(), "stream", new MemoryStream()));

        // ---- directories ----
        r.fn("scandir", a -> io(() -> {
            List<String> names = entries(path(a, 0), true);
            if (Args.lng(a, 1, 0) == 1) {
                Collections.reverse(names);
            }
            return list(names);
        }));
        r.fn("glob", a -> glob(Str.java(Args.str(a, 0)), Args.lng(a, 1, 0)));
        r.fn("opendir", (context, a) -> io(() -> new PhpResource(context.nextResourceId(), "stream",
                new Directory(entries(path(a, 0), false)))));
        r.fn("readdir", a -> {
            Directory directory = (Directory) resource(a, 0).getHandle();
            return directory.position < directory.entries.size()
                    ? (Object) Str.of(directory.entries.get(directory.position++)) : Boolean.FALSE;
        });
        r.fn("rewinddir", a -> {
            ((Directory) resource(a, 0).getHandle()).position = 0;
            return PhpNull.NULL;
        });
        r.fn("closedir", a -> {
            resource(a, 0).close();
            return PhpNull.NULL;
        });
        r.fn("getcwd", a -> Str.of(Path.of("").toAbsolutePath().toString()));
    }

    // ---- helpers ----

    interface IoBody {
        Object run() throws IOException;
    }

    /** Runs an I/O operation; a failure is PHP's false (the warning is not reported). */
    @TruffleBoundary
    private static Object io(IoBody body) {
        try {
            return body.run();
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return Boolean.FALSE;
        }
    }

    @TruffleBoundary
    private static Path path(Object[] a, int index) {
        return Path.of(Str.java(Args.str(a, index)));
    }

    private static Object key(String name) {
        return ArrayKey.of(Str.of(name));
    }

    private static PhpResource resource(Object[] a, int index) {
        if (Args.get(a, index) instanceof PhpResource resource && !resource.isClosed()) {
            return resource;
        }
        throw new PhpError("TypeError: Argument #" + (index + 1) + " ($stream) must be an open resource, "
                + Convert.typeName(Args.get(a, index, PhpNull.NULL)) + " given");
    }

    private static Stream stream(Object[] a, int index) {
        return (Stream) resource(a, index).getHandle();
    }

    private static byte[] writeBytes(Object[] a) {
        byte[] bytes = Str.bytes(Convert.toStr(Args.get(a, 1)));
        if (Args.has(a, 2) && Args.get(a, 2) != PhpNull.NULL) {
            return Arrays.copyOf(bytes, (int) Math.min(bytes.length, Math.max(0, Args.lng(a, 2, 0))));
        }
        return bytes;
    }

    @TruffleBoundary
    private static Object open(PhpContext context, String name, String mode) {
        Stream stream;
        switch (name) {
            case "php://memory", "php://temp" -> stream = new MemoryStream();
            case "php://input" -> {
                MemoryStream input = new MemoryStream();
                input.write(context.requestBody());
                input.position = 0;
                stream = input;
            }
            case "php://stdin" -> stream = new StdStream(context, 0);
            case "php://stdout", "php://output" -> stream = new StdStream(context, 1);
            case "php://stderr" -> stream = new StdStream(context, 2);
            default -> {
                if (name.startsWith("php://temp/")) {
                    stream = new MemoryStream();
                    break;
                }
                if (name.contains("://") && !name.startsWith("file://")) {
                    return Boolean.FALSE;
                }
                stream = openFile(Path.of(name.startsWith("file://") ? name.substring(7) : name), mode);
                if (stream == null) {
                    return Boolean.FALSE;
                }
            }
        }
        return new PhpResource(context.nextResourceId(), "stream", stream);
    }

    private static Stream openFile(Path path, String mode) {
        char kind = mode.isEmpty() ? 'r' : mode.charAt(0);
        boolean plus = mode.indexOf('+') >= 0;
        try {
            switch (kind) {
                case 'r' -> {
                    if (!Files.isRegularFile(path)) {
                        return null;
                    }
                    return new FileStream(new RandomAccessFile(path.toFile(), plus ? "rw" : "r"), false);
                }
                case 'w' -> {
                    RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
                    file.setLength(0);
                    return new FileStream(file, false);
                }
                case 'a' -> {
                    RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
                    file.seek(file.length());
                    return new FileStream(file, true);
                }
                case 'x' -> {
                    if (Files.exists(path)) {
                        return null;
                    }
                    return new FileStream(new RandomAccessFile(path.toFile(), "rw"), false);
                }
                case 'c' -> {
                    return new FileStream(new RandomAccessFile(path.toFile(), "rw"), false);
                }
                default -> {
                    return null;
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    @TruffleBoundary
    private static byte[] readAll(String name) {
        try {
            return Files.readAllBytes(Path.of(name));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** FILE_APPEND = 8; data may be a string, an array of strings or a stream. */
    @TruffleBoundary
    private static Object putContents(String name, Object data, long flags) {
        byte[] bytes;
        if (data instanceof PhpArray array) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                out.writeBytes(Str.bytes(Convert.toStr(array.valueAt(p))));
            }
            bytes = out.toByteArray();
        } else if (data instanceof PhpResource resource && resource.getHandle() instanceof Stream stream) {
            try {
                bytes = stream.read(Integer.MAX_VALUE - 8);
            } catch (IOException e) {
                return Boolean.FALSE;
            }
        } else {
            bytes = Str.bytes(Convert.toStr(data));
        }
        try {
            if ((flags & 8) != 0) {
                Files.write(Path.of(name), bytes, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.write(Path.of(name), bytes);
            }
            return (long) bytes.length;
        } catch (IOException | RuntimeException e) {
            return Boolean.FALSE;
        }
    }

    /** file(): FILE_IGNORE_NEW_LINES = 2, FILE_SKIP_EMPTY_LINES = 4. */
    @TruffleBoundary
    private static Object lines(String name, long flags) {
        byte[] bytes = readAll(name);
        if (bytes == null) {
            return Boolean.FALSE;
        }
        PhpArray lines = new PhpArray();
        int start = 0;
        for (int i = 0; i <= bytes.length; i++) {
            if (i == bytes.length || bytes[i] == '\n') {
                if (i == bytes.length && start == i) {
                    break;
                }
                int end = i < bytes.length && (flags & 2) == 0 ? i + 1 : i;
                byte[] line = Arrays.copyOfRange(bytes, start, end);
                if ((flags & 4) == 0 || line.length > 0) {
                    lines.append(Str.of(line));
                }
                start = i + 1;
            }
        }
        return lines;
    }

    @TruffleBoundary
    private static Object digestFile(String algorithm, Object[] a) {
        byte[] bytes = readAll(Str.java(Args.str(a, 0)));
        if (bytes == null) {
            return Boolean.FALSE;
        }
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(bytes);
            if (Args.bool(a, 1, false)) {
                return Str.of(digest);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return Str.of(hex.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long mode(Path path, boolean follow) throws IOException {
        LinkOption[] options = follow ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
        long type = Files.isDirectory(path, options) ? 0040000 : Files.isSymbolicLink(path) && !follow ? 0120000
                : 0100000;
        try {
            long bits = 0;
            for (PosixFilePermission permission : Files.getPosixFilePermissions(path, options)) {
                bits |= 1L << (8 - permission.ordinal());
            }
            return type | bits;
        } catch (UnsupportedOperationException e) {
            return type | 0644;
        }
    }

    private static Object stat(Path path, boolean follow) throws IOException {
        LinkOption[] options = follow ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
        if (!Files.exists(path, options)) {
            return Boolean.FALSE;
        }
        long size = Files.isDirectory(path, options) ? 4096 : Files.size(path);
        long mtime = Files.getLastModifiedTime(path, options).toMillis() / 1000;
        Object[][] fields = {{"dev", 0L}, {"ino", 0L}, {"mode", mode(path, follow)}, {"nlink", 1L}, {"uid", 0L},
                {"gid", 0L}, {"rdev", 0L}, {"size", size}, {"atime", mtime}, {"mtime", mtime}, {"ctime", mtime},
                {"blksize", 4096L}, {"blocks", (size + 511) / 512}};
        PhpArray stat = new PhpArray();
        for (int i = 0; i < fields.length; i++) {
            stat.put((long) i, fields[i][1]);
        }
        for (Object[] field : fields) {
            stat.put(key((String) field[0]), field[1]);
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
    private static List<String> entries(Path directory, boolean sorted) throws IOException {
        List<String> names = new ArrayList<>();
        names.add(".");
        names.add("..");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                names.add(entry.getFileName().toString());
            }
        }
        if (sorted) {
            Collections.sort(names);
        }
        return names;
    }

    /** glob(): GLOB_MARK = 2, GLOB_NOSORT = 4, GLOB_ONLYDIR = 0x2000 (Linux), GLOB_BRACE = 0x400. */
    @TruffleBoundary
    private static Object glob(String pattern, long flags) {
        int slash = pattern.lastIndexOf('/');
        String directory = slash < 0 ? "." : slash == 0 ? "/" : pattern.substring(0, slash);
        String filePattern = pattern.substring(slash + 1);
        List<String> found = new ArrayList<>();
        if (directory.matches(".*[*?\\[].*")) {
            Object parents = glob(directory, 0x2000);
            if (parents instanceof PhpArray array) {
                for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                    Object sub = glob(Str.java((TruffleString) array.valueAt(p)) + "/" + filePattern, flags);
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
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(directory))) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") && !filePattern.startsWith(".")) {
                    continue;
                }
                if (!matcher.matches(entry.getFileName())) {
                    continue;
                }
                boolean isDirectory = Files.isDirectory(entry);
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
