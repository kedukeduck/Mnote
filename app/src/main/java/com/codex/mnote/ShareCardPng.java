package com.codex.mnote;

import android.graphics.*;
import java.io.*;
import java.util.zip.*;

/** Streams one tall PNG with a bounded 512-row strip, rather than allocating a giant bitmap. */
final class ShareCardPng {
    static void write(ShareCardRenderer.Document doc, OutputStream output) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(header);
        h.writeInt(ShareCardRenderer.WIDTH);
        h.writeInt(doc.height);
        h.write(new byte[] {8, 6, 0, 0, 0});
        chunk(out, "IHDR", header.toByteArray(), header.size());
        Idat idat = new Idat(out);
        Deflater compressor = new Deflater(6);
        Bitmap strip = null;
        try {
            DeflaterOutputStream zip = new DeflaterOutputStream(idat, compressor, 32768);
            strip = Bitmap.createBitmap(ShareCardRenderer.WIDTH, 512, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(strip);
            int[] pixels = new int[ShareCardRenderer.WIDTH];
            byte[] row = new byte[1 + ShareCardRenderer.WIDTH * 4];
            for (int y = 0; y < doc.height; y += 512) {
                if (Thread.currentThread().isInterrupted())
                    throw new IOException("card_save_interrupted");
                canvas.save();
                canvas.translate(0, -y);
                doc.draw(canvas);
                canvas.restore();
                for (int local = 0; local < Math.min(512, doc.height - y); local++) {
                    strip.getPixels(
                        pixels, 0, ShareCardRenderer.WIDTH, 0, local, ShareCardRenderer.WIDTH, 1);
                    for (int x = 0; x < pixels.length; x++) {
                        int p = pixels[x], offset = 1 + x * 4;
                        row[offset] = (byte) (p >>> 16);
                        row[offset + 1] = (byte) (p >>> 8);
                        row[offset + 2] = (byte) p;
                        row[offset + 3] = (byte) (p >>> 24);
                    }
                    zip.write(row);
                }
            }
            zip.finish();
            idat.finish();
            chunk(out, "IEND", new byte[0], 0);
            out.flush();
        } finally {
            compressor.end();
            if (strip != null)
                strip.recycle();
        }
    }
    private static void chunk(DataOutputStream out, String type, byte[] bytes, int size)
        throws IOException {
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(bytes, 0, size);
        out.writeInt(size);
        out.write(name);
        out.write(bytes, 0, size);
        out.writeInt((int) crc.getValue());
    }
    private static final class Idat extends OutputStream {
        private final DataOutputStream out;
        private final byte[] buffer = new byte[32768];
        private int used;
        Idat(DataOutputStream out) {
            this.out = out;
        }
        @Override
        public void write(int value) throws IOException {
            buffer[used++] = (byte) value;
            if (used == buffer.length)
                finish();
        }
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                int n = Math.min(length, buffer.length - used);
                System.arraycopy(bytes, offset, buffer, used, n);
                used += n;
                offset += n;
                length -= n;
                if (used == buffer.length)
                    finish();
            }
        }
        void finish() throws IOException {
            if (used > 0) {
                chunk(out, "IDAT", buffer, used);
                used = 0;
            }
        }
    }
}
