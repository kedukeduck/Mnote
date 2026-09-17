package com.codex.mnote;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Account-only gallery. Visible cards load one bounded cover, never create new public shares. */
public final class ShareGalleryActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<JSONObject> rows = new ArrayList<>();
    private final Map<String, String> captions = new HashMap<>(), errors = new HashMap<>();
    private final Map<String, Integer> positions = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final android.util.LruCache<String, Bitmap> cache =
        new android.util.LruCache<>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getAllocationByteCount();
            }
        };
    private String scope;
    private TextView status;
    private Button refresh;
    private ListView list;
    private BaseAdapter adapter;
    private volatile boolean destroyed;
    private volatile int generation;
    private boolean refreshing, revoking;
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scope = CaptureAccountSession.scope(this);
        String requested = getIntent().getStringExtra("scope");
        if (!CaptureAccountSession.hasAccount(this)
            || (requested != null && !scope.equals(requested))) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.cream));
        root.setFitsSystemWindows(true);
        setContentView(root);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(22), dp(8), dp(22), dp(12));
        root.addView(heading);
        LinearLayout nav = new LinearLayout(this);
        heading.addView(nav);
        button(nav, "返回", this::finish);
        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        refresh = button(nav, "刷新", this::reload);
        text(heading, "分享管理", 28, R.color.ink);
        text(heading, "留住分享时的画面", 14, R.color.ink_muted);
        status = text(heading, "正在读取历史分享…", 12, R.color.ink_muted);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        list = new ListView(this);
        list.setPadding(dp(22), 0, dp(22), dp(18));
        list.setClipToPadding(false);
        list.setDivider(
            new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        list.setDividerHeight(dp(12));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        adapter = new BaseAdapter() {
            public int getCount() {
                return rows.size();
            }
            public Object getItem(int position) {
                return rows.get(position);
            }
            public long getItemId(int position) {
                return position;
            }
            public View getView(int position, View reuse, ViewGroup parent) {
                Card card = reuse instanceof Card ? (Card) reuse : new Card();
                card.bind(rows.get(position));
                return card;
            }
        };
        list.setAdapter(adapter);
        reload();
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
    private void reload() {
        if (refreshing || revoking || !active())
            return;
        refreshing = true;
        refresh.setEnabled(false);
        int ticket = ++generation;
        loading.clear();
        errors.clear();
        captions.clear();
        positions.clear();
        cache.evictAll();
        rows.clear();
        adapter.notifyDataSetChanged();
        status.setText("正在读取历史分享…");
        worker.execute(() -> {
            try {
                var c = config();
                JSONArray exports =
                    CaptureAccountHttp
                        .request(c.baseUrl, "GET", "/v1/exports", c.writeToken, null, null)
                        .getJSONArray("exports");
                if (exports.length() > 50)
                    throw new java.io.IOException("invalid_history");
                List<JSONObject> values = new ArrayList<>();
                for (int i = 0; i < exports.length(); i++) {
                    JSONObject item = exports.getJSONObject(i);
                    if (!item.getString("id").matches("[a-f0-9]{32}"))
                        throw new java.io.IOException("invalid_history");
                    values.add(item);
                }
                ui(() -> {
                    if (ticket != generation)
                        return;
                    rows.addAll(values);
                    refreshing = false;
                    refresh.setEnabled(true);
                    status.setText(rows.isEmpty()
                            ? "还没有有效的分享。导出 Markdown 后会显示在这里。"
                            : "共 " + rows.size() + " 次分享 · 点击图片看大图");
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception error) {
                ui(() -> {
                    if (ticket != generation)
                        return;
                    refreshing = false;
                    refresh.setEnabled(true);
                    status.setText("读取失败，请检查网络或重新登录后刷新。");
                });
            }
        });
    }
    private void cover(JSONObject share) {
        String id = share.optString("id");
        if (loading.contains(id) || errors.containsKey(id) || cache.get(id) != null)
            return;
        loading.add(id);
        int ticket = generation;
        worker.execute(() -> {
            try {
                if (ticket != generation || destroyed)
                    return;
                var c = config();
                JSONArray images =
                    CaptureAccountHttp
                        .request(c.baseUrl, "GET", "/v1/exports/" + id, c.writeToken, null, null)
                        .getJSONArray("images");
                if (images.length() == 0 || images.length() > 300)
                    throw new java.io.IOException("no_images");
                int chosen = 0;
                for (int i = 0; i < images.length(); i++)
                    if ("annotated".equals(images.getJSONObject(i).optString("role"))) {
                        chosen = i;
                        break;
                    }
                JSONObject asset = images.getJSONObject(chosen);
                if (!asset.getString("name").matches(
                        "[0-9]+-(context|original|annotated)\\.(png|jpg|webp)"))
                    throw new java.io.IOException("invalid_export");
                byte[] bytes = CaptureAccountHttp.image(c.baseUrl,
                    "/v1/exports/" + id + "/assets/" + asset.getString("name"), c.writeToken);
                if (bytes.length != asset.getInt("size"))
                    throw new java.io.IOException("invalid_image");
                Bitmap bitmap = ShareHistoryActivity.decode(bytes, 640);
                int position = chosen;
                String caption = "记录 " + asset.getInt("record_index") + " · "
                    + RecordImageLoader.label(asset.getString("role"));
                ui(() -> {
                    if (ticket != generation)
                        return;
                    loading.remove(id);
                    cache.put(id, bitmap);
                    positions.put(id, position);
                    captions.put(id, caption);
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception | OutOfMemoryError error) {
                ui(() -> {
                    if (ticket != generation)
                        return;
                    loading.remove(id);
                    errors.put(id, "预览暂不可用 · 点击重试");
                    adapter.notifyDataSetChanged();
                });
            }
        });
    }
    private void open(String id) {
        if (active() && !revoking)
            startActivity(new Intent(this, ShareHistoryActivity.class)
                    .putExtra("scope", scope)
                    .putExtra("export_id", id)
                    .putExtra("image_index", positions.getOrDefault(id, 0)));
    }
    private void revoke(JSONObject share) {
        if (revoking || refreshing || !active())
            return;
        new AlertDialog.Builder(this)
            .setTitle("撤销这次分享？")
            .setMessage("这次分享的图片链接将失效，历史预览也会移除。原始记录不删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("撤销分享",
                (dialog, which) -> {
                    if (!active())
                        return;
                    revoking = true;
                    refresh.setEnabled(false);
                    ++generation;
                    loading.clear();
                    String id = share.optString("id");
                    worker.execute(() -> {
                        try {
                            var c = config();
                            CaptureAccountHttp.request(
                                c.baseUrl, "DELETE", "/v1/exports/" + id, c.writeToken, null, null);
                            ui(() -> {
                                revoking = false;
                                Toast.makeText(this, "分享已撤销，原始记录保留", Toast.LENGTH_LONG)
                                    .show();
                                reload();
                            });
                        } catch (Exception error) {
                            ui(() -> {
                                revoking = false;
                                refresh.setEnabled(true);
                                status.setText("撤销失败，请重试。");
                                adapter.notifyDataSetChanged();
                            });
                        }
                    });
                })
            .show();
    }
    private final class Card extends LinearLayout {
        final TextView date, meta, caption;
        final ImageView photo;
        final Button all, remove;
        Card() {
            super(ShareGalleryActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(12), dp(16), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(getColor(R.color.card));
            bg.setCornerRadius(dp(20));
            bg.setStroke(dp(1), getColor(R.color.line));
            setBackground(bg);
            date = text(this, "", 17, R.color.ink);
            meta = text(this, "", 12, R.color.ink_muted);
            photo = new ImageView(ShareGalleryActivity.this);
            photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            photo.setBackgroundColor(getColor(R.color.cream));
            addView(photo, new LinearLayout.LayoutParams(-1, dp(196)));
            caption = text(this, "", 12, R.color.ink_muted);
            LinearLayout actions = new LinearLayout(ShareGalleryActivity.this);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            addView(actions);
            all = button(actions, "", () -> {});
            all.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
            all.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            remove = button(actions, "撤销", () -> {});
            remove.setTextColor(getColor(R.color.danger));
            remove.setTextSize(13);
        }
        void bind(JSONObject item) {
            String id = item.optString("id");
            setTag(id);
            int count = item.optInt("image_count");
            String stamp = item.optString("created");
            try {
                stamp = java.time.Instant.parse(stamp)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("M月d日  HH:mm"));
            } catch (Exception ignored) {
            }
            date.setText(stamp);
            meta.setText(getString(R.string.share_gallery_counts, item.optInt("record_count"), count));
            photo.setVisibility(count > 0 ? VISIBLE : GONE);
            photo.setImageBitmap(cache.get(id));
            photo.setContentDescription("历史分享截图预览");
            caption.setText(count == 0       ? "这次分享只有文字，没有图片。"
                    : errors.containsKey(id) ? errors.get(id)
                                             : captions.getOrDefault(id, "正在读取当时的图片…"));
            all.setText(count == 0 ? "分享详情" : "查看全部 " + count + " 张图片  ›");
            all.setOnClickListener(v -> open(id));
            remove.setOnClickListener(v -> revoke(item));
            photo.setOnClickListener(v -> open(id));
            caption.setOnClickListener(v -> {
                if (errors.remove(id) != null)
                    cover(item);
                else
                    open(id);
            });
            if (count > 0)
                cover(item);
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        if (scope != null && !scope.equals(CaptureAccountSession.scope(this)))
            finish();
    }
    @Override
    protected void onRestart() {
        super.onRestart();
        if (active())
            reload();
    }
    @Override
    protected void onDestroy() {
        destroyed = true;
        ++generation;
        worker.shutdownNow();
        cache.evictAll();
        super.onDestroy();
    }
    private TextView text(LinearLayout root, String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        view.setPadding(0, dp(6), 0, dp(8));
        root.addView(view);
        return view;
    }
    private Button button(LinearLayout root, String value, Runnable action) {
        Button view = new Button(this);
        view.setText(value);
        view.setBackgroundResource(android.R.color.transparent);
        view.setOnClickListener(v -> action.run());
        root.addView(view, new LinearLayout.LayoutParams(-2, dp(48)));
        return view;
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
