package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Generic (slow-path) arithmetic with PHP semantics: operands juggle to int or float, int results that overflow
 * become floats, and the division family raises DivisionByZeroError. Nodes handle the common int/float cases
 * themselves and fall back here.
 */
public final class Operators {
    private Operators() {
    }

    public static Object addLongs(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return (double) a + (double) b;
        }
        return r;
    }

    public static Object subLongs(long a, long b) {
        long r = a - b;
        if (((a ^ b) & (a ^ r)) < 0) {
            return (double) a - (double) b;
        }
        return r;
    }

    public static Object mulLongs(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        if (high == 0 && low >= 0 || high == -1 && low < 0) {
            return low;
        }
        return (double) a * (double) b;
    }

    public static Object divLongs(long a, long b) {
        if (b == 0) {
            throw divisionByZero("Division by zero");
        }
        if (a % b == 0 && !(a == Long.MIN_VALUE && b == -1)) {
            return a / b;
        }
        return (double) a / (double) b;
    }

    public static long modLongs(long a, long b) {
        if (b == 0) {
            throw divisionByZero("Modulo by zero");
        }
        if (b == -1) {
            return 0;
        }
        return a % b;
    }

    @TruffleBoundary
    private static PhpError divisionByZero(String message) {
        return new PhpError("DivisionByZeroError: " + message);
    }

    @TruffleBoundary
    public static Object add(Object a, Object b) {
        if (a instanceof PhpArray x && b instanceof PhpArray y) {
            return union(x, y);
        }
        Object x = Convert.toNumber(a);
        Object y = Convert.toNumber(b);
        if (x instanceof Long l && y instanceof Long r) {
            return addLongs(l, r);
        }
        return ((Number) x).doubleValue() + ((Number) y).doubleValue();
    }

    @TruffleBoundary
    public static Object sub(Object a, Object b) {
        Object x = Convert.toNumber(a);
        Object y = Convert.toNumber(b);
        if (x instanceof Long l && y instanceof Long r) {
            return subLongs(l, r);
        }
        return ((Number) x).doubleValue() - ((Number) y).doubleValue();
    }

    @TruffleBoundary
    public static Object mul(Object a, Object b) {
        Object x = Convert.toNumber(a);
        Object y = Convert.toNumber(b);
        if (x instanceof Long l && y instanceof Long r) {
            return mulLongs(l, r);
        }
        return ((Number) x).doubleValue() * ((Number) y).doubleValue();
    }

    @TruffleBoundary
    public static Object div(Object a, Object b) {
        Object x = Convert.toNumber(a);
        Object y = Convert.toNumber(b);
        if (x instanceof Long l && y instanceof Long r) {
            return divLongs(l, r);
        }
        double divisor = ((Number) y).doubleValue();
        if (divisor == 0) {
            throw divisionByZero("Division by zero");
        }
        return ((Number) x).doubleValue() / divisor;
    }

    @TruffleBoundary
    public static long mod(Object a, Object b) {
        return modLongs(Convert.toLong(a), Convert.toLong(b));
    }

    @TruffleBoundary
    public static Object pow(Object a, Object b) {
        Object x = Convert.toNumber(a);
        Object y = Convert.toNumber(b);
        if (x instanceof Long base && y instanceof Long exponent && exponent >= 0) {
            long result = 1;
            long factor = base;
            long e = exponent;
            boolean overflow = false;
            while (e > 0 && !overflow) {
                if ((e & 1) != 0) {
                    Object product = mulLongs(result, factor);
                    if (product instanceof Long l) {
                        result = l;
                    } else {
                        overflow = true;
                    }
                }
                e >>= 1;
                if (e > 0) {
                    Object square = mulLongs(factor, factor);
                    if (square instanceof Long l) {
                        factor = l;
                    } else {
                        overflow = true;
                    }
                }
            }
            if (!overflow) {
                return result;
            }
        }
        return Math.pow(((Number) x).doubleValue(), ((Number) y).doubleValue());
    }

    public static long shiftLeft(long a, long b) {
        if (b < 0) {
            throw new PhpError("ArithmeticError: Bit shift by negative number");
        }
        return b >= 64 ? 0 : a << b;
    }

    public static long shiftRight(long a, long b) {
        if (b < 0) {
            throw new PhpError("ArithmeticError: Bit shift by negative number");
        }
        return b >= 64 ? (a < 0 ? -1 : 0) : a >> b;
    }

    @TruffleBoundary
    public static Object negate(Object a) {
        Object x = Convert.toNumber(a);
        if (x instanceof Long l) {
            return l == Long.MIN_VALUE ? -(double) l : (Object) (-l);
        }
        return -((Double) x);
    }

    /** `$a + $b` on arrays: $a's entries, then $b's entries whose keys $a lacks. */
    @TruffleBoundary
    public static PhpArray union(PhpArray a, PhpArray b) {
        PhpArray result = a.copy();
        for (int p = b.firstPosition(); p >= 0; p = b.nextPosition(p)) {
            Object key = b.keyAt(p);
            if (!result.containsKey(key)) {
                result.put(key, b.valueAt(p));
            }
        }
        return result;
    }
}
