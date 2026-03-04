package com.luminaplayer.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.io.File;
import java.util.List;

/**
 * Facade for subtitle-related operations on the VLC media player.
 */
public class SubtitleManager {

    private static final Logger log = LoggerFactory.getLogger(SubtitleManager.class);

    private final EmbeddedMediaPlayer mediaPlayer;

    public SubtitleManager(EmbeddedMediaPlayer mediaPlayer) {
        this.mediaPlayer = mediaPlayer;
    }

    public boolean loadExternalSubtitle(File srtFile) {
        if (srtFile == null || !srtFile.exists()) {
            log.warn("Subtitle file does not exist: {}", srtFile);
            return false;
        }
        boolean result = mediaPlayer.subpictures().setSubTitleFile(srtFile.getAbsolutePath());
        log.info("Loaded external subtitle: {} (success={})", srtFile.getName(), result);
        return result;
    }

    public int getSubtitleTrackCount() {
        return mediaPlayer.subpictures().trackCount();
    }

    public int getSubtitleTrack() {
        return mediaPlayer.subpictures().track();
    }

    public void setSubtitleTrack(int trackId) {
        mediaPlayer.subpictures().setTrack(trackId);
        log.debug("Subtitle track set to: {}", trackId);
    }

    public List<? extends uk.co.caprica.vlcj.player.base.TrackDescription> getSubtitleTrackDescriptions() {
        return mediaPlayer.subpictures().trackDescriptions();
    }

    public void disableSubtitles() {
        mediaPlayer.subpictures().setTrack(-1);
        log.debug("Subtitles disabled");
    }

    public void setDelay(long microseconds) {
        mediaPlayer.subpictures().setDelay(microseconds);
        log.debug("Subtitle delay set to: {} us", microseconds);
    }

    public long getDelay() {
        return mediaPlayer.subpictures().delay();
    }
}
