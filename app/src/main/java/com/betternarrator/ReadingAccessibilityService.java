package com.betternarrator;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;

public class ReadingAccessibilityService extends AccessibilityService {
    private static ReadingAccessibilityService instance;
    public static String foregroundPackage = "";

    public static boolean available() { return instance != null; }
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if (pkg.equals(getPackageName()) && event.getClassName() != null
                    && event.getClassName().toString().equals(MainActivity.class.getName())) {
                NarratorService.onForegroundChanged(pkg);
            } else if (!pkg.equals(getPackageName()) && !pkg.equals("com.android.systemui")) {
                foregroundPackage = pkg;
                NarratorService.onForegroundChanged(pkg);
            }
        }
    }
    @Override public void onInterrupt() {
        NarratorService.onForegroundChanged("");
    }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    public static boolean advance(Rect area, String mode, Runnable done, Runnable failed) {
        if (instance == null) return false;
        Path path = new Path();
        if (mode.equals("Scroll")) {
            // Keep half the viewport: paragraph spacing and clipped lines consume overlap.
            path.moveTo(area.centerX(), area.top + area.height() * .85f);
            path.lineTo(area.centerX(), area.top + area.height() * .35f);
        } else {
            float x = mode.equals("Tap left") ? area.left + area.width() * .08f
                    : area.right - area.width() * .08f;
            path.moveTo(x, area.centerY());
        }
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, mode.equals("Scroll") ? 450 : 70))
                .build();
        return instance.dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { done.run(); }
            @Override public void onCancelled(GestureDescription g) { failed.run(); }
        }, null);
    }
}
