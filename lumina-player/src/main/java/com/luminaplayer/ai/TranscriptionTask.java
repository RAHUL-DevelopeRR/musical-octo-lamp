package com.luminaplayer.ai;

import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * JavaFX Task that runs subtitle generation in a background thread
 * while reporting progress to the UI.
 */
public class TranscriptionTask extends Task<File> {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionTask.class);

    private final SubtitleGenerator generator;
    private final File mediaFile;
    private final WhisperModel model;
    private final WhisperLanguage language;
    private final boolean translate;

    public TranscriptionTask(SubtitleGenerator generator, File mediaFile,
                             WhisperModel model, WhisperLanguage language,
                             boolean translate) {
        this.generator = generator;
        this.mediaFile = mediaFile;
        this.model = model;
        this.language = language;
        this.translate = translate;
    }

    @Override
    protected File call() throws Exception {
        updateMessage("Initializing subtitle generation...");
        updateProgress(-1, 1); // indeterminate

        return generator.generateSubtitles(mediaFile, model, language, translate, message -> {
            if (isCancelled()) {
                throw new RuntimeException("Transcription cancelled");
            }
            updateMessage(message);

            // Parse percentage from progress messages
            if (message.contains("%")) {
                try {
                    int pctIdx = message.indexOf('%');
                    int start = pctIdx - 1;
                    while (start >= 0 && (Character.isDigit(message.charAt(start)) || message.charAt(start) == '.')) {
                        start--;
                    }
                    String pctStr = message.substring(start + 1, pctIdx).trim();
                    double pct = Double.parseDouble(pctStr);
                    updateProgress(pct, 100.0);
                } catch (NumberFormatException e) {
                    // ignore parse errors
                }
            }
        });
    }

    @Override
    protected void cancelled() {
        log.info("Transcription task cancelled");
        updateMessage("Cancelled");
    }

    @Override
    protected void failed() {
        log.error("Transcription task failed", getException());
        updateMessage("Failed: " + getException().getMessage());
    }

    @Override
    protected void succeeded() {
        File result = getValue();
        if (result != null) {
            updateMessage("Complete: " + result.getName());
        } else {
            updateMessage("Transcription produced no output");
        }
    }
}
