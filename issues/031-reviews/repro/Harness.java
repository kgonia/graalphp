import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import java.io.*;

public class Harness {
    public static void main(String[] args) throws Exception {
        System.setProperty("polyglot.engine.WarnVirtualThreadSupport", "false");
        try (Engine engine = Engine.newBuilder("php").allowExperimentalOptions(true).option("php.Parser", "java").build()) {
            for (String script : args) {
                long t = System.nanoTime();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Context leaked = null;
                try (Context context = Context.newBuilder("php").engine(engine).allowIO(IOAccess.ALL)
                        .allowNativeAccess(true).allowCreateThread(true).allowExperimentalOptions(true)
                        .out(out).err(out).option("php.Parser", "java").build()) {
                    leaked = context;
                    context.eval(Source.newBuilder("php", new File(script)).build());
                } catch (PolyglotException e) {
                    System.out.println("[" + script + "] PolyglotException exit=" + e.isExit() + " " + e.getMessage());
                    for (Throwable s : e.getSuppressed()) System.out.println("   suppressed: " + s);
                } catch (Throwable e) {
                    System.out.println("[" + script + "] other: " + e);
                    for (Throwable s : e.getSuppressed()) System.out.println("   suppressed: " + s);
                }
                System.out.print(out.toString());
                System.out.println("[" + script + "] done in " + (System.nanoTime() - t) / 1_000_000 + " ms");
            }
        }
        System.out.println("engine closed");
    }
}
