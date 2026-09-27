package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.*;

/** First-party username/password login; passwords are never persisted. */
public final class CaptureAccountActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText server, username, password, invitation;
    private TextView status;
    private Button login, activate, logout, importNotes, sync;
    private TextView syncSummary;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener syncListener;
    private boolean activating, busy;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(getColor(R.color.cream));
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(14), dp(22), dp(28));
        column.setBackgroundColor(getColor(R.color.cream)); scroll.addView(column); setContentView(scroll);
        boolean signedIn = CaptureAccountSession.hasAccount(this);
        JournalUi.header(this, column, signedIn ? "账号与同步" : "登录", this::finish);
        TextView title = new TextView(this); title.setText(signedIn ? CaptureAccountSession.username(this) : "Mnote"); title.setTextSize(34); title.setTextColor(getColor(R.color.ink));
        title.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL));
        title.setPadding(0, dp(24), 0, dp(12));
        column.addView(title);
        status = new TextView(this); status.setTextSize(14); status.setPadding(0,dp(12),0,dp(24));
        status.setTextColor(getColor(R.color.ink_muted)); status.setLineSpacing(dp(4), 1);
        status.setText(CaptureAccountSession.hasAccount(this) ? "当前账号：" + CaptureAccountSession.username(this)
                + "\n同一账号自动同步记录。退出后本机缓存保留，但不会向其他账号显示。" : "登录后，想法和截图会自动同步到你的账号。");
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); column.addView(status);
        if (signedIn) {
            JournalUi.rule(column);
            JournalUi.section(column, "自动同步");
            column.addView(JournalUi.text(this, "已开启 · 本机保存后自动同步", 16, R.color.ink));
            syncSummary = JournalUi.text(this, "正在读取同步状态…", 13, R.color.ink_muted);
            syncSummary.setPadding(0, dp(12), 0, dp(12));
            syncSummary.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
            column.addView(syncSummary);
            sync = button(column, "立即同步", this::syncNow);
            JournalUi.primary(sync);
            syncListener = (preferences, key) -> {
                if ("sync_error".equals(key) || "last_sync".equals(key)) renderSync();
            };
            CaptureAccountSession.preferences(this).registerOnSharedPreferenceChangeListener(syncListener);
            renderSync();
        } else {
            TextView loginTitle = JournalUi.text(this, "登录你的账号", 24, R.color.ink);
            column.addView(loginTitle);
        }
        LinearLayout form = JournalUi.column(this);
        column.addView(form);
        server = input(form, "服务地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(CaptureAccountSession.baseUrl(this));
        username = input(form, "用户名（3–32 位英文、数字、._-）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        username.setText(CaptureAccountSession.username(this));
        username.setHint("输入用户名");
        password = input(form, "密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint("输入密码");
        invitation = input(form, "首次激活码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        invitation.setVisibility(android.view.View.GONE);
        ((TextView) invitation.getTag()).setVisibility(android.view.View.GONE);
        login = button(form, "登录并同步", () -> authenticate());
        JournalUi.primary(login);
        activate = button(form, "首次激活账号", () -> {
            activating = !activating;
            invitation.setVisibility(activating ? android.view.View.VISIBLE : android.view.View.GONE);
            ((TextView) invitation.getTag()).setVisibility(invitation.getVisibility());
            login.setText(activating ? "设置账号并同步" : "登录并同步");
            activate.setText(activating ? "已有账号，返回登录" : "首次激活账号");
            ((TextView) password.getTag()).setText(activating ? "设置密码（至少 12 位）" : "密码");
        });
        if (signedIn) {
            form.setVisibility(android.view.View.GONE);
            JournalUi.section(column, "管理账号");
            button(column, "服务地址与重新登录", () -> {
                if (!canChange()) return;
                form.setVisibility(form.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE);
            });
            TextView address = JournalUi.text(this, CaptureAccountSession.baseUrl(this), 13, R.color.ink_muted);
            address.setTextIsSelectable(true); column.addView(address);
        } else {
            button(column, "暂不登录，仅保存在本机", this::finish);
        }
        logout = button(column, "退出账号", () -> new AlertDialog.Builder(this).setMessage("退出会暂停同步并隐藏此账号的本机缓存。未上传内容保留，重新登录同一账号后继续。")
                .setPositiveButton("退出", (dialog, which) -> logout()).setNegativeButton("取消", null).show());
        importNotes = button(column, "导入未登录时的本机旧记录", () -> new AlertDialog.Builder(this)
                .setTitle("导入本机旧记录？")
                .setMessage("将本机旧记录复制到账号「" + CaptureAccountSession.username(this) + "」并上传。原本机记录保留；请确认这是你的账号。")
                .setPositiveButton("导入并同步", (dialog, which) -> importNotes()).setNegativeButton("取消", null).show());
        logout.setVisibility(CaptureAccountSession.hasAccount(this) ? android.view.View.VISIBLE : android.view.View.GONE);
        importNotes.setVisibility(logout.getVisibility());
        logout.setTextColor(getColor(R.color.danger));
    }

    private boolean canChange() {
        if (busy) return false;
        if (CaptureAccessibilityService.hasOverlay()) { status.setText("请先保存或取消当前悬浮摘录，再切换账号。"); return false; }
        return true;
    }
    private void authenticate() {
        if (!canChange()) return;
        String base = server.getText().toString().trim(), user = username.getText().toString().trim();
        String pass = password.getText().toString(), code = invitation.getText().toString().trim();
        if (!user.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{2,31}") || pass.isEmpty() || pass.length() > 128
                || (activating && (pass.length() < 12 || code.isEmpty()))) {
            status.setText("请检查用户名、密码和首次激活码。密码不会保存到手机。"); return;
        }
        boolean create = activating;
        setBusy(true); status.setText("正在安全登录…");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("username", user).put("password", pass);
                if (create) body.put("invitation", code);
                JSONObject result = CaptureAccountHttp.request(base, "POST", create ? "/v1/auth/activate" : "/v1/auth/login", null, body, null);
                synchronized (CaptureAccountSession.LOCK) {
                    if (destroyed) return;
                    CaptureAccountSession.save(this, base, result);
                }
                CaptureAccountSync.enqueue(this);
                runOnUiThread(() -> { if (!destroyed) { password.setText(""); invitation.setText(""); Toast.makeText(this, "已登录，正在同步记录", Toast.LENGTH_SHORT).show(); finish(); } });
            } catch (Exception error) { error(error); }
        });
    }
    private void logout() {
        if (!canChange()) return;
        setBusy(true); status.setText("正在退出…");
        executor.execute(() -> {
            try {
                synchronized (CaptureAccountSession.LOCK) {
                    CaptureSyncPreferences.Config old = null;
                    try { old = CaptureAccountSession.config(this); } catch (Exception ignored) { }
                    // Local logout succeeds offline; the remote session otherwise expires in 30 days.
                    if (old != null) try { CaptureAccountHttp.request(old.baseUrl, "POST", "/v1/auth/logout", old.writeToken, new JSONObject(), null); }
                    catch (Exception ignored) { }
                    CaptureAccountSync.cancel(this); CaptureAccountSession.clear(this);
                }
                runOnUiThread(() -> { if (!destroyed) finish(); });
            } catch (Exception error) { error(error); }
        });
    }
    private void importNotes() {
        if (!canChange()) return;
        setBusy(true); status.setText("正在导入本机旧记录…");
        executor.execute(() -> {
            try {
                int count = CaptureAccountImport.run(this);
                CaptureAccountSync.enqueue(this);
                runOnUiThread(() -> { if (!destroyed) { setBusy(false); status.setText("已导入 " + count + " 条，正在同步。"); } });
            } catch (Exception error) { error(error); }
        });
    }
    private void error(Exception error) {
        String code = error.getMessage();
        String message = "http_401".equals(code) ? "用户名或密码不正确，请重试。"
                : "http_403".equals(code) ? "激活码无效或已使用。"
                : "http_409".equals(code) ? "用户名已被使用，请更换。"
                : "http_429".equals(code) ? "尝试次数过多，请稍后再试。"
                : "https_required".equals(code) ? "账号登录必须使用 HTTPS 服务地址。"
                : "操作未完成，请检查网络和服务地址；本机记录已保留。";
        runOnUiThread(() -> { if (!destroyed) { setBusy(false); status.setText(message); } });
    }
    private void setBusy(boolean value) {
        busy = value;
        for (Button button : new Button[]{login,activate,logout,importNotes}) button.setEnabled(!value);
        if (sync != null) sync.setEnabled(!value);
    }
    private void renderSync() {
        if (destroyed || syncSummary == null) return;
        android.content.SharedPreferences preferences = CaptureAccountSession.preferences(this);
        String error = preferences.getString("sync_error", "");
        long last = preferences.getLong("last_sync", 0);
        String result = "login_required".equals(error) ? "登录已过期，请重新登录；本机内容已保留。"
            : "revision_conflict".equals(error) ? "云端有新版本，本机修改已保留。"
            : !error.isEmpty() ? "同步未完成，可重试；本机记录已保留。"
            : last == 0 ? "还没有完成同步。" : "最近同步：" + android.text.format.DateFormat.format("M月d日 HH:mm", last);
        syncSummary.setText(result);
    }
    private void syncNow() {
        if (busy) return;
        setBusy(true); status.setText("正在同步记录…");
        executor.execute(() -> {
            try {
                CaptureAccountSync.run(this);
                runOnUiThread(() -> { if (!destroyed) { setBusy(false); status.setText("记录已同步"); renderSync(); } });
            } catch (Exception failure) {
                runOnUiThread(() -> { if (!destroyed) { setBusy(false); status.setText("同步未完成，本机内容已保留。"); renderSync(); } });
            }
        });
    }
    private EditText input(LinearLayout column, String hint, int type) {
        TextView label = new TextView(this); label.setText(hint);
        label.setTextSize(13); label.setTextColor(getColor(R.color.ink_muted));
        label.setPadding(dp(2), dp(8), 0, dp(8)); column.addView(label);
        EditText input = new EditText(this); input.setHint(hint); input.setInputType(type); input.setSingleLine(true);
        input.setTextSize(16); input.setTextColor(getColor(R.color.ink)); input.setHintTextColor(getColor(R.color.ink_muted));
        input.setId(android.view.View.generateViewId());
        label.setLabelFor(input.getId());
        input.setTag(label);
        input.setSaveEnabled(false); input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setBackgroundResource(R.drawable.bg_input); input.setPadding(dp(16),dp(14),dp(16),dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.bottomMargin=dp(12);
        column.addView(input,params); return input;
    }
    private Button button(LinearLayout column, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setMinHeight(dp(48));
        JournalUi.quiet(button);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2);
        params.topMargin=dp(10); column.addView(button,params); return button;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    @Override public void onDestroy() {
        destroyed = true;
        if (syncListener != null) CaptureAccountSession.preferences(this).unregisterOnSharedPreferenceChangeListener(syncListener);
        executor.shutdownNow(); super.onDestroy();
    }
}
