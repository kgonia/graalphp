package pl.codeloom.php.b1;

import pl.codeloom.php.ir.IrSourceSpan;
import pl.codeloom.php.ir.IrUnit.*;

import java.util.List;
import java.util.Set;

/** Per-function graph. Every protected block starts and ends with an empty JVM operand stack. */
record B1Cfg(IrFunction function, int entry, int exit, List<Block> blocks, Set<Integer> longLocals, int tempCount) {
    record Block(int label, Op op, int normal, int exceptional) {}
    sealed interface Op {}
    record YieldPoint(Expr key, Expr value, boolean delegate, ReleaseTemps cleanup) implements Op {}
    record Receive(int slot) implements Op {}
    record ResolveCall(Target target,int callee) implements Op {}
    record SendCall(Argument arg,int index,int callee) implements Op {}
    record InvokeCall(Call call,int callee,int result) implements Op {}
    record Enter() implements Op {}
    record Execute(IrSourceSpan at, Action action) implements Op {}
    record Declare(int id, String name, IrSourceSpan at) implements Op {}
    record Cleanup(ReleaseTemps temps, boolean unwinding) implements Op {}
    record Catch(String type, int local) implements Op {}
    record TestBranch(Test test, int otherwise) implements Op {}
    record StartEach(Each each) implements Op {}
    record NextEach(Each each, int end) implements Op {}
    record Pass() implements Op {}
    record Suspend(int continuation) implements Op {}
    record Resume(List<Integer> targets) implements Op {}
    record Cancel() implements Op {}
    record Leave() implements Op {}
}
