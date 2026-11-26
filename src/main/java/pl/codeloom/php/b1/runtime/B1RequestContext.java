package pl.codeloom.php.b1.runtime;

import java.io.OutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** All mutable execution and linkage state belongs to this request. */
public final class B1RequestContext {
    private static final pl.codeloom.php.ir.IrUnit.Operator[] OPERATORS = pl.codeloom.php.ir.IrUnit.Operator.values();
    private final Map<String, String> foldedNames = new HashMap<>();
    public String folded(String name) { return foldedNames.computeIfAbsent(name, pl.codeloom.php.ir.AsciiCase::lower); }
    private int emitted;
    public int lintedClasses() { return emitted; }
    private int largestMethod, outlineHelpers;
    public int largestMethod() { return largestMethod; }
    public int outlineHelpers() { return outlineHelpers; }
    public void linked(int count,int helpers,int max) {
        emitted += count; outlineHelpers += helpers; largestMethod = Math.max(largestMethod,max);
    }
    private final OutputStream output;
    private final Map<String, B1Callable> functions = new HashMap<>();
    private final Map<String, B1Callable> files = new HashMap<>();
    private final Map<String, ClassDef> classes = new HashMap<>();
    private final LinkedHashMap<String, B1Ref> globals = new LinkedHashMap<>();
    private boolean abortDestructors;
    boolean destructorsAborted() { return abortDestructors; }
    private final LinkedHashSet<B1Object> destructibles = new LinkedHashSet<>();
    private final ArrayDeque<Long> freeObjectIds = new ArrayDeque<>();
    private long nextObjectId = 1;
    long acquireObjectId() { return freeObjectIds.isEmpty() ? nextObjectId++ : freeObjectIds.pop(); }
    void releaseObjectId(long id) { freeObjectIds.push(id); }
    private B1Frame frame;
    private String exceptionHandler;

    public record ClassDef(String name, List<String> props, Map<String, B1Callable> methods, Map<String, Object> defaults, Map<String,Integer> visibility, pl.codeloom.php.ir.IrSourceSpan declaration) {
        public ClassDef(String name, List<String> props, Map<String, B1Callable> methods) {
            this(name, props, methods, Map.of(),Map.of(),null);
        }
        public ClassDef {
            props = List.copyOf(props);
            methods = Map.copyOf(methods);
            defaults = Map.copyOf(defaults);
            visibility = Map.copyOf(visibility);
        }
        @HotPath(lookup = true)
        B1Callable method(B1RequestContext ctx,String lower) { return methods.get(lower); }
    }

    public B1RequestContext(OutputStream output) {
        this.output = output;
        var methods = new HashMap<String, B1Callable>();
        for (String method : List.of("__construct", "getMessage", "getLine", "getPrevious", "getFile", "getCode", "getTrace", "getTraceAsString")) {
            methods.put(pl.codeloom.php.ir.AsciiCase.lower(method), new ExceptionMethod(method));
        }
        for (String type : List.of("Exception", "Error", "TypeError", "ArgumentCountError", "ValueError", "ParseError", "CompileError")) {
            classes.put(pl.codeloom.php.ir.AsciiCase.lower(type), new ClassDef(type,
                    List.of("message", "file", "line", "code", "trace", "previous"), methods));
        }
        classes.put("stdclass", new ClassDef("stdClass", List.of(), Map.of()));
        builtin("count",1,"value","mode");
        builtin("strlen",1,"string");
        builtin("array_slice",2,"array","offset","length","preserve_keys");
        builtin("array_key_exists",2,"key","array");
        for (String name : List.of("is_int","is_string","is_array","is_object")) builtin(name,1,"value");
        builtin("set_exception_handler",1,"callback");
        builtin("get_object_vars",1,"object");
        builtin("str_repeat",2,"string","times");
        builtin("array_keys",1,"array","filter_value","strict");
        builtin("spl_object_id",1,"object");
        builtin("trim",1,"string","characters");
        builtin("ucfirst",1,"string");
        builtin("str_replace",3,"search","replace","subject","count");
        builtin("ksort",1,"array","flags");
        builtin("array_pop",1,"array");
        builtin("array_unshift",1,"array","values");
        builtin("next",1,"array");
        builtin("current",1,"array");
    }

    private void builtin(String name,int required,String... params) {
        functions.put(name,new Builtin(name,required,params));
    }

