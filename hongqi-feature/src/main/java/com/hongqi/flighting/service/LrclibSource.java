package com.hongqi.flighting.service;

import android.util.Log;

import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;

/**
 * LRCLIB（lrclib.net）社区歌词源，作为兜底。
 * 注意：LRCLIB 返回的歌词可能为繁体，需配合客户端本地简繁转换（可选）。
 */
public final class LrclibSource implements LyricSource {
    private static final String TAG = "LrclibSource";
    private static final String BASE = "https://lrclib.net/api";

    @Override
    public String id() {
        return "lrclib";
    }

    @Override
    public String displayName() {
        return "LRCLIB";
    }

    @Override
    public List<LyricsProvider.LrcLine> fetch(String title, String artist, String album) {
        try {
            // 1) 精确匹配
            StringBuilder url = new StringBuilder(BASE);
            url.append("/get?track_name=").append(URLEncoder.encode(title, "UTF-8"));
            url.append("&artist_name=").append(URLEncoder.encode(artist != null ? artist : "", "UTF-8"));
            if (album != null && !album.isEmpty()) {
                url.append("&album_name=").append(URLEncoder.encode(album, "UTF-8"));
            }
            String json = LyricsProvider.httpGet(url.toString());
            if (json != null) {
                String lrc = LyricsProvider.extractJsonField(json, "syncedLyrics");
                if (lrc == null || lrc.isEmpty() || "null".equals(lrc)) {
                    lrc = LyricsProvider.extractJsonField(json, "plainLyrics");
                }
                if (lrc != null && !lrc.isEmpty() && !"null".equals(lrc)) {
                    Log.i(TAG, "命中 /get: " + title + " - " + artist);
                    return LyricsProvider.parse(lrc);
                }
            }

            // 2) 搜索回退
            String query = title + " " + (artist != null ? artist : "");
            String searchUrl = BASE + "/search?q=" + URLEncoder.encode(query, "UTF-8");
            String searchJson = LyricsProvider.httpGet(searchUrl);
            if (searchJson != null) {
                String lrc = LyricsProvider.extractJsonField(searchJson, "syncedLyrics");
                if (lrc == null || lrc.isEmpty() || "null".equals(lrc)) {
                    lrc = LyricsProvider.extractJsonField(searchJson, "plainLyrics");
                }
                if (lrc != null && !lrc.isEmpty() && !"null".equals(lrc)) {
                    Log.i(TAG, "命中 /search: " + title + " - " + artist);
                    return LyricsProvider.parse(lrc);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + title, e);
        }
        return Collections.emptyList();
    }
}