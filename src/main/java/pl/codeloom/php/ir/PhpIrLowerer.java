package pl.codeloom.php.ir;

import pl.codeloom.php.parser.Ast;
import pl.codeloom.php.parser.JumpChecks;
import pl.codeloom.php.parser.Names;
import pl.codeloom.php.parser.zend.ZendParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static pl.codeloom.php.ir.IrUnit.*;

/** The first file-level subset. A declined declaration or body declines the entire unit before publication. */
public final class PhpIrLowerer {
    public static final class UnsupportedIr extends RuntimeException {
        UnsupportedIr(String reason) { super(reason, null, false, false); }
    }

    private final String file;
    private boolean evalThis;
    private final List<IrFunction> functions = new ArrayList<>();
    private final List<IrClass> classes = new ArrayList<>();
    private final Set<String> functionNames = new HashSet<>();
    private final Set<String> classNames = new HashSet<>();

    private final java.util.function.Predicate<String> externalFunction, externalClass;

    private PhpIrLowerer(String file, java.util.function.Predicate<String> externalFunction,
                         java.util.function.Predicate<String> externalClass) {
        this.file = file;
        this.externalFunction = externalFunction;
        this.externalClass = externalClass;
    }

    public static IrUnit lowerFile(String file, byte[] source) {
        return lowerFile(file, source, ignored -> false, ignored -> false);
    }

    public static IrUnit lowerFile(String file, byte[] source, java.util.function.Predicate<String> functions,
                                   java.util.function.Predicate<String> classes) {
        return lowerEval(file,source,functions,classes,false);
    }

    public static IrUnit lowerEval(String file,byte[] source,java.util.function.Predicate<String> functions,
                                   java.util.function.Predicate<String> classes,boolean hasThis) {
        var parsed = ZendParser.parse(source);
        try {
            if (!parsed.diagnostics().isEmpty() || !JumpChecks.file(parsed.ast()).isEmpty())
                throw new UnsupportedIr("compile diagnostics");
        } catch (JumpChecks.Failure failure) {
            throw new PhpCompileFatal(failure.getMessage(),new IrSourceSpan(file,failure.line),"#0 {main}");
        }
        var lowerer = new PhpIrLowerer(file,functions,classes);
        lowerer.evalThis = hasThis;
        return lowerer.unit(parsed.ast());
    }

    /** The normal Truffle entry point has already run parser diagnostics and JumpChecks. */
    public static IrUnit lowerFile(String file, Ast checkedFile) {
        return lowerFile(file,checkedFile,false);
    }
    public static IrUnit lowerFile(String file,Ast checkedFile,boolean eval) {
        var lowerer = new PhpIrLowerer(file,ignored -> false,ignored -> false);
        lowerer.evalThis = eval;
        return lowerer.unit(checkedFile);
    }

    private IrUnit unit(Ast tree) {
        functions.add(null); // function 0 is the file
        // PHP binds unconditional declarations before its first statement, including forward calls.
        for (Object item : tree.children()) {
            Ast n = node(item);
            if (n.is("AST_FUNC_DECL") && !functionNames.add(AsciiCase.lower(name(n)))) {
                Ast previous = tree.children().stream().map(PhpIrLowerer::node).filter(d -> d.is("AST_FUNC_DECL")
                        && name(d).equalsIgnoreCase(name(n))).findFirst().orElseThrow();
                throw new PhpCompileFatal("Cannot redeclare function " + name(n) + "() (previously declared in "
                        + file + ":" + previous.line + ")",at(n),"#0 {main}");
            }
            if (n.is("AST_CLASS")) classNames.add(AsciiCase.lower(name(n)));
        }
        var main = new Body(null);
        var code = new ArrayList<Statement>();
        for (Object item : tree.children()) {
            Ast node = node(item);
            switch (node.kind) {
                case "AST_FUNC_DECL" -> function(node, null);
                case "AST_CLASS" -> {
                    classDecl(node);
                    var cls = classes.getLast();
                    code.add(new ClassDeclaration(cls.id(), cls.name(), cls.at()));
                }
                default -> code.add(main.statement(node));
            }
        }
        code = main.exposeLabels(code);
        functions.set(0, main.finish(0, file, tree, List.of(), code));
        for (IrFunction function : functions) checkTransfers(function.body(), Set.of(), 0);
        return new IrUnit(file, functions, classes);
    }