    private static final class Builtin extends B1Callable {
        final String name;
        final int required;
        Builtin(String name,int required,String[] params) {
            super(references(name,params.length),params);
            this.name = name;
            this.required = required;
        }
        private static boolean[] references(String name,int count) {
            boolean[] refs = new boolean[count];
            refs[0] = name.equals("ksort") || name.equals("array_pop") || name.equals("array_unshift") || name.equals("next");
            if (name.equals("str_replace")) refs[3] = true;
            return refs;
        }
        private void required(B1RequestContext ctx,Object arg,int index) {
            if (arg == OMITTED) throw ctx.error("ArgumentCountError",name + "(): Argument #" + index + " ($" + locals[index-1] + ") not passed");
        }
        @Override Object sparse(B1RequestContext ctx,B1Object self,ShortNamed args) {
            for (int i = 0; i < required; i++) required(ctx,args.at(i),i+1);
            if (name.equals("array_slice")) return slice(ctx,args.at(0),args.at(1),args.at(2),args.at(3));
            throw arityError(ctx,args.count());
        }
        private B1Thrown arityError(B1RequestContext ctx,int count) {
            int expected = count < required ? required : arity();
            String limit = required == arity() ? "exactly" : count < required ? "at least" : "at most";
            return ctx.error("ArgumentCountError",name + "() expects " + limit + " " + expected + " argument"
                    + (expected == 1 ? "" : "s") + ", " + count + " given");
        }
        @Override public Object invoke0(B1RequestContext ctx,B1Object self) { throw arityError(ctx,0); }
        @Override public Object invoke1(B1RequestContext ctx, B1Object self, Object a) {
            return switch (name) {
                case "is_array" -> a instanceof B1Array;
                case "is_string" -> a instanceof String || a instanceof B1Bytes;
                case "is_int" -> a instanceof Long;
                case "count" -> (long)array(ctx,a,1,"value","Countable|array").size();
                case "strlen" -> (long)stringParam(ctx,a).length;
                case "get_object_vars" -> {
                    if (!(a instanceof B1Object obj)) throw badType(ctx,a,1,"object");
                    yield obj.publicProperties(ctx);
                }
                case "is_object" -> a instanceof B1Object || a instanceof B1Closure;
                case "spl_object_id" -> {
                    if (a instanceof B1Object obj) yield obj.id;
                    if (a instanceof B1Closure closure) yield closure.id;
                    throw badType(ctx,a,1,"object");
                }
                case "ucfirst" -> {
                    byte[] bytes = stringParam(ctx,a).clone();
                    if (bytes.length > 0 && bytes[0] >= 'a' && bytes[0] <= 'z') bytes[0] -= 32;
                    yield B1Bytes.of(bytes);
                }
                case "trim" -> {
                    byte[] bytes = stringParam(ctx,a);
                    int start = 0, end = bytes.length;
                    while (start < end && trimByte(bytes[start])) start++;
                    while (start < end && trimByte(bytes[end-1])) end--;
                    yield B1Bytes.of(Arrays.copyOfRange(bytes,start,end));
                }
                case "current" -> array(ctx,a,1,"array","array").current();
                case "next" -> ((B1Ref)a).writable(ctx).advance();
                case "array_pop" -> ((B1Ref)a).writable(ctx).pop(ctx);
                case "array_keys" -> {
                    B1Array array = array(ctx,a,1,"array","array"), keys = new B1Array(array.size());
                    for (int i = 0; i < array.size(); i++) keys.append(ctx,array.keyAt(i));
                    yield keys;
                }
                case "set_exception_handler" -> {
                    Object previous = ctx.exceptionHandler;
                    ctx.exceptionHandler = a == null ? null : a.toString();
                    yield previous;
                }
                default -> throw arityError(ctx,1);
            };
        }
        private static boolean trimByte(byte b) { return b == 0 || b == ' ' || b >= 9 && b <= 11 || b == 13; }
        private B1Thrown badType(B1RequestContext ctx,Object value,int index,String type) {
            return ctx.error("TypeError",name + "(): Argument #" + index + " ($" + locals[index-1] + ") must be of type "
                    + type + ", " + ctx.argumentType(value) + " given");
        }
        private byte[] stringParam(B1RequestContext ctx,Object a) {
            if (a instanceof B1Array || a instanceof B1Closure || a instanceof B1Object obj && obj.method(ctx,"__tostring") == null)
                throw badType(ctx,a,1,"string");
            if (a == null) ctx.diagnostic("Deprecated",name + "(): Passing null to parameter #1 ($" + locals[0] + ") of type string is deprecated");
            return ctx.stringBytes(a);
        }
        @Override public Object invoke2(B1RequestContext ctx, B1Object self, Object a, Object b) {
            required(ctx,a,1);
            if (name.equals("ksort")) { ((B1Ref)a).writable(ctx).sortKeys(ctx,b); return true; }
            if (name.equals("array_unshift")) {
                B1Ref ref = (B1Ref)a;
                B1Array copy = new B1Array(1); copy.append(ctx,b);
                B1Array source = array(ctx,ref.get(),1,"array","array");
                for (int i = 0; i < source.size(); i++) {
                    Object key = source.keyAt(i);
                    if (key instanceof Long) copy.append(ctx,source.rawAt(i));
                    else copy.put(ctx,key,source.rawAt(i));
                }
                ref.set(ctx,copy); copy.release(ctx);
                return (long)copy.size();
            }
            if (name.equals("str_repeat")) {
                byte[] part = stringParam(ctx,a);
                int times = Math.toIntExact(ctx.integral(b,"str_repeat()",2,"times"));
                if (times < 0) throw ctx.error("ValueError","str_repeat(): Argument #2 ($times) must be greater than or equal to 0");
                byte[] bytes = new byte[Math.multiplyExact(part.length,times)];
                for (int i = 0; i < bytes.length; i += part.length) System.arraycopy(part,0,bytes,i,part.length);
                return B1Bytes.of(bytes);
            }
            if (name.equals("array_key_exists")) {
                B1Array array = array(ctx,b,2,"array","array");
                if (a instanceof B1Array || a instanceof B1Object || a instanceof B1Closure)
                    throw ctx.error("TypeError","Cannot access offset of type " + ctx.typeName(a) + " on array");
                if (a == null) ctx.diagnostic("Deprecated","Using null as the key parameter for array_key_exists() is deprecated, use an empty string instead");
                return array.contains(a);
            }
            if (name.equals("array_slice")) return slice(ctx,a,b,null,false);
            if (name.equals("count")) {
                B1Array array = array(ctx,a,1,"value","Countable|array");
                long mode = ctx.integral(b,"count()",2,"mode");
                if (mode == 0) return (long)array.size();
                if (mode == 1) return recursiveSize(array);
                throw ctx.error("ValueError","count(): Argument #2 ($mode) must be either COUNT_NORMAL or COUNT_RECURSIVE");
            }
            throw arityError(ctx,2);
        }
        private static long recursiveSize(B1Array array) {
            long size = array.size();
            for (int i = 0; i < array.size(); i++)
                if (array.valueAt(i) instanceof B1Array nested) size += recursiveSize(nested);
            return size;
        }
        private B1Array array(B1RequestContext ctx, Object value, int index, String param, String type) {
            if (value instanceof B1Array array) return array;
            throw ctx.error("TypeError",name + "(): Argument #" + index + " ($" + param + ") must be of type " +
                    type + ", " + ctx.argumentType(value) + " given");
        }
        private B1Array slice(B1RequestContext ctx,Object value,Object offset,Object length,Object preserve) {
            required(ctx,value,1); required(ctx,offset,2);
            B1Array array = array(ctx,value,1,"array","array");
            long start = ctx.integral(offset,"array_slice()",2,"offset");
            long size = length == null || length == OMITTED ? Long.MAX_VALUE : ctx.integral(length,"array_slice()",3,"length","?int");
            if (preserve instanceof B1Array || preserve instanceof B1Object || preserve instanceof B1Closure)
                throw badType(ctx,preserve,4,"bool");
            if (preserve == null) ctx.diagnostic("Deprecated","array_slice(): Passing null to parameter #4 ($preserve_keys) of type bool is deprecated");
            return array.slice(ctx,start,size,preserve != OMITTED && truth(preserve));
        }
        @Override public Object invoke(B1RequestContext ctx, B1Object self, Object[] args) {
            if (name.equals("str_replace") && args.length == 3) {
                byte[] search = ctx.stringBytes(args[0]), replacement = ctx.stringBytes(args[1]), subject = ctx.stringBytes(args[2]);
                var out = new java.io.ByteArrayOutputStream();
                for (int i = 0; i < subject.length;) {
                    if (search.length > 0 && i <= subject.length - search.length &&
                            Arrays.equals(search,0,search.length,subject,i,i + search.length)) {
                        out.writeBytes(replacement); i += search.length;
                    } else out.write(subject[i++]);
                }
                return B1Bytes.of(out.toByteArray());
            }
            if (args.length == 1) return invoke1(ctx, self, args[0]);
            if (args.length == 2) return invoke2(ctx, self, args[0], args[1]);
            if (name.equals("array_slice") && (args.length == 3 || args.length == 4))
                return slice(ctx,args[0],args[1],args[2],args.length == 4 ? args[3] : false);
            throw arityError(ctx,args.length);
        }
    }

