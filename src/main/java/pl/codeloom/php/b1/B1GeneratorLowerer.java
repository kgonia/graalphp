package pl.codeloom.php.b1;

import pl.codeloom.php.ir.IrSourceSpan;

import java.util.ArrayList;
import java.util.List;

import static pl.codeloom.php.b1.B1Cfg.*;
import static pl.codeloom.php.ir.IrUnit.*;

/** Split expressions at suspension points. Each generated edge has an empty JVM stack.
 * Scratch owners belong to the enclosing statement and drain on both exits. */
final class B1GeneratorLowerer {
    private final List<Block> blocks;
    private int slots, cursor, handler;
    private IrSourceSpan at;
    private final List<ReleaseTemp> scratch = new ArrayList<>();
    private static final ReleaseTemps EMPTY = new ReleaseTemps(List.of());

    B1GeneratorLowerer(List<Block> blocks,int slots) { this.blocks = blocks; this.slots = slots; }

    int lower() {
        for (Block block : List.copyOf(blocks)) {
            if (block.op() instanceof Execute exec && hasYield(exec.action())) {
                begin(block,exec.at());
                finish(block,new Execute(at,action(exec.action())));
            } else if (block.op() instanceof TestBranch branch && hasYield(branch.test().value())) {
                begin(block,branch.test().at());
                Expr value = expression(branch.test().value());
                int no = cleanup(branch.otherwise(),false);
                finish(block,new TestBranch(new Test(at,value,branch.test().cleanup()),no));
            } else if (block.op() instanceof StartEach start && hasYield(start.each().source().value())) {
                Each each = start.each();
                begin(block,each.source().at());
                finish(block,new StartEach(new Each(new Test(at,expression(each.source().value()),each.source().cleanup()),
                        each.iterator(),each.key(),each.value(),each.body())));
            }
        }
        return slots;
    }

