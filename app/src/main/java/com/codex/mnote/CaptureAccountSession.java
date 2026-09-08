package com.codex.mnote;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** Credential changes and account-bound I/O share a lock, preventing cross-account writes. */
final class CaptureAccountSession {
    static final Object LOCK = new Object();
    static final String DEFAULT_SERVER = "https://chenyu.online/heartnote-capture";

    static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences("mnote_account_v1", Context.MODE_PRIVATE);
    }
    static boolean hasAccount(Context context) { return !preferences(context).getString("account_id", "").isEmpty(); }
    static String scope(Context context) {
        String scope = preferences(context).getString("scope", "guest");
        if (!scope.equals("guest") && !scope.matches("[a-f0-9]{64}")) throw new IllegalStateException("invalid_account_scope");
        return scope;
    }
    static String username(Context context) { return preferences(context).getString("username", ""); }
    static String baseUrl(Context context) { return preferences(context).getString("base_url", DEFAULT_SERVER); }
    static void requireScope(Context context, String expected) throws java.io.IOException {
        if (!scope(context).equals(expected)) throw new java.io.IOException("account_changed");
    }
    static CaptureSyncPreferences.Config config(Context context) throws GeneralSecurityException {
        SharedPreferences prefs = preferences(context);
        if (!hasAccount(context)) throw new GeneralSecurityException("login_required");
        if (prefs.getLong("expires_at", 0) <= System.currentTimeMillis() / 1000)
            throw new GeneralSecurityException("login_required");
        String token = CaptureSyncPreferences.decryptToken(prefs.getString("ciphertext", ""), prefs.getString("iv", ""));
        return new CaptureSyncPreferences.Config(prefs.getString("base_url", ""), token,
                CaptureSyncPreferences.defaultAiAccess(context), scope(context));
    }
    static void save(Context context, String baseUrl, JSONObject session) throws Exception {
        synchronized (LOCK) {
            String base = CaptureSyncPreferences.validateAndNormalizeBaseUrl(baseUrl);
            String id = session.getString("account_id");
            String token = session.getString("access_token");
            if (!id.matches("[a-f0-9]{32}") || !token.startsWith("mns_")) throw new GeneralSecurityException("invalid_session");
            CaptureSyncPreferences.validateToken(token);
            CaptureSyncPreferences.EncryptedValue encrypted = CaptureSyncPreferences.encryptToken(token);
            String scope = CaptureRemoteCache.digest((base + "\n" + id).getBytes(StandardCharsets.UTF_8));
            if (!preferences(context).edit().putString("account_id", id).putString("username", session.getString("username"))
                    .putString("base_url", base).putString("scope", scope)
                    .putString("ciphertext", encrypted.ciphertext).putString("iv", encrypted.iv)
                    .putString("sync_error", "").putLong("last_sync", 0)
                    .putLong("expires_at", session.getLong("expires_at")).commit()) throw new GeneralSecurityException("session_not_saved");
        }
    }
    static void clear(Context context) throws GeneralSecurityException {
        synchronized (LOCK) {
            if (!preferences(context).edit().clear().commit()) throw new GeneralSecurityException("logout_failed");
        }
    }
}
