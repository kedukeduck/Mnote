package com.codex.mnote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

/** One quiet home for account and application preferences. */
public final class SettingsActivity extends Activity {
    private TextView accountSummary;
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(getColor(R.color.cream));
        setContentView(scroll);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(8), dp(22), dp(28));
        scroll.addView(root);
        Button back = new Button(this);
        back.setText("返回");
        back.setBackgroundResource(android.R.color.transparent);
        root.addView(back, new LinearLayout.LayoutParams(-2, dp(48)));
        back.setOnClickListener(v -> finish());
        TextView title = text(root, "设置", 32, R.color.ink);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text(root, "让记录安心保存，让 Mnote 保持最新。", 14, R.color.ink_muted);
        section(root, "我的空间");
        accountSummary = row(root, "账号与同步", "", R.id.settings_account,
            () -> startActivity(new Intent(this, CaptureAccountActivity.class)));
        section(root, "应用");
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        row(root, "版本与更新", "当前 " + version + " · 从 Mnote 服务器获取", R.id.settings_updates,
            () -> startActivity(new Intent(this, AppUpdateActivity.class)));
        text(root, "更新无需笔记账号或 Token。安装前保存草稿，覆盖安装即可保留现有记录。", 12,
            R.color.ink_muted);
    }
    @Override
    protected void onResume() {
        super.onResume();
        accountSummary.setText(CaptureAccountSession.hasAccount(this)
                ? CaptureAccountSession.username(this) + " · 管理登录、同步与本机导入"
                : "登录后，在不同设备之间同步记录");
    }
    private void section(LinearLayout root, String value) {
        TextView label = text(root, value, 12, R.color.ink_muted);
        label.setPadding(0, dp(30), 0, dp(10));
    }
    private TextView row(LinearLayout root, String title, String detail, int id, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setId(id);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setMinimumHeight(dp(100));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));
        text(card, title, 18, R.color.ink);
        return text(card, detail, 13, R.color.ink_muted);
    }
    private TextView text(LinearLayout root, String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(getColor(color));
        v.setPadding(0, dp(6), 0, dp(6));
        v.setLineSpacing(dp(3), 1);
        root.addView(v);
        return v;
    }
    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
