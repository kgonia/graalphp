package pl.codeloom.php.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads Gate 1 corpus headings into a PhpArray of ByteStr values (matching bench/gate1/corpus.php). */
final class Corpus {
    private static final Pattern HEADING = Pattern.compile("<h[1-6][^>]*>(.*?)</h[1-6]>", Pattern.DOTALL);
    private final String text;
    private int at;

    private Corpus(Path file) throws IOException {
        text = Files.readString(file, StandardCharsets.ISO_8859_1);
        at = text.indexOf("return [");
        expect("return [");
    }

    private void space() {
        while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) at++;
    }

    private void expect(String token) {
        space();
        if (!text.startsWith(token, at)) throw new IllegalArgumentException("Expected " + token + " at " + at);
        at += token.length();
    }

    private String quoted() {
        expect("'");
        StringBuilder s = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '\'') return s.toString();
            if (c == '\\' && (text.charAt(at) == '\\' || text.charAt(at) == '\'')) c = text.charAt(at++);
            s.append(c);
        }
        throw new IllegalArgumentException("Unterminated PHP string literal");
    }

    static PhpArray headings(Path file) throws IOException {
        Corpus src = new Corpus(file);
        PhpArray headings = new PhpArray();
        src.space();
        while (src.text.charAt(src.at) == '[') {
            src.expect("[");
            headings.append(ByteStr.of(src.quoted()));
            src.expect(",");
            Matcher matches = HEADING.matcher(src.quoted());
            while (matches.find()) headings.append(ByteStr.of(matches.group(1)));
            src.expect(",");
            src.space();
            src.expect(src.text.startsWith("true", src.at) ? "true" : "false");
            src.expect("],");
            src.space();
        }
        src.expect("];");
        src.space();
        if (src.at != src.text.length()) throw new IllegalArgumentException("Trailing corpus data");
        return headings;
    }
}
