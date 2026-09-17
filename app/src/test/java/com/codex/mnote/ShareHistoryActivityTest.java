package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.widget.*;
import java.io.*;
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
        ShareHistoryActivityTest.Http.class})
public class ShareHistoryActivityTest {
    @Implements(value = CaptureAccountHttp.class, isInAndroidSdk = false)
    public static class Http {
        static byte[] png;
        static int deleted, downloads;
        static boolean empty, unavailable;
        static Runnable afterImage;
        @Implementation
        protected static JSONObject request(String base, String method, String path, String token,
            JSONObject body, Integer revision) throws Exception {
            assertEquals("https://example.test", base);
            assertEquals("mns_test_only", token);
            assertEquals("/v1/exports/"
                    + "e".repeat(32),
                path);
            if (method.equals("DELETE")) {
                deleted++;
                return new JSONObject();
            }
            assertEquals("GET", method);
            if (unavailable)
                throw new IOException("http_404");
            JSONArray images = new JSONArray();
            if (!empty)
                for (String role : new String[] {"context", "annotated"})
                    images.put(new JSONObject()
                            .put("name", "1-" + role + ".png")
                            .put("record_index", 1)
                            .put("role", role)
                            .put("size", png.length));
            return new JSONObject().put("created", "2026-09-17 14:30").put("images", images);
        }
        @Implementation
        protected static byte[] image(String base, String path, String token) throws Exception {
            assertEquals("https://example.test", base);
            assertEquals("mns_test_only", token);
            assertTrue(path.startsWith("/v1/exports/"
                + "e".repeat(32) + "/assets/1-"));
            downloads++;
            if (afterImage != null)
                afterImage.run();
            return png;
        }
    }
    Context context;
    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Http.deleted = Http.downloads = 0;
        Http.empty = Http.unavailable = false;
        Http.afterImage = null;
        CaptureAccountSession.save(context, "https://example.test",
            new JSONObject()
                .put("account_id", "a".repeat(32))
                .put("username", "preview-test")
                .put("access_token", "mns_test_only")
                .put("expires_at", 4102444800L));
        try (var controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            var root = SettingsActivityTest.layout(controller.get(), 390, 844);
            Bitmap bitmap =
                Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            Http.png = out.toByteArray();
            bitmap.recycle();
        }
    }
    Intent intent() {
        return new Intent(context, ShareHistoryActivity.class)
            .putExtra("scope", CaptureAccountSession.scope(context))
            .putExtra("export_id", "e".repeat(32));
    }
    void drain(ShareHistoryActivity activity) throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
        for (int i = 0; i < 3; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }
    <T> T field(ShareHistoryActivity activity, String name) {
        return ReflectionHelpers.getField(activity, name);
    }
    @Test
    public void opensHistoricalImagesWithoutLocalRecordOrNewExportAndSwitchesRole()
        throws Exception {
        assertTrue(CaptureStore.list(context, 100).isEmpty());
        try (var controller =
                 Robolectric.buildActivity(ShareHistoryActivity.class, intent()).setup()) {
            var activity = controller.get();
            drain(activity);
            ImageView image = field(activity, "image");
            assertNotNull(image.getDrawable());
            Spinner selector = field(activity, "selector");
            assertEquals(2, selector.getCount());
            var root = SettingsActivityTest.layout(activity, 390, 844);
            SettingsActivityTest.render(root, "share-history-images.png");
            selector.setSelection(1);
            drain(activity);
            assertNotNull(image.getDrawable());
            assertEquals(2, Http.downloads);
            assertEquals(0, Http.deleted);
        }
    }
    @Test
    public void emptyRevokedAndBrokenImagesAreExplicitWithoutCrash() throws Exception {
        Http.empty = true;
        try (var c = Robolectric.buildActivity(ShareHistoryActivity.class, intent()).setup()) {
            drain(c.get());
            assertTrue(
                ((TextView) field(c.get(), "status")).getText().toString().contains("只有文字"));
            assertEquals(0, Http.downloads);
        }
        Http.empty = false;
        Http.unavailable = true;
        try (var c = Robolectric.buildActivity(ShareHistoryActivity.class, intent()).setup()) {
            drain(c.get());
            assertTrue(
                ((TextView) field(c.get(), "status")).getText().toString().contains("已撤销"));
        }
        Http.unavailable = false;
        Http.png = new byte[] {1, 2, 3};
        try (var c = Robolectric.buildActivity(ShareHistoryActivity.class, intent()).setup()) {
            drain(c.get());
            assertNull(((ImageView) field(c.get(), "image")).getDrawable());
            assertTrue(((Button) field(c.get(), "retry")).isEnabled());
        }
    }
    @Test
    public void revokeRequiresConfirmationAndAccountSwitchBlocksLateImages() throws Exception {
        try (var c = Robolectric.buildActivity(ShareHistoryActivity.class, intent()).setup()) {
            var a = c.get();
            drain(a);
            ((Button) field(a, "revoke")).performClick();
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_NEGATIVE)
                .performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, Http.deleted);
            ((Button) field(a, "revoke")).performClick();
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .performClick();
            drain(a);
            assertEquals(1, Http.deleted);
            assertTrue(a.isFinishing());
        }
        Http.afterImage = () -> {
            try {
                CaptureAccountSession.clear(context);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        };
        try (var c = Robolectric.buildActivity(ShareHistoryActivity.class, intent()).setup()) {
            drain(c.get());
            assertNull(((ImageView) field(c.get(), "image")).getDrawable());
            c.pause().resume();
            assertTrue(c.get().isFinishing());
        }
    }
}
