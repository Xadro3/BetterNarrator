package com.betternarrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Align recognized words while tolerating punctuation, case, and cropped first lines. */
public final class TextContinuity {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*");
    private static final class Word {
        final String value;
        final int end;
        Word(String value, int end) { this.value = value; this.end = end; }
    }
    private static List<Word> words(String text) {
        List<Word> result = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) result.add(new Word(m.group().toLowerCase(Locale.ROOT).replace('’', '\''), m.end()));
        return result;
    }
    public static String normalized(String text) {
        StringBuilder b = new StringBuilder();
        for (Word w : words(text)) b.append(w.value).append(' ');
        return b.toString().trim();
    }
    /** Returns null when a scroll cannot be aligned safely; never guess and skip text. */
    public static String afterScroll(String previous, String current) {
        List<Word> old = words(previous), next = words(current);
        if (old.isEmpty()) return current;
        if (normalized(previous).equals(normalized(current))) return "";
        int bestLength = 0, bestEnd = 0;
        // The crop can start with a clipped or misrecognized line. Allow up to 12 prefix words.
        for (int prefix = 0; prefix <= Math.min(12, next.size() - 1); prefix++) {
            int max = Math.min(old.size(), next.size() - prefix);
            for (int count = max; count >= 4; count--) {
                boolean match = true;
                for (int k = 0; k < count; k++) {
                    if (!old.get(old.size() - count + k).value.equals(next.get(prefix + k).value)) {
                        match = false; break;
                    }
                }
                if (match && count > bestLength) {
                    bestLength = count;
                    bestEnd = next.get(prefix + count - 1).end;
                    break;
                }
            }
        }
        return bestLength >= 4 ? current.substring(bestEnd).replaceFirst("^[\\s\\p{Punct}’”]+", "").trim() : null;
    }
}
