package com.hongqi.flighting.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置管理器 - 所有配置项都从SharedPreferences读取，不硬编码
 */
public class ConfigManager {
    private static final String PREFS_NAME = "carlife_mirror_config";

    // 配置项Key
    private static final String KEY_ENABLED = "stream_enabled";
    private static final String KEY_SIMULATOR_PREVIEW = "simulator_preview";
    private static final String KEY_WIDTH = "video_width";
    private static final String KEY_HEIGHT = "video_height";
    private static final String KEY_STRETCH_MODE = "stretch_mode";
    private static final String KEY_CROP_START_X = "crop_start_x";
    private static final String KEY_CROP_END_X = "crop_end_x";
    private static final String KEY_CROP_START_Y = "crop_start_y";
    private static final String KEY_CROP_END_Y = "crop_end_y";
    private static final String KEY_OUTPUT_WIDTH_PERCENT = "output_width_percent";
    private static final String KEY_OUTPUT_HORIZONTAL_ALIGN = "output_horizontal_align";

    // Region 2 配置Key
    private static final String KEY_R2_SOURCE_TYPE = "region2_source_type";
    private static final String KEY_R2_IMAGE_NAME = "region2_image_name";
    private static final String KEY_R2_IMAGE_URL = "region2_image_url";
    private static final String KEY_R2_IMAGE_LOCAL_PATH = "region2_image_local_path";
    private static final String KEY_R2_ENABLED = "region2_enabled";
    private static final String KEY_R2_STRETCH_MODE = "region2_stretch_mode";
    private static final String KEY_R2_CROP_START_X = "region2_crop_start_x";
    private static final String KEY_R2_CROP_END_X = "region2_crop_end_x";
    private static final String KEY_R2_CROP_START_Y = "region2_crop_start_y";
    private static final String KEY_R2_CROP_END_Y = "region2_crop_end_y";
    private static final String KEY_R2_OUTPUT_WIDTH_PERCENT = "region2_output_width_percent";
    private static final String KEY_R2_OUTPUT_HORIZONTAL_ALIGN = "region2_output_horizontal_align";

    // Region 3 配置Key
    private static final String KEY_R3_SOURCE_TYPE = "region3_source_type";
    private static final String KEY_R3_IMAGE_NAME = "region3_image_name";
    private static final String KEY_R3_IMAGE_URL = "region3_image_url";
    private static final String KEY_R3_IMAGE_LOCAL_PATH = "region3_image_local_path";
    private static final String KEY_R3_ENABLED = "region3_enabled";
    private static final String KEY_R3_STRETCH_MODE = "region3_stretch_mode";
    private static final String KEY_R3_CROP_START_X = "region3_crop_start_x";
    private static final String KEY_R3_CROP_END_X = "region3_crop_end_x";
    private static final String KEY_R3_CROP_START_Y = "region3_crop_start_y";
    private static final String KEY_R3_CROP_END_Y = "region3_crop_end_y";
    private static final String KEY_R3_OUTPUT_WIDTH_PERCENT = "region3_output_width_percent";
    private static final String KEY_R3_OUTPUT_HORIZONTAL_ALIGN = "region3_output_horizontal_align";

    // 歌词源配置Key
    private static final String KEY_LYRIC_PROVIDER_ORDER = "lyric_provider_order";

    // 默认值（匹配仪表盘ClusterDisplayService参数）
    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 720;
    private static final String DEFAULT_STRETCH_MODE = "stretch_fill";
    private static final int DEFAULT_CROP_START_X = -100;
    private static final int DEFAULT_CROP_END_X = 100;
    private static final int DEFAULT_CROP_START_Y = -100;
    private static final int DEFAULT_CROP_END_Y = 100;
    private static final int DEFAULT_OUTPUT_WIDTH_PERCENT = 40;
    private static final String DEFAULT_OUTPUT_HORIZONTAL_ALIGN = "center";

