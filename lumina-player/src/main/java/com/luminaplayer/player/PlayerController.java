package com.luminaplayer.player;

import com.luminaplayer.app.AppConfig;
import com.luminaplayer.engine.VlcEngine;
import com.luminaplayer.playlist.PlaylistController;
import com.luminaplayer.playlist.PlaylistItem;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.image.ImageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;

import java.io.File;
import java.util.List;

/**
 * Central mediator between the UI and vlcj engine.
 * Exposes JavaFX observable properties that UI components bind to.
 */
public class PlayerController {

    private static final Logger log = LoggerFactory.getLogger(PlayerController.class);

    private final VlcEngine engine;
    private PlaylistController playlistController;

    // Observable state
    private final ObjectProperty<PlaybackState> playbackState = new SimpleObjectProperty<>(PlaybackState.IDLE);
    private final LongProperty currentTime = new SimpleLongProperty(0);
    private final LongProperty totalDuration = new SimpleLongProperty(0);
    private final FloatProperty position = new SimpleFloatProperty(0f);
    private final IntegerProperty volume = new SimpleIntegerProperty(AppConfig.DEFAULT_VOLUME);
    private final BooleanProperty muted = new SimpleBooleanProperty(false);
    private final FloatProperty playbackRate = new SimpleFloatProperty(1.0f);
    private final BooleanProperty looping = new SimpleBooleanProperty(false);
    private final ObjectProperty<MediaInfo> currentMedia = new SimpleObjectProperty<>(null);
    private final StringProperty statusText = new SimpleStringProperty("");

    // Throttle for time updates
    private long lastTimeUpdate = 0;
    private static final long TIME_UPDATE_INTERVAL_MS = 50;

    public PlayerController(VlcEngine engine) {
        this.engine = engine;

        // Register event bridge
        PlayerEventBridge bridge = new PlayerEventBridge(this);
        engine.mediaPlayer().events().addMediaPlayerEventListener(bridge);

        // Set initial volume
        Platform.runLater(() -> engine.audio().setVolume(AppConfig.DEFAULT_VOLUME));
    }

    public void setPlaylistController(PlaylistController playlistController) {
        this.playlistController = playlistController;
    }

    // --- Video Surface ---

    public void attachVideoSurface(ImageView imageView) {
        engine.mediaPlayer().videoSurface().set(new ImageViewVideoSurface(imageView));
        log.info("Video surface attached to ImageView");
    }

    // --- Playback Controls ---

    public void openFile(File file) {
        if (file == null || !file.exists()) {
            log.warn("Cannot open file: {}", file);
            return;
        }

        log.info("Opening file: {}", file.getAbsolutePath());
        MediaInfo info = new MediaInfo();
        info.setFilePath(file.getAbsolutePath());
        info.setTitle(file.getName());
        info.setFileSize(file.length());

        Platform.runLater(() -> {
            currentMedia.set(info);
            statusText.set("Opening: " + file.getName());
        });

        engine.mediaPlayer().media().play(file.getAbsolutePath());
    }

    public void openUrl(String url) {
        if (url == null || url.isBlank()) {
            log.warn("Cannot open URL: empty or null");
            return;
        }

        log.info("Opening URL: {}", url);
        MediaInfo info = new MediaInfo();
        info.setFilePath(url);
        info.setTitle(url);
        info.setFileSize(0);

        Platform.runLater(() -> {
            currentMedia.set(info);
            statusText.set("Opening stream: " + url);
        });

        engine.mediaPlayer().media().play(url);
    }

    public void openFiles(List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (playlistController != null) {
            playlistController.getPlaylist().clear();
            for (File f : files) {
                playlistController.getPlaylist().add(f);
            }
            PlaylistItem first = playlistController.playAt(0);
            if (first != null) {
                openFile(first.getFile());
            }
        } else {
            openFile(files.get(0));
        }
    }

    public void play() {
        engine.mediaPlayer().controls().play();
    }

    public void pause() {
        engine.mediaPlayer().controls().pause();
    }

