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
import static pl.codeloom.php.parser.zend.derived.ZendConstants.ZEND_ACC_GENERATOR;

/** The first file-level subset. A declined declaration or body declines the entire unit before publication. */
public final class PhpIrLowerer {
    public static final class UnsupportedIr extends RuntimeException {
        UnsupportedIr(String reason) { super(reason, null, false, false); }
    }

    private final String file;
    private boolean evalThis;
    /** B1's language level: the Truffle subset plus constructs only the B1 backend implements. The Truffle
     * entry points keep it off, so their IR coverage (and therefore their behaviour) is unchanged. */
    private boolean extended;
    /** With {@link #lowerB1Declarations}: the folded names of the top-level declarations to link. */
    private Set<String> keep;
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

    /** B1 language level for a whole file. Unknown functions and classes resolve at run time. */
    public static IrUnit lowerB1File(String file, byte[] source, java.util.function.Predicate<String> functions,
                                     java.util.function.Predicate<String> classes) {
        return lowerB1(file,source,functions,classes,false,null);
    }
    public static IrUnit lowerB1Eval(String file, byte[] source, java.util.function.Predicate<String> functions,
                                     java.util.function.Predicate<String> classes, boolean hasThis) {
        return lowerB1(file,source,functions,classes,hasThis,null);
    }
    /** Links only the named top-level function and class declarations of an unmodified file. Its statements and
     * other declarations are not part of the unit; calling one of those raises PHP's undefined-function error. */
    public static IrUnit lowerB1Declarations(String file, byte[] source, Set<String> names) {
        Set<String> folded = new HashSet<>();
        for (String name : names) folded.add(AsciiCase.lower(name));
        return lowerB1(file,source,ignored -> false,ignored -> false,false,folded);
    }
    private static IrUnit lowerB1(String file, byte[] source, java.util.function.Predicate<String> functions,
                                  java.util.function.Predicate<String> classes, boolean hasThis, Set<String> keep) {
        ZendParser.Result parsed = ZendParser.parse(source);
        try {
            if (!parsed.diagnostics().isEmpty() || !JumpChecks.file(parsed.ast()).isEmpty())
                throw new UnsupportedIr("compile diagnostics");
        } catch (JumpChecks.Failure failure) {
            throw new PhpCompileFatal(failure.getMessage(),new IrSourceSpan(file,failure.line),"#0 {main}");
        }
        PhpIrLowerer lowerer = new PhpIrLowerer(file,functions,classes);
        lowerer.evalThis = hasThis;
        lowerer.extended = true;
        lowerer.keep = keep;
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
        if (keep != null) {
            List<Object> kept = new ArrayList<>();
            for (Object item : tree.children()) {
                Ast n = node(item);
                if ((n.is("AST_FUNC_DECL") || n.is("AST_CLASS")) && keep.contains(AsciiCase.lower(name(n)))) kept.add(n);
            }
            tree = new Ast(tree.kind,tree.flags,tree.line,tree.endLine,kept,null);
        }
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
        boolean strict = false;
        boolean first = true;
        var main = new Body(null);
        main.reuseTemps = tree.size() >= IrUnit.LARGE_BODY_STATEMENTS;
        var code = new ArrayList<Statement>();
        for (Object item : tree.children()) {
            Ast node = node(item);
            if (node.is("AST_DECLARE")) {
                Ast entries = node.node("declares");
                check(first && entries.size() == 1 && node.get("stmts") == null, node);
                Ast entry = node(entries.child(0));
                check("strict_types".equalsIgnoreCase(name(entry)) && entry.get("value") instanceof Long n && (n == 0 || n == 1), entry);
                strict |= Long.valueOf(1).equals(entry.get("value"));
                continue;
            }
            first = false;
            switch (node.kind) {
                case "AST_FUNC_DECL" -> function(node, null);
                case "AST_CLASS" -> {
                    classDecl(node);
                    var cls = classes.getLast();
                    code.add(new ClassDeclaration(cls.id(), cls.name(), cls.at()));
                }
                default -> main.append(code,node);
            }
        }
        code = main.exposeLabels(code);
        functions.set(0, main.finish(0, file, tree, List.of(), code));
        for (IrFunction function : functions) checkTransfers(function.body(), Set.of(), 0);
        return new IrUnit(file, strict, functions, classes);
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
        Set<String> classAttributes = attributes(decl,Set.of("allowdynamicproperties"));
        check((decl.flags == 0 || decl.flags == 32) && classAttributes != null && decl.get("extends") == null
                && (decl.get("implements") == null || extended), decl);
        String name = Names.GLOBAL.declared(name(decl));
        check(!Names.reservedClassName(AsciiCase.lower(name)), decl);
        List<PropertyDef> props = new ArrayList<>();
        List<Integer> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> tentative = new HashSet<>();
        for (Object item : decl.node("stmts").children()) {
            Ast member = node(item);
            if (member.is("AST_METHOD")) {
                String method = AsciiCase.lower(name(member));
                // B1 implements __get and __isset; the others need their own dispatch.
                check(seen.add(method) && !Set.of("__set", "__unset", "__call").contains(method)
                        && (extended || !Set.of("__get", "__isset").contains(method)), member);
                Set<String> methodAttributes = attributes(member,Set.of("returntypewillchange"));
                check(methodAttributes != null,member);
                if (methodAttributes.contains("returntypewillchange")) tentative.add(method);
                methods.add(function(member, name));
            } else {
                check(member.is("AST_PROP_GROUP") && Set.of(1,2,4,17,18,20).contains(member.flags)
                        && member.get("attributes") == null, member);
                for (Object element : member.node("props").children()) {
                    Ast prop = node(element);
                    check(prop.get("hooks") == null
                            && (extended || prop.get("docComment") == null) && props.stream().noneMatch(p -> p.name().equals(name(prop))), prop);
                    Ast declared = member.node("type");
                    boolean nullable = declared != null && declared.is("AST_NULLABLE_TYPE");
                    String type = typeName(nullable ? declared.node("type") : declared,false);
                    if (nullable) type = "?" + type;
                    if ("callable".equals(type) || "?callable".equals(type)) throw new PhpCompileFatal(
                            "Property " + name + "::$" + name(prop) + " cannot have type " + type, at(declared), "#0 {main}");
                    Object value = prop.get("default");
                    Expr initial = value instanceof Ast a && a.is("AST_ARRAY") ? literalArray(a,new int[1]) : scalar(value);
                    props.add(new PropertyDef(name(prop), member.flags & 7, (member.flags & 16) != 0, type,
                            initial, type == null || value != null));
                }
            }
        }
        List<String> interfaces = new ArrayList<>();
        if (decl.get("implements") != null) for (Object item : decl.node("implements").children()) {
            Ast type = node(item);
            String iface = AsciiCase.lower(className(type));
            java.util.Map<String,Integer> required = INTERFACE_METHODS.get(iface);
            check(required != null && !interfaces.contains(iface), type);
            // Accept only implementations PHP links without a diagnostic: every method present, untyped and
            // marked #[ReturnTypeWillChange] (the interfaces declare tentative return types).
            for (java.util.Map.Entry<String,Integer> entry : required.entrySet()) {
                IrFunction method = methods.stream().map(functions::get)
                        .filter(f -> f.name().equalsIgnoreCase(entry.getKey())).findFirst().orElse(null);
                check(method != null && method.visibility() == 1 && !method.isStatic() && method.returnType() == null
                        && tentative.contains(AsciiCase.lower(entry.getKey())) && method.params().size() == entry.getValue()
                        && method.params().stream().allMatch(p -> p.type() == null && !p.byRef() && !p.variadic()
                                && p.defaultValue() == null), type);
            }
            interfaces.add(iface);
        }
        if (interfaces.contains("iterator")) interfaces.add("traversable");
        classes.add(new IrClass(classes.size(), name, at(decl), decl.endLine, List.copyOf(props), List.copyOf(methods),
                List.copyOf(interfaces), classAttributes.contains("allowdynamicproperties")));
    }

