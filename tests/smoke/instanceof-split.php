<?php
// An instanceof in a function called from many sites with objects of many classes and with non-objects
// (WordPress's is_wp_error()): Truffle splits the function, a copy per caller, and each copy must answer as the
// original did. A bytecode copy kept the original's specialization state but not its caches, and answered false
// for an object of the class (wp_signon() went on with a WP_Error).
class WP_Error {}
class Post {}
class Term {}
class User {}

function is_error($thing) {
    return $thing instanceof WP_Error;
}

function site1($x) { return is_error($x); }
function site2($x) { return is_error($x); }
function site3($x) { return is_error($x); }
function site4($x) { return is_error($x); }

$error = new WP_Error();
$answers = [];
for ($i = 0; $i < 3; $i++) {
    // the original sees non-objects and objects of several classes: polymorphic, so its callers get copies
    $answers[] = site1(null);
    $answers[] = site1(false);
    $answers[] = site2(new Post());
    $answers[] = site2(new Term());
    $answers[] = site3(new User());
    // the copies
    $answers[] = site4($error);
    $answers[] = site1($error);
    $answers[] = site2($error);
    $answers[] = site3([1]);
    $answers[] = site3($error);
}
echo implode(' ', array_map(fn($a) => $a ? 'T' : 'F', $answers)), "\n";
