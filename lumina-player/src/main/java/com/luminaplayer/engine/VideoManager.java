package com.luminaplayer.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.io.File;

/**
 * Facade for video-related operations on the VLC media player.
 */
public class VideoManager {

    private static final Logger log = LoggerFactory.getLogger(VideoManager.class);

    private final EmbeddedMediaPlayer mediaPlayer;

    public VideoManager(EmbeddedMediaPlayer mediaPlayer) {
        this.mediaPlayer = mediaPlayer;
    }

    public void setAspectRatio(String ratio) {
        mediaPlayer.video().setAspectRatio(ratio);
        log.debug("Aspect ratio set to: {}", ratio == null ? "default" : ratio);
    }

    public void setScale(float scale) {
        mediaPlayer.video().setScale(scale);
        log.debug("Video scale set to: {}", scale);
    }

    public boolean takeSnapshot(File destination) {
        if (destination == null) {
            return false;
        }
        boolean result = mediaPlayer.snapshots().save(destination);
        if (result) {
            log.info("Snapshot saved to: {}", destination.getAbsolutePath());
        } else {
            log.warn("Failed to save snapshot to: {}", destination.getAbsolutePath());
        }
        return result;
    }

    public java.awt.Dimension getVideoDimension() {
        return mediaPlayer.video().videoDimension();
    }
}
