package com.betternarrator;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoiceSettingsUiTest {
    @Test public void onOffAreFirstAndStayVisibleWhileSettingsScroll() {
        Instrumentation test = InstrumentationRegistry.getInstrumentation();
        MainActivity activity = open(test);
        try {
            test.runOnMainSync(() -> {
                View on = find(activity.getWindow().getDecorView(), "Turn narrator on");
                View off = find(activity.getWindow().getDecorView(), "Turn narrator off");
                View engine = find(activity.getWindow().getDecorView(), "Narration engine");
                ScrollView settings = (ScrollView) find(activity.getWindow().getDecorView(), "Reader settings");
                Rect onBounds = new Rect(), offBounds = new Rect(), engineBounds = new Rect();
                assertTrue(on.getGlobalVisibleRect(onBounds)); assertTrue(off.getGlobalVisibleRect(offBounds));
                assertTrue(engine.getGlobalVisibleRect(engineBounds)); assertTrue(engineBounds.top > onBounds.bottom);
                float density = activity.getResources().getDisplayMetrics().density;
                assertTrue(on.getHeight() >= 56 * density); assertTrue(off.getHeight() >= 56 * density);
                assertTrue(onBounds.centerY() < activity.getResources().getDisplayMetrics().heightPixels / 2);
                settings.fullScroll(View.FOCUS_DOWN);
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                Rect bounds = new Rect();
                assertTrue(find(activity.getWindow().getDecorView(), "Turn narrator on").getGlobalVisibleRect(bounds));
                assertTrue(find(activity.getWindow().getDecorView(), "Turn narrator off").getGlobalVisibleRect(bounds));
            });
        } finally { test.runOnMainSync(activity::finish); }
    }
    @Test public void modelDialogSurvivesRefreshAndPreservesVoiceChoices() throws Exception {
        Instrumentation test = InstrumentationRegistry.getInstrumentation(); test.getUiAutomation();
        MainActivity activity = open(test);
        try {
            Button engine = (Button) find(activity.getWindow().getDecorView(), "Narration engine");
            Button voice = (Button) find(activity.getWindow().getDecorView(), "Narrator voice");
            choose(test, engine, "Kitten Nano · compact offline voice");
            assertEquals("kitten", VoiceSettings.engine(activity.getSharedPreferences("reader", 0)));
            choose(test, voice, "Voice 3 · female");
            assertEquals(3, VoiceSettings.voiceIndex(activity.getSharedPreferences("reader", 0)));
            choose(test, engine, "Kokoro · offline voice");
            assertEquals("kokoro", VoiceSettings.engine(activity.getSharedPreferences("reader", 0)));
            choose(test, engine, "Kitten Nano · compact offline voice");
            assertEquals(3, VoiceSettings.voiceIndex(activity.getSharedPreferences("reader", 0)));
            test.runOnMainSync(() -> assertTrue(voice.getText().toString().contains("Voice 3 · female")));
        } finally { test.runOnMainSync(activity::finish); }
    }
    private static MainActivity open(Instrumentation test) {
        MainActivity result = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync(); return result;
    }
    private static void choose(Instrumentation test, Button button, String text) throws Exception {
        test.runOnMainSync(button::performClick); Thread.sleep(1100);
        AccessibilityNodeInfo choice = null;
        for (int attempt = 0; attempt < 30 && choice == null; attempt++) {
            AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            if (root != null) choice = root.findAccessibilityNodeInfosByText(text).stream()
                    .filter(node -> text.contentEquals(node.getText() == null ? "" : node.getText())).findFirst().orElse(null);
            if (choice == null) Thread.sleep(100);
        }
        assertNotNull("Setting dialog must stay open across status refresh", choice);
        assertTrue(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK)); test.waitForIdleSync();
    }
    private static View find(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { View match = find(group.getChildAt(i), description); if (match != null) return match; }
        }
        return null;
    }
}
