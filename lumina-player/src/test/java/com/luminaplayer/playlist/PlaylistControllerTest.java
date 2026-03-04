package com.luminaplayer.playlist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistControllerTest {

    @TempDir
    Path tempDir;

    private Playlist playlist;
    private PlaylistController controller;

    @BeforeEach
    void setUp() throws IOException {
        playlist = new Playlist();
        controller = new PlaylistController(playlist);

        // Create dummy media files
        for (int i = 1; i <= 5; i++) {
            File f = tempDir.resolve("video" + i + ".mp4").toFile();
            Files.writeString(f.toPath(), "dummy");
            playlist.add(f);
        }
    }

    @Test
    void playAtValid() {
        PlaylistItem item = controller.playAt(0);
        assertNotNull(item);
        assertEquals("video1.mp4", item.getDisplayName());
        assertEquals(0, controller.currentIndexProperty().get());
    }

    @Test
    void playAtInvalid() {
        assertNull(controller.playAt(-1));
        assertNull(controller.playAt(100));
    }

    @Test
    void playNextSequential() {
        controller.playAt(0);
        PlaylistItem next = controller.playNext();
        assertNotNull(next);
        assertEquals("video2.mp4", next.getDisplayName());
    }

    @Test
    void playNextAtEndReturnsNull() {
        controller.playAt(4);
        assertNull(controller.playNext());
    }

    @Test
    void playNextRepeatAll() {
        controller.repeatModeProperty().set(RepeatMode.ALL);
        controller.playAt(4);
        PlaylistItem next = controller.playNext();
        assertNotNull(next);
        assertEquals("video1.mp4", next.getDisplayName());
    }

    @Test
    void playNextRepeatOne() {
        controller.repeatModeProperty().set(RepeatMode.ONE);
        controller.playAt(2);
        PlaylistItem next = controller.playNext();
        assertNotNull(next);
        assertEquals("video3.mp4", next.getDisplayName());
    }

    @Test
    void playPrevious() {
        controller.playAt(2);
        PlaylistItem prev = controller.playPrevious();
        assertNotNull(prev);
        assertEquals("video2.mp4", prev.getDisplayName());
    }

    @Test
    void cycleRepeatMode() {
        assertEquals(RepeatMode.NONE, controller.repeatModeProperty().get());
        controller.cycleRepeatMode();
        assertEquals(RepeatMode.ALL, controller.repeatModeProperty().get());
        controller.cycleRepeatMode();
        assertEquals(RepeatMode.ONE, controller.repeatModeProperty().get());
        controller.cycleRepeatMode();
        assertEquals(RepeatMode.NONE, controller.repeatModeProperty().get());
    }

    @Test
    void toggleShuffle() {
        assertFalse(controller.shuffleEnabledProperty().get());
        controller.toggleShuffle();
        assertTrue(controller.shuffleEnabledProperty().get());
        controller.toggleShuffle();
        assertFalse(controller.shuffleEnabledProperty().get());
    }

    @Test
    void playingPropertyUpdated() {
        controller.playAt(0);
        assertTrue(playlist.getItem(0).isPlaying());

        controller.playAt(2);
        assertFalse(playlist.getItem(0).isPlaying());
        assertTrue(playlist.getItem(2).isPlaying());
    }
}
