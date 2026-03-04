package com.luminaplayer.playlist;

import javafx.beans.property.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Controls playlist navigation: next/previous, shuffle, and repeat.
 */
public class PlaylistController {

    private static final Logger log = LoggerFactory.getLogger(PlaylistController.class);
    private static final Random RANDOM = new Random();

    private final Playlist playlist;
    private final IntegerProperty currentIndex = new SimpleIntegerProperty(-1);
    private final BooleanProperty shuffleEnabled = new SimpleBooleanProperty(false);
    private final ObjectProperty<RepeatMode> repeatMode = new SimpleObjectProperty<>(RepeatMode.NONE);

    private List<Integer> shuffledOrder = new ArrayList<>();

    public PlaylistController(Playlist playlist) {
        this.playlist = playlist;
    }

    public Playlist getPlaylist() {
        return playlist;
    }

    public PlaylistItem playAt(int index) {
        if (index < 0 || index >= playlist.size()) {
            return null;
        }
        updateCurrentIndex(index);
        return playlist.getItem(index);
    }

    public PlaylistItem playNext() {
        if (playlist.isEmpty()) return null;

        RepeatMode mode = repeatMode.get();
        int current = currentIndex.get();

        if (mode == RepeatMode.ONE) {
            return playlist.getItem(current);
        }

        int next;
        if (shuffleEnabled.get()) {
            next = getNextShuffledIndex(current);
        } else {
            next = current + 1;
        }

        if (next >= playlist.size()) {
            if (mode == RepeatMode.ALL) {
                next = 0;
                if (shuffleEnabled.get()) {
                    regenerateShuffleOrder();
                }
            } else {
                return null; // End of playlist
            }
        }

        updateCurrentIndex(next);
        return playlist.getItem(next);
    }

    public PlaylistItem playPrevious() {
        if (playlist.isEmpty()) return null;

        int current = currentIndex.get();
        int prev;

        if (shuffleEnabled.get()) {
            prev = getPreviousShuffledIndex(current);
        } else {
            prev = current - 1;
        }

        if (prev < 0) {
            if (repeatMode.get() == RepeatMode.ALL) {
                prev = playlist.size() - 1;
            } else {
                prev = 0;
            }
        }

        updateCurrentIndex(prev);
        return playlist.getItem(prev);
    }

    public void toggleShuffle() {
        boolean newValue = !shuffleEnabled.get();
        shuffleEnabled.set(newValue);
        if (newValue) {
            regenerateShuffleOrder();
        }
        log.debug("Shuffle toggled: {}", newValue);
    }

    public void cycleRepeatMode() {
        RepeatMode current = repeatMode.get();
        RepeatMode next = switch (current) {
            case NONE -> RepeatMode.ALL;
            case ALL -> RepeatMode.ONE;
            case ONE -> RepeatMode.NONE;
        };
        repeatMode.set(next);
        log.debug("Repeat mode: {}", next);
    }

    private void updateCurrentIndex(int index) {
        // Clear old playing state
        int old = currentIndex.get();
        if (old >= 0 && old < playlist.size()) {
            playlist.getItem(old).setPlaying(false);
        }

        currentIndex.set(index);

        if (index >= 0 && index < playlist.size()) {
            playlist.getItem(index).setPlaying(true);
        }
    }

    private void regenerateShuffleOrder() {
        shuffledOrder = new ArrayList<>();
        for (int i = 0; i < playlist.size(); i++) {
            shuffledOrder.add(i);
        }
        Collections.shuffle(shuffledOrder, RANDOM);
    }

    private int getNextShuffledIndex(int currentActualIndex) {
        if (shuffledOrder.isEmpty()) {
            regenerateShuffleOrder();
        }
        int pos = shuffledOrder.indexOf(currentActualIndex);
        if (pos < 0 || pos + 1 >= shuffledOrder.size()) {
            return playlist.size(); // Signal end
        }
        return shuffledOrder.get(pos + 1);
    }

    private int getPreviousShuffledIndex(int currentActualIndex) {
        if (shuffledOrder.isEmpty()) {
            return 0;
        }
        int pos = shuffledOrder.indexOf(currentActualIndex);
        if (pos <= 0) {
            return -1; // Signal start
        }
        return shuffledOrder.get(pos - 1);
    }

    // Properties
    public IntegerProperty currentIndexProperty() { return currentIndex; }
    public BooleanProperty shuffleEnabledProperty() { return shuffleEnabled; }
    public ObjectProperty<RepeatMode> repeatModeProperty() { return repeatMode; }
}
