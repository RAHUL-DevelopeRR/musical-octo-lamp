package com.luminaplayer.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.util.List;

/**
 * Facade for audio-related operations on the VLC media player.
 */
public class AudioManager {

    private static final Logger log = LoggerFactory.getLogger(AudioManager.class);

    private final EmbeddedMediaPlayer mediaPlayer;

    public AudioManager(EmbeddedMediaPlayer mediaPlayer) {
        this.mediaPlayer = mediaPlayer;
    }

    public int getVolume() {
        return mediaPlayer.audio().volume();
    }

    public void setVolume(int percent) {
        int clamped = Math.max(0, Math.min(200, percent));
        mediaPlayer.audio().setVolume(clamped);
    }

    public boolean isMuted() {
        return mediaPlayer.audio().isMute();
    }

    public void setMuted(boolean muted) {
        mediaPlayer.audio().setMute(muted);
    }

    public void toggleMute() {
        mediaPlayer.audio().setMute(!mediaPlayer.audio().isMute());
    }

    public int getAudioTrackCount() {
        return mediaPlayer.audio().trackCount();
    }

    public int getAudioTrack() {
        return mediaPlayer.audio().track();
    }

    public void setAudioTrack(int trackId) {
        mediaPlayer.audio().setTrack(trackId);
        log.debug("Audio track set to: {}", trackId);
    }

    public List<? extends uk.co.caprica.vlcj.player.base.TrackDescription> getAudioTrackDescriptions() {
        return mediaPlayer.audio().trackDescriptions();
    }

    public void setAudioDelay(long microseconds) {
        mediaPlayer.audio().setDelay(microseconds);
        log.debug("Audio delay set to: {} us", microseconds);
    }

    public long getAudioDelay() {
        return mediaPlayer.audio().delay();
    }
}
