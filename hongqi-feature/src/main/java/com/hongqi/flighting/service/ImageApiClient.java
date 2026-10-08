package com.hongqi.flighting.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * 图片管理服务 API 客户端。
 *
 * 负责从后端拉取图片列表、下载图片位图用于投屏显示。
 * 复用激活服务使用的服务器地址（SharedPreferences 中的 activation_server_url）。
 */
public class ImageApiClient {

    private static final String TAG = "ImageApiClient";
    private static final String PREFS_NAME = "carlife_mirror_activation";
    private static final String KEY_SERVER_URL = "activation_server_url";
    private static final String DEFAULT_SERVER_URL = "https://hongqi.ypyp.net";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private final String baseUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ImageApiClient(Context context) {
        this(resolveServerUrl(context));
    }

    public ImageApiClient(String baseUrl) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
    }

    private static String resolveServerUrl(Context context) {
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
        } catch (Exception e) {
            return DEFAULT_SERVER_URL;
        }
    }

    /** 图片信息。 */
    public static class ImageInfo {
        public final String id;
        public final String name;
        public final String url;   // 可直接下载的绝对地址
        public final long size;

        public ImageInfo(String id, String name, String url, long size) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.size = size;
        }
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    /** 拉取图片列表（异步，回调在主线程）。 */
    public void listImages(Callback<List<ImageInfo>> callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(baseUrl + "/api/images");
                conn = (HttpURLConnection) url.openConnection();
                configureSsl(conn);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                String body = readBody(conn, code);
                if (code != 200) {
                    throw new RuntimeException("HTTP " + code + ": " + body);
                }

                JSONObject json = new JSONObject(body);
                List<ImageInfo> result = new ArrayList<>();
                if (json.getBoolean("success")) {
                    JSONArray data = json.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject item = data.getJSONObject(i);
                            result.add(new ImageInfo(
                                item.getString("id"),
                                item.optString("name", ""),
                                toAbsolute(item.optString("url", "")),
                                item.optLong("size", 0)
                            ));
                        }
                    }
                }

                final List<ImageInfo> finalResult = result;
                mainHandler.post(() -> callback.onSuccess(finalResult));
            } catch (Exception e) {
                Log.e(TAG, "listImages failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /** 通过绝对地址下载图片字节（阻塞调用，请在后台线程使用）。 */
    public static byte[] downloadBytes(String absoluteUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(absoluteUrl);
            conn = (HttpURLConnection) url.openConnection();
            configureSsl(conn);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "downloadBytes HTTP " + code + " for " + absoluteUrl);
                return null;
            }

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            is.close();
            byte[] bytes = buffer.toByteArray();
            return bytes.length == 0 ? null : bytes;
        } catch (Exception e) {
            Log.w(TAG, "downloadBytes failed: " + absoluteUrl, e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 通过绝对地址下载并解码图片（阻塞调用，请在后台线程使用）。 */
    public static Bitmap downloadBitmap(String absoluteUrl) {
        byte[] bytes = downloadBytes(absoluteUrl);
        if (bytes == null) {
            return null;
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    /** 下载图片到本地缓存目录（应用私有 filesDir/images），成功返回绝对路径，失败返回 null。阻塞调用。 */
    public static String downloadToCache(Context context, String absoluteUrl, String fileName) {
        byte[] bytes = downloadBytes(absoluteUrl);
        if (bytes == null) {
            return null;
        }
        File dir = new File(context.getFilesDir(), "images");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create cache dir: " + dir);
            return null;
        }
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.w(TAG, "Failed to cache image: " + absoluteUrl, e);
            return null;
        }
    }

    private String toAbsolute(String url) {
        if (url == null || url.isEmpty()) {
            return baseUrl;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return baseUrl + url;
    }

    private static String readBody(HttpURLConnection conn, int code) throws Exception {
        InputStream is;
        try {
            is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        } catch (Exception e) {
            is = conn.getErrorStream();
        }
        if (is == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    /** 创建支持 TLS 1.2+ 的 SSLSocketFactory，兼容部分定制车机 ROM。 */
    private static SSLSocketFactory createTlsSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, null, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            Log.w(TAG, "TLSv1.2 not available, falling back to default TLS", e);
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, null, new SecureRandom());
                return sslContext.getSocketFactory();
            } catch (Exception e2) {
                Log.e(TAG, "Failed to create SSL context, using default", e2);
                return HttpsURLConnection.getDefaultSSLSocketFactory();
            }
        }
    }

    private static void configureSsl(HttpURLConnection conn) {
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
            httpsConn.setSSLSocketFactory(createTlsSocketFactory());
        }
    }
}