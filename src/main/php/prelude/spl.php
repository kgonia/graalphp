<?php
// SPL's interfaces, iterators and data structures, written in PHP: Truffle PHP's prelude. A context loads this
// file the first time it looks up one of its classes (PhpContext.findClass), so a request that uses none of
// them pays nothing. After php-src's ext/spl; the behaviour WordPress and its libraries use, not every corner.
// The runtime reads the AST export in src/main/resources (tools/export-prelude.sh regenerates it).

interface RecursiveIterator extends Iterator
{
    public function hasChildren();

    public function getChildren();
}

interface OuterIterator extends Iterator
{
    public function getInnerIterator();
}

interface SeekableIterator extends Iterator
{
    public function seek(int $offset);
}

interface SplObserver
{
    public function update(SplSubject $subject);
}

interface SplSubject
{
    public function attach(SplObserver $observer);

    public function detach(SplObserver $observer);

    public function notify();
}

class ArrayIterator implements SeekableIterator, ArrayAccess, Countable
{
    const STD_PROP_LIST = 1;
    const ARRAY_AS_PROPS = 2;

    private $storage;
    private $flags;
    private $keys = [];
    private $position = 0;

    public function __construct($array = [], int $flags = 0)
    {
        $this->storage = is_object($array) ? get_object_vars($array) : $array;
        $this->flags = $flags;
        $this->keys = array_keys($this->storage);
    }

    public function current(): mixed
    {
        return $this->valid() ? $this->storage[$this->keys[$this->position]] : null;
    }

    public function key(): mixed
    {
        return $this->valid() ? $this->keys[$this->position] : null;
    }

    public function next(): void
    {
        $this->position++;
    }

    public function rewind(): void
    {
        $this->keys = array_keys($this->storage);
        $this->position = 0;
    }

    public function valid(): bool
    {
        return $this->position < count($this->keys) && array_key_exists($this->keys[$this->position], $this->storage);
    }

    public function seek(int $offset): void
    {
        if ($offset < 0 || $offset >= count($this->storage)) {
            throw new OutOfBoundsException("Seek position $offset is out of range");
        }
        $this->keys = array_keys($this->storage);
        $this->position = $offset;
    }

    public function count(): int
    {
        return count($this->storage);
    }

    public function offsetExists($key): bool
    {
        return isset($this->storage[$key]);
    }

    public function offsetGet($key): mixed
    {
        return $this->storage[$key];
    }

    public function offsetSet($key, $value): void
    {
        if ($key === null) {
            $this->storage[] = $value;
        } else {
            $this->storage[$key] = $value;
        }
        $this->keys = array_keys($this->storage);
    }

    public function offsetUnset($key): void
    {
        unset($this->storage[$key]);
    }

    public function append($value): void
    {
        $this->storage[] = $value;
        $this->keys = array_keys($this->storage);
    }

    public function getArrayCopy(): array
    {
        return $this->storage;
    }

    public function getFlags(): int
    {
        return $this->flags;
    }

    public function setFlags(int $flags): void
    {
        $this->flags = $flags;
    }

    public function asort(int $flags = SORT_REGULAR): bool
    {
        asort($this->storage, $flags);
        $this->keys = array_keys($this->storage);
        return true;
    }

    public function ksort(int $flags = SORT_REGULAR): bool
    {
        ksort($this->storage, $flags);
        $this->keys = array_keys($this->storage);
        return true;
    }

    public function uasort($callback): bool
    {
        uasort($this->storage, $callback);
        $this->keys = array_keys($this->storage);
        return true;
    }

    public function uksort($callback): bool
    {
        uksort($this->storage, $callback);
        $this->keys = array_keys($this->storage);
        return true;
    }
}

class RecursiveArrayIterator extends ArrayIterator implements RecursiveIterator
{
    const CHILD_ARRAYS_ONLY = 4;

    public function hasChildren(): bool
    {
        $current = $this->current();
        return is_array($current) || (is_object($current) && !($this->getFlags() & self::CHILD_ARRAYS_ONLY));
    }

    public function getChildren(): ?RecursiveArrayIterator
    {
        return new static($this->current(), $this->getFlags());
    }
}

class ArrayObject implements IteratorAggregate, ArrayAccess, Countable
{
    const STD_PROP_LIST = 1;
    const ARRAY_AS_PROPS = 2;

