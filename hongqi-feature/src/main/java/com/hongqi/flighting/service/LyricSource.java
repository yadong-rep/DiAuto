package com.hongqi.flighting.service;

import java.util.List;

/**
 * 单个歌词源的抽象。实现类负责从某个平台（LRCLIB / 网易云 等）抓取并解析歌词。
 *
 * <p>编排逻辑在 {@link LyricsProvider}：按配置的顺序依次尝试每个源，命中即返回，
 * 前一个源成功就不会再请求后面的源（后面的作为兜底）。
 */
public interface LyricSource {
    /** 配置标识符（用于 ConfigManager 的顺序与开关）。 */
    String id();

    /** 展示名（用于日志 / 设置界面）。 */
    String displayName();

    /** 同步抓取歌词（需在后台线程调用）；找不到返回空列表，绝不返回 null。 */
    List<LyricsProvider.LrcLine> fetch(String title, String artist, String album);
}