package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.nodes.call.CallNode;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpType;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

/**
 * `$obj->name` (or `$obj->$name`). Properties are read and written through a shape-dispatched object library,
 * so a site that always sees objects of one class compiles to a field access. A property slot may hold a PhpRef.
 * A protected or private property carries shape flags ({@link PhpClass#PROPERTY_PROTECTED}): only then does the
 * site check that the calling scope may access it, and from a scope that may not, the property is as php-src
 * treats it: the magic methods if the class has them, else an Error.
 */
public final class PropNode extends LValueNode {
    @Child private ExprNode object;
    @Child private ExprNode nameExpr;
    @Child private DynamicObjectLibrary objects;
    private final String constantName;
    /** The class (lowercase) the code is written in, or null outside classes. */
    private final String lexicalClass;
    /**
     * In a closure or a file's top-level code, whose scope the frame carries (a closure's bound scope, the scope
     * a file was included into); elsewhere the scope is the lexical class.
     */
    private final boolean scopeInFrame;
    /**
     * `$this->name` in a method: a protected property is always accessible there, as the method's class and the
     * class that declared the property are both ancestors of $this's.
     */
    private final boolean thisInMethod;
    /** In a property hook's body, the hooked property's name: there `$this->name` is its backing value. */
    private final String hookOf;
    /** The uncached access lazy objects' accesses are made again with (twin()). */
    private PropNode twin;
    /**
     * The key of classes whose non-public property (the constant name) the site's lexical scope may access. A key,
     * not the PhpClass: the AST is shared by contexts, and a context's class must neither stay reachable from it
     * once the context is closed nor be the only class the shortcut serves.
     */
    @CompilationFinal private ClassKey accessibleKey;
    /**
     * What writes (and reads of non-objects) here have met, one bit per case (the SEEN_ constants), as LocalVarNode's:
     * compiled code leaves out a reference's write, __set() and the holder updates until a write meets them.
     * Unprofiled, every property assignment compiled all of them (about 27 IR nodes a site).
     */
    @CompilationFinal private int seen;
    private static final int SEEN_REF = 1;
    private static final int SEEN_MISSING = 1 << 1;
    private static final int SEEN_NEW_ARRAY = 1 << 2;
    private static final int SEEN_OLD_ARRAY = 1 << 3;
    /** A read (read()) of a value that isn't an object. */
    private static final int SEEN_NON_OBJECT = 1 << 4;
    /** The writes through a property that is a reference (SEEN_REF). */
    private final RefWrites refWrites = RefWrites.create();

    /** constantName is an interned property name, or null with nameExpr computing it. */
    public PropNode(ExprNode object, String constantName, ExprNode nameExpr, String lexicalClass,
                    boolean scopeInFrame) {
        this(object, constantName, nameExpr, lexicalClass, scopeInFrame, object instanceof ThisNode && !scopeInFrame,
                null, DynamicObjectLibrary.getFactory().createDispatched(4));
    }

    private PropNode(ExprNode object, String constantName, ExprNode nameExpr, String lexicalClass,
                     boolean scopeInFrame, boolean thisInMethod, String hookOf, DynamicObjectLibrary objects) {
        this.object = object;
        this.hookOf = hookOf;
        this.constantName = constantName;
        this.nameExpr = nameExpr;
        this.lexicalClass = lexicalClass;
        this.scopeInFrame = scopeInFrame;
        this.thisInMethod = thisInMethod;
        this.objects = objects;
    }

    /**
     * The property access of a site whose object and name a bytecode instruction evaluates, which the *On methods
     * take. constantName is null for a name computed per access ({@link #dynamicName}); thisInMethod as the
     * tree's (`$this->name` in a method); hookOf the property whose hook the site is in, or null; uncached for the
     * uncached interpreter, which doesn't adopt the node.
     */
    public static PropNode forOperands(String constantName, String lexicalClass, boolean scopeInFrame,
                                       boolean thisInMethod, String hookOf, boolean uncached) {
        return new PropNode(null, constantName, null, lexicalClass, scopeInFrame, thisInMethod, hookOf,
                uncached ? DynamicObjectLibrary.getUncached() : DynamicObjectLibrary.getFactory().createDispatched(4));
    }

    /** Notes that a write here met a case; in compiled code that hadn't seen it, deoptimizes first. */
    private void met(int bit) {
        if ((seen & bit) == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seen |= bit;
        }
    }

    private String name(VirtualFrame frame) {
        return constantName != null ? constantName : dynamicName(nameExpr.execute(frame));
    }

    /** A property name computed at run time (`$obj->$name`): the value as a string, as a property key. */
    @TruffleBoundary
    public static String dynamicName(Object value) {
        return Str.key(Convert.toStr(value));
    }

    /** The object written to: a `$$name` is fetched for W (made if undefined, no warning), as php-src does. */
    private PhpObject target(VirtualFrame frame, String name, String action) {
        return target(writtenObject(frame, VariableVariableNode.W), name, action);
    }

    /**
     * The object as a write (BP_VAR_W) or unset (BP_VAR_UNSET) fetches it: an unset place quietly, as null; a
     * `$$name` by that fetch's rules.
     */
    private Object writtenObject(VirtualFrame frame, int fetch) {
        return written(frame, object, fetch);
    }

    /**
     * A place as that fetch fetches it, down the chain: a property read as a read does (FETCH_OBJ_W falls back to
     * __get, no __isset) with readonly's checks (readForWriteOn, readForUnset), an element or a variable quietly.
     */
    static Object written(VirtualFrame frame, ExprNode node, int fetch) {
        if (node instanceof VariableVariableNode variable) {
            return variable.fetch(frame, fetch);
        }
        if (node instanceof PropNode property) {
            return fetch == VariableVariableNode.UNSET ? property.readForUnset(frame)
                    : property.readForWriteOn(frame.getArguments(), written(frame, property.object, fetch), property.name(frame),
                    false);
        }
        if (node instanceof DimNode dim) {
            return dim.writtenIn(frame, written(frame, dim.container(), fetch));
        }
        if (node instanceof LValueNode place) {
            Object value = place.readIfSet(frame);
            return value == null ? PhpNull.NULL : value;
        }
        return node.execute(frame);
    }

    private static PhpObject target(Object value, String name, String action) {
        if (value instanceof PhpObject phpObject) {
            return phpObject;
        }
        throw notAnObject(action, name, value);
    }

    /**
     * The error of a write (action "assign", "modify"...) to a property of a value that isn't an object
     * (zend_throw_non_object_error: the value as zend_zval_value_name names it).
     */
    @TruffleBoundary
    public static PhpError notAnObject(String action, String name, Object value) {
        if (value instanceof PhpClosure) {
            // an object of a class without dynamic properties (ZEND_ACC_NO_DYNAMIC_PROPERTIES)
            return new PhpError("Cannot create dynamic property Closure::$" + Str.keyText(name));
        }
        return new PhpError("Attempt to " + action + " property \"" + Str.keyText(name) + "\" on "
                + Args.valueName(value));
    }

