package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
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
    @CompilationFinal private PhpClass singleContextClass;
    /** The lowercase name's PhpLanguage.classId, resolved on first use. */
    @CompilationFinal private int id = -1;

    private ClassRefNode(String name, boolean lateStatic, ExprNode dynamic) {
        this.name = name;
        this.lateStatic = lateStatic;
        this.dynamic = dynamic;
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
        PhpClass phpClass = singleContextClass;
        if (phpClass != null && PhpLanguage.get(this).singleContext().isValid()) {
            return phpClass;
        }
        if (id < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            id = PhpLanguage.get(this).classId(name.toLowerCase());
        }
        phpClass = PhpContext.get(this).classAt(id);
        if (phpClass == null) {
            CompilerDirectives.transferToInterpreter();
            phpClass = PhpContext.get(this).getClass(name); // throws "not found"
        }
        if (singleContextClass == null && PhpLanguage.get(this).singleContext().isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            singleContextClass = phpClass;
        }
        return phpClass;
    }
}
