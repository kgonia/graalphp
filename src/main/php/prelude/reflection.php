<?php
// ext/reflection's classes, written in PHP: Truffle PHP's prelude (see spl.php). They read the runtime through
// functions in namespace Truffle (builtins/ReflectionFunctions.java): classes, functions, methods and their
// parameters as declared. The classes have php-src's public members only; what an object reflects is kept in
// TruffleReflection's table, keyed by object id, so var_dump(), print_r() and method_exists() see what they see on
// php-src. The built-ins are declared without php-src's arginfo, so their parameters can't be reflected
// (getParameters() throws). Not here yet: properties, class constants as objects, attributes, enums, __toString()
// of everything but types (they throw).

/** The Reflection objects' state and the helpers they share. */
final class TruffleReflection
{
    /** Object id to what the object reflects. */
    public static array $state = [];

    private const BUILTIN = ['int', 'float', 'string', 'bool', 'array', 'callable', 'iterable', 'object', 'mixed',
        'void', 'null', 'never', 'false', 'true'];

    public static function of(object $reflection): array
    {
        return self::$state[spl_object_id($reflection)];
    }

    public static function notImplemented(string $method): never
    {
        throw new Error("Truffle PHP doesn't implement $method yet");
    }

    /** A declared type (zend_type_to_string's text), with self and parent as the declaring class's. */
    public static function type(?string $type, ?string $self, ?string $parent): ?ReflectionType
    {
        if ($type === null) {
            return null;
        }
        if ($type[0] === '?') {
            return self::named(self::resolve(substr($type, 1), $self, $parent), true);
        }
        if (str_contains($type, '|')) {
            $union = \Truffle\reflection_new(ReflectionUnionType::class);
            $types = [];
            $text = [];
            $null = false;
            foreach (explode('|', $type) as $part) {
                if ($part[0] === '(') {
                    $types[] = self::intersection(trim($part, '()'), $self, $parent);
                    $text[] = $part;
                    continue;
                }
                $name = self::resolve($part, $self, $parent);
                $null = $null || $name === 'null';
                $types[] = self::named($name, $name === 'null');
                $text[] = $name;
            }
            self::$state[spl_object_id($union)] = ['types' => $types, 'null' => $null, 'text' => implode('|', $text)];
            return $union;
        }
        if (str_contains($type, '&')) {
            return self::intersection($type, $self, $parent);
        }
        $name = self::resolve($type, $self, $parent);
        return self::named($name, $name === 'null' || $name === 'mixed');
    }

    private static function resolve(string $name, ?string $self, ?string $parent): string
    {
        $lower = strtolower($name);
        return $lower === 'self' && $self !== null ? $self : ($lower === 'parent' && $parent !== null ? $parent : $name);
    }

    private static function named(string $name, bool $null): ReflectionNamedType
    {
        $type = \Truffle\reflection_new(ReflectionNamedType::class);
        $text = $null && $name !== 'null' && $name !== 'mixed' ? "?$name" : $name;
        self::$state[spl_object_id($type)] = ['name' => $name, 'null' => $null, 'text' => $text,
            'builtin' => in_array(strtolower($name), self::BUILTIN, true)];
        return $type;
    }

    private static function intersection(string $text, ?string $self, ?string $parent): ReflectionIntersectionType
    {
        $type = \Truffle\reflection_new(ReflectionIntersectionType::class);
        $types = [];
        $names = [];
        foreach (explode('&', $text) as $part) {
            $names[] = $name = self::resolve($part, $self, $parent);
            $types[] = self::named($name, false);
        }
        self::$state[spl_object_id($type)] = ['types' => $types, 'null' => false, 'text' => implode('&', $names)];
        return $type;
    }

    public static function shortName(string $name): string
    {
        $separator = strrpos($name, '\\');
        return $separator === false ? $name : substr($name, $separator + 1);
    }

    public static function namespaceName(string $name): string
    {
        $separator = strrpos($name, '\\');
        return $separator === false ? '' : substr($name, 0, $separator);
    }