    private $storage;
    private $flags;
    private $iteratorClass;

    public function __construct($array = [], int $flags = 0, string $iteratorClass = "ArrayIterator")
    {
        $this->storage = is_object($array) ? get_object_vars($array) : $array;
        $this->flags = $flags;
        $this->iteratorClass = $iteratorClass;
    }

    public function getIterator(): Iterator
    {
        return new $this->iteratorClass($this->storage);
    }

    public function count(): int
    {
        return count($this->storage);
    }

    public function offsetExists($key): bool
    {
        return isset($this->storage[$key]);
    }

    public function offsetGet($key): mixed
    {
        return $this->storage[$key];
    }

    public function offsetSet($key, $value): void
    {
        if ($key === null) {
            $this->storage[] = $value;
        } else {
            $this->storage[$key] = $value;
        }
    }

    public function offsetUnset($key): void
    {
        unset($this->storage[$key]);
    }

    public function append($value): void
    {
        $this->storage[] = $value;
    }

    public function getArrayCopy(): array
    {
        return $this->storage;
    }

    public function exchangeArray($array): array
    {
        $old = $this->storage;
        $this->storage = is_object($array) ? get_object_vars($array) : $array;
        return $old;
    }

    public function getFlags(): int
    {
        return $this->flags;
    }

    public function setFlags(int $flags): void
    {
        $this->flags = $flags;
    }

    public function getIteratorClass(): string
    {
        return $this->iteratorClass;
    }

    public function setIteratorClass(string $iteratorClass): void
    {
        $this->iteratorClass = $iteratorClass;
    }

    public function asort(int $flags = SORT_REGULAR): bool
    {
        return asort($this->storage, $flags);
    }

    public function ksort(int $flags = SORT_REGULAR): bool
    {
        return ksort($this->storage, $flags);
    }

    public function uasort($callback): bool
    {
        return uasort($this->storage, $callback);
    }

    public function uksort($callback): bool
    {
        return uksort($this->storage, $callback);
    }
}

class EmptyIterator implements Iterator
{
    public function current(): never
    {
        throw new BadMethodCallException("Accessing the value of an EmptyIterator");
    }

    public function key(): never
    {
        throw new BadMethodCallException("Accessing the key of an EmptyIterator");
    }

    public function next(): void
    {
    }

    public function rewind(): void
    {
    }

    public function valid(): bool
    {
        return false;
    }
}

class IteratorIterator implements OuterIterator
{
    private $inner;

    public function __construct(Traversable $iterator, ?string $class = null)
    {
        while ($iterator instanceof IteratorAggregate) {
            $iterator = $iterator->getIterator();
        }
        $this->inner = $iterator;
    }

    public function getInnerIterator(): ?Iterator
    {
        return $this->inner;
    }

    public function rewind(): void
    {
        $this->inner->rewind();
    }

    public function valid(): bool
    {
        return $this->inner->valid();
    }

    public function current(): mixed
    {
        return $this->inner->current();
    }

    public function key(): mixed
    {
        return $this->inner->key();
    }

    public function next(): void
    {
        $this->inner->next();
    }
}

abstract class FilterIterator extends IteratorIterator
{
    abstract public function accept(): bool;

    public function __construct(Iterator $iterator)
    {
        parent::__construct($iterator);
    }

    public function rewind(): void
    {
        parent::rewind();
        $this->skip();
    }

    public function next(): void
    {
        parent::next();
        $this->skip();
    }

    private function skip(): void
    {
        while (parent::valid() && !$this->accept()) {
            parent::next();
        }
    }
}

class CallbackFilterIterator extends FilterIterator
{
    private $callback;

    public function __construct(Iterator $iterator, callable $callback)
    {
        parent::__construct($iterator);
        $this->callback = $callback;
    }

    public function accept(): bool
    {
        return (bool) call_user_func($this->callback, $this->current(), $this->key(), $this->getInnerIterator());
    }
}

class LimitIterator extends IteratorIterator
{
    private $offset;
    private $limit;
    private $position = 0;

    public function __construct(Iterator $iterator, int $offset = 0, int $limit = -1)
    {
        if ($offset < 0) {
            throw new ValueError("LimitIterator::__construct(): Argument #2 (\$offset) must be greater than or equal to 0");
        }
        parent::__construct($iterator);
        $this->offset = $offset;
        $this->limit = $limit;
    }

