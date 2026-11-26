package pl.codeloom.php.ir;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** zend_call_destructors: reverse unique globals first, then the remaining objects in creation order. */
public final class D17Shutdown {
    private D17Shutdown() {}

    private static int liveGlobals(LinkedHashMap<String, ?> globals) {
        int live = 0;
        for (Object slot : globals.values()) if (slot != null) live++;
        return live;
    }

    public static <S, O> void destruct(LinkedHashMap<String, S> globals, Predicate<S> uniqueObject,
                                       Consumer<String> unset, LinkedHashSet<O> live, Consumer<O> destroy) {
        int before;
        do {
            before = liveGlobals(globals);
            for (String key : globals.reversed().keySet().toArray(String[]::new)) {
                S current = globals.get(key);
                if (current != null && uniqueObject.test(current)) unset.accept(key);
            }
        } while (before != liveGlobals(globals));
        while (!live.isEmpty()) destroy.accept(live.removeFirst());
    }
}