    /** ReflectionMethod::__construct's lookup: the method's description, or a ReflectionException. */
    public static function findMethod(object|string $objectOrMethod, ?string $method): array
    {
        if (is_object($objectOrMethod)) {
            if ($method === null) {
                throw new ValueError('ReflectionMethod::__construct(): Argument #2 ($method) cannot be null when '
                    . 'argument #1 ($objectOrMethod) is an object');
            }
            $class = get_class($objectOrMethod);
        } elseif ($method !== null) {
            $class = $objectOrMethod;
        } else {
            $separator = strpos($objectOrMethod, '::');
            if ($separator === false) {
                throw new ReflectionException('ReflectionMethod::__construct(): Argument #1 ($objectOrMethod) must '
                    . 'be a valid method name');
            }
            $class = substr($objectOrMethod, 0, $separator);
            $method = substr($objectOrMethod, $separator + 2);
        }
        $classInfo = \Truffle\reflection_class($class);
        if ($classInfo === false) {
            throw new ReflectionException("Class \"$class\" does not exist");
        }
        $info = \Truffle\reflection_method($classInfo['name'], $method);
        if ($info === false) {
            throw new ReflectionException("Method {$classInfo['name']}::$method() does not exist");
        }
        return self::scoped($info, $classInfo['name']);
    }

    /** A method's description with the class it was looked up from (invoke's called scope), self and parent. */
    private static function scoped(array $info, string $lookedUp): array
    {
        $info['lookedUp'] = $lookedUp;
        $info['self'] = $info['class'];
        $declaring = \Truffle\reflection_class($info['class'], false);
        $info['parent'] = $declaring === false || $declaring['parent'] === false ? null : $declaring['parent'];
        return $info;
    }

    /** A ReflectionMethod for a method ReflectionClass found. */
    public static function method(array $info, string $lookedUp): ReflectionMethod
    {
        $method = \Truffle\reflection_new(ReflectionMethod::class);
        self::$state[spl_object_id($method)] = self::scoped($info, $lookedUp);
        $method->name = $info['name'];
        $method->class = $info['class'];
        return $method;
    }

    /** The parameters' descriptions; the built-ins have none to give. */
    public static function params(ReflectionFunctionAbstract $function): array
    {
        $info = self::of($function);
        if ($info['params'] === null) {
            throw new Error("Truffle PHP has no parameter information for the built-in {$info['name']}()");
        }
        return $info['params'];
    }

    public static function parameter(ReflectionFunctionAbstract $function, int $position, array $param): ReflectionParameter
    {
        $parameter = \Truffle\reflection_new(ReflectionParameter::class);
        self::$state[spl_object_id($parameter)] = ['function' => $function, 'position' => $position] + $param;
        $parameter->name = $param['name'];
        return $parameter;
    }

    /** ReflectionClass::newInstance's checks of the constructor. */
    public static function constructible(string $name, array $args): void
    {
        $constructor = \Truffle\reflection_method($name, '__construct');
        if ($constructor !== false && ($constructor['modifiers'] & ReflectionMethod::IS_PUBLIC) === 0) {
            throw new ReflectionException("Access to non-public constructor of class $name");
        }
        if ($constructor === false && $args !== []) {
            throw new ReflectionException("Class $name does not have a constructor, so you cannot pass any constructor "
                . 'arguments');
        }
    }

    /** ReflectionMethod::invoke's checks: the object it is called on, or null for a static method. */
    public static function invokedOn(ReflectionMethod $method, ?object $object): ?object
    {
        $info = self::of($method);
        if (($info['modifiers'] & ReflectionMethod::IS_ABSTRACT) !== 0) {
            throw new ReflectionException("Trying to invoke abstract method {$info['class']}::{$info['name']}()");
        }
        if (($info['modifiers'] & ReflectionMethod::IS_STATIC) !== 0) {
            return null;
        }
        if ($object === null) {
            throw new ReflectionException("Trying to invoke non static method {$info['class']}::{$info['name']}() "
                . 'without an object');
        }
        if (!($object instanceof $info['class'])) {
            throw new ReflectionException('Given object is not an instance of the class this method was declared in');
        }
        return $object;
    }
}

interface Reflector extends Stringable
{
}

class ReflectionException extends Exception
{
}

abstract class ReflectionType implements Stringable
{
    public function allowsNull(): bool
    {
        return TruffleReflection::of($this)['null'];
    }

    public function __toString(): string
    {
        return TruffleReflection::of($this)['text'];
    }
}

class ReflectionNamedType extends ReflectionType
{
    public function getName(): string
    {
        return TruffleReflection::of($this)['name'];
    }

    public function isBuiltin(): bool
    {
        return TruffleReflection::of($this)['builtin'];
    }
}

class ReflectionUnionType extends ReflectionType
{
    public function getTypes(): array
    {
        return TruffleReflection::of($this)['types'];
    }
}

class ReflectionIntersectionType extends ReflectionType
{
    public function getTypes(): array
    {
        return TruffleReflection::of($this)['types'];
    }
}

