package com.luminaplayer.util;

import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

class FileUtilsTest {

    @Test
    void getExtensionNormal() {
        assertEquals("mp4", FileUtils.getExtension(new File("video.mp4")));
    }

    @Test
    void getExtensionUpperCase() {
        assertEquals("mkv", FileUtils.getExtension(new File("video.MKV")));
    }

    @Test
    void getExtensionNoExtension() {
        assertEquals("", FileUtils.getExtension(new File("noextension")));
    }

    @Test
    void getExtensionDotOnly() {
        assertEquals("", FileUtils.getExtension(new File("file.")));
    }

    @Test
    void isVideoFileTrue() {
        assertTrue(FileUtils.isVideoFile(new File("movie.mp4")));
        assertTrue(FileUtils.isVideoFile(new File("movie.MKV")));
        assertTrue(FileUtils.isVideoFile(new File("movie.avi")));
    }

    @Test
    void isVideoFileFalse() {
        assertFalse(FileUtils.isVideoFile(new File("song.mp3")));
        assertFalse(FileUtils.isVideoFile(new File("readme.txt")));
    }

    @Test
    void isAudioFileTrue() {
        assertTrue(FileUtils.isAudioFile(new File("song.mp3")));
        assertTrue(FileUtils.isAudioFile(new File("song.flac")));
    }

    @Test
    void isSubtitleFileTrue() {
        assertTrue(FileUtils.isSubtitleFile(new File("subs.srt")));
        assertTrue(FileUtils.isSubtitleFile(new File("subs.ass")));
    }

    @Test
    void isMediaFile() {
        assertTrue(FileUtils.isMediaFile(new File("video.mp4")));
        assertTrue(FileUtils.isMediaFile(new File("audio.mp3")));
        assertFalse(FileUtils.isMediaFile(new File("readme.txt")));
    }
}
