package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * `$GLOBALS[$name]`: the global variable named by the key, in every context php-src compiles it in (ZEND_FETCH_*
 * with ZEND_FETCH_GLOBAL): read, written, read and written (`op=`, `??=`, `++`), as a container, by reference,
 * isset() and unset(). `$GLOBALS` itself is a read-only copy ({@link GlobalsArrayNode}).
 */
public final class GlobalsDimNode extends LValueNode {
    @Child private ExprNode key;

    public GlobalsDimNode(ExprNode key) {
        this.key = key;
    }

    private String name(VirtualFrame frame) {
        return toName(key.execute(frame));
    }

    /** The global variable a key names (the bytecode's $GLOBALS[key] instructions convert it so too). */
    @TruffleBoundary
    public static String toName(Object value) {
        return Str.java(Convert.toStr(value));
    }

    /** The global's value; null after "Undefined global variable" when it doesn't exist (also for `op=`). */
    @Override
    public Object execute(VirtualFrame frame) {
        String name = name(frame);
        PhpRef ref = PhpContext.get(this).findGlobal(name);
        if (ref == null) {
            GlobalVarNode.undefined(this, "Undefined global variable $" + name);
            return PhpNull.NULL;
        }
        return ref.get();
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        PhpContext.get(this).globalRef(name(frame)).set(value);
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        return PhpContext.get(this).globalRef(name(frame));
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        PhpContext.get(this).bindGlobal(name(frame), ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        return Places.refArrayForWrite(PhpContext.get(this).globalRef(name(frame)));
    }

    @Override
    public void unset(VirtualFrame frame) {
        PhpContext.get(this).unsetGlobal(name(frame));
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        PhpRef ref = PhpContext.get(this).findGlobal(name(frame));
        return ref != null && ref.get() != PhpNull.NULL;
    }

    @Override
    public boolean isPure() {
        return key.constantValue() != null || key instanceof LocalVarNode || key instanceof FileVarNode
                || key instanceof GlobalVarNode;
    }
}
