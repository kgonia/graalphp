package pl.codeloom.php.b1;

import pl.codeloom.php.b1.runtime.HotPath;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Same gate for the exact pre-definition classfile and the stock-JDK runtime directory. */
public final class B1Lint {
    public enum Kind { EMITTED, RUNTIME }
    private B1Lint() {}

    private static final String BASE = "pl/codeloom/php/b1/runtime/B1Callable";
    private static final String CTX = "Lpl/codeloom/php/b1/runtime/B1RequestContext;";
    private static final int MAX_INLINE_STRING_BYTES = 16_384;
    private static final int MAX_EMITTED_CP_ENTRIES = 2_000;
    private static final Set<String> MUTABLE_STATIC_CONTAINERS = Set.of(
            "java/util/HashMap", "java/util/LinkedHashMap", "java/util/TreeMap",
            "java/util/IdentityHashMap", "java/util/WeakHashMap", "java/util/Hashtable",
            "java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentSkipListMap",
            "java/util/ArrayList", "java/util/LinkedList", "java/util/Vector", "java/util/ArrayDeque",
            "java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet",
            "java/util/concurrent/CopyOnWriteArrayList", "java/util/concurrent/CopyOnWriteArraySet",
            "java/util/concurrent/ConcurrentLinkedQueue", "java/util/concurrent/LinkedBlockingQueue",
            "java/util/concurrent/atomic/AtomicReference", "java/util/concurrent/atomic/AtomicInteger",
            "java/util/concurrent/atomic/AtomicLong", "java/util/concurrent/atomic/AtomicBoolean"
    );

    public static List<String> inspect(byte[] bytes, Kind kind) {
        ClassModel model = ClassFile.of().parse(bytes);
        String cls = model.thisClass().asInternalName();
        var errors = new ArrayList<String>();
        if (cls.equals(BASE) && (!model.flags().has(AccessFlag.ABSTRACT) || model.flags().has(AccessFlag.INTERFACE)))
            errors.add("B1-03 callable base must be an abstract class: " + cls);
        if (kind == Kind.EMITTED && !model.superclass().orElseThrow().asInternalName().equals(BASE))
            errors.add("B1-03 emitted callable superclass: " + cls);
        if (kind == Kind.EMITTED && model.constantPool().size() > MAX_EMITTED_CP_ENTRIES)
            errors.add("B1-09 constant pool too large in " + cls + " (" + model.constantPool().size() + " > " + MAX_EMITTED_CP_ENTRIES + ")");
        // Class, name-and-type and method-type constants also cover every symbolic component
        // of member refs, method handles and bootstrap arguments (including nested condy).
        for (var entry : model.constantPool()) {
            if (kind == Kind.EMITTED && entry instanceof StringEntry str) {
                int len = str.utf8().stringValue().getBytes(StandardCharsets.UTF_8).length;
                if (len > MAX_INLINE_STRING_BYTES)
                    errors.add("B1-09 oversized inline string in " + cls + " (" + len + " > " + MAX_INLINE_STRING_BYTES + " B)");
            }
            String type = switch (entry) {
                case ClassEntry c -> c.asInternalName();
                case NameAndTypeEntry n -> n.type().stringValue();
                case MethodTypeEntry m -> m.descriptor().stringValue();
                default -> null;
            };
            if (type != null) checkType(type, kind, cls, errors);
        }
        attributes(model, kind, cls, errors);
        for (var field : model.fields()) {
            String fieldDesc = field.fieldType().stringValue();
            checkType(fieldDesc, kind, cls, errors);
            attributes(field, kind, cls, errors);
            if (field.flags().has(AccessFlag.STATIC)) {
                if (!field.flags().has(AccessFlag.FINAL))
                    errors.add("B1-04 mutable static " + cls + "." + field.fieldName());
                else if (isMutableStaticContainer(fieldDesc))
                    errors.add("B1-11 mutable static container " + cls + "." + field.fieldName() + " (" + fieldDesc + ")");
            }
        }
        if ((kind == Kind.EMITTED || model.superclass().map(c -> c.asInternalName().equals(BASE)).orElse(false)) && model.methods().stream().anyMatch(m -> m.methodName().equalsString("invoke"))) {
            for (int arity = 0; arity <= 2; arity++) {
                String name = "invoke" + arity;
                String descriptor = "(" + CTX + "Lpl/codeloom/php/b1/runtime/B1Object;" +
                        "Ljava/lang/Object;".repeat(arity) + ")Ljava/lang/Object;";
                if (model.methods().stream().noneMatch(m -> m.methodName().equalsString(name)
                        && m.methodType().equalsString(descriptor) && m.flags().has(AccessFlag.PUBLIC)
                        && !m.flags().has(AccessFlag.STATIC))) errors.add("B1-06 missing " + cls + "." + name);
            }
        }
        for (var method : model.methods()) checkMethod(model, kind, cls, method, errors);
        return List.copyOf(errors);
    }

