package com.betternarrator;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.Consumer;

/** Full-screen overlay; the result is in physical display pixels, matching MediaProjection. */
public class RegionSelector extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF region = new RectF();
    private final Consumer<Rect> confirm;
    private final Runnable cancel;
    private float startX, startY;
    private final float density;
    private boolean initialized;
    public RegionSelector(Context context, Consumer<Rect> confirm, Runnable cancel) {
        super(context); this.confirm = confirm; this.cancel = cancel;
        density = getResources().getDisplayMetrics().density;
        setContentDescription("Drag a rectangle around the story. Use area button at bottom left; cancel at bottom right.");
    }
    @Override protected void onDraw(Canvas canvas) {
        if (!initialized) {
            region.set(getWidth() * .04f, getHeight() * .22f, getWidth() * .96f, getHeight() * .78f);
            initialized = true;
        }
        paint.setColor(0x990B1423); paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, getWidth(), region.top, paint);
        canvas.drawRect(0, region.bottom, getWidth(), getHeight(), paint);
        canvas.drawRect(0, region.top, region.left, region.bottom, paint);
        canvas.drawRect(region.right, region.top, getWidth(), region.bottom, paint);
        paint.setColor(0xFF5CE0C4); paint.setStrokeWidth(3 * density); paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(region, paint); paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE); paint.setTextSize(18 * density);
        canvas.drawText("Drag around complete story lines", 18 * density, 55 * density, paint);
        paint.setTextSize(13 * density);
        canvas.drawText("Exclude browser bars and floating controls", 18 * density, 79 * density, paint);
        float top = getHeight() - 110 * density;
        paint.setColor(0xFF166D60);
        canvas.drawRoundRect(12 * density, top, getWidth() / 2f - 6 * density, top + 54 * density, 12, 12, paint);
        paint.setColor(0xFF334155);
        canvas.drawRoundRect(getWidth() / 2f + 6 * density, top, getWidth() - 12 * density, top + 54 * density, 12, 12, paint);
        paint.setColor(Color.WHITE); paint.setTextSize(17 * density);
        canvas.drawText("Use area", 30 * density, top + 34 * density, paint);
        canvas.drawText("Cancel", getWidth() / 2f + 28 * density, top + 34 * density, paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        float top = getHeight() - 110 * density;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (y >= top) {
                if (x < getWidth() / 2f && region.width() > 80 * density && region.height() > 80 * density) {
                    int[] location = new int[2]; getLocationOnScreen(location);
                    confirm.accept(new Rect(Math.round(region.left) + location[0], Math.round(region.top) + location[1],
                            Math.round(region.right) + location[0], Math.round(region.bottom) + location[1]));
                } else if (x >= getWidth() / 2f) cancel.run();
                return true;
            }
            startX = x; startY = y;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_UP) {
            region.set(Math.max(0, Math.min(startX, x)), Math.max(0, Math.min(startY, y)),
                    Math.min(getWidth(), Math.max(startX, x)), Math.min(top - 8 * density, Math.max(startY, y)));
            invalidate();
        }
        return true;
    }
}
