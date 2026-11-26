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
    private final List<IrFunction> functions = new ArrayList<>();
    private final List<IrClass> classes = new ArrayList<>();
    private final Set<String> functionNames = new HashSet<>();
    private final Set<String> classNames = new HashSet<>();

    private PhpIrLowerer(String file) { this.file = file; }

    public static IrUnit lowerFile(String file, byte[] source) {
        var parsed = ZendParser.parse(source);
        if (!parsed.diagnostics().isEmpty() || !JumpChecks.file(parsed.ast()).isEmpty()) {
            throw new UnsupportedIr("compile diagnostics");
        }
        return lowerFile(file, parsed.ast());
    }

    /** The normal Truffle entry point has already run parser diagnostics and JumpChecks. */
    public static IrUnit lowerFile(String file, Ast checkedFile) {
        return new PhpIrLowerer(file).unit(checkedFile);
    }

    private IrUnit unit(Ast tree) {
        functions.add(null); // function 0 is the file
        // PHP binds unconditional declarations before its first statement, including forward calls.
        for (Object item : tree.children()) {
            Ast n = node(item);
            if (n.is("AST_FUNC_DECL")) check(functionNames.add(AsciiCase.lower(name(n))), n);
            if (n.is("AST_CLASS")) check(classNames.add(AsciiCase.lower(name(n))), n);
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
        functions.set(0, main.finish(0, file, tree, List.of(), code));
        return new IrUnit(file, functions, classes);
    }

    private void classDecl(Ast decl) {
        check(decl.flags == 0 && plainDeclaration(decl) && decl.get("extends") == null
                && decl.get("implements") == null, decl);
        String name = Names.GLOBAL.declared(name(decl));
        check(!Names.reservedClassName(AsciiCase.lower(name)), decl);
        List<String> props = new ArrayList<>();
        List<Integer> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : decl.node("stmts").children()) {
            Ast member = node(item);
            if (member.is("AST_METHOD")) {
                String method = AsciiCase.lower(name(member));
                check(seen.add(method) && !Set.of("__get", "__set", "__isset", "__unset", "__call").contains(method), member);
                methods.add(function(member, name));
            } else {
                check(member.is("AST_PROP_GROUP") && member.flags == 1 && member.get("type") == null
                        && member.get("attributes") == null, member);
                for (Object element : member.node("props").children()) {
                    Ast prop = node(element);
                    check(prop.get("default") == null && prop.get("hooks") == null
                            && prop.get("docComment") == null && !props.contains(name(prop)), prop);
                    props.add(name(prop));
                }
            }
        }
        classes.add(new IrClass(classes.size(), name, at(decl), decl.endLine, List.copyOf(props), List.copyOf(methods)));
    }

    private int function(Ast decl, String owner) {
        check((decl.flags == 0 || owner != null && decl.flags == 1) && plainDeclaration(decl)
                && decl.get("returnType") == null && decl.get("stmts") != null, decl);
        var body = new Body(owner);
        var params = new ArrayList<Param>();
        for (Object item : decl.node("params").children()) {
            Ast param = node(item);
            Ast type = param.node("type");
            check((param.flags & ~8) == 0 && param.get("default") == null && plainDeclaration(param)
                    && (type == null || type.is("AST_TYPE") && type.flags == 4), param);
            String name = name(param);
            check(body.local(name) == params.size(), param);
            params.add(new Param(name, param.flags == 8, type != null, at(param)));
        }
        int id = functions.size();
        String name = Names.GLOBAL.declared(name(decl));
        functions.add(body.finish(id, name, decl, params, body.statements(decl.body())));
        return id;
    }

    private final class Body {
        final String owner;
        final List<String> locals = new ArrayList<>();
        final List<TempKind> temps = new ArrayList<>();
        List<ReleaseTemp> releases;
        int labels;

        Body(String owner) { this.owner = owner; }

        IrFunction finish(int id, String name, Ast at, List<Param> params, List<Statement> body) {
            return new IrFunction(id, owner, name, at(at), at.endLine, List.copyOf(params), List.copyOf(locals),
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
            return List.copyOf(code);
        }

        Statement statement(Ast n) {
            if (n.is("AST_TRY")) {
                Ast catches = n.node("catches");
                check(n.get("finally") == null && catches.size() == 1, n);
                Ast handler = node(catches.child(0));
                Ast types = handler.node("class");
                check(types.size() == 1 && handler.node("var") != null, handler);
                String type = className(node(types.child(0)));
                check(Set.of("exception", "throwable", "error", "typeerror", "argumentcounterror").contains(AsciiCase.lower(type)), handler);
                return new TryCatch(labels++, at(n), statements(n.node("try")), type,
                        local(name(handler.node("var"))), statements(handler.node("stmts")));
            }
            releases = new ArrayList<>();
            Action action = switch (n.kind) {
                case "AST_ECHO" -> new Echo(expr(n.get("expr"), false));
                case "AST_RETURN" -> new Return(expr(n.get("expr"), false));
                case "AST_THROW" -> new Raise(expr(n.get("expr"), false));
                case "AST_ASSIGN" -> {
                    Ast place = n.node("var");
                    if (place.is("AST_VAR")) yield new Write(local(name(place)), expr(n.get("expr"), false));
                    check(place.is("AST_PROP") && place.get("prop") instanceof String, place);
                    yield new WriteProperty(receiver(place), (String) place.get("prop"), expr(n.get("expr"), false));
                }
                case "AST_CALL", "AST_METHOD_CALL", "AST_NEW" -> new Evaluate(call(n, false, false));
                default -> throw unsupported(n);
            };
            var cleanup = new ReleaseTemps(List.copyOf(releases));
            return new Region(at(n), action, cleanup, cleanup);
        }

        Expr receiver(Ast n) {
            Ast base = node(n.get("expr"));
            check(base.is("AST_VAR"), base);
            return expr(base, false);
        }

        Expr expr(Object value, boolean argument) {
            if (value == null || value instanceof String || value instanceof Long) return new Literal(value);
            Ast n = node(value);
            return switch (n.kind) {
                case "AST_VAR" -> {
                    if ("this".equals(name(n))) {
                        check(owner != null, n);
                        yield new This();
                    }
                    yield new Read(local(name(n)));
                }
                case "AST_PROP" -> {
                    check(n.get("prop") instanceof String, n);
                    yield new Property(receiver(n), (String) n.get("prop"));
                }
                case "AST_BINARY_OP" -> {
                    check(n.flags == 1 || n.flags == 8, n);
                    Expr left = binaryOperand(expr(n.get("left"), false));
                    yield new Binary(n.flags == 1 ? Operator.ADD : Operator.CONCAT, left,
                            binaryOperand(expr(n.get("right"), false)));
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

        Expr call(Ast n, boolean argument, boolean used) {
            Target target;
            if (n.is("AST_NEW")) {
                String name = className(node(n.get("class")));
                check(classNames.contains(AsciiCase.lower(name)) || name.equalsIgnoreCase("Exception"), n);
                target = new Target(Dispatch.CONSTRUCTOR, name, new StoreTemp(slot(TempKind.CONSTRUCTION), new Allocate(name, at(n))));
            } else if (n.is("AST_METHOD_CALL")) {
                check(n.get("method") instanceof String, n);
                target = new Target(Dispatch.METHOD, (String) n.get("method"), new StoreTemp(slot(TempKind.RECEIVER), receiver(n)));
            } else {
                Ast callee = node(n.get("expr"));
                check(callee.is("AST_NAME"), callee);
                String name = Names.GLOBAL.functionName(name(callee), callee.flags)[0];
                // Frame-sensitive builtins and intrinsics have their own lowering in the legacy frontend.
                check(functionNames.contains(AsciiCase.lower(name)) || name.equalsIgnoreCase("set_exception_handler"), callee);
                target = new Target(Dispatch.FUNCTION, name, null);
            }
            Ast arguments = n.node("args");
            check(arguments != null && arguments.is("AST_ARG_LIST"), n);
            if (target.kind() == Dispatch.FUNCTION && target.name().equalsIgnoreCase("set_exception_handler"))
                check(arguments.size() == 1 && arguments.child(0) instanceof String callback
                        && functionNames.contains(AsciiCase.lower(callback)), arguments);
            var args = new ArrayList<Argument>();
            for (Object arg : arguments.children()) {
                Expr operand = expr(arg, true);
                check(operand instanceof Read || operand instanceof Literal || operand instanceof StoreTemp, n);
                int id = operand instanceof StoreTemp result ? result.id() : slot(TempKind.SEND);
                args.add(new Argument(id, operand, operand instanceof Read));
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
