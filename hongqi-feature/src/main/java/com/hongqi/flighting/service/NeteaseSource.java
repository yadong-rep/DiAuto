package com.hongqi.flighting.service;

import android.util.Log;

import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云音乐歌词源（简体），直连公开接口，无需 weapi 加密。
 * 覆盖抖音 / 汽水等热歌更全，作为简体优先源。
 *
 * <p>依赖两个公开 GET 接口：
 * <ol>
 *   <li>搜索拿歌曲 id：/api/search/get/web?s=歌名 歌手 &amp;type=1</li>
 *   <li>按 id 拿歌词：/api/song/lyric?id=xxx&amp;lv=-1&amp;kv=-1&amp;tv=-1</li>
 * </ol>
 */
public final class NeteaseSource implements LyricSource {
    private static final String TAG = "NeteaseSource";
    private static final String SEARCH_URL = "https://music.163.com/api/search/get/web";
    private static final String LYRIC_URL = "https://music.163.com/api/song/lyric";
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    @Override
    public String id() {
        return "netease";
    }

    @Override
    public String displayName() {
        return "网易云音乐";
    }

    @Override
    public List<LyricsProvider.LrcLine> fetch(String title, String artist, String album) {
        try {
            long id = searchSongId(title, artist);
            if (id <= 0) {
                return Collections.emptyList();
            }
            String url = LYRIC_URL + "?id=" + id + "&lv=-1&kv=-1&tv=-1";
            String json = LyricsProvider.httpGet(url);
            if (json == null) {
                return Collections.emptyList();
            }
            // 取第一个 "lyric" 字段（对象里字段顺序通常是 lrc.lyric 在前，即原文歌词）
            String lrc = LyricsProvider.extractJsonField(json, "lyric");
            if (lrc == null || lrc.isEmpty() || "null".equals(lrc)) {
                return Collections.emptyList();
            }
            Log.i(TAG, "命中: " + title + " - " + artist);
            return LyricsProvider.parse(lrc);
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + title, e);
            return Collections.emptyList();
        }
    }

    private long searchSongId(String title, String artist) throws Exception {
        String q = title + (artist != null && !artist.isEmpty() ? " " + artist : "");
        String url = SEARCH_URL + "?s=" + URLEncoder.encode(q, "UTF-8")
            + "&type=1&limit=5&offset=0";
        String json = LyricsProvider.httpGet(url);
        if (json == null) {
            return -1L;
        }
        Matcher m = ID_PATTERN.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return -1L;
    }
}