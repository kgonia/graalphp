package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.BranchProfile;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A variable of a file's top-level code: a global ({@link GlobalVarNode}), or, while the file runs included from a
 * function, that call's variable ({@link IncludeScope}).
 */
public final class FileVarNode extends LValueNode {
    private final String name;
    @Child private GlobalVarNode global;
    private final BranchProfile included = BranchProfile.create();

    public FileVarNode(String name) {
        this.name = name;
        this.global = new GlobalVarNode(name);
    }

    private IncludeScope scope(VirtualFrame frame) {
        IncludeScope scope = IncludeScope.of(frame);
        if (scope != null) {
            included.enter();
        }
        return scope;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        IncludeScope scope = scope(frame);
        if (scope == null) {
            return global.execute(frame);
        }
        Object value = scope.get(name);
        return value == null ? PhpNull.NULL : value;
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        IncludeScope scope = scope(frame);
        if (scope == null) {
            global.write(frame, value);
        } else {
            scope.set(name, value);
        }
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        IncludeScope scope = scope(frame);
        return scope == null ? global.makeRef(frame) : scope.ref(name);
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        IncludeScope scope = scope(frame);
        if (scope == null) {
            global.bindRef(frame, ref);
        } else {
            scope.bind(name, ref);
        }
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        IncludeScope scope = scope(frame);
        return scope == null ? global.arrayForWrite(frame) : scope.arrayForWrite(name);
    }

    @Override
    public void unset(VirtualFrame frame) {
        IncludeScope scope = scope(frame);
        if (scope == null) {
            global.unset(frame);
        } else {
            scope.unset(name);
        }
    }

    @Override
    public boolean isDefined(VirtualFrame frame) {
        IncludeScope scope = scope(frame);
        return scope == null ? global.isDefined(frame) : scope.isDefined(name);
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        IncludeScope scope = scope(frame);
        if (scope == null) {
            return global.isSet(frame);
        }
        Object value = scope.get(name);
        return value != null && value != PhpNull.NULL;
    }

    @Override
    public boolean isPure() {
        return true;
    }
}