    public function rewind(): void
    {
        parent::rewind();
        for ($this->position = 0; $this->position < $this->offset && parent::valid(); $this->position++) {
            parent::next();
        }
    }

    public function valid(): bool
    {
        return ($this->limit < 0 || $this->position < $this->offset + $this->limit) && parent::valid();
    }

    public function next(): void
    {
        parent::next();
        $this->position++;
    }

    public function getPosition(): int
    {
        return $this->position;
    }
}

class RegexIterator extends FilterIterator
{
    const USE_KEY = 1;
    const INVERT_MATCH = 2;
    const MATCH = 0;
    const GET_MATCH = 1;
    const ALL_MATCHES = 2;
    const SPLIT = 3;
    const REPLACE = 4;

    public $replacement = null;
    private $regex;
    private $mode;
    private $flags;
    private $pregFlags;
    private $result;

    public function __construct(Iterator $iterator, string $pattern, int $mode = self::MATCH, int $flags = 0,
                                int $pregFlags = 0)
    {
        parent::__construct($iterator);
        $this->regex = $pattern;
        $this->mode = $mode;
        $this->flags = $flags;
        $this->pregFlags = $pregFlags;
    }

    public function accept(): bool
    {
        $subject = (string) (($this->flags & self::USE_KEY) ? parent::key() : parent::current());
        switch ($this->mode) {
            case self::GET_MATCH:
                $matched = preg_match($this->regex, $subject, $matches, $this->pregFlags) > 0;
                $this->result = $matches;
                break;
            case self::ALL_MATCHES:
                preg_match_all($this->regex, $subject, $matches, $this->pregFlags ?: PREG_PATTERN_ORDER);
                $matched = true;
                $this->result = $matches;
                break;
            case self::SPLIT:
                $parts = preg_split($this->regex, $subject, -1, $this->pregFlags);
                $matched = count($parts) > 1;
                $this->result = $parts;
                break;
            case self::REPLACE:
                $replaced = preg_replace($this->regex, (string) $this->replacement, $subject);
                $matched = $replaced !== null;
                $this->result = $replaced;
                break;
            default:
                $matched = preg_match($this->regex, $subject) > 0;
                $this->result = null;
        }
        return ($this->flags & self::INVERT_MATCH) ? !$matched : $matched;
    }

    public function current(): mixed
    {
        return $this->mode === self::MATCH || ($this->flags & self::USE_KEY) ? parent::current() : $this->result;
    }

    public function getMode(): int
    {
        return $this->mode;
    }

    public function setMode(int $mode): void
    {
        $this->mode = $mode;
    }

    public function getFlags(): int
    {
        return $this->flags;
    }

    public function setFlags(int $flags): void
    {
        $this->flags = $flags;
    }

    public function getRegex(): string
    {
        return $this->regex;
    }
}

class RecursiveRegexIterator extends RegexIterator implements RecursiveIterator
{
    private $arguments;

    public function __construct(RecursiveIterator $iterator, string $pattern, int $mode = self::MATCH,
                                int $flags = 0, int $pregFlags = 0)
    {
        parent::__construct($iterator, $pattern, $mode, $flags, $pregFlags);
        $this->arguments = [$pattern, $mode, $flags, $pregFlags];
    }

    public function hasChildren(): bool
    {
        return $this->getInnerIterator()->hasChildren();
    }

    public function getChildren(): RecursiveRegexIterator
    {
        return new static($this->getInnerIterator()->getChildren(), ...$this->arguments);
    }
}

/**
 * Walks a RecursiveIterator depth first. The stack holds the iterator of each level; in CHILD_FIRST mode a
 * level whose children are done is reported once more before it advances (the pending flag).
 */
class RecursiveIteratorIterator implements OuterIterator
{
    const LEAVES_ONLY = 0;
    const SELF_FIRST = 1;
    const CHILD_FIRST = 2;
    const CATCH_GET_CHILD = 16;

    private $stack = [];
    private $pending = [];
    private $mode;
    private $flags;
    private $maxDepth = -1;

