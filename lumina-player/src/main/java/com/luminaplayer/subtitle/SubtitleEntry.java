package com.luminaplayer.subtitle;

/**
 * Represents a single subtitle entry with timing and text.
 */
public class SubtitleEntry {

    private final int index;
    private final long startTimeMs;
    private final long endTimeMs;
    private final String text;

    public SubtitleEntry(int index, long startTimeMs, long endTimeMs, String text) {
        this.index = index;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.text = text;
    }

    public int getIndex() { return index; }
    public long getStartTimeMs() { return startTimeMs; }
    public long getEndTimeMs() { return endTimeMs; }
    public String getText() { return text; }

    @Override
    public String toString() {
        return String.format("[%d] %d -> %d: %s", index, startTimeMs, endTimeMs, text);
    }
}
