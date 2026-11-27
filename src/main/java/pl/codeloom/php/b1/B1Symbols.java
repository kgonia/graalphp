package pl.codeloom.php.b1;

import java.util.concurrent.ConcurrentHashMap;

/** Dense ids of folded symbol names (functions, methods, classes, properties, globals) for one code cache, or for
 * one request context without a cache. Linked hidden classes carry the ids of the registry they were emitted with,
 * and every context running them resolves those ids through its own tables. A registry lives as long as its cache
 * (or context): a retired cache takes its ids with it. */
public final class B1Symbols {
    private final ConcurrentHashMap<String,Integer> ids = new ConcurrentHashMap<>();

    public int of(String folded) {
        Integer id = ids.get(folded);
        if (id != null) return id;
        synchronized (ids) { return ids.computeIfAbsent(folded, ignored -> ids.size()); }
    }
    public int size() { return ids.size(); }
}