abstract class ReflectionFunctionAbstract implements Reflector
{
    public string $name = '';

    public function getName(): string
    {
        return TruffleReflection::of($this)['name'];
    }

    public function getShortName(): string
    {
        return TruffleReflection::shortName(TruffleReflection::of($this)['name']);
    }

    public function getNamespaceName(): string
    {
        return TruffleReflection::namespaceName(TruffleReflection::of($this)['name']);
    }

    public function inNamespace(): bool
    {
        return str_contains(TruffleReflection::of($this)['name'], '\\');
    }

    public function isClosure(): bool
    {
        return TruffleReflection::of($this)['closure'] ?? false;
    }

    public function isInternal(): bool
    {
        return TruffleReflection::of($this)['internal'];
    }

    public function isUserDefined(): bool
    {
        return !TruffleReflection::of($this)['internal'];
    }

    public function isDeprecated(): bool
    {
        return false;
    }

    public function isStatic(): bool
    {
        return TruffleReflection::of($this)['static'];
    }

    public function getFileName(): string|false
    {
        return TruffleReflection::of($this)['file'];
    }

    public function getStartLine(): int|false
    {
        return TruffleReflection::of($this)['startLine'];
    }

    public function getEndLine(): int|false
    {
        return TruffleReflection::of($this)['endLine'];
    }

    public function getDocComment(): string|false
    {
        return TruffleReflection::of($this)['docComment'];
    }

    public function returnsReference(): bool
    {
        return TruffleReflection::of($this)['returnsRef'];
    }

    public function hasReturnType(): bool
    {
        return TruffleReflection::of($this)['returnType'] !== null;
    }

    public function getReturnType(): ?ReflectionType
    {
        $info = TruffleReflection::of($this);
        return TruffleReflection::type($info['returnType'], $info['self'] ?? null, $info['parent'] ?? null);
    }

    public function getClosureThis(): ?object
    {
        return TruffleReflection::of($this)['this'] ?? null;
    }

    public function getClosureScopeClass(): ?ReflectionClass
    {
        $scope = TruffleReflection::of($this)['scope'] ?? null;
        return $scope === null ? null : new ReflectionClass($scope);
    }

    public function getClosureCalledClass(): ?ReflectionClass
    {
        $info = TruffleReflection::of($this);
        $called = isset($info['this']) ? get_class($info['this']) : $info['scope'] ?? null;
        return $called === null ? null : new ReflectionClass($called);
    }

    public function getNumberOfParameters(): int
    {
        return count(TruffleReflection::params($this));
    }

    public function getNumberOfRequiredParameters(): int
    {
        TruffleReflection::params($this);
        return TruffleReflection::of($this)['required'];
    }

    public function isVariadic(): bool
    {
        $params = TruffleReflection::params($this);
        return $params !== [] && $params[count($params) - 1]['variadic'];
    }

    /** @return list<ReflectionParameter> */
    public function getParameters(): array
    {
        $result = [];
        foreach (TruffleReflection::params($this) as $position => $param) {
            $result[] = TruffleReflection::parameter($this, $position, $param);
        }
        return $result;
    }

    public function __toString(): string
    {
        TruffleReflection::notImplemented(static::class . '::__toString()');
    }
}

class ReflectionFunction extends ReflectionFunctionAbstract
{
    public function __construct(Closure|string $function)
    {
        $info = \Truffle\reflection_function($function);
        if ($info === false) {
            throw new ReflectionException("Function $function() does not exist");
        }
        if ($function instanceof Closure) {
            $info['object'] = $function;
            $info['self'] = $info['scope'];
            $info['parent'] = $info['scope'] === null ? null : (get_parent_class($info['scope']) ?: null);
        }
        TruffleReflection::$state[spl_object_id($this)] = $info;
        $this->name = $info['name'];
    }

    public function isAnonymous(): bool
    {
        return TruffleReflection::of($this)['closure'];
    }

    public function invoke(mixed ...$args): mixed
    {
        $info = TruffleReflection::of($this);
        return ($info['object'] ?? $info['name'])(...$args);
    }

    public function invokeArgs(array $args = []): mixed
    {
        $info = TruffleReflection::of($this);
        return ($info['object'] ?? $info['name'])(...$args);
    }

    public function getClosure(): Closure
    {
        $info = TruffleReflection::of($this);
        return $info['object'] ?? Closure::fromCallable($info['name']);
    }
}

