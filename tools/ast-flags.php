<?php
// Prints php-ast's flag constants as "NAME=value" lines, the numbers the Truffle front end switches on.
foreach (get_defined_constants(true)['ast'] as $name => $value) {
    if (str_starts_with($name, 'ast\\flags\\')) {
        echo substr($name, strlen('ast\\flags\\')), '=', $value, "\n";
    }
}
