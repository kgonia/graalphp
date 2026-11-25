package pl.codeloom.php.launcher;

import pl.codeloom.php.runtime.BootstrapEpoch;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Function;
import static java.nio.file.StandardWatchEventKinds.*;

/** Publish both site variants together. Readers retain a strong reference to the
 * generation they acquired. Only selection and rebuilding share this monitor. */
final class SnapshotCache<T> implements AutoCloseable {
    private final Path maintenance;
    private final WatchService watcher;
    private final Function<BootstrapEpoch.Capture,T> capture;
    private long generation = -1;
    private long fileGeneration;
    private T active;

    SnapshotCache(Path root, Function<BootstrapEpoch.Capture,T> capture) throws IOException {
        this.capture = capture;
        maintenance = root.resolve(".maintenance");
        watcher = root.getFileSystem().newWatchService();
        root.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    }

    private boolean maintenanceActive() throws IOException {
        WatchKey key;
        while ((key = watcher.poll()) != null) {
            for (WatchEvent<?> e : key.pollEvents()) {
                if (e.kind() == OVERFLOW || maintenance.getFileName().equals(e.context())) {
                    active = null;
                    fileGeneration++;
                }
            }
            if (!key.reset()) throw new IOException("Lost maintenance-file watch");
        }
        try {
            Files.readAttributes(maintenance, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            active = null;
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    synchronized T get() throws IOException {
        // Allow one retry when the first bootstrap initializes a site option itself.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (maintenanceActive()) return null;
            if (active != null && generation == BootstrapEpoch.current()) return active;
            active = null;
            long fileStart = fileGeneration;
            BootstrapEpoch.Capture reads = BootstrapEpoch.beginCapture();
            T candidate;
            try {
                candidate = capture.apply(reads);
            } catch (RuntimeException failed) {
                if (maintenanceActive()) return null;
                if (fileGeneration != fileStart || BootstrapEpoch.current() != reads.generation) continue;
                throw failed;
            }
            if (maintenanceActive()) return null;
            if (fileGeneration == fileStart && reads.publish()) {
                generation = reads.generation;
                active = candidate;
                return active;
            }
        }
        return null;
    }

    public void close() throws IOException { watcher.close(); }
}
