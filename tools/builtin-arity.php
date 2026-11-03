<?php
// Writes the argument counts php-src checks internal functions and methods against (their arginfo, as Reflection
// gives it) into the resource Truffle PHP's builtins/Registry.java loads. Run on the reference PHP with mysqli, the
// extensions the runtime implements being those listed below:
//   docker run --rm -v "$PWD:/w" -w /w bootshare-php:8.5 php -d auto_prepend_file= tools/builtin-arity.php \
//     > src/main/resources/pl/codeloom/php/builtins/arity.txt
// Output lines: <function or Class::method> <required> <maximum, -1 for variadic> [<Class::method declaring it>]
$extensions = ['Core', 'standard', 'date', 'pcre', 'ctype', 'curl', 'json', 'filter', 'hash', 'SPL', 'mbstring',
    'random', 'Reflection', 'mysqli'];
// the functions whose own parameter parsing differs from their arginfo (no arguments, or both bounds): their bodies
// check the count, nothing before them
$own = ['mt_rand', 'rand'];
$line = fn(string $name, ReflectionFunctionAbstract $f, string $declared = '') => $name . ' '
    . $f->getNumberOfRequiredParameters() . ' '
    . ($f->isVariadic() || in_array($name, $own) ? -1 : $f->getNumberOfParameters()) . $declared . "\n";
foreach ($extensions as $extension) {
    $ext = new ReflectionExtension($extension);
    foreach ($ext->getFunctions() as $name => $function) {
        echo $line($name, $function);
    }
    foreach ($ext->getClassNames() as $class) {
        foreach ((new ReflectionClass($class))->getMethods() as $method) {
            // an inherited method too (the prelude may declare it in a subclass), naming the class declaring it; not
            // an interface's, which the class doesn't implement (ReflectionFunctionAbstract's __toString)
            if ($method->class !== $class && $method->isAbstract()) {
                continue;
            }
            echo $line($class . '::' . $method->name, $method, $method->class === $class ? '' : ' ' . $method->class
                . '::' . $method->name);
        }
    }
}
