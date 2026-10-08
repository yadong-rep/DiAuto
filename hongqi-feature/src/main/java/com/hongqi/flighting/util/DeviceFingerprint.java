package com.hongqi.flighting.util;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 设备指纹与API签名工具。
 * 
 * 设备指纹 = SHA-256(ANDROID_ID + 硬件信息 + 固定盐值)
 * 仅使用设备静态信息，卸载重装后指纹不变，激活码不变。
 * 
 * API签名 = HMAC-SHA256(deviceFingerprint:timestamp:nonce:bodyHash, MASTER_KEY)
 */
public class DeviceFingerprint {

    private static final String MASTER_KEY = "HongQi-CarLife-Mirror-2025!@#$%^&*()_Activation";

    private static final String PREFS_NAME = "carlife_mirror_activation";
    private static final String KEY_DEVICE_FINGERPRINT = "device_fingerprint";

    private static volatile String sCachedFingerprint;

    /** 十六进制字符表，替代 Java 17 的 HexFormat（Android 不支持） */
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    /**
     * 生成或获取设备指纹（纯静态信息，卸载重装不变）。
     */
    @NonNull
    public static String getDeviceFingerprint(@NonNull Context context) {
        if (sCachedFingerprint != null) {
            return sCachedFingerprint;
        }

        android.content.SharedPreferences prefs =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY_DEVICE_FINGERPRINT, null);
        if (cached != null && cached.length() == 64) {
            sCachedFingerprint = cached;
            return cached;
        }

        String androidId = Settings.Secure.getString(
            context.getContentResolver(), Settings.Secure.ANDROID_ID);

        String hardwareSerial;
        try {
            hardwareSerial = Build.VERSION.SDK_INT >= 26 ? Build.getSerial() : Build.SERIAL;
        } catch (SecurityException e) {
            hardwareSerial = Build.HARDWARE + "_" + Build.BOARD + "_" + Build.MODEL;
        }
        if (hardwareSerial == null || hardwareSerial.isEmpty() || "unknown".equalsIgnoreCase(hardwareSerial)) {
            hardwareSerial = Build.HARDWARE + "_" + Build.BOARD + "_" + Build.MODEL;
        }

        // 仅使用设备静态信息，不包含安装时间戳
        String raw = (androidId != null ? androidId : "unknown") + "|" +
                     hardwareSerial + "|" +
                     "HongQiCarLifeMirror2025";

        String fingerprint = sha256Hex(raw);
        sCachedFingerprint = fingerprint;

        prefs.edit()
            .putString(KEY_DEVICE_FINGERPRINT, fingerprint)
            .apply();

        return fingerprint;
    }

    // ===================== 签名相关 =====================

    @NonNull
    public static String computeSignature(@NonNull String deviceFingerprint,
                                          long timestampSeconds,
                                          @NonNull String nonce,
                                          @NonNull String body) {
        try {
            String bodyHash = body.isEmpty() ? "" : sha256Hex(body);
            String payload = deviceFingerprint + ":" + timestampSeconds + ":" + nonce + ":" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                MASTER_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(signature);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute signature", e);
        }
    }

    // ===================== 工具方法 =====================

    @NonNull
    public static String generateNonce() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return bytesToHex(bytes);
    }

    public static long currentTimestampSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    @NonNull
    public static String sha256Hex(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256", e);
        }
    }

    @NonNull
    public static String bytesToHex(@NonNull byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }
}
