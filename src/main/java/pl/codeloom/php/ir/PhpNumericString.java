package pl.codeloom.php.ir;

/** PHP numeric-string grammar used by both backends. */
public final class PhpNumericString {
    private PhpNumericString() {}

    private static boolean isWhitespace(int b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == 0x0B || b == '\f';
    }

    /**
     * Parses a PHP numeric string as php-src's _is_numeric_string_ex does: optional leading whitespace, sign,
     * digits, fraction and exponent, optional trailing whitespace. Returns Long or Double, or null when the
     * string isn't numeric. With allowTrailing, a numeric prefix followed by other bytes ("12abc") also counts,
     * as in arithmetic and (int) casts.
     */
    public static Number parse(byte[] s, boolean allowTrailing) {
        int n = s.length;
        int i = 0;
        while (i < n && isWhitespace(s[i])) {
            i++;
        }
        int start = i;
        if (i < n && (s[i] == '+' || s[i] == '-')) {
            i++;
        }
        int digitsStart = i;
        while (i < n && s[i] >= '0' && s[i] <= '9') {
            i++;
        }
        int intDigits = i - digitsStart;
        boolean isDouble = false;
        int fracDigits = 0;
        if (i < n && s[i] == '.') {
            int j = i + 1;
            while (j < n && s[j] >= '0' && s[j] <= '9') {
                j++;
            }
            fracDigits = j - i - 1;
            if (intDigits > 0 || fracDigits > 0) {
                isDouble = true;
                i = j;
            }
        }
        if (intDigits == 0 && fracDigits == 0) {
            return null;
        }
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            int j = i + 1;
            if (j < n && (s[j] == '+' || s[j] == '-')) {
                j++;
            }
            int expStart = j;
            while (j < n && s[j] >= '0' && s[j] <= '9') {
                j++;
            }
            if (j > expStart) {
                isDouble = true;
                i = j;
            }
        }
        int end = i;
        while (i < n && isWhitespace(s[i])) {
            i++;
        }
        if (i < n && !allowTrailing) {
            return null;
        }
        String text = new String(s, start, end - start, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!isDouble) {
            try {
                return Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
            } catch (NumberFormatException overflow) {
                return Double.parseDouble(text);
            }
        }
        return Double.parseDouble(text);
    }

}
