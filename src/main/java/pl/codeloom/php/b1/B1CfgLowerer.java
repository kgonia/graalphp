package pl.codeloom.php.b1;

import pl.codeloom.php.ir.IrUnit.*;
import static pl.codeloom.php.b1.B1Cfg.*;

import java.util.*;

/** Build backwards from continuations. A cleanup which throws targets the enclosing exception region. */
final class B1CfgLowerer {
    private final List<Block> blocks = new ArrayList<>();
    private final int exit = block(new Leave(), -1, -1);

    private final Set<Integer> statementBound;
    private B1CfgLowerer(Set<Integer> statementBound) { this.statementBound = statementBound; }

    static B1Cfg lower(IrFunction f) { return lower(f, Set.of()); }

    static B1Cfg lower(IrFunction f, Set<Integer> statementBound) {
        var graph = new B1CfgLowerer(statementBound);
        var jumps = new HashMap<String, Integer>();
        graph.marks(f.body(), jumps);
        int start = graph.sequence(f.body(), graph.exit, graph.exit, new Exits(graph.exit, List.of(), jumps));
        int entry = graph.block(new Enter(), start, graph.exit);
        return new B1Cfg(f, entry, graph.exit, List.copyOf(graph.blocks), longLocals(f));
    }

    private int block(Op op, int normal, int exceptional) {
        int id = blocks.size();
        blocks.add(new Block(id, op, normal, exceptional));
        return id;
    }

    private record LoopTargets(int stop, int again) {}
    private record Exits(int returns, List<LoopTargets> loops, Map<String,Integer> jumps) {
        Exits loop(int stop, int again) {
            var nested = new ArrayList<>(loops);
            nested.add(new LoopTargets(stop, again));
            return new Exits(returns, nested, jumps);
        }
    }

    private void marks(List<Statement> body, Map<String,Integer> into) {
        visitMarks(body, name -> into.computeIfAbsent(name, ignored -> block(new Pass(), -1, exit)));
    }

    private void connect(int id, int next) { blocks.set(id, new Block(id, new Pass(), next, exit)); }

    /** Remap only edges leaving the protected body. Internal gotos keep their targets. */
    private Exits through(Exits exits, List<Statement> body, java.util.function.IntUnaryOperator wrap) {
        var inner = new HashSet<String>();
        visitMarks(body, inner::add);
        var jumps = new HashMap<>(exits.jumps);
        jumps.replaceAll((name, id) -> inner.contains(name) ? id : wrap.applyAsInt(id));
        return new Exits(wrap.applyAsInt(exits.returns), exits.loops.stream()
                .map(l -> new LoopTargets(wrap.applyAsInt(l.stop), wrap.applyAsInt(l.again))).toList(), jumps);
    }

    private static void visitMarks(List<Statement> body, java.util.function.Consumer<String> mark) {
        for (Statement stmt : body) switch (stmt) {
            case Mark m -> mark.accept(m.name());
            case Branch b -> { visitMarks(b.yes(),mark); visitMarks(b.no(),mark); }
            case Loop l -> visitMarks(l.body(),mark);
            case Each e -> visitMarks(e.body(),mark);
            case TryCatch t -> { visitMarks(t.body(),mark); visitMarks(t.handler(),mark); }
            case Finally t -> { visitMarks(t.body(),mark); visitMarks(t.cleanup(),mark); }
            default -> {}
        }
    }

