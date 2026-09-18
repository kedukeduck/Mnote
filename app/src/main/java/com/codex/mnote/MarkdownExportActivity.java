package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
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
    private Button export, all, clear;
    private boolean inlineExport;
    private boolean writing;
    private String scope;
    private boolean busy, picker;
    private volatile boolean destroyed;
    private JSONArray pending;
    private RecordImageLoader images;
    private Set<String> requestedIds;
    @Override
    public void onCreate(Bundle state) {
        inlineExport = getIntent().getBooleanExtra("inline_export", false);
        setTheme(inlineExport ? R.style.Theme_CaptureTrigger : R.style.Theme_Mnote);
        super.onCreate(state);
        if (inlineExport && state != null && state.getBoolean("writing")) {
            Toast
                .makeText(this, "上次导出可能仍在处理中，请检查文件和分享管理后再重试。",
                    Toast.LENGTH_LONG)
                .show();
            finish();
            return;
        }
        scope = CaptureAccountSession.scope(this);
        images = new RecordImageLoader(this, scope);
        ArrayList<String> requested = getIntent().getStringArrayListExtra("record_ids");
        if (inlineExport && requested == null) {
            finish();
            return;
        }
        if (requested != null) {
            if (!scope.equals(getIntent().getStringExtra("selection_scope")) || requested.isEmpty()
                || requested.size() > 100) {
                Toast.makeText(this, "所选记录或账号已变化，请重新选择", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            requestedIds = new LinkedHashSet<>(requested);
            selected.addAll(requestedIds);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.cream));
        root.setFitsSystemWindows(true);
        setContentView(root);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(22), dp(8), dp(22), 0);
        root.addView(header);
        LinearLayout nav = new LinearLayout(this);
        header.addView(nav);
        Button back = button(nav, "返回");
        back.setOnClickListener(v -> finish());
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(48)));
        nav.addView(new View(this), 1, new LinearLayout.LayoutParams(0, 1, 1));
        back.setBackgroundResource(android.R.color.transparent);
        TextView title = text(header, "带走一些灵感", 30);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView intro = text(header, "把选中的想法与上下文，整理成一份 Markdown。", 14);
        intro.setTextColor(getColor(R.color.ink_muted));
        LinearLayout actions = new LinearLayout(this);
        header.addView(actions);
        all = button(actions, "全选可用");
        clear = button(actions, "清空");
        all.setBackgroundResource(android.R.color.transparent);
        clear.setBackgroundResource(android.R.color.transparent);
        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setPadding(dp(22), dp(8), dp(22), dp(12));
        list.setClipToPadding(false);
        list.setDivider(
            new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        list.setDividerHeight(dp(10));
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(dp(22), dp(10), dp(22), dp(14));
        dock.setBackgroundColor(getColor(R.color.card));
        dock.setElevation(dp(4));
        root.addView(dock);
        status = text(dock, "正在加载记录…", 13);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        export = button(dock, "导出 Markdown");
        export.setBackgroundResource(R.drawable.bg_button_primary);
        export.setTextColor(getColor(R.color.white));
        export.setMinHeight(dp(52));
        TextView privacy = text(dock, "仅导出已同步记录 · 截图链接经确认后分享，可撤销", 11);
        privacy.setTextColor(getColor(R.color.ink_muted));
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
        if (inlineExport) {
            root.setVisibility(View.INVISIBLE);
            Toast.makeText(this, "正在检查所选记录…", Toast.LENGTH_SHORT).show();
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
                    if (requestedIds != null && !requestedIds.contains(r.id))
                        continue;
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
                        public View getView(int position, View recycled, ViewGroup parent) {
                            ExportRow row = recycled instanceof ExportRow ? (ExportRow) recycled
                                                                          : new ExportRow();
                            row.bind(rows.get(position));
                            return row;
                        }
                        @Override
                        public boolean isEnabled(int position) {
                            return eligible(rows.get(position));
                        }
                        @Override
                        public boolean areAllItemsEnabled() {
                            return false;
                        }
                    });
                    busy = writing;
                    checks();
                    if (inlineExport && !picker && !writing) {
                        if (!CaptureAccountSession.hasAccount(this) || requestedIds == null
                            || !selected.equals(requestedIds)) {
                            notice("所选记录尚未同步、禁止导出或已变化，请先刷新同步后重新选择。");
                            finish();
                        } else
                            confirm();
                    }
                });
            } catch (Exception error) {
                failure(error);
            }
        });
    }
    private void checks() {
        for (int i = 0; i < rows.size(); i++)
            list.setItemChecked(i, selected.contains(rows.get(i).id));
        if (list.getAdapter() != null)
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        status.setText("已选 " + selected.size() + " 条 · 当前共 " + rows.size() + " 条"
            + (CaptureAccountSession.hasAccount(this) ? "" : " · 请先登录并同步"));
        export.setText(
            selected.isEmpty() ? "选择要导出的记录" : "导出 " + selected.size() + " 条记录");
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
    }
    private void confirm() {
        if (busy || picker || selected.isEmpty())
            return;
        new AlertDialog.Builder(this)
            .setTitle("导出 " + selected.size() + " 条记录？")
            .setMessage("将导出想法、摘录、原文及完整截图／圈选图链接。仅这些记录的图片会获得独立分"
                + "享链接，任何持有链接的人均可访问。原笔记权限不变。\n\n请确认截图可分享。"
                + "链接可在设置 → 分享管理中撤销，但已下载的副本无法收回。")
            .setOnCancelListener(d -> {
                if (inlineExport)
                    finish();
            })
            .setNegativeButton("取消",
                (d, w) -> {
                    if (inlineExport)
                        finish();
                })
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
                        if (inlineExport)
                            finish();
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
            if (inlineExport)
                finish();
            return;
        }
        Uri target = data.getData();
        JSONArray selection = pending;
        pending = null;
        busy = true;
        writing = true;
        buttons();
        status.setText("正在生成 Markdown 和图片分享链接…");
        if (inlineExport)
            Toast.makeText(this, "正在导出，请稍候…", Toast.LENGTH_LONG).show();
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
                    notice("Markdown 已保存，可在设置 → 分享管理回看与撤销。");
                    buttons();
                    if (inlineExport)
                        finish();
                });
            } catch (Exception error) {
                failure(error);
            } finally {
                if (!saved && created != null && config != null && created.matches("[a-f0-9]{32}"))
                    try {
                        CaptureAccountHttp.request(config.baseUrl, "DELETE",
                            "/v1/exports/" + created, config.writeToken, null, null);
                    } catch (Exception ignored) {
                        runOnUiThread(()
                                          -> Toast
                                              .makeText(getApplicationContext(),
                                                  "保存失败，自动撤销未完成，请到“设置 → "
                                                  + "分享管理”撤销此次分享。",
                                                  Toast.LENGTH_LONG)
                                              .show());
                    }
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
            if (inlineExport)
                finish();
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
        state.putBoolean("writing", writing);
        state.putString("scope", scope);
        state.putStringArrayList("selected", new ArrayList<>(selected));
        state.putBoolean("picker", picker);
        if (pending != null)
            state.putString("pending", pending.toString());
        super.onSaveInstanceState(state);
    }
    @Override
    public void onBackPressed() {
        if (inlineExport && writing && busy) {
            Toast.makeText(this, "正在导出，请稍候…", Toast.LENGTH_SHORT).show();
        } else
            super.onBackPressed();
    }
    @Override
    protected void onDestroy() {
        destroyed = true;
        if (images != null)
            images.close();
        worker.shutdown();
        super.onDestroy();
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private final class ExportRow extends LinearLayout implements Checkable {
        private final TextView body, meta;
        private final CheckBox check;
        private final LinearLayout pictures;
        private final Button preview;
        private String recordId;
        private String imageKey;
        ExportRow() {
            super(MarkdownExportActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(14), dp(12), dp(14));
            LinearLayout copy = new LinearLayout(MarkdownExportActivity.this);
            copy.setOrientation(VERTICAL);
            LinearLayout heading = new LinearLayout(MarkdownExportActivity.this);
            heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
            addView(heading);
            heading.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            meta = text(copy, "", 12);
            meta.setTextColor(getColor(R.color.ink_muted));
            meta.setPadding(0, 0, 0, dp(7));
            body = text(copy, "", 16);
            body.setPadding(0, 0, dp(8), 0);
            body.setMaxLines(3);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.setLineSpacing(dp(3), 1);
            check = new CheckBox(MarkdownExportActivity.this);
            check.setClickable(false);
            check.setFocusable(false);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            heading.addView(check);
            pictures = new LinearLayout(MarkdownExportActivity.this);
            addView(pictures);
            preview = new Button(MarkdownExportActivity.this);
            preview.setText("查看将导出的图片");
            preview.setTextSize(13);
            preview.setBackgroundResource(android.R.color.transparent);
            preview.setFocusable(false);
            addView(preview);
            setMinimumHeight(dp(112));
        }
        void bind(CaptureStore.CaptureRecord r) {
            recordId = r.id;
            StringBuilder key = new StringBuilder(r.id);
            for (String role : new String[] {"annotated", "original", "context"}) {
                java.io.File file = RecordImageLoader.file(r, role);
                if (file != null)
                    key.append(':')
                        .append(file.getAbsolutePath())
                        .append(':')
                        .append(file.length())
                        .append(':')
                        .append(file.lastModified());
            }
            if (!key.toString().equals(imageKey)) {
                imageKey = key.toString();
                pictures.removeAllViews();
                int count = 0;
                for (String role : new String[] {"annotated", "original", "context"}) {
                    java.io.File file = RecordImageLoader.file(r, role);
                    if (file == null)
                        continue;
                    count++;
                    LinearLayout tile = new LinearLayout(MarkdownExportActivity.this);
                    tile.setOrientation(VERTICAL);
                    LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -2, 1);
                    tileParams.setMargins(dp(3), dp(12), dp(3), 0);
                    pictures.addView(tile, tileParams);
                    ImageView image = new ImageView(MarkdownExportActivity.this);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    image.setBackgroundColor(getColor(R.color.cream));
                    image.setContentDescription(RecordImageLoader.label(role));
                    tile.addView(image, new LinearLayout.LayoutParams(-1, dp(128)));
                    TextView label = text(tile, RecordImageLoader.label(role), 11);
                    label.setTextColor(getColor(R.color.ink_muted));
                    image.setTag(r.id + role);
                    image.setContentDescription("查看" + RecordImageLoader.label(role) + "大图");
                    image.setFocusable(false);
                    image.setOnClickListener(v -> images.preview(r, role));
                    images.load(file, false, bitmap -> {
                        if (!r.id.equals(recordId) || tile.getParent() != pictures)
                            return;
                        image.setImageBitmap(bitmap);
                        if (bitmap == null)
                            label.setText(RecordImageLoader.label(role) + " · 需同步重试");
                    });
                }
                pictures.setVisibility(count > 0 ? VISIBLE : GONE);
                preview.setVisibility(count > 0 ? VISIBLE : GONE);
            }
            preview.setOnClickListener(v -> images.preview(r));
            String content = !r.comment.isEmpty() ? r.comment
                : !r.sourceText.isEmpty()         ? r.sourceText
                                                  : CaptureRecordEdits.original(r);
            body.setText(content.isEmpty() ? "保存的一刻 · 页面截图" : content);
            String tags = CaptureTags.display(r.tags);
            meta.setText(android.text.format.DateFormat.format("M月d日  HH:mm", r.createdAt)
                + (tags.isEmpty() ? "" : "  ·  " + tags)
                + (!eligible(r) ? "  ·  需同步或检查权限" : ""));
            setAlpha(eligible(r) ? 1f : 0.5f);
            setChecked(selected.contains(r.id));
        }
        @Override
        public void setChecked(boolean value) {
            check.setChecked(value);
            check.jumpDrawablesToCurrentState();
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(value ? 0xffeef1fe : getColor(R.color.card));
            bg.setStroke(dp(1), value ? getColor(R.color.coral) : getColor(R.color.line));
            setBackground(bg);
        }
        @Override
        public boolean isChecked() {
            return check.isChecked();
        }
        @Override
        public void toggle() {
            setChecked(!isChecked());
        }
        @Override
        public void onInitializeAccessibilityNodeInfo(
            android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCheckable(true);
            info.setChecked(isChecked());
        }
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
