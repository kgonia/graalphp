package pl.codeloom.php.runtime;

import com.oracle.truffle.api.TruffleSafepoint;

import java.util.concurrent.locks.LockSupport;

/**
 * One direction of a fiber's hand-over: the thread waiting for its turn, and the other thread opening the gate. The
 * other side usually answers within microseconds (a scheduler's loop suspends right after a little work), and
 * waking a parked platform thread through the OS costs tens of them, so the waiter spins for a while before it
 * parks. Parking lets Truffle's safepoints through (a cancel, a close).
 *
 * <p>The waiter writes {@link #waiter} before it reads {@link #open}; the opener writes {@link #open} before it
 * reads {@link #waiter}. Both are volatile, so at least one sees the other's write: either the waiter finds the
 * gate open, or the opener unparks it (a permit given before the park keeps).
 */
final class FiberGate {
    /** How long a waiter spins before it parks. */
    private static final long SPIN_NANOS = 20_000;

    private volatile boolean open;
    private volatile Thread waiter;

    /** Lets the waiter through. */
    void open() {
        open = true;
        Thread parked = waiter;
        if (parked != null) {
            LockSupport.unpark(parked);
        }
    }

    /** Waits until the gate opens, and closes it again. */
    void await() {
        if (!open) {
            long start = System.nanoTime();
            do {
                Thread.onSpinWait();
            } while (!open && System.nanoTime() - start < SPIN_NANOS);
            if (!open) {
                waiter = Thread.currentThread();
                TruffleSafepoint.setBlockedThreadInterruptible(null, FiberGate::park, this);
                waiter = null;
            }
        }
        open = false;
    }

    private static void park(FiberGate gate) throws InterruptedException {
        while (!gate.open) {
            LockSupport.park(gate);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }
}
