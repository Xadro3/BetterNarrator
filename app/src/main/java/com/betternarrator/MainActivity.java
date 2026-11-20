package com.betternarrator;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** Fixed session controls above a compact, scrollable settings panel. */
public class MainActivity extends Activity {
    public static final String STORY_URL = "https://www.royalroad.com/fiction/107917/sky-pride/chapter/3524131/chapter-9--the-children-of-the-endless-blue-sky";
    private static final int INK = 0xFF172A33, MUTED = 0xFF53656C, GREEN = 0xFF146C59, SURFACE = 0xFFF0F5F5;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private android.content.SharedPreferences prefs;
    private Button on, off, engineChoice, voiceChoice, previewButton, advanceChoice;
    private TextView session, rateValue;
    private Button overlayAccess, pageAccess;
    private Switch automatic, early;
    private SpeechEngine preview;
    private boolean previewReady, previewPlaying, starting, updating;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateControls(); handler.postDelayed(this, 750); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().setStatusBarColor(SURFACE);
        LinearLayout root = column(); root.setBackgroundColor(SURFACE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout controls = card(root);
        label(controls, "Better Narrator", 20, INK).setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout actions = row(); controls.addView(actions);
        on = button("Turn on", v -> turnOn()); on.setContentDescription("Turn narrator on");
        on.setTextColor(Color.WHITE); on.setBackground(background(GREEN, 14)); on.setMinHeight(dp(64));
        off = button("Turn off", v -> {
            starting = false; closePreview(); stopService(new Intent(this, NarratorService.class));
            prefs.edit().putString("status", "Reader off.").apply(); updateControls();
        });
        off.setContentDescription("Turn narrator off"); off.setMinHeight(dp(64));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.setMargins(0, dp(8), dp(6), dp(8));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1); right.setMargins(dp(6), dp(8), 0, dp(8));
        actions.addView(on, left); actions.addView(off, right);
        session = label(controls, "Reader off", 14, MUTED); session.setMaxLines(2);
        session.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setContentDescription("Reader settings");
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout settings = column(); settings.setPadding(0, 0, 0, dp(16)); scroll.addView(settings);

