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
        JournalUi.header(this, root, "设置", this::finish);
        TextView title = text(root, "Mnote", 34, R.color.ink);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        View folio = new View(this);
        folio.setBackgroundColor(getColor(R.color.coral));
        root.addView(folio, new LinearLayout.LayoutParams(dp(44), dp(5)));
        section(root, "我的记录");
        accountSummary = row(root, "账号与同步", "", R.id.settings_account,
            () -> startActivity(new Intent(this, CaptureAccountActivity.class)));
        row(root, "分享管理", "回看已导出的文字与图片，管理分享链接", R.id.settings_shares, () -> {
            if (!CaptureAccountSession.hasAccount(this)) {
                Toast.makeText(this, "请先登录后查看分享", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, CaptureAccountActivity.class));
            } else {
                startActivity(new Intent(this, ShareGalleryActivity.class)
                    .putExtra("scope", CaptureAccountSession.scope(this)));
            }
        });
        section(root, "AI 与思考");
        row(root, "AI 模型配置", "自定义模型服务，密钥仅保存在本机", R.id.ai_settings_models,
            () -> startActivity(new Intent(this, AiModelSettingsActivity.class)));
        row(root, "全部 AI 对话", "回顾每条记录的讨论，继续或整理会话", R.id.ai_settings_history,
            () -> startActivity(AiUi.history(this, CaptureAccountSession.scope(this), null)));
        section(root, "应用");
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        row(root, "版本与更新", "当前 " + version + " · 从 Mnote 服务器获取", R.id.settings_updates,
            () -> startActivity(new Intent(this, AppUpdateActivity.class)));
        row(root, "快捷方式与权限", "添加统一记录按钮与设置截图权限", View.NO_ID,
            () -> startActivity(new Intent(this, CapturePermissionsActivity.class)));
        row(root, "帮助与诊断", "使用说明与本机异常诊断", View.NO_ID, this::showHelp);
        text(root, "本机保存成功后，登录状态下自动同步。", 13, R.color.ink_muted)
            .setPadding(0, dp(24), 0, 0);
    }
    @Override
    protected void onResume() {
        super.onResume();
        accountSummary.setText(CaptureAccountSession.hasAccount(this)
                ? CaptureAccountSession.username(this) + " · 管理登录、同步与本机导入"
                : "登录后，在不同设备之间同步记录");
    }
    private void section(LinearLayout root, String value) {
        TextView label = text(root, value, 13, R.color.copper);
        label.setPadding(0, dp(30), 0, dp(10));
    }
    private TextView row(LinearLayout root, String title, String detail, int id, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setId(id);
        card.setBackgroundResource(android.R.color.transparent);
        card.setPadding(0, dp(12), 0, dp(12));
        card.setMinimumHeight(dp(80));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));
        text(card, title, 17, R.color.ink);
        TextView summary = text(card, detail, 13, R.color.ink_muted);
        JournalUi.rule(root);
        return summary;
    }
    private void showHelp() {
        new android.app.AlertDialog.Builder(this).setTitle("帮助与诊断")
            .setMessage("记录：切换到需要记录的页面，从快捷设置启动。先保存一份临时全屏图，再在编辑页选择想法、摘录、原文、来源或截图。\n\n圈选与批注：从本次原始截图编辑，原图与圈选结果独立保留。取消勾选不清空本次草稿，保存只包含选中模块。剪贴板和页面文字只在主动点击后读取。\n\n分享与导出：长按列表记录即可多选；公开链接可在分享管理撤销。\n\n诊断仅包含运行信息，不包含笔记正文或账号凭证。")
            .setPositiveButton("查看异常诊断", (d, which) -> MnoteApplication.showDiagnostic(this))
            .setNegativeButton("关闭", null).show();
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
