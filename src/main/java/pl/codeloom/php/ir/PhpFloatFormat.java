package pl.codeloom.php.ir;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** zend_gcvt decimal formatting shared by the scanner and the runtimes. */
public final class PhpFloatFormat {
    private PhpFloatFormat() {}

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
