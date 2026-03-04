package com.luminaplayer.subtitle;

/**
 * Lifecycle states for a subtitle generation chunk.
 */
public enum ChunkStatus {

    PENDING("Pending"),
    EXTRACTING("Extracting audio"),
    TRANSCRIBING("Transcribing"),
    COMPLETED("Completed"),
    FAILED("Failed");

    private final String displayName;

    ChunkStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
