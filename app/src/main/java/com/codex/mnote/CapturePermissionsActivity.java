package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.widget.*;

/** Settings-only guidance. System permissions remain owned by Android. */
public final class CapturePermissionsActivity extends Activity {
    private TextView status;
    private Button capture, screenshotTile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(getColor(R.color.cream));
        LinearLayout root = JournalUi.column(this);
        root.setPadding(dp(22), dp(8), dp(22), dp(28));
        scroll.addView(root);
        setContentView(scroll);
        JournalUi.header(this, root, "快捷方式与权限", this::finish);
        JournalUi.section(root, "跨应用记录");
        status = JournalUi.text(this, "", 16, R.color.ink);
        status.setLineSpacing(dp(4), 1);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        capture = action(root, "", () -> {
            if (CaptureAccessibilityService.isReady()) {
                startActivity(new Intent(this, CaptureTriggerActivity.class));
            } else if (CaptureAccessibilityService.isConfigured(this)) {
                Toast.makeText(this, R.string.capture_service_connecting_toast, Toast.LENGTH_LONG).show();
                render();
            } else {
                disclose();
            }
        });
        JournalUi.primary(capture);
        action(root, "打开系统无障碍设置", this::disclose);
        JournalUi.section(root, "快捷设置按钮");
        root.addView(JournalUi.text(this, "在其他应用中下拉快捷设置，即可开始记录。", 13, R.color.ink_muted));
        screenshotTile = action(root, "添加「记录」快捷按钮", this::requestTile);
        root.addView(JournalUi.text(this, "先截取当前页面，再选择保留想法、摘录、原文或截图。旧版两个按钮均进入同一流程；可从系统快捷设置中移除重复按钮。", 13, R.color.ink_muted));
        action(root, "仅记录文字", () -> startActivity(UnifiedCaptureActivity.forText(this)));
        JournalUi.rule(root);
        JournalUi.section(root, "权限说明");
        root.addView(JournalUi.text(this, "截图与页面文字读取需要系统无障碍授权；没有授权也可以先记录自己的想法。页面文字可能不完整，来源识别没有独立的系统开关。", 14, R.color.ink_muted));
        action(root, "查看页面读取诊断", () -> new AlertDialog.Builder(this)
            .setTitle("页面读取诊断").setMessage(CaptureAccessibilitySettings.diagnostic(this))
            .setPositiveButton("关闭", null).show());
        render();
    }
    @Override protected void onResume() { super.onResume(); if (status != null) render(); }
    private void render() {
        boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        boolean configured = supported && CaptureAccessibilityService.isConfigured(this);
        boolean ready = configured && CaptureAccessibilityService.isReady();
        capture.setEnabled(supported);
        status.setText(!supported ? R.string.capture_access_unsupported
            : ready ? R.string.capture_access_ready_detail
            : configured ? R.string.capture_access_connecting_detail : R.string.capture_access_disabled_detail);
        capture.setText(ready ? (CaptureAccessibilityService.hasOverlay()
            ? R.string.capture_tile_resume : R.string.capture_test_capture_button)
            : configured ? R.string.capture_retry_connection_button : R.string.capture_setup_button);
    }
    private void disclose() {
        new AlertDialog.Builder(this).setTitle(R.string.capture_accessibility_dialog_title)
            .setMessage(R.string.capture_accessibility_dialog_detail)
            .setNegativeButton(R.string.capture_cancel, null)
            .setPositiveButton(R.string.capture_open_accessibility_settings, (d, which) -> {
                try { CaptureAccessibilitySettings.open(this); }
                catch (RuntimeException error) { Toast.makeText(this, R.string.capture_error_open_accessibility_settings, Toast.LENGTH_LONG).show(); }
            }).show();
    }
    private void requestTile() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.capture_add_tile_manual, Toast.LENGTH_LONG).show(); return;
        }
        StatusBarManager manager = getSystemService(StatusBarManager.class);
        if (manager == null) {
            Toast.makeText(this, R.string.capture_add_tile_manual, Toast.LENGTH_LONG).show(); return;
        }
        screenshotTile.setEnabled(false);
        try {
            manager.requestAddTileService(new ComponentName(this, CaptureQuickSettingsTileService.class),
                getString(R.string.capture_tile_label),
                Icon.createWithResource(this, R.drawable.ic_capture_tile), getMainExecutor(), result -> {
                    if (isFinishing() || isDestroyed()) return;
                    screenshotTile.setEnabled(true);
                    int message = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                        ? R.string.capture_tile_added
                        : result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                        ? R.string.capture_tile_already_added
                        : R.string.capture_tile_not_added;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
        } catch (RuntimeException error) {
            screenshotTile.setEnabled(true);
            Toast.makeText(this, R.string.capture_add_tile_manual, Toast.LENGTH_LONG).show();
        }
    }
    private Button action(LinearLayout root, String value, Runnable task) {
        Button button = new Button(this); button.setText(value); JournalUi.quiet(button);
        button.setOnClickListener(v -> task.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(12);
        root.addView(button, p); return button;
    }
    private int dp(int value) { return JournalUi.dp(this, value); }
}
