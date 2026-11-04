<?php
// Writes the parameters of the internal functions and methods (params.txt, by tools/builtin-params.php, has the
// functions' names only, for var_dump of their closures) (their arginfo, as Reflection gives it) into the
// resource Truffle PHP's built-ins take their names and defaults from for named arguments (wiki/named-arguments.md
// 4.6), for the extensions tools/builtin-arity.php lists. Run on the reference PHP with mysqli:
//   docker run --rm -v "$PWD:/w" -w /w bootshare-php:8.5 php -d auto_prepend_file= tools/builtin-named-params.php \
//     > src/main/resources/pl/codeloom/php/builtins/named-params.txt
// Output lines: <function or Class::method> then a tab-separated parameter each: [&|~][...]name[=default], & by
// reference, ~ by reference if a variable is passed (ZEND_SEND_PREFER_REF), ... variadic; the default rawurlencode()d
// serialize() of ReflectionParameter::getDefaultValue(), or ? when php-src doesn't know it (zend_handle_undef_args'
// "must be passed explicitly"); none for a required parameter.
$extensions = ['Core', 'standard', 'date', 'pcre', 'ctype', 'curl', 'json', 'filter', 'hash', 'SPL', 'mbstring',
    'random', 'Reflection', 'mysqli'];
$params = function (ReflectionFunctionAbstract $f): string {
    $out = '';
    foreach ($f->getParameters() as $p) {
        $text = ($p->isPassedByReference() ? ($p->canBePassedByValue() ? '~' : '&') : '')
            . ($p->isVariadic() ? '...' : '') . $p->getName();
        if ($p->isOptional() && !$p->isVariadic()) {
            $text .= '=' . ($p->isDefaultValueAvailable() ? rawurlencode(serialize($p->getDefaultValue())) : '?');
        }
        $out .= "\t" . $text;
    }
    return $out;
};
foreach ($extensions as $extension) {
    $ext = new ReflectionExtension($extension);
    foreach ($ext->getFunctions() as $name => $function) {
        echo $name, $params($function), "\n";
    }
    foreach ($ext->getClassNames() as $class) {
        foreach ((new ReflectionClass($class))->getMethods() as $method) {
            if ($method->class !== $class && $method->isAbstract()) {
                continue;
            }
            echo $class, '::', $method->name, $params($method), "\n";
        }
    }
}