    /** Accept branches within a list and exits to an enclosing list. PHP's remaining
     * non-lexical entries (for instance a goto into a catch) need the legacy entry dispatcher. */
    private static void checkTransfers(List<Statement> body, Set<String> enclosing, int loops) {
        Set<String> visible = enclosing;
        for (Statement s : body) if (s instanceof Mark m) {
            if (visible == enclosing) visible = new HashSet<>(enclosing);
            visible.add(m.name());
        }
        for (Statement s : body) switch (s) {
            case Jump j -> {
                if (!visible.contains(j.name())) throw new UnsupportedIr("jump into protected region");
            }
            case LoopExit j -> {
                if (j.depth() < 1 || j.depth() > loops) throw new UnsupportedIr("invalid loop transfer");
            }
            case Branch b -> { checkTransfers(b.yes(),visible,loops); checkTransfers(b.no(),visible,loops); }
            case Loop l -> checkTransfers(l.body(),visible,loops + 1);
            case Each e -> checkTransfers(e.body(),visible,loops + 1);
            case TryCatch t -> { checkTransfers(t.body(),visible,loops); checkTransfers(t.handler(),visible,loops); }
            case Finally t -> { checkTransfers(t.body(),visible,loops); checkTransfers(t.cleanup(),Set.of(),0); }
            default -> {}
        }
    }

