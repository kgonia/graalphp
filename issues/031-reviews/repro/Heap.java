import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import java.io.*;

public class Heap {
    public static void main(String[] args) throws Exception {
        System.setProperty("polyglot.engine.WarnVirtualThreadSupport", "false");
        int rounds = Integer.parseInt(args[1]);
        try (Engine engine = Engine.newBuilder("php").allowExperimentalOptions(true).option("php.Parser", "java").build()) {
            for (int r = 1; r <= rounds; r++) {
                try (Context context = Context.newBuilder("php").engine(engine).allowIO(IOAccess.ALL)
                        .allowNativeAccess(true).allowExperimentalOptions(true).out(OutputStream.nullOutputStream())
                        .option("php.Parser", "java").arguments("php", new String[] {args[0], Integer.toString(r)}).build()) {
                    context.eval(Source.newBuilder("php", new File(args[0])).build());
                }
                if (r % 10 == 0) {
                    System.gc(); System.gc();
                    Runtime rt = Runtime.getRuntime();
                    System.out.println("after " + r + " requests: live heap " + (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024) + " MB");
                }
            }
        }
    }
}
