package pl.codeloom.php.b1;

import pl.codeloom.php.b1.runtime.B1Callable;
import pl.codeloom.php.b1.runtime.B1RequestContext;
import pl.codeloom.php.ir.IrUnit;
import pl.codeloom.php.ir.PhpIrLowerer;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

import static pl.codeloom.php.ir.AsciiCase.lower;

/** Only the named vtable, its CV names and signature survive linking. */
public final class B1Compiler {
    private B1Compiler() {}
    public record CompiledFile(B1Callable entry, int lintedClasses) {}

    public static CompiledFile compile(Path file, B1RequestContext ctx) throws IOException {
        return compile(PhpIrLowerer.lowerFile(file.toString(), Files.readAllBytes(file)), ctx);
    }

    public static CompiledFile compile(IrUnit unit, B1RequestContext ctx) {
        var statementBound = new java.util.HashSet<String>();
        for (var cls : unit.classes()) {
            if (ctx.isInternalClass(lower(cls.name())) || cls.methods().stream()
                    .anyMatch(id -> unit.functions().get(id).name().equalsIgnoreCase("__toString"))) {
                statementBound.add(cls.name());
            }
        }
        var linked = new ArrayList<B1Callable>();
        for (var function : unit.functions()) {
            byte[] bytes = B1ClassFileEmitter.emit(B1CfgLowerer.lower(function, statementBound));
            B1Lint.requireClean(bytes, B1Lint.Kind.EMITTED);
            try {
                var lookup = MethodHandles.lookup().defineHiddenClass(bytes, false);
                var ctor = lookup.findConstructor(lookup.lookupClass(),
                        MethodType.methodType(void.class, boolean[].class, String[].class));
                boolean[] refs = new boolean[function.params().size()];
                for (int i = 0; i < refs.length; i++) refs[i] = function.params().get(i).byRef();
                var callable = (B1Callable)ctor.invoke(refs, function.locals().toArray(String[]::new));
                callable.display = function.owner() == null ? function.name() : function.owner() + "::" + function.name();
                linked.add(callable);
            } catch (RuntimeException | Error ex) { throw ex; }
            catch (Throwable ex) { throw new IllegalStateException("linking " + function.name(), ex); }
        }
        var functions = new HashMap<String, B1Callable>();
        for (var f : unit.functions()) {
            if (!f.fileBody() && f.owner() == null) functions.put(lower(f.name()), linked.get(f.id()));
        }
        var classes = new HashMap<String, B1RequestContext.ClassDef>();
        var lateClasses = new HashMap<String, B1RequestContext.ClassDef>();
        for (var cls : unit.classes()) {
            var methods = new HashMap<String, B1Callable>();
            for (int id : cls.methods()) methods.put(lower(unit.functions().get(id).name()), linked.get(id));
            (statementBound.contains(cls.name()) ? lateClasses : classes).put(lower(cls.name()), new B1RequestContext.ClassDef(cls.name(), cls.properties(), methods));
        }
        ctx.install(unit.file(), linked.getFirst(), functions, classes, lateClasses);
        return new CompiledFile(linked.getFirst(), linked.size());
    }
}
