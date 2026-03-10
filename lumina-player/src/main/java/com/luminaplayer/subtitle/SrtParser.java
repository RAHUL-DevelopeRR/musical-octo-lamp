package com.luminaplayer.subtitle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and writes standard {@code .srt} subtitle files.
 *
 * <p>Handles the three-line SRT block format:
 * <pre>
 * 1
 * 00:00:01,200 --> 00:00:04,800
 * Subtitle text here
 * </pre>
 */
public class SrtParser {

    private static final Logger log = LoggerFactory.getLogger(SrtParser.class);

    // ── Parse ─────────────────────────────────────────────────────────────────

    /**
     * Parses an SRT file into a list of {@link SubtitleEntry} objects.
     */
    public List<SubtitleEntry> parse(File srtFile) {
        List<SubtitleEntry> entries = new ArrayList<>();
        if (srtFile == null || !srtFile.exists()) return entries;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(srtFile), StandardCharsets.UTF_8))) {

            String line;
            int    index    = -1;
            long   startMs  = 0;
            long   endMs    = 0;
            StringBuilder textBuf = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    // Block separator — flush pending entry
                    if (index > 0 && textBuf.length() > 0) {
                        entries.add(new SubtitleEntry(index, startMs, endMs, textBuf.toString().trim()));
                        textBuf.setLength(0);
                        index = -1;
                    }
                    continue;
                }

                if (index < 0) {
                    // Expect sequence number
                    try {
                        index = Integer.parseInt(line);
                    } catch (NumberFormatException e) {
                        log.debug("SRT: skipping non-numeric line: {}", line);
                    }
                    continue;
                }

                if (line.contains("-->")) {
                    // Timestamp line
                    String[] parts = line.split("-->");
                    if (parts.length == 2) {
                        startMs = parseTimestamp(parts[0].trim());
                        endMs   = parseTimestamp(parts[1].trim());
                    }
                    continue;
                }

                // Text line
                if (textBuf.length() > 0) textBuf.append('\n');
                textBuf.append(line);
            }

            // Flush final entry (file may not end with blank line)
            if (index > 0 && textBuf.length() > 0) {
                entries.add(new SubtitleEntry(index, startMs, endMs, textBuf.toString().trim()));
            }

        } catch (IOException e) {
            log.error("Failed to parse SRT file: {}", srtFile.getAbsolutePath(), e);
        }

        log.debug("Parsed {} entries from {}", entries.size(), srtFile.getName());
        return entries;
    }

    // ── Write (new static helper used by WhisperEngine sidecar mode) ──────────

    /**
     * Writes a list of {@link SubtitleEntry} objects to an SRT file.
     * Used by the Faster-Whisper sidecar path to materialise the SRT on disk
     * so the rest of the pipeline can load it normally.
     */
    public static void writeToFile(List<SubtitleEntry> entries, File outputFile)
            throws IOException {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {
            for (SubtitleEntry e : entries) {
                pw.println(e.getIndex());
                pw.println(formatTimestamp(e.getStartTimeMs()) + " --> " + formatTimestamp(e.getEndTimeMs()));
                pw.println(e.getText());
                pw.println();
            }
        }
    }

    // ── Timestamp helpers ─────────────────────────────────────────────────────

    /** Parses {@code HH:MM:SS,mmm} or {@code HH:MM:SS.mmm} into milliseconds. */
    public static long parseTimestamp(String ts) {
        try {
            ts = ts.replace(',', '.').trim();
            String[] parts = ts.split(":");
            if (parts.length != 3) return 0;
            int    h   = Integer.parseInt(parts[0]);
            int    m   = Integer.parseInt(parts[1]);
            double s   = Double.parseDouble(parts[2]);
            return (long) ((h * 3600L + m * 60L + s) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Formats milliseconds as {@code HH:MM:SS,mmm}. */
    public static String formatTimestamp(long ms) {
        long totalSec = ms / 1000;
        long millis   = ms % 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, millis);
    }
}