    public Object forward(Object value, Object source, Object offsetValue, Object lengthValue,boolean sliced) {
        if (!(source instanceof B1Array args)) throw error("TypeError",(sliced ? "array_slice(): Argument #1 ($array)" :
                "call_user_func_array(): Argument #2 ($args)") + " must be of type array, " + argumentType(source) + " given");
        int start = args.sliceStart(!sliced ? 0 : integral(offsetValue,"array_slice()",2,"offset"));
        int count = args.sliceLength(start,lengthValue == null ? Long.MAX_VALUE : integral(lengthValue,"array_slice()",3,"length","?int"));
        B1Callable target = callable(value);
        B1Object self = receiver(value);
        if (value instanceof B1Closure closure) frame.nextClosure = closure;
        if (!args.packed()) {
            for (int i = start; i < start + count; i++) {
                if (args.keyAt(i) instanceof B1Bytes) return target.named(this,self,args,start,count);
            }
        }
        return target.callSlice(this,self,args,start,count);
    }
    public B1Callable callable(Object value) {
        if (value instanceof B1Closure closure) return closure.body;
        if (value instanceof B1Bytes name) return function(this, name.symbolKey());
        if (value instanceof String name) return function(this, folded(name));
        if (value instanceof B1Array pair && pair.size() == 2 && pair.get(1L) instanceof B1Bytes name) {
            Object receiver = pair.get(0L);
            if (receiver instanceof B1Object obj) return resolve(obj, name.symbolKey(), name.toString());
            if (receiver instanceof B1Bytes cls) {
                B1Callable fn = method(cls.symbolKey(), name.symbolKey());
                if (fn != null && fn.isStatic) return fn;
            }
        }
        throw error("Error", "Value is not callable");
    }
    public B1Object receiver(Object value) {
        return value instanceof B1Array pair && pair.get(0L) instanceof B1Object obj ? obj : null;
    }
    public B1Object allocateDynamic(Object name) {
        String cls = string(name);
        return allocate(name instanceof B1Bytes bytes ? bytes.symbolKey() : folded(cls), cls);
    }
    public Object eval(Object code,B1Object self,int budget) {
        return pl.codeloom.php.b1.B1Compiler.eval(this, stringBytes(code), new pl.codeloom.php.ir.IrSourceSpan(frame.file, frame.line),self,budget);
    }
    public static boolean truth(Object value) {
        if (value instanceof B1Bytes text) return text.truth();
        return value != null && !Boolean.FALSE.equals(value) && !(value instanceof Number n && n.doubleValue() == 0)
                && !"".equals(value) && !"0".equals(value) && !(value instanceof B1Array a && a.size() == 0);
    }
    public B1Array occupiedAppend() { throw error("Error",B1Array.OCCUPIED); }
    B1Array writableArray(Object old) {
        if (old instanceof B1Array a) return a.writable();
        if (old == null) return new B1Array(0);
        if (Boolean.FALSE.equals(old)) {
            diagnostic("Deprecated","Automatic conversion of false to array is deprecated");
            return new B1Array(0);
        }
        if (old instanceof B1Object) throw error("Error","Cannot use object of type " + typeName(old) + " as array");
        if (old instanceof B1Bytes || old instanceof String) throw error("Error","[] operator not supported for strings");
        throw error("Error","Cannot use a scalar value as an array");
    }
    Object arrayKey(Object key) {
        if (key instanceof B1Array || key instanceof B1Object || key instanceof B1Closure)
            throw error("TypeError","Cannot access offset of type " + typeName(key) + " on array");
        if (key instanceof Double d) integer(d);
        return B1Array.key(key);
    }

