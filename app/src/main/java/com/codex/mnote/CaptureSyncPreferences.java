package com.codex.mnote;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Capture Server configuration. The write credential is encrypted by Android Keystore. */
final class CaptureSyncPreferences {
    static final String AI_DENY = "deny";
    static final String AI_LOCAL_ONLY = "local_only";
    static final String AI_REMOTE_NO_MEMORY = "remote_no_memory";
    static final String AI_REMOTE_MEMORY = "remote_memory";

    private static final String PREFERENCES = "capture_sync_v1";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_TOKEN_CIPHERTEXT = "write_token_ciphertext";
    private static final String KEY_TOKEN_IV = "write_token_iv";
    private static final String KEY_AI_ACCESS = "default_ai_access";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEYSTORE_ALIAS = "mnote.capture.sync.write-token.v1";
    private static final int MAX_BASE_URL_CHARACTERS = 8_192;

    private CaptureSyncPreferences() {
    }

    static String configuredBaseUrl(Context context) {
        return preferences(context).getString(KEY_BASE_URL, "");
    }

    static boolean hasStoredToken(Context context) {
        SharedPreferences values = preferences(context);
        return !values.getString(KEY_TOKEN_CIPHERTEXT, "").isEmpty()
                && !values.getString(KEY_TOKEN_IV, "").isEmpty();
    }

    static String defaultAiAccess(Context context) {
        return cleanAiAccess(
                preferences(context).getString(KEY_AI_ACCESS, AI_LOCAL_ONLY)
        );
    }

    static String deviceId(Context context) {
        SharedPreferences values = preferences(context);
        String current = values.getString(KEY_DEVICE_ID, "");
        if (!current.isEmpty()) {
            return current;
        }
        String created = "android-" + UUID.randomUUID();
        values.edit().putString(KEY_DEVICE_ID, created).apply();
        return created;
    }

    static boolean isConfigured(Context context) {
        try {
            load(context);
            return true;
        } catch (IllegalArgumentException | GeneralSecurityException error) {
            return false;
        }
    }

    static Config load(Context context) throws GeneralSecurityException {
        SharedPreferences values = preferences(context);
        String baseUrl = validateAndNormalizeBaseUrl(
                values.getString(KEY_BASE_URL, "")
        );
        String token = decryptToken(
                values.getString(KEY_TOKEN_CIPHERTEXT, ""),
                values.getString(KEY_TOKEN_IV, "")
        );
        validateToken(token);
        return new Config(
                baseUrl,
                token,
                cleanAiAccess(values.getString(KEY_AI_ACCESS, AI_LOCAL_ONLY))
        );
    }

