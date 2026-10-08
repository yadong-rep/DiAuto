package com.hongqi.flighting.sidebar;

import android.accessibilityservice.AccessibilityService;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hongqi.flighting.R;
import com.hongqi.flighting.mapping.KeyMappingAccessibilityService;
import com.hongqi.flighting.service.ScreenCaptureService;
import com.hongqi.flighting.ui.HongqiFeatureActivity;
import com.hongqi.flighting.util.ActivationGuard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 侧边栏浮窗服务。完整复刻参考 App（com.hzsoft.sidebar 的 Sidebar + SwipeDetector + AppDrawer）：
 * 在屏幕边缘放置一条细窄感应条，向内滑动呼出竖向侧边栏；侧边栏内含固定控件（返回/桌面）、
 * 应用快捷方式与系统控件（WiFi/蓝牙/亮度/定位/旋转/音量/手电筒/媒体键/全部应用/最近任务）。
 * 基于 WindowManager 浮窗直接实现，不依赖参考 App 的 StandOut 库。
 */
public class SidebarService extends Service {

    public static final String ACTION_SHOW = "com.hongqi.flighting.sidebar.SHOW";
    public static final String ACTION_HIDE = "com.hongqi.flighting.sidebar.HIDE";
    public static final String ACTION_REFRESH = "com.hongqi.flighting.sidebar.REFRESH";

    private static final String TAG = "SidebarService";
    private static volatile boolean running = false;

    private WindowManager windowManager;
    private View edgeStrip;
    private View sidebarPanel;
    private LinearLayout sidebarList;
    private View appDrawer;

