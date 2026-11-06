package pl.codeloom.php.nodes.expr;

import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

/** isset(), empty() and instanceof. */
public final class TestNodes {
    private TestNodes() {
    }

    /** `$x instanceof $class`: the class by name (a string) or as an object's class. */
    public static final class DynamicInstanceofNode {
        /** Whether value is an instance of the class target names (an object's class, or a name). */
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public static boolean test(Object value, Object target) {
            String lowerClassName;
            if (target instanceof PhpObject object) {
                lowerClassName = object.getPhpClass().getLowerName();
            } else if (target instanceof pl.codeloom.php.runtime.PhpClass phpClass) {
                // static, parent, a closure's self: the class the code resolved
                lowerClassName = phpClass.getLowerName();
            } else if (target instanceof com.oracle.truffle.api.strings.TruffleString name) {
                String text = name.toJavaStringUncached();
                lowerClassName = Str.lower(text.startsWith("\\") ? text.substring(1) : text);
            } else {
                throw new pl.codeloom.php.runtime.PhpError("Class name must be a valid object or a string");
            }
            if (value instanceof PhpObject object) {
                return object.getPhpClass().isSubclassOf(lowerClassName);
            }
            return value instanceof PhpClosure && lowerClassName.equals("closure");
        }
    }

}
