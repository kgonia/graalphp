package pl.codeloom.php.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.graalvm.nativeimage.ImageInfo;

/**
 * A native library called through the FFM API: the one way this project binds native code (wiki/native-calls.md).
 * Declare the library and its functions as static finals of a class that a native image initializes at build
 * time, and list that class in {@link NativeCallsFeature}:
 *
 * <pre>
 * static final NativeLibrary PCRE2 = NativeLibrary.named("php.pcre2", "libpcre2-8.so.0", "libpcre2-8-0.dll");
 * static final MethodHandle MATCH = PCRE2.downcall("pcre2_match_8", FunctionDescriptor.of(...));
 * ... (int) MATCH.invokeExact(code, subject, ...)
 * </pre>
 *
 * <p>On the JVM a downcall is bound to its function's address, looked up when the declaring class initializes, as
 * {@code Linker.downcallHandle(address, descriptor)}. In a native image the handle is made while the image is
 * built: an address-less handle, with the function's address looked up at the first call at run time and passed
 * as its first argument. Made at build time, the handle is a constant, so a call is a direct call of the
 * downcall's stub (12-20 ns). A handle made at run time is not one: every call would take the generic
 * method-handle path (about 2 us), so making a downcall at run time in an image is an error. Nothing of the
 * library (its handle, an address) is looked up at build time: an image may keep neither native memory nor the
 * build's addresses in its heap.
 */
public final class NativeLibrary {
    private static final Linker LINKER = Linker.nativeLinker();
    /** C's long (32 bits on Windows) and size_t, as the platform defines them. */
    public static final ValueLayout C_LONG = (ValueLayout) LINKER.canonicalLayouts().get("long");
    public static final ValueLayout SIZE_T = (ValueLayout) LINKER.canonicalLayouts().get("size_t");
    /**
     * The C library and whatever else the process has loaded ({@link Linker#defaultLookup}). A native image
     * built with -H:+StaticExecutable has no default lookup.
     */
    public static final NativeLibrary LIBC = new NativeLibrary(null, null, null);

    private static final MethodHandle ADDRESS;
    /** The descriptors and upcall targets declared so far, which a native image registers (NativeCallsFeature). */
    private static final List<Declared> DECLARED = new ArrayList<>();
    /** Set once a native image has registered its stubs: a downcall declared later would have none. */
    private static volatile boolean registered;

    static {
        try {
            ADDRESS = MethodHandles.lookup().findVirtual(Symbol.class, "address",
                    MethodType.methodType(MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String property;
    private final String unixName;
    private final String windowsName;
    /** Opened at the first lookup: at run time in a native image. */
    private volatile SymbolLookup lookup;

    private NativeLibrary(String property, String unixName, String windowsName) {
        this.property = property;
        this.unixName = unixName;
        this.windowsName = windowsName;
    }

    /**
     * A shared library: the path in system property {@code property} if it is set, else {@code unixName}, or on
     * Windows {@code windowsName} (from Git for Windows' mingw64 when it is there).
     */
    public static NativeLibrary named(String property, String unixName, String windowsName) {
        return new NativeLibrary(property, unixName, windowsName);
    }

    /**
     * The function {@code symbol} with {@code descriptor} as a downcall handle, called with invokeExact as any
     * (see the class comment). On the JVM a library that doesn't load fails here; a missing symbol fails the
     * calls ({@link #has} tells first).
     */
    public MethodHandle downcall(String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
        if (ImageInfo.inImageRuntimeCode()) {
            throw new IllegalStateException("downcall " + symbol + " made at run time in a native image, where it "
                    + "isn't a constant: declare it in a class the image initializes at build time");
        }
        if (ImageInfo.inImageBuildtimeCode()) {
            if (registered) {
                throw new IllegalStateException("downcall " + symbol + " declared after the native image "
                        + "registered its stubs: list its class in NativeCallsFeature.BINDINGS");
            }
            synchronized (DECLARED) {
                DECLARED.add(new Declared(descriptor, options, null));
            }
            return MethodHandles.foldArguments(LINKER.downcallHandle(descriptor, options),
                    ADDRESS.bindTo(new Symbol(this, symbol)));
        }
        Optional<MemorySegment> address = lookup().find(symbol);
        if (address.isEmpty()) {
            return missing(symbol, descriptor.toMethodType());
        }
        return LINKER.downcallHandle(address.get(), descriptor, options);
    }

    /** Whether the library has {@code symbol}; opens the library (at run time in a native image). */
    public boolean has(String symbol) {
        return lookup().find(symbol).isPresent();
    }

    /** The library's symbols, opening it on first use; throws if it doesn't load. */
    public SymbolLookup lookup() {
        SymbolLookup opened = lookup;
        if (opened == null) {
            if (ImageInfo.inImageBuildtimeCode()) {
                throw new IllegalStateException("native library opened while the image is built: its addresses "
                        + "would be the build's");
            }
            synchronized (this) {
                opened = lookup;
                if (opened == null) {
                    opened = property == null && unixName == null ? LINKER.defaultLookup()
                            : SymbolLookup.libraryLookup(path(), Arena.global());
                    lookup = opened;
                }
            }
        }
        return opened;
    }

    private String path() {
        String configured = property == null ? null : System.getProperty(property);
        if (configured != null) {
            return configured;
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path git = Path.of("C:/Program Files/Git/mingw64/bin", windowsName);
            return Files.exists(git) ? git.toString() : windowsName;
        }
        return unixName;
    }

    /** A handle of the function's type that fails each call: the library lacks the symbol. */
    private static MethodHandle missing(String symbol, MethodType type) {
        MethodHandle thrower = MethodHandles.throwException(type.returnType(), UnsatisfiedLinkError.class);
        MethodHandle failing = MethodHandles.foldArguments(thrower,
                MethodHandles.constant(UnsatisfiedLinkError.class, new UnsatisfiedLinkError("no symbol " + symbol))
                        .asType(MethodType.methodType(UnsatisfiedLinkError.class)));
        return MethodHandles.dropArguments(failing, 0, type.parameterList());
    }

    /** An upcall declared by {@link Upcall#of}, to register. */
    static void declareUpcall(MethodHandle target, FunctionDescriptor descriptor, Linker.Option... options) {
        if (ImageInfo.inImageBuildtimeCode()) {
            if (registered) {
                throw new IllegalStateException("upcall " + target + " declared after the native image registered "
                        + "its stubs: list its class in NativeCallsFeature.BINDINGS");
            }
            synchronized (DECLARED) {
                DECLARED.add(new Declared(descriptor, options, target));
            }
        }
    }

    /** What the bindings declared, for the image's registration; afterwards nothing more may be declared. */
    static List<Declared> declaredForRegistration() {
        synchronized (DECLARED) {
            registered = true;
            return List.copyOf(DECLARED);
        }
    }

    /** A downcall's or (with a target) a direct upcall's stub, as the native image registers it. */
    record Declared(FunctionDescriptor descriptor, Linker.Option[] options, MethodHandle upcallTarget) {
    }

    /** A function's address, looked up at its first call (run time). */
    private static final class Symbol {
        private final NativeLibrary library;
        private final String name;
        private volatile MemorySegment address;

        Symbol(NativeLibrary library, String name) {
            this.library = library;
            this.name = name;
        }

        @SuppressWarnings("unused") // through ADDRESS
        MemorySegment address() {
            MemorySegment found = address;
            if (found == null) {
                found = library.lookup().find(name)
                        .orElseThrow(() -> new UnsatisfiedLinkError("no symbol " + name));
                address = found;
            }
            return found;
        }
    }
}
