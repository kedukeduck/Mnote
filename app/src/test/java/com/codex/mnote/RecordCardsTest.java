package com.codex.mnote;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RecordCardsTest {
    @Test public void compactCardsShowCategoryTimeAndTagsWithoutCaptureDock() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        save(context,"todo","周日花 20 分钟，回顾这周的摘录。","","每周回顾",8,16);
        save(context,"comment","","记录，不只是保存信息。也保留那些被触动的时刻。","阅读，灵感",9,18);
        save(context,"thought","想把每周回顾变成习惯。不只看收藏了什么，也看看自己为什么会被触动。","","自我成长，每周回顾",10,24);
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity=controller.get();
            View root=SettingsActivityTest.layout(activity,390,844);
            LinearLayout records=activity.findViewById(R.id.capture_records);
            assertEquals(3,records.getChildCount());
            String[] types={"想法","摘录","待办"};
            String[] times={"10:24","09:18","08:16"};
            for(int i=0;i<3;i++) {
                View card=records.getChildAt(i);
                TextView category=card.findViewById(R.id.capture_item_kind);
                assertEquals(types[i],category.getText().toString());
                assertEquals(times[i],card.<TextView>findViewById(R.id.capture_item_time).getText().toString());
                assertTrue(category.isShown()); assertEquals(12f,category.getTextSize(),0.01f);
                RecordTagsView tags=card.findViewById(R.id.capture_item_tags);
                assertTrue(tags.isShown());
                assertEquals(11f,((TextView)tags.getChildAt(0)).getTextSize(),0.01f);
                assertNotNull(card.findViewById(R.id.journal_item_body).getBackground());
                assertTrue(((LinearLayout.LayoutParams)card.getLayoutParams()).bottomMargin>=12);
            }
            assertNull(activity.findViewById(R.id.capture_action_dock));
            SettingsActivityTest.render(root,"record-cards-home.png");
            records.getChildAt(0).performLongClick();
            assertTrue(records.getChildAt(0).findViewById(R.id.journal_item_body).isSelected());
            SettingsActivityTest.render(SettingsActivityTest.layout(activity,390,844),"record-cards-selected.png");
            activity.findViewById(R.id.capture_selection_cancel).performClick();
            assertFalse(records.getChildAt(0).findViewById(R.id.journal_item_body).isSelected());
            assertNull(activity.findViewById(R.id.capture_action_dock));
            activity.findViewById(R.id.capture_refresh_button).performClick();
            assertEquals(3,records.getChildCount());
        }
    }

    @Test public void longCustomTagsWrapAtSmallWidthAndLargeFontWithoutClipping() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        try {
            JSONArray values=CaptureTags.parse("这是一个比较长的自定义标签用于检查换行，灵感，阅读计划，个人成长，每周回顾");
            RecordTagsView tags=new RecordTagsView(RuntimeEnvironment.getApplication(),null);
            tags.setTags(values);
            tags.measure(View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            tags.layout(0,0,200,tags.getMeasuredHeight());
            assertEquals(values.length(),tags.getChildCount());
            int rows=0, previousTop=-1;
            for(int i=0;i<tags.getChildCount();i++) {
                View chip=tags.getChildAt(i);
                assertTrue(chip.getLeft()>=0); assertTrue(chip.getRight()<=200);
                assertTrue(chip.getBottom()<=tags.getHeight());
                if(chip.getTop()!=previousTop) { rows++; previousTop=chip.getTop(); }
            }
            assertTrue(rows>1);
            tags.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            tags.layout(0,0,200,tags.getMeasuredHeight());
            assertTrue(tags.getChildAt(0).getRight()<=200);
            tags.setTags(new JSONArray()); assertEquals(View.GONE,tags.getVisibility());
        } finally { RuntimeEnvironment.setFontScale(1f); }
    }

    private void save(Context context,String kind,String thought,String quote,String tags,int hour,int minute) throws Exception {
        var record=CaptureStore.save(context,null,null,null,null,kind,thought,"quick_note",quote,"","","",false,null,CaptureTags.parse(tags));
        var data=CaptureStore.readRecordObject(record.metadataFile);
        data.put("createdAt",java.time.LocalDate.now().atTime(hour,minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        CaptureStore.writeJson(data,record.metadataFile);
    }
}
