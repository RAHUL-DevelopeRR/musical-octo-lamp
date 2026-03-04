package com.luminaplayer.subtitle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory representation of a .dsrt (Dynamic SRT) file.
 * Provides O(log n) cue lookup by playback time via ConcurrentSkipListMap,
 * and supports concurrent cue insertion from multiple chunk worker threads.
 *
 * The .dsrt format is a JSON file that tracks chunk generation status
 * and subtitle cues, enabling resume and real-time progressive display.
 */
public class DsrtFile {

    private static final Logger log = LoggerFactory.getLogger(DsrtFile.class);

    private int version = 1;
    private String mediaFilePath;
    private long chunkDurationMs;
    private long totalDurationMs;
    private String modelName;
    private String languageCode;
    private boolean translate;
    private String createdAt;
    private String lastModified;

    private final CopyOnWriteArrayList<DsrtChunk> chunks;
    private final ConcurrentSkipListMap<Long, DsrtCue> cueMap;
    private final AtomicInteger nextCueId;

    private DsrtFile() {
        this.chunks = new CopyOnWriteArrayList<>();
        this.cueMap = new ConcurrentSkipListMap<>();
        this.nextCueId = new AtomicInteger(1);
    }

    /**
     * Creates a new DsrtFile for a media file, initializing all chunks as PENDING.
     */
    public static DsrtFile create(File mediaFile, long totalDurationMs,
                                   long chunkDurationMs, String modelName,
                                   String languageCode, boolean translate) {
        DsrtFile dsrt = new DsrtFile();
        dsrt.mediaFilePath = mediaFile.getAbsolutePath();
        dsrt.totalDurationMs = totalDurationMs;
        dsrt.chunkDurationMs = chunkDurationMs;
        dsrt.modelName = modelName;
        dsrt.languageCode = languageCode;
        dsrt.translate = translate;
        dsrt.createdAt = Instant.now().toString();
        dsrt.lastModified = dsrt.createdAt;
        dsrt.buildChunks();
        return dsrt;
    }

    private void buildChunks() {
        int numChunks = (int) Math.ceil((double) totalDurationMs / chunkDurationMs);
        if (numChunks == 0) numChunks = 1;
        for (int i = 0; i < numChunks; i++) {
            long start = i * chunkDurationMs;
            long end = Math.min(start + chunkDurationMs, totalDurationMs);
            chunks.add(new DsrtChunk(i, start, end));
        }
        log.info("Created {} chunks for {}ms duration ({}ms per chunk)",
            numChunks, totalDurationMs, chunkDurationMs);
    }

    // --- Cue Management ---

    /**
     * Adds subtitle entries from a completed chunk, offsetting timestamps.
     * Thread-safe: can be called concurrently from multiple chunk workers.
     *
     * @param chunkIndex the chunk these entries belong to
     * @param entries    parsed subtitle entries (timestamps relative to chunk start)
     * @param offsetMs   time offset to add to all timestamps (= chunk start time)
     */
    public void addCues(int chunkIndex, List<SubtitleEntry> entries, long offsetMs) {
        for (SubtitleEntry entry : entries) {
            int id = nextCueId.getAndIncrement();
            long start = entry.getStartTimeMs() + offsetMs;
            long end = entry.getEndTimeMs() + offsetMs;
            DsrtCue cue = new DsrtCue(id, start, end, entry.getText(), chunkIndex);
            cueMap.put(start, cue);
        }
        lastModified = Instant.now().toString();
        log.debug("Added {} cues for chunk {} (offset {}ms)", entries.size(), chunkIndex, offsetMs);
    }

    /**
     * Removes all cues belonging to a specific chunk (for retry).
     */
    public void removeCuesForChunk(int chunkIndex) {
        cueMap.values().removeIf(cue -> cue.chunkIndex() == chunkIndex);
    }

    /**
     * Returns the active cue at the given playback time, or null.
     * O(log n) lookup via ConcurrentSkipListMap.floorEntry().
     */
    public DsrtCue getActiveCue(long currentTimeMs) {
        Map.Entry<Long, DsrtCue> entry = cueMap.floorEntry(currentTimeMs);
        if (entry != null && entry.getValue().endTimeMs() > currentTimeMs) {
            return entry.getValue();
        }
        return null;
    }

    public List<DsrtCue> getAllCues() {
        return new ArrayList<>(cueMap.values());
    }

    public int getCueCount() {
        return cueMap.size();
    }

