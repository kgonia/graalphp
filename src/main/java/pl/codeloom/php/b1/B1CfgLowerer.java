package pl.codeloom.php.b1;

import pl.codeloom.php.ir.IrUnit.*;
import static pl.codeloom.php.b1.B1Cfg.*;

import java.util.*;

/** Build backwards from continuations. A cleanup which throws targets the enclosing exception region. */
final class B1CfgLowerer {
    private final List<Block> blocks = new ArrayList<>();
    private final int exit = block(new Leave(), -1, -1);

    private final Set<String> statementBound;
    private B1CfgLowerer(Set<String> statementBound) { this.statementBound = statementBound; }

    static B1Cfg lower(IrFunction f) { return lower(f, Set.of()); }

    static B1Cfg lower(IrFunction f, Set<String> statementBound) {
        var graph = new B1CfgLowerer(statementBound);
        int start = graph.sequence(f.body(), graph.exit, graph.exit);
        int entry = graph.block(new Enter(), start, graph.exit);
        return new B1Cfg(f, entry, graph.exit, List.copyOf(graph.blocks), longLocals(f));
    }

    private int block(Op op, int normal, int exceptional) {
        int id = blocks.size();
        blocks.add(new Block(id, op, normal, exceptional));
        return id;
    }

    private int sequence(List<Statement> body, int next, int handler) {
        for (var stmt : body.reversed()) {
            if (stmt instanceof ClassDeclaration decl) {
                if (statementBound.contains(decl.name())) next = block(new Declare(decl.name(), decl.at()), next, handler);
            } else if (stmt instanceof TryCatch tc) {
                int caught = sequence(tc.handler(), next, handler);
                int match = block(new Catch(tc.type(), tc.variable()), caught, handler);
                next = sequence(tc.body(), next, match);
            } else {
                Region r = (Region) stmt;
                int normal = r.action() instanceof Return ? exit : next;
                int exceptional = handler;
                if (!r.exceptional().slots().isEmpty())
                    exceptional = block(new Cleanup(r.exceptional(), true), handler, handler);
                if (!r.normal().slots().isEmpty())
                    normal = block(new Cleanup(r.normal(), false), normal, exceptional);
                next = block(new Execute(r.at(), r.action()), normal, exceptional);
            }
        }
        return next;
    }

    /** A straight-line CV written only integer literals and never sent by address can occupy a JVM long pair. */
    private static Set<Integer> longLocals(IrFunction f) {
        if (f.fileBody() || !f.temps().isEmpty() || f.body().stream().anyMatch(TryCatch.class::isInstance)) return Set.of();
        var candidates = new HashSet<Integer>();
        var boxed = new HashSet<Integer>();
        for (int i = 0; i < f.params().size(); i++) boxed.add(i);
        boolean initializing = true;
        for (Statement s : f.body()) {
            Action a = ((Region)s).action();
            if (a instanceof Write(int id, Expr value)) {
                if (value instanceof Literal(Object v) && v instanceof Long) {
                    if (initializing) candidates.add(id);
                    continue;
                }
                boxed.add(id);
            }
            initializing = false;
        }
        candidates.removeAll(boxed);
        return Set.copyOf(candidates);
    }
}
