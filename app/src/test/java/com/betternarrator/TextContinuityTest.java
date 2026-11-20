package com.betternarrator;

import org.junit.Test;
import static org.junit.Assert.*;

public class TextContinuityTest {
    @Test public void overlapIsSpokenOnlyOnce() {
        assertEquals("The journey continued.", TextContinuity.afterScroll(
                "They stepped outside. The bright sky stretched above the harbor.",
                "The bright sky stretched above the harbor. The journey continued."));
    }
    @Test public void croppedFirstLineAndPunctuationAreTolerated() {
        assertEquals("A bell rang.", TextContinuity.afterScroll(
                "She said, ‘We will return before the sun sets.’",
                "garbled edge WE will return before the sun sets! A bell rang."));
    }
    @Test public void noOverlapRequiresManualRecovery() {
        assertNull(TextContinuity.afterScroll("A distant mountain rose above the quiet valley.",
                "Several days later they arrived in a city."));
    }
    @Test public void unchangedCaptureDoesNotRepeat() {
        assertEquals("", TextContinuity.afterScroll("The sea was calm.", "THE SEA was calm!"));
    }
    @Test public void repeatedWordsDoNotCauseAShortFalseMatch() {
        assertNull(TextContinuity.afterScroll("She whispered the end", "the end was only the beginning"));
    }
}