    private static final java.util.Map<String,java.util.Map<String,Integer>> INTERFACE_METHODS = java.util.Map.of(
            "iterator", java.util.Map.of("current",0,"next",0,"key",0,"valid",0,"rewind",0),
            "arrayaccess", java.util.Map.of("offsetExists",1,"offsetGet",1,"offsetSet",2,"offsetUnset",1));

    /** The folded names of a declaration's attributes, or null when it has others. Doc comments do not
     * change execution and are ignored at the B1 level (Reflection is not part of it). */
    private Set<String> attributes(Ast decl, Set<String> allowed) {
        if (!extended) return plainDeclaration(decl) ? Set.of() : null;
        Ast list = decl.node("attributes");
        if (list == null) return Set.of();
        Set<String> names = new HashSet<>();
        for (Object group : list.children()) for (Object item : node(group).children()) {
            Ast attribute = node(item);
            Ast cls = attribute.node("class");
            if (attribute.get("args") != null || cls == null || !cls.is("AST_NAME")) return null;
            String folded = AsciiCase.lower(Names.GLOBAL.className(name(cls), cls.flags));
            if (folded.startsWith("\\")) folded = folded.substring(1);
            if (!allowed.contains(folded)) return null;
            names.add(folded);
        }
        return names;
    }

    private int function(Ast decl, String owner) {
        return function(decl, owner, null);
    }

