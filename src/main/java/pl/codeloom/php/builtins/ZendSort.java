package pl.codeloom.php.builtins;

import java.util.Comparator;

/**
 * zend_sort (Zend/zend_sort.c): insertion sort up to 16 elements, else libc++'s quicksort, with php-src's exact
 * comparisons and swaps, so an order that isn't total (NAN, a callback) sorts as in PHP. Ties keep the original
 * order (stable_sort_fallback), which makes the sort stable.
 */
final class ZendSort<T> {
    private final T[] items;
    private final int[] order;
    private final Comparator<? super T> compare;

    private ZendSort(T[] items, Comparator<? super T> compare) {
        this.items = items;
        this.compare = compare;
        order = new int[items.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
    }

    static <T> void sort(T[] items, Comparator<? super T> compare) {
        new ZendSort<>(items, compare).sort(0, items.length);
    }

    /** cmp(a, b) > 0, with the fallback to the original order. */
    private boolean greater(int a, int b) {
        int result = compare.compare(items[a], items[b]);
        return result != 0 ? result > 0 : order[a] > order[b];
    }

    private void swap(int a, int b) {
        T item = items[a];
        items[a] = items[b];
        items[b] = item;
        int position = order[a];
        order[a] = order[b];
        order[b] = position;
    }

    private void sort3(int a, int b, int c) {
        if (!greater(a, b)) {
            if (!greater(b, c)) {
                return;
            }
            swap(b, c);
            if (greater(a, b)) {
                swap(a, b);
            }
            return;
        }
        if (!greater(c, b)) {
            swap(a, c);
            return;
        }
        swap(a, b);
        if (greater(b, c)) {
            swap(b, c);
        }
    }

    private void sort4(int a, int b, int c, int d) {
        sort3(a, b, c);
        if (greater(c, d)) {
            swap(c, d);
            if (greater(b, c)) {
                swap(b, c);
                if (greater(a, b)) {
                    swap(a, b);
                }
            }
        }
    }

    private void sort5(int a, int b, int c, int d, int e) {
        sort4(a, b, c, d);
        if (greater(d, e)) {
            swap(d, e);
            if (greater(c, d)) {
                swap(c, d);
                if (greater(b, c)) {
                    swap(b, c);
                    if (greater(a, b)) {
                        swap(a, b);
                    }
                }
            }
        }
    }

    /** zend_insert_sort */
    private void insertSort(int start, int count) {
        switch (count) {
            case 0, 1 -> {
            }
            case 2 -> {
                if (greater(start, start + 1)) {
                    swap(start, start + 1);
                }
            }
            case 3 -> sort3(start, start + 1, start + 2);
            case 4 -> sort4(start, start + 1, start + 2, start + 3);
            case 5 -> sort5(start, start + 1, start + 2, start + 3, start + 4);
            default -> {
                int end = start + count;
                int sentry = start + 6;
                for (int i = start + 1; i < sentry; i++) {
                    int j = i - 1;
                    if (!greater(j, i)) {
                        continue;
                    }
                    while (j != start) {
                        j--;
                        if (!greater(j, i)) {
                            j++;
                            break;
                        }
                    }
                    for (int k = i; k > j; k--) {
                        swap(k, k - 1);
                    }
                }
                for (int i = sentry; i < end; i++) {
                    int j = i - 1;
                    if (!greater(j, i)) {
                        continue;
                    }
                    while (true) {
                        j -= 2;
                        if (!greater(j, i)) {
                            j++;
                            if (!greater(j, i)) {
                                j++;
                            }
                            break;
                        }
                        if (j == start) {
                            break;
                        }
                        if (j == start + 1) {
                            j--;
                            if (greater(i, j)) {
                                j++;
                            }
                            break;
                        }
                    }
                    for (int k = i; k > j; k--) {
                        swap(k, k - 1);
                    }
                }
            }
        }
    }

    private void sort(int base, int count) {
        while (true) {
            if (count <= 16) {
                insertSort(base, count);
                return;
            }
            int start = base;
            int end = start + count;
            int offset = count >> 1;
            int pivot = start + offset;
            if ((count >> 10) != 0) {
                int delta = offset >> 1;
                sort5(start, start + delta, pivot, pivot + delta, end - 1);
            } else {
                sort3(start, pivot, end - 1);
            }
            swap(start + 1, pivot);
            pivot = start + 1;
            int i = pivot + 1;
            int j = end - 1;
            partition:
            while (true) {
                while (greater(pivot, i)) {
                    i++;
                    if (i == j) {
                        break partition;
                    }
                }
                j--;
                if (j == i) {
                    break;
                }
                while (greater(j, pivot)) {
                    j--;
                    if (j == i) {
                        break partition;
                    }
                }
                swap(i, j);
                i++;
                if (i == j) {
                    break;
                }
            }
            swap(pivot, i - 1);
            if ((i - 1) - start < end - i) {
                sort(start, (i - start) - 1);
                base = i;
                count = end - i;
            } else {
                sort(i, end - i);
                count = (i - start) - 1;
            }
        }
    }
}
