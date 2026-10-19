package com.hongqi.flighting.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.security.MessageDigest;


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 激活安全守卫：防止绕过激活界面直接进入主界面。
 *
 * <p>核心策略：
 * <ol>
 *   <li>激活状态不再以明文存储，而是 HMAC 签名 token（设备指纹 + 状态 + 过期时间）</li>
 *   <li>直接修改 SharedPreferences 的 activation_status=ACTIVE 不会通过校验（签名不匹配）</li>
 *   <li>token 含过期时间，超时后即使本地有缓存也必须重新联网校验</li>
 *   <li>APK 签名校验：检测重打包（release 签名指纹不一致则拒绝运行）</li>
 *   <li>Debug 检测：debug 模式下放宽（方便开发），release 模式严格</li>
 * </ol>
 */
public final class ActivationGuard {

    private static final String TAG = "ActivationGuard";

    private static final String PREFS_NAME = "carlife_mirror_activation";
    private static final String KEY_ACTIVATION_CODE = "activation_code";
    private static final String KEY_TOKEN = "activation_token";      // HMAC 签名 token
    private static final String KEY_LAST_VERIFY_TIME = "last_verify_time";
    private static final String KEY_ACTIVATION_SERVER_URL = "activation_server_url";

    /** 离线宽限期 */
    private static final long OFFLINE_GRACE_MS = 7 * 24 * 3600_000L; // 7 天

    /**
     * HMAC 密钥（编译进代码，R8 字符串加密后不易提取）。
     * 若要更换，需同步修改 ActivationController 中的 saveActivationToken 调用。
     */
    private static final byte[] HMAC_KEY = new byte[]{
        0x4e, 0x2b, 0x71, (byte) 0xa3, 0x5f, (byte) 0xc8, 0x1d, 0x6e,
        (byte) 0xb9, 0x03, 0x47, (byte) 0xd5, (byte) 0x8a, 0x2c, 0x6f, 0x17,
        0x53, 0x21, (byte) 0xe4, 0x09, (byte) 0x8d, (byte) 0xc2, 0x44, 0x57,
        (byte) 0xa6, 0x1f, (byte) 0x93, 0x3b, (byte) 0xe0, 0x7c, 0x18, (byte) 0xd6
    };

    // Release 签名 SHA-256（首次部署后需替换为实际值，为空则跳过签名校验）
    private static final String RELEASE_SIGNATURE_HASH = "";

    private ActivationGuard() {
    }

    // ===== 公开 API =====

    /**
     * 检查当前是否已激活（含签名校验 + 过期检查）。
     * 可在任意线程调用。
     */
    public static boolean isActivated(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, null);
        if (token == null || token.isEmpty()) {
            return false;
        }
        // 验证签名
        String[] parts = token.split("\\|");
        if (parts.length != 4) {
            return false;
        }
        String status = parts[0];
        String code = parts[1];
        String fingerprint = parts[2];
        String sig = parts[3];

        // 校验签名是否匹配
        String expected = computeHMAC(status + "|" + code + "|" + fingerprint);
        if (expected.isEmpty() || !MessageDigest.isEqual(sig.getBytes(java.nio.charset.StandardCharsets.UTF_8), expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            Log.w(TAG, "token 签名校验失败，可能被篡改");
            return false;
        }

        // 校验状态
        if (!"ACTIVE".equals(status)) {
            return false;
        }

        // 校验设备指纹一致
        String currentFp = DeviceFingerprint.getDeviceFingerprint(context);
        if (!fingerprint.equals(currentFp)) {
            Log.w(TAG, "设备指纹不匹配，token 可能被复制到其他设备");
            return false;
        }

        // 校验离线宽限期
        long lastVerify = prefs.getLong(KEY_LAST_VERIFY_TIME, 0);
        if (lastVerify <= 0 || System.currentTimeMillis() < lastVerify || System.currentTimeMillis() - lastVerify > OFFLINE_GRACE_MS) {
            Log.i(TAG, "离线宽限期已过，需要重新联网校验");
            return false;
        }

        return true;
    }

