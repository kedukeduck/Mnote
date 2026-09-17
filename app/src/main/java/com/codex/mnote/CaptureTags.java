package com.codex.mnote;

import android.content.Context;
import android.text.*;
import android.view.View;
import android.widget.*;
import android.widget.EditText;
import java.util.*;
import org.json.JSONArray;

/** Tags are record metadata, not part of the captured source text. */
final class CaptureTags {
    static JSONArray parse(String input) {
        if (input.length() > 4096)
            throw new IllegalArgumentException(
                "标签输入过长；每条记录最多 20 个标签，每个最多 32 字。");
        JSONArray values = new JSONArray();
        for (String part : input.split("[,，;；\\n\\r]")) values.put(part);
        return normalize(values);
    }
    static JSONArray normalize(JSONArray input) {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (input == null)
            return result;
        for (int i = 0; i < input.length(); i++) {
            Object value = input.opt(i);
            if (!(value instanceof String))
                throw new IllegalArgumentException("标签必须是文字。");
            String tag = ((String) value).trim();
            if (tag.startsWith("#"))
                tag = tag.substring(1).trim();
            if (tag.isEmpty())
                continue;
            if (tag.length() > 32
                || java.util.regex.Pattern.compile("[,，;；\\p{Cntrl}]").matcher(tag).find())
                throw new IllegalArgumentException(
                    "每个标签最多 32 字，不能包含逗号、分号或控制字符。");
            if (seen.add(tag.toLowerCase(Locale.ROOT)))
                result.put(tag);
            if (result.length() > 20)
                throw new IllegalArgumentException("每条记录最多添加 20 个标签。");
        }
        return result;
    }
    static String input(JSONArray tags) {
        List<String> values = new ArrayList<>();
        if (tags != null)
            for (int i = 0; i < tags.length(); i++) values.add(tags.optString(i));
        return String.join("，", values);
    }
    static String display(JSONArray tags) {
        return tags.length() == 0 ? "" : "#" + input(tags).replace("，", "   #");
    }
    static boolean matches(JSONArray tags, String filter) {
        if (filter == null)
            return true;
        if (filter.isEmpty())
            return tags.length() == 0;
        for (int i = 0; i < tags.length(); i++)
            if (filter.equalsIgnoreCase(tags.optString(i)))
                return true;
        return false;
    }
    static final class Field {
        final EditText input;
        private final String scope;
        private final LinearLayout picker, options;
        private final EditText search;
        private final Button choose, more;
        private final TextView hint;
        private List<String> known = Collections.emptyList();
        private int limit = 20, generation;
        private boolean loaded;
        Field(View root) {
            input = root.findViewById(R.id.capture_tags_input);
            scope = CaptureAccountSession.scope(input.getContext());
            picker = root.findViewById(R.id.capture_tags_picker);
            options = root.findViewById(R.id.capture_tags_options);
            search = root.findViewById(R.id.capture_tags_search);
            choose = root.findViewById(R.id.capture_tags_choose);
            more = root.findViewById(R.id.capture_tags_more);
            hint = root.findViewById(R.id.capture_tags_hint);
            if (picker == null)
                return;
            choose.setOnClickListener(v -> {
                boolean open = picker.getVisibility() != View.VISIBLE;
                picker.setVisibility(open ? View.VISIBLE : View.GONE);
                choose.setText(open ? "收起标签" : "选择已有");
                if (open)
                    load();
            });
            more.setOnClickListener(v -> {
                limit += 20;
                render();
            });
            search.addTextChangedListener(watcher(() -> {
                limit = 20;
                render();
            }));
            input.addTextChangedListener(watcher(this::render));
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) {}
                public void onViewDetachedFromWindow(View v) {
                    generation++;
                }
            });
        }
        private static TextWatcher watcher(Runnable action) {
            return new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                public void afterTextChanged(Editable text) {
                    action.run();
                }
            };
        }
        private void load() {
            int ticket = ++generation;
            loaded = false;
            options.removeAllViews();
            more.setVisibility(View.GONE);
            hint.setText("正在读取已有标签…");
            Context context = input.getContext().getApplicationContext();
            TAG_WORKER.execute(() -> {
                try {
                    List<String> values = existing(context, scope);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (ticket != generation
                            || !scope.equals(CaptureAccountSession.scope(context)))
                            return;
                        known = values;
                        loaded = true;
                        render();
                    });
                } catch (Exception error) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (ticket == generation)
                            hint.setText("读取失败，请收起后重试；仍可手动输入标签。");
                    });
                }
            });
        }
        private void render() {
            if (picker == null || !loaded || picker.getVisibility() != View.VISIBLE)
                return;
            options.removeAllViews();
            if (!scope.equals(CaptureAccountSession.scope(input.getContext()))) {
                hint.setText("账号已变化，请重新打开记录。");
                more.setVisibility(View.GONE);
                return;
            }
            JSONArray selected;
            try {
                selected = parse(input.getText().toString());
            } catch (IllegalArgumentException error) {
                hint.setText(error.getMessage());
                more.setVisibility(View.GONE);
                return;
            }
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            int matches = 0;
            for (String tag : known) {
                if (!tag.toLowerCase(Locale.ROOT).contains(query))
                    continue;
                if (++matches > limit)
                    continue;
                CheckBox option = new CheckBox(input.getContext());
                option.setText(tag);
                option.setTextSize(14);
                option.setMinHeight(
                    Math.round(48 * input.getResources().getDisplayMetrics().density));
                option.setChecked(matches(selected, tag));
                int gap = Math.round(6 * input.getResources().getDisplayMetrics().density);
                android.graphics.drawable.GradientDrawable background =
                    new android.graphics.drawable.GradientDrawable();
                background.setCornerRadius(gap * 2);
                background.setColor(input.getContext().getColor(
                    option.isChecked() ? R.color.coral_soft : R.color.cream));
                option.setBackground(background);
                option.setTextColor(input.getContext().getColor(
                    option.isChecked() ? R.color.coral_dark : R.color.ink));
                option.setPadding(gap, 0, gap * 2, 0);
                LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
                row.bottomMargin = gap;
                options.addView(option, row);
                option.setOnClickListener(v -> {
                    if (!scope.equals(CaptureAccountSession.scope(input.getContext()))) {
                        render();
                        return;
                    }
                    try {
                        JSONArray values = parse(input.getText().toString()),
                                  next = new JSONArray();
                        boolean had = matches(values, tag);
                        for (int i = 0; i < values.length(); i++)
                            if (!tag.equalsIgnoreCase(values.optString(i)))
                                next.put(values.optString(i));
                        if (!had)
                            next.put(tag);
                        input.setText(input(normalize(next)));
                        input.setSelection(input.length());
                        input.setError(null);
                    } catch (IllegalArgumentException error) {
                        input.setError(error.getMessage());
                        render();
                    }
                });
            }
            hint.setText(known.isEmpty() ? "还没有已有标签，可以在上方输入新标签。"
                    : matches == 0       ? "没有匹配的标签；可在上方新建。"
                                         : "可多选 · 已选 " + selected.length() + " / 20");
            more.setVisibility(matches > limit ? View.VISIBLE : View.GONE);
        }
        JSONArray validated() {
            try {
                return parse(input.getText().toString());
            } catch (IllegalArgumentException error) {
                input.setError(error.getMessage());
                input.requestFocus();
                if (!input.isShown())
                    android.widget.Toast
                        .makeText(input.getContext(), error.getMessage(),
                            android.widget.Toast.LENGTH_LONG)
                        .show();
                return null;
            }
        }
    }
    static final java.util.concurrent.ExecutorService TAG_WORKER =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mnote-tags");
            thread.setDaemon(true);
            return thread;
        });
    static List<String> existing(Context context, String scope) throws Exception {
        synchronized (CaptureAccountSession.LOCK) {
            CaptureAccountSession.requireScope(context, scope);
            Map<String, String> names = new TreeMap<>();
            for (CaptureStore.CaptureRecord record :
                CaptureRemoteCache.merged(context, CaptureStore.list(context, Integer.MAX_VALUE)))
                for (int i = 0; i < record.tags.length(); i++) {
                    String tag = record.tags.optString(i);
                    names.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
                }
            return new ArrayList<>(names.values());
        }
    }
}
