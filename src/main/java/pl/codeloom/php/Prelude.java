package pl.codeloom.php;

import pl.codeloom.php.nodes.FileRootNode;
import pl.codeloom.php.parser.Ast;
import pl.codeloom.php.parser.AstReader;
import pl.codeloom.php.parser.Translator;
import pl.codeloom.php.runtime.PhpError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The built-in classes written in PHP (src/main/php/prelude): SPL's iterators and data structures. Their AST
 * exports and index are classpath resources (tools/export-prelude.sh). A file is translated once per language and
 * run in a context the first time that context looks up one of its classes.
 */
final class Prelude {
    private static final String RESOURCES = "/pl/codeloom/php/prelude/";

    /** Lower-case class name to the prelude file declaring it. */
    private final Map<String, String> classes = new HashMap<>();

    Prelude() {
        try (InputStream in = Prelude.class.getResourceAsStream(RESOURCES + "classes.txt")) {
            if (in == null) {
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] parts = line.split("\t");
                if (parts.length == 2) {
                    classes.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("reading the prelude index failed", e);
        }
    }

    /** Every prelude file, each once. */
    java.util.Collection<String> files() {
        return new java.util.TreeSet<>(classes.values());
    }

    /** The prelude file declaring the class, or null if it isn't a prelude class. */
    String fileOf(String lowerName) {
        return classes.get(lowerName);
    }

    /** The file's path as the runtime names it (in errors, __FILE__): "prelude/spl.php". */
    static String path(String file) {
        return pl.codeloom.php.runtime.SourceLocation.PRELUDE + file;
    }

    static FileRootNode translate(PhpLanguage language, String file) {
        Ast ast;
        try (InputStream in = Prelude.class.getResourceAsStream(RESOURCES + file + ".json")) {
            if (in == null) {
                throw new PhpError("prelude file " + file + " has no AST export");
            }
            ast = AstReader.read(in);
        } catch (IOException e) {
            throw new PhpError("reading prelude file " + file + " failed: " + e.getMessage());
        }
        return new Translator(language, path(file), null).translateFile(ast);
    }
}