    public function __construct(Traversable $iterator, int $mode = self::LEAVES_ONLY, int $flags = 0)
    {
        while ($iterator instanceof IteratorAggregate) {
            $iterator = $iterator->getIterator();
        }
        if (!($iterator instanceof RecursiveIterator)) {
            throw new InvalidArgumentException("An instance of RecursiveIterator or IteratorAggregate creating it is required");
        }
        $this->stack = [$iterator];
        $this->pending = [false];
        $this->mode = $mode;
        $this->flags = $flags;
    }

    public function rewind(): void
    {
        $root = $this->stack[0];
        while (count($this->stack) > 1) {
            array_pop($this->stack);
            $this->endChildren();
        }
        $this->pending = [false];
        $root->rewind();
        $this->beginIteration();
        $this->settle(true);
    }

    public function valid(): bool
    {
        $depth = count($this->stack) - 1;
        return $this->pending[$depth] || $this->stack[$depth]->valid();
    }

    public function key(): mixed
    {
        return $this->stack[count($this->stack) - 1]->key();
    }

    public function current(): mixed
    {
        return $this->stack[count($this->stack) - 1]->current();
    }

    public function next(): void
    {
        $depth = count($this->stack) - 1;
        if ($this->pending[$depth]) {
            $this->pending[$depth] = false;
        } elseif ($this->mode === self::SELF_FIRST && $this->callHasChildren()
            && ($this->maxDepth < 0 || $depth < $this->maxDepth) && $this->descend()) {
            $this->settle(true);
            return;
        }
        $this->stack[count($this->stack) - 1]->next();
        $this->settle(false);
    }

    /** Moves to the next element the mode reports, entering and leaving levels on the way. */
    private function settle(bool $fresh): void
    {
        while (true) {
            $depth = count($this->stack) - 1;
            $iterator = $this->stack[$depth];
            if ($iterator->valid()) {
                $this->nextElement();
                if ($this->callHasChildren() && ($this->maxDepth < 0 || $depth < $this->maxDepth)) {
                    if ($this->mode === self::SELF_FIRST) {
                        return;
                    }
                    if ($this->descend()) {
                        continue;
                    }
                    if ($this->mode === self::CHILD_FIRST) {
                        return;
                    }
                    $iterator->next();
                    continue;
                }
                return;
            }
            if ($depth === 0) {
                $this->endIteration();
                return;
            }
            array_pop($this->stack);
            array_pop($this->pending);
            $this->endChildren();
            if ($this->mode === self::CHILD_FIRST) {
                $this->pending[$depth - 1] = true;
                return;
            }
            $this->stack[$depth - 1]->next();
        }
    }

    /** Enters the current element's children; false if getChildren failed under CATCH_GET_CHILD. */
    private function descend(): bool
    {
        try {
            $children = $this->callGetChildren();
        } catch (Exception $e) {
            if ($this->flags & self::CATCH_GET_CHILD) {
                return false;
            }
            throw $e;
        }
        $children->rewind();
        $this->stack[] = $children;
        $this->pending[] = false;
        $this->beginChildren();
        return true;
    }

    public function getDepth(): int
    {
        return count($this->stack) - 1;
    }

    public function getSubIterator(?int $level = null): ?RecursiveIterator
    {
        $level = $level ?? count($this->stack) - 1;
        return $this->stack[$level] ?? null;
    }

    public function getInnerIterator(): RecursiveIterator
    {
        return $this->stack[count($this->stack) - 1];
    }

    public function setMaxDepth(int $maxDepth = -1): void
    {
        if ($maxDepth < -1) {
            throw new OutOfRangeException("RecursiveIteratorIterator::setMaxDepth(): Argument #1 (\$maxDepth) must be greater than or equal to -1");
        }
        $this->maxDepth = $maxDepth;
    }

    public function getMaxDepth(): int|false
    {
        return $this->maxDepth < 0 ? false : $this->maxDepth;
    }

    public function callHasChildren(): bool
    {
        return $this->stack[count($this->stack) - 1]->hasChildren();
    }

    public function callGetChildren(): ?RecursiveIterator
    {
        return $this->stack[count($this->stack) - 1]->getChildren();
    }

    public function beginIteration()
    {
    }

    public function endIteration()
    {
    }

    public function beginChildren(): void
    {
    }

    public function endChildren(): void
    {
    }

    public function nextElement(): void
    {
    }
}

class SplFileInfo implements Stringable
{
    private $pathname;

