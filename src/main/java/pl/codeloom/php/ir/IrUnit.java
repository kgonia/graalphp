package pl.codeloom.php.ir;

import java.util.List;

/** Backend-independent PHP operations. IDs index their owning unit or function. */
public record IrUnit(String file, List<IrFunction> functions, List<IrClass> classes) {
    public IrUnit {
        functions = List.copyOf(functions);
        classes = List.copyOf(classes);
    }

    public record IrClass(int id, String name, IrSourceSpan at, int endLine, List<PropertyDef> properties,
                          List<Integer> methods) {}
    public record PropertyDef(String name,int visibility,Expr initial) {}
    public record Capture(int outer, int inner, boolean byRef) {}
    public record Param(String name, boolean byRef, String type, boolean variadic, Expr defaultValue, IrSourceSpan at) {}
    public record IrFunction(int id, String owner, String name, IrSourceSpan at, int endLine, String returnType, List<Param> params,
                             boolean closure, boolean isStatic, List<Capture> captures, List<String> locals, List<TempKind> temps, List<Statement> body) {
        public boolean fileBody() { return id == 0; }
    }

    public sealed interface Statement {}
    public record Test(IrSourceSpan at, Expr value, ReleaseTemps cleanup) {}
    public record Branch(Test test, List<Statement> yes, List<Statement> no) implements Statement {}
    public record Loop(List<Statement> init, Test test, List<Statement> step, List<Statement> body) implements Statement {}
    public record Each(Test source, int iterator, int key, int value, List<Statement> body) implements Statement {}
    public record Jump(String name) implements Statement {}
    public record Mark(String name) implements Statement {}
    public record LoopExit(boolean again, int depth) implements Statement {}
    public record Finally(List<Statement> body, List<Statement> cleanup, int savedReturn) implements Statement {}
    public record ClassDeclaration(int id, String name, IrSourceSpan at) implements Statement {}
    /** Both exits of a statement release the initialized prefix of its owning temporaries. */
    public record Region(IrSourceSpan at, Action action, ReleaseTemps normal, ReleaseTemps exceptional)
            implements Statement {}
    public record TryCatch(int label, IrSourceSpan at, List<Statement> body, String type, int variable,
                           List<Statement> handler) implements Statement {}
    public record ReleaseTemp(int id) {}
    public record ReleaseTemps(List<ReleaseTemp> slots) {}

    public sealed interface Action {}
    public record Write(int local, Expr value) implements Action {}
    public record WriteDim(Expr root, List<Expr> keys, Expr value) implements Action {
        public boolean early(int i) {
            Expr key = keys.get(i);
            return key != null && !(key instanceof Read || key instanceof Literal);
        }
    }
    public record Unset(Expr root, Expr key) implements Action {}
    public record Global(int local) implements Action {}
    public record WriteNamedProperty(Expr receiver, Expr name, Expr value) implements Action {}
    public record QuietProperty(Expr receiver, String name) implements Expr {}
    public record WriteProperty(Expr receiver, String name, Expr value) implements Action {}
    public record Echo(Expr value) implements Action {}
    /** Protect the borrowed result before releasing the region and function frame. */
    public record Return(Expr value) implements Action {}
    public record Raise(Expr value) implements Action {}
    public record Evaluate(Expr value) implements Action {}

    public sealed interface Expr {}
    public record Literal(Object value) implements Expr {}
    public record Read(int local) implements Expr {}
    public record This() implements Expr {}
    public record Property(Expr receiver, String name) implements Expr {}
    public enum Operator { ADD, MOD, CONCAT, IDENTICAL, NOT_IDENTICAL, LT, LE, GT, GE, EQ, NE, AND, OR, COALESCE, ELVIS }
    public record Item(Expr key, Expr value) {}
    public record ArrayValue(List<Item> items) implements Expr {}
    public record Prepared(List<StoreTemp> operands,Expr result) implements Expr {}
    public record LoadTemp(int id) implements Expr {}
    public record Dim(Expr base, Expr key, boolean quiet) implements Expr {}
    public record QuietRead(int local) implements Expr {}
    public record IsSet(Expr value) implements Expr {}
    public record Not(Expr value) implements Expr {}
    public record Choose(Expr test, Expr yes, Expr no) implements Expr {}
    public record Update(int local, Binary value) implements Expr {}
    public record Cast(Expr value,boolean string) implements Expr {}
    public record CheckedReturn(Expr value,boolean missing) implements Expr {}
    public record PassedArguments() implements Expr {}
    public record IncrementPlace(Expr place,boolean up,boolean prefix) implements Expr {}
    public record Increment(int local, boolean up, boolean prefix) implements Expr {}
    public record Closure(int id, List<Capture> captures) implements Expr {}
    public record Forward(Expr callable, Expr args, Expr offset, Expr length) implements Expr {}
    public record Eval(Expr code, IrSourceSpan at) implements Expr {}
    public record DynamicAllocate(Expr name, IrSourceSpan at) implements Expr {}
    public record Binary(Operator op, Expr left, Expr right) implements Expr {
        public boolean preservesValue() { return op == Operator.COALESCE || op == Operator.ELVIS; }
        public boolean deferredLeft() {
            return left instanceof Read && !(right instanceof Literal || right instanceof Read || right instanceof This);
        }

        /** The opcode frees its TMP/VAR operands before the surrounding expression uses the result. */
        public ReleaseTemps consumed() {
            var slots = new java.util.ArrayList<ReleaseTemp>(2);
            if (left instanceof StoreTemp t) slots.add(new ReleaseTemp(t.id()));
            if (right instanceof StoreTemp t) slots.add(new ReleaseTemp(t.id()));
            return new ReleaseTemps(List.copyOf(slots));
        }
    }
    public enum TempKind { VALUE, SEND, RECEIVER, CONSTRUCTION }
    public record StoreTemp(int id, Expr value) implements Expr {}
    public record Allocate(String className, IrSourceSpan at) implements Expr {}
    public enum Dispatch { FUNCTION, METHOD, CONSTRUCTOR, DYNAMIC }
    /** Resolve before evaluating any arguments. A constructor receiver is the protected NEW temporary. */
    public record Target(Dispatch kind, String name, Expr receiver) {}
    /** A variable can bind to the resolved parameter by reference. Other operands send their value. */
    public record Argument(int slot, Expr value, boolean bindVariable) {}
    public record Call(Target target, List<Argument> args, ReleaseTemps cleanup, boolean discardReturn, IrSourceSpan at) implements Expr {}
}
