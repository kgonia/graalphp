package pl.codeloom.php.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import org.graalvm.nativeimage.ImageInfo;

/**
 * A static method that native code calls back: a direct upcall, which a native image compiles into its own stub
 * (registered by {@link NativeCallsFeature}); an upcall registered by its descriptor alone goes through the generic
 * method-handle path there, about 100 times slower. Declare it as a static final next to the downcalls (a class
 * initialized at build time); make its stub with {@link #stub} in a class initialized at run time, since a stub is
 * native memory.
 */
public final class Upcall {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle target;
    private final FunctionDescriptor descriptor;

    private Upcall(MethodHandle target, FunctionDescriptor descriptor) {
        this.target = target;
        this.descriptor = descriptor;
    }

    /** Static method {@code method} of {@code owner} (found with {@code lookup}), of the descriptor's type. */
    public static Upcall of(MethodHandles.Lookup lookup, Class<?> owner, String method,
                            FunctionDescriptor descriptor) {
        try {
            MethodHandle target = lookup.findStatic(owner, method, descriptor.toMethodType());
            NativeLibrary.declareUpcall(target, descriptor);
            return new Upcall(target, descriptor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new stub, alive for the process: a C function pointer to the method. Run time only. */
    public MemorySegment stub() {
        if (ImageInfo.inImageBuildtimeCode()) {
            throw new IllegalStateException("upcall stub made while the image is built: it is native memory");
        }
        return LINKER.upcallStub(target, descriptor, Arena.global());
    }
}
