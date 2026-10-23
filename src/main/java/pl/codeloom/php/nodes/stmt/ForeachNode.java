package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
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
    @Child private LoopNode arrayLoop;
    private final boolean byRef;
    /**
     * Hidden frame slots holding the iterated array and the next position, so the loop state is plain frame
     * values (registers, once compiled) rather than a heap object updated at each step. Cleared after the loop,
     * so paths with and without it merge with the same slot tags.
     */
    private final int arraySlot;
    private final int positionSlot;
    /** Whether this loop has iterated an object: until it does, compiled code leaves the object path out. */
    @CompilationFinal private boolean seenObject;
    /**
     * The array layouts this loop has iterated by value. Compiled code has a path only for those: an int list
     * binds its values unboxed, and no layout pays for the others' checks.
     */
    @CompilationFinal private byte seenLayouts;
    private static final byte LONGS = 1;
    private static final byte OBJECTS = 2;
    private static final byte HASH = 4;

    public ForeachNode(ExprNode subject, LValueNode keyTarget, LValueNode valueTarget, ListAssignNode valueList,
                       StmtNode body, boolean byRef, int arraySlot, int positionSlot) {
        this.subject = subject;
        this.keyTarget = keyTarget;
        this.valueTarget = valueTarget;
        this.valueList = valueList;
        this.body = body;
        this.byRef = byRef;
        this.arraySlot = arraySlot;
        this.positionSlot = positionSlot;
        this.arrayLoop = Truffle.getRuntime().createLoopNode(new ArrayStep());
    }

    /** One element of an array foreach: bind key and value (or the element's reference), run the body. */
    private final class ArrayStep extends Node implements RepeatingNode {
        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            int p = frame.getInt(positionSlot);
            if (p < 0) {
                return false;
            }
            PhpArray array = (PhpArray) frame.getObject(arraySlot);
            if (byRef) {
                if (keyTarget != null) {
                    keyTarget.write(frame, array.keyAt(p));
                }
                valueTarget.bindRef(frame, array.refAtPosition(p));
            } else {
                bind(frame, array, p);
            }
            if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                return false;
            }
            int next = array.nextPosition(p);
            frame.setInt(positionSlot, next);
            return next >= 0;
        }
    }

    /** Binds key and value of the element at position p, by the array's layout. */
    private void bind(VirtualFrame frame, PhpArray array, int p) {
        if (array.holdsLongs()) {
            seen(LONGS);
            if (keyTarget != null) {
                keyTarget.writeLong(frame, p);
            }
            if (valueList == null) {
                valueTarget.writeLong(frame, array.longAt(p));
            } else {
                valueList.assign(frame, array.longAt(p));
            }
        } else if (array.holdsObjects()) {
            seen(OBJECTS);
            if (keyTarget != null) {
                keyTarget.writeLong(frame, p);
            }
            assignValue(frame, PhpRef.deref(array.packedAt(p)));
        } else {
            seen(HASH);
            if (keyTarget != null) {
                keyTarget.write(frame, array.hashKeyAt(p));
            }
            assignValue(frame, PhpRef.deref(array.hashValueAt(p)));
        }
    }

    private void seen(byte layout) {
        if ((seenLayouts & layout) == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seenLayouts |= layout;
        }
    }

    private void runArray(VirtualFrame frame, PhpArray array) {
        frame.setObject(arraySlot, array);
        frame.setInt(positionSlot, array.firstPosition());
        try {
            arrayLoop.execute(frame);
        } finally {
            frame.clear(arraySlot);
            frame.clear(positionSlot);
        }
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
            if (!seenObject) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenObject = true;
            }
            iterateObject(frame, object);
        }
    }

    private void iterate(VirtualFrame frame, PhpArray array) {
        array.addHolder();
        try {
            runArray(frame, array);
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
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot iterate a temporary by reference");
        }
        PhpArray array = place.arrayForWrite(frame);
        if (array == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("foreach by reference over an object is not supported");
        }
        runArray(frame, array);
    }

    private void iterateObject(VirtualFrame frame, PhpObject subject) {
        PhpObject object = unwrapAggregates(subject);
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

    /** Follows getIterator() to the Iterator (a loop, not recursion: partial evaluation would inline it forever). */
    @TruffleBoundary
    private static PhpObject unwrapAggregates(PhpObject object) {
        while (object.getPhpClass().isSubclassOf("iteratoraggregate")) {
            if (!(object.callMethod("getiterator") instanceof PhpObject inner)) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("getIterator() must return a Traversable");
            }
            object = inner;
        }
        return object;
    }
}
