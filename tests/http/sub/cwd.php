<?php
// A web SAPI runs the script in its own directory (php_execute_script's VCWD_CHDIR_FILE; only the CLI keeps
// the caller's): WordPress's admin menu tests file_exists('edit.php') from wp-admin
var_dump(basename(str_replace('\\', '/', getcwd())), file_exists('cwd.php'), file_exists('../sub/cwd.php'),
    is_file(basename(__FILE__)));
