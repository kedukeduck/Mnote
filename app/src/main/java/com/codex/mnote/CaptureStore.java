package com.codex.mnote;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** App-private, offline persistence for user-triggered captures. */
final class CaptureStore {
    static final String ORIGINAL_FILENAME = "original.png";
    static final String ANNOTATED_FILENAME = "annotated.png";
    static final String RECORD_FILENAME = "record.json";
    static final String SYNC_LOCAL_ONLY = "local_only";
    static final String SYNC_PENDING = "pending";
    static final String SYNC_FAILED = "failed";
    static final String SYNC_SYNCED = "synced";

    private static final String INBOX_DIRECTORY = "capture_inbox";
    private static final String DRAFT_DIRECTORY = "capture_drafts";
    private static final int BUFFER_BYTES = 16 * 1024;
    private static final int MAX_EDITOR_DIMENSION = 2560;
    private static final int MAX_THUMBNAIL_DIMENSION = 420;
    private static final long MAX_DRAFT_AGE_MILLIS = 24L * 60L * 60L * 1000L;
    private static final Object METADATA_LOCK = new Object();

    private CaptureStore() {
    }

    static File writeDraftBitmap(Context context, Bitmap bitmap) throws IOException {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            throw new IOException("Empty capture bitmap");
        }
        File directory = draftDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create capture draft directory");
        }
        File destination = new File(directory, UUID.randomUUID() + ".png");
        File partial = new File(directory, destination.getName() + ".part");
        boolean complete = false;
        try (FileOutputStream output = new FileOutputStream(partial, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Cannot encode capture bitmap");
            }
            output.getFD().sync();
            if (!partial.renameTo(destination)) {
                throw new IOException("Cannot finish capture draft");
            }
            complete = true;
            return destination;
        } finally {
            if (!complete) {
                partial.delete();
                destination.delete();
            }
        }
    }

    static File importImageDraft(Context context, Uri source) throws IOException {
        if (source == null) {
            throw new IOException("Shared image is missing");
        }
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) {
                throw new IOException("Shared image cannot be opened");
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Shared content is not a readable image");
        }
        int sample = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDITOR_DIMENSION);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) {
                throw new IOException("Shared image cannot be reopened");
            }
            bitmap = BitmapFactory.decodeStream(input, null, options);
        } catch (OutOfMemoryError error) {
            throw new IOException("Shared image is too large to import", error);
        }
        if (bitmap == null) {
            throw new IOException("Shared content is not a readable image");
        }
        try {
            return writeDraftBitmap(context, bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    static Bitmap decodeEditorBitmap(File source) {
        return decodeSampled(source, MAX_EDITOR_DIMENSION);
    }

    static Bitmap decodeThumbnail(File source) {
        return decodeSampled(source, MAX_THUMBNAIL_DIMENSION);
    }

    static Bitmap decodeReviewBitmap(File source) {
        return decodeSampled(source, 1600);
    }

    static File safeDraftFile(Context context, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            File directory = draftDirectory(context).getCanonicalFile();
            File candidate = new File(path).getCanonicalFile();
            File parent = candidate.getParentFile();
            if (parent == null
                    || !parent.equals(directory)
                    || !candidate.getName().endsWith(".png")
                    || !candidate.isFile()) {
                return null;
            }
            return candidate;
        } catch (IOException error) {
            return null;
        }
    }

    static void discardDraft(Context context, File draft) {
        if (draft == null) {
            return;
        }
        File safe = safeDraftFile(context, draft.getAbsolutePath());
        if (safe != null) {
            safe.delete();
        }
    }

    static void cleanupStaleDrafts(Context context) {
        File[] drafts = draftDirectory(context).listFiles();
        if (drafts == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - MAX_DRAFT_AGE_MILLIS;
        for (File draft : drafts) {
            if (draft.isFile() && draft.lastModified() < cutoff) {
                draft.delete();
            }
        }
    }

    /**
     * Commits one record. Image records contain both an unmarked crop and a
     * rendered preview; text-only records keep source text as a separate field.
     */
    static CaptureRecord save(
            Context context,
            File sourceDraft,
            Bitmap originalCrop,
            Bitmap annotatedCrop,
            JSONObject annotationLayer,
            String kind,
            String comment,
            String sourceType,
            String sourceText,
            String sourcePackage
    ) throws IOException {
        File safeDraft = null;
        if (sourceDraft != null) {
            safeDraft = safeDraftFile(context, sourceDraft.getAbsolutePath());
            if (safeDraft == null) {
                throw new IOException("Capture draft is no longer available");
            }
        }
        boolean hasImage = originalCrop != null && annotatedCrop != null;
        if ((originalCrop == null) != (annotatedCrop == null)) {
            throw new IOException("Incomplete capture image pair");
        }
        if (!hasImage && safeText(sourceText, 100_000).isEmpty()
                && safeText(comment, 20_000).isEmpty()) {
            throw new IOException("Capture record has no content");
        }

        long createdAt = System.currentTimeMillis();
        String id = new SimpleDateFormat("yyyyMMdd'T'HHmmssSSS", Locale.US)
                .format(new Date(createdAt))
                + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        File directory = new File(inboxDirectory(context), id);
        if (!directory.mkdirs()) {
            throw new IOException("Cannot create capture record");
        }
        File original = new File(directory, ORIGINAL_FILENAME);
        File annotatedFile = new File(directory, ANNOTATED_FILENAME);
        File recordFile = new File(directory, RECORD_FILENAME);
        boolean complete = false;
        try {
            if (hasImage) {
                writeBitmap(originalCrop, original);
                writeBitmap(annotatedCrop, annotatedFile);
            }
            JSONObject object = new JSONObject()
                    .put("schemaVersion", 1)
                    .put("id", id)
                    .put("createdAt", createdAt)
                    .put("kind", cleanKind(kind))
                    .put("comment", safeText(comment, 20_000))
                    .put("sourceType", safeText(sourceType, 80))
                    .put("sourceText", safeText(sourceText, 100_000))
                    .put("sourcePackage", safeText(sourcePackage, 255))
                    .put("fidelityLevel", fidelityLevel(sourceType, hasImage))
                    .put("hasImage", hasImage)
                    .put("originalFile", hasImage ? ORIGINAL_FILENAME : JSONObject.NULL)
                    .put("annotatedFile", hasImage ? ANNOTATED_FILENAME : JSONObject.NULL)
                    .put("width", hasImage ? annotatedCrop.getWidth() : 0)
                    .put("height", hasImage ? annotatedCrop.getHeight() : 0)
                    .put(
                            "aiAccess",
                            CaptureSyncPreferences.defaultAiAccess(context)
                    )
                    .put(
                            "syncState",
                            CaptureSyncPreferences.isConfigured(context)
                                    ? SYNC_PENDING
                                    : SYNC_LOCAL_ONLY
                    )
                    .put("syncLastError", JSONObject.NULL)
                    .put("syncUpdatedAt", createdAt)
                    .put("serverRevision", JSONObject.NULL)
                    .put(
                            "annotationLayer",
                            annotationLayer == null ? JSONObject.NULL : annotationLayer
                    );
            writeJson(object, recordFile);
            CaptureRecord result = parseRecord(directory, object);
            complete = true;
            if (safeDraft != null) {
                safeDraft.delete();
            }
            return result;
        } catch (JSONException error) {
            throw new IOException("Cannot encode capture metadata", error);
        } finally {
            if (!complete) {
                deleteNewRecord(directory);
            }
        }
    }

    static List<CaptureRecord> list(Context context, int maximum) {
        if (maximum <= 0) {
            return new ArrayList<>();
        }
        File root = inboxDirectory(context);
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) {
            return new ArrayList<>();
        }
        Arrays.sort(directories, Comparator.comparing(File::getName).reversed());
        List<CaptureRecord> result = new ArrayList<>();
        for (File directory : directories) {
            CaptureRecord record = readRecord(directory);
            if (record != null) {
                result.add(record);
                if (result.size() >= maximum) {
                    break;
                }
            }
        }
        return result;
    }

    static CaptureRecord find(Context context, String id) {
        if (id == null || !id.matches("[0-9]{8}T[0-9]{9}-[a-f0-9]{8}")) {
            return null;
        }
        File root = inboxDirectory(context);
        try {
            File directory = new File(root, id).getCanonicalFile();
            File parent = directory.getParentFile();
            if (parent == null || !parent.equals(root.getCanonicalFile())) {
                return null;
            }
            return readRecord(directory);
        } catch (IOException error) {
            return null;
        }
    }

    static void markAllForSync(Context context) {
        for (CaptureRecord record : list(context, Integer.MAX_VALUE)) {
            updateSyncState(
                    context,
                    record.id,
                    SYNC_PENDING,
                    "",
                    record.serverRevision
            );
        }
    }

    static void markAllLocalOnly(Context context) {
        for (CaptureRecord record : list(context, Integer.MAX_VALUE)) {
            updateSyncState(
                    context,
                    record.id,
                    SYNC_LOCAL_ONLY,
                    "",
                    0
            );
        }
    }

    static boolean updateSyncState(
            Context context,
            String id,
            String state,
            String errorCode,
            int serverRevision
    ) {
        if (!isSyncState(state)) {
            throw new IllegalArgumentException("Unsupported capture sync state");
        }
        synchronized (METADATA_LOCK) {
            CaptureRecord record = find(context, id);
            if (record == null) {
                return false;
            }
            JSONObject object = readRecordObject(record.metadataFile);
            if (object == null) {
                return false;
            }
            try {
                object.put("syncState", state);
                object.put(
                        "syncLastError",
                        errorCode == null || errorCode.isEmpty()
                                ? JSONObject.NULL
                                : safeText(errorCode, 120)
                );
                object.put("syncUpdatedAt", System.currentTimeMillis());
                object.put(
                        "serverRevision",
                        serverRevision > 0 ? serverRevision : JSONObject.NULL
                );
                writeJson(object, record.metadataFile);
                return true;
            } catch (IOException | JSONException error) {
                return false;
            }
        }
    }

    static JSONArray annotationItems(CaptureRecord record) {
        JSONObject object = record == null
                ? null
                : readRecordObject(record.metadataFile);
        if (object == null) {
            return new JSONArray();
        }
        JSONObject layer = object.optJSONObject("annotationLayer");
        if (layer == null) {
            return new JSONArray();
        }
        JSONArray strokes = layer.optJSONArray("strokes");
        return strokes == null ? new JSONArray() : strokes;
    }

    private static CaptureRecord readRecord(File directory) {
        File metadata = new File(directory, RECORD_FILENAME);
        JSONObject object = readRecordObject(metadata);
        if (object == null) {
            return null;
        }
        try {
            boolean hasImage = object.optBoolean("hasImage", false);
            if (hasImage
                    && (!new File(directory, ORIGINAL_FILENAME).isFile()
                    || !new File(directory, ANNOTATED_FILENAME).isFile())) {
                return null;
            }
            return parseRecord(directory, object);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static JSONObject readRecordObject(File metadata) {
        if (metadata == null || !metadata.isFile() || metadata.length() <= 0L
                || metadata.length() > 512 * 1024L) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(metadata)) {
            byte[] bytes = new byte[(int) metadata.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset != bytes.length) {
                return null;
            }
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | JSONException | RuntimeException error) {
            return null;
        }
    }

    private static CaptureRecord parseRecord(File directory, JSONObject object) {
        boolean hasImage = object.optBoolean("hasImage", false);
        return new CaptureRecord(
                object.optString("id", directory.getName()),
                object.optLong("createdAt", directory.lastModified()),
                cleanKind(object.optString("kind", "comment")),
                object.optString("comment", ""),
                object.optString("sourceType", "screen"),
                object.optString("sourceText", ""),
                object.optString("sourcePackage", ""),
                object.optString("fidelityLevel", hasImage ? "L2" : "user"),
                CaptureSyncPreferences.cleanAiAccess(
                        object.optString("aiAccess", CaptureSyncPreferences.AI_LOCAL_ONLY)
                ),
                cleanSyncState(object.optString("syncState", SYNC_LOCAL_ONLY)),
                object.optString("syncLastError", ""),
                object.optInt("serverRevision", 0),
                hasImage,
                hasImage ? new File(directory, ORIGINAL_FILENAME) : null,
                hasImage ? new File(directory, ANNOTATED_FILENAME) : null,
                new File(directory, RECORD_FILENAME)
        );
    }

    private static Bitmap decodeSampled(File source, int maximumDimension) {
        if (source == null || !source.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(
                bounds.outWidth,
                bounds.outHeight,
                maximumDimension
        );
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        } catch (OutOfMemoryError | RuntimeException error) {
            return null;
        }
    }

    private static int sampleSize(int width, int height, int maximumDimension) {
        int sample = 1;
        while (width / sample > maximumDimension
                || height / sample > maximumDimension) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private static void writeBitmap(Bitmap bitmap, File destination) throws IOException {
        File partial = new File(destination.getParentFile(), destination.getName() + ".part");
        boolean complete = false;
        try (FileOutputStream output = new FileOutputStream(partial, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Cannot encode capture image");
            }
            output.getFD().sync();
            if (!partial.renameTo(destination)) {
                throw new IOException("Cannot finish capture image");
            }
            complete = true;
        } finally {
            if (!complete) {
                partial.delete();
            }
        }
    }

    private static void writeJson(JSONObject object, File destination) throws IOException {
        File partial = new File(destination.getParentFile(), destination.getName() + ".part");
        byte[] bytes;
        try {
            bytes = object.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IOException("Cannot format capture metadata", error);
        }
        boolean complete = false;
        try {
            try (FileOutputStream output = new FileOutputStream(partial, false)) {
                output.write(bytes);
                output.getFD().sync();
            }
            replaceAtomically(partial, destination);
            complete = true;
        } finally {
            if (!complete) {
                partial.delete();
            }
        }
    }

    private static void replaceAtomically(File source, File destination)
            throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (
                BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
                FileOutputStream fileOutput = new FileOutputStream(destination, false);
                BufferedOutputStream output = new BufferedOutputStream(fileOutput)
        ) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            output.flush();
            fileOutput.getFD().sync();
        }
    }

    private static File inboxDirectory(Context context) {
        File directory = new File(context.getApplicationContext().getFilesDir(), INBOX_DIRECTORY);
        if (!directory.isDirectory()) {
            directory.mkdirs();
        }
        return directory;
    }

    private static File draftDirectory(Context context) {
        return new File(context.getApplicationContext().getCacheDir(), DRAFT_DIRECTORY);
    }

    private static String cleanKind(String kind) {
        if ("thought".equals(kind) || "todo".equals(kind)) {
            return kind;
        }
        return "comment";
    }

    private static boolean isSyncState(String value) {
        return SYNC_LOCAL_ONLY.equals(value)
                || SYNC_PENDING.equals(value)
                || SYNC_FAILED.equals(value)
                || SYNC_SYNCED.equals(value);
    }

    private static String cleanSyncState(String value) {
        return isSyncState(value) ? value : SYNC_LOCAL_ONLY;
    }

    private static String fidelityLevel(String sourceType, boolean hasImage) {
        if ("process_text".equals(sourceType) || "share_text".equals(sourceType)) {
            return "L3";
        }
        return hasImage ? "L2" : "user";
    }

    private static String safeText(String value, int maximumCharacters) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\u0000', ' ').trim();
        return clean.length() <= maximumCharacters
                ? clean
                : clean.substring(0, maximumCharacters);
    }

    private static void deleteNewRecord(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        directory.delete();
    }

    static final class CaptureRecord {
        final String id;
        final long createdAt;
        final String kind;
        final String comment;
        final String sourceType;
        final String sourceText;
        final String sourcePackage;
        final String fidelityLevel;
        final String aiAccess;
        final String syncState;
        final String syncLastError;
        final int serverRevision;
        final boolean hasImage;
        final File originalFile;
        final File annotatedFile;
        final File metadataFile;

        CaptureRecord(
                String id,
                long createdAt,
                String kind,
                String comment,
                String sourceType,
                String sourceText,
                String sourcePackage,
                String fidelityLevel,
                String aiAccess,
                String syncState,
                String syncLastError,
                int serverRevision,
                boolean hasImage,
                File originalFile,
                File annotatedFile,
                File metadataFile
        ) {
            this.id = id;
            this.createdAt = createdAt;
            this.kind = kind;
            this.comment = comment;
            this.sourceType = sourceType;
            this.sourceText = sourceText;
            this.sourcePackage = sourcePackage;
            this.fidelityLevel = fidelityLevel;
            this.aiAccess = aiAccess;
            this.syncState = syncState;
            this.syncLastError = syncLastError;
            this.serverRevision = serverRevision;
            this.hasImage = hasImage;
            this.originalFile = originalFile;
            this.annotatedFile = annotatedFile;
            this.metadataFile = metadataFile;
        }
    }
}
