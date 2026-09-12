package com.codex.mnote;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;

/** A bounded, independently scrolling editor; never measures a whole article at one-pixel width. */
final class CaptureLongText {
    static void enableScrolling(EditText field) {
        field.setVerticalScrollBarEnabled(true);
        field.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        final float[] lastY = {0};
        field.setOnTouchListener((view, event) -> {
            ViewParent parent = view.getParent();
            if (parent == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lastY[0] = event.getY();
                parent.requestDisallowInterceptTouchEvent(view.canScrollVertically(-1) || view.canScrollVertically(1));
            } else if (action == MotionEvent.ACTION_MOVE) {
                float delta = lastY[0] - event.getY();
                if (Math.abs(delta) > 1) parent.requestDisallowInterceptTouchEvent(view.canScrollVertically(delta > 0 ? 1 : -1));
                lastY[0] = event.getY();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                parent.requestDisallowInterceptTouchEvent(false);
            }
            return false; // Preserve cursor placement, selection and native text scrolling.
        });
    }
    static Button attach(Activity activity, EditText source, String title) {
        enableScrolling(source);
        Button action = new Button(activity);
        action.setTextSize(13);
        action.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        action.setBackgroundResource(android.R.color.transparent);
        action.setTextColor(activity.getColor(R.color.coral));
        action.setContentDescription("展开编辑" + title);
        LinearLayout parent = (LinearLayout)source.getParent();
        parent.addView(action, parent.indexOfChild(source) + 1, new LinearLayout.LayoutParams(-1, -2));
        Runnable update = () -> action.setText("已保留 " + source.length() + " 字 · 展开编辑  ↗");
        source.addTextChangedListener(watch(update)); update.run();
        action.setOnClickListener(v -> { if(source.isEnabled()) show(activity, source, title); });
        return action;
    }
    static void show(Activity activity, EditText source, String title) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Dialog dialog = new Dialog(activity);
        LinearLayout page = new LinearLayout(activity); page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true); page.setBackgroundColor(activity.getColor(R.color.cream));
        int space = CaptureReadingLayout.dp(activity, 20); page.setPadding(space, space, space, space);
        TextView heading = new TextView(activity); heading.setText(title); heading.setTextSize(20);
        page.addView(heading);
        TextView help = new TextView(activity); help.setTextSize(12);
        help.setText("这里显示已保留的全部文字，可上下滑动、编辑。修改自动回填，返回记录页后点击保存。");
        help.setPadding(0, space / 2, 0, space / 2); page.addView(help);
        EditText expanded = new EditText(activity); expanded.setId(R.id.capture_expanded_text);
        expanded.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        expanded.setGravity(Gravity.TOP | Gravity.START); expanded.setTextSize(16);
        expanded.setBackgroundResource(R.drawable.bg_input); expanded.setPadding(space,space,space,space);
        expanded.setSaveEnabled(false); expanded.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        expanded.setText(source.getText().toString()); enableScrolling(expanded);
        page.addView(expanded, new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer = new LinearLayout(activity);
        Button end = new Button(activity); end.setText("到文末");
        end.setOnClickListener(v -> { expanded.requestFocus(); expanded.setSelection(expanded.length());
            expanded.post(() -> expanded.bringPointIntoView(expanded.length())); });
        footer.addView(end,new LinearLayout.LayoutParams(0,-2,1));
        Button done = new Button(activity); done.setText("完成"); done.setOnClickListener(v -> dialog.dismiss());
        footer.addView(done,new LinearLayout.LayoutParams(0,-2,1)); page.addView(footer);
        TextWatcher edits = watch(() -> source.setText(expanded.getText().toString()));
        expanded.addTextChangedListener(edits);
        View.OnAttachStateChangeListener lifecycle = new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { dialog.dismiss(); }
        };
        source.addOnAttachStateChangeListener(lifecycle);
        dialog.setOnDismissListener(d -> { expanded.removeTextChangedListener(edits); source.removeOnAttachStateChangeListener(lifecycle); });
        dialog.setContentView(page);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(activity.getColor(R.color.cream)));
        dialog.getWindow().setLayout(-1,-1);
    }
    private static TextWatcher watch(Runnable changed) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { changed.run(); }
            public void afterTextChanged(Editable text) { }
        };
    }
}
