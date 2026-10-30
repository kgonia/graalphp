package pl.codeloom.php.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.frame.FrameDescriptor;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.PhpFunction;

class PhpBytecodeRootNodeTest {
    @Test
    void addsArguments() {
        try (Context context = Context.newBuilder(PhpLanguage.ID).build()) {
            context.initialize(PhpLanguage.ID);
            context.enter();
            try {
                var language = PhpLanguage.get(null);
                var root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot();
                    b.beginReturn();
                    b.beginAdd();
                    b.emitLoadArgument(PhpFunction.ARG_FIRST);
                    b.emitLoadArgument(PhpFunction.ARG_FIRST + 1);
                    b.endAdd();
                    b.endReturn();
                    b.endRoot();
                }).getNode(0);
                RootCallTarget target = root.getCallTarget();
                assertEquals(5L, target.call((Object) null, null, null, 2L, 3L));
                assertEquals(Long.MAX_VALUE + 1.0, target.call((Object) null, null, null, Long.MAX_VALUE, 1L));
                assertEquals(2.5, target.call((Object) null, null, null, 1L, 1.5));
                for (int i = 0; i < 100; i++) {
                    target.call((Object) null, null, null, (long) i, 1L);
                }
                System.out.println(root.dump());
            } finally {
                context.leave();
            }
        }
    }
}