    /**
     * Saves a complete configuration. An empty suppliedToken retains the
     * previously encrypted credential, which is never returned to the UI.
     */
    static void save(
            Context context,
            String suppliedBaseUrl,
            String suppliedToken,
            String defaultAiAccess
    ) throws GeneralSecurityException {
        String baseUrl = validateAndNormalizeBaseUrl(suppliedBaseUrl);
        String aiAccess = requireAiAccess(defaultAiAccess);
        String token = suppliedToken == null ? "" : suppliedToken.trim();
        if (token.isEmpty()) {
            SharedPreferences existing = preferences(context);
            token = decryptToken(
                    existing.getString(KEY_TOKEN_CIPHERTEXT, ""),
                    existing.getString(KEY_TOKEN_IV, "")
            );
        }
        validateToken(token);
        EncryptedValue encrypted = encryptToken(token);
        boolean stored = preferences(context).edit()
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_TOKEN_CIPHERTEXT, encrypted.ciphertext)
                .putString(KEY_TOKEN_IV, encrypted.iv)
                .putString(KEY_AI_ACCESS, aiAccess)
                .commit();
        if (!stored) {
            throw new GeneralSecurityException("Cannot persist capture sync settings");
        }
    }

    /** Removes the optional server credential without touching capture data. */
    static void clearCredentials(Context context) throws GeneralSecurityException {
        boolean cleared = preferences(context).edit()
                .remove(KEY_BASE_URL)
                .remove(KEY_TOKEN_CIPHERTEXT)
                .remove(KEY_TOKEN_IV)
                .commit();
        if (!cleared) {
            throw new GeneralSecurityException("Cannot clear capture sync settings");
        }
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEYSTORE_ALIAS)) {
                store.deleteEntry(KEYSTORE_ALIAS);
            }
        } catch (java.io.IOException | GeneralSecurityException ignored) {
            // The ciphertext and URL are already durably removed. A stranded
            // AES key contains no server credential and can be replaced later.
        }
    }

    static String validateAndNormalizeBaseUrl(String supplied) {
        String value = supplied == null ? "" : supplied.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Capture Server URL is required");
        }
        if (value.length() > MAX_BASE_URL_CHARACTERS) {
            throw new IllegalArgumentException("Capture Server URL is too long");
        }
        final URI parsed;
        try {
            parsed = new URI(value).normalize();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Capture Server URL is invalid", error);
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Capture Server URL needs a host");
        }
        scheme = scheme.toLowerCase(Locale.US);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("Only HTTPS or private HTTP is supported");
        }
        if (parsed.getRawUserInfo() != null
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("URL cannot include credentials, query, or fragment");
        }
        int port = parsed.getPort();
        if (port == 0 || port > 65_535) {
            throw new IllegalArgumentException("Capture Server port is invalid");
        }
        if ("http".equals(scheme) && !isPrivateOrLoopbackLiteral(host)) {
            throw new IllegalArgumentException(
                    "HTTP is limited to localhost or a literal private-network address"
            );
        }
        String path = parsed.getPath();
        if (path == null || "/".equals(path)) {
            path = "";
        } else {
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }
        try {
            return new URI(scheme, null, host, port, path, null, null)
                    .toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Capture Server URL is invalid", error);
        }
    }

    static boolean isPrivateOrLoopbackLiteral(String suppliedHost) {
        String host = suppliedHost == null
                ? ""
                : suppliedHost.toLowerCase(Locale.US);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if ("localhost".equals(host)) {
            return true;
        }
        if (host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] parts = host.split("\\.");
            int[] octets = new int[4];
            for (int index = 0; index < parts.length; index++) {
                try {
                    octets[index] = Integer.parseInt(parts[index]);
                } catch (NumberFormatException error) {
                    return false;
                }
                if (octets[index] < 0 || octets[index] > 255) {
                    return false;
                }
            }
            return octets[0] == 10
                    || octets[0] == 127
                    || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                    || (octets[0] == 192 && octets[1] == 168)
                    || (octets[0] == 169 && octets[1] == 254);
        }
        if (!host.contains(":")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = address.getAddress();
            boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            return address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || uniqueLocal;
        } catch (Exception error) {
            return false;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    private static String requireAiAccess(String value) {
        String clean = cleanAiAccess(value);
        if (!clean.equals(value)) {
            throw new IllegalArgumentException("Unsupported AI access policy");
        }
        return clean;
    }

    static String cleanAiAccess(String value) {
        if (AI_DENY.equals(value)
                || AI_LOCAL_ONLY.equals(value)
                || AI_REMOTE_NO_MEMORY.equals(value)
                || AI_REMOTE_MEMORY.equals(value)) {
            return value;
        }
        return AI_LOCAL_ONLY;
    }

    static void validateToken(String token) {
        if (token == null || token.length() < 8 || token.length() > 512) {
            throw new IllegalArgumentException("Write Token must be 8 to 512 characters");
        }
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value < 0x21 || value > 0x7e) {
                throw new IllegalArgumentException(
                        "Write Token must contain printable ASCII without spaces"
                );
            }
        }
    }

    private static EncryptedValue encryptToken(String token)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new EncryptedValue(
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
        );
    }

    private static String decryptToken(String ciphertext, String iv)
            throws GeneralSecurityException {
        if (ciphertext == null || ciphertext.isEmpty() || iv == null || iv.isEmpty()) {
            throw new GeneralSecurityException("No encrypted write token is stored");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            );
            byte[] clear = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
            return new String(clear, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new GeneralSecurityException("Stored write token is invalid", error);
        }
    }

    private static SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
        } catch (java.io.IOException error) {
            throw new GeneralSecurityException("Cannot open Android Keystore", error);
        }
        java.security.Key existing = store.getKey(KEYSTORE_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    static final class Config {
        final String baseUrl;
        final String writeToken;
        final String defaultAiAccess;

        Config(String baseUrl, String writeToken, String defaultAiAccess) {
            this.baseUrl = baseUrl;
            this.writeToken = writeToken;
            this.defaultAiAccess = defaultAiAccess;
        }
    }

    private static final class EncryptedValue {
        final String ciphertext;
        final String iv;

        EncryptedValue(String ciphertext, String iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }
}
