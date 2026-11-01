package pl.codeloom.php.ffi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Native calls go through pl.codeloom.php.ffi only (wiki/native-calls.md): a handle made elsewhere would be made
 * at run time in a native image, with no registration, and every call would take the generic method-handle path.
 */
class NativeCallsTest {
    private static final Pattern RAW_FFM = Pattern.compile(
            "\\.downcallHandle\\(|\\.upcallStub\\(|SymbolLookup\\.libraryLookup\\(|\\.defaultLookup\\(\\)");

    @Test
    void noBindingOutsideTheHelper() throws IOException {
        Path sources = Path.of("src/main/java");
        Path helper = sources.resolve("pl/codeloom/php/ffi");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (file.startsWith(helper)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (RAW_FFM.matcher(lines.get(i)).find()) {
                        found.add(sources.relativize(file) + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        assertEquals(List.of(), found, "FFM bindings outside pl.codeloom.php.ffi (use NativeLibrary, Upcall)");
    }

    @Test
    void theFeatureNamesExistingClasses() {
        List<String> names = new ArrayList<>(NativeCallsFeature.BINDINGS);
        names.addAll(NativeCallsFeature.NATIVE_STATE);
        for (String name : names) {
            assertDoesNotThrow(() -> Class.forName(name, false, NativeCallsTest.class.getClassLoader()), name);
        }
    }
}
