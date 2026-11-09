import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

public class Cancel {
    public static void main(String[] args) throws Exception {
        System.setProperty("polyglot.engine.WarnVirtualThreadSupport", "false");
        Engine engine = Engine.newBuilder("php").allowExperimentalOptions(true).option("php.Parser", "java").build();
        for (String script : args) {
            Context context = Context.newBuilder("php").engine(engine).allowIO(IOAccess.ALL)
                    .allowNativeAccess(true).allowCreateThread(true).allowExperimentalOptions(true)
                    .option("php.Parser", "java").build();
            Thread runner = new Thread(() -> {
                try { context.eval(Source.newBuilder("php", new File(script)).build()); System.out.println("eval returned"); }
                catch (PolyglotException e) { System.out.println("eval: cancelled=" + e.isCancelled() + " exit=" + e.isExit() + " " + e.getMessage()); }
                catch (Throwable t) { System.out.println("eval: " + t); }
            });
            runner.setDaemon(true);
            runner.start();
            Thread.sleep(Long.parseLong(System.getProperty("wait", "1500")));
            long t = System.nanoTime();
            Thread closer = new Thread(() -> {
                try { context.close(true); System.out.println("close(true) returned"); } catch (Throwable e) { System.out.println("close: " + e); }
            });
            closer.setDaemon(true);
            closer.start();
            closer.join(Long.parseLong(System.getProperty("limit", "8000")));
            System.out.println("[" + script + "] close " + (closer.isAlive() ? "STILL BLOCKED" : "done") + " after " + (System.nanoTime() - t) / 1_000_000 + " ms");
        }
        System.exit(0);
    }
}
