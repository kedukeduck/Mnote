package com.codex.mnote;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** Pull down at the start of the list. The refresh button is the accessible alternative. */
public final class JournalRefreshScroll extends ScrollView {
    private float downX, downY;
    private boolean atTop;
    private Runnable refresh;
    public JournalRefreshScroll(Context context, AttributeSet attrs) { super(context, attrs); }
    void setRefreshAction(Runnable action) { refresh = action; }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); atTop = !canScrollVertically(-1);
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP && atTop && refresh != null
                && event.getY() - downY > JournalUi.dp(getContext(), 96)
                && Math.abs(event.getX() - downX) < (event.getY() - downY) / 2) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel); cancel.recycle();
            refresh.run(); return true;
        }
        return super.dispatchTouchEvent(event);
    }
}
