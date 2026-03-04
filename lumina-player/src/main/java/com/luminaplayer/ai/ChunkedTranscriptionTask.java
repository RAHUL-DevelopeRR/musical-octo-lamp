package com.luminaplayer.ai;

import com.luminaplayer.subtitle.DsrtFile;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * JavaFX Task that runs chunked subtitle generation in a background thread
 * while reporting chunk-level progress to the UI.
 * Fires a callback when the first chunk completes so the subtitle overlay
 * can begin displaying cues immediately. Remaining chunks process in background
 * threads and complete after this task returns.
 */
public class ChunkedTranscriptionTask extends Task<DsrtFile> {

    private static final Logger log = LoggerFactory.getLogger(ChunkedTranscriptionTask.class);

    private final ChunkedSubtitleGenerator generator;
    private final java.io.File mediaFile;
    private final long totalDurationMs;
    private final long chunkDurationMs;
    private final WhisperModel model;
    private final WhisperLanguage language;
    private final boolean translate;
    private final WhisperQuality quality;
    private final long startFromMs;
    private final Consumer<DsrtFile> onFirstChunkReady;
    private final Consumer<ChunkProgressEvent> onChunkProgress;
    private final Runnable onAllComplete;

    public ChunkedTranscriptionTask(ChunkedSubtitleGenerator generator,
                                     java.io.File mediaFile, long totalDurationMs,
                                     long chunkDurationMs,
                                     WhisperModel model, WhisperLanguage language,
                                     boolean translate, WhisperQuality quality,
                                     long startFromMs,
                                     Consumer<DsrtFile> onFirstChunkReady,
                                     Consumer<ChunkProgressEvent> onChunkProgress,
                                     Runnable onAllComplete) {
        this.generator = generator;
        this.mediaFile = mediaFile;
        this.totalDurationMs = totalDurationMs;
        this.chunkDurationMs = chunkDurationMs;
        this.model = model;
        this.language = language;
        this.translate = translate;
        this.quality = quality;
        this.startFromMs = startFromMs;
        this.onFirstChunkReady = onFirstChunkReady;
        this.onChunkProgress = onChunkProgress;
        this.onAllComplete = onAllComplete;
    }

    @Override
    protected DsrtFile call() throws Exception {
        updateMessage("Initializing chunked subtitle generation...");
        updateProgress(-1, 1);

        return generator.generateChunked(
            mediaFile, totalDurationMs, chunkDurationMs,
            model, language, translate, quality, startFromMs,
            event -> {
                // Use generator's volatile flag instead of Task.isCancelled()
                // because Task state methods require the FX Application Thread
                if (generator.isCancelled()) {
                    throw new RuntimeException("Transcription cancelled");
                }

                // updateMessage/updateProgress are thread-safe from any thread
                updateMessage(event.message());
                updateProgress(event.completedChunks(), event.totalChunks());

                // Relay chunk progress to UI indicators on the FX thread
                if (onChunkProgress != null) {
                    Platform.runLater(() -> onChunkProgress.accept(event));
                }
            },
            // First chunk ready callback: relay to the UI on the FX thread
            dsrtFile -> {
                if (onFirstChunkReady != null) {
                    Platform.runLater(() -> onFirstChunkReady.accept(dsrtFile));
                }
            },
            // All chunks complete callback
            () -> {
                if (onAllComplete != null) {
                    Platform.runLater(onAllComplete);
                }
            }
        );
    }

    @Override
    protected void cancelled() {
        log.info("Chunked transcription task cancelled");
        generator.cancel();
        updateMessage("Cancelled");
    }

    @Override
    protected void failed() {
        log.error("Chunked transcription task failed", getException());
        updateMessage("Failed: " + getException().getMessage());
    }

    @Override
    protected void succeeded() {
        DsrtFile result = getValue();
        if (result != null) {
            updateMessage("First chunk ready - remaining chunks generating in background...");
        } else {
            updateMessage("Transcription produced no output");
        }
    }
}
