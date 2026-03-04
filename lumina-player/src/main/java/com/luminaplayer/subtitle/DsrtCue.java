package com.luminaplayer.subtitle;

/**
 * A single subtitle cue in the .dsrt dynamic subtitle format.
 * Immutable and sorted by start time for efficient lookup.
 */
public record DsrtCue(
    int id,
    long startTimeMs,
    long endTimeMs,
    String text,
    int chunkIndex
) implements Comparable<DsrtCue> {

    @Override
    public int compareTo(DsrtCue other) {
        return Long.compare(this.startTimeMs, other.startTimeMs);
    }
}
