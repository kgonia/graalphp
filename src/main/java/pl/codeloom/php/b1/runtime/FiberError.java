package pl.codeloom.php.b1.runtime;

final class FiberError {
    static B1Thrown state(B1RequestContext ctx,String message) { return ctx.error("FiberError",message); }
    private FiberError() {}
}
