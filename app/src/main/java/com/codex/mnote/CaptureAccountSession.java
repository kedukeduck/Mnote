package com.codex.mnote;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;

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
    /**
     * Capture one coherent credential tuple without waiting for the record-sync
     * lock. SharedPreferences publishes save/clear atomically; separate getString
     * calls could otherwise combine one account's URL with another account's key.
     * Callers must still reject a changed scope before sending and after reading.
     */
    static CaptureSyncPreferences.Config snapshot(Context context,String expectedScope)
            throws GeneralSecurityException,java.io.IOException {
        Map<String,?> saved=preferences(context).getAll();
        Object scope=saved.get("scope"),id=saved.get("account_id"),expiry=saved.get("expires_at");
        if(!expectedScope.equals(scope==null?"guest":scope))throw new java.io.IOException("account_changed");
        if(!(id instanceof String)||!((String)id).matches("[a-f0-9]{32}")
                ||!(expiry instanceof Long)||((Long)expiry)<=System.currentTimeMillis()/1000)
            throw new GeneralSecurityException("login_required");
        Object base=saved.get("base_url"),cipher=saved.get("ciphertext"),iv=saved.get("iv");
        if(!(base instanceof String)||!(cipher instanceof String)||!(iv instanceof String))
            throw new GeneralSecurityException("invalid_session");
        String normalized=CaptureSyncPreferences.validateAndNormalizeBaseUrl((String)base);
        String boundScope=CaptureRemoteCache.digest((normalized+"\n"+id).getBytes(StandardCharsets.UTF_8));
        if(!boundScope.equals(expectedScope))throw new GeneralSecurityException("invalid_session");
        String token=CaptureSyncPreferences.decryptToken((String)cipher,(String)iv);
        CaptureSyncPreferences.validateToken(token);
        if(!token.startsWith("mns_"))throw new GeneralSecurityException("invalid_session");
        requireScope(context,expectedScope);
        return new CaptureSyncPreferences.Config(normalized,token,CaptureSyncPreferences.defaultAiAccess(context),expectedScope);
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
