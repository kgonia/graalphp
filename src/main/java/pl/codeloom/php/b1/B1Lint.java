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
import java.util.Arrays;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Same gate for the exact pre-definition classfile and the stock-JDK runtime directory. */
public final class B1Lint {
    public enum Kind { EMITTED, RUNTIME }
    private B1Lint() {}

    private static final String BASE = "pl/codeloom/php/b1/runtime/B1Callable";
    private static final String CTX = "Lpl/codeloom/php/b1/runtime/B1RequestContext;";

    public static List<String> inspect(byte[] bytes, Kind kind) {
        ClassModel model = ClassFile.of().parse(bytes);
        String cls = model.thisClass().asInternalName();
        var errors = new ArrayList<String>();
        if (cls.equals(BASE) && (!model.flags().has(AccessFlag.ABSTRACT) || model.flags().has(AccessFlag.INTERFACE)))
            errors.add("B1-03 callable base must be an abstract class: " + cls);
        if (kind == Kind.EMITTED && !model.superclass().orElseThrow().asInternalName().equals(BASE))
            errors.add("B1-03 emitted callable superclass: " + cls);
        // Class, name-and-type and method-type constants also cover every symbolic component
        // of member refs, method handles and bootstrap arguments (including nested condy).
        for (var entry : model.constantPool()) {
            String type = switch (entry) {
                case ClassEntry c -> c.asInternalName();
                case NameAndTypeEntry n -> n.type().stringValue();
                case MethodTypeEntry m -> m.descriptor().stringValue();
                default -> null;
            };
            if (type != null) checkType(type, cls, errors);
        }
        attributes(model, cls, errors);
        for (var field : model.fields()) {
            checkType(field.fieldType().stringValue(), cls, errors);
            attributes(field, cls, errors);
            if (field.flags().has(AccessFlag.STATIC) && !field.flags().has(AccessFlag.FINAL))
                errors.add("B1-04 mutable static " + cls + "." + field.fieldName());
        }
        for (var method : model.methods()) checkMethod(kind, cls, method, errors);
        return List.copyOf(errors);
    }

    private static void checkType(String type, String cls, List<String> errors) {
        if (type.startsWith("com/oracle/truffle/") || type.contains("Lcom/oracle/truffle/"))
            errors.add("B1-08 Truffle reference in " + cls + ": " + type);
        for (String ambient : List.of("java/lang/ThreadLocal", "java/lang/InheritableThreadLocal", "java/lang/ScopedValue")) {
            if (type.equals(ambient) || type.startsWith(ambient + "$") || type.contains("L" + ambient + ";")
                    || type.contains("L" + ambient + "<") || type.contains("L" + ambient + "$"))
                errors.add("B1-04 ambient context in " + cls);
        }
    }

    private static void attributes(AttributedElement element, String cls, List<String> errors) {
        for (var attribute : element.attributes()) {
            switch (attribute) {
                case SignatureAttribute s -> checkType(s.signature().stringValue(), cls, errors);
                case RuntimeVisibleAnnotationsAttribute a -> a.annotations().forEach(v -> annotation(v, cls, errors));
                case RuntimeInvisibleAnnotationsAttribute a -> a.annotations().forEach(v -> annotation(v, cls, errors));
                case RuntimeVisibleTypeAnnotationsAttribute a -> a.annotations().forEach(v -> annotation(v.annotation(), cls, errors));
                case RuntimeInvisibleTypeAnnotationsAttribute a -> a.annotations().forEach(v -> annotation(v.annotation(), cls, errors));
                case RuntimeVisibleParameterAnnotationsAttribute a -> a.parameterAnnotations().forEach(p -> p.forEach(v -> annotation(v, cls, errors)));
                case RuntimeInvisibleParameterAnnotationsAttribute a -> a.parameterAnnotations().forEach(p -> p.forEach(v -> annotation(v, cls, errors)));
                case AnnotationDefaultAttribute a -> annotationValue(a.defaultValue(), cls, errors);
                case RecordAttribute r -> r.components().forEach(c -> {
                    checkType(c.descriptor().stringValue(), cls, errors);
                    attributes(c, cls, errors);
                });
                default -> { }
            }
        }
    }

