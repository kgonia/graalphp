<?php
// The parameters of PHP's internal functions (their arginfo), which var_dump() of a closure made from a built-in
// shows: one line per function, "name<TAB>required count<TAB>names", a by-reference parameter's name with "&", a
// variadic one's with "...". Regenerate with the reference PHP after it changes:
//   bench/gate1/php.sh php tools/builtin-params.php > src/main/resources/pl/codeloom/php/builtins/params.txt
$functions = get_defined_functions()['internal'];
sort($functions);
foreach ($functions as $name) {
    if (str_starts_with($name, 'ast\\')) {
        continue; // php-ast, which exports the reference PHP's trees
    }
    $function = new ReflectionFunction($name);
    $params = [];
    foreach ($function->getParameters() as $param) {
        $params[] = ($param->isPassedByReference() ? '&' : '') . ($param->isVariadic() ? '...' : '') . $param->getName();
    }
    echo $name, "\t", $function->getNumberOfRequiredParameters(), "\t", implode(',', $params), "\n";
}
