package com.codex.mnote;

import android.content.Context;
import android.util.Base64;
import android.util.Base64OutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Streams one canonical V1 record and its two optional PNGs to Capture Server. */
final class CaptureSyncUploader {
    private static final long MAX_PAYLOAD_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_ASSET_BYTES = 16L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MILLIS = 20_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;
    private static final int RESPONSE_LIMIT_BYTES = 512 * 1024;

    private CaptureSyncUploader() {
    }

    static UploadResult upload(
            Context context,
            CaptureSyncPreferences.Config config,
            CaptureStore.CaptureRecord record
    ) throws UploadFailure {
        File payload = null;
        try {
            payload = createPayload(context, record);
            URI endpoint = URI.create(
                    config.baseUrl + "/v1/captures/" + record.id
            );
            if ("http".equals(endpoint.getScheme())) {
                return uploadPrivateHttp(endpoint, config.writeToken, payload);
            }
            return uploadHttps(endpoint.toURL(), config.writeToken, payload);
        } catch (UploadFailure error) {
            throw error;
        } catch (IOException | JSONException | RuntimeException error) {
            throw new UploadFailure("network_or_payload_io", true, error);
        } finally {
            if (payload != null) {
                payload.delete();
            }
        }
    }

    private static File createPayload(
            Context context,
            CaptureStore.CaptureRecord record
    ) throws IOException, JSONException, UploadFailure {
        checkAsset(record.originalFile);
        checkAsset(record.annotatedFile);
        File payload = File.createTempFile(
                "capture-sync-",
                ".json",
                context.getApplicationContext().getCacheDir()
        );
        boolean complete = false;
        try (FileOutputStream output = new FileOutputStream(payload, false)) {
            JSONObject metadata = metadata(context, record);
            String encoded = metadata.toString();
            writeUtf8(output, encoded.substring(0, encoded.length() - 1));
            writeUtf8(output, ",\"assets\":{");
            boolean wroteAsset = false;
            if (record.hasImage && record.originalFile != null) {
                writeAsset(output, "original", record.originalFile, false);
                wroteAsset = true;
            }
            if (record.hasImage && record.annotatedFile != null) {
                writeAsset(output, "annotated", record.annotatedFile, wroteAsset);
            }
            writeUtf8(output, "}}");
            output.getFD().sync();
            if (payload.length() > MAX_PAYLOAD_BYTES) {
                throw new UploadFailure("payload_too_large", false, null);
            }
            complete = true;
            return payload;
        } finally {
            if (!complete) {
                payload.delete();
            }
        }
    }

    private static JSONObject metadata(
            Context context,
            CaptureStore.CaptureRecord record
    ) throws JSONException {
        JSONObject source = new JSONObject()
                .put("type", acquisition(record.sourceType))
                .put("text", record.sourceText)
                .put("app_id", record.sourcePackage)
                .put("fidelity_level", record.fidelityLevel);
        JSONArray ocr = new JSONArray();
        JSONObject origin = new JSONObject()
                .put("device_id", CaptureSyncPreferences.deviceId(context))
                .put("local_id", record.id)
                .put("platform", "android");
        JSONObject evidence = new JSONObject()
                .put(
                        "exact_text",
                        record.sourceText.isEmpty()
                                ? JSONObject.NULL
                                : new JSONObject()
                                        .put("text", record.sourceText)
                                        .put("delivered_by", acquisition(record.sourceType))
                )
                .put(
                        "ocr",
                        new JSONObject().put("status", "not_requested")
                );
        return new JSONObject()
                .put("schema_version", 1)
                .put("id", record.id)
                .put("created_at", isoUtc(record.createdAt))
                .put("kind", record.kind)
                .put("comment", record.comment)
                .put("source", source)
                .put("ocr", ocr)
                .put("annotations", CaptureStore.annotationItems(record))
                .put("ai_access", record.aiAccess)
                .put("origin", origin)
                .put("evidence", evidence);
    }

    private static String acquisition(String sourceType) {
        if ("screen".equals(sourceType)) {
            return "screen_capture";
        }
        if ("share_image".equals(sourceType)) {
            return "shared_image";
        }
        return sourceType == null || sourceType.isEmpty() ? "import" : sourceType;
    }

