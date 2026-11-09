<?php
// Issue 063: include_once of a missing file warns and returns false, each time.
var_dump(@include_once '/nonexistent/x.php');
var_dump(@include_once '/nonexistent/x.php');
// include warns twice and gives false; a file counts as included once it has opened
var_dump(include '/nonexistent/a.php');
var_dump(include_once '/nonexistent/b.php');
var_dump(include_once '/nonexistent/b.php');
function f() { return include '/nonexistent/c.php'; }
var_dump(f());
// require warns, then throws an Error
function g() { require '/nonexistent/e.php'; }
try { g(); } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { include ''; } catch (ValueError $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
var_dump(require_once '/nonexistent/d.php');
echo "not reached\n";
