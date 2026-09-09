package com.codex.mnote;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared, scrollable evidence -> context -> thought layout for Activity composers. */
final class CaptureReadingLayout {
    static void attach(LinearLayout root) {
        Context context = root.getContext();
        View evidence = root.findViewById(R.id.capture_evidence_container);
        View status = root.findViewById(R.id.capture_editor_status);
        View tools = root.findViewById(R.id.capture_tool_row);
        View composer = root.findViewById(R.id.capture_composer);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context) {
            @Override protected void onMeasure(int width, int height) {
                evidence.getLayoutParams().height = dp(context, 320);
                super.onMeasure(width, height);
            }
        };
        scroll.setId(R.id.capture_reading_scroll);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        for (View child : new View[]{evidence,tools,composer,status}) {
            ((ViewGroup)child.getParent()).removeView(child);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2);
            if (child == evidence) {
                params.height=dp(context,320); params.setMargins(dp(context,20),dp(context,8),dp(context,20),dp(context,12));
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
