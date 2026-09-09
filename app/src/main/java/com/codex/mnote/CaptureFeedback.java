package com.codex.mnote;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** Service-owned, short-lived, touch-through fallback when a system Toast is obscured. */
final class CaptureFeedback {
    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView chip;
    CaptureFeedback(AccessibilityService service) { this.service = service; }

    void show(int message) {
        hide();
        KeyguardManager lock = service.getSystemService(KeyguardManager.class);
        if (lock != null && lock.isKeyguardLocked()) return;
        TextView view = new TextView(service);
        view.setText(message);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(20), dp(12), dp(20), dp(12));
        view.setMaxWidth(service.getResources().getDisplayMetrics().widthPixels - dp(40));
        view.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(0xF0000000 | (service.getColor(R.color.ink) & 0xFFFFFF));
        surface.setCornerRadius(dp(24));
        view.setBackground(surface);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(24);
        params.setTitle("Mnote capture feedback");
        try {
            service.getSystemService(WindowManager.class).addView(view, params);
            chip = view;
            main.postDelayed(this::hide, 2600);
        } catch (RuntimeException ignored) { /* The standard text Toast remains the fallback. */ }
    }

    void hide() {
        main.removeCallbacksAndMessages(null);
        if (chip == null) return;
        try { service.getSystemService(WindowManager.class).removeViewImmediate(chip); }
        catch (RuntimeException ignored) { }
        chip = null;
    }

    private int dp(int value) { return Math.round(value * service.getResources().getDisplayMetrics().density); }
}