    public void togglePlayPause() {
        PlaybackState state = playbackState.get();
        if (state == PlaybackState.PLAYING) {
            pause();
        } else if (state == PlaybackState.PAUSED || state == PlaybackState.STOPPED
                   || state == PlaybackState.IDLE || state == PlaybackState.BUFFERING) {
            play();
        }
    }

    public void stop() {
        engine.mediaPlayer().controls().stop();
    }

    public void seek(float pos) {
        float clamped = Math.max(0f, Math.min(1f, pos));
        engine.mediaPlayer().controls().setPosition(clamped);
    }

    public void seekToTime(long millis) {
        engine.mediaPlayer().controls().setTime(millis);
    }

    public void skipForward(long millis) {
        engine.mediaPlayer().controls().skipTime(millis);
    }

    public void skipBackward(long millis) {
        engine.mediaPlayer().controls().skipTime(-millis);
    }

    public void nextFrame() {
        engine.mediaPlayer().controls().nextFrame();
    }

    public void setRate(float rate) {
        engine.mediaPlayer().controls().setRate(rate);
        Platform.runLater(() -> playbackRate.set(rate));
    }

    public void toggleLoop() {
        boolean newValue = !looping.get();
        looping.set(newValue);
        engine.mediaPlayer().controls().setRepeat(newValue);
        log.debug("Loop toggled: {}", newValue);
    }

    // --- Volume ---

    public void setVolume(int percent) {
        int clamped = Math.max(0, Math.min(200, percent));
        engine.audio().setVolume(clamped);
        Platform.runLater(() -> volume.set(clamped));
    }

    public void toggleMute() {
        engine.audio().toggleMute();
        Platform.runLater(() -> muted.set(engine.audio().isMuted()));
    }

    // --- Playlist Navigation ---

    public void playNext() {
        if (playlistController == null) return;
        PlaylistItem next = playlistController.playNext();
        if (next != null) {
            openFile(next.getFile());
        }
    }

    public void playPrevious() {
        if (playlistController == null) return;
        PlaylistItem prev = playlistController.playPrevious();
        if (prev != null) {
            openFile(prev.getFile());
        }
    }

    /**
     * Called by PlayerEventBridge when media finishes playing.
     */
    public void onMediaFinished() {
        log.debug("Media finished");
        if (looping.get()) {
            // vlcj repeat handles this, but as a fallback:
            play();
            return;
        }
        if (playlistController != null) {
            PlaylistItem next = playlistController.playNext();
            if (next != null) {
                openFile(next.getFile());
            } else {
                playbackState.set(PlaybackState.STOPPED);
                statusText.set("Playback finished");
            }
        } else {
            playbackState.set(PlaybackState.STOPPED);
            statusText.set("Playback finished");
        }
    }

    // --- Event Bridge Callbacks (called from PlayerEventBridge via Platform.runLater) ---

    void updatePlaybackState(PlaybackState state) {
        playbackState.set(state);
    }

    void updateCurrentTime(long timeMs) {
        long now = System.currentTimeMillis();
        if (now - lastTimeUpdate >= TIME_UPDATE_INTERVAL_MS) {
            lastTimeUpdate = now;
            currentTime.set(timeMs);
        }
    }

    void updatePosition(float pos) {
        position.set(pos);
    }

    void updateDuration(long durationMs) {
        totalDuration.set(durationMs);
    }

    void updateStatusText(String text) {
        statusText.set(text);
    }

    // --- Engine Access (for subtitle/video dialogs) ---

    public VlcEngine getEngine() {
        return engine;
    }

    // --- Properties ---

    public ObjectProperty<PlaybackState> playbackStateProperty() { return playbackState; }
    public LongProperty currentTimeProperty() { return currentTime; }
    public LongProperty totalDurationProperty() { return totalDuration; }
    public FloatProperty positionProperty() { return position; }
    public IntegerProperty volumeProperty() { return volume; }
    public BooleanProperty mutedProperty() { return muted; }
    public FloatProperty playbackRateProperty() { return playbackRate; }
    public BooleanProperty loopingProperty() { return looping; }
    public ObjectProperty<MediaInfo> currentMediaProperty() { return currentMedia; }
    public StringProperty statusTextProperty() { return statusText; }
}
