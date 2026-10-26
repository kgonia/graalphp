import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

// Cost of one pcre2_jit_match downcall per FFM handle kind and arena kind: a 13-byte subject (slugs' typical
// match), and a 4 KB scan right after Java code that used AVX (the critical stub skips vzeroupper).
// Run in gate1-truffle: java --enable-native-access=ALL-UNNAMED bench/ffm/FfmBench.java
public class FfmBench {
    static final Linker LINKER = Linker.nativeLinker();
    static final SymbolLookup LIB = SymbolLookup.libraryLookup("/opt/pcre2-php/libpcre2-8.so", Arena.global());
    static final FunctionDescriptor SIG = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    static final MethodHandle NORMAL = LINKER.downcallHandle(LIB.find("pcre2_jit_match_8").get(), SIG);
    static final MethodHandle CRITICAL = LINKER.downcallHandle(LIB.find("pcre2_jit_match_8").get(), SIG,
            Linker.Option.critical(false));
    /** The same call with the pointers passed as raw addresses: no segment checks in the handle's adapter. */
    static final MethodHandle NORMAL_LONGS = LINKER.downcallHandle(LIB.find("pcre2_jit_match_8").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG));
    static final MethodHandle CRITICAL_HEAP = LINKER.downcallHandle(LIB.find("pcre2_jit_match_8").get(), SIG,
            Linker.Option.critical(true));

    public static void main(String[] args) throws Throwable {
        MethodHandle compile = LINKER.downcallHandle(LIB.find("pcre2_compile_8").get(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle jit = LINKER.downcallHandle(LIB.find("pcre2_jit_compile_8").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle data = LINKER.downcallHandle(LIB.find("pcre2_match_data_create_from_pattern_8").get(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        Arena arena = Arena.global();
        MemorySegment pattern = arena.allocateFrom("[\\x80-\\xff]");
        MemorySegment code = (MemorySegment) compile.invokeExact(pattern, pattern.byteSize() - 1, 0,
                arena.allocate(4), arena.allocate(8), MemorySegment.NULL);
        int ignored = (int) jit.invokeExact(code, 1);
        MemorySegment md = (MemorySegment) data.invokeExact(code, MemorySegment.NULL);
        byte[] subject = "hello-world-x".getBytes();
        MemorySegment nativeGlobal = arena.allocate(64);
        MemorySegment.copy(subject, 0, nativeGlobal, ValueLayout.JAVA_BYTE, 0, subject.length);
        MemorySegment nativeShared = Arena.ofShared().allocate(64);
        MemorySegment.copy(subject, 0, nativeShared, ValueLayout.JAVA_BYTE, 0, subject.length);
        MemorySegment nativeAuto = Arena.ofAuto().allocate(64);
        MemorySegment.copy(subject, 0, nativeAuto, ValueLayout.JAVA_BYTE, 0, subject.length);
        MemorySegment heap = MemorySegment.ofArray(subject);
        long n = subject.length;
        int noUtf = 0x40000000;
        // a 4 KB no-match scan, after Java code that leaves wide vector registers dirty (array copies use AVX)
        MemorySegment big = arena.allocate(4096);
        big.fill((byte) 'a');
        long[] from = new long[64];
        long[] to = new long[64];
        for (int round = 0; round < 2; round++) {
            time("4KB normal", () -> (int) NORMAL.invokeExact(code, big, 4096L, 0L, noUtf, md, MemorySegment.NULL));
            time("4KB critical(false)", () -> (int) CRITICAL.invokeExact(code, big, 4096L, 0L, noUtf, md, MemorySegment.NULL));
            time("4KB normal + AVX before", () -> { System.arraycopy(from, 0, to, 0, 64); return (int) NORMAL.invokeExact(code, big, 4096L, 0L, noUtf, md, MemorySegment.NULL); });
            time("4KB critical + AVX before", () -> { System.arraycopy(from, 0, to, 0, 64); return (int) CRITICAL.invokeExact(code, big, 4096L, 0L, noUtf, md, MemorySegment.NULL); });
        }
        for (int round = 0; round < 3; round++) {
            time("normal, global segment", () -> (int) NORMAL.invokeExact(code, nativeGlobal, n, 0L, noUtf, md, MemorySegment.NULL));
            time("normal, shared segment", () -> (int) NORMAL.invokeExact(code, nativeShared, n, 0L, noUtf, md, MemorySegment.NULL));
            time("normal, auto segment", () -> (int) NORMAL.invokeExact(code, nativeAuto, n, 0L, noUtf, md, MemorySegment.NULL));
            long codeAddress = code.address();
            long subjectAddress = nativeAuto.address();
            long dataAddress = md.address();
            time("normal, raw long addresses", () -> (int) NORMAL_LONGS.invokeExact(codeAddress, subjectAddress, n, 0L, noUtf, dataAddress, 0L));
            time("critical(false), auto", () -> (int) CRITICAL.invokeExact(code, nativeAuto, n, 0L, noUtf, md, MemorySegment.NULL));
            time("critical(false), global", () -> (int) CRITICAL.invokeExact(code, nativeGlobal, n, 0L, noUtf, md, MemorySegment.NULL));
            time("critical(true), heap array", () -> (int) CRITICAL_HEAP.invokeExact(code, heap, n, 0L, noUtf, md, MemorySegment.NULL));
        }
    }

    interface Call {
        int run() throws Throwable;
    }

    static void time(String label, Call call) throws Throwable {
        int sink = 0;
        int count = label.startsWith("4KB") ? 200_000 : 5_000_000;
        for (int i = 0; i < count; i++) {
            sink += call.run();
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            sink += call.run();
        }
        long ns = System.nanoTime() - t0;
        System.out.printf("%-28s %6.1f ns/call  (rc sum %d)%n", label, (double) ns / count, sink);
    }
}
