package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

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
                RecordTagsView categories=card.findViewById(R.id.capture_item_kind);
                assertCategories(card,types[i]);
                TextView category=(TextView)categories.getChildAt(0);
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

    @Test public void mixedRecordShowsThoughtAndExcerptWithDistinctPreviewsAndBothFilters() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        save(context,"todo","周日花 20 分钟，回顾这周的摘录。","","每周回顾",8,16);
        save(context,"thought","","把注意力留给真正重要的事情。","阅读",9,18);
        var mixed=save(context,"comment","想把每周回顾变成习惯。不只看收藏了什么，也看看自己为什么会被触动。",
                "记录，不只是保存信息。也保留那些被触动的时刻。","自我成长，每周回顾",10,24);
        String savedBefore=CaptureStore.readRecordObject(mixed.metadataFile).toString();
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity=controller.get();
            View root=SettingsActivityTest.layout(activity,390,844);
            View card=findCard(activity,mixed.id);
            assertCategories(card,"想法","摘录");
            TextView thought=card.findViewById(R.id.capture_item_comment);
            TextView excerpt=card.findViewById(R.id.capture_item_exact_text);
            assertTrue(thought.isShown()); assertTrue(excerpt.isShown());
            assertEquals(mixed.comment,thought.getText().toString());
            assertEquals(mixed.sourceText,excerpt.getText().toString());
            assertEquals("我的想法",card.<TextView>findViewById(R.id.capture_item_comment_label).getText().toString());
            assertEquals("摘录",card.<TextView>findViewById(R.id.capture_item_excerpt_label).getText().toString());
            assertTrue(thought.getTextSize()>excerpt.getTextSize());
            assertNotNull(card.findViewById(R.id.capture_item_excerpt_block).getBackground());
            assertTrue(card.getContentDescription().toString().contains(mixed.comment));
            assertTrue(card.getContentDescription().toString().contains(mixed.sourceText));
            SettingsActivityTest.render(root,"mixed-record-cards-home.png");
            card.performLongClick();
            assertTrue(card.findViewById(R.id.journal_item_body).isSelected());
            SettingsActivityTest.render(SettingsActivityTest.layout(activity,390,844),"mixed-record-cards-selected.png");
            activity.findViewById(R.id.capture_selection_cancel).performClick();
            assertFalse(card.findViewById(R.id.journal_item_body).isSelected());
            RadioGroup filters=activity.findViewById(R.id.capture_filter_group);
            filters.check(R.id.capture_filter_thought);
            assertEquals(1,records(activity).getChildCount()); assertNotNull(findCard(activity,mixed.id));
            filters.check(R.id.capture_filter_excerpt);
            assertEquals(2,records(activity).getChildCount()); assertNotNull(findCard(activity,mixed.id));
            EditText search=activity.findViewById(R.id.capture_search);
            search.setText("每周回顾变成习惯");
            assertEquals(1,records(activity).getChildCount());
            filters.check(R.id.capture_filter_thought); search.setText("保存信息");
            assertEquals(1,records(activity).getChildCount());
            filters.check(R.id.capture_filter_todo);
            assertEquals(0,records(activity).getChildCount());
        }
        assertEquals("Classification and filtering must not migrate stored records",savedBefore,
                CaptureStore.readRecordObject(mixed.metadataFile).toString());
        assertEquals("comment",CaptureStore.find(context,mixed.id).kind);
    }

    @Test public void todoWithExcerptRetainsTodoIdentityAndDoesNotBecomeAThought() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        var todo=save(context,"todo","下周试着写一篇阅读笔记。","把阅读变成行动。","行动",10,24);
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity=controller.get(); SettingsActivityTest.layout(activity,390,844);
            View card=findCard(activity,todo.id);
            assertCategories(card,"待办","摘录");
            assertEquals("待办内容",card.<TextView>findViewById(R.id.capture_item_comment_label).getText().toString());
            assertTrue(card.findViewById(R.id.capture_item_comment).isShown());
            assertTrue(card.findViewById(R.id.capture_item_exact_text).isShown());
            RadioGroup filters=activity.findViewById(R.id.capture_filter_group);
            filters.check(R.id.capture_filter_thought); assertEquals(0,records(activity).getChildCount());
            filters.check(R.id.capture_filter_todo); assertEquals(1,records(activity).getChildCount());
            filters.check(R.id.capture_filter_excerpt); assertEquals(1,records(activity).getChildCount());
        }
    }

    @Test public void standaloneOriginalAndScreenshotRemainVisibleBesideThoughts() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        var original=CaptureStore.save(context,null,null,null,null,"thought","这篇文章值得周末再读一次。",
                "quick_note","","","","",false,
                CaptureContext.text("页面原文的第一段，保留足够的阅读上下文。","accessibility_page",""),CaptureTags.parse("阅读"));
        Bitmap sample=Bitmap.createBitmap(480,240,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(sample); canvas.drawColor(Color.rgb(233,241,238));
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.rgb(35,75,67));paint.setTextSize(28);
        canvas.drawText("A little room for ideas",28,72,paint);
        paint.setColor(Color.rgb(174,192,178));canvas.drawRect(28,108,310,120,paint);canvas.drawRect(28,142,406,154,paint);
        var screenshot=CaptureStore.save(context,null,sample,sample,null,"thought","截下这个页面，之后整理成行动。",
                "screen","","","","",false,null,CaptureTags.parse("灵感"));
        sample.recycle();
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity=controller.get();
            awaitThumbnails(activity); SettingsActivityTest.layout(activity,390,844);
            View originalCard=findCard(activity,original.id);
            assertCategories(originalCard,"想法","摘录");
            assertEquals("页面原文",originalCard.<TextView>findViewById(R.id.capture_item_excerpt_label).getText().toString());
            assertTrue(originalCard.findViewById(R.id.capture_item_comment).isShown());
            assertEquals(CaptureRecordEdits.original(original),originalCard.<TextView>findViewById(R.id.capture_item_exact_text).getText().toString());
            View screenshotCard=findCard(activity,screenshot.id);
            assertCategories(screenshotCard,"想法","摘录");
            assertEquals("截图",screenshotCard.<TextView>findViewById(R.id.capture_item_excerpt_label).getText().toString());
            assertTrue(screenshotCard.findViewById(R.id.capture_item_comment).isShown());
            assertEquals(View.GONE,screenshotCard.findViewById(R.id.capture_item_exact_text).getVisibility());
            ImageView image=screenshotCard.findViewById(R.id.capture_item_image);
            assertTrue(image.isShown()); assertNotNull(image.getDrawable());
            assertTrue(isDescendant(image,screenshotCard.findViewById(R.id.capture_item_excerpt_block)));
            SettingsActivityTest.render(SettingsActivityTest.layout(activity,390,844),"mixed-record-cards-materials.png");
        }
    }

    @Test public void whitespaceAndLinkOnlyRecordsDoNotCreatePhantomExcerptOrThoughtPreviews() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        var pureExcerpt=save(context,"thought"," \n\t ","只有摘录，没有自己的想法。","",8,0);
        var pureThought=save(context,"comment","这是我的想法。"," \n\t ","",9,0);
        var linkOnly=CaptureStore.save(context,null,null,null,null,"thought","","quick_note"," \n\t ","",
                "https://example.test/article","user_entered",false,CaptureContext.text(" \n\t ","accessibility_page",""));
        var emptyLegacy=save(context,"comment","临时内容","","",10,0);
        JSONObject data=CaptureStore.readRecordObject(emptyLegacy.metadataFile);
        data.put("comment"," \n\t ");CaptureStore.writeJson(data,emptyLegacy.metadataFile);
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity=controller.get(); SettingsActivityTest.layout(activity,390,844);
            View excerpt=findCard(activity,pureExcerpt.id);assertCategories(excerpt,"摘录");
            assertEquals(View.GONE,excerpt.findViewById(R.id.capture_item_comment).getVisibility());
            assertEquals(View.GONE,excerpt.findViewById(R.id.capture_item_comment_label).getVisibility());
            View thought=findCard(activity,pureThought.id);assertCategories(thought,"想法");
            assertEquals(View.GONE,thought.findViewById(R.id.capture_item_excerpt_block).getVisibility());
            View link=findCard(activity,linkOnly.id);assertCategories(link,"想法");
            assertEquals(View.GONE,link.findViewById(R.id.capture_item_excerpt_block).getVisibility());
            assertEquals(View.GONE,link.findViewById(R.id.capture_item_comment_label).getVisibility());
            assertCategories(findCard(activity,emptyLegacy.id),"记录");
            activity.<RadioGroup>findViewById(R.id.capture_filter_group).check(R.id.capture_filter_excerpt);
            assertEquals(1,records(activity).getChildCount()); assertNotNull(findCard(activity,pureExcerpt.id));
        }
    }

    @Test public void longMixedPreviewsAreBoundedButDetailAndPersistedTextStayComplete() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        String thought="这是我的想法，需要保留全部内容。\n".repeat(40)+"想法的最后一句。";
        String excerpt="这是选中的摘录，和自己的想法分开。\n".repeat(40)+"摘录的最后一句。";
        String original="完整原文需要在详情页面继续阅读。\n".repeat(50)+"原文的最后一句。";
        var record=CaptureStore.save(context,null,null,null,null,"comment",thought,"clipboard",excerpt,"","","",false,
                CaptureContext.text(original,"accessibility_page",""),CaptureTags.parse("长内容"));
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity=controller.get(); SettingsActivityTest.layout(activity,390,844);
            View card=findCard(activity,record.id); assertCategories(card,"想法","摘录");
            for(int id:new int[]{R.id.capture_item_comment,R.id.capture_item_exact_text}) {
                TextView preview=card.findViewById(id);
                assertTrue(preview.isShown());
                assertTrue("Each preview has a small finite line budget",preview.getMaxLines()>0 && preview.getMaxLines()<=4);
                assertNotNull(preview.getEllipsize());
            }
            card.performClick(); AlertDialog detail=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(thought,detail.<TextView>findViewById(R.id.journal_review_thought).getText().toString());
            assertEquals(excerpt,detail.<TextView>findViewById(R.id.journal_review_excerpt).getText().toString());
            assertEquals(original,detail.<TextView>findViewById(R.id.journal_review_original).getText().toString());
            detail.dismiss();
        }
        var persisted=CaptureStore.find(context,record.id);
        assertEquals(thought,persisted.comment); assertEquals(excerpt,persisted.sourceText);
        assertEquals(original,CaptureRecordEdits.original(persisted));
    }

    @Test public void multipleSystemCategoriesWrapAtNarrowWidthsWithLargeText() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        try {
            var record=save(RuntimeEnvironment.getApplication(),"comment","我的想法","摘录的内容","阅读，个人成长",10,24);
            try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
                var activity=controller.get(); SettingsActivityTest.layout(activity,320,844);
                View card=findCard(activity,record.id); assertCategories(card,"想法","摘录");
                RecordTagsView categories=card.findViewById(R.id.capture_item_kind);
                TextView time=card.findViewById(R.id.capture_item_time);
                assertTrue(time.isShown());
                assertTrue(categories.getHeight()>0);
                SettingsActivityTest.render(SettingsActivityTest.layout(activity,320,844),"mixed-record-cards-large-text.png");
                categories.measure(View.MeasureSpec.makeMeasureSpec(82,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                categories.layout(0,0,82,categories.getMeasuredHeight());
                assertTrue("System chips wrap instead of hiding one classification",
                        categories.getChildAt(1).getTop()>categories.getChildAt(0).getTop());
                for(int i=0;i<categories.getChildCount();i++) {
                    View chip=categories.getChildAt(i);
                    assertTrue(chip.getLeft()>=0); assertTrue(chip.getRight()<=82);
                    assertTrue(chip.getBottom()<=categories.getHeight());
                }
                categories.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                categories.layout(0,0,82,categories.getMeasuredHeight());
                assertTrue(categories.getChildAt(0).getLeft()>=0);
                assertTrue(categories.getChildAt(0).getRight()<=82);
            }
        } finally { RuntimeEnvironment.setFontScale(1f); }
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

    private CaptureStore.CaptureRecord save(Context context,String kind,String thought,String quote,String tags,int hour,int minute) throws Exception {
        var record=CaptureStore.save(context,null,null,null,null,kind,thought,"quick_note",quote,"","","",false,null,CaptureTags.parse(tags));
        var data=CaptureStore.readRecordObject(record.metadataFile);
        data.put("createdAt",java.time.LocalDate.now().atTime(hour,minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        CaptureStore.writeJson(data,record.metadataFile);
        return CaptureStore.find(context,record.id);
    }

    private static LinearLayout records(CaptureInboxActivity activity) {return activity.findViewById(R.id.capture_records);}

    private static View findCard(CaptureInboxActivity activity,String id) {
        LinearLayout records=records(activity);
        for(int i=0;i<records.getChildCount();i++) if(id.equals(records.getChildAt(i).getTag())) return records.getChildAt(i);
        fail("Missing card: "+id);return null;
    }

    private static void assertCategories(View card,String... expected) {
        ViewGroup categories=card.findViewById(R.id.capture_item_kind);
        Set<String> actual=new LinkedHashSet<>();
        for(int i=0;i<categories.getChildCount();i++) {
            TextView chip=(TextView)categories.getChildAt(i);
            String label=chip.getText().toString();
            assertFalse("System categories are not custom hashtags",label.startsWith("#"));
            assertNotEquals("批注",label);
            actual.add(label);
        }
        assertEquals(new LinkedHashSet<>(Arrays.asList(expected)),actual);
        assertEquals("No duplicate system categories",actual.size(),categories.getChildCount());
    }

    private static boolean isDescendant(View child,View ancestor) {
        for(android.view.ViewParent parent=child.getParent();parent!=null;parent=parent.getParent()) if(parent==ancestor)return true;
        return false;
    }

    private static void awaitThumbnails(CaptureInboxActivity activity) throws Exception {
        ReflectionHelpers.<ExecutorService>getField(activity,"thumbnailExecutor").submit(()->{}).get(10,TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
}
