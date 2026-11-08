<?php
// Issue 063: include_once of a missing file warns and returns false, each time.
var_dump(@include_once '/nonexistent/x.php');
var_dump(@include_once '/nonexistent/x.php');
