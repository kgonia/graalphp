package pl.codeloom.php.b1.runtime;

/** One request owns this worker until shutdown. A suspended stack keeps its worker. */
final class B1FiberThread implements Runnable {
    private final B1FiberGate assigned = new B1FiberGate();
    private final Thread thread;
    private B1Fiber fiber;

    B1FiberThread() {
        thread = Thread.ofPlatform().name("B1 PHP Fiber").stackSize(16L << 20).daemon(true).start(this);
    }
    void assign(B1Fiber next) { fiber = next; assigned.open(); }
    void retire() { assign(null); }
    void join() {
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(); } catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Override public void run() {
        for (;;) {
            assigned.await();
            B1Fiber job = fiber;
            fiber = null;
            if (job == null) return;
            job.runOn(this);
        }
    }
}
