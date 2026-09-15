package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Select a bounded, account-owned snapshot. No public links are created before confirmation. */
public final class MarkdownExportActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<CaptureStore.CaptureRecord> rows = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private ListView list;
    private TextView status;
    private Button export, all, clear, manage;
    private String scope;
    private boolean busy, picker;
    private volatile boolean destroyed;
    private JSONArray pending;
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        scope = CaptureAccountSession.scope(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.cream));
        root.setFitsSystemWindows(true);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        setContentView(root);
        Button back = button(root, "返回");
        back.setOnClickListener(v -> finish());
        TextView title = text(root, "导出给 AI", 26);
        text(root,
            "勾选记录，导出一个 Markdown "
            + "文件。截图生成无需登录、持有链接即可访问的独立分享快照；可撤销。",
            14);
        text(root,
            "仅支持当前账号已同步的记录，最多 100 条。未同步、冲突和禁止 AI 访问的记录不可选择。",
            13);
        LinearLayout actions = new LinearLayout(this);
        root.addView(actions);
        all = button(actions, "全选可用记录");
        clear = button(actions, "清空选择");
        status = text(root, "正在加载记录…", 14);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        export = button(root, "导出 Markdown");
        manage = button(root, "管理导出图片链接");
        all.setOnClickListener(v -> {
            if (busy || picker)
                return;
            long count = rows.stream().filter(MarkdownExportActivity::eligible).count();
            if (count > 100) {
                notice("可用记录超过 100 条，请先在首页缩小筛选范围，或逐条勾选。");
                return;
            }
            for (CaptureStore.CaptureRecord r : rows)
                if (eligible(r))
                    selected.add(r.id);
            checks();
        });
        clear.setOnClickListener(v -> {
            selected.clear();
            checks();
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            CaptureStore.CaptureRecord r = rows.get(position);
            if (busy || picker || !eligible(r)) {
                list.setItemChecked(position, selected.contains(r.id));
                return;
            }
            if (list.isItemChecked(position)) {
                if (selected.size() >= 100) {
                    list.setItemChecked(position, false);
                    notice("每次最多导出 100 条。");
                    return;
                }
                selected.add(r.id);
            } else
                selected.remove(r.id);
            checks();
        });
        export.setOnClickListener(v -> confirm());
        manage.setOnClickListener(v -> manage());
        if (state != null && scope.equals(state.getString("scope"))) {
            ArrayList<String> ids = state.getStringArrayList("selected");
            if (ids != null)
                selected.addAll(ids);
            picker = state.getBoolean("picker");
            try {
                String json = state.getString("pending");
                if (json != null)
                    pending = new JSONArray(json);
            } catch (Exception ignored) {
            }
        }
        load();
    }
    static boolean eligible(CaptureStore.CaptureRecord r) {
        return !"deny".equals(r.aiAccess) && CaptureStore.SYNC_SYNCED.equals(r.syncState)
            && r.serverRevision > 0;
    }
    private void load() {
        busy = true;
        buttons();
        worker.execute(() -> {
            try {
                List<CaptureStore.CaptureRecord> records;
                synchronized (CaptureAccountSession.LOCK) {
                    CaptureAccountSession.requireScope(this, scope);
                    records =
                        CaptureRemoteCache.merged(this, CaptureStore.list(this, Integer.MAX_VALUE));
                }
                String query = getIntent().getStringExtra("query"),
                       tag = getIntent().getStringExtra("tag");
                int type = getIntent().getIntExtra("type", 0);
                List<CaptureStore.CaptureRecord> matches = new ArrayList<>();
                for (CaptureStore.CaptureRecord r : records) {
                    String hay = r.comment + "\n" + r.sourceText + "\n"
                        + CaptureRecordEdits.original(r) + "\n" + r.sourceUrl + "\n"
                        + CaptureTags.input(r.tags);
                    boolean kind = type == 1 ? r.hasImage || !r.sourceText.isEmpty()
                            || !CaptureRecordEdits.original(r).isEmpty()
                        : type == 2 ? "thought".equals(r.kind)
                        : type == 3 ? "todo".equals(r.kind)
                                    : true;
                    if (kind && CaptureTags.matches(r.tags, tag)
                        && (query == null
                            || hay.toLowerCase(Locale.ROOT)
                                .contains(query.toLowerCase(Locale.ROOT))))
                        matches.add(r);
                }
                ui(() -> {
                    rows.clear();
                    rows.addAll(matches);
                    selected.removeIf(
                        id -> rows.stream().noneMatch(r -> r.id.equals(id) && eligible(r)));
                    List<String> labels = new ArrayList<>();
                    for (CaptureStore.CaptureRecord r : rows) {
                        String body = r.comment.isEmpty() ? r.sourceText : r.comment;
                        labels.add((eligible(r) ? "" : "[不可导出] ")
                            + android.text.format.DateFormat.format("MM-dd HH:mm", r.createdAt)
                            + " · " + CaptureTags.display(r.tags) + "\n"
                            + (body.isEmpty() ? "截图记录"
                                              : body.substring(0, Math.min(100, body.length()))
                                                    .replace('\n', ' ')));
                    }
                    list.setAdapter(new ArrayAdapter<String>(
                        this, android.R.layout.simple_list_item_multiple_choice, labels) {
                        @Override
                        public boolean isEnabled(int position) {
                            return eligible(rows.get(position));
                        }
                        @Override
                        public boolean areAllItemsEnabled() {
                            return false;
                        }
                    });
                    busy = false;
                    checks();
                });
            } catch (Exception error) {
                failure(error);
            }
        });
    }
    private void checks() {
        for (int i = 0; i < rows.size(); i++)
            list.setItemChecked(i, selected.contains(rows.get(i).id));
        status.setText("已选择 " + selected.size() + " / " + rows.size() + " 条"
            + (CaptureAccountSession.hasAccount(this) ? "" : " · 请先登录并同步"));
        buttons();
    }
    private void buttons() {
        if (export == null)
            return;
        export.setEnabled(
            !busy && !picker && !selected.isEmpty() && CaptureAccountSession.hasAccount(this));
        all.setEnabled(!busy && !picker);
        clear.setEnabled(!busy && !picker);
        list.setEnabled(!busy && !picker);
        manage.setEnabled(!busy && !picker && CaptureAccountSession.hasAccount(this));
    }
    private void confirm() {
        if (busy || picker || selected.isEmpty())
            return;
        new AlertDialog.Builder(this)
            .setTitle("导出 " + selected.size() + " 条记录？")
            .setMessage("将导出想法、摘录、原文及完整截图／圈选图链接。仅这些记录的图片会获得独立分"
                        + "享链接，任何持有链接的人均可访问。原笔记权限不变。\n\n请确认截图可分享。"
                        + "链接可在此页管理撤销，但已下载的副本无法收回。")
            .setNegativeButton("取消", null)
            .setPositiveButton("选择保存位置",
                (d, w) -> {
                    try {
                        pending = new JSONArray();
                        for (CaptureStore.CaptureRecord r : rows)
                            if (selected.contains(r.id))
                                pending.put(new JSONObject()
                                        .put("id", r.id)
                                        .put("revision", r.serverRevision));
                        picker = true;
                        buttons();
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                            .addCategory(Intent.CATEGORY_OPENABLE)
                                            .setType("text/markdown")
                                            .putExtra(Intent.EXTRA_TITLE,
                                                "Mnote-" + java.time.LocalDate.now() + ".md");
                        startActivityForResult(intent, 902);
                    } catch (Exception error) {
                        picker = false;
                        notice("无法打开文件保存位置。");
                        buttons();
                    }
                })
            .show();
    }
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 902)
            return;
        picker = false;
        if (result != RESULT_OK || data == null || data.getData() == null || pending == null) {
            pending = null;
            buttons();
            return;
        }
        Uri target = data.getData();
        JSONArray selection = pending;
        pending = null;
        busy = true;
        buttons();
        status.setText("正在生成 Markdown 和图片分享链接…");
        worker.execute(() -> {
            String created = null;
            CaptureSyncPreferences.Config config = null;
            boolean saved = false;
            try {
                synchronized (CaptureAccountSession.LOCK) {
                    CaptureAccountSession.requireScope(this, scope);
                    config = CaptureAccountSession.config(this);
                    // Revalidate local revisions/state after returning from the system picker.
                    for (int i = 0; i < selection.length(); i++) {
                        JSONObject item = selection.getJSONObject(i);
                        CaptureStore.CaptureRecord latest =
                            CaptureRecordEdits.latest(this, scope, item.getString("id"));
                        if (!eligible(latest) || latest.serverRevision != item.getInt("revision"))
                            throw new java.io.IOException("export_changed");
                    }
                    JSONObject resultJson = CaptureAccountHttp.request(config.baseUrl, "POST",
                        "/v1/exports/markdown", config.writeToken,
                        new JSONObject()
                            .put("records", selection)
                            .put("publish_images", true)
                            .put("scope", "首页筛选后手动选择，共 " + selection.length() + " 条"),
                        null);
                    created = resultJson.getString("id");
                    String markdown = resultJson.getString("markdown");
                    if (!created.matches("[a-f0-9]{32}")
                        || !markdown.startsWith("# Mnote 记录导出"))
                        throw new java.io.IOException("invalid_export");
                    CaptureAccountSession.requireScope(this, scope);
                    try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                        if (out == null)
                            throw new java.io.IOException("write_failed");
                        out.write(markdown.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    saved = true;
                }
                ui(() -> {
                    busy = false;
                    notice("Markdown 已保存，图片链接可在下方管理撤销。");
                    buttons();
                });
            } catch (Exception error) {
                failure(error);
            } finally {
                if (!saved && created != null && config != null && created.matches("[a-f0-9]{32}"))
                    try {
                        CaptureAccountHttp.request(config.baseUrl, "DELETE",
                            "/v1/exports/" + created, config.writeToken, null, null);
                    } catch (Exception ignored) {
                        ui(()
                                -> notice("保存失败，自动撤销未完成，请到“管理导出图片链接”撤销此次"
                                          + "分享。"));
                    }
            }
        });
    }
    private void manage() {
        if (busy || picker)
            return;
        busy = true;
        buttons();
        status.setText("正在读取导出列表…");
        worker.execute(() -> {
            try {
                JSONObject result;
                synchronized (CaptureAccountSession.LOCK) {
                    CaptureAccountSession.requireScope(this, scope);
                    CaptureSyncPreferences.Config c = CaptureAccountSession.config(this);
                    result = CaptureAccountHttp.request(
                        c.baseUrl, "GET", "/v1/exports", c.writeToken, null, null);
                }
                JSONArray exports = result.getJSONArray("exports");
                String[] labels = new String[exports.length()];
                for (int i = 0; i < labels.length; i++) {
                    JSONObject e = exports.getJSONObject(i);
                    labels[i] = e.getString("created") + " · " + e.getInt("record_count") + " 条 / "
                        + e.getInt("image_count") + " 张图";
                }
                ui(() -> {
                    busy = false;
                    buttons();
                    if (labels.length == 0) {
                        notice("没有有效的导出分享链接。");
                        return;
                    }
                    new AlertDialog.Builder(this)
                        .setTitle("选择要撤销的导出")
                        .setItems(labels,
                            (d, which) -> {
                                String id = exports.optJSONObject(which).optString("id");
                                new AlertDialog.Builder(this)
                                    .setTitle("撤销这次导出的所有图片链接？")
                                    .setMessage("原始笔记和截图不删除；已导出的文字和已下载的图片无"
                                                + "法收回。")
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("撤销链接", (dialog, index) -> revoke(id))
                                    .show();
                            })
                        .setNegativeButton("关闭", null)
                        .show();
                });
            } catch (Exception error) {
                failure(error);
            }
        });
    }
    private void revoke(String id) {
        if (!id.matches("[a-f0-9]{32}"))
            return;
        busy = true;
        buttons();
        worker.execute(() -> {
            try {
                synchronized (CaptureAccountSession.LOCK) {
                    CaptureAccountSession.requireScope(this, scope);
                    CaptureSyncPreferences.Config c = CaptureAccountSession.config(this);
                    CaptureAccountHttp.request(
                        c.baseUrl, "DELETE", "/v1/exports/" + id, c.writeToken, null, null);
                }
                ui(() -> {
                    busy = false;
                    notice("该次导出的图片链接已撤销，原始记录保留。");
                    buttons();
                });
            } catch (Exception error) {
                failure(error);
            }
        });
    }
    private void failure(Exception error) {
        String code = error.getMessage();
        String message = "http_409".equals(code) || "export_changed".equals(code)
            ? "记录已变化，请返回首页刷新同步后重新选择。"
            : "http_401".equals(code) || "login_required".equals(code)
            ? "登录已失效，请重新登录后导出。"
            : "http_404".equals(code) ? "记录不存在或服务器尚未支持导出，请刷新后重试。"
            : "http_400".equals(code)
            ? "导出被拒绝：请检查 AI 权限、减少记录或图片数量，或撤销旧导出释放配额。"
            : "account_changed".equals(code)
            ? "账号已切换，请关闭此页重新选择。"
            : "导出未完成，请检查网络和保存位置；已生成的链接可在管理中撤销。";
        ui(() -> {
            busy = false;
            notice(message);
            buttons();
        });
    }
    private void notice(String value) {
        status.setText(value);
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
    private void ui(Runnable task) {
        runOnUiThread(() -> {
            if (!destroyed && !isFinishing())
                task.run();
        });
    }
    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("scope", scope);
        state.putStringArrayList("selected", new ArrayList<>(selected));
        state.putBoolean("picker", picker);
        if (pending != null)
            state.putString("pending", pending.toString());
        super.onSaveInstanceState(state);
    }
    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.shutdown();
        super.onDestroy();
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private TextView text(LinearLayout root, String value, int size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(getColor(R.color.ink));
        v.setPadding(0, dp(8), 0, dp(8));
        root.addView(v);
        return v;
    }
    private Button button(LinearLayout root, String value) {
        Button v = new Button(this);
        v.setText(value);
        v.setAllCaps(false);
        root.addView(v,
            new LinearLayout.LayoutParams(root.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1,
                -2, root.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0));
        return v;
    }
}
