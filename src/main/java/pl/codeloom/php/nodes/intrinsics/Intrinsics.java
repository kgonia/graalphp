package pl.codeloom.php.nodes.intrinsics;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

import java.util.Set;

/**
 * Built-ins compiled in place: the translator emits these nodes instead of a call for small, hot built-ins, so
 * partial evaluation sees through them (no argument array, no boundary, no boxing). PHP forbids redeclaring a
 * built-in, so binding at translation time is safe. Forms these nodes don't cover (other arities, spreads) are
 * left to the regular built-in.
 */
public final class Intrinsics {
    private Intrinsics() {
    }

    private static final Set<String> NAMES = Set.of("is_array", "is_string", "is_int", "is_integer", "is_long",
            "is_float", "is_double", "is_bool", "is_null", "is_object", "count", "sizeof", "strlen", "current",
            "pos", "key", "next", "prev", "reset", "end", "ord", "chr", "substr", "strpos", "str_contains",
            "str_starts_with", "str_ends_with", "array_key_exists", "key_exists", "gettype", "strspn", "strcspn");

    private static final Set<String> BY_REF_FIRST = Set.of("next", "prev", "reset", "end");

    public static boolean handles(String lowerName) {
        return NAMES.contains(lowerName);
    }

    /** Whether argument i must be a place (by-reference parameter). */
    public static boolean byRef(String lowerName, int index) {
        return index == 0 && BY_REF_FIRST.contains(lowerName);
    }

    /** The node for `name(args)`, or null when this arity or argument shape isn't covered. */
    public static ExprNode create(String lowerName, ExprNode[] args) {
        int n = args.length;
        switch (lowerName) {
            case "is_array":
                return n == 1 ? new TypeTestNode(args[0], TypeTestNode.ARRAY) : null;
            case "is_string":
                return n == 1 ? new TypeTestNode(args[0], TypeTestNode.STRING) : null;
            case "is_int":
            case "is_integer":
            case "is_long":
                return n == 1 ? new TypeTestNode(args[0], TypeTestNode.INT) : null;
            case "is_float":
            case "is_double":
                return n == 1 ? new TypeTestNode(args[0], TypeTestNode.FLOAT) : null;
            case "is_bool":
                return n == 1 ? new TypeTestNode(args[0], TypeTestNode.BOOL) : null;
            case "is_null":
                return n == 1 ? new TypeTestNode(args[0], TypeTestNode.NULL) : null;
            case "is_object":
                return n == 1 ? new TypeTestNode(args[0], TypeTestNode.OBJECT) : null;
            case "count":
            case "sizeof":
                return n == 1 ? new CountNode(args[0]) : null;
            case "strlen":
                return n == 1 ? new StrlenNode(args[0]) : null;
            case "current":
            case "pos":
                return n == 1 ? new CurrentNode(args[0], false) : null;
            case "key":
                return n == 1 ? new CurrentNode(args[0], true) : null;
            case "next":
            case "prev":
            case "reset":
            case "end":
                return n == 1 && args[0] instanceof LValueNode place ? new MoveNode(place, lowerName) : null;
            case "ord":
                return n == 1 ? new OrdNode(args[0]) : null;
            case "chr":
                return n == 1 ? new ChrNode(args[0]) : null;
            case "substr":
                return n == 2 || n == 3 ? new SubstrNode(args[0], args[1], n == 3 ? args[2] : null) : null;
            case "strpos":
                return n == 2 ? new StrposNode(args[0], args[1]) : null;
            case "str_contains":
                return n == 2 ? new ContainsNode(args[0], args[1], ContainsNode.CONTAINS) : null;
            case "str_starts_with":
                return n == 2 ? new ContainsNode(args[0], args[1], ContainsNode.STARTS) : null;
            case "str_ends_with":
                return n == 2 ? new ContainsNode(args[0], args[1], ContainsNode.ENDS) : null;
            case "array_key_exists":
            case "key_exists":
                return n == 2 ? new KeyExistsNode(args[0], args[1]) : null;
            case "gettype":
                return n == 1 ? new GettypeNode(args[0]) : null;
            case "strspn":
            case "strcspn":
                return n >= 2 && n <= 4 ? new SpanNode(args, lowerName.equals("strspn")) : null;
            default:
                return null;
        }
    }

    // ---- shared helpers ----

