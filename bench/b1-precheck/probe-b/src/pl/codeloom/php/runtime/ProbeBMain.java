package pl.codeloom.php.runtime;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.zip.CRC32;

import static pl.codeloom.php.runtime.PhpNull.NULL;

/**
 * Probe B CLI driver (Rev 2):
 *   check [corpus.php]
 *   d17
 *   hooks <rounds> <corpus.php>
 *   hooks-ctxb <rounds> <corpus.php>
 *   hooks-2ctx <rounds> <corpus.php>
 *   aget <rounds>
 *   post <rounds>
 *   mega <rounds>
 *   mega-ctxb <rounds>
 *   mega-2ctx <rounds>
 */
public final class ProbeBMain {
    static volatile long sink;
    static final String HOOKS_CHECKSUM = "393035:a61726cd";
    static final long AGET_CHECKSUM = 60000L;
    static final long POST_CHECKSUM = 130000L;
    static final long MEGA_CHECKSUM = 535000L;
    static final long MEGA_CTXB_CHECKSUM = 250L * (2140L + 100L);

    static void renderBytes(ByteArrayOutputStream out, Object value) {
        if (value instanceof PhpArray a) {
            out.write('[');
            for (int p = a.firstPosition(); p >= 0; p = a.nextPosition(p)) {
                Object k = a.keyAt(p);
                if (k instanceof ByteStr ks) {
                    out.writeBytes(ks.rawBytes());
                } else {
                    out.writeBytes(k.toString().getBytes(StandardCharsets.ISO_8859_1));
                }
                out.write('=');
                out.write('>');
                renderBytes(out, a.valueAt(p));
                out.write(',');
            }
            out.write(']');
        } else if (value instanceof ByteStr s) {
            out.writeBytes("string:".getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(s.rawBytes());
        } else if (value instanceof Long n) {
            out.writeBytes(("integer:" + n).getBytes(StandardCharsets.ISO_8859_1));
        } else if (value instanceof Boolean b) {
            out.writeBytes((b ? "true" : "false").getBytes(StandardCharsets.ISO_8859_1));
        } else if (value == NULL) {
            out.writeBytes("null".getBytes(StandardCharsets.ISO_8859_1));
        } else {
            throw new AssertionError("Unexpected result type: " + value.getClass());
        }
    }

    static String checksum(PhpArray a) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        renderBytes(out, a);
        byte[] bytes = out.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return bytes.length + ":" + HexFormat.of().toHexDigits((int) crc.getValue());
    }

    private static long uptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    static void runHooks(int rounds, Path corpus) throws Exception {
        PhpArray headings = Corpus.headings(corpus);
        PhpRequestContext ctx = new PhpRequestContext();
        HooksOpenWorld.registerHooksDomain(ctx, false, ByteStr.of("A:"));
        int half = rounds / 2;
        for (int round = 1; round <= rounds; round++) {
            if (round == half + 1) System.out.println("STEADY_START_MS " + uptimeMs());
            long t = System.nanoTime();
            PhpArray result = HooksOpenWorld.hooksKernel(ctx, headings);
            long ns = System.nanoTime() - t;
            String check = checksum(result);
            if (!HOOKS_CHECKSUM.equals(check)) {
                throw new AssertionError("hooks checksum mismatch: " + check);
            }
            System.out.println("hooks " + round + " " + ns + " " + check);
        }
        System.out.println("STEADY_END_MS " + uptimeMs());
        ctx.shutdown();
    }