class ReflectionMethod extends ReflectionFunctionAbstract
{
    const IS_STATIC = 16;
    const IS_PUBLIC = 1;
    const IS_PROTECTED = 2;
    const IS_PRIVATE = 4;
    const IS_ABSTRACT = 64;
    const IS_FINAL = 32;

    public string $class = '';

    public function __construct(object|string $objectOrMethod, ?string $method = null)
    {
        if (func_num_args() === 1) {
            \Truffle\reflection_raise(E_DEPRECATED, 'Calling ReflectionMethod::__construct() with 1 argument is '
                . 'deprecated, use ReflectionMethod::createFromMethodName() instead');
        }
        $info = TruffleReflection::findMethod($objectOrMethod, $method);
        TruffleReflection::$state[spl_object_id($this)] = $info;
        $this->name = $info['name'];
        $this->class = $info['class'];
    }

    public static function createFromMethodName(string $method): static
    {
        $info = TruffleReflection::findMethod($method, null);
        $reflection = \Truffle\reflection_new(static::class);
        TruffleReflection::$state[spl_object_id($reflection)] = $info;
        $reflection->name = $info['name'];
        $reflection->class = $info['class'];
        return $reflection;
    }

    public function getModifiers(): int
    {
        return TruffleReflection::of($this)['modifiers'];
    }

    public function isPublic(): bool
    {
        return (TruffleReflection::of($this)['modifiers'] & self::IS_PUBLIC) !== 0;
    }

    public function isPrivate(): bool
    {
        return (TruffleReflection::of($this)['modifiers'] & self::IS_PRIVATE) !== 0;
    }

    public function isProtected(): bool
    {
        return (TruffleReflection::of($this)['modifiers'] & self::IS_PROTECTED) !== 0;
    }

    public function isAbstract(): bool
    {
        return (TruffleReflection::of($this)['modifiers'] & self::IS_ABSTRACT) !== 0;
    }

    public function isFinal(): bool
    {
        return (TruffleReflection::of($this)['modifiers'] & self::IS_FINAL) !== 0;
    }

    public function isStatic(): bool
    {
        return (TruffleReflection::of($this)['modifiers'] & self::IS_STATIC) !== 0;
    }

    public function isConstructor(): bool
    {
        return strtolower(TruffleReflection::of($this)['name']) === '__construct';
    }

    public function isDestructor(): bool
    {
        return strtolower(TruffleReflection::of($this)['name']) === '__destruct';
    }

    public function getDeclaringClass(): ReflectionClass
    {
        return new ReflectionClass(TruffleReflection::of($this)['class']);
    }

    public function setAccessible(bool $accessible): void
    {
        \Truffle\reflection_raise(E_DEPRECATED, 'Method ReflectionMethod::setAccessible() is deprecated since 8.5, '
            . 'as it has no effect since PHP 8.1');
    }

    public function invoke(?object $object = null, mixed ...$args): mixed
    {
        $info = TruffleReflection::of($this);
        return \Truffle\reflection_call(TruffleReflection::invokedOn($this, $object), $info['lookedUp'], $info['name'],
            $args);
    }

    public function invokeArgs(?object $object = null, array $args = []): mixed
    {
        $info = TruffleReflection::of($this);
        return \Truffle\reflection_call(TruffleReflection::invokedOn($this, $object), $info['lookedUp'], $info['name'],
            $args);
    }

    public function getClosure(?object $object = null): Closure
    {
        $info = TruffleReflection::of($this);
        if (($info['modifiers'] & self::IS_STATIC) === 0) {
            if ($object === null) {
                throw new ValueError('ReflectionMethod::getClosure(): Argument #1 ($object) cannot be null for '
                    . 'non-static methods');
            }
            if (!($object instanceof $info['class'])) {
                throw new ReflectionException('Given object is not an instance of the class this method was declared '
                    . 'in');
            }
        }
        return \Truffle\reflection_closure($object, $info['lookedUp'], $info['name']);
    }
}

class ReflectionParameter implements Reflector
{
    public string $name = '';