    private static void annotation(Annotation annotation, String cls, List<String> errors) {
        checkType(annotation.className().stringValue(), cls, errors);
        annotation.elements().forEach(e -> annotationValue(e.value(), cls, errors));
    }

    private static void annotationValue(AnnotationValue value, String cls, List<String> errors) {
        switch (value) {
            case AnnotationValue.OfClass c -> checkType(c.className().stringValue(), cls, errors);
            case AnnotationValue.OfEnum e -> checkType(e.className().stringValue(), cls, errors);
            case AnnotationValue.OfAnnotation a -> annotation(a.annotation(), cls, errors);
            case AnnotationValue.OfArray a -> a.values().forEach(v -> annotationValue(v, cls, errors));
            default -> { }
        }
    }

    private static void checkMethod(Kind kind, String cls, MethodModel method, List<String> errors) {
        String name = method.methodName().stringValue();
        String where = cls + "." + name;
        attributes(method, cls, errors);
        checkType(method.methodType().stringValue(), cls, errors);
        var hot = method.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
                .flatMap(a -> a.annotations().stream()).filter(a -> a.classSymbol().equals(ClassDesc.of(HotPath.class.getName())))
                .findFirst();
        if ((kind == Kind.EMITTED && !name.startsWith("<") || dispatchName(name) || hot.isPresent())
                && !method.methodType().stringValue().startsWith("(" + CTX)) errors.add("B1-04 context parameter 0: " + where);
        var code = method.findAttribute(Attributes.code()).orElse(null);
        if (code == null) return;
        attributes(code, cls, errors);
        if (code.codeLength() > (name.equals("<clinit>") ? 4000 : 7500))
            errors.add("B1-01 " + where + " is " + code.codeLength() + " bytes");
        boolean lookup = hot.stream().flatMap(a -> a.elements().stream()).anyMatch(e -> e.name().equalsString("lookup")
                && e.value() instanceof AnnotationValue.OfBoolean flag && flag.booleanValue());
        int probes = 0;
        for (CodeElement op : code) {
            if (op instanceof InvokeDynamicInstruction indy && (kind == Kind.EMITTED || hot.isPresent()
                    && !indy.bootstrapMethod().owner().descriptorString().equals("Ljava/lang/invoke/StringConcatFactory;")))
                errors.add("B1-02 invokedynamic in " + where);
            if (op instanceof NewReferenceArrayInstruction array && hot.isPresent()
                    && array.componentType().asInternalName().equals("java/lang/Object")) errors.add("B1-06 array in " + where);
            if (!(op instanceof InvokeInstruction call)) continue;
            String owner = call.owner().asInternalName(), target = call.name().stringValue();
            if (call.opcode() == Opcode.INVOKEINTERFACE && (owner.startsWith("pl/codeloom/php/b1/") || dispatchName(target)
                    || call.type().stringValue().startsWith("(" + CTX)
                    || (kind == Kind.EMITTED || hot.isPresent()) && functionalInterface(owner)))
                errors.add("B1-03 interface call in " + where);
            if (call.opcode() == Opcode.INVOKESPECIAL && target.startsWith("invoke")
                    && (owner.equals(BASE) || call.type().stringValue().startsWith("(" + CTX + "Lpl/codeloom/php/b1/runtime/B1Object;")))
                errors.add("B1-03 nonvirtual callable invocation in " + where);
            if (hot.isEmpty()) continue;
            if (owner.equals("java/lang/StringBuilder") || owner.equals("java/lang/String") &&
                    (target.equals("getBytes") || target.equals("<init>") && call.type().stringValue().startsWith("([B")))
                errors.add("B1-05 string conversion in " + where);
            if (owner.equals("java/lang/Long") && target.equals("valueOf")) errors.add("B1-06 boxing in " + where);
            if (lookup && (target.equals("lower") || target.equals("toLowerCase"))) errors.add("B1-07 uncached name in " + where);
            if (lookup && target.equals("get") && (owner.equals("java/util/Map") || owner.equals("java/util/HashMap"))) probes++;
        }
        if (probes > 1) errors.add("B1-07 repeated lookup in " + where);
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