    private int sequence(List<Statement> body, int next, int handler, Exits exits) {
        for (var stmt : body.reversed()) {
            if (stmt instanceof Branch b) {
                int yes = sequence(b.yes(), next, handler, exits);
                int no = sequence(b.no(), next, handler, exits);
                next = test(b.test(), yes, no, handler);
            } else if (stmt instanceof Loop l) {
                int head = block(new Pass(), -1, handler);
                int step = sequence(l.step(), head, handler, exits);
                int run = sequence(l.body(), step, handler, exits.loop(next,step));
                connect(head, test(l.test(), run, next, handler));
                next = sequence(l.init(), head, handler, exits);
            } else if (stmt instanceof Each e) {
                var cleanup = new ReleaseTemps(List.of(new ReleaseTemp(e.iterator())));
                var cleaned = new HashMap<Integer,Integer>();
                java.util.function.IntUnaryOperator end = target -> cleaned.computeIfAbsent(target,
                        k -> block(new Cleanup(cleanup,false), k, handler));
                int fail = block(new Cleanup(cleanup,true), handler, handler);
                int head = block(new Pass(), -1, fail);
                int done = end.applyAsInt(next);
                int run = sequence(e.body(), head, fail, through(exits,e.body(),end).loop(done,head));
                connect(head, block(new NextEach(e, done), run, fail));
                next = block(new StartEach(e), head, failedTest(e.source(),fail));
            } else if (stmt instanceof Mark m) {
                int id = exits.jumps.get(m.name());
                connect(id,next);
                next = id;
            } else if (stmt instanceof Jump j) next = exits.jumps.get(j.name());
            else if (stmt instanceof LoopExit l) {
                var loop = exits.loops.get(exits.loops.size() - l.depth());
                next = l.again() ? loop.again : loop.stop;
            } else if (stmt instanceof Finally t) {
                var cancelled = new HashMap<Integer,Integer>();
                java.util.function.IntUnaryOperator cancel = target -> cancelled.computeIfAbsent(target,
                        k -> block(new Cancel(), k, handler));
                // Each exit records its continuation, then enters the same finally body. A throw
                // or return in that body cancels the suspended transfer before leaving the region.
                var entries = new LinkedHashMap<Integer,Integer>();
                int cleanup = block(new Pass(), -1, handler);
                java.util.function.IntUnaryOperator suspend = target -> entries.computeIfAbsent(target,
                        k -> block(new Suspend(k), cleanup, handler));
                int normal = suspend.applyAsInt(next);
                int exceptional = suspend.applyAsInt(handler);
                Exits fromTry = through(exits, t.body(), suspend);
                int resume = block(new Resume(List.copyOf(entries.keySet())), -1, handler);
                int run = sequence(t.cleanup(), resume, cancel.applyAsInt(handler), through(exits, t.cleanup(), cancel));
                connect(cleanup, run);
                next = sequence(t.body(), normal, exceptional, fromTry);
            } else if (stmt instanceof ClassDeclaration decl) {
                if (statementBound.contains(decl.id())) next = block(new Declare(decl.id(),decl.name(), decl.at()), next, handler);
            } else if (stmt instanceof TryCatch tc) {
                int caught = sequence(tc.handler(), next, handler, exits);
                int match = block(new Catch(tc.type(), tc.variable()), caught, handler);
                next = sequence(tc.body(), next, match, exits);
            } else {
                Region r = (Region) stmt;
                int normal = r.action() instanceof Return ? exits.returns : next;
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

    private int failedTest(Test t, int handler) {
        return t.cleanup().slots().isEmpty() ? handler : block(new Cleanup(t.cleanup(), true), handler, handler);
    }
    private int test(Test t, int yes, int no, int handler) {
        return block(new TestBranch(t, no), yes, failedTest(t, handler));
    }

    private static boolean registerExpression(Expr e) {
        return switch (e) {
            case Literal ignored -> true;
            case Read ignored -> true;
            case Binary b -> registerExpression(b.left()) && registerExpression(b.right());
            case null, default -> false;
        };
    }
    /** A straight-line CV written only integer literals and never sent by address can occupy a JVM long pair. */
    private static Set<Integer> longLocals(IrFunction f) {
        if (f.fileBody() || !f.temps().isEmpty() || f.body().stream().anyMatch(s -> !(s instanceof Region))) return Set.of();
        var candidates = new HashSet<Integer>();
        var boxed = new HashSet<Integer>();
        for (int i = 0; i < f.params().size(); i++) boxed.add(i);
        for (var capture : f.captures()) if (capture.byRef()) boxed.add(capture.inner());
        // Only direct CV reads are compatible with the JVM-long representation. Operations
        // such as ++, quiet reads and closure binding require an addressable frame slot.
        for (Statement statement : f.body()) {
            Action action = ((Region)statement).action();
            Expr value = switch (action) {
                case Write w -> w.value();
                case Echo e -> e.value();
                case Return r -> r.value();
                default -> null;
            };
            if (!registerExpression(value)) return Set.of();
        }
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
