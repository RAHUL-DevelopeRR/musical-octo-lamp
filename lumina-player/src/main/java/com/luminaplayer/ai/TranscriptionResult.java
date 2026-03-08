package com.luminaplayer.ai;

import java.io.File;

/**
 * Result of a whisper.cpp transcription, including the output SRT file,
 * optionally the auto-detected language code, and average log probability
 * (confidence score) for the transcription.
 */
public record TranscriptionResult(File srtFile, String detectedLanguage, double avgLogProb) {
    /** Constructor without confidence (backwards-compatible). */
    public TranscriptionResult(File srtFile, String detectedLanguage) {
        this(srtFile, detectedLanguage, 0.0);
    }
}
