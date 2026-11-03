package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
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

    /** lexicalClass: the class the code is written in (lowercase) or null; scopeInFrame as PropNode's. */
    public StaticPropNode(ClassRefNode classRef, String name, String lexicalClass, boolean scopeInFrame) {
        this.classRef = classRef;
        this.name = name;
        this.lookup = Lookup.create(name, lexicalClass, scopeInFrame);
    }

    private PhpRef ref(VirtualFrame frame) {
        PhpClass phpClass = classRef.resolve(frame);
        return declared(lookup.execute(frame, phpClass, false), phpClass, name);
    }

    /** The class's reference for the property (Lookup's), or php-src's Error when the class has none. */
    public static PhpRef declared(PhpRef ref, PhpClass phpClass, String name) {
        if (ref == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Access to undeclared static property " + phpClass.getName() + "::$"
                    + Str.keyText(name));
        }
        return ref;
    }

    /** ZEND_UNSET_STATIC_PROP: a static property can't be unset, declared or not. */
    @TruffleBoundary
    public static PhpError unsetError(PhpClass phpClass, String name) {
        return new PhpError("Attempt to unset static property " + phpClass.getName() + "::$" + Str.keyText(name));
    }

    /** `Cls::$name = &ref`: the declared property (else its Error) bound to ref (PhpClass.bindStaticProperty). */
    @TruffleBoundary
    public static void bind(Node site, PhpClass phpClass, String name, PhpRef ref) {
        declared(phpClass.getStaticProperty(name), phpClass, name);
        phpClass.bindStaticProperty(PhpContext.get(site), name, ref);
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
        bind(this, classRef.resolve(frame), name, ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        return Places.refArrayForWrite(ref(frame));
    }

    @Override
    public void unset(VirtualFrame frame) {
        PhpClass phpClass = classRef.resolve(frame);
        CompilerDirectives.transferToInterpreter();
        throw unsetError(phpClass, name);
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        PhpRef ref = lookup.execute(frame, classRef.resolve(frame), true);
        return ref != null && ref.get() != PhpNull.NULL;
    }

    @Override
    public boolean isPure() {
        return true;
    }

    /**
     * The class's reference for the property, or null if it has none. A class's static properties are made when
     * it is declared and its initializers are evaluated on first access, so while the language serves one context
     * a site keeps the reference it found per class as a compile-time constant, and whether its code may access it:
     * a public property, or a non-public one from the lexical class, needs no check per access.
     */
    public abstract static class Lookup extends Node {
        static final int LIMIT = 3;

        protected final String name;
        protected final String lexicalClass;
        protected final boolean scopeInFrame;

        Lookup(String name, String lexicalClass, boolean scopeInFrame) {
            this.name = name;
            this.lexicalClass = lexicalClass;
            this.scopeInFrame = scopeInFrame;
        }

        /** A site's own lookup (the bytecode's static property instructions keep one each). */
        public static Lookup create(String name, String lexicalClass, boolean scopeInFrame) {
            return StaticPropNodeFactory.LookupNodeGen.create(name, lexicalClass, scopeInFrame);
        }

        /**
         * The reference, null for a class without the property; one the calling scope may not access is php-src's
         * Error, or null when quiet (isset(), empty(), `??`: BP_VAR_IS).
         */
        public abstract PhpRef execute(VirtualFrame frame, PhpClass phpClass, boolean quiet);

        @Specialization(guards = "phpClass == cachedClass", limit = "LIMIT",
                assumptions = {"singleContext()", "staticPropertyRefs()"})
        protected PhpRef cached(VirtualFrame frame, PhpClass phpClass, boolean quiet,
                                @Cached("phpClass") PhpClass cachedClass,
                                @Cached("cachedClass.getStaticProperty(name)") PhpRef ref,
                                @Cached("allowed(cachedClass)") boolean allowed) {
            return allowed ? ref : checked(frame, phpClass, ref, name, lexicalClass, scopeInFrame, quiet);
        }

        @Specialization(replaces = "cached")
        protected PhpRef uncached(VirtualFrame frame, PhpClass phpClass, boolean quiet) {
            return checked(frame, phpClass, phpClass.getStaticProperty(name), name, lexicalClass, scopeInFrame, quiet);
        }

        /** Whether every access from here may use the property: a public one, or the lexical class may. */
        protected boolean allowed(PhpClass phpClass) {
            return !phpClass.isRestrictedStatic(name) || !scopeInFrame && phpClass.canAccessStatic(name, lexicalClass);
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }

        protected Assumption staticPropertyRefs() {
            return PhpLanguage.get(this).staticPropertyRefs();
        }
    }

    /** The reference if the calling scope may access it (Lookup's check, and the uncached interpreter's). */
    public static PhpRef checked(VirtualFrame frame, PhpClass phpClass, PhpRef ref, String name, String lexicalClass,
                                 boolean scopeInFrame, boolean quiet) {
        if (ref == null || !phpClass.isRestrictedStatic(name) || phpClass.canAccessStatic(name,
                scopeInFrame ? PropNode.callingScope(frame, lexicalClass) : lexicalClass)) {
            return ref;
        }
        if (quiet) {
            return null;
        }
        CompilerDirectives.transferToInterpreter();
        throw phpClass.inaccessibleStatic(name);
    }
}
