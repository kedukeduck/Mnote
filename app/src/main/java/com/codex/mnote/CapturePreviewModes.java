package com.codex.mnote;

import android.content.Context;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import java.util.function.Consumer;

/** Preview switching is deliberately independent of the retain-context consent. */
final class CapturePreviewModes {
    static RadioGroup create(Context context, boolean text, Consumer<Boolean> changed) {
        RadioGroup group = new RadioGroup(context);
        group.setId(text ? R.id.capture_text_preview_modes : R.id.capture_preview_modes);
        group.setOrientation(RadioGroup.HORIZONTAL);
        group.setBackgroundResource(R.drawable.bg_card);
        int padding=CaptureReadingLayout.dp(context,3);
        group.setPadding(padding,padding,padding,padding);
        String[] titles = text ? new String[]{"摘录文字","原文"} : new String[]{"圈选区域","完整截图"};
        int[] ids = text ? new int[]{R.id.capture_text_preview_quote,R.id.capture_text_preview_original}
                : new int[]{R.id.capture_preview_crop,R.id.capture_preview_full};
        for(int i=0;i<ids.length;i++) {
            RadioButton button=new RadioButton(context,null,0,R.style.CaptureSegment);
            button.setId(ids[i]); button.setText(titles[i]);
            group.addView(button,new RadioGroup.LayoutParams(0,-2,1));
        }
        group.check(ids[0]);
        group.setOnCheckedChangeListener((view,id)->changed.accept(id==ids[1]));
        return group;
    }
}