    // ---- visibility ----

    /** The flags every access checks: visibility and hooks (a typed or set-checked property's writes check those). */
    private static final int CHECKED = PhpClass.PROPERTY_PROTECTED | PhpClass.PROPERTY_PRIVATE
            | PhpClass.PROPERTY_HOOKED;

    /**
     * Whether the calling scope may access the property as a plain one: public and undeclared ones need no check;
     * a hooked one only in its own hook on its own object, where it is its backing value.
     */
    private boolean accessible(Object[] arguments, PhpObject phpObject, String name) {
        return accessible(arguments, phpObject, name, objects.getPropertyFlagsOrDefault(phpObject, name, 0));
    }

    /** accessible() of a property with these shape flags. */
    private boolean accessible(Object[] arguments, PhpObject phpObject, String name, int shapeFlags) {
        int flags = shapeFlags & CHECKED;
        if (flags == 0 || thisInMethod && flags == PhpClass.PROPERTY_PROTECTED) {
            return true;
        }
        if ((flags & PhpClass.PROPERTY_HOOKED) != 0 && !inOwnHook(arguments, phpObject, name)) {
            return false;
        }
        Object scope = scopeInFrame ? arguments[PhpFunction.ARG_CLOSURE] : null;
        boolean lexical = scope == null && constantName != null;
        PhpClass phpClass = phpObject.getPhpClass();
        if (lexical && phpClass.getKey() == accessibleKey) {
            return true;
        }
        boolean allowed = phpClass.canAccessProperty(name, callingScope(scope, lexicalClass));
        if (allowed && lexical && accessibleKey == null && phpClass.isPropertyAccessKeyed(name, lexicalClass)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            accessibleKey = phpClass.getKey();
        }
        return allowed;
    }

    /**
     * Whether code here may write the property with these shape flags: after the visibility, a readonly or
     * protected(set) property's checks (PROPERTY_SET_CHECKED: PhpObject.checkModify, an Error if not).
     */
    private boolean writable(Object[] arguments, PhpObject phpObject, String name, String operation) {
        int flags = objects.getPropertyFlagsOrDefault(phpObject, name, 0);
        if (!accessible(arguments, phpObject, name, flags)) {
            return false;
        }
        if ((flags & PhpClass.PROPERTY_SET_CHECKED) != 0) {
            phpObject.checkModify(name, scope(arguments), operation);
        }
        return true;
    }

    /**
     * A reference to, or an element write into, a property: PhpObject.indirect for a readonly one or one with a
     * set visibility (PROPERTY_SET_CHECKED), or one this object doesn't have (old null); false for the others.
     */
    private boolean readonlyIndirect(Object[] arguments, PhpObject phpObject, String name, Object old) {
        return (old == null || (objects.getPropertyFlagsOrDefault(phpObject, name, 0)
                & PhpClass.PROPERTY_SET_CHECKED) != 0) && phpObject.indirect(name, old, scope(arguments));
    }

    /**
     * php-src's executed scope: a closure's bound scope, the scope a file was included into, else the class the
     * code is written in.
     */
    public static String callingScope(VirtualFrame frame, String lexicalClass) {
        return callingScope(frame.getArguments()[PhpFunction.ARG_CLOSURE], lexicalClass);
    }

    public static String callingScope(Object scope, String lexicalClass) {
        if (scope instanceof PhpClosure closure) {
            return closure.getScope() == null ? null : closure.getScope().getLowerName();
        }
        if (scope instanceof IncludeScope included) {
            return included.classScope();
        }
        return lexicalClass;
    }

    /** zend_should_call_hook's other side: in the property's own hook, on the hook's $this, the backing value. */
    private boolean inOwnHook(Object[] arguments, PhpObject phpObject, String name) {
        if (hookOf == null || !hookOf.equals(name)) {
            return false;
        }
        Object self = arguments[PhpFunction.ARG_THIS];
        // a hook running on an initialized proxy has its instance's backing value too
        return self == phpObject || self instanceof PhpObject proxy && proxy.lazy() != null
                && proxy.lazy().instance() == phpObject;
    }

    /** The calling scope, as accessible() finds it. */
    private String scope(Object[] arguments) {
        return callingScope(scopeInFrame ? arguments[PhpFunction.ARG_CLOSURE] : null, lexicalClass);
    }

    /**
     * The hooks of a property the fast path didn't take (accessible() said no, or the object hasn't the property):
     * null for one without; from a scope that may not access it, null when the class has the magic method to call
     * instead, else the Error (zend_get_property_offset checks the scope before the hooks).
     */
    private PhpClass.Hooks hooks(Object[] arguments, PhpObject phpObject, String name, String magic) {
        PhpClass phpClass = phpObject.getPhpClass();
        PhpClass.Hooks hooks = phpClass.findHooks(name);
        if (hooks == null || phpClass.canAccessProperty(name, scope(arguments))) {
            return hooks;
        }
        if (phpClass.findMethod(magic) == null) {
            throw inaccessible(phpObject, name);
        }
        return null;
    }

    /** A hook's call on the object: a by-reference get hook's result stays a PhpRef. */
    private Object callHook(PhpFunction hook, PhpObject phpObject, Object... args) {
        return Backtrace.callFrom(this, () -> hook.invokeRef(phpObject, phpObject.getPhpClass(), args));
    }

    /** A hooked property's value (zend_std_read_property's hooked case), dereferenced. */
    private Object hookedRead(Object[] arguments, PhpObject phpObject, String name, PhpClass.Hooks hooks) {
        boolean own = inOwnHook(arguments, phpObject, name);
        if (hooks.get() == null || own) {
            if (hooks.virtual()) {
                throw new PhpError((own ? "Must not read from virtual property " : "Property ")
                        + phpObject.getPhpClass().getName() + "::$" + Str.keyText(name) + (own ? "" : " is write-only"));
            }
            Object value = PhpRef.deref(phpObject.getProperty(name));
            if (value == null && phpObject.lazy() != null && !phpObject.isInitializingLazy()) {
                // the backing value of an uninitialized lazy object
                value = PhpRef.deref(phpObject.initializeLazy().getProperty(name));
            }
            return value == null ? PhpNull.NULL : value;
        }
        return PhpRef.deref(callHook(hooks.get(), phpObject));
    }

