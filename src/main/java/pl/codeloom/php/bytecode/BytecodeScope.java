package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import pl.codeloom.php.nodes.vars.IncludeScope;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpRef;

import java.util.Map;

/**
 * A call's variables by name ({@link IncludeScope}) for a function on bytecode: what the files it includes and
 * extract() see. A variable the function names is its bytecode local, read and written by the same rules as its
 * own instructions ({@link Locals}: unboxed kinds, references, copy-on-write holders); any other name is one of
 * the call's other variables, which IncludeScope keeps. Made at the call's first include or extract() and kept in
 * a hidden local; rare code, behind boundaries.
 */
final class BytecodeScope extends IncludeScope {
    private final PhpBytecodeRootNode root;
    private final MaterializedFrame frame;
    /** The bytecode node running the call now: the call may move to another tier between two uses. */
    private BytecodeNode bytecode;

    BytecodeScope(PhpBytecodeRootNode root, MaterializedFrame frame, BytecodeNode bytecode, String classScope) {
        super(null, Map.of(), classScope);
        this.root = root;
        this.frame = frame;
        this.bytecode = bytecode;
    }

    /** The scope, with current as the bytecode node running the call. */
    BytecodeScope at(BytecodeNode current) {
        bytecode = current;
        return this;
    }

    @Override
    @TruffleBoundary
    public Object get(String name) {
        LocalAccessor variable = root.variableNamed(name);
        if (variable == null) {
            return super.get(name);
        }
        Object stored = Locals.stored(frame, variable, bytecode);
        return stored instanceof PhpRef ref ? ref.get() : stored;
    }

    @Override
    @TruffleBoundary
    public boolean isDefined(String name) {
        LocalAccessor variable = root.variableNamed(name);
        return variable == null ? super.isDefined(name) : !variable.isCleared(bytecode, frame);
    }

    @Override
    @TruffleBoundary
    public PhpArray definedVariables() {
        return root.definedVariables(frame, bytecode, this, PhpContext.get(null));
    }

    @Override
    @TruffleBoundary
    public boolean isFunctionVariable(String name) {
        return root.variableNamed(name) != null;
    }

    @Override
    @TruffleBoundary
    public void set(String name, Object value) {
        LocalAccessor variable = root.variableNamed(name);
        if (variable == null) {
            super.set(name, value);
        } else {
            Locals.Writes.getUncached().write(frame, variable, bytecode, value);
        }
    }

    @Override
    @TruffleBoundary
    public PhpRef ref(String name) {
        LocalAccessor variable = root.variableNamed(name);
        return variable == null ? super.ref(name) : Locals.makeRef(frame, variable, bytecode);
    }

    @Override
    @TruffleBoundary
    public void bind(String name, PhpRef ref) {
        LocalAccessor variable = root.variableNamed(name);
        if (variable == null) {
            super.bind(name, ref);
        } else {
            Locals.bindRef(frame, variable, bytecode, ref);
        }
    }

    @Override
    @TruffleBoundary
    public void unset(String name) {
        LocalAccessor variable = root.variableNamed(name);
        if (variable == null) {
            super.unset(name);
        } else {
            Locals.unset(frame, variable, bytecode);
        }
    }

    @Override
    @TruffleBoundary
    public PhpArray arrayForWrite(String name) {
        LocalAccessor variable = root.variableNamed(name);
        if (variable == null) {
            return super.arrayForWrite(name);
        }
        return Locals.arrayForWrite(frame, variable, bytecode, bytecode, InlinedBranchProfile.getUncached(),
                InlinedBranchProfile.getUncached(), InlinedBranchProfile.getUncached());
    }
}
