package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {30, 35}, instrumentedPackages = "com.codex.mnote",
    shadows = {CaptureAccountTest.Crypto.class, CaptureAccountTest.Scheduler.class})
public class RecordSelectionTest {
    Context context;
    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        shadowOf((android.app.Application) context)
            .grantPermissions(
                context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    }
    CaptureStore.CaptureRecord note(String text) throws Exception {
        return CaptureStore.save(
            context, null, null, null, null, "thought", text, "quick_note", "", "");
    }
    void login(String value) throws Exception {
        CaptureAccountSession.save(context, "https://test.example",
            new JSONObject()
                .put("account_id", value.repeat(32))
                .put("username", value)
                .put("access_token", "mns_test_only")
                .put("expires_at", 4102444800L));
    }
    void drain(CaptureInboxActivity activity) throws Exception {
        shadowOf(Looper.getMainLooper()).idle();
        ExecutorService worker = ReflectionHelpers.getField(activity, "refreshExecutor");
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
    @Test
    public void longPressSelectsInlineAndExportsOnlyChosenIds() throws Exception {
        note("第一条思考");
        note("第二条思考");
        note("不选中的记录");
        try (var controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity = controller.get();
            SettingsActivityTest.layout(activity, 390, 844);
            LinearLayout rows = activity.findViewById(R.id.capture_records);
            View first = rows.getChildAt(0), second = rows.getChildAt(1);
            first.performLongClick();
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertEquals(
                View.VISIBLE, activity.findViewById(R.id.capture_selection_dock).getVisibility());
            second.performClick();
            assertTrue(((CheckBox) first.findViewById(R.id.capture_item_selected)).isChecked());
            assertTrue(((CheckBox) second.findViewById(R.id.capture_item_selected)).isChecked());
            var root = SettingsActivityTest.layout(activity, 390, 844);
            SettingsActivityTest.render(root, "record-multiselect.png");
            activity.findViewById(R.id.capture_selection_export).performClick();
            Intent intent = shadowOf(activity).getNextStartedActivity();
            assertEquals(
                MarkdownExportActivity.class.getName(), intent.getComponent().getClassName());
            assertEquals(new HashSet<>(Arrays.asList(first.getTag(), second.getTag())),
                new HashSet<>(intent.getStringArrayListExtra("record_ids")));
            activity.findViewById(R.id.capture_export_markdown).performClick();
            assertEquals(intent.getStringArrayListExtra("record_ids"),
                shadowOf(activity).getNextStartedActivity().getStringArrayListExtra("record_ids"));
            activity.onBackPressed();
            assertFalse(activity.isFinishing());
            assertEquals(
                View.GONE, activity.findViewById(R.id.capture_selection_dock).getVisibility());
        }
    }
    @Test
    public void deleteRequiresConfirmationAndNeverDeletesUnselectedRecords() throws Exception {
        note("一");
        note("二");
        note("保留");
        try (var controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity = controller.get();
            LinearLayout rows = activity.findViewById(R.id.capture_records);
            String id = (String) rows.getChildAt(0).getTag();
            rows.getChildAt(0).performLongClick();
            activity.findViewById(R.id.capture_selection_delete).performClick();
            var dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            assertTrue(CaptureDeletionStore.hidden(context).isEmpty());
            activity.findViewById(R.id.capture_selection_delete).performClick();
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .performClick();
            drain(activity);
            assertEquals(Set.of(id), CaptureDeletionStore.hidden(context));
            assertEquals(
                2, CaptureRemoteCache.merged(context, CaptureStore.list(context, 100)).size());
        }
    }
    @Test
    public void batchValidationRejectsChangedOrMissingRecordWithoutPartialDelete()
        throws Exception {
        var a = note("a");
        var b = note("b");
        CaptureStore.updateSyncState(context, b.id, CaptureStore.SYNC_SYNCED, "", 2);
        assertThrows(Exception.class,
            () -> CaptureDeletionStore.deleteMany(context, "guest", Arrays.asList(a, b)));
        assertTrue(CaptureDeletionStore.hidden(context).isEmpty());
        login("a");
        assertThrows(Exception.class,
            () -> CaptureDeletionStore.deleteMany(context, "guest", Arrays.asList(a, b)));
        assertTrue(CaptureDeletionStore.hidden(context).isEmpty());
    }
    @Test
    public void filteringAndAccountChangeClearHiddenSelection() throws Exception {
        login("a");
        note("alpha");
        note("beta");
        try (var controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity = controller.get();
            LinearLayout rows = activity.findViewById(R.id.capture_records);
            rows.getChildAt(0).performLongClick();
            ((EditText) activity.findViewById(R.id.capture_search)).setText("nothing matches");
            assertFalse(activity.findViewById(R.id.capture_selection_delete).isEnabled());
            login("b");
            controller.pause().resume();
            assertEquals(
                View.GONE, activity.findViewById(R.id.capture_selection_dock).getVisibility());
        }
    }
    @Test
    public void accountSwitchAfterDeleteConfirmationCannotDeleteEitherAccount() throws Exception {
        login("a");
        note("a record");
        try (var controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity = controller.get();
            ((LinearLayout) activity.findViewById(R.id.capture_records))
                .getChildAt(0)
                .performLongClick();
            activity.findViewById(R.id.capture_selection_delete).performClick();
            var dialog = ShadowAlertDialog.getLatestAlertDialog();
            login("b");
            note("b record");
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            drain(activity);
            assertTrue(CaptureDeletionStore.hidden(context).isEmpty());
            login("a");
            assertTrue(CaptureDeletionStore.hidden(context).isEmpty());
        }
    }
    @Test
    public void selectionLimitAndRotationRetainOnlyExplicitIds() throws Exception {
        note("visible");
        try (var controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            var activity = controller.get();
            ((LinearLayout) activity.findViewById(R.id.capture_records))
                .getChildAt(0)
                .performLongClick();
            var saved = new android.os.Bundle();
            activity.onSaveInstanceState(saved);
            try (var restored = Robolectric.buildActivity(CaptureInboxActivity.class)
                     .create(saved)
                     .start()
                     .resume()) {
                Set<String> selection =
                    ReflectionHelpers.getField(restored.get(), "selectedRecords");
                assertEquals(1, selection.size());
                for (int i = 1; i < 100; i++) selection.add("test-" + i);
                ReflectionHelpers.callInstanceMethod(restored.get(), "toggleSelection",
                    ReflectionHelpers.ClassParameter.from(String.class, "overflow"));
                assertEquals(100, selection.size());
                assertFalse(selection.contains("overflow"));
            }
        }
    }
    @Test
    public void requestedExportCannotExpandToEntireLibrary() throws Exception {
        login("a");
        var a = note("chosen");
        var b = note("not chosen");
        CaptureStore.updateSyncState(context, a.id, CaptureStore.SYNC_SYNCED, "", 1);
        CaptureStore.updateSyncState(context, b.id, CaptureStore.SYNC_SYNCED, "", 1);
        Intent intent = new Intent(context, MarkdownExportActivity.class)
                            .putExtra("selection_scope", CaptureAccountSession.scope(context))
                            .putStringArrayListExtra("record_ids", new ArrayList<>(List.of(a.id)));
        try (var controller =
                 Robolectric.buildActivity(MarkdownExportActivity.class, intent).setup()) {
            var activity = controller.get();
            ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            ListView list = ReflectionHelpers.getField(activity, "list");
            assertEquals(1, list.getCount());
            assertEquals(1, list.getCheckedItemCount());
        }
        login("b");
        try (var controller =
                 Robolectric.buildActivity(MarkdownExportActivity.class, intent).setup()) {
            assertTrue(controller.get().isFinishing());
        }
    }
}