    public function __construct($function, int|string $param)
    {
        if (is_string($function) && str_contains($function, '::')) {
            $reflection = new ReflectionMethod(...explode('::', $function, 2));
        } elseif (is_array($function)) {
            $reflection = new ReflectionMethod($function[0], $function[1]);
        } elseif ($function instanceof Closure || is_string($function)) {
            $reflection = new ReflectionFunction($function);
        } else {
            throw new ReflectionException('Expected array($object, $method) or array($classname, $method)');
        }
        if (is_int($param) && $param < 0) {
            throw new ValueError('ReflectionParameter::__construct(): Argument #2 ($param) must be greater than or '
                . 'equal to 0');
        }
        foreach (TruffleReflection::params($reflection) as $position => $info) {
            if (is_int($param) ? $position === $param : $info['name'] === $param) {
                TruffleReflection::$state[spl_object_id($this)] = ['function' => $reflection, 'position' => $position]
                    + $info;
                $this->name = $info['name'];
                return;
            }
        }
        throw new ReflectionException(is_int($param) ? 'The parameter specified by its offset could not be found'
            : 'The parameter specified by its name could not be found');
    }

    public function getName(): string
    {
        return TruffleReflection::of($this)['name'];
    }

    public function getPosition(): int
    {
        return TruffleReflection::of($this)['position'];
    }

    public function isOptional(): bool
    {
        return TruffleReflection::of($this)['optional'];
    }

    public function isDefaultValueAvailable(): bool
    {
        return TruffleReflection::of($this)['hasDefault'];
    }

    public function isPassedByReference(): bool
    {
        return TruffleReflection::of($this)['byRef'];
    }

    public function canBePassedByValue(): bool
    {
        return !TruffleReflection::of($this)['byRef'];
    }

    public function isVariadic(): bool
    {
        return TruffleReflection::of($this)['variadic'];
    }

    public function hasType(): bool
    {
        return TruffleReflection::of($this)['type'] !== null;
    }

    public function getType(): ?ReflectionType
    {
        $info = TruffleReflection::of($this);
        $function = TruffleReflection::of($info['function']);
        return TruffleReflection::type($info['type'], $function['self'] ?? null, $function['parent'] ?? null);
    }

    public function allowsNull(): bool
    {
        $type = $this->getType();
        return $type === null || $type->allowsNull();
    }

    public function getDeclaringFunction(): ReflectionFunctionAbstract
    {
        return TruffleReflection::of($this)['function'];
    }

    public function getDeclaringClass(): ?ReflectionClass
    {
        $function = TruffleReflection::of($this)['function'];
        if ($function instanceof ReflectionMethod) {
            return new ReflectionClass($function->class);
        }
        $scope = TruffleReflection::of($function)['scope'] ?? null;
        return $scope === null ? null : new ReflectionClass($scope);
    }

    public function __toString(): string
    {
        TruffleReflection::notImplemented('ReflectionParameter::__toString()');
    }
}

class ReflectionClass implements Reflector
{
    const IS_IMPLICIT_ABSTRACT = 16;
    const IS_EXPLICIT_ABSTRACT = 64;
    const IS_FINAL = 32;
    const IS_READONLY = 65536;

    public string $name = '';

    public function __construct(object|string $objectOrClass)
    {
        $info = \Truffle\reflection_class($objectOrClass);
        if ($info === false) {
            throw new ReflectionException("Class \"$objectOrClass\" does not exist", -1);
        }
        TruffleReflection::$state[spl_object_id($this)] = $info;
        $this->name = $info['name'];
    }

    public function getName(): string
    {
        return TruffleReflection::of($this)['name'];
    }

    public function getShortName(): string
    {
        return TruffleReflection::shortName(TruffleReflection::of($this)['name']);
    }

    public function getNamespaceName(): string
    {
        return TruffleReflection::namespaceName(TruffleReflection::of($this)['name']);
    }

    public function inNamespace(): bool
    {
        return str_contains(TruffleReflection::of($this)['name'], '\\');
    }

    public function isInternal(): bool
    {
        return TruffleReflection::of($this)['internal'];
    }

    public function isUserDefined(): bool
    {
        return !TruffleReflection::of($this)['internal'];
    }

    public function isAnonymous(): bool
    {
        return TruffleReflection::of($this)['anonymous'];
    }

    public function isInterface(): bool
    {
        return TruffleReflection::of($this)['interface'];
    }

    public function isTrait(): bool
    {
        return TruffleReflection::of($this)['trait'];
    }

    public function isEnum(): bool
    {
        return false;
    }

    public function isAbstract(): bool
    {
        return TruffleReflection::of($this)['abstract'];
    }

    public function isFinal(): bool
    {
        return (TruffleReflection::of($this)['modifiers'] & self::IS_FINAL) !== 0;
    }

    public function isReadOnly(): bool
    {
        return (TruffleReflection::of($this)['modifiers'] & self::IS_READONLY) !== 0;
    }

    public function getModifiers(): int
    {
        return TruffleReflection::of($this)['modifiers'];
    }

