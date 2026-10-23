<?php
// ext/mysqli's classes written in PHP: Truffle PHP's prelude (see spl.php). The functions are
// builtins/MysqliFunctions.java.

/** What a failing mysqli call throws under MYSQLI_REPORT_STRICT (PHP 8.1's default mysqli_report mode). */
final class mysqli_sql_exception extends RuntimeException
{
    protected string $sqlstate = '00000';

    public function getSqlState(): string
    {
        return $this->sqlstate;
    }
}