    /**
     * A write to a hooked property (zend_std_write_property's hooked case), the set hook's call: true when done,
     * false for a backed property without one, which the caller writes as a plain one.
     */
    private boolean hookedWrite(Object[] arguments, PhpObject phpObject, String name, Object value,
                                PhpClass.Hooks hooks) {
        boolean own = inOwnHook(arguments, phpObject, name);
        if (hooks.set() == null || own) {
            if (hooks.virtual()) {
                throw new PhpError((own ? "Must not write to virtual property " : "Property ")
                        + phpObject.getPhpClass().getName() + "::$" + Str.keyText(name) + (own ? "" : " is read-only"));
            }
            if (phpObject.getProperty(name) == null && phpObject.lazy() != null && !phpObject.isInitializingLazy()) {
                // the backing value of a lazy object: initialized first, a proxy's instance's
                PhpObject target = phpObject.initializeLazy();
                if (target != phpObject) {
                    target.writeRaw(name, value);
                    return true;
                }
            }
            if (phpObject.getProperty(name) != null) {
                return false;
            }
            // an uninitialized backing value: written with the slot's flags, which a plain put wouldn't give it
            phpObject.writeRaw(name, value);
            return true;
        }
        // the set visibility before the hook (zend_std_write_property's hooked case)
        phpObject.checkSetAccess(name, scope(arguments), "modify");
        callHook(hooks.set(), phpObject, value);
        return true;
    }

    /**
     * A hooked property as a place to modify (zend_std_get_property_ptr_ptr gives none, so read_property's BP_VAR_W):
     * a by-reference get hook's reference, an object in a reference of its own (its modification goes through
     * the object), else php-src's Error.
     */
    private PhpRef hookedRef(Object[] arguments, PhpObject phpObject, String name, PhpClass.Hooks hooks) {
        Object value = hooks.get() != null && !inOwnHook(arguments, phpObject, name)
                ? callHook(hooks.get(), phpObject) : hookedRead(arguments, phpObject, name, hooks);
        if (value instanceof PhpRef ref) {
            return ref;
        }
        if (value instanceof PhpObject) {
            return new PhpRef(value);
        }
        throw new PhpError("Indirect modification of " + phpObject.getPhpClass().getName() + "::$"
                + Str.keyText(name) + " is not allowed");
    }

