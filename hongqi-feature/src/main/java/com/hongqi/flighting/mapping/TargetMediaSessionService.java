package com.hongqi.flighting.mapping;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.KeyEvent;



import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标媒体会话服务。完整复刻参考方控 App（com.hzsoft.sidebar.TargetMediaSessionService）：
 * 作为通知监听服务跟踪活动媒体会话，并把媒体按键定向派发给绑定 App 的活动会话。
 */
public class TargetMediaSessionService extends NotificationListenerService {
    private static final String TAG = "SidebarSteering";

    private static volatile TargetMediaSessionService INSTANCE;
    private static volatile List<MediaController> activeSessions = Collections.emptyList();

    /** 从通知 bigText/textLines 提取的歌词缓存：key = 包名，value = 歌词文本。 */
    private static final Map<String, String> notificationLyrics = new HashMap<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener listener =
        new MediaSessionManager.OnActiveSessionsChangedListener() {
            @Override
            public void onActiveSessionsChanged(List<MediaController> controllers) {
                updateSessions(controllers);
            }
        };

    private MediaSessionManager manager;

    /** 把媒体键定向派发给指定包名的活动会话；成功返回 true。 */
    public static boolean dispatch(String packageName, int keyCode) {
        if (packageName == null || packageName.length() == 0) {
            return false;
        }
        for (MediaController controller : activeSessions) {
            if (packageName.equals(controller.getPackageName())) {
                long time = System.currentTimeMillis();
                controller.dispatchMediaButtonEvent(
                    new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0));
                controller.dispatchMediaButtonEvent(
                    new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0));
                Log.i(TAG, "media target=" + packageName + " key=" + keyCode);
                return true;
            }
        }
        Log.i(TAG, "media target unavailable=" + packageName);
        return false;
    }

    /** 通知使用权是否已在系统设置中开启。 */
    public static boolean isEnabledInSettings(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
            "enabled_notification_listeners");
        if (enabled == null) {
            return false;
        }
        return enabled.toLowerCase().contains(
            new ComponentName(context, TargetMediaSessionService.class).flattenToString().toLowerCase());
    }

    /** 当前正在播放的音乐信息（跨播放软件，来自活动 MediaSession 元数据）。 */
    public static final class MediaInfo {
        public final String title;
        public final String artist;
        public final String album;
        public final Bitmap artwork;
        public final String artUri;
        public final long durationMs;
        public final long positionMs;
        public final boolean playing;
        public final String lyrics;       // 从 MediaMetadata 自定义字段直接读取的歌词

        MediaInfo(String title, String artist, String album,
                  Bitmap artwork, String artUri, long durationMs,
                  long positionMs, boolean playing, String lyrics) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artwork = artwork;
            this.artUri = artUri;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.playing = playing;
            this.lyrics = lyrics;
        }
    }

    /**
     * 读取当前正在播放的音乐信息：优先命中正在播放的活动会话；无正在播放会话则回退到首个带元数据的会话。
     * 读取不到（未授权通知使用权、无活动会话、无元数据）时返回 null。
     */
    public static MediaInfo getCurrentPlayingMedia() {
        for (MediaController controller : activeSessions) {
            try {
                PlaybackState state = controller.getPlaybackState();
                boolean playingNow = state != null && isActivelyPlaying(state.getState());
                if (!playingNow) {
                    continue;
                }
                MediaMetadata metadata = controller.getMetadata();
                if (metadata == null) {
                    continue;
                }
                MediaInfo info = extractMediaInfo(controller.getPackageName(), metadata, state);
                if (info != null) {
                    return info;
                }
            } catch (Exception e) {
                Log.w(TAG, "inspect media session failed", e);
            }
        }
        // 回退：没有正在播放状态的会话时，取首个带元数据的会话（覆盖部分车机音乐 App 不更新 STATE_PLAYING 的情况）
        for (MediaController controller : activeSessions) {
            try {
                MediaMetadata metadata = controller.getMetadata();
                if (metadata == null) {
                    continue;
                }
                PlaybackState state = controller.getPlaybackState();
                MediaInfo info = extractMediaInfo(controller.getPackageName(), metadata, state);
                if (info != null) {
                    return info;
                }
            } catch (Exception e) {
                Log.w(TAG, "inspect fallback media session failed", e);
            }
        }
        return null;
    }

    /** 从 MediaMetadata + PlaybackState 提取完整播放信息。 */
    private static MediaInfo extractMediaInfo(String packageName, MediaMetadata metadata, PlaybackState state) {
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        Bitmap artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (artwork == null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        String artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (artUri == null) {
            artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
        }
        if (artwork == null && artUri != null && !artUri.isEmpty()) {
            artwork = loadArtworkFromUri(artUri);
        }

        long positionMs = state != null ? state.getPosition() : 0;
        boolean playing = state != null && isActivelyPlaying(state.getState());

        // 扫描 MediaMetadata 所有自定义字段，查找歌词文本
        String lyrics = scanLyricsFromMetadata(metadata);
        // 车机部分音乐 App（如汽水音乐）不把歌词放进 MediaSession 元数据，回退到通知歌词
        if (lyrics == null) {
            lyrics = getNotificationLyrics(packageName);
        }

        if (title != null || artist != null || artwork != null) {
            return new MediaInfo(title, artist, album, artwork, artUri,
                duration, positionMs, playing, lyrics);
        }
        return null;
    }

    /**
     * 遍历 MediaMetadata 所有 key，查找可能包含歌词的字段。
     * 车机音乐服务（QisiBtMusicService/QisiLocalMusicService）可能在自定义字段中发布歌词。
     * 匹配关键词：lyric, lrc, word, 歌词, ge_ci
     */
    private static String scanLyricsFromMetadata(MediaMetadata metadata) {
        try {
            for (String key : metadata.keySet()) {
                if (key == null || key.isEmpty()) {
                    continue;
                }
                String lowerKey = key.toLowerCase();
                // 匹配歌词相关的 key
                if (lowerKey.contains("lyric") || lowerKey.contains("lrc")
                    || lowerKey.contains("word") || lowerKey.contains("歌词")
                    || lowerKey.contains("ge_ci") || lowerKey.contains("geshi")) {
                    String value = metadata.getString(key);
                    if (value != null && value.length() > 10) {
                        Log.i(TAG, ">>> 在 MediaMetadata 中发现歌词字段: key=" + key
                            + " len=" + value.length() + " preview="
                            + value.substring(0, Math.min(80, value.length())));
                        return value;
                    }
                }
            }
            // 车机音乐 App 常把「当前一句歌词」放到 DISPLAY_SUBTITLE / DISPLAY_DESCRIPTION（锁屏/车机显示字段）
            String subtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (subtitle != null && subtitle.length() > 1) {
                Log.i(TAG, ">>> 从 DISPLAY_SUBTITLE 读取歌词: len=" + subtitle.length()
                    + " preview=" + subtitle.substring(0, Math.min(80, subtitle.length())));
                return subtitle;
            }
            String description = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
            if (description != null && description.length() > 1) {
                Log.i(TAG, ">>> 从 DISPLAY_DESCRIPTION 读取歌词: len=" + description.length()
                    + " preview=" + description.substring(0, Math.min(80, description.length())));
                return description;
            }

            // 调试：打印所有 key（帮助排查车机是否提供了歌词字段）
            Log.i(TAG, "MediaMetadata keys: " + metadata.keySet());
        } catch (Exception e) {
            Log.w(TAG, "scanLyricsFromMetadata failed", e);
        }
        return null;
    }

    /** 从通知缓存取歌词（按包名）。 */
    private static String getNotificationLyrics(String packageName) {
        if (packageName == null) return null;
        synchronized (notificationLyrics) {
            String lyric = notificationLyrics.get(packageName);
            if (lyric != null) {
                Log.i(TAG, ">>> 回退使用通知歌词: pkg=" + packageName + " len=" + lyric.length());
            }
            return lyric;
        }
    }

    /**
     * 通知发布回调：提取歌词文本并按包名缓存。
     * 车机音乐 App（如汽水音乐，包名 com.android.htmlviewer）不把歌词放进 MediaSession 元数据，
     * 而是通过通知的 bigText/textLines 展示，这里从中兜底读取。
     */
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getPackageName() == null) return;
            String lyric = extractLyricsFromNotification(sbn);
            if (lyric != null) {
                synchronized (notificationLyrics) {
                    notificationLyrics.put(sbn.getPackageName(), lyric);
                }
                Log.i(TAG, ">>> 从通知提取歌词: pkg=" + sbn.getPackageName()
                    + " len=" + lyric.length() + " preview="
                    + lyric.substring(0, Math.min(60, lyric.length())));
            }
        } catch (Exception e) {
            Log.w(TAG, "onNotificationPosted lyrics failed", e);
        }
    }

    /** 从通知 extras 提取歌词文本（优先 bigText，其次 textLines，再扫描自定义 key）。 */
    private static String extractLyricsFromNotification(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null) return null;
            Bundle extras = n.extras;

            CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (big != null && big.length() > 10) return big.toString();

            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (CharSequence line : lines) {
                    if (line != null) sb.append(line).append('\n');
                }
                if (sb.length() > 10) return sb.toString().trim();
            }

            for (String key : extras.keySet()) {
                if (key == null) continue;
                String lower = key.toLowerCase();
                if (lower.contains("lyric") || lower.contains("lrc")
                    || lower.contains("geci") || lower.contains("歌词")) {
                    Object v = extras.get(key);
                    if (v instanceof CharSequence && ((CharSequence) v).length() > 10) {
                        return v.toString();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "extractLyricsFromNotification failed", e);
        }
        return null;
    }

    /** 从 content:// 或 file:// URI 加载封面 Bitmap。 */
    private static Bitmap loadArtworkFromUri(String uriStr) {
        if (INSTANCE == null) {
            return null;
        }
        try {
            Uri uri = Uri.parse(uriStr);
            InputStream is = null;
            try {
                is = INSTANCE.getContentResolver().openInputStream(uri);
                if (is != null) {
                    return BitmapFactory.decodeStream(is);
                }
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "load artwork from URI failed: " + uriStr, e);
        }
        return null;
    }

    private static KeyMappingManager.Mapping findMapping(List<KeyMappingManager.Mapping> mappings,
                                                         String packageName) {
        for (KeyMappingManager.Mapping mapping : mappings) {
            if (mapping.packageName.equals(packageName)) {
                return mapping;
            }
        }
        return null;
    }

    private static boolean isActivelyPlaying(int state) {
        return state == PlaybackState.STATE_PLAYING
            || state == PlaybackState.STATE_BUFFERING
            || state == PlaybackState.STATE_CONNECTING
            || state == PlaybackState.STATE_FAST_FORWARDING
            || state == PlaybackState.STATE_REWINDING
            || state == PlaybackState.STATE_SKIPPING_TO_NEXT
            || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
            || state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
    }

    /**
     * 在命中同一指纹的多个映射里选出实际应派发的那一个：
     * 优先正在播放的绑定 App；若未绑定 App 正在播放则放行；否则退化为媒体会话优先级最高的绑定 App。
     */
    public static KeyMappingManager.Mapping selectMapping(Context context,
                                                          List<KeyMappingManager.Mapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return null;
        }
        KeyMappingManager.Mapping playingMapping = null;
        KeyMappingManager.Mapping firstMapping = null;
        for (MediaController controller : activeSessions) {
            KeyMappingManager.Mapping mapping = findMapping(mappings, controller.getPackageName());
            PlaybackState playbackState = controller.getPlaybackState();
            boolean playing = playbackState != null && isActivelyPlaying(playbackState.getState());
            if (playing && mapping == null) {
                Log.i(TAG, "mapping bypassed for active media=" + controller.getPackageName());
                return null;
            } else if (mapping != null) {
                if (firstMapping == null) {
                    firstMapping = mapping;
                }
                if (playing && playingMapping == null) {
                    playingMapping = mapping;
                }
            }
        }
        if (playingMapping != null) {
            return playingMapping;
        }
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null && audioManager.isMusicActive()) {
                Log.i(TAG, "mapping bypassed for active car audio");
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to inspect active audio", e);
        }
        return firstMapping;
    }

    private static void updateSessions(List<MediaController> controllers) {
        activeSessions = controllers == null ? Collections.emptyList() : new ArrayList<>(controllers);
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        if (manager != null) {
            manager.removeOnActiveSessionsChangedListener(listener);
        }
        activeSessions = Collections.emptyList();
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        INSTANCE = this;
        MediaSessionManager mediaSessionManager =
            (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        manager = mediaSessionManager;
        if (mediaSessionManager == null) {
            return;
        }
        ComponentName componentName = new ComponentName(this, TargetMediaSessionService.class);
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(listener, componentName);
            updateSessions(mediaSessionManager.getActiveSessions(componentName));
        } catch (SecurityException e) {
            activeSessions = Collections.emptyList();
            Log.w(TAG, "media session access denied", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (manager != null) {
            manager.removeOnActiveSessionsChangedListener(listener);
        }
        activeSessions = Collections.emptyList();
        super.onListenerDisconnected();
    }
}