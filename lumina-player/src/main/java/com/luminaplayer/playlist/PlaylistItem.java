package com.luminaplayer.playlist;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.io.File;

/**
 * Represents a single item in the playlist.
 */
public class PlaylistItem {

    private final File file;
    private final String displayName;
    private long durationMs;
    private final BooleanProperty playing = new SimpleBooleanProperty(false);

    public PlaylistItem(File file) {
        this.file = file;
        this.displayName = file.getName();
    }

    public File getFile() {
        return file;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public boolean isPlaying() {
        return playing.get();
    }

    public void setPlaying(boolean playing) {
        this.playing.set(playing);
    }

    public BooleanProperty playingProperty() {
        return playing;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
