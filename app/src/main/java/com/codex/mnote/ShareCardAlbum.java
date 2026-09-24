package com.codex.mnote;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.*;

final class ShareCardAlbum {
    static Uri save(Context context, Bitmap bitmap) throws IOException {
        return save(context, out -> {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                throw new IOException("album_write_failed");
        });
    }
    static Uri save(Context context, ShareCardRenderer.Document document) throws IOException {
        return save(context, out -> ShareCardPng.write(document, out));
    }
    private interface ImageWriter {
        void write(OutputStream out) throws IOException;
    }
    private static Uri save(Context context, ImageWriter writer) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(
            MediaStore.Images.Media.DISPLAY_NAME, "Mnote-" + java.util.UUID.randomUUID() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mnote");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null)
            throw new IOException("album_unavailable");
        boolean complete = false;
        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null)
                    throw new IOException("album_write_failed");
                writer.write(out);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (resolver.update(uri, ready, null, null) != 1)
                    throw new IOException("album_publish_failed");
            }
            complete = true;
            return uri;
        } finally {
            if (!complete)
                resolver.delete(uri, null, null); // Only this attempt's inserted image.
        }
    }
}
