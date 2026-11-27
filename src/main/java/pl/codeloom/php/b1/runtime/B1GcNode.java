package pl.codeloom.php.b1.runtime;

import java.util.function.Consumer;

/** The shadow RC graph includes reference cells. Two bindings share one edge to their value. */
sealed interface B1GcNode permits B1Array, B1Closure, B1Object, B1Ref {
    static void markRoot(B1GcNode node, boolean inGcRoots) {
        switch (node) {
            case B1Array a -> a.inGcRoots = inGcRoots;
            case B1Closure c -> c.inGcRoots = inGcRoots;
            case B1Object o -> o.inGcRoots = inGcRoots;
            case B1Ref r -> r.inGcRoots = inGcRoots;
        }
    }
    static int slot(B1GcNode node) {
        return switch (node) {
            case B1Array a -> a.gcSlot;
            case B1Closure c -> c.gcSlot;
            case B1Object o -> o.gcSlot;
            case B1Ref r -> r.gcSlot;
        };
    }
    static void slot(B1GcNode node, int slot) {
        switch (node) {
            case B1Array a -> a.gcSlot = slot;
            case B1Closure c -> c.gcSlot = slot;
            case B1Object o -> o.gcSlot = slot;
            case B1Ref r -> r.gcSlot = slot;
        }
    }
    static int count(B1GcNode node) {
        return switch (node) {
            case B1Array a -> a.gcCount();
            case B1Closure c -> c.gcCount();
            case B1Object o -> o.gcCount();
            case B1Ref r -> r.gcCount();
        };
    }
    static void edges(B1GcNode node, Consumer<Object> visit) {
        switch (node) {
            case B1Array a -> a.gcEdges(visit);
            case B1Closure c -> c.gcEdges(visit);
            case B1Object o -> o.gcEdges(visit);
            case B1Ref r -> visit.accept(r.get());
        }
    }
}
