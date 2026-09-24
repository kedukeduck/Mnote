package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.io.*;
import java.time.Duration;
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
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = {30, 35}, qualifiers = "zh-rCN-w390dp-h844dp-mdpi",
    instrumentedPackages = "com.codex.mnote",
    shadows = {CaptureAccountTest.Crypto.class, CaptureAccountTest.Scheduler.class,
        ShareCardTest.Http.class, ShareCardTest.Album.class})
public class ShareCardTest {
    @Implements(value = CaptureAccountHttp.class, isInAndroidSdk = false)
    public static class Http {
        static int creates, revokes;
        static JSONObject body;
        static boolean fail, logout;
        @Implementation
        protected static JSONObject request(String base, String method, String path, String token,
            JSONObject data, Integer revision) throws Exception {
            if (method.equals("DELETE")) {
                revokes++;
                return new JSONObject();
            }
            assertEquals("/v1/exports/card", path);
            creates++;
            body = data;
            if (fail)
                throw new IOException("network");
            if (logout)
                CaptureAccountSession.clear(RuntimeEnvironment.getApplication());
            String shared = data.getString("token");
            String id = ShareCardActivity
                            .hex(java.security.MessageDigest.getInstance("SHA-256").digest(
                                shared.getBytes()))
                            .substring(0, 32);
            return new JSONObject().put("id", id).put("url", base + "/c/" + shared);
        }
    }
    @Implements(value = ShareCardAlbum.class, isInAndroidSdk = false)
    public static class Album {
        static Bitmap saved;
        static ShareCardRenderer.Document savedDocument;
        static boolean fail;
        @Implementation
        protected static android.net.Uri save(Context c, Bitmap bitmap) throws IOException {
            if (fail)
                throw new IOException("disk_full");
            saved = bitmap;
            return android.net.Uri.parse("content://media/1");
        }
        @Implementation
        protected static android.net.Uri save(Context c, ShareCardRenderer.Document document)
            throws IOException {
            if (fail)
                throw new IOException("disk_full");
            savedDocument = document;
            return android.net.Uri.parse("content://media/1");
        }
    }
    Context context;
    CaptureStore.CaptureRecord record;
    Intent intent;
    static final String QUOTE = "记录，不只是保存信息。\n也保留那些被触动的时刻。";
    static final String THOUGHT =
        "想把每周回顾变成习惯。\n不只看收藏了什么，\n也看看自己为什么会被触动。";
    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions("com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        CaptureAccountSession.save(context, "https://example.test",
            new JSONObject()
                .put("account_id", "a".repeat(32))
                .put("username", "test")
                .put("access_token", "mns_test_only")
                .put("expires_at", 4102444800L));
        record
        = CaptureStore.save(context, null, null, null, null, "thought", THOUGHT, "clipboard", QUOTE,
            "", "https://example.com/article", "manual", false,
            CaptureContext.text("完整原文仅允许二维码访问。", "user_supplied", QUOTE));
        CaptureStore.updateSyncState(context, record.id, CaptureStore.SYNC_SYNCED, "", 1);
        intent =
            new Intent(context, ShareCardActivity.class)
                .putExtra(CaptureRecordEditActivity.ID, record.id)
                .putExtra(CaptureRecordEditActivity.SCOPE, CaptureAccountSession.scope(context));
        Http.creates = Http.revokes = 0;
        Http.fail = Http.logout = false;
        Http.body = null;
        Album.saved = null;
        Album.savedDocument = null;
        Album.fail = false;
    }
    void drain(ShareCardActivity a) throws Exception {
        for (int i = 0; i < 3; i++) {
            ReflectionHelpers.<ExecutorService>getField(a, "worker")
                .submit(() -> {})
                .get(10, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120));
        }
    }
    CheckBox choice(ShareCardActivity a, int flag) {
        return a.<LinearLayout>findViewById(R.id.share_card_modules).findViewWithTag(flag);
    }
    @Test
    public void allModuleCombinationsFitAndRealQrDecodes() throws Exception {
        Bitmap image = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.LTGRAY);
        String url = "https://example.test/c/"
            + "a".repeat(64);
        Bitmap qr = ShareCardRenderer.qr(url);
        for (int mask = 1; mask < 64; mask++) {
            var r = ShareCardRenderer.render(QUOTE, THOUGHT, image, image, mask, qr);
            assertEquals("mask " + mask, "", r.error);
            assertEquals(1080, r.bitmap.getWidth());
            assertTrue(r.bitmap.getHeight() <= 1920);
            if (mask == 63) {
                int height = r.bitmap.getHeight();
                int[] pixels = new int[1080 * height];
                r.bitmap.getPixels(pixels, 0, 1080, 0, 0, 1080, height);
                String decoded = new MultiFormatReader()
                                     .decode(new BinaryBitmap(new HybridBinarizer(
                                         new RGBLuminanceSource(1080, height, pixels))))
                                     .getText();
                assertEquals(url, decoded);
            }
            r.bitmap.recycle();
        }
    }
    @Test
    public void longTextMissingImagesAndEmptySelectionFailExplicitly() {
        assertNull(ShareCardRenderer.render("x", "y", null, null, 0, null).bitmap);
        var longQuote = ShareCardRenderer.render("长文".repeat(5000), "", null, null, 1, null);
        assertEquals("", longQuote.error);
        assertTrue(longQuote.document.quoteTruncated);
        assertTrue(longQuote.document.displayedQuote.length() < 10000);
        longQuote.bitmap.recycle();
        assertTrue(ShareCardRenderer.render("", "", null, null, 4, null).error.contains("截图"));
    }
    @Test
    public void togglesAreLivePrivateAndSavedBitmapEqualsPreview() throws Exception {
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent).setup()) {
            var a = c.get();
            drain(a);
            Bitmap before = ReflectionHelpers.getField(a, "previewBitmap");
            assertNotNull(before);
            choice(a, 1).setChecked(false);
            choice(a, 1).setChecked(true);
            choice(a, 2).setChecked(false);
            drain(a);
            Bitmap after = ReflectionHelpers.getField(a, "previewBitmap");
            assertNotSame(before, after);
            assertEquals(0, Http.creates);
            a.findViewById(R.id.share_card_save).performClick();
            drain(a);
            assertSame(after, Album.saved);
            assertEquals(0, Http.creates);
            SettingsActivityTest.render(
                SettingsActivityTest.layout(a, 390, 844), "share-card-preview.png");
        }
    }
    @Test
    public void qrConsentCancelExactFieldsAndRepeatSaveDoesNotRepublish() throws Exception {
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent).setup()) {
            var a = c.get();
            drain(a);
            choice(a, 16).setChecked(true);
            drain(a);
            assertEquals(0, Http.creates);
            a.findViewById(R.id.share_card_save).performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(-2).performClick();
            drain(a);
            assertEquals(0, Http.creates);
            Bitmap image = ReflectionHelpers.getField(a, "previewBitmap");
            a.findViewById(R.id.share_card_save).performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(-1).performClick();
            drain(a);
            assertEquals(1, Http.creates);
            assertSame(image, Album.saved);
            assertEquals("[\"original\"]", Http.body.getJSONArray("fields").toString());
            a.findViewById(R.id.share_card_save).performClick();
            drain(a);
            assertEquals(1, Http.creates);
            String token = ReflectionHelpers.getField(a, "token");
            choice(a, 32).setChecked(true);
            drain(a);
            assertNotEquals(token, ReflectionHelpers.getField(a, "token"));
            assertEquals(1, Http.creates);
        }
    }
    @Test
    public void failureRevokesOnlyNewShareAndAccountChangeNeverSaves() throws Exception {
        for (boolean logout : new boolean[] {false, true}) {
            Http.logout = logout;
            Album.fail = !logout;
            try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent).setup()) {
                var a = c.get();
                drain(a);
                choice(a, 16).setChecked(true);
                drain(a);
                a.findViewById(R.id.share_card_save).performClick();
                ShadowAlertDialog.getLatestAlertDialog().getButton(-1).performClick();
                drain(a);
                assertNull(Album.saved);
            }
        }
        assertEquals(2, Http.creates);
        assertEquals(2, Http.revokes);
    }
    @Test
    public void staleRecordCannotPublishAndSavedStateKeepsSelection() throws Exception {
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent).setup()) {
            var a = c.get();
            drain(a);
            choice(a, 16).setChecked(true);
            drain(a);
            record
            = CaptureStore.find(context, record.id);
            CaptureRecordEdits.save(context, CaptureAccountSession.scope(context), record.id,
                CaptureRecordEdits.fingerprint(record), "changed", QUOTE,
                CaptureRecordEdits.original(record));
            a.findViewById(R.id.share_card_save).performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(-1).performClick();
            drain(a);
            assertEquals(0, Http.creates);
            assertNull(Album.saved);
        }
    }
    @Test
    public void imageRecordHasOneScreenCompositionAndSelectableAnnotation() throws Exception {
        Bitmap full;
        try (var c = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            var root = SettingsActivityTest.layout(c.get(), 390, 844);
            full = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(full));
        }
        Bitmap crop = Bitmap.createBitmap(full, 22, 210, 346, 110);
        JSONObject layer = new JSONObject()
                               .put("sourceWidth", 390)
                               .put("sourceHeight", 844)
                               .put("selection",
                                   new JSONObject()
                                       .put("left", 22)
                                       .put("top", 210)
                                       .put("right", 368)
                                       .put("bottom", 320));
        var imageRecord = CaptureStore.save(context, CaptureStore.writeDraftBitmap(context, full),
            crop, crop, layer, "thought", THOUGHT, "screen_capture", QUOTE, "",
            "https://example.com/article", "manual", true,
            CaptureContext.text("保存的原文", "user_supplied", QUOTE));
        CaptureStore.updateSyncState(context, imageRecord.id, CaptureStore.SYNC_SYNCED, "", 1);
        intent.putExtra(CaptureRecordEditActivity.ID, imageRecord.id);
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent).setup()) {
            var a = c.get();
            drain(a);
            choice(a, 8).setChecked(true);
            choice(a, 16).setChecked(true);
            choice(a, 32).setChecked(true);
            drain(a);
            Bitmap finalImage = ReflectionHelpers.getField(a, "previewBitmap");
            assertNotNull(finalImage);
            File dir = new File("build/ui-previews");
            dir.mkdirs();
            try (OutputStream out = new FileOutputStream(new File(dir, "share-card-full.png"))) {
                finalImage.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            SettingsActivityTest.render(
                SettingsActivityTest.layout(a, 390, 844), "share-card-full-preview.png");
            a.findViewById(R.id.share_card_adjust_context).performClick();
            var dialog = ShadowAlertDialog.getLatestAlertDialog();
            SeekBar position = dialog.findViewById(R.id.share_card_context_position);
            assertNotNull(position);
            position.setProgress(1000);
            shadowOf(position).getOnSeekBarChangeListener().onProgressChanged(position, 1000, true);
            drain(a);
            ShareCardRenderer.Document moved = ReflectionHelpers.getField(a, "previewDocument");
            assertEquals(1f, moved.contextPosition, 0);
            assertFalse(finalImage.sameAs(ReflectionHelpers.getField(a, "previewBitmap")));
            Bundle savedState = new Bundle();
            c.saveInstanceState(savedState);
            assertEquals(1000, savedState.getInt("page_position"));
            var root = dialog.getWindow().getDecorView();
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                             390, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    844, android.view.View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
            SettingsActivityTest.render(root, "share-card-context-adjust.png");
            dialog.dismiss();
            choice(a, 64).setChecked(false);
            drain(a);
            assertNotNull(ReflectionHelpers.getField(a, "previewBitmap"));
        }
    }
    @Test
    public void restoringSavingStateDoesNotPublishAgain() {
        Bundle b = new Bundle();
        b.putBoolean("saving", true);
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent)
                 .create(b)
                 .start()
                 .resume()) {
            assertTrue(c.get().isFinishing());
            assertEquals(0, Http.creates);
        }
    }
    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h568dp-mdpi")
    public void smallPhoneKeepsPreviewAndSaveVisible() throws Exception {
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent).setup()) {
            var a = c.get();
            drain(a);
            choice(a, 16).setChecked(true);
            drain(a);
            var root = SettingsActivityTest.layout(a, 320, 568);
            assertTrue(a.findViewById(R.id.share_card_preview).getHeight() >= 150);
            assertTrue(a.findViewById(R.id.share_card_save).getBottom() <= root.getHeight());
            SettingsActivityTest.render(root, "share-card-small-preview.png");
        }
    }
    @Test
    public void recreationKeepsSelectionAndTokenWithoutPublishing() throws Exception {
        Bundle saved = new Bundle();
        String token;
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent).setup()) {
            var a = c.get();
            drain(a);
            choice(a, 1).setChecked(false);
            choice(a, 16).setChecked(true);
            drain(a);
            token = ReflectionHelpers.getField(a, "token");
            c.saveInstanceState(saved);
        }
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent)
                 .create(saved)
                 .start()
                 .resume()
                 .visible()) {
            var a = c.get();
            drain(a);
            assertFalse(choice(a, 1).isChecked());
            assertTrue(choice(a, 16).isChecked());
            assertEquals(token, ReflectionHelpers.getField(a, "token"));
            assertEquals(0, Http.creates);
        }
    }
    @Test
    public void unsyncedRecordAllowsPrivateCardButDisablesPublicQr() throws Exception {
        CaptureStore.updateSyncState(context, record.id, CaptureStore.SYNC_PENDING, "", 0);
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent).setup()) {
            var a = c.get();
            drain(a);
            assertFalse(choice(a, 16).isEnabled());
            assertFalse(choice(a, 32).isEnabled());
            a.findViewById(R.id.share_card_save).performClick();
            drain(a);
            assertNotNull(Album.saved);
            assertEquals(0, Http.creates);
        }
    }
    @Test
    public void tallThoughtUsesScrollableReaderAndExportsFullDocument() throws Exception {
        String thought = ("完整保留我的想法，不应被删节。\n").repeat(80) + "最后一句也必须保留。";
        record
        = CaptureStore.save(context, null, null, null, null, "thought", thought, "clipboard", QUOTE,
            "", "", "", false, new JSONObject());
        intent.putExtra(CaptureRecordEditActivity.ID, record.id);
        try (var c = Robolectric.buildActivity(ShareCardActivity.class, intent).setup().visible()) {
            var a = c.get();
            drain(a);
            ShareCardRenderer.Document doc = ReflectionHelpers.getField(a, "previewDocument");
            assertEquals(thought, doc.fullThought);
            assertTrue(doc.height > ShareCardRenderer.PREVIEW_HEIGHT);
            a.findViewById(R.id.share_card_preview).performClick();
            drain(a);
            var dialog = ShadowAlertDialog.getLatestAlertDialog();
            ScrollView reader = dialog.findViewById(R.id.share_card_reader);
            assertNotNull(reader);
            var window = dialog.getWindow().getDecorView();
            window.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                               390, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    844, android.view.View.MeasureSpec.EXACTLY));
            window.layout(0, 0, 390, 844);
            assertTrue(reader.getChildAt(0).getHeight() > 700);
            reader.scrollTo(0, reader.getChildAt(0).getHeight());
            assertTrue(reader.getScrollY() > 0);
            SettingsActivityTest.render(window, "share-card-long-reader.png");
            dialog.dismiss();
            a.findViewById(R.id.share_card_save).performClick();
            drain(a);
            assertSame(doc, Album.savedDocument);
            assertNull(Album.saved);
            assertEquals(0, Http.creates);
            assertEquals(thought, CaptureStore.find(context, record.id).comment);
        }
    }
}
