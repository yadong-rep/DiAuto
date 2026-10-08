package com.hongqi.flighting.service;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词获取器：多源歌词编排，按配置顺序依次尝试（网易云/QQ 等简体源优先，LRCLIB 兜底）。
 *
 * <p>流程：
 * <ol>
 *   <li>从 MediaSession 拿到 标题 + 歌手 + 专辑</li>
 *   <li>按 {@link #enabledOrder} 顺序依次调用各 {@link LyricSource}</li>
 *   <li>解析 syncedLyrics（带时间轴的 LRC 格式），第一个命中即返回</li>
 *   <li>缓存结果，失败 1 分钟内不重试，成功 10 分钟内不重试</li>
 * </ol>
 */
public final class LyricsProvider {

    private static final String TAG = "LyricsProvider";
    private static final int TIMEOUT_MS = 5000;
    private static final long CACHE_FAIL_TTL_MS = 60_000L;   // 失败后 1 分钟再试
    private static final long CACHE_OK_TTL_MS = 600_000L;   // 成功后 10 分钟刷新
    private static final long DISK_CACHE_TTL_MS = 30L * 24 * 3600 * 1000;  // 磁盘缓存 30 天

    /** 所有可用的歌词源，按 id 注册。 */
    private static final Map<String, LyricSource> REGISTRY = new HashMap<>();
    static {
        REGISTRY.put("netease", new NeteaseSource());
        REGISTRY.put("lrclib", new LrclibSource());
    }

    /** 启用顺序，可被 {@link #configure(String[])} / ConfigManager 覆盖；默认简体优先（网易云 → LRCLIB 兜底）。 */
    private static volatile String[] enabledOrder = { "netease", "lrclib" };

    /** 覆盖歌词源启用顺序（供 ConfigManager 在启动时注入）。 */
    public static void configure(String[] ids) {
        if (ids != null && ids.length > 0) {
            enabledOrder = ids;
        }
    }

    /** 歌词磁盘缓存目录；null 表示未初始化（无 Context）。 */
    private static volatile File diskCacheDir;

    /** 初始化磁盘缓存目录（在 Service/Application 启动时传入 Context）。 */
    public static void initCache(Context context) {
        if (context != null) {
            diskCacheDir = new File(context.getFilesDir(), "lyrics_cache");
        }
    }

    /** 单行歌词（时间戳 + 文本）。 */
    public static final class LrcLine {
        public final long timeMs;
        public final String text;
        LrcLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private static final class CacheEntry {
        final List<LrcLine> lyrics;
        final boolean success;
        final long fetchedAt;
        CacheEntry(List<LrcLine> lyrics, boolean success) {
            this.lyrics = lyrics;
            this.success = success;
            this.fetchedAt = System.currentTimeMillis();
        }
    }

    private static final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public interface Callback {
        void onResult(List<LrcLine> lyrics);
    }

    /**
     * 异步获取歌词，优先命中缓存。
     * 回调可能在任意线程触发，调用方需自行处理线程切换。
     */
    public static void fetchAsync(String title, String artist, String album, Callback cb) {
        final String key = (title != null ? title : "") + "|" + (artist != null ? artist : "");
        CacheEntry cached = cache.get(key);
        if (cached != null) {
            long age = System.currentTimeMillis() - cached.fetchedAt;
            long ttl = cached.success ? CACHE_OK_TTL_MS : CACHE_FAIL_TTL_MS;
            if (age < ttl) {
                cb.onResult(cached.lyrics);
                return;
            }
        }
        new Thread(() -> {
            List<LrcLine> result = fetchSync(title, artist, album);
            cache.put(key, new CacheEntry(result, result != null));
            cb.onResult(result);
        }, "LyricsProvider").start();
    }

    /** 同步获取歌词（需在后台线程调用）：磁盘缓存 → 网络多源，命中即返回。 */
    public static List<LrcLine> fetchSync(String title, String artist, String album) {
        if (title == null || title.isEmpty()) {
            return null;
        }
        final String key = (title != null ? title : "") + "|" + (artist != null ? artist : "");

        // 1) 磁盘缓存（车机无网时离线命中）
        List<LrcLine> disk = loadDiskCache(key);
        if (disk != null && !disk.isEmpty()) {
            Log.i(TAG, "歌词命中磁盘缓存: " + title + " - " + artist);
            return disk;
        }

        // 2) 网络多源
        for (String id : enabledOrder) {
            LyricSource source = REGISTRY.get(id);
            if (source == null) {
                continue;
            }
            try {
                List<LrcLine> result = source.fetch(title, artist, album);
                if (result != null && !result.isEmpty()) {
                    Log.i(TAG, "歌词来自 " + source.displayName() + ": " + title + " - " + artist);
                    saveDiskCache(key, result);
                    return result;
                }
            } catch (Exception e) {
                Log.w(TAG, "歌词源 " + id + " 异常: " + title, e);
            }
        }
        Log.i(TAG, "所有歌词源均未命中: " + title + " - " + artist);
        return null;
    }

    /** 解析 LRC 文本为按时间排序的歌词行列表。 */
    public static List<LrcLine> parse(String lrcText) {
        List<LrcLine> result = new ArrayList<>();
        Pattern tsPattern = Pattern.compile("\\[(\\d+):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
        for (String line : lrcText.split("\n")) {
            Matcher m = tsPattern.matcher(line);
            List<Long> timestamps = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                lastEnd = m.end();
                int min = Integer.parseInt(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                int frac = 0;
                if (m.group(3) != null) {
                    String fracStr = m.group(3);
                    if (fracStr.length() == 1) {
                        frac = Integer.parseInt(fracStr) * 100;
                    } else if (fracStr.length() == 2) {
                        frac = Integer.parseInt(fracStr) * 10;
                    } else {
                        frac = Integer.parseInt(fracStr);
                    }
                }
                timestamps.add(min * 60_000L + sec * 1000L + frac);
            }
            if (timestamps.isEmpty()) {
                continue;
            }
            String text = line.substring(lastEnd).trim();
            if (text.isEmpty()) {
                continue;
            }
            for (long ts : timestamps) {
                result.add(new LrcLine(ts, text));
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return result;
    }

    /**
     * 解析无 LRC 时间戳的纯文本歌词（多行或单句），按行等间隔分配伪时间轴。
     * 用于兜底车机音乐 App 通过通知 / DISPLAY_SUBTITLE / DISPLAY_DESCRIPTION 提供的非 LRC 歌词。
     */
    public static List<LrcLine> parsePlain(String text) {
        List<LrcLine> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        long idx = 0;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            result.add(new LrcLine(idx * 5000L, line));
            idx++;
        }
        return result;
    }

    /**
     * 在已排序的歌词列表中，根据当前播放位置找到当前行索引。
     * 返回 -1 表示 position 小于第一行的时间戳。
     */
    public static int findCurrentLine(List<LrcLine> lyrics, long positionMs) {
        if (lyrics == null || lyrics.isEmpty()) {
            return -1;
        }
        int lo = 0, hi = lyrics.size() - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (lyrics.get(mid).timeMs <= positionMs) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    // ---- 供 LyricSource 实现类复用的工具 ----

    static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "CarlifeMirror/1.0");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.i(TAG, "HTTP " + code + " for " + urlStr);
                return null;
            }
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "HTTP GET failed: " + urlStr, e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 轻量 JSON 字段提取（避免引入 JSON 库），支持转义序列。 */
    static String extractJsonField(String json, String fieldName) {
        String search = "\"" + fieldName + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) {
            return null;
        }
        idx += search.length();
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) {
            idx++;
        }
        if (idx >= json.length()) {
            return null;
        }
        if (json.charAt(idx) == '"') {
            StringBuilder sb = new StringBuilder();
            idx++;
            while (idx < json.length() && json.charAt(idx) != '"') {
                char c = json.charAt(idx);
                if (c == '\\' && idx + 1 < json.length()) {
                    char next = json.charAt(idx + 1);
                    switch (next) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case '\\': sb.append('\\'); break;
                        case '"': sb.append('"'); break;
                        case '/': sb.append('/'); break;
                        case 'u':
                            if (idx + 5 < json.length()) {
                                String hex = json.substring(idx + 2, idx + 6);
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                } catch (Exception e) {
                                    sb.append('?');
                                }
                                idx += 4;
                            }
                            break;
                        default: sb.append(c).append(next); break;
                    }
                    idx += 2;
                } else {
                    sb.append(c);
                    idx++;
                }
            }
            return sb.toString();
        }
        return null;
    }

    // ---- 磁盘缓存 ----

    private static void saveDiskCache(String key, List<LrcLine> lines) {
        File dir = diskCacheDir;
        if (dir == null || lines == null || lines.isEmpty()) {
            return;
        }
        File file = new File(dir, hashKey(key) + ".lrc");
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            StringBuilder sb = new StringBuilder();
            for (LrcLine line : lines) {
                sb.append(toLrcTimestamp(line.timeMs)).append(line.text).append('\n');
            }
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "写磁盘缓存失败: " + key, e);
        }
    }

    private static List<LrcLine> loadDiskCache(String key) {
        File dir = diskCacheDir;
        if (dir == null) {
            return null;
        }
        File file = new File(dir, hashKey(key) + ".lrc");
        if (!file.exists()) {
            return null;
        }
        if (System.currentTimeMillis() - file.lastModified() > DISK_CACHE_TTL_MS) {
            file.delete();
            return null;
        }
        try {
            String text = readAll(file);
            List<LrcLine> lines = parse(text);
            return (lines == null || lines.isEmpty()) ? null : lines;
        } catch (Exception e) {
            Log.w(TAG, "读磁盘缓存失败: " + key, e);
            return null;
        }
    }

    private static String readAll(File file) throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private static String hashKey(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private static String toLrcTimestamp(long timeMs) {
        long totalSec = timeMs / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        long hundredth = (timeMs % 1000) / 10;
        return String.format(Locale.US, "[%02d:%02d.%02d]", min, sec, hundredth);
    }
}
