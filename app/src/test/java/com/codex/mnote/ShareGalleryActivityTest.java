package com.codex.mnote;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {30, 35}, instrumentedPackages = "com.codex.mnote",
    shadows = {CaptureAccountTest.Crypto.class, CaptureAccountTest.Scheduler.class,
        ShareGalleryActivityTest.Http.class})
public class ShareGalleryActivityTest {
    @Implements(value = CaptureAccountHttp.class, isInAndroidSdk = false)
    public static class Http {
        static int deleted, downloads;
        static boolean unavailable, empty;
        static Runnable afterImage;
        @Implementation
        protected static JSONObject request(String base, String method, String path, String token,
            JSONObject body, Integer revision) throws Exception {
            assertEquals("https://example.test", base);
            assertEquals("mns_test_only", token);
            if (method.equals("DELETE")) {
                deleted++;
                return new JSONObject();
            }
            assertEquals("GET", method);
            if (path.equals("/v1/exports")) {
                JSONArray shares = new JSONArray();
                if (!empty && deleted == 0)
                    shares.put(new JSONObject()
                            .put("id", "e".repeat(32))
                            .put("created", "2026-09-18T03:00:00Z")
                            .put("record_count", 1)
                            .put("image_count", 2));
                return new JSONObject().put("exports", shares);
            }
            assertEquals("/v1/exports/"
                    + "e".repeat(32),
                path);
            if (unavailable)
                throw new java.io.IOException("http_404");
            JSONArray images = new JSONArray();
            for (String role : new String[] {"context", "annotated"})
                images.put(new JSONObject()
                        .put("name", "1-" + role + ".png")
                        .put("record_index", 1)
                        .put("role", role)
                        .put("size", ShareHistoryActivityTest.Http.png.length));
            return new JSONObject().put("images", images);
        }
        @Implementation
        protected static byte[] image(String base, String path, String token) {
            downloads++;
            assertTrue(path.endsWith("1-annotated.png"));
            if (afterImage != null)
                afterImage.run();
            return ShareHistoryActivityTest.Http.png;
        }
    }
    @Before
    public void setup() throws Exception {
        new ShareHistoryActivityTest().setup();
        Http.deleted = Http.downloads = 0;
        Http.empty = Http.unavailable = false;
        Http.afterImage = null;
    }
    void drain(ShareGalleryActivity activity) throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
        for (int i = 0; i < 3; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }
    android.view.View card(ShareGalleryActivity activity) throws Exception {
        drain(activity);
        SettingsActivityTest.layout(activity, 390, 844);
        drain(activity);
        return ((ListView) ReflectionHelpers.getField(activity, "list")).getChildAt(0);
    }
    @Test
    public void galleryShowsRealCoverBeforeAnyClickAndOpensMatchingLargeImage() throws Exception {
        try (var c = Robolectric.buildActivity(ShareGalleryActivity.class).setup()) {
            var activity = c.get();
            var card = card(activity);
            ImageView photo = ReflectionHelpers.getField(card, "photo");
            assertNotNull(photo.getDrawable());
            assertEquals(1, Http.downloads);
            assertNull(shadowOf(activity).getNextStartedActivity());
            var root = SettingsActivityTest.layout(activity, 390, 844);
            drain(activity);
            SettingsActivityTest.render(root, "share-gallery-preview.png");
            photo.performClick();
            Intent intent = shadowOf(activity).getNextStartedActivity();
            assertEquals(
                ShareHistoryActivity.class.getName(), intent.getComponent().getClassName());
            assertEquals(1, intent.getIntExtra("image_index", -1));
            SettingsActivityTest.layout(activity, 320, 568);
            drain(activity);
            SettingsActivityTest.render(root, "share-gallery-small.png");
        }
    }
    @Test
    public void revokeIsSeparateAndCancelKeepsPreview() throws Exception {
        try (var c = Robolectric.buildActivity(ShareGalleryActivity.class).setup()) {
            var activity = c.get();
            var card = card(activity);
            Button remove = ReflectionHelpers.getField(card, "remove");
            remove.performClick();
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_NEGATIVE)
                .performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, Http.deleted);
            remove.performClick();
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .performClick();
            drain(activity);
            assertEquals(1, Http.deleted);
            assertEquals(0, ((ListView) ReflectionHelpers.getField(activity, "list")).getCount());
        }
    }
    @Test
    public void failureCanRetryAndAccountChangeRejectsLateCover() throws Exception {
        Http.unavailable = true;
        try (var c = Robolectric.buildActivity(ShareGalleryActivity.class).setup()) {
            var activity = c.get();
            var card = card(activity);
            TextView caption = ReflectionHelpers.getField(card, "caption");
            assertTrue(caption.getText().toString().contains("不可用"));
            Http.unavailable = false;
            caption.performClick();
            drain(activity);
            card = card(activity);
            assertNotNull(((ImageView) ReflectionHelpers.getField(card, "photo")).getDrawable());
        }
        Http.afterImage = () -> {
            try {
                CaptureAccountSession.clear(RuntimeEnvironment.getApplication());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        };
        try (var c = Robolectric.buildActivity(ShareGalleryActivity.class).setup()) {
            var card = card(c.get());
            // setImageBitmap(null) may retain an empty BitmapDrawable on Android.
            var drawable = ((ImageView) ReflectionHelpers.getField(card, "photo")).getDrawable();
            assertTrue(drawable == null
                || ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap() == null);
            assertEquals("guest", CaptureAccountSession.scope(c.get()));
            c.pause().resume();
            assertTrue(c.get().isFinishing());
        }
    }
    @Test
    public void emptyHistoryHasClearMessage() throws Exception {
        Http.empty = true;
        try (var c = Robolectric.buildActivity(ShareGalleryActivity.class).setup()) {
            drain(c.get());
            assertTrue(((TextView) ReflectionHelpers.getField(c.get(), "status"))
                    .getText()
                    .toString()
                    .contains("还没有"));
            assertEquals(0, Http.downloads);
        }
    }
}
