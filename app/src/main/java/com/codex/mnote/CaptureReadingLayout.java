package com.codex.mnote;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared, scrollable material -> thought layout with opt-in auxiliary controls. */
final class CaptureReadingLayout {
    static void bindOptions(View root) {
        View action = root.findViewById(R.id.capture_more_options);
        if (action == null) return;
        action.setOnClickListener(v -> showOptions(root,
                root.findViewById(R.id.capture_auxiliary_options).getVisibility() != View.VISIBLE));
        showOptions(root, false);
    }

    static void showOptions(View root, boolean expanded) {
        View options = root.findViewById(R.id.capture_auxiliary_options);
        if (options == null) return;
        options.setVisibility(expanded ? View.VISIBLE : View.GONE);
        TextView action = root.findViewById(R.id.capture_more_options);
        action.setText(expanded ? "收起更多选项" : "更多选项");
        action.setContentDescription(expanded ? "更多选项，已展开，点击收起" : "更多选项，已收起，点击展开");
        View summary = root.findViewById(R.id.capture_options_summary);
        if (summary != null) summary.setVisibility(expanded ? View.GONE : View.VISIBLE);
    }

    static void revealCursor(android.widget.EditText input) {
        android.text.Layout layout = input.getLayout();
        if (layout == null) return;
        int offset = Math.max(0, Math.min(input.length(), input.getSelectionEnd()));
        int line = layout.getLineForOffset(offset);
        int top = input.getTotalPaddingTop() + layout.getLineTop(line);
        int bottom = input.getTotalPaddingTop() + layout.getLineBottom(line);
        input.requestRectangleOnScreen(new android.graphics.Rect(0, top, input.getWidth(), bottom), true);
    }

    static void attach(LinearLayout root) {
        Context context = root.getContext();
        View evidence = root.findViewById(R.id.capture_evidence_container);
        View status = root.findViewById(R.id.capture_editor_status);
        View tools = root.findViewById(R.id.capture_tool_row);
        View composer = root.findViewById(R.id.capture_composer);
        View saveAndChat = root.findViewById(R.id.ai_save_chat);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context) {
            @Override protected void onMeasure(int width, int height) {
                int available = View.MeasureSpec.getSize(height);
                evidence.getLayoutParams().height = Math.min(dp(context, 220), Math.max(dp(context, 100), available / 3));
                android.widget.EditText thought = root.findViewById(R.id.capture_comment_input);
                if (thought != null) thought.setMinHeight(Math.min(dp(context, 220), Math.max(dp(context, 100), available / 3)));
                super.onMeasure(width, height);
            }
        };
        scroll.setId(R.id.capture_reading_scroll);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        // Keep the optional chat action in the scrollable reading area. A second fixed
        // toolbar steals the writing space when the keyboard or large text is active.
        for (View child : new View[]{evidence,tools,composer,saveAndChat,status}) {
            if(child==null)continue;
            ((ViewGroup)child.getParent()).removeView(child);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2);
            if (child == evidence) {
                params.height=dp(context,220); params.setMargins(dp(context,20),dp(context,8),dp(context,20),dp(context,12));
            }
            body.addView(child,params);
        }
        // Routine instructions should not displace the captured material.
        ((TextView)status).setMaxLines(3);
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }

    static int dp(Context context,int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
