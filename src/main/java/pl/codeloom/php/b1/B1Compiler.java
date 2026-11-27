package pl.codeloom.php.b1;

import pl.codeloom.php.b1.runtime.B1Callable;
import pl.codeloom.php.b1.runtime.B1RequestContext;
import pl.codeloom.php.ir.IrSourceSpan;
import pl.codeloom.php.ir.IrUnit;
import pl.codeloom.php.ir.PhpIrLowerer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static pl.codeloom.php.ir.AsciiCase.lower;

/** Only the named vtable, its CV names and signature survive linking. */
public final class B1Compiler {
    private B1Compiler() {}
    /** A linked unit. It holds no request state, so several requests can install the same hidden classes. */
    public record CompiledFile(B1Callable entry, int lintedClasses, int outlinedHelperCount, int maxMethodBytes,
                               java.util.List<MethodSize> methods, String path, java.util.Map<String,B1Callable> functions,
                               java.util.Map<String,B1RequestContext.ClassDef> classes) {
        void install(B1RequestContext ctx) {
            ctx.install(path,entry,functions,classes);
            ctx.linked(lintedClasses,outlinedHelperCount,maxMethodBytes);
        }
    }
    public record MethodSize(String function, String hiddenClass, String method, int bytes) {}

    public static CompiledFile compile(Path file, B1RequestContext ctx) throws IOException {
        return compile(PhpIrLowerer.lowerFile(file.toString(), Files.readAllBytes(file)), ctx);
    }

    public static CompiledFile compile(IrUnit unit, B1RequestContext ctx) {
        return compile(unit, ctx, 7500);
    }
    public static CompiledFile compile(IrUnit unit, B1RequestContext ctx, int maxMethodBytes) {
        return compile(unit,ctx,maxMethodBytes,false,false,null);
    }
    /** A unit lowered at the B1 language level ({@link PhpIrLowerer#lowerB1File}); its evals keep that level. */
    public static CompiledFile compileB1(IrUnit unit, B1RequestContext ctx, int maxMethodBytes) {
        return compile(unit,ctx,maxMethodBytes,false,true,null);
    }

    public static Object eval(B1RequestContext ctx, byte[] code, IrSourceSpan callerSpan,
                              pl.codeloom.php.b1.runtime.B1Object self, int budget, boolean extended) {
        String path = callerSpan.file() + "(" + callerSpan.line() + ") : eval()'d code";
        byte[] source = java.util.Arrays.copyOf("<?php ".getBytes(StandardCharsets.US_ASCII),6 + code.length);
        System.arraycopy(code,0,source,6,code.length);
        try {
            IrUnit unit = extended ? PhpIrLowerer.lowerB1Eval(path,source,ctx::hasFunction,ctx::hasClass,self != null)
                    : PhpIrLowerer.lowerEval(path,source,ctx::hasFunction,ctx::hasClass,self != null);
            String key = (self != null ? "this:" : "") + new String(source,StandardCharsets.ISO_8859_1);
            return compile(unit,ctx,budget,true,extended,key).entry().invoke0(ctx,self);
        } catch (pl.codeloom.php.parser.zend.ZendCompileError ex) {
            throw ctx.compileError(ex.className(),ex.messageBytes(),ex.atExecutedLine() ? callerSpan.file() : path,
                    ex.atExecutedLine() ? callerSpan.line() : ex.line());
        }
    }

    /** include/require[_once] (ZEND_INCLUDE_OR_EVAL flags 2, 4, 8, 16) at the including file's top level. */
    public static Object include(B1RequestContext ctx, String path, int kind, IrSourceSpan caller, int budget) {
        if (!ctx.atTopLevel()) throw ctx.unsupported("include inside a function scope");
        Path file = resolve(path, caller);
        if (file == null) throw ctx.unsupported("including a file that cannot be opened (" + path + ")");
        return load(ctx, file, kind, budget);
    }

    /** Runs a script at the B1 language level like a CLI request would, without the request shutdown (the host
     * calls it). Linked through the context's code cache when it has one. Returns the script's owned result. */
    public static Object runScript(B1RequestContext ctx, Path file, int budget) {
        return load(ctx, file.toAbsolutePath(), 8, budget);
    }

