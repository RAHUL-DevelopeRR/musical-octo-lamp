package com.luminaplayer.player;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

/**
 * Bridges vlcj native events to JavaFX observable properties on PlayerController.
 * All callbacks arrive on the vlcj native thread and are marshalled to the FX thread.
 */
public class PlayerEventBridge extends MediaPlayerEventAdapter {

    private static final Logger log = LoggerFactory.getLogger(PlayerEventBridge.class);

    private final PlayerController controller;

    public PlayerEventBridge(PlayerController controller) {
        this.controller = controller;
    }

    @Override
    public void opening(MediaPlayer mediaPlayer) {
        log.debug("Event: opening");
        Platform.runLater(() -> {
            controller.updatePlaybackState(PlaybackState.BUFFERING);
            controller.updateStatusText("Opening...");
        });
    }

    @Override
    public void buffering(MediaPlayer mediaPlayer, float newCache) {
        if (newCache < 100f) {
            Platform.runLater(() -> {
                controller.updatePlaybackState(PlaybackState.BUFFERING);
                controller.updateStatusText(String.format("Buffering... %.0f%%", newCache));
            });
        }
    }

    @Override
    public void playing(MediaPlayer mediaPlayer) {
        log.debug("Event: playing");
        Platform.runLater(() -> {
            controller.updatePlaybackState(PlaybackState.PLAYING);
            controller.updateStatusText("Playing");
        });
    }

    @Override
    public void paused(MediaPlayer mediaPlayer) {
        log.debug("Event: paused");
        Platform.runLater(() -> {
            controller.updatePlaybackState(PlaybackState.PAUSED);
            controller.updateStatusText("Paused");
        });
    }

    @Override
    public void stopped(MediaPlayer mediaPlayer) {
        log.debug("Event: stopped");
        Platform.runLater(() -> {
            controller.updatePlaybackState(PlaybackState.STOPPED);
            controller.updateCurrentTime(0);
            controller.updatePosition(0f);
            controller.updateStatusText("Stopped");
        });
    }

    @Override
    public void finished(MediaPlayer mediaPlayer) {
        log.debug("Event: finished");
        Platform.runLater(() -> controller.onMediaFinished());
    }

    @Override
    public void error(MediaPlayer mediaPlayer) {
        log.error("Event: playback error");
        Platform.runLater(() -> {
            controller.updatePlaybackState(PlaybackState.ERROR);
            controller.updateStatusText("Playback error");
        });
    }

    @Override
    public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
        Platform.runLater(() -> controller.updateCurrentTime(newTime));
    }

    @Override
    public void positionChanged(MediaPlayer mediaPlayer, float newPosition) {
        Platform.runLater(() -> controller.updatePosition(newPosition));
    }

    @Override
    public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
        log.debug("Event: length changed to {} ms", newLength);
        Platform.runLater(() -> controller.updateDuration(newLength));
    }

    @Override
    public void volumeChanged(MediaPlayer mediaPlayer, float volume) {
        // Volume is 0.0 to ~2.0 from vlcj, convert to 0-200 int
        Platform.runLater(() -> controller.volumeProperty().set((int) (volume * 100)));
    }

    @Override
    public void muted(MediaPlayer mediaPlayer, boolean muted) {
        Platform.runLater(() -> controller.mutedProperty().set(muted));
    }
}
