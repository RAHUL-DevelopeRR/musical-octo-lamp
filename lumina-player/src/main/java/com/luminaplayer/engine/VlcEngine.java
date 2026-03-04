package com.luminaplayer.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

/**
 * Core VLC engine wrapping vlcj MediaPlayerFactory and EmbeddedMediaPlayer.
 * Owns the lifecycle of native VLC resources.
 */
public class VlcEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VlcEngine.class);

    private final MediaPlayerFactory factory;
    private final EmbeddedMediaPlayer mediaPlayer;
    private final AudioManager audioManager;
    private final SubtitleManager subtitleManager;
    private final VideoManager videoManager;

    public VlcEngine() {
        log.info("Initializing VLC engine...");
        this.factory = new MediaPlayerFactory(
            "--no-video-title-show",
            "--quiet",
            "--no-metadata-network-access"
        );
        this.mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();
        this.audioManager = new AudioManager(mediaPlayer);
        this.subtitleManager = new SubtitleManager(mediaPlayer);
        this.videoManager = new VideoManager(mediaPlayer);
        log.info("VLC engine initialized successfully.");
    }

    public MediaPlayerFactory factory() {
        return factory;
    }

    public EmbeddedMediaPlayer mediaPlayer() {
        return mediaPlayer;
    }

    public AudioManager audio() {
        return audioManager;
    }

    public SubtitleManager subtitles() {
        return subtitleManager;
    }

    public VideoManager video() {
        return videoManager;
    }

    @Override
    public void close() {
        log.info("Shutting down VLC engine...");
        try {
            mediaPlayer.controls().stop();
        } catch (Exception e) {
            log.debug("Error stopping playback during shutdown", e);
        }
        try {
            mediaPlayer.release();
        } catch (Exception e) {
            log.warn("Error releasing media player", e);
        }
        try {
            factory.release();
        } catch (Exception e) {
            log.warn("Error releasing media player factory", e);
        }
        log.info("VLC engine shut down.");
    }
}
