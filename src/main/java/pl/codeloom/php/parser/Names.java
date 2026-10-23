package pl.codeloom.php.parser;

import java.util.HashMap;
import java.util.Map;

/**
 * The namespace and `use` imports in effect at a point of a file, and PHP's rules for resolving a name written
 * there. Immutable: a namespace or use statement makes a new one, so a function body translated later resolves
 * names as at its declaration.
 */
final class Names {
    /** php-ast AST_NAME flags. */
    static final int NAME_FQ = 0;
    static final int NAME_RELATIVE = 2;
    /** php-ast AST_USE flags. */
    static final int USE_FUNCTION = 2;
    static final int USE_CONST = 4;

    static final Names GLOBAL = new Names("", Map.of(), Map.of(), Map.of());

    private final String namespace;
    /** Lowercase alias to fully qualified name: classes, and namespaces for qualified names. */
    private final Map<String, String> classes;
    /** Lowercase alias to fully qualified name. */
    private final Map<String, String> functions;
    /** Alias (case-sensitive) to fully qualified name. */
    private final Map<String, String> constants;

    private Names(String namespace, Map<String, String> classes, Map<String, String> functions,
                  Map<String, String> constants) {
        this.namespace = namespace;
        this.classes = classes;
        this.functions = functions;
        this.constants = constants;
    }

    /** `namespace Name;` or `namespace Name { }` (null or "" for the global namespace): imports start over. */
    static Names namespace(String name) {
        return new Names(name == null ? "" : name, Map.of(), Map.of(), Map.of());
    }

    String namespace() {
        return namespace;
    }

    /** With `use [function|const] target as alias` (alias null: the target's last segment). */
    Names use(int kind, String target, String alias) {
        String name = stripSlash(target);
        String as = alias != null ? alias : name.substring(name.lastIndexOf('\\') + 1);
        if (kind == USE_FUNCTION) {
            return new Names(namespace, classes, with(functions, as.toLowerCase(), name), constants);
        }
        if (kind == USE_CONST) {
            return new Names(namespace, classes, functions, with(constants, as, name));
        }
        return new Names(namespace, with(classes, as.toLowerCase(), name), functions, constants);
    }

    private static Map<String, String> with(Map<String, String> map, String key, String value) {
        Map<String, String> copy = new HashMap<>(map);
        copy.put(key, value);
        return Map.copyOf(copy);
    }

    /** A name declared here (function, class, constant): prefixed with the namespace. */
    String declared(String name) {
        return namespace.isEmpty() ? name : namespace + "\\" + name;
    }

    /** A class name as written (self, parent and static stay as they are). */
    String className(String text, int flags) {
        String name = stripSlash(text);
        if (flags == NAME_FQ) {
            return name;
        }
        if (flags == NAME_RELATIVE) {
            return declared(name);
        }
        String lower = name.toLowerCase();
        if (lower.equals("self") || lower.equals("parent") || lower.equals("static")) {
            return name;
        }
        return qualified(name);
    }

    /**
     * A function or constant name as written: the fully qualified name, and for an unqualified name in a
     * namespace the global name PHP falls back to at run time (else null).
     */
    String[] functionName(String text, int flags) {
        return symbolName(text, flags, functions, true);
    }

    String[] constantName(String text, int flags) {
        return symbolName(text, flags, constants, false);
    }

    private String[] symbolName(String text, int flags, Map<String, String> imports, boolean caseless) {
        String name = stripSlash(text);
        if (flags == NAME_FQ) {
            return new String[] {name, null};
        }
        if (flags == NAME_RELATIVE) {
            return new String[] {declared(name), null};
        }
        if (name.indexOf('\\') >= 0) {
            return new String[] {qualified(name), null};
        }
        String imported = imports.get(caseless ? name.toLowerCase() : name);
        if (imported != null) {
            return new String[] {imported, null};
        }
        return namespace.isEmpty() ? new String[] {name, null} : new String[] {declared(name), name};
    }

    /** A class name or a qualified name: its first segment may be an imported alias, else it is in namespace. */
    private String qualified(String name) {
        int separator = name.indexOf('\\');
        String first = separator < 0 ? name : name.substring(0, separator);
        String imported = classes.get(first.toLowerCase());
        if (imported != null) {
            return separator < 0 ? imported : imported + name.substring(separator);
        }
        return declared(name);
    }

    private static String stripSlash(String name) {
        return name.startsWith("\\") ? name.substring(1) : name;
    }
}
