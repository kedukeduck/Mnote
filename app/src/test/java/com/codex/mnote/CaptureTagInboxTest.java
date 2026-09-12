package com.codex.mnote;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureTagInboxTest {
    @Before public void setup() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    }
    @Test public void labelsFilterAllKindsCombineWithSearchAndSurviveRotation() throws Exception {
        note("thought","读书想法","学习，灵感");note("todo","读书计划","学习");note("thought","日常杂记","");
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity a=controller.get();assertEquals(3,count(a));
            choose(a,"#学习");assertEquals(2,count(a));
            a.<RadioGroup>findViewById(R.id.capture_filter_group).check(R.id.capture_filter_todo);assertEquals(1,count(a));
            a.<EditText>findViewById(R.id.capture_search).setText("读书");assertEquals(1,count(a));
            a.<EditText>findViewById(R.id.capture_search).setText("不存在");assertEquals(0,count(a));
            a.<EditText>findViewById(R.id.capture_search).setText("");
            a.<RadioGroup>findViewById(R.id.capture_filter_group).check(R.id.capture_filter_all);
            controller.recreate();a=controller.get();assertEquals(2,count(a));
            assertTrue(a.<Button>findViewById(R.id.capture_tag_filter).getText().toString().contains("学习"));
            choose(a,"未分类");assertEquals(1,count(a));
            choose(a,"全部标签");assertEquals(3,count(a));
            a.<EditText>findViewById(R.id.capture_search).setText("灵感");assertEquals(1,count(a));
            LinearLayout records=a.findViewById(R.id.capture_records);
            assertTrue(records.getChildAt(0).<TextView>findViewById(R.id.capture_item_tags).getText().toString().contains("#灵感"));
        }
    }
    @Test public void recordsBeyondFirstFiftyRemainReachableAndFiltersSearchTheWholeLibrary() throws Exception {
        for(int i=0;i<52;i++)note("thought","记录 "+i,"共同标签");
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity a=controller.get();assertEquals(50,count(a));
            assertEquals(View.VISIBLE,a.findViewById(R.id.capture_load_more).getVisibility());
            a.findViewById(R.id.capture_load_more).performClick();assertEquals(52,count(a));
            assertEquals(View.GONE,a.findViewById(R.id.capture_load_more).getVisibility());
            choose(a,"#共同标签");assertEquals(50,count(a));
            a.<EditText>findViewById(R.id.capture_search).setText("记录 0");assertEquals(1,count(a));
        }
    }
    @Test public void selectedTagDoesNotCarryIntoAnotherAccountScope() throws Exception {
        note("thought","私有标签记录","只在本机");
        try(var controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity a=controller.get();choose(a,"#只在本机");
            CaptureAccountSession.preferences(a).edit().putString("scope","b".repeat(64)).commit();
            // Reload locally without logging into or connecting to a server.
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(a,"renderRecords");
            assertTrue(a.<Button>findViewById(R.id.capture_tag_filter).getText().toString().contains("全部"));
            assertEquals(0,count(a));
        }
    }
    private void note(String kind,String text,String tags) throws Exception {
        CaptureStore.save(RuntimeEnvironment.getApplication(),null,null,null,null,kind,text,"quick_note","","","","",false,null,CaptureTags.parse(tags));
        shadowOf(Looper.getMainLooper()).idle();
    }
    private int count(CaptureInboxActivity a) {return a.<LinearLayout>findViewById(R.id.capture_records).getChildCount();}
    private void choose(CaptureInboxActivity a,String prefix) {
        a.findViewById(R.id.capture_tag_filter).performClick();
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView list=dialog.getListView();
        for(int i=0;i<list.getAdapter().getCount();i++) if(list.getAdapter().getItem(i).toString().startsWith(prefix)) {
            list.performItemClick(null,i,list.getAdapter().getItemId(i));shadowOf(Looper.getMainLooper()).idle();return;
        }
        fail("Missing tag: "+prefix);
    }
}
