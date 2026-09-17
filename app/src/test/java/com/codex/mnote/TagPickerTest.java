package com.codex.mnote;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {30, 35}, instrumentedPackages = "com.codex.mnote",
    shadows = {CaptureAccountTest.Crypto.class, CaptureAccountTest.Scheduler.class})
public class TagPickerTest {
    Context context;
    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
    }
    CaptureStore.CaptureRecord note(String tags) throws Exception {
        return CaptureStore.save(context, null, null, null, null, "thought", "标签测试",
            "quick_note", "", "", "", "", false, null, CaptureTags.parse(tags));
    }
    void drain() throws Exception {
        CaptureTags.TAG_WORKER.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
    void login(String id) throws Exception {
        CaptureAccountSession.save(context, "https://example.test",
            new JSONObject()
                .put("account_id", id.repeat(32))
                .put("username", id)
                .put("access_token", "mns_test_only")
                .put("expires_at", 4102444800L));
    }
    @Test
    public void searchChooseRemoveAndTypedTagsStayInSyncWithoutPopup() throws Exception {
        note("工作，灵感，TODO");
        note("todo，待读");
        try (var c = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = c.get();
            activity.setTheme(R.style.Theme_Mnote);
            activity.setContentView(R.layout.capture_tags);
            var field = new CaptureTags.Field(activity.getWindow().getDecorView());
            field.input.setText("自定义");
            activity.findViewById(R.id.capture_tags_choose).performClick();
            drain();
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            LinearLayout options = activity.findViewById(R.id.capture_tags_options);
            assertEquals(4, options.getChildCount());
            EditText search = activity.findViewById(R.id.capture_tags_search);
            search.setText("工作");
            assertEquals(1, options.getChildCount());
            options.getChildAt(0).performClick();
            assertTrue(CaptureTags.matches(field.validated(), "工作"));
            assertTrue(CaptureTags.matches(field.validated(), "自定义"));
            options.getChildAt(0).performClick();
            assertFalse(CaptureTags.matches(field.validated(), "工作"));
            field.input.setText("自定义，工作");
            assertTrue(((CheckBox) options.getChildAt(0)).isChecked());
            search.setText("");
            var root = SettingsActivityTest.layout(activity, 390, 844);
            SettingsActivityTest.render(root, "existing-tag-picker.png");
            JSONArray many = new JSONArray();
            for (int i = 0; i < 20; i++) many.put("tag" + i);
            field.input.setText(CaptureTags.input(many));
            options.getChildAt(0).performClick();
            assertEquals(20, field.validated().length());
            assertNotNull(field.input.getError());
        }
    }
    @Test
    public void tagsExcludeDeletedAndOtherAccounts() throws Exception {
        note("访客");
        login("a");
        note("账号A");
        var gone = note("删除标签");
        CaptureDeletionStore.delete(context, gone.id);
        String scope = CaptureAccountSession.scope(context);
        assertEquals(List.of("账号A"), CaptureTags.existing(context, scope));
        login("b");
        note("账号B");
        assertEquals(
            List.of("账号B"), CaptureTags.existing(context, CaptureAccountSession.scope(context)));
        assertThrows(Exception.class, () -> CaptureTags.existing(context, scope));
    }
    @Test
    public void overlayContextAndPaginationNeedNoNewWindow() throws Exception {
        for (int n = 0; n < 30; n++) note("标签" + n);
        Context themed = new ContextThemeWrapper(context, R.style.Theme_Mnote);
        View root = LayoutInflater.from(themed).inflate(R.layout.capture_tags, null);
        var field = new CaptureTags.Field(root);
        root.findViewById(R.id.capture_tags_choose).performClick();
        drain();
        LinearLayout options = root.findViewById(R.id.capture_tags_options);
        assertEquals(20, options.getChildCount());
        root.findViewById(R.id.capture_tags_more).performClick();
        assertEquals(30, options.getChildCount());
        options.getChildAt(25).performClick();
        assertEquals(1, field.validated().length());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }
}
