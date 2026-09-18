package com.codex.mnote;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.*;
import android.os.Looper;
import android.widget.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {30, 35}, instrumentedPackages = "com.codex.mnote",
    shadows = {CaptureAccountTest.Crypto.class, CaptureAccountTest.Scheduler.class,
        ShareTextActivityTest.Http.class})
public class ShareTextActivityTest {
    @Implements(value = CaptureAccountHttp.class, isInAndroidSdk = false)
    public static class Http {
        static String text;
        static boolean fail, logout;
        @Implementation
        protected static JSONObject request(String base, String method, String path, String token,
            JSONObject body, Integer revision) throws Exception {
            assertEquals("GET", method);
            assertEquals("/v1/exports/"
                    + "e".repeat(32) + "/text",
                path);
            if (fail)
                throw new java.io.IOException("http_404");
            if (logout)
                CaptureAccountSession.clear(RuntimeEnvironment.getApplication());
            return new JSONObject().put("text", text);
        }
    }
    Context context;
    Intent intent;
    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        CaptureAccountSession.save(context, "https://example.test",
            new JSONObject()
                .put("account_id", "a".repeat(32))
                .put("username", "test")
                .put("access_token", "mns_test_only")
                .put("expires_at", 4102444800L));
        intent = new Intent(context, ShareTextActivity.class)
                     .putExtra("scope", CaptureAccountSession.scope(context))
                     .putExtra("export_id", "e".repeat(32));
        Http.text =
            "记录 1\n我的想法\n留住自己的理解\n\n摘录\n触发灵感的句子\n\n原文与上下文\n原文内容";
        Http.fail = Http.logout = false;
    }
    void drain(ShareTextActivity a) throws Exception {
        ReflectionHelpers.<ExecutorService>getField(a, "worker")
            .submit(() -> {})
            .get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
    @Test
    public void fullTextIsSelectablePagedWithoutDroppingUnicode() throws Exception {
        Http.text = "前".repeat(11999) + "🌱"
            + "后".repeat(13000) + " END";
        try (var c = Robolectric.buildActivity(ShareTextActivity.class, intent).setup()) {
            var a = c.get();
            drain(a);
            StringBuilder result = new StringBuilder();
            TextView body = ReflectionHelpers.getField(a, "body");
            Button next = ReflectionHelpers.getField(a, "next");
            assertTrue(body.isTextSelectable());
            do {
                result.append(body.getText());
                if (!next.isEnabled())
                    break;
                next.performClick();
            } while (true);
            assertEquals(Http.text, result.toString());
            assertTrue(body.getText().toString().endsWith(" END"));
        }
    }
    @Test
    public void ownerGuardAndMissingLegacyText() throws Exception {
        Http.fail = true;
        try (var c = Robolectric.buildActivity(ShareTextActivity.class, intent).setup()) {
            drain(c.get());
            assertTrue(ReflectionHelpers.<TextView>getField(c.get(), "status")
                    .getText()
                    .toString()
                    .contains("旧分享"));
        }
        Http.fail = false;
        Http.logout = true;
        try (var c = Robolectric.buildActivity(ShareTextActivity.class, intent).setup()) {
            drain(c.get());
            assertEquals(
                "", ReflectionHelpers.<TextView>getField(c.get(), "body").getText().toString());
            c.pause().resume();
            assertTrue(c.get().isFinishing());
        }
    }
    @Test
    public void rendersTextSnapshot() throws Exception {
        try (var c = Robolectric.buildActivity(ShareTextActivity.class, intent).setup()) {
            drain(c.get());
            SettingsActivityTest.render(
                SettingsActivityTest.layout(c.get(), 390, 844), "share-text-preview.png");
        }
    }
}