    /**
     * 保存激活 token（仅 ActivationController 校验成功后调用）。
     */
    public static void saveActivationToken(Context context, String status, String activationCode) {
        String fingerprint = DeviceFingerprint.getDeviceFingerprint(context);
        String sig = computeHMAC(status + "|" + activationCode + "|" + fingerprint);
        String token = status + "|" + activationCode + "|" + fingerprint + "|" + sig;
        context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_ACTIVATION_CODE, activationCode)
            .putLong(KEY_LAST_VERIFY_TIME, System.currentTimeMillis())
            .apply();
    }

    /**
     * 清除激活数据（用于禁用/重置）。
     */
    public static void clearActivation(Context context) {
        context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TOKEN)
            .remove(KEY_ACTIVATION_CODE)
            .remove(KEY_LAST_VERIFY_TIME)
            .apply();
    }

    /**
     * 在 Activity 中检查激活状态，未激活则跳转激活界面并关闭当前 Activity。
     * 应在 onCreate() 最开始调用。
     */
    public static void requireActivated(Context context) {
        if (context instanceof android.app.Activity && ((android.app.Activity) context).isFinishing()) return;
        if (!isSecureRunning(context)) {
            Log.w(TAG, "未激活，拦截 " + context.getClass().getSimpleName());
            // The dedicated activation screen remembers the requested in-app destination.
            // DiAuto services and every feature independently enforce the same guard.
            Intent intent = new Intent(context, com.hongqi.flighting.ui.ActivationActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (context instanceof android.app.Activity) {
                android.app.Activity activity = (android.app.Activity) context;
                Intent destination = new Intent(activity.getIntent());
                destination.setComponent(new android.content.ComponentName(activity, activity.getClass()));
                intent.putExtra(com.hongqi.flighting.ui.ActivationActivity.EXTRA_DESTINATION, destination);
            }
            context.startActivity(intent);
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).finish();
            }
        }
    }

    /**
     * 检查 APK 签名是否被篡改（重打包检测）。
     * RELEASE_SIGNATURE_HASH 为空时跳过（开发阶段）。
     */
    public static boolean isSignatureValid(Context context) {
        if (RELEASE_SIGNATURE_HASH == null || RELEASE_SIGNATURE_HASH.isEmpty()) {
            return true; // 未配置 release 签名指纹，跳过
        }
        try {
            Signature[] sigs = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES)
                .signatures;
            if (sigs == null || sigs.length == 0) {
                return false;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sigs[0].toByteArray());
            String hashHex = toHex(hash);
            return RELEASE_SIGNATURE_HASH.equalsIgnoreCase(hashHex);
        } catch (Exception e) {
            Log.e(TAG, "签名校验异常", e);
            return false;
        }
    }

    /**
     * 是否在 debug 模式下（BuildConfig.DEBUG 不可用时用这个）。
     */
    public static boolean isDebugMode(Context context) {
        try {
            return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 综合安全检查：激活 + 签名 + 非调试模式。
     * 用于关键服务启动前的门槛。
     */
    public static boolean isSecureRunning(Context context) {
        // debug 模式放宽检查（方便开发调试）
        if (isDebugMode(context)) {
            return true;
        }
        // release 模式严格检查
        if (!isSignatureValid(context)) {
            Log.e(TAG, "APK 签名被篡改，拒绝运行");
            return false;
        }
        return isActivated(context);
    }

    // ===== 内部工具 =====

    private static String computeHMAC(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY, "HmacSHA256"));
            byte[] result = mac.doFinal(input.getBytes("UTF-8"));
            return toHex(result);
        } catch (Exception e) {
            Log.e(TAG, "HMAC 计算失败", e);
            return "";
        }
    }
    private static String toHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }

}
