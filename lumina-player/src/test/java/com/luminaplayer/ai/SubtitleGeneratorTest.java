package com.luminaplayer.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SubtitleGenerator availability checking.
 */
class SubtitleGeneratorTest {

    @Test
    void checkAvailabilityReturnsNonNull() {
        SubtitleGenerator generator = new SubtitleGenerator();
        SubtitleGenerator.AvailabilityStatus status = generator.checkAvailability();
        assertNotNull(status);
    }

    @Test
    void availabilityStatusSummaryContainsToolNames() {
        SubtitleGenerator generator = new SubtitleGenerator();
        SubtitleGenerator.AvailabilityStatus status = generator.checkAvailability();
        String summary = status.getSummary();
        assertTrue(summary.contains("FFmpeg"));
        assertTrue(summary.contains("Whisper"));
    }

    @Test
    void isReadyRequiresBothTools() {
        SubtitleGenerator generator = new SubtitleGenerator();
        SubtitleGenerator.AvailabilityStatus status = generator.checkAvailability();
        // isReady should be true only if both tools are available
        assertEquals(status.ffmpegAvailable() && status.whisperAvailable(), status.isReady());
    }

    @Test
    void getSubComponentsNotNull() {
        SubtitleGenerator generator = new SubtitleGenerator();
        assertNotNull(generator.getAudioExtractor());
        assertNotNull(generator.getWhisperEngine());
    }
}
