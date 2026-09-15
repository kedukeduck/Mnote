package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.*;
import android.os.Looper;
import android.widget.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35}, instrumentedPackages = "com.codex.mnote",
    shadows = {CaptureAccountTest.Crypto.class, CaptureAccountTest.Scheduler.class,
        MarkdownExportActivityTest.Http.class})
public class MarkdownExportActivityTest {
    @Implements(value = CaptureAccountHttp.class, isInAndroidSdk = false)
    public static class Http {
        static int calls;
        static int created, revoked;
        @Implementation
        protected static JSONObject request(String base, String method, String path, String token,
            JSONObject body, Integer revision) throws Exception {
            calls++;
            if (path.equals("/v1/exports/markdown")) {
                assertTrue(body.getBoolean("publish_images"));
                created++;
                return new JSONObject().put("id", "e".repeat(32)).put("markdown", "# Mnote 记录导出\n\n想法与原文\n");
            }
            if (path.equals("/v1/exports/" + "e".repeat(32)) && method.equals("DELETE")) {
                revoked++;
                return new JSONObject();
            }
            if (path.equals("/v1/exports"))
                return new JSONObject().put("exports", new JSONArray());
            throw new java.io.IOException("unexpected_network");
        }
    }
    Context context;
    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Http.calls = 0;
        Http.created = Http.revoked = 0;
        CaptureAccountSession.save(context, "https://example.test",
            new JSONObject()
                .put("account_id", "a".repeat(32))
                .put("username", "test-export")
                .put("access_token", "mns_test_only")
                .put("expires_at", 4102444800L));
    }
    CaptureStore.CaptureRecord note(String value, boolean synced) throws Exception {
        var record = CaptureStore.save(
            context, null, null, null, null, "thought", value, "quick_note", "", "");
        if (synced)
            CaptureStore.updateSyncState(context, record.id, CaptureStore.SYNC_SYNCED, "", 1);
        return CaptureStore.find(context, record.id);
    }
    void drain(MarkdownExportActivity activity) throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
    Button button(MarkdownExportActivity activity, String name) {
        return ReflectionHelpers.getField(activity, name);
    }
    @Test
    public void onlySyncedRecordsAreSelectableAndCancelNeverPublishes() throws Exception {
        var synced = note("可导出", true);
        var pending = note("未同步", false);
        assertTrue(MarkdownExportActivity.eligible(synced));
        assertFalse(MarkdownExportActivity.eligible(pending));
        try (var controller = Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity = controller.get();
            drain(activity);
            assertFalse(button(activity, "export").isEnabled());
            button(activity, "all").performClick();
            ListView list = ReflectionHelpers.getField(activity, "list");
            assertEquals(1, list.getCheckedItemCount());
            button(activity, "export").performClick();
            var dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, Http.calls);
            button(activity, "export").performClick();
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .performClick();
            shadowOf(Looper.getMainLooper()).idle();
            Intent picker = shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.getAction());
            assertEquals("text/markdown", picker.getType());
            assertEquals(0, Http.calls);
            activity.onActivityResult(902, Activity.RESULT_CANCELED, null);
            assertTrue(button(activity, "export").isEnabled());
            assertEquals(0, Http.calls);
        }
    }
    @Test
    public void searchFilterAndClearSelectionAreApplied() throws Exception {
        note("alpha", true);
        note("beta", true);
        Intent intent =
            new Intent(context, MarkdownExportActivity.class).putExtra("query", "alpha");
        try (var controller =
                 Robolectric.buildActivity(MarkdownExportActivity.class, intent).setup()) {
            var activity = controller.get();
            drain(activity);
            ListView list = ReflectionHelpers.getField(activity, "list");
            assertEquals(1, list.getCount());
            button(activity, "all").performClick();
            assertEquals(1, list.getCheckedItemCount());
            button(activity, "clear").performClick();
            assertEquals(0, list.getCheckedItemCount());
            assertFalse(button(activity, "export").isEnabled());
        }
    }
    @Test
    public void managementIsAccountScopedAndReportsEmptyShares() throws Exception {
        try (var controller = Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity = controller.get();
            drain(activity);
            button(activity, "manage").performClick();
            drain(activity);
            assertEquals(1, Http.calls);
            TextView status = ReflectionHelpers.getField(activity, "status");
            assertTrue(status.getText().toString().contains("没有有效"));
            CaptureAccountSession.clear(context);
            // A stale window must not fetch another account's export list.
            ReflectionHelpers.callInstanceMethod(activity, "manage");
            drain(activity);
            assertEquals(1, Http.calls);
        }
    }
    @Test
    public void denyAndZeroRevisionAreNeverExportable() {
        var denied =
            new CaptureStore.CaptureRecord("denied", 0, "thought", "", "quick_note", "", "", "", "",
                "", "deny", CaptureStore.SYNC_SYNCED, "", 1, false, null, null, null, null, null);
        assertFalse(MarkdownExportActivity.eligible(denied));
        var zero =
            new CaptureStore.CaptureRecord("zero", 0, "thought", "", "quick_note", "", "", "", "",
                "", "allow", CaptureStore.SYNC_SYNCED, "", 0, false, null, null, null, null, null);
        assertFalse(MarkdownExportActivity.eligible(zero));
    }
    @Rule public org.junit.rules.TemporaryFolder files = new org.junit.rules.TemporaryFolder();
    private void returnFile(MarkdownExportActivity activity, CaptureStore.CaptureRecord record, java.io.File file) throws Exception {
        ReflectionHelpers.setField(activity, "pending", new JSONArray().put(new JSONObject().put("id",record.id).put("revision",record.serverRevision)));
        activity.onActivityResult(902,Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(file)));
        drain(activity);
    }
    @Test public void savedMarkdownIsUtf8AndDoesNotRevokeSuccessfulExport() throws Exception {
        var record=note("成功",true);
        try(var controller=Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity=controller.get();drain(activity);var file=files.newFile("export.md");
            returnFile(activity,record,file);
            assertEquals("# Mnote 记录导出\n\n想法与原文\n",java.nio.file.Files.readString(file.toPath()));
            assertEquals(1,Http.created);assertEquals(0,Http.revoked);
        }
    }
    @Test public void failedFileWriteRevokesCreatedImageLinks() throws Exception {
        var record=note("失败",true);
        try(var controller=Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity=controller.get();drain(activity);
            returnFile(activity,record,files.newFolder("not-a-file.md"));
            assertEquals(1,Http.created);assertEquals(1,Http.revoked);
        }
    }
    @Test public void changedRevisionAfterPickerCreatesNoLinks() throws Exception {
        var record=note("旧内容",true);
        try(var controller=Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity=controller.get();drain(activity);
            CaptureStore.updateSyncState(context,record.id,CaptureStore.SYNC_PENDING,"",1);
            returnFile(activity,record,files.newFile("empty.md"));
            assertEquals(0,Http.created);
        }
    }
}