    // Region 2/3 默认值
    private static final String DEFAULT_IMAGE_NAME = "";
    private static final String DEFAULT_R2_SOURCE_TYPE = "image";
    private static final String DEFAULT_R2_IMAGE_NAME = "动图1.gif";
    private static final boolean DEFAULT_R2_ENABLED = true;
    private static final String DEFAULT_R2_STRETCH_MODE = "stretch_fill";
    private static final int DEFAULT_R2_OUTPUT_WIDTH_PERCENT = 30;
    private static final String DEFAULT_R2_OUTPUT_HORIZONTAL_ALIGN = "left";
    private static final String DEFAULT_R3_SOURCE_TYPE = "music";
    private static final boolean DEFAULT_R3_ENABLED = true;
    private static final String DEFAULT_R3_STRETCH_MODE = "stretch_fill";
    private static final int DEFAULT_R3_OUTPUT_WIDTH_PERCENT = 30;
    private static final String DEFAULT_R3_OUTPUT_HORIZONTAL_ALIGN = "right";

    // 歌词源默认顺序：网易云（简体）优先，LRCLIB 兜底
    private static final String DEFAULT_LYRIC_PROVIDER_ORDER = "netease,lrclib";

    // 最大值校验
    private static final int MAX_TOTAL_WIDTH_PERCENT = 100;

    private final SharedPreferences prefs;

    public ConfigManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ========== 启用状态 ==========
    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // ========== 模拟器预览（无第二屏时回退到主屏） ==========
    public boolean isSimulatorPreview() {
        return prefs.getBoolean(KEY_SIMULATOR_PREVIEW, false);
    }

    public void setSimulatorPreview(boolean preview) {
        prefs.edit().putBoolean(KEY_SIMULATOR_PREVIEW, preview).apply();
    }

