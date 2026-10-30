/*
 * Hand-written.
 */
package pl.codeloom.php.parser.zend;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A script's text, decoded so that encoding it again gives its bytes back: as UTF-8 when the bytes are valid UTF-8,
 * else as ISO-8859-1 (one char per byte, as a script in a single-byte encoding is). The runtime's Truffle Source
 * holds this text, and the function bodies left unparsed ({@link SourceBody}) are ranges of it, so a body is parsed
 * again from the very bytes it was first parsed from without the file's bytes being kept beside the text.
 */
public final class ScriptText {
    private final String text;
    private final Charset charset;

    private ScriptText(String text, Charset charset) {
        this.text = text;
        this.charset = charset;
    }

    public static ScriptText decode(byte[] script) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(script)).toString();
            return new ScriptText(text, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return new ScriptText(new String(script, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
        }
    }

    /** The text, for the runtime's Source. */
    public String text() {
        return text;
    }

    /** The charset the bytes were decoded with (UTF-8 or ISO-8859-1). */
    public Charset charset() {
        return charset;
    }

    /** The bytes of text[charFrom, charTo), as in the script. */
    byte[] bytes(int charFrom, int charTo) {
        return text.substring(charFrom, charTo).getBytes(charset);
    }

    /** Char offsets of byte offsets of the script, for offsets asked in mostly increasing order. */
    final class Offsets {
        private final byte[] bytes;
        private int byteAt;
        private int charAt;

        Offsets(byte[] bytes) {
            this.bytes = bytes;
        }

        int charOffset(int byteOffset) {
            if (charset != StandardCharsets.UTF_8) {
                return byteOffset;
            }
            if (byteOffset < byteAt) {
                byteAt = 0;
                charAt = 0;
            }
            for (; byteAt < byteOffset; byteAt++) {
                int b = bytes[byteAt] & 0xff;
                // a char per sequence's first byte, two for a supplementary code point (a surrogate pair)
                if ((b & 0xC0) != 0x80) {
                    charAt += b >= 0xF0 ? 2 : 1;
                }
            }
            return charAt;
        }
    }
}
