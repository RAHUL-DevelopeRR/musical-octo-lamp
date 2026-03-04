package com.luminaplayer.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AudioExtractor configuration.
 */
class AudioExtractorTest {

    @Test
    void defaultExtractorCreatedWithoutError() {
        AudioExtractor extractor = new AudioExtractor();
        assertNotNull(extractor);
    }

    @Test
    void setCustomFfmpegPath(@TempDir Path tempDir) throws Exception {
        AudioExtractor extractor = new AudioExtractor();

        Path fakeFfmpeg = tempDir.resolve("ffmpeg.exe");
        Files.writeString(fakeFfmpeg, "fake");

        extractor.setFfmpegPath(fakeFfmpeg);
        assertEquals(fakeFfmpeg.toString(), extractor.getToolPath());
    }

    @Test
    void isAvailableReturnsFalseForNonExistentPath() {
        AudioExtractor extractor = new AudioExtractor();
        extractor.setFfmpegPath(Path.of("/nonexistent/ffmpeg"));
        assertFalse(extractor.isAvailable());
    }
}
