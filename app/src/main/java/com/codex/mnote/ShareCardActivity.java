package com.codex.mnote;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Private live composition. Publishing is a separate, explicit save-time operation. */
public final class ShareCardActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private String id, scope, token, base = "", fingerprint;
    private CaptureStore.CaptureRecord record;
    private Bitmap originalImage, annotatedImage, contextImage, previewBitmap;
    private ImageView preview;
    private TextView status;
    private Button save;
    private LinearLayout modules;
    private final List<CheckBox> choices = new ArrayList<>();
    private volatile boolean destroyed;
    private boolean saving, loading = true, published;
    private int mask;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        id = getIntent().getStringExtra(CaptureRecordEditActivity.ID);
        scope = getIntent().getStringExtra(CaptureRecordEditActivity.SCOPE);
        if (scope == null || id == null || !scope.equals(CaptureAccountSession.scope(this))) {
            finish();
            return;
        }
        if (state != null && state.getBoolean("saving")) {
            toast("上次保存可能仍在处理中，请先检查相册和分享管理。");
            finish();
            return;
        }
        buildPage();
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::close);
        worker.execute(() -> {
            try {
                synchronized (CaptureAccountSession.LOCK) {
                    record
                    = CaptureRecordEdits.latest(this, scope, id);
                    fingerprint = CaptureRecordEdits.fingerprint(record);
                    if (CaptureAccountSession.hasAccount(this))
                        base = CaptureAccountSession.config(this).baseUrl;
                }
                originalImage = read(record.originalFile);
                annotatedImage = read(record.annotatedFile);
                contextImage = read(record.contextFile);
                runOnUiThread(() -> {
                    if (!active())
                        return;
                    int defaults = (!record.sourceText.isEmpty() ? 1 : 0)
                        | (!record.comment.isEmpty() ? 2 : 0)
                        | (annotatedImage != null || originalImage != null ? 4 : 0) | 64;
                    mask = state == null || state.getBoolean("loading")
                        ? defaults
                        : state.getInt("mask", defaults);
                    token = state == null ? freshToken() : state.getString("token", freshToken());
                    if (token == null || !token.matches("[a-f0-9]{64}"))
                        token = freshToken();
                    boolean sameSnapshot = state != null
                        && fingerprint.equals(state.getString("fingerprint"))
                        && record.serverRevision == state.getInt("revision", -1);
                    published = sameSnapshot && state.getBoolean("published");
                    if (state != null && !sameSnapshot)
                        token = freshToken();
                    loading = false;
                    buildChoices();
                    schedule(false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (active())
                        status.setText("无法读取记录，请返回刷新后重试。");
                });
            }
        });
    }
    private static Bitmap read(java.io.File file) {
        return file != null && file.isFile() ? CaptureStore.decodeReviewBitmap(file) : null;
    }
    private void buildPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(getColor(R.color.cream));
        setContentView(root);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("返回");
        back.setBackgroundResource(android.R.color.transparent);
        head.addView(back);
        back.setOnClickListener(v -> close());
        TextView title = label(getString(R.string.share_card_title), 20);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(head);
        TextView hint = label(getString(R.string.share_card_hint), 12);
        hint.setPadding(dp(20), 0, dp(20), dp(8));
        root.addView(hint);
        preview = new ImageView(this);
        preview.setId(R.id.share_card_preview);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(dp(18), dp(4), dp(18), dp(8));
        preview.setContentDescription("最终分享图片预览，点击放大");
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        preview.setOnClickListener(v -> {
            if (previewBitmap == null)
                return;
            ImageView big = new ImageView(this);
            big.setImageBitmap(previewBitmap);
            big.setScaleType(ImageView.ScaleType.FIT_CENTER);
            big.setBackgroundColor(ShareCardRenderer.PAPER);
            big.setContentDescription("放大的最终分享卡片");
            AlertDialog dialog = new AlertDialog.Builder(this)
                                     .setView(big)
                                     .setPositiveButton("返回预览", null)
                                     .create();
            dialog.show();
            dialog.getWindow().setLayout(-1, -1);
        });
        ScrollView options = new ScrollView(this);
        modules = new LinearLayout(this);
        modules.setOrientation(LinearLayout.VERTICAL);
        modules.setId(R.id.share_card_modules);
        modules.setPadding(dp(16), 0, dp(16), 0);
        options.addView(modules);
        root.addView(options,
            new LinearLayout.LayoutParams(
                -1, dp(getResources().getConfiguration().screenHeightDp < 650 ? 156 : 202)));
        status = label("正在读取记录…", 12);
        status.setId(R.id.share_card_status);
        status.setPadding(dp(20), dp(6), dp(20), dp(6));
        root.addView(status);
        save = new Button(this);
        save.setId(R.id.share_card_save);
        save.setText(R.string.share_card_save);
        save.setEnabled(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(dp(20), dp(4), dp(20), dp(12));
        root.addView(save, p);
        save.setOnClickListener(v -> requestSave());
    }
    private TextView label(String text, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(getColor(R.color.ink));
        return t;
    }
    private void buildChoices() {
        boolean synced = CaptureAccountSession.hasAccount(this) && record.serverRevision > 0
            && CaptureStore.SYNC_SYNCED.equals(record.syncState);
        addChoice("摘录", 1, !record.sourceText.isEmpty());
        addChoice("我的想法", 2, !record.comment.isEmpty());
        addChoice("圈选截图", 4, originalImage != null || annotatedImage != null);
        addChoice("完整截图", 8, contextImage != null);
        addChoice("原文 · 二维码", 16, synced && !CaptureRecordEdits.original(record).isEmpty());
        addChoice("来源 · 二维码", 32, synced && validSource(record.sourceUrl));
        addChoice("截图保留批注", 64, annotatedImage != null && originalImage != null);
        if (!synced) {
            TextView t = label("本机卡片无需登录；二维码需要登录并先同步此记录。", 12);
            modules.addView(t);
        }
    }
    private void addChoice(String title, int flag, boolean available) {
        if (!available)
            mask &= ~flag;
        LinearLayout row;
        if (choices.size() % 2 == 0) {
            row = new LinearLayout(this);
            modules.addView(row);
        } else
            row = (LinearLayout) modules.getChildAt(modules.getChildCount() - 1);
        CheckBox c = new CheckBox(this);
        c.setText(title);
        c.setTextSize(14);
        c.setTag(flag);
        c.setEnabled(available);
        c.setChecked((mask & flag) != 0);
        row.addView(c, new LinearLayout.LayoutParams(0, dp(48), 1));
        choices.add(c);
        c.setOnCheckedChangeListener((button, on) -> {
            if (on)
                mask |= flag;
            else
                mask &= ~flag;
            button.jumpDrawablesToCurrentState();
            schedule(true);
        });
    }
    static boolean validSource(String url) {
        try {
            java.net.URI u = new java.net.URI(url);
            return ("https".equals(u.getScheme()) || "http".equals(u.getScheme()))
                && u.getHost() != null && u.getUserInfo() == null && !url.contains("\\");
        } catch (Exception e) {
            return false;
        }
    }
    private void schedule(boolean changed) {
        if (loading || saving)
            return;
        if (changed) {
            token = freshToken();
            published = false;
        }
        int version = generation.incrementAndGet(), selected = mask;
        String url = base + "/c/" + token;
        save.setEnabled(false);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(()
                                -> worker.execute(() -> {
            if (destroyed || version != generation.get())
                return;
            ShareCardRenderer.Result result;
            try {
                Bitmap qr = (selected & 48) == 0 ? null : ShareCardRenderer.qr(url);
                Bitmap chosen = (selected & 64) != 0 && annotatedImage != null ? annotatedImage
                    : originalImage != null                                    ? originalImage
                                                                               : annotatedImage;
                result = ShareCardRenderer.render(record.sourceText, record.comment, chosen,
                    contextImage, selected, qr, getResources().getFont(R.font.noto_serif_cjk));
            } catch (Exception | OutOfMemoryError e) {
                result = new ShareCardRenderer.Result(null, "图片生成失败，请减少图片模块后重试。");
            }
            ShareCardRenderer.Result rendered = result;
            runOnUiThread(() -> {
                if (!active() || version != generation.get())
                    return;
                previewBitmap = rendered.bitmap;
                preview.setImageBitmap(previewBitmap);
                status.setText(rendered.error.isEmpty() ? published
                            ? "二维码已生效，可在设置 → 分享管理撤销。"
                            : getString((selected & 48) != 0 ? R.string.share_card_publish_hint
                                                             : R.string.share_card_local_hint)
                                                        : rendered.error);
                save.setEnabled(previewBitmap != null);
            });
        }),
            80);
    }
    private void requestSave() {
        if (saving || previewBitmap == null || !active())
            return;
        if (Build.VERSION.SDK_INT <= 28
            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 401);
            return;
        }
        if ((mask & 48) == 0 || published) {
            save();
            return;
        }
        String fields = ((mask & 16) != 0 ? getString(R.string.share_card_original) : "")
            + ((mask & 48) == 48 ? "、" : "")
            + ((mask & 32) != 0 ? getString(R.string.share_card_source) : "");
        new AlertDialog.Builder(this)
            .setTitle(R.string.share_card_confirm)
            .setMessage(getString(R.string.share_card_confirm_message, fields))
            .setNegativeButton("取消", null)
            .setPositiveButton("确认并保存", (d, w) -> save())
            .show();
    }
    private void save() {
        if (saving || previewBitmap == null || !active())
            return;
        saving = true;
        save.setEnabled(false);
        for (CheckBox c : choices) c.setEnabled(false);
        status.setText("正在保存，请稍候…");
        final Bitmap bitmap = previewBitmap;
        final int selected = mask;
        final String shareToken = token;
        final boolean existing = published;
        final Context app = getApplicationContext();
        worker.execute(() -> {
            CaptureSyncPreferences.Config config = null;
            boolean attempted = false;
            String exportId = "";
            String message;
            boolean success = false;
            try {
                CaptureStore.CaptureRecord current;
                synchronized (CaptureAccountSession.LOCK) {
                    current = CaptureRecordEdits.latest(app, scope, id);
                    if (!fingerprint.equals(CaptureRecordEdits.fingerprint(current))
                        || current.serverRevision != record.serverRevision)
                        throw new java.io.IOException("record_changed");
                    if ((selected & 48) != 0) {
                        if (!CaptureAccountSession.hasAccount(app)
                            || !CaptureStore.SYNC_SYNCED.equals(current.syncState))
                            throw new java.io.IOException("sync_required");
                        config = CaptureAccountSession.config(app);
                        if (!base.equals(config.baseUrl))
                            throw new java.io.IOException("account_changed");
                    }
                }
                if (config != null && !existing) {
                    exportId = hex(MessageDigest.getInstance("SHA-256").digest(shareToken.getBytes(
                                       java.nio.charset.StandardCharsets.UTF_8)))
                                   .substring(0, 32);
                    JSONArray fields = new JSONArray();
                    if ((selected & 16) != 0)
                        fields.put("original");
                    if ((selected & 32) != 0)
                        fields.put("source");
                    JSONObject body = new JSONObject()
                                          .put("id", id)
                                          .put("revision", current.serverRevision)
                                          .put("token", shareToken)
                                          .put("publish", true)
                                          .put("fields", fields);
                    attempted = true;
                    JSONObject result = CaptureAccountHttp.request(
                        config.baseUrl, "POST", "/v1/exports/card", config.writeToken, body, null);
                    if (!exportId.equals(result.getString("id"))
                        || !(base + "/c/" + shareToken).equals(result.getString("url")))
                        throw new java.io.IOException("unexpected_share_url");
                }
                synchronized (CaptureAccountSession.LOCK) {
                    CaptureAccountSession.requireScope(app, scope);
                    ShareCardAlbum.save(app, bitmap);
                }
                message = "已保存到相册" + ((selected & 48) != 0 ? "，二维码已生效。" : "。");
                success = true;
            } catch (Exception | OutOfMemoryError error) {
                message = "保存失败，请检查存储空间、相册权限或网络；记录变更时请重新打开预览。";
                if (attempted && config != null) {
                    try {
                        CaptureAccountHttp.request(config.baseUrl, "DELETE",
                            "/v1/exports/" + exportId, config.writeToken, null, null);
                    } catch (Exception cleanup) {
                        message += " 如已创建分享，请到设置 → 分享管理检查并撤销。";
                    }
                }
            }
            String outcome = message;
            boolean saved = success;
            runOnUiThread(() -> {
                Toast.makeText(app, outcome, Toast.LENGTH_LONG).show();
                if (!active())
                    return;
                saving = false;
                if (saved)
                    published = (selected & 48) != 0;
                else if (!existing) {
                    token = freshToken();
                    published = false;
                }
                modules.removeAllViews();
                choices.clear();
                buildChoices();
                schedule(false);
            });
        });
    }
    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 401) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                requestSave();
            else
                toast("未获得相册写入权限，未保存或发布分享。");
        }
    }
    private boolean active() {
        return !destroyed && !isFinishing() && scope != null
            && scope.equals(CaptureAccountSession.scope(this));
    }
    private void close() {
        if (saving)
            toast("正在保存，请稍候。");
        else
            finish();
    }
    @Override
    public void onBackPressed() {
        close();
    }
    @Override
    protected void onResume() {
        super.onResume();
        if (scope != null && !scope.equals(CaptureAccountSession.scope(this)))
            finish();
    }
    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("mask", mask);
        state.putString("token", token);
        state.putBoolean("saving", saving);
        state.putBoolean("published", published);
        state.putBoolean("loading", loading);
        state.putString("fingerprint", fingerprint);
        state.putInt("revision", record == null ? -1 : record.serverRevision);
    }
    @Override
    protected void onDestroy() {
        destroyed = true;
        generation.incrementAndGet();
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }
    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
    static String freshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return hex(bytes);
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