    private int side = SidebarPrefs.SIDE_LEFT;
    private int panelWidth;
    private int screenWidth;
    private boolean sidebarVisible = false;
    private boolean swipeTriggered = false;
    private float swipeStartX = 0f;
    private android.hardware.Camera torchCamera;
    private boolean torchOn = false;
    private android.content.BroadcastReceiver castStateReceiver;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context, String action) {
        androidx.core.content.ContextCompat.startForegroundService(context,
            new Intent(context, SidebarService.class).setAction(action));
    }

    private void promoteToForeground() {
        String channel = "hongqi_sidebar";
        android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new android.app.NotificationChannel(channel,
                "红旗侧边栏", android.app.NotificationManager.IMPORTANCE_LOW));
        }
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 2002,
            new Intent(this, HongqiFeatureActivity.class),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new android.app.Notification.Builder(this, channel) : new android.app.Notification.Builder(this);
        android.app.Notification notification = builder.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("红旗侧边栏正在运行").setContentText("点击设置侧边栏")
            .setContentIntent(open).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2002, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else { startForeground(2002, notification); }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        // 监听投屏状态变化，动态刷新投屏图标
        castStateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                rebuildList();
            }
        };
        IntentFilter filter = new IntentFilter(ScreenCaptureService.ACTION_QUERY);
        androidx.core.content.ContextCompat.registerReceiver(this, castStateReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { promoteToForeground(); }
        catch (RuntimeException error) { Log.w(TAG, "无法启动侧边栏", error); stopSelf(); return START_NOT_STICKY; }
        // 激活安全检查：未激活则停止服务并跳转激活界面
        if (!ActivationGuard.isSecureRunning(this)) {
            Log.w(TAG, "未激活，拒绝运行侧边栏服务");
            stopSelf();
            Intent ai = new Intent(this, com.hongqi.flighting.ui.ActivationActivity.class);
            ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(ai);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            stopSidebar();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            removeOverlay(edgeStrip);
            removeOverlay(sidebarPanel);
            removeOverlay(appDrawer);
            edgeStrip = null;
            sidebarPanel = null;
            sidebarList = null;
            appDrawer = null;
        }
        if (!SidebarPrefs.isEnabled(this)) { stopSidebar(); return START_NOT_STICKY; }
        showSidebar();
        return running ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        running = false;
        super.onDestroy();
        running = false;
        if (torchOn) {
            releaseTorch();
        }
        if (castStateReceiver != null) {
            try {
                unregisterReceiver(castStateReceiver);
            } catch (Exception ignored) {}
            castStateReceiver = null;
        }
        removeOverlay(edgeStrip);
        removeOverlay(sidebarPanel);
        removeOverlay(appDrawer);
        edgeStrip = null;
        sidebarPanel = null;
        appDrawer = null;
    }

    // ================= 显示 / 隐藏 =================

    private void showSidebar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            running = false;
            stopSelf();
            return;
        }
        running = true;
        ensureEdgeStrip();
        ensureSidebarPanel();
        hideSidebarPanel();
    }

    private void stopSidebar() {
        running = false;
        if (torchOn) {
            releaseTorch();
        }
        removeOverlay(appDrawer);
        appDrawer = null;
        stopSelf();
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // ================= 边缘感应条（滑动呼出） =================

    private void ensureEdgeStrip() {
        if (edgeStrip != null) {
            return;
        }
        side = SidebarPrefs.side(this);
        // 感应条：细窄渐变条，更精致
        View strip = new View(this);
        int gold = 0xD9B46A;
        int[] colors = side == SidebarPrefs.SIDE_RIGHT
            ? new int[]{0x00D9B46A, 0x44D9B46A, 0x22D9B46A}
            : new int[]{0x22D9B46A, 0x44D9B46A, 0x00D9B46A};
        GradientDrawable gd = new GradientDrawable(
            side == SidebarPrefs.SIDE_RIGHT
                ? GradientDrawable.Orientation.LEFT_RIGHT
                : GradientDrawable.Orientation.RIGHT_LEFT,
            colors);
        strip.setBackground(gd);
        float stripW = dp(16f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            (int) stripW, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = side == SidebarPrefs.SIDE_RIGHT ? Gravity.RIGHT : Gravity.LEFT;
        lp.x = 0;
        lp.y = 0;
        strip.setOnTouchListener(this::onStripTouch);
        windowManager.addView(strip, lp);
        edgeStrip = strip;
    }

    private boolean onStripTouch(View v, MotionEvent event) {
        final int threshold = (int) dp(40f);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                swipeStartX = event.getRawX();
                swipeTriggered = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!swipeTriggered) {
                    float dx = event.getRawX() - swipeStartX;
                    boolean inward = side == SidebarPrefs.SIDE_RIGHT ? (dx < -threshold) : (dx > threshold);
                    if (inward) {
                        swipeTriggered = true;
                        vibrate();
                        showSidebarPanel();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                swipeTriggered = false;
                return true;
            default:
                return true;
        }
    }

    // ================= 侧边栏面板 =================

    private void ensureSidebarPanel() {
        if (sidebarPanel != null) {
            return;
        }
        side = SidebarPrefs.side(this);
        int itemSize = SidebarPrefs.itemSize(this);
        int cornerDp = SidebarPrefs.cornerRadius(this);
        int bgRgb = SidebarPrefs.bgColor(this) & 0x00FFFFFF;
        int alpha = SidebarPrefs.panelAlpha(this);
        int bgColor = (alpha << 24) | bgRgb;
        panelWidth = (int) dp(itemSize + 16);

        LinearLayout root = new SidebarPanelView(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 圆角渐变背景
        GradientDrawable bgDrawable = new GradientDrawable();
        bgDrawable.setCornerRadius(dp(cornerDp));
        bgDrawable.setColor(bgColor);
        // 内侧细微高光边
        bgDrawable.setStroke(1, 0x33D9B46A);
        root.setBackground(bgDrawable);

        // 虚化效果（Android 12+）
        int blur = SidebarPrefs.blurRadius(this);
        if (blur > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            root.setRenderEffect(RenderEffect.createBlurEffect(
                blur, blur, Shader.TileMode.MIRROR));
        }

        // 阴影
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            root.setElevation(dp(8));
        }

        root.setPadding(0, (int) dp(cornerDp), 0, (int) dp(cornerDp));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            GradientDrawable scrollBg = new GradientDrawable();
            scrollBg.setCornerRadius(dp(cornerDp));
            scrollBg.setColor(Color.TRANSPARENT);
            scroll.setBackground(scrollBg);
        }
        sidebarList = new LinearLayout(this);
        sidebarList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(sidebarList, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            panelWidth, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = side == SidebarPrefs.SIDE_RIGHT ? Gravity.RIGHT : Gravity.LEFT;
        lp.x = 0;
        lp.y = 0;
        windowManager.addView(root, lp);
        sidebarPanel = root;
        buildList();
        root.setVisibility(View.INVISIBLE);
    }

    private void rebuildList() {
        side = SidebarPrefs.side(this);
        if (sidebarList == null) {
            if (running) {
                ensureSidebarPanel();
            }
            return;
        }
        buildList();
    }

    /** 首次使用时自动把“投屏”控件加入侧边栏快捷列表。 */
    private void ensureDefaultAppsPref() {
        SharedPreferences sp = SidebarPrefs.get(this);
        String apps = SidebarPrefs.apps(this);
        if (apps != null && !apps.trim().isEmpty()) {
            return;
        }
        String defaultEntry = "widget:" + SidebarWidgets.CAST;
        sp.edit().putString(SidebarPrefs.KEY_APPS, defaultEntry).apply();
        Log.i(TAG, "首次启用侧边栏，已自动插入默认控件：" + defaultEntry);
    }

    private void buildList() {
        sidebarList.removeAllViews();

        // 固定控件：返回、桌面（参考 SidebarFixedControls.add）
        addFixedButton(R.drawable.hongqi_ic_sidebar_back, "返回", v -> {
            final Context ctx = getApplicationContext();
            hideSidebarPanel();
            v.postDelayed(() -> {
                if (!KeyMappingAccessibilityService.performSystemAction(
                        AccessibilityService.GLOBAL_ACTION_BACK)) {
                    Toast.makeText(ctx, "返回失败，请先在按键映射页开启无障碍服务", Toast.LENGTH_SHORT).show();
                }
            }, 180L);
        });
        addFixedButton(R.drawable.hongqi_ic_sidebar_home, "桌面", v -> {
            final Context ctx = getApplicationContext();
            hideSidebarPanel();
            v.postDelayed(() -> {
                if (!KeyMappingAccessibilityService.performSystemAction(
                        AccessibilityService.GLOBAL_ACTION_HOME)) {
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(home);
                }
            }, 180L);
        });

        // 用户快捷方式与控件（若用户尚未配置则默认插入投屏控件）
        ensureDefaultAppsPref();
        List<ResolveInfo> launchables = queryLaunchables();
        Log.i(TAG, "buildList apps=[" + SidebarPrefs.apps(this) + "] launchables=" + launchables.size());
        for (String entry : SidebarPrefs.apps(this).split("\n")) {
            String token = entry.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("widget:")) {
                try {
                    int code = Integer.parseInt(token.substring("widget:".length()).trim());
                    addWidgetItem(code);
                } catch (Exception ignored) {
                }
            } else {
                addAppItem(token);
            }
        }

        // 底部“添加”按钮（参考 Sidebar.i()，呼出全部应用）
        addFixedButton(R.drawable.hongqi_ic_sidebar_apps, "添加", v -> {
            hideSidebarPanel();
            showAppDrawer();
        });
    }

    /** 创建圆角按钮背景，按下时高亮。 */
    private GradientDrawable itemBackground(boolean pressed) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(pressed ? 0x33D9B46A : 0x11FFFFFF);
        if (pressed) {
            gd.setStroke(1, 0x66D9B46A);
        }
        return gd;
    }

    /** 创建图标按钮；activated=true 时按钮呈现激活态金色描边（用于投屏运行中）。 */
    private ImageView createItemButton(int iconRes, String desc, boolean activated) {
        ImageView iv = new ImageView(this);
        if (iconRes != 0) {
            iv.setImageResource(iconRes);
        }
        iv.setContentDescription(desc);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = (int) dp(12f);
        iv.setPadding(pad, pad, pad, pad);
        int itemSize = SidebarPrefs.itemSize(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(itemSize));
        lp.setMargins((int) dp(4), (int) dp(2), (int) dp(4), (int) dp(2));
        iv.setLayoutParams(lp);
        if (activated) {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(12));
            gd.setColor(0x33D9B46A);
            gd.setStroke(2, 0xCCFFFFFF);
            iv.setBackground(gd);
        } else {
            iv.setBackground(itemBackground(false));
        }
        iv.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setBackground(itemBackground(true));
                v.setAlpha(0.85f);
            } else if (e.getAction() == MotionEvent.ACTION_UP
                || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setBackground(itemBackground(false));
                v.setAlpha(1.0f);
            }
            return false;
        });
        return iv;
    }

    /** 在列表中添加细分隔线。 */
    private void addDivider() {
        View line = new View(this);
        line.setBackgroundColor(0x1AD9B46A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins((int) dp(12), 0, (int) dp(12), 0);
        sidebarList.addView(line, lp);
    }

    /** 创建普通按钮（兼容旧调用）。 */
    private ImageView createItemButton(int iconRes, String desc) {
        return createItemButton(iconRes, desc, false);
    }

    private void addFixedButton(int iconRes, String desc, View.OnClickListener listener) {
        ImageView iv = createItemButton(iconRes, desc);
        iv.setOnClickListener(listener);
        sidebarList.addView(iv);
        addDivider();
    }

    private void addAppItem(String entry) {
        ResolveInfo info = resolveApp(entry);
        ImageView iv = createItemButton(0, entry);
        if (info != null) {
            iv.setImageDrawable(info.loadIcon(getPackageManager()));
            iv.setContentDescription(String.valueOf(info.loadLabel(getPackageManager())));
        } else {
            iv.setImageResource(R.drawable.hongqi_ic_sidebar_apps);
        }
        final ResolveInfo target = info;
        iv.setOnClickListener(v -> {
            hideSidebarPanel();
            if (target != null) {
                launchApp(target);
            } else {
                Toast.makeText(this, "应用不存在：" + entry, Toast.LENGTH_SHORT).show();
            }
        });
        iv.setOnLongClickListener(v -> {
            removeEntry(entry);
            Toast.makeText(this, "已移除：" + (target != null ? target.loadLabel(getPackageManager()) : entry),
                Toast.LENGTH_SHORT).show();
            return true;
        });
        sidebarList.addView(iv);
        addDivider();
    }

    private void addWidgetItem(int code) {
        boolean activated = code == SidebarWidgets.CAST && ScreenCaptureService.isMirrorRunning();
        ImageView iv = createItemButton(SidebarWidgets.icon(code), SidebarWidgets.label(code), activated);
        iv.setOnClickListener(v -> {
            // 亮度/音量/手电筒/投屏动作后不收起（投屏自行控制收起）
            if (code != SidebarWidgets.BRIGHTNESS && code != SidebarWidgets.SOUND
                && code != SidebarWidgets.TORCH && code != SidebarWidgets.CAST) {
                hideSidebarPanel();
            }
            performWidget(code);
        });
        iv.setOnLongClickListener(v -> {
            removeEntry("widget:" + code);
            Toast.makeText(this, "已移除控件：" + SidebarWidgets.label(code), Toast.LENGTH_SHORT).show();
            return true;
        });
        sidebarList.addView(iv);
        addDivider();
    }

    private void showSidebarPanel() {
        if (sidebarPanel == null) {
            return;
        }
        sidebarVisible = true;
        sidebarPanel.setVisibility(View.VISIBLE);
        float end = 0f;
        float start = side == SidebarPrefs.SIDE_RIGHT ? panelWidth : -panelWidth;
        long speed = SidebarPrefs.animSpeed(this);
        sidebarPanel.setTranslationX(start);
        sidebarPanel.setAlpha(0f);
        sidebarPanel.animate()
            .translationX(end)
            .alpha(1f)
            .setDuration(speed)
            .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f))
            .start();
    }

    private void hideSidebarPanel() {
        if (sidebarPanel == null || !sidebarVisible) {
            return;
        }
        sidebarVisible = false;
        float target = side == SidebarPrefs.SIDE_RIGHT ? panelWidth : -panelWidth;
        long speed = Math.max(80, SidebarPrefs.animSpeed(this) * 4 / 5);
        sidebarPanel.animate()
            .translationX(target)
            .alpha(0f)
            .setDuration(speed)
            .setInterpolator(new android.view.animation.AccelerateInterpolator())
            .withEndAction(() -> sidebarPanel.setVisibility(View.INVISIBLE))
            .start();
    }

    // ================= 全部应用（AppDrawer） =================

    private void showAppDrawer() {
        if (appDrawer != null) {
            return;
        }
        final List<ResolveInfo> launchables = queryLaunchables();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 圆角半透明背景
        GradientDrawable drawerBg = new GradientDrawable();
        drawerBg.setCornerRadius(dp(20));
        int bgRgb = SidebarPrefs.bgColor(this) & 0x00FFFFFF;
        drawerBg.setColor((0xF2 << 24) | bgRgb);
        drawerBg.setStroke(1, 0x33D9B46A);
        root.setBackground(drawerBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            root.setElevation(dp(12));
        }
        root.setPadding((int) dp(20), (int) dp(24), (int) dp(20), (int) dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("全部应用");
        title.setTextColor(getResources().getColor(R.color.hongqi_brand_gold));
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(getResources().getColor(R.color.hongqi_brand_gold));
        close.setTextSize(22);
        close.setPadding((int) dp(14), (int) dp(4), (int) dp(4), (int) dp(4));
        close.setOnClickListener(v -> removeOverlay(appDrawer));
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        header.addView(close, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        // 标题下分隔线
        View titleLine = new View(this);
        titleLine.setBackgroundColor(0x33D9B46A);
        root.addView(titleLine, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, (int) dp(8), 0, 0);
        for (final ResolveInfo info : launchables) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) dp(10), (int) dp(10), (int) dp(10), (int) dp(10));

            // 行背景
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(dp(10));
            rowBg.setColor(0x11FFFFFF);
            row.setBackground(rowBg);
            row.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    GradientDrawable gd = new GradientDrawable();
                    gd.setCornerRadius(dp(10));
                    gd.setColor(0x33D9B46A);
                    v.setBackground(gd);
                } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    GradientDrawable gd = new GradientDrawable();
                    gd.setCornerRadius(dp(10));
                    gd.setColor(0x11FFFFFF);
                    v.setBackground(gd);
                }
                return false;
            });

            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageDrawable(info.loadIcon(getPackageManager()));
            row.addView(icon, new LinearLayout.LayoutParams((int) dp(44), (int) dp(44)));
            TextView name = new TextView(this);
            name.setText(info.loadLabel(getPackageManager()));
            name.setTextColor(getResources().getColor(R.color.hongqi_text));
            name.setTextSize(16);
            name.setPadding((int) dp(12), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            row.setOnClickListener(v -> addOrLaunch(info));
            list.addView(row);

            // 行间细分隔线
            View line = new View(this);
            line.setBackgroundColor(0x1AD9B46A);
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
            lineLp.setMargins((int) dp(12), 0, (int) dp(12), 0);
            list.addView(line, lineLp);
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
            (int) (getResources().getDisplayMetrics().heightPixels * 0.85f),
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.x = 0;
        lp.y = 0;
        windowManager.addView(root, lp);
        appDrawer = root;

        // 淡入动画
        root.setAlpha(0f);
        root.setScaleY(0.92f);
        root.animate().alpha(1f).scaleY(1f).setDuration(200).start();
    }

    private void addOrLaunch(ResolveInfo info) {
        // “添加”按钮进入时点击 = 加入侧边栏；加到缓存/预加载后直接投屏逻辑复用，这里做添加。
        String entry = info.activityInfo.packageName + ":" + info.activityInfo.name;
        String apps = SidebarPrefs.apps(this);
        if (!apps.contains(entry)) {
            SharedPreferences.Editor ed = SidebarPrefs.get(this).edit();
            ed.putString(SidebarPrefs.KEY_APPS, (apps.trim() + "\n" + entry).trim()).commit();
            Toast.makeText(this, "已添加：" + info.loadLabel(getPackageManager()), Toast.LENGTH_SHORT).show();
            rebuildList();
        } else {
            Toast.makeText(this, "该应用已添加", Toast.LENGTH_SHORT).show();
        }
        removeOverlay(appDrawer);
    }

    // ================= 系统控件动作（参考 Sidebar.w()） =================

    private void performWidget(final int code) {
        final Context ctx = getApplicationContext();
        try {
            switch (code) {
                case SidebarWidgets.WIFI:
                    toggleWifi(ctx);
                    break;
                case SidebarWidgets.BLUETOOTH:
                    startSettings("android.settings.BLUETOOTH_SETTINGS");
                    break;
                case SidebarWidgets.GPS:
                    startSettings("android.settings.LOCATION_SOURCE_SETTINGS");
                    break;
                case SidebarWidgets.ROTATION:
                    toggleRotation(ctx);
                    break;
                case SidebarWidgets.SOUND:
                    cycleVolume();
                    break;
                case SidebarWidgets.BRIGHTNESS:
                    cycleBrightness();
                    break;
                case SidebarWidgets.TORCH:
                    toggleTorch();
                    break;
                case SidebarWidgets.ALL_APPS:
                    showAppDrawer();
                    break;
                case SidebarWidgets.RECENT:
                    if (!KeyMappingAccessibilityService.performSystemAction(
                            AccessibilityService.GLOBAL_ACTION_RECENTS)) {
                        Toast.makeText(ctx, "最近任务需要无障碍服务支持", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case SidebarWidgets.PREV:
                    dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    break;
                case SidebarWidgets.PLAY_PAUSE:
                    dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    break;
                case SidebarWidgets.NEXT:
                    dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT);
                    break;
                case SidebarWidgets.CAST:
                    toggleCast(ctx);
                    return; // 自己控制是否收起
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "控件动作失败 code=" + code, e);
            Toast.makeText(ctx, "操作失败：" + SidebarWidgets.label(code), Toast.LENGTH_SHORT).show();
        }
    }

    private void startSettings(String action) {
        Intent intent = new Intent(action);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /** 投屏切换：运行中则直接 stop；否则拉起 HongqiFeatureActivity 自动启动。 */
    private void toggleCast(Context ctx) {
        boolean running = ScreenCaptureService.isMirrorRunning();
        hideSidebarPanel();
        if (running) {
            // 向 ScreenCaptureService 发送 ACTION_STOP，直接 stopMirror
            try {
                Intent intent = new Intent(this, ScreenCaptureService.class);
                intent.setAction(ScreenCaptureService.ACTION_STOP);
                startService(intent);
                Toast.makeText(ctx, "投屏已停止", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.w(TAG, "停止投屏失败", e);
                Toast.makeText(ctx, "停止投屏失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            // 启动投屏必须走 Activity（需屏幕录制授权弹窗），走 HongqiFeatureActivity 自动启动
            try {
                Intent intent = new Intent(this, HongqiFeatureActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra(ScreenCaptureService.EXTRA_AUTO_START, true);
                startActivity(intent);
                Toast.makeText(ctx, "正在启动投屏...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.w(TAG, "启动投屏失败", e);
                Toast.makeText(ctx, "启动投屏失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void toggleWifi(Context ctx) {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi == null) {
            return;
        }
        try {
            boolean target = !wifi.isWifiEnabled();
            // Android 10+ 已禁止第三方直接开关 WiFi，退化为打开设置页
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent intent = new Intent(Settings.Panel.ACTION_WIFI);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else if (!wifi.setWifiEnabled(target)) {
                startSettings("android.settings.WIFI_SETTINGS");
            }
        } catch (Exception e) {
            startSettings("android.settings.WIFI_SETTINGS");
        }
    }

    private boolean ensureWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this)) return true;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException error) {
            Log.w(TAG, "无法打开系统设置授权页", error);
        }
        Toast.makeText(this, "请允许修改系统设置后再操作", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void toggleRotation(Context ctx) {
        if (!ensureWriteSettingsPermission()) return;
        try {
            int current = Settings.System.getInt(ctx.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(ctx.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, current == 1 ? 0 : 1);
            Toast.makeText(ctx, current == 1 ? "已关闭自动旋转" : "已开启自动旋转", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "toggleRotation failed", e);
        }
    }

    private void cycleVolume() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int next;
        if (cur < max / 4) next = max / 4;
        else if (cur < max / 2) next = max / 2;
        else if (cur < (max * 3) / 4) next = (max * 3) / 4;
        else if (cur >= max) next = 0;
        else next = max;
        am.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI);
    }

    private void cycleBrightness() {
        if (!ensureWriteSettingsPermission()) return;
        Context ctx = getApplicationContext();
        try {
            int mode = Settings.System.getInt(ctx.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            int brightness = Settings.System.getInt(ctx.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 128);
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 64);
                Toast.makeText(ctx, "已设置低亮度", Toast.LENGTH_SHORT).show();
            } else if (brightness < 85) {
                Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
                Toast.makeText(ctx, "已设置中等亮度", Toast.LENGTH_SHORT).show();
            } else if (brightness <= 170) {
                Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 255);
                Toast.makeText(ctx, "已设置高亮度", Toast.LENGTH_SHORT).show();
            } else {
                Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, 1);
                Toast.makeText(ctx, "已切换为自动亮度", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.w(TAG, "cycleBrightness failed", e);
            Toast.makeText(ctx, "亮度调节不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleTorch() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, HongqiFeatureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(HongqiFeatureActivity.EXTRA_CAMERA_PERMISSION, true));
            Toast.makeText(this, "请授权相机权限后再开启手电筒", Toast.LENGTH_SHORT).show();
            return;
        }
        Context ctx = getApplicationContext();
        try {
            if (torchOn) {
                releaseTorch();
                Toast.makeText(ctx, "手电筒已关闭", Toast.LENGTH_SHORT).show();
            } else {
                torchCamera = Camera.open();
                if (torchCamera == null) {
                    Toast.makeText(ctx, "手电筒不可用", Toast.LENGTH_SHORT).show();
                    return;
                }
                Camera.Parameters p = torchCamera.getParameters();
                p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                torchCamera.setParameters(p);
                torchCamera.startPreview();
                torchOn = true;
                Toast.makeText(ctx, "手电筒已开启", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.w(TAG, "toggleTorch failed", e);
            torchCamera = null;
            torchOn = false;
            Toast.makeText(ctx, "手电筒不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private void releaseTorch() {
        try {
            if (torchCamera != null) {
                torchCamera.stopPreview();
                torchCamera.release();
            }
        } catch (Exception ignored) {
        }
        torchCamera = null;
        torchOn = false;
    }

    // ================= 应用启动 =================

    private void launchApp(ResolveInfo info) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "launchApp failed", e);
            Toast.makeText(this, "启动失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ================= 工具方法 =================

    private void dispatchMediaButton(int keyCode) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        sendBroadcast(intent);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        sendBroadcast(intent);
    }

    private void removeEntry(String entry) {
        String apps = SidebarPrefs.apps(this);
        String replaced = apps.replace(entry, "").replaceAll("(?m)^[ \\t]*\\r?\\n", "");
        SidebarPrefs.get(this).edit().putString(SidebarPrefs.KEY_APPS, replaced.trim() + "\n").commit();
        rebuildList();
    }

    private List<ResolveInfo> queryLaunchables() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(main, 0);
        Collections.sort(list, (a, b) -> String.valueOf(a.loadLabel(getPackageManager()))
            .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager()))));
        return list;
    }

    private ResolveInfo resolveApp(String entry) {
        for (ResolveInfo info : queryLaunchables()) {
            String key = info.activityInfo.packageName + ":" + info.activityInfo.name;
            if (entry.contains(key)) {
                return info;
            }
        }
        return null;
    }

    private void vibrate() {
        if (!SidebarPrefs.vibrate(this)) {
            return;
        }
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(30);
            }
        } catch (Exception ignored) {
        }
    }

    private void removeOverlay(View view) {
        if (view == null) {
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (Exception ignored) {
        }
        if (view == appDrawer) {
            appDrawer = null;
        }
    }

    /**
     * 侧边栏面板根视图。配合 FLAG_WATCH_OUTSIDE_TOUCH，在用户点击侧边栏区域之外时
     * 收到 ACTION_OUTSIDE 事件，从而自动收起侧边栏（参考 Sidebar.a() 中的 hide 逻辑）。
     */
    private class SidebarPanelView extends LinearLayout {
        SidebarPanelView(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_OUTSIDE) {
                hideSidebarPanel();
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }
    }
}