    public function __construct(string $filename)
    {
        $this->pathname = $filename;
    }

    public function getPathname(): string
    {
        return $this->pathname;
    }

    public function getFilename(): string
    {
        $slash = strrpos($this->pathname, '/');
        return $slash === false ? $this->pathname : substr($this->pathname, $slash + 1);
    }

    public function getPath(): string
    {
        $slash = strrpos($this->pathname, '/');
        return $slash === false ? '' : substr($this->pathname, 0, $slash);
    }

    public function getBasename(string $suffix = ""): string
    {
        return basename($this->pathname, $suffix);
    }

    public function getExtension(): string
    {
        $name = $this->getFilename();
        $dot = strrpos($name, '.');
        return $dot === false ? '' : substr($name, $dot + 1);
    }

    public function getRealPath(): string|false
    {
        return realpath($this->pathname);
    }

    public function getSize(): int|false
    {
        return filesize($this->pathname);
    }

    public function getMTime(): int|false
    {
        return filemtime($this->pathname);
    }

    public function getPerms(): int|false
    {
        return fileperms($this->pathname);
    }

    public function getType(): string|false
    {
        return is_link($this->pathname) ? 'link' : (is_dir($this->pathname) ? 'dir' : 'file');
    }

    public function isDir(): bool
    {
        return is_dir($this->pathname);
    }

    public function isFile(): bool
    {
        return is_file($this->pathname);
    }

    public function isLink(): bool
    {
        return is_link($this->pathname);
    }

    public function isReadable(): bool
    {
        return is_readable($this->pathname);
    }

    public function isWritable(): bool
    {
        return is_writable($this->pathname);
    }

    public function getFileInfo(?string $class = null): SplFileInfo
    {
        $class = $class ?? SplFileInfo::class;
        return new $class($this->pathname);
    }

    public function getPathInfo(?string $class = null): ?SplFileInfo
    {
        $class = $class ?? SplFileInfo::class;
        return new $class($this->getPath());
    }

    public function __toString(): string
    {
        return $this->pathname;
    }
}

/** A directory's entries in readdir() order, "." and ".." included. */
class DirectoryIterator extends SplFileInfo implements SeekableIterator
{
    protected $directory;
    protected $entries = [];
    protected $position = 0;

    public function __construct(string $directory)
    {
        if ($directory === '') {
            throw new ValueError("DirectoryIterator::__construct(): Argument #1 (\$directory) cannot be empty");
        }
        $handle = @opendir($directory);
        if ($handle === false) {
            throw new UnexpectedValueException("DirectoryIterator::__construct($directory): Failed to open directory: No such file or directory");
        }
        while (($entry = readdir($handle)) !== false) {
            $this->entries[] = $entry;
        }
        closedir($handle);
        $this->directory = rtrim($directory, '/');
        parent::__construct($this->entryPath());
    }

    protected function entryPath(): string
    {
        return $this->directory . '/' . ($this->entries[$this->position] ?? '');
    }

    public function isDot(): bool
    {
        $entry = $this->entries[$this->position] ?? null;
        return $entry === '.' || $entry === '..';
    }

    public function current(): mixed
    {
        return $this;
    }

    public function key(): mixed
    {
        return $this->position;
    }

    public function next(): void
    {
        $this->position++;
    }

    public function rewind(): void
    {
        $this->position = 0;
    }

    public function valid(): bool
    {
        return $this->position < count($this->entries);
    }

    public function seek(int $offset): void
    {
        $this->position = $offset;
    }

    public function getFilename(): string
    {
        return $this->entries[$this->position] ?? '';
    }

    public function getPathname(): string
    {
        return $this->entryPath();
    }

    public function getPath(): string
    {
        return $this->directory;
    }

    public function isDir(): bool
    {
        return is_dir($this->entryPath());
    }

    public function isFile(): bool
    {
        return is_file($this->entryPath());
    }

    public function getSize(): int|false
    {
        return filesize($this->entryPath());
    }

    public function getMTime(): int|false
    {
        return filemtime($this->entryPath());
    }

    public function getExtension(): string
    {
        $name = $this->getFilename();
        $dot = strrpos($name, '.');
        return $dot === false ? '' : substr($name, $dot + 1);
    }

    public function getBasename(string $suffix = ""): string
    {
        return basename($this->getFilename(), $suffix);
    }

