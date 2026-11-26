package pl.codeloom.php.b1;

import pl.codeloom.php.ir.IrSourceSpan;
import pl.codeloom.php.ir.IrUnit.*;

import java.util.List;
import java.util.Set;

/** Per-function graph. Every protected block starts and ends with an empty JVM operand stack. */
record B1Cfg(IrFunction function, int entry, int exit, List<Block> blocks, Set<Integer> longLocals) {
    record Block(int label, Op op, int normal, int exceptional) {}
    sealed interface Op permits Enter, Execute, Declare, Cleanup, Catch, Leave {}
    record Enter() implements Op {}
    record Execute(IrSourceSpan at, Action action) implements Op {}
    record Declare(String name, IrSourceSpan at) implements Op {}
    record Cleanup(ReleaseTemps temps, boolean unwinding) implements Op {}
    record Catch(String type, int local) implements Op {}
    record Leave() implements Op {}
}
