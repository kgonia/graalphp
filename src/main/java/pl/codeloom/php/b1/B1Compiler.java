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
    public record CompiledFile(B1Callable entry, int lintedClasses, int outlinedHelperCount, int maxMethodBytes) {}

    public static CompiledFile compile(Path file, B1RequestContext ctx) throws IOException {
        return compile(PhpIrLowerer.lowerFile(file.toString(), Files.readAllBytes(file)), ctx);
    }

    public static CompiledFile compile(IrUnit unit, B1RequestContext ctx) {
        return compile(unit, ctx, 7500);
    }
    public static Object eval(B1RequestContext ctx, byte[] code, pl.codeloom.php.ir.IrSourceSpan callerSpan,
                              pl.codeloom.php.b1.runtime.B1Object self, int budget) {
        String path = callerSpan.file() + "(" + callerSpan.line() + ") : eval()'d code";
        byte[] source = java.util.Arrays.copyOf("<?php ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),6 + code.length);
        System.arraycopy(code,0,source,6,code.length);
        try {
            var unit = PhpIrLowerer.lowerEval(path,source,ctx::hasFunction,ctx::hasClass,self != null);
            return compile(unit,ctx,budget,true).entry().invoke0(ctx,self);
        } catch (pl.codeloom.php.parser.zend.ZendCompileError ex) {
            throw ctx.compileError(ex.className(),ex.messageBytes(),ex.atExecutedLine() ? callerSpan.file() : path,
                    ex.atExecutedLine() ? callerSpan.line() : ex.line());
        }
    }
    public static CompiledFile compile(IrUnit unit, B1RequestContext ctx, int maxMethodBytes) {
        return compile(unit,ctx,maxMethodBytes,false);
    }
    private static CompiledFile compile(IrUnit unit, B1RequestContext ctx, int maxMethodBytes, boolean eval) {
        int helpers = 0, max = 0;
        var statementBound = new java.util.HashSet<Integer>();
        var seenClasses = new java.util.HashSet<String>();
        for (var cls : unit.classes()) {
            String name = lower(cls.name());
            if (ctx.hasClass(name) || !seenClasses.add(name) || cls.methods().stream()
                    .anyMatch(id -> unit.functions().get(id).name().equalsIgnoreCase("__toString"))) {
                statementBound.add(cls.id());
            }
        }
        var linked = new ArrayList<B1Callable>();
        for (var function : unit.functions()) {
            var emission = B1ClassFileEmitter.measured(B1CfgLowerer.lower(function, statementBound), maxMethodBytes);
            helpers += emission.helpers();
            max = Math.max(max,emission.maxCode());
            byte[] bytes = emission.bytes();
            B1Lint.requireClean(bytes, B1Lint.Kind.EMITTED);
            try {
                var lookup = emission.blobs().isEmpty() ? MethodHandles.lookup().defineHiddenClass(bytes, false)
                        : MethodHandles.lookup().defineHiddenClassWithClassData(bytes, java.util.List.copyOf(emission.blobs()), false);
                var ctor = lookup.findConstructor(lookup.lookupClass(),
                        MethodType.methodType(void.class, boolean[].class, String[].class));
                boolean[] refs = new boolean[function.params().size()];
                for (int i = 0; i < refs.length; i++) refs[i] = function.params().get(i).byRef();
                var callable = (B1Callable)ctor.invoke(refs, function.locals().toArray(String[]::new));
                callable.display = function.owner() == null ? function.name() : function.owner() + "::" + function.name();
                callable.evalBody = eval && function.fileBody();
                callable.scope = function.owner() == null ? null : lower(function.owner());
                callable.declaredFile = function.at().file();
                callable.declaredLine = function.at().line();
                callable.methodBudget = maxMethodBytes;
                callable.variadic = !function.params().isEmpty() && function.params().getLast().variadic();
                callable.isStatic = function.isStatic();
                linked.add(callable);
            } catch (RuntimeException | Error ex) { throw ex; }
            catch (Throwable ex) { throw new IllegalStateException("linking " + function.name(), ex); }
        }
        var siblings = linked.toArray(B1Callable[]::new);
        for (var callable : siblings) callable.siblings = siblings;
        var functions = new HashMap<String, B1Callable>();
        for (var f : unit.functions()) {
            if (!f.fileBody() && !f.closure() && f.owner() == null) functions.put(lower(f.name()), linked.get(f.id()));
        }
        var classes = new HashMap<String, B1RequestContext.ClassDef>();
        var lateClasses = new HashMap<Integer, B1RequestContext.ClassDef>();
        for (var cls : unit.classes()) {
            var methods = new HashMap<String, B1Callable>();
            for (int id : cls.methods()) methods.put(lower(unit.functions().get(id).name()), linked.get(id));
            var defaults = new HashMap<String,Object>();
            var visibility = new HashMap<String,Integer>();
            for (var prop : cls.properties()) {
                if (prop.visibility() != 1) visibility.put(prop.name(),prop.visibility());
                Object initial = prop.initial() instanceof IrUnit.ArrayValue ? new pl.codeloom.php.b1.runtime.B1Array(0).freeze()
                        : ((IrUnit.Literal)prop.initial()).value();
                if (initial instanceof String str) initial = pl.codeloom.php.b1.runtime.B1Bytes.ofUtf8(str);
                if (initial instanceof byte[] binary) initial = pl.codeloom.php.b1.runtime.B1Bytes.of(binary);
                if (initial != null) defaults.put(prop.name(),initial);
            }
            var definition = new B1RequestContext.ClassDef(cls.name(), cls.properties().stream().map(IrUnit.PropertyDef::name).toList(),
                    methods, defaults, visibility, cls.at());
            if (statementBound.contains(cls.id())) lateClasses.put(cls.id(), definition);
            else classes.put(lower(cls.name()), definition);
        }
        linked.getFirst().statementClasses = java.util.Map.copyOf(lateClasses);
        ctx.install(unit.file(), linked.getFirst(), functions, classes);
        ctx.linked(linked.size(),helpers,max);
        return new CompiledFile(linked.getFirst(), linked.size(), helpers, max);
    }
}
