package pl.codeloom.php.b1.runtime;

import java.util.concurrent.Semaphore;

/** Single-consumer handoff. Release/acquire publishes both the frame chain and the payload. */
final class B1FiberGate {
    private final Semaphore gate = new Semaphore(0);
    void open() { gate.release(); }
    void await() { gate.acquireUninterruptibly(); }
}
