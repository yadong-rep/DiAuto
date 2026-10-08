package com.hongqi.flighting.ui;

import android.app.Dialog;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;

import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.hongqi.flighting.R;
import com.hongqi.flighting.autostart.AppAutoStartManager;
import com.hongqi.flighting.display.ClusterMirrorCapabilityPolicy;
import com.hongqi.flighting.mapping.KeyActionDispatcher;
import com.hongqi.flighting.mapping.KeyMappingAccessibilityService;
import com.hongqi.flighting.mapping.KeyMappingManager;
import com.hongqi.flighting.mapping.TargetMediaSessionService;
import com.hongqi.flighting.service.ImageApiClient;
import com.hongqi.flighting.service.ScreenCaptureService;
import com.hongqi.flighting.sidebar.SidebarPrefs;
import com.hongqi.flighting.sidebar.SidebarService;
import com.hongqi.flighting.sidebar.SidebarWidgets;
import com.hongqi.flighting.util.ActivationGuard;
import com.hongqi.flighting.util.ConfigManager;
import com.hongqi.flighting.util.InAppLogger;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 主界面 - 配置所有推流参数
 */
public class HongqiFeatureActivity extends AppCompatActivity {

    private ConfigManager configManager;
    private ImageApiClient imageApiClient;
    private final ClusterMirrorCapabilityPolicy clusterMirrorCapabilityPolicy =
        new ClusterMirrorCapabilityPolicy();
    private final ClusterMirrorCapabilityPolicy.Decision clusterMirrorCapability =
        clusterMirrorCapabilityPolicy.evaluate();
    private ScreenCaptureService captureService;
    public static final String EXTRA_CAMERA_PERMISSION = "hongqi_request_camera_permission";
    private final ActivityResultLauncher<String[]> featurePermissions = registerForActivityResult(
        new ActivityResultContracts.RequestMultiplePermissions(), result -> {});
    private boolean serviceBound = false;
    private boolean serviceConnectionRegistered;
    private boolean permissionRequested = false;
    private int pendingProjectionToken = -1;

    private static final String TAG = "HongqiFeatureActivity";

    // UI组件
    private Switch switchEnabled;
    private Switch switchSimulatorPreview;
    private Spinner homeSpinnerOutputAlign;
    private CropSelectionView homeCropSelectionView;
    private Spinner spinnerStretchMode;
    private Spinner spinnerOutputAlign;
    private CropSelectionView cropSelectionView;
    private SeekBar homeSeekOutputWidth;
    private SeekBar seekOutputWidth;
    private Button btnStart;
    private Button btnStop;
    private Button btnSave;
    private Button btnReset;
    private Button btnCast;
    private Button btnSettings;
    private Button btnSettingsBack;
    private Button btnRefreshDisplays;
    private Button btnRefreshLogs;
    private Button btnClearLogs;
    private TextView textStatus;
    private TextView textHomeVideoShortcutTitle;
    private TextView homeTextOutputWidthSummary;
    private TextView homeTextCropRangeSummary;
    private TextView textOutputWidthSummary;
    private TextView textCropRangeSummary;
    private TextView textDisplayOutput;
    private TextView textLogOutput;
    private View settingsPanel;
    private boolean syncingVideoControls = false;
    private int settingsTapCount = 0;
    private long lastSettingsTapTime = 0L;

    // Tab 切换（飞屏 / 按键映射 / 启动 / 侧边栏）
    private static final int TAB_CAST = 0;
    private static final int TAB_MAPPING = 1;
    private static final int TAB_AUTOSTART = 2;
    private static final int TAB_SIDEBAR = 3;
    private View layoutCastHome;
    private View layoutKeyMapping;
    private View layoutAutostart;
    private View layoutSidebar;
    private Button btnTabCast;
    private Button btnTabMapping;
    private Button btnTabAutostart;
    private Button btnTabSidebar;
    private int currentTab = TAB_CAST;

    // 按键映射（嵌入样式，与飞屏同风格）
    private TextView keyStatus;
    private Button keyAccessibilityButton;
    private LinearLayout keyMappings;
    private boolean keyLearning = false;
    private Button btnKeyDoubleTapCast;
    private boolean doubleTapLearning = false;

    // 启动（开机自启 / 开机启动其他应用）
    private Switch switchAutostartBoot;
    private Switch switchAutostartEnabled;
    private LinearLayout layoutAutostartSelected;
    private Button btnAutostartAdd;
    private EditText editAutostartDelay;
    private EditText editAutostartInterval;
    private Switch switchAutostartReturnHome;
    private final ArrayList<String> autostartPackages = new ArrayList<>();

    // 侧边栏
    private Switch switchSidebarEnabled;
    private Switch switchSidebarVibrate;
    private Spinner spinnerSidebarSide;
    private Spinner spinnerSidebarColor;
    private SeekBar seekbarSidebarCorner;
    private TextView tvSidebarCornerVal;
    private SeekBar seekbarSidebarBlur;
    private TextView tvSidebarBlurVal;
    private SeekBar seekbarSidebarAlpha;
    private TextView tvSidebarAlphaVal;
    private LinearLayout layoutSidebarSelected;
    private Button btnSidebarOverlay;
    private final ArrayList<String> sidebarEntries = new ArrayList<>();

    private final Runnable keyCapturePoll = new Runnable() {
        @Override
        public void run() {
            String captured = KeyMappingManager.consumeCaptured(HongqiFeatureActivity.this);
            if (captured != null) {
                keyLearning = false;
                Toast.makeText(HongqiFeatureActivity.this,
                    "已记录 " + KeyMappingManager.keyLabel(captured), Toast.LENGTH_SHORT).show();
                refreshKeyMapping();
            }
            String doubleTapCaptured = KeyMappingManager.consumeDoubleTapCastCaptured(HongqiFeatureActivity.this);
            if (doubleTapCaptured != null) {
                doubleTapLearning = false;
                Toast.makeText(HongqiFeatureActivity.this,
                    "投屏键已设为 " + KeyMappingManager.keyLabel(doubleTapCaptured),
                    Toast.LENGTH_SHORT).show();
                updateDoubleTapCastButton();
            }
            if (keyLearning || doubleTapLearning) {
                handler.postDelayed(this, 120L);
            }
        }
    };

    private interface KeyAppChoice {
        void onSelected(String packageName);
    }

    // Region 2 source type & image picker
    private Spinner homeSpinnerSourceType2;
    private View homeLayoutImagePicker2;
    private Button homeBtnSelectImage2;
    private TextView homeTextSelectedImage2;
    private Spinner settingsSpinnerSourceType2;
    private View settingsLayoutImagePicker2;
    private Button settingsBtnSelectImage2;
    private TextView settingsTextSelectedImage2;

    // Region 2 UI（主页）
    private View homeRegion2Panel;
    private View homeRegion2Controls;
    private Switch homeSwitchRegion2;
    private SeekBar homeSeekOutputWidth2;
    private TextView homeTextOutputWidthSummary2;
    private Spinner homeSpinnerOutputAlign2;
    private CropSelectionView homeCropSelectionView2;
    private TextView homeTextCropRangeSummary2;

    // Region 2 UI（设置页）
    private Switch settingsSwitchRegion2;
    private Spinner spinnerStretchMode2;
    private SeekBar seekOutputWidth2;
    private TextView textOutputWidthSummary2;
    private Spinner spinnerOutputAlign2;
    private CropSelectionView cropSelectionView2;
    private TextView textCropRangeSummary2;

    // Region 3 source type & image picker
    private Spinner homeSpinnerSourceType3;
    private View homeLayoutImagePicker3;
    private Button homeBtnSelectImage3;
    private TextView homeTextSelectedImage3;
    private Spinner settingsSpinnerSourceType3;
    private View settingsLayoutImagePicker3;
    private Button settingsBtnSelectImage3;
    private TextView settingsTextSelectedImage3;

    // Region 3 UI（主页）
    private View homeRegion3Panel;
    private View homeRegion3Controls;
    private Switch homeSwitchRegion3;
    private SeekBar homeSeekOutputWidth3;
    private TextView homeTextOutputWidthSummary3;
    private Spinner homeSpinnerOutputAlign3;
    private CropSelectionView homeCropSelectionView3;
    private TextView homeTextCropRangeSummary3;

    // Region 3 UI（设置页）
    private Switch settingsSwitchRegion3;
    private Spinner spinnerStretchMode3;
    private SeekBar seekOutputWidth3;
    private TextView textOutputWidthSummary3;
    private Spinner spinnerOutputAlign3;
    private CropSelectionView cropSelectionView3;
    private TextView textCropRangeSummary3;

    private Handler handler = new Handler(Looper.getMainLooper());

    // 拉伸方式选项：输出仍固定为仪表 1920x720，这里控制画面如何铺到宽屏仪表。
    private static final String[] STRETCH_MODE_LABELS = {
        "满屏拉伸（铺满仪表）",
        "等比完整显示（可能留黑边）",
        "等比填满裁切（可能裁边）",
        "适应宽度",
        "适应高度",
        "居中原比例"
    };
    private static final String[] STRETCH_MODE_VALUES = {
        "stretch_fill",
        "fit_center",
        "center_crop",
        "fit_width",
        "fit_height",
        "center_inside"
    };
    private static int getStretchModeIndex(String mode) {
        for (int i = 0; i < STRETCH_MODE_VALUES.length; i++) {
            if (STRETCH_MODE_VALUES[i].equals(mode)) return i;
        }
        return 0;
    }

    private static final String[] OUTPUT_ALIGN_LABELS = {
        "居左",
        "居中",
        "居右"
    };
    private static final String[] OUTPUT_ALIGN_VALUES = {
        "left",
        "center",
        "right"
    };

    private static final String[] SOURCE_TYPE_LABELS = {
        "屏幕采集",
        "图片",
        "音乐"
    };
    private static final String[] SOURCE_TYPE_VALUES = {
        "screen",
        "image",
        "music"
    };

    // Service连接
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ScreenCaptureService.LocalBinder binder = (ScreenCaptureService.LocalBinder) service;
            captureService = binder.getService();
            serviceBound = true;
            InAppLogger.i(TAG, "屏幕采集服务已连接：" + captureService.getDebugState());
            updateStatus();

            if (!ensureClusterMirrorSupported()) {
                permissionRequested = false;
                return;
            }

            // 如果之前需要启动推流
            if (permissionRequested) {
                permissionRequested = false;
                doStartCaptureWithServiceReady();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            captureService = null;
            InAppLogger.w(TAG, "屏幕采集服务已断开");
            updateStatus();
        }
    };

    // 权限请求
    private final ActivityResultLauncher<Intent> projectionLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (!ensureClusterMirrorSupported()) {
                pendingProjectionToken = -1;
                return;
            }
            int projectionToken = pendingProjectionToken;
            pendingProjectionToken = -1;
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                if (serviceBound && captureService != null) {
                    InAppLogger.i(TAG, "屏幕录制权限已授权，开始通知本地镜像服务");
                    Intent start = new Intent(this, ScreenCaptureService.class)
                        .setAction(ScreenCaptureService.ACTION_START_GRANTED)
                        .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.getResultCode())
                        .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.getData())
                        .putExtra(ScreenCaptureService.EXTRA_SESSION_TOKEN, projectionToken);
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(this, start);
                    } catch (RuntimeException e) {
                        captureService.cancelPendingStart(projectionToken);
                        InAppLogger.e(TAG, "无法启动投屏服务", e);
                        Toast.makeText(this, "无法启动投屏，请重试", Toast.LENGTH_LONG).show();
                    }
                    updateStatus();
                } else {
                    InAppLogger.e(TAG, "屏幕录制权限已授权，但服务未连接");
                    Toast.makeText(this, "服务连接失败", Toast.LENGTH_SHORT).show();
                }
            } else {
                if (captureService != null && projectionToken >= 0) {
                    captureService.cancelPendingStart(projectionToken);
                }
                InAppLogger.w(TAG, "用户或系统拒绝了屏幕录制权限");
                Toast.makeText(this, "屏幕录制权限被拒绝", Toast.LENGTH_SHORT).show();
                configManager.setEnabled(false);
                switchEnabled.setChecked(false);
            }
        }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 单 Activity 架构：未激活就在当前 Activity 内显示激活界面并联网校验，
        // 不存在可被绕过的第二个 Activity；校验通过后 recreate() 重走 onCreate 进入主界面。
        if (!ActivationGuard.isSecureRunning(this)) {
            ActivationGuard.requireActivated(this);
            return;
        }

        setContentView(R.layout.hongqi_activity_main);

        configManager = new ConfigManager(this);