    public function isInstantiable(): bool
    {
        $info = TruffleReflection::of($this);
        if ($info['interface'] || $info['trait'] || $info['abstract']) {
            return false;
        }
        $constructor = \Truffle\reflection_method($info['name'], '__construct');
        return $constructor === false || ($constructor['modifiers'] & ReflectionMethod::IS_PUBLIC) !== 0;
    }

    public function isCloneable(): bool
    {
        $info = TruffleReflection::of($this);
        return !($info['interface'] || $info['trait'] || $info['abstract'] || $info['privateClone']
            || $info['uncloneable']);
    }

    public function isIterable(): bool
    {
        $info = TruffleReflection::of($this);
        return !($info['interface'] || $info['trait'] || $info['abstract'])
            && is_subclass_of($info['name'], 'Traversable');
    }

    public function isIterateable(): bool
    {
        return $this->isIterable();
    }

    public function getFileName(): string|false
    {
        return TruffleReflection::of($this)['file'];
    }

    public function getStartLine(): int|false
    {
        return TruffleReflection::of($this)['startLine'];
    }

    public function getEndLine(): int|false
    {
        return TruffleReflection::of($this)['endLine'];
    }

    public function getDocComment(): string|false
    {
        return TruffleReflection::of($this)['docComment'];
    }

    public function getParentClass(): ReflectionClass|false
    {
        $parent = TruffleReflection::of($this)['parent'];
        return $parent === false ? false : new ReflectionClass($parent);
    }

    public function getInterfaceNames(): array
    {
        return TruffleReflection::of($this)['interfaces'];
    }

    public function getInterfaces(): array
    {
        $result = [];
        foreach (TruffleReflection::of($this)['interfaces'] as $name) {
            $result[$name] = new ReflectionClass($name);
        }
        return $result;
    }

    public function getTraitNames(): array
    {
        return TruffleReflection::of($this)['traits'];
    }

    public function getTraits(): array
    {
        $result = [];
        foreach (TruffleReflection::of($this)['traits'] as $name) {
            $result[$name] = new ReflectionClass($name);
        }
        return $result;
    }

    public function isSubclassOf(ReflectionClass|string $class): bool
    {
        $name = $class instanceof ReflectionClass ? $class->name : $class;
        $info = \Truffle\reflection_class($name);
        if ($info === false) {
            throw new ReflectionException("Class \"$name\" does not exist", -1);
        }
        $own = TruffleReflection::of($this)['name'];
        return strcasecmp($info['name'], $own) !== 0 && is_a($own, $info['name'], true);
    }

    public function implementsInterface(ReflectionClass|string $interface): bool
    {
        $name = $interface instanceof ReflectionClass ? $interface->name : $interface;
        $info = \Truffle\reflection_class($name);
        if ($info === false) {
            throw new ReflectionException("Interface \"$name\" does not exist", -1);
        }
        if (!$info['interface']) {
            throw new ReflectionException("{$info['name']} is not an interface");
        }
        return is_a(TruffleReflection::of($this)['name'], $info['name'], true);
    }

    public function isInstance(object $object): bool
    {
        $name = TruffleReflection::of($this)['name'];
        return $object instanceof $name;
    }

    public function getConstructor(): ?ReflectionMethod
    {
        $name = TruffleReflection::of($this)['name'];
        $info = \Truffle\reflection_method($name, '__construct');
        return $info === false ? null : TruffleReflection::method($info, $name);
    }

    public function hasMethod(string $name): bool
    {
        return \Truffle\reflection_method(TruffleReflection::of($this)['name'], $name) !== false;
    }

    public function getMethod(string $name): ReflectionMethod
    {
        $class = TruffleReflection::of($this)['name'];
        $info = \Truffle\reflection_method($class, $name);
        if ($info === false) {
            throw new ReflectionException("Method $class::$name() does not exist");
        }
        return TruffleReflection::method($info, $class);
    }

    /** @return list<ReflectionMethod> */
    public function getMethods(?int $filter = null): array
    {
        $class = TruffleReflection::of($this)['name'];
        $result = [];
        foreach (\Truffle\reflection_methods($class) as $method) {
            $info = \Truffle\reflection_method($class, $method['name']);
            if ($filter === null || ($info['modifiers'] & $filter) !== 0) {
                $result[] = TruffleReflection::method($info, $class);
            }
        }
        return $result;
    }