    private int function(Ast decl, String owner, Body outer) {
        boolean closure = outer != null;
        int flags = decl.flags & ~ZEND_ACC_GENERATOR;
        // Methods: one visibility bit (public 1, protected 2, private 4), optionally static (16) or final (32).
        boolean method = owner != null && !closure && (extended ? (flags & ~(7 | 16 | 32)) == 0
                && Integer.bitCount(flags & 7) == 1 : flags == 1 || flags == 17);
        boolean plain = owner != null && !closure ? attributes(decl,Set.of("returntypewillchange")) != null
                : attributes(decl,Set.of()) != null;
        check((flags == 0 || closure && flags == 16 || method) && plain && decl.get("stmts") != null, decl);
        var body = new Body(owner);
        body.closure = closure;
        if (!closure) body.functionName = name(decl);
        Ast syntax = decl.body();
        Ast declared = decl.node("returnType");
        body.generator = (decl.flags & ZEND_ACC_GENERATOR) != 0 || declared != null && Ast.containsYield(syntax);
        if (body.generator && declared != null) {
            check(declared.is("AST_NAME") && Set.of("generator","iterator","traversable").contains(AsciiCase.lower(name(declared)))
                    || declared.is("AST_TYPE") && Set.of(8,13,16).contains(declared.flags), declared);
        } else body.returnType = typeName(declared,true);
        var params = new ArrayList<Param>();
        for (Object item : decl.node("params").children()) {
            Ast param = node(item);
            Ast type = param.node("type");
            check((param.flags & ~24) == 0 && attributes(param,Set.of()) != null && (param.flags != 16 || type == null),param);
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
        body.reuseTemps = syntax.size() >= IrUnit.LARGE_BODY_STATEMENTS;
        if (decl.is("AST_ARROW_FUNC")) {
            check(syntax.is("AST_RETURN"),decl);
            code = List.of(body.statement(syntax));
            // An arrow with free variables needs implicit by-value captures.
            check(body.locals.size() == params.size(),decl);
        } else code = body.statements(syntax);
        if (body.checkedReturn()) {
            var withEnd = new ArrayList<>(code);
            var empty = new ReleaseTemps(List.of());
            withEnd.add(new Region(new IrSourceSpan(file,decl.endLine),new Return(new CheckedReturn(null,true)),empty,empty));
            code = List.copyOf(withEnd);
        }
        functions.set(id, new IrFunction(id, owner, name, at(decl), decl.endLine, body.returnType,List.copyOf(params), body.generator, closure,
                (decl.flags & 16) != 0, List.copyOf(captures), List.copyOf(body.locals), List.copyOf(body.temps), code,
                method ? flags & 7 : 1));
        return id;
    }

    private static String typeName(Ast type,boolean result) {
        if (type == null) return null;
        if (type.is("AST_NULLABLE_TYPE") && result && "string".equals(typeName(type.node("type"),true))) return "?string";
        check(type.is("AST_TYPE"),type);
        return switch (type.flags) {
            case 4 -> "int";
            case 6 -> "string";
            case 7 -> "array";
            case 12 -> "callable";
            case 16 -> "mixed";
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
            case "inf" -> { check("INF".equals(n.text("name")),n); yield new Literal(Double.POSITIVE_INFINITY); }
            case "nan" -> { check("NAN".equals(n.text("name")),n); yield new Literal(Double.NaN); }
            case "php_int_max" -> new Literal(Long.MAX_VALUE);
            case "sort_numeric" -> new Literal(1L);
            default -> throw unsupported(n);
        };
        if (n.is("AST_UNARY_OP") && n.flags == 262) {
            Object operand = scalar(n.get("expr")).value();
            if (operand instanceof Long l) return new Literal(-l);
            if (operand instanceof Double d) return new Literal(-d);
        }
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

    private Operator operator(Ast n) {
        if (extended) switch (n.flags) {
            case 2: return Operator.SUB;
            case 4: return Operator.DIV;
            case 6: return Operator.SL;
            case 7: return Operator.SR;
            case 9: return Operator.BW_OR;
            case 10: return Operator.BW_AND;
            case 11: return Operator.BW_XOR;
            default: break;
        }
        return switch (n.flags) {
            case 1 -> Operator.ADD;
            case 3 -> Operator.MUL;
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
        /** __FUNCTION__: the declared name; empty for the file body. */
        String functionName = "";
        boolean closure;
        String returnType;
        boolean generator;
        boolean checkedReturn() { return returnType != null && !returnType.equals("void"); }
        final List<String> locals = new ArrayList<>();
        final List<TempKind> temps = new ArrayList<>();
        List<ReleaseTemp> releases;
        int labels;
        boolean reuseTemps;
        final java.util.EnumMap<TempKind,List<Integer>> reusable = new java.util.EnumMap<>(TempKind.class);
        final int[] used = new int[TempKind.values().length];

        Body(String owner) { this.owner = owner; }

        IrFunction finish(int id, String name, Ast at, List<Param> params, List<Statement> body) {
            return new IrFunction(id, owner, name, at(at), at.endLine,null, List.copyOf(params), false, false, false, List.of(), List.copyOf(locals),
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

        void append(List<Statement> code, Ast n) {
            if (n.is("AST_STMT_LIST")) {
                for (Object child : n.children()) append(code,node(child));
            } else code.add(statement(n));
        }

        List<Statement> statements(Ast list) {
            var code = new ArrayList<Statement>();
            for (Object item : list.children()) append(code,node(item));
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
            beginOperands();
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

        Statement switchStatement(Ast n) {
            // Dispatch once, then enter the selected arm. Literal cases use PHP's loose comparison.
            // A one-iteration loop provides the switch break target, including nested break levels.
            int subject = local("#switch" + labels++);
            beginOperands();
            Action save = new Write(subject,expr(n.get("cond"),false));
            var cleanup = new ReleaseTemps(List.copyOf(releases));
            var none = new ReleaseTemps(List.of());
            var body = new ArrayList<Statement>();
            var arms = new ArrayList<Statement>();
            String done = "#switchEnd" + labels++, otherwise = done;
            for (Object item : n.node("stmts").children()) {
                Ast arm = node(item);
                String label = "#case" + labels++;
                if (arm.get("cond") == null) otherwise = label;
                else body.add(new Branch(new Test(at(arm),new Binary(Operator.EQ,new Read(subject),scalar(arm.get("cond"))),none),
                        List.of(new Jump(label)),List.of()));
                arms.add(new Mark(label));
                arms.add(new Region(at(arm),new Unset(new Read(subject),null),none,none));
                arms.addAll(statements(arm.node("stmts")));
            }
            body.add(new Jump(otherwise));
            body.addAll(arms);
            body.add(new Mark(done));
            body.add(new Region(at(n),new Unset(new Read(subject),null),none,none));
            body.add(new LoopExit(false,1));
            // continue targeting a switch emits a compiler diagnostic and is declined at file entry.
            return new Loop(List.of(new Region(at(n),save,cleanup,cleanup)),new Test(at(n),new Literal(true),none),List.of(),body);
        }

        StaticProperty staticProperty(Ast n) {
            Ast cls = n.node("class");
            check(cls != null && cls.is("AST_NAME") && n.get("prop") instanceof String,n);
            String name = "self".equalsIgnoreCase(name(cls)) ? owner : className(cls);
            check(name != null,n);
            return new StaticProperty(name,(String)n.get("prop"));
        }

        Statement statement(Ast n) {
            switch (n.kind) {
                case "AST_IF": return branch(n, 0);
                case "AST_SWITCH": return switchStatement(n);
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
                    if (extended && value.is("AST_REF")) {
                        // FE_RESET_RW: the source place is fetched for writing; each element becomes a reference.
                        Ast target = value.node("var");
                        check(target != null && target.is("AST_VAR") && !"this".equals(name(target))
                                && (key == null || key.is("AST_VAR")), n);
                        beginOperands();
                        Expr source = expr(n.get("expr"), false);
                        check(addressable(source) && !(source instanceof StoreTemp), n);
                        Test test = new Test(at(n), new ByReference(source), new ReleaseTemps(List.copyOf(releases)));
                        return new Each(test, reserve(TempKind.VALUE), key == null ? -1 : local(name(key)),
                                local(name(target)), statements(n.node("stmts")));
                    }
                    check(value.is("AST_VAR") && (key == null || key.is("AST_VAR")), n);
                    return new Each(test(n, n.get("expr")), reserve(TempKind.VALUE),
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
                    // ArithmeticError (B1 raises it for negative shifts) is caught at the B1 level only.
                    check(Set.of("exception", "throwable", "error", "typeerror", "argumentcounterror","divisionbyzeroerror","parseerror","compileerror","valueerror","fibererror","closedgeneratorexception").contains(AsciiCase.lower(type))
                            || extended && AsciiCase.lower(type).equals("arithmeticerror"), handler);
                    protectedBody = List.of(new TryCatch(labels++, at(n), protectedBody, type,
                            local(name(handler.node("var"))), statements(handler.node("stmts"))));
                }
                return n.node("finally") == null ? protectedBody.getFirst() : new Finally(protectedBody, statements(n.node("finally")), reserve(TempKind.VALUE));
            }
            beginOperands();
            Action action = switch (n.kind) {
                case "AST_ASSIGN_REF" -> {
                    Ast dest = n.node("var");
                    Expr source = expr(n.get("expr"),false);
                    check(dest.is("AST_VAR") && addressable(source),n);
                    yield new Bind(local(name(dest)),source);
                }
                case "AST_GLOBAL" -> new Global(local(name(n.node("var"))));
                case "AST_STATIC" -> {
                    // Function and method statics only: a closure's are per object, a file's per op_array.
                    check(extended && !closure && !functionName.isEmpty() && n.node("var").is("AST_VAR"), n);
                    int id = local(name(n.node("var")));
                    yield new StaticVar(id, n.get("default") == null ? null : expr(n.get("default"), false));
                }
                case "AST_UNSET" -> {
                    Ast place = n.node("var");
                    if (place.is("AST_VAR")) yield new Unset(expr(place,false), null);
                    if (extended && place.is("AST_DIM") && place.get("dim") != null && place.node("expr") != null
                            && (place.node("expr").is("AST_DIM") || place.node("expr").is("AST_STATIC_PROP"))) {
                        // FETCH_DIM_UNSET: containers on the path are separated but never created.
                        Expr root = expr(place.get("expr"),false);
                        check(addressable(root),place);
                        yield new Unset(root,expr(place.get("dim"),false));
                    }
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
                    if (place.is("AST_STATIC_PROP")) yield new WriteStatic(staticProperty(place),expr(n.get("expr"),false));
                    if (place.is("AST_VAR")) yield new Write(local(name(place)), expr(n.get("expr"), false));
                    if (place.is("AST_DIM")) {
                        var path = new ArrayList<Ast>();
                        while (place != null && place.is("AST_DIM")) { path.addFirst(place); place = place.node("expr"); }
                        check(place != null && (place.is("AST_VAR") || place.is("AST_PROP") || place.is("AST_STATIC_PROP")), n);
                        var keys = new ArrayList<Expr>();
                        for (Ast dim : path) keys.add(dim.get("dim") == null ? null : expr(dim.get("dim"), false));
                        yield new WriteDim(expr(place,false), java.util.Collections.unmodifiableList(keys), hold(expr(n.get("expr"), false)));
                    }
                    check(place.is("AST_PROP"), place);
                    if (!(place.get("prop") instanceof String))
                        yield new WriteNamedProperty(receiver(place),expr(place.get("prop"),false),expr(n.get("expr"),false));
                    yield new WriteProperty(receiver(place), (String) place.get("prop"), expr(n.get("expr"), false));
                }
                case "AST_CALL", "AST_METHOD_CALL", "AST_STATIC_CALL", "AST_NEW" -> new Evaluate(call(n, false, false));
                case "AST_ASSIGN_OP" -> {
                    Ast place = n.node("var");
                    if (n.flags == 260) {
                        check(place.is("AST_DIM") && place.node("expr") != null && place.node("expr").is("AST_VAR")
                                && place.get("dim") != null,n);
                        yield new CoalesceDim(local(name(place.node("expr"))),hold(expr(place.get("dim"),false)),
                                hold(expr(n.get("expr"),false)));
                    }
                    if (extended && !place.is("AST_VAR")) yield new Evaluate(updatePlace(n));
                    check(place.is("AST_VAR") && (n.flags == 1 || n.flags == 8 || extended && n.flags >= 1 && n.flags <= 11), n);
                    int id = local(name(place));
                    yield new Write(id, new Binary(operator(n), new Read(id), expr(n.get("expr"), false)));
                }
                default -> new Evaluate(expr(n, false));
            };
            var cleanup = new ReleaseTemps(List.copyOf(releases));
            return new Region(at(n), action, cleanup, cleanup);
        }

        /** ZEND_ASSIGN_DIM_OP / ZEND_ASSIGN_OBJ_OP / ZEND_ASSIGN_STATIC_PROP_OP. */
        Expr updatePlace(Ast n) {
            Ast place = n.node("var");
            check(n.flags >= 1 && n.flags <= 11, n);
            Operator op = operator(n);
            if (place.is("AST_PROP") || place.is("AST_STATIC_PROP")) {
                check(place.is("AST_STATIC_PROP") || place.get("prop") instanceof String, n);
                Expr root = place.is("AST_PROP") ? expr(place,false) : staticProperty(place);
                return new UpdatePlace(root, List.of(), op, expr(n.get("expr"), false));
            }
            List<Ast> path = new ArrayList<>();
            while (place != null && place.is("AST_DIM")) { path.addFirst(place); place = place.node("expr"); }
            check(!path.isEmpty() && place != null && (place.is("AST_VAR") && !"this".equals(name(place))
                    || place.is("AST_PROP") || place.is("AST_STATIC_PROP")), n);
            List<Expr> keys = new ArrayList<>();
            for (Ast dim : path) {
                check(dim.get("dim") != null, dim);
                Expr key = expr(dim.get("dim"), false);
                keys.add(key instanceof Read || key instanceof Literal ? key : hold(key));
            }
            return new UpdatePlace(expr(place,false), List.copyOf(keys), op, expr(n.get("expr"), false));
        }

        Expr receiver(Ast n) {
            Ast base = node(n.get("expr"));

            return expr(base, false);
        }

        Expr expr(Object value, boolean argument) {
            if (!(value instanceof Ast)) return value instanceof Boolean ? new Literal(value) : scalar(value);
            Ast n = node(value);
            return switch (n.kind) {
                case "AST_YIELD", "AST_YIELD_FROM" -> {
                    check(!enclosing.equals(file), n);
                    generator = true;
                    boolean from = n.is("AST_YIELD_FROM");
                    yield owned(new Yield(n.get("key") == null ? null : expr(n.get("key"),false),
                            expr(n.get(from ? "expr" : "value"),false),from));
                }
                case "AST_CONST" -> {
                    if (!extended) yield scalar(n);
                    try { yield scalar(n); } catch (UnsupportedIr unknown) {
                        String constant = n.text("name");
                        if (constant.startsWith("\\")) constant = constant.substring(1);
                        check(!constant.contains("\\") && !builtinConstant(constant), n);
                        yield new Constant(constant);
                    }
                }
                case "AST_ASSIGN" -> {
                    // ZEND_ASSIGN's result is its own copy of the assigned value.
                    Ast place = n.node("var");
                    check(extended && place.is("AST_VAR") && !"this".equals(name(place)), n);
                    int id = local(name(place));
                    yield owned(new Assign(id, expr(n.get("expr"), false)));
                }
                case "AST_MAGIC_CONST" -> {
                    // T_LINE, T_FILE, T_DIR; T_CLASS_C, T_METHOD_C and T_FUNC_C outside closures.
                    check(extended && (n.flags >= 346 && n.flags <= 348
                            || !closure && (n.flags == 349 || n.flags == 351 || n.flags == 352)), n);
                    yield new Literal(switch (n.flags) {
                        case 346 -> (Object)(long)n.line;
                        case 347 -> file;
                        case 348 -> dirname(file);
                        case 349 -> owner == null ? "" : owner;
                        case 351 -> functionName.isEmpty() || owner == null ? functionName : owner + "::" + functionName;
                        default -> functionName;
                    });
                }
                case "AST_INSTANCEOF" -> {
                    Ast cls = n.node("class");
                    check(extended && cls != null && cls.is("AST_NAME"), n);
                    String type = "self".equalsIgnoreCase(name(cls)) ? owner : className(cls);
                    check(type != null, n);
                    yield new InstanceOf(binaryOperand(expr(n.get("expr"), false)), type);
                }
                case "AST_ARRAY" -> {
                    ArrayValue pure = largeLiteral(n);
                    if (pure != null) yield owned(pure);
                    var items = new ArrayList<Item>();
                    for (Object element : n.children()) {
                        Ast e = node(element);
                        check(e.is("AST_ARRAY_ELEM") && (e.flags == 0 || extended && e.flags == 1), e);
                        Expr key = e.get("key") == null ? null : expr(e.get("key"), false);
                        Expr item = expr(e.get("value"), false);
                        if (e.flags == 1) {
                            check(addressable(item) && !(item instanceof StoreTemp), e);
                            item = new ByReference(item);
                        }
                        items.add(new Item(key, item));
                    }
                    yield owned(new ArrayValue(List.copyOf(items)));
                }
                case "AST_DIM" -> dim(n, false);
                case "AST_STATIC_PROP" -> staticProperty(n);
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
                    if (extended && n.flags == 18) yield new Not(new Not(expr(n.get("expr"),false)));
                    if (extended && (n.flags == 5 || n.flags == 7))
                        yield owned(new Convert(expr(n.get("expr"),false), n.flags == 5 ? "float" : "array"));
                    check(n.flags == 4 || n.flags == 6,n);
                    yield new Cast(expr(n.get("expr"),false),n.flags == 6);
                }
                case "AST_UNARY_OP" -> {
                    if (n.flags == 14) yield new Not(expr(n.get("expr"), false));
                    if (extended && (n.flags == 261 || n.flags == 262)) {
                        try { yield scalar(n); } catch (UnsupportedIr notConstant) {
                            // zend_compile_unary_pm: ZEND_MUL by -1 (or 1).
                            yield new Binary(Operator.MUL, binaryOperand(expr(n.get("expr"), false)),
                                    new Literal(n.flags == 262 ? -1L : 1L));
                        }
                    }
                    yield scalar(n);
                }
                case "AST_ASSIGN_OP" -> {
                    Ast place = n.node("var");
                    if (extended && !place.is("AST_VAR") && n.flags != 260) yield owned(updatePlace(n));
                    check(place.is("AST_VAR") && (n.flags == 1 || n.flags == 8 || extended && n.flags >= 1 && n.flags <= 11),n);
                    int id = local(name(place));
                    yield new Update(id,new Binary(operator(n),new Read(id),expr(n.get("expr"),false)));
                }
                case "AST_CONDITIONAL" -> {
                    if (n.get("true") == null) yield owned(new Binary(Operator.ELVIS, expr(n.get("cond"), false), expr(n.get("false"), false)));
                    yield new Choose(expr(n.get("cond"), false), expr(n.get("true"), false), expr(n.get("false"), false));
                }
                case "AST_PRE_INC", "AST_PRE_DEC", "AST_POST_INC", "AST_POST_DEC" -> {
                    Ast v = n.node("var");
                    if (v.is("AST_PROP") || v.is("AST_DIM") || v.is("AST_STATIC_PROP")) yield new IncrementPlace(expr(v,false),n.kind.endsWith("INC"),n.kind.startsWith("AST_PRE"));
                    check(v.is("AST_VAR"), n);
                    yield new Increment(local(name(v)), n.kind.endsWith("INC"), n.kind.startsWith("AST_PRE"));
                }
                case "AST_CLOSURE", "AST_ARROW_FUNC" -> {
                    check((n.flags & ~(ZEND_ACC_GENERATOR | 16)) == 0, n);
                    int id = function(n, owner, this);
                    yield owned(new Closure(id, functions.get(id).captures()));
                }
                case "AST_INCLUDE_OR_EVAL" -> {
                    if (extended && n.flags != 1) {
                        check(n.flags == 2 || n.flags == 4 || n.flags == 8 || n.flags == 16, n);
                        yield owned(new Include(expr(n.get("expr"), false), n.flags, at(n)));
                    }
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
                case "AST_NEW", "AST_CALL", "AST_METHOD_CALL", "AST_STATIC_CALL" -> call(n, argument, true);
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

        void beginOperands() {
            releases = new ArrayList<>();
            java.util.Arrays.fill(used,0);
        }

        // Both exits of a region drain its operands. Reuse those physical slots in large
        // frames, keeping the iterator and suspended-finally slots distinct across regions.
        int slot(TempKind kind) {
            if (!reuseTemps) return reserve(kind);
            var pool = reusable.computeIfAbsent(kind,k -> new ArrayList<>());
            int i = used[kind.ordinal()]++;
            if (i == pool.size()) pool.add(reserve(kind));
            return pool.get(i);
        }

        int reserve(TempKind kind) {
            temps.add(kind);
            return temps.size() - 1;
        }

        boolean addressable(Expr e) {
            return switch (e) {
                case Read ignored -> true;
                case Property ignored -> true;
                case StaticProperty ignored -> true;
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
                    String name = extended && cls.is("AST_NAME") && "self".equalsIgnoreCase(name(cls)) && owner != null ? owner : className(cls);
                    check(extended || classNames.contains(AsciiCase.lower(name)) || externalClass.test(AsciiCase.lower(name)) || name.equalsIgnoreCase("Fiber") || name.equalsIgnoreCase("Exception") || name.equalsIgnoreCase("stdClass"), n);
                    target = new Target(Dispatch.CONSTRUCTOR, name, new StoreTemp(slot(TempKind.CONSTRUCTION), new Allocate(name, at(n))));
                }
            } else if (extended && n.is("AST_STATIC_CALL") && n.node("class") != null && n.node("class").is("AST_NAME")
                    && n.get("method") instanceof String method
                    && !("fiber".equalsIgnoreCase(name(n.node("class"))) && Set.of("suspend","getcurrent").contains(AsciiCase.lower(method)))) {
                Ast cls = n.node("class");
                String type = "self".equalsIgnoreCase(name(cls)) ? owner : className(cls);
                check(type != null, n);
                target = new Target(Dispatch.STATIC, method, new Literal(type));
            } else if (n.is("AST_STATIC_CALL")) {
                check(n.node("class") != null && n.node("class").is("AST_NAME")
                        && className(n.node("class")).equalsIgnoreCase("Fiber") && n.get("method") instanceof String method
                        && Set.of("suspend","getcurrent").contains(AsciiCase.lower(method)),n);
                target = new Target(Dispatch.STATIC,(String)n.get("method"),new Literal("Fiber"));
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
                    check(functionNames.contains(AsciiCase.lower(name)) || externalFunction.test(AsciiCase.lower(name))
                            || extended && !FRAME_SENSITIVE.contains(AsciiCase.lower(name)) || Set.of("set_exception_handler", "count", "strlen", "array_slice", "array_key_exists", "is_int", "is_string", "is_array", "str_repeat", "get_object_vars", "is_object", "spl_object_id", "gc_collect_cycles","is_callable","is_numeric","intval","var_dump","strtolower","array_map","implode","array_keys", "ucfirst", "trim", "array_pop","array_unshift","ksort","next","current","str_replace").contains(AsciiCase.lower(name)), callee);
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

    /** Builtins that read or write the caller's frame; the legacy frontend lowers them specially. */
    private static final Set<String> FRAME_SENSITIVE = Set.of("compact", "extract", "get_defined_vars", "func_get_args",
            "func_num_args", "func_get_arg", "parse_str", "mb_parse_str", "get_called_class", "get_class",
            "get_parent_class", "assert", "call_user_func", "forward_static_call", "forward_static_call_array");

    /** The engine constants {@link #scalar} folds. Constant names are case-sensitive except true, false and null. */
    public static boolean foldedConstant(String name) {
        return switch (AsciiCase.lower(name)) {
            case "true", "false", "null" -> true;
            default -> name.equals("INF") || name.equals("NAN") || name.equals("PHP_INT_MAX") || name.equals("SORT_NUMERIC");
        };
    }

    /** PHP's own constants are not modelled at the B1 level; a unit using one is declined, and the runtime stops
     * a request that asks about one (defined(), define()) instead of answering for an engine it does not model. */
    public static boolean builtinConstant(String name) {
        for (String prefix : List.of("E_", "PHP_", "M_", "SORT_", "COUNT_", "JSON_", "PREG_", "ENT_", "LC_", "STR_", "ARRAY_",
                "INI_", "DATE_", "FILTER_", "PATHINFO_", "SEEK_", "LOCK_", "FILE_", "GLOB_", "MB_", "ZEND_", "UPLOAD_", "CURL",
                "PASSWORD_", "OPENSSL_", "LIBXML_", "XML_", "IMAGETYPE_", "IMG_", "CRYPT_", "HTML_", "DEBUG_", "CASE_",
                "EXTR_", "PREG", "MT_RAND", "PHP", "DNS_", "LOG_", "STREAM_", "SQLITE", "MYSQLI_", "INTL_", "IDNA_", "ICONV_"))
            if (name.startsWith(prefix)) return true;
        return List.of("DIRECTORY_SEPARATOR", "PATH_SEPARATOR", "DEFAULT_INCLUDE_PATH", "PEAR_INSTALL_DIR", "PEAR_EXTENSION_DIR",
                "STDIN", "STDOUT", "STDERR", "NAN", "INF", "UPLOAD_ERR_OK", "SID", "NULL", "TRUE", "FALSE").contains(name);
    }

    /** zend_dirname of a file name, as __DIR__ reports it. */
    private static String dirname(String path) {
        int end = path.length();
        while (end > 1 && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) end--;
        int slash = Math.max(path.lastIndexOf('/', end - 1), path.lastIndexOf('\\', end - 1));
        if (slash < 0) return ".";
        while (slash > 0 && (path.charAt(slash - 1) == '/' || path.charAt(slash - 1) == '\\')) slash--;
        return slash == 0 ? path.substring(0, 1) : path.substring(0, slash);
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
