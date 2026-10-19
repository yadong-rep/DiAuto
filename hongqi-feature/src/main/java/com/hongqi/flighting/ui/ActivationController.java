package com.hongqi.flighting.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.hongqi.flighting.R;
import com.hongqi.flighting.service.ActivationApiClient;
import com.hongqi.flighting.util.ActivationGuard;
import com.hongqi.flighting.util.DeviceFingerprint;

/**
 * 激活流程控制器：承载激活码获取 / 联网校验 / 状态展示逻辑。
 *
 * <p>ActivationActivity delegates registration/verification and supplies a success callback.
 * Offline ACTIVE grants retain the original seven-day grace policy. close() discards stale callbacks.
 */
public class ActivationController {

    private static final String PREFS_NAME = "carlife_mirror_activation";
    private static final String KEY_ACTIVATION_CODE = "activation_code";
    private static final String KEY_ACTIVATION_SERVER_URL = "activation_server_url";
    private static final String DEFAULT_SERVER_URL = "https://hongqi.ypyp.net";

    private final Activity host;
    private final Runnable onActivated;
    private boolean closed;
    private final SharedPreferences prefs;

    private TextView textCode;
    private TextView textStatus;
    private Button btnRefresh;
    private Button btnContact;
    private ProgressBar progressBar;

    private ActivationApiClient apiClient;
    private String deviceFingerprint;
    private String activationCode;

    public ActivationController(Activity host) {
        this(host, host::recreate);
    }

