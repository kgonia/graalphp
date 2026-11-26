package pl.codeloom.php.b1.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Bacon-Rajan trial deletion. Trial counts never change live owners while PHP destructors run. */
final class B1CycleCollector {
    private final LinkedHashSet<B1GcNode> roots = new LinkedHashSet<>();
    private static final int DEFAULT_THRESHOLD = 10_000;
    private int gcThreshold = DEFAULT_THRESHOLD;
    private boolean active;

    // Callers check their node's bit before touching the root set.
    void dropped(B1RequestContext ctx, B1GcNode node, int count) {
        boolean candidate = count > 0;
        B1GcNode.markRoot(node, candidate);
        if (!candidate) {
            roots.remove(node);
        } else {
            roots.add(node);
            if (roots.size() >= gcThreshold && !active) {
                long collected = collect(ctx);
                if (collected < 100) gcThreshold = Math.min(gcThreshold + DEFAULT_THRESHOLD, 1_000_000);
                else gcThreshold = Math.max(gcThreshold - DEFAULT_THRESHOLD, DEFAULT_THRESHOLD);
            }
        }
    }
    int rootCount() { return roots.size(); }

    private static LinkedHashSet<B1GcNode> discover(Iterable<B1GcNode> roots) {
        var graph = new LinkedHashSet<B1GcNode>();
        var queue = new ArrayDeque<B1GcNode>();
        for (var root : roots) if (graph.add(root)) queue.add(root);
        while (!queue.isEmpty()) {
            B1GcNode.edges(queue.remove(),value -> {
                if (value instanceof B1GcNode child && B1GcNode.count(child) > 0 && graph.add(child)) queue.add(child);
            });
        }
        return graph;
    }

    private static ArrayList<B1GcNode> garbage(Iterable<B1GcNode> graph,int protection) {
        var counts = new LinkedHashMap<B1GcNode,Integer>();
        var live = new ArrayDeque<B1GcNode>();
        for (var node : graph) counts.put(node,B1GcNode.count(node) - protection);
        for (var node : graph) B1GcNode.edges(node,child -> {
            Integer rc = counts.get(child);
            if (rc != null) counts.put((B1GcNode)child,rc - 1);
        });
        counts.forEach((node,count) -> { if (count > 0) live.add(node); });
        while (!live.isEmpty()) {
            var node = live.remove();
            if (counts.remove(node) != null) B1GcNode.edges(node,value -> {
                if (value instanceof B1GcNode child && counts.containsKey(child)) live.add(child);
            });
        }
        return new ArrayList<>(counts.keySet());
    }

    long collect(B1RequestContext ctx) {
        if (active || roots.isEmpty()) return 0;
        active = true;
        try {
            var white = garbage(discover(roots),0);
            for (var root : roots) B1GcNode.markRoot(root, false);
            roots.clear();
            return collectWhite(ctx,white);
        } finally { active = false; }
    }

    private long collectWhite(B1RequestContext ctx,ArrayList<B1GcNode> white) {
        // A destructor can sever peers' last edges while the batch is being visited.
        for (var node : white) B1Object.retain(node);
        Throwable pending = null;
        long collected = 0;
        for (var node : white) {
            if (node instanceof B1Object object) {
                try { object.destruct(ctx); }
                catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
            }
        }
        // Remove the collector's protection from trial counts before testing for resurrection.
        for (var node : garbage(white,1)) {
            try {
                switch (node) {
                    case B1Array array -> array.clear(ctx);
                    case B1Closure closure -> closure.clear(ctx);
                    case B1Object object -> object.clear(ctx);
                    case B1Ref ref -> ref.clear(ctx);
                }
            } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
            if (!(node instanceof B1Ref)) collected++;
        }
        for (var node : white) {
            try { B1Object.release(ctx,node); }
            catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
        }
        B1Thrown.propagate(pending);
        return collected;
    }
}