    public function getRealPath(): string|false
    {
        return realpath($this->entryPath());
    }

    public function __toString(): string
    {
        return $this->getFilename();
    }
}

class FilesystemIterator extends DirectoryIterator
{
    const CURRENT_MODE_MASK = 240;
    const CURRENT_AS_PATHNAME = 32;
    const CURRENT_AS_FILEINFO = 0;
    const CURRENT_AS_SELF = 16;
    const KEY_MODE_MASK = 3840;
    const KEY_AS_PATHNAME = 0;
    const KEY_AS_FILENAME = 256;
    const FOLLOW_SYMLINKS = 16384;
    const NEW_CURRENT_AND_KEY = 256;
    const OTHER_MODE_MASK = 28672;
    const SKIP_DOTS = 4096;
    const UNIX_PATHS = 8192;

    protected $flags;

    public function __construct(string $directory, int $flags = FilesystemIterator::KEY_AS_PATHNAME
        | FilesystemIterator::CURRENT_AS_FILEINFO | FilesystemIterator::SKIP_DOTS)
    {
        parent::__construct($directory);
        $this->flags = $flags | self::SKIP_DOTS;
        $this->skipDots();
    }

    protected function skipDots(): void
    {
        if ($this->flags & self::SKIP_DOTS) {
            $this->entries = array_values(array_filter($this->entries, function ($entry) {
                return $entry !== '.' && $entry !== '..';
            }));
        }
    }

    public function current(): mixed
    {
        if ($this->flags & self::CURRENT_AS_PATHNAME) {
            return $this->entryPath();
        }
        if ($this->flags & self::CURRENT_AS_SELF) {
            return $this;
        }
        return new SplFileInfo($this->entryPath());
    }

    public function key(): mixed
    {
        return ($this->flags & self::KEY_AS_FILENAME) ? $this->getFilename() : $this->entryPath();
    }

    public function getFlags(): int
    {
        return $this->flags & (self::CURRENT_MODE_MASK | self::KEY_MODE_MASK | self::OTHER_MODE_MASK);
    }

    public function setFlags(int $flags): void
    {
        $this->flags = $flags;
    }
}

class RecursiveDirectoryIterator extends FilesystemIterator implements RecursiveIterator
{
    private $subPath = '';

    public function __construct(string $directory, int $flags = FilesystemIterator::KEY_AS_PATHNAME
        | FilesystemIterator::CURRENT_AS_FILEINFO)
    {
        DirectoryIterator::__construct($directory);
        $this->flags = $flags;
        $this->skipDots();
    }

    public function hasChildren(bool $allowLinks = false): bool
    {
        if ($this->isDot()) {
            return false;
        }
        $path = $this->entryPath();
        return is_dir($path) && ($allowLinks || ($this->flags & self::FOLLOW_SYMLINKS) || !is_link($path));
    }

    public function getChildren(): RecursiveDirectoryIterator
    {
        $children = new static($this->entryPath(), $this->flags);
        $children->subPath = ($this->subPath === '' ? '' : $this->subPath . '/') . $this->getFilename();
        return $children;
    }

    public function getSubPath(): string
    {
        return $this->subPath;
    }

    public function getSubPathname(): string
    {
        return ($this->subPath === '' ? '' : $this->subPath . '/') . $this->getFilename();
    }
}

class SplFixedArray implements IteratorAggregate, ArrayAccess, Countable, JsonSerializable
{
    private $data = [];
    private $size = 0;

    public function __construct(int $size = 0)
    {
        if ($size < 0) {
            throw new ValueError("SplFixedArray::__construct(): Argument #1 (\$size) must be greater than or equal to 0");
        }
        $this->size = $size;
        $this->data = $size > 0 ? array_fill(0, $size, null) : [];
    }

    public static function fromArray(array $array, bool $preserveKeys = true): SplFixedArray
    {
        if (!$preserveKeys) {
            $array = array_values($array);
        }
        $size = $array === [] ? 0 : max(array_keys($array)) + 1;
        $fixed = new SplFixedArray($size);
        foreach ($array as $key => $value) {
            if (!is_int($key) || $key < 0) {
                throw new ValueError("array must contain only positive integer keys");
            }
            $fixed->data[$key] = $value;
        }
        return $fixed;
    }

