package com.luminaplayer.ai;

import com.luminaplayer.ai.orchestration.MultiModelOrchestrator;
import com.luminaplayer.ai.orchestration.OrchestrationConfig;
import com.luminaplayer.ai.orchestration.TranslationException;
import com.luminaplayer.subtitle.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Orchestrates parallel chunked subtitle generation.
 * Splits a media file into 30-second chunks, processes the first chunk immediately
 * for instant subtitles, then processes remaining chunks via a thread pool.
 */
public class ChunkedSubtitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChunkedSubtitleGenerator.class);

    private final AudioExtractor audioExtractor;
    private final WhisperEngine whisperEngine;
    private volatile ExecutorService executorService;
    private final List<Future<?>> activeFutures = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled = false;
    private volatile OrchestrationConfig orchestrationConfig;

    public ChunkedSubtitleGenerator() {
        this.audioExtractor = new AudioExtractor();
        this.whisperEngine = new WhisperEngine();
    }

    public AudioExtractor getAudioExtractor() {
        return audioExtractor;
    }

    public WhisperEngine getWhisperEngine() {
        return whisperEngine;
    }

    public void setOrchestrationConfig(OrchestrationConfig config) {
        this.orchestrationConfig = config;
    }

    public OrchestrationConfig getOrchestrationConfig() {
        return orchestrationConfig;
    }

    /**
     * Checks whether all required tools are available.
     */
    public SubtitleGenerator.AvailabilityStatus checkAvailability() {
        boolean ffmpeg = audioExtractor.isAvailable();
        boolean whisper = whisperEngine.isAvailable();
        return new SubtitleGenerator.AvailabilityStatus(ffmpeg, whisper,
            audioExtractor.getToolPath(), whisperEngine.getBinaryPath());
    }

    /**
     * Runs the full chunked generation pipeline with priority-based ordering.
     * Processes the chunk at the current playback position first for immediate
     * subtitles, then submits remaining chunks to run in the background.
     * Returns immediately after launching background work -- does NOT block.
     *
     * @param mediaFile            source media file
     * @param totalDurationMs      total duration of the media in milliseconds
     * @param chunkDurationMs      duration of each chunk (default 30000ms)
     * @param model                Whisper model to use
     * @param language             target language
     * @param translate            whether to translate to English
     * @param quality              transcription quality preset
     * @param startFromMs          current playback position to determine priority chunk
     * @param progressCallback     callback for chunk progress events
     * @param firstChunkReadyCallback callback when the first chunk is ready for display
     * @param onAllComplete        callback when all background chunks finish
     * @return the populated DsrtFile, or null on total failure
     */
    public DsrtFile generateChunked(File mediaFile, long totalDurationMs,
                                     long chunkDurationMs,
                                     WhisperModel model, WhisperLanguage language,
                                     boolean translate, WhisperQuality quality,
                                     long startFromMs,
                                     Consumer<ChunkProgressEvent> progressCallback,
                                     Consumer<DsrtFile> firstChunkReadyCallback,
                                     Runnable onAllComplete)
            throws IOException, InterruptedException {

        cancelled = false;

        DsrtFile dsrtFile = DsrtFile.create(mediaFile, totalDurationMs,
            chunkDurationMs, model.modelName(), language.code(), translate);

        int totalChunks = dsrtFile.getTotalChunkCount();

        // Determine priority chunk from current playback position
        int priorityIndex = Math.max(0, Math.min(
            (int) (startFromMs / chunkDurationMs), totalChunks - 1));
        log.info("Starting chunked generation: {} chunks for {}ms, priority chunk {} (from {}ms)",
            totalChunks, totalDurationMs, priorityIndex, startFromMs);

        // --- Determine first-chunk speed model ---
        // Use TINY model + FAST quality for the priority chunk for ~3s generation time
        // Then use the user's selected (possibly larger) model for background chunks
        WhisperModel firstChunkModel = WhisperModel.TINY;
        WhisperQuality firstChunkQuality = WhisperQuality.FAST;
        boolean firstChunkTranslate = translate; // still honor user's translate preference

        // If user selected TINY already, use their settings
        if (model.ordinal() <= WhisperModel.TINY.ordinal()) {
            firstChunkModel = model;
            firstChunkQuality = quality;
        }

        // Check if TINY model is available; if not, fall back to user's selection
        if (!whisperEngine.isModelAvailable(firstChunkModel)) {
            firstChunkModel = model;
            firstChunkQuality = quality;
            log.info("TINY model not available for fast first chunk, using {} instead", model.modelName());
        } else {
            log.info("First chunk: FAST mode with {} model for instant subtitles", firstChunkModel.modelName());
        }

        // Thread pool: scale with CPU cores for real parallelism
        int poolSize = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        AtomicInteger threadCounter = new AtomicInteger(0);
        executorService = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "chunk-worker-" + threadCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        log.info("Thread pool size: {} workers", poolSize);

        AtomicInteger completedCount = new AtomicInteger(0);
        File outputDsrt = new File(mediaFile.getParent(),
            getBaseName(mediaFile) + ".dsrt");

        // --- Phase 1: Process priority chunk synchronously for immediate display ---
        // Uses TINY+FAST for speed, skips translation — shows raw transcription instantly
        try {
            if (totalChunks > 0 && !cancelled) {
                DsrtChunk priorityChunk = dsrtFile.getChunk(priorityIndex);
                String label = "Chunk " + (priorityIndex + 1) + "/" + totalChunks;
                progressCallback.accept(new ChunkProgressEvent(priorityIndex, totalChunks,
                    ChunkStatus.EXTRACTING, label + ": Extracting audio (fast mode)...", 0));

                // First chunk: use fast model, skip translation for instant display
                boolean skipOrchestrationForFirstChunk = (orchestrationConfig != null && orchestrationConfig.isMultiModel());
                OrchestrationConfig savedConfig = orchestrationConfig;
                if (skipOrchestrationForFirstChunk) {
                    orchestrationConfig = null; // temporarily disable for speed
                }

                boolean success = processChunk(dsrtFile, priorityChunk, mediaFile,
                    firstChunkModel, language, firstChunkTranslate, firstChunkQuality, msg -> {
                        progressCallback.accept(new ChunkProgressEvent(priorityIndex, totalChunks,
                            priorityChunk.getStatus(), label + ": " + msg,
                            completedCount.get()));
                    });

                // Restore orchestration config for background chunks
                if (skipOrchestrationForFirstChunk) {
                    orchestrationConfig = savedConfig;
                }

                if (success) {
                    completedCount.incrementAndGet();
                    progressCallback.accept(new ChunkProgressEvent(priorityIndex, totalChunks,
                        ChunkStatus.COMPLETED,
                        label + " complete (" + dsrtFile.getCueCount() + " cues) — instant!",
                        completedCount.get()));
                    dsrtFile.saveTo(outputDsrt);

                    // Notify that first chunk is ready for immediate subtitle display
                    if (firstChunkReadyCallback != null) {
                        firstChunkReadyCallback.accept(dsrtFile);
                    }
                } else {
                    progressCallback.accept(new ChunkProgressEvent(priorityIndex, totalChunks,
                        ChunkStatus.FAILED,
                        label + " failed: " + priorityChunk.getErrorMessage(),
                        completedCount.get()));
                }
            }
        } catch (Exception e) {
            shutdown();
            throw e;
        }

        // --- Phase 2: Submit remaining chunks to run in the background (non-blocking) ---
        // Also re-process the priority chunk with the user's higher-quality model
        // so translations and better accuracy get applied transparently
        List<Integer> chunkOrder = buildChunkOrder(priorityIndex, totalChunks);

        // If first chunk used a fast model, schedule it for re-transcription with full quality
        boolean needsUpgrade = (firstChunkModel != model || firstChunkQuality != quality
                || (orchestrationConfig != null && orchestrationConfig.isMultiModel()));
        if (needsUpgrade && !cancelled) {
            chunkOrder.add(priorityIndex); // add priority chunk at the end for upgrade
            log.info("Priority chunk {} scheduled for quality upgrade: {} {} → {} {}",
                priorityIndex, firstChunkModel.modelName(), firstChunkQuality,
                model.modelName(), quality);
        }

        if (!chunkOrder.isEmpty() && !cancelled) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int chunkIndex : chunkOrder) {
                if (cancelled) break;
                final int ci = chunkIndex;
                DsrtChunk chunk = dsrtFile.getChunk(ci);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    if (cancelled || Thread.currentThread().isInterrupted()) return;

                    String prefix = "Chunk " + (ci + 1) + "/" + totalChunks + ": ";
                    progressCallback.accept(new ChunkProgressEvent(ci, totalChunks,
                        ChunkStatus.EXTRACTING, prefix + "Extracting audio...",
                        completedCount.get()));

                    try {
                        boolean success = processChunk(dsrtFile, chunk, mediaFile,
                            model, language, translate, quality, msg -> {
                                progressCallback.accept(new ChunkProgressEvent(ci,
                                    totalChunks, chunk.getStatus(), prefix + msg,
                                    completedCount.get()));
                            });

                        if (success) {
                            int completed = completedCount.incrementAndGet();
                            progressCallback.accept(new ChunkProgressEvent(ci,
                                totalChunks, ChunkStatus.COMPLETED,
                                prefix + "Complete (" + dsrtFile.getCueCount() + " cues total)",
                                completed));
                            dsrtFile.saveTo(outputDsrt);
                        } else {
                            progressCallback.accept(new ChunkProgressEvent(ci,
                                totalChunks, ChunkStatus.FAILED,
                                prefix + "Failed: " + chunk.getErrorMessage(),
                                completedCount.get()));
                        }
                    } catch (IOException | InterruptedException e) {
                        chunk.setStatus(ChunkStatus.FAILED);
                        chunk.setErrorMessage(e.getMessage());
                        progressCallback.accept(new ChunkProgressEvent(ci,
                            totalChunks, ChunkStatus.FAILED,
                            prefix + "Error: " + e.getMessage(),
                            completedCount.get()));
                    }
                }, executorService);

                futures.add(future);
            }

            // Non-blocking: cleanup when all background chunks finish
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((result, error) -> {
                    try {
                        if (!cancelled) {
                            dsrtFile.saveTo(outputDsrt);
                            log.info("Background chunked generation complete: {}/{} chunks, {} cues",
                                completedCount.get(), totalChunks, dsrtFile.getCueCount());
                        }
                    } catch (IOException e) {
                        log.error("Failed to save final .dsrt file", e);
                    } finally {
                        shutdown();
                    }
                    if (onAllComplete != null) {
                        onAllComplete.run();
                    }
                });
        } else {
            // No remaining chunks or cancelled - complete immediately
            shutdown();
            if (onAllComplete != null) {
                onAllComplete.run();
            }
        }

        // Return immediately - subtitles appear incrementally as each chunk completes
        return dsrtFile;
    }

    /**
     * Builds chunk processing order: forward from priority index first, then backward.
     * This ensures upcoming chunks are ready before the user reaches them.
     */
    private List<Integer> buildChunkOrder(int priorityIndex, int totalChunks) {
        List<Integer> order = new ArrayList<>();
        // Forward chunks first (user is watching forward)
        for (int i = priorityIndex + 1; i < totalChunks; i++) {
            order.add(i);
        }
        // Then backward chunks (already passed, lower priority)
        for (int i = priorityIndex - 1; i >= 0; i--) {
            order.add(i);
        }
        return order;
    }

    /**
     * Processes a single chunk: extract audio segment -> transcribe -> add cues.
     */
    private boolean processChunk(DsrtFile dsrtFile, DsrtChunk chunk,
                                  File mediaFile, WhisperModel model,
                                  WhisperLanguage language, boolean translate,
                                  WhisperQuality quality,
                                  Consumer<String> progressUpdate)
            throws IOException, InterruptedException {

        Path tempDir = Files.createTempDirectory("lumina-chunk-" + chunk.getIndex() + "-");
        File tempWav = tempDir.resolve("chunk.wav").toFile();

        try {
            // Step 1: Extract audio chunk
            chunk.setStatus(ChunkStatus.EXTRACTING);
            progressUpdate.accept("Extracting audio...");

            boolean extracted = audioExtractor.extractAudioChunk(mediaFile, tempWav,
                chunk.getStartMs(), chunk.getDurationMs(), progressUpdate);

            if (!extracted) {
                chunk.setStatus(ChunkStatus.FAILED);
                chunk.setErrorMessage("Audio extraction failed");
                return false;
            }

            if (cancelled || Thread.currentThread().isInterrupted()) return false;

            // Step 2: Transcribe with Whisper
            chunk.setStatus(ChunkStatus.TRANSCRIBING);
            progressUpdate.accept("Transcribing with " + model.modelName() + " model...");

            File tempSrt = tempDir.resolve("chunk").toFile(); // Whisper adds .srt
            File srtResult = whisperEngine.transcribe(tempWav, tempSrt, model,
                language, translate, quality, progressUpdate);

            if (srtResult == null || !srtResult.exists()) {
                chunk.setStatus(ChunkStatus.FAILED);
                chunk.setErrorMessage("Whisper produced no output");
                return false;
            }

            // Step 3: Parse SRT and optionally translate via orchestration
            SrtParser parser = new SrtParser();
            List<SubtitleEntry> entries = parser.parse(srtResult);

            // Step 4: Multi-model translation (if configured)
            OrchestrationConfig oc = orchestrationConfig;
            if (oc != null && oc.isMultiModel() && !entries.isEmpty()) {
                chunk.setStatus(ChunkStatus.TRANSCRIBING); // reuse status for "translating"
                progressUpdate.accept("Translating via " + oc.getTranslationProvider().getName() + "...");

                try {
                    MultiModelOrchestrator orchestrator = new MultiModelOrchestrator(oc);
                    entries = orchestrator.translateEntries(entries, language.code(), progressUpdate);
                    log.info("Chunk {} translated: {} entries via {}", chunk.getIndex(),
                            entries.size(), oc.getTranslationProvider().getName());
                } catch (TranslationException e) {
                    log.warn("Translation failed for chunk {}, using original text: {}",
                            chunk.getIndex(), e.getMessage());
                    progressUpdate.accept("Translation failed, keeping original: " + e.getMessage());
                }
            }

            dsrtFile.addCues(chunk.getIndex(), entries, chunk.getStartMs());

            chunk.setStatus(ChunkStatus.COMPLETED);
            log.info("Chunk {} complete: {} entries", chunk.getIndex(), entries.size());
            return true;

        } catch (Exception e) {
            chunk.setStatus(ChunkStatus.FAILED);
            chunk.setErrorMessage(e.getMessage());
            log.error("Chunk {} failed", chunk.getIndex(), e);
            throw e;
        } finally {
            // Clean up temp files
            try {
                File[] tempFiles = tempDir.toFile().listFiles();
                if (tempFiles != null) {
                    for (File f : tempFiles) {
                        Files.deleteIfExists(f.toPath());
                    }
                }
                Files.deleteIfExists(tempDir);
            } catch (IOException e) {
                log.debug("Could not clean up temp files for chunk {}", chunk.getIndex(), e);
            }
        }
    }

    /**
     * Returns whether generation has been cancelled.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Cancels all running and pending chunk tasks.
     */
    public void cancel() {
        cancelled = true;
        if (executorService != null) {
            executorService.shutdownNow();
        }
        log.info("Chunked generation cancelled");
    }

    /**
     * Shuts down the executor service gracefully.
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String getBaseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
