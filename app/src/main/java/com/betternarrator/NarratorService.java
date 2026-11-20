package com.betternarrator;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.widget.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.nio.ByteBuffer;
import java.util.*;

public class NarratorService extends Service {
    private static NarratorService instance;
    private static final String TAG = "BetterNarrator";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private LinearLayout toolbar;
    private TextView statusView;
    private Button readButton;
    private RegionSelector selector;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader images;
    private TextRecognizer recognizer;
    private SpeechEngine speech;
    private boolean voiceReady, active, waitingFrame, destroyed, capturing, captureScheduled;
    private boolean pendingAheadCapture, waitingForAhead, navigationInFlight;
    private int width, height, epoch, captureEpoch, passageSerial, boundarySerial;
    private long boundaryDoneAt;
    private final ReadAheadState ahead = new ReadAheadState();
    private Rect region;
    private String previous = "", mode = "Scroll", readingPackage = "";
    private List<String> chunks = new ArrayList<>();
    private int chunkIndex;
    private SharedPreferences prefs;

    public static boolean isRunning() { return instance != null; }
    public static void onForegroundChanged(String pkg) {
        NarratorService current = instance;
        if (current != null) current.handler.post(() -> {
            if (current.active && !current.readingPackage.isEmpty() && !current.readingPackage.equals(pkg))
                current.pause("Paused: reading app changed. Return to it and tap Read.");
        });
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        windows = getSystemService(WindowManager.class);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        speech = VoiceSettings.create(this, new SpeechEngine.Listener() {
            @Override public void onReady() {
                if (destroyed) return;
                voiceReady = true;
                status("Offline voice ready. Select Area, then tap Read.");
            }
            @Override public void onStarted(String id) {
                if (!destroyed && active && id.equals(speechId())) {
                    status("Reading " + (chunkIndex + 1) + "/" + chunks.size() + " · " + mode);
                    if (chunkIndex == 0 && boundaryDoneAt > 0 && passageSerial > boundarySerial) {
                        Log.i(TAG, "Screen handoff gap=" + (SystemClock.elapsedRealtime() - boundaryDoneAt) + " ms");
                        boundaryDoneAt = 0;
                    }
                }
            }
            @Override public void onDone(String id) {
                if (destroyed || !active || !id.equals(speechId())) return;
                if (chunkIndex == chunks.size() - 1) { boundaryDoneAt = SystemClock.elapsedRealtime(); boundarySerial = passageSerial; }
                chunkIndex++;
                speakNext();
            }
            @Override public void onError(String id, String message) {
                if (!destroyed && (id.isEmpty() || id.equals(speechId()))) pause(message);
            }
            @Override public void onBuffered(String id, long remainingMillis) {
                if (destroyed || !active || !id.equals(speechId())) return;
                if (ReadAheadState.canPrepareEarly(prefs.getBoolean("auto", true), prefs.getBoolean("look_ahead", true),
                        speech instanceof OfflineSpeechEngine, chunkIndex == chunks.size() - 1, remainingMillis)
                        && ahead.phase() == ReadAheadState.Phase.IDLE) {
                    Log.i(TAG, "Read-ahead requested with " + remainingMillis + " ms of buffered audio remaining");
                    beginAdvance();
                }
            }
        });
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("reading", "Screen reading", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (projection != null) return START_NOT_STICKY;
        if (intent == null || !intent.hasExtra("capture")) { stopSelf(); return START_NOT_STICKY; }
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, NarratorService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "reading")
                .setContentTitle("Better Narrator is ready")
                .setContentText("Screen capture active. Use the floating controls to read or stop.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop reader", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(1, notification);
        try {
            Intent permission = intent.getParcelableExtra("capture");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result", Activity.RESULT_OK), permission);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { if (!destroyed) { status("Screen sharing ended. Start a new session from the app."); stopSelf(); } }
            }, handler);
            DisplayMetrics metrics = new DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(metrics);
            width = metrics.widthPixels; height = metrics.heightPixels;
            images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            images.setOnImageAvailableListener(reader -> {
                try (Image frame = reader.acquireLatestImage()) {
                    if (frame != null && waitingFrame && captureEpoch == epoch) {
                        waitingFrame = false;
                        recognize(frame, captureEpoch, pendingAheadCapture);
                    }
                } catch (Exception e) { capturing = false; if (!destroyed) captureFailure("Capture failed: " + e.getMessage(), pendingAheadCapture); }
            }, handler);
            display = projection.createVirtualDisplay("BetterNarrator", width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images.getSurface(), null, handler);
            showToolbar();
            status(voiceReady ? "Open your story, then tap Area." : "Loading offline voice… You can select Area now.");
        } catch (Exception e) {
            Log.e(TAG, "Cannot start capture", e);
            status("Cannot start capture: " + e.getMessage()); stopSelf();
        }
        return START_NOT_STICKY;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void showToolbar() {
        toolbar = new LinearLayout(this); toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setPadding(dp(8), dp(5), dp(8), dp(5));
        toolbar.setBackgroundColor(0xF51B2A3E);
        statusView = new TextView(this); statusView.setTextColor(Color.WHITE); statusView.setTextSize(12);
        statusView.setMaxLines(2); toolbar.addView(statusView);
        LinearLayout buttons = new LinearLayout(this); toolbar.addView(buttons);
        addButton(buttons, "Area", this::selectRegion);
        readButton = addButton(buttons, "Read", () -> { if (active) pause("Paused. Read restarts the interrupted passage."); else read(); });
        addButton(buttons, "Next", this::nextScreen);
        addButton(buttons, "Off", this::stopSelf);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        windows.addView(toolbar, params);
    }
    private Button addButton(LinearLayout row, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(14); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> action.run()); row.addView(button, new LinearLayout.LayoutParams(0, dp(56), text.equals("Read") ? 2 : 1));
        return button;
    }
    private void selectRegion() {
        pause("Select a reading area."); chunks.clear(); chunkIndex = 0; previous = ""; ahead.reset(); passageSerial++;
        toolbar.setVisibility(View.GONE);
        selector = new RegionSelector(this, selected -> {
            if (!selected.intersect(0, 0, width, height) || selected.width() < dp(80) || selected.height() < dp(80)) {
                status("Choose a larger area within the screen."); return;
            }
            region = selected;
            closeSelector();
            status("Area selected. Tap Read to begin.");
        }, () -> { closeSelector(); status("Selection cancelled. Tap Area to choose again."); });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 28)
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        windows.addView(selector, params);
    }
    private void closeSelector() {
        if (selector != null) { windows.removeView(selector); selector = null; }
        if (toolbar != null) toolbar.setVisibility(View.VISIBLE);
    }
    private void read() {
        if (region == null) { selectRegion(); return; }
        if (!voiceReady) { status("Offline voice is loading. Please wait, then tap Read again."); return; }
        if (capturing) { status("Finishing the previous recognition. Tap Read again in a moment."); return; }
        active = true; epoch++; waitingForAhead = false;
        mode = prefs.getString("mode", "Scroll");
        readingPackage = ReadingAccessibilityService.foregroundPackage;
        readButton.setText("Pause");
        if ((ahead.phase() == ReadAheadState.Phase.FAILED || ahead.phase() == ReadAheadState.Phase.LOADING) && !ahead.moved()) ahead.reset();
        if (chunkIndex < chunks.size()) {
            speakNext();
            if (ahead.needsCapture()) {
                int token = epoch;
                handler.postDelayed(() -> { if (active && token == epoch && ahead.needsCapture()) capture(true); }, 600);
            }
        } else if (!previous.isEmpty()) completeScreen(); else capture(false);
    }
    private void capture(boolean isAhead) {
        if (!active || destroyed || capturing || waitingFrame || captureScheduled) return;
        captureScheduled = true; pendingAheadCapture = isAhead;
        status(isAhead && !waitingForAhead ? "Reading · preparing next screen…" : "Reading screen…");
        int token = epoch;
        // Preserve the physical gesture's settle deadline even when pause invalidates callbacks.
        handler.postDelayed(() -> {
            if (!active || token != epoch || destroyed) return;
            // Hide our own overlay only immediately before acquiring a fresh frame.
            toolbar.setVisibility(View.INVISIBLE);
            handler.postDelayed(() -> {
                if (!active || token != epoch || destroyed) return;
                captureScheduled = false; captureEpoch = token; waitingFrame = true;
                try (Image frame = images.acquireLatestImage()) {
                    if (frame != null) { waitingFrame = false; recognize(frame, token, isAhead); }
                } catch (Exception e) { capturing = false; captureFailure("Capture failed: " + e.getMessage(), isAhead); }
                handler.postDelayed(() -> {
                    if (waitingFrame && token == epoch) { waitingFrame = false; captureFailure("No screen image received. The reading app may block capture.", isAhead); }
                }, 4000);
            }, 250);
        }, isAhead ? ahead.captureDelay(SystemClock.elapsedRealtime()) : 0);
    }
    private void recognize(Image image, int token, boolean isAhead) {
        capturing = true;
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int paddedWidth = plane.getRowStride() / plane.getPixelStride();
        Bitmap full = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(buffer);
        Bitmap crop = Bitmap.createBitmap(full, region.left, region.top, region.width(), region.height());
        if (crop != full) full.recycle();
        recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener(result -> {
                    if (destroyed || token != epoch || !active) return;
                    toolbar.setVisibility(View.VISIBLE);
                    List<Text.Line> lines = new ArrayList<>();
                    for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines()) {
                        Rect box = line.getBoundingBox();
                        // Never read a line clipped by the top or bottom of the crop.
                        if (box != null && box.top > 2 && box.bottom < region.height() - 2) lines.add(line);
                    }
                    lines.sort(Comparator.comparingInt((Text.Line l) -> l.getBoundingBox().top)
                            .thenComparingInt(l -> l.getBoundingBox().left));
                    StringBuilder text = new StringBuilder();
                    for (Text.Line line : lines) text.append(line.getText()).append('\n');
                    String recognized = text.toString().trim();
                    if (recognized.length() < 12) { captureFailure("Little or no text found. Check the selected area or capture restrictions.", isAhead); return; }
                    prefs.edit().putString("last_text", recognized).apply();
                    String novel = recognized;
                    if (!previous.isEmpty()) {
                        if (TextContinuity.normalized(previous).equals(TextContinuity.normalized(recognized))) {
                            captureFailure("No new text. The page may not have moved, or the passage has ended. Choose Area to continue.", isAhead); return;
                        }
                        if (mode.equals("Scroll")) {
                            novel = TextContinuity.afterScroll(previous, recognized);
                            if (novel == null) { captureFailure("Could not match the next screen. Choose Area at your reading position.", isAhead); return; }
                        } else if (!isAhead || !ahead.moved()) {
                            captureFailure("Page changed manually. Choose Area to reset your position.", isAhead); return;
                        }
                    }
                    if (novel.trim().isEmpty()) { captureFailure("No new text after scrolling. Choose Area again if needed.", isAhead); return; }
                    Log.i(TAG, "OCR success: " + recognized.length() + " chars; new narration: " + novel.length() + " chars");
                    if (isAhead) {
                        ahead.ready(recognized, splitSpeech(novel));
                        Log.i(TAG, "Next screen ready while current chunk=" + chunkIndex + "/" + chunks.size());
                        if (waitingForAhead) promoteAhead();
                        else { prepareNextAudio(); status("Reading · next screen ready"); }
                    } else {
                        previous = recognized; chunks = splitSpeech(novel); chunkIndex = 0; passageSerial++;
                        speakNext();
                    }
                })
                .addOnFailureListener(error -> { if (!destroyed && token == epoch) captureFailure("Text recognition failed: " + error.getMessage(), isAhead); })
                .addOnCompleteListener(task -> { capturing = false; crop.recycle(); });
    }
    static List<String> splitSpeech(String text) {
        return SpeechChunks.split(text);
    }
    private void speakNext() {
        if (!active || destroyed) return;
        readButton.setText("Pause");
        if (chunkIndex >= chunks.size()) {
            if (prefs.getBoolean("auto", true)) completeScreen();
            else pause("Passage finished. Tap Next, or enable automatic advancement in the app.");
            return;
        }
        float rate = prefs.getFloat("rate", 1f);
        int speaker = VoiceSettings.speaker(prefs);
        status("Preparing voice " + (chunkIndex + 1) + "/" + chunks.size() + " · " + mode);
        speech.speak(chunks.get(chunkIndex), speechId(), speaker, rate);
        prepareNextAudio();
    }
    private String speechId() { return epoch + ":" + passageSerial + ":" + chunkIndex; }
    private void prepareNextAudio() {
        if (chunkIndex + 1 < chunks.size()) speech.prepare(chunks.get(chunkIndex + 1), VoiceSettings.speaker(prefs), prefs.getFloat("rate", 1f));
        else if (ahead.phase() == ReadAheadState.Phase.READY)
            speech.prepare(ahead.chunks().get(0), VoiceSettings.speaker(prefs), prefs.getFloat("rate", 1f));
    }
    private void nextScreen() {
        if (region == null) { status("Choose Area first."); return; }
        if (!voiceReady) { status("The voice is still loading."); return; }
        if (capturing) { status("Finishing text recognition. Try Next again in a moment."); return; }
        pause("Preparing next screen…"); active = true;
        readingPackage = ReadingAccessibilityService.foregroundPackage; mode = prefs.getString("mode", "Scroll");
        chunkIndex = chunks.size();
        if (ahead.phase() == ReadAheadState.Phase.FAILED) {
            boolean moved = ahead.moved(); long settleDelay = ahead.captureDelay(SystemClock.elapsedRealtime()); ahead.reset();
            if (moved) { ahead.begin(); ahead.navigationAttempted(SystemClock.elapsedRealtime() + settleDelay); }
        }
        completeScreen();
    }
    private void completeScreen() {
        if (!active || destroyed) return;
        waitingForAhead = true;
        switch (ahead.phase()) {
            case READY: promoteAhead(); break;
            case FAILED: pause(ahead.error()); break;
            case LOADING:
                status("Preparing next screen…");
                if (!navigationInFlight && ahead.needsCapture()) capture(true);
                break;
            case IDLE: beginAdvance(); break;
        }
    }
    private void promoteAhead() {
        if (ahead.phase() != ReadAheadState.Phase.READY) return;
        previous = ahead.screen(); chunks = new ArrayList<>(ahead.chunks()); chunkIndex = 0; passageSerial++;
        ahead.reset(); waitingForAhead = false;
        speakNext();
    }
    private void captureFailure(String message, boolean isAhead) {
        if (toolbar != null) toolbar.setVisibility(View.VISIBLE);
        captureScheduled = false; waitingFrame = false;
        if (isAhead) {
            ahead.fail(message); Log.i(TAG, "Next screen failed: " + message);
            if (!waitingForAhead && chunkIndex < chunks.size()) { status("Reading · next screen needs attention"); return; }
        }
        pause(message);
    }
    private void beginAdvance() {
        if (!active || destroyed || !ahead.begin()) return;
        if (!ReadingAccessibilityService.available()) {
            captureFailure("Enable page controls in Android accessibility settings, then tap Read.", true); return;
        }
        if (readingPackage.isEmpty()) {
            captureFailure("Switch to Home, return to your story, then tap Read to enable page controls.", true); return;
        }
        if (!readingPackage.equals(ReadingAccessibilityService.foregroundPackage)) {
            captureFailure("Return to your reading app, then tap Read.", true); return;
        }
        int token = epoch;
        status(waitingForAhead ? "Preparing next screen…" : "Reading · preparing ahead…");
        Log.i(TAG, waitingForAhead ? "Advancing after playback" : "Advancing before playback ends");
        navigationInFlight = true;
        boolean dispatched = ReadingAccessibilityService.advance(region, mode, () -> {
            if (destroyed || !active || token != epoch) return;
            handler.postDelayed(() -> {
                if (token == epoch && active && !destroyed) { navigationInFlight = false; capture(true); }
            }, 1100);
        }, () -> { if (token == epoch && !destroyed) { navigationInFlight = false; captureFailure("Page movement was interrupted. Choose Area to reset your position.", true); } });
        if (dispatched) ahead.navigationAttempted(SystemClock.elapsedRealtime() + 1600);
        else { navigationInFlight = false; captureFailure("Page controls are unavailable. Enable them in Android accessibility settings.", true); }
    }
    private void pause(String message) {
        active = false; epoch++; waitingFrame = false; captureScheduled = false; navigationInFlight = false; waitingForAhead = false; boundaryDoneAt = 0;
        if (speech != null) speech.stop();
        if (toolbar != null) toolbar.setVisibility(View.VISIBLE);
        if (readButton != null) readButton.setText("Read");
        status(message);
    }
    private void status(String message) {
        prefs.edit().putString("status", message).apply();
        if (statusView != null) statusView.setText(message);
        Log.i(TAG, message);
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        DisplayMetrics metrics = new DisplayMetrics(); windows.getDefaultDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels != width || metrics.heightPixels != height) {
            status("Screen orientation changed. Start a new session and select the area again."); stopSelf();
        }
    }
    @Override public void onDestroy() {
        destroyed = true; instance = null; active = false; epoch++; waitingFrame = false;
        handler.removeCallbacksAndMessages(null);
        if (speech != null) speech.close();
        if (selector != null) windows.removeView(selector);
        if (toolbar != null) windows.removeView(toolbar);
        if (display != null) display.release();
        if (images != null) images.close();
        if (projection != null) projection.stop();
        if (recognizer != null) recognizer.close();
        prefs.edit().putString("status", "Reader stopped. Start a new session when ready.").apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
