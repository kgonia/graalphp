<?php
function bump(&$x) { ++$x; }
$o = new stdClass(); $s = "\xff";
echo isset($o->missing), isset($s[0]), $s[0], "\n";
echo call_user_func_array('strlen',['string'=>'abc']), "\n";
call_user_func_array('bump',[1]);
try { call_user_func_array('strlen',1); } catch (Throwable $e) { echo $e->getMessage(), "\n"; }
try { strlen([]); } catch (Throwable $e) { echo $e->getMessage(), "\n"; }
try { count(); } catch (Throwable $e) { echo $e->getMessage(), "\n"; }
try { array_key_exists([],[]); } catch (Throwable $e) { echo $e->getMessage(), "\n"; }
try { array_slice([1],0,null,[]); } catch (Throwable $e) { echo $e->getMessage(), "\n"; }