    /** Reading a property the fast path didn't take: its hooks, __get's result, else zend_bad_property_access. */
    @TruffleBoundary
    private Object inaccessibleRead(Object[] arguments, PhpObject phpObject, String name) {
        PhpClass.Hooks hooks = hooks(arguments, phpObject, name, "__get");
        if (hooks != null) {
            return hookedRead(arguments, phpObject, name, hooks);
        }
        Object magic = phpObject.magicProperty(this, "__get", name);
        if (magic == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
        return magic;
    }

    /** A write the fast path didn't take: false for a hooked one the caller writes as a plain property after all. */
    @TruffleBoundary
    private boolean inaccessibleWrite(Object[] arguments, PhpObject phpObject, String name, Object value) {
        PhpClass.Hooks hooks = hooks(arguments, phpObject, name, "__set");
        if (hooks != null) {
            return hookedWrite(arguments, phpObject, name, value, hooks);
        }
        if (phpObject.magicProperty(this, "__set", name, value) == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
        return true;
    }

    /**
     * Writing a property the object hasn't: a virtual one's hooks, a lazy object's initialization, else __set();
     * false when none took it.
     */
    @TruffleBoundary
    private boolean missingWrite(Object[] arguments, PhpObject phpObject, String name, Object value) {
        PhpClass.Hooks hooks = missingHooks(arguments, phpObject, name, "__set");
        if (hooks != null) {
            return hookedWrite(arguments, phpObject, name, value, hooks);
        }
        PhpObject target = lazyTarget(arguments, phpObject, name, "__set");
        if (target != null) {
            twin().writeOn(arguments, target, name, value);
            return true;
        }
        String scope = scope(arguments);
        if (initialize(phpObject, name, value, scope)) {
            return true;
        }
        if (phpObject.lazy() == null || phpObject.isInitializingLazy()) {
            // __set, else a declared property's set visibility (PhpObject.writeMissing)
            return phpObject.writeMissing(this, scope, name, value);
        }
        return phpObject.magicProperty(this, "__set", name, value) != PhpObject.NO_MAGIC
                || afterMagic(phpObject, name, "__set", instance -> {
                    twin().writeOn(arguments, instance, name, value);
                    return true;
                }) != null;
    }

    /**
     * The object a lazy one forwards an access to a property it hasn't to before its magic method
     * (zend_std_read_property...): for a declared one, itself initialized or a proxy's instance; on an initialized
     * proxy, the instance whose magic method for the property runs. Null when the access goes on as on any object;
     * a declared property the scope may not access is the Error, unless the class has the magic method.
     */
    private PhpObject lazyTarget(Object[] arguments, PhpObject phpObject, String name, String magic) {
        PhpObject.Lazy lazy = phpObject.lazy();
        if (lazy == null || phpObject.isInitializingLazy() || phpObject.getProperty(name) != null) {
            return null;
        }
        PhpClass phpClass = phpObject.getPhpClass();
        if (phpClass.isDeclaredProperty(name) && phpClass.findHooks(name) == null) {
            if (!phpClass.canAccessProperty(name, scope(arguments))) {
                if (phpClass.findMethod(magic) == null) {
                    throw inaccessible(phpObject, name);
                }
                return null;
            }
            return phpObject.initializeLazy();
        }
        PhpObject instance = lazy.instance();
        return instance != null && instance.inMagic(magic, name) ? instance : null;
    }

    /**
     * A lazy object's access its magic method didn't take (it has none, or its guard is set): made on the object
     * initialized, with that guard set there when the class has the method (zend_std_*_property's lazy_init); null
     * for an object that isn't lazy.
     */
    private <T> T afterMagic(PhpObject phpObject, String name, String magic,
                             java.util.function.Function<PhpObject, T> access) {
        if (phpObject.lazy() == null || phpObject.isInitializingLazy()) {
            return null;
        }
        PhpObject instance = phpObject.initializeLazy();
        return phpObject.getPhpClass().findMethod(magic) == null ? access.apply(instance)
                : instance.guarded(magic, name, () -> access.apply(instance));
    }

    /** The access made again on a lazy object's target (lazyTarget), from runtime code: the uncached twin's. */
    private PropNode twin() {
        PropNode access = twin;
        if (access == null) {
            access = new PropNode(null, constantName, null, lexicalClass, scopeInFrame, thisInMethod, hookOf,
                    DynamicObjectLibrary.getUncached());
            twin = access;
        }
        return access;
    }

    /** A modification's place the fast path didn't take: a hooked property's (hookedRef), or __get's value's. */
    @TruffleBoundary
    private PhpRef inaccessibleRef(Object[] arguments, PhpObject phpObject, String name) {
        PhpClass.Hooks hooks = hooks(arguments, phpObject, name, "__get");
        return hooks != null ? hookedRef(arguments, phpObject, name, hooks)
                : magicRef(inaccessibleForWrite(phpObject, name));
    }

    /** makeRef of a property the object hasn't: a virtual one's place, a lazy object's target's; else null. */
    @TruffleBoundary
    private PhpRef missingRef(Object[] arguments, PhpObject phpObject, String name) {
        if (missingHooks(arguments, phpObject, name, "__get") != null) {
            return inaccessibleRef(arguments, phpObject, name);
        }
        PhpObject target = lazyTarget(arguments, phpObject, name, "__get");
        if (target != null) {
            return twin().makeRefOn(arguments, target, name);
        }
        // an uninitialized readonly one gives readonlyIndirect's Error, a typed one starts as null
        if (phpObject.isReadonly(name) && phpObject.isUninitialized(name) || uninitializedRef(phpObject, name)) {
            return null;
        }
        Object magic = magicForWrite(phpObject, name);
        if (magic != PhpObject.NO_MAGIC) {
            return magicRef(magic);
        }
        return afterMagic(phpObject, name, "__get", instance -> twin().makeRefOn(arguments, instance, name));
    }

    /** The hooks of a virtual property, which the object hasn't (hooks()); null for another missing one. */
    @TruffleBoundary
    private PhpClass.Hooks missingHooks(Object[] arguments, PhpObject phpObject, String name, String magic) {
        return phpObject.getPhpClass().hasHooks() ? hooks(arguments, phpObject, name, magic) : null;
    }

    @TruffleBoundary
    private static PhpError inaccessible(PhpObject phpObject, String name) {
        PhpClass.PropertyDecl decl = phpObject.getPhpClass().findProperty(name);
        return new PhpError("Cannot access " + (decl.visibility() == PhpClass.PropertyDecl.PRIVATE ? "private"
                : "protected") + " property " + phpObject.getPhpClass().getName() + "::$" + Str.keyText(name));
    }

    // ---- reads ----

    /** An int property read without boxing when the object stores it in a long location. */
    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value = object.execute(frame);
        if (value instanceof PhpObject phpObject) {
            String name = name(frame);
            if (!accessible(frame.getArguments(), phpObject, name)) {
                throw new UnexpectedResultException(inaccessibleRead(frame.getArguments(), phpObject, name));
            }
            try {
                return objects.getLongOrDefault(phpObject, name, ABSENT);
            } catch (UnexpectedResultException e) {
                Object result = e.getResult();
                throw new UnexpectedResultException(result == ABSENT ? missing(frame.getArguments(), phpObject, name)
                        : PhpRef.deref(result));
            }
        }
        throw new UnexpectedResultException(read(frame.getArguments(), value, name(frame)));
    }

    /** The default of a property lookup: the property doesn't exist (declared ones exist until unset). */
    private static final Object ABSENT = new Object();

    /**
     * Reading a property the object hasn't: a virtual one's hooks, a lazy object's initialization, an uninitialized
     * typed property's Error (from a scope that may access it), else __get's result if the class has one, else null.
     */
    @TruffleBoundary
    private Object missing(Object[] arguments, PhpObject phpObject, String name) {
        PhpClass.Hooks hooks = missingHooks(arguments, phpObject, name, "__get");
        if (hooks != null) {
            return hookedRead(arguments, phpObject, name, hooks);
        }
        PhpObject target = lazyTarget(arguments, phpObject, name, "__get");
        if (target != null) {
            return twin().readOn(arguments, target, name);
        }
        PhpClass.PropertyDecl decl = typedDecl(phpObject, name);
        if (decl == null && phpObject.lazy() == null) {
            return phpObject.readMissing(this, name);
        }
        if (decl != null && !phpObject.getPhpClass().canAccessProperty(name, scope(arguments))) {
            return inaccessibleRead(arguments, phpObject, name);
        }
        Object magic = decl == null || phpObject.wasUnset(name) ? phpObject.magicProperty(this, "__get", name)
                : PhpObject.NO_MAGIC;
        if (magic != PhpObject.NO_MAGIC) {
            return magic;
        }
        if (decl != null) {
            throw new PhpError("Typed property " + PhpType.declaringName(decl) + "::$" + Str.keyText(name)
                    + " must not be accessed before initialization");
        }
        Object forwarded = afterMagic(phpObject, name, "__get", instance -> twin().readOn(arguments, instance, name));
        return forwarded == null ? PhpNull.NULL : forwarded;    }

    /** The declaration of a typed property, or null. */
    @TruffleBoundary
    private static PhpClass.PropertyDecl typedDecl(PhpObject phpObject, String name) {
        PhpClass.PropertyDecl decl = phpObject.getPhpClass().findProperty(name);
        return decl != null && decl.type() != null ? decl : null;
    }

    /** Whether the property has a type: its writes are checked (compiled code tests the shape's flag). */
    private boolean typed(PhpObject phpObject, String name) {
        return (objects.getPropertyFlagsOrDefault(phpObject, name, 0) & PhpClass.PROPERTY_TYPED) != 0;
    }

    /** A value for a typed property: converted in the writing code's mode, or the TypeError. */
    @TruffleBoundary
    private Object checked(PhpObject phpObject, String name, Object value) {
        PhpClass.PropertyDecl decl = phpObject.getPhpClass().findProperty(name);
        return decl.type().assign(PhpRef.deref(value), strict(), decl, this);
    }

    /** Whether the user code running now is in a strict_types file. */
    private static boolean strict() {
        PhpLanguage language = PhpContext.get(null).getLanguage();
        return !language.noStrictFiles().isValid() && language.isStrict(SourceLocation.current().file());
    }

    /**
     * A write to an uninitialized typed property, which skips __set: the converted value stored with the
     * declaration's flags. False if the property isn't one.
     */
    @TruffleBoundary
    private boolean initialize(PhpObject phpObject, String name, Object value, String scope) {
        PhpClass.PropertyDecl decl = typedDecl(phpObject, name);
        if (decl == null) {
            return false;
        }
        if (!phpObject.getPhpClass().canAccessProperty(name, scope)) {
            throw inaccessible(phpObject, name);
        }
        if (phpObject.wasUnset(name)) {
            if (phpObject.magicProperty(this, "__set", name, value) != PhpObject.NO_MAGIC) {
                return true;
            }
            phpObject.setUnset(name, false);
        }
        // a readonly or set-restricted property is initialized from a scope that may set it
        phpObject.checkSetAccess(name, scope, "modify");
        Object converted = decl.type().assign(PhpRef.deref(value), strict(), decl, this);
        if (converted instanceof PhpArray array) {
            array.addHolder();
        }
        phpObject.setProperty(name, converted);
        return true;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = object.execute(frame);
        return read(frame.getArguments(), value, name(frame));
    }

    /**
     * A read of property name of value as an operand (ZEND_FETCH_OBJ_R): readOn, and for a value that isn't an
     * object null after php-src's warning (zend_wrong_property_read).
     */
    public Object read(Object[] arguments, Object value, String name) {
        if (!(value instanceof PhpObject)) {
            met(SEEN_NON_OBJECT);
            nonObjectRead(this, value, name);
            return PhpNull.NULL;
        }
        return readOn(arguments, value, name);
    }

    @TruffleBoundary
    private static void nonObjectRead(Node site, Object value, String name) {
        ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_WARNING, Str.of("Attempt to read property \""
                + Str.keyText(name) + "\" on " + Args.valueName(value)), SourceLocation.of(site));
    }

