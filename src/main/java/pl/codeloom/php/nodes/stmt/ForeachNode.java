package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.profiles.BranchProfile;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.nodes.call.CallNode;
import pl.codeloom.php.nodes.vars.DimNode;
import pl.codeloom.php.nodes.vars.ListAssignNode;
import pl.codeloom.php.nodes.vars.PropNode;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.RefIteration;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

/**
 * foreach. By value it iterates a snapshot: the loop holds the array, so writes to the variable inside the loop
 * copy it and the iteration is unaffected. By reference it holds the variable's reference and walks whatever array
 * that reference holds at each step ({@link RefIteration}), binding the value variable to each element's
 * reference. Objects iterate their Iterator methods or public properties.
 */
public final class ForeachNode extends StmtNode {
    @Child private ExprNode subject;
    @Child private BindNode bind;
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
    /** The class (lowercase) the loop is written in: foreach over an object sees what that scope may access. */
    private final String lexicalClass;
    /** Until this loop iterates an object, compiled code leaves the object path out. */
    private final BranchProfile objectSubject = BranchProfile.create();

    public ForeachNode(ExprNode subject, LValueNode keyTarget, LValueNode valueTarget, ListAssignNode valueList,
                       StmtNode body, boolean byRef, int arraySlot, int positionSlot, String lexicalClass) {
        this.lexicalClass = lexicalClass;
        this.subject = subject;
        this.bind = ForeachNodeFactory.BindNodeGen.create(keyTarget, valueTarget, valueList);
        this.body = body;
        this.byRef = byRef;
        this.arraySlot = arraySlot;
        this.positionSlot = positionSlot;
        this.arrayLoop = Truffle.getRuntime().createLoopNode(byRef ? new RefStep() : new ArrayStep());
    }