    /** A string argument: strings pass through, scalars convert (PHP's coercive mode), arrays are a TypeError. */
    static TruffleString string(Object value, String function, int position) {
        if (value instanceof TruffleString s) {
            return s;
        }
        return convertString(value, function, position);
    }

    @TruffleBoundary
    private static TruffleString convertString(Object value, String function, int position) {
        if (value instanceof PhpArray) {
            throw new PhpError("TypeError: " + function + "(): Argument #" + position + " must be of type string, "
                    + "array given");
        }
        return Convert.toStr(value);
    }

    static long integer(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        return convertInteger(value);
    }

    @TruffleBoundary
    private static long convertInteger(Object value) {
        return Convert.toLong(value);
    }

    // ---- nodes ----

    /** is_array() and friends. */
    static final class TypeTestNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        static final int ARRAY = 0;
        static final int STRING = 1;
        static final int INT = 2;
        static final int FLOAT = 3;
        static final int BOOL = 4;
        static final int NULL = 5;
        static final int OBJECT = 6;

        @Child private ExprNode value;
        private final int type;

        TypeTestNode(ExprNode value, int type) {
            this.value = value;
            this.type = type;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            Object v = value.execute(frame);
            return switch (type) {
                case ARRAY -> v instanceof PhpArray;
                case STRING -> v instanceof TruffleString;
                case INT -> v instanceof Long;
                case FLOAT -> v instanceof Double;
                case BOOL -> v instanceof Boolean;
                case NULL -> v == PhpNull.NULL;
                default -> v instanceof PhpObject || v instanceof PhpClosure;
            };
        }
    }

    static final class CountNode extends ExprNode {
        @Child private ExprNode value;

        CountNode(ExprNode value) {
            this.value = value;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeLong(frame);
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            Object v = value.execute(frame);
            if (v instanceof PhpArray array) {
                return array.size();
            }
            return countSlow(v);
        }

        @TruffleBoundary
        private static long countSlow(Object v) {
            if (v instanceof PhpObject object && object.getPhpClass().isSubclassOf("countable")) {
                return Convert.toLong(object.callMethod("count"));
            }
            throw new PhpError("TypeError: count(): Argument #1 ($value) must be of type Countable|array, "
                    + Convert.typeName(v) + " given");
        }
    }

    static final class StrlenNode extends ExprNode {
        @Child private ExprNode value;

        StrlenNode(ExprNode value) {
            this.value = value;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeLong(frame);
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            return string(value.execute(frame), "strlen", 1).byteLength(Str.ENC);
        }
    }

    /** current() / key(): the element or key at the internal pointer; false (current) or null (key) past the end. */
    static final class CurrentNode extends ExprNode {
        @Child private ExprNode value;
        private final boolean wantKey;

        CurrentNode(ExprNode value, boolean wantKey) {
            this.value = value;
            this.wantKey = wantKey;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (!(value.execute(frame) instanceof PhpArray array)) {
                return wantKey ? PhpNull.NULL : Boolean.FALSE;
            }
            int p = array.pointer();
            if (!array.isValidPosition(p)) {
                return wantKey ? PhpNull.NULL : Boolean.FALSE;
            }
            return wantKey ? array.keyAt(p) : array.valueAt(p);
        }
    }

    /** next/prev/reset/end on a place: separate the array (the pointer is part of it), move, return the value. */
    static final class MoveNode extends ExprNode {
        private static final int NEXT = 0;
        private static final int PREV = 1;
        private static final int RESET = 2;
        private static final int END = 3;

        @Child private LValueNode place;
        private final int how;

        MoveNode(LValueNode place, String name) {
            this.place = place;
            this.how = switch (name) {
                case "next" -> NEXT;
                case "prev" -> PREV;
                case "reset" -> RESET;
                default -> END;
            };
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (!(place.execute(frame) instanceof PhpArray)) {
                return Boolean.FALSE;
            }
            PhpArray array = place.arrayForWrite(frame);
            int p = array.pointer();
            switch (how) {
                case NEXT -> p = array.isValidPosition(p) ? array.nextPosition(p) : -1;
                case PREV -> p = array.isValidPosition(p) ? array.previousPosition(p) : -1;
                case RESET -> p = array.firstPosition();
                default -> p = array.lastPosition();
            }
            array.setPointer(p);
            return array.isValidPosition(p) ? array.valueAt(p) : Boolean.FALSE;
        }
    }

    static final class OrdNode extends ExprNode {
        @Child private ExprNode value;
        @Child private TruffleString.ReadByteNode readByte = TruffleString.ReadByteNode.create();

        OrdNode(ExprNode value) {
            this.value = value;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeLong(frame);
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            TruffleString s = string(value.execute(frame), "ord", 1);
            return s.byteLength(Str.ENC) == 0 ? 0 : readByte.execute(s, 0, Str.ENC);
        }
    }

    static final class ChrNode extends ExprNode {
        @Child private ExprNode value;

        ChrNode(ExprNode value) {
            this.value = value;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return Str.singleByte((int) Math.floorMod(integer(value.execute(frame)), 256L));
        }
    }

    /** substr(): a lazy view of the bytes (no copy), with php-src's offset and length rules. */
    static final class SubstrNode extends ExprNode {
        @Child private ExprNode string;
        @Child private ExprNode offset;
        @Child private ExprNode length;
        @Child private TruffleString.SubstringByteIndexNode substring = TruffleString.SubstringByteIndexNode.create();

        SubstrNode(ExprNode string, ExprNode offset, ExprNode length) {
            this.string = string;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            TruffleString s = string(string.execute(frame), "substr", 1);
            long from = integer(offset.execute(frame));
            Object lengthValue = length == null ? PhpNull.NULL : length.execute(frame);
            int n = s.byteLength(Str.ENC);
            long start = from < 0 ? Math.max(0, n + from) : from;
            if (start > n) {
                return Str.EMPTY;
            }
            long end;
            if (lengthValue == PhpNull.NULL) {
                end = n;
            } else {
                long len = integer(lengthValue);
                if (len < 0) {
                    end = n + len;
                    if (end < start) {
                        return Str.EMPTY;
                    }
                } else {
                    end = Math.min(n, start + len);
                }
            }
            if (start == 0 && end == n) {
                return s;
            }
            return substring.execute(s, (int) start, (int) (end - start), Str.ENC, true);
        }
    }

    /** strpos($haystack, $needle): the byte offset of the first occurrence, or false. */
    static final class StrposNode extends ExprNode {
        @Child private ExprNode haystack;
        @Child private ExprNode needle;
        @Child private TruffleString.ByteIndexOfStringNode indexOf = TruffleString.ByteIndexOfStringNode.create();

        StrposNode(ExprNode haystack, ExprNode needle) {
            this.haystack = haystack;
            this.needle = needle;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            TruffleString h = string(haystack.execute(frame), "strpos", 1);
            TruffleString n = string(needle.execute(frame), "strpos", 2);
            int index = n.byteLength(Str.ENC) == 0 ? 0 : indexOf.execute(h, n, 0, h.byteLength(Str.ENC), Str.ENC);
            return index < 0 ? (Object) Boolean.FALSE : (long) index;
        }
    }

    /** str_contains / str_starts_with / str_ends_with. */
    static final class ContainsNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        static final int CONTAINS = 0;
        static final int STARTS = 1;
        static final int ENDS = 2;

        @Child private ExprNode haystack;
        @Child private ExprNode needle;
        @Child private TruffleString.ByteIndexOfStringNode indexOf = TruffleString.ByteIndexOfStringNode.create();
        @Child private TruffleString.RegionEqualByteIndexNode regionEqual =
                TruffleString.RegionEqualByteIndexNode.create();
        private final int mode;
        private final String name;

        ContainsNode(ExprNode haystack, ExprNode needle, int mode) {
            this.haystack = haystack;
            this.needle = needle;
            this.mode = mode;
            this.name = mode == CONTAINS ? "str_contains" : mode == STARTS ? "str_starts_with" : "str_ends_with";
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            TruffleString h = string(haystack.execute(frame), name, 1);
            TruffleString n = string(needle.execute(frame), name, 2);
            int hl = h.byteLength(Str.ENC);
            int nl = n.byteLength(Str.ENC);
            if (nl == 0) {
                return true;
            }
            if (nl > hl) {
                return false;
            }
            return switch (mode) {
                case CONTAINS -> indexOf.execute(h, n, 0, hl, Str.ENC) >= 0;
                case STARTS -> regionEqual.execute(h, 0, n, 0, nl, Str.ENC);
                default -> regionEqual.execute(h, hl - nl, n, 0, nl, Str.ENC);
            };
        }
    }

    static final class KeyExistsNode extends ExprNode {
        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Child private ExprNode key;
        @Child private ExprNode container;

        KeyExistsNode(ExprNode key, ExprNode container) {
            this.key = key;
            this.container = container;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeBoolean(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            Object k = key.execute(frame);
            Object c = container.execute(frame);
            if (c instanceof PhpArray array) {
                return array.containsKey(k instanceof Long ? k : normalize(k));
            }
            return keyExistsSlow(k, c);
        }

        @TruffleBoundary
        private static Object normalize(Object k) {
            return ArrayKey.of(k);
        }

        @TruffleBoundary
        private static boolean keyExistsSlow(Object k, Object c) {
            if (c instanceof PhpObject object) {
                return object.getProperty(Str.java(Convert.toStr(k))) != null;
            }
            throw new PhpError("TypeError: array_key_exists(): Argument #2 ($array) must be of type array, "
                    + Convert.typeName(c) + " given");
        }
    }

    static final class GettypeNode extends ExprNode {
        private static final TruffleString INTEGER = Str.of("integer");
        private static final TruffleString DOUBLE = Str.of("double");
        private static final TruffleString STRING = Str.of("string");
        private static final TruffleString BOOLEAN = Str.of("boolean");
        private static final TruffleString ARRAY = Str.of("array");
        private static final TruffleString NULL = Str.of("NULL");
        private static final TruffleString OBJECT = Str.of("object");

        @Child private ExprNode value;

        GettypeNode(ExprNode value) {
            this.value = value;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object v = value.execute(frame);
            if (v instanceof Long) {
                return INTEGER;
            }
            if (v instanceof Double) {
                return DOUBLE;
            }
            if (v instanceof TruffleString) {
                return STRING;
            }
            if (v instanceof Boolean) {
                return BOOLEAN;
            }
            if (v instanceof PhpArray) {
                return ARRAY;
            }
            return v == PhpNull.NULL ? NULL : OBJECT;
        }
    }

    /**
     * strspn / strcspn: the length of the run at offset made of (or free of) mask bytes. Scans the subject's
     * bytes in place; a literal mask becomes a constant 256-entry table.
     */
    static final class SpanNode extends ExprNode {
        @Child private ExprNode subject;
        @Child private ExprNode mask;
        @Child private ExprNode offset;
        @Child private ExprNode length;
        @Child private TruffleString.GetInternalByteArrayNode bytes = TruffleString.GetInternalByteArrayNode.create();
        @CompilationFinal(dimensions = 1) private final boolean[] constantTable;
        private final boolean accept;
        private final String name;

        SpanNode(ExprNode[] args, boolean accept) {
            this.subject = args[0];
            this.accept = accept;
            this.name = accept ? "strspn" : "strcspn";
            this.offset = args.length > 2 ? args[2] : null;
            this.length = args.length > 3 ? args[3] : null;
            Object constant = args[1].constantValue();
            if (constant instanceof TruffleString maskString) {
                this.constantTable = table(maskString);
                this.mask = null;
            } else {
                this.constantTable = null;
                this.mask = args[1];
            }
        }

        @TruffleBoundary
        private static boolean[] table(TruffleString mask) {
            boolean[] set = new boolean[256];
            for (byte b : Str.readBytes(mask)) {
                set[b & 0xFF] = true;
            }
            return set;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeLong(frame);
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            TruffleString s = string(subject.execute(frame), name, 1);
            boolean[] set = constantTable != null ? constantTable : table(string(mask.execute(frame), name, 2));
            long from = offset == null ? 0 : integer(offset.execute(frame));
            Object lengthValue = length == null ? PhpNull.NULL : length.execute(frame);
            InternalByteArray view = bytes.execute(s, Str.ENC);
            int n = view.getLength();
            long start = from < 0 ? Math.max(0, n + from) : Math.min(from, n);
            long end;
            if (lengthValue == PhpNull.NULL) {
                end = n;
            } else {
                long len = integer(lengthValue);
                end = len < 0 ? Math.max(start, n + len) : Math.min(n, start + len);
            }
            byte[] array = view.getArray();
            int base = view.getOffset();
            int i = (int) start;
            int limit = (int) end;
            while (i < limit && set[array[base + i] & 0xFF] == accept) {
                i++;
            }
            return i - start;
        }
    }
}