    public Object dim(Object base, Object key, boolean quiet) {
        if (base instanceof B1Array a) {
            key = arrayKey(key);
            Object v = a.get(key);
            if (v == null && !quiet && !a.contains(key)) diagnostic("Warning", "Undefined array key " +
                    (key instanceof String || key instanceof B1Bytes ? "\"" + key + "\"" : key));
            return v;
        }
        if (base instanceof B1Bytes || base instanceof String) {
            byte[] bytes = B1Bytes.bytes(base);
            Long offset = stringOffset(key,quiet);
            if (offset == null) {
                if (quiet) return null;
                throw error("TypeError","Cannot access offset of type " + typeName(key) + " on string");
            }
            long index = offset < 0 ? bytes.length + offset : offset;
            if (index >= 0 && index < bytes.length) return B1Bytes.of(new byte[]{bytes[(int)index]});
            if (quiet) return null;
            diagnostic("Warning","Uninitialized string offset " + offset);
            return B1Bytes.ofUtf8("");
        }
        if (!quiet) diagnostic("Warning", "Trying to access array offset on " + typeName(base));
        return null;
    }
    Long stringOffset(Object key,boolean quiet) {
        if (key instanceof Long index) return index;
        if (key instanceof B1Bytes || key instanceof String) {
            byte[] bytes = B1Bytes.bytes(key);
            if (pl.codeloom.php.ir.PhpNumericString.parse(bytes,false) instanceof Long index) return index;
            if (!quiet && pl.codeloom.php.ir.PhpNumericString.parse(bytes,true) instanceof Long index) {
                diagnostic("Warning","Illegal string offset \"" + key + "\""); return index;
            }
            return null;
        }
        if (key == null || key instanceof Boolean || key instanceof Double) {
            if (!quiet) diagnostic("Warning","String offset cast occurred");
            if (key instanceof Double d) return quiet ? integer(d) : (long)d.doubleValue();
            return Boolean.TRUE.equals(key) ? 1L : 0L;
        }
        return null;
    }
    byte[] stringBytes(Object value) {
        if (!(value instanceof B1Object obj)) return B1Bytes.bytes(value instanceof B1Bytes ? value : string(value));
        Object result = convertObject(obj);
        try { return B1Bytes.bytes(result instanceof B1Bytes ? result : string(result)); }
        finally { B1Object.release(this,result); }
    }
    private static boolean identical(Object a, Object b) {
        if (a == b) return true;
        if (a instanceof String && b instanceof B1Bytes || a instanceof B1Bytes && b instanceof String)
            return Arrays.equals(B1Bytes.bytes(a),B1Bytes.bytes(b));
        if (a instanceof B1Array x && b instanceof B1Array y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                if (!Objects.equals(x.keyAt(i),y.keyAt(i)) || !identical(x.valueAt(i),y.valueAt(i))) return false;
            }
            return true;
        }
        return Objects.equals(a,b);
    }

    public Object compare(Object a, Object b, int opcode) {
        var op = OPERATORS[opcode];
        if (op == pl.codeloom.php.ir.IrUnit.Operator.IDENTICAL || op == pl.codeloom.php.ir.IrUnit.Operator.NOT_IDENTICAL)
            return identical(a,b) == (op == pl.codeloom.php.ir.IrUnit.Operator.IDENTICAL);
        int cmp = order(a,b);
        return switch (op) {
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            default -> throw new IllegalArgumentException(op.name());
        };
    }

    private int order(Object a, Object b) {
        if (a == b) return 0;
        if (a instanceof Boolean || b instanceof Boolean) return Boolean.compare(truth(a),truth(b));
        if (a == null || b == null) {
            Object other = a == null ? b : a;
            int cmp = other instanceof String || other instanceof B1Bytes ? Integer.signum(stringBytes(other).length)
                    : other instanceof Number n ? Double.compare(n.doubleValue(),0) : Boolean.compare(truth(other),false);
            return a == null ? -cmp : cmp;
        }
        if (a instanceof B1Array x && b instanceof B1Array y) {
            if (x.size() != y.size()) return Integer.compare(x.size(),y.size());
            for (int i = 0; i < x.size(); i++) {
                Object key = x.keyAt(i);
                if (!y.contains(key)) return 1;
                int cmp = order(x.valueAt(i),y.get(key));
                if (cmp != 0) return cmp;
            }
            return 0;
        }
        if (a instanceof B1Array) return 1;
        if (b instanceof B1Array) return -1;
        Number x = number(a,false), y = number(b,false);
        if (x instanceof Long && y instanceof Long) return Long.compare(x.longValue(),y.longValue());
        if (x != null && y != null) return Double.compare(x.doubleValue(),y.doubleValue());
        return Arrays.compareUnsigned(stringBytes(a),stringBytes(b));
    }

    private static final class ExceptionMethod extends B1Callable {
        private final String name;
        ExceptionMethod(String name) {
            super(new boolean[name.equals("__construct") ? 3 : 0],new String[]{"message","code","previous"});
            this.name = name;
        }
        @Override public Object invoke0(B1RequestContext ctx,B1Object self) { return execute(ctx,self,0,null,null,null); }
        @Override public Object invoke1(B1RequestContext ctx,B1Object self,Object a) { return execute(ctx,self,1,a,null,null); }
        @Override public Object invoke2(B1RequestContext ctx,B1Object self,Object a,Object b) { return execute(ctx,self,2,a,b,null); }
        @Override public Object invoke(B1RequestContext ctx,B1Object self,Object[] args) {
            return execute(ctx,self,args.length,args.length > 0 ? args[0] : null,args.length > 1 ? args[1] : null,
                    args.length > 2 ? args[2] : null);
        }
        @Override Object sparse(B1RequestContext ctx,B1Object self,ShortNamed args) {
            return execute(ctx,self,args.count(),args.at(0),args.at(1),args.at(2));
        }
        private Object execute(B1RequestContext ctx,B1Object self,int count,Object message,Object code,Object previous) {
            String owner = self.type.equals("exception") ? "Exception" : "Error";
            if (name.equals("__construct")) {
                if (count > 3) throw ctx.error("ArgumentCountError",owner + "::__construct() expects at most 3 arguments, " + count + " given");
                if (previous != null && !(previous instanceof B1Object obj && B1Thrown.throwable(obj.type)))
                    throw ctx.error("TypeError",owner + "::__construct(): Argument #3 ($previous) must be of type ?Throwable, "
                            + ctx.typeName(previous) + " given");
                self.write(ctx,"message",message == null || message == OMITTED ? "" : ctx.string(message));
                if (count > 1 && code != OMITTED) self.write(ctx,"code",code);
                if (count > 2) self.write(ctx,"previous",previous);
                return null;
            }
            if (count != 0) throw ctx.error("ArgumentCountError",owner + "::" + name
                    + "() expects exactly 0 arguments, " + count + " given");
            if (name.equals("getTraceAsString")) return B1Exception.traceString(ctx,(B1Array)self.read("trace"));
            return B1Object.retain(self.read(pl.codeloom.php.ir.AsciiCase.lower(name.substring(3))));
        }
    }

    public boolean hasFunction(String lower) { return functions.containsKey(lower); }
    public boolean hasClass(String lower) { return classes.containsKey(lower); }
    public void declareClass(B1Callable entry, int id, String lowerName) {
        ClassDef cls = entry.statementClasses.get(id);
        ClassDef previous = classes.putIfAbsent(lowerName, cls);
        if (previous != null) throw redeclared("class " + cls.name(), previous.declaration(), cls.declaration());
    }

    /** Publish a fully linked unit to this request. PHP symbol keys have already been ASCII-folded. */
    public void install(String path, B1Callable entry, Map<String, B1Callable> declarations,
                        Map<String, ClassDef> types) {
        for (String key : declarations.keySet()) {
            if (functions.containsKey(key)) {
                B1Callable old = functions.get(key), next = declarations.get(key);
                throw redeclared("function " + next.display + "()",
                        old.declaredFile == null ? null : new pl.codeloom.php.ir.IrSourceSpan(old.declaredFile,old.declaredLine),
                        new pl.codeloom.php.ir.IrSourceSpan(path,next.declaredLine));
            }
        }
        classes.putAll(types);
        functions.putAll(declarations);
        files.put(path, entry);
    }
    private pl.codeloom.php.ir.PhpCompileFatal redeclared(String name,pl.codeloom.php.ir.IrSourceSpan previous,
                                                           pl.codeloom.php.ir.IrSourceSpan at) {
        B1Array trace = B1Exception.trace(this,frame);
        try {
            String message = "Cannot redeclare " + name + (previous == null ? "" : " (previously declared in " +
                    previous.file() + ":" + previous.line() + ")");
            return new pl.codeloom.php.ir.PhpCompileFatal(message,at,B1Exception.traceString(this,trace));
        } finally { trace.release(this); }
    }
    public B1Callable file(String path) { return files.get(path); }
    @HotPath(lookup = true)
    public static B1Callable function(B1RequestContext ctx, String lower) {
        var fn = ctx.functions.get(lower);
        if (fn == null) throw ctx.error("Error","Call to undefined function " + lower + "()");
        return fn;
    }
    @HotPath(lookup = true)
    private static ClassDef findClass(B1RequestContext ctx, String lower) { return ctx.classes.get(lower); }
    B1Callable method(String lowerClass,String lowerMethod) {
        ClassDef def = findClass(this,lowerClass);
        return def == null ? null : def.method(this,lowerMethod);
    }
    public B1Callable resolve(B1Object recv, String lowerName, String name) {
        var target = recv.method(this,lowerName);
        if (target == null) throw error("Error", "Call to undefined method " + typeName(recv) + "::" + name + "()");
        return target;
    }
    public B1Callable constructor(B1Object recv) { return recv.method(this,"__construct"); }

    public B1Object allocate(String lower, String name) {
        var cls = findClass(this, lower);
        if (cls == null) throw error("Error", "Class \"" + name + "\" not found");
        var obj = new B1Object(acquireObjectId(), lower, cls);
        for (var entry : cls.defaults.entrySet()) obj.initialize(this,entry.getKey(),entry.getValue());
        if (cls.methods.containsKey("__destruct")) destructibles.add(obj);
        if (B1Thrown.throwable(lower)) {
            obj.write(this, "line", frame == null ? 0L : (long) frame.line);
            obj.write(this, "file", frame == null ? "" : frame.file);
            obj.write(this, "code", 0L);
            B1Array trace = B1Exception.trace(this,frame);
            obj.write(this, "trace",trace);
            B1Object.release(this,trace);
        }
        return obj;
    }
    void forget(B1Object obj) { destructibles.remove(obj); }
    public int liveDestructibleCount() { return destructibles.size(); }

    public B1Frame enter(B1Callable fn, String file, String name, int temps, boolean top) {
        if (top && !fn.evalBody) for (String var : fn.locals) globals.putIfAbsent(var, null);
        frame = new B1Frame(frame, file, fn.evalBody ? "eval" : name, fn.locals, temps, top, fn.evalBody);
        frame.staticCall = fn.isStatic;
        frame.scope = fn.evalBody && frame.caller != null ? frame.caller.scope : fn.scope;
        if (frame.caller != null) {
            frame.closure = frame.caller.nextClosure;
            frame.caller.nextClosure = null;
            if (fn.evalBody) frame.attachEval(this, frame.caller);
        }
        return frame;
    }
    void pop(B1Frame f) {
        if (frame != f) throw new IllegalStateException("unbalanced PHP frame");
        frame = f.caller;
    }
    B1Ref removeGlobal(String name) { return globals.remove(name); }
    B1Ref global(String name) { return globals.computeIfAbsent(name, unused -> new B1Ref()); }
    public B1Ref findGlobal(String name) { return globals.get(name); }

    String scope() { return frame == null ? null : frame.scope; }

    public void echo(Object value) {
        try { output.write(stringBytes(value)); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    public B1Thrown compileError(String type,byte[] message,String file,int line) {
        B1Thrown result = error(type,"");
        result.exception.write(this,"message",B1Bytes.of(message));
        result.exception.write(this,"file",file);
        result.exception.write(this,"line",(long)line);
        return result;
    }
    B1Thrown error(String type, String message) {
        var obj = allocate(pl.codeloom.php.ir.AsciiCase.lower(type), type);
        obj.constructing = false;
        obj.write(this, "message", message);
        var thrown = new B1Thrown(obj);
        B1Object.release(this, obj);
        return thrown;
    }

    String argumentType(Object value) { return value instanceof Boolean b ? b.toString() : typeName(value); }
    String typeName(Object value) {
        if (value instanceof B1Closure) return "Closure";
        if (value instanceof B1Object obj) return findClass(this, obj.type).name();
        if (value instanceof Map || value instanceof B1Array) return "array";
        if (value instanceof Long) return "int";
        if (value instanceof Double) return "float";
        if (value instanceof String || value instanceof B1Bytes) return "string";
        return value == null ? "null" : "bool";
    }

    void diagnostic(String level, String message) {
        echo("\n" + level + ": " + message + " in " + frame.file + " on line " + frame.line + "\n");
    }

    public static Object quietProperty(Object receiver,B1RequestContext ctx,String name) {
        return receiver instanceof B1Object obj && obj.has(name) && obj.accessible(ctx,name) ? obj.read(name) : null;
    }
    public static Object readProperty(Object receiver, B1RequestContext ctx, String name) {
        if (receiver instanceof B1Object obj) {
            if (obj.has(name)) return obj.property(ctx,name).get();
            ctx.diagnostic("Warning", "Undefined property: " + ctx.typeName(obj) + "::$" + name);
        } else ctx.diagnostic("Warning", "Attempt to read property \"" + name + "\" on " + ctx.typeName(receiver));
        return null;
    }

    private Object convertObject(B1Object obj) {
        var convert = obj.method(this,"__tostring");
        if (convert == null) throw error("Error", "Object of class " + typeName(obj) + " could not be converted to string");
        Object result = convert.invoke0(this,obj);
        if (result instanceof String || result instanceof B1Bytes || result instanceof Number || result instanceof Boolean) return result;
        try { throw error("TypeError", typeName(obj) + "::__toString(): Return value must be of type string, "
                + typeName(result) + " returned"); }
        finally { B1Object.release(this,result); }
    }
    public String string(Object value) {
        if (value instanceof B1Object obj) {
            Object result = convertObject(obj);
            try { return string(result); }
            finally { B1Object.release(this,result); }
        }
        if (value instanceof Map || value instanceof B1Array) { diagnostic("Warning", "Array to string conversion"); return "Array"; }
        if (value instanceof Boolean flag) return flag ? "1" : "";
        if (value instanceof Double d) return pl.codeloom.php.ir.PhpFloatFormat.formatDouble(d, 14);
        return value == null ? "" : value.toString();
    }

    public Object concat(Object left, Object right) {
        return B1Bytes.concat(stringBytes(left),stringBytes(right));
    }

    private Number number(Object value, boolean leading) {
        if (value instanceof Number n) return n;
        if (value instanceof Boolean flag) return flag ? 1L : 0L;
        if (value == null) return leading ? 0L : null;
        if (!(value instanceof String || value instanceof B1Bytes)) return null;
        byte[] bytes = B1Bytes.bytes(value);
        Number number = pl.codeloom.php.ir.PhpNumericString.parse(bytes, false);
        if (number == null && leading) {
            number = pl.codeloom.php.ir.PhpNumericString.parse(bytes, true);
            if (number != null) diagnostic("Warning", "A non-numeric value encountered");
        }
        return number;
    }

    private long integral(Object value,String function,int index,String name) {
        return integral(value,function,index,name,"int");
    }
    private long integral(Object value, String function, int index, String name, String type) {
        if (value == null) {
            diagnostic("Deprecated",function + ": Passing null to parameter #" + index + " ($" + name + ") of type int is deprecated");
            return 0;
        }
        Long n = integer(value);
        if (n == null) throw error("TypeError",function + ": Argument #" + index + " ($" + name +
                ") must be of type " + type + ", " + argumentType(value) + " given");
        return n;
    }

    Long integer(Object value) {
        Number n = number(value, false);
        if (n instanceof Long l) return l;
        if (n == null || !(n.doubleValue() >= -0x1p63 && n.doubleValue() < 0x1p63)) return null;
        long l = n.longValue();
        if ((double)l != n.doubleValue()) diagnostic("Deprecated", "Implicit conversion from "
                + (value instanceof String || value instanceof B1Bytes ? "float-string \"" + value + "\"" : "float " + value)
                + " to int loses precision");
        return l;
    }

    public Object castLong(Object value) {
        Number n = number(value,true);
        return n == null ? 0L : n.longValue();
    }
    public Object mod(Object a,Object b) {
        long divisor = (long)castLong(b);
        if (divisor == 0) throw error("Error","Modulo by zero");
        return (long)castLong(a) % divisor;
    }
    public Object add(Object left, Object right) {
        Number a = number(left, true), b = number(right, true);
        if (a == null || b == null) throw error("TypeError", "Unsupported operand types: " + typeName(left) + " + " + typeName(right));
        if (a instanceof Long && b instanceof Long) {
            try { return Math.addExact(a.longValue(), b.longValue()); } catch (ArithmeticException overflow) { }
        }
        return a.doubleValue() + b.doubleValue();
    }

    private void handleUncaught(B1Thrown thrown) {
        String installed = exceptionHandler;
        if (installed == null) throw thrown;
        exceptionHandler = null;
        try {
            var handler = function(this, pl.codeloom.php.ir.AsciiCase.lower(installed));
            B1Object.release(this, handler.invoke1(this, null, thrown.exception));
            if (exceptionHandler == null) exceptionHandler = installed;
        } finally { B1Object.release(this, thrown.exception); }
    }

    public void runScript(B1Callable entry) {
        try { B1Object.release(this, entry.invoke0(this, null)); }
        catch (B1Thrown thrown) { handleUncaught(thrown); }
        finally { shutdown(); }
    }

    private void shutdownStep(Runnable release) {
        try { release.run(); }
        catch (B1Thrown thrown) { handleUncaught(thrown); }
    }

    public void shutdown() {
        Throwable pending = null;
        try {
            pl.codeloom.php.ir.D17Shutdown.destruct(globals,
                    slot -> !slot.reference() && slot.get() instanceof B1Object obj && obj.uniquelyHeld(),
                    key -> shutdownStep(() -> globals.remove(key).unbind(this)), destructibles, obj -> shutdownStep(() -> obj.destruct(this)));
        } catch (Throwable ex) { abortDestructors = true; pending = ex; }
        while (!globals.isEmpty()) {
            var slot = globals.pollLastEntry().getValue();
            if (slot == null) continue;
            try { slot.unbind(this); } catch (Throwable ex) { pending = B1Thrown.chain(this, ex, pending); }
        }
        while (!destructibles.isEmpty()) {
            try { destructibles.getFirst().destruct(this); } catch (Throwable ex) { pending = B1Thrown.chain(this, ex, pending); }
        }
        if (frame != null || liveDestructibleCount() != 0) throw new IllegalStateException("incomplete PHP shutdown");
        B1Thrown.propagate(pending);
    }
}
