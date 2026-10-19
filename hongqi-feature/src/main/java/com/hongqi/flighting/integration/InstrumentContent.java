package com.hongqi.flighting.integration;

/** Immutable snapshots shared with the host. No screen pixels or media-session scraping. */
public final class InstrumentContent {
    private InstrumentContent() {}
    public static final class Navigation {
        public final boolean active;
        public final String arrow, action, road;
        public final Integer distanceMeters;
        public Navigation(boolean active, String arrow, String action, String road, Integer distance) {
            this.active = active; this.arrow = arrow; this.action = action;
            this.road = road; this.distanceMeters = distance;
        }
    }
    public static final class Music {
        public final String title, artist;
        private final byte[] artwork;
        public final long durationMs, positionMs, updatedAtMs;
        public final boolean playing;
        public Music(String title, String artist, byte[] art, long duration, long position,
                     boolean playing, long updatedAt) {
            this.title = title; this.artist = artist;
            this.artwork = art == null ? null : art.clone();
            this.durationMs = Math.max(0, duration); this.positionMs = Math.max(0, position);
            this.playing = playing; this.updatedAtMs = updatedAt;
        }
        public byte[] artwork() { return artwork == null ? null : artwork.clone(); }
        public long positionAt(long now) {
            long position = positionMs + (playing ? Math.max(0, now - updatedAtMs) : 0);
            return durationMs > 0 ? Math.min(durationMs, position) : position;
        }
    }
    private static volatile Navigation navigation = emptyNavigation();
    private static volatile Music music = emptyMusic();
    public static Navigation navigation() { return navigation; }
    public static Music music() { return music; }
    public static void navigation(Navigation value) { navigation = value; }
    public static synchronized void metadata(String title, String artist, byte[] art, long duration, long now) {
        Music old = music;
        boolean changed = !old.title.equals(title) || !old.artist.equals(artist) || old.durationMs != duration;
        music = new Music(title, artist, art, duration,
            changed && !old.title.isEmpty() ? 0 : old.positionAt(now), old.playing, now);
    }
    public static synchronized void playback(Long position, boolean playing, long now) {
        Music old = music;
        music = new Music(old.title, old.artist, old.artwork, old.durationMs,
            position == null ? old.positionAt(now) : position, playing, now);
    }
    public static synchronized void clear() { navigation = emptyNavigation(); music = emptyMusic(); }
    private static Navigation emptyNavigation() { return new Navigation(false, "", "", "", null); }
    private static Music emptyMusic() { return new Music("", "", null, 0, 0, false, 0); }
}
