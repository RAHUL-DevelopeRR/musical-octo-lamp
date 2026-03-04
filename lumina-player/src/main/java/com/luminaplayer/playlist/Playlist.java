package com.luminaplayer.playlist;

import com.luminaplayer.util.FileUtils;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;
import java.util.List;

/**
 * Ordered list of media items for playback.
 */
public class Playlist {

    private final ObservableList<PlaylistItem> items = FXCollections.observableArrayList();

    public ObservableList<PlaylistItem> getItems() {
        return items;
    }

    public void add(File file) {
        if (file != null && file.exists() && FileUtils.isMediaFile(file)) {
            items.add(new PlaylistItem(file));
        }
    }

    public void addAll(List<File> files) {
        for (File f : files) {
            add(f);
        }
    }

    public void remove(int index) {
        if (index >= 0 && index < items.size()) {
            items.remove(index);
        }
    }

    public void clear() {
        items.clear();
    }

    public void moveItem(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= items.size() || toIndex < 0 || toIndex >= items.size()) {
            return;
        }
        PlaylistItem item = items.remove(fromIndex);
        items.add(toIndex, item);
    }

    public PlaylistItem getItem(int index) {
        if (index >= 0 && index < items.size()) {
            return items.get(index);
        }
        return null;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
