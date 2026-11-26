package pl.codeloom.php.b1;

import pl.codeloom.php.b1.runtime.B1RequestContext;
import pl.codeloom.php.b1.runtime.B1Thrown;
import pl.codeloom.php.ir.PhpCompileFatal;
import pl.codeloom.php.ir.PhpIrLowerer;
import pl.codeloom.php.parser.zend.ZendCompileError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** B1BatchMain phpt-root [--timeout=seconds] [--source-root=oracle-path] directory|case.php|@case-list ...
 * Uses the extractor's manifest (excludes support files and SKIPIF scripts) and PHP's
 * oracle ledger. Each runnable case gets an isolated, forcibly terminable stock JVM. */
public final class B1BatchMain {
    private static final int UNSUPPORTED = 77;
    private enum Outcome { pass, unsupported, diff, error, timeout }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--worker")) { worker(Path.of(args[1]),args[2]); return; }
        if (args.length < 2) throw new IllegalArgumentException("Expected phpt root and case selectors");
        Path root = Path.of(args[0]);
        var selectors = new LinkedHashSet<String>();
        int seconds = 10;
        String sourceRoot = root.resolve("cases").toAbsolutePath().toString();
        for (String arg : Arrays.copyOfRange(args,1,args.length)) {
            if (arg.startsWith("--timeout=")) seconds = Integer.parseInt(arg.substring(10));
            else if (arg.startsWith("--source-root=")) sourceRoot = arg.substring(14);
            else if (arg.startsWith("@")) selectors.addAll(Files.readAllLines(Path.of(arg.substring(1))));
            else selectors.add(arg);
        }
        if (seconds <= 0) throw new IllegalArgumentException("Timeout must be positive");
        var ledger = new HashMap<String,String[]>();
        for (var row : Files.readAllLines(root.resolve("expected/results.txt"))) {
            String[] fields = row.split("\t");
            ledger.put(fields[0],fields);
        }
        var manifest = Files.readAllLines(root.resolve("cases/cases.txt"));
        var selected = new HashSet<String>();
        var total = new EnumMap<Outcome,Integer>(Outcome.class);
        for (String selector : selectors) {
            var counts = new EnumMap<Outcome,Integer>(Outcome.class);
            String prefix = selector.replace('\\','/').replaceAll("/+$","");
            boolean found = false;
            for (String name : manifest) {
                if (!name.equals(prefix) && !name.startsWith(prefix + "/")) continue;
                found = true;
                if (!selected.add(name)) continue;
                var row = ledger.get(name);
                if (row != null && row[1].equals("skip")) continue;
                var result = row == null ? Outcome.error : row[1].equals("timeout") ? Outcome.timeout :
                        compare(root,name,sourceRoot,Integer.parseInt(row[2]),seconds);
                counts.merge(result,1,Integer::sum);
                total.merge(result,1,Integer::sum);
                System.out.println(result + "\t" + name);
            }
            if (!found) throw new IllegalArgumentException("No extracted cases matching " + selector);
            summary(prefix,counts);
        }
        summary("total",total);
    }
    private static void summary(String label,EnumMap<Outcome,Integer> counts) {
        System.out.print(label);
        for (var result : Outcome.values()) System.out.print(" " + result + "=" + counts.getOrDefault(result,0));
        System.out.println();
    }
    private static Outcome compare(Path root,String name,String sourceRoot,int expectedExit,int seconds) throws Exception {
        Path expected = root.resolve("expected").resolve(name + ".out");
        if (!Files.isRegularFile(expected)) return Outcome.error;
        Path actual = Files.createTempFile("b1-phpt-", ".out");
        try {
            String java = ProcessHandle.current().info().command().orElseThrow();
            Process child = new ProcessBuilder(java,"-Xmx192m","-cp",System.getProperty("java.class.path"),
                    B1BatchMain.class.getName(),"--worker",root.resolve("cases").resolve(name).toString(),sourceRoot + "/" + name)
                    .redirectOutput(actual.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                if (!child.waitFor(seconds,TimeUnit.SECONDS)) return Outcome.timeout;
                int code = child.exitValue();
                if (code == UNSUPPORTED) return Outcome.unsupported;
                if (code != 0 && code != 255) return Outcome.error;
                return code == expectedExit && Files.mismatch(actual,expected) == -1 ? Outcome.pass : Outcome.diff;
            } finally {
                if (child.isAlive()) child.destroyForcibly().waitFor();
            }
        } finally { Files.deleteIfExists(actual); }
    }
    private static void worker(Path file,String logicalPath) throws IOException {
        try {
            // tests/phpt/oracle.sh uses display_errors=0. Keep program stdout binary clean.
            var ctx = new B1RequestContext(System.out,false);
            ctx.runScript(B1Compiler.compile(PhpIrLowerer.lowerFile(logicalPath,Files.readAllBytes(file)),ctx).entry());
        } catch (PhpIrLowerer.UnsupportedIr unsupported) { System.exit(UNSUPPORTED); }
        catch (PhpCompileFatal | B1Thrown | ZendCompileError phpFatal) { System.exit(255); }
    }
}
