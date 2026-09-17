package com.codex.mnote;

import android.app.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.concurrent.*;
import org.json.*;

/** Authenticated, read-only preview of an existing export snapshot. Never republishes images. */
public final class ShareHistoryActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String scope, exportId;
    private JSONArray assets = new JSONArray();
    private TextView status, summary;
    private Spinner selector;
    private ImageView image;
    private Button revoke, retry;
    private volatile int request;
    private int selected = -1, restoreIndex;
    private volatile boolean destroyed;
    private boolean revoking, binding;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scope = getIntent().getStringExtra("scope");
        exportId = getIntent().getStringExtra("export_id");
        if (scope == null || !scope.equals(CaptureAccountSession.scope(this)) || exportId == null
            || !exportId.matches("[a-f0-9]{32}") || !CaptureAccountSession.hasAccount(this)) {
            finish();
            return;
        }
        restoreIndex =
            state == null ? getIntent().getIntExtra("image_index", 0) : state.getInt("image_index");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.cream));
        page.setFitsSystemWindows(true);
        page.addView(root, new LinearLayout.LayoutParams(-1, -1));
        root.setPadding(dp(22), dp(8), dp(22), dp(16));
        setContentView(page);
        LinearLayout nav = new LinearLayout(this);
        root.addView(nav);
        button(nav, "返回", this::finish);
        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        revoke = button(nav, "撤销分享", this::confirmRevoke);
        revoke.setTextColor(getColor(R.color.danger));
        text(root, "分享图片", 28);
        summary = text(root, "当次导出的截图快照，不随原记录修改而改变。", 13);
        selector = new Spinner(this);
        root.addView(selector, new LinearLayout.LayoutParams(-1, dp(56)));
        status = text(root, "正在读取历史分享…", 13);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("当次导出保存的图片");
        scroll.addView(image, new ScrollView.LayoutParams(-1, -2));
        retry = button(root, "重新加载", () -> {
            if (assets.length() == 0)
                load();
            else
                showImage(Math.max(0, selected));
        });
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!binding && position == parent.getSelectedItemPosition()
                    && position != selected)
                    showImage(position);
            }
        });
        load();
    }
    private boolean active() {
        return !destroyed && !isFinishing() && scope.equals(CaptureAccountSession.scope(this));
    }
    private CaptureSyncPreferences.Config config() throws Exception {
        synchronized (CaptureAccountSession.LOCK) {
            CaptureAccountSession.requireScope(this, scope);
            return CaptureAccountSession.config(this);
        }
    }
    private void ui(Runnable action) {
        runOnUiThread(() -> {
            if (active())
                action.run();
        });
    }
    private void load() {
        int ticket = ++request;
        retry.setEnabled(false);
        worker.execute(() -> {
            try {
                var c = config();
                var detail = CaptureAccountHttp.request(
                    c.baseUrl, "GET", "/v1/exports/" + exportId, c.writeToken, null, null);
                JSONArray values = detail.getJSONArray("images");
                if (values.length() > 300)
                    throw new IOException("invalid_export");
                String[] labels = new String[values.length()];
                for (int i = 0; i < labels.length; i++) {
                    var asset = values.getJSONObject(i);
                    if (!asset.getString("name").matches(
                            "[0-9]+-(context|original|annotated)\\.(png|jpg|webp)"))
                        throw new IOException("invalid_export");
                    labels[i] = "记录 " + asset.getInt("record_index") + " · "
                        + RecordImageLoader.label(asset.getString("role"));
                }
                ui(() -> {
                    if (ticket != request)
                        return;
                    assets = values;
                    retry.setEnabled(true);
                    summary.setText(detail.optString("created") + " · " + labels.length
                        + " 张历史图片\n保存于导出时，不随原记录修改而改变。");
                    int position = Math.max(0, Math.min(restoreIndex, labels.length - 1));
                    selected = position;
                    binding = true;
                    selector.setAdapter(new ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_dropdown_item, labels));
                    if (labels.length == 0) {
                        status.setText("这次分享只有文字，没有保存图片。");
                        selector.setVisibility(View.GONE);
                    } else {
                        selector.setSelection(position);
                        showImage(position);
                    }
                    binding = false;
                });
            } catch (Exception error) {
                failed(ticket, error);
            }
        });
    }
    private void showImage(int position) {
        if (revoking || position < 0 || position >= assets.length())
            return;
        int ticket = ++request;
        selected = position;
        restoreIndex = position;
        image.setImageDrawable(null);
        status.setText("正在加载当时分享的图片…");
        retry.setEnabled(false);
        JSONObject asset = assets.optJSONObject(position);
        worker.execute(() -> {
            try {
                if (ticket != request || destroyed)
                    return;
                var c = config();
                byte[] bytes = CaptureAccountHttp.image(c.baseUrl,
                    "/v1/exports/" + exportId + "/assets/" + asset.getString("name"), c.writeToken);
                if (bytes.length != asset.getInt("size"))
                    throw new IOException("invalid_export_image");
                Bitmap bitmap = decode(bytes, 1600);
                ui(() -> {
                    if (ticket != request)
                        return;
                    image.setImageBitmap(bitmap);
                    retry.setEnabled(true);
                    status.setText("第 " + (position + 1) + " / " + assets.length()
                        + " 张 · 上下滑动查看完整图片");
                });
            } catch (Exception error) {
                failed(ticket, error);
            } catch (OutOfMemoryError error) {
                failed(ticket, new IOException("image_too_large"));
            }
        });
    }
    private void failed(int ticket, Exception error) {
        ui(() -> {
            if (ticket != request)
                return;
            retry.setEnabled(true);
            status.setText("http_404".equals(error.getMessage())
                    ? "分享已撤销或图片不可用，无法再预览。"
                    : "加载失败，请检查网络或重新登录后重试。");
        });
    }
    static Bitmap decode(byte[] bytes, int maximum) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0)
            throw new IOException("invalid_export_image");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > maximum
            || options.outHeight / options.inSampleSize > maximum)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null)
            throw new IOException("invalid_export_image");
        return bitmap;
    }
    private void confirmRevoke() {
        if (revoking)
            return;
        new AlertDialog.Builder(this)
            .setTitle("撤销这次分享的所有图片链接？")
            .setMessage("撤销后不能再预览这些分享图片，原始笔记不删除；已经下载的内容无法收回。")
            .setNegativeButton("取消", null)
            .setPositiveButton("撤销分享",
                (dialog, which) -> {
                    revoking = true;
                    ++request;
                    revoke.setEnabled(false);
                    retry.setEnabled(false);
                    selector.setEnabled(false);
                    image.setImageDrawable(null);
                    worker.execute(() -> {
                        try {
                            var c = config();
                            CaptureAccountHttp.request(c.baseUrl, "DELETE",
                                "/v1/exports/" + exportId, c.writeToken, null, null);
                            ui(() -> {
                                Toast.makeText(this, "分享已撤销，原始记录保留", Toast.LENGTH_LONG)
                                    .show();
                                finish();
                            });
                        } catch (Exception error) {
                            ui(() -> {
                                revoking = false;
                                revoke.setEnabled(true);
                                retry.setEnabled(true);
                                selector.setEnabled(true);
                                status.setText("撤销失败，请重试。");
                            });
                        }
                    });
                })
            .show();
    }
    @Override
    protected void onResume() {
        super.onResume();
        if (scope != null && !scope.equals(CaptureAccountSession.scope(this)))
            finish();
    }
    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("image_index", Math.max(0, selected));
        super.onSaveInstanceState(state);
    }
    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
    private TextView text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(size > 20 ? R.color.ink : R.color.ink_muted));
        view.setPadding(0, dp(8), 0, dp(12));
        root.addView(view);
        return view;
    }
    private Button button(LinearLayout root, String value, Runnable action) {
        Button view = new Button(this);
        view.setText(value);
        view.setBackgroundResource(android.R.color.transparent);
        view.setOnClickListener(v -> action.run());
        root.addView(view);
        return view;
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
