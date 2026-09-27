package com.codex.mnote;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native primitives for the private-journal visual system. No business state lives here. */
final class JournalUi {
    private JournalUi() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    static LinearLayout column(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }
    static TextView text(Context context, String value, int size, int colorRes) {
        TextView result = new TextView(context);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(context.getColor(colorRes));
        result.setFontFeatureSettings("kern");
        result.setLineSpacing(dp(context, 3), 1.15f);
        return result;
    }
    static TextView label(Context context, String value) {
        TextView result = text(context, value, 13, R.color.copper);
        result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return result;
    }
    static void rule(LinearLayout root) {
        View rule = new View(root.getContext());
        rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        rule.setBackgroundColor(root.getContext().getColor(R.color.line));
        root.addView(rule, new LinearLayout.LayoutParams(-1, dp(root.getContext(), 1)));
    }
    static void primary(Button button) {
        button.setBackgroundTintList(null);
        button.setBackgroundResource(R.drawable.bg_button_primary);
        button.setTextColor(button.getContext().getColorStateList(R.color.primary_button_text));
        button.setMinHeight(dp(button.getContext(), 48));
    }
    static void quiet(Button button) {
        android.util.TypedValue value = new android.util.TypedValue();
        button.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        button.setBackgroundResource(value.resourceId);
        button.setTextColor(button.getContext().getColor(R.color.coral));
        button.setMinHeight(dp(button.getContext(), 48));
        button.setMinimumWidth(dp(button.getContext(), 48));
        button.setMinWidth(dp(button.getContext(), 48));
    }
    static LinearLayout header(Activity activity, LinearLayout root, String value, Runnable back) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button close = new Button(activity);
        close.setText("返回");
        quiet(close);
        close.setOnClickListener(v -> back.run());
        row.addView(close, new LinearLayout.LayoutParams(-2, -2));
        TextView title = text(activity, value, 18, R.color.ink);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        View balance = new View(activity);
        row.addView(balance, new LinearLayout.LayoutParams(dp(activity, 48), 1));
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        rule(root);
        return row;
    }
    static void section(LinearLayout root, String value) {
        TextView label = label(root.getContext(), value);
        label.setPadding(0, dp(root.getContext(), 24), 0, dp(root.getContext(), 12));
        root.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }
    static LinearLayout row(LinearLayout root, String title, String subtitle, Runnable action) {
        Context context = root.getContext();
        LinearLayout row = column(context);
        row.setPadding(0, dp(context, 16), 0, dp(context, 16));
        row.setMinimumHeight(dp(context, 72));
        row.addView(text(context, title, 17, R.color.ink));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView detail = text(context, subtitle, 13, R.color.ink_muted);
            detail.setPadding(0, dp(context, 4), 0, 0);
            row.addView(detail);
        }
        if (action != null) {
            android.util.TypedValue value = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
            row.setBackgroundResource(value.resourceId);
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(v -> action.run());
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
        }
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        rule(root);
        return row;
    }
}
