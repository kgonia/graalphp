import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.*;
import java.lang.reflect.AccessFlag;
import java.nio.file.*;
import java.util.*;

/**
 * Zero-dependency JDK 25 bytecode linter enforcing B1 performance & safety invariants
 * (derived empirically from Probe A, Option 1, and Probe B Rev 1-4).
 *
 * Usage:
 *   java --source 25 bench/b1-precheck/probe-b/tools/B1Lint.java <classes-dir>
 */
public final class B1Lint {
    private static final int MAX_METHOD_BYTES = 7_500;   // HotSpot DontCompileHugeMethods cliff is 8,000
    private static final int MAX_CLINIT_BYTES = 4_000;   // Forces side-blob / ConstantDynamic for large literals

    private static final Set<String> RUNTIME_HOT_CLASSES = Set.of(
            "pl/codeloom/php/runtime/PhpRequestContext",
            "pl/codeloom/php/runtime/HooksOpenWorld",
            "pl/codeloom/php/runtime/HooksOpenWorld$Hook",
            "pl/codeloom/php/runtime/WPPost",
            "pl/codeloom/php/runtime/StdClass",
            "pl/codeloom/php/runtime/HotOpenWorld",
            "pl/codeloom/php/runtime/ByteStr",
            "pl/codeloom/php/runtime/ArrayKey",
            "pl/codeloom/php/runtime/PhpArray",
            "pl/codeloom/php/runtime/PhpRef"
    );

    private static final Set<String> CALLABLE_BASES = Set.of(
            "pl/codeloom/php/runtime/PhpCallable",
            "pl/codeloom/php/runtime/PhpInstanceMethod",
            "pl/codeloom/php/runtime/PhpClosure"
    );

    private static final Set<String> ALLOWED_MUTABLE_STATICS = Set.of(
            "pl/codeloom/php/runtime/HotOpenWorld.postSink",
            "pl/codeloom/php/runtime/ProbeBMain.sink"
    );

    private static final Set<String> ZERO_BOX_VARARGS_METHODS = Set.of(
            "pl/codeloom/php/runtime/PhpRequestContext.call0",
            "pl/codeloom/php/runtime/PhpRequestContext.call1",
            "pl/codeloom/php/runtime/PhpRequestContext.call2",
            "pl/codeloom/php/runtime/HotOpenWorld.post",
            "pl/codeloom/php/runtime/WPPost.readProp",
            "pl/codeloom/php/runtime/WPPost.writeProp"
    );

