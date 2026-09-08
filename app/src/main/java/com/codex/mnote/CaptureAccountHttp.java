package com.codex.mnote;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Account credentials only travel over HTTPS. No redirect or server-supplied target is followed. */
final class CaptureAccountHttp {
    static JSONObject request(String base, String method, String path, String token, JSONObject body, Integer revision) throws Exception {
        URI uri = URI.create(CaptureSyncPreferences.validateAndNormalizeBaseUrl(base) + path);
        if (!"https".equals(uri.getScheme()) || !path.startsWith("/v1/")) throw new IOException("https_required");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (revision != null) connection.setRequestProperty("If-Match", "revision:" + revision);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("http_" + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (output.size() + read > 8 * 1024 * 1024) throw new IOException("response_too_large");
                    output.write(buffer, 0, read);
                }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}
