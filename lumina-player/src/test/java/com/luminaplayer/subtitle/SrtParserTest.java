package com.luminaplayer.subtitle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SrtParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parseValidSrt() throws IOException {
        String content = """
                1
                00:00:01,000 --> 00:00:04,000
                Hello, world!

                2
                00:00:05,000 --> 00:00:08,500
                This is a test
                subtitle file.

                3
                00:01:00,000 --> 00:01:05,000
                Third entry.
                """;

        File srtFile = tempDir.resolve("test.srt").toFile();
        Files.writeString(srtFile.toPath(), content);

        SrtParser parser = new SrtParser();
        List<SubtitleEntry> entries = parser.parse(srtFile);

        assertEquals(3, entries.size());

        assertEquals(1, entries.get(0).getIndex());
        assertEquals(1000, entries.get(0).getStartTimeMs());
        assertEquals(4000, entries.get(0).getEndTimeMs());
        assertEquals("Hello, world!", entries.get(0).getText());

        assertEquals(2, entries.get(1).getIndex());
        assertEquals(5000, entries.get(1).getStartTimeMs());
        assertEquals(8500, entries.get(1).getEndTimeMs());
        assertEquals("This is a test\nsubtitle file.", entries.get(1).getText());

        assertEquals(3, entries.get(2).getIndex());
        assertEquals(60000, entries.get(2).getStartTimeMs());
        assertEquals(65000, entries.get(2).getEndTimeMs());
    }

    @Test
    void parseEmptyFile() throws IOException {
        File srtFile = tempDir.resolve("empty.srt").toFile();
        Files.writeString(srtFile.toPath(), "");

        SrtParser parser = new SrtParser();
        List<SubtitleEntry> entries = parser.parse(srtFile);

        assertTrue(entries.isEmpty());
    }

    @Test
    void parseTimestampMath() throws IOException {
        String content = """
                1
                01:30:45,123 --> 02:00:00,000
                Timestamp test
                """;

        File srtFile = tempDir.resolve("time.srt").toFile();
        Files.writeString(srtFile.toPath(), content);

        SrtParser parser = new SrtParser();
        List<SubtitleEntry> entries = parser.parse(srtFile);

        assertEquals(1, entries.size());
        // 1*3600000 + 30*60000 + 45*1000 + 123 = 5445123
        assertEquals(5445123, entries.get(0).getStartTimeMs());
        // 2*3600000 = 7200000
        assertEquals(7200000, entries.get(0).getEndTimeMs());
    }
}
