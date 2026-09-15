package com.codex.mnote;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

final class AppUpdateClient {
    interface Progress {
        void update(int percent);
    }
    static AppRelease check(String current) throws Exception {
        HttpsURLConnection connection = open(AppRelease.API, false);
        try (InputStream input = connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            long deadline = System.nanoTime() + 30_000_000_000L;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(deadline);
                if (bytes.size() + count > 8 * 1024 * 1024)
                    throw new IOException("invalid_release");
                bytes.write(buffer, 0, count);
            }
            return AppRelease.select(bytes.toString(StandardCharsets.UTF_8.name()), current);
        } finally {
            connection.disconnect();
        }
    }
    private static void checkCancelled(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
            throw new IOException("cancelled");
    }
    private static HttpsURLConnection open(String url, boolean download) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (download ? !AppRelease.allowedDownload(url) : !AppRelease.API.equals(url))
                throw new IOException("unsafe_url");
            HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mnote-Updater");
            c.setRequestProperty(
                "Accept", download ? "application/octet-stream" : "application/vnd.github+json");
            int status;
            try {
                status = c.getResponseCode();
            } catch (IOException error) {
                c.disconnect();
                throw error;
            }
            if (status == 200)
                return c;
            if (download
                && (status == 301 || status == 302 || status == 303 || status == 307
                    || status == 308)) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null)
                    throw new IOException("unsafe_url");
                url = new URL(new URL(url), next).toString();
                continue;
            }
            c.disconnect();
            throw new IOException(
                status == 403 || status == 429 ? "rate_limited" : "network_failed");
        }
        throw new IOException("unsafe_url");
    }
    static File download(Context context, AppRelease release, Progress progress) throws Exception {
        release.validate();
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("storage_failed");
        File destination = new File(directory, release.sha256 + ".apk"),
             temporary = new File(directory, UUID.randomUUID() + ".part");
        if (destination.isFile())
            try {
                verify(context, destination, release);
                progress.update(100);
                return destination;
            } catch (Exception ignored) {
                if (!destination.delete())
                    throw new IOException("storage_failed");
            }
        HttpsURLConnection connection = open(release.url, true);
        try (InputStream input = connection.getInputStream();
            FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32768];
            long total = 0, deadline = System.nanoTime() + 180_000_000_000L;
            int count, last = -1;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(deadline);
                total += count;
                if (total > release.size)
                    throw new IOException("integrity_failed");
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                int percent = (int) (total * 100 / release.size);
                if (percent != last) {
                    last = percent;
                    progress.update(percent);
                }
            }
            output.getFD().sync();
            if (total != release.size || !hex(digest.digest()).equals(release.sha256))
                throw new IOException("integrity_failed");
        } catch (Exception error) {
            temporary.delete();
            throw error;
        } finally {
            connection.disconnect();
        }
        try {
            verify(context, temporary, release);
            Files.move(
                temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally {
            temporary.delete();
        }
    }
    @SuppressWarnings("deprecation")
    static void verify(Context context, File file, AppRelease release) throws Exception {
        release.validate();
        if (file.length() != release.size)
            throw new IOException("integrity_failed");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[32768];
            int n;
            while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n);
        }
        if (!hex(digest.digest()).equals(release.sha256))
            throw new IOException("integrity_failed");
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES
                                                : PackageManager.GET_SIGNATURES;
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags),
                    archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        verifyIdentity(context.getPackageName(), installed, archive, release);
    }
    static void verifyIdentity(String packageName, PackageInfo installed, PackageInfo archive,
        AppRelease release) throws IOException {
        if (archive == null || !packageName.equals(archive.packageName)
            || !release.version.equals(archive.versionName)
            || versionCode(archive) <= versionCode(installed) || archive.applicationInfo == null
            || archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
            throw new IOException("wrong_package");
        if (!signatures(installed).equals(signatures(archive)) || signatures(installed).isEmpty())
            throw new IOException("wrong_signature");
    }
    @SuppressWarnings("deprecation")
    private static Set<String> signatures(PackageInfo info) {
        Signature[] values = Build.VERSION.SDK_INT >= 28
            ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners())
            : info.signatures;
        Set<String> result = new HashSet<>();
        if (values != null)
            for (Signature value : values) result.add(value.toCharsString());
        return result;
    }
    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    static String error(Exception error) {
        String code = error.getMessage();
        if ("rate_limited".equals(code))
            return "GitHub 请求暂时受限，请稍后重试。";
        if ("wrong_signature".equals(code))
            return "安装包签名与本机版本不同，已阻止安装。不要卸载旧版，请联系维护者。";
        if ("wrong_package".equals(code))
            return "安装包版本、应用身份或系统要求不匹配，已阻止安装。";
        if ("integrity_failed".equals(code) || "invalid_release".equals(code)
            || "unsafe_url".equals(code))
            return "更新信息或文件校验未通过，未启动安装。请重新检查更新。";
        return "更新未完成，请检查网络和存储空间后重试。现有应用和记录不受影响。";
    }
}
