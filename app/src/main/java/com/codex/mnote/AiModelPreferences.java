package com.codex.mnote;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.UUID;

/** Account-scoped local BYOK profiles. Secrets are encrypted by Android Keystore, never synced. */
final class AiModelPreferences {
    private AiModelPreferences() { }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("mnote_ai_profiles_" + CaptureAccountSession.scope(context), Context.MODE_PRIVATE);
    }
    static String validateBaseUrl(String value) {
        try {
            String result = CaptureSyncPreferences.validateAndNormalizeBaseUrl(value);
            if(result.length()>2048) throw new IllegalArgumentException("invalid_endpoint");
            URI uri = URI.create(result);
            if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException("https_required");
            if (uri.getRawPath().contains("%") || uri.getPath().contains("..")) throw new IllegalArgumentException("invalid_endpoint");
            if (result.endsWith("/chat/completions")) result = result.substring(0, result.length() - 17);
            return result;
        } catch (RuntimeException error) { throw new IllegalArgumentException("invalid_endpoint"); }
    }
    static JSONArray list(Context context) throws Exception {
        synchronized (CaptureAccountSession.LOCK) {
            JSONObject profiles = new JSONObject(prefs(context).getString("profiles", "{}"));
            JSONArray result = new JSONArray();
            java.util.Iterator<String> ids = profiles.keys();
            while (ids.hasNext()) {
                String id = ids.next(); JSONObject value = new JSONObject(profiles.getJSONObject(id).toString());
                value.remove("ciphertext"); value.remove("iv");
                value.put("has_key", true).put("is_default", id.equals(defaultId(context)));
                result.put(value);
            }
            return result;
        }
    }
    static String defaultId(Context context) { return prefs(context).getString("default", ""); }
    static String save(Context context, String id, String label, String baseUrl, String model,
                       String key, boolean vision, boolean makeDefault) throws Exception {
        synchronized (CaptureAccountSession.LOCK) {
            String base = validateBaseUrl(baseUrl);
            if (model == null || model.trim().isEmpty() || model.length() > 200 || model.matches(".*[\\r\\n].*"))
                throw new IllegalArgumentException("invalid_model");
            if (label == null || label.trim().isEmpty() || label.length() > 100) throw new IllegalArgumentException("invalid_label");
            if (id == null || id.isEmpty()) id = UUID.randomUUID().toString();
            UUID.fromString(id);
            JSONObject profiles = new JSONObject(prefs(context).getString("profiles", "{}"));
            JSONObject previous = profiles.optJSONObject(id);
            JSONObject value = new JSONObject().put("id", id).put("label", label.trim())
                    .put("base_url", base).put("model", model.trim()).put("vision", vision);
            if (key == null || key.trim().isEmpty()) {
                if (previous == null) throw new IllegalArgumentException("api_key_required");
                if (!base.equals(previous.optString("base_url"))) throw new IllegalArgumentException("endpoint_changed_reenter_key");
                value.put("ciphertext", previous.getString("ciphertext")).put("iv", previous.getString("iv"));
            } else {
                String secret = key.trim();
                if (secret.length() > 4096 || !secret.matches("[!-~]+")) throw new IllegalArgumentException("invalid_api_key");
                CaptureSyncPreferences.EncryptedValue encrypted = encrypt(secret);
                value.put("ciphertext", encrypted.ciphertext).put("iv", encrypted.iv);
            }
            profiles.put(id, value);
            SharedPreferences.Editor editor = prefs(context).edit().putString("profiles", profiles.toString());
            if (makeDefault || defaultId(context).isEmpty()) editor.putString("default", id);
            if (!editor.commit()) throw new java.io.IOException("profile_save_failed");
            return id;
        }
    }
    static void delete(Context context, String id) throws Exception {
        synchronized (CaptureAccountSession.LOCK) {
            JSONObject values = new JSONObject(prefs(context).getString("profiles", "{}")); values.remove(id);
            SharedPreferences.Editor editor = prefs(context).edit().putString("profiles", values.toString());
            if (id.equals(defaultId(context))) editor.putString("default", values.length() == 0 ? "" : values.keys().next());
            if (!editor.commit()) throw new java.io.IOException("profile_save_failed");
        }
    }
    static void setDefault(Context context, String id) throws Exception {
        synchronized (CaptureAccountSession.LOCK) {
            if (!new JSONObject(prefs(context).getString("profiles", "{}")).has(id)) throw new java.io.IOException("model_required");
            if (!prefs(context).edit().putString("default", id).commit()) throw new java.io.IOException("profile_save_failed");
        }
    }
    static Config load(Context context, String id) throws Exception {
        synchronized (CaptureAccountSession.LOCK) {
            JSONObject value = new JSONObject(prefs(context).getString("profiles", "{}")).optJSONObject(id == null ? defaultId(context) : id);
            if (value == null) throw new java.io.IOException("model_required");
            return new Config(value.getString("id"), value.getString("label"), validateBaseUrl(value.getString("base_url")),
                    value.getString("model"), decrypt(value.getString("ciphertext"), value.getString("iv")), value.optBoolean("vision"));
        }
    }
    static CaptureSyncPreferences.EncryptedValue encrypt(String secret) throws Exception {
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,key());
        return new CaptureSyncPreferences.EncryptedValue(android.util.Base64.encodeToString(cipher.doFinal(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP),
                android.util.Base64.encodeToString(cipher.getIV(),android.util.Base64.NO_WRAP));
    }
    static String decrypt(String value,String iv) throws Exception {
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,key(),new javax.crypto.spec.GCMParameterSpec(128,android.util.Base64.decode(iv,android.util.Base64.NO_WRAP)));
        return new String(cipher.doFinal(android.util.Base64.decode(value,android.util.Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
    }
    private static javax.crypto.SecretKey key() throws Exception {
        String alias="mnote.ai.byok.v1";
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        java.security.Key existing=store.getKey(alias,null);
        if(existing instanceof javax.crypto.SecretKey) return (javax.crypto.SecretKey)existing;
        javax.crypto.KeyGenerator generator=javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore");
        generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    static final class Config {
        final String id, label, baseUrl, model, apiKey;
        final boolean vision;
        Config(String id, String label, String baseUrl, String model, String apiKey, boolean vision) {
            this.id=id; this.label=label; this.baseUrl=baseUrl; this.model=model; this.apiKey=apiKey; this.vision=vision;
        }
        JSONObject metadata() throws Exception {
            return new JSONObject().put("label", label).put("base_url", baseUrl).put("model", model);
        }
    }
}