    /** Links only the named top-level declarations of an unmodified file ({@link PhpIrLowerer#lowerB1Declarations}). */
    public static CompiledFile linkDeclarations(B1RequestContext ctx, Path file, Set<String> names, int budget) {
        byte[] bytes;
        String real;
        try {
            real = file.toRealPath().toString();
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
        String key = new java.util.TreeSet<>(names) + new String(bytes,StandardCharsets.ISO_8859_1);
        B1CodeCache cache = ctx.codeCache();
        IrUnit unit = cache == null ? PhpIrLowerer.lowerB1Declarations(real,bytes,names)
                : cache.unit(real,key,() -> PhpIrLowerer.lowerB1Declarations(real,bytes,names));
        return compile(unit,ctx,budget,false,true,key);
    }

    private static Object load(B1RequestContext ctx, Path file, int kind, int budget) {
        String real;
        byte[] bytes;
        try {
            real = file.toRealPath().toString();
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
        boolean once = kind == 4 || kind == 16;
        if (once && ctx.wasIncluded(real)) return Boolean.TRUE;
        ctx.markIncluded(real);
        B1CodeCache cache = ctx.codeCache();
        String source = new String(bytes,StandardCharsets.ISO_8859_1);
        IrUnit unit = cache == null ? PhpIrLowerer.lowerB1File(real,bytes,ctx::hasFunction,ctx::hasClass)
                : cache.unit(real,source,() -> PhpIrLowerer.lowerB1File(real,bytes,ignored -> false,ignored -> false));
        return ctx.execute(compile(unit,ctx,budget,false,true,source).entry());
    }

    /** Absolute paths, then (like php_resolve_path without include_path entries other than ".") the working
     * directory and, for names not starting with ./ or ../, the including file's directory. */
    private static Path resolve(String path, IrSourceSpan caller) {
        try {
            Path p = Path.of(path);
            if (p.isAbsolute()) return Files.isRegularFile(p) ? p : null;
            Path fromCwd = Path.of("").toAbsolutePath().resolve(p);
            if (Files.isRegularFile(fromCwd)) return fromCwd;
            boolean explicit = path.startsWith("./") || path.startsWith("../") || path.startsWith(".\\") || path.startsWith("..\\");
            if (!explicit) {
                Path dir = Path.of(caller.file()).toAbsolutePath().getParent();
                if (dir != null && Files.isRegularFile(dir.resolve(p))) return dir.resolve(p);
            }
            return null;
        } catch (InvalidPathException ex) { return null; }
    }

    private static CompiledFile compile(IrUnit unit, B1RequestContext ctx, int maxMethodBytes, boolean eval, boolean extended,
                                        String sourceKey) {
        Set<Integer> statementBound = statementBound(unit, ctx);
        B1CodeCache cache = ctx.codeCache();
        CompiledFile file = cache == null || sourceKey == null ? link(unit,statementBound,maxMethodBytes,eval,extended)
                : cache.linked(new B1CodeCache.Key(unit.file(),sourceKey,maxMethodBytes,eval,extended,Set.copyOf(statementBound)),
                        () -> link(unit,statementBound,maxMethodBytes,eval,extended));
        file.install(ctx);
        return file;
    }

    /** Classes that bind at their declaration statement: duplicates, already-declared names, and __toString holders. */
    private static Set<Integer> statementBound(IrUnit unit, B1RequestContext ctx) {
        Set<Integer> statementBound = new HashSet<>();
        Set<String> seenClasses = new HashSet<>();
        for (IrUnit.IrClass cls : unit.classes()) {
            String name = lower(cls.name());
            if (ctx.hasClass(name) || !seenClasses.add(name) || cls.methods().stream()
                    .anyMatch(id -> unit.functions().get(id).name().equalsIgnoreCase("__toString"))) {
                statementBound.add(cls.id());
            }
        }
        return statementBound;
    }

    private static CompiledFile link(IrUnit unit, Set<Integer> statementBound, int maxMethodBytes, boolean eval, boolean extended) {
        int helpers = 0, max = 0;
        var linked = new ArrayList<B1Callable>();
        var sizes = new ArrayList<MethodSize>();
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
                callable.requiredParams = function.requiredParams();
                callable.display = function.owner() == null ? function.name() : function.owner() + "::" + function.name();
                callable.evalBody = eval && function.fileBody();
                callable.scope = function.owner() == null ? null : lower(function.owner());
                callable.declaredFile = function.at().file();
                callable.declaredLine = function.at().line();
                callable.methodBudget = maxMethodBytes;
                callable.variadic = !function.params().isEmpty() && function.params().getLast().variadic();
                callable.strictTypes = unit.strictTypes();
                callable.isStatic = function.isStatic();
                callable.visibility = function.visibility();
                callable.extendedLanguage = extended;
                for (var method : java.lang.classfile.ClassFile.of().parse(bytes).methods()) {
                    sizes.add(new MethodSize(callable.display, callable.getClass().getName(), method.methodName().stringValue(),
                            method.findAttribute(java.lang.classfile.Attributes.code()).orElseThrow().codeLength()));
                }
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
            var visibility = new HashMap<String,Integer>();
            var properties = new HashMap<String,B1RequestContext.Property>();
            var instance = new ArrayList<String>();
            for (var prop : cls.properties()) {
                if (prop.visibility() != 1) visibility.put(prop.name(),prop.visibility());
                Object initial = B1LiteralBlob.propertyDefault(prop.initial());
                properties.put(prop.name(),new B1RequestContext.Property(prop.type(),prop.isStatic(),prop.initialized(),initial));
                if (!prop.isStatic()) instance.add(prop.name());
            }
            var definition = new B1RequestContext.ClassDef(cls.name(), instance,
                    methods, visibility, properties, cls.at(), Set.copyOf(cls.interfaces()), cls.allowDynamicProperties());
            if (statementBound.contains(cls.id())) lateClasses.put(cls.id(), definition);
            else classes.put(lower(cls.name()), definition);
        }
        linked.getFirst().statementClasses = java.util.Map.copyOf(lateClasses);
        return new CompiledFile(linked.getFirst(), linked.size(), helpers, max, java.util.List.copyOf(sizes),
                unit.file(), java.util.Map.copyOf(functions), java.util.Map.copyOf(classes));
    }
}
