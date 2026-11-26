package pl.codeloom.php.ir;

/** PHP symbol names fold ASCII letters only. */
public final class AsciiCase {
    private AsciiCase() {}

    public static String lower(String s) {
        int i = 0;
        while (i < s.length() && !(s.charAt(i) >= 'A' && s.charAt(i) <= 'Z')) {
            i++;
        }
        if (i == s.length()) {
            return s;
        }
        char[] chars = s.toCharArray();
        for (; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] += 'a' - 'A';
            }
        }
        return new String(chars);
    }
}
