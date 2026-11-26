package pl.codeloom.php.ir;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** zend_gcvt decimal formatting shared by the scanner and the runtimes. */
public final class PhpFloatFormat {
    private PhpFloatFormat() {}

    /** zend_dval_to_lval_slow on a 64-bit build. */
    public static long wrapToLong(double value) {
        double remainder = value % 0x1p64;
        if (remainder >= 0x1p63) remainder -= 0x1p64;
        else if (remainder < -0x1p63) remainder += 0x1p64;
        return (long)remainder;
    }

    public static String doubleToRepr(double d) {
        if (!Double.isFinite(d)) {
            return Double.isNaN(d) ? "NAN" : d > 0 ? "INF" : "-INF";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0" : "0";
        }
        double abs = Math.abs(d);
        BigDecimal shortest = new BigDecimal(Double.toString(abs));
        if (shortest.precision() == 2) {
            // Double.toString picks among 1- and 2-digit decimals when one digit would do (4.9E-324); dtoa
            // takes the 1-digit one closest to d
            BigDecimal down = shortest.round(new MathContext(1, RoundingMode.FLOOR));
            BigDecimal up = shortest.round(new MathContext(1, RoundingMode.CEILING));
            boolean downOk = down.doubleValue() == abs;
            boolean upOk = up.doubleValue() == abs;
            if (downOk || upOk) {
                BigDecimal exact = new BigDecimal(abs);
                shortest = downOk && (!upOk || exact.subtract(down).compareTo(up.subtract(exact)) <= 0) ? down : up;
            }
        }
        String digits = shortest.unscaledValue().toString();
        return (d < 0 ? "-" : "") + gcvt(digits, shortest.precision() - shortest.scale(), 17);
    }

    public static String formatDouble(double d, int precision) {
        if (Double.isNaN(d)) {
            return "NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "INF" : "-INF";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0" : "0";
        }
        BigDecimal rounded = new BigDecimal(Math.abs(d)).round(new MathContext(precision, RoundingMode.HALF_EVEN));
        String digits = rounded.unscaledValue().toString();
        int decimalPoint = rounded.precision() - rounded.scale();
        return (d < 0 ? "-" : "") + gcvt(digits, decimalPoint, precision);
    }

    /**
     * Lays out significant digits as zend_gcvt does: digits "d1d2..." with the decimal point after decimalPoint
     * digits; exponential form ("1.0E+25") when decimalPoint < -3 or > ndigit.
     */
    public static String gcvt(String rawDigits, int decimalPoint, int ndigit) {
        int significant = rawDigits.length();
        while (significant > 0 && rawDigits.charAt(significant - 1) == '0') {
            significant--;
        }
        String digits = significant == 0 ? "0" : rawDigits.substring(0, significant);
        if (decimalPoint < -3 || decimalPoint > ndigit) {
            StringBuilder out = new StringBuilder();
            out.append(digits.charAt(0)).append('.');
            out.append(digits.length() > 1 ? digits.substring(1) : "0");
            int exponent = decimalPoint - 1;
            out.append('E').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
            return out.toString();
        }
        if (decimalPoint <= 0) {
            return "0." + "0".repeat(-decimalPoint) + digits;
        }
        if (digits.length() <= decimalPoint) {
            return digits + "0".repeat(decimalPoint - digits.length());
        }
        return digits.substring(0, decimalPoint) + "." + digits.substring(decimalPoint);
    }
}
