package com.luminaplayer.ai;

import com.luminaplayer.subtitle.SrtParser;
import com.luminaplayer.subtitle.SubtitleEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates the full subtitle generation pipeline:
 * Media File -> Audio Extraction (FFmpeg) -> Transcription (Whisper) -> SRT File.
 */
public class SubtitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(SubtitleGenerator.class);

    private final AudioExtractor audioExtractor;
    private final WhisperEngine whisperEngine;

    public SubtitleGenerator() {
        this.audioExtractor = new AudioExtractor();
        this.whisperEngine = new WhisperEngine();
    }

    public AudioExtractor getAudioExtractor() {
        return audioExtractor;
    }

    public WhisperEngine getWhisperEngine() {
        return whisperEngine;
    }

    /**
     * Checks whether all required tools are available.
     */
    public AvailabilityStatus checkAvailability() {
        boolean ffmpeg = audioExtractor.isAvailable();
        boolean whisper = whisperEngine.isAvailable();
        return new AvailabilityStatus(ffmpeg, whisper,
            audioExtractor.getToolPath(), whisperEngine.getBinaryPath());
    }

    /**
     * Generates subtitles for a media file.
     *
     * @param mediaFile      source media file
     * @param model          Whisper model to use
     * @param language       target language
     * @param translate      translate to English
     * @param progressUpdate callback for progress messages
     * @return the generated SRT file, or null on failure
     */
    public File generateSubtitles(File mediaFile, WhisperModel model,
                                  WhisperLanguage language, boolean translate,
                                  Consumer<String> progressUpdate)
            throws IOException, InterruptedException {

        // Create temp directory for intermediate files
        Path tempDir = Files.createTempDirectory("lumina-stt-");
        File tempWav = tempDir.resolve("audio.wav").toFile();

        try {
            // Step 1: Extract audio
            progressUpdate.accept("Step 1/2: Extracting audio...");
            boolean extracted = audioExtractor.extractAudio(mediaFile, tempWav, progressUpdate);
            if (!extracted) {
                throw new IOException("Audio extraction failed");
            }

            // Step 2: Transcribe with Whisper
            progressUpdate.accept("Step 2/2: Transcribing with Whisper...");
            String baseName = mediaFile.getName();
            int dotIdx = baseName.lastIndexOf('.');
            if (dotIdx > 0) {
                baseName = baseName.substring(0, dotIdx);
            }

            // Output SRT next to the original media file
            File outputSrt = new File(mediaFile.getParent(), baseName + ".srt");
            File result = whisperEngine.transcribe(tempWav, outputSrt, model,
                language, translate, progressUpdate);

            if (result != null) {
                progressUpdate.accept("Subtitles generated: " + result.getName());
                log.info("Subtitle generation complete: {}", result.getAbsolutePath());

                // Validate the SRT file
                SrtParser parser = new SrtParser();
                List<SubtitleEntry> entries = parser.parse(result);
                log.info("Generated SRT contains {} subtitle entries", entries.size());
            }

            return result;

        } finally {
            // Clean up temp files
            try {
                if (tempWav.exists()) Files.delete(tempWav.toPath());
                Files.deleteIfExists(tempDir);
            } catch (IOException e) {
                log.debug("Could not clean up temp files", e);
            }
        }
    }

    /**
     * Status of required tool availability.
     */
    public record AvailabilityStatus(
        boolean ffmpegAvailable,
        boolean whisperAvailable,
        String ffmpegPath,
        String whisperPath
    ) {
        public boolean isReady() {
            return ffmpegAvailable && whisperAvailable;
        }

        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("FFmpeg: ").append(ffmpegAvailable ? "Found" : "NOT FOUND");
            if (ffmpegPath != null) sb.append(" (").append(ffmpegPath).append(")");
            sb.append("\n");
            sb.append("Whisper: ").append(whisperAvailable ? "Found" : "NOT FOUND");
            if (whisperPath != null) sb.append(" (").append(whisperPath).append(")");
            return sb.toString();
        }
    }
}
