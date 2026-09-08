package com.codex.mnote;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable account-scoped delete outbox. Retained tombstones prevent accidental resurrection. */
final class CaptureDeletionStore {
    static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "deleted-" + CaptureAccountSession.scope(context) + ".json"));
    }
    static JSONObject read(Context context) throws Exception {
        AtomicFile file = file(context);
        if (!file.getBaseFile().exists()) return new JSONObject();
        return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
    }
    static Set<String> hidden(Context context) {
            try {
                Set<String> ids = new HashSet<>();
                for (Iterator<String> it = read(context).keys(); it.hasNext();) ids.add(it.next());
                return ids;
            } catch (Exception error) { throw new IllegalStateException("delete_state_unavailable", error); }
    }
    static void delete(Context context, String id) throws Exception {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || id.contains("..")) throw new IOException("invalid_id");
        synchronized (CaptureAccountSession.LOCK) {
            JSONObject data = read(context);
            data.put(id, CaptureAccountSession.hasAccount(context));
            write(context, data);
        }
        // The durable delete is already committed. Notification failures must not report a rollback.
        try { context.sendBroadcast(new android.content.Intent(CaptureStore.ACTION_RECORDS_CHANGED).setPackage(context.getPackageName())); }
        catch (RuntimeException ignored) { }
        try { CaptureAccountSync.enqueue(context); } catch (RuntimeException ignored) { }
    }
    static List<String> pending(Context context) throws Exception {
        List<String> result = new ArrayList<>(); JSONObject data = read(context);
        for (Iterator<String> it = data.keys(); it.hasNext();) { String id = it.next(); if (data.getBoolean(id)) result.add(id); }
        return result;
    }
    static void acknowledged(Context context, String id) throws Exception {
        JSONObject data = read(context); data.put(id, false); write(context, data);
    }
    static void restored(Context context, String vault, String id) throws Exception {
        if (!CaptureAccountSession.hasAccount(context) || !vault.equals(CaptureAccountSession.scope(context))) return;
        JSONObject data = read(context);
        if (data.has(id) && !data.getBoolean(id)) { data.remove(id); write(context,data); }
    }
    static void write(Context context, JSONObject data) throws IOException {
        AtomicFile file = file(context); FileOutputStream output = null;
        try { output = file.startWrite(); output.write(data.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(output); }
        catch (IOException error) { file.failWrite(output); throw error; }
    }
}