    // ========== 视频宽度 ==========
    public int getVideoWidth() {
        return prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH);
    }

    public void setVideoWidth(int width) {
        prefs.edit().putInt(KEY_WIDTH, width).apply();
    }

    // ========== 视频高度 ==========
    public int getVideoHeight() {
        return prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT);
    }

    public void setVideoHeight(int height) {
        prefs.edit().putInt(KEY_HEIGHT, height).apply();
    }

    // ========== 拉伸方式 ==========
    public String getStretchMode() {
        return prefs.getString(KEY_STRETCH_MODE, DEFAULT_STRETCH_MODE);
    }

    public void setStretchMode(String stretchMode) {
        prefs.edit().putString(KEY_STRETCH_MODE, stretchMode).apply();
    }

    // ========== 裁剪范围 ==========
    public int getCropStartX() {
        return prefs.getInt(KEY_CROP_START_X, DEFAULT_CROP_START_X);
    }

    public int getCropEndX() {
        return prefs.getInt(KEY_CROP_END_X, DEFAULT_CROP_END_X);
    }

    public int getCropStartY() {
        return prefs.getInt(KEY_CROP_START_Y, DEFAULT_CROP_START_Y);
    }

    public int getCropEndY() {
        return prefs.getInt(KEY_CROP_END_Y, DEFAULT_CROP_END_Y);
    }

    public void setCropRange(int cropStartX, int cropEndX, int cropStartY, int cropEndY) {
        prefs.edit()
            .putInt(KEY_CROP_START_X, cropStartX)
            .putInt(KEY_CROP_END_X, cropEndX)
            .putInt(KEY_CROP_START_Y, cropStartY)
            .putInt(KEY_CROP_END_Y, cropEndY)
            .apply();
    }

    public int getOutputWidthPercent() {
        return prefs.getInt(KEY_OUTPUT_WIDTH_PERCENT, DEFAULT_OUTPUT_WIDTH_PERCENT);
    }

    public void setOutputWidthPercent(int outputWidthPercent) {
        prefs.edit().putInt(KEY_OUTPUT_WIDTH_PERCENT, clamp(outputWidthPercent, 0, 100)).apply();
    }

    public String getOutputHorizontalAlign() {
        return prefs.getString(KEY_OUTPUT_HORIZONTAL_ALIGN, DEFAULT_OUTPUT_HORIZONTAL_ALIGN);
    }

    public void setOutputHorizontalAlign(String outputHorizontalAlign) {
        prefs.edit().putString(KEY_OUTPUT_HORIZONTAL_ALIGN, normalizeAlign(outputHorizontalAlign)).apply();
    }

    // ========== Region 2 配置 ==========
    public String getRegion2SourceType() {
        String val = prefs.getString(KEY_R2_SOURCE_TYPE, DEFAULT_R2_SOURCE_TYPE);
        return normalizeSourceType(val);
    }

    public void setRegion2SourceType(String type) {
        prefs.edit().putString(KEY_R2_SOURCE_TYPE, normalizeSourceType(type)).apply();
    }

    public String getRegion2ImageName() {
        return prefs.getString(KEY_R2_IMAGE_NAME, DEFAULT_R2_IMAGE_NAME);
    }

    public void setRegion2ImageName(String name) {
        prefs.edit().putString(KEY_R2_IMAGE_NAME, name != null ? name : "").apply();
    }

    public String getRegion2ImageUrl() {
        return prefs.getString(KEY_R2_IMAGE_URL, "");
    }

    public void setRegion2ImageUrl(String url) {
        prefs.edit().putString(KEY_R2_IMAGE_URL, url != null ? url : "").apply();
    }

    public String getRegion2ImageLocalPath() {
        return prefs.getString(KEY_R2_IMAGE_LOCAL_PATH, "");
    }

    public void setRegion2ImageLocalPath(String path) {
        prefs.edit().putString(KEY_R2_IMAGE_LOCAL_PATH, path != null ? path : "").apply();
    }

    public boolean isRegion2Enabled() {
        return prefs.getBoolean(KEY_R2_ENABLED, DEFAULT_R2_ENABLED);
    }

    public void setRegion2Enabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_R2_ENABLED, enabled).apply();
    }

    public String getRegion2StretchMode() {
        return prefs.getString(KEY_R2_STRETCH_MODE, DEFAULT_R2_STRETCH_MODE);
    }

    public void setRegion2StretchMode(String mode) {
        prefs.edit().putString(KEY_R2_STRETCH_MODE, mode).apply();
    }

    public int getRegion2OutputWidthPercent() {
        return prefs.getInt(KEY_R2_OUTPUT_WIDTH_PERCENT, DEFAULT_R2_OUTPUT_WIDTH_PERCENT);
    }

    public void setRegion2OutputWidthPercent(int percent) {
        prefs.edit().putInt(KEY_R2_OUTPUT_WIDTH_PERCENT, clamp(percent, 0, MAX_TOTAL_WIDTH_PERCENT)).apply();
    }

    public String getRegion2OutputHorizontalAlign() {
        return prefs.getString(KEY_R2_OUTPUT_HORIZONTAL_ALIGN, DEFAULT_R2_OUTPUT_HORIZONTAL_ALIGN);
    }

    public void setRegion2OutputHorizontalAlign(String align) {
        prefs.edit().putString(KEY_R2_OUTPUT_HORIZONTAL_ALIGN, normalizeAlign(align)).apply();
    }

    public int getRegion2CropStartX() { return prefs.getInt(KEY_R2_CROP_START_X, DEFAULT_CROP_START_X); }
    public int getRegion2CropEndX()   { return prefs.getInt(KEY_R2_CROP_END_X, DEFAULT_CROP_END_X); }
    public int getRegion2CropStartY() { return prefs.getInt(KEY_R2_CROP_START_Y, DEFAULT_CROP_START_Y); }
    public int getRegion2CropEndY()   { return prefs.getInt(KEY_R2_CROP_END_Y, DEFAULT_CROP_END_Y); }

    public void setRegion2CropRange(int startX, int endX, int startY, int endY) {
        prefs.edit()
            .putInt(KEY_R2_CROP_START_X, startX)
            .putInt(KEY_R2_CROP_END_X, endX)
            .putInt(KEY_R2_CROP_START_Y, startY)
            .putInt(KEY_R2_CROP_END_Y, endY)
            .apply();
    }

    // ========== Region 3 配置 ==========
    public String getRegion3SourceType() {
        String val = prefs.getString(KEY_R3_SOURCE_TYPE, DEFAULT_R3_SOURCE_TYPE);
        return normalizeSourceType(val);
    }

    public void setRegion3SourceType(String type) {
        prefs.edit().putString(KEY_R3_SOURCE_TYPE, normalizeSourceType(type)).apply();
    }

    public String getRegion3ImageName() {
        return prefs.getString(KEY_R3_IMAGE_NAME, DEFAULT_IMAGE_NAME);
    }

    public void setRegion3ImageName(String name) {
        prefs.edit().putString(KEY_R3_IMAGE_NAME, name != null ? name : "").apply();
    }

    public String getRegion3ImageUrl() {
        return prefs.getString(KEY_R3_IMAGE_URL, "");
    }

    public void setRegion3ImageUrl(String url) {
        prefs.edit().putString(KEY_R3_IMAGE_URL, url != null ? url : "").apply();
    }

    public String getRegion3ImageLocalPath() {
        return prefs.getString(KEY_R3_IMAGE_LOCAL_PATH, "");
    }

    public void setRegion3ImageLocalPath(String path) {
        prefs.edit().putString(KEY_R3_IMAGE_LOCAL_PATH, path != null ? path : "").apply();
    }

    public boolean isRegion3Enabled() {
        return prefs.getBoolean(KEY_R3_ENABLED, DEFAULT_R3_ENABLED);
    }

    public void setRegion3Enabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_R3_ENABLED, enabled).apply();
    }

    public String getRegion3StretchMode() {
        return prefs.getString(KEY_R3_STRETCH_MODE, DEFAULT_R3_STRETCH_MODE);
    }

    public void setRegion3StretchMode(String mode) {
        prefs.edit().putString(KEY_R3_STRETCH_MODE, mode).apply();
    }

    public int getRegion3OutputWidthPercent() {
        return prefs.getInt(KEY_R3_OUTPUT_WIDTH_PERCENT, DEFAULT_R3_OUTPUT_WIDTH_PERCENT);
    }

    public void setRegion3OutputWidthPercent(int percent) {
        prefs.edit().putInt(KEY_R3_OUTPUT_WIDTH_PERCENT, clamp(percent, 0, MAX_TOTAL_WIDTH_PERCENT)).apply();
    }

    public String getRegion3OutputHorizontalAlign() {
        return prefs.getString(KEY_R3_OUTPUT_HORIZONTAL_ALIGN, DEFAULT_R3_OUTPUT_HORIZONTAL_ALIGN);
    }

    public void setRegion3OutputHorizontalAlign(String align) {
        prefs.edit().putString(KEY_R3_OUTPUT_HORIZONTAL_ALIGN, normalizeAlign(align)).apply();
    }

    public int getRegion3CropStartX() { return prefs.getInt(KEY_R3_CROP_START_X, DEFAULT_CROP_START_X); }
    public int getRegion3CropEndX()   { return prefs.getInt(KEY_R3_CROP_END_X, DEFAULT_CROP_END_X); }
    public int getRegion3CropStartY() { return prefs.getInt(KEY_R3_CROP_START_Y, DEFAULT_CROP_START_Y); }
    public int getRegion3CropEndY()   { return prefs.getInt(KEY_R3_CROP_END_Y, DEFAULT_CROP_END_Y); }

    public void setRegion3CropRange(int startX, int endX, int startY, int endY) {
        prefs.edit()
            .putInt(KEY_R3_CROP_START_X, startX)
            .putInt(KEY_R3_CROP_END_X, endX)
            .putInt(KEY_R3_CROP_START_Y, startY)
            .putInt(KEY_R3_CROP_END_Y, endY)
            .apply();
    }

    public void saveLayout(String stretchMode,
                           int cropStartX, int cropEndX, int cropStartY, int cropEndY,
                           int outputWidthPercent, String outputHorizontalAlign) {
        prefs.edit()
            .putString(KEY_STRETCH_MODE, stretchMode)
            .putInt(KEY_CROP_START_X, cropStartX)
            .putInt(KEY_CROP_END_X, cropEndX)
            .putInt(KEY_CROP_START_Y, cropStartY)
            .putInt(KEY_CROP_END_Y, cropEndY)
            .putInt(KEY_OUTPUT_WIDTH_PERCENT, clamp(outputWidthPercent, 0, 100))
            .putString(KEY_OUTPUT_HORIZONTAL_ALIGN, normalizeAlign(outputHorizontalAlign))
            .apply();
    }

    // ========== 歌词源 ==========
    public String[] getLyricProviderOrder() {
        String v = prefs.getString(KEY_LYRIC_PROVIDER_ORDER, DEFAULT_LYRIC_PROVIDER_ORDER);
        if (v == null || v.trim().isEmpty()) {
            v = DEFAULT_LYRIC_PROVIDER_ORDER;
        }
        List<String> list = new ArrayList<>();
        for (String part : v.split(",")) {
            String s = part.trim();
            if (!s.isEmpty() && !list.contains(s)) {
                list.add(s);
            }
        }
        return list.toArray(new String[0]);
    }

    public void setLyricProviderOrder(String[] ids) {
        if (ids == null || ids.length == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids[i]);
        }
        prefs.edit().putString(KEY_LYRIC_PROVIDER_ORDER, sb.toString()).apply();
    }

    // ========== 重置为默认 ==========
    public void resetToDefaults() {
        prefs.edit()
            .putInt(KEY_WIDTH, DEFAULT_WIDTH)
            .putInt(KEY_HEIGHT, DEFAULT_HEIGHT)
            .putString(KEY_STRETCH_MODE, DEFAULT_STRETCH_MODE)
            .putInt(KEY_CROP_START_X, DEFAULT_CROP_START_X)
            .putInt(KEY_CROP_END_X, DEFAULT_CROP_END_X)
            .putInt(KEY_CROP_START_Y, DEFAULT_CROP_START_Y)
            .putInt(KEY_CROP_END_Y, DEFAULT_CROP_END_Y)
            .putInt(KEY_OUTPUT_WIDTH_PERCENT, DEFAULT_OUTPUT_WIDTH_PERCENT)
            .putString(KEY_OUTPUT_HORIZONTAL_ALIGN, DEFAULT_OUTPUT_HORIZONTAL_ALIGN)
            .putString(KEY_R2_SOURCE_TYPE, DEFAULT_R2_SOURCE_TYPE)
            .putString(KEY_R2_IMAGE_NAME, DEFAULT_R2_IMAGE_NAME)
            .putString(KEY_R2_IMAGE_URL, "")
            .putString(KEY_R2_IMAGE_LOCAL_PATH, "")
            .putBoolean(KEY_R2_ENABLED, DEFAULT_R2_ENABLED)
            .putString(KEY_R2_STRETCH_MODE, DEFAULT_R2_STRETCH_MODE)
            .putInt(KEY_R2_CROP_START_X, DEFAULT_CROP_START_X)
            .putInt(KEY_R2_CROP_END_X, DEFAULT_CROP_END_X)
            .putInt(KEY_R2_CROP_START_Y, DEFAULT_CROP_START_Y)
            .putInt(KEY_R2_CROP_END_Y, DEFAULT_CROP_END_Y)
            .putInt(KEY_R2_OUTPUT_WIDTH_PERCENT, DEFAULT_R2_OUTPUT_WIDTH_PERCENT)
            .putString(KEY_R2_OUTPUT_HORIZONTAL_ALIGN, DEFAULT_R2_OUTPUT_HORIZONTAL_ALIGN)
            .putString(KEY_R3_SOURCE_TYPE, DEFAULT_R3_SOURCE_TYPE)
            .putString(KEY_R3_IMAGE_NAME, DEFAULT_IMAGE_NAME)
            .putString(KEY_R3_IMAGE_URL, "")
            .putString(KEY_R3_IMAGE_LOCAL_PATH, "")
            .putBoolean(KEY_R3_ENABLED, DEFAULT_R3_ENABLED)
            .putString(KEY_R3_STRETCH_MODE, DEFAULT_R3_STRETCH_MODE)
            .putInt(KEY_R3_CROP_START_X, DEFAULT_CROP_START_X)
            .putInt(KEY_R3_CROP_END_X, DEFAULT_CROP_END_X)
            .putInt(KEY_R3_CROP_START_Y, DEFAULT_CROP_START_Y)
            .putInt(KEY_R3_CROP_END_Y, DEFAULT_CROP_END_Y)
            .putInt(KEY_R3_OUTPUT_WIDTH_PERCENT, DEFAULT_R3_OUTPUT_WIDTH_PERCENT)
            .putString(KEY_R3_OUTPUT_HORIZONTAL_ALIGN, DEFAULT_R3_OUTPUT_HORIZONTAL_ALIGN)
            .putString(KEY_LYRIC_PROVIDER_ORDER, DEFAULT_LYRIC_PROVIDER_ORDER)
            .apply();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeAlign(String value) {
        if ("left".equals(value) || "right".equals(value)) {
            return value;
        }
        return "center";
    }

    private String normalizeSourceType(String value) {
        if ("image".equals(value)) {
            return "image";
        }
        if ("music".equals(value)) {
            return "music";
        }
        return "screen";
    }
}
