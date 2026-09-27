package com.codex.mnote;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PrivateJournalLibraryTest {
    @Test public void filterSheetIsStagedCountsAllMatchesAndCancelDoesNotMutateLibrary() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        note(context,"thought","想把每周回顾变成习惯。\n不只看收藏了什么，\n也看看自己为什么会被触动。","学习");
        note(context,"thought","散步时不戴耳机，也许会听见自己的想法。","");
        note(context,"todo","周日花 20 分钟回顾这周的摘录。","学习");
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity=controller.get();
            LinearLayout records=activity.findViewById(R.id.capture_records);
            assertEquals(3,records.getChildCount());
            assertFalse(activity.findViewById(R.id.capture_filter_group).isShown());
            SettingsActivityTest.render(SettingsActivityTest.layout(activity,390,844),"journal-library.png");
            activity.findViewById(R.id.capture_tag_filter).performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            dialog.<RadioGroup>findViewById(R.id.journal_filter_types).check(R.id.capture_filter_todo);
            assertEquals("查看 1 条记录",dialog.<Button>findViewById(R.id.journal_filter_apply).getText().toString());
            assertEquals(3,records.getChildCount());
            dialog.cancel();assertEquals(3,records.getChildCount());
            activity.findViewById(R.id.capture_tag_filter).performClick();
            dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(R.id.capture_filter_all,dialog.<RadioGroup>findViewById(R.id.journal_filter_types).getCheckedRadioButtonId());
            EditText tagSearch=dialog.findViewById(R.id.journal_filter_search);
            RadioGroup tags=dialog.findViewById(R.id.journal_filter_tags);
            tagSearch.setText("不存在的标签");
            assertEquals(View.GONE,tags.getChildAt(2).getVisibility());
            assertEquals(3,records.getChildCount());
            assertEquals("查看 3 条记录",dialog.<Button>findViewById(R.id.journal_filter_apply).getText().toString());
            tagSearch.setText("学"); assertEquals(View.VISIBLE,tags.getChildAt(2).getVisibility());
            dialog.findViewById(R.id.journal_filter_reset).performClick();
            assertEquals("",tagSearch.getText().toString());
            dialog.<RadioGroup>findViewById(R.id.journal_filter_types).check(R.id.capture_filter_todo);
            View sheet=dialog.getWindow().getDecorView();
            sheet.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(580,View.MeasureSpec.EXACTLY));
            sheet.layout(0,0,390,580);SettingsActivityTest.render(sheet,"journal-filter.png");
            dialog.findViewById(R.id.journal_filter_apply).performClick();assertEquals(1,records.getChildCount());
            activity.findViewById(R.id.journal_filter_summary).performClick();assertEquals(3,records.getChildCount());
            records.getChildAt(0).performLongClick();
            assertEquals("已选 1 条",activity.<TextView>findViewById(R.id.capture_selection_count).getText().toString());
            SettingsActivityTest.render(SettingsActivityTest.layout(activity,390,844),"journal-multiselect.png");
            View selected=records.getChildAt(0).findViewById(R.id.capture_item_selected);
            assertEquals(records.getChildAt(0).findViewById(R.id.journal_item_body),selected.getParent());
            assertEquals(View.VISIBLE,activity.findViewById(R.id.journal_selection_header).getVisibility());
            assertEquals(View.GONE,activity.findViewById(R.id.journal_library_header).getVisibility());
            activity.findViewById(R.id.capture_selection_cancel).performClick();
            assertEquals(View.GONE,activity.findViewById(R.id.capture_selection_dock).getVisibility());
            records.getChildAt(1).performClick();
            AlertDialog detail=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(detail.findViewById(R.id.journal_review_thought));
            View reader=detail.getWindow().getDecorView();
            reader.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));
            reader.layout(0,0,390,844);SettingsActivityTest.render(reader,"journal-reader.png");
        }
    }
    @Test public void quickNoteOptionsAreOptInAndReturnKeepsWriting() throws Exception {
        try(var controller=Robolectric.buildActivity(QuickNoteActivity.class).setup()) {
            var activity=controller.get();activity.onWindowFocusChanged(true);
            EditText thought=activity.findViewById(R.id.capture_comment_input);
            thought.setText("想把每周回顾变成习惯。\n不只看收藏了什么，\n也看看自己为什么会被触动。");
            SettingsActivityTest.render(SettingsActivityTest.layout(activity,390,844),"journal-compose.png");
            assertEquals(View.GONE,activity.findViewById(R.id.capture_auxiliary_options).getVisibility());
            activity.findViewById(R.id.capture_more_options).performClick();
            assertEquals(View.VISIBLE,activity.findViewById(R.id.capture_auxiliary_options).getVisibility());
            assertTrue(activity.findViewById(R.id.quick_note_context).isShown());
            assertFalse(activity.<Switch>findViewById(R.id.quick_note_clipboard).isChecked());
            activity.findViewById(R.id.capture_more_options).performClick();
            assertTrue(thought.getText().toString().contains("每周回顾"));
            controller.recreate();
            assertTrue(controller.get().<EditText>findViewById(R.id.capture_comment_input).getText().toString().contains("每周回顾"));
        }
    }
    private void note(Context context,String kind,String text,String tags) throws Exception {
        CaptureStore.save(context,null,null,null,null,kind,text,"quick_note","","","","",false,null,CaptureTags.parse(tags));
    }
}
