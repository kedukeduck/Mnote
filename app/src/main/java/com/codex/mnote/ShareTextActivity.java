package com.codex.mnote;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import java.util.concurrent.*;

/** Owner-only historical text, paged to keep long originals inexpensive to lay out. */
public final class ShareTextActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String scope, id, content = "";
    private int page;
    private boolean destroyed;
    private TextView body, status;
    private Button previous, next;
    private ScrollView scroll;
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        scope = getIntent().getStringExtra("scope");
        id = getIntent().getStringExtra("export_id");
        if (!CaptureAccountSession.hasAccount(this)
            || !CaptureAccountSession.scope(this).equals(scope) || id == null
            || !id.matches("[a-f0-9]{32}")) {
            finish();
            return;
        }
        page = state == null ? 0 : Math.max(0, state.getInt("page"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(8), dp(22), dp(14));
        root.setBackgroundColor(getColor(R.color.cream));
        LinearLayout outer = new LinearLayout(this);
        outer.setFitsSystemWindows(true);
        outer.setBackgroundColor(getColor(R.color.cream));
        outer.addView(root, new LinearLayout.LayoutParams(-1, -1));
        setContentView(outer);
        Button back = new Button(this);
        back.setText("返回分享管理");
        back.setBackgroundResource(android.R.color.transparent);
        root.addView(back);
        back.setOnClickListener(v -> finish());
        TextView title = new TextView(this);
        title.setText("分享时的文字");
        title.setTextSize(28);
        title.setTextColor(getColor(R.color.ink));
        root.addView(title);
        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(getColor(R.color.ink_muted));
        status.setPadding(0, dp(10), 0, dp(14));
        root.addView(status);
        scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = new TextView(this);
        body.setTextSize(16);
        body.setTextColor(getColor(R.color.ink));
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(6), 1);
        body.setPadding(dp(18), dp(18), dp(18), dp(18));
        body.setBackgroundResource(R.drawable.bg_card);
        scroll.addView(body);
        LinearLayout actions = new LinearLayout(this);
        root.addView(actions);
        previous = new Button(this);
        previous.setText("上一页");
        actions.addView(previous);
        next = new Button(this);
        next.setText("下一页");
        actions.addView(next);
        Button retry = new Button(this);
        retry.setText("重新读取");
        actions.addView(retry);
        for (Button button : new Button[] {previous, next, retry}) {
            button.setBackgroundResource(android.R.color.transparent);
            button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
            button.setTextSize(14);
        }
        previous.setOnClickListener(v -> {
            page--;
            render();
        });
        next.setOnClickListener(v -> {
            page++;
            render();
        });
        retry.setOnClickListener(v -> load());
        load();
    }
    private boolean active() {
        return !destroyed && !isFinishing() && CaptureAccountSession.scope(this).equals(scope);
    }
    private void load() {
        status.setText("正在读取当时保存的文字…");
        previous.setEnabled(false);
        next.setEnabled(false);
        worker.execute(() -> {
            try {
                CaptureSyncPreferences.Config config;
                synchronized (CaptureAccountSession.LOCK) {
                    CaptureAccountSession.requireScope(this, scope);
                    config = CaptureAccountSession.config(this);
                }
                String text = CaptureAccountHttp
                                  .request(config.baseUrl, "GET", "/v1/exports/" + id + "/text",
                                      config.writeToken, null, null)
                                  .getString("text");
                runOnUiThread(() -> {
                    if (active()) {
                        content = text;
                        render();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (active())
                        status.setText(R.string.share_text_unavailable);
                });
            }
        });
    }
    private void render() {
        int pages = Math.max(1, (content.length() + 11999) / 12000);
        page = Math.max(0, Math.min(page, pages - 1));
        int start = page * 12000, end = Math.min(content.length(), start + 12000);
        // Keep surrogate pairs intact at page boundaries.
        if (start > 0 && Character.isLowSurrogate(content.charAt(start)))
            start--;
        if (end < content.length() && Character.isLowSurrogate(content.charAt(end)))
            end--;
        body.setText(content.substring(start, end));
        status.setText(getString(R.string.share_text_page, page + 1, pages));
        previous.setEnabled(page > 0);
        next.setEnabled(page + 1 < pages);
        scroll.scrollTo(0, 0);
    }
    @Override
    protected void onResume() {
        super.onResume();
        if (scope != null && !CaptureAccountSession.scope(this).equals(scope))
            finish();
    }
    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
    }
    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