    private static boolean isMutableStaticContainer(String desc) {
        if (!desc.startsWith("L") || !desc.endsWith(";")) return false;
        return MUTABLE_STATIC_CONTAINERS.contains(desc.substring(1, desc.length() - 1));
    }

    private static void checkType(String type, Kind kind, String cls, List<String> errors) {
        if (type.startsWith("com/oracle/truffle/") || type.contains("Lcom/oracle/truffle/"))
            errors.add("B1-08 Truffle reference in " + cls + ": " + type);
        if (kind == Kind.EMITTED) {
            for (String retained : List.of("pl/codeloom/php/ir/", "pl/codeloom/php/parser/", "pl/codeloom/php/b1/B1Cfg")) {
                if (type.startsWith(retained) || type.contains("L" + retained))
                    errors.add("B1-12 retained compiler/IR reference in " + cls + ": " + type);
            }
        }
        for (String ambient : List.of("java/lang/ThreadLocal", "java/lang/InheritableThreadLocal", "java/lang/ScopedValue")) {
            if (type.equals(ambient) || type.startsWith(ambient + "$") || type.contains("L" + ambient + ";")
                    || type.contains("L" + ambient + "<") || type.contains("L" + ambient + "$"))
                errors.add("B1-04 ambient context in " + cls);
        }
    }

    private static void attributes(AttributedElement element, Kind kind, String cls, List<String> errors) {
        for (var attribute : element.attributes()) {
            switch (attribute) {
                case LocalVariableTableAttribute locals -> locals.localVariables().forEach(v -> checkType(v.type().stringValue(), kind, cls, errors));
                case LocalVariableTypeTableAttribute locals -> locals.localVariableTypes().forEach(v -> checkType(v.signature().stringValue(), kind, cls, errors));
                case SignatureAttribute s -> checkType(s.signature().stringValue(), kind, cls, errors);
                case RuntimeVisibleAnnotationsAttribute a -> a.annotations().forEach(v -> annotation(v, kind, cls, errors));
                case RuntimeInvisibleAnnotationsAttribute a -> a.annotations().forEach(v -> annotation(v, kind, cls, errors));
                case RuntimeVisibleTypeAnnotationsAttribute a -> a.annotations().forEach(v -> annotation(v.annotation(), kind, cls, errors));
                case RuntimeInvisibleTypeAnnotationsAttribute a -> a.annotations().forEach(v -> annotation(v.annotation(), kind, cls, errors));
                case RuntimeVisibleParameterAnnotationsAttribute a -> a.parameterAnnotations().forEach(p -> p.forEach(v -> annotation(v, kind, cls, errors)));
                case RuntimeInvisibleParameterAnnotationsAttribute a -> a.parameterAnnotations().forEach(p -> p.forEach(v -> annotation(v, kind, cls, errors)));
                case AnnotationDefaultAttribute a -> annotationValue(a.defaultValue(), kind, cls, errors);
                case RecordAttribute r -> r.components().forEach(c -> {
                    checkType(c.descriptor().stringValue(), kind, cls, errors);
                    attributes(c, kind, cls, errors);
                });
                default -> { }
            }
        }
    }

