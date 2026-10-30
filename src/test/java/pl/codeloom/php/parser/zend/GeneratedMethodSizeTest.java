package pl.codeloom.php.parser.zend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.codeloom.php.parser.zend.generated.ZendLanguageParser;
import pl.codeloom.php.parser.zend.generated.ZendLanguageScanner;

/**
 * Every method the scanner and parser run per token or per reduction stays under HotSpot's HugeMethodLimit (8000
 * bytes of bytecode), above which the JIT never compiles a method: tools/parser/split_dfa.py and post_bison.py
 * split what re2c and Bison write as one method. Table initializers, which run once, are left out.
 */
class GeneratedMethodSizeTest {
    private static final int HUGE_METHOD_LIMIT = 8000;

    @Test
    void noHugeMethods() throws IOException {
        List<String> huge = new ArrayList<>();
        int methods = 0;
        for (Class<?> type : List.of(ZendLanguageScanner.class, ZendLanguageParser.class)) {
            ClassModel model;
            try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                model = ClassFile.of().parse(in.readAllBytes());
            }
            for (MethodModel method : model.methods()) {
                String name = method.methodName().stringValue();
                if (name.equals("<clinit>") || name.endsWith("_init") || name.matches(".*_init_\\d+")) {
                    continue;
                }
                methods++;
                int size = method.findAttribute(java.lang.classfile.Attributes.code())
                        .map(CodeAttribute::codeLength).orElse(0);
                if (size > HUGE_METHOD_LIMIT) {
                    huge.add(type.getSimpleName() + "." + name + ": " + size + " bytes");
                }
            }
        }
        assertTrue(methods > 20, "found " + methods + " methods");
        assertTrue(huge.isEmpty(), "methods the JIT won't compile: " + huge);
    }
}