    /** One element of an array foreach by value: bind key and value, run the body. */
    private final class ArrayStep extends Node implements RepeatingNode {
        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            int p = frame.getInt(positionSlot);
            if (p < 0) {
                return false;
            }
            PhpArray array = (PhpArray) frame.getObject(arraySlot);
            int next = bind.execute(frame, array, p);
            if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                return false;
            }
            frame.setInt(positionSlot, next);
            return next >= 0;
        }
    }

    /**
     * One element of a foreach by reference (FE_FETCH_RW): the array now in the loop's reference, the next element
     * from the iterator's position made a reference, the value variable bound to it, then the key; the body.
     */
    private final class RefStep extends Node implements RepeatingNode {
        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            RefIteration iteration = (RefIteration) frame.getObject(arraySlot);
            PhpArray array = iteration.current();
            if (array == null) {
                notIterable(iteration.ref().get());
                return false;
            }
            int from = iteration.position();
            int p = from == RefIteration.END ? -1 : array.positionFrom(from);
            if (p < 0) {
                return false;
            }
            iteration.setPosition(p + 1);
            bind.bindRef(frame, array.refAtPosition(p));
            bind.writeKey(frame, array.keyAt(p));
            return LoopNodes.runBody(body, frame) != LoopNodes.EXIT;
        }
    }

    /**
     * The loop variables (key, and value or list) and the binding of an array element to them, by the array's
     * layout: compiled code has a path only for the layouts the loop has met, and an int list binds unboxed.
     */
    @ReportPolymorphism
    abstract static class BindNode extends Node {
        @Child private LValueNode keyTarget;
        @Child private LValueNode valueTarget;
        @Child private ListAssignNode valueList;

        BindNode(LValueNode keyTarget, LValueNode valueTarget, ListAssignNode valueList) {
            this.keyTarget = keyTarget;
            this.valueTarget = valueTarget;
            this.valueList = valueList;
        }

        /**
         * Binds key and value of the element at position p and returns the next position (-1 at the end), found by
         * the same layout: by value the loop holds the array, so the body can't change it.
         */
        abstract int execute(VirtualFrame frame, PhpArray array, int p);

        @Specialization(guards = "array.holdsLongs()")
        protected int longs(VirtualFrame frame, PhpArray array, int p) {
            if (keyTarget != null) {
                keyTarget.writeLong(frame, p);
            }
            if (valueList == null) {
                valueTarget.writeLong(frame, array.longAt(p));
            } else {
                valueList.assign(frame, array.longAt(p));
            }
            return array.nextPackedPosition(p);
        }

        @Specialization(guards = "array.holdsObjects()")
        protected int objects(VirtualFrame frame, PhpArray array, int p) {
            if (keyTarget != null) {
                keyTarget.writeLong(frame, p);
            }
            assignValue(frame, PhpRef.deref(array.packedAt(p)));
            return array.nextPackedPosition(p);
        }

        @Specialization(guards = "!array.isPacked()")
        protected int hash(VirtualFrame frame, PhpArray array, int p) {
            writeKey(frame, array.hashKeyAt(p));
            assignValue(frame, PhpRef.deref(array.hashValueAt(p)));
            return array.nextHashPosition(p);
        }

        void writeKey(VirtualFrame frame, Object key) {
            if (keyTarget != null) {
                keyTarget.write(frame, key);
            }
        }

        boolean hasKey() {
            return keyTarget != null;
        }

        void assignValue(VirtualFrame frame, Object element) {
            if (valueList != null) {
                valueList.assign(frame, element);
            } else {
                valueTarget.write(frame, element);
            }
        }

        void bindRef(VirtualFrame frame, PhpRef ref) {
            valueTarget.bindRef(frame, ref);
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
            objectSubject.enter();
            iterateObject(frame, object);
        } else {
            notIterable(value);
        }
    }

    /** FE_RESET's and FE_FETCH_RW's warning for a subject that is neither an array nor an object. */
    private void notIterable(Object value) {
        notIterable(this, value);
    }

    /** notIterable for a loop at site. A Closure is an object without properties: the loop runs no times, quietly. */
    @TruffleBoundary
    public static void notIterable(Node site, Object value) {
        if (value instanceof pl.codeloom.php.runtime.PhpClosure) {
            return;
        }
        String type = value instanceof Boolean b ? (b ? "true" : "false") : Convert.debugType(value);
        ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_WARNING,
                Str.of("foreach() argument must be of type array|object, " + type + " given"), SourceLocation.of(site));
    }

    /** The loop holds the array; it lets go once on each exit (a finally is copied onto both by javac). */
    private void iterate(VirtualFrame frame, PhpArray array) {
        array.addHolder();
        try {
            runArray(frame, array);
        } catch (Throwable t) {
            letGo(array);
            throw t;
        }
        array.removeHolder();
    }

    @TruffleBoundary
    private static void letGo(PhpArray array) {
        array.removeHolder();
    }

    private void executeByRef(VirtualFrame frame) {
        if (!(subject instanceof LValueNode place)) {
            executeByRefOver(frame, temporaryRef(frame));
            return;
        }
        Object value = subject.execute(frame);
        if (!(value instanceof PhpArray)) {
            CompilerDirectives.transferToInterpreter();
            if (value instanceof PhpObject object) {
                iterateObjectByRef(frame, object);
                return;
            }
            if (subject instanceof DimNode) {
                // FETCH_DIM_W has created the element (null) before FE_RESET_RW looks at it
                place.makeRef(frame);
            }
            notIterable(value);
            return;
        }
        // FE_RESET_RW: the variable becomes a reference, which the loop holds while it runs
        executeByRefOver(frame, place.makeRef(frame));
    }

    /**
     * The subject of a foreach by reference that is no variable: the reference a function returning by reference
     * returned, whose array the loop then changes; else a temporary reference to the value (FE_RESET_RW's
     * ZVAL_NEW_REF), which the loop changes unseen.
     */
    private PhpRef temporaryRef(VirtualFrame frame) {
        Object value = subject instanceof CallNode call ? call.executeRef(frame) : subject.execute(frame);
        return value instanceof PhpRef ref ? ref : PhpRef.unbound(value);
    }

    private void executeByRefOver(VirtualFrame frame, PhpRef ref) {
        Object value = ref.get();
        if (!(value instanceof PhpArray)) {
            CompilerDirectives.transferToInterpreter();
            if (value instanceof PhpObject object) {
                iterateObjectByRef(frame, object);
                return;
            }
            notIterable(value);
            return;
        }
        PhpContext context = PhpContext.get(this);
        RefIteration iteration = RefIteration.start(context, ref);
        frame.setObject(arraySlot, iteration);
        try {
            arrayLoop.execute(frame);
        } finally {
            frame.clear(arraySlot);
            iteration.end(context);
        }
    }

    /**
     * foreach by reference over an object: a Generator of a function yielding by reference gives its references
     * (GeneratorRefIteration, which raises php-src's errors for other iterators); the value is bound to each, then
     * the key is written.
     */
    private void iterateObjectByRef(VirtualFrame frame, PhpObject subject) {
        GeneratorRefIteration iteration = GeneratorRefIteration.start(this, subject,
                PropNode.callingScope(frame, lexicalClass));
        while (iteration.advance(this)) {
            bind.bindRef(frame, iteration.currentRef(this));
            if (bind.hasKey()) {
                bind.writeKey(frame, PhpRef.deref(iteration.key(this)));
            }
            if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                return;
            }
        }
    }

    /**
     * An Iterator through its methods, a Generator directly (php-src iterates it without calling its methods); the
     * calls are made from this statement, as traces show them.
     */
    private void iterateObject(VirtualFrame frame, PhpObject subject) {
        PhpObject object = unwrapAggregates(subject).initialized();
        if (object.getPhpClass().isSubclassOf("iterator")) {
            step(object, "rewind");
            while (Convert.toBool(step(object, "valid"))) {
                Object current = PhpRef.deref(step(object, "current"));
                if (bind.hasKey()) {
                    bind.writeKey(frame, PhpRef.deref(step(object, "key")));
                }
                bind.assignValue(frame, current);
                if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                    return;
                }
                step(object, "next");
            }
            return;
        }
        // the properties the scope may access; their values as they are when the loop reaches them, as php-src
        // walks the object's property table (one unset meanwhile is skipped)
        String scope = PropNode.callingScope(frame, lexicalClass);
        java.util.List<String> properties = object.visiblePropertyNames(scope);
        String property;
        for (int i = 0; (property = object.visiblePropertyAt(properties, i, scope)) != null; i++) {
            Object value = object.visibleValue(property);
            if (value == null) {
                continue;
            }
            bind.writeKey(frame, Str.ofKey(property));
            bind.assignValue(frame, value);
            if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                return;
            }
        }
    }


    /** An iteration step: the Iterator's method, or the Generator's own operation for it. */
    private Object step(PhpObject object, String method) {
        return step(this, object, method);
    }

    /** An iteration step made from site, which traces show as the call's location. */
    @TruffleBoundary
    public static Object step(Node site, PhpObject object, String method) {
        if (!(object.getInternal() instanceof PhpGenerator generator)) {
            return object.callMethod(site, method);
        }
        return Backtrace.callFrom(site, () -> generatorStep(generator, method));
    }

    private static Object generatorStep(PhpGenerator generator, String method) {
        return switch (method) {
            case "valid" -> generator.valid();
            case "current" -> generator.current();
            case "key" -> generator.key();
            case "rewind" -> {
                // foreach's start: zend_generator_get_iterator, then its rewind
                generator.startIteration(false);
                generator.rewind();
                yield PhpNull.NULL;
            }
            default -> {
                generator.next();
                yield PhpNull.NULL;
            }
        };
    }

    /** A step of {@link #traverse}: false stops the walk. */
    @FunctionalInterface
    public interface Visit {
        boolean element(Object key, Object value);
    }

    /**
     * A Traversable walked as php-src's object iterator walks it for unpacking (zend_user_it_*): rewind, then per
     * element valid, current, key, visit, next; from site, which traces show as the calls' location.
     */
    @TruffleBoundary
    public static void traverse(Node site, PhpObject subject, Visit visit) {
        PhpObject iterator = unwrapAggregates(site, subject);
        step(site, iterator, "rewind");
        while (Convert.toBool(step(site, iterator, "valid"))) {
            Object value = PhpRef.deref(step(site, iterator, "current"));
            if (!visit.element(PhpRef.deref(step(site, iterator, "key")), value)) {
                return;
            }
            step(site, iterator, "next");
        }
    }

    /** Follows getIterator() to the Iterator (a loop, not recursion: partial evaluation would inline it forever). */
    private PhpObject unwrapAggregates(PhpObject subject) {
        return unwrapAggregates(this, subject);
    }

    /** unwrapAggregates for a loop at site. */
    @TruffleBoundary
    public static PhpObject unwrapAggregates(Node site, PhpObject subject) {
        PhpObject object = subject;
        while (object.getPhpClass().isSubclassOf("iteratoraggregate")) {
            if (!(object.callMethod(site, "getiterator") instanceof PhpObject inner)) {
                throw new PhpError("getIterator() must return a Traversable");
            }
            object = inner;
        }
        return object;
    }
}