initViews();
        loadConfig();
        autoDownloadDefaultImages();
        setupListeners();
        applyClusterMirrorCapabilityState();
        handleAutoStartIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (configManager != null) handleAutoStartIntent(intent);
    }

    private void handleAutoStartIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra(EXTRA_CAMERA_PERMISSION, false)) {
            intent.removeExtra(EXTRA_CAMERA_PERMISSION);
            featurePermissions.launch(new String[]{android.Manifest.permission.CAMERA});
        }
        if (intent.getBooleanExtra(ScreenCaptureService.EXTRA_AUTO_START, false)) {
            intent.removeExtra(ScreenCaptureService.EXTRA_AUTO_START);
            handler.postDelayed(this::startCapture, 250);
        }
    }

    private void initViews() {
        findViewById(R.id.hongqi_btn_diauto_home).setOnClickListener(v -> {
            startActivity(com.hongqi.flighting.integration.FeatureNavigation.homeIntent(this)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        switchEnabled = findViewById(R.id.hongqi_switch_enabled);
        switchSimulatorPreview = findViewById(R.id.hongqi_switch_simulator_preview);
        homeSpinnerOutputAlign = findViewById(R.id.hongqi_home_spinner_output_align);
        homeCropSelectionView = findViewById(R.id.hongqi_home_crop_selection_view);
        spinnerStretchMode = findViewById(R.id.hongqi_spinner_stretch_mode);
        spinnerOutputAlign = findViewById(R.id.hongqi_spinner_output_align);
        cropSelectionView = findViewById(R.id.hongqi_crop_selection_view);
        homeSeekOutputWidth = findViewById(R.id.hongqi_home_seek_output_width);
        seekOutputWidth = findViewById(R.id.hongqi_seek_output_width);
        btnStart = findViewById(R.id.hongqi_btn_start);
        btnStop = findViewById(R.id.hongqi_btn_stop);
        btnSave = findViewById(R.id.hongqi_btn_save);
        btnReset = findViewById(R.id.hongqi_btn_reset);
        btnCast = findViewById(R.id.hongqi_btn_cast);
        btnSettings = findViewById(R.id.hongqi_btn_settings);
        btnSettingsBack = findViewById(R.id.hongqi_btn_settings_back);
        btnRefreshDisplays = findViewById(R.id.hongqi_btn_refresh_displays);
        btnRefreshLogs = findViewById(R.id.hongqi_btn_refresh_logs);
        btnClearLogs = findViewById(R.id.hongqi_btn_clear_logs);
        textStatus = findViewById(R.id.hongqi_text_status);
        textHomeVideoShortcutTitle = findViewById(R.id.hongqi_text_home_video_shortcut_title);
        homeTextOutputWidthSummary = findViewById(R.id.hongqi_home_text_output_width_summary);
        homeTextCropRangeSummary = findViewById(R.id.hongqi_home_text_crop_range_summary);
        textOutputWidthSummary = findViewById(R.id.hongqi_text_output_width_summary);
        textCropRangeSummary = findViewById(R.id.hongqi_text_crop_range_summary);
        textDisplayOutput = findViewById(R.id.hongqi_text_display_output);
        textLogOutput = findViewById(R.id.hongqi_text_log_output);
        settingsPanel = findViewById(R.id.hongqi_layout_settings_panel);

        layoutCastHome = findViewById(R.id.hongqi_layout_cast_home);
        layoutKeyMapping = findViewById(R.id.hongqi_layout_key_mapping);
        layoutAutostart = findViewById(R.id.hongqi_layout_autostart);
        layoutSidebar = findViewById(R.id.hongqi_layout_sidebar);
        btnTabCast = findViewById(R.id.hongqi_btn_tab_cast);
        btnTabMapping = findViewById(R.id.hongqi_btn_tab_mapping);
        btnTabAutostart = findViewById(R.id.hongqi_btn_tab_autostart);
        btnTabSidebar = findViewById(R.id.hongqi_btn_tab_sidebar);
        keyStatus = findViewById(R.id.hongqi_key_status);
        keyAccessibilityButton = findViewById(R.id.hongqi_btn_key_accessibility);
        keyMappings = findViewById(R.id.hongqi_layout_key_mappings);
        keyAccessibilityButton.setOnClickListener(v -> openKeyAccessibilitySettings());
        btnKeyDoubleTapCast = findViewById(R.id.hongqi_btn_key_double_tap_cast);
        btnKeyDoubleTapCast.setOnClickListener(v -> beginDoubleTapCastLearning());
        findViewById(R.id.hongqi_btn_key_media_access).setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.hongqi_btn_key_add_mapping).setOnClickListener(v -> chooseKeyAction());
        findViewById(R.id.hongqi_btn_key_clear_all).setOnClickListener(v -> {
            KeyMappingManager.clearMappings(HongqiFeatureActivity.this);
            refreshKeyMapping();
        });

        // 启动 Tab 控件
        switchAutostartBoot = findViewById(R.id.hongqi_switch_autostart_boot);
        switchAutostartEnabled = findViewById(R.id.hongqi_switch_autostart_enabled);
        layoutAutostartSelected = findViewById(R.id.hongqi_layout_autostart_selected);
        btnAutostartAdd = findViewById(R.id.hongqi_btn_autostart_add);
        editAutostartDelay = findViewById(R.id.hongqi_edit_autostart_delay);
        editAutostartInterval = findViewById(R.id.hongqi_edit_autostart_interval);
        switchAutostartReturnHome = findViewById(R.id.hongqi_switch_autostart_return_home);
        btnAutostartAdd.setOnClickListener(v -> chooseAutoStartApp());
        findViewById(R.id.hongqi_btn_autostart_save).setOnClickListener(v -> saveAutoStart());
        switchAutostartBoot.setOnCheckedChangeListener((buttonView, isChecked) ->
            AppAutoStartManager.preferences(this).edit().putBoolean("boot", isChecked).commit());
        switchAutostartEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
            AppAutoStartManager.preferences(this).edit().putBoolean(AppAutoStartManager.KEY_ENABLED, isChecked).commit());
        switchAutostartReturnHome.setOnCheckedChangeListener((buttonView, isChecked) ->
            AppAutoStartManager.preferences(this).edit().putBoolean(AppAutoStartManager.KEY_RETURN_HOME, isChecked).commit());

        // 侧边栏 Tab 控件
        switchSidebarEnabled = findViewById(R.id.hongqi_switch_sidebar_enabled);
        switchSidebarVibrate = findViewById(R.id.hongqi_switch_sidebar_vibrate);
        spinnerSidebarSide = findViewById(R.id.hongqi_spinner_sidebar_side);
        spinnerSidebarColor = findViewById(R.id.hongqi_spinner_sidebar_color);
        seekbarSidebarCorner = findViewById(R.id.hongqi_seekbar_sidebar_corner);
        tvSidebarCornerVal = findViewById(R.id.hongqi_tv_sidebar_corner_val);
        seekbarSidebarBlur = findViewById(R.id.hongqi_seekbar_sidebar_blur);
        tvSidebarBlurVal = findViewById(R.id.hongqi_tv_sidebar_blur_val);
        seekbarSidebarAlpha = findViewById(R.id.hongqi_seekbar_sidebar_alpha);
        tvSidebarAlphaVal = findViewById(R.id.hongqi_tv_sidebar_alpha_val);
        layoutSidebarSelected = findViewById(R.id.hongqi_layout_sidebar_selected);
        spinnerSidebarSide.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"左侧", "右侧"}));
        spinnerSidebarColor.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            SidebarPrefs.PRESET_NAMES));
        // SeekBar 实时显示数值
        seekbarSidebarCorner.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvSidebarCornerVal.setText(String.valueOf(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        seekbarSidebarBlur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvSidebarBlurVal.setText(String.valueOf(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        seekbarSidebarAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvSidebarAlphaVal.setText((progress * 100 / 255) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        btnSidebarOverlay = findViewById(R.id.hongqi_btn_sidebar_overlay);
        btnSidebarOverlay.setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.hongqi_btn_sidebar_add_app).setOnClickListener(v -> chooseSidebarApp());
        findViewById(R.id.hongqi_btn_sidebar_add_widget).setOnClickListener(v -> chooseSidebarWidget());
        findViewById(R.id.hongqi_btn_sidebar_save).setOnClickListener(v -> saveSidebar());

        // Region 2
        homeRegion2Panel = findViewById(R.id.hongqi_home_region2_panel);
        homeRegion2Controls = findViewById(R.id.hongqi_home_region2_controls);
        homeSwitchRegion2 = findViewById(R.id.hongqi_home_switch_region2);
        homeSeekOutputWidth2 = findViewById(R.id.hongqi_home_seek_output_width_2);
        homeTextOutputWidthSummary2 = findViewById(R.id.hongqi_home_text_output_width_summary_2);
        homeSpinnerOutputAlign2 = findViewById(R.id.hongqi_home_spinner_output_align_2);
        homeCropSelectionView2 = findViewById(R.id.hongqi_home_crop_selection_view_2);
        homeTextCropRangeSummary2 = findViewById(R.id.hongqi_home_text_crop_range_summary_2);
        settingsSwitchRegion2 = findViewById(R.id.hongqi_settings_switch_region2);
        spinnerStretchMode2 = findViewById(R.id.hongqi_spinner_stretch_mode_2);
        seekOutputWidth2 = findViewById(R.id.hongqi_seek_output_width_2);
        textOutputWidthSummary2 = findViewById(R.id.hongqi_text_output_width_summary_2);
        spinnerOutputAlign2 = findViewById(R.id.hongqi_spinner_output_align_2);
        cropSelectionView2 = findViewById(R.id.hongqi_crop_selection_view_2);
        textCropRangeSummary2 = findViewById(R.id.hongqi_text_crop_range_summary_2);
        homeSpinnerSourceType2 = findViewById(R.id.hongqi_home_spinner_source_type_2);
        homeLayoutImagePicker2 = findViewById(R.id.hongqi_home_layout_image_picker_2);
        homeBtnSelectImage2 = findViewById(R.id.hongqi_home_btn_select_image_2);
        homeTextSelectedImage2 = findViewById(R.id.hongqi_home_text_selected_image_2);
        settingsSpinnerSourceType2 = findViewById(R.id.hongqi_settings_spinner_source_type_2);
        settingsLayoutImagePicker2 = findViewById(R.id.hongqi_settings_layout_image_picker_2);
        settingsBtnSelectImage2 = findViewById(R.id.hongqi_settings_btn_select_image_2);
        settingsTextSelectedImage2 = findViewById(R.id.hongqi_settings_text_selected_image_2);

        // Region 3
        homeRegion3Panel = findViewById(R.id.hongqi_home_region3_panel);
        homeRegion3Controls = findViewById(R.id.hongqi_home_region3_controls);
        homeSwitchRegion3 = findViewById(R.id.hongqi_home_switch_region3);
        homeSeekOutputWidth3 = findViewById(R.id.hongqi_home_seek_output_width_3);
        homeTextOutputWidthSummary3 = findViewById(R.id.hongqi_home_text_output_width_summary_3);
        homeSpinnerOutputAlign3 = findViewById(R.id.hongqi_home_spinner_output_align_3);
        homeCropSelectionView3 = findViewById(R.id.hongqi_home_crop_selection_view_3);
        homeTextCropRangeSummary3 = findViewById(R.id.hongqi_home_text_crop_range_summary_3);
        settingsSwitchRegion3 = findViewById(R.id.hongqi_settings_switch_region3);
        spinnerStretchMode3 = findViewById(R.id.hongqi_spinner_stretch_mode_3);
        seekOutputWidth3 = findViewById(R.id.hongqi_seek_output_width_3);
        textOutputWidthSummary3 = findViewById(R.id.hongqi_text_output_width_summary_3);
        spinnerOutputAlign3 = findViewById(R.id.hongqi_spinner_output_align_3);
        cropSelectionView3 = findViewById(R.id.hongqi_crop_selection_view_3);
        textCropRangeSummary3 = findViewById(R.id.hongqi_text_crop_range_summary_3);
        homeSpinnerSourceType3 = findViewById(R.id.hongqi_home_spinner_source_type_3);
        homeLayoutImagePicker3 = findViewById(R.id.hongqi_home_layout_image_picker_3);
        homeBtnSelectImage3 = findViewById(R.id.hongqi_home_btn_select_image_3);
        homeTextSelectedImage3 = findViewById(R.id.hongqi_home_text_selected_image_3);
        settingsSpinnerSourceType3 = findViewById(R.id.hongqi_settings_spinner_source_type_3);
        settingsLayoutImagePicker3 = findViewById(R.id.hongqi_settings_layout_image_picker_3);
        settingsBtnSelectImage3 = findViewById(R.id.hongqi_settings_btn_select_image_3);
        settingsTextSelectedImage3 = findViewById(R.id.hongqi_settings_text_selected_image_3);

        spinnerStretchMode.setAdapter(createStretchModeAdapter());
        homeSpinnerOutputAlign.setAdapter(createOutputAlignAdapter());
        spinnerOutputAlign.setAdapter(createOutputAlignAdapter());

        spinnerStretchMode2.setAdapter(createStretchModeAdapter());
        homeSpinnerOutputAlign2.setAdapter(createOutputAlignAdapter());
        spinnerOutputAlign2.setAdapter(createOutputAlignAdapter());

        spinnerStretchMode3.setAdapter(createStretchModeAdapter());
        homeSpinnerOutputAlign3.setAdapter(createOutputAlignAdapter());
        spinnerOutputAlign3.setAdapter(createOutputAlignAdapter());

        homeSpinnerSourceType2.setAdapter(createSourceTypeAdapter());
        settingsSpinnerSourceType2.setAdapter(createSourceTypeAdapter());
        homeSpinnerSourceType3.setAdapter(createSourceTypeAdapter());
        settingsSpinnerSourceType3.setAdapter(createSourceTypeAdapter());
    }

    private ArrayAdapter<String> createStretchModeAdapter() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.hongqi_spinner_item, STRETCH_MODE_LABELS);
        adapter.setDropDownViewResource(R.layout.hongqi_spinner_dropdown_item);
        return adapter;
    }

    private ArrayAdapter<String> createOutputAlignAdapter() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.hongqi_spinner_item, OUTPUT_ALIGN_LABELS);
        adapter.setDropDownViewResource(R.layout.hongqi_spinner_dropdown_item);
        return adapter;
    }

    private ArrayAdapter<String> createSourceTypeAdapter() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.hongqi_spinner_item, SOURCE_TYPE_LABELS);
        adapter.setDropDownViewResource(R.layout.hongqi_spinner_dropdown_item);
        return adapter;
    }

    private void loadConfig() {
        switchEnabled.setChecked(configManager.isEnabled());
        switchSimulatorPreview.setChecked(configManager.isSimulatorPreview());

        syncingVideoControls = true;

        spinnerStretchMode.setSelection(getStretchModeIndex(configManager.getStretchMode()));

        int outputWidthPercent = configManager.getOutputWidthPercent();
        homeSeekOutputWidth.setProgress(outputWidthPercent);
        seekOutputWidth.setProgress(outputWidthPercent);

        String outputAlign = configManager.getOutputHorizontalAlign();
        for (int i = 0; i < OUTPUT_ALIGN_VALUES.length; i++) {
            if (OUTPUT_ALIGN_VALUES[i].equals(outputAlign)) {
                homeSpinnerOutputAlign.setSelection(i);
                spinnerOutputAlign.setSelection(i);
                break;
            }
        }

        cropSelectionView.setCropRange(
            configManager.getCropStartX(),
            configManager.getCropEndX(),
            configManager.getCropStartY(),
            configManager.getCropEndY()
        );
        homeCropSelectionView.setCropRange(
            configManager.getCropStartX(),
            configManager.getCropEndX(),
            configManager.getCropStartY(),
            configManager.getCropEndY()
        );
        syncingVideoControls = false;
        updateOutputWidthSummary(outputWidthPercent);
        updateCropRangeSummary();

        // 加载区域2配置
        boolean r2enabled = configManager.isRegion2Enabled();
        homeSwitchRegion2.setChecked(r2enabled);
        settingsSwitchRegion2.setChecked(r2enabled);
        homeRegion2Controls.setVisibility(r2enabled ? View.VISIBLE : View.GONE);
        spinnerStretchMode2.setSelection(getStretchModeIndex(configManager.getRegion2StretchMode()));
        int r2width = configManager.getRegion2OutputWidthPercent();
        homeSeekOutputWidth2.setProgress(r2width);
        seekOutputWidth2.setProgress(r2width);
        String r2align = configManager.getRegion2OutputHorizontalAlign();
        for (int i = 0; i < OUTPUT_ALIGN_VALUES.length; i++) {
            if (OUTPUT_ALIGN_VALUES[i].equals(r2align)) {
                homeSpinnerOutputAlign2.setSelection(i);
                spinnerOutputAlign2.setSelection(i);
                break;
            }
        }
        homeCropSelectionView2.setCropRange(
            configManager.getRegion2CropStartX(),
            configManager.getRegion2CropEndX(),
            configManager.getRegion2CropStartY(),
            configManager.getRegion2CropEndY()
        );
        cropSelectionView2.setCropRange(
            configManager.getRegion2CropStartX(),
            configManager.getRegion2CropEndX(),
            configManager.getRegion2CropStartY(),
            configManager.getRegion2CropEndY()
        );
        updateRegion2WidthSummary(r2width);
        updateRegion2CropSummary();
        // 区域2 来源类型和图片
        loadRegionSourceType(2);

        // 加载区域3配置
        boolean r3enabled = configManager.isRegion3Enabled();
        homeSwitchRegion3.setChecked(r3enabled);
        settingsSwitchRegion3.setChecked(r3enabled);
        homeRegion3Controls.setVisibility(r3enabled ? View.VISIBLE : View.GONE);
        spinnerStretchMode3.setSelection(getStretchModeIndex(configManager.getRegion3StretchMode()));
        int r3width = configManager.getRegion3OutputWidthPercent();
        homeSeekOutputWidth3.setProgress(r3width);
        seekOutputWidth3.setProgress(r3width);
        String r3align = configManager.getRegion3OutputHorizontalAlign();
        for (int i = 0; i < OUTPUT_ALIGN_VALUES.length; i++) {
            if (OUTPUT_ALIGN_VALUES[i].equals(r3align)) {
                homeSpinnerOutputAlign3.setSelection(i);
                spinnerOutputAlign3.setSelection(i);
                break;
            }
        }
        homeCropSelectionView3.setCropRange(
            configManager.getRegion3CropStartX(),
            configManager.getRegion3CropEndX(),
            configManager.getRegion3CropStartY(),
            configManager.getRegion3CropEndY()
        );
        cropSelectionView3.setCropRange(
            configManager.getRegion3CropStartX(),
            configManager.getRegion3CropEndX(),
            configManager.getRegion3CropStartY(),
            configManager.getRegion3CropEndY()
        );
        updateRegion3WidthSummary(r3width);
        updateRegion3CropSummary();
        // 区域3 来源类型和图片
        loadRegionSourceType(3);

        updateStatus();
    }

    private void setupListeners() {
        // 保存按钮 - 保存配置
        btnSave.setOnClickListener(v -> {
            if (saveConfig()) {
                Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
                InAppLogger.i(TAG, "用户已成功保存设置");
            }
        });

        btnSettings.setVisibility(View.GONE);
        btnSettings.setOnClickListener(v -> showSettingsPanel());
        textHomeVideoShortcutTitle.setOnClickListener(v -> handleHiddenSettingsTap());
        btnSettingsBack.setOnClickListener(v -> hideSettingsPanel());
        btnRefreshDisplays.setOnClickListener(v -> refreshDisplayInfo());
        spinnerStretchMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!syncingVideoControls) {
                    syncStretchModeFromSettings(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        homeSpinnerOutputAlign.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!syncingVideoControls) {
                    syncOutputAlignFromHome(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spinnerOutputAlign.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!syncingVideoControls) {
                    syncOutputAlignFromSettings(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        homeSeekOutputWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !syncingVideoControls) {
                    syncOutputWidthFromHome(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        seekOutputWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !syncingVideoControls) {
                    syncOutputWidthFromSettings(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        homeCropSelectionView.setOnCropChangeListener((startX, endX, startY, endY) -> syncCropRangeFromHome(startX, endX, startY, endY));
        cropSelectionView.setOnCropChangeListener((startX, endX, startY, endY) -> syncCropRangeFromSettings(startX, endX, startY, endY));
        btnRefreshLogs.setOnClickListener(v -> refreshLogs());
        btnClearLogs.setOnClickListener(v -> {
            InAppLogger.clear();
            textLogOutput.setText("");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        textLogOutput.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        btnCast.setOnClickListener(v -> {
            if (serviceBound && captureService != null && captureService.isRunning()) {
                stopCasting();
            } else {
                startCasting();
            }
        });
        // 重置按钮
        btnReset.setOnClickListener(v -> {
            configManager.resetToDefaults();
            loadConfig();
            Toast.makeText(this, "已重置为默认值", Toast.LENGTH_SHORT).show();
        });

        // 启动按钮
        btnStart.setOnClickListener(v -> startCasting());

        // 停止按钮
        btnStop.setOnClickListener(v -> stopCasting());

        // 启用开关
        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !ensureClusterMirrorSupported()) {
                return;
            }
            configManager.setEnabled(isChecked);
        });

        // 模拟器预览开关（无第二屏时回退到主屏显示）
        switchSimulatorPreview.setOnCheckedChangeListener((buttonView, isChecked) ->
            configManager.setSimulatorPreview(isChecked));

        // ====== 区域2 监听器 ======
        setupRegionListeners(2);

        // ====== 区域3 监听器 ======
        setupRegionListeners(3);

        // 来源类型切换监听
        setupSourceTypeListeners(2);
        setupSourceTypeListeners(3);

        setupTabListeners();
    }

    private void setupTabListeners() {
        btnTabCast.setOnClickListener(v -> showTab(TAB_CAST));
        btnTabMapping.setOnClickListener(v -> showTab(TAB_MAPPING));
        btnTabAutostart.setOnClickListener(v -> showTab(TAB_AUTOSTART));
        btnTabSidebar.setOnClickListener(v -> showTab(TAB_SIDEBAR));
    }

    private void showTab(int tab) {
        currentTab = tab;
        layoutCastHome.setVisibility(tab == TAB_CAST ? View.VISIBLE : View.GONE);
        layoutKeyMapping.setVisibility(tab == TAB_MAPPING ? View.VISIBLE : View.GONE);
        layoutAutostart.setVisibility(tab == TAB_AUTOSTART ? View.VISIBLE : View.GONE);
        layoutSidebar.setVisibility(tab == TAB_SIDEBAR ? View.VISIBLE : View.GONE);
        styleTabButton(btnTabCast, tab == TAB_CAST);
        styleTabButton(btnTabMapping, tab == TAB_MAPPING);
        styleTabButton(btnTabAutostart, tab == TAB_AUTOSTART);
        styleTabButton(btnTabSidebar, tab == TAB_SIDEBAR);
        if (tab == TAB_MAPPING) {
            refreshKeyMapping();
        } else if (tab == TAB_AUTOSTART) {
            refreshAutoStart();
        } else if (tab == TAB_SIDEBAR) {
            refreshSidebar();
        }
    }

    private void styleTabButton(Button btn, boolean active) {
        btn.setBackgroundResource(active ? R.drawable.hongqi_bg_tab_active : R.drawable.hongqi_bg_tab_inactive);
        btn.setTextColor(getResources().getColor(active ? R.color.hongqi_brand_gold : R.color.hongqi_muted_text));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ====== 按键映射（嵌入本页 Tab，与飞屏统一风格，不复开独立页面） ======

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyMappingManager.handleKey(this, event, null)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int keyColor(int resId) {
        return getResources().getColor(resId);
    }

    private TextView keyText(String text, int sizeSp, int colorRes) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(keyColor(colorRes));
        return tv;
    }

    private LinearLayout.LayoutParams keyMatchWrap(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private Button keyRowButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15.0f);
        b.setTextColor(keyColor(R.color.hongqi_text));
        b.setMinHeight(dp(54));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackgroundResource(R.drawable.hongqi_bg_field);
        return b;
    }

    private String keyAppName(String packageName) {
        try {
            return String.valueOf(getPackageManager()
                .getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0)));
        } catch (Exception e) {
            return packageName;
        }
    }

    private boolean isKeyAccessibilityEnabled() {
        return KeyMappingAccessibilityService.isEnabledInSettings(this);
    }

    private boolean isKeyMediaAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
            "enabled_notification_listeners");
        if (enabled == null) {
            return false;
        }
        return enabled.toLowerCase().contains(
            new ComponentName(this, TargetMediaSessionService.class).flattenToString().toLowerCase());
    }

    private void openKeyAccessibilitySettings() {
        try {
            Intent intent = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private void beginKeyLearning(String action, String app) {
        KeyMappingManager.beginLearning(this, action, app);
        keyLearning = true;
        keyStatus.setText("等待方向盘按键：" + KeyActionDispatcher.label(this, action)
            + "\n" + KeyMappingManager.appLabel(this, app));
        handler.removeCallbacksAndMessages(null);
        KeyMappingManager.cancelLearningDoubleTapCast(this);
        handler.post(keyCapturePoll);
    }

    private void beginDoubleTapCastLearning() {
        KeyMappingManager.beginLearningDoubleTapCast(this);
        doubleTapLearning = true;
        keyStatus.setText("等待方向盘按键（投屏键）：请按一个方向盘按键");
        handler.removeCallbacksAndMessages(null);
        KeyMappingManager.cancelLearningDoubleTapCast(this);
        handler.post(keyCapturePoll);
    }

    private void updateDoubleTapCastButton() {
        if (btnKeyDoubleTapCast == null) {
            return;
        }
        String fp = KeyMappingManager.getDoubleTapCastFingerprint(this);
        btnKeyDoubleTapCast.setText(fp == null
            ? "投屏键：未设置"
            : "投屏键：" + KeyMappingManager.keyLabel(fp));
    }

    private interface DialogItemClickListener {
        void onItemClick(int which);
    }

    private interface DialogInputListener {
        void onSubmit(String text);
    }

    // 统一风格的列表选择弹框（暗色卡片 + 金色标题，避免系统默认弹框标题不清晰）
    private void showStyledListDialog(String title, String[] items, final DialogItemClickListener listener) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.hongqi_bg_panel);

        TextView titleView = keyText(title, 18, R.color.hongqi_brand_gold);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);

        View divider = new View(this);
        divider.setBackgroundColor(keyColor(R.color.hongqi_divider));

        int listHeight = Math.min(dp(320), items.length * dp(52));
        ScrollView scroll = new ScrollView(this);
        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listLayout, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView item = keyText(items[i], 16, R.color.hongqi_text);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(8), dp(15), dp(8), dp(15));
            item.setOnClickListener(v -> {
                dialog.dismiss();
                listener.onItemClick(index);
            });
            listLayout.addView(item, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            View line = new View(this);
            line.setBackgroundColor(keyColor(R.color.hongqi_divider));
            listLayout.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }

        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText("取消");
        cancel.setTextSize(16.0f);
        cancel.setTextColor(keyColor(R.color.hongqi_brand_gold));
        cancel.setBackgroundResource(R.drawable.hongqi_bg_outline_button);
        cancel.setOnClickListener(v -> dialog.dismiss());

        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(0, dp(16), 0, 0);
        buttonBar.addView(cancel, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        container.addView(titleView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        container.addView(divider, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        container.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, listHeight));
        container.addView(buttonBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(container);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // 统一风格的输入弹框（手动输入包名等）
    private void showStyledInputDialog(String title, String hint, String positiveLabel, final DialogInputListener listener) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.hongqi_bg_panel);

        TextView titleView = keyText(title, 18, R.color.hongqi_brand_gold);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);

        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(keyColor(R.color.hongqi_text));
        input.setHintTextColor(keyColor(R.color.hongqi_subtitle));
        input.setBackgroundResource(R.drawable.hongqi_bg_field);

        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText("取消");
        cancel.setTextSize(16.0f);
        cancel.setTextColor(keyColor(R.color.hongqi_brand_gold));
        cancel.setBackgroundResource(R.drawable.hongqi_bg_outline_button);
        cancel.setOnClickListener(v -> dialog.dismiss());

        Button confirm = new Button(this);
        confirm.setAllCaps(false);
        confirm.setText(positiveLabel);
        confirm.setTextSize(16.0f);
        confirm.setTextColor(keyColor(R.color.hongqi_text));
        confirm.setBackgroundResource(R.drawable.hongqi_bg_primary_button);
        confirm.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(HongqiFeatureActivity.this, "包名不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            listener.onSubmit(text);
        });

        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(0, dp(16), 0, 0);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(46), 1.0f);
        cancelLp.rightMargin = dp(8);
        buttonBar.addView(cancel, cancelLp);
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, dp(46), 1.0f);
        confirmLp.leftMargin = dp(8);
        buttonBar.addView(confirm, confirmLp);

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        inputLp.topMargin = dp(16);

        container.addView(titleView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        container.addView(input, inputLp);
        container.addView(buttonBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(container);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void chooseKeyAction() {
        final String[] actions = {
            KeyActionDispatcher.PREVIOUS,
            KeyActionDispatcher.PLAY_PAUSE,
            KeyActionDispatcher.NEXT
        };
        showStyledListDialog("选择动作", new String[]{"上一曲", "播放 / 暂停", "下一曲"},
            which -> chooseKeyTargetApp(actions[which]));
    }

    private void chooseKeyApp(String title, final KeyAppChoice appChoice) {
        final List<ResolveInfo> launchables = getPackageManager().queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Collections.sort(launchables, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(HongqiFeatureActivity.this.getPackageManager()))
                    .compareToIgnoreCase(String.valueOf(
                        b.loadLabel(HongqiFeatureActivity.this.getPackageManager())));
            }
        });
        String[] items = new String[launchables.size() + 1];
        for (int i = 0; i < launchables.size(); i++) {
            items[i] = String.valueOf(launchables.get(i).loadLabel(getPackageManager()));
        }
        items[launchables.size()] = "手动输入包名…";
        showStyledListDialog(title, items, which -> {
            if (which == launchables.size()) {
                manualKeyPackageInput(appChoice);
            } else {
                appChoice.onSelected(launchables.get(which).activityInfo.packageName);
            }
        });
    }

    private void manualKeyPackageInput(final KeyAppChoice appChoice) {
        showStyledInputDialog("手动输入包名", "输入目标应用包名", "绑定并学习",
            pkg -> appChoice.onSelected(pkg));
    }

    private void chooseKeyTargetApp(final String action) {
        chooseKeyApp("选择要绑定的音乐应用", new KeyAppChoice() {
            @Override
            public void onSelected(String packageName) {
                beginKeyLearning(action, packageName);
            }
        });
    }

    private LinearLayout createKeyAppGroup(String packageName) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(16), dp(14), dp(16), dp(16));
        group.setBackgroundResource(R.drawable.hongqi_bg_mapping_card);
        TextView name = keyText(keyAppName(packageName), 18, R.color.hongqi_title);
        name.setTypeface(null, Typeface.BOLD);
        group.addView(name, keyMatchWrap(0));
        TextView pkg = keyText(packageName, 12, R.color.hongqi_muted_text);
        pkg.setPadding(0, dp(2), 0, dp(3));
        group.addView(pkg, keyMatchWrap(0));
        return group;
    }

    private void refreshKeyMapping() {
        if (keyStatus == null || keyMappings == null) {
            return;
        }
        boolean enabled = isKeyAccessibilityEnabled();
        boolean connected = KeyMappingAccessibilityService.isRunning();
        keyStatus.setText(
            (!enabled ? "按键映射服务：未开启（必须）"
                : connected ? "按键映射服务：已连接"
                : "按键映射服务：已开启但未连接（请关闭后重新开启）")
                + "\n"
                + (isKeyMediaAccessEnabled() ? "媒体会话访问：已开启" : "媒体会话访问：未开启"));
        if (keyAccessibilityButton != null) {
            keyAccessibilityButton.setText(
                !enabled ? "开启按键映射服务（必须）"
                    : connected ? "按键映射服务已连接"
                    : "修复全局服务（关闭后重新开启）");
        }
        updateDoubleTapCastButton();
        keyMappings.removeAllViews();
        List<KeyMappingManager.Mapping> all = KeyMappingManager.all(this);
        if (all.isEmpty()) {
            TextView empty = keyText("暂无自定义映射", 15, R.color.hongqi_subtitle);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            empty.setBackgroundResource(R.drawable.hongqi_bg_mapping_card);
            keyMappings.addView(empty, keyMatchWrap(0));
            return;
        }
        Collections.sort(all, new Comparator<KeyMappingManager.Mapping>() {
            @Override
            public int compare(KeyMappingManager.Mapping a, KeyMappingManager.Mapping b) {
                int byName = keyAppName(a.packageName).compareToIgnoreCase(keyAppName(b.packageName));
                if (byName != 0) {
                    return byName;
                }
                int byPkg = a.packageName.compareToIgnoreCase(b.packageName);
                if (byPkg != 0) {
                    return byPkg;
                }
                return a.fingerprint.compareTo(b.fingerprint);
            }
        });
        String lastPackage = null;
        LinearLayout group = null;
        for (final KeyMappingManager.Mapping mapping : all) {
            if (!mapping.packageName.equals(lastPackage)) {
                lastPackage = mapping.packageName;
                group = createKeyAppGroup(mapping.packageName);
                keyMappings.addView(group, keyMatchWrap(dp(8)));
            }
            Button row = keyRowButton(KeyActionDispatcher.label(this, mapping.action) + "\n"
                + KeyMappingManager.keyLabel(mapping.fingerprint) + "    点击删除");
            row.setOnClickListener(view -> {
                KeyMappingManager.removeMapping(HongqiFeatureActivity.this,
                    mapping.fingerprint, mapping.packageName);
                refreshKeyMapping();
            });
            if (group != null) {
                group.addView(row, keyMatchWrap(dp(7)));
            }
        }
    }

    // ====== 启动 Tab（开机自启 / 开机启动其他应用，嵌入本页，不复开独立页面） ======

    private void refreshAutoStart() {
        if (switchAutostartBoot == null) {
            return;
        }
        SharedPreferences prefs = AppAutoStartManager.preferences(this);
        switchAutostartBoot.setChecked(prefs.getBoolean("boot", false));
        switchAutostartEnabled.setChecked(prefs.getBoolean(AppAutoStartManager.KEY_ENABLED, false));
        editAutostartDelay.setText(String.valueOf(prefs.getInt(AppAutoStartManager.KEY_START_DELAY, 5)));
        editAutostartInterval.setText(String.valueOf(prefs.getInt(AppAutoStartManager.KEY_INTERVAL, 3)));
        switchAutostartReturnHome.setChecked(prefs.getBoolean(AppAutoStartManager.KEY_RETURN_HOME, true));
        autostartPackages.clear();
        autostartPackages.addAll(AppAutoStartManager.getPackages(this));
        refreshAutoStartSelectedApps();
    }

    private int parseAutoStartNumber(EditText edit, int fallback) {
        try {
            return Math.max(0, Math.min(300, Integer.parseInt(edit.getText().toString().trim())));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void saveAutoStart() {
        AppAutoStartManager.preferences(this).edit()
            .putBoolean("boot", switchAutostartBoot.isChecked())
            .putBoolean(AppAutoStartManager.KEY_ENABLED, switchAutostartEnabled.isChecked())
            .putInt(AppAutoStartManager.KEY_START_DELAY, parseAutoStartNumber(editAutostartDelay, 5))
            .putInt(AppAutoStartManager.KEY_INTERVAL, parseAutoStartNumber(editAutostartInterval, 3))
            .putBoolean(AppAutoStartManager.KEY_RETURN_HOME, switchAutostartReturnHome.isChecked())
            .commit();
        AppAutoStartManager.savePackages(this, autostartPackages);
        Toast.makeText(this, "启动设置已保存", Toast.LENGTH_SHORT).show();
    }

    private void chooseAutoStartApp() {
        final List<ResolveInfo> launchables = getPackageManager().queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Collections.sort(launchables, (a, b) -> String.valueOf(a.loadLabel(getPackageManager()))
            .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager()))));
        ArrayList<String> labels = new ArrayList<>();
        for (ResolveInfo info : launchables) {
            labels.add(String.valueOf(info.loadLabel(getPackageManager())));
        }
        labels.add("手动输入包名…");
        int manualIndex = launchables.size();
        showStyledListDialog("添加启动应用", labels.toArray(new String[0]), which -> {
            if (which == manualIndex) {
                manualAutoStartInput();
            } else {
                addAutoStartPackage(launchables.get(which).activityInfo.packageName);
            }
        });
    }

    private void manualAutoStartInput() {
        showStyledInputDialog("手动输入包名", "输入目标应用包名", "添加", this::addAutoStartPackage);
    }

    private void addAutoStartPackage(String pkg) {
        if (autostartPackages.contains(pkg)) {
            Toast.makeText(this, "该应用已添加", Toast.LENGTH_SHORT).show();
            return;
        }
        autostartPackages.add(pkg);
        AppAutoStartManager.savePackages(this, autostartPackages);
        refreshAutoStartSelectedApps();
    }

    private void refreshAutoStartSelectedApps() {
        if (layoutAutostartSelected == null) {
            return;
        }
        layoutAutostartSelected.removeAllViews();
        if (autostartPackages.isEmpty()) {
            TextView empty = keyText("还没有选择应用", 15, R.color.hongqi_subtitle);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(20), dp(10), dp(20));
            empty.setBackgroundResource(R.drawable.hongqi_bg_mapping_card);
            layoutAutostartSelected.addView(empty, keyMatchWrap(0));
            return;
        }
        for (final String pkg : new ArrayList<>(autostartPackages)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(12), dp(10));
            row.setBackgroundResource(R.drawable.hongqi_bg_mapping_card);
            layoutAutostartSelected.addView(row, keyMatchWrap(dp(6)));

            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try {
                icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
            } catch (Exception ignored) {
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            textLp.leftMargin = dp(12);
            row.addView(texts, textLp);
            TextView name = keyText(keyAppName(pkg), 16, R.color.hongqi_title);
            texts.addView(name, keyMatchWrap(0));
            TextView pkgView = keyText(pkg, 12, R.color.hongqi_muted_text);
            pkgView.setPadding(0, dp(2), 0, 0);
            texts.addView(pkgView, keyMatchWrap(0));

            Button remove = new Button(this);
            remove.setAllCaps(false);
            remove.setText("移除");
            remove.setTextSize(14.0f);
            remove.setTextColor(keyColor(R.color.hongqi_brand_red));
            remove.setBackgroundResource(R.drawable.hongqi_bg_outline_button);
            remove.setOnClickListener(view -> {
                autostartPackages.remove(pkg);
                AppAutoStartManager.savePackages(HongqiFeatureActivity.this, autostartPackages);
                refreshAutoStartSelectedApps();
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(72), dp(40)));
        }
    }

    // ================= 侧边栏 Tab =================

    private void refreshSidebar() {
        if (switchSidebarEnabled == null) {
            return;
        }
        switchSidebarEnabled.setChecked(SidebarPrefs.isEnabled(this));
        switchSidebarVibrate.setChecked(SidebarPrefs.vibrate(this));
        spinnerSidebarSide.setSelection(SidebarPrefs.side(this) == SidebarPrefs.SIDE_RIGHT ? 1 : 0);
        spinnerSidebarColor.setSelection(SidebarPrefs.colorIndex(this));
        seekbarSidebarCorner.setProgress(SidebarPrefs.cornerRadius(this));
        tvSidebarCornerVal.setText(String.valueOf(SidebarPrefs.cornerRadius(this)));
        seekbarSidebarBlur.setProgress(SidebarPrefs.blurRadius(this));
        tvSidebarBlurVal.setText(String.valueOf(SidebarPrefs.blurRadius(this)));
        seekbarSidebarAlpha.setProgress(SidebarPrefs.panelAlpha(this));
        tvSidebarAlphaVal.setText((SidebarPrefs.panelAlpha(this) * 100 / 255) + "%");
        loadSidebarEntries();
        refreshSidebarSelectedApps();
        updateSidebarOverlayButton();
    }

    private void updateSidebarOverlayButton() {
        if (btnSidebarOverlay != null) {
            btnSidebarOverlay.setText((Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) ? "已授权" : "去开启");
        }
    }

    private void saveSidebar() {
        int side = spinnerSidebarSide.getSelectedItemPosition() == 1
            ? SidebarPrefs.SIDE_RIGHT : SidebarPrefs.SIDE_LEFT;
        int colorIdx = spinnerSidebarColor.getSelectedItemPosition();
        int bgColor = SidebarPrefs.PRESET_COLORS[colorIdx];
        int corner = seekbarSidebarCorner.getProgress();
        int blur = seekbarSidebarBlur.getProgress();
        int alpha = seekbarSidebarAlpha.getProgress();
        SidebarPrefs.get(this).edit()
            .putBoolean(SidebarPrefs.KEY_ENABLED, switchSidebarEnabled.isChecked())
            .putBoolean(SidebarPrefs.KEY_VIBRATE, switchSidebarVibrate.isChecked())
            .putInt(SidebarPrefs.KEY_SIDE, side)
            .putInt(SidebarPrefs.KEY_BG_COLOR, bgColor)
            .putInt(SidebarPrefs.KEY_CORNER_RADIUS, corner)
            .putInt(SidebarPrefs.KEY_BLUR_RADIUS, blur)
            .putInt(SidebarPrefs.KEY_PANEL_ALPHA, alpha)
            .commit();
        persistSidebarEntries();
        if (switchSidebarEnabled.isChecked() && Build.VERSION.SDK_INT >= 33
            && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            featurePermissions.launch(new String[]{android.Manifest.permission.POST_NOTIFICATIONS});
        }
        applySidebarService();
        Toast.makeText(this, "侧边栏设置已保存", Toast.LENGTH_SHORT).show();
    }

    private void applySidebarService() {
        if (!switchSidebarEnabled.isChecked()) {
            stopService(new Intent(this, SidebarService.class));
        } else if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            SidebarService.start(this, SidebarService.ACTION_REFRESH);
        } else {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show();
        }
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void loadSidebarEntries() {
        sidebarEntries.clear();
        String apps = SidebarPrefs.apps(this);
        if (apps == null) {
            return;
        }
        for (String token : apps.split("\n")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                sidebarEntries.add(t);
            }
        }
    }

    private void persistSidebarEntries() {
        StringBuilder sb = new StringBuilder();
        for (String e : sidebarEntries) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e);
        }
        sb.append('\n');
        SidebarPrefs.get(this).edit().putString(SidebarPrefs.KEY_APPS, sb.toString()).commit();
    }

    private void chooseSidebarApp() {
        final List<ResolveInfo> launchables = querySidebarLaunchables();
        ArrayList<String> labels = new ArrayList<>();
        for (ResolveInfo info : launchables) {
            labels.add(String.valueOf(info.loadLabel(getPackageManager())));
        }
        labels.add("手动输入包名…");
        int manualIndex = launchables.size();
        showStyledListDialog("添加侧边栏应用", labels.toArray(new String[0]), which -> {
            if (which == manualIndex) {
                showStyledInputDialog("手动输入包名", "输入目标应用包名", "添加",
                    this::addSidebarAppByPackage);
            } else {
                ResolveInfo info = launchables.get(which);
                addSidebarEntry(info.activityInfo.packageName + ":" + info.activityInfo.name);
            }
        });
    }

    private void addSidebarAppByPackage(String pkg) {
        for (ResolveInfo info : querySidebarLaunchables()) {
            if (pkg.equals(info.activityInfo.packageName)) {
                addSidebarEntry(info.activityInfo.packageName + ":" + info.activityInfo.name);
                return;
            }
        }
        Toast.makeText(this, "未找到该应用的启动入口", Toast.LENGTH_SHORT).show();
    }

    private void chooseSidebarWidget() {
        int[] all = SidebarWidgets.ALL;
        String[] labels = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            labels[i] = SidebarWidgets.label(all[i]);
        }
        showStyledListDialog("添加侧边栏控件", labels, which -> addSidebarEntry("widget:" + all[which]));
    }

    private void addSidebarEntry(String entry) {
        loadSidebarEntries();
        if (sidebarEntries.contains(entry)) {
            Toast.makeText(this, "该项已添加", Toast.LENGTH_SHORT).show();
            return;
        }
        sidebarEntries.add(entry);
        persistSidebarEntries();
        refreshSidebarSelectedApps();
    }

    private void removeSidebarEntry(String entry) {
        sidebarEntries.remove(entry);
        persistSidebarEntries();
        refreshSidebarSelectedApps();
    }

    private List<ResolveInfo> querySidebarLaunchables() {
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Collections.sort(list, (a, b) -> String.valueOf(a.loadLabel(getPackageManager()))
            .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager()))));
        return list;
    }

    private ResolveInfo resolveSidebarApp(String entry) {
        for (ResolveInfo info : querySidebarLaunchables()) {
            String key = info.activityInfo.packageName + ":" + info.activityInfo.name;
            if (entry.contains(key)) {
                return info;
            }
        }
        return null;
    }

    private String sidebarEntryLabel(String entry) {
        if (entry.startsWith("widget:")) {
            try {
                return SidebarWidgets.label(
                    Integer.parseInt(entry.substring("widget:".length()).trim()));
            } catch (Exception e) {
                return entry;
            }
        }
        ResolveInfo info = resolveSidebarApp(entry);
        if (info != null) {
            return String.valueOf(info.loadLabel(getPackageManager()));
        }
        return entry;
    }

    private void refreshSidebarSelectedApps() {
        if (layoutSidebarSelected == null) {
            return;
        }
        layoutSidebarSelected.removeAllViews();
        if (sidebarEntries.isEmpty()) {
            TextView empty = keyText("还没有添加项目（默认显示返回 / 桌面 / 添加）", 15, R.color.hongqi_subtitle);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(16), dp(10), dp(16));
            empty.setBackgroundResource(R.drawable.hongqi_bg_mapping_card);
            layoutSidebarSelected.addView(empty, keyMatchWrap(0));
            return;
        }
        for (final String entry : new ArrayList<>(sidebarEntries)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(12), dp(8));
            row.setBackgroundResource(R.drawable.hongqi_bg_mapping_card);
            layoutSidebarSelected.addView(row, keyMatchWrap(dp(6)));

            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (entry.startsWith("widget:")) {
                try {
                    int code = Integer.parseInt(entry.substring("widget:".length()).trim());
                    icon.setImageResource(SidebarWidgets.icon(code));
                } catch (Exception e) {
                    icon.setImageResource(R.drawable.hongqi_ic_sidebar_apps);
                }
            } else {
                ResolveInfo info = resolveSidebarApp(entry);
                if (info != null) {
                    icon.setImageDrawable(info.loadIcon(getPackageManager()));
                } else {
                    icon.setImageResource(R.drawable.hongqi_ic_sidebar_apps);
                }
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            TextView name = keyText(sidebarEntryLabel(entry), 15, R.color.hongqi_title);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            textLp.leftMargin = dp(12);
            row.addView(name, textLp);

            Button remove = new Button(this);
            remove.setAllCaps(false);
            remove.setText("移除");
            remove.setTextSize(14.0f);
            remove.setTextColor(keyColor(R.color.hongqi_brand_red));
            remove.setBackgroundResource(R.drawable.hongqi_bg_outline_button);
            remove.setOnClickListener(view -> removeSidebarEntry(entry));
            row.addView(remove, new LinearLayout.LayoutParams(dp(72), dp(40)));
        }
    }

    private void setupRegionListeners(int regionIndex) {
        Switch homeSwitch, settingsSwitch;
        View homePanel;
        Spinner settingsSpinnerStretch;
        SeekBar homeSeekWidth, settingsSeekWidth;
        Spinner homeSpinnerAlign, settingsSpinnerAlign;
        CropSelectionView homeCropView, settingsCropView;

        if (regionIndex == 2) {
            homeSwitch = homeSwitchRegion2;
            settingsSwitch = settingsSwitchRegion2;
            homePanel = homeRegion2Controls;
            settingsSpinnerStretch = spinnerStretchMode2;
            homeSeekWidth = homeSeekOutputWidth2;
            settingsSeekWidth = seekOutputWidth2;
            homeSpinnerAlign = homeSpinnerOutputAlign2;
            settingsSpinnerAlign = spinnerOutputAlign2;
            homeCropView = homeCropSelectionView2;
            settingsCropView = cropSelectionView2;
        } else {
            homeSwitch = homeSwitchRegion3;
            settingsSwitch = settingsSwitchRegion3;
            homePanel = homeRegion3Controls;
            settingsSpinnerStretch = spinnerStretchMode3;
            homeSeekWidth = homeSeekOutputWidth3;
            settingsSeekWidth = seekOutputWidth3;
            homeSpinnerAlign = homeSpinnerOutputAlign3;
            settingsSpinnerAlign = spinnerOutputAlign3;
            homeCropView = homeCropSelectionView3;
            settingsCropView = cropSelectionView3;
        }

        homeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            homePanel.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            settingsSwitch.setChecked(isChecked);
            if (regionIndex == 2) {
                configManager.setRegion2Enabled(isChecked);
            } else {
                configManager.setRegion3Enabled(isChecked);
            }
            InAppLogger.i(TAG, "区域" + regionIndex + " " + (isChecked ? "已开启" : "已关闭"));
        });
        settingsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            homeSwitch.setChecked(isChecked);
        });

        settingsSpinnerStretch.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!syncingVideoControls) {
                    syncRegionStretchFromSettings(regionIndex, position);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 输出宽度
        homeSeekWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !syncingVideoControls) {
                    syncRegionWidthFromHome(regionIndex, progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        settingsSeekWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !syncingVideoControls) {
                    syncRegionWidthFromSettings(regionIndex, progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 水平对齐
        homeSpinnerAlign.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!syncingVideoControls) {
                    syncRegionAlignFromHome(regionIndex, position);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        settingsSpinnerAlign.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!syncingVideoControls) {
                    syncRegionAlignFromSettings(regionIndex, position);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 裁剪范围
        if (regionIndex == 2) {
            homeCropView.setOnCropChangeListener((sx, ex, sy, ey) -> syncRegionCropFromHome(2, sx, ex, sy, ey));
            settingsCropView.setOnCropChangeListener((sx, ex, sy, ey) -> syncRegionCropFromSettings(2, sx, ex, sy, ey));
        } else {
            homeCropView.setOnCropChangeListener((sx, ex, sy, ey) -> syncRegionCropFromHome(3, sx, ex, sy, ey));
            settingsCropView.setOnCropChangeListener((sx, ex, sy, ey) -> syncRegionCropFromSettings(3, sx, ex, sy, ey));
        }
    }

    private void setupSourceTypeListeners(int regionIndex) {
        Spinner homeSpinner, settingsSpinner;
        View homeImagePicker, settingsImagePicker;
        Button homeBtnPick, settingsBtnPick;
        TextView homeTextImg, settingsTextImg;

        if (regionIndex == 2) {
            homeSpinner = homeSpinnerSourceType2;
            settingsSpinner = settingsSpinnerSourceType2;
            homeImagePicker = homeLayoutImagePicker2;
            settingsImagePicker = settingsLayoutImagePicker2;
            homeBtnPick = homeBtnSelectImage2;
            settingsBtnPick = settingsBtnSelectImage2;
            homeTextImg = homeTextSelectedImage2;
            settingsTextImg = settingsTextSelectedImage2;
        } else {
            homeSpinner = homeSpinnerSourceType3;
            settingsSpinner = settingsSpinnerSourceType3;
            homeImagePicker = homeLayoutImagePicker3;
            settingsImagePicker = settingsLayoutImagePicker3;
            homeBtnPick = homeBtnSelectImage3;
            settingsBtnPick = settingsBtnSelectImage3;
            homeTextImg = homeTextSelectedImage3;
            settingsTextImg = settingsTextSelectedImage3;
        }

        AdapterView.OnItemSelectedListener sourceListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (syncingVideoControls) return;
                boolean isImage = position == 1;
                homeImagePicker.setVisibility(isImage ? View.VISIBLE : View.GONE);
                settingsImagePicker.setVisibility(isImage ? View.VISIBLE : View.GONE);
                syncingVideoControls = true;
                if (parent == homeSpinner) {
                    settingsSpinner.setSelection(position);
                } else {
                    homeSpinner.setSelection(position);
                }
                syncingVideoControls = false;
                if (regionIndex == 2) {
                    configManager.setRegion2SourceType(SOURCE_TYPE_VALUES[position]);
                } else {
                    configManager.setRegion3SourceType(SOURCE_TYPE_VALUES[position]);
                }
                InAppLogger.i(TAG, "区域" + regionIndex + "来源类型已保存：" + SOURCE_TYPE_VALUES[position]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };

        homeSpinner.setOnItemSelectedListener(sourceListener);
        settingsSpinner.setOnItemSelectedListener(sourceListener);

        View.OnClickListener pickListener = v -> showImagePickerDialog(regionIndex);
        homeBtnPick.setOnClickListener(pickListener);
        settingsBtnPick.setOnClickListener(pickListener);
    }

    private void loadRegionSourceType(int regionIndex) {
        Spinner homeSpinner, settingsSpinner;
        View homeImagePicker, settingsImagePicker;
        TextView homeTextImg, settingsTextImg;

        if (regionIndex == 2) {
            homeSpinner = homeSpinnerSourceType2;
            settingsSpinner = settingsSpinnerSourceType2;
            homeImagePicker = homeLayoutImagePicker2;
            settingsImagePicker = settingsLayoutImagePicker2;
            homeTextImg = homeTextSelectedImage2;
            settingsTextImg = settingsTextSelectedImage2;
        } else {
            homeSpinner = homeSpinnerSourceType3;
            settingsSpinner = settingsSpinnerSourceType3;
            homeImagePicker = homeLayoutImagePicker3;
            settingsImagePicker = settingsLayoutImagePicker3;
            homeTextImg = homeTextSelectedImage3;
            settingsTextImg = settingsTextSelectedImage3;
        }

        String sourceType = (regionIndex == 2)
            ? configManager.getRegion2SourceType()
            : configManager.getRegion3SourceType();
        String imageName = (regionIndex == 2)
            ? configManager.getRegion2ImageName()
            : configManager.getRegion3ImageName();

        syncingVideoControls = true;
        for (int i = 0; i < SOURCE_TYPE_VALUES.length; i++) {
            if (SOURCE_TYPE_VALUES[i].equals(sourceType)) {
                homeSpinner.setSelection(i);
                settingsSpinner.setSelection(i);
                break;
            }
        }
        syncingVideoControls = false;

        boolean isImage = "image".equals(sourceType);
        homeImagePicker.setVisibility(isImage ? View.VISIBLE : View.GONE);
        settingsImagePicker.setVisibility(isImage ? View.VISIBLE : View.GONE);

        if (imageName != null && !imageName.isEmpty()) {
            homeTextImg.setText(imageName);
            settingsTextImg.setText(imageName);
        } else {
            homeTextImg.setText("未选择");
            settingsTextImg.setText("未选择");
        }
    }

    private String saveRegionSourceType(int regionIndex) {
        Spinner spinner = (regionIndex == 2) ? settingsSpinnerSourceType2 : settingsSpinnerSourceType3;
        int pos = spinner.getSelectedItemPosition();
        return pos >= 0 && pos < SOURCE_TYPE_VALUES.length ? SOURCE_TYPE_VALUES[pos] : "screen";
    }

    private String saveRegionImageName(int regionIndex) {
        TextView tv = (regionIndex == 2) ? settingsTextSelectedImage2 : settingsTextSelectedImage3;
        String text = tv.getText().toString();
        return text.equals("未选择") ? "" : text;
    }

    private void showImagePickerDialog(final int regionIndex) {
        if (imageApiClient == null) {
            imageApiClient = new ImageApiClient(this);
        }
        Toast.makeText(this, "正在从服务器加载图片…", Toast.LENGTH_SHORT).show();
        imageApiClient.listImages(new ImageApiClient.Callback<List<ImageApiClient.ImageInfo>>() {
            @Override
            public void onSuccess(List<ImageApiClient.ImageInfo> images) {
                if (images == null || images.isEmpty()) {
                    Toast.makeText(HongqiFeatureActivity.this, "服务端暂无图片，请先在管理后台上传", Toast.LENGTH_SHORT).show();
                    return;
                }
                final List<ImageApiClient.ImageInfo> imageList = images;
                String[] labels = new String[imageList.size()];
                for (int i = 0; i < imageList.size(); i++) {
                    String name = imageList.get(i).name;
                    labels[i] = (name == null || name.isEmpty()) ? imageList.get(i).id : name;
                }
                showStyledListDialog("选择图片", labels, which -> {
                    ImageApiClient.ImageInfo info = imageList.get(which);
                    applySelectedImage(regionIndex, info);
                });
            }

            @Override
            public void onError(String message) {
                Toast.makeText(HongqiFeatureActivity.this, "加载图片失败：" + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applySelectedImage(int regionIndex, ImageApiClient.ImageInfo info) {
        TextView homeText = (regionIndex == 2) ? homeTextSelectedImage2 : homeTextSelectedImage3;
        TextView settingsText = (regionIndex == 2) ? settingsTextSelectedImage2 : settingsTextSelectedImage3;
        String label = (info.name == null || info.name.isEmpty()) ? info.id : info.name;
        homeText.setText(label);
        settingsText.setText(label);
        if (regionIndex == 2) {
            configManager.setRegion2ImageName(label);
            configManager.setRegion2ImageUrl(info.url);
            configManager.setRegion2ImageLocalPath("");
        } else {
            configManager.setRegion3ImageName(label);
            configManager.setRegion3ImageUrl(info.url);
            configManager.setRegion3ImageLocalPath("");
        }
        cacheImageToLocal(regionIndex, info);
        InAppLogger.i(TAG, "区域" + regionIndex + "选择图片：" + label + " -> " + info.url);
    }

    /** 选图后异步下载到本地缓存，投屏时优先使用本地文件，避免启动时阻塞网络。 */
    private void cacheImageToLocal(final int regionIndex, final ImageApiClient.ImageInfo info) {
        new Thread(() -> {
            String path = ImageApiClient.downloadToCache(HongqiFeatureActivity.this, info.url, info.id);
            if (path == null) {
                return;
            }
            runOnUiThread(() -> {
                if (regionIndex == 2) {
                    configManager.setRegion2ImageLocalPath(path);
                } else {
                    configManager.setRegion3ImageLocalPath(path);
                }
                InAppLogger.i(TAG, "区域" + regionIndex + "图片已缓存到本地：" + path);
            });
        }, "image-cache").start();
    }

    /** 首次加载时，若区域使用默认图片名且尚未设置 URL/本地缓存，则按文件名匹配服务端图片列表并自动下载缓存。 */
    private void autoDownloadDefaultImages() {
        final boolean needR2 = "image".equals(configManager.getRegion2SourceType())
            && isBlank(configManager.getRegion2ImageUrl())
            && isBlank(configManager.getRegion2ImageLocalPath())
            && !isBlank(configManager.getRegion2ImageName());
        final boolean needR3 = "image".equals(configManager.getRegion3SourceType())
            && isBlank(configManager.getRegion3ImageUrl())
            && isBlank(configManager.getRegion3ImageLocalPath())
            && !isBlank(configManager.getRegion3ImageName());
        if (!needR2 && !needR3) {
            return;
        }
        if (imageApiClient == null) {
            imageApiClient = new ImageApiClient(this);
        }
        final String r2Name = configManager.getRegion2ImageName();
        final String r3Name = configManager.getRegion3ImageName();
        imageApiClient.listImages(new ImageApiClient.Callback<List<ImageApiClient.ImageInfo>>() {
            @Override
            public void onSuccess(List<ImageApiClient.ImageInfo> images) {
                if (images == null || images.isEmpty()) {
                    return;
                }
                if (needR2) {
                    ImageApiClient.ImageInfo match = findImageByName(images, r2Name);
                    if (match != null) {
                        configManager.setRegion2ImageUrl(match.url);
                        cacheImageToLocal(2, match);
                        InAppLogger.i(TAG, "区域2默认图片自动匹配：" + match.name);
                    }
                }
                if (needR3) {
                    ImageApiClient.ImageInfo match = findImageByName(images, r3Name);
                    if (match != null) {
                        configManager.setRegion3ImageUrl(match.url);
                        cacheImageToLocal(3, match);
                        InAppLogger.i(TAG, "区域3默认图片自动匹配：" + match.name);
                    }
                }
            }

            @Override
            public void onError(String message) {
                InAppLogger.w(TAG, "自动下载默认图片失败：" + message);
            }
        });
    }

    private ImageApiClient.ImageInfo findImageByName(List<ImageApiClient.ImageInfo> images, String name) {
        if (isBlank(name)) {
            return null;
        }
        for (ImageApiClient.ImageInfo info : images) {
            if (name.equals(info.name)) {
                return info;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean saveConfig() {
        int selectedStretchMode = spinnerStretchMode.getSelectedItemPosition();
        String stretchMode = selectedStretchMode >= 0 && selectedStretchMode < STRETCH_MODE_VALUES.length
            ? STRETCH_MODE_VALUES[selectedStretchMode]
            : STRETCH_MODE_VALUES[0];
        int cropStartX = cropSelectionView.getCropStartX();
        int cropEndX = cropSelectionView.getCropEndX();
        int cropStartY = cropSelectionView.getCropStartY();
        int cropEndY = cropSelectionView.getCropEndY();
        int outputWidthPercent = seekOutputWidth.getProgress();
        int outputAlignPosition = spinnerOutputAlign.getSelectedItemPosition();
        String outputHorizontalAlign = outputAlignPosition >= 0 && outputAlignPosition < OUTPUT_ALIGN_VALUES.length
            ? OUTPUT_ALIGN_VALUES[outputAlignPosition]
            : OUTPUT_ALIGN_VALUES[1];
        // 读取区域2宽度
        int r2Width = seekOutputWidth2.getProgress();
        int r3Width = seekOutputWidth3.getProgress();

        // 校验已开启区域的仪表占比总和 ≤ 100
        int totalWidth = outputWidthPercent;
        if (settingsSwitchRegion2.isChecked()) totalWidth += r2Width;
        if (settingsSwitchRegion3.isChecked()) totalWidth += r3Width;
        if (totalWidth > 100) {
            String errMsg = "已开启区域宽度之和 (" + totalWidth + "%) 不能超过 100%，请调小各区域宽度或在设置中关闭区域后再保存";
            InAppLogger.w(TAG, errMsg);
            Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show();
            return false;
        }

        configManager.saveLayout(stretchMode, cropStartX, cropEndX, cropStartY, cropEndY,
            outputWidthPercent, outputHorizontalAlign);

        // 保存区域2配置
        int r2StretchIdx = spinnerStretchMode2.getSelectedItemPosition();
        String r2StretchMode = r2StretchIdx >= 0 && r2StretchIdx < STRETCH_MODE_VALUES.length
            ? STRETCH_MODE_VALUES[r2StretchIdx] : STRETCH_MODE_VALUES[0];
        int r2AlignPos = spinnerOutputAlign2.getSelectedItemPosition();
        String r2Align = r2AlignPos >= 0 && r2AlignPos < OUTPUT_ALIGN_VALUES.length
            ? OUTPUT_ALIGN_VALUES[r2AlignPos] : OUTPUT_ALIGN_VALUES[1];
        int r2CropStartX = cropSelectionView2.getCropStartX();
        int r2CropEndX = cropSelectionView2.getCropEndX();
        int r2CropStartY = cropSelectionView2.getCropStartY();
        int r2CropEndY = cropSelectionView2.getCropEndY();
        configManager.setRegion2StretchMode(r2StretchMode);
        configManager.setRegion2OutputWidthPercent(r2Width);
        configManager.setRegion2OutputHorizontalAlign(r2Align);
        configManager.setRegion2CropRange(r2CropStartX, r2CropEndX, r2CropStartY, r2CropEndY);
        configManager.setRegion2SourceType(saveRegionSourceType(2));
        configManager.setRegion2ImageName(saveRegionImageName(2));

        // 保存区域3配置
        int r3StretchIdx = spinnerStretchMode3.getSelectedItemPosition();
        String r3StretchMode = r3StretchIdx >= 0 && r3StretchIdx < STRETCH_MODE_VALUES.length
            ? STRETCH_MODE_VALUES[r3StretchIdx] : STRETCH_MODE_VALUES[0];
        int r3AlignPos = spinnerOutputAlign3.getSelectedItemPosition();
        String r3Align = r3AlignPos >= 0 && r3AlignPos < OUTPUT_ALIGN_VALUES.length
            ? OUTPUT_ALIGN_VALUES[r3AlignPos] : OUTPUT_ALIGN_VALUES[1];
        int r3CropStartX = cropSelectionView3.getCropStartX();
        int r3CropEndX = cropSelectionView3.getCropEndX();
        int r3CropStartY = cropSelectionView3.getCropStartY();
        int r3CropEndY = cropSelectionView3.getCropEndY();
        configManager.setRegion3StretchMode(r3StretchMode);
        configManager.setRegion3OutputWidthPercent(r3Width);
        configManager.setRegion3OutputHorizontalAlign(r3Align);
        configManager.setRegion3CropRange(r3CropStartX, r3CropEndX, r3CropStartY, r3CropEndY);
        configManager.setRegion3SourceType(saveRegionSourceType(3));
        configManager.setRegion3ImageName(saveRegionImageName(3));

        InAppLogger.i(TAG, "布局配置已保存：R1拉伸=" + stretchMode
            + "，R1裁剪=" + cropStartX + ".." + cropEndX + "," + cropStartY + ".." + cropEndY
            + "，R1宽度=" + outputWidthPercent + "% 对齐=" + outputHorizontalAlign
            + "，R2拉伸=" + r2StretchMode + " 宽度=" + r2Width + "% 对齐=" + r2Align
            + "，R3拉伸=" + r3StretchMode + " 宽度=" + r3Width + "% 对齐=" + r3Align);
        return true;
    }

    private void syncStretchModeFromSettings(int position) {
        if (position < 0 || position >= STRETCH_MODE_VALUES.length) return;
        configManager.setStretchMode(STRETCH_MODE_VALUES[position]);
    }

    private void syncOutputWidthFromHome(int percent) {
        syncingVideoControls = true;
        seekOutputWidth.setProgress(percent);
        syncingVideoControls = false;
        updateOutputWidthSummary(percent);
        configManager.setOutputWidthPercent(percent);
        InAppLogger.i(TAG, "主页面视频快捷配置已保存：仪表占用宽度=" + percent + "%");
    }

    private void syncOutputWidthFromSettings(int percent) {
        syncingVideoControls = true;
        homeSeekOutputWidth.setProgress(percent);
        syncingVideoControls = false;
        updateOutputWidthSummary(percent);
    }

    private void syncOutputAlignFromHome(int position) {
        if (position < 0 || position >= OUTPUT_ALIGN_VALUES.length) {
            return;
        }
        syncingVideoControls = true;
        spinnerOutputAlign.setSelection(position);
        syncingVideoControls = false;
        configManager.setOutputHorizontalAlign(OUTPUT_ALIGN_VALUES[position]);
        InAppLogger.i(TAG, "主页面视频快捷配置已保存：水平对齐=" + OUTPUT_ALIGN_VALUES[position]);
    }

    private void syncOutputAlignFromSettings(int position) {
        if (position < 0 || position >= OUTPUT_ALIGN_VALUES.length) {
            return;
        }
        syncingVideoControls = true;
        homeSpinnerOutputAlign.setSelection(position);
        syncingVideoControls = false;
    }

    private void syncCropRangeFromHome(int startX, int endX, int startY, int endY) {
        if (syncingVideoControls) {
            return;
        }
        syncingVideoControls = true;
        cropSelectionView.setCropRange(startX, endX, startY, endY);
        syncingVideoControls = false;
        updateCropRangeSummary();
        configManager.setCropRange(startX, endX, startY, endY);
        InAppLogger.i(TAG, "主页面视频快捷配置已保存：裁剪范围=x" + startX + ".." + endX + " y" + startY + ".." + endY);
    }

    private void syncCropRangeFromSettings(int startX, int endX, int startY, int endY) {
        if (syncingVideoControls) {
            return;
        }
        syncingVideoControls = true;
        homeCropSelectionView.setCropRange(startX, endX, startY, endY);
        syncingVideoControls = false;
        updateCropRangeSummary();
    }

    // ==================== 区域2/3 同步方法 ====================

    private void syncRegionStretchFromSettings(int regionIndex, int position) {
        if (position < 0 || position >= STRETCH_MODE_VALUES.length) return;
        String value = STRETCH_MODE_VALUES[position];
        if (regionIndex == 2) {
            configManager.setRegion2StretchMode(value);
        } else {
            configManager.setRegion3StretchMode(value);
        }
        InAppLogger.i(TAG, "设置页区域" + regionIndex + "拉伸模式已保存：" + value);
    }

    private void syncRegionWidthFromHome(int regionIndex, int percent) {
        syncingVideoControls = true;
        SeekBar target = (regionIndex == 2) ? seekOutputWidth2 : seekOutputWidth3;
        target.setProgress(percent);
        syncingVideoControls = false;
        if (regionIndex == 2) {
            updateRegion2WidthSummary(percent);
            configManager.setRegion2OutputWidthPercent(percent);
        } else {
            updateRegion3WidthSummary(percent);
            configManager.setRegion3OutputWidthPercent(percent);
        }
        InAppLogger.i(TAG, "区域" + regionIndex + "主页宽度已保存：" + percent + "%");
    }

    private void syncRegionWidthFromSettings(int regionIndex, int percent) {
        syncingVideoControls = true;
        SeekBar target = (regionIndex == 2) ? homeSeekOutputWidth2 : homeSeekOutputWidth3;
        target.setProgress(percent);
        syncingVideoControls = false;
        if (regionIndex == 2) {
            updateRegion2WidthSummary(percent);
        } else {
            updateRegion3WidthSummary(percent);
        }
    }

    private void syncRegionAlignFromHome(int regionIndex, int position) {
        if (position < 0 || position >= OUTPUT_ALIGN_VALUES.length) return;
        syncingVideoControls = true;
        Spinner target = (regionIndex == 2) ? spinnerOutputAlign2 : spinnerOutputAlign3;
        target.setSelection(position);
        syncingVideoControls = false;
        String value = OUTPUT_ALIGN_VALUES[position];
        if (regionIndex == 2) {
            configManager.setRegion2OutputHorizontalAlign(value);
        } else {
            configManager.setRegion3OutputHorizontalAlign(value);
        }
        InAppLogger.i(TAG, "区域" + regionIndex + "主页水平对齐已保存：" + value);
    }

    private void syncRegionAlignFromSettings(int regionIndex, int position) {
        if (position < 0 || position >= OUTPUT_ALIGN_VALUES.length) return;
        syncingVideoControls = true;
        Spinner target = (regionIndex == 2) ? homeSpinnerOutputAlign2 : homeSpinnerOutputAlign3;
        target.setSelection(position);
        syncingVideoControls = false;
    }

    private void syncRegionCropFromHome(int regionIndex, int startX, int endX, int startY, int endY) {
        if (syncingVideoControls) return;
        syncingVideoControls = true;
        CropSelectionView target = (regionIndex == 2) ? cropSelectionView2 : cropSelectionView3;
        target.setCropRange(startX, endX, startY, endY);
        syncingVideoControls = false;
        if (regionIndex == 2) {
            updateRegion2CropSummary();
            configManager.setRegion2CropRange(startX, endX, startY, endY);
        } else {
            updateRegion3CropSummary();
            configManager.setRegion3CropRange(startX, endX, startY, endY);
        }
        InAppLogger.i(TAG, "区域" + regionIndex + "主页裁剪已保存：x" + startX + ".." + endX + " y" + startY + ".." + endY);
    }

    private void syncRegionCropFromSettings(int regionIndex, int startX, int endX, int startY, int endY) {
        if (syncingVideoControls) return;
        syncingVideoControls = true;
        CropSelectionView target = (regionIndex == 2) ? homeCropSelectionView2 : homeCropSelectionView3;
        target.setCropRange(startX, endX, startY, endY);
        syncingVideoControls = false;
        if (regionIndex == 2) {
            updateRegion2CropSummary();
        } else {
            updateRegion3CropSummary();
        }
    }

    private void updateRegion2WidthSummary(int percent) {
        String summary = "仪表占用宽度：" + percent + "%";
        if (homeTextOutputWidthSummary2 != null) homeTextOutputWidthSummary2.setText(summary);
        if (textOutputWidthSummary2 != null) textOutputWidthSummary2.setText(summary);
    }

    private void updateRegion2CropSummary() {
        if (textCropRangeSummary2 != null && homeTextCropRangeSummary2 != null && cropSelectionView2 != null) {
            String summary = "当前裁剪：水平 "
                + cropSelectionView2.getCropStartX() + "~" + cropSelectionView2.getCropEndX()
                + "，垂直 " + cropSelectionView2.getCropStartY() + "~" + cropSelectionView2.getCropEndY();
            textCropRangeSummary2.setText(summary);
            homeTextCropRangeSummary2.setText(summary);
        }
    }

    private void updateRegion3WidthSummary(int percent) {
        String summary = "仪表占用宽度：" + percent + "%";
        if (homeTextOutputWidthSummary3 != null) homeTextOutputWidthSummary3.setText(summary);
        if (textOutputWidthSummary3 != null) textOutputWidthSummary3.setText(summary);
    }

    private void updateRegion3CropSummary() {
        if (textCropRangeSummary3 != null && homeTextCropRangeSummary3 != null && cropSelectionView3 != null) {
            String summary = "当前裁剪：水平 "
                + cropSelectionView3.getCropStartX() + "~" + cropSelectionView3.getCropEndX()
                + "，垂直 " + cropSelectionView3.getCropStartY() + "~" + cropSelectionView3.getCropEndY();
            textCropRangeSummary3.setText(summary);
            homeTextCropRangeSummary3.setText(summary);
        }
    }

    private void toggleSettingsPanel() {
        if (settingsPanel == null) {
            return;
        }
        boolean showSettings = settingsPanel.getVisibility() != View.VISIBLE;
        settingsPanel.setVisibility(showSettings ? View.VISIBLE : View.GONE);
        btnSettings.setVisibility(View.GONE);
        if (showSettings) {
            refreshDisplayInfo();
            refreshLogs();
        }
    }

    private void showSettingsPanel() {
        if (settingsPanel != null) {
            settingsPanel.setVisibility(View.VISIBLE);
            refreshDisplayInfo();
            refreshLogs();
        }
        btnSettings.setVisibility(View.GONE);
        // 线性布局下需隐藏主面板与 Tab 栏，否则设置面板会与它们各占一半空间
        layoutCastHome.setVisibility(View.GONE);
        layoutKeyMapping.setVisibility(View.GONE);
        layoutAutostart.setVisibility(View.GONE);
        View tabBar = findViewById(R.id.hongqi_tab_bar);
        if (tabBar != null) {
            tabBar.setVisibility(View.GONE);
        }
    }

    private void handleHiddenSettingsTap() {
        long now = System.currentTimeMillis();
        if (now - lastSettingsTapTime > 1500) {
            settingsTapCount = 0;
        }
        lastSettingsTapTime = now;
        settingsTapCount++;
        if (settingsTapCount >= 5) {
            settingsTapCount = 0;
            showSettingsPanel();
            InAppLogger.i(TAG, "已通过连续点击标题 5 次打开隐藏设置页");
        }
    }

    private void hideSettingsPanel() {
        if (settingsPanel != null) {
            settingsPanel.setVisibility(View.GONE);
        }
        btnSettings.setVisibility(View.GONE);
        // 恢复之前选中的主面板与 Tab 栏
        View tabBar = findViewById(R.id.hongqi_tab_bar);
        if (tabBar != null) {
            tabBar.setVisibility(View.VISIBLE);
        }
        showTab(currentTab);
    }

    private void startCasting() {
        if (!ensureClusterMirrorSupported()) {
            return;
        }
        if (!saveConfig()) {
            return;
        }
        int r1w = seekOutputWidth.getProgress();
        int r2w = seekOutputWidth2.getProgress();
        int r3w = seekOutputWidth3.getProgress();
        int totalWidth = r1w;
        if (homeSwitchRegion2.isChecked()) totalWidth += r2w;
        if (homeSwitchRegion3.isChecked()) totalWidth += r3w;
        if (totalWidth > 100) {
            String errMsg = "已开启区域宽度之和 (" + totalWidth + "%) 不能超过 100%，请调小各区域宽度后再保存配置";
            InAppLogger.w(TAG, errMsg);
            Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show();
            return;
        }
        if (!validateEnabledRegionPositionsForStart()) {
            return;
        }

        InAppLogger.i(TAG, "用户请求仪表镜像");
        if (settingsPanel != null) {
            settingsPanel.setVisibility(View.GONE);
            btnSettings.setVisibility(View.GONE);
        }
        startCapture();
    }

    private boolean validateEnabledRegionPositionsForStart() {
        String r1Align = getSelectedAlignValue(spinnerOutputAlign);
        String r2Align = getSelectedAlignValue(spinnerOutputAlign2);
        String r3Align = getSelectedAlignValue(spinnerOutputAlign3);

        if (homeSwitchRegion2.isChecked() && r1Align.equals(r2Align)) {
            return showDuplicateRegionPositionError(1, 2, r1Align);
        }
        if (homeSwitchRegion3.isChecked() && r1Align.equals(r3Align)) {
            return showDuplicateRegionPositionError(1, 3, r1Align);
        }
        if (homeSwitchRegion2.isChecked() && homeSwitchRegion3.isChecked() && r2Align.equals(r3Align)) {
            return showDuplicateRegionPositionError(2, 3, r2Align);
        }
        return true;
    }

    private String getSelectedAlignValue(Spinner spinner) {
        int position = spinner.getSelectedItemPosition();
        if (position >= 0 && position < OUTPUT_ALIGN_VALUES.length) {
            return OUTPUT_ALIGN_VALUES[position];
        }
        return OUTPUT_ALIGN_VALUES[1];
    }

    private boolean showDuplicateRegionPositionError(int firstRegion, int secondRegion, String align) {
        String msg = "区域" + firstRegion + "和区域" + secondRegion + "不能同时选择" + getAlignLabel(align) + "，请调整后再保存配置";
        InAppLogger.w(TAG, msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        return false;
    }

    private String getAlignLabel(String align) {
        for (int i = 0; i < OUTPUT_ALIGN_VALUES.length; i++) {
            if (OUTPUT_ALIGN_VALUES[i].equals(align)) return OUTPUT_ALIGN_LABELS[i];
        }
        return align;
    }

    private void stopCasting() {
        InAppLogger.i(TAG, "用户请求停止投屏");
        pendingProjectionToken = -1;
        stopCapture();
    }

private void startCapture() {
        if (configManager == null || !ActivationGuard.isSecureRunning(this) || isFinishing()) return;
        if (!ensureClusterMirrorSupported()) {
            return;
        }
        if (!ensureOverlayPermissionGranted()) {
            return;
        }
        if (serviceBound && captureService != null) {
            InAppLogger.i(TAG, "服务已绑定，开始请求推流：" + captureService.getDebugState());
            doStartCaptureWithServiceReady();
        } else {
            // 需要等待服务绑定
            permissionRequested = true;
            InAppLogger.i(TAG, "开始镜像前等待服务绑定");
            Toast.makeText(this, "正在连接服务...", Toast.LENGTH_SHORT).show();

            // 重新绑定服务
            Intent intent = new Intent(this, ScreenCaptureService.class);
            if (!serviceConnectionRegistered) {
                serviceConnectionRegistered = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
                if (!serviceConnectionRegistered) {
                    permissionRequested = false;
                    Toast.makeText(this, "服务连接失败", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private boolean ensureOverlayPermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (Settings.canDrawOverlays(this)) {
            return true;
        }
        InAppLogger.w(TAG, "缺少悬浮窗权限（SYSTEM_ALERT_WINDOW），引导用户开启");
        Toast.makeText(this, "需要开启“显示在其他应用上层”权限才能覆盖原车导航，请在系统设置中授权", Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            InAppLogger.w(TAG, "无法打开悬浮窗授权页", e);
        }
        return false;
    }

    private void doStartCaptureWithServiceReady() {
        if (!ensureClusterMirrorSupported()) {
            return;
        }
        if (captureService == null) {
            InAppLogger.e(TAG, "服务就绪后启动推流失败：captureService=null");
            Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!captureService.beginStart()) {
            InAppLogger.w(TAG, "本地镜像已在运行或启动中：" + captureService.getDebugState());
            Toast.makeText(this, "镜像已在运行或启动中", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingProjectionToken = captureService.getActiveToken();
        InAppLogger.i(TAG, "已请求启动前台 ScreenCaptureService，授权 token=" + pendingProjectionToken);

        // 请求屏幕录制权限
        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent captureIntent = Build.VERSION.SDK_INT >= 34
            ? projectionManager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            : projectionManager.createScreenCaptureIntent();

        // 保存当前启用状态
        configManager.setEnabled(true);
        switchEnabled.setChecked(true);

        projectionLauncher.launch(captureIntent);
        InAppLogger.i(TAG, "已打开屏幕录制权限请求界面");
    }

    private void stopCapture() {
        if (serviceBound && captureService != null) {
            InAppLogger.i(TAG, "调用 stopMirror 前状态：" + captureService.getDebugState());
            captureService.stopMirror();
            InAppLogger.i(TAG, "调用 stopMirror 后状态：" + captureService.getDebugState());
        } else {
            InAppLogger.w(TAG, "停止投屏时服务未绑定");
        }
        configManager.setEnabled(false);
        switchEnabled.setChecked(false);
        updateStatus();
        refreshLogs();
        Toast.makeText(this, "镜像已停止", Toast.LENGTH_SHORT).show();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateCropRangeSummary() {
        if (textCropRangeSummary == null || homeTextCropRangeSummary == null || cropSelectionView == null) {
            return;
        }
        String summary = "当前裁剪：水平 "
            + cropSelectionView.getCropStartX() + "~" + cropSelectionView.getCropEndX()
            + "，垂直 " + cropSelectionView.getCropStartY() + "~" + cropSelectionView.getCropEndY();
        textCropRangeSummary.setText(summary);
        homeTextCropRangeSummary.setText(summary);
    }

    private void updateOutputWidthSummary(int percent) {
        String summary = "仪表占用宽度：" + percent + "%";
        if (homeTextOutputWidthSummary != null) {
            homeTextOutputWidthSummary.setText(summary);
        }
        if (textOutputWidthSummary != null) {
            textOutputWidthSummary.setText(summary);
        }
    }

    private void updateStatus() {
        handler.post(() -> {
            if (clusterMirrorCapability.getOutcome() == ClusterMirrorCapabilityPolicy.Outcome.UNSUPPORTED) {
                applyClusterMirrorUnsupportedState();
                return;
            }
            boolean busy = serviceBound && captureService != null && captureService.isBusy();
            if (busy) {
                textStatus.setText("状态: 镜像中");
                textStatus.setTextColor(0xFF4CAF50); // Green
                btnStart.setEnabled(false);
                btnStop.setEnabled(true);
            } else {
                textStatus.setText("状态: 已停止");
                textStatus.setTextColor(0xFF9E9E9E); // Gray
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
            }
            updateCastButton(busy);
        });
    }

    private void updateCastButton(boolean running) {
        if (btnCast == null) {
            return;
        }
        if (clusterMirrorCapability.getOutcome() == ClusterMirrorCapabilityPolicy.Outcome.UNSUPPORTED) {
            btnCast.setText(R.string.hongqi_cluster_mirror_unavailable_label);
            btnCast.setEnabled(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                btnCast.setBackgroundTintList(null);
            }
            return;
        }
        btnCast.setText(running ? "停止镜像" : "开始镜像");
        btnCast.setEnabled(true);
        int color = getResources().getColor(running ? R.color.hongqi_stop_button : R.color.hongqi_start_button);
        btnCast.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    private boolean ensureClusterMirrorSupported() {
        switch (clusterMirrorCapability.getOutcome()) {
            case UNSUPPORTED:
                applyClusterMirrorUnsupportedState();
                InAppLogger.w(TAG, "Cluster mirror unsupported: " + clusterMirrorCapability.getReason());
                Toast.makeText(this, R.string.hongqi_cluster_mirror_unsupported_message, Toast.LENGTH_LONG).show();
                return false;
            case SUPPORTED:
                return true;
        }
        throw new IllegalStateException("Unhandled cluster mirror capability outcome");
    }

    private void applyClusterMirrorCapabilityState() {
        if (clusterMirrorCapability.getOutcome() == ClusterMirrorCapabilityPolicy.Outcome.UNSUPPORTED) {
            applyClusterMirrorUnsupportedState();
        } else {
            applyClusterMirrorSupportedState();
        }
    }

    private void applyClusterMirrorSupportedState() {
        permissionRequested = false;
        pendingProjectionToken = -1;
        if (textStatus != null) {
            textStatus.setText("状态: 已停止");
            textStatus.setTextColor(0xFF9E9E9E);
        }
        if (switchEnabled != null) {
            switchEnabled.setEnabled(true);
            switchEnabled.setChecked(configManager != null && configManager.isEnabled());
        }
        if (btnStart != null) {
            btnStart.setText("开始镜像");
            btnStart.setEnabled(true);
        }
        if (btnStop != null) {
            btnStop.setText("停止镜像");
            btnStop.setEnabled(false);
        }
        updateCastButton(false);
    }

    private void applyClusterMirrorUnsupportedState() {
        permissionRequested = false;
        pendingProjectionToken = -1;
        if (configManager != null) {
            configManager.setEnabled(false);
        }
        if (textStatus != null) {
            textStatus.setText(R.string.hongqi_cluster_mirror_unsupported_message);
            textStatus.setTextColor(getResources().getColor(R.color.hongqi_status));
        }
        if (switchEnabled != null) {
            switchEnabled.setChecked(false);
            switchEnabled.setEnabled(false);
        }
        if (btnStart != null) {
            btnStart.setText(R.string.hongqi_cluster_mirror_unavailable_label);
            btnStart.setEnabled(false);
        }
        if (btnStop != null) {
            btnStop.setEnabled(false);
        }
        if (btnCast != null) {
            btnCast.setText(R.string.hongqi_cluster_mirror_unavailable_label);
            btnCast.setEnabled(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                btnCast.setBackgroundTintList(null);
            }
        }
    }

    private void refreshLogs() {
        if (textLogOutput == null) {
            return;
        }
        textLogOutput.setText("正在读取日志...");
        new Thread(() -> {
            String logs = buildLogText();
            handler.post(() -> textLogOutput.setText(logs));
        }, "SettingsLogRefresh").start();
    }

    private void refreshDisplayInfo() {
        if (textDisplayOutput == null) {
            return;
        }
        textDisplayOutput.setText(buildDisplayInfoText());
    }

    private String buildDisplayInfoText() {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            return "无法获取 DisplayManager";
        }

        Display[] displays = displayManager.getDisplays();
        StringBuilder builder = new StringBuilder();
        builder.append("系统可见屏幕数量: ").append(displays.length).append('\n');
        builder.append("说明: 如果这里只显示 1 个屏幕，仪表屏没有以 Android Display 形式暴露，本地镜像将无法启动。\n\n");

        for (int i = 0; i < displays.length; i++) {
            Display display = displays[i];
            Display.Mode mode = Build.VERSION.SDK_INT >= 23 ? display.getMode() : null;
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            builder.append("#").append(i + 1).append('\n')
                .append("  id: ").append(display.getDisplayId()).append('\n')
                .append("  name: ").append(display.getName()).append('\n')
                .append("  size: ").append(mode != null ? mode.getPhysicalWidth() : metrics.widthPixels).append('x').append(mode != null ? mode.getPhysicalHeight() : metrics.heightPixels).append('\n')
                .append("  refreshRate: ").append(mode != null ? mode.getRefreshRate() : display.getRefreshRate()).append("Hz\n")
                .append("  flags: 0x").append(Integer.toHexString(display.getFlags())).append('\n')
                .append("  state: ").append(displayStateToString(display.getState())).append("\n\n");
        }

        InAppLogger.i(TAG, "车机屏幕信息已刷新：数量=" + displays.length);
        return builder.toString();
    }

    private String displayStateToString(int state) {
        switch (state) {
            case Display.STATE_OFF:
                return "OFF";
            case Display.STATE_ON:
                return "ON";
            case Display.STATE_DOZE:
                return "DOZE";
            case Display.STATE_DOZE_SUSPEND:
                return "DOZE_SUSPEND";
            case Display.STATE_VR:
                return "VR";
            case Display.STATE_ON_SUSPEND:
                return "ON_SUSPEND";
            case Display.STATE_UNKNOWN:
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    private String buildLogText() {
        StringBuilder builder = new StringBuilder();
        builder.append("=== 当前状态 ===\n");
        builder.append("serviceBound=").append(serviceBound).append('\n');
        builder.append("captureService=").append(captureService != null ? captureService.getDebugState() : "null").append('\n');
        builder.append("说明: 停止后 virtualDisplay/presentation/mediaProjection/activeSurface 都应为 false/null。\n\n");

        builder.append("=== App关键日志 ===\n");
        builder.append(InAppLogger.dump()).append("\n\n");

        builder.append("=== logcat最近日志（尽力读取） ===\n");
        builder.append(readRecentLogcat());
        return builder.toString();
    }

    private String readRecentLogcat() {
        StringBuilder builder = new StringBuilder();
        Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", "300")
                .redirectErrorStream(true)
                .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isInterestingLogLine(line)) {
                        builder.append(line).append('\n');
                    }
                }
            }

            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy();
                builder.append("logcat 读取超时；普通 App 可能无法完整读取系统日志。\n");
            }
        } catch (Exception e) {
            builder.append("无法读取 logcat: ")
                .append(e.getClass().getSimpleName())
                .append(": ")
                .append(e.getMessage())
                .append('\n')
                .append("提示: Android 普通 App 通常看不到其它进程日志；系统签名/调试版本或 adb logcat 可看到更多车机日志。\n");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        if (builder.length() == 0) {
            builder.append("没有读到匹配的 logcat 行；请看上方 App关键日志，或用 adb logcat 抓取 PateoMapAuto 和本地镜像日志。\n");
        }
        return builder.toString();
    }

    private boolean isInterestingLogLine(String line) {
        String lower = line.toLowerCase(Locale.US);
        return lower.contains("hongqi")
            || lower.contains("flight")
            || lower.contains("screencaptureservice")
            || lower.contains("mainactivity")
            || lower.contains("pateomapauto")
            || lower.contains("pateo")
            || lower.contains("navi");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (configManager == null || isFinishing()) return;
        if (!ActivationGuard.isSecureRunning(this)) { ActivationGuard.requireActivated(this); return; }
        updateStatus();
        refreshKeyMapping();
        updateSidebarOverlayButton();
        // 已启用且已授权悬浮窗时，确保侧边栏服务运行（应用重启后自动拉起）
        if (SidebarPrefs.isEnabled(this) && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))) {
            SidebarService.start(this, SidebarService.ACTION_SHOW);
        }
    }

    @Override
    protected void onDestroy() {
        if (captureService != null && pendingProjectionToken >= 0) captureService.cancelPendingStart(pendingProjectionToken);
        if (serviceConnectionRegistered) {
            unbindService(serviceConnection);
            serviceConnectionRegistered = false;
            serviceBound = false;
        }
        keyLearning = false;
        handler.removeCallbacksAndMessages(null);
        KeyMappingManager.cancelLearningDoubleTapCast(this);
        KeyMappingManager.cancelLearning(this);
        super.onDestroy();
    }
}