    private static void annotation(Annotation annotation, Kind kind, String cls, List<String> errors) {
        checkType(annotation.className().stringValue(), kind, cls, errors);
        annotation.elements().forEach(e -> annotationValue(e.value(), kind, cls, errors));
    }

    private static void annotationValue(AnnotationValue value, Kind kind, String cls, List<String> errors) {
        switch (value) {
            case AnnotationValue.OfClass c -> checkType(c.className().stringValue(), kind, cls, errors);
            case AnnotationValue.OfEnum e -> checkType(e.className().stringValue(), kind, cls, errors);
            case AnnotationValue.OfAnnotation a -> annotation(a.annotation(), kind, cls, errors);
            case AnnotationValue.OfArray a -> a.values().forEach(v -> annotationValue(v, kind, cls, errors));
            default -> { }
        }
    }

    private static void checkMethod(ClassModel model, Kind kind, String cls, MethodModel method, List<String> errors) {
        String name = method.methodName().stringValue();
        String where = cls + "." + name;
        attributes(method, kind, cls, errors);
        checkType(method.methodType().stringValue(), kind, cls, errors);
        var hot = method.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
                .flatMap(a -> a.annotations().stream()).filter(a -> a.classSymbol().equals(ClassDesc.of(HotPath.class.getName())))
                .findFirst();
        if ((kind == Kind.EMITTED && !name.startsWith("<") || dispatchName(name) || hot.isPresent())
                && !method.methodType().stringValue().startsWith("(" + CTX)) errors.add("B1-04 context parameter 0: " + where);
        var code = method.findAttribute(Attributes.code()).orElse(null);
        if (code == null) return;
        attributes(code, kind, cls, errors);
        if (code.codeLength() > (name.equals("<clinit>") ? 4000 : 7500))
            errors.add("B1-01 " + where + " is " + code.codeLength() + " bytes");
        boolean lookup = requiredLookup(cls,name) || hot.stream().flatMap(a -> a.elements().stream()).anyMatch(e -> e.name().equalsString("lookup")
                && e.value() instanceof AnnotationValue.OfBoolean flag && flag.booleanValue());
        boolean fixed = name.equals("invoke0") || name.equals("invoke1") || name.equals("invoke2");
        boolean hotCode = hot.isPresent() || lookup || dispatchName(name);
        for (CodeElement op : code) {
            if (op instanceof InvokeDynamicInstruction indy && (kind == Kind.EMITTED || hotCode
                    && !indy.bootstrapMethod().owner().descriptorString().equals("Ljava/lang/invoke/StringConcatFactory;")))
                errors.add("B1-02 invokedynamic in " + where);
            if (op instanceof NewReferenceArrayInstruction array && (hot.isPresent() || fixed)
                    && array.componentType().asInternalName().equals("java/lang/Object")) errors.add("B1-06 array in " + where);
            if (!(op instanceof InvokeInstruction call)) continue;
            String owner = call.owner().asInternalName(), target = call.name().stringValue();
            if (kind == Kind.EMITTED && name.startsWith("fragment")
                    && (owner.equals("pl/codeloom/php/b1/runtime/B1Frame") && target.equals("leave")
                    || owner.equals("pl/codeloom/php/b1/runtime/B1RequestContext")
                    && (target.equals("pushFrame") || target.equals("popFrame"))))
                errors.add("B1-10 outlined helper " + where + " must not call " + owner + "." + target);
            if (owner.equals("java/lang/Thread") && (target.equals("ofVirtual") || target.equals("startVirtualThread"))
                    || owner.equals("java/util/concurrent/Executors") && target.equals("newVirtualThreadPerTaskExecutor"))
                errors.add("B1-13 virtual thread forbidden in " + where);
            if (call.opcode() == Opcode.INVOKEINTERFACE && (owner.startsWith("pl/codeloom/php/b1/") || dispatchName(target)
                    || call.type().stringValue().startsWith("(" + CTX)
                    || (kind == Kind.EMITTED || hot.isPresent()) && functionalInterface(owner)))
                errors.add("B1-03 interface call in " + where);
            if ((call.opcode() == Opcode.INVOKESPECIAL || call.opcode() == Opcode.INVOKESTATIC) && dispatchName(target)
                    && (owner.equals(BASE) || call.type().stringValue().startsWith("(" + CTX + "Lpl/codeloom/php/b1/runtime/B1Object;")))
                errors.add("B1-03 nonvirtual callable invocation in " + where);
            if (fixed && (target.equals("invoke") && owner.equals(BASE) || kind == Kind.EMITTED && owner.equals("java/lang/Long") && target.equals("valueOf")))
                errors.add("B1-06 boxing or varargs bridge in " + where);
            if (fixed && kind == Kind.RUNTIME && dispatchHelper(model,call)) {
                var helper = helper(model,call);
                if (helper != null && bridgeAllocates(helper,new java.util.HashSet<>()))
                    errors.add("B1-06 allocating dispatch helper in " + where);
            }
            if (call.opcode() == Opcode.INVOKESTATIC && call.type().stringValue().contains("L" + BASE + ";")
                    && call.type().stringValue().endsWith(")Ljava/lang/Object;")
                    && !(kind == Kind.EMITTED && owner.equals(cls) && (target.equals("run") || target.startsWith("fragment"))))
                errors.add("B1-03 static callable dispatch in " + where);
            if (!hotCode) continue;
            if (owner.equals("java/lang/StringBuilder") || owner.equals("java/lang/String") &&
                    (target.equals("getBytes") || target.equals("<init>") && call.type().stringValue().startsWith("([B")))
                errors.add("B1-05 string conversion in " + where);
            if (hot.isPresent() && owner.equals("java/lang/Long") && target.equals("valueOf")) errors.add("B1-06 boxing in " + where);
            if (lookup && (target.equals("lower") || target.equals("toLowerCase"))) errors.add("B1-07 uncached name in " + where);
        }
        if (lookup && probes(model,method,new java.util.HashSet<>()) > 1) errors.add("B1-07 repeated lookup in " + where);
    }

