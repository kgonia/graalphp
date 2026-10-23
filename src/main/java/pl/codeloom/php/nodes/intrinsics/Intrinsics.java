package pl.codeloom.php.nodes.intrinsics;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.expr.ConstNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

import java.util.Arrays;
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
            "str_starts_with", "str_ends_with", "array_key_exists", "key_exists", "gettype", "strspn", "strcspn",
            "strtolower", "strtoupper", "trim", "ltrim", "rtrim", "chop", "ucfirst", "str_replace", "in_array",
            "array_pop", "array_push", "array_slice", "array_unshift", "array_keys", "implode", "join", "explode",
            "array_filter", "array_map");

    private static final Set<String> BY_REF_FIRST = Set.of("next", "prev", "reset", "end", "array_pop",
            "array_push", "array_unshift");

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
                return n == 1 ? BuiltinNodesFactory.CountNodeGen.create(args) : null;
            case "strlen":
                return n == 1 ? BuiltinNodesFactory.StrlenNodeGen.create(args) : null;
            case "current":
            case "pos":
                return n == 1 ? new CurrentNode(args[0], false) : null;
            case "key":
                return n == 1 ? new CurrentNode(args[0], true) : null;
            case "next":
            case "prev":
            case "reset":
            case "end":
                return n == 1 && writable(args[0]) ? new MoveNode((LValueNode) args[0], lowerName) : null;
            case "ord":
                return n == 1 ? BuiltinNodesFactory.OrdNodeGen.create(args) : null;
            case "chr":
                return n == 1 ? new ChrNode(args[0]) : null;
            case "substr":
                return n == 2 || n == 3
                        ? BuiltinNodesFactory.SubstrNodeGen.create(withDefaults(args, 2, PhpNull.NULL)) : null;
            case "strpos":
                return n == 2 ? BuiltinNodesFactory.StrposNodeGen.create(args) : null;
            case "str_contains":
                return n == 2 ? BuiltinNodesFactory.ContainsNodeGen.create(BuiltinNodes.ContainsNode.CONTAINS, args) : null;
            case "str_starts_with":
                return n == 2 ? BuiltinNodesFactory.ContainsNodeGen.create(BuiltinNodes.ContainsNode.STARTS, args) : null;
            case "str_ends_with":
                return n == 2 ? BuiltinNodesFactory.ContainsNodeGen.create(BuiltinNodes.ContainsNode.ENDS, args) : null;
            case "array_key_exists":
            case "key_exists":
                return n == 2 ? BuiltinNodesFactory.KeyExistsNodeGen.create(args) : null;
            case "gettype":
                return n == 1 ? new GettypeNode(args[0]) : null;
            case "strspn":
            case "strcspn":
                return n >= 2 && n <= 4 ? new SpanNode(args, lowerName.equals("strspn")) : null;
            // DSL nodes (D10 step 2): missing optional arguments become their default values
            case "strtolower":
            case "strtoupper":
                return n == 1 ? BuiltinNodesFactory.CaseNodeGen.create(lowerName.equals("strtoupper"), args) : null;
            case "trim":
                return n == 1 ? BuiltinNodesFactory.TrimNodeGen.create(3, args) : null;
            case "ltrim":
                return n == 1 ? BuiltinNodesFactory.TrimNodeGen.create(1, args) : null;
            case "rtrim":
            case "chop":
                return n == 1 ? BuiltinNodesFactory.TrimNodeGen.create(2, args) : null;
            case "ucfirst":
                return n == 1 ? BuiltinNodesFactory.UcfirstNodeGen.create(args) : null;
            case "str_replace":
                return n == 3 ? BuiltinNodesFactory.StrReplaceNodeGen.create(args) : null;
            case "in_array":
                return n == 2 || n == 3
                        ? BuiltinNodesFactory.InArrayNodeGen.create(withDefaults(args, 2, Boolean.FALSE)) : null;
            case "array_pop":
                return n == 1 && writable(args[0]) ? BuiltinNodesFactory.ArrayPopNodeGen.create(
                        new ExprNode[] {new BuiltinNodes.WritableArrayNode((LValueNode) args[0])}) : null;
            case "array_push":
                return n == 2 && writable(args[0]) ? BuiltinNodesFactory.ArrayPushNodeGen.create(
                        new ExprNode[] {new BuiltinNodes.WritableArrayNode((LValueNode) args[0]), args[1]}) : null;
            case "array_slice":
                return n >= 2 && n <= 4
                        ? BuiltinNodesFactory.ArraySliceNodeGen.create(withDefaults(args, 2, PhpNull.NULL, Boolean.FALSE))
                        : null;
            case "array_unshift":
                return n == 2 && writable(args[0]) ? BuiltinNodesFactory.ArrayUnshiftNodeGen.create(
                        new ExprNode[] {new BuiltinNodes.WritableArrayNode((LValueNode) args[0]), args[1]}) : null;
            case "array_keys":
                return n == 1 ? BuiltinNodesFactory.ArrayKeysNodeGen.create(args) : null;
            case "implode":
            case "join":
                return n == 2 ? BuiltinNodesFactory.ImplodeNodeGen.create(args) : null;
            case "explode":
                return n == 2 || n == 3
                        ? BuiltinNodesFactory.ExplodeNodeGen.create(withDefaults(args, 2, Long.MAX_VALUE)) : null;
            case "array_filter":
                return n >= 1 && n <= 3
                        ? BuiltinNodesFactory.ArrayFilterNodeGen.create(withDefaults(args, 1, PhpNull.NULL, 0L)) : null;
            case "array_map":
                return n == 2 ? BuiltinNodesFactory.ArrayMapNodeGen.create(args) : null;
            default:
                return null;
        }
    }

    // ---- shared helpers ----

    /** The arguments, then constants for the missing optional ones (defaults: those after the required). */
    private static ExprNode[] withDefaults(ExprNode[] args, int required, Object... defaults) {
        ExprNode[] full = Arrays.copyOf(args, required + defaults.length);
        for (int i = args.length; i < full.length; i++) {
            full[i] = new ConstNode(defaults[i - required]);
        }
        return full;
    }

    /** A by-reference array argument a DSL node can take: a place that can be read and then written. */
    private static boolean writable(ExprNode arg) {
        return arg instanceof LValueNode place && place.isPure();
    }

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
            throw new PhpError("TypeError", function + "(): Argument #" + position + " must be of type string, "
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
            return executeCondition(frame);
        }

        @Override
        public boolean executeCondition(VirtualFrame frame) {
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