    /** The read of property name of value, evaluated (null for a value that isn't an object). */
    public Object readOn(Object[] arguments, Object value, String name) {
        if (value instanceof PhpObject phpObject) {
            if (!accessible(arguments, phpObject, name)) {
                return inaccessibleRead(arguments, phpObject, name);
            }
            Object property = objects.getOrDefault(phpObject, name, null);
            if (property instanceof PhpRef ref) {
                return ref.get();
            }
            return property == null ? missing(arguments, phpObject, name) : property;
        }
        return PhpNull.NULL;
    }

    /**
     * The property as the base of an unset() of its elements (BP_VAR_UNSET): a readonly one holding a value that
     * isn't an object is an Error, an uninitialized one has nothing to unset.
     */
    public Object readForUnset(VirtualFrame frame) {
        Object value = written(frame, object, VariableVariableNode.UNSET);
        return readForUnsetOn(frame.getArguments(), value, name(frame));
    }

    /**
     * The property as the object of a write to one of its own properties or elements (BP_VAR_W, or BP_VAR_RW for
     * a compound assignment): a readonly one that holds no object is an Error, an uninitialized one too unless rw
     * (whose read is the "must not be accessed" Error).
     */
    public Object readForWriteOn(Object[] arguments, Object value, String name, boolean rw) {
        if (value instanceof PhpObject phpObject && accessible(arguments, phpObject, name)) {
            Object property = objects.getOrDefault(phpObject, name, null);
            if (property != null) {
                readonlyIndirect(arguments, phpObject, name, property);
            } else if (!rw && phpObject.isUninitialized(name)) {
                readonlyIndirect(arguments, phpObject, name, null);
            }
        }
        return readOn(arguments, value, name);
    }

    public Object readForUnsetOn(Object[] arguments, Object value, String name) {
        if (value instanceof PhpObject phpObject && accessible(arguments, phpObject, name)) {
            Object property = objects.getOrDefault(phpObject, name, null);
            if (property == null && phpObject.isUninitialized(name)) {
                return PhpNull.NULL;
            }
            if (property != null) {
                readonlyIndirect(arguments, phpObject, name, property);
            }
        }
        return readOn(arguments, value, name);
    }

    // ---- writes ----

