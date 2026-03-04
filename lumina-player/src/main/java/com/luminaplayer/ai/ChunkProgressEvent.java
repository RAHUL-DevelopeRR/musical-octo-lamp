package com.luminaplayer.ai;

import com.luminaplayer.subtitle.ChunkStatus;

/**
 * Progress event fired during chunked subtitle generation.
 * Passed from ChunkedSubtitleGenerator to the UI via a callback.
 */
public record ChunkProgressEvent(
    int chunkIndex,
    int totalChunks,
    ChunkStatus status,
    String message,
    int completedChunks
) {}