    private static boolean dispatchHelper(ClassModel caller,InvokeInstruction call) {
        String type = call.type().stringValue();
        return !call.owner().asInternalName().startsWith("java/") && (type.endsWith(")[Ljava/lang/Object;")
                || type.contains(CTX + "Lpl/codeloom/php/b1/runtime/B1Object;")
                || call.owner().asInternalName().equals(caller.thisClass().asInternalName()) && type.endsWith(")Ljava/lang/Object;"));
    }
    private record Helper(ClassModel owner, MethodModel method) {}
    private static Helper helper(ClassModel caller,InvokeInstruction call) {
        String owner = call.owner().asInternalName();
        var callee = owner.equals(caller.thisClass().asInternalName()) ? caller : readHelper(owner);
        if (callee != null) for (var method : callee.methods()) {
            if (method.methodName().equalsString(call.name().stringValue())
                    && method.methodType().equalsString(call.type().stringValue()) && method.code().isPresent())
                return new Helper(callee,method);
        }
        return null;
    }

    private static boolean bridgeAllocates(Helper bridge,java.util.Set<String> visited) {
        String key = bridge.owner().thisClass().asInternalName() + "." + bridge.method().methodName().stringValue()
                + bridge.method().methodType().stringValue();
        if (!visited.add(key)) return false;
        for (CodeElement op : bridge.method().code().orElseThrow()) {
            if (op instanceof NewReferenceArrayInstruction array && array.componentType().asInternalName().equals("java/lang/Object"))
                return true;
            if (op instanceof InvokeInstruction call && dispatchHelper(bridge.owner(),call)) {
                var next = helper(bridge.owner(),call);
                if (next != null && bridgeAllocates(next,visited)) return true;
            }
        }
        return false;
    }

