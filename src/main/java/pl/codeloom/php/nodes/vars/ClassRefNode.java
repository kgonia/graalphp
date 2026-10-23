package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

/**
 * Resolves a class reference: a name (self and parent are resolved to names by the translator), `static`
 * (the late-static-binding class passed in the frame), or an expression giving a name or an object.
 * A named class is looked up in the calling context by its name's language-level id; while the language serves
 * one context the class found first is a compile-time constant (classes can't be redeclared).
 */
public final class ClassRefNode extends Node {
    private final String name;
    private final boolean lateStatic;
    @Child private ExprNode dynamic;
    @Child private Named named;

    private ClassRefNode(String name, boolean lateStatic, ExprNode dynamic) {
        this.name = name;
        this.lateStatic = lateStatic;
        this.dynamic = dynamic;
        this.named = name == null ? null : ClassRefNodeFactory.NamedNodeGen.create(name);
    }

    public static ClassRefNode named(String name) {
        return new ClassRefNode(name, false, null);
    }

    /** `static::`; selfName is used when no late-static-binding class was passed (plain function context). */
    public static ClassRefNode lateStatic(String selfName) {
        return new ClassRefNode(selfName, true, null);
    }

    public static ClassRefNode dynamic(ExprNode expression) {
        return new ClassRefNode(null, false, expression);
    }

    public String getName() {
        return name;
    }

    public PhpClass resolve(VirtualFrame frame) {
        if (lateStatic && frame.getArguments()[PhpFunction.ARG_STATIC] instanceof PhpClass phpClass) {
            return phpClass;
        }
        if (dynamic != null) {
            Object value = dynamic.execute(frame);
            if (value instanceof PhpObject object) {
                return object.getPhpClass();
            }
            return PhpContext.get(this).getClass(Str.java(Convert.toStr(value)));
        }
        return named.execute();
    }

    /** A class by name: with one context the class found first, else the calling context's by name id. */
    abstract static class Named extends Node {
        protected final String name;

        Named(String name) {
            this.name = name;
        }

        abstract PhpClass execute();

        @Specialization(assumptions = "singleContext()")
        protected PhpClass single(@Cached("find(classId())") PhpClass phpClass) {
            return phpClass;
        }

        @Specialization(replaces = "single")
        protected PhpClass perContext(@Cached("classId()") int id) {
            return find(id);
        }

        /** The class under the name's id; else (not declared yet) by name, which autoloads or throws. */
        protected PhpClass find(int id) {
            PhpContext context = PhpContext.get(this);
            PhpClass phpClass = context.classAt(id);
            if (phpClass == null) {
                CompilerDirectives.transferToInterpreter();
                phpClass = context.getClass(name); // throws "not found"
            }
            return phpClass;
        }

        protected int classId() {
            return PhpLanguage.get(this).classId(name.toLowerCase());
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }
    }
}
