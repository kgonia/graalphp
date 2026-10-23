package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/** `Cls::$name`: a static property, which is always a reference shared by the class (and subclasses). */
public final class StaticPropNode extends LValueNode {
    @Child private ClassRefNode classRef;
    @Child private Lookup lookup;
    private final String name;

    public StaticPropNode(ClassRefNode classRef, String name) {
        this.classRef = classRef;
        this.name = name;
        this.lookup = StaticPropNodeFactory.LookupNodeGen.create(name);
    }

    private PhpRef ref(VirtualFrame frame) {
        PhpRef ref = lookup.execute(classRef.resolve(frame));
        if (ref == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Access to undeclared static property " + classRef.getName() + "::$" + Str.keyText(name));
        }
        return ref;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return ref(frame).get();
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        ref(frame).set(value);
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        return ref(frame);
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Binding a static property by reference is not supported");
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        return Places.refArrayForWrite(ref(frame));
    }

    @Override
    public void unset(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Attempt to unset static property " + classRef.getName() + "::$" + Str.keyText(name));
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        PhpRef ref = lookup.execute(classRef.resolve(frame));
        return ref != null && ref.get() != PhpNull.NULL;
    }

    @Override
    public boolean isPure() {
        return true;
    }

    /**
     * The class's reference for the property, or null if it has none. A class's static properties are made when
     * it is declared and its initializers are evaluated on first access, so while the language serves one context
     * a site keeps the reference it found per class as a compile-time constant.
     */
    abstract static class Lookup extends Node {
        static final int LIMIT = 3;

        protected final String name;

        Lookup(String name) {
            this.name = name;
        }

        abstract PhpRef execute(PhpClass phpClass);

        @Specialization(guards = "phpClass == cachedClass", limit = "LIMIT", assumptions = "singleContext()")
        protected PhpRef cached(PhpClass phpClass,
                                @Cached("phpClass") PhpClass cachedClass,
                                @Cached("cachedClass.getStaticProperty(name)") PhpRef ref) {
            return ref;
        }

        @Specialization(replaces = "cached")
        protected PhpRef uncached(PhpClass phpClass) {
            return phpClass.getStaticProperty(name);
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }
    }
}
