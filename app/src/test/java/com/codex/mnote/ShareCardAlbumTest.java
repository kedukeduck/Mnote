package com.codex.mnote;

import static org.junit.Assert.*;

import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 30, 35})
public class ShareCardAlbumTest {
    static class Media extends ContentProvider {
        File file;
        ContentValues inserted, updated;
        boolean failOpen, failPublish;
        int deleted;
        @Override
        public boolean onCreate() {
            return true;
        }
        @Override
        public Uri insert(Uri uri, ContentValues values) {
            inserted = new ContentValues(values);
            return Uri.parse("content://media/external/images/media/42");
        }
        @Override
        public int update(Uri uri, ContentValues values, String where, String[] args) {
            updated = values;
            return failPublish ? 0 : 1;
        }
        @Override
        public int delete(Uri uri, String where, String[] args) {
            assertTrue(uri.toString().endsWith("/42"));
            deleted++;
            return 1;
        }
        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (failOpen)
                throw new FileNotFoundException("disk_full");
            return ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                    | ParcelFileDescriptor.MODE_READ_WRITE);
        }
        @Override
        public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
            return null;
        }
        @Override
        public String getType(Uri u) {
            return "image/png";
        }
    }
    Media provider;
    Context app;
    Bitmap bitmap;
    @Before
    public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        provider = new Media();
        provider.attachInfo(app, new android.content.pm.ProviderInfo());
        provider.file = File.createTempFile("card-", ".png", app.getCacheDir());
        ShadowContentResolver.registerProviderInternal("media", provider);
        // Robolectric's openOutputStream bypasses ContentProvider.openFile. Register
        // the actual output stream explicitly; insert/update/delete still use Media.
        org.robolectric.Shadows.shadowOf(app.getContentResolver())
            .registerOutputStream(
                Uri.parse("content://media/external/images/media/42"), new OutputStream() {
                    final OutputStream file = new FileOutputStream(provider.file);
                    @Override
                    public void write(int value) throws IOException {
                        if (provider.failOpen)
                            throw new IOException("disk_full");
                        file.write(value);
                    }
                    @Override
                    public void write(byte[] values, int offset, int length) throws IOException {
                        if (provider.failOpen)
                            throw new IOException("disk_full");
                        file.write(values, offset, length);
                    }
                    @Override
                    public void close() throws IOException {
                        file.close();
                    }
                });
        bitmap = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888);
    }
    @After
    public void cleanup() {
        bitmap.recycle();
        provider.file.delete();
    }
    @Test
    public void streamedCardUsesSameMediaStoreTransaction() throws Exception {
        var card = ShareCardRenderer.render("", "完整的想法。", null, null, 2, null);
        ShareCardAlbum.save(app, card.document);
        assertTrue(provider.file.length() > 0);
        assertEquals(0, provider.deleted);
        var decoded = android.graphics.BitmapFactory.decodeFile(provider.file.getAbsolutePath());
        assertEquals(card.document.height, decoded.getHeight());
        assertTrue(card.bitmap.sameAs(decoded));
        decoded.recycle();
        card.bitmap.recycle();
    }
    @Test
    public void streamedWriteFailureCleansOnlyItsPendingImage() {
        var card = ShareCardRenderer.render("", "完整的想法。", null, null, 2, null);
        provider.failOpen = true;
        try {
            ShareCardAlbum.save(app, card.document);
            fail();
        } catch (IOException expected) {
        }
        assertEquals(1, provider.deleted);
        assertNull(provider.updated);
        card.bitmap.recycle();
    }
    @Test
    public void savesPngAndPublishesPendingImageOnlyAfterWrite() throws Exception {
        Uri uri = ShareCardAlbum.save(app, bitmap);
        assertTrue(uri.toString().endsWith("/42"));
        assertTrue(provider.file.length() > 0);
        assertEquals("image/png", provider.inserted.getAsString(MediaStore.Images.Media.MIME_TYPE));
        assertEquals(0, provider.deleted);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            assertEquals(Integer.valueOf(1),
                provider.inserted.getAsInteger(MediaStore.Images.Media.IS_PENDING));
            assertEquals(Integer.valueOf(0),
                provider.updated.getAsInteger(MediaStore.Images.Media.IS_PENDING));
            assertEquals("Pictures/Mnote",
                provider.inserted.getAsString(MediaStore.Images.Media.RELATIVE_PATH));
        }
    }
    @Test
    public void failedWriteRemovesOnlyThisInsertedRow() {
        provider.failOpen = true;
        try {
            ShareCardAlbum.save(app, bitmap);
            fail();
        } catch (IOException expected) {
        }
        assertEquals(1, provider.deleted);
        assertNull(provider.updated);
    }
    @Test
    @Config(sdk = {30, 35})
    public void failedPublishAlsoCleansPendingRow() {
        provider.failPublish = true;
        try {
            ShareCardAlbum.save(app, bitmap);
            fail();
        } catch (IOException expected) {
        }
        assertEquals(1, provider.deleted);
    }
}