    private static boolean requiredLookup(String cls,String name) {
        return cls.equals("pl/codeloom/php/b1/runtime/B1RequestContext") && (name.equals("function") || name.equals("findClass"))
                || name.equals("method") && (cls.equals("pl/codeloom/php/b1/runtime/B1Object")
                || cls.equals("pl/codeloom/php/b1/runtime/B1RequestContext$ClassDef"));
    }

    /** Follow application helpers which can participate in a successful lookup. Diagnostic factories ending in
     * ATHROW belong to the exceptional path. A recursive lookup is already more than one table probe. */
    private static int probes(ClassModel model,MethodModel method,java.util.Set<String> active) {
        String id = model.thisClass().asInternalName() + "." + method.methodName().stringValue() + method.methodType().stringValue();
        if (!active.add(id)) return 2;
        int total = 0;
        var instructions = method.code().orElseThrow().elementStream().filter(Instruction.class::isInstance).toList();
        for (int i = 0; i < instructions.size(); i++) {
            if (!(instructions.get(i) instanceof InvokeInstruction call)) continue;
            String owner = call.owner().asInternalName(), name = call.name().stringValue();
            if (owner.startsWith("java/util/") && (name.equals("get") || name.equals("containsKey") || name.equals("getOrDefault"))) {
                total++;
                continue;
            }
            if (i+1 < instructions.size() && ((Instruction)instructions.get(i+1)).opcode() == Opcode.ATHROW) continue;
            if (owner.startsWith("java/") || owner.startsWith("jdk/") || owner.startsWith("[")) continue;
            ClassModel callee = owner.equals(model.thisClass().asInternalName()) ? model : readHelper(owner);
            if (callee == null) continue;
            for (var candidate : callee.methods()) if (candidate.methodName().equalsString(name)
                    && candidate.methodType().equalsString(call.type().stringValue()) && candidate.code().isPresent()) {
                total += probes(callee,candidate,active);
                break;
            }
        }
        active.remove(id);
        return total;
    }

    private static ClassModel readHelper(String owner) {
        try (var in = B1Lint.class.getResourceAsStream("/" + owner + ".class")) {
            return in == null ? null : ClassFile.of().parse(in.readAllBytes());
        } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }

    /** Recognize erased callable interfaces even when their single abstract method has an application-specific name. */
    private static boolean functionalInterface(String owner) {
        if (owner.startsWith("java/util/function/")) return true;
        try {
            Class<?> type = Class.forName(owner.replace('/', '.'), false, B1Lint.class.getClassLoader());
            if (type.isAnnotationPresent(FunctionalInterface.class)) return true;
            Method single = null;
            for (Method method : type.getMethods()) {
                if (!Modifier.isAbstract(method.getModifiers()) || objectMethod(method)) continue;
                if (single != null && !(single.getName().equals(method.getName())
                        && Arrays.equals(single.getParameterTypes(), method.getParameterTypes()))) return false;
                single = method;
            }
            return single != null;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            // A pre-definition scan may only have the symbolic owner. The dispatch name and descriptor still apply.
            return false;
        }
    }

    private static boolean objectMethod(Method method) {
        return switch (method.getName()) {
            case "hashCode", "toString" -> method.getParameterCount() == 0;
            case "equals" -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == Object.class;
            default -> false;
        };
    }

    private static boolean dispatchName(String name) {
        return name.startsWith("invoke") || name.equals("call") || name.equals("callSlice") || name.equals("dispatch");
    }

    public static void requireClean(byte[] bytes, Kind kind) {
        var errors = inspect(bytes, kind);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("\n", errors));
    }

    public static void main(String[] args) throws IOException {
        try (var files = Files.walk(Path.of(args[0]))) {
            var classes = files.filter(p -> p.toString().endsWith(".class")).toList();
            if (classes.isEmpty()) throw new IllegalArgumentException("no runtime classes");
            for (var file : classes) requireClean(Files.readAllBytes(file), Kind.RUNTIME);
            System.out.println("B1Lint: " + classes.size() + " runtime classes, 0 violations");
        }
    }
}
