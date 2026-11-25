#!/usr/bin/env python3
"""
Project repo PhpArray and PhpRef into Probe B with ByteStr and zero Truffle,
emit byte-direct ArrayKey.java, and generate MegaGenerated.java with 181 nested
classes (61 functions including the override, 20 Hot_C classes, 60 methods, 40 closures).
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
RUNTIME = ROOT / "src/main/java/pl/codeloom/php/runtime"
OUT = Path(__file__).resolve().parent / "build/generated/pl/codeloom/php/runtime"
OUT.mkdir(parents=True, exist_ok=True)


def strip_truffle(src: str) -> str:
    src = re.sub(r"(?m)^import com\.oracle\.truffle\..*;\r?\n", "", src)
    src = re.sub(r"(?m)^\s*@TruffleBoundary\r?\n", "", src)
    src = src.replace(" implements TruffleObject", "")
    src = src.replace("TruffleString", "ByteStr")
    src = src.replace("CompilerDirectives.transferToInterpreter();", "")
    return src


# 1. PhpArray.java: keep all 3 storage strategies (long[], Object[], Hash), CoW, and Refcount calls,
# plus O(1) php-src gc_possible_root buffering for deep arrays whose holder count drops > 0.
php_array = strip_truffle((RUNTIME / "PhpArray.java").read_text(encoding="utf-8"))
old_deep = "    private boolean deep;"
new_deep = """    private boolean deep;
    // Remember the request between collections, including for array-only cycles.
    PhpRequestContext gcOwner;
    int gcArrayIdx = -1;

    private void forgetGcOwner() {
        if (gcArrayIdx >= 0) Refcount.unbufferArrayRoot(this);
        gcOwner = null;
    }"""
if old_deep not in php_array:
    raise RuntimeError("Failed to patch PhpArray GC fields")
php_array = php_array.replace(old_deep, new_deep, 1)

old_rem = """    public void removeHolder() {
        int count = holders;
        if (count > 0) {
            holders = count - 1;
            if (count == 1 && deep) {
                // one out-of-line call for both walks
                lastHolderGone();
            }
        }
    }"""
new_rem = """    public void removeHolder() {
        int count = holders;
        if (count > 0) {
            holders = count - 1;
            if (count == 1 && (deep || gcOwner != null)) {
                // one out-of-line call for both walks
                lastHolderGone();
            } else if (count > 1 && (deep || gcOwner != null) && gcArrayIdx < 0) {
                Refcount.possibleArrayRoot(this);
            }
        }
    }"""
if old_rem not in php_array:
    raise RuntimeError("Failed to patch PhpArray.removeHolder")
php_array = php_array.replace(old_rem, new_rem, 1)
# The nested-array release fast paths bypass lastHolderGone. Returning an array
# as a temporary and freezing it also end its time in the candidate buffer.
for before, after in [
    ("private void lastHolderGone() {", """private void lastHolderGone() {
        forgetGcOwner();"""),
    ("nested.holders = RELEASED;", "nested.markReleased();"),
    ("holders = RELEASED;\n        releaseElements(0);", """markReleased();
        releaseElements(0);
    }

    private void markReleased() {
        forgetGcOwner();
        holders = RELEASED;"""),
    ("if (count == 1 && mayHoldRefs)", """if (count == 1 && gcOwner != null) forgetGcOwner();
            if (count == 1 && mayHoldRefs)"""),
    ("holders = FROZEN;", """forgetGcOwner();
        holders = FROZEN;"""),
]:
    if before not in php_array:
        raise RuntimeError(f"Missing PhpArray projection anchor: {before}")
    php_array = php_array.replace(before, after)
(OUT / "PhpArray.java").write_text(php_array, encoding="utf-8", newline="\n")

# 2. PhpRef.java: CRITICAL-2 fix — retain newValue (array/object/closure) BEFORE releasing old!
php_ref = strip_truffle((RUNTIME / "PhpRef.java").read_text(encoding="utf-8"))
old_set = """        if (holds && newValue instanceof PhpArray array) {
            array.addHolder();
        }
        value = newValue;
        if (holds && old instanceof PhpArray array && old != newValue) {
            array.removeHolder();
        }
        if (holds && (newValue instanceof PhpObject || old instanceof PhpObject)) {
            countObjects(newValue, old);
        }"""
new_set = """        if (old == newValue) {
            return;
        }
        if (holds) {
            if (newValue instanceof PhpArray array) {
                array.addHolder();
            } else if (newValue instanceof PhpObject || newValue instanceof PhpClosure) {
                Refcount.inc(newValue);
            }
        }
        value = newValue;
        if (holds) {
            if (old instanceof PhpArray array) {
                array.removeHolder();
            } else if (old instanceof PhpObject || old instanceof PhpClosure) {
                Refcount.dec(old);
            }
        }"""
if old_set not in php_ref:
    raise RuntimeError("Failed to patch PhpRef.set")
php_ref = php_ref.replace(old_set, new_set, 1)
php_ref = php_ref.replace("    private int bindings;", """    private int bindings;

    int bindingCount() {
        return bindings;
    }""")
# Removing an external alias can make a previously scanned cycle unreachable
# without changing the count of the value held by the reference.
php_ref = php_ref.replace("""if (bindings > 0 && --bindings == 0) {
            release();
        }""", """if (bindings > 0) {
            if (--bindings == 0) release();
            else Refcount.possibleValueRoot(value);
        }""", 1)
(OUT / "PhpRef.java").write_text(php_ref, encoding="utf-8", newline="\n")

# 3. ArrayKey.java: LOW-1 fix — direct raw byte[] integer parsing (zero java.lang.String allocation).
array_key = """package pl.codeloom.php.runtime;

public final class ArrayKey {
    private ArrayKey() {}

    public static Object of(Object key) {
        if (key instanceof Long) return key;
        if (key instanceof ByteStr s) return ofString(s);
        if (key instanceof Boolean b) return b ? 1L : 0L;
        if (key == PhpNull.NULL) return Str.EMPTY;
        throw new PhpError("Cannot access offset of type " + key.getClass().getSimpleName() + " on array");
    }

    /** "123" and "-5" become ints; "0123", "1.5", " 1", "-0" and out-of-range digits stay ByteStr. */
    public static Object ofString(ByteStr s) {
        int length = s.length();
        if (length == 0 || length > 20) {
            return s;
        }
        byte[] bytes = s.rawBytes();
        int first = bytes[0] & 0xFF;
        boolean neg = first == '-';
        if (!neg && (first < '0' || first > '9')) {
            return s;
        }
        int i = neg ? 1 : 0;
        if (i == length || (bytes[i] == '0' && (length - i > 1 || neg))) {
            return s;
        }
        // Accumulate negatively so Long.MIN_VALUE (-9223372036854775808) fits cleanly without overflow.
        long limit = neg ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multmin = limit / 10L;
        long result = 0L;
        for (int j = i; j < length; j++) {
            int d = (bytes[j] & 0xFF) - '0';
            if (d < 0 || d > 9) {
                return s;
            }
            if (result < multmin) {
                return s;
            }
            result *= 10L;
            if (result < limit + d) {
                return s;
            }
            result -= d;
        }
        return neg ? result : -result;
    }
}
"""
(OUT / "ArrayKey.java").write_text(array_key, encoding="utf-8", newline="\n")

# 4. MegaGenerated.java: HIGH-1 fix — 160 distinct JVM target classes matching hot.php eval() diversity!
lines = [
    "package pl.codeloom.php.runtime;",
    "",
    "/**",
    " * Auto-generated distinct JVM target classes for hot.php mega:",
    " * - 60 distinct function classes HotFn0..HotFn59 (+ HotFn0Override for CtxB)",
    " * - 20 distinct object classes HotC0..HotC19, each with distinct method classes HotC{i}_a, HotC{i}_b, HotC{i}_s",
    " * - 40 distinct closure subclasses HotClosure0..HotClosure39",
    " * Prevents HotSpot C2 / Graal from collapsing 160 megamorphic PHP call targets into a single shared lambda.",
    " */",
    "final class MegaGenerated {",
    "    private MegaGenerated() {}",
    "",
]

# 60 distinct function classes
for i in range(60):
    lines.append(f"    static final class HotFn{i} extends PhpCallable {{")
    lines.append(f"        @Override public Object invoke(PhpRequestContext ctx, PhpArray args) {{ return (long) args.get(0L) + {i}L; }}")
    lines.append("    }")
lines.append("    static final class HotFn0Override extends PhpCallable {")
lines.append("        @Override public Object invoke(PhpRequestContext ctx, PhpArray args) { return (long) args.get(0L) + 100L; }")
lines.append("    }")
lines.append("")

# 20 distinct HotC classes + 60 distinct method classes (a, b, s)
for i in range(20):
    lines.append(f"    static final class HotC{i} extends PhpObject {{")
    lines.append(f"        long n = {i}L;")
    lines.append(f"        HotC{i}(PhpRequestContext ctx, PhpClass cls) {{ super(ctx, cls); }}")
    lines.append("    }")
    lines.append(f"    static final class HotC{i}_a extends PhpInstanceMethod {{")
    lines.append(f"        @Override public Object invoke(PhpRequestContext ctx, PhpObject self, PhpArray args) {{ return (long) args.get(0L) + ((HotC{i}) self).n; }}")
    lines.append("    }")
    lines.append(f"    static final class HotC{i}_b extends PhpInstanceMethod {{")
    lines.append(f"        @Override public Object invoke(PhpRequestContext ctx, PhpObject self, PhpArray args) {{ return (long) args.get(0L) + (long) args.get(1L); }}")
    lines.append("    }")
    lines.append(f"    static final class HotC{i}_s extends PhpCallable {{")
    lines.append(f"        @Override public Object invoke(PhpRequestContext ctx, PhpArray args) {{ return (long) args.get(0L) + 2L; }}")
    lines.append("    }")
lines.append("")

# 40 distinct closure subclasses
for h in range(40):
    lines.append(f"    static final class HotClosure{h} extends PhpClosure {{")
    lines.append(f"        HotClosure{h}(PhpRequestContext ctx) {{ super(ctx, null); }}")
    lines.append(f"        @Override Object invoke(PhpRequestContext ctx, PhpArray args) {{ return (long) args.get(0L) + {h}L; }}")
    lines.append("    }")
lines.append("")

# Registration helper
lines.append("    static PhpObject[] registerAndCreate(PhpRequestContext ctx, boolean overrideCtxB) {")
lines.append("        // 60 distinct functions")
lines.append("        ctx.registerFunction(ByteStr.of(\"hot_f0\"), overrideCtxB ? new HotFn0Override() : new HotFn0());")
for i in range(1, 60):
    lines.append(f"        ctx.registerFunction(ByteStr.of(\"hot_f{i}\"), new HotFn{i}());")
lines.append("        // 20 distinct classes & objects")
lines.append("        PhpObject[] objects = new PhpObject[20];")
for i in range(20):
    lines.append(f"        {{")
    lines.append(f"            PhpClass cls = ctx.defineClass(ByteStr.of(\"Hot_C{i}\"));")
    lines.append(f"            cls.addMethod(HotOpenWorld.METHOD_A, new HotC{i}_a());")
    lines.append(f"            cls.addMethod(HotOpenWorld.METHOD_B, new HotC{i}_b());")
    lines.append(f"            cls.addStaticMethod(HotOpenWorld.METHOD_S, new HotC{i}_s());")
    lines.append(f"            objects[{i}] = new HotC{i}(ctx, cls);")
    lines.append(f"        }}")
lines.append("        // 40 hooks x 5 megamorphic callbacks")
for h in range(40):
    lines.append(f"        {{")
    lines.append(f"            ByteStr hookName = ByteStr.of(\"hot_hook_{h}\");")
    lines.append(f"            HooksOpenWorld.addFilter(ctx, hookName, ByteStr.of(\"hot_f{h % 60}\"), 10, 1);")
    lines.append(f"            HooksOpenWorld.addFilter(ctx, hookName, PhpArray.list(objects[{h % 20}], HotOpenWorld.METHOD_A), 10, 1);")
    lines.append(f"            HooksOpenWorld.addFilter(ctx, hookName, PhpArray.list(ByteStr.of(\"Hot_C{(h + 7) % 20}\"), HotOpenWorld.METHOD_S), 11, 1);")
    lines.append(f"            HooksOpenWorld.addFilter(ctx, hookName, PhpArray.list(objects[{(h + 3) % 20}], HotOpenWorld.METHOD_B), 12, 2);")
    lines.append(f"            PhpClosure cl = new HotClosure{h}(ctx);")
    lines.append(f"            HooksOpenWorld.addFilter(ctx, hookName, cl, 20, 1);")
    lines.append(f"            cl.release();")
    lines.append(f"        }}")
lines.append("        return objects;")
lines.append("    }")
lines.append("}")
lines.append("")

(OUT / "MegaGenerated.java").write_text("\n".join(lines), encoding="utf-8", newline="\n")
print("Projected PhpArray.java, PhpRef.java, ArrayKey.java, and MegaGenerated.java to", OUT)
