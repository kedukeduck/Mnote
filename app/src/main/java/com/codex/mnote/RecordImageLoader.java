package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Local, bounded decoding only. Viewing an image never publishes it or makes a network request. */
final class RecordImageLoader implements AutoCloseable {
    private final Activity activity;
    private final String scope;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String, Bitmap> cache =
        new android.util.LruCache<>(6 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getAllocationByteCount();
            }
        };
    private volatile boolean closed;
    RecordImageLoader(Activity activity, String scope) {
        this.activity = activity;
        this.scope = scope;
    }
    static File file(CaptureStore.CaptureRecord record, String role) {
        return "context".equals(role) ? record.contextFile
            : "original".equals(role) ? record.originalFile
                                        : record.annotatedFile;
    }
    static String label(String role) {
        return "context".equals(role) ? "完整页面"
            : "original".equals(role) ? "圈选原图"
                                        : "批注图";
    }
    void load(File file, boolean full, Consumer<Bitmap> callback) {
        if (closed || !scope.equals(CaptureAccountSession.scope(activity)))
            return;
        String key = file == null
            ? ""
            : file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
        Bitmap cached = full ? null : cache.get(key);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        worker.execute(() -> {
            if (closed || !scope.equals(CaptureAccountSession.scope(activity)))
                return;
            Bitmap result = null;
            if (file != null && file.isFile()) {
                result = full ? null : cache.get(key);
                if (result == null) {
                    result = full ? CaptureStore.decodeReviewBitmap(file)
                                  : CaptureStore.decodeThumbnail(file);
                    if (result != null && !full)
                        cache.put(key, result);
                }
            }
            final Bitmap bitmap = result;
            activity.runOnUiThread(() -> {
                if (!closed && !activity.isFinishing() && !activity.isDestroyed()
                    && scope.equals(CaptureAccountSession.scope(activity)))
                    callback.accept(bitmap);
            });
        });
    }
    void preview(CaptureStore.CaptureRecord record) {
        preview(record, "annotated");
    }
    void preview(CaptureStore.CaptureRecord record, String preferredRole) {
        if (closed || !scope.equals(CaptureAccountSession.scope(activity)))
            return;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        TextView status = new TextView(activity);
        status.setTextColor(activity.getColor(R.color.ink_muted));
        content.addView(status);
        ScrollView scroll = new ScrollView(activity);
        CaptureContextPreview image = new CaptureContextPreview(activity, null, null);
        image.setAdjustViewBounds(true);
        scroll.addView(image, new ScrollView.LayoutParams(-1, -2));
        LinearLayout tabs = new LinearLayout(activity);
        content.addView(tabs);
        int height = Math.min(activity.getResources().getDisplayMetrics().heightPixels / 2,
            Math.round(440 * activity.getResources().getDisplayMetrics().density));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, height));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                                 .setTitle("将导出的截图")
                                 .setView(content)
                                 .setPositiveButton("完成", null)
                                 .create();
        int[] ticket = {0};
        java.util.Map<String, Button> roleButtons = new java.util.LinkedHashMap<>();
        java.util.function.Consumer<String> show = role -> {
            int request = ++ticket[0];
            for (java.util.Map.Entry<String, Button> entry : roleButtons.entrySet()) {
                boolean selected = role.equals(entry.getKey());
                entry.getValue().setSelected(selected);
                entry.getValue().setBackgroundResource(
                    selected ? R.drawable.bg_button_primary : android.R.color.transparent);
                entry.getValue().setTextColor(
                    activity.getColor(selected ? R.color.white : R.color.ink_muted));
            }
            image.setContent(null, null);
            status.setText(label(role) + " · 正在加载…");
            load(file(record, role), true, bitmap -> {
                if (!dialog.isShowing() || request != ticket[0])
                    return;
                status.setText(label(role)
                    + (bitmap == null ? " · 图片无法读取，请回到首页同步后重试"
                                      : " · 导出时保留原始图片，不裁切"));
                image.setContent(bitmap,
                    "context".equals(role) ? record.captureContext.optJSONObject("image") : null);
            });
        };
        String first = null;
        for (String role : new String[] {"annotated", "original", "context"}) {
            if (file(record, role) == null)
                continue;
            if (first == null || role.equals(preferredRole))
                first = role;
            Button tab = new Button(activity);
            tab.setText(label(role));
            tab.setTextSize(12);
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, -2, 1);
            tabParams.setMargins(0, pad / 2, 0, pad / 2);
            tabs.addView(tab, tabParams);
            roleButtons.put(role, tab);
            tab.setOnClickListener(v -> show.accept(role));
        }
        dialog.show();
        if (first != null)
            show.accept(first);
        else
            status.setText("这条记录没有保存截图");
    }
    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        cache.evictAll();
    }
}
