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
            case StaticVar v -> new StaticVar(v.local(),v.init() == null ? null : expression(v.init()));
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
            case Yield y -> yieldExpr(y,slot());
            case StoreTemp t when t.value() instanceof Yield y -> yieldExpr(y,t.id());
            case StoreTemp t when t.value() instanceof Call call -> call(call,t.id());
            case StoreTemp t when t.value() instanceof ArrayValue a -> arrayValue(a,t.id());
            case StoreTemp t when t.value() instanceof Binary b -> binary(b,t.id());
            case StoreTemp t when t.value() instanceof Choose c -> chooseExpr(c,t.id());
            case StoreTemp t -> new StoreTemp(t.id(),expression(t.value()));
            case Binary b -> binary(b,-1);
            case Choose c -> chooseExpr(c,slot());
            case Call call -> call(call,slot());
            case Property p -> new Property(expression(p.receiver()),p.name());
            case QuietProperty p -> new QuietProperty(expression(p.receiver()),p.name());
            case Dim d -> new Dim(spill(expression(d.base())),expression(d.key()),d.quiet());
            case ArrayValue a -> arrayValue(a,-1);
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
            // B1 language level (PhpIrLowerer.lowerB1*).
            case Assign a -> new Assign(a.local(),expression(a.value()));
            case InstanceOf i -> new InstanceOf(expression(i.value()),i.className());
            case Convert c -> new Convert(expression(c.value()),c.type());
            case Include i -> new Include(expression(i.path()),i.kind(),i.at());
            case ByReference r -> new ByReference(address(r.place()));
            case UpdatePlace u -> {
                // The emitter's order: early keys, the value, then the RW fetches of the root and the path.
                var keys = new ArrayList<Expr>(u.keys().size());
                for (int i = 0; i < u.keys().size(); i++) keys.add(u.early(i) ? spill(expression(u.keys().get(i))) : u.keys().get(i));
                Expr v = spill(expression(u.value()));
                yield new UpdatePlace(hasYield(u.root()) ? address(u.root()) : u.root(),List.copyOf(keys),u.op(),v);
            }
            default -> throw new IllegalStateException("Unhandled suspension in " + e);
        };
    }

    private Expr yieldExpr(Yield y,int dest) {
        boolean keyOwned = y.key() instanceof StoreTemp;
        boolean valOwned = y.value() instanceof StoreTemp;
        Expr key = spill(expression(y.key()));
        Expr val = expression(y.value());
        var toRelease = new ArrayList<ReleaseTemp>(2);
        if (keyOwned && key instanceof LoadTemp kt) toRelease.add(new ReleaseTemp(kt.id()));
        else if (key instanceof StoreTemp kt) { trackScratch(kt.id()); toRelease.add(new ReleaseTemp(kt.id())); }
        if (valOwned && val instanceof LoadTemp vt) toRelease.add(new ReleaseTemp(vt.id()));
        else if (val instanceof StoreTemp vt) { trackScratch(vt.id()); toRelease.add(new ReleaseTemp(vt.id())); }
        ReleaseTemps clean = toRelease.isEmpty() ? EMPTY : new ReleaseTemps(List.copyOf(toRelease));
        emit(new YieldPoint(key,val,y.delegate(),clean));
        trackScratch(dest);
        emit(new Receive(dest));
        return new LoadTemp(dest);
    }

    private Expr binary(Binary b,int target) {
        if (b.preservesValue() || b.op() == Operator.AND || b.op() == Operator.OR) {
            Expr rawLeft = b.left();
            Expr left = spill(expression(rawLeft));
            Expr cond = b.op() == Operator.COALESCE ? new IsSet(left) : left;
            int dest = target >= 0 ? target : slot();
            if (target >= 0) trackScratch(dest);
            boolean releaseLeft = !(rawLeft instanceof LoadTemp) && left instanceof LoadTemp;
            ReleaseTemps condClean = !b.preservesValue() && releaseLeft
                    ? new ReleaseTemps(List.of(new ReleaseTemp(((LoadTemp)left).id()))) : EMPTY;
            return switch (b.op()) {
                case AND -> choose(cond,new Not(new Not(b.right())),new Literal(false),condClean,dest);
                case OR -> choose(cond,new Literal(true),new Not(new Not(b.right())),condClean,dest);
                default -> choose(cond,left,b.right(),EMPTY,dest);
            };
        }
        boolean leftOwned = b.left() instanceof StoreTemp;
        boolean rightOwned = b.right() instanceof StoreTemp;
        Expr left = b.deferredLeft() ? b.left() : spill(expression(b.left()));
        Expr right = expression(b.right());
        var toRelease = new ArrayList<ReleaseTemp>(2);
        if (leftOwned && left instanceof LoadTemp lt) toRelease.add(new ReleaseTemp(lt.id()));
        if (rightOwned && right instanceof LoadTemp rt) toRelease.add(new ReleaseTemp(rt.id()));
        Expr result = new Binary(b.op(),left,right);
        if (!toRelease.isEmpty()) {
            int res = target >= 0 ? target : slot();
            if (target >= 0) trackScratch(res);
            emit(new Execute(at,new Evaluate(new StoreTemp(res,result))));
            emit(new Cleanup(new ReleaseTemps(List.copyOf(toRelease)),false));
            return new LoadTemp(res);
        }
        return target >= 0 ? new StoreTemp(target,result) : result;
    }

    private Expr arrayValue(ArrayValue a,int target) {
        var items = new ArrayList<Item>(a.items().size());
        var toRelease = new ArrayList<ReleaseTemp>();
        for (var i : a.items()) {
            boolean keyOwned = i.key() instanceof StoreTemp;
            boolean valOwned = i.value() instanceof StoreTemp;
            Expr k = spill(expression(i.key()));
            // A by-reference element stays a place: its operands are spilled, the fetch happens when the array is built.
            Expr v = i.value() instanceof ByReference r ? new ByReference(address(r.place())) : spill(expression(i.value()));
            items.add(new Item(k,v));
            if (keyOwned && k instanceof LoadTemp kt) toRelease.add(new ReleaseTemp(kt.id()));
            if (valOwned && v instanceof LoadTemp vt) toRelease.add(new ReleaseTemp(vt.id()));
        }
        Expr built = new ArrayValue(items);
        if (!toRelease.isEmpty()) {
            int res = target >= 0 ? target : slot();
            if (target >= 0) trackScratch(res);
            emit(new Execute(at,new Evaluate(new StoreTemp(res,built))));
            emit(new Cleanup(new ReleaseTemps(List.copyOf(toRelease)),false));
            return new LoadTemp(res);
        }
        return target >= 0 ? new StoreTemp(target,built) : built;
    }

    private void collectCondTemps(Expr raw,Expr lowered,List<ReleaseTemp> out) {
        if (raw instanceof StoreTemp st) {
            trackScratch(st.id());
            out.add(new ReleaseTemp(st.id()));
        } else if (raw instanceof Not n && lowered instanceof Not ln) {
            collectCondTemps(n.value(),ln.value(),out);
        } else if (raw instanceof IsSet i && lowered instanceof IsSet li) {
            collectCondTemps(i.value(),li.value(),out);
        } else if (!(raw instanceof LoadTemp) && lowered instanceof LoadTemp lt) {
            out.add(new ReleaseTemp(lt.id()));
        }
    }

    private Expr chooseExpr(Choose c,int dest) {
        trackScratch(dest);
        Expr cond = expression(c.test());
        var condTemps = new ArrayList<ReleaseTemp>(1);
        collectCondTemps(c.test(),cond,condTemps);
        ReleaseTemps condClean = condTemps.isEmpty() ? EMPTY : new ReleaseTemps(List.copyOf(condTemps));
        return choose(cond,c.yes(),c.no(),condClean,dest);
    }

    private Expr choose(Expr cond,Expr yes,Expr no,ReleaseTemps condClean,int dest) {
        int left = label(), right = label(), done = label();
        set(cursor,new TestBranch(new Test(at,cond,condClean),right),left,handler);
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
