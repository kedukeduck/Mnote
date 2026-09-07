package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class SourceLinkFlowTest {
    private static final String URL = "https://m.weibo.cn/detail/123456789?from=share#comments";

    @Before public void installSignaturePermission() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    }

    @Test public void sharedPostLinkPersistsSeparatelyAndRoundTripsIntoSyncPayload() throws Exception {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "这是一条动态 " + URL);
        try (ActivityController<CaptureEditorActivity> controller =
                     Robolectric.buildActivity(CaptureEditorActivity.class, share).setup()) {
            CaptureEditorActivity activity = controller.get();
            EditText url = activity.findViewById(R.id.capture_source_url);
            assertEquals(URL, url.getText().toString());
            ((EditText) activity.findViewById(R.id.capture_comment_input)).setText("我的想法");
            CaptureStore.CaptureRecord record = save(activity);
            assertEquals(URL, record.sourceUrl);
            assertEquals("shared_text", record.sourceUrlOrigin);
            assertEquals("这是一条动态 " + URL, record.sourceText);
            assertEquals("我的想法", record.comment);
            JSONObject payload = ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class, "metadata",
                    ReflectionHelpers.ClassParameter.from(Context.class, activity),
                    ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class, record));
            assertEquals(URL, payload.getJSONObject("source").getString("url"));
            assertEquals("shared_text", payload.getJSONObject("source").getString("url_origin"));
            assertFalse(record.hasImage);
        }
    }

    @Test public void manualLinkOnlyNoteIsValidAndNeverCreatesImageAssets() throws Exception {
        try (ActivityController<CaptureEditorActivity> controller =
                     Robolectric.buildActivity(CaptureEditorActivity.class).setup()) {
            CaptureEditorActivity activity = controller.get();
            ((EditText) activity.findViewById(R.id.capture_source_url)).setText(URL);
            CaptureStore.CaptureRecord record = save(activity);
            assertEquals(URL, record.sourceUrl);
            assertEquals("user_entered", record.sourceUrlOrigin);
            assertEquals("", record.comment);
            assertFalse(record.hasImage);
        }
    }

    @Test public void clipboardIsNotImportedUntilExplicitPasteAndCanBeRemoved() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("link", URL));
        try (ActivityController<CaptureEditorActivity> controller =
                     Robolectric.buildActivity(CaptureEditorActivity.class).setup()) {
            CaptureEditorActivity activity = controller.get();
            EditText url = activity.findViewById(R.id.capture_source_url);
            assertEquals("", url.getText().toString());
            activity.findViewById(R.id.capture_url_toggle).performClick();
            assertEquals(View.VISIBLE, activity.findViewById(R.id.capture_url_panel).getVisibility());
            activity.findViewById(R.id.capture_url_paste).performClick();
            assertEquals(URL, url.getText().toString());
            url.setText("");
            ((EditText) activity.findViewById(R.id.capture_comment_input)).setText("不关联这条链接");
            assertEquals("", save(activity).sourceUrl);
        }
    }

    @Test public void existingRecordsWithoutLinkFieldsRemainReadable() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CaptureStore.CaptureRecord original = CaptureStore.save(context, null, null, null, null,
                "thought", "旧记录", "quick_note", "", "");
        JSONObject legacy = new JSONObject(new String(
                Files.readAllBytes(original.metadataFile.toPath()), StandardCharsets.UTF_8));
        legacy.remove("sourceUrl");
        legacy.remove("sourceUrlOrigin");
        Files.write(original.metadataFile.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
        CaptureStore.CaptureRecord loaded = CaptureStore.list(context, 10).get(0);
        assertEquals("旧记录", loaded.comment);
        assertEquals("", loaded.sourceUrl);
        assertEquals("", loaded.sourceUrlOrigin);
    }

    @Test public void multipleSharedLinksStayInOriginalTextWithoutGuessingOne() {
        String text = "作者 https://example.com/user 原帖 " + URL;
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        try (ActivityController<CaptureEditorActivity> controller =
                     Robolectric.buildActivity(CaptureEditorActivity.class, share).setup()) {
            assertEquals("", ((EditText) controller.get().findViewById(R.id.capture_source_url)).getText().toString());
            assertEquals(text, ReflectionHelpers.getField(controller.get(), "sourceText"));
        }
    }

    @Test public void reopeningUsesOnlyBrowsableWebIntentAndRejectsUnsafeLinks() {
        try (ActivityController<CaptureInboxActivity> controller =
                     Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            ReflectionHelpers.callInstanceMethod(activity, "openSourceUrl",
                    ReflectionHelpers.ClassParameter.from(String.class, URL));
            Intent opened = shadowOf(activity).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertEquals(URL, opened.getDataString());
            assertTrue(opened.hasCategory(Intent.CATEGORY_BROWSABLE));
            assertNull(opened.getComponent());
            assertNull(opened.getExtras());
            ReflectionHelpers.callInstanceMethod(activity, "openSourceUrl",
                    ReflectionHelpers.ClassParameter.from(String.class, "javascript:alert(1)"));
            assertNull(shadowOf(activity).getNextStartedActivity());
        }
    }

    @Test public void screenshotFallbackPreservesDetectedBrowserMetadata() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.File draft = CaptureStore.writeDraftBitmap(context, image);
        image.recycle();
        Intent intent = new Intent(context, CaptureEditorActivity.class)
                .setAction("com.codex.mnote.action.EDIT_SCREENSHOT")
                .putExtra("com.codex.mnote.extra.CAPTURE_DRAFT_PATH", draft.getAbsolutePath())
                .putExtra("capture_source_package", "com.android.chrome")
                .putExtra("capture_source_url", URL)
                .putExtra("capture_source_origin", "browser_address_bar");
        try (ActivityController<CaptureEditorActivity> controller =
                     Robolectric.buildActivity(CaptureEditorActivity.class, intent).setup()) {
            CaptureEditorActivity activity = controller.get();
            ExecutorService writer = ReflectionHelpers.getField(activity, "executor");
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            CaptureStore.CaptureRecord record = save(activity);
            assertEquals("com.android.chrome", record.sourcePackage);
            assertEquals(URL, record.sourceUrl);
            assertEquals("browser_address_bar", record.sourceUrlOrigin);
            assertTrue(record.hasImage);
        }
    }

    @Test public void detectedUrlCanBeEditedOrRemovedWithoutRetainingAutomaticOrigin() {
        try (ActivityController<CaptureEditorActivity> controller =
                     Robolectric.buildActivity(CaptureEditorActivity.class).setup()) {
            SourceLinkField field = ReflectionHelpers.getField(controller.get(), "sourceLink");
            field.acceptDetected(URL, "browser_address_bar");
            assertEquals("browser_address_bar", field.origin(field.validated()));
            field.input.setText("https://example.com/another");
            assertEquals("user_entered", field.origin(field.validated()));
            field.input.setText("");
            assertEquals("", field.validated());
            assertEquals("", field.origin(field.validated()));
        }
    }

    private static CaptureStore.CaptureRecord save(CaptureEditorActivity activity) throws Exception {
        activity.findViewById(R.id.capture_editor_save).performClick();
        ExecutorService writer = ReflectionHelpers.getField(activity, "executor");
        writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(activity.isFinishing());
        return CaptureStore.list(activity, 10).get(0);
    }
}
