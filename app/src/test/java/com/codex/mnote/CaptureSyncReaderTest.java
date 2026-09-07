package com.codex.mnote;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class CaptureSyncReaderTest {
    private byte[] response(String raw, int limit) throws Exception {
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> served = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));
                    assertEquals("GET /v1/changes?after=7&limit=50 HTTP/1.1",reader.readLine());
                    String header; boolean authorized = false;
                    while (!(header = reader.readLine()).isEmpty())
                        if (header.equals("Authorization: Bearer test-only-credential")) authorized = true;
                    assertTrue(authorized);
                    socket.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));
                } catch (IOException error) { throw new UncheckedIOException(error); }
            });
            try {
                CaptureSyncReader.Transport client = CaptureSyncReader.forConfig(new CaptureSyncPreferences.Config(
                        "http://127.0.0.1:"+server.getLocalPort(),"test-only-credential","deny"));
                return client.get("/v1/changes?after=7&limit=50",limit);
            } finally { served.get(5,TimeUnit.SECONDS); executor.shutdownNow(); }
        }
    }
    @Test public void sendsAuthorizedGetWithCursor() throws Exception {
        assertEquals("{}",new String(response("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}",20),StandardCharsets.UTF_8));
    }
    @Test public void rejectsRedirectsWithoutFollowingThem() {
        IOException error = assertThrows(IOException.class,() -> response("HTTP/1.1 302 Found\r\nLocation: https://untrusted.example/\r\n\r\n",20));
        assertEquals("http_302",error.getMessage());
    }
    @Test public void rejectsTruncatedAndOversizedBodies() {
        assertThrows(IOException.class,() -> response("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n{}",20));
        assertThrows(IOException.class,() -> response("HTTP/1.1 200 OK\r\nContent-Length: 40\r\n\r\n{}",20));
        assertThrows(IOException.class,() -> response("HTTP/1.1 200 OK\r\n\r\n123456",4));
    }
    @Test public void blocksPublicCleartextAndInjectedPaths() {
        CaptureSyncReader.Transport client = CaptureSyncReader.forConfig(new CaptureSyncPreferences.Config("http://8.8.8.8","test-only","deny"));
        assertEquals("cleartext_host_blocked",assertThrows(IOException.class,() -> client.get("/v1/changes",100)).getMessage());
        assertThrows(IOException.class,() -> client.get("https://other.example",100));
        assertThrows(IOException.class,() -> client.get("/v1/changes\r\nInjected: true",100));
    }
}