    // The IR consists of immutable records and lists. Inspect only during compilation and
    // only for generator bodies. Calls, branches and addressable operands are lowered below.
    private static boolean hasYield(Object tree) {
        if (tree instanceof Yield) return true;
        if (tree instanceof List<?> list) return list.stream().anyMatch(B1GeneratorLowerer::hasYield);
        if (!(tree instanceof Record record) || tree instanceof IrSourceSpan || tree instanceof Literal) return false;
        try {
            for (var field : record.getClass().getRecordComponents())
                if (hasYield(field.getAccessor().invoke(record))) return true;
            return false;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private int label() { int id = blocks.size(); blocks.add(new Block(id,new Pass(),-1,-1)); return id; }
    private void set(int id,Op op,int next,int fail) { blocks.set(id,new Block(id,op,next,fail)); }
    private void emit(Op op) { int next = label(); set(cursor,op,next,handler); cursor = next; }
    private void begin(Block block,IrSourceSpan span) {
        cursor = block.label(); handler = label(); at = span; scratch.clear();
    }
    private int cleanup(int next,boolean unwinding) {
        if (scratch.isEmpty()) return next;
        int id = label();
        set(id,new Cleanup(new ReleaseTemps(List.copyOf(scratch)),unwinding),next,handler);
        return id;
    }
    private void finish(Block block,Op op) {
        set(handler,new Cleanup(new ReleaseTemps(List.copyOf(scratch)),true),block.exceptional(),block.exceptional());
        set(cursor,op,cleanup(block.normal(),false),handler);
    }
    private void trackScratch(int id) {
        for (var r : scratch) if (r.id() == id) return;
        scratch.add(new ReleaseTemp(id));
    }
    private int slot() { int id = slots++; scratch.add(new ReleaseTemp(id)); return id; }
    private Expr spill(Expr e) {
        if (e == null || e instanceof Literal || e instanceof LoadTemp) return e;
        int id;
        if (e instanceof StoreTemp t) {
            id = t.id();
            trackScratch(id);
            emit(new Execute(at,new Evaluate(e)));
        } else {
            id = slot();
            emit(new Execute(at,new Evaluate(new StoreTemp(id,e))));
        }
        return new LoadTemp(id);
    }

    private Action action(Action action) {
        return switch (action) {
            case Write w -> new Write(w.local(),expression(w.value()));
            case Echo e -> new Echo(expression(e.value()));
            case Evaluate e -> new Evaluate(expression(e.value()));
            case Raise e -> new Raise(expression(e.value()));
            case Return r -> new Return(expression(r.value()));
            case WriteProperty w -> {
                Expr v = spill(expression(w.value()));
                yield new WriteProperty(expression(w.receiver()),w.name(),v);
            }
            case WriteNamedProperty w -> new WriteNamedProperty(spill(expression(w.receiver())),
                    spill(expression(w.name())),expression(w.value()));
            case WriteStatic w -> new WriteStatic(w.place(),expression(w.value()));
            case WriteDim w -> {
                var keys = new ArrayList<Expr>();
                for (int i=0;i<w.keys().size();i++) keys.add(w.early(i) ? spill(expression(w.keys().get(i))) : w.keys().get(i));
                Expr v = spill(expression(w.value()));
                yield new WriteDim(address(w.root()),keys,v);
            }
            case Bind b -> new Bind(b.local(),address(b.place()));
            case Unset u -> new Unset(address(u.root()),expression(u.key()));
            case CoalesceDim d -> {
                Expr key = spill(expression(d.key()));
                int done = label(), yes = label();
                set(cursor,new TestBranch(new Test(at,new IsSet(new Dim(new QuietRead(d.local()),key,true)),EMPTY),yes),done,handler);
                cursor = yes;
                emit(new Execute(at,new WriteDim(new Read(d.local()),List.of(key),expression(d.value()))));
                set(cursor,new Pass(),done,handler); cursor = done;
                yield new Evaluate(new Literal(null));
            }
            case Global g -> g;
        };
    }

    private Expr address(Expr e) {
        if (e instanceof Prepared p) {
            for (var operand : p.operands()) spill(expression(operand));
            return address(p.result());
        }
        if (e instanceof StoreTemp t) return address(t.value());
        if (e instanceof Dim d) return new Dim(address(d.base()),spill(expression(d.key())),d.quiet());
        if (e instanceof Property p) return new Property(spill(expression(p.receiver())),p.name());
        return e;
    }

    private Expr expression(Expr e) {
        if (!hasYield(e)) return e;
        return switch (e) {
            case Yield y -> {
                Expr key = spill(expression(y.key()));
                emit(new YieldPoint(key,expression(y.value()),y.delegate()));
                int dest = slot();
                emit(new Receive(dest));
                yield new LoadTemp(dest);
            }
            case StoreTemp t when t.value() instanceof Call call -> call(call,t.id());
            case StoreTemp t -> new StoreTemp(t.id(),expression(t.value()));
            case Binary b -> {
                if (b.preservesValue() || b.op() == Operator.AND || b.op() == Operator.OR) {
                    Expr left = spill(expression(b.left()));
                    Expr cond = b.op() == Operator.COALESCE ? new IsSet(left) : left;
                    yield switch (b.op()) {
                        case AND -> choose(cond,new Not(new Not(b.right())),new Literal(false));
                        case OR -> choose(cond,new Literal(true),new Not(new Not(b.right())));
                        default -> choose(cond,left,b.right());
                    };
                }
                boolean leftOwned = b.left() instanceof StoreTemp;
                Expr left = b.deferredLeft() ? b.left() : spill(expression(b.left()));
                Expr right = expression(b.right());
                if (leftOwned && left instanceof LoadTemp lt) {
                    int res = slot();
                    emit(new Execute(at,new Evaluate(new StoreTemp(res,new Binary(b.op(),left,right)))));
                    emit(new Cleanup(new ReleaseTemps(List.of(new ReleaseTemp(lt.id()))),false));
                    yield new LoadTemp(res);
                }
                yield new Binary(b.op(),left,right);
            }
            case Choose c -> choose(expression(c.test()),c.yes(),c.no());
            case Call call -> call(call,slot());
            case Property p -> new Property(expression(p.receiver()),p.name());
            case QuietProperty p -> new QuietProperty(expression(p.receiver()),p.name());
            case Dim d -> new Dim(spill(expression(d.base())),expression(d.key()),d.quiet());
            case ArrayValue a -> {
                var items = new ArrayList<Item>();
                for (var i : a.items()) items.add(new Item(spill(expression(i.key())),spill(expression(i.value()))));
                yield new ArrayValue(items);
            }
            case Prepared p -> {
                for (var t : p.operands()) spill(expression(t));
                yield expression(p.result());
            }
            case Cast c -> new Cast(expression(c.value()),c.string());
            case Not n -> new Not(expression(n.value()));
            case IsSet i -> new IsSet(expression(i.value()));
            case IncrementPlace i -> new IncrementPlace(address(i.place()),i.up(),i.prefix());
            case Update u -> {
                Expr v = spill(expression(u.value()));
                emit(new Execute(at,new Write(u.local(),v)));
                yield v;
            }
            case DynamicAllocate a -> new DynamicAllocate(expression(a.name()),a.at());
            case Eval v -> new Eval(expression(v.code()),v.at());
            case Forward f -> new Forward(spill(expression(f.callable())),spill(expression(f.args())),
                    spill(expression(f.offset())),expression(f.length()));
            default -> throw new IllegalStateException("Unhandled suspension in " + e);
        };
    }

    private Expr choose(Expr cond,Expr yes,Expr no) {
        int left = label(), right = label(), done = label(), dest = slot();
        set(cursor,new TestBranch(new Test(at,cond,EMPTY),right),left,handler);
        cursor = left;
        Expr yesExpr = expression(yes);
        if (yesExpr instanceof StoreTemp yt) trackScratch(yt.id());
        emit(new Execute(at,new Evaluate(new StoreTemp(dest,yesExpr))));
        set(cursor,new Pass(),done,handler);
        cursor = right;
        Expr noExpr = expression(no);
        if (noExpr instanceof StoreTemp nt) trackScratch(nt.id());
        emit(new Execute(at,new Evaluate(new StoreTemp(dest,noExpr))));
        set(cursor,new Pass(),done,handler);
        cursor = done;
        return new LoadTemp(dest);
    }

    private Expr call(Call call,int dest) {
        Target t = call.target();
        Expr receiver = expression(t.receiver());
        if (t.kind() == Dispatch.DYNAMIC) receiver = spill(receiver);
        t = new Target(t.kind(),t.name(),receiver);
        int callee = slot();
        emit(new ResolveCall(t,callee));
        int index = 0;
        for (var arg : call.args()) {
            Expr value = arg.bindVariable() ? address(arg.value()) : expression(arg.value());
            emit(new SendCall(new Argument(arg.slot(),value,arg.bindVariable()),index++,callee));
        }
        emit(new InvokeCall(new Call(t,call.args(),call.cleanup(),call.discardReturn(),call.at()),callee,dest));
        return new LoadTemp(dest);
    }
}
