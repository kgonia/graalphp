package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.nodes.vars.ListAssignNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * foreach. By value it iterates a snapshot: the loop holds the array, so writes to the variable inside the loop
 * copy it and the iteration is unaffected. By reference it makes the variable's array exclusive and binds the
 * value variable to each element's reference. Objects iterate their Iterator methods or public properties.
 */
public final class ForeachNode extends StmtNode {
    @Child private ExprNode subject;
    @Child private LValueNode keyTarget;
    @Child private LValueNode valueTarget;
    @Child private ListAssignNode valueList;
    @Child private StmtNode body;
    private final boolean byRef;

    public ForeachNode(ExprNode subject, LValueNode keyTarget, LValueNode valueTarget, ListAssignNode valueList,
                       StmtNode body, boolean byRef) {
        this.subject = subject;
        this.keyTarget = keyTarget;
        this.valueTarget = valueTarget;
        this.valueList = valueList;
        this.body = body;
        this.byRef = byRef;
    }

    @Override
    public void execute(VirtualFrame frame) {
        if (byRef) {
            executeByRef(frame);
            return;
        }
        Object value = subject.execute(frame);
        if (value instanceof PhpArray array) {
            iterate(frame, array);
        } else if (value instanceof PhpObject object) {
            iterateObject(frame, object);
        }
    }

    private void iterate(VirtualFrame frame, PhpArray array) {
        array.addHolder();
        try {
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                if (keyTarget != null) {
                    keyTarget.write(frame, array.keyAt(p));
                }
                assignValue(frame, array.valueAt(p));
                if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                    return;
                }
            }
        } finally {
            array.removeHolder();
        }
    }

    private void assignValue(VirtualFrame frame, Object element) {
        if (valueList != null) {
            valueList.assign(frame, element);
        } else {
            valueTarget.write(frame, element);
        }
    }

    private void executeByRef(VirtualFrame frame) {
        if (!(subject instanceof LValueNode place)) {
            throw new PhpError("Cannot iterate a temporary by reference");
        }
        PhpArray array = place.arrayForWrite(frame);
        if (array == null) {
            throw new PhpError("foreach by reference over an object is not supported");
        }
        int p = array.firstPosition();
        while (p >= 0) {
            PhpRef ref = array.refAtPosition(p);
            if (keyTarget != null) {
                keyTarget.write(frame, array.keyAt(p));
            }
            valueTarget.bindRef(frame, ref);
            if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                return;
            }
            p = array.nextPosition(p);
        }
    }

    private void iterateObject(VirtualFrame frame, PhpObject object) {
        if (object.getPhpClass().isSubclassOf("iteratoraggregate")) {
            Object inner = object.callMethod("getiterator");
            if (inner instanceof PhpObject iterator) {
                iterateObject(frame, iterator);
                return;
            }
            throw new PhpError("getIterator() must return a Traversable");
        }
        if (object.getPhpClass().isSubclassOf("iterator")) {
            object.callMethod("rewind");
            while (Convert.toBool(object.callMethod("valid"))) {
                Object current = PhpRef.deref(object.callMethod("current"));
                if (keyTarget != null) {
                    keyTarget.write(frame, PhpRef.deref(object.callMethod("key")));
                }
                assignValue(frame, current);
                if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                    return;
                }
                object.callMethod("next");
            }
            return;
        }
        for (Object name : object.propertyNames()) {
            String property = (String) name;
            if (!object.getPhpClass().isPublicProperty(property)) {
                continue;
            }
            if (keyTarget != null) {
                keyTarget.write(frame, Str.of(property));
            }
            assignValue(frame, PhpRef.deref(object.getProperty(property)));
            if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                return;
            }
        }
    }
}
