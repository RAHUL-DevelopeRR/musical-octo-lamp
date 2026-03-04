package com.luminaplayer.subtitle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DsrtFileTest {

    @Test
    void testCreate_chunksCalculated() {
        File dummy = new File("test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 120000, 30000, "base", "auto", false);

        assertEquals(4, dsrt.getTotalChunkCount());
        assertEquals(0, dsrt.getCompletedChunkCount());
        assertEquals(0, dsrt.getCueCount());
    }

    @Test
    void testCreate_singleChunkForShortVideo() {
        File dummy = new File("test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 15000, 30000, "base", "auto", false);

        assertEquals(1, dsrt.getTotalChunkCount());
    }

    @Test
    void testCreate_exactMultiple() {
        File dummy = new File("test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 90000, 30000, "base", "auto", false);

        assertEquals(3, dsrt.getTotalChunkCount());
    }

    @Test
    void testAddCues_offsetApplied() {
        File dummy = new File("test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 60000, 30000, "base", "auto", false);

        List<SubtitleEntry> entries = List.of(
            new SubtitleEntry(1, 1000, 3000, "Hello"),
            new SubtitleEntry(2, 5000, 8000, "World")
        );

        // Add to chunk 1 (starts at 30000ms)
        dsrt.addCues(1, entries, 30000);

        assertEquals(2, dsrt.getCueCount());

        // Verify offset was applied
        DsrtCue cue1 = dsrt.getActiveCue(31000); // 1000 + 30000
        assertNotNull(cue1);
        assertEquals("Hello", cue1.text());
        assertEquals(31000, cue1.startTimeMs());

        DsrtCue cue2 = dsrt.getActiveCue(35000); // 5000 + 30000
        assertNotNull(cue2);
        assertEquals("World", cue2.text());
    }

    @Test
    void testGetActiveCue_returnsCorrectCue() {
        File dummy = new File("test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 60000, 30000, "base", "auto", false);

        List<SubtitleEntry> entries = List.of(
            new SubtitleEntry(1, 1000, 3000, "First"),
            new SubtitleEntry(2, 5000, 8000, "Second")
        );
        dsrt.addCues(0, entries, 0);

        // During first cue
        DsrtCue cue = dsrt.getActiveCue(2000);
        assertNotNull(cue);
        assertEquals("First", cue.text());

        // Between cues (no active cue)
        DsrtCue gap = dsrt.getActiveCue(4000);
        assertNull(gap);

        // During second cue
        DsrtCue cue2 = dsrt.getActiveCue(6000);
        assertNotNull(cue2);
        assertEquals("Second", cue2.text());

        // After all cues
        DsrtCue after = dsrt.getActiveCue(10000);
        assertNull(after);

        // Before any cue
        DsrtCue before = dsrt.getActiveCue(500);
        assertNull(before);
    }

    @Test
    void testRemoveCuesForChunk() {
        File dummy = new File("test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 60000, 30000, "base", "auto", false);

        dsrt.addCues(0, List.of(new SubtitleEntry(1, 1000, 3000, "Chunk 0")), 0);
        dsrt.addCues(1, List.of(new SubtitleEntry(1, 1000, 3000, "Chunk 1")), 30000);

        assertEquals(2, dsrt.getCueCount());

        dsrt.removeCuesForChunk(0);
        assertEquals(1, dsrt.getCueCount());
        assertNotNull(dsrt.getActiveCue(31000));
        assertNull(dsrt.getActiveCue(1500));
    }

    @Test
    void testJsonRoundTrip(@TempDir Path tempDir) throws IOException {
        File dummy = new File("C:/Videos/test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 90000, 30000, "base", "en", true);

        dsrt.addCues(0, List.of(
            new SubtitleEntry(1, 1000, 3000, "Hello"),
            new SubtitleEntry(2, 5000, 8000, "World")
        ), 0);
        dsrt.getChunk(0).setStatus(ChunkStatus.COMPLETED);

        dsrt.addCues(1, List.of(
            new SubtitleEntry(1, 2000, 4000, "Line with\nnewline")
        ), 30000);
        dsrt.getChunk(1).setStatus(ChunkStatus.COMPLETED);

        // Save
        File dsrtFile = tempDir.resolve("test.dsrt").toFile();
        dsrt.saveTo(dsrtFile);

        assertTrue(dsrtFile.exists());
        assertTrue(dsrtFile.length() > 0);

        // Load
        DsrtFile loaded = DsrtFile.loadFrom(dsrtFile);

        assertEquals(3, loaded.getTotalChunkCount());
        assertEquals(2, loaded.getCompletedChunkCount());
        assertEquals(3, loaded.getCueCount());
        assertEquals("base", loaded.getModelName());
        assertEquals("en", loaded.getLanguageCode());
        assertTrue(loaded.isTranslate());

        // Verify cues
        DsrtCue cue = loaded.getActiveCue(1500);
        assertNotNull(cue);
        assertEquals("Hello", cue.text());

        // Verify multiline text survived JSON round-trip
        DsrtCue multiline = loaded.getActiveCue(32000);
        assertNotNull(multiline);
        assertTrue(multiline.text().contains("\n"));
    }

    @Test
    void testExportToSrt(@TempDir Path tempDir) throws IOException {
        File dummy = new File("test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 60000, 30000, "base", "auto", false);

        dsrt.addCues(0, List.of(
            new SubtitleEntry(1, 1200, 4500, "Hello world."),
            new SubtitleEntry(2, 5000, 8200, "Second line.")
        ), 0);

        File srtFile = tempDir.resolve("test.srt").toFile();
        dsrt.exportToSrt(srtFile);

        assertTrue(srtFile.exists());
        String content = Files.readString(srtFile.toPath());

        assertTrue(content.contains("Hello world."));
        assertTrue(content.contains("Second line."));
        assertTrue(content.contains("00:00:01,200 --> 00:00:04,500"));
        assertTrue(content.contains("00:00:05,000 --> 00:00:08,200"));
    }

    @Test
    void testGetPendingOrFailedChunks() {
        File dummy = new File("test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 120000, 30000, "base", "auto", false);

        dsrt.getChunk(0).setStatus(ChunkStatus.COMPLETED);
        dsrt.getChunk(1).setStatus(ChunkStatus.FAILED);
        dsrt.getChunk(1).setErrorMessage("Whisper failed");
        // Chunks 2 and 3 remain PENDING

        List<DsrtChunk> pending = dsrt.getPendingOrFailedChunks();
        assertEquals(3, pending.size()); // chunks 1, 2, 3
    }

    @Test
    void testChunkMetadata() {
        File dummy = new File("test.mp4");
        DsrtFile dsrt = DsrtFile.create(dummy, 100000, 30000, "base", "auto", false);

        assertEquals(4, dsrt.getTotalChunkCount()); // 30 + 30 + 30 + 10

        DsrtChunk lastChunk = dsrt.getChunk(3);
        assertEquals(90000, lastChunk.getStartMs());
        assertEquals(100000, lastChunk.getEndMs());
        assertEquals(10000, lastChunk.getDurationMs());
    }
}