    private function index($index): int
    {
        if (is_string($index) && is_numeric($index)) {
            $index = (int) $index;
        }
        if (is_float($index) || is_bool($index)) {
            $index = (int) $index;
        }
        if (!is_int($index)) {
            throw new TypeError("Cannot access offset of type " . get_debug_type($index) . " on SplFixedArray");
        }
        if ($index < 0 || $index >= $this->size) {
            throw new RuntimeException("Index invalid or out of range");
        }
        return $index;
    }

    public function offsetExists($index): bool
    {
        return is_int($index) && $index >= 0 && $index < $this->size && $this->data[$index] !== null;
    }

    public function offsetGet($index): mixed
    {
        return $this->data[$this->index($index)];
    }

    public function offsetSet($index, $value): void
    {
        if ($index === null) {
            throw new RuntimeException("[] operator not supported for SplFixedArray");
        }
        $this->data[$this->index($index)] = $value;
    }

    public function offsetUnset($index): void
    {
        $this->data[$this->index($index)] = null;
    }

    public function count(): int
    {
        return $this->size;
    }

    public function getSize(): int
    {
        return $this->size;
    }

    public function setSize(int $size)
    {
        if ($size < 0) {
            throw new ValueError("SplFixedArray::setSize(): Argument #1 (\$size) must be greater than or equal to 0");
        }
        if ($size < $this->size) {
            $this->data = array_slice($this->data, 0, $size);
        } else {
            for ($i = $this->size; $i < $size; $i++) {
                $this->data[$i] = null;
            }
        }
        $this->size = $size;
        return true;
    }

    public function toArray(): array
    {
        return $this->data;
    }

    public function jsonSerialize(): array
    {
        return $this->data;
    }

    public function getIterator(): Iterator
    {
        return new ArrayIterator($this->data);
    }
}

class SplObjectStorage implements Countable, Iterator, ArrayAccess
{
    private $objects = [];
    private $data = [];
    private $position = 0;

    public function attach(object $object, mixed $info = null): void
    {
        $id = spl_object_id($object);
        $this->objects[$id] = $object;
        $this->data[$id] = $info;
    }

    public function detach(object $object): void
    {
        $id = spl_object_id($object);
        unset($this->objects[$id], $this->data[$id]);
    }

    public function contains(object $object): bool
    {
        return isset($this->objects[spl_object_id($object)]);
    }

    public function addAll(SplObjectStorage $storage): int
    {
        foreach ($storage->objects as $id => $object) {
            $this->objects[$id] = $object;
            $this->data[$id] = $storage->data[$id];
        }
        return count($this->objects);
    }

    public function removeAll(SplObjectStorage $storage): int
    {
        foreach ($storage->objects as $id => $object) {
            unset($this->objects[$id], $this->data[$id]);
        }
        return count($this->objects);
    }

    public function getInfo(): mixed
    {
        $ids = array_keys($this->objects);
        return isset($ids[$this->position]) ? $this->data[$ids[$this->position]] : null;
    }

    public function setInfo(mixed $info): void
    {
        $ids = array_keys($this->objects);
        if (isset($ids[$this->position])) {
            $this->data[$ids[$this->position]] = $info;
        }
    }

    public function getHash(object $object): string
    {
        return spl_object_hash($object);
    }

    public function count(int $mode = COUNT_NORMAL): int
    {
        return count($this->objects);
    }

    public function rewind(): void
    {
        $this->position = 0;
    }

    public function valid(): bool
    {
        return $this->position < count($this->objects);
    }

    public function key(): int
    {
        return $this->position;
    }

    public function current(): object
    {
        $ids = array_keys($this->objects);
        if (!isset($ids[$this->position])) {
            throw new RuntimeException("Called current() on invalid iterator");
        }
        return $this->objects[$ids[$this->position]];
    }

    public function next(): void
    {
        $this->position++;
    }

    public function offsetExists($object): bool
    {
        return $this->contains($object);
    }

    public function offsetGet($object): mixed
    {
        $id = spl_object_id($object);
        if (!isset($this->objects[$id])) {
            throw new UnexpectedValueException("Object not found");
        }
        return $this->data[$id];
    }

    public function offsetSet($object, $info): void
    {
        $this->attach($object, $info);
    }

    public function offsetUnset($object): void
    {
        $this->detach($object);
    }
}
