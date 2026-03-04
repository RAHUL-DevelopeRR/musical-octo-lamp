package com.luminaplayer.subtitle;

/**
 * Metadata for a single generation chunk in the .dsrt pipeline.
 * Status and error are mutable and updated from worker threads.
 */
public class DsrtChunk {

    private final int index;
    private final long startMs;
    private final long endMs;
    private volatile ChunkStatus status;
    private volatile String errorMessage;

    public DsrtChunk(int index, long startMs, long endMs) {
        this.index = index;
        this.startMs = startMs;
        this.endMs = endMs;
        this.status = ChunkStatus.PENDING;
        this.errorMessage = null;
    }

    public int getIndex() { return index; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }

    public ChunkStatus getStatus() { return status; }
    public void setStatus(ChunkStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getDurationMs() { return endMs - startMs; }

    @Override
    public String toString() {
        return String.format("Chunk[%d] %dms-%dms (%s)", index, startMs, endMs, status);
    }
}