    private void classDecl(Ast decl) {
        check((decl.flags == 0 || decl.flags == 32) && plainDeclaration(decl) && decl.get("extends") == null
                && decl.get("implements") == null, decl);
        String name = Names.GLOBAL.declared(name(decl));
        check(!Names.reservedClassName(AsciiCase.lower(name)), decl);
        List<PropertyDef> props = new ArrayList<>();
        List<Integer> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : decl.node("stmts").children()) {
            Ast member = node(item);
            if (member.is("AST_METHOD")) {
                String method = AsciiCase.lower(name(member));
                check(seen.add(method) && !Set.of("__get", "__set", "__isset", "__unset", "__call").contains(method), member);
                methods.add(function(member, name));
            } else {
                check(member.is("AST_PROP_GROUP") && (member.flags == 1 || member.flags == 2 || member.flags == 4) && member.get("type") == null
                        && member.get("attributes") == null, member);
                for (Object element : member.node("props").children()) {
                    Ast prop = node(element);
                    check(prop.get("hooks") == null
                            && prop.get("docComment") == null && props.stream().noneMatch(p -> p.name().equals(name(prop))), prop);
                    props.add(new PropertyDef(name(prop),member.flags,prop.node("default") != null && prop.node("default").is("AST_ARRAY") && prop.node("default").size() == 0 ? new ArrayValue(List.of()) : scalar(prop.get("default"))));
                }
            }
        }
        classes.add(new IrClass(classes.size(), name, at(decl), decl.endLine, List.copyOf(props), List.copyOf(methods)));
    }

    private int function(Ast decl, String owner) {
        return function(decl, owner, null);
    }

    private int function(Ast decl, String owner, Body outer) {
        boolean closure = outer != null;
        check((decl.flags == 0 || closure && decl.flags == 16 || owner != null && (decl.flags == 1 || decl.flags == 17)) && plainDeclaration(decl)
                && decl.get("stmts") != null, decl);
        var body = new Body(owner);
        body.returnType = typeName(decl.node("returnType"),true);
        var params = new ArrayList<Param>();
        for (Object item : decl.node("params").children()) {
            Ast param = node(item);
            Ast type = param.node("type");
            check((param.flags & ~24) == 0 && plainDeclaration(param) && (param.flags != 16 || type == null),param);
            String name = name(param);
            check(body.local(name) == params.size(), param);
            params.add(new Param(name, (param.flags & 8) != 0, typeName(type,false), (param.flags & 16) != 0, defaultValue(param.get("default")), at(param)));
        }
        int id = functions.size();
        String name = closure ? "{closure:" + outer.enclosing + ":" + decl.line + "}" : Names.GLOBAL.declared(name(decl));
        body.enclosing = closure ? name : (owner == null ? name : owner + "::" + name) + "()";
        functions.add(null);
        var captures = new ArrayList<Capture>();
        if (closure && decl.node("uses") != null) for (Object item : decl.node("uses").children()) {
            Ast use = node(item);
            String var = name(use);
            check(!body.locals.contains(var), use);
            captures.add(new Capture(outer.local(var), body.local(var), use.flags == 1));
        }
        List<Statement> code;
        if (decl.is("AST_ARROW_FUNC")) {
            check(decl.body().is("AST_RETURN"),decl);
            code = List.of(body.statement(decl.body()));
            // An arrow with free variables needs implicit by-value captures.
            check(body.locals.size() == params.size(),decl);
        } else code = body.statements(decl.body());
        if (body.checkedReturn()) {
            var withEnd = new ArrayList<>(code);
            var empty = new ReleaseTemps(List.of());
            withEnd.add(new Region(new IrSourceSpan(file,decl.endLine),new Return(new CheckedReturn(null,true)),empty,empty));
            code = List.copyOf(withEnd);
        }
        functions.set(id, new IrFunction(id, owner, name, at(decl), decl.endLine, body.returnType,List.copyOf(params), closure,
                (decl.flags & 16) != 0, List.copyOf(captures), List.copyOf(body.locals), List.copyOf(body.temps), code));
        return id;
    }

    private static String typeName(Ast type,boolean result) {
        if (type == null) return null;
        if (type.is("AST_NULLABLE_TYPE") && result && "string".equals(typeName(type.node("type"),true))) return "?string";
        check(type.is("AST_TYPE"),type);
        return switch (type.flags) {
            case 4 -> "int";
            case 6 -> "string";
            case 14 -> { check(result,type); yield "void"; }
            default -> throw unsupported(type);
        };
    }

    private static Literal scalar(Object value) {
        if (value == null || value instanceof String || value instanceof byte[] || value instanceof Number)
            return new Literal(value);
        Ast n = node(value);
        if (n.is("AST_CONST")) return switch (AsciiCase.lower(n.text("name"))) {
            case "true" -> new Literal(true);
            case "false" -> new Literal(false);
            case "null" -> new Literal(null);
            case "php_int_max" -> new Literal(Long.MAX_VALUE);
            case "sort_numeric" -> new Literal(1L);
            default -> throw unsupported(n);
        };
        if (n.is("AST_UNARY_OP") && n.flags == 262 && n.get("expr") instanceof Long number)
            return new Literal(-number);
        throw unsupported(n);
    }

    /** Recognize a pure tree before assigning any live-range slots to its children. */
    private static ArrayValue largeLiteral(Ast array) {
        int[] count = {0};
        try {
            ArrayValue value = literalArray(array,count);
            return count[0] >= 16 ? value : null;
        } catch (UnsupportedIr dynamic) { return null; }
    }

    private static ArrayValue literalArray(Ast array, int[] count) {
        var items = new ArrayList<Item>();
        count[0] += array.size();
        for (Object child : array.children()) {
            Ast e = node(child);
            check(e.is("AST_ARRAY_ELEM") && e.flags == 0,e);
            Object value = e.get("value");
            items.add(new Item(e.get("key") == null ? null : scalar(e.get("key")),
                    value instanceof Ast a && a.is("AST_ARRAY") ? literalArray(a,count) : scalar(value)));
        }
        return new ArrayValue(List.copyOf(items));
    }

    private static Expr defaultValue(Object value) {
        if (value == null) return null;
        if (value instanceof Ast n && n.is("AST_ARRAY") && n.size() == 0) return new ArrayValue(List.of());
        return scalar(value);
    }

    private static Operator operator(Ast n) {
        return switch (n.flags) {
            case 1 -> Operator.ADD;
            case 5 -> Operator.MOD;
            case 8 -> Operator.CONCAT;
            case 16 -> Operator.IDENTICAL;
            case 17 -> Operator.NOT_IDENTICAL;
            case 18 -> Operator.EQ;
            case 19 -> Operator.NE;
            case 20 -> Operator.LT;
            case 21 -> Operator.LE;
            case 256 -> Operator.GT;
            case 257 -> Operator.GE;
            case 258 -> Operator.OR;
            case 259 -> Operator.AND;
            case 260 -> Operator.COALESCE;
            default -> throw unsupported(n);
        };
    }

    private final class Body {
        final String owner;
        String enclosing = file;
        String returnType;
        boolean checkedReturn() { return returnType != null && !returnType.equals("void"); }
        final List<String> locals = new ArrayList<>();
        final List<TempKind> temps = new ArrayList<>();
        List<ReleaseTemp> releases;
        int labels;

        Body(String owner) { this.owner = owner; }

        IrFunction finish(int id, String name, Ast at, List<Param> params, List<Statement> body) {
            return new IrFunction(id, owner, name, at(at), at.endLine,null, List.copyOf(params), false, false, List.of(), List.copyOf(locals),
                    List.copyOf(temps), List.copyOf(body));
        }

        int local(String name) {
            if (Set.of("this", "GLOBALS", "_SERVER", "_GET", "_POST", "_FILES", "_COOKIE", "_SESSION",
                    "_REQUEST", "_ENV").contains(name)) throw new UnsupportedIr("special variable " + name);
            int id = locals.indexOf(name);
            if (id >= 0) return id;
            locals.add(name);
            return locals.size() - 1;
        }

        List<Statement> statements(Ast list) {
            var code = new ArrayList<Statement>();
            for (Object item : list.children()) code.add(statement(node(item)));
            return List.copyOf(exposeLabels(code));
        }

        // A label inside an if is a legal entry from outside it. Give these branches explicit
        // continuation labels at their containing scope so both backends can enter them.
        ArrayList<Statement> exposeLabels(List<Statement> code) {
            var flat = new ArrayList<Statement>();
            for (Statement stmt : code) {
                if (stmt instanceof Branch b) {
                    var yes = exposeLabels(b.yes());
                    var no = exposeLabels(b.no());
                    if (yes.stream().anyMatch(Mark.class::isInstance) || no.stream().anyMatch(Mark.class::isInstance)) {
                        String otherwise = "#else" + labels++, end = "#end" + labels++;
                        flat.add(new Branch(b.test(), List.of(), List.of(new Jump(otherwise))));
                        flat.addAll(yes);
                        flat.add(new Jump(end));
                        flat.add(new Mark(otherwise));
                        flat.addAll(no);
                        flat.add(new Mark(end));
                        continue;
                    }
                }
                flat.add(stmt);
            }
            return flat;
        }

        Test test(Ast n, Object value) {
            releases = new ArrayList<>();
            Expr result = expr(value, false);
            return new Test(at(n), result, new ReleaseTemps(List.copyOf(releases)));
        }

        Statement branch(Ast n, int index) {
            Ast part = node(n.child(index));
            Test condition = test(part, part.get("cond") == null ? true : part.get("cond"));
            var yes = statements(part.node("stmts"));
            var no = index + 1 < n.size() ? List.of(branch(n, index + 1)) : List.<Statement>of();
            return new Branch(condition, yes, no);
        }

        List<Statement> optionalList(Ast n) { return n == null ? List.of() : statements(n); }

        Statement statement(Ast n) {
            switch (n.kind) {
                case "AST_IF": return branch(n, 0);
                case "AST_LABEL": return new Mark(name(n));
                case "AST_GOTO": return new Jump(n.text("label"));
                case "AST_BREAK", "AST_CONTINUE": {
                    Object depth = n.get("depth");
                    check(depth == null || depth instanceof Long level && level > 0 && level <= Integer.MAX_VALUE, n);
                    return new LoopExit(n.is("AST_CONTINUE"), depth == null ? 1 : Math.toIntExact((Long) depth));
                }
                case "AST_DO_WHILE": {
                    var cond = test(n,n.get("cond"));
                    int first = local("#first" + labels++);
                    var none = new ReleaseTemps(List.of());
                    var init = new Region(at(n),new Write(first,new Literal(true)),none,none);
                    var code = new ArrayList<Statement>();
                    code.add(new Region(at(n),new Write(first,new Literal(false)),none,none));
                    code.addAll(statements(n.node("stmts")));
                    return new Loop(List.of(init),new Test(at(n),new Binary(Operator.OR,new Read(first),cond.value()),cond.cleanup()),List.of(),code);
                }
                case "AST_WHILE": return new Loop(List.of(), test(n, n.get("cond")), List.of(), statements(n.node("stmts")));
                case "AST_FOR": {
                    Ast cond = n.node("cond");
                    check(cond == null || cond.size() <= 1, n);
                    return new Loop(optionalList(n.node("init")), test(n, cond == null || cond.size() == 0 ? true : cond.child(0)),
                            optionalList(n.node("loop")), statements(n.node("stmts")));
                }
                case "AST_FOREACH": {
                    Ast value = n.node("value"), key = n.node("key");
                    check(value.is("AST_VAR") && (key == null || key.is("AST_VAR")), n);
                    return new Each(test(n, n.get("expr")), slot(TempKind.VALUE),
                            key == null ? -1 : local(name(key)), local(name(value)), statements(n.node("stmts")));
                }
            }
            if (n.is("AST_TRY")) {
                Ast catches = n.node("catches");
                var protectedBody = statements(n.node("try"));
                check(catches == null || catches.size() <= 1, n);
                if (catches != null && catches.size() > 0) {
                    Ast handler = node(catches.child(0));
                    Ast types = handler.node("class");
                    check(types.size() == 1 && handler.node("var") != null, handler);
                    String type = className(node(types.child(0)));
                    check(Set.of("exception", "throwable", "error", "typeerror", "argumentcounterror","parseerror","compileerror","valueerror").contains(AsciiCase.lower(type)), handler);
                    protectedBody = List.of(new TryCatch(labels++, at(n), protectedBody, type,
                            local(name(handler.node("var"))), statements(handler.node("stmts"))));
                }
                return n.node("finally") == null ? protectedBody.getFirst() : new Finally(protectedBody, statements(n.node("finally")), slot(TempKind.VALUE));
            }
            releases = new ArrayList<>();
            Action action = switch (n.kind) {
                case "AST_GLOBAL" -> new Global(local(name(n.node("var"))));
                case "AST_UNSET" -> {
                    Ast place = n.node("var");
                    if (place.is("AST_VAR")) yield new Unset(expr(place,false), null);
                    check(place.is("AST_DIM") && (place.node("expr").is("AST_VAR") || place.node("expr").is("AST_PROP")) && place.get("dim") != null,place);
                    yield new Unset(expr(place.get("expr"),false),expr(place.get("dim"),false));
                }
                case "AST_ECHO" -> new Echo(expr(n.get("expr"), false));
                case "AST_RETURN" -> {
                    if (checkedReturn()) {
                        check(n.get("expr") != null,n);
                        yield new Return(new CheckedReturn(expr(n.get("expr"),false),false));
                    }
                    check(!"void".equals(returnType) || n.get("expr") == null,n);
                    yield new Return(expr(n.get("expr"),false));
                }
                case "AST_THROW" -> new Raise(expr(n.get("expr"), false));
                case "AST_ASSIGN" -> {
                    Ast place = n.node("var");
                    if (place.is("AST_VAR")) yield new Write(local(name(place)), expr(n.get("expr"), false));
                    if (place.is("AST_DIM")) {
                        var path = new ArrayList<Ast>();
                        while (place != null && place.is("AST_DIM")) { path.addFirst(place); place = place.node("expr"); }
                        check(place != null && (place.is("AST_VAR") || place.is("AST_PROP")), n);
                        var keys = new ArrayList<Expr>();
                        for (Ast dim : path) keys.add(dim.get("dim") == null ? null : expr(dim.get("dim"), false));
                        yield new WriteDim(expr(place,false), java.util.Collections.unmodifiableList(keys), hold(expr(n.get("expr"), false)));
                    }
                    check(place.is("AST_PROP"), place);
                    if (!(place.get("prop") instanceof String))
                        yield new WriteNamedProperty(receiver(place),expr(place.get("prop"),false),expr(n.get("expr"),false));
                    yield new WriteProperty(receiver(place), (String) place.get("prop"), expr(n.get("expr"), false));
                }
                case "AST_CALL", "AST_METHOD_CALL", "AST_NEW" -> new Evaluate(call(n, false, false));
                case "AST_ASSIGN_OP" -> {
                    Ast place = n.node("var");
                    check(place.is("AST_VAR") && (n.flags == 1 || n.flags == 8), n);
                    int id = local(name(place));
                    yield new Write(id, new Binary(operator(n), new Read(id), expr(n.get("expr"), false)));
                }
                default -> new Evaluate(expr(n, false));
            };
            var cleanup = new ReleaseTemps(List.copyOf(releases));
            return new Region(at(n), action, cleanup, cleanup);
        }

        Expr receiver(Ast n) {
            Ast base = node(n.get("expr"));

            return expr(base, false);
        }

        Expr expr(Object value, boolean argument) {
            if (!(value instanceof Ast)) return value instanceof Boolean ? new Literal(value) : scalar(value);
            Ast n = node(value);
            return switch (n.kind) {
                case "AST_CONST" -> scalar(n);
                case "AST_ARRAY" -> {
                    ArrayValue pure = largeLiteral(n);
                    if (pure != null) yield owned(pure);
                    var items = new ArrayList<Item>();
                    for (Object element : n.children()) {
                        Ast e = node(element);
                        check(e.is("AST_ARRAY_ELEM") && e.flags == 0, e);
                        items.add(new Item(e.get("key") == null ? null : expr(e.get("key"), false), expr(e.get("value"), false)));
                    }
                    yield owned(new ArrayValue(List.copyOf(items)));
                }
                case "AST_DIM" -> dim(n, false);
                case "AST_ISSET" -> {
                    Ast place = n.node("var");
                    if (place == null || !(place.is("AST_VAR") || place.is("AST_DIM") || place.is("AST_PROP"))) {
                        if (place != null && (place.is("AST_STATIC_PROP") || place.is("AST_NULLSAFE_PROP"))) throw unsupported(place);
                        throw new PhpCompileFatal("Cannot use isset() on the result of an expression (you can use \"null !== expression\" instead)",at(n),"#0 {main}");
                    }
                    yield new IsSet(quiet(place));
                }
                case "AST_EMPTY" -> new Not(quiet(n.get("expr")));
                case "AST_CAST" -> {
                    check(n.flags == 4 || n.flags == 6,n);
                    yield new Cast(expr(n.get("expr"),false),n.flags == 6);
                }
                case "AST_UNARY_OP" -> {
                    if (n.flags == 14) yield new Not(expr(n.get("expr"), false));
                    yield scalar(n);
                }
                case "AST_ASSIGN_OP" -> {
                    Ast place = n.node("var");
                    check(place.is("AST_VAR") && (n.flags == 1 || n.flags == 8),n);
                    int id = local(name(place));
                    yield new Update(id,new Binary(operator(n),new Read(id),expr(n.get("expr"),false)));
                }
                case "AST_CONDITIONAL" -> {
                    if (n.get("true") == null) yield owned(new Binary(Operator.ELVIS, expr(n.get("cond"), false), expr(n.get("false"), false)));
                    yield new Choose(expr(n.get("cond"), false), expr(n.get("true"), false), expr(n.get("false"), false));
                }
                case "AST_PRE_INC", "AST_PRE_DEC", "AST_POST_INC", "AST_POST_DEC" -> {
                    Ast v = n.node("var");
                    if (v.is("AST_PROP") || v.is("AST_DIM")) yield new IncrementPlace(expr(v,false),n.kind.endsWith("INC"),n.kind.startsWith("AST_PRE"));
                    check(v.is("AST_VAR"), n);
                    yield new Increment(local(name(v)), n.kind.endsWith("INC"), n.kind.startsWith("AST_PRE"));
                }
                case "AST_CLOSURE", "AST_ARROW_FUNC" -> {
                    check(n.flags == 16, n);
                    int id = function(n, owner, this);
                    yield owned(new Closure(id, functions.get(id).captures()));
                }
                case "AST_INCLUDE_OR_EVAL" -> {
                    check(n.flags == 1, n);
                    yield owned(new Eval(expr(n.get("expr"), false), at(n)));
                }
                case "AST_VAR" -> {
                    if ("this".equals(name(n))) {
                        check(owner != null || evalThis && enclosing.equals(file), n);
                        yield new This();
                    }
                    yield new Read(local(name(n)));
                }
                case "AST_PROP" -> {
                    check(n.get("prop") instanceof String, n);
                    yield new Property(receiver(n), (String) n.get("prop"));
                }
                case "AST_BINARY_OP" -> {
                    Operator op = operator(n);
                    Expr left = binaryOperand(op == Operator.COALESCE ? quiet(n.get("left")) : expr(n.get("left"), false));
                    Binary binary = new Binary(op,left,binaryOperand(expr(n.get("right"),false)));
                    yield binary.preservesValue() ? owned(binary) : binary;
                }
                case "AST_ENCAPS_LIST" -> {
                    Expr result = new Literal("");
                    for (Object part : n.children()) result = new Binary(Operator.CONCAT, result, expr(part, false));
                    yield result;
                }
                case "AST_NEW", "AST_CALL", "AST_METHOD_CALL" -> call(n, argument, true);
                default -> throw unsupported(n);
            };
        }

        Expr owned(Expr expr) {
            int id = slot(TempKind.VALUE);
            releases.add(new ReleaseTemp(id));
            return new StoreTemp(id, expr);
        }
        Expr hold(Expr value) {
            return value instanceof StoreTemp || value instanceof Literal ? value : owned(value);
        }
        Expr quiet(Object value) {
            if (value instanceof Ast n && n.is("AST_VAR")) return name(n).equals("this") ? expr(n,false) : new QuietRead(local(name(n)));
            if (value instanceof Ast n && n.is("AST_DIM")) return dim(n, true);
            if (value instanceof Ast n && n.is("AST_PROP") && n.get("prop") instanceof String name)
                return new QuietProperty(quiet(n.get("expr")),name);
            return expr(value, false);
        }
        Expr dim(Ast n, boolean quiet) {
            var chain = new ArrayList<Ast>();
            Ast root = n;
            while (root != null && root.is("AST_DIM")) {
                check(root.get("dim") != null,root);
                chain.addFirst(root);
                root = root.node("expr");
            }
            Object source = chain.getFirst().get("expr");
            Expr base = quiet ? quiet(source) : expr(source,false);
            var before = new ArrayList<StoreTemp>();
            if (base instanceof StoreTemp tmp) { before.add(tmp); base = new LoadTemp(tmp.id()); }
            for (Ast dim : chain) {
                Expr key = expr(dim.get("dim"),false);
                if (!(key instanceof Read || key instanceof Literal)) {
                    StoreTemp tmp = (StoreTemp)hold(key);
                    before.add(tmp); key = new LoadTemp(tmp.id());
                }
                base = new Dim(base instanceof Dim ? hold(base) : base,key,quiet);
            }
            return before.isEmpty() ? base : new Prepared(List.copyOf(before),base);
        }

        // FETCH_OBJ_R produces an owned operand. CV operands remain reads until the operator executes.
        Expr binaryOperand(Expr operand) {
            if (!(operand instanceof Property)) return operand;
            int id = slot(TempKind.VALUE);
            releases.add(new ReleaseTemp(id));
            return new StoreTemp(id, operand);
        }

        int slot(TempKind kind) {
            temps.add(kind);
            return temps.size() - 1;
        }

        boolean addressable(Expr e) {
            return switch (e) {
                case Read ignored -> true;
                case Property ignored -> true;
                case Dim d -> addressable(d.base());
                case Prepared p -> addressable(p.result());
                case StoreTemp t -> t.value() instanceof Dim d && addressable(d);
                default -> false;
            };
        }

        Expr call(Ast n, boolean argument, boolean used) {
            Target target;
            if (n.is("AST_NEW")) {
                Ast cls = n.node("class");
                check(cls != null,n);
                if (cls.is("AST_VAR")) target = new Target(Dispatch.CONSTRUCTOR, null,
                        new StoreTemp(slot(TempKind.CONSTRUCTION), new DynamicAllocate(expr(cls, false), at(n))));
                else {
                    String name = className(cls);
                    check(classNames.contains(AsciiCase.lower(name)) || externalClass.test(AsciiCase.lower(name)) || name.equalsIgnoreCase("Exception") || name.equalsIgnoreCase("stdClass"), n);
                    target = new Target(Dispatch.CONSTRUCTOR, name, new StoreTemp(slot(TempKind.CONSTRUCTION), new Allocate(name, at(n))));
                }
            } else if (n.is("AST_METHOD_CALL")) {
                check(n.get("method") instanceof String, n);
                target = new Target(Dispatch.METHOD, (String) n.get("method"), new StoreTemp(slot(TempKind.RECEIVER), receiver(n)));
            } else {
                Ast callee = n.node("expr");
                if (callee == null || !callee.is("AST_NAME")) target = new Target(Dispatch.DYNAMIC, null, hold(expr(n.get("expr"), false)));
                else {
                    String name = Names.GLOBAL.functionName(name(callee), callee.flags)[0];
                    if (name.equalsIgnoreCase("func_get_args") && n.node("args").size() == 0) return owned(new PassedArguments());
                    if (name.equalsIgnoreCase("call_user_func") && n.node("args").size() == 1)
                        return owned(new Forward(hold(expr(n.node("args").child(0),false)),owned(new ArrayValue(List.of())),null,null));
                    if (name.equalsIgnoreCase("call_user_func_array")) {
                        Ast args = n.node("args");
                        check(args.is("AST_ARG_LIST") && args.size() == 2,n);
                        Expr callback = hold(expr(args.child(0), false));
                        Object source = args.child(1);
                        if (source instanceof Ast slice && slice.is("AST_CALL") && slice.node("expr") != null && slice.node("expr").is("AST_NAME")
                                && "array_slice".equalsIgnoreCase(slice.node("expr").text("name"))
                                && slice.node("args").size() >= 2 && slice.node("args").size() <= 3) {
                            var parts = slice.node("args");
                            return owned(new Forward(callback, hold(expr(parts.child(0),false)), expr(parts.child(1),false),
                                    parts.size() == 3 ? expr(parts.child(2),false) : null));
                        }
                        return owned(new Forward(callback, expr(source,false), null, null));
                    }
                    // Frame-sensitive builtins and intrinsics have their own lowering in the legacy frontend.
                    check(functionNames.contains(AsciiCase.lower(name)) || externalFunction.test(AsciiCase.lower(name)) || Set.of("set_exception_handler", "count", "strlen", "array_slice", "array_key_exists", "is_int", "is_string", "is_array", "str_repeat", "get_object_vars", "is_object", "spl_object_id", "array_keys", "ucfirst", "trim", "array_pop","array_unshift","ksort","next","current","str_replace").contains(AsciiCase.lower(name)), callee);
                    target = new Target(Dispatch.FUNCTION, name, null);
                }
            }
            Ast arguments = n.node("args");
            check(arguments != null && arguments.is("AST_ARG_LIST"), n);
            if (target.kind() == Dispatch.FUNCTION && target.name().equalsIgnoreCase("set_exception_handler"))
                check(arguments.size() == 1 && arguments.child(0) instanceof String callback
                        && functionNames.contains(AsciiCase.lower(callback)), arguments);
            var args = new ArrayList<Argument>();
            for (Object arg : arguments.children()) {
                Expr operand = expr(arg, true);
                int id = operand instanceof StoreTemp result ? result.id() : slot(TempKind.SEND);
                args.add(new Argument(id, operand, addressable(operand)));
            }
            // Nested calls registered their cleanup first. Each unfinished call frees sent
            // arguments in order, followed by its receiver.
            var slots = new ArrayList<ReleaseTemp>();
            for (Argument arg : args) slots.add(new ReleaseTemp(arg.slot()));
            if (target.receiver() instanceof StoreTemp recv) slots.add(new ReleaseTemp(recv.id()));
            var cleanup = new ReleaseTemps(List.copyOf(slots));
            releases.addAll(slots);
            int result = slot(argument ? TempKind.SEND : TempKind.VALUE);
            if (!argument) releases.add(new ReleaseTemp(result));
            return new StoreTemp(result, new Call(target, List.copyOf(args), cleanup,
                    !used || target.kind() == Dispatch.CONSTRUCTOR, at(n)));
        }
    }

    private IrSourceSpan at(Ast n) { return new IrSourceSpan(file, n.line); }
    private static boolean plainDeclaration(Ast n) {
        return n.get("attributes") == null && n.get("docComment") == null;
    }
    private static String className(Ast n) {
        check(n.is("AST_NAME"), n);
        String name = Names.GLOBAL.className(name(n), n.flags);
        check(!Set.of("self", "parent", "static").contains(AsciiCase.lower(name)), n);
        return name;
    }
    private static String name(Ast n) {
        if (n.get("name") instanceof String name) return name;
        throw unsupported(n);
    }
    private static Ast node(Object value) {
        if (value instanceof Ast n) return n;
        throw new UnsupportedIr("expected a syntax node");
    }
    private static void check(boolean ok, Ast n) { if (!ok) throw unsupported(n); }
    private static UnsupportedIr unsupported(Ast n) { return new UnsupportedIr(n.toString()); }
}
