package com.betternarrator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Group complete sentences for streaming while bounding unusually long OCR sentences. */
public final class SpeechChunks {
    private static final int TARGET = 500, MAX_GROUP = 800, MAX_SENTENCE = 350;
    private static final Pattern END = Pattern.compile("[.!?][\\\"'’”)]*(?:\\s+|$)");
    private SpeechChunks() { }
    public static List<String> split(String text) {
        return group(text, TARGET, MAX_GROUP, MAX_SENTENCE);
    }
    /** Kitten's runtime ignores incremental callbacks, so bound each native generation call. */
    static List<String> forSynthesis(String text) { return group(text, 100, 220, 220); }
    private static List<String> group(String text, int target, int maxGroup, int maxSentence) {
        List<String> sentences = new ArrayList<>();
        String normalized = text.replaceAll("\\s+", " ").trim();
        Matcher matcher = END.matcher(normalized);
        int start = 0;
        while (matcher.find()) {
            bounded(sentences, normalized.substring(start, matcher.end()).trim(), maxSentence);
            start = matcher.end();
        }
        bounded(sentences, normalized.substring(start).trim(), maxSentence);
        List<String> groups = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        for (String sentence : sentences) {
            if (group.length() > 0 && group.length() + 1 + sentence.length() > maxGroup) {
                groups.add(group.toString()); group.setLength(0);
            }
            if (group.length() > 0) group.append(' ');
            group.append(sentence);
            if (group.length() >= target) { groups.add(group.toString()); group.setLength(0); }
        }
        if (group.length() > 0) groups.add(group.toString());
        return groups;
    }
    private static void bounded(List<String> output, String sentence, int maxSentence) {
        while (sentence.length() > maxSentence) {
            int end = sentence.lastIndexOf(' ', maxSentence);
            if (end <= 0) end = maxSentence;
            output.add(sentence.substring(0, end)); sentence = sentence.substring(end).trim();
        }
        if (!sentence.isEmpty()) output.add(sentence);
    }
}
