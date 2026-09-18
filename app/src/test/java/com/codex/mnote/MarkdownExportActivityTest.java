package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.*;
import android.os.Looper;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {30, 35}, instrumentedPackages = "com.codex.mnote",
    shadows = {CaptureAccountTest.Crypto.class, CaptureAccountTest.Scheduler.class,
        MarkdownExportActivityTest.Http.class})
public class MarkdownExportActivityTest {
    @Implements(value = CaptureAccountHttp.class, isInAndroidSdk = false)
    public static class Http {
        static int calls;
        static int created, revoked;
        static JSONArray history;
        @Implementation
        protected static JSONObject request(String base, String method, String path, String token,
            JSONObject body, Integer revision) throws Exception {
            calls++;
            if (path.equals("/v1/exports/markdown")) {
                assertTrue(body.getBoolean("publish_images"));
                created++;
                return new JSONObject()
                    .put("id", "e".repeat(32))
                    .put("markdown", "# Mnote 记录导出\n\n想法与原文\n");
            }
            if (path.equals("/v1/exports/"
                    + "e".repeat(32))
                && method.equals("DELETE")) {
                revoked++;
                return new JSONObject();
            }
            if (path.equals("/v1/exports"))
                return new JSONObject().put("exports", history);
            throw new java.io.IOException("unexpected_network");
        }
    }
    Context context;
    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Http.calls = 0;
        Http.created = Http.revoked = 0;
        Http.history = new JSONArray();
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
    public void realScreenshotThumbnailsAndRolePreviewAreVisibleWithoutPublishing()
        throws Exception {
        android.graphics.Bitmap source;
        // Capture an actual native test screen, not a production user's screenshot.
        try (var settings = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            var root = SettingsActivityTest.layout(settings.get(), 390, 844);
            source = android.graphics.Bitmap.createBitmap(
                root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(source));
        }
        var crop = android.graphics.Bitmap.createBitmap(source, 22, 210, 346, 110);
        var draft = CaptureStore.writeDraftBitmap(context, source);
        var layer = new JSONObject()
                        .put("sourceWidth", source.getWidth())
                        .put("sourceHeight", source.getHeight())
                        .put("selection",
                            new JSONObject()
                                .put("left", 22)
                                .put("top", 210)
                                .put("right", 368)
                                .put("bottom", 320));
        var record = CaptureStore.save(context, draft, crop, crop, layer, "thought",
            "把账号和更新放在同一个清晰的入口，界面会更安静。", "screen_capture", "", "", "", "",
            true, null);
        CaptureStore.updateSyncState(context, record.id, CaptureStore.SYNC_SYNCED, "", 1);
        try (var controller = Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity = controller.get();
            drain(activity);
            var root = SettingsActivityTest.layout(activity, 390, 844);
            RecordImageLoader loader = ReflectionHelpers.getField(activity, "images");
            ExecutorService worker = ReflectionHelpers.getField(loader, "worker");
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            ListView list = ReflectionHelpers.getField(activity, "list");
            android.view.View row = list.getChildAt(0);
            LinearLayout pictures = ReflectionHelpers.getField(row, "pictures");
            assertEquals(3, pictures.getChildCount());
            for (int i = 0; i < 3; i++)
                assertNotNull(((ImageView) ((LinearLayout) pictures.getChildAt(i)).getChildAt(0))
                        .getDrawable());
            SettingsActivityTest.layout(activity, 390, 844);
            for (int i = 0; i < 3; i++)
                assertNotNull(((ImageView) ((LinearLayout) pictures.getChildAt(i)).getChildAt(0))
                        .getDrawable());
            SettingsActivityTest.render(root, "markdown-images-preview.png");
            Button preview = ReflectionHelpers.getField(row, "preview");
            preview.performClick();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            android.app.AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            android.view.View dialogRoot = dialog.getWindow().getDecorView();
            CaptureContextPreview full = findPreview(dialogRoot);
            assertNotNull(full);
            assertNotNull(full.getDrawable());
            // Open the full-page role directly by tapping its thumbnail.
            dialog.dismiss();
            ((LinearLayout) pictures.getChildAt(2)).getChildAt(0).performClick();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            dialogRoot = ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
            full = findPreview(dialogRoot);
            assertNotNull(full.getDrawable());
            assertTrue(
                full.getDrawable().getIntrinsicHeight() > full.getDrawable().getIntrinsicWidth());
            dialogRoot.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                                   360, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    760, android.view.View.MeasureSpec.AT_MOST));
            dialogRoot.layout(0, 0, dialogRoot.getMeasuredWidth(), dialogRoot.getMeasuredHeight());
            SettingsActivityTest.render(dialogRoot, "markdown-full-image-preview.png");
            assertEquals(0, Http.calls);
            assertTrue(record.originalFile.exists());
            assertTrue(record.contextFile.exists());
        }
        source.recycle();
        crop.recycle();
    }
    private static CaptureContextPreview findPreview(android.view.View view) {
        if (view instanceof CaptureContextPreview)
            return (CaptureContextPreview) view;
        if (view instanceof android.view.ViewGroup) {
            var group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                var found = findPreview(group.getChildAt(i));
                if (found != null)
                    return found;
            }
        }
        return null;
    }
    @Test
    public void cardsShowSelectionAndFixedActionOnSmallScreen() throws Exception {
        note("比起收集更多知识，我更想留住那些让我开始行动的想法。", true);
        note("好的记录，应该能让我找回当时为什么被触动。", true);
        note("下一次回顾：整理一份给 AI 的思考清单。", true);
        try (var controller = Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity = controller.get();
            drain(activity);
            var root = SettingsActivityTest.layout(activity, 390, 844);
            ListView list = ReflectionHelpers.getField(activity, "list");
            list.performItemClick(list.getChildAt(0), 0, list.getItemIdAtPosition(0));
            assertEquals(1, list.getCheckedItemCount());
            assertEquals("导出 1 条记录", button(activity, "export").getText().toString());
            list.performItemClick(list.getChildAt(0), 0, list.getItemIdAtPosition(0));
            assertEquals(0, list.getCheckedItemCount());
            button(activity, "all").performClick();
            SettingsActivityTest.layout(activity, 390, 844);
            assertTrue(((Checkable) list.getChildAt(0)).isChecked());
            SettingsActivityTest.render(root, "markdown-export-preview.png");
            SettingsActivityTest.layout(activity, 320, 568);
            SettingsActivityTest.render(root, "markdown-export-small.png");
            assertTrue(list.getHeight() > 80);
            var action = button(activity, "export");
            android.graphics.Rect visible =
                new android.graphics.Rect(0, 0, action.getWidth(), action.getHeight());
            ((android.view.ViewGroup) root).offsetDescendantRectToMyCoords(action, visible);
            // Robolectric's window-session visible frame is not resized by a manual measure.
            // Check the actual laid-out descendant bounds in the rendered viewport instead.
            assertTrue(visible.top >= 0 && visible.bottom <= root.getHeight());
            assertTrue(visible.left >= 0 && visible.right <= root.getWidth());
            assertTrue(action.isShown());
            button(activity, "clear").performClick();
            assertEquals(0, list.getCheckedItemCount());
            assertFalse(action.isEnabled());
        }
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
    public void inlineSelectionGoesStraightToConfirmationAndCancelReturns() throws Exception {
        var record = note("chosen", true);
        Intent intent = new Intent(context, MarkdownExportActivity.class)
            .putExtra("inline_export",true)
            .putExtra("selection_scope",CaptureAccountSession.scope(context))
            .putStringArrayListExtra("record_ids",new ArrayList<>(List.of(record.id)));
        try(var c=Robolectric.buildActivity(MarkdownExportActivity.class,intent).setup()) {
            drain(c.get());
            assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
            ListView list=ReflectionHelpers.getField(c.get(),"list");
            assertFalse(list.isShown());
            assertEquals(0,Http.created);
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(c.get().isFinishing());
        }
    }
    @Test
    public void inlineExportNeverSilentlyDropsUnsyncedSelection() throws Exception {
        var record=note("not synced",false);
        Intent intent=new Intent(context,MarkdownExportActivity.class).putExtra("inline_export",true)
            .putExtra("selection_scope",CaptureAccountSession.scope(context))
            .putStringArrayListExtra("record_ids",new ArrayList<>(List.of(record.id)));
        try(var c=Robolectric.buildActivity(MarkdownExportActivity.class,intent).setup()) {
            drain(c.get()); assertTrue(c.get().isFinishing()); assertEquals(0,Http.created);
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
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
    private void returnFile(MarkdownExportActivity activity, CaptureStore.CaptureRecord record,
        java.io.File file) throws Exception {
        ReflectionHelpers.setField(activity, "pending",
            new JSONArray().put(
                new JSONObject().put("id", record.id).put("revision", record.serverRevision)));
        activity.onActivityResult(
            902, Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(file)));
        drain(activity);
    }
    @Test
    public void savedMarkdownIsUtf8AndDoesNotRevokeSuccessfulExport() throws Exception {
        var record = note("成功", true);
        try (var controller = Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity = controller.get();
            drain(activity);
            var file = files.newFile("export.md");
            returnFile(activity, record, file);
            assertEquals("# Mnote 记录导出\n\n想法与原文\n",
                new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(1, Http.created);
            assertEquals(0, Http.revoked);
        }
    }
    @Test
    public void failedFileWriteRevokesCreatedImageLinks() throws Exception {
        var record = note("失败", true);
        try (var controller = Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity = controller.get();
            drain(activity);
            returnFile(activity, record, files.newFolder("not-a-file.md"));
            assertEquals(1, Http.created);
            assertEquals(1, Http.revoked);
        }
    }
    @Test
    public void changedRevisionAfterPickerCreatesNoLinks() throws Exception {
        var record = note("旧内容", true);
        try (var controller = Robolectric.buildActivity(MarkdownExportActivity.class).setup()) {
            var activity = controller.get();
            drain(activity);
            CaptureStore.updateSyncState(context, record.id, CaptureStore.SYNC_PENDING, "", 1);
            returnFile(activity, record, files.newFile("empty.md"));
            assertEquals(0, Http.created);
        }
    }
}
