package pl.codeloom.php.b1.runtime;

/** Internal unwind that crosses PHP catches and executes finally blocks. */
final class B1ForceClose extends RuntimeException {
    B1ForceClose() { super(null,null,false,false); }
}
