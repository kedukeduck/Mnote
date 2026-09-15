package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.concurrent.*;

/** Explicit foreground-only update flow. No sync credentials and no silent installation. */
public final class AppUpdateActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status, details;
    private Button check, download, install;
    private ProgressBar progress;
    private AppRelease release;
    private File packageFile;
    private String current;
    private volatile boolean destroyed;
    private boolean busy;
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("版本与更新");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(getColor(R.color.cream));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(32));
        scroll.addView(root);
        setContentView(scroll);
        Button back = button(root, "返回");
        back.setOnClickListener(v -> finish());
        text(root, "让 Mnote 保持最新", 25);
        try {
            current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception error) {
            current = "0.0.0";
        }
        text(root, "当前版本：" + current, 15);
        text(root,
            "更新不会清空账号和笔记。请先保存尚未完成的想法；下载后由系统确认覆盖安装，不要卸载旧版"
            + "。测试版会检查测试及正式发布，正式版只检查正式发布。",
            14);
        status = text(root, "点击检查更新，获取 Android 最新版本。", 15);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        details = text(root, "", 14);
        details.setTextIsSelectable(true);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress);
        check = button(root, "检查更新");
        download = button(root, "下载更新");
        install = button(root, "安装更新");
        check.setOnClickListener(v -> check());
        download.setOnClickListener(v -> download());
        install.setOnClickListener(v -> install());
        Button releases = button(root, "打开官方发布页");
        releases.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AppRelease.PAGE)));
            } catch (Exception error) {
                status.setText("找不到可用浏览器。");
            }
        });
        buttons();
        check();
    }
    private void buttons() {
        check.setEnabled(!busy);
        download.setEnabled(!busy && release != null);
        install.setEnabled(!busy && packageFile != null);
    }
    private void ui(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed && !isFinishing())
                action.run();
        });
    }
    private void fail(Exception error) {
        ui(() -> {
            busy = false;
            status.setText(AppUpdateClient.error(error));
            buttons();
        });
    }
    private void check() {
        if (busy)
            return;
        busy = true;
        release = null;
        packageFile = null;
        details.setText("");
        status.setText("正在查询官方发布…");
        buttons();
        executor.execute(() -> {
            try {
                AppRelease result = AppUpdateClient.check(current);
                ui(() -> {
                    release = result;
                    busy = false;
                    status.setText(result == null ? "当前已是最新可用版本。"
                                                  : "发现新版本：" + result.version);
                    if (result != null)
                        details.setText(
                            "安装包：" + (result.size / 1024 / 1024.0) + " MiB\n\n" + result.notes);
                    buttons();
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }
    private void download() {
        if (busy || release == null)
            return;
        AppRelease selected = release;
        new AlertDialog.Builder(this)
            .setTitle("下载 " + selected.version)
            .setMessage(
                "将从 GitHub 下载更新，可能使用移动数据。安装前会校验文件、应用身份和签名。")
            .setNegativeButton("取消", null)
            .setPositiveButton("下载",
                (d, which) -> {
                    busy = true;
                    buttons();
                    status.setText("正在下载…");
                    progress.setProgress(0);
                    executor.execute(() -> {
                        try {
                            File file = AppUpdateClient.download(getApplicationContext(), selected,
                                p -> ui(() -> progress.setProgress(p)));
                            ui(() -> {
                                packageFile = file;
                                busy = false;
                                status.setText("下载和校验完成，点击安装更新。");
                                buttons();
                            });
                        } catch (Exception error) {
                            fail(error);
                        }
                    });
                })
            .show();
    }
    private void install() {
        if (busy || packageFile == null || release == null)
            return;
        if (CaptureAccessibilityService.hasOverlay()) {
            status.setText("请先保存或关闭悬浮层中的记录，再安装更新。");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                .setTitle("允许 Mnote 安装更新")
                .setMessage(
                    "系统需要你授权此应用安装更新。返回后，再点击“安装更新”。不会静默安装。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往设置",
                    (d, which) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                        } catch (Exception error) {
                            status.setText(
                                "无法打开系统授权页，请在系统设置中允许 Mnote 安装未知应用。");
                        }
                    })
                .show();
            return;
        }
        File file = packageFile;
        AppRelease selected = release;
        busy = true;
        buttons();
        status.setText("正在复核安装包…");
        executor.execute(() -> {
            try {
                AppUpdateClient.verify(getApplicationContext(), file, selected);
                ui(() -> {
                    busy = false;
                    buttons();
                    try {
                        Uri uri =
                            FileProvider.getUriForFile(this, getPackageName() + ".updates", file);
                        Intent intent =
                            new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(intent);
                        status.setText("请在系统安装器中确认更新。取消后可再次点击安装。");
                    } catch (Exception error) {
                        status.setText(
                            "无法启动系统安装器。请使用官方发布页下载并覆盖安装，不要卸载旧版。");
                    }
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }
    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private TextView text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(R.color.ink));
        view.setPadding(0, dp(10), 0, dp(12));
        root.addView(view);
        return view;
    }
    private Button button(LinearLayout root, String value) {
        Button view = new Button(this);
        view.setText(value);
        view.setAllCaps(false);
        view.setMinHeight(dp(52));
        root.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
}
