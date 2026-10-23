package pl.codeloom.php.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Language-level ids for names (functions, constants): a node resolves its name to an id once, and every context
 * keeps its declarations in an array indexed by those ids, so a lookup is an array load. Thread-safe.
 */
public final class NameIds {
    private final Map<String, Integer> ids = new ConcurrentHashMap<>();

    /** The name's id, assigned on first use; ids are dense from 0. */
    public int id(String name) {
        Integer id = ids.get(name);
        if (id == null) {
            synchronized (ids) {
                id = ids.computeIfAbsent(name, key -> ids.size());
            }
        }
        return id;
    }
}
