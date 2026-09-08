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
    private Button login, activate, logout, importNotes;
    private boolean activating, busy;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(getColor(R.color.cream));
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(28), dp(24), dp(28));
        column.setBackgroundColor(getColor(R.color.cream)); scroll.addView(column); setContentView(scroll);
        TextView title = new TextView(this); title.setText("你的 Mnote"); title.setTextSize(30); title.setTextColor(getColor(R.color.ink));
        column.addView(title);
        status = new TextView(this); status.setTextSize(14); status.setPadding(0,dp(16),0,dp(20));
        status.setText(CaptureAccountSession.hasAccount(this) ? "当前账号：" + CaptureAccountSession.username(this)
                + "\n同一账号自动同步记录。退出后本机缓存保留，但不会向其他账号显示。" : "登录后，想法和截图会自动同步到你的账号。");
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); column.addView(status);
        server = input(column, "服务地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(CaptureAccountSession.baseUrl(this));
        username = input(column, "用户名（3–32 位英文、数字、._-）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        username.setText(CaptureAccountSession.username(this));
        password = input(column, "密码（首次设置至少 12 位）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        invitation = input(column, "首次激活码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        invitation.setVisibility(android.view.View.GONE);
        login = button(column, "登录并同步", () -> authenticate());
        login.setBackgroundResource(R.drawable.bg_button_primary); login.setTextColor(getColor(R.color.white));
        activate = button(column, "首次激活账号", () -> {
            activating = !activating;
            invitation.setVisibility(activating ? android.view.View.VISIBLE : android.view.View.GONE);
            login.setText(activating ? "设置账号并同步" : "登录并同步");
            activate.setText(activating ? "已有账号，返回登录" : "首次激活账号");
        });
        logout = button(column, "退出账号", () -> new AlertDialog.Builder(this).setMessage("退出会暂停同步并隐藏此账号的本机缓存。未上传内容保留，重新登录同一账号后继续。")
                .setPositiveButton("退出", (dialog, which) -> logout()).setNegativeButton("取消", null).show());
        importNotes = button(column, "导入未登录时的本机旧记录", () -> new AlertDialog.Builder(this)
                .setMessage("将本机旧记录复制到当前账号并上传。旧记录不会删除；请确认这是你的账号。")
                .setPositiveButton("导入并同步", (dialog, which) -> importNotes()).setNegativeButton("取消", null).show());
        logout.setVisibility(CaptureAccountSession.hasAccount(this) ? android.view.View.VISIBLE : android.view.View.GONE);
        importNotes.setVisibility(logout.getVisibility());
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
    }
    private EditText input(LinearLayout column, String hint, int type) {
        EditText input = new EditText(this); input.setHint(hint); input.setInputType(type); input.setSingleLine(true);
        input.setSaveEnabled(false); input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setBackgroundResource(R.drawable.bg_input); input.setPadding(dp(16),dp(14),dp(16),dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.bottomMargin=dp(12);
        column.addView(input,params); return input;
    }
    private Button button(LinearLayout column, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run()); column.addView(button,new LinearLayout.LayoutParams(-1,-2)); return button;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    @Override public void onDestroy() { destroyed = true; executor.shutdownNow(); super.onDestroy(); }
}
