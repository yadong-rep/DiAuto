package com.hongqi.flighting.service;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hongqi.flighting.util.DeviceFingerprint;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * 激活服务API客户端。
 *
 * 负责与后端激活服务器通信：注册设备、校验激活状态。
 * 所有请求均附带 HMAC-SHA256 签名（防篡改）+ nonce（防重放）+ timestamp（防过期）。
 */
public class ActivationApiClient {

    private static final String TAG = "ActivationApiClient";

    private String baseUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    public ActivationApiClient(String serverBaseUrl) {
        if (serverBaseUrl != null && serverBaseUrl.endsWith("/")) {
            this.baseUrl = serverBaseUrl.substring(0, serverBaseUrl.length() - 1);
        } else {
            this.baseUrl = serverBaseUrl;
        }
    }

    public void close() { executor.shutdownNow(); mainHandler.removeCallbacksAndMessages(null); }

    public void setBaseUrl(String url) {
        if (url != null && url.endsWith("/")) {
            this.baseUrl = url.substring(0, url.length() - 1);
        } else {
            this.baseUrl = url;
        }
    }

    // ===================== 公共接口 =====================

    public void register(String deviceFingerprint, String deviceModel,
                         String androidVersion, String appVersion,
                         ActivationCallback<RegisterResult> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("deviceFingerprint", deviceFingerprint);
                body.put("deviceModel", deviceModel != null ? deviceModel : Build.UNKNOWN);
                body.put("androidVersion", androidVersion != null ? androidVersion : String.valueOf(Build.VERSION.SDK_INT));
                body.put("appVersion", appVersion != null ? appVersion : "1.0");

                String response = signedPost("/api/activation/register", body.toString(), deviceFingerprint);
                JSONObject json = new JSONObject(response);

                if (json.getBoolean("success")) {
                    JSONObject data = json.getJSONObject("data");
                    RegisterResult result = new RegisterResult(
                        data.getString("activationCode"),
                        data.getString("activationCodeFormatted"),
                        data.getString("status")
                    );
                    mainHandler.post(() -> callback.onSuccess(result));
                } else {
                    String msg = json.optString("message", "Unknown error");
                    mainHandler.post(() -> callback.onError(msg));
                }
            } catch (Exception e) {
                Log.e(TAG, "Register failed", e);
                mainHandler.post(() -> callback.onError("网络错误: " + e.getMessage()));
            }
        });
    }

    public void verify(String deviceFingerprint, String activationCode,
                       ActivationCallback<VerifyResult> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("deviceFingerprint", deviceFingerprint);
                body.put("activationCode", activationCode);

                String response = signedPost("/api/activation/verify", body.toString(), deviceFingerprint);
                JSONObject json = new JSONObject(response);

                if (json.getBoolean("success")) {
                    JSONObject data = json.getJSONObject("data");
                    VerifyResult result = new VerifyResult(
                        data.getBoolean("activated"),
                        data.getString("status"),
                        data.optString("activationCode", null),
                        data.optString("activationCodeFormatted", null)
                    );
                    mainHandler.post(() -> callback.onSuccess(result));
                } else {
                    String msg = json.optString("message", "Unknown error");
                    mainHandler.post(() -> callback.onError(msg));
                }
            } catch (Exception e) {
                Log.e(TAG, "Verify failed", e);
                mainHandler.post(() -> callback.onError("网络错误: " + e.getMessage()));
            }
        });
    }

    public void healthCheck(ActivationCallback<Boolean> callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(baseUrl + "/api/activation/health");
                conn = (HttpURLConnection) url.openConnection();
                configureSsl(conn);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                mainHandler.post(() -> callback.onSuccess(code == 200));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                if (conn != null) { conn.disconnect(); }
            }
        });
    }

    // ===================== 内部实现 =====================

    /**
     * 创建支持 TLS 1.2+ 的 SSLSocketFactory。
     * 部分定制 Android ROM（特别是车机）默认 SSL 实现不支持 TLS 1.2，
     * 需要显式启用以保证 HTTPS 连接正常。
     */
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

    private String signedPost(String path, String body, String deviceFingerprint) throws Exception {
        String fullUrl = baseUrl + path;
        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        configureSsl(conn);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        // 生成签名参数
        long timestamp = DeviceFingerprint.currentTimestampSeconds();
        String nonce = DeviceFingerprint.generateNonce();
        String sign = DeviceFingerprint.computeSignature(deviceFingerprint, timestamp, nonce, body);

        // 设置签名头
        conn.setRequestProperty("X-Device-Fingerprint", deviceFingerprint);
        conn.setRequestProperty("X-Timestamp", String.valueOf(timestamp));
        conn.setRequestProperty("X-Nonce", nonce);
        conn.setRequestProperty("X-Sign", sign);

        Log.d(TAG, "Request: " + path + " ts=" + timestamp + " nonce=" + nonce);

        // 写入请求体
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();

        // 读取响应
        int responseCode = conn.getResponseCode();
        StringBuilder response = new StringBuilder();

        InputStream is = null;
        try {
            if (responseCode >= 200 && responseCode < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
            }
        } catch (Exception e) {
            is = conn.getErrorStream();
        }

        if (is != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
        }

        conn.disconnect();

        if (responseCode != 200) {
            throw new RuntimeException("HTTP " + responseCode + ": " + response);
        }

        return response.toString();
    }

    // ===================== 数据类 =====================

    public static class RegisterResult {
        public final String activationCode;
        public final String activationCodeFormatted;
        public final String status;

        public RegisterResult(String activationCode, String activationCodeFormatted, String status) {
            this.activationCode = activationCode;
            this.activationCodeFormatted = activationCodeFormatted;
            this.status = status;
        }
    }

    public static class VerifyResult {
        public final boolean activated;
        public final String status;
        public final String activationCode;
        public final String activationCodeFormatted;

        public VerifyResult(boolean activated, String status,
                           String activationCode, String activationCodeFormatted) {
            this.activated = activated;
            this.status = status;
            this.activationCode = activationCode;
            this.activationCodeFormatted = activationCodeFormatted;
        }
    }

    public interface ActivationCallback<T> {
        void onSuccess(T result);
        void onError(String message);
    }
}
