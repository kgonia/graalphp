package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.SubclassTestNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A declared type (php-src's zend_type) of a parameter, return value or property: the built-in types as a mask,
 * the class names of a union, and its intersections (DNF). self and parent are resolved where the type is
 * declared; static is the called class. iterable is Traversable|array, as php-src compiles it.
 */
public final class PhpType {
    public static final int NULL = 1;
    public static final int FALSE = 2;
    public static final int TRUE = 4;
    public static final int INT = 8;
    public static final int FLOAT = 16;
    public static final int STRING = 32;
    public static final int ARRAY = 64;
    public static final int OBJECT = 128;
    public static final int RESOURCE = 256;
    public static final int CALLABLE = 512;
    public static final int STATIC = 1024;
    public static final int VOID = 2048;
    public static final int NEVER = 4096;
    public static final int BOOL = FALSE | TRUE;
    public static final int SCALAR = BOOL | INT | FLOAT | STRING;
    /** mixed: any value (MAY_BE_ANY). */
    public static final int MIXED = NULL | SCALAR | ARRAY | OBJECT | RESOURCE;

    public final int mask;
    /** The union's single classes, as written (resolved) and lowercase. */
    private final String[] names;
    @CompilationFinal(dimensions = 1) private final String[] classes;
    /** The union's intersections (or the whole type's), each a list of class names as written. */
    private final String[][] intersections;
    /** The classes and the bracketed intersections as zend_type_to_string writes them, in declaration order. */
    private final String[] classText;

    public PhpType(int mask, String[] names, String[][] intersections, String[] classText) {
        this.mask = mask;
        this.names = names;
        this.classes = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            classes[i] = Str.lower(names[i]);
        }
        this.intersections = intersections;
        this.classText = classText;
    }

    /** The type with null added (?T). */
    public PhpType withNull() {
        return new PhpType(mask | NULL, names, intersections, classText);
    }

    /** The union's single classes, as written. */
    public String[] classNames() {
        return names;
    }

    public String[][] intersections() {
        return intersections;
    }

    /** A type that is one intersection (A&B). */
    public boolean isIntersection() {
        return names.length == 0 && intersections.length == 1 && (mask & ~NULL) == 0;
    }

    public boolean has(int bits) {
        return (mask & bits) != 0;
    }

    public boolean hasClasses() {
        return classes.length > 0 || intersections.length > 0;
    }

    public boolean isMixed() {
        return (mask & MIXED) == MIXED;
    }

    /** The value as the type takes it with no coercion (an int for float as a float), or null: see {@link #slow}. */
    public Object fast(Object value) {
        if (value instanceof Long l) {
            return (mask & INT) != 0 ? value : (mask & FLOAT) != 0 ? (Object) (double) l : null;
        }
        if ((mask & bit(value)) != 0) {
            return value;
        }
        if (value instanceof PhpClosure && hasClass("closure")) {
            return value;
        }
        return null;
    }

    /** {@link #fast} for an object, its class tested against the union's classes by key. */
    @ExplodeLoop
    public boolean acceptsObject(PhpClass phpClass, SubclassTestNode test) {
        if ((mask & OBJECT) != 0) {
            return true;
        }
        for (String name : classes) {
            if (test.execute(phpClass, name)) {
                return true;
            }
        }
        return false;
    }

    @ExplodeLoop
    private boolean hasClass(String name) {
        for (String c : classes) {
            if (c.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int bit(Object value) {
        if (value instanceof TruffleString) {
            return STRING;
        }
        if (value instanceof Double) {
            return FLOAT;
        }
        if (value instanceof Boolean b) {
            return b ? TRUE : FALSE;
        }
        if (value == PhpNull.NULL) {
            return NULL;
        }
        if (value instanceof PhpArray) {
            return ARRAY;
        }
        if (value instanceof PhpObject || value instanceof PhpClosure) {
            return OBJECT;
        }
        return RESOURCE;
    }

    /**
     * What {@link #fast} leaves (zend_check_type_slow): intersections, callable and static, then in coercive mode
     * (not strict) the scalar conversions, int before float before string before bool. Null when the value fails;
     * a conversion that loses a fraction is deprecated first. calledClass is static's class, or null.
     */
    @TruffleBoundary
    public Object slow(Object value, boolean strict, PhpClass calledClass, Node site) {
        Object fast = fast(value);
        if (fast != null) {
            return fast;
        }
        if (value instanceof PhpObject object) {
            PhpClass phpClass = object.getPhpClass();
            for (String name : classes) {
                if (phpClass.isSubclassOf(name)) {
                    return value;
                }
            }
            for (String[] intersection : intersections) {
                boolean all = true;
                for (String name : intersection) {
                    all &= phpClass.isSubclassOf(Str.lower(name));
                }
                if (all) {
                    return value;
                }
            }
            if ((mask & STATIC) != 0 && calledClass != null && phpClass.isSubclassOf(calledClass.getLowerName())) {
                return value;
            }
        }
        if (value instanceof PhpClosure closure) {
            for (String[] intersection : intersections) {
                if (intersection.length == 1 && intersection[0].equalsIgnoreCase("closure")) {
                    return value;
                }
            }
            if ((mask & STATIC) != 0 && calledClass != null && calledClass.getLowerName().equals("closure")) {
                return value;
            }
        }
        if ((mask & CALLABLE) != 0 && Callables.resolve(PhpContext.get(null), value) != null) {
            return value;
        }
        if (strict || value == PhpNull.NULL || (mask & SCALAR) == 0) {
            return null;
        }
        return weak(value, site);
    }

    /** zend_verify_weak_scalar_type_hint. */
    private Object weak(Object value, Node site) {
        if ((mask & INT) != 0) {
            if ((mask & FLOAT) != 0 && value instanceof TruffleString s) {
                Object number = Convert.numericValue(s);
                if (number != null) {
                    return number;
                }
            } else {
                Long l = weakLong(value, site);
                if (l != null) {
                    return l;
                }
            }
        }
        if ((mask & FLOAT) != 0) {
            if (value instanceof Boolean b) {
                return b ? 1.0 : 0.0;
            }
            if (value instanceof TruffleString s && Convert.numericValue(s) instanceof Number number) {
                return number.doubleValue();
            }
        }
        if ((mask & STRING) != 0) {
            if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
                return Convert.toStr(value);
            }
            if (value instanceof PhpObject object && object.getPhpClass().findMethod("__tostring") != null) {
                return object.toPhpString();
            }
        }
        if ((mask & BOOL) == BOOL && (value instanceof Long || value instanceof Double
                || value instanceof TruffleString)) {
            return Convert.toBool(value);
        }
        return null;
    }

    /** zend_parse_arg_long_weak: a float or float-string with a fraction converts, deprecated. */
    private static Long weakLong(Object value, Node site) {
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        TruffleString string = value instanceof TruffleString s ? s : null;
        Object number = string != null ? Convert.numericValue(string) : value;
        if (number instanceof Long l) {
            return l;
        }
        if (!(number instanceof Double d) || !(d >= -9.223372036854775808E18 && d < 9.223372036854775807E18)) {
            return null;
        }
        long l = (long) (double) d;
        if (l != d) {
            String message = string != null
                    ? "Implicit conversion from float-string \"" + Str.java(string) + "\" to int loses precision"
                    : "Implicit conversion from float " + Convert.doubleToRepr(d) + " to int loses precision";
            ErrorFunctions.raise(PhpContext.get(null), ErrorFunctions.E_DEPRECATED, Str.of(message),
                    site == null ? SourceLocation.current() : SourceLocation.of(site));
        }
        return l;
    }

    /**
     * zend_verify_property_type: the value as the property takes it (converted in coercive mode), or the
     * TypeError.
     */
    @TruffleBoundary
    public Object assign(Object value, boolean strict, PhpClass.PropertyDecl decl) {
        Object accepted = slow(value, strict, null, null);
        if (accepted == null) {
            throw new PhpError("TypeError", "Cannot assign " + valueName(value) + " to property "
                    + declaringName(decl) + "::$" + Str.keyText(decl.name()) + " of type " + text(null));
        }
        return accepted;
    }

    /** A class constant's value checked in strict mode: what is "class constant C::X". */
    @TruffleBoundary
    public Object assignTo(Object value, String what) {
        Object accepted = slow(value, true, null, null);
        if (accepted == null) {
            throw new PhpError("TypeError", "Cannot assign " + valueName(value) + " to " + what + " of type "
                    + text(null));
        }
        return accepted;
    }

    /** The name of the class that declared the property, as written. */
    @TruffleBoundary
    public static String declaringName(PhpClass.PropertyDecl decl) {
        PhpClass declaring = PhpContext.get(null).findClass(decl.declaringClass());
        String name = declaring != null ? declaring.getName() : decl.declaringClass();
        // %s stops at an anonymous class's NUL
        return name.split("\0", 2)[0];
    }

    /** zend_type_to_string_resolved: static as the called class (if any), null as ?T for a single type. */
    @TruffleBoundary
    public String text(PhpClass calledClass) {
        if (isMixed()) {
            return "mixed";
        }
        List<String> parts = new ArrayList<>(List.of(classText));
        if ((mask & STATIC) != 0) {
            parts.add(calledClass == null ? "static" : calledClass.getName());
        }
        String[] keywords = {"callable", "object", "array", "string", "int", "float"};
        int[] bits = {CALLABLE, OBJECT, ARRAY, STRING, INT, FLOAT};
        for (int i = 0; i < bits.length; i++) {
            if ((mask & bits[i]) != 0) {
                parts.add(keywords[i]);
            }
        }
        if ((mask & BOOL) == BOOL) {
            parts.add("bool");
        } else if ((mask & FALSE) != 0) {
            parts.add("false");
        } else if ((mask & TRUE) != 0) {
            parts.add("true");
        }
        if ((mask & VOID) != 0) {
            parts.add("void");
        }
        if ((mask & NEVER) != 0) {
            parts.add("never");
        }
        // an anonymous class is "class@anonymous"
        String text = String.join("|", parts).replaceAll("\0[^|&)]*", "");
        if ((mask & NULL) == 0) {
            return text;
        }
        if (parts.isEmpty()) {
            return "null";
        }
        if (text.indexOf('|') < 0 && text.indexOf('&') >= 0 && text.charAt(0) != '(') {
            return "(" + text + ")|null";
        }
        return text.indexOf('|') < 0 && text.indexOf('&') < 0 ? "?" + text : text + "|null";
    }

    /** zend_zval_value_name: an object's class, true and false by value, else the type's name. */
    @TruffleBoundary
    public static String valueName(Object value) {
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof PhpResource) {
            return "resource";
        }
        return Convert.debugType(value);
    }
}