    // --- Chunk Management ---

    public DsrtChunk getChunk(int index) {
        if (index >= 0 && index < chunks.size()) {
            return chunks.get(index);
        }
        return null;
    }

    public List<DsrtChunk> getChunks() {
        return Collections.unmodifiableList(chunks);
    }

    public int getTotalChunkCount() {
        return chunks.size();
    }

    public int getCompletedChunkCount() {
        return (int) chunks.stream().filter(c -> c.getStatus() == ChunkStatus.COMPLETED).count();
    }

    public List<DsrtChunk> getPendingOrFailedChunks() {
        return chunks.stream()
            .filter(c -> c.getStatus() == ChunkStatus.PENDING || c.getStatus() == ChunkStatus.FAILED)
            .collect(Collectors.toList());
    }

    // --- Metadata ---

    public String getMediaFilePath() { return mediaFilePath; }
    public long getChunkDurationMs() { return chunkDurationMs; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public String getModelName() { return modelName; }
    public String getLanguageCode() { return languageCode; }
    public boolean isTranslate() { return translate; }

    // --- JSON Persistence ---

    /**
     * Saves the .dsrt file to disk as JSON (atomic write via temp file + rename).
     */
    public synchronized void saveTo(File file) throws IOException {
        String json = toJson();
        Path tempFile = file.toPath().resolveSibling(file.getName() + ".tmp");
        Files.writeString(tempFile, json, StandardCharsets.UTF_8);
        Files.move(tempFile, file.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        log.debug("Saved .dsrt file: {} ({} cues, {}/{} chunks)",
            file.getName(), cueMap.size(), getCompletedChunkCount(), chunks.size());
    }

    /**
     * Loads a .dsrt file from disk.
     */
    public static DsrtFile loadFrom(File file) throws IOException {
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return fromJson(json);
    }

    /**
     * Exports all cues to a standard .srt file.
     */
    public void exportToSrt(File srtFile) throws IOException {
        List<DsrtCue> sortedCues = getAllCues();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(srtFile), StandardCharsets.UTF_8))) {
            int index = 1;
            for (DsrtCue cue : sortedCues) {
                writer.write(String.valueOf(index++));
                writer.newLine();
                writer.write(formatSrtTime(cue.startTimeMs()) + " --> " + formatSrtTime(cue.endTimeMs()));
                writer.newLine();
                writer.write(cue.text());
                writer.newLine();
                writer.newLine();
            }
        }
        log.info("Exported {} cues to SRT: {}", sortedCues.size(), srtFile.getName());
    }

    private static String formatSrtTime(long ms) {
        long hours = ms / 3600000;
        long minutes = (ms % 3600000) / 60000;
        long seconds = (ms % 60000) / 1000;
        long millis = ms % 1000;
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    // --- JSON Serialization (manual, no external dependency) ---

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(version).append(",\n");
        sb.append("  \"mediaFile\": ").append(jsonString(mediaFilePath)).append(",\n");
        sb.append("  \"chunkDurationMs\": ").append(chunkDurationMs).append(",\n");
        sb.append("  \"totalDurationMs\": ").append(totalDurationMs).append(",\n");
        sb.append("  \"model\": ").append(jsonString(modelName)).append(",\n");
        sb.append("  \"language\": ").append(jsonString(languageCode)).append(",\n");
        sb.append("  \"translate\": ").append(translate).append(",\n");
        sb.append("  \"createdAt\": ").append(jsonString(createdAt)).append(",\n");
        sb.append("  \"lastModified\": ").append(jsonString(lastModified)).append(",\n");
        sb.append("  \"chunks\": [\n");
        for (int i = 0; i < chunks.size(); i++) {
            DsrtChunk chunk = chunks.get(i);
            sb.append("    { \"index\": ").append(chunk.getIndex());
            sb.append(", \"startMs\": ").append(chunk.getStartMs());
            sb.append(", \"endMs\": ").append(chunk.getEndMs());
            sb.append(", \"status\": ").append(jsonString(chunk.getStatus().name()));
            sb.append(", \"error\": ").append(chunk.getErrorMessage() != null
                ? jsonString(chunk.getErrorMessage()) : "null");
            sb.append(" }");
            if (i < chunks.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"cues\": [\n");
        List<DsrtCue> allCues = getAllCues();
        for (int i = 0; i < allCues.size(); i++) {
            DsrtCue cue = allCues.get(i);
            sb.append("    { \"id\": ").append(cue.id());
            sb.append(", \"startMs\": ").append(cue.startTimeMs());
            sb.append(", \"endMs\": ").append(cue.endTimeMs());
            sb.append(", \"text\": ").append(jsonString(cue.text()));
            sb.append(", \"chunkIndex\": ").append(cue.chunkIndex());
            sb.append(" }");
            if (i < allCues.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            + "\"";
    }

    /**
     * Parses a .dsrt JSON string back into a DsrtFile.
     * This is a simple JSON parser for the known schema.
     */
    static DsrtFile fromJson(String json) {
        DsrtFile dsrt = new DsrtFile();
        dsrt.version = getIntValue(json, "version", 1);
        dsrt.mediaFilePath = getStringValue(json, "mediaFile");
        dsrt.chunkDurationMs = getLongValue(json, "chunkDurationMs", 30000);
        dsrt.totalDurationMs = getLongValue(json, "totalDurationMs", 0);
        dsrt.modelName = getStringValue(json, "model");
        dsrt.languageCode = getStringValue(json, "language");
        dsrt.translate = getBooleanValue(json, "translate", false);
        dsrt.createdAt = getStringValue(json, "createdAt");
        dsrt.lastModified = getStringValue(json, "lastModified");

        // Parse chunks array
        String chunksArray = extractArray(json, "chunks");
        if (chunksArray != null) {
            for (String obj : splitJsonObjects(chunksArray)) {
                int index = getIntValue(obj, "index", 0);
                long startMs = getLongValue(obj, "startMs", 0);
                long endMs = getLongValue(obj, "endMs", 0);
                String statusStr = getStringValue(obj, "status");
                String error = getStringValue(obj, "error");

                DsrtChunk chunk = new DsrtChunk(index, startMs, endMs);
                if (statusStr != null) {
                    try {
                        chunk.setStatus(ChunkStatus.valueOf(statusStr));
                    } catch (IllegalArgumentException e) {
                        chunk.setStatus(ChunkStatus.PENDING);
                    }
                }
                chunk.setErrorMessage(error);
                dsrt.chunks.add(chunk);
            }
        }

        // Parse cues array
        String cuesArray = extractArray(json, "cues");
        if (cuesArray != null) {
            int maxId = 0;
            for (String obj : splitJsonObjects(cuesArray)) {
                int id = getIntValue(obj, "id", 0);
                long startMs = getLongValue(obj, "startMs", 0);
                long endMs = getLongValue(obj, "endMs", 0);
                String text = getStringValue(obj, "text");
                int chunkIndex = getIntValue(obj, "chunkIndex", 0);

                DsrtCue cue = new DsrtCue(id, startMs, endMs, text != null ? text : "", chunkIndex);
                dsrt.cueMap.put(startMs, cue);
                if (id > maxId) maxId = id;
            }
            dsrt.nextCueId.set(maxId + 1);
        }

        log.info("Loaded .dsrt: {} chunks, {} cues", dsrt.chunks.size(), dsrt.cueMap.size());
        return dsrt;
    }

    // --- Simple JSON extraction helpers ---

    private static String getStringValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;

        String rest = json.substring(colonIdx + 1).trim();
        if (rest.startsWith("null")) return null;
        if (!rest.startsWith("\"")) return null;

        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = 1; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    default -> { result.append('\\'); result.append(c); }
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static int getIntValue(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return defaultValue;

        String rest = json.substring(colonIdx + 1).trim();
        StringBuilder numStr = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c) || c == '-') numStr.append(c);
            else break;
        }
        try {
            return Integer.parseInt(numStr.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long getLongValue(String json, String key, long defaultValue) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return defaultValue;

        String rest = json.substring(colonIdx + 1).trim();
        StringBuilder numStr = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c) || c == '-') numStr.append(c);
            else break;
        }
        try {
            return Long.parseLong(numStr.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean getBooleanValue(String json, String key, boolean defaultValue) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return defaultValue;

        String rest = json.substring(colonIdx + 1).trim();
        if (rest.startsWith("true")) return true;
        if (rest.startsWith("false")) return false;
        return defaultValue;
    }

    private static String extractArray(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int bracketStart = json.indexOf('[', idx);
        if (bracketStart < 0) return null;

        int depth = 0;
        for (int i = bracketStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(bracketStart + 1, i);
                }
            }
        }
        return null;
    }

    private static List<String> splitJsonObjects(String arrayContent) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(arrayContent.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }
}
