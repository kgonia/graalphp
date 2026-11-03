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
                DynamicObjectLibrary.getFactory().createDispatched(4));
    }

    private PropNode(ExprNode object, String constantName, ExprNode nameExpr, String lexicalClass,
                     boolean scopeInFrame, boolean thisInMethod, DynamicObjectLibrary objects) {
        this.object = object;
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
     * tree's (`$this->name` in a method); uncached for the uncached interpreter, which doesn't adopt the node.
     */
    public static PropNode forOperands(String constantName, String lexicalClass, boolean scopeInFrame,
                                       boolean thisInMethod, boolean uncached) {
        return new PropNode(null, constantName, null, lexicalClass, scopeInFrame, thisInMethod,
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

    /** A place as that fetch fetches it; a property read as a read does (FETCH_OBJ_W falls back to __get, no __isset). */
    static Object written(VirtualFrame frame, ExprNode node, int fetch) {
        if (node instanceof VariableVariableNode variable) {
            return variable.fetch(frame, fetch);
        }
        if (node instanceof DimNode dim) {
            return dim.writtenIn(frame, written(frame, dim.container(), fetch));
        }
        if (node instanceof LValueNode place && !(node instanceof PropNode)) {
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

    private static final int VISIBILITY = PhpClass.PROPERTY_PROTECTED | PhpClass.PROPERTY_PRIVATE;

    /** Whether the calling scope may access the property: public and undeclared ones need no check. */
    private boolean accessible(VirtualFrame frame, PhpObject phpObject, String name) {
        int flags = objects.getPropertyFlagsOrDefault(phpObject, name, 0) & VISIBILITY;
        if (flags == 0 || thisInMethod && flags == PhpClass.PROPERTY_PROTECTED) {
            return true;
        }
        Object scope = scopeInFrame ? frame.getArguments()[PhpFunction.ARG_CLOSURE] : null;
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

    /** Reading a property the scope may not access: __get's result, else zend_bad_property_access. */
    @TruffleBoundary
    private Object inaccessibleRead(PhpObject phpObject, String name) {
        Object magic = phpObject.magicProperty(this, "__get", name);
        if (magic == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
        return magic;
    }

    @TruffleBoundary
    private void inaccessibleWrite(PhpObject phpObject, String name, Object value) {
        if (phpObject.magicProperty(this, "__set", name, value) == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
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
            if (!accessible(frame, phpObject, name)) {
                throw new UnexpectedResultException(inaccessibleRead(phpObject, name));
            }
            try {
                return objects.getLongOrDefault(phpObject, name, ABSENT);
            } catch (UnexpectedResultException e) {
                Object result = e.getResult();
                throw new UnexpectedResultException(result == ABSENT ? missing(frame, phpObject, name)
                        : PhpRef.deref(result));
            }
        }
        throw new UnexpectedResultException(read(frame, value, name(frame)));
    }

    /** The default of a property lookup: the property doesn't exist (declared ones exist until unset). */
    private static final Object ABSENT = new Object();

    /**
     * Reading a property that doesn't exist: an uninitialized typed property's Error (from a scope that may access
     * it), else __get's result if the class has one, else null.
     */
    private Object missing(VirtualFrame frame, PhpObject phpObject, String name) {
        return missing(phpObject, name, callingScope(frame, lexicalClass));
    }

    @TruffleBoundary
    private Object missing(PhpObject phpObject, String name, String scope) {
        PhpClass.PropertyDecl decl = typedDecl(phpObject, name);
        if (decl != null && !phpObject.getPhpClass().canAccessProperty(name, scope)) {
            return inaccessibleRead(phpObject, name);
        }
        Object magic = decl == null || phpObject.wasUnset(name) ? phpObject.magicProperty(this, "__get", name)
                : PhpObject.NO_MAGIC;
        if (magic == PhpObject.NO_MAGIC && decl != null) {
            throw new PhpError("Typed property " + PhpType.declaringName(decl) + "::$" + Str.keyText(name)
                    + " must not be accessed before initialization");
        }
        return magic == PhpObject.NO_MAGIC ? PhpNull.NULL : magic;
    }

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
        return read(frame, value, name(frame));
    }

    /**
     * A read of property name of value as an operand (ZEND_FETCH_OBJ_R): readOn, and for a value that isn't an
     * object null after php-src's warning (zend_wrong_property_read).
     */
    public Object read(VirtualFrame frame, Object value, String name) {
        if (!(value instanceof PhpObject)) {
            met(SEEN_NON_OBJECT);
            nonObjectRead(this, value, name);
            return PhpNull.NULL;
        }
        return readOn(frame, value, name);
    }

    @TruffleBoundary
    private static void nonObjectRead(Node site, Object value, String name) {
        ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_WARNING, Str.of("Attempt to read property \""
                + Str.keyText(name) + "\" on " + Args.valueName(value)), SourceLocation.of(site));
    }

    /** The read of property name of value, evaluated (null for a value that isn't an object). */
    public Object readOn(VirtualFrame frame, Object value, String name) {
        if (value instanceof PhpObject phpObject) {
            if (!accessible(frame, phpObject, name)) {
                return inaccessibleRead(phpObject, name);
            }
            Object property = objects.getOrDefault(phpObject, name, null);
            if (property instanceof PhpRef ref) {
                return ref.get();
            }
            return property == null ? missing(frame, phpObject, name) : property;
        }
        return PhpNull.NULL;
    }

    // ---- writes ----

    /** Stores an int unboxed (a long location), unless the property is a reference. */
    @Override
    public void writeLong(VirtualFrame frame, long value) {
        String name = name(frame);
        PhpObject phpObject = target(frame, name, "assign");
        if (typed(phpObject, name)) {
            writeOn(frame, phpObject, name, value);
            return;
        }
        if (!accessible(frame, phpObject, name)) {
            inaccessibleWrite(phpObject, name, value);
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
            if (initialize(phpObject, name, value, callingScope(frame, lexicalClass))
                    || phpObject.magicProperty(this, "__set", name, value) != PhpObject.NO_MAGIC) {
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
        return writeOn(frame, target(frame, name, "assign"), name, value);
    }

    /**
     * `object->name = value`, the object evaluated: an error if it isn't one. The value stored (a typed
     * property's converted one), the assignment's result.
     */
    public Object writeOn(VirtualFrame frame, Object target, String name, Object value) {
        PhpObject phpObject = target(target, name, "assign");
        if (!accessible(frame, phpObject, name)) {
            inaccessibleWrite(phpObject, name, value);
            return value;
        }
        if (typed(phpObject, name)) {
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
            if (initialize(phpObject, name, value, callingScope(frame, lexicalClass))) {
                return PhpRef.deref(objects.getOrDefault(phpObject, name, null));
            }
            if (phpObject.magicProperty(this, "__set", name, value) != PhpObject.NO_MAGIC) {
                return value;
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
        return makeRefOn(frame, target(frame, name, "modify"), name);
    }

    /** makeRef of property name of target, evaluated: an error if it isn't an object. */
    public PhpRef makeRefOn(VirtualFrame frame, Object target, String name) {
        PhpObject phpObject = target(target, name, "modify");
        if (!accessible(frame, phpObject, name)) {
            return magicRef(inaccessibleForWrite(phpObject, name));
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpRef ref) {
            return ref;
        }
        if (old == null) {
            if (!uninitializedRef(phpObject, name)) {
                Object magic = magicForWrite(phpObject, name);
                if (magic != PhpObject.NO_MAGIC) {
                    return magicRef(magic);
                }
            }
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
        bindRefOn(frame, writtenObject(frame, VariableVariableNode.W), name, ref);
    }

    /** bindRef of property name of target, evaluated: an error if it isn't an object. */
    public void bindRefOn(VirtualFrame frame, Object target, String name, PhpRef ref) {
        PhpObject phpObject = target(target, name, "modify");
        if (!accessible(frame, phpObject, name)) {
            throw boundToInaccessible(phpObject, name);
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        } else if (old instanceof PhpRef previous) {
            previous.unbind();
        }
        ref.bind();
        objects.put(phpObject, name, ref);
    }

    @TruffleBoundary
    private static PhpError boundToInaccessible(PhpObject phpObject, String name) {
        return phpObject.getPhpClass().findMethod("__set") != null
                ? new PhpError("Cannot assign by reference to overloaded object") : inaccessible(phpObject, name);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        String name = name(frame);
        return arrayForWriteOn(frame, target(frame, name, "modify"), name);
    }

    /** arrayForWrite of property name of target, evaluated: an error if it isn't an object. */
    public PhpArray arrayForWriteOn(VirtualFrame frame, Object target, String name) {
        return containerForWriteOn(frame, target, name) instanceof PhpArray array ? array : null;
    }

    /**
     * The container a write into property name of target goes to (FETCH_OBJ_W): arrayForWriteOn's array, else the
     * object the property holds (or __get gives).
     */
    public Object containerForWriteOn(VirtualFrame frame, Object target, String name) {
        PhpObject phpObject = target(target, name, "modify");
        if (!accessible(frame, phpObject, name)) {
            return overloadedContainer(inaccessibleForWrite(phpObject, name));
        }
        Object old = objects.getOrDefault(phpObject, name, null);
        if (old == null && !skipsMagic(phpObject, name)) {
            Object magic = magicForWrite(phpObject, name);
            if (magic != PhpObject.NO_MAGIC) {
                return overloadedContainer(magic);
            }
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
            unsetOn(frame, value, name(frame));
        }
    }

    /** unset() of property name of value, evaluated (nothing for a value that isn't an object). */
    public void unsetOn(VirtualFrame frame, Object value, String name) {
        if (value instanceof PhpObject phpObject) {
            if (!accessible(frame, phpObject, name)) {
                inaccessibleUnset(phpObject, name);
                return;
            }
            Object old = objects.getOrDefault(phpObject, name, null);
            if (old == null) {
                if (!unsetUninitialized(phpObject, name)) {
                    phpObject.magicProperty(this, "__unset", name);
                }
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
    private static boolean unsetUninitialized(PhpObject phpObject, String name) {
        if (typedDecl(phpObject, name) == null || phpObject.wasUnset(name)) {
            return false;
        }
        phpObject.setUnset(name, true);
        return true;
    }

    @TruffleBoundary
    private void inaccessibleUnset(PhpObject phpObject, String name) {
        if (phpObject.magicProperty(this, "__unset", name) == PhpObject.NO_MAGIC) {
            throw inaccessible(phpObject, name);
        }
    }

    // ---- isset ----

    @Override
    public boolean isSet(VirtualFrame frame) {
        Object value = object instanceof LValueNode place ? place.readIfSet(frame) : object.execute(frame);
        return value instanceof PhpObject && isSetOn(frame, value, name(frame));
    }

    /** isset() of property name of value, as readIfSet evaluated it (null when unset). */
    public boolean isSetOn(VirtualFrame frame, Object value, String name) {
        if (value instanceof PhpObject phpObject) {
            if (!accessible(frame, phpObject, name)) {
                return magicIsset(phpObject, name);
            }
            Object property = PhpRef.deref(objects.getOrDefault(phpObject, name, null));
            if (property == null) {
                return (typedDecl(phpObject, name) == null || phpObject.wasUnset(name)) && magicIsset(phpObject, name);
            }
            return property != PhpNull.NULL;
        }
        return false;
    }

    /** isset() of a property that doesn't exist or the scope may not access: __isset's answer if there is one. */
    @TruffleBoundary
    private boolean magicIsset(PhpObject phpObject, String name) {
        Object magic = phpObject.magicProperty(this, "__isset", name);
        return magic != PhpObject.NO_MAGIC && Convert.toBool(magic);
    }

    @Override
    public Object readIfSet(VirtualFrame frame) {
        Object value = object instanceof LValueNode place ? place.readIfSet(frame) : object.execute(frame);
        return value instanceof PhpObject ? readIfSetOn(frame, value, name(frame)) : null;
    }

    /** readIfSet of property name of value, as readIfSet evaluated it (null when unset). */
    public Object readIfSetOn(VirtualFrame frame, Object value, String name) {
        if (value instanceof PhpObject phpObject) {
            Object property;
            if (!accessible(frame, phpObject, name)) {
                property = null;
            } else {
                property = PhpRef.deref(objects.getOrDefault(phpObject, name, null));
            }
            if (property == null) {
                // isset($o->x[...]) / $o->x ?? ...: __isset decides, then __get gives the value; an uninitialized
                // typed property is unset
                if (typedDecl(phpObject, name) != null && !phpObject.wasUnset(name) || !magicIsset(phpObject, name)) {
                    return null;
                }
                property = missing(frame, phpObject, name);
            }
            return property == PhpNull.NULL ? null : property;
        }
        return null;
    }

    @Override
    public boolean isPure() {
        boolean objectPure = object instanceof ThisNode || object instanceof LValueNode place && place.isPure();
        return objectPure && (nameExpr == null || nameExpr instanceof LocalVarNode);
    }
}
