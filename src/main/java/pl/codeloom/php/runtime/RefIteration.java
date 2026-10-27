package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;

import java.util.Arrays;
import java.util.List;

/**
 * A foreach by reference in progress: php-src's FE_RESET_RW temporary (the reference to the iterated variable,
 * which the loop holds) and its hash iterator. Each step follows the array now in that reference, as
 * zend_hash_iterator_pos_ex does: the same array continues at the iterator's position; a copy of it made while the
 * loop ran (zend_array_dup copies the iterators of the array it duplicates) continues at the position the copy was
 * made at; any other array, made exclusive first, starts at its internal pointer.
 *
 * <p>Arrays under an iteration are {@link PhpArray#isIterated marked}, so their copies and rehashes report here;
 * the context keeps the iterations in progress.
 */
public final class RefIteration {
    /** A position past every element. */
    public static final int END = Integer.MAX_VALUE;

    private final PhpRef ref;
    private PhpArray array;
    /** Where the next step looks for an element (php-src's iterator pos): a position, or END. */
    private int position;
    /** Copies of the array (or of its copies) made during the loop, with the position each was made at. */
    private PhpArray[] copies = new PhpArray[0];
    private int[] copyPositions = new int[0];
    private int copyCount;

    private RefIteration(PhpRef ref, PhpArray array) {
        this.ref = ref;
        this.array = array;
    }

    /**
     * FE_RESET_RW over a reference holding an array: the loop holds the reference (one more binding) and walks
     * its array, made exclusive, from the start.
     */
    @TruffleBoundary
    public static RefIteration start(PhpContext context, PhpRef ref) {
        ref.bind();
        RefIteration iteration = new RefIteration(ref, ref.arrayForWrite());
        iteration.array.setIterated(true);
        context.refIterations().add(iteration);
        return iteration;
    }

    /** FE_FREE: the loop releases the reference and the iterator. */
    @TruffleBoundary
    public void end(PhpContext context) {
        List<RefIteration> active = context.refIterations();
        active.remove(this);
        ref.unbind();
        unmark(active, array);
        for (int i = 0; i < copyCount; i++) {
            unmark(active, copies[i]);
        }
    }

    /**
     * The array a step walks: the one in the reference, following it as php-src's iterator does. Null when the
     * reference no longer holds an array (the caller warns and ends the loop).
     */
    public PhpArray current() {
        Object now = ref.get();
        if (now == array) {
            return array;
        }
        return now instanceof PhpArray other ? follow(other) : null;
    }

    /** The reference holds another array: a copy of the iterated one, or any other array. */
    @TruffleBoundary
    private PhpArray follow(PhpArray now) {
        int copied = copyPosition(now);
        List<RefIteration> active = PhpContext.get(null).refIterations();
        unmark(active, array);
        for (int i = 0; i < copyCount; i++) {
            if (copies[i] != now) {
                unmark(active, copies[i]);
            }
        }
        copyCount = 0;
        if (copied >= 0) {
            array = now;
            position = copied;
        } else {
            array = ref.arrayForWrite();
            int pointer = array.pointer();
            position = pointer < 0 ? END : pointer;
        }
        array.setIterated(true);
        return array;
    }

    public int position() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public PhpRef ref() {
        return ref;
    }

    private int copyPosition(PhpArray candidate) {
        for (int i = 0; i < copyCount; i++) {
            if (copies[i] == candidate) {
                return copyPositions[i];
            }
        }
        return -1;
    }

    private void addCopy(PhpArray copy, int at) {
        if (copyCount == copies.length) {
            copies = Arrays.copyOf(copies, Math.max(4, copyCount * 2));
            copyPositions = Arrays.copyOf(copyPositions, copies.length);
        }
        copies[copyCount] = copy;
        copyPositions[copyCount++] = at;
    }

    private boolean tracks(PhpArray candidate) {
        return array == candidate || copyPosition(candidate) >= 0;
    }

    private static void unmark(List<RefIteration> active, PhpArray candidate) {
        for (RefIteration other : active) {
            if (other.tracks(candidate)) {
                return;
            }
        }
        candidate.setIterated(false);
    }

    /** zend_array_dup_ht_iterators: an iteration over source (or over a copy of it) gains copy at its position. */
    @TruffleBoundary
    static void copied(PhpArray source, PhpArray copy) {
        for (RefIteration iteration : PhpContext.get(null).refIterations()) {
            if (iteration.array == source) {
                iteration.addCopy(copy, iteration.position);
                copy.setIterated(true);
            } else {
                int at = iteration.copyPosition(source);
                if (at >= 0) {
                    iteration.addCopy(copy, at);
                    copy.setIterated(true);
                }
            }
        }
    }

    /**
     * A hash store compacted its deleted slots (oldKeys, oldUsed: the store before): positions into it move to
     * where their slot went, as zend_hash_rehash moves the iterators.
     */
    @TruffleBoundary
    static void rehashed(PhpArray hashed, Object[] oldKeys, int oldUsed) {
        for (RefIteration iteration : PhpContext.get(null).refIterations()) {
            if (iteration.array == hashed) {
                iteration.position = compacted(iteration.position, oldKeys, oldUsed);
            }
            for (int i = 0; i < iteration.copyCount; i++) {
                if (iteration.copies[i] == hashed) {
                    iteration.copyPositions[i] = compacted(iteration.copyPositions[i], oldKeys, oldUsed);
                }
            }
        }
    }

    private static int compacted(int position, Object[] oldKeys, int oldUsed) {
        if (position == END) {
            return END;
        }
        int live = 0;
        for (int i = 0; i < Math.min(position, oldUsed); i++) {
            if (oldKeys[i] != null) {
                live++;
            }
        }
        return position >= oldUsed ? live + (position - oldUsed) : live;
    }
}