    private static String isoUtc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                Locale.US
        );
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static void checkAsset(File asset) throws UploadFailure {
        if (asset == null) {
            return;
        }
        if (!asset.isFile() || asset.length() <= 0L) {
            throw new UploadFailure("asset_missing", false, null);
        }
        if (asset.length() > MAX_ASSET_BYTES) {
            throw new UploadFailure("asset_too_large", false, null);
        }
    }

    private static void writeAsset(
            OutputStream output,
            String role,
            File source,
            boolean prependComma
    ) throws IOException {
        writeUtf8(
                output,
                (prependComma ? "," : "")
                        + JSONObject.quote(role)
                        + ":{\"content_type\":\"image/png\",\"data_base64\":\""
        );
        try (
                InputStream input = new BufferedInputStream(new FileInputStream(source));
                Base64OutputStream base64 = new Base64OutputStream(
                        new NonClosingOutputStream(output),
                        Base64.NO_WRAP
                )
        ) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    base64.write(buffer, 0, read);
                }
            }
        }
        writeUtf8(output, "\"}");
    }

    private static UploadResult uploadHttps(
            URL endpoint,
            String token,
            File payload
    ) throws UploadFailure {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length());
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            try (
                    OutputStream output = new BufferedOutputStream(connection.getOutputStream());
                    InputStream input = new BufferedInputStream(new FileInputStream(payload))
            ) {
                copy(input, output);
            }
            int status = connection.getResponseCode();
            byte[] response = readResponse(connection, status);
            return evaluate(status, connection.getHeaderField("ETag"), response);
        } catch (IOException error) {
            throw new UploadFailure("network_io", true, error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Raw HTTP is intentionally used only after strict private/loopback URL
     * validation. This preserves the application's global cleartext deny rule.
     */
    private static UploadResult uploadPrivateHttp(
            URI endpoint,
            String token,
            File payload
    ) throws UploadFailure {
        String host = endpoint.getHost();
        if (!CaptureSyncPreferences.isPrivateOrLoopbackLiteral(host)) {
            throw new UploadFailure("cleartext_host_blocked", false, null);
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port = endpoint.getPort() < 0 ? 80 : endpoint.getPort();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            if (!isPrivateAddress(socket.getInetAddress())) {
                throw new UploadFailure("cleartext_address_blocked", false, null);
            }
            String path = endpoint.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String hostHeader = host.contains(":") ? "[" + host + "]" : host;
            if (port != 80) {
                hostHeader += ":" + port;
            }
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            try (InputStream payloadInput = new BufferedInputStream(new FileInputStream(payload))) {
                String headers = "PUT " + path + " HTTP/1.1\r\n"
                        + "Host: " + hostHeader + "\r\n"
                        + "Authorization: Bearer " + token + "\r\n"
                        + "Content-Type: application/json; charset=utf-8\r\n"
                        + "Accept: application/json\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Connection: close\r\n"
                        + "Content-Length: " + payload.length() + "\r\n\r\n";
                output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
                copy(payloadInput, output);
                output.flush();
            }
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String statusLine = readAsciiLine(input);
            int status = parseStatus(statusLine);
            String etag = null;
            int contentLength = 0;
            while (true) {
                String line = readAsciiLine(input);
                if (line.isEmpty()) {
                    break;
                }
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String name = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if ("ETag".equalsIgnoreCase(name)) {
                    etag = value;
                } else if ("Content-Length".equalsIgnoreCase(name)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        contentLength = 0;
                    }
                }
            }
            byte[] response = readLimited(input, Math.min(contentLength, RESPONSE_LIMIT_BYTES));
            return evaluate(status, etag, response);
        } catch (UploadFailure error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new UploadFailure("network_io", true, error);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // No credential or capture content is logged from cleanup failures.
            }
        }
    }

    private static boolean isPrivateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocal = address instanceof Inet6Address
                && bytes.length == 16
                && (bytes[0] & 0xfe) == 0xfc;
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || uniqueLocal;
    }

    private static UploadResult evaluate(int status, String etag, byte[] response)
            throws UploadFailure {
        if (status >= 200 && status < 300) {
            int revision = revisionFromEtag(etag);
            if (revision <= 0 && response.length > 0) {
                try {
                    revision = new JSONObject(
                            new String(response, StandardCharsets.UTF_8)
                    ).optInt("revision", 0);
                } catch (JSONException ignored) {
                    revision = 0;
                }
            }
            return new UploadResult(revision);
        }
        boolean retriable = status == 408
                || status == 425
                || status == 429
                || status >= 500;
        throw new UploadFailure("http_" + status, retriable, null);
    }

    private static int revisionFromEtag(String etag) {
        if (etag == null) {
            return 0;
        }
        String clean = etag.replace("\"", "");
        if (!clean.startsWith("revision:")) {
            return 0;
        }
        try {
            return Integer.parseInt(clean.substring("revision:".length()));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static byte[] readResponse(HttpURLConnection connection, int status)
            throws IOException {
        InputStream stream = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (stream == null) {
            return new byte[0];
        }
        try (InputStream input = stream) {
            return readLimited(input, RESPONSE_LIMIT_BYTES);
        }
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(Math.max(maximum, 0), 16 * 1024)
        );
        byte[] buffer = new byte[8 * 1024];
        int remaining = Math.max(0, maximum);
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            if (read > 0) {
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
        return output.toByteArray();
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() <= 8 * 1024) {
            int value = input.read();
            if (value < 0) {
                if (output.size() == 0) {
                    throw new IOException("Unexpected end of HTTP response");
                }
                break;
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                output.write(value);
            }
        }
        if (output.size() > 8 * 1024) {
            throw new IOException("HTTP response header is too long");
        }
        return output.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static int parseStatus(String line) throws IOException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("Invalid HTTP status line");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            throw new IOException("Invalid HTTP status code", error);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void writeUtf8(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    static final class UploadResult {
        final int revision;

        UploadResult(int revision) {
            this.revision = revision;
        }
    }

    static final class UploadFailure extends Exception {
        final String code;
        final boolean retriable;

        UploadFailure(String code, boolean retriable, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.retriable = retriable;
        }
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
