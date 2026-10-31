package pl.codeloom.php.ffi;

import java.util.List;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

/**
 * The native image's half of {@link NativeLibrary}: it initializes the classes that declare downcalls and upcalls
 * at build time (so their handles are constants) and registers exactly what they declared, a downcall stub per
 * descriptor and a direct upcall per target; the classes that hold native state initialize at run time. Enabled
 * by META-INF/native-image/pl.codeloom/graalphp/native-image.properties. The tracing agent's configuration must
 * not register foreign calls of its own (bench/native/agent.sh drops them, image.sh checks).
 */
public final class NativeCallsFeature implements Feature {
    /** The classes that declare native functions (NativeLibrary.downcall, Upcall.of): built with the image. */
    static final List<String> BINDINGS = List.of();
    /** The classes that hold native state (upcall stubs, native memory, a library's initialization): run time. */
    static final List<String> NATIVE_STATE = List.of();

    @Override
    public void duringSetup(DuringSetupAccess access) {
        for (String name : NATIVE_STATE) {
            RuntimeClassInitialization.initializeAtRunTime(load(access, name));
        }
        for (String name : BINDINGS) {
            Class<?> binding = load(access, name);
            RuntimeClassInitialization.initializeAtBuildTime(binding);
            try {
                Class.forName(name, true, binding.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        for (NativeLibrary.Declared declared : NativeLibrary.declaredForRegistration()) {
            if (declared.upcallTarget() == null) {
                RuntimeForeignAccess.registerForDowncall(declared.descriptor(), (Object[]) declared.options());
            } else {
                RuntimeForeignAccess.registerForDirectUpcall(declared.upcallTarget(), declared.descriptor(),
                        (Object[]) declared.options());
            }
        }
    }

    private static Class<?> load(DuringSetupAccess access, String name) {
        Class<?> loaded = access.findClassByName(name);
        if (loaded == null) {
            throw new IllegalStateException("NativeCallsFeature: no class " + name);
        }
        return loaded;
    }
}
