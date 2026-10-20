package com.codex.mnote;

import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercises the production account snapshot and transport; only Android Keystore is substituted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35}, instrumentedPackages = "com.codex.mnote",
        shadows = VideoTranscriptClientTest.Crypto.class)
public class VideoTranscriptClientTest {
    private static final String BASE_A = "https://capture-a.example/mnote";
    private static final String BASE_B = "https://capture-b.example/other";
    private static final String SCOPE_A = scope(BASE_A, "a");
    private static final String SCOPE_B = scope(BASE_B, "b");
    private static final String TOKEN_A = "mns_test_only_account_a_credential";
    private static final String TOKEN_B = "mns_test_only_account_b_credential";
    private Context context;

    private static String scope(String base, String id) {
        return CaptureRemoteCache.digest((base + "\n" + id.repeat(32)).getBytes(StandardCharsets.UTF_8));
    }

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        Crypto.onDecrypt.set(null);
        account("a", SCOPE_A, BASE_A, TOKEN_A, System.currentTimeMillis() / 1000 + 3600);
    }

    @After public void clear() {
        Crypto.onDecrypt.set(null);
        CaptureAccountSession.preferences(context).edit().clear().commit();
    }

    private void account(String id, String scope, String base, String token, long expiry) {
        assertTrue(CaptureAccountSession.preferences(context).edit().clear()
                .putString("account_id", id.repeat(32)).putString("username", "test-" + id)
                .putString("scope", scope).putString("base_url", base)
                .putString("ciphertext", token).putString("iv", "test-only-iv")
                .putLong("expires_at", expiry).commit());
    }

    @Test public void configConnectionDoesNotWaitForAccountSyncLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread synchronizing = new Thread(() -> {
            synchronized (CaptureAccountSession.LOCK) {
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "test-account-sync-holds-lock");
        synchronizing.setDaemon(true);
        ExecutorService network = Executors.newSingleThreadExecutor();
        FakeConnection connection = new FakeConnection(200, "{\"enabled\":true}");
        try {
            synchronizing.start();
            assertTrue(locked.await(3, TimeUnit.SECONDS));
            Future<JSONObject> result = network.submit(() -> VideoTranscriptClient.request(
                    context, SCOPE_A, "GET", "/config", null, target -> connection));
            assertTrue("Transcript connection must not queue behind the long-running account sync",
                    result.get(3, TimeUnit.SECONDS).getBoolean("enabled"));
            assertTrue(connection.disconnected);
        } finally {
            release.countDown();
            synchronizing.join(3000);
            network.shutdownNow();
            assertTrue(network.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test public void sendsOnlyAccountCredentialToExactServerRouteWithBoundedTimeouts() throws Exception {
        FakeConnection connection = new FakeConnection(200, "{\"accepted\":true}");
        AtomicReference<URI> opened = new AtomicReference<>();
        JSONObject body = new JSONObject().put("share_text", "视频 https://v.douyin.com/test/");
        JSONObject result = VideoTranscriptClient.request(context, SCOPE_A, "POST", "/jobs", body,
                target -> { opened.set(target); return connection; });

        assertTrue(result.getBoolean("accepted"));
        assertEquals(BASE_A + "/v1/transcripts/jobs", opened.get().toString());
        assertEquals("POST", connection.getRequestMethod());
        assertEquals("Bearer " + TOKEN_A, connection.getRequestProperty("Authorization"));
        assertEquals("application/json", connection.getRequestProperty("Accept"));
        assertEquals("application/json", connection.getRequestProperty("Content-Type"));
        assertEquals("no-store", connection.getRequestProperty("Cache-Control"));
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.getConnectTimeout() > 0 && connection.getConnectTimeout() <= 45000);
        assertTrue(connection.getReadTimeout() > 0 && connection.getReadTimeout() <= 45000);
        assertEquals(body.toString(), connection.output.toString(StandardCharsets.UTF_8));
        assertEquals(body.toString().getBytes(StandardCharsets.UTF_8).length, connection.fixedLength());
        assertNull(connection.getRequestProperty("X-Owner-Id"));
        assertTrue(connection.disconnected);
    }

    @Test public void credentialsAndServerCannotBeMixedDuringAtomicAccountSwitch() throws Exception {
        Crypto.onDecrypt.set(() -> account("b", SCOPE_B, BASE_B, TOKEN_B,
                System.currentTimeMillis() / 1000 + 3600));
        FakeConnection connection = new FakeConnection(200, "{\"enabled\":true}");
        AtomicReference<URI> opened = new AtomicReference<>();
        Exception error = assertThrows(Exception.class, () -> VideoTranscriptClient.request(
                context, SCOPE_A, "GET", "/config", null, target -> {
                    opened.set(target);
                    return connection;
                }));

        assertTrue(error.getMessage(), error.getMessage().contains("account_changed"));
        if (opened.get() != null) {
            assertEquals("Use the same preference snapshot for destination and credential",
                    BASE_A + "/v1/transcripts/config", opened.get().toString());
            assertEquals("Bearer " + TOKEN_A, connection.getRequestProperty("Authorization"));
            assertTrue(connection.disconnected);
        }
        assertEquals(SCOPE_B, CaptureAccountSession.scope(context));
    }

    @Test public void expiredSessionAndChangedScopeNeverOpenNetworkConnection() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        VideoTranscriptClient.ConnectionFactory factory = target -> {
            connections.incrementAndGet();
            return new FakeConnection(200, "{}");
        };
        CaptureAccountSession.preferences(context).edit().putLong("expires_at", 0).commit();
        Exception expired = assertThrows(Exception.class, () -> VideoTranscriptClient.request(
                context, SCOPE_A, "GET", "/config", null, factory));
        assertTrue(VideoTranscriptClient.error(expired).contains("登录"));
        account("b", SCOPE_B, BASE_B, TOKEN_B, System.currentTimeMillis() / 1000 + 3600);
        Exception changed = assertThrows(Exception.class, () -> VideoTranscriptClient.request(
                context, SCOPE_A, "GET", "/config", null, factory));
        assertTrue(changed.getMessage().contains("account_changed"));
        assertEquals(0, connections.get());
    }

    @Test public void accountSwitchWhileResponseIsInFlightDiscardsTheResponse() throws Exception {
        FakeConnection connection = new FakeConnection(200, "{\"text\":\"private account A transcript\"}");
        connection.onResponse = () -> account("b", SCOPE_B, BASE_B, TOKEN_B,
                System.currentTimeMillis() / 1000 + 3600);
        Exception error = assertThrows(Exception.class, () -> VideoTranscriptClient.request(
                context, SCOPE_A, "GET", "/config", null, target -> connection));
        assertTrue(error.getMessage().contains("account_changed"));
        assertTrue(connection.disconnected);
    }

    @Test public void unauthorizedStatusMapsToLoginEvenWhenServerErrorCodeOmits401() throws Exception {
        FakeConnection connection = new FakeConnection(401, "{\"error\":{\"code\":\"unauthorized\"}}");
        VideoTranscriptClient.Rejected error = assertThrows(VideoTranscriptClient.Rejected.class,
                () -> VideoTranscriptClient.request(context, SCOPE_A, "GET", "/config", null,
                        target -> connection));
        assertEquals(401, error.status);
        assertTrue(VideoTranscriptClient.error(error).contains("登录"));
        assertTrue(connection.disconnected);
    }

    @Test public void redirectIsRejectedRatherThanForwardingCredentials() throws Exception {
        FakeConnection connection = new FakeConnection(302, "{}");
        AtomicInteger connections = new AtomicInteger();
        VideoTranscriptClient.Rejected error = assertThrows(VideoTranscriptClient.Rejected.class,
                () -> VideoTranscriptClient.request(context, SCOPE_A, "GET", "/config", null,
                        target -> { connections.incrementAndGet(); return connection; }));
        assertEquals(302, error.status);
        assertEquals(1, connections.get());
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.disconnected);
    }

    @Test public void invalidRouteAndPlainHttpNeverReceiveAccountCredential() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        VideoTranscriptClient.ConnectionFactory factory = target -> {
            connections.incrementAndGet();
            return new FakeConnection(200, "{}");
        };
        assertThrows(IOException.class, () -> VideoTranscriptClient.request(context, SCOPE_A,
                "GET", "/jobs/../../other?redirect=https://evil.example", null, factory));
        String httpBase = "http://127.0.0.1:8791";
        String httpScope = scope(httpBase, "a");
        account("a", httpScope, httpBase, TOKEN_A,
                System.currentTimeMillis() / 1000 + 3600);
        Exception error = assertThrows(Exception.class, () -> VideoTranscriptClient.request(
                context, httpScope, "GET", "/config", null, factory));
        assertTrue(error.getMessage().contains("https_required"));
        assertEquals(0, connections.get());
    }

    @Test public void oversizedResponseIsRejectedAndConnectionClosed() throws Exception {
        FakeConnection connection = new FakeConnection(200,
                "{\"text\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}");
        IOException error = assertThrows(IOException.class, () -> VideoTranscriptClient.request(
                context, SCOPE_A, "GET", "/config", null, target -> connection));
        assertEquals("response_too_large", error.getMessage());
        assertTrue(connection.disconnected);
    }

    @Test public void malformedResponseStillClosesConnection() throws Exception {
        FakeConnection connection = new FakeConnection(200, "not-json");
        assertThrows(Exception.class, () -> VideoTranscriptClient.request(context, SCOPE_A,
                "GET", "/config", null, target -> connection));
        assertTrue(connection.disconnected);
    }

    @Test public void wholeRequestTimeoutDoesNotWaitForAnUnresponsiveNetworkWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(200, "{\"enabled\":true}");
        connection.onResponse = () -> {
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a system DNS/TLS call which cannot promptly respond to interruption.
                }
            }
        };
        try {
            long began = System.nanoTime();
            IOException error = assertThrows(IOException.class, () -> VideoTranscriptClient.request(
                    context, SCOPE_A, "GET", "/config", null, target -> connection, 200));
            assertTrue("Caller must return without waiting for stuck network I/O",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(error.getMessage(), error.getMessage().contains("timeout"));
        } finally {
            release.countDown();
            assertTrue("Late worker must close its connection", connection.closed.await(3, TimeUnit.SECONDS));
        }
    }

    @Implements(CaptureSyncPreferences.class) public static class Crypto {
        static final AtomicReference<Runnable> onDecrypt = new AtomicReference<>();
        @Implementation protected static String decryptToken(String ciphertext, String iv) {
            Runnable hook = onDecrypt.getAndSet(null);
            if (hook != null) hook.run();
            return ciphertext;
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final int status;
        final byte[] response;
        Runnable onResponse;
        boolean disconnected;
        final CountDownLatch closed = new CountDownLatch(1);

        FakeConnection(int status, String response) throws IOException {
            super(URI.create("https://capture-a.example/").toURL());
            this.status = status;
            this.response = response.getBytes(StandardCharsets.UTF_8);
        }

        @Override public int getResponseCode() {
            if (onResponse != null) onResponse.run();
            return status;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void disconnect() { disconnected = true; closed.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        int fixedLength() { return fixedContentLength; }
    }
}