    /** Stores an int unboxed (a long location), unless the property is a reference. */
    @Override
    public void writeLong(VirtualFrame frame, long value) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "assign");
        int flags = objects.getPropertyFlagsOrDefault(phpObject, name, 0);
        if ((flags & (PhpClass.PROPERTY_TYPED | PhpClass.PROPERTY_SET_CHECKED)) != 0) {
            writeOn(frame.getArguments(), phpObject, name, value);
            return;
        }
        if (!accessible(frame.getArguments(), phpObject, name, flags)
                && inaccessibleWrite(frame.getArguments(), phpObject, name, value)) {
            return;
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpRef ref) {
            met(SEEN_REF);
            refWrites.setScalar(ref, value);
            return;
        }
        if (old == null) {
            met(SEEN_MISSING);
            if (missingWrite(frame.getArguments(), phpObject, name, value)) {
                return;
            }
        }
        objects.putLong(phpObject, name, value);
        if (old instanceof PhpArray array) {
            met(SEEN_OLD_ARRAY);
            array.removeHolder();
        }
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        assign(frame, value);
    }

    /** `$obj->name = value`: the value stored, the assignment's result. */
    public Object assign(VirtualFrame frame, Object value) {
        String name = name(frame);
        return writeOn(frame.getArguments(), target(frame, name, "assign"), name, value);
    }

    /**
     * `object->name = value`, the object evaluated: an error if it isn't one. The value stored (a typed
     * property's converted one), the assignment's result.
     */
    public Object writeOn(Object[] arguments, Object target, String name, Object value) {
        PhpObject phpObject = target(target, name, "assign");
        int flags = objects.getPropertyFlagsOrDefault(phpObject, name, 0);
        if (!accessible(arguments, phpObject, name, flags) && inaccessibleWrite(arguments, phpObject, name, value)) {
            return value;
        }
        // readonly and set visibility before the type (zend_std_write_property)
        boolean setChecked = (flags & PhpClass.PROPERTY_SET_CHECKED) != 0;
        if (setChecked) {
            phpObject.checkModify(name, scope(arguments), "modify");
        }
        if ((flags & PhpClass.PROPERTY_TYPED) != 0) {
            value = checked(phpObject, name, value);
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpRef ref) {
            met(SEEN_REF);
            refWrites.set(ref, value);
            return value;
        }
        if (old == null) {
            met(SEEN_MISSING);
            if (missingWrite(arguments, phpObject, name, value)) {
                Object stored = typedDecl(phpObject, name) == null ? null : PhpRef.deref(phpObject.getProperty(name));
                return stored == null ? value : stored;
            }
        }
        if (value instanceof Long l) {
            objects.putLong(phpObject, name, l);
        } else {
            if (value instanceof PhpArray array) {
                met(SEEN_NEW_ARRAY);
                array.addHolder();
            }
            objects.put(phpObject, name, value);
        }
        if (old instanceof PhpArray array && old != value) {
            met(SEEN_OLD_ARRAY);
            array.removeHolder();
        }
        if (setChecked) {
            // a readonly property's one write in __clone is taken (IS_PROP_REINITABLE cleared)
            phpObject.unmark("reinit:" + name);
        }
        return value;
    }

    /** {@link #incDecOverflow(PhpObject, String, boolean)} of this site's object and name. */
    public void incDecOverflow(VirtualFrame frame, boolean increment) {
        String name = name(frame);
        incDecOverflow(target(frame, name, "increment/decrement"), name, increment);
    }

    /**
     * `$o->p++` stepping a typed int property's PHP_INT_MAX to a float (zend_incdec_typed_prop): the TypeError,
     * unless the type takes a float.
     */
    @TruffleBoundary
    public static void incDecOverflow(PhpObject phpObject, String name, boolean increment) {
        PhpClass.PropertyDecl decl = typedDecl(phpObject, name);
        if (decl != null && !decl.type().has(PhpType.FLOAT)) {
            throw new PhpError("TypeError", "Cannot " + (increment ? "increment" : "decrement") + " property "
                    + PhpType.declaringName(decl) + "::$" + Str.keyText(name) + " of type " + decl.type().text(null)
                    + " past its " + (increment ? "maximal" : "minimal") + " value");
        }
    }

    // ---- references and in-place updates ----

    /**
     * From a scope that may not access the property, __get's value stands in: a modification through it has no
     * effect, as php-src's "Indirect modification of overloaded property".
     */
    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        String name = name(frame);
        return makeRefOn(frame.getArguments(), target(frame, name, "modify"), name);
    }

    /** makeRef of property name of target, evaluated: an error if it isn't an object. */
    public PhpRef makeRefOn(Object[] arguments, Object target, String name) {
        PhpObject phpObject = target(target, name, "modify");
        if (!accessible(arguments, phpObject, name)) {
            return inaccessibleRef(arguments, phpObject, name);
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpRef ref) {
            return ref;
        }
        if (old == null) {
            PhpRef forwarded = missingRef(arguments, phpObject, name);
            if (forwarded != null) {
                return forwarded;
            }
        }
        if (readonlyIndirect(arguments, phpObject, name, old)) {
            return new PhpRef(old);
        }
        PhpRef ref = new PhpRef(old == null ? PhpNull.NULL : old);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
        ref.bind();
        if (old == null) {
            phpObject.setProperty(name, ref);
        } else {
            objects.put(phpObject, name, ref);
        }
        return ref;
    }

    /**
     * A reference to an uninitialized typed property, which skips __get: one that takes null starts as null.
     * False if the property isn't one (or was unset: __get then answers).
     */
    @TruffleBoundary
    private static boolean uninitializedRef(PhpObject phpObject, String name) {
        PhpClass.PropertyDecl decl = typedDecl(phpObject, name);
        if (decl == null || phpObject.wasUnset(name)) {
            return false;
        }
        if (!decl.type().has(PhpType.NULL)) {
            throw new PhpError("Cannot access uninitialized non-nullable property " + PhpType.declaringName(decl)
                    + "::$" + Str.keyText(name) + " by reference");
        }
        return true;
    }

    /** An uninitialized typed property never unset: its accesses skip the magic methods. */
    @TruffleBoundary
    private static boolean skipsMagic(PhpObject phpObject, String name) {
        return typedDecl(phpObject, name) != null && !phpObject.wasUnset(name);
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        String name = name(frame);
        bindRefOn(frame.getArguments(), writtenObject(frame, VariableVariableNode.W), name, ref);
    }

    /** bindRef of property name of target, evaluated: an error if it isn't an object. */
    public void bindRefOn(Object[] arguments, Object target, String name, PhpRef ref) {
        PhpObject phpObject = target(target, name, "modify");
        if (!accessible(arguments, phpObject, name) || objects.getOrDefault(phpObject, name, null) == null
                && (phpObject.getPhpClass().hasHooks() || phpObject.lazy() != null)) {
            PhpObject forwarded = boundToHooked(arguments, phpObject, name);
            if (forwarded != null) {
                twin().bindRefOn(arguments, forwarded, name, ref);
                return;
            }
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (readonlyIndirect(arguments, phpObject, name, old)) {
            throw phpObject.indirectError(name, scope(arguments));
        }
        if (old instanceof PhpArray array) {
            array.removeHolder();
        } else if (old instanceof PhpRef previous) {
            previous.unbind();
        }
        ref.bind();
        objects.put(phpObject, name, ref);
    }

    /**
     * `$o->name = &$x` the fast path didn't take: a hooked property's and an inaccessible one's Errors; a lazy
     * object's target (lazyTarget) to bind on, else null.
     */
    @TruffleBoundary
    private PhpObject boundToHooked(Object[] arguments, PhpObject phpObject, String name) {
        PhpObject target = lazyTarget(arguments, phpObject, name, "__set");
        if (target != null) {
            return target;
        }
        PhpClass.Hooks hooks = phpObject.getPhpClass().findHooks(name);
        if (hooks != null && phpObject.getPhpClass().canAccessProperty(name, scope(arguments))) {
            // read_property(BP_VAR_W) gives no place to bind; its own Error is the new one's previous in php-src
            try {
                hookedRef(arguments, phpObject, name, hooks);
            } catch (PhpError ignored) {
            }
            throw new PhpError("Cannot assign by reference to overloaded object");
        }
        if (!phpObject.getPhpClass().canAccessProperty(name, scope(arguments))) {
            throw boundToInaccessible(phpObject, name);
        }
        return null;
    }

    @TruffleBoundary
    private static PhpError boundToInaccessible(PhpObject phpObject, String name) {
        return phpObject.getPhpClass().findMethod("__set") != null
                ? new PhpError("Cannot assign by reference to overloaded object") : inaccessible(phpObject, name);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        String name = name(frame);
        return arrayForWriteOn(frame.getArguments(), target(frame, name, "modify"), name);
    }

    /** arrayForWrite of property name of target, evaluated: an error if it isn't an object. */
    public PhpArray arrayForWriteOn(Object[] arguments, Object target, String name) {
        return containerForWriteOn(arguments, target, name) instanceof PhpArray array ? array : null;
    }

    /**
     * The container a write into property name of target goes to (FETCH_OBJ_W): arrayForWriteOn's array, else the
     * object the property holds (or __get gives).
     */
    public Object containerForWriteOn(Object[] arguments, Object target, String name) {
        PhpObject phpObject = target(target, name, "modify");
        if (!accessible(arguments, phpObject, name)) {
            return inaccessibleContainer(arguments, phpObject, name);
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old == null) {
            Object forwarded = missingContainer(arguments, phpObject, name);
            if (forwarded != MISSING) {
                return forwarded;
            }
        }
        if (readonlyIndirect(arguments, phpObject, name, old)) {
            // a readonly or set-restricted property holding an object: the object, not a place in this one
            return PhpRef.deref(old);
        }
        if (old instanceof PhpArray array) {
            if (array.isShared()) {
                PhpArray copy = array.copy();
                copy.addHolder();
                array.removeHolder();
                objects.put(phpObject, name, copy);
                return copy;
            }
            return array;
        }
        if (old instanceof PhpRef ref) {
            return Places.refArrayForWrite(ref);
        }
        if (old == null || old == PhpNull.NULL || old == Boolean.FALSE) {
            if (old == null || typed(phpObject, name)) {
                autoInitialize(phpObject, name);
            }
            PhpArray created = new PhpArray();
            created.addHolder();
            if (old == null) {
                phpObject.setProperty(name, created);
            } else {
                objects.put(phpObject, name, created);
            }
            return created;
        }
        if (old instanceof PhpObject) {
            return old;
        }
        throw scalarAsArray();
    }

    /** What missingContainer returns when the write goes on as for any missing property. */
    private static final Object MISSING = new Object();

    /**
     * The container of a write into a property the object hasn't: a virtual one's, a lazy object's target's (before
     * and after __get, as zend_std_get_property_ptr_ptr), __get's result; else MISSING.
     */
    @TruffleBoundary
    private Object missingContainer(Object[] arguments, PhpObject phpObject, String name) {
        if (missingHooks(arguments, phpObject, name, "__get") != null) {
            return inaccessibleContainer(arguments, phpObject, name);
        }
        PhpObject target = lazyTarget(arguments, phpObject, name, "__get");
        if (target != null) {
            return twin().containerForWriteOn(arguments, target, name);
        }
        if (skipsMagic(phpObject, name)) {
            return MISSING;
        }
        Object magic = magicForWrite(phpObject, name);
        if (magic != PhpObject.NO_MAGIC) {
            return overloadedContainer(magic);
        }
        return phpObject.lazy() == null || phpObject.isInitializingLazy() ? MISSING
                : afterMagic(phpObject, name, "__get", instance -> twin().containerForWriteOn(arguments, instance, name));
    }

    /** A write's container the fast path didn't take: a hooked property's place (hookedRef), else __get's result. */
    @TruffleBoundary
    private Object inaccessibleContainer(Object[] arguments, PhpObject phpObject, String name) {
        PhpClass.Hooks hooks = hooks(arguments, phpObject, name, "__get");
        if (hooks == null) {
            return overloadedContainer(inaccessibleForWrite(phpObject, name));
        }
        PhpRef place = hookedRef(arguments, phpObject, name, hooks);
        return place.get() instanceof PhpObject object ? object : Places.refArrayForWrite(place);
    }

    /**
     * `parent::$name::get()` / `::set($value)` from a hook of the class lexicalClass: the parent's hook on $this, or
     * where the parent's property has none, its trampoline's access to the backing value.
     */
    @TruffleBoundary
    public static Object parentHook(Node site, Object[] arguments, String lexicalClass, String name, boolean set,
                                    Object[] args) {
        PhpClass parent = PhpContext.get(null).findClass(lexicalClass).getParent();
        if (parent == null) {
            throw new PhpError("Cannot use \"parent\" when current class scope has no parent");
        }
        PhpClass.PropertyDecl decl = parent.findProperty(name);
        if (decl == null) {
            throw new PhpError("Undefined property " + parent.getName() + "::$" + Str.keyText(name));
        }
        if (decl.visibility() == PhpClass.PropertyDecl.PRIVATE) {
            throw new PhpError("Cannot access private property " + parent.getName() + "::$" + Str.keyText(name));
        }
        PhpObject self = (PhpObject) arguments[PhpFunction.ARG_THIS];
        PhpClass.Hooks hooks = parent.findHooks(name);
        PhpFunction hook = hooks == null ? null : set ? hooks.set() : hooks.get();
        if (hook != null) {
            return Backtrace.callFrom(site, () -> hook.invoke(self, (PhpClass) arguments[PhpFunction.ARG_STATIC], args));
        }
        String function = parent.getName() + "::$" + Str.keyText(name) + (set ? "::set" : "::get");
        if (args.length != (set ? 1 : 0)) {
            throw new PhpError("ArgumentCountError", function + "() expects exactly " + (set ? 1 : 0) + " argument"
                    + (set ? "" : "s") + ", " + args.length + " given");
        }
        if (set) {
            self.writeRaw(name, args[0]);
            return args[0];
        }
        Object value = PhpRef.deref(self.getProperty(name));
        return value == null ? PhpNull.NULL : value;
    }

    /** `$o->p[] = ...` on a typed property that is null or uninitialized: its type must take an array. */
    @TruffleBoundary
    private static void autoInitialize(PhpObject phpObject, String name) {
        PhpClass.PropertyDecl decl = typedDecl(phpObject, name);
        if (decl != null && !decl.type().has(PhpType.ARRAY)) {
            throw new PhpError("TypeError", "Cannot auto-initialize an array inside property "
                    + PhpType.declaringName(decl) + "::$" + Str.keyText(name) + " of type " + decl.type().text(null));
        }
    }

    /**
     * A write fetch (BP_VAR_W) of a property that doesn't exist, on a class with __get (zend_std_get_property_ptr_ptr
     * gives NULL, the fetch calls read_property(BP_VAR_W)): what __get returns, a reference as it is; another value
     * but an object with php-src's notice, as a temporary the write doesn't reach. NO_MAGIC: __get wasn't called (the
     * class has none, or it is running for this property).
     */
    @TruffleBoundary
    private Object magicForWrite(PhpObject phpObject, String name) {
        Object magic = phpObject.magicGetRef(this, name);
        if (magic != PhpObject.NO_MAGIC && !(magic instanceof PhpRef) && !(magic instanceof PhpObject)) {
            CallNode.notice(this, "Indirect modification of overloaded property " + phpObject.getPhpClass().getName()
                    + "::$" + Str.keyText(name) + " has no effect");
        }
        return magic;
    }

    /** magicForWrite of a property the scope may not access: zend_bad_property_access without __get. */
    @TruffleBoundary
    private Object inaccessibleForWrite(PhpObject phpObject, String name) {
        Object magic = magicForWrite(phpObject, name);
        if (magic == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
        return magic;
    }

    /** What a reference to __get's result binds: its reference, or a temporary's. */
    private static PhpRef magicRef(Object magic) {
        return magic instanceof PhpRef ref ? ref : new PhpRef(magic);
    }

    /** The container a write goes to through __get's result: a reference's array, an object, else a temporary. */
    @TruffleBoundary
    private static Object overloadedContainer(Object value) {
        if (value instanceof PhpRef ref) {
            PhpArray array = Places.refArrayForWrite(ref);
            return array != null ? array : ref.get();
        }
        if (value instanceof PhpObject) {
            return value;
        }
        return value instanceof PhpArray array ? array.copy() : new PhpArray();
    }

    @TruffleBoundary
    private static PhpError scalarAsArray() {
        return new PhpError("Cannot use a scalar value as an array");
    }

    @Override
    public void unset(VirtualFrame frame) {
        Object value = writtenObject(frame, VariableVariableNode.UNSET);
        if (value instanceof PhpObject) {
            unsetOn(frame.getArguments(), value, name(frame));
        }
    }

    /** unset() of property name of value, evaluated (nothing for a value that isn't an object). */
    public void unsetOn(Object[] arguments, Object value, String name) {
        if (value instanceof PhpObject phpObject) {
            if (!writable(arguments, phpObject, name, "unset")) {
                inaccessibleUnset(arguments, phpObject, name);
                return;
            }
            Object old = objects.getOrDefault(phpObject, name, null);
            if (old == null) {
                missingUnset(arguments, phpObject, name);
                return;
            }
            if (old instanceof PhpArray array) {
                array.removeHolder();
            }
            boolean typed = typed(phpObject, name);
            objects.removeKey(phpObject, name);
            if (typed) {
                phpObject.setUnset(name, true);
            }
        }
    }

    /** unset() of a typed property never initialized: marked unset, bypassing __unset. */
    @TruffleBoundary
    private static boolean unsetUninitialized(PhpObject phpObject, String name, String scope) {
        if (typedDecl(phpObject, name) == null || phpObject.wasUnset(name)) {
            return false;
        }
        phpObject.checkSetAccess(name, scope, "unset");
        phpObject.setUnset(name, true);
        return true;
    }

    @TruffleBoundary
    private void inaccessibleUnset(Object[] arguments, PhpObject phpObject, String name) {
        if (hooks(arguments, phpObject, name, "__unset") != null) {
            throw cannotUnsetHooked(phpObject, name);
        }
        if (phpObject.magicProperty(this, "__unset", name) == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
    }

    /** unset() of a property the object hasn't: a virtual one's Error, a lazy object's target's, else __unset(). */
    @TruffleBoundary
    private void missingUnset(Object[] arguments, PhpObject phpObject, String name) {
        if (missingHooks(arguments, phpObject, name, "__unset") != null) {
            throw cannotUnsetHooked(phpObject, name);
        }
        PhpObject target = lazyTarget(arguments, phpObject, name, "__unset");
        if (target != null) {
            twin().unsetOn(arguments, target, name);
            return;
        }
        String scope = scope(arguments);
        if (!phpObject.getPhpClass().canAccessProperty(name, scope)) {
            inaccessibleUnset(arguments, phpObject, name);
        } else if (!unsetUninitialized(phpObject, name, scope)
                && phpObject.magicProperty(this, "__unset", name) == PhpObject.NO_MAGIC
                && afterMagic(phpObject, name, "__unset", instance -> {
                    twin().unsetOn(arguments, instance, name);
                    return true;
                }) == null) {
            // the standard unset's set visibility (a guarded __unset, or none)
            phpObject.checkSetAccess(name, scope, "unset");
        }
    }

    private static PhpError cannotUnsetHooked(PhpObject phpObject, String name) {
        return new PhpError("Cannot unset hooked property " + phpObject.getPhpClass().getName() + "::$"
                + Str.keyText(name));
    }

    // ---- isset ----

    @Override
    public boolean isSet(VirtualFrame frame) {
        Object value = object instanceof LValueNode place ? place.readIfSet(frame) : object.execute(frame);
        return value instanceof PhpObject && isSetOn(frame.getArguments(), value, name(frame));
    }

    /** isset() of property name of value, as readIfSet evaluated it (null when unset). */
    public boolean isSetOn(Object[] arguments, Object value, String name) {
        if (value instanceof PhpObject phpObject) {
            if (!accessible(arguments, phpObject, name)) {
                return slowIsset(arguments, phpObject, name);
            }
            Object property = PhpRef.deref(objects.getOrDefault(phpObject, name, null));
            if (property == null) {
                return slowIsset(arguments, phpObject, name);
            }
            return property != PhpNull.NULL;
        }
        return false;
    }

    /**
     * isset() of a property that doesn't exist, the scope may not access or that has hooks: the hooked value isn't
     * null, else __isset's answer if there is one.
     */
    @TruffleBoundary
    private boolean slowIsset(Object[] arguments, PhpObject phpObject, String name) {
        Object hooked = hookedIfSet(arguments, phpObject, name);
        if (hooked != null) {
            return hooked != PhpNull.NULL;
        }
        PhpObject target = lazyTarget(arguments, phpObject, name, "__isset");
        if (target != null) {
            return twin().isSetOn(arguments, target, name);
        }
        if (phpObject.getProperty(name) == null && typedDecl(phpObject, name) != null && !phpObject.wasUnset(name)) {
            return false;
        }
        Object magic = phpObject.magicProperty(this, "__isset", name);
        if (magic != PhpObject.NO_MAGIC) {
            return Convert.toBool(magic);
        }
        return afterMagic(phpObject, name, "__isset", instance -> twin().isSetOn(arguments, instance, name)) == Boolean.TRUE;
    }

    /** A hooked property's value as isset() reads it (zend_std_has_property's hooked case); null for another. */
    private Object hookedIfSet(Object[] arguments, PhpObject phpObject, String name) {
        PhpClass phpClass = phpObject.getPhpClass();
        PhpClass.Hooks hooks = phpClass.findHooks(name);
        if (hooks == null || !phpClass.canAccessProperty(name, scope(arguments))) {
            return null;
        }
        return hookedRead(arguments, phpObject, name, hooks);
    }

    @TruffleBoundary
    private boolean magicIsset(PhpObject phpObject, String name) {
        Object magic = phpObject.magicProperty(this, "__isset", name);
        return magic != PhpObject.NO_MAGIC && Convert.toBool(magic);
    }

    @Override
    public Object readIfSet(VirtualFrame frame) {
        Object value = object instanceof LValueNode place ? place.readIfSet(frame) : object.execute(frame);
        return value instanceof PhpObject ? readIfSetOn(frame.getArguments(), value, name(frame)) : null;
    }

    /** readIfSet of property name of value, as readIfSet evaluated it (null when unset). */
    public Object readIfSetOn(Object[] arguments, Object value, String name) {
        if (value instanceof PhpObject phpObject) {
            Object property;
            if (!accessible(arguments, phpObject, name)) {
                property = null;
            } else {
                property = PhpRef.deref(objects.getOrDefault(phpObject, name, null));
            }
            if (property == null) {
                property = readIfSetSlow(arguments, phpObject, name);
                if (property == null) {
                    return null;
                }
            }
            return property == PhpNull.NULL ? null : property;
        }
        return null;
    }

    /** readIfSet's slow path: a hooked property's value, else __isset decides and __get gives it; null when unset. */
    @TruffleBoundary
    private Object readIfSetSlow(Object[] arguments, PhpObject phpObject, String name) {
        Object hooked = hookedIfSet(arguments, phpObject, name);
        if (hooked != null) {
            return hooked;
        }
        PhpObject target = lazyTarget(arguments, phpObject, name, "__isset");
        if (target != null) {
            return twin().readIfSetOn(arguments, target, name);
        }
        // an uninitialized typed property is unset
        if (phpObject.getProperty(name) == null && typedDecl(phpObject, name) != null && !phpObject.wasUnset(name)) {
            return null;
        }
        // isset($o->x[...]) / $o->x ?? ... (read_property's BP_VAR_IS): __isset decides, then __get gives the value;
        // without __isset, __get's
        Object magic = phpObject.magicProperty(this, "__isset", name);
        if (magic != PhpObject.NO_MAGIC) {
            return Convert.toBool(magic) ? missing(arguments, phpObject, name) : null;
        }
        PhpClass phpClass = phpObject.getPhpClass();
        if (phpClass.findMethod("__get") != null && !phpObject.inMagic("__get", name)) {
            return missing(arguments, phpObject, name);
        }
        return afterMagic(phpObject, name, phpClass.findMethod("__isset") != null ? "__isset" : "__get",
                instance -> twin().readIfSetOn(arguments, instance, name));
    }

    @Override
    public boolean isPure() {
        boolean objectPure = object instanceof ThisNode || object instanceof LValueNode place && place.isPure();
        return objectPure && (nameExpr == null || nameExpr instanceof LocalVarNode);
    }
}
