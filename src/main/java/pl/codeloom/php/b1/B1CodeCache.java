package pl.codeloom.php.b1;

import pl.codeloom.php.ir.IrUnit;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Hidden classes linked once and installed into every request context that shares this cache.
 * A linked unit carries no request state (see {@link B1Compiler.CompiledFile}); a key covers everything its
 * classfiles depend on: the exact source, the bytecode budget, the language level and which of its classes the
 * installing request already declares (those bind at their statement, like PHP's delayed early binding). */
public final class B1CodeCache {
    record Key(String file, String source, int budget, boolean eval, boolean extended, Set<Integer> statementBound) {}

    private final ConcurrentHashMap<String, IrUnit> units = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, B1Compiler.CompiledFile> linked = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong();

    IrUnit unit(String file, String source, Supplier<IrUnit> lower) {
        return units.computeIfAbsent(file + '\0' + source, ignored -> lower.get());
    }

    B1Compiler.CompiledFile linked(Key key, Supplier<B1Compiler.CompiledFile> link) {
        B1Compiler.CompiledFile existing = linked.get(key);
        if (existing != null) { hits.incrementAndGet(); return existing; }
        misses.incrementAndGet();
        return linked.computeIfAbsent(key, ignored -> link.get());
    }

    /** The linked units, for method-size reports. */
    public java.util.Collection<B1Compiler.CompiledFile> files() { return java.util.List.copyOf(linked.values()); }

    /** Linked units, reuses, and first links. */
    public long size() { return linked.size(); }
    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }
    /** Hidden classes defined through this cache. */
    public long hiddenClasses() { return linked.values().stream().mapToLong(B1Compiler.CompiledFile::lintedClasses).sum(); }
}
