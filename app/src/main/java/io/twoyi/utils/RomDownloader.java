package io.twoyi.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Multi-mirror ROM downloader.
 *
 * Given a raw download URL (typically a GitHub release/raw link), it builds a
 * candidate URL list: the original URL first, then the same URL prefixed with
 * each public GitHub acceleration mirror. It tries them in order, retries on
 * failure, and skips downloading entirely when a local copy already exists.
 */
public final class RomDownloader {

    private static final String TAG = "RomDownloader";

    /** 用户提供的加速镜像 + 常见公共 GitHub 加速镜像。 */
    private static final List<String> MIRRORS = Arrays.asList(
            "https://gh.luogen.cc.cd/",
            "https://ghproxy.net/",
            "https://gh-proxy.com/",
            "https://mirror.ghproxy.com/"
    );

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final int MAX_RETRIES_PER_URL = 2;

    public interface ProgressListener {
        void onProgress(int percent, long downloaded, long total);

        void onMirrorSwitch(String url, int index, int total);

        void onSuccess(File file, boolean fromCache);

        void onFailure(String reason);
    }

    private RomDownloader() {
    }

    /** 本地第三方 ROM 保存路径（直接复用两仪的 3rd ROM 机制）。 */
    public static File targetRomFile(Context context) {
        return RomManager.get3rdRootfsFile(context);
    }

    /**
     * 下载 ROM。若本地已存在同长度文件则直接回调 onSuccess(fromCache=true)。
     */
    public static void downloadRom(Context context, String rawUrl, ProgressListener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        File target = targetRomFile(context);

        List<String> candidates = buildCandidates(rawUrl);

        new Thread(() -> {
            // 0. cache check: any previously downloaded rom that parses ok?
            if (target.exists() && target.length() > 1024 * 1024
                    && RomManager.getRomInfo(target).isValid()) {
                main.post(() -> listener.onSuccess(target, true));
                return;
            }

            int total = candidates.size();
            for (int i = 0; i < total; i++) {
                final String url = candidates.get(i);
                final int idx = i + 1;
                main.post(() -> listener.onMirrorSwitch(url, idx, total));
                for (int retry = 0; retry < MAX_RETRIES_PER_URL; retry++) {
                    try {
                        File tmp = new File(target.getAbsolutePath() + ".tmp");
                        long size = httpDownload(url, tmp, new ProgressListener() {
                            @Override public void onProgress(int p, long d, long t) {
                                main.post(() -> listener.onProgress(p, d, t));
                            }
                            @Override public void onMirrorSwitch(String u, int i, int tot) { }
                            @Override public void onSuccess(File f, boolean c) { }
                            @Override public void onFailure(String r) { }
                        });
                        if (size <= 0) throw new IOException("empty body");
                        if (!tmp.renameTo(target)) {
                            // fallback copy
                            try (InputStream in = new java.io.FileInputStream(tmp);
                                 OutputStream out = new FileOutputStream(target)) {
                                byte[] buf = new byte[8192];
                                int c;
                                while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
                            }
                            tmp.delete();
                        }
                        main.post(() -> listener.onSuccess(target, false));
                        return;
                    } catch (Throwable e) {
                        Log.w(TAG, "download failed: " + url + " retry=" + retry, e);
                    }
                }
            }
            main.post(() -> listener.onFailure("all mirrors failed"));
        }, "rom-downloader").start();
    }

    static List<String> buildCandidates(String rawUrl) {
        List<String> list = new ArrayList<>();
        if (TextUtils.isEmpty(rawUrl)) return list;
        list.add(rawUrl);
        for (String mirror : MIRRORS) {
            list.add(mirror + rawUrl);
        }
        return list;
    }

    private static long httpDownload(String url, File out, ProgressListener l) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "liangyi-rom-downloader");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);

            long total = conn.getContentLengthLong();
            long downloaded = 0;
            int lastPercent = -1;
            try (InputStream in = new java.io.BufferedInputStream(conn.getInputStream());
                 OutputStream os = new FileOutputStream(out, false)) {
                byte[] buf = new byte[16384];
                int c;
                while ((c = in.read(buf)) > 0) {
                    os.write(buf, 0, c);
                    downloaded += c;
                    if (total > 0) {
                        int percent = (int) (downloaded * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            l.onProgress(percent, downloaded, total);
                        }
                    }
                }
            }
            return downloaded;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