    public ActivationController(Activity host, Runnable onActivated) {
        this.host = host;
        this.onActivated = onActivated;
        this.prefs = host.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 启动激活流程：加载激活布局并发起联网校验。 */
    public void start() {
        host.setContentView(R.layout.hongqi_activity_activation);
        initViews();
        initData();
        startVerification();
    }

    private void initViews() {
        textCode = host.findViewById(R.id.hongqi_activation_text_code);
        textStatus = host.findViewById(R.id.hongqi_activation_text_status);
        btnRefresh = host.findViewById(R.id.hongqi_activation_btn_refresh);
        btnContact = host.findViewById(R.id.hongqi_activation_btn_contact);
        progressBar = host.findViewById(R.id.hongqi_activation_progress);

        btnRefresh.setOnClickListener(v -> startVerification());
        btnContact.setOnClickListener(v -> showContactDialog());
    }

    private void showContactDialog() {
        AlertDialog dialog = new AlertDialog.Builder(host)
            .setView(R.layout.hongqi_dialog_contact)
            .create();
        dialog.show();
        dialog.findViewById(R.id.hongqi_dialog_btn_close)
            .setOnClickListener(v -> dialog.dismiss());
    }

    private void initData() {
        String serverUrl = prefs.getString(KEY_ACTIVATION_SERVER_URL, DEFAULT_SERVER_URL);
        apiClient = new ActivationApiClient(serverUrl);
        deviceFingerprint = DeviceFingerprint.getDeviceFingerprint(host);
        activationCode = prefs.getString(KEY_ACTIVATION_CODE, null);
    }

    /**
     * 每次启动强制执行：向服务器校验激活状态。
     */
    private void startVerification() {
        if (closed) return;
        showLoading(true);
        textStatus.setText("正在验证激活状态...");
        textStatus.setTextColor(ContextCompat.getColor(host, R.color.hongqi_brand_gold));

        if (activationCode == null) {
            // 首次使用：先注册获取激活码
            registerDevice();
        } else {
            // 已注册过：联网校验
            verifyWithServer();
        }
    }

    /**
     * 注册设备，从服务器获取激活码。
     */
    private void registerDevice() {
        String model = android.os.Build.MODEL;
        String androidVer = String.valueOf(android.os.Build.VERSION.SDK_INT);
        String appVer = getAppVersion();

        apiClient.register(deviceFingerprint, model, androidVer, appVer,
            new ActivationApiClient.ActivationCallback<ActivationApiClient.RegisterResult>() {
                @Override
                public void onSuccess(ActivationApiClient.RegisterResult result) {
                    if (closed || host.isFinishing()) return;
                    activationCode = result.activationCode;
                    // 激活状态统一以 HMAC 签名 token 存储（含状态字段），不再写明文 activation_status。
                    // 非 ACTIVE 状态的 token 同样受签名保护，无法被改成 ACTIVE，且 isActivated 会拒绝。
                    ActivationGuard.saveActivationToken(host, result.status, activationCode);

                    if ("ACTIVE".equals(result.status)) {
                        // 服务器返回已激活（注册时发现已有绑定且已激活）
                        enterMain();
                    } else {
                        displayActivationCode(result.activationCodeFormatted, result.status);
                    }
                }

                @Override
                public void onError(String message) {
                    if (closed || host.isFinishing()) return;
                    // 服务器不可达但本地有缓存 → 回退本地缓存，不暴露 502
                    String cachedCode = prefs.getString(KEY_ACTIVATION_CODE, null);
                    if (cachedCode != null) {
                        activationCode = cachedCode;
                        displayActivationCode(formatCode(cachedCode), "PENDING");
                        showError("无法连接服务器，当前显示的是本地缓存的激活状态");
                    } else {
                        showError("注册失败: " + message);
                    }
                }
            });
    }

    /**
     * 联网校验激活状态。
     */
    private void verifyWithServer() {
        apiClient.verify(deviceFingerprint, activationCode,
            new ActivationApiClient.ActivationCallback<ActivationApiClient.VerifyResult>() {
                @Override
                public void onSuccess(ActivationApiClient.VerifyResult result) {
                    if (closed || host.isFinishing()) return;
                    String status = result.status != null ? result.status : (result.activated ? "ACTIVE" : "PENDING");
                    // 服务器返回的最新状态写入 HMAC 签名 token；任何旧 ACTIVE token 都会被覆盖，
                    // 防止服务器已禁用但本地仍用旧 token 在宽限期内离线进入。
                    ActivationGuard.saveActivationToken(host, status, activationCode);

                    if ("ACTIVE".equals(status)) {
                        // 服务器确认已激活 → 重建 Activity 进入主界面
                        enterMain();
                    } else {
                        // 未激活或已禁用 → 显示激活码
                        String formatted = result.activationCodeFormatted != null
                            ? result.activationCodeFormatted
                            : formatCode(activationCode);
                        displayActivationCode(formatted, status);

                        if ("DISABLED".equals(status)) {
                            textStatus.setText("该设备已被禁用，请联系管理员");
                        }
                    }
                }

                @Override
                public void onError(String message) {
                    if (closed || host.isFinishing()) return;
                    // 服务器错误（502 等）或网络不可达 → 使用本地 HMAC token + 宽限期策略
                    // 不再读明文 activation_status，改用 ActivationGuard.isActivated
                    // （含签名校验 + 设备指纹 + 宽限期），防止篡改 SharedPreferences 伪造 ACTIVE
                    if (ActivationGuard.isActivated(host)) {
                        // 上次校验签发的 ACTIVE token 仍有效且在宽限期内 → 允许离线进入
                        enterMain();
                    } else if (activationCode != null) {
                        // 无有效 ACTIVE token → 显示激活码并提示网络错误
                        showError("无法连接服务器，请检查网络后点击刷新");
                        displayActivationCode(formatCode(activationCode), "UNKNOWN");
                    } else {
                        // 没有任何缓存数据 → 显示原始错误
                        showError("无法连接服务器: " + message);
                    }
                }
            });
    }

    /**
     * 激活成功：在主线程重建 Activity，onCreate 这次会走主界面分支。
     */
    private void enterMain() {
        host.runOnUiThread(() -> {
            if (!closed && !host.isFinishing()) onActivated.run();
        });
    }

    /**
     * 显示激活码和状态。
     */
    private void displayActivationCode(String formattedCode, String status) {
        showLoading(false);

        textCode.setText(formattedCode);
        textCode.setVisibility(View.VISIBLE);

        switch (status != null ? status : "") {
            case "PENDING":
                textStatus.setText("待激活 — 请将激活码提供给管理员");
                textStatus.setTextColor(ContextCompat.getColor(host, R.color.hongqi_brand_gold));
                break;
            case "ACTIVE":
                textStatus.setText("已激活 ✓");
                textStatus.setTextColor(ContextCompat.getColor(host, android.R.color.holo_green_light));
                break;
            case "DISABLED":
                textStatus.setText("已禁用 ✗ — 请联系管理员");
                textStatus.setTextColor(ContextCompat.getColor(host, R.color.hongqi_brand_red));
                break;
            default:
                textStatus.setText("无法连接服务器，请检查网络后点击刷新");
                textStatus.setTextColor(ContextCompat.getColor(host, R.color.hongqi_subtitle));
                break;
        }

        btnRefresh.setVisibility(View.VISIBLE);
    }

    private void showError(String message) {
        showLoading(false);
        textStatus.setText(message);
        textStatus.setTextColor(ContextCompat.getColor(host, R.color.hongqi_brand_red));
        btnRefresh.setVisibility(View.VISIBLE);
    }

    private void showLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnRefresh.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    private static String formatCode(String raw) {
        if (raw == null || raw.length() != 16) return raw != null ? raw : "----";
        return raw.substring(0, 4) + "-" +
               raw.substring(4, 8) + "-" +
               raw.substring(8, 12) + "-" +
               raw.substring(12, 16);
    }

    public void close() {
        closed = true;
        if (apiClient != null) apiClient.close();
    }

    private String getAppVersion() {
        try {
            return host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }
}