    static void runHooksCtxB(int rounds, Path corpus) throws Exception {
        PhpArray headings = Corpus.headings(corpus);
        PhpRequestContext ctxB = new PhpRequestContext();
        HooksOpenWorld.registerHooksDomain(ctxB, true, ByteStr.of("B:"));
        long[] allCountB = {0L};
        PhpClosure allHook = new PhpClosure(ctxB, null) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                allCountB[0]++;
                return NULL;
            }
        };
        HooksOpenWorld.addFilter(ctxB, HooksOpenWorld.ALL_KEY, allHook, 10, 1);
        allHook.release();

        String expectedB = checksum(HooksOpenWorld.hooksKernel(ctxB, headings));
        if (HOOKS_CHECKSUM.equals(expectedB)) throw new AssertionError("CtxB must diverge");
        allCountB[0] = 0L;
        int half = rounds / 2;
        for (int round = 1; round <= rounds; round++) {
            if (round == half + 1) System.out.println("STEADY_START_MS " + uptimeMs());
            long t = System.nanoTime();
            PhpArray resB = HooksOpenWorld.hooksKernel(ctxB, headings);
            long ns = System.nanoTime() - t;
            String checkB = checksum(resB);
            if (!expectedB.equals(checkB)) throw new AssertionError("hooks-ctxb drift: " + checkB);
            System.out.println("hooks-ctxb " + round + " " + ns + " " + checkB);
        }
        System.out.println("STEADY_END_MS " + uptimeMs());
        if (allCountB[0] == 0L) throw new AssertionError("CtxB all-hook never fired");
        ctxB.shutdown();
    }

    /** Alternating two-context benchmark: times BOTH CtxA and CtxB in every round! */
    static void runHooks2Ctx(int rounds, Path corpus) throws Exception {
        PhpArray headings = Corpus.headings(corpus);
        PhpRequestContext ctxA = new PhpRequestContext();
        PhpRequestContext ctxB = new PhpRequestContext();
        HooksOpenWorld.registerHooksDomain(ctxA, false, ByteStr.of("A:"));
        HooksOpenWorld.registerHooksDomain(ctxB, true, ByteStr.of("B:"));

        long[] allCountB = {0L};
        PhpClosure allHook = new PhpClosure(ctxB, null) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                allCountB[0]++;
                return NULL;
            }
        };
        HooksOpenWorld.addFilter(ctxB, HooksOpenWorld.ALL_KEY, allHook, 10, 1);
        allHook.release();

        String expectedB = checksum(HooksOpenWorld.hooksKernel(ctxB, headings));
        if (HOOKS_CHECKSUM.equals(expectedB)) throw new AssertionError("CtxB must diverge from CtxA");
        allCountB[0] = 0L;
        int half = rounds / 2;

        for (int round = 1; round <= rounds; round++) {
            if (round == half + 1) System.out.println("STEADY_START_MS " + uptimeMs());

            long tb = System.nanoTime();
            PhpArray resB = HooksOpenWorld.hooksKernel(ctxB, headings);
            long nsB = System.nanoTime() - tb;
            String checkB = checksum(resB);
            if (!expectedB.equals(checkB)) throw new AssertionError("CtxB checksum drift: " + checkB);
            System.out.println("hooks-2ctx-b " + round + " " + nsB + " " + checkB);

            long ta = System.nanoTime();
            PhpArray resA = HooksOpenWorld.hooksKernel(ctxA, headings);
            long nsA = System.nanoTime() - ta;
            String checkA = checksum(resA);
            if (!HOOKS_CHECKSUM.equals(checkA)) throw new AssertionError("CtxA checksum mismatch: " + checkA);
            System.out.println("hooks-2ctx-a " + round + " " + nsA + " " + checkA);
        }
        System.out.println("STEADY_END_MS " + uptimeMs());
        if (allCountB[0] == 0L) throw new AssertionError("CtxB all-hook never fired");
        ctxA.shutdown();
        ctxB.shutdown();
    }

    static void runHot(String name, int rounds) {
        PhpRequestContext ctx = new PhpRequestContext();
        HotOpenWorld kernel = new HotOpenWorld(ctx, false);
        long expected = switch (name) {
            case "aget" -> AGET_CHECKSUM;
            case "post" -> POST_CHECKSUM;
            case "mega" -> MEGA_CHECKSUM;
            default -> throw new IllegalArgumentException(name);
        };
        int half = rounds / 2;
        for (int round = 0; round < rounds; round++) {
            if (round == half) System.out.println("STEADY_START_MS " + uptimeMs());
            long sum = 0;
            long t = System.nanoTime();
            switch (name) {
                case "aget" -> { for (int n = 0; n < 250; n++) sum += kernel.aget(); }
                case "post" -> { for (int n = 0; n < 250; n++) sum += kernel.post(); }
                case "mega" -> { for (int n = 0; n < 250; n++) sum += kernel.mega(); }
            }
            long ns = System.nanoTime() - t;
            sink = sum;
            if (sum != expected) throw new AssertionError(name + " checksum " + sum + " != " + expected);
            System.out.println(name + " " + round + " " + (long) Math.rint(ns / 250.0) + " " + sum);
        }
        System.out.println("STEADY_END_MS " + uptimeMs());
        kernel.close();
        ctx.shutdown();
    }

    static void runMegaCtxB(int rounds) {
        PhpRequestContext ctxB = new PhpRequestContext();
        HotOpenWorld kernelB = new HotOpenWorld(ctxB, true);
        long[] allCountB = {0L};
        PhpClosure allHook = new PhpClosure(ctxB, null) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                allCountB[0]++;
                return NULL;
            }
        };
        HooksOpenWorld.addFilter(ctxB, HooksOpenWorld.ALL_KEY, allHook, 10, 1);
        allHook.release();
        int half = rounds / 2;
        for (int round = 0; round < rounds; round++) {
            if (round == half) System.out.println("STEADY_START_MS " + uptimeMs());
            long sumB = 0;
            long t = System.nanoTime();
            for (int n = 0; n < 250; n++) sumB += kernelB.mega();
            long ns = System.nanoTime() - t;
            sink = sumB;
            if (sumB != MEGA_CTXB_CHECKSUM) throw new AssertionError("mega-ctxb checksum " + sumB);
            System.out.println("mega-ctxb " + round + " " + (long) Math.rint(ns / 250.0) + " " + sumB);
        }
        System.out.println("STEADY_END_MS " + uptimeMs());
        if (allCountB[0] == 0L) throw new AssertionError("mega-ctxb all-hook never fired");
        kernelB.close();
        ctxB.shutdown();
    }

    static void runMega2Ctx(int rounds) {
        PhpRequestContext ctxA = new PhpRequestContext();
        PhpRequestContext ctxB = new PhpRequestContext();
        HotOpenWorld kernelA = new HotOpenWorld(ctxA, false);
        HotOpenWorld kernelB = new HotOpenWorld(ctxB, true);

        long[] allCountB = {0L};
        PhpClosure allHook = new PhpClosure(ctxB, null) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                allCountB[0]++;
                return NULL;
            }
        };
        HooksOpenWorld.addFilter(ctxB, HooksOpenWorld.ALL_KEY, allHook, 10, 1);
        allHook.release();
        int half = rounds / 2;

        for (int round = 0; round < rounds; round++) {
            if (round == half) System.out.println("STEADY_START_MS " + uptimeMs());

            long sumB = 0;
            long tb = System.nanoTime();
            for (int n = 0; n < 250; n++) sumB += kernelB.mega();
            long nsB = System.nanoTime() - tb;
            if (sumB != MEGA_CTXB_CHECKSUM) throw new AssertionError("mega CtxB checksum " + sumB);
            System.out.println("mega-2ctx-b " + round + " " + (long) Math.rint(nsB / 250.0) + " " + sumB);

            long sumA = 0;
            long ta = System.nanoTime();
            for (int n = 0; n < 250; n++) sumA += kernelA.mega();
            long nsA = System.nanoTime() - ta;
            sink = sumA;
            if (sumA != MEGA_CHECKSUM) throw new AssertionError("mega CtxA checksum " + sumA);
            System.out.println("mega-2ctx-a " + round + " " + (long) Math.rint(nsA / 250.0) + " " + sumA);
        }
        System.out.println("STEADY_END_MS " + uptimeMs());
        if (allCountB[0] == 0L) throw new AssertionError("mega CtxB all-hook never fired");
        kernelA.close();
        kernelB.close();
        ctxA.shutdown();
        ctxB.shutdown();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Usage: ProbeBMain <check|d17|hooks|hooks-ctxb|hooks-2ctx|aget|post|mega|mega-ctxb|mega-2ctx> ...");
        }
        switch (args[0]) {
            case "check" -> Checks.runAll(args.length > 1 ? Path.of(args[1]) : null);
            case "d17" -> Checks.runD17Fixture(System.out);
            case "hooks" -> runHooks(Integer.parseInt(args[1]), Path.of(args[2]));
            case "hooks-ctxb" -> runHooksCtxB(Integer.parseInt(args[1]), Path.of(args[2]));
            case "hooks-2ctx" -> runHooks2Ctx(Integer.parseInt(args[1]), Path.of(args[2]));
            case "aget", "post", "mega" -> runHot(args[0], Integer.parseInt(args[1]));
            case "mega-ctxb" -> runMegaCtxB(Integer.parseInt(args[1]));
            case "mega-2ctx" -> runMega2Ctx(Integer.parseInt(args[1]));
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }
}
