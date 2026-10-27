package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;

import java.util.Arrays;

/**
 * A foreach by reference in progress: php-src's FE_RESET_RW temporary (the reference to the iterated variable,
 * which the loop holds) and its hash iterator. Each step follows the array now in that reference, as
 * zend_hash_iterator_pos_ex does: the same array continues at the iterator's position; a copy of it made while the
 * loop ran (zend_array_dup copies the iterators of the array it duplicates) continues at the position the copy was
 * made at; any other array, made exclusive first, starts at its internal pointer.
 *
 * <p>The arrays an iteration walks or tracks count it ({@link PhpArray#isIterated}), so their copies and rehashes
 * report here. The context links the iterations in progress, innermost first ({@link #outer}): entering and leaving
 * a loop are a few field writes, which compiled code keeps inline.
 */
public final class RefIteration {
    /** A position past every element. */
    public static final int END = Integer.MAX_VALUE;
    private static final PhpArray[] NO_COPIES = new PhpArray[0];
    private static final int[] NO_POSITIONS = new int[0];

    private final PhpRef ref;
    private PhpArray array;
    /** Where the next step looks for an element (php-src's iterator pos): a position, or END. */
    private int position;
    /** Copies of the array (or of its copies) made during the loop, with the position each was made at. */
    private PhpArray[] copies = NO_COPIES;
    private int[] copyPositions = NO_POSITIONS;
    private int copyCount;
    /** The iteration in progress when this one started (the loop this one runs inside), null for the outermost. */
    private RefIteration outer;

    private RefIteration(PhpRef ref, PhpArray array, RefIteration outer) {
        this.ref = ref;
        this.array = array;
        this.outer = outer;
    }

    /**
     * FE_RESET_RW over a reference holding an array: the loop holds the reference (one more binding) and walks
     * its array, made exclusive, from the start.
     */
    public static RefIteration start(PhpContext context, PhpRef ref) {
        ref.bind();
        PhpArray array = ref.arrayForWrite();
        RefIteration iteration = new RefIteration(ref, array, context.innermostRefIteration());
        array.track();
        context.setInnermostRefIteration(iteration);
        return iteration;
    }

    /** FE_FREE: the loop releases the reference and the iterator (and its copies). */
    public void end(PhpContext context) {
        if (context.innermostRefIteration() == this) {
            context.setInnermostRefIteration(outer);
        } else {
            unlink(context);
        }
        ref.unbind();
        array.untrack();
        for (int i = 0; i < copyCount; i++) {
            copies[i].untrack();
        }
    }

    /** Loops end innermost first (their finally blocks unwind in order), so this is a safety net. */
    @TruffleBoundary
    private void unlink(PhpContext context) {
        for (RefIteration it = context.innermostRefIteration(); it != null; it = it.outer) {
            if (it.outer == this) {
                it.outer = outer;
                return;
            }
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

    /**
     * The reference holds another array: a copy of the iterated one (whose iterator copy it keeps), or any other
     * array. The other iterator copies go, as zend_hash_iterator_pos_ex removes them.
     */
    @TruffleBoundary
    private PhpArray follow(PhpArray now) {
        int copied = copyPosition(now);
        array.untrack();
        for (int i = 0; i < copyCount; i++) {
            if (copies[i] != now) {
                copies[i].untrack();
            }
        }
        copyCount = 0;
        if (copied >= 0) {
            array = now;
            position = copied;
        } else {
            array = null;
            PhpArray exclusive = ref.arrayForWrite();
            exclusive.track();
            array = exclusive;
            int pointer = exclusive.pointer();
            position = pointer < 0 ? END : pointer;
        }
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
        copy.track();
    }

    /** zend_array_dup_ht_iterators: an iteration over source (or over a copy of it) gains copy at its position. */
    @TruffleBoundary
    static void copied(PhpArray source, PhpArray copy) {
        for (RefIteration it = PhpContext.get(null).innermostRefIteration(); it != null; it = it.outer) {
            if (it.array == source) {
                it.addCopy(copy, it.position);
            } else {
                int at = it.copyPosition(source);
                if (at >= 0) {
                    it.addCopy(copy, at);
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
        for (RefIteration it = PhpContext.get(null).innermostRefIteration(); it != null; it = it.outer) {
            if (it.array == hashed) {
                it.position = compacted(it.position, oldKeys, oldUsed);
            }
            for (int i = 0; i < it.copyCount; i++) {
                if (it.copies[i] == hashed) {
                    it.copyPositions[i] = compacted(it.copyPositions[i], oldKeys, oldUsed);
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
