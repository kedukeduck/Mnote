package com.codex.mnote;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Separate read-only remote cache: never overwrites or uploads local captures. */
final class CaptureRemoteCache {
    interface StillCurrent { boolean check(); }
    static final int PAGE_LIMIT = 50;
    static final int MAX_PAGES = 40;

    static String vault(CaptureSyncPreferences.Config config) {
        if (!config.accountKey.isEmpty()) return config.accountKey;
        return digest((config.baseUrl + "\n" + config.writeToken).getBytes(StandardCharsets.UTF_8));
    }

    static String digest(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder value = new StringBuilder();
            for (byte b : hash) value.append(String.format(Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    static List<CaptureStore.CaptureRecord> merged(Context context, List<CaptureStore.CaptureRecord> local) {
        if (CaptureAccountSession.hasAccount(context)) return merged(context, CaptureAccountSession.scope(context), local);
        try { return merged(context, vault(CaptureSyncPreferences.load(context)), local); }
        catch (Exception ignored) {
            List<CaptureStore.CaptureRecord> visible = new ArrayList<>(local);
            Set<String> hidden = CaptureDeletionStore.hidden(context);
            visible.removeIf(record -> hidden.contains(record.id));
            return visible;
        }
    }

    static List<CaptureStore.CaptureRecord> merged(Context context, String vault, List<CaptureStore.CaptureRecord> local) {
        Map<String, CaptureStore.CaptureRecord> result = new LinkedHashMap<>();
        // Unsent local edits always win. Synced account copies may show newer cloud revisions.
        for (CaptureStore.CaptureRecord record : local) result.put(record.id, record);
        try {
            File directory = directory(context, vault);
            JSONObject records = index(directory).optJSONObject("records");
            if (records != null) for (Iterator<String> it = records.keys(); it.hasNext();) {
                String id = it.next();
                JSONObject entry = records.getJSONObject(id);
                if (!validId(id) || entry.optBoolean("deleted")) continue;
                CaptureStore.CaptureRecord localRecord = result.get(id);
                if (localRecord != null && (!CaptureAccountSession.hasAccount(context)
                        || !CaptureStore.SYNC_SYNCED.equals(localRecord.syncState)
                        || localRecord.serverRevision > entry.getInt("revision"))) continue;
                File revision = new File(directory, id + "/" + entry.getInt("revision"));
                CaptureStore.CaptureRecord record = CaptureStore.readRecord(revision);
                if (record != null) result.put(id, record);
            }
        } catch (Exception ignored) { }
        List<CaptureStore.CaptureRecord> records = new ArrayList<>(result.values());
        Set<String> hidden = CaptureDeletionStore.hidden(context);
        if (CaptureAccountSession.hasAccount(context)) hidden.addAll(deletedIds(context, vault));
        records.removeIf(record -> hidden.contains(record.id));
        records.sort(Comparator.comparingLong((CaptureStore.CaptureRecord record) -> record.createdAt).reversed()
                .thenComparing(record -> record.id));
        return records;
    }

    static Set<String> deletedIds(Context context, String vault) {
        Set<String> deleted = new HashSet<>();
        try {
            JSONObject entries = index(directory(context, vault)).optJSONObject("records");
            if (entries != null) for (Iterator<String> it = entries.keys(); it.hasNext();) {
                String id = it.next(); if (entries.getJSONObject(id).optBoolean("deleted")) deleted.add(id);
            }
        } catch (Exception error) { throw new IllegalStateException("remote_state_unavailable", error); }
        return deleted;
    }

    static synchronized int pull(Context context, String vault, CaptureSyncReader.Transport remote, StillCurrent current) throws Exception {
        File directory = directory(context, vault);
        JSONObject index = index(directory);
        JSONObject records = index.optJSONObject("records");
        if (records == null) { records = new JSONObject(); index.put("records", records); }
        long after = index.optLong("cursor", 0);
        int updated = 0;
        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            requireCurrent(current);
            JSONObject page = new JSONObject(new String(remote.get("/v1/changes?after=" + after + "&limit=" + PAGE_LIMIT,
                    8 * 1024 * 1024), StandardCharsets.UTF_8));
            JSONArray changes = page.getJSONArray("changes");
            long previous = after;
            for (int i = 0; i < changes.length(); i++) {
                requireCurrent(current);
                JSONObject change = changes.getJSONObject(i);
                long sequence = change.getLong("sequence");
                if (sequence <= previous) throw new IOException("invalid_sequence");
                previous = sequence;
                String id = change.getString("capture_id");
                if (!validId(id)) throw new IOException("invalid_record_id");
                JSONObject record = change.optJSONObject("record");
                String operation = change.getString("operation");
                if ("delete".equals(operation) || "purge".equals(operation) || (record != null && record.optBoolean("deleted"))) {
                    JSONObject existing = records.optJSONObject(id);
                    if (existing != null && !existing.optBoolean("deleted")) updated++;
                    records.put(id, new JSONObject().put("deleted", true));
                } else if ("upsert".equals(operation) || "restore".equals(operation)) {
                    // A record purged after this historical change has no payload.
                    if (record == null) continue;
                    if (!id.equals(record.getString("id"))) throw new IOException("record_id_mismatch");
                    int revision = record.getInt("revision");
                    if (revision < 1) throw new IOException("invalid_revision");
                    JSONObject existing = records.optJSONObject(id);
                    if (existing == null || existing.optBoolean("deleted") || existing.optInt("revision") != revision) {
                        install(context, directory, record, remote);
                        records.put(id, new JSONObject().put("revision", revision));
                        updated++;
                    }
                    if ("restore".equals(operation)) CaptureDeletionStore.restored(context, vault, id);
                } else throw new IOException("invalid_operation");
            }
            long next = page.getLong("next_sequence");
            if (next != previous || (page.getBoolean("has_more") && next <= after)) throw new IOException("invalid_cursor");
            requireCurrent(current);
            index.put("cursor", next);
            atomic(new File(directory, "index.json"), index.toString().getBytes(StandardCharsets.UTF_8));
            after = next;
            if (!page.getBoolean("has_more")) return updated;
        }
        throw new IOException("more_records_pending"); // committed pages remain visible; next refresh continues
    }

    private static void install(Context context, File directory, JSONObject remote, CaptureSyncReader.Transport transport) throws Exception {
        String id = remote.getString("id");
        File target = new File(directory, id + "/" + remote.getInt("revision"));
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cache_unavailable");
        JSONObject assets = remote.optJSONObject("assets");
        boolean hasOriginal = assets != null && assets.has("original");
        boolean hasAnnotated = assets != null && assets.has("annotated");
        for (String role : new String[]{"original", "annotated"}) {
            if (assets == null || !assets.has(role)) continue;
            JSONObject asset = assets.getJSONObject(role);
            long size = asset.getLong("size");
            if (size < 1 || size > 16 * 1024 * 1024) throw new IOException("asset_too_large");
            String hash = asset.getString("sha256");
            if (!hash.matches("[a-f0-9]{64}")) throw new IOException("invalid_asset_hash");
            // Do not trust href: never send the vault credential to another host.
            byte[] bytes = transport.get("/v1/captures/" + id + "/assets/" + role, 16 * 1024 * 1024);
            if (bytes.length != size || !hash.equals(digest(bytes))) throw new IOException("asset_integrity_failed");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || (long) bounds.outWidth * bounds.outHeight > 64_000_000)
                throw new IOException("invalid_image");
            atomic(new File(target, role + ".png"), bytes);
        }
        if (hasOriginal && !hasAnnotated) atomic(new File(target, "annotated.png"), Files.readAllBytes(new File(target, "original.png").toPath()));
        if (hasAnnotated && !hasOriginal) atomic(new File(target, "original.png"), Files.readAllBytes(new File(target, "annotated.png").toPath()));
        JSONObject source = remote.optJSONObject("source");
        if (source == null) source = new JSONObject();
        String sourceType = source.optString("type", "import");
        if ("screen_capture".equals(sourceType)) sourceType = "screen";
        if ("shared_image".equals(sourceType)) sourceType = "share_image";
        JSONObject local = new JSONObject().put("id", id)
                .put("createdAt", Instant.parse(remote.getString("created_at")).toEpochMilli())
                .put("kind", remote.optString("kind", "comment")).put("comment", remote.optString("comment", ""))
                .put("sourceType", sourceType).put("sourceText", source.optString("text", ""))
                .put("sourcePackage", source.optString("app_id", ""))
                .put("sourceUrl", CaptureSourceUrl.clean(source.optString("url", "")))
                .put("sourceUrlOrigin", source.optString("url_origin", ""))
                .put("fidelityLevel", source.optString("fidelity_level", "L2"))
                .put("aiAccess", remote.optString("ai_access", "local_only"))
                .put("syncState", CaptureStore.SYNC_SYNCED).put("serverRevision", remote.getInt("revision"))
                .put("hasImage", hasOriginal || hasAnnotated)
                .put("annotationLayer", new JSONObject().put("strokes", remote.optJSONArray("annotations")));
        // Keep canonical metadata as well: OCR/evidence/unknown fields are not lost.
        atomic(new File(target, "remote.json"), remote.toString().getBytes(StandardCharsets.UTF_8));
        byte[] metadata = local.toString().getBytes(StandardCharsets.UTF_8);
        if (metadata.length > 512 * 1024) throw new IOException("record_too_large");
        atomic(new File(target, "record.json"), metadata);
    }

    private static void requireCurrent(StillCurrent current) throws IOException {
        if (Thread.currentThread().isInterrupted() || !current.check()) throw new IOException("configuration_changed");
    }
    private static boolean validId(String id) { return id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") && !id.contains(".."); }
    private static File directory(Context context, String vault) throws IOException {
        if (!vault.matches("[a-f0-9]{64}")) throw new IOException("invalid_vault");
        File directory = new File(context.getFilesDir(), "remote_captures/" + vault);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cache_unavailable");
        return directory;
    }
    private static JSONObject index(File directory) throws Exception {
        AtomicFile file = new AtomicFile(new File(directory, "index.json"));
        if (!file.getBaseFile().exists()) return new JSONObject();
        return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
    }
    private static void atomic(File file, byte[] bytes) throws IOException {
        AtomicFile target = new AtomicFile(file);
        FileOutputStream output = null;
        try { output = target.startWrite(); output.write(bytes); target.finishWrite(output); }
        catch (IOException error) { target.failWrite(output); throw error; }
    }
}
