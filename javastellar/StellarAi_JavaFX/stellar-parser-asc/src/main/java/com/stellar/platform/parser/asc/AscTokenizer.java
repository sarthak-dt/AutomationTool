package com.stellar.platform.parser.asc;

/**
 * Whitespace tokenizer (spaces and tabs). No regex, no {@code String.split}.
 */
final class AscTokenizer {

    static final int MAX_TOKENS = 80;

    private AscTokenizer() {
    }

    static int tokenize(String line, String[] out) {
        int count = 0;
        int i = 0;
        int n = line.length();
        while (i < n && count < out.length) {
            while (i < n && isSpace(line.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            while (i < n && !isSpace(line.charAt(i))) {
                i++;
            }
            out[count++] = line.substring(start, i);
        }
        return count;
    }

    static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }
}
