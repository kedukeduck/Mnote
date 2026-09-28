package com.codex.mnote;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.List;
import org.json.JSONArray;

/** Compact, non-interactive metadata chips that wrap with the user's font size. */
public final class RecordTagsView extends ViewGroup {
    public RecordTagsView(Context context, AttributeSet attributes) { super(context, attributes); }

    void setTags(JSONArray tags) {
        removeAllViews();
        setContentDescription("自定义标签：" + CaptureTags.display(tags));
        for (int i = 0; i < tags.length(); i++) {
            addChip("# " + tags.optString(i), false);
        }
        setVisibility(tags.length() == 0 ? GONE : VISIBLE);
    }

    void setCategories(List<String> categories) {
        removeAllViews();
        setContentDescription("系统分类：" + android.text.TextUtils.join("、", categories));
        for (String category : categories) addChip(category, true);
        setVisibility(categories.isEmpty() ? GONE : VISIBLE);
    }

    private void addChip(String text, boolean system) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setTextColor(getContext().getColor(system ? R.color.white : R.color.ink_muted));
        chip.setTextSize(system ? 12 : 11);
        chip.setIncludeFontPadding(false);
        chip.setPadding(dp(system ? 8 : 7), dp(system ? 3 : 4), dp(system ? 8 : 7), dp(system ? 3 : 4));
        chip.setBackgroundResource(!system ? R.drawable.bg_record_tag
                : "摘录".equals(text) ? R.drawable.bg_record_category_excerpt : R.drawable.bg_record_category);
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(chip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? getResources().getDisplayMetrics().widthPixels : MeasureSpec.getSize(widthSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int x = 0, y = 0, rowHeight = 0, usedWidth = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + child.getMeasuredWidth() > available) {
                y += rowHeight + dp(6); x = 0; rowHeight = 0;
            }
            usedWidth = Math.max(usedWidth, x + child.getMeasuredWidth());
            x += child.getMeasuredWidth() + dp(6);
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        setMeasuredDimension(resolveSize(usedWidth + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(y + rowHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = right - left - getPaddingLeft() - getPaddingRight();
        int x = 0, y = getPaddingTop(), rowHeight = 0;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
            if (x > 0 && x + width > available) { y += rowHeight + dp(6); x = 0; rowHeight = 0; }
            int childLeft = getPaddingLeft() + (rtl ? available - x - width : x);
            child.layout(childLeft, y, childLeft + width, y + height);
            x += width + dp(6); rowHeight = Math.max(rowHeight, height);
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
