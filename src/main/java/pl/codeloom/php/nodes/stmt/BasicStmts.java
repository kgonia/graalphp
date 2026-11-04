package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpException;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpObject;

/** Straight-line statements. */
public final class BasicStmts {
    private BasicStmts() {
    }

    public static final class EchoNode {
        /** Writes a value as a string to the output of site's context. */
        public static void echo(Node site, Object value) {
            TruffleString text = value instanceof TruffleString s ? s : Convert.toStr(site, value);
            PhpContext.get(site).write(text, site);
        }
    }

    public static final class ThrowNode {
        /** ZEND_THROW: a Throwable object goes up as a PHP exception; anything else is an Error. */
        public static void throwValue(Object thrown) {
            if (thrown instanceof PhpObject object && object.getPhpClass().isSubclassOf("throwable")) {
                throw new PhpException(object);
            }
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Can only throw objects");
        }
    }

    /** Runs an expression for its side effect in statement position, e.g. a hoisted declaration's runtime part. */
    /** Constructor property promotion: `$this->name = $name` before the constructor body runs. */
    public static final class PromoteNode {
        /** As `$this->name = $name` (ZEND_ASSIGN_OBJ): a hooked property's set hook takes it. */
        @TruffleBoundary
        public static void store(PhpObject self, String name, Object value) {
            PhpClass.Hooks hooks = self.getPhpClass().findHooks(name);
            if (hooks != null && hooks.set() != null) {
                hooks.set().invoke(self, self.getPhpClass(), value);
                return;
            }
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            // an uninitialized (readonly) property is put with its flags
            if (!DynamicObjectLibrary.getUncached().putIfPresent(self, name, value)) {
                self.initProperty(name, value);
            }
        }
    }
}
