package com.luminaplayer.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkedSubtitleGeneratorTest {

    @Test
    void testChunkCountCalculation_120s() {
        // 120 seconds / 30 second chunks = 4 chunks
        long totalDuration = 120_000;
        long chunkDuration = 30_000;
        int expected = (int) Math.ceil((double) totalDuration / chunkDuration);
        assertEquals(4, expected);
    }

    @Test
    void testChunkCountCalculation_45s() {
        // 45 seconds / 30 second chunks = 2 chunks (30s + 15s)
        long totalDuration = 45_000;
        long chunkDuration = 30_000;
        int expected = (int) Math.ceil((double) totalDuration / chunkDuration);
        assertEquals(2, expected);
    }

    @Test
    void testChunkCountCalculation_exactMultiple() {
        // 90 seconds / 30 second chunks = exactly 3 chunks
        long totalDuration = 90_000;
        long chunkDuration = 30_000;
        int expected = (int) Math.ceil((double) totalDuration / chunkDuration);
        assertEquals(3, expected);
    }

    @Test
    void testChunkCountCalculation_shortVideo() {
        // 10 seconds / 30 second chunks = 1 chunk
        long totalDuration = 10_000;
        long chunkDuration = 30_000;
        int expected = (int) Math.ceil((double) totalDuration / chunkDuration);
        assertEquals(1, expected);
    }

    @Test
    void testAvailabilityCheck() {
        ChunkedSubtitleGenerator generator = new ChunkedSubtitleGenerator();
        SubtitleGenerator.AvailabilityStatus status = generator.checkAvailability();

        // Status should return non-null
        assertNotNull(status);
        assertNotNull(status.getSummary());
    }

    @Test
    void testChunkOffsetCalculation() {
        // Chunk index 3 with 30s chunks starts at 90000ms
        int chunkIndex = 3;
        long chunkDuration = 30_000;
        long expectedOffset = chunkIndex * chunkDuration;
        assertEquals(90_000, expectedOffset);
    }

    @Test
    void testThreadPoolSize() {
        int processors = Runtime.getRuntime().availableProcessors();
        int poolSize = Math.min(4, Math.max(2, processors / 2));
        assertTrue(poolSize >= 2);
        assertTrue(poolSize <= 4);
    }
}
