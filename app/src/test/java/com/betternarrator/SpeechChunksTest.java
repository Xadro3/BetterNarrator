package com.betternarrator;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SpeechChunksTest {
    @Test public void shortSentencesAreGroupedTogether() {
        String text = "The ship was quiet. A bell rang. She looked up and smiled.";
        assertEquals(java.util.Collections.singletonList(text), SpeechChunks.split(text));
    }
    @Test public void quotesAndWordOrderArePreserved() {
        String text = "\"Are we there?\" she asked. \"Almost!\" He pointed toward the shore.";
        assertEquals(text, String.join(" ", SpeechChunks.split(text)));
    }
    @Test public void longPassagesStayBoundedWithoutLosingText() {
        String text = ("The travelers waited beside the river while the small boat approached the shore. ").repeat(30).trim();
        List<String> chunks = SpeechChunks.split(text);
        assertTrue(chunks.size() > 1);
        assertEquals(text, String.join(" ", chunks));
        for (String chunk : chunks) assertTrue(chunk.length() <= 800);
    }
    @Test public void emptyOcrProducesNoSpeech() { assertTrue(SpeechChunks.split(" \n\t ").isEmpty()); }
    @Test public void kittenProducesMultipleBatchesWithoutDroppingText() {
        String text = "The ship moved slowly across the quiet harbor. Above the water, the morning sky was clear. "
                + "A bell rang from the distant tower. The travelers gathered their bags and waited for the captain.";
        List<String> batches = SpeechChunks.forSynthesis(text);
        assertTrue(batches.size() > 1);
        assertEquals(text, String.join(" ", batches));
        for (String batch : batches) assertTrue(batch.length() <= 220);
    }
    @Test public void oversizedSentenceIsSplitAtWords() {
        String text = ("sky ").repeat(400).trim();
        List<String> chunks = SpeechChunks.split(text);
        assertEquals(text, String.join(" ", chunks));
        for (String chunk : chunks) assertTrue(chunk.length() <= 800);
    }
}