    private static final Set<String> SINGLE_PROBE_LOOKUP_METHODS = Set.of(
            "pl/codeloom/php/runtime/PhpRequestContext.findFunction",
            "pl/codeloom/php/runtime/PhpRequestContext.findClass",
            "pl/codeloom/php/runtime/PhpClass.findMethod",
            "pl/codeloom/php/runtime/PhpClass.findStaticMethod"
    );

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: B1Lint <classes-dir>");
            System.exit(2);
        }
        Path root = Path.of(args[0]);
        List<Path> classFiles;
        try (var stream = Files.walk(root)) {
            classFiles = stream.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        if (classFiles.isEmpty()) {
            System.err.println("No .class files found under " + root);
            System.exit(2);
        }

        List<String> violations = new ArrayList<>();
        int checkedClasses = 0;
        int checkedMethods = 0;
        boolean sawByteStrLowerField = false;

        ClassFile cf = ClassFile.of();
        for (Path path : classFiles) {
            ClassModel cm = cf.parse(path);
            checkedClasses++;
            String cls = cm.thisClass().asInternalName();
            boolean isTestOrDriver = cls.contains("Checks") || cls.endsWith("ProbeBMain") || cls.endsWith("Corpus");
            boolean isMega = cls.contains("MegaGenerated");
            boolean isHotClass = RUNTIME_HOT_CLASSES.contains(cls) || isMega;

            // B1-03: Callable/method/closure bases MUST be abstract classes, never interfaces
            if (CALLABLE_BASES.contains(cls)) {
                if (cm.flags().has(AccessFlag.INTERFACE)) {
                    violations.add("[B1-03] " + cls + " must be an abstract class (vtable), not an interface (itable)");
                }
                if (!cm.flags().has(AccessFlag.ABSTRACT)) {
                    violations.add("[B1-03] " + cls + " should be abstract");
                }
            }

            // B1-04: No mutable static fields in runtime or emitted classes
            if (!isTestOrDriver) {
                for (FieldModel f : cm.fields()) {
                    String fname = f.fieldName().stringValue();
                    if (cls.equals("pl/codeloom/php/runtime/ByteStr") && fname.equals("lower")) {
                        sawByteStrLowerField = true;
                    }
                    if (f.flags().has(AccessFlag.STATIC) && !f.flags().has(AccessFlag.FINAL)) {
                        String key = cls + "." + fname;
                        if (!ALLOWED_MUTABLE_STATICS.contains(key)) {
                            violations.add("[B1-04] Mutable static field forbidden in runtime/emitted code: " + key);
                        }
                    }
                }
            }

            for (MethodModel m : cm.methods()) {
                checkedMethods++;
                String mname = m.methodName().stringValue();
                String fullMethod = cls + "." + mname;
                Optional<CodeAttribute> codeOpt = m.findAttribute(Attributes.code());
                if (codeOpt.isEmpty()) continue;
                CodeAttribute code = codeOpt.get();
                int byteLen = code.codeLength();

                // B1-01: HotSpot DontCompileHugeMethods cliff (< 7,500 bytes; <clinit> < 4,000 bytes)
                if (mname.equals("<clinit>")) {
                    if (byteLen > MAX_CLINIT_BYTES) {
                        violations.add("[B1-01] <clinit> too large (" + byteLen + " > " + MAX_CLINIT_BYTES + " B): " + cls);
                    }
                } else if (byteLen > MAX_METHOD_BYTES) {
                    violations.add("[B1-01] Method exceeds DontCompileHugeMethods margin (" + byteLen + " > " + MAX_METHOD_BYTES + " B): " + fullMethod);
                }

                int hashMapGets = 0;
                for (CodeElement el : code) {
                    // B1-02: No per-site invokedynamic in emitted/hot classes (ignore string concat & one-time init lambdas)
                    if (el instanceof InvokeDynamicInstruction indy) {
                        String bsm = indy.bootstrapMethod().owner().descriptorString();
                        boolean isStringConcat = bsm.equals("Ljava/lang/invoke/StringConcatFactory;");
                        boolean isColdInit = mname.equals("<init>") || mname.equals("<clinit>") || mname.equals("registerCoreBuiltins");
                        if (isMega) {
                            violations.add("[B1-02] invokedynamic forbidden in emitted PHP class: " + fullMethod + " -> " + bsm);
                        } else if (isHotClass && !isStringConcat && !isColdInit) {
                            violations.add("[B1-02] Non-concat invokedynamic forbidden in hot runtime class: " + fullMethod + " -> " + bsm);
                        }
                    }

                    if (el instanceof InvokeInstruction inv) {
                        String owner = inv.owner().asInternalName();
                        String targetName = inv.name().stringValue();
                        String desc = inv.type().stringValue();

                        // B1-03: No invokeinterface on callable/method/closure bases
                        if (inv.opcode() == Opcode.INVOKEINTERFACE && CALLABLE_BASES.contains(owner)) {
                            violations.add("[B1-03] Megamorphic invokeinterface on callable base: " + fullMethod + " -> " + owner + "." + targetName);
                        }

                        // B1-04: No ThreadLocal / ScopedValue in runtime/emitted classes
                        if (!isTestOrDriver && (owner.equals("java/lang/ThreadLocal") || owner.equals("java/lang/ScopedValue"))) {
                            violations.add("[B1-04] ThreadLocal/ScopedValue forbidden (pass PhpRequestContext param 0): " + fullMethod);
                        }

                        // B1-05: No UTF-16 String/StringBuilder conversions in hot PHP value/key paths
                        if (isHotClass && !mname.equals("toString") && !mname.equals("ofUtf8") && !mname.equals("of")) {
                            if (owner.equals("java/lang/StringBuilder")
                                    || (owner.equals("java/lang/String") && (targetName.equals("getBytes") || targetName.equals("<init>")))) {
                                violations.add("[B1-05] UTF-16 String/StringBuilder conversion in hot path: " + fullMethod + " -> " + owner + "." + targetName);
                            }
                        }

                        // B1-06: No Long.valueOf boxing in zero-box hot dispatchers
                        if (ZERO_BOX_VARARGS_METHODS.contains(fullMethod)
                                && owner.equals("java/lang/Long") && targetName.equals("valueOf")) {
                            violations.add("[B1-06] Long.valueOf boxing in @HotPath method: " + fullMethod);
                        }

                        // B1-07: Single-probe case-insensitive lookup in symbol/method tables
                        if (SINGLE_PROBE_LOOKUP_METHODS.contains(fullMethod)
                                && owner.equals("java/util/HashMap") && targetName.equals("get")) {
                            hashMapGets++;
                        }
                    }

                    // B1-06: No varargs Object[] allocation in zero-box hot dispatchers
                    if (ZERO_BOX_VARARGS_METHODS.contains(fullMethod) && el instanceof NewReferenceArrayInstruction arr) {
                        if (arr.componentType().asInternalName().equals("java/lang/Object")) {
                            violations.add("[B1-06] Object[] varargs allocation in @HotPath method: " + fullMethod);
                        }
                    }
                }

                if (SINGLE_PROBE_LOOKUP_METHODS.contains(fullMethod) && hashMapGets > 1) {
                    violations.add("[B1-07] Double HashMap.get (" + hashMapGets + "x) in symbol lookup (use cached ByteStr.toLowerAscii()): " + fullMethod);
                }
            }
        }

        if (!sawByteStrLowerField) {
            violations.add("[B1-07] ByteStr is missing cached 'lower' field for O(1) case-insensitive lookups");
        }

        System.out.println("B1Lint scanned " + checkedClasses + " classes, " + checkedMethods + " methods under " + root);
        if (violations.isEmpty()) {
            System.out.println("B1Lint PASSED (0 violations across B1-01..B1-07)");
        } else {
            System.err.println("B1Lint FAILED (" + violations.size() + " violations):");
            for (String v : violations) {
                System.err.println("  " + v);
            }
            System.exit(1);
        }
    }
}