        LinearLayout voiceCard = card(settings); title(voiceCard, "Voice");
        engineChoice = fullButton(voiceCard, "", v -> {
            if (NarratorService.isRunning()) return;
            choose("Voice engine", VoiceSettings.ENGINES, VoiceSettings.engineIndex(prefs), selected -> {
                closePreview(); prefs.edit().putString("voice_engine", VoiceSettings.ENGINE_IDS[selected]).apply(); updateControls();
            });
        }); engineChoice.setContentDescription("Narration engine");
        voiceChoice = fullButton(voiceCard, "", v -> choose("Narrator voice", VoiceSettings.voices(prefs), VoiceSettings.voiceIndex(prefs), selected -> {
            closePreview(); prefs.edit().putInt(VoiceSettings.voiceKey(prefs), selected).apply(); updateControls();
        })); voiceChoice.setContentDescription("Narrator voice");
        previewButton = fullButton(voiceCard, "Preview voice", v -> {
            if (previewPlaying || (preview != null && !previewReady)) closePreview(); else previewVoice();
        }); previewButton.setContentDescription("Preview or stop voice");
        LinearLayout speed = row(); speed.setGravity(Gravity.CENTER_VERTICAL); voiceCard.addView(speed);
        TextView speedLabel = label(speed, "Speed", 16, INK);
        speedLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button slower = button("−", v -> changeRate(-.1f)); slower.setContentDescription("Slower narration");
        speed.addView(slower, new LinearLayout.LayoutParams(dp(52), dp(52)));
        rateValue = label(speed, "", 16, INK); rateValue.setGravity(Gravity.CENTER);
        rateValue.setMinWidth(dp(64)); rateValue.setContentDescription("Narration speed");
        Button faster = button("+", v -> changeRate(.1f)); faster.setContentDescription("Faster narration");
        speed.addView(faster, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout reading = card(settings); title(reading, "Reading");
        String[] modes = {"Scroll", "Tap right", "Tap left"};
        advanceChoice = fullButton(reading, "", v -> {
            int selected = java.util.Arrays.asList(modes).indexOf(prefs.getString("mode", "Scroll"));
            choose("Page movement", modes, Math.max(0, selected), index -> { prefs.edit().putString("mode", modes[index]).apply(); updateControls(); });
        }); advanceChoice.setContentDescription("Page movement");
        automatic = toggle(reading, "Automatic reading", "Scroll or turn pages for me", checked -> {
            if (!updating) { prefs.edit().putBoolean("auto", checked).apply(); updateControls(); }
        }); automatic.setContentDescription("Automatic reading");
        early = toggle(reading, "Prepare next screen early", "Moves the page ahead while the voice continues", checked -> {
            if (!updating) prefs.edit().putBoolean("look_ahead", checked).apply();
        }); early.setContentDescription("Prepare next screen early");

        LinearLayout access = card(settings); title(access, "Device access");
        overlayAccess = fullButton(access, "", v -> openOverlaySettings());
        overlayAccess.setContentDescription("Floating controls permission");
        pageAccess = fullButton(access, "", v -> new AlertDialog.Builder(this)
                .setTitle("Page controls")
                .setMessage("Enable Better Narrator page controls in Accessibility → Downloaded apps. It performs your chosen swipe or tap for automatic reading. You can stop it with Turn off.")
                .setPositiveButton("Open settings", (d, which) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Cancel", null).show()); pageAccess.setContentDescription("Page controls permission");
        LinearLayout help = card(settings);
        fullButton(help, "How to use & session details", v -> showHelp());
        fullButton(help, "Voice credits & licenses", v -> showLicenses());
        label(help, "Voices and text recognition stay on your device.", 13, MUTED);

        setContentView(root); updateControls();
    }
    private void turnOn() {
        if (NarratorService.isRunning() || starting) return;
        closePreview();
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setTitle("Allow floating controls")
                    .setMessage("The reader needs floating controls so you can choose the text area and pause while using another app.")
                    .setPositiveButton("Open settings", (d, w) -> openOverlaySettings()).setNegativeButton("Cancel", null).show(); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notifications_requested", false)) {
            prefs.edit().putBoolean("notifications_requested", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101); return;
        }
        requestCapture();
    }
    private void requestCapture() {
        starting = true; updateControls();
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent capture = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) : manager.createScreenCaptureIntent();
        startActivityForResult(capture, 100);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 101) requestCapture();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 100) {
            starting = false;
            if (result == RESULT_OK && data != null) {
                startForegroundService(new Intent(this, NarratorService.class).putExtra("result", result).putExtra("capture", data));
                moveTaskToBack(true);
            }
            updateControls();
        }
    }
    private void updateControls() {
        if (on == null) return;
        boolean running = NarratorService.isRunning();
        on.setEnabled(!running && !starting); off.setEnabled(running || starting || preview != null);
        on.setAlpha(on.isEnabled() ? 1f : .5f); off.setAlpha(off.isEnabled() ? 1f : .5f);
        changed(session, starting ? "Waiting for screen sharing…" : running ? "On · " + prefs.getString("status", "Ready") : "Off · Ready when you are");
        changed(engineChoice, "Engine: " + (VoiceSettings.engine(prefs).equals("kitten") ? "Kitten Nano" : VoiceSettings.engine(prefs).equals("kokoro") ? "Kokoro" : "Android offline") + "  ▾");
        changed(voiceChoice, VoiceSettings.isNeural(prefs) ? VoiceSettings.voices(prefs)[VoiceSettings.voiceIndex(prefs)] + "  ▾" : "Installed English voice");
        engineChoice.setEnabled(!running && !starting); voiceChoice.setEnabled(!running && VoiceSettings.isNeural(prefs));
        engineChoice.setAlpha(engineChoice.isEnabled() ? 1f : .5f); voiceChoice.setAlpha(voiceChoice.isEnabled() ? 1f : .5f);
        previewButton.setEnabled(!running && !starting);
        changed(previewButton, previewPlaying ? "Stop preview" : preview != null && !previewReady ? "Loading voice · tap to cancel" : "Preview voice");
        changed(rateValue, String.format(Locale.US, "%.2f×", prefs.getFloat("rate", 1f)));
        changed(advanceChoice, "Page movement: " + prefs.getString("mode", "Scroll") + "  ▾"); advanceChoice.setEnabled(!running);
        advanceChoice.setAlpha(advanceChoice.isEnabled() ? 1f : .5f);
        updating = true;
        automatic.setChecked(prefs.getBoolean("auto", true)); early.setChecked(prefs.getBoolean("look_ahead", true));
        early.setEnabled(automatic.isChecked() && VoiceSettings.isNeural(prefs));
        updating = false;
        changed(overlayAccess, "Floating controls · " + (Settings.canDrawOverlays(this) ? "Ready" : "Enable") + "  ›");
        changed(pageAccess, "Page controls · " + (ReadingAccessibilityService.available() ? "Ready" : "Enable") + "  ›");
    }
    private void changeRate(float amount) {
        float rate = Math.max(.75f, Math.min(2f, Math.round((prefs.getFloat("rate", 1f) + amount) * 100) / 100f));
        prefs.edit().putFloat("rate", rate).apply(); updateControls();
    }
    private void previewVoice() {
        if (NarratorService.isRunning()) return;
        if (preview == null) {
            preview = VoiceSettings.create(this, new SpeechEngine.Listener() {
                @Override public void onReady() { previewReady = true; playPreview(); }
                @Override public void onStarted(String id) { previewPlaying = true; updateControls(); }
                @Override public void onDone(String id) { previewPlaying = false; updateControls(); }
                @Override public void onError(String id, String message) { toast(message); closePreview(); }
            }); updateControls();
        } else { preview.stop(); playPreview(); }
    }
    private void playPreview() {
        if (preview == null) return;
        previewPlaying = true; updateControls();
        preview.speak("The ship drifted beneath the endless blue sky. Somewhere beyond the clouds, a new adventure was waiting.",
                "preview", VoiceSettings.speaker(prefs), prefs.getFloat("rate", 1f));
    }
    private void closePreview() {
        previewReady = false; previewPlaying = false;
        if (preview != null) { preview.close(); preview = null; }
        updateControls();
    }
    private void openOverlaySettings() { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
    private void showHelp() {
        String text = "1. Turn on and allow screen sharing.\n2. Open your story and tap Area in the floating controls.\n3. Select complete text lines, then tap Read.\n\nPause stops audio. Read restarts the interrupted passage. Next skips to the next screen; Off ends the session.\n\nPrepare next screen early moves the page while buffered audio is playing. Switch it off to keep the current text visible until narration finishes. It needs automatic reading and a neural voice.\n\nAfter manual scrolling, choose Area again to reset your place. Stop the reader to change its engine, voice, or page movement.\n\nSession\n"
                + prefs.getString("status", "Reader off.") + "\n\nLast recognized text\n" + prefs.getString("last_text", "No text captured yet.");
        ScrollView scroll = new ScrollView(this); TextView body = new TextView(this); body.setText(text); body.setTextSize(15); body.setTextColor(INK);
        body.setPadding(dp(20), dp(16), dp(20), dp(16)); body.setTextIsSelectable(true); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Using Better Narrator").setView(scroll).setPositiveButton("Close", null)
                .setNeutralButton("Open test story", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(STORY_URL))); }
                    catch (ActivityNotFoundException e) { toast("Install a browser to open the story."); }
                }).show();
    }
    private void showLicenses() {
        try (java.io.InputStream input = getAssets().open("licenses/NOTICE.txt")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            new AlertDialog.Builder(this).setTitle("Voice credits").setMessage(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)).setPositiveButton("Close", null).show();
        } catch (java.io.IOException error) { toast("Could not load voice credits."); }
    }
    private void choose(String title, String[] choices, int selected, java.util.function.IntConsumer pick) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(choices, selected, (d, index) -> {
            pick.accept(index); d.dismiss();
        }).setNegativeButton("Close", null).create();
        dialog.show(); Window window = dialog.getWindow();
        if (window != null) { window.setGravity(Gravity.BOTTOM); window.setLayout(-1, -2); }
        if (dialog.getListView() != null && choices.length > 6) {
            ViewGroup.LayoutParams params = dialog.getListView().getLayoutParams(); params.height = dp(360); dialog.getListView().setLayoutParams(params);
        }
    }
    private Switch toggle(LinearLayout parent, String title, String detail, java.util.function.Consumer<Boolean> action) {
        LinearLayout line = row(); line.setGravity(Gravity.CENTER_VERTICAL); line.setMinimumHeight(dp(68)); parent.addView(line);
        LinearLayout words = column(); line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        label(words, title, 16, INK); label(words, detail, 13, MUTED);
        Switch control = new Switch(this); control.setMinimumWidth(dp(56)); control.setMinimumHeight(dp(56));
        control.setOnCheckedChangeListener((b, checked) -> action.accept(checked)); line.addView(control);
        line.setOnClickListener(v -> { if (control.isEnabled()) control.setChecked(!control.isChecked()); });
        return control;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column(); card.setPadding(dp(16), dp(10), dp(16), dp(10)); card.setBackground(background(Color.WHITE, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(dp(14), dp(8), dp(14), dp(4)); parent.addView(card, params); return card;
    }
    private Button button(String text, View.OnClickListener action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(16); button.setTextColor(INK); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(56)); button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setBackground(background(SURFACE, 12)); button.setOnClickListener(action); return button;
    }
    private Button fullButton(LinearLayout parent, String text, View.OnClickListener action) {
        Button button = button(text, action); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(4), 0, dp(4)); parent.addView(button, params); return button;
    }
    private TextView label(LinearLayout parent, String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(3), 0, dp(3)); parent.addView(view); return view;
    }
    private void title(LinearLayout parent, String text) { label(parent, text, 15, MUTED).setTypeface(null, android.graphics.Typeface.BOLD); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private GradientDrawable background(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private void changed(TextView view, String value) { if (!value.contentEquals(view.getText())) view.setText(value); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); closePreview(); super.onPause(); }
}