    public function getConstants(?int $filter = null): array
    {
        if ($filter !== null) {
            TruffleReflection::notImplemented('ReflectionClass::getConstants() with a filter');
        }
        return \Truffle\reflection_constants(TruffleReflection::of($this)['name']);
    }

    public function hasConstant(string $name): bool
    {
        return array_key_exists($name, \Truffle\reflection_constants(TruffleReflection::of($this)['name']));
    }

    public function getConstant(string $name): mixed
    {
        $constants = \Truffle\reflection_constants(TruffleReflection::of($this)['name']);
        if (!array_key_exists($name, $constants)) {
            \Truffle\reflection_raise(E_DEPRECATED, 'ReflectionClass::getConstant() for a non-existent constant is '
                . 'deprecated, use ReflectionClass::hasConstant() to check if the constant exists');
            return false;
        }
        return $constants[$name];
    }

    public function newInstance(mixed ...$args): object
    {
        $name = TruffleReflection::of($this)['name'];
        TruffleReflection::constructible($name, $args);
        return new $name(...$args);
    }

    public function newInstanceArgs(array $args = []): ?object
    {
        $name = TruffleReflection::of($this)['name'];
        TruffleReflection::constructible($name, $args);
        return new $name(...$args);
    }

    public function newInstanceWithoutConstructor(): object
    {
        return \Truffle\reflection_new(TruffleReflection::of($this)['name']);
    }

    public function hasProperty(string $name): bool
    {
        return \Truffle\reflection_property(TruffleReflection::of($this)['name'], $name) !== false;
    }

    public function getProperty(string $name): ReflectionProperty
    {
        return new ReflectionProperty(TruffleReflection::of($this)['name'], $name);
    }

    /** @return list<ReflectionProperty> */
    public function getProperties(?int $filter = null): array
    {
        $class = TruffleReflection::of($this)['name'];
        $result = [];
        foreach (\Truffle\reflection_properties($class) as $name) {
            $property = new ReflectionProperty($class, $name);
            if ($filter === null || ($property->getModifiers() & $filter) !== 0) {
                $result[] = $property;
            }
        }
        return $result;
    }

    // ---- lazy objects (zend_lazy_objects.c) ----

    const SKIP_INITIALIZATION_ON_SERIALIZE = 8;
    const SKIP_DESTRUCTOR = 16;

    public function newLazyGhost(callable $initializer, int $options = 0): object
    {
        $this->lazyOptions('newLazyGhost', $options, 2, false);
        return \Truffle\lazy_new(TruffleReflection::of($this)['name'], $initializer, $options, false);
    }

    public function newLazyProxy(callable $factory, int $options = 0): object
    {
        $this->lazyOptions('newLazyProxy', $options, 2, false);
        return \Truffle\lazy_new(TruffleReflection::of($this)['name'], $factory, $options, true);
    }

    public function resetAsLazyGhost(object $object, callable $initializer, int $options = 0): void
    {
        $this->lazyReset('resetAsLazyGhost', $object, $initializer, $options, false);
    }

    public function resetAsLazyProxy(object $object, callable $factory, int $options = 0): void
    {
        $this->lazyReset('resetAsLazyProxy', $object, $factory, $options, true);
    }

    public function isUninitializedLazyObject(object $object): bool
    {
        $this->lazyObject('isUninitializedLazyObject', $object);
        return \Truffle\lazy_state($object, 'uninitialized');
    }

    public function initializeLazyObject(object $object): object
    {
        $this->lazyObject('initializeLazyObject', $object);
        return \Truffle\lazy_state($object, 'initialize');
    }

    public function markLazyObjectAsInitialized(object $object): object
    {
        $this->lazyObject('markLazyObjectAsInitialized', $object);
        return \Truffle\lazy_state($object, 'mark');
    }

    public function getLazyInitializer(object $object): ?callable
    {
        $this->lazyObject('getLazyInitializer', $object);
        return \Truffle\lazy_state($object, 'initializer');
    }

    private function lazyOptions(string $method, int $options, int $position, bool $reset): void
    {
        if (($options & ~(self::SKIP_INITIALIZATION_ON_SERIALIZE | self::SKIP_DESTRUCTOR)) !== 0) {
            throw new ReflectionException("ReflectionClass::$method(): Argument #$position (\$options) contains invalid flags");
        }
        if (!$reset && ($options & self::SKIP_DESTRUCTOR) !== 0) {
            throw new ReflectionException("ReflectionClass::$method(): Argument #$position (\$options) does not accept "
                . 'ReflectionClass::SKIP_DESTRUCTOR');
        }
    }

