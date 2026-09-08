package com.codex.mnote;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Explicit copy-only import. Guests are never silently assigned to the next logged-in account. */
final class CaptureAccountImport {
    static int run(Context context) throws Exception {
        synchronized (CaptureAccountSession.LOCK) {
            if (!CaptureAccountSession.hasAccount(context)) throw new IOException("login_required");
            File root = new File(context.getFilesDir(), "capture_inbox");
            File[] directories = root.listFiles(); int imported = 0;
            if (directories == null) return 0;
            File destination = new File(context.getFilesDir(), "account_inbox/" + CaptureAccountSession.scope(context));
            AtomicFile guestDeletes = new AtomicFile(new File(context.getFilesDir(), "deleted-guest.json"));
            JSONObject deleted = guestDeletes.getBaseFile().exists()
                    ? new JSONObject(new String(guestDeletes.readFully(), StandardCharsets.UTF_8)) : new JSONObject();
            java.util.Set<String> accountDeleted = CaptureDeletionStore.hidden(context);
            for (File directory : directories) {
                CaptureStore.CaptureRecord record = CaptureStore.readRecord(directory);
                if (record == null || !record.id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || record.id.contains("..")) continue;
                if (deleted.has(record.id) || accountDeleted.contains(record.id)) continue;
                File target = new File(destination, record.id);
                if (CaptureStore.readRecord(target) != null) continue;
                if (!target.isDirectory() && !target.mkdirs()) throw new IOException("storage_unavailable");
                for (String name : new String[]{"original.png", "annotated.png"}) {
                    File source = new File(directory,name);
                    if (source.isFile()) write(new File(target,name), Files.readAllBytes(source.toPath()));
                }
                JSONObject metadata = new JSONObject(new String(Files.readAllBytes(record.metadataFile.toPath()),StandardCharsets.UTF_8));
                metadata.put("syncState", CaptureStore.SYNC_PENDING);
                write(new File(target,"record.json"),metadata.toString().getBytes(StandardCharsets.UTF_8));
                imported++;
            }
            return imported;
        }
    }
    private static void write(File target, byte[] data) throws IOException {
        AtomicFile file = new AtomicFile(target); FileOutputStream output = null;
        try { output=file.startWrite(); output.write(data); file.finishWrite(output); }
        catch (IOException error) { file.failWrite(output); throw error; }
    }
}
