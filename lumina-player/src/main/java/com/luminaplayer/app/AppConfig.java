package com.luminaplayer.app;

/**
 * Application-wide constants and configuration.
 */
public final class AppConfig {

    public static final String APP_NAME = "LuminaPlayer";
    public static final String APP_VERSION = "1.0.0";
    public static final int DEFAULT_VOLUME = 80;
    public static final int MIN_WINDOW_WIDTH = 800;
    public static final int MIN_WINDOW_HEIGHT = 500;
    public static final int DEFAULT_WINDOW_WIDTH = 1280;
    public static final int DEFAULT_WINDOW_HEIGHT = 720;

    public static final String[] VIDEO_EXTENSIONS = {
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v",
        "mpg", "mpeg", "3gp", "ts", "vob", "ogv"
    };

    public static final String[] AUDIO_EXTENSIONS = {
        "mp3", "flac", "wav", "aac", "ogg", "wma", "m4a", "opus", "aiff"
    };

    public static final String[] SUBTITLE_EXTENSIONS = {
        "srt", "ass", "ssa", "sub", "idx", "vtt", "dsrt"
    };

    public static final float[] PLAYBACK_RATES = {
        0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f
    };

    // Chunked subtitle generation
    public static final long CHUNK_DURATION_MS = 30_000;
    public static final int CHUNK_THREAD_POOL_SIZE = 2;
    public static final long SUBTITLE_POLL_INTERVAL_MS = 100;
    public static final String DSRT_EXTENSION = "dsrt";

    private AppConfig() {
        // Prevent instantiation
    }
}
