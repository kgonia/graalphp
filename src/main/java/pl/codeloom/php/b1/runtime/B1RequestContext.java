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
    private final boolean displayErrors;
    private final Map<String, B1Callable> functions = new HashMap<>();
    private final Map<String, B1Callable> files = new HashMap<>();
    private final Map<String, ClassDef> classes = new HashMap<>();
    private LinkedHashMap<String, B1Ref> globals = new LinkedHashMap<>();
    final B1CycleCollector cycles = new B1CycleCollector();
    public int gcRootCount() { return cycles.rootCount(); }
    public long collectCycles() { return cycles.collect(this); }

    private Map<String,B1Ref> staticProperties = new LinkedHashMap<>();
    private boolean abortDestructors;
    boolean destructorsAborted() { return abortDestructors; }
    private final LinkedHashSet<B1Object> destructibles = new LinkedHashSet<>();
    private final ArrayDeque<Long> freeObjectIds = new ArrayDeque<>();
    private long nextObjectId = 1;
    long acquireObjectId() { return freeObjectIds.isEmpty() ? nextObjectId++ : freeObjectIds.pop(); }
    void releaseObjectId(long id) { freeObjectIds.push(id); }
    private B1Frame frame;
    final ClassDef generatorClass;
    B1Fiber currentFiber;
    private final ArrayDeque<B1FiberThread> idleFibers = new ArrayDeque<>();
    private final ArrayList<B1FiberThread> fiberThreads = new ArrayList<>();

    B1FiberThread acquireFiberThread() {
        if (!idleFibers.isEmpty()) return idleFibers.pop();
        var worker = new B1FiberThread();
        fiberThreads.add(worker);
        return worker;
    }
    void recycle(B1FiberThread worker) { idleFibers.push(worker); }
    public int fiberThreadCount() { return fiberThreads.size(); }

    B1Frame swapFrame(B1Frame next) { B1Frame previous = frame; frame = next; return previous; }
    void attach(B1Frame resumed) { resumed.caller = frame; frame = resumed; }
    <T extends B1Object> T track(T object) { destructibles.add(object); return object; }

    private String exceptionHandler;

    public record Property(String type, boolean isStatic, boolean initialized, Object initial) {}
    public record ClassDef(String name, List<String> props, Map<String, B1Callable> methods, Map<String,Integer> visibility, Map<String,Property> properties, pl.codeloom.php.ir.IrSourceSpan declaration,
                           Set<String> interfaces, boolean allowDynamicProperties) {
        public ClassDef(String name, List<String> props, Map<String, B1Callable> methods) {
            this(name, props, methods, Map.of(),Map.of(),null);
        }
        public ClassDef(String name, List<String> props, Map<String, B1Callable> methods, Map<String,Integer> visibility,
                        Map<String,Property> properties, pl.codeloom.php.ir.IrSourceSpan declaration) {
            this(name, props, methods, visibility, properties, declaration, Set.of(), false);
        }
        public ClassDef {
            props = List.copyOf(props);
            methods = Map.copyOf(methods);
            visibility = Map.copyOf(visibility);
            properties = Map.copyOf(properties);
            interfaces = Set.copyOf(interfaces);
        }
        boolean magicAccess() { return methods.containsKey("__get") || methods.containsKey("__isset"); }
        @HotPath(lookup = true)
        B1Callable method(B1RequestContext ctx,String lower) { return methods.get(lower); }
    }

    public B1RequestContext(OutputStream output) { this(output,true); }
    public B1RequestContext(OutputStream output,boolean displayErrors) {
        this.displayErrors = displayErrors;
        this.output = output;
        var methods = new HashMap<String, B1Callable>();
        for (String method : List.of("__construct", "getMessage", "getLine", "getPrevious", "getFile", "getCode", "getTrace", "getTraceAsString", "__toString")) {
            methods.put(pl.codeloom.php.ir.AsciiCase.lower(method), new ExceptionMethod(method));
        }
        for (String type : List.of("Exception", "Error", "TypeError", "ArgumentCountError", "ArithmeticError", "DivisionByZeroError", "ValueError", "ParseError", "CompileError", "FiberError", "ClosedGeneratorException")) {
            classes.put(pl.codeloom.php.ir.AsciiCase.lower(type), new ClassDef(type,
                    List.of("message", "file", "line", "code", "trace", "previous"), methods));
        }
        classes.put("stdclass", new ClassDef("stdClass", List.of(), Map.of()));
        generatorClass = B1CoroutineMethod.definition("Generator","current","key","valid","rewind","next","send","throw","getreturn");
        classes.put("generator",generatorClass);
        classes.put("fiber",B1CoroutineMethod.definition("Fiber","__construct","start","resume","throw","getreturn","isstarted",
                "issuspended","isrunning","isterminated","suspend","getcurrent"));
        builtin("gc_collect_cycles",0);
        builtin("var_dump",1,"value","values");
        builtin("array_map",2,"callback","array","arrays");
        builtin("implode",1,"separator","array");
        builtin("strtolower",1,"string");
        builtin("intval",1,"value","base");
        for (String fn : List.of("is_callable","is_numeric")) builtin(fn,1,"value");
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
        builtin("key",1,"array");
        builtin("reset",1,"array");
        builtin("is_null",1,"value");
        builtin("array_fill",3,"start_index","count","value");
        builtin("sqrt",1,"num");
        builtin("max",1,"value","values");
        builtin("min",1,"value","values");
        builtin("sizeof",1,"value","mode");
        builtin("sprintf",1,"format","values");
        builtin("define",2,"constant_name","value","case_insensitive");
        builtin("defined",1,"constant_name");
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
            if (count == 0) return refs;
            refs[0] = name.equals("ksort") || name.equals("array_pop") || name.equals("array_unshift") || name.equals("next") || name.equals("reset");
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
        @Override public Object invoke0(B1RequestContext ctx,B1Object self) {
            if (name.equals("gc_collect_cycles")) return ctx.collectCycles();
            throw arityError(ctx,0);
        }
        @Override public Object invoke1(B1RequestContext ctx, B1Object self, Object a) {
            return switch (name) {
                case "is_callable" -> ctx.isCallable(a);
                case "is_numeric" -> a instanceof Number || (a instanceof B1Bytes || a instanceof String) && ctx.number(a,false) != null;
                case "var_dump" -> { new B1Dump(ctx).value(a,0); yield null; }
                case "intval" -> ctx.castLong(a);
                case "implode" -> implode(ctx,a,null);
                case "strtolower" -> {
                    byte[] bytes = stringParam(ctx,a).clone();
                    for (int i = 0; i < bytes.length; i++) if (bytes[i] >= 'A' && bytes[i] <= 'Z') bytes[i] += 32;
                    yield B1Bytes.of(bytes);
                }
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
                case "key" -> array(ctx,a,1,"array","array").currentKey();
                case "reset" -> B1Object.retain(((B1Ref)a).writable(ctx).rewind());
                case "is_null" -> a == null;
                case "defined" -> ctx.defined(B1Bytes.of(stringParam(ctx,a)).toString());
                case "sqrt" -> Math.sqrt(floatParam(ctx,a,1,"num"));
                case "max", "min" -> extreme(ctx,a,OMITTED,null);
                case "sizeof" -> (long)array(ctx,a,1,"value","Countable|array").size();
                case "sprintf" -> sprintf(ctx,a,null,0);
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
            return stringParam(ctx,a,"string","string");
        }
        private byte[] stringParam(B1RequestContext ctx,Object a,String declaredType,String errorType) {
            if (ctx.strict() && !(a instanceof B1Bytes || a instanceof String)) throw badType(ctx,a,1,errorType);
            if (a instanceof B1Array || a instanceof B1Closure || a instanceof B1Object obj && obj.method(ctx,"__tostring") == null)
                throw badType(ctx,a,1,errorType);
            if (a == null) ctx.diagnostic("Deprecated",name + "(): Passing null to parameter #1 ($" + locals[0] + ") of type " + declaredType + " is deprecated");
            return ctx.stringBytes(a);
        }
        private static B1Bytes join(B1RequestContext ctx,byte[] sep,B1Array array) {
            var out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) out.writeBytes(sep);
                out.writeBytes(ctx.stringBytes(array.valueAt(i)));
            }
            return B1Bytes.of(out.toByteArray());
        }
        // Zend's frameless handlers have their own validation order for direct calls.
        @Override public Object invokeFrameless1(B1RequestContext ctx,B1Object self,Object a) {
            return implodeArray(ctx,null,a,null);
        }
        @Override public Object invokeFrameless2(B1RequestContext ctx,B1Object self,Object a,Object b) {
            if (a instanceof B1Array) throw badType(ctx,a,1,"string");
            return implodeArray(ctx,stringParam(ctx,a,"array|string","string"),a,b);
        }

        private Object implode(B1RequestContext ctx,Object first,Object second) {
            byte[] separator = first instanceof B1Array ? null : stringParam(ctx,first,"array|string","array|string");
            return implodeArray(ctx,separator,first,second);
        }

        private Object implodeArray(B1RequestContext ctx,byte[] separator,Object first,Object second) {
            if (second == null) {
                if (first instanceof B1Array pieces) return join(ctx,new byte[0],pieces);
                throw ctx.error("TypeError","implode(): If argument #1 ($separator) is of type string, argument #2 ($array) must be of type array, null given");
            }
            B1Array pieces = array(ctx,second,2,"array","?array");
            if (separator == null) throw badType(ctx,first,1,"string");
            return join(ctx,separator,pieces);
        }

        private B1Thrown invalidCallback(B1RequestContext ctx,String reason) {
            return ctx.error("TypeError","array_map(): Argument #1 ($callback) must be a valid callback or null, " + reason);
        }
        private B1Callable mapCallback(B1RequestContext ctx,Object callback) {
            if (callback == null) return null;
            if (ctx.isCallable(callback)) return ctx.callable(callback);
            Object target, method;
            if (callback instanceof String || callback instanceof B1Bytes) {
                String fn = callback.toString();
                int separator = fn.indexOf("::");
                if (separator < 0) throw invalidCallback(ctx,"function \"" + fn + "\" not found or invalid function name");
                target = fn.substring(0,separator);
                method = fn.substring(separator+2);
            } else if (callback instanceof B1Array pair) {
                if (pair.size() != 2) throw invalidCallback(ctx,"array callback must have exactly two members");
                if (!pair.contains(0L) || !pair.contains(1L)) throw invalidCallback(ctx,"array callback has to contain indices 0 and 1");
                target = pair.get(0L);
                method = pair.get(1L);
            } else throw invalidCallback(ctx,"no array or string given");
            if (!(target instanceof B1Object || target instanceof B1Bytes || target instanceof String))
                throw invalidCallback(ctx,"first array member is not a valid class name or object");
            if (!(method instanceof B1Bytes || method instanceof String))
                throw invalidCallback(ctx,"second array member is not a valid method");
            String owner = target instanceof B1Object obj ? ctx.typeName(obj) : target.toString();
            ClassDef cls = findClass(ctx,ctx.folded(owner));
            if (cls == null) throw invalidCallback(ctx,"class \"" + owner + "\" not found");
            var fn = cls.method(ctx,ctx.folded(method.toString()));
            if (fn == null) throw invalidCallback(ctx,"class " + cls.name() + " does not have a method \"" + method + "\"");
            if (!ctx.callableFromScope(fn))
                throw invalidCallback(ctx,"cannot access " + (fn.visibility == 2 ? "protected" : "private") + " method " + fn.display + "()");
            if (!fn.isStatic && !(target instanceof B1Object)) throw invalidCallback(ctx,"non-static method " + fn.display + "() cannot be called statically");
            return fn;
        }

        private B1Array map(B1RequestContext ctx,Object callback,Object first,Object[] args) {
            var fn = mapCallback(ctx,callback);
            var input = new B1Array[args == null ? 1 : args.length-1];
            input[0] = array(ctx,first,2,"array","array");
            int length = input[0].size();
            for (int i = 1; i < input.length; i++) {
                if (!(args[i+1] instanceof B1Array extra)) throw ctx.error("TypeError","array_map(): Argument #"
                        + (i+2) + " must be of type array, " + ctx.argumentType(args[i+1]) + " given");
                input[i] = extra;
                length = Math.max(length,extra.size());
            }
            if (fn == null && input.length == 1) return (B1Array)B1Object.retain(input[0]);
            for (var array : input) array.retain();
            B1Object.retain(callback);
            var out = new B1Array(length);
            Throwable pending = null;
            try {
                for (int i = 0; i < length; i++) {
                    Object value = mapRow(ctx,callback,fn,input,i);
                    try {
                        if (input.length == 1) out.put(ctx,input[0].keyAt(i),value);
                        else out.append(ctx,value);
                    } finally { B1Object.release(ctx,value); }
                }
            } catch (Throwable ex) { pending = ex; }
            for (var array : input) {
                try { array.release(ctx); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
            }
            try { B1Object.release(ctx,callback); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
            if (pending != null) {
                try { out.release(ctx); } catch (Throwable ex) { pending = B1Thrown.chain(ctx,ex,pending); }
                B1Thrown.propagate(pending);
            }
            return out;
        }

        private Object mapRow(B1RequestContext ctx,Object callback,B1Callable fn,B1Array[] input,int row) {
            if (fn == null) {
                var tuple = new B1Array(input.length);
                for (var array : input) tuple.append(ctx,row < array.size() ? array.rawAt(row) : null);
                return tuple;
            }
            B1Object receiver = ctx.receiver(callback);
            B1Frame caller = ctx.frame;
            boolean previous = caller.internalCall;
            caller.internalCall = true;
            try {
                if (callback instanceof B1Closure closure) caller.nextClosure = closure;
                if (input.length == 1) return fn.invoke1(ctx,receiver,fn.forwarded(ctx,input[0],row,0));
                if (input.length == 2) return fn.invoke2(ctx,receiver,
                        row < input[0].size() ? fn.forwarded(ctx,input[0],row,0) : null,
                        row < input[1].size() ? fn.forwarded(ctx,input[1],row,1) : null);
                Object[] values = new Object[input.length];
                for (int i = 0; i < input.length; i++) {
                    if (row < input[i].size()) values[i] = fn.forwarded(ctx,input[i],row,i);
                }
                return fn.invoke(ctx,receiver,values);
            } finally { caller.internalCall = previous; }
        }
        @Override public Object invoke2(B1RequestContext ctx, B1Object self, Object a, Object b) {
            required(ctx,a,1);
            if (name.equals("var_dump")) {
                var dump = new B1Dump(ctx);
                dump.value(a,0);
                dump.value(b,0);
                return null;
            }
            if (name.equals("array_map")) return map(ctx,a,b,null);
            if (name.equals("implode")) return implode(ctx,a,b);
            if (name.equals("intval")) {
                long base = ctx.integral(b,"intval()",2,"base");
                if (base != 10 && (a instanceof String || a instanceof B1Bytes))
                    return pl.codeloom.php.ir.PhpNumericString.parseBase(B1Bytes.bytes(a),(int)base);
                return ctx.castLong(a);
            }
            if (name.equals("ksort")) { long flags = ctx.integral(b,"ksort()",2,"flags"); ((B1Ref)a).writable(ctx).sortKeys(ctx,flags); return true; }
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
            if (name.equals("max") || name.equals("min")) return extreme(ctx,a,b,null);
            if (name.equals("define")) return define(ctx,a,b,OMITTED);
            if (name.equals("sprintf")) return sprintf(ctx,a,b,1);
            if (name.equals("count") || name.equals("sizeof")) {
                B1Array array = array(ctx,a,1,"value","Countable|array");
                long mode = ctx.integral(b,name + "()",2,"mode");
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
        private double floatParam(B1RequestContext ctx,Object value,int index,String param) {
            if (value instanceof Double d) return d;
            if (value instanceof Long n) return n;
            Object coerced = ctx.strict() ? null : value instanceof Boolean flag ? (flag ? 1.0 : 0.0)
                    : value instanceof B1Bytes || value instanceof String ? pl.codeloom.php.ir.PhpNumericString.parse(B1Bytes.bytes(value),false) : null;
            if (value == null && !ctx.strict()) {
                ctx.diagnostic("Deprecated",name + "(): Passing null to parameter #" + index + " ($" + param + ") of type float is deprecated");
                return 0;
            }
            if (coerced instanceof Number n) return n.doubleValue();
            throw badType(ctx,value,index,"float");
        }
        /** max()/min(): zend_compare keeps the first of equal candidates. A second argument (null included)
         * compares the arguments; only a single argument is the array of candidates. */
        private Object extreme(B1RequestContext ctx,Object first,Object second,Object[] all) {
            boolean max = name.equals("max");
            Object best;
            if (all == null && second == OMITTED) {
                if (!(first instanceof B1Array values)) throw badType(ctx,first,1,"array");
                if (values.size() == 0) throw ctx.error("ValueError",name + "(): Argument #1 ($value) must contain at least one element");
                best = values.valueAt(0);
                for (int i = 1; i < values.size(); i++) {
                    int cmp = ctx.order(values.valueAt(i),best);
                    if (max ? cmp > 0 : cmp < 0) best = values.valueAt(i);
                }
            } else if (all == null) {
                // PHP 8.5's two-argument path: min() returns the right operand of equal ones, max() the left.
                best = max ? (ctx.order(second,first) > 0 ? second : first) : (ctx.order(first,second) < 0 ? first : second);
            } else {
                best = all[0];
                for (int i = 1; i < all.length; i++) {
                    int cmp = ctx.order(all[i],best);
                    if (max ? cmp > 0 : cmp < 0) best = all[i];
                }
            }
            return B1Object.retain(best);
        }
        private B1Array fill(B1RequestContext ctx,Object start,Object count,Object value) {
            long first = ctx.integral(start,"array_fill()",1,"start_index");
            long n = ctx.integral(count,"array_fill()",2,"count");
            if (n < 0) throw ctx.error("ValueError","array_fill(): Argument #2 ($count) must be greater than or equal to 0");
            if (n >= Integer.MAX_VALUE) throw ctx.error("ValueError","array_fill(): Argument #2 ($count) is too large");
            // PHP 8 numbers the keys on from start_index, negative or not; past PHP_INT_MAX the next key is occupied.
            if (n > 0 && first > Long.MAX_VALUE - (n - 1)) throw ctx.error("Error",B1Array.OCCUPIED);
            B1Array result = new B1Array((int)n);
            for (long i = 0; i < n; i++) {
                if (first == 0) result.append(ctx,value);
                else result.put(ctx,i == 0 ? first : first + i,value);
            }
            return result;
        }
        /** define(string $constant_name, mixed $value, bool $case_insensitive = false): parameters, then the body. */
        private Object define(B1RequestContext ctx,Object name,Object value,Object caseInsensitive) {
            String constant = B1Bytes.of(stringParam(ctx,name)).toString();
            boolean ci = caseInsensitive != OMITTED && boolParam(ctx,caseInsensitive,3);
            return ctx.define(constant,value,ci);
        }
        private boolean boolParam(B1RequestContext ctx,Object value,int index) {
            if (value instanceof Boolean flag) return flag;
            if (ctx.strict() || value instanceof B1Array || value instanceof B1Object || value instanceof B1Closure)
                throw badType(ctx,value,index,"bool");
            if (value == null) {
                ctx.diagnostic("Deprecated",name + "(): Passing null to parameter #" + index + " ($" + locals[index-1] + ") of type bool is deprecated");
                return false;
            }
            return truth(value);
        }
        /** sprintf with no or one value: the arity-specialized B1Sprintf entries allocate no argument array (B1-06). */
        private B1Bytes sprintf(B1RequestContext ctx,Object format,Object value,int count) {
            if (format == OMITTED) throw arityError(ctx,0);
            byte[] pattern = stringParam(ctx,format);
            return B1Bytes.of(count == 0 ? B1Sprintf.format(ctx,pattern) : B1Sprintf.format(ctx,pattern,value));
        }
        private B1Bytes format(B1RequestContext ctx,Object format,Object[] values) {
            if (format == OMITTED) throw arityError(ctx,0);
            return B1Bytes.of(B1Sprintf.format(ctx,stringParam(ctx,format),values));
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
            if (name.equals("var_dump") && args.length > 0) {
                var dump = new B1Dump(ctx);
                for (var value : args) dump.value(value,0);
                return null;
            }
            if (name.equals("array_map") && args.length > 2) return map(ctx,args[0],args[1],args);
            if (name.equals("array_fill") && args.length == 3) return fill(ctx,args[0],args[1],args[2]);
            if (name.equals("define") && args.length == 3) { required(ctx,args[0],1); required(ctx,args[1],2); return define(ctx,args[0],args[1],args[2]); }
            if ((name.equals("max") || name.equals("min")) && args.length > 2) return extreme(ctx,args[0],args[1],args);
            if (name.equals("sprintf") && args.length > 2) return format(ctx,args[0],java.util.Arrays.copyOfRange(args,1,args.length));
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
    /** The B1 runtime stops a request which reaches PHP behaviour it does not implement, instead of deviating. */
    public UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException("B1 runtime does not implement " + what);
    }

    boolean isCallable(Object value) {
        if (value instanceof B1Closure) return true;
        if (value instanceof B1Bytes s) return functions.containsKey(s.symbolKey());
        if (value instanceof String s) return functions.containsKey(folded(s));
        if (value instanceof B1Object obj) return obj.method(this,"__invoke") != null;
        if (value instanceof B1Array pair && pair.size() == 2 && pair.get(1L) instanceof B1Bytes name) {
            Object recv = pair.get(0L);
            if (recv instanceof B1Object obj) {
                B1Callable fn = obj.method(this,name.symbolKey());
                return fn != null && callableFromScope(fn);
            }
            if (recv instanceof B1Bytes cls) {
                B1Callable fn = method(cls.symbolKey(),name.symbolKey());
                return fn != null && fn.isStatic && callableFromScope(fn);
            }
        }
        return false;
    }
    public B1Callable callable(Object value) {
        if (value instanceof B1Object obj) return resolve(obj,"__invoke","__invoke");
        if (value instanceof B1Closure closure) return closure.body;
        if (value instanceof B1Bytes name) return function(this, name.symbolKey());
        if (value instanceof String name) return function(this, folded(name));
        if (value instanceof B1Array pair && pair.size() == 2 && pair.get(1L) instanceof B1Bytes name) {
            Object receiver = pair.get(0L);
            if (receiver instanceof B1Object obj) return resolve(obj, name.symbolKey(), name.toString());
            if (receiver instanceof B1Bytes cls) {
                B1Callable fn = method(cls.symbolKey(), name.symbolKey());
                if (fn != null && fn.isStatic) { visible(fn); return fn; }
            }
        }
        throw error("Error", "Value is not callable");
    }
    public B1Object receiver(Object value) {
        if (value instanceof B1Closure closure) return closure.self;
        if (value instanceof B1Object obj) return obj;
        return value instanceof B1Array pair && pair.get(0L) instanceof B1Object obj ? obj : null;
    }
    public B1Object allocateDynamic(Object name) {
        String cls = string(name);
        return allocate(name instanceof B1Bytes bytes ? bytes.symbolKey() : folded(cls), cls);
    }
    public Object eval(Object code,B1Object self,int budget,boolean extended) {
        return pl.codeloom.php.b1.B1Compiler.eval(this, stringBytes(code), new pl.codeloom.php.ir.IrSourceSpan(frame.file, frame.line),self,budget,extended);
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
        if (old instanceof B1Object obj && findClass(this,obj.type).interfaces().contains("arrayaccess"))
            throw unsupported("ArrayAccess dimension writes");
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
        if (base instanceof B1Object obj && findClass(this,obj.type).interfaces().contains("arrayaccess"))
            throw unsupported("ArrayAccess dimension reads");
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

    int order(Object a, Object b) {
        if (a == b) return 0;
        if (a instanceof Boolean || b instanceof Boolean) return Boolean.compare(truth(a),truth(b));
        if (a == null || b == null) {
            Object other = a == null ? b : a;
            int cmp = other instanceof String || other instanceof B1Bytes ? Integer.signum(stringBytes(other).length)
                    : Boolean.compare(truth(other),false); // zend_compare: null against a number compares as bool
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

    public boolean instanceOf(Object value, String lower) {
        if (value instanceof B1Closure) return lower.equals("closure");
        if (!(value instanceof B1Object obj)) return false;
        if (obj.type.equals(lower)) return true;
        if (B1Thrown.throwable(obj.type)) return B1Thrown.subtype(obj.type,lower) || lower.equals("stringable");
        if (obj instanceof B1Generator) return lower.equals("iterator") || lower.equals("traversable");
        ClassDef def = findClass(this,obj.type);
        return def != null && def.interfaces().contains(lower);
    }

    /** A static call: Class::method() or self::method(). */
    public B1Callable staticMethod(String lowerClass, String lowerMethod, String cls, String method) {
        ClassDef def = findClass(this,lowerClass);
        if (def == null) throw error("Error","Class \"" + cls + "\" not found");
        B1Callable fn = def.method(this,lowerMethod);
        if (fn == null) throw error("Error","Call to undefined method " + def.name() + "::" + method + "()");
        visible(fn);
        return fn;
    }
    /** A non-static method called statically keeps a compatible $this; PHP 8 rejects every other case. */
    public B1Object staticReceiver(B1Callable fn, B1Object self) {
        if (fn.isStatic) return null;
        if (self != null && self.type.equals(fn.scope)) return self;
        throw error("Error","Non-static method " + fn.display + "() cannot be called statically");
    }
    private void visible(B1Callable fn) {
        if (callableFromScope(fn)) return;
        throw error("Error","Call to " + (fn.visibility == 2 ? "protected" : "private") + " method " + fn.display + "() from " + scopeName());
    }
    /** Without inheritance a protected method is callable from its own class only, like a private one. */
    private boolean callableFromScope(B1Callable fn) { return fn.visibility == 1 || fn.scope.equals(scope()); }
    private String scopeName() { return scope() == null ? "global scope" : "scope " + findClass(this,scope()).name(); }

    public Object subtract(Object left, Object right) {
        Number a = number(left, true), b = number(right, true);
        if (a == null || b == null) throw error("TypeError", "Unsupported operand types: " + typeName(left) + " - " + typeName(right));
        if (a instanceof Long && b instanceof Long) {
            try { return Math.subtractExact(a.longValue(), b.longValue()); } catch (ArithmeticException overflow) { }
        }
        return a.doubleValue() - b.doubleValue();
    }
    public Object divide(Object left, Object right) {
        Number a = number(left, true), b = number(right, true);
        if (a == null || b == null) throw error("TypeError", "Unsupported operand types: " + typeName(left) + " / " + typeName(right));
        if (b instanceof Long ? b.longValue() == 0 : b.doubleValue() == 0) throw error("DivisionByZeroError","Division by zero");
        if (a instanceof Long x && b instanceof Long y) {
            if (!(x == Long.MIN_VALUE && y == -1) && x % y == 0) return x / y;
            return (double)x / (double)y;
        }
        return a.doubleValue() / b.doubleValue();
    }
    public Object shiftLeft(Object left, Object right) {
        long a = integerOperand(left,left,right,"<<"), b = integerOperand(right,left,right,"<<");
        if (b < 0) throw error("ArithmeticError","Bit shift by negative number");
        return b >= 64 ? 0L : a << b;
    }
    public Object shiftRight(Object left, Object right) {
        long a = integerOperand(left,left,right,">>"), b = integerOperand(right,left,right,">>");
        if (b < 0) throw error("ArithmeticError","Bit shift by negative number");
        return b >= 64 ? (a < 0 ? -1L : 0L) : a >> b;
    }
    public Object bitAnd(Object left, Object right) { return bitwise(left,right,'&'); }
    public Object bitOr(Object left, Object right) { return bitwise(left,right,'|'); }
    public Object bitXor(Object left, Object right) { return bitwise(left,right,'^'); }
    private Object bitwise(Object left, Object right, char op) {
        if ((left instanceof B1Bytes || left instanceof String) && (right instanceof B1Bytes || right instanceof String)) {
            byte[] x = B1Bytes.bytes(left), y = B1Bytes.bytes(right);
            byte[] longer = x.length >= y.length ? x : y;
            byte[] out = op == '|' ? longer.clone() : new byte[Math.min(x.length,y.length)];
            for (int i = 0; i < Math.min(x.length,y.length); i++)
                out[i] = (byte)(op == '&' ? x[i] & y[i] : op == '|' ? x[i] | y[i] : x[i] ^ y[i]);
            return B1Bytes.of(out);
        }
        String symbol = String.valueOf(op);
        long a = integerOperand(left,left,right,symbol), b = integerOperand(right,left,right,symbol);
        return op == '&' ? a & b : op == '|' ? a | b : a ^ b;
    }
    /** The binary operator of a compound assignment, by IR operator ordinal. */
    public Object arithmetic(int opcode, Object left, Object right) {
        return switch (OPERATORS[opcode]) {
            case ADD -> add(left,right);
            case SUB -> subtract(left,right);
            case MUL -> multiply(left,right);
            case DIV -> divide(left,right);
            case MOD -> mod(left,right);
            case CONCAT -> concat(left,right);
            case SL -> shiftLeft(left,right);
            case SR -> shiftRight(left,right);
            case BW_AND -> bitAnd(left,right);
            case BW_OR -> bitOr(left,right);
            case BW_XOR -> bitXor(left,right);
            default -> throw new IllegalArgumentException(OPERATORS[opcode].name());
        };
    }
    static String describeKey(Object key) {
        return key instanceof String || key instanceof B1Bytes ? "\"" + key + "\"" : String.valueOf(key);
    }

    private Map<String,Object> constants = new HashMap<>();
    private final IdentityHashMap<B1Callable,LinkedHashMap<String,B1Ref>> staticVariables = new IdentityHashMap<>();
    /** Restored function statics ({@link B1Snapshot#staticKey}) whose function has not run in this request yet. */
    private Map<String,Map<String,Object>> unboundStatics = new HashMap<>();
    Map<String,B1Ref> staticVariables(B1Callable fn) {
        LinkedHashMap<String,B1Ref> slots = staticVariables.get(fn);
        if (slots != null) return slots;
        LinkedHashMap<String,B1Ref> created = new LinkedHashMap<>();
        Map<String,Object> restored = unboundStatics.isEmpty() ? null : unboundStatics.remove(B1Snapshot.staticKey(fn));
        if (restored != null) restored.forEach((name,value) -> {
            B1Ref slot = new B1Ref();
            slot.set(this,value);
            created.put(name,slot);
        });
        staticVariables.put(fn,created);
        return created;
    }
    /** Unbinds the function statics. Destructors that run meanwhile may bind new ones; those are drained as well. */
    private Throwable dropStaticVariables(Throwable pending) {
        while (!staticVariables.isEmpty()) {
            LinkedHashMap<String,B1Ref> slots = staticVariables.remove(staticVariables.keySet().iterator().next());
            while (!slots.isEmpty()) {
                B1Ref slot = slots.pollFirstEntry().getValue();
                try { slot.unbind(this); } catch (Throwable ex) { pending = B1Thrown.chain(this,ex,pending); }
            }
        }
        return pending;
    }
    /** Releases the defined constants, including any a destructor defines meanwhile. */
    private Throwable dropConstants(Map<String,Object> table,Throwable pending) {
        while (!table.isEmpty()) {
            String name = table.keySet().iterator().next();
            Object value = table.remove(name);
            try { B1Object.release(this,value); } catch (Throwable ex) { pending = B1Thrown.chain(this,ex,pending); }
        }
        return pending;
    }
    /** define()'s body (ZEND_FUNCTION(define), zend_register_constant). The name is kept as given: a leading
     * backslash is part of it. Arrays are copied without references; objects are not modelled. */
    Object define(String name, Object value, boolean caseInsensitive) {
        if (name.contains("::")) throw error("ValueError","define(): Argument #1 ($constant_name) cannot be a class constant");
        if (caseInsensitive) diagnostic("Warning","define(): Argument #3 ($case_insensitive) is ignored since declaration of case-insensitive constants is no longer supported");
        if (value instanceof B1Object || value instanceof B1Closure) throw unsupported("object constants");
        Object stored = value instanceof B1Array array
                ? array.constantCopy(this,Collections.newSetFromMap(new IdentityHashMap<>())) : value;
        if (constants.containsKey(name) || pl.codeloom.php.ir.PhpIrLowerer.foldedConstant(name)) {
            diagnostic("Warning","Constant " + name + " already defined, this will be an error in PHP 9");
            return false;
        }
        if (pl.codeloom.php.ir.PhpIrLowerer.builtinConstant(name)) throw unsupported("define() of a name PHP may define (" + name + ")");
        constants.put(name, stored);
        return true;
    }
    /** defined(): a lookup strips one leading backslash, as zend_get_constant_ex does. */
    boolean defined(String name) {
        if (name.startsWith("\\")) name = name.substring(1);
        if (constants.containsKey(name) || pl.codeloom.php.ir.PhpIrLowerer.foldedConstant(name)) return true;
        if (pl.codeloom.php.ir.PhpIrLowerer.builtinConstant(name)) throw unsupported("defined() of the engine constant " + name);
        return false;
    }
    /** (array) and (float) casts. The result is owned by the caller. */
    public Object convert(Object value, String type) {
        if (type.equals("float")) {
            if (value instanceof Double || value == null) return value == null ? 0.0 : value;
            if (value instanceof Long n) return n.doubleValue();
            if (value instanceof Boolean flag) return flag ? 1.0 : 0.0;
            if (value instanceof B1Array array) return array.size() == 0 ? 0.0 : 1.0;
            if (value instanceof B1Bytes || value instanceof String) {
                Number n = pl.codeloom.php.ir.PhpNumericString.parse(B1Bytes.bytes(value),true);
                return n == null ? 0.0 : n.doubleValue();
            }
            throw unsupported("(float) casts of objects");
        }
        if (value instanceof B1Array array) { array.retain(); return array; }
        if (value == null) return new B1Array(0);
        if (value instanceof B1Object || value instanceof B1Closure) throw unsupported("(array) casts of objects");
        return new B1Array(1).append(this,value);
    }
    /** A constant this request defined. PHP's own constants are resolved when lowering, or not at all. */
    public Object constant(String name) {
        Object value = constants.get(name);
        if (value == null && !constants.containsKey(name)) throw unsupported("the undefined (or engine) constant " + name);
        return value;
    }

    // Host embedding: a benchmark or test drives linked PHP code without the CLI script lifecycle.
    private pl.codeloom.php.b1.B1CodeCache code;
    private final Set<String> included = new HashSet<>();
    /** Contexts sharing a cache reuse each other's hidden classes for identical includes and evals. */
    public void shareCode(pl.codeloom.php.b1.B1CodeCache cache) { code = cache; }
    public pl.codeloom.php.b1.B1CodeCache codeCache() { return code; }
    public boolean markIncluded(String realPath) { return included.add(realPath); }
    public boolean wasIncluded(String realPath) { return included.contains(realPath); }
    public boolean atTopLevel() { return frame != null && frame.isTop(); }
    public Object include(Object path, int kind, int budget) {
        return pl.codeloom.php.b1.B1Compiler.include(this, string(path), kind,
                new pl.codeloom.php.ir.IrSourceSpan(frame.file, frame.line), budget);
    }
    /** Runs a linked file body like a CLI script, without the request shutdown. Returns the owned result. */
    public Object execute(B1Callable entry) { return entry.invoke0(this, null); }
    /** Calls a PHP callable from Java as an internal caller (like call_user_func). Returns the owned result. */
    public Object callHost(Object value, Object... args) {
        B1Callable target = callable(value);
        B1Object self = receiver(value);
        B1Frame host = new B1Frame(frame, "[host]", "{host}", new String[0], 0, false, false);
        host.internalCall = true;
        frame = host;
        try {
            if (value instanceof B1Closure closure) host.nextClosure = closure;
            return switch (args.length) {
                case 0 -> target.invoke0(this, self);
                case 1 -> target.invoke1(this, self, args[0]);
                case 2 -> target.invoke2(this, self, args[0], args[1]);
                default -> target.invoke(this, self, args.clone());
            };
        } finally { pop(host); }
    }
    public void setGlobal(String name, Object value) { global(name).set(this, value); }
    public Object globalValue(String name) {
        B1Ref slot = globals.get(name);
        return slot == null ? null : slot.get();
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
            if (name.equals("__toString")) return B1Exception.format(ctx, self);
            return B1Object.retain(self.read(pl.codeloom.php.ir.AsciiCase.lower(name.substring(3))));
        }
    }

    public boolean hasFunction(String lower) { return functions.containsKey(lower); }
    public boolean hasClass(String lower) { return classes.containsKey(lower); }
    public void declareClass(B1Callable entry, int id, String lowerName) {
        ClassDef cls = entry.statementClasses.get(id);
        ClassDef previous = classes.putIfAbsent(lowerName, cls);
        if (previous != null) throw redeclared("class " + cls.name(), previous.declaration(), cls.declaration());
        initializeStatics(lowerName,cls);
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
        types.forEach(this::initializeStatics);
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
    public B1Callable method(String lowerClass,String lowerMethod) {
        ClassDef def = findClass(this,lowerClass);
        return def == null ? null : def.method(this,lowerMethod);
    }
    public B1Callable resolve(B1Object recv, String lowerName, String name) {
        var target = recv.method(this,lowerName);
        if (target == null) throw error("Error", "Call to undefined method " + typeName(recv) + "::" + name + "()");
        if (target.visibility != 1) visible(target);
        return target;
    }
    /** zend_std_get_constructor: a non-public constructor is callable from its own class only. */
    public B1Callable constructor(B1Object recv) {
        B1Callable ctor = recv.method(this,"__construct");
        if (ctor != null && !callableFromScope(ctor))
            throw error("Error","Call to " + (ctor.visibility == 2 ? "protected" : "private") + " " + ctor.display + "() from " + scopeName());
        return ctor;
    }

    public B1Object allocate(String lower, String name) {
        var cls = findClass(this, lower);
        if (cls == null) throw error("Error", "Class \"" + name + "\" not found");
        if (lower.equals("fiber")) return track(new B1Fiber(this,cls));
        if (lower.equals("generator")) throw error("Error","The \"Generator\" class is reserved for internal use and cannot be manually instantiated");
        var obj = new B1Object(this,acquireObjectId(), lower, cls);
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
        frame.strictTypes = fn.strictTypes;
        frame.staticCall = fn.isStatic;
        frame.scope = fn.evalBody && frame.caller != null ? frame.caller.scope : fn.scope;
        if (frame.caller != null) {
            frame.closure = frame.caller.nextClosure;
            frame.caller.nextClosure = null;
            if (fn.evalBody) frame.attachEval(this, frame.caller);
        }
        if (frame.caller == null && currentFiber != null) frame.closure = currentFiber.entryClosure();
        return frame;
    }
    void pop(B1Frame f) {
        if (frame != f) throw new IllegalStateException("unbalanced PHP frame");
        frame = f.caller;
    }
    long globalVersion;
    B1Ref bindGlobal(String name,B1Ref slot) { globalVersion++; return globals.put(name,slot); }
    B1Ref removeGlobal(String name) { globalVersion++; return globals.remove(name); }
    B1Ref global(String name) { return globals.computeIfAbsent(name, unused -> new B1Ref()); }
    public B1Ref findGlobal(String name) { return globals.get(name); }

    private void initializeStatics(String owner,ClassDef cls) {
        initializeStatics(staticProperties,owner,cls);
    }

    private void initializeStatics(Map<String,B1Ref> slots,String owner,ClassDef cls) {
        cls.properties().forEach((name,p) -> {
            if (p.isStatic()) {
                String key = owner + "::$" + name;
                B1Ref slot = slots.computeIfAbsent(key,k -> new B1Ref());
                slot.declare(this,cls.name(),name,p);
            }
        });
    }
    public B1Ref staticProperty(String owner,String name) {
        var cls = findClass(this,owner);
        if (cls == null) throw error("Error","Class \"" + owner + "\" not found");
        if (cls.visibility().containsKey(name) && !owner.equals(scope()))
            throw error("Error","Cannot access " + (cls.visibility().get(name) == 2 ? "protected" : "private")
                    + " property " + cls.name() + "::$" + name);
        var slot = staticProperties.get(owner + "::$" + name);
        Property declaration = cls.properties().get(name);
        if (slot == null || declaration == null || !declaration.isStatic()) throw error("Error","Access to undeclared static property " + cls.name() + "::$" + name);
        return slot;
    }

    /** Copy initialized data into an immutable image, rejecting objects and PHP aliases. */
    public B1Snapshot freezePureData() {
        return B1Snapshot.capture(globals,staticProperties,staticVariables,unboundStatics,constants,included);
    }
    /** Replace this idle request's data. Class and function definitions are linked by the request. */
    public void restoreFrom(B1Snapshot snapshot) {
        if (frame != null) throw new IllegalStateException("Cannot restore during PHP execution");
        var nextGlobals = new LinkedHashMap<String,B1Ref>();
        var nextStatics = new LinkedHashMap<String,B1Ref>();
        classes.forEach((name,cls) -> initializeStatics(nextStatics,name,cls));
        snapshot.restore(this,nextGlobals,nextStatics);
        var oldGlobals = globals;
        var oldStatics = staticProperties;
        var oldConstants = constants;
        var oldStaticVariables = new ArrayList<>(staticVariables.values());
        globals = nextGlobals;
        staticProperties = nextStatics;
        staticVariables.clear();
        unboundStatics = new HashMap<>(snapshot.staticVariables());
        constants = new HashMap<>(snapshot.constants());
        included.clear();
        included.addAll(snapshot.included());
        Throwable pending = null;
        for (var slots : List.of(oldGlobals,oldStatics)) for (var old : slots.values()) {
            if (old == null) continue;
            try { old.unbind(this); } catch (Throwable ex) { pending = B1Thrown.chain(this,ex,pending); }
        }
        for (var slots : oldStaticVariables) for (var old : slots.values()) {
            try { old.unbind(this); } catch (Throwable ex) { pending = B1Thrown.chain(this,ex,pending); }
        }
        pending = dropConstants(oldConstants,pending);
        B1Thrown.propagate(pending);
    }
    boolean strict() { return frame != null && !frame.internalCall && frame.strictTypes; }

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
        if (!displayErrors) return;
        echo("\n" + level + ": " + message + " in " + frame.file + " on line " + frame.line + "\n");
    }

    public static Object quietProperty(Object receiver,B1RequestContext ctx,String name) {
        if (receiver instanceof B1Object obj && !(obj.has(name) && obj.accessible(ctx,name)) && obj.magicAccess())
            throw ctx.unsupported("__isset/__get dispatch");
        return receiver instanceof B1Object obj && obj.has(name) && obj.accessible(ctx,name) ? obj.read(name) : null;
    }
    public static Object readProperty(Object receiver, B1RequestContext ctx, String name) {
        if (receiver instanceof B1Object obj) {
            if (!(obj.has(name) && obj.accessible(ctx,name)) && obj.magicAccess()) throw ctx.unsupported("__get dispatch");
            if (obj.has(name)) return obj.property(ctx,name).readProperty(ctx);
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
        if (value == null && !strict()) {
            diagnostic("Deprecated",function + ": Passing null to parameter #" + index + " ($" + name + ") of type int is deprecated");
            return 0;
        }
        Long n = strict() && !(value instanceof Long) ? null : integer(value);
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
        if (value instanceof B1Array a) return a.size() == 0 ? 0L : 1L;
        if (value instanceof B1Object || value instanceof B1Closure) {
            diagnostic("Warning","Object of class " + typeName(value) + " could not be converted to int");
            return 1L;
        }
        Number n = value instanceof B1Bytes || value instanceof String
                ? pl.codeloom.php.ir.PhpNumericString.parse(B1Bytes.bytes(value),true) : number(value,true);
        if (n instanceof Double d) {
            if (value instanceof Double) return floatToLong(d);
            if (!Double.isFinite(d)) return 0L;
        }
        return n == null ? 0L : n.longValue();
    }
    public Object multiply(Object left,Object right) {
        Number a = number(left,true), b = number(right,true);
        if (a == null || b == null) throw error("TypeError","Unsupported operand types: " + typeName(left) + " * " + typeName(right));
        if (a instanceof Long && b instanceof Long) {
            try { return Math.multiplyExact(a.longValue(),b.longValue()); } catch (ArithmeticException overflow) { }
        }
        return a.doubleValue() * b.doubleValue();
    }
    private long floatToLong(double value) {
        if (value >= -0x1p63 && value < 0x1p63) return (long)value;
        diagnostic("Warning","The float " + string(value) + " is not representable as an int, cast occurred");
        return pl.codeloom.php.ir.PhpFloatFormat.wrapToLong(value);
    }
    private long modOperand(Object value,Object a,Object b) { return integerOperand(value,a,b,"%"); }
    /** zendi_try_get_long for the integer operators. */
    private long integerOperand(Object value,Object a,Object b,String operator) {
        Number n = number(value,true);
        if (n == null) throw error("TypeError","Unsupported operand types: " + typeName(a) + " " + operator + " " + typeName(b));
        if (n instanceof Double d) {
            boolean isFloat = value instanceof Double;
            long integer = isFloat ? floatToLong(d) : Double.isFinite(d) ? d.longValue() : 0;
            boolean inRange = d >= -0x1p63 && d < 0x1p63;
            if ((!isFloat || inRange) && (!inRange || (double)integer != d)) {
                String source = isFloat ? "float " + string(d) : "float-string \"" + value + "\"";
                diagnostic("Deprecated","Implicit conversion from " + source + " to int loses precision");
            }
            return integer;
        }
        return n.longValue();
    }
    public Object mod(Object a,Object b) {
        long left = modOperand(a,a,b), divisor = modOperand(b,a,b);
        if (divisor == 0) throw error("DivisionByZeroError","Modulo by zero");
        return left % divisor;
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
        for (var slot : staticProperties.values()) {
            try { slot.unbind(this); } catch (Throwable ex) { pending = B1Thrown.chain(this,ex,pending); }
        }
        staticProperties.clear();
        pending = dropStaticVariables(pending);
        unboundStatics = new HashMap<>();
        pending = dropConstants(constants,pending);
        try { collectCycles(); } catch (Throwable ex) { pending = B1Thrown.chain(this,ex,pending); }
        while (!destructibles.isEmpty()) {
            try { destructibles.getFirst().destruct(this); } catch (Throwable ex) { pending = B1Thrown.chain(this, ex, pending); }
        }
        for (var worker : fiberThreads) worker.retire();
        for (var worker : fiberThreads) worker.join();
        idleFibers.clear();
        fiberThreads.clear();
        if (frame != null || liveDestructibleCount() != 0) throw new IllegalStateException("incomplete PHP shutdown");
        B1Thrown.propagate(pending);
    }
}