    private function lazyReset(string $method, object $object, callable $initializer, int $options, bool $proxy): void
    {
        $this->lazyObject($method, $object);
        $this->lazyOptions($method, $options, 3, true);
        if (\Truffle\lazy_state($object, 'uninitialized')) {
            throw new ReflectionException('Object is already lazy');
        }
        \Truffle\lazy_reset(TruffleReflection::of($this)['name'], $object, $initializer, $options, $proxy);
    }

    /** Z_PARAM_OBJ_OF_CLASS: the object must be of the reflected class. */
    private function lazyObject(string $method, object $object): void
    {
        $class = TruffleReflection::of($this)['name'];
        if (!$object instanceof $class) {
            throw new TypeError("ReflectionClass::$method(): Argument #1 (\$object) must be of type $class, "
                . get_debug_type($object) . ' given');
        }
    }

    public function __toString(): string
    {
        TruffleReflection::notImplemented('ReflectionClass::__toString()');
    }
}

class ReflectionProperty implements Reflector
{
    const IS_STATIC = 16;
    const IS_READONLY = 128;
    const IS_PUBLIC = 1;
    const IS_PROTECTED = 2;
    const IS_PRIVATE = 4;
    const IS_ABSTRACT = 64;
    const IS_PROTECTED_SET = 2048;
    const IS_PRIVATE_SET = 4096;
    const IS_VIRTUAL = 512;
    const IS_FINAL = 32;

    public string $name = '';
    public string $class = '';

    public function __construct(object|string $class, string $property)
    {
        $info = \Truffle\reflection_property($class, $property);
        if ($info === false) {
            $name = is_object($class) ? get_class($class) : $class;
            if (!class_exists($name) && !interface_exists($name) && !trait_exists($name)) {
                throw new ReflectionException("Class \"$name\" does not exist", -1);
            }
            throw new ReflectionException("Property $name::\$$property does not exist");
        }
        TruffleReflection::$state[spl_object_id($this)] = $info;
        $this->name = $info['name'];
        $this->class = $info['class'];
    }

    public function getName(): string
    {
        return TruffleReflection::of($this)['name'];
    }

    public function getModifiers(): int
    {
        return TruffleReflection::of($this)['modifiers'];
    }

    public function isPublic(): bool
    {
        return ($this->getModifiers() & self::IS_PUBLIC) !== 0;
    }

    public function isProtected(): bool
    {
        return ($this->getModifiers() & self::IS_PROTECTED) !== 0;
    }

    public function isPrivate(): bool
    {
        return ($this->getModifiers() & self::IS_PRIVATE) !== 0;
    }

    public function isStatic(): bool
    {
        return ($this->getModifiers() & self::IS_STATIC) !== 0;
    }

    public function isVirtual(): bool
    {
        return ($this->getModifiers() & self::IS_VIRTUAL) !== 0;
    }

    public function isFinal(): bool
    {
        return ($this->getModifiers() & self::IS_FINAL) !== 0;
    }

    public function getDeclaringClass(): ReflectionClass
    {
        return new ReflectionClass(TruffleReflection::of($this)['class']);
    }

    public function setAccessible(bool $accessible): void
    {
    }

    public function skipLazyInitialization(object $object): void
    {
        $this->lazyObject('skipLazyInitialization', $object);
        \Truffle\property_lazy($object, TruffleReflection::of($this)['name'], 'skip');
    }

    public function setRawValueWithoutLazyInitialization(object $object, mixed $value): void
    {
        $this->lazyObject('setRawValueWithoutLazyInitialization', $object);
        \Truffle\property_lazy($object, TruffleReflection::of($this)['name'], 'raw', $value);
    }

    public function isLazy(object $object): bool
    {
        $this->lazyObject('isLazy', $object);
        return \Truffle\property_lazy($object, TruffleReflection::of($this)['name'], 'isLazy');
    }

    /** Z_PARAM_OBJ_OF_CLASS: the object must be of the property's class. */
    private function lazyObject(string $method, object $object): void
    {
        $class = TruffleReflection::of($this)['reflected'];
        if (!$object instanceof $class) {
            throw new TypeError("ReflectionProperty::$method(): Argument #1 (\$object) must be of type $class, "
                . get_debug_type($object) . ' given');
        }
    }

    public function __toString(): string
    {
        TruffleReflection::notImplemented('ReflectionProperty::__toString()');
    }
}

class ReflectionObject extends ReflectionClass
{
    public function __construct(object $object)
    {
        parent::__construct($object);
    }
}
