package com.codex.mnote;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Authenticated GETs. Never follows redirects or server-provided asset URLs. */
final class CaptureSyncReader {
    interface Transport { byte[] get(String path, int limit) throws IOException; }

    static Transport forConfig(CaptureSyncPreferences.Config config) {
        return (path, limit) -> get(config, path, limit);
    }

    private static byte[] get(CaptureSyncPreferences.Config config, String path, int limit) throws IOException {
        if (!path.startsWith("/v1/") || path.contains("\r") || path.contains("\n")) throw new IOException("invalid_endpoint");
        URI uri = URI.create(config.baseUrl + path);
        if ("http".equals(uri.getScheme())) return privateHttp(uri, config.writeToken, limit);
        if (!"https".equals(uri.getScheme())) throw new IOException("https_required");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + config.writeToken);
            connection.setRequestProperty("Accept", "application/json, image/png, image/jpeg");
            connection.setRequestProperty("Cache-Control", "no-store");
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("http_" + status);
            try (InputStream input = connection.getInputStream()) { return read(input, limit, -1); }
        } finally { connection.disconnect(); }
    }

    private static byte[] privateHttp(URI uri, String token, int limit) throws IOException {
        String host = uri.getHost();
        if (!CaptureSyncPreferences.isPrivateOrLoopbackLiteral(host)) throw new IOException("cleartext_host_blocked");
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        int port = uri.getPort() < 0 ? 80 : uri.getPort();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 15_000);
            socket.setSoTimeout(30_000);
            String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            String authority = host.contains(":") ? "[" + host + "]" : host;
            String headers = "GET " + target + " HTTP/1.1\r\nHost: " + authority + ":" + port
                    + "\r\nAuthorization: Bearer " + token + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String[] status = line(input).split(" ", 3);
            if (status.length < 2 || !status[0].startsWith("HTTP/") || !"200".equals(status[1]))
                throw new IOException(status.length > 1 && status[1].matches("[0-9]{3}") ? "http_" + status[1] : "invalid_response");
            int length = -1;
            int total = 0;
            while (true) {
                String header = line(input);
                if (header.isEmpty()) break;
                total += header.length();
                if (total > 32_768) throw new IOException("headers_too_large");
                int colon = header.indexOf(':');
                if (colon < 0) throw new IOException("invalid_header");
                String name = header.substring(0, colon).trim();
                String value = header.substring(colon + 1).trim();
                if ("Transfer-Encoding".equalsIgnoreCase(name)) throw new IOException("unsupported_transfer_encoding");
                if ("Content-Length".equalsIgnoreCase(name)) {
                    if (length >= 0) throw new IOException("duplicate_length");
                    try { length = Integer.parseInt(value); } catch (NumberFormatException error) { throw new IOException("invalid_length"); }
                    if (length < 0 || length > limit) throw new IOException("response_too_large");
                }
            }
            return read(input, limit, length);
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int count = 0; count < 8192; count++) {
            int value = input.read();
            if (value < 0) throw new EOFException("truncated_response");
            if (value == '\n') return output.toString("ISO-8859-1");
            if (value != '\r') output.write(value);
        }
        throw new IOException("header_too_large");
    }

    private static byte[] read(InputStream input, int limit, int expected) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (expected < 0 || output.size() < expected) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
            int read = input.read(buffer, 0, expected < 0 ? buffer.length : Math.min(buffer.length, expected - output.size()));
            if (read < 0) break;
            if (output.size() + read > limit) throw new IOException("response_too_large");
            output.write(buffer, 0, read);
        }
        if (expected >= 0 && output.size() != expected) throw new IOException("truncated_response");
        return output.toByteArray();
    }
}
