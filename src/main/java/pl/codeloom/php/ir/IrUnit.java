package pl.codeloom.php.ir;

import java.util.List;

/** Backend-independent PHP operations. IDs index their owning unit or function. */
public record IrUnit(String file, List<IrFunction> functions, List<IrClass> classes) {
    public IrUnit {
        functions = List.copyOf(functions);
        classes = List.copyOf(classes);
    }

    public record IrClass(int id, String name, IrSourceSpan at, int endLine, List<String> properties,
                          List<Integer> methods) {}
    public record Param(String name, boolean byRef, boolean integer, IrSourceSpan at) {}
    public record IrFunction(int id, String owner, String name, IrSourceSpan at, int endLine, List<Param> params,
                             List<String> locals, List<TempKind> temps, List<Statement> body) {
        public boolean fileBody() { return id == 0; }
    }

    public sealed interface Statement permits Region, TryCatch, ClassDeclaration {}
    public record ClassDeclaration(int id, String name, IrSourceSpan at) implements Statement {}
    /** Both exits of a statement release the initialized prefix of its owning temporaries. */
    public record Region(IrSourceSpan at, Action action, ReleaseTemps normal, ReleaseTemps exceptional)
            implements Statement {}
    public record TryCatch(int label, IrSourceSpan at, List<Statement> body, String type, int variable,
                           List<Statement> handler) implements Statement {}
    public record ReleaseTemp(int id) {}
    public record ReleaseTemps(List<ReleaseTemp> slots) {}

    public sealed interface Action permits Write, WriteProperty, Echo, Return, Raise, Evaluate {}
    public record Write(int local, Expr value) implements Action {}
    public record WriteProperty(Expr receiver, String name, Expr value) implements Action {}
    public record Echo(Expr value) implements Action {}
    /** Protect the borrowed result before releasing the region and function frame. */
    public record Return(Expr value) implements Action {}
    public record Raise(Expr value) implements Action {}
    public record Evaluate(Expr value) implements Action {}

    public sealed interface Expr permits Literal, Read, This, Property, Binary, Call, Allocate, StoreTemp {}
    public record Literal(Object value) implements Expr {}
    public record Read(int local) implements Expr {}
    public record This() implements Expr {}
    public record Property(Expr receiver, String name) implements Expr {}
    public enum Operator { ADD, CONCAT }
    public record Binary(Operator op, Expr left, Expr right) implements Expr {
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
    public enum Dispatch { FUNCTION, METHOD, CONSTRUCTOR }
    /** Resolve before evaluating any arguments. A constructor receiver is the protected NEW temporary. */
    public record Target(Dispatch kind, String name, Expr receiver) {}
    /** A variable can bind to the resolved parameter by reference. Other operands send their value. */
    public record Argument(int slot, Expr value, boolean bindVariable) {}
    public record Call(Target target, List<Argument> args, ReleaseTemps cleanup, boolean discardReturn, IrSourceSpan at) implements Expr {}
}
