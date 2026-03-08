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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Orchestrates parallel chunked subtitle generation with a near-real-time
 * first-subtitle pipeline. Architecture:
 *
 * Phase 1 (instant): Extract 10s micro-chunk -> transcribe with TINY+INSTANT -> display
 * Phase 2 (background): Pre-extract full audio WAV -> slice chunks from WAV -> transcribe in parallel
 * Phase 3 (upgrade): Re-process priority chunk with full quality model
 */
public class ChunkedSubtitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChunkedSubtitleGenerator.class);

    /** Duration of the micro-chunk used for near-instant first subtitle (ms) */
    private static final long MICRO_CHUNK_MS = 10_000;

    /** Context overlap: extra audio prepended to each chunk for decoder context (ms).
     *  Whisper sees chunkDuration + CONTEXT_OVERLAP_MS of audio, but only cues
     *  in the owned time range are kept. Similar to YouTube's sliding window approach. */
    private static final long CONTEXT_OVERLAP_MS = 5_000;

    /** RMS energy threshold below which a chunk is considered silent and skipped */
    private static final double VAD_SILENCE_THRESHOLD = 50.0;

    private final AudioExtractor audioExtractor;
    private final WhisperEngine whisperEngine;
    private volatile ExecutorService executorService;
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
     * Runs the full chunked generation pipeline with near-real-time first subtitle.
     *
     * Phase 1: Extract a 10s micro-chunk at playback position -> transcribe with TINY+INSTANT
     *          -> deliver first subtitle in 1-3 seconds
     * Phase 2: Pre-extract full audio to WAV -> slice chunks from WAV -> parallel transcription
     * Phase 3: Re-process priority chunk with user's full-quality model
     *
     * @param mediaFile            source media file
     * @param totalDurationMs      total duration of the media in milliseconds
     * @param chunkDurationMs      duration of each chunk (default 60000ms)
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

        // --- Resumable generation: try loading existing .dsrt cache ---
        DsrtFile dsrtFile = null;
        File existingDsrt = new File(mediaFile.getParent(), getBaseName(mediaFile) + ".dsrt");
        if (existingDsrt.exists()) {
            try {
                DsrtFile cached = DsrtFile.loadFrom(existingDsrt);
                // Reuse if media file, chunk size, and settings match
                if (cached.getMediaFilePath() != null
                        && cached.getChunkDurationMs() == chunkDurationMs
                        && cached.getTotalDurationMs() == totalDurationMs) {
                    dsrtFile = cached;
                    int completed = dsrtFile.getCompletedChunkCount();
                    int total = dsrtFile.getTotalChunkCount();
                    log.info("Resuming from cached .dsrt: {}/{} chunks already completed", completed, total);
                    progressCallback.accept(new ChunkProgressEvent(-1, total,
                        ChunkStatus.EXTRACTING,
                        "Resuming: " + completed + "/" + total + " chunks cached",
                        completed));
                }
            } catch (Exception e) {
                log.warn("Could not load cached .dsrt, starting fresh: {}", e.getMessage());
            }
        }

        if (dsrtFile == null) {
            dsrtFile = DsrtFile.create(mediaFile, totalDurationMs,
                chunkDurationMs, model.modelName(), language.code(), translate);
        }

        // Capture as effectively-final for lambda use
        final DsrtFile dsrt = dsrtFile;

        int totalChunks = dsrtFile.getTotalChunkCount();

        // Determine priority chunk from current playback position
        int priorityIndex = Math.max(0, Math.min(
            (int) (startFromMs / chunkDurationMs), totalChunks - 1));
        log.info("Starting chunked generation: {} chunks for {}ms, priority chunk {} (from {}ms)",
            totalChunks, totalDurationMs, priorityIndex, startFromMs);

        // --- Check if priority chunk is already cached ---
        // A chunk is considered "fully cached" only if it was processed at full quality
        // (not just a Phase 1 micro-chunk). We check that the cue count is reasonable
        // for the full chunk duration. A micro-chunk (10s) typically produces 1-5 cues,
        // while a full 60s chunk produces 10+ cues for normal speech.
        int priorityCueCount = 0;
        boolean priorityChunkCached = false;
        if (dsrtFile.getChunk(priorityIndex) != null
                && dsrtFile.getChunk(priorityIndex).getStatus() == ChunkStatus.COMPLETED) {
            priorityCueCount = dsrtFile.getCuesForChunk(priorityIndex).size();
            // A fully-processed chunk should have at least ~1 cue per 10s of audio.
            // If it has fewer, it was likely only a micro-chunk (Phase 1) result.
            long chunkDurSec = chunkDurationMs / 1000;
            int minCuesForFull = Math.max(1, (int) (chunkDurSec / 15));
            priorityChunkCached = priorityCueCount >= minCuesForFull;
            if (!priorityChunkCached && priorityCueCount > 0) {
                log.info("Priority chunk {} has {} cues (min {} for full cache), will re-process in Phase 2",
                    priorityIndex, priorityCueCount, minCuesForFull);
            }
        }

        // --- Select fast model for micro-chunk ---
        WhisperModel firstChunkModel = WhisperModel.TINY;
        WhisperQuality firstChunkQuality = WhisperQuality.INSTANT;
        boolean firstChunkTranslate = translate;

        if (model.ordinal() <= WhisperModel.TINY.ordinal()) {
            firstChunkModel = model;
            firstChunkQuality = quality;
        }

        if (!whisperEngine.isModelAvailable(firstChunkModel)) {
            firstChunkModel = model;
            firstChunkQuality = quality;
            log.info("TINY model not available, using {} for first chunk", model.modelName());
        } else {
            log.info("First chunk: INSTANT mode with {} model, 10s micro-chunk", firstChunkModel.modelName());
        }

        // Thread pool with tuned whisper thread count to avoid CPU over-subscription
        int poolSize = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        int threadsPerWorker = Math.max(2, Runtime.getRuntime().availableProcessors() / poolSize);
        whisperEngine.setThreadCount(threadsPerWorker);
        log.info("Whisper thread count tuned to {} per worker ({} workers)", threadsPerWorker, poolSize);

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

        // Track language detected during Phase 1 for AUTO mode
        WhisperLanguage effectiveLanguage = language;
        AtomicReference<String> detectedLanguageCode = new AtomicReference<>();

        // ======================================================================
        // PHASE 1: 10s micro-chunk for near-instant first subtitle (1-3 seconds)
        // ======================================================================
        // Use local variable for orchestration config to avoid race condition
        OrchestrationConfig phase1OrcConfig = null; // disabled for speed in Phase 1
        try {
            if (totalChunks > 0 && !cancelled && !priorityChunkCached) {
                DsrtChunk priorityChunk = dsrtFile.getChunk(priorityIndex);
                String label = "Chunk " + (priorityIndex + 1) + "/" + totalChunks;
                progressCallback.accept(new ChunkProgressEvent(priorityIndex, totalChunks,
                    ChunkStatus.EXTRACTING, label + ": Extracting 10s micro-chunk...", 0));

                boolean skipOrchestrationForFirstChunk = (orchestrationConfig != null && orchestrationConfig.isMultiModel());
                if (skipOrchestrationForFirstChunk) {
                    firstChunkTranslate = true; // Use Whisper's -tr flag for English
                }

                // Process only MICRO_CHUNK_MS (10s) of audio for instant display
                long microDuration = Math.min(MICRO_CHUNK_MS, priorityChunk.getDurationMs());
                boolean success = processChunk(dsrtFile, priorityChunk, mediaFile, null,
                    firstChunkModel, language, firstChunkTranslate, firstChunkQuality,
                    microDuration, phase1OrcConfig, msg -> {
                        progressCallback.accept(new ChunkProgressEvent(priorityIndex, totalChunks,
                            priorityChunk.getStatus(), label + ": " + msg,
                            completedCount.get()));
                    }, detectedLanguageCode);

                if (success) {
                    completedCount.incrementAndGet();
                    progressCallback.accept(new ChunkProgressEvent(priorityIndex, totalChunks,
                        ChunkStatus.COMPLETED,
                        label + " complete (" + dsrtFile.getCueCount() + " cues) - instant!",
                        completedCount.get()));
                    dsrtFile.saveTo(outputDsrt);

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

        // If priority chunk was already cached, deliver it immediately for fast display.
        // This covers both fully cached AND partially cached (micro-chunk) cases.
        if (priorityCueCount > 0 && firstChunkReadyCallback != null) {
            completedCount.set(dsrtFile.getCompletedChunkCount());
            boolean allCached = dsrtFile.getCompletedChunkCount() == totalChunks;
            log.info("Priority chunk {} served from cache ({} cues, fullyCached={}, allChunksCached={})",
                priorityIndex, priorityCueCount, priorityChunkCached, allCached);
            if (allCached) {
                progressCallback.accept(new ChunkProgressEvent(-1, totalChunks,
                    ChunkStatus.COMPLETED,
                    "Loaded from cache: " + dsrtFile.getCueCount() + " cues",
                    totalChunks));
            }
            firstChunkReadyCallback.accept(dsrtFile);
        }

        // ======================================================================
        // PHASE 2: Pre-extract full audio + parallel background chunks
        // ======================================================================

        // --- Auto language detection + model routing ---
        // Use the language detected in Phase 1 to select the optimal model for Phase 2.
        WhisperModel phase2Model = model;
        String detectedLang = detectedLanguageCode.get();
        if (detectedLang != null && language == WhisperLanguage.AUTO) {
            WhisperLanguage resolved = ModelRouter.resolveLanguage(detectedLang);
            if (resolved != null) {
                effectiveLanguage = resolved;
                phase2Model = ModelRouter.route(resolved, model, whisperEngine);
                log.info("Auto-detected language '{}' -> {}, routed model: {} -> {}",
                    detectedLang, resolved.displayName(), model.modelName(), phase2Model.modelName());
            }
        } else if (language != WhisperLanguage.AUTO) {
            // User chose a language explicitly - still try to route to a better model
            phase2Model = ModelRouter.route(language, model, whisperEngine);
        }

        List<Integer> chunkOrder = buildChunkOrder(priorityIndex, totalChunks, dsrtFile);

        // Always re-process the priority chunk in Phase 2 unless it was FULLY cached
        // (with adequate cue coverage). Phase 1 only processed a 10s micro-chunk, so
        // the priority chunk needs full-duration processing at the user's selected quality.
        if (!cancelled && !priorityChunkCached) {
            chunkOrder.add(priorityIndex);
            if (firstChunkModel != model || firstChunkQuality != quality
                    || (orchestrationConfig != null && orchestrationConfig.isMultiModel())) {
                log.info("Priority chunk {} scheduled for Phase 2: full duration + quality upgrade {} {} -> {} {}",
                    priorityIndex, firstChunkModel.modelName(), firstChunkQuality,
                    model.modelName(), quality);
            } else {
                log.info("Priority chunk {} scheduled for Phase 2: full duration (was micro-chunk only)",
                    priorityIndex);
            }
        }

        if (!chunkOrder.isEmpty() && !cancelled) {
            // Pre-extract full audio WAV in background for fast chunk slicing
            progressCallback.accept(new ChunkProgressEvent(-1, totalChunks,
                ChunkStatus.EXTRACTING, "Pre-extracting full audio for fast processing...",
                completedCount.get()));

            Path fullAudioDir = java.nio.file.Files.createTempDirectory("lumina-fullaudio-");
            File fullAudioWav = fullAudioDir.resolve("full.wav").toFile();

            boolean audioExtracted = audioExtractor.extractAudio(mediaFile, fullAudioWav, msg -> {
                progressCallback.accept(new ChunkProgressEvent(-1, totalChunks,
                    ChunkStatus.EXTRACTING, msg, completedCount.get()));
            });

            // If pre-extraction fails, fall back to per-chunk extraction from media file
            File audioSource = audioExtracted ? fullAudioWav : null;
            if (audioExtracted) {
                log.info("Full audio pre-extracted: {} ({} bytes)", fullAudioWav.getName(), fullAudioWav.length());
            } else {
                log.warn("Full audio pre-extraction failed, falling back to per-chunk extraction");
            }

            // Capture orchestration config for Phase 2 (avoid field mutation race)
            final OrchestrationConfig phase2OrcConfig = orchestrationConfig;
            final WhisperLanguage phase2Language = effectiveLanguage;
            final WhisperModel phase2ModelFinal = phase2Model;

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int chunkIndex : chunkOrder) {
                if (cancelled) break;
                final int ci = chunkIndex;
                DsrtChunk chunk = dsrt.getChunk(ci);
                final File audioSrc = audioSource;

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    if (cancelled || Thread.currentThread().isInterrupted()) return;

                    String prefix = "Chunk " + (ci + 1) + "/" + totalChunks + ": ";
                    progressCallback.accept(new ChunkProgressEvent(ci, totalChunks,
                        ChunkStatus.EXTRACTING, prefix + "Processing...",
                        completedCount.get()));

                    try {
                        boolean success = processChunk(dsrt, chunk, mediaFile, audioSrc,
                            phase2ModelFinal, phase2Language, translate, quality, 0,
                            phase2OrcConfig, msg -> {
                                progressCallback.accept(new ChunkProgressEvent(ci,
                                    totalChunks, chunk.getStatus(), prefix + msg,
                                    completedCount.get()));
                            }, null);

                        if (success) {
                            int completed = completedCount.incrementAndGet();
                            progressCallback.accept(new ChunkProgressEvent(ci,
                                totalChunks, ChunkStatus.COMPLETED,
                                prefix + "Complete (" + dsrt.getCueCount() + " cues total)",
                                completed));
                            dsrt.saveTo(outputDsrt);
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

            // Cleanup when all background chunks finish
            final File fullAudioFile = fullAudioWav;
            final Path fullAudioDirPath = fullAudioDir;
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((result, error) -> {
                    try {
                        if (!cancelled) {
                            dsrt.saveTo(outputDsrt);
                            log.info("Background chunked generation complete: {}/{} chunks, {} cues",
                                completedCount.get(), totalChunks, dsrt.getCueCount());
                        }
                    } catch (IOException e) {
                        log.error("Failed to save final .dsrt file", e);
                    } finally {
                        shutdown();
                        // Clean up pre-extracted audio (recursive for Windows compatibility)
                        cleanupTempDirectory(fullAudioDirPath);
                    }
                    if (onAllComplete != null) {
                        onAllComplete.run();
                    }
                });
        } else {
            shutdown();
            if (onAllComplete != null) {
                onAllComplete.run();
            }
        }

        return dsrtFile;
    }

    /**
     * Builds chunk processing order: forward from priority index first, then backward.
     * This ensures upcoming chunks are ready before the user reaches them.
     * Skips chunks that are already COMPLETED (resumable generation).
     */
    private List<Integer> buildChunkOrder(int priorityIndex, int totalChunks) {
        return buildChunkOrder(priorityIndex, totalChunks, null);
    }

    private List<Integer> buildChunkOrder(int priorityIndex, int totalChunks, DsrtFile dsrtFile) {
        List<Integer> order = new ArrayList<>();
        // Forward chunks first (user is watching forward)
        for (int i = priorityIndex + 1; i < totalChunks; i++) {
            if (isChunkPending(dsrtFile, i)) {
                order.add(i);
            }
        }
        // Then backward chunks (already passed, lower priority)
        for (int i = priorityIndex - 1; i >= 0; i--) {
            if (isChunkPending(dsrtFile, i)) {
                order.add(i);
            }
        }
        return order;
    }

    /** Returns true if a chunk needs processing (not already completed). */
    private boolean isChunkPending(DsrtFile dsrtFile, int chunkIndex) {
        if (dsrtFile == null) return true;
        DsrtChunk chunk = dsrtFile.getChunk(chunkIndex);
        return chunk == null || chunk.getStatus() != ChunkStatus.COMPLETED
            || dsrtFile.getCuesForChunk(chunkIndex).isEmpty();
    }

    /**
     * Processes a single chunk: extract audio segment -> VAD check -> transcribe -> add cues.
     *
     * @param preExtractedWav if non-null, slice from this WAV instead of extracting from media
     * @param maxDurationMs   if > 0, cap audio extraction at this duration (for micro-chunks)
     * @param orcConfig       orchestration config for this chunk (null to skip orchestration)
     * @param detectedLanguageOut if non-null, stores the auto-detected language code from Whisper
     */
    private boolean processChunk(DsrtFile dsrtFile, DsrtChunk chunk,
                                  File mediaFile, File preExtractedWav,
                                  WhisperModel model,
                                  WhisperLanguage language, boolean translate,
                                  WhisperQuality quality,
                                  long maxDurationMs,
                                  OrchestrationConfig orcConfig,
                                  Consumer<String> progressUpdate,
                                  AtomicReference<String> detectedLanguageOut)
            throws IOException, InterruptedException {

        Path tempDir = Files.createTempDirectory("lumina-chunk-" + chunk.getIndex() + "-");
        File tempWav = tempDir.resolve("chunk.wav").toFile();

        try {
            // Step 1: Extract audio chunk with context overlap for better accuracy.
            // The sliding context window prepends extra audio before the chunk start
            // so Whisper has decoder context, but we only keep cues in the owned range.
            chunk.setStatus(ChunkStatus.EXTRACTING);
            long extractDuration = (maxDurationMs > 0)
                ? Math.min(maxDurationMs, chunk.getDurationMs())
                : chunk.getDurationMs();

            // Calculate context-aware extraction boundaries
            long contextOverlap = (maxDurationMs > 0) ? 0 : CONTEXT_OVERLAP_MS; // No overlap for micro-chunks
            long contextStartMs = Math.max(0, chunk.getStartMs() - contextOverlap);
            long actualOverlap = chunk.getStartMs() - contextStartMs;
            long contextDuration = extractDuration + actualOverlap;

            boolean extracted;
            if (preExtractedWav != null && preExtractedWav.exists()) {
                // Fast path: slice from pre-extracted WAV (no video demuxing)
                progressUpdate.accept("Slicing audio chunk...");
                extracted = audioExtractor.sliceWavChunk(preExtractedWav, tempWav,
                    contextStartMs, contextDuration, progressUpdate);
            } else {
                // Fallback: extract directly from media file
                progressUpdate.accept("Extracting audio...");
                extracted = audioExtractor.extractAudioChunk(mediaFile, tempWav,
                    contextStartMs, contextDuration, progressUpdate);
            }

            if (!extracted) {
                chunk.setStatus(ChunkStatus.FAILED);
                chunk.setErrorMessage("Audio extraction failed");
                return false;
            }

            if (cancelled || Thread.currentThread().isInterrupted()) return false;

            // Step 1.5: VAD check - skip silent chunks to save whisper invocations
            if (maxDurationMs <= 0) { // Only for full chunks, not micro-chunks
                try {
                    if (!SimpleVAD.hasSpeech(tempWav, VAD_SILENCE_THRESHOLD)) {
                        chunk.setStatus(ChunkStatus.COMPLETED);
                        log.info("Chunk {} skipped: silent (below VAD threshold)", chunk.getIndex());
                        progressUpdate.accept("Skipped (silent)");
                        return true; // No cues to add, chunk is done
                    }
                } catch (Exception e) {
                    log.debug("VAD check failed for chunk {}, proceeding with transcription", chunk.getIndex(), e);
                    // Non-fatal: proceed with transcription on VAD failure
                }
            }

            // Step 2: Transcribe with Whisper
            chunk.setStatus(ChunkStatus.TRANSCRIBING);
            progressUpdate.accept("Transcribing with " + model.modelName() + " model...");

            File tempSrt = tempDir.resolve("chunk").toFile(); // Whisper adds .srt
            TranscriptionResult result = whisperEngine.transcribe(tempWav, tempSrt, model,
                language, translate, quality, progressUpdate);

            if (result == null || result.srtFile() == null || !result.srtFile().exists()) {
                chunk.setStatus(ChunkStatus.FAILED);
                chunk.setErrorMessage("Whisper produced no output");
                return false;
            }

            // Step 2.5: Confidence-based fallback - if log probability is too low,
            // re-transcribe with a larger model for better accuracy.
            // avgLogProb closer to 0 = high confidence; below -1.0 = low confidence.
            if (maxDurationMs <= 0 && result.avgLogProb() < -1.0 && model.ordinal() < WhisperModel.LARGE.ordinal()) {
                WhisperModel fallbackModel = WhisperModel.values()[model.ordinal() + 1];
                if (whisperEngine.isModelAvailable(fallbackModel)) {
                    log.info("Chunk {} low confidence (logProb={}), retrying with {} model",
                        chunk.getIndex(), String.format("%.3f", result.avgLogProb()), fallbackModel.modelName());
                    progressUpdate.accept("Low confidence, retrying with " + fallbackModel.modelName() + " model...");

                    File fallbackSrt = tempDir.resolve("chunk-fallback").toFile();
                    TranscriptionResult fallbackResult = whisperEngine.transcribe(tempWav, fallbackSrt,
                        fallbackModel, language, translate, quality, progressUpdate);

                    if (fallbackResult != null && fallbackResult.srtFile() != null
                            && fallbackResult.srtFile().exists()
                            && fallbackResult.avgLogProb() > result.avgLogProb()) {
                        log.info("Chunk {} fallback improved confidence: {} -> {}",
                            chunk.getIndex(),
                            String.format("%.3f", result.avgLogProb()),
                            String.format("%.3f", fallbackResult.avgLogProb()));
                        result = fallbackResult;
                    }
                }
            }

            // Capture detected language from Whisper's auto-detect
            if (detectedLanguageOut != null && result.detectedLanguage() != null) {
                detectedLanguageOut.set(result.detectedLanguage());
            }

            // Step 3: Parse SRT and optionally translate via orchestration
            SrtParser parser = new SrtParser();
            List<SubtitleEntry> entries = parser.parse(result.srtFile());

            // Step 3.1: Sliding context window - filter out cues from the overlap region.
            // Whisper timestamps are relative to the extracted audio (0-based).
            // The first `actualOverlap` ms is context only - discard cues that start there,
            // and adjust remaining cue timestamps by subtracting the overlap offset.
            if (actualOverlap > 0 && !entries.isEmpty()) {
                List<SubtitleEntry> filtered = new ArrayList<>();
                for (SubtitleEntry entry : entries) {
                    if (entry.getStartTimeMs() >= actualOverlap) {
                        // Shift timestamps back to be relative to chunk start
                        filtered.add(new SubtitleEntry(entry.getIndex(),
                            entry.getStartTimeMs() - actualOverlap,
                            entry.getEndTimeMs() - actualOverlap,
                            entry.getText()));
                    }
                }
                log.debug("Chunk {} context filter: {} -> {} entries ({}ms overlap discarded)",
                    chunk.getIndex(), entries.size(), filtered.size(), actualOverlap);
                entries = filtered;
            }

            // Step 3.5: Bilingual merge for single-pass translate mode
            // When translate=true but no orchestration, Whisper's -tr flag produces
            // English-only text. Do a second native pass to capture original text.
            boolean needsBilingualMerge = translate
                && (orcConfig == null || !orcConfig.isMultiModel())
                && language != null
                && !"en".equals(language.code())
                && !"auto".equals(language.code())
                && !entries.isEmpty();

            if (needsBilingualMerge) {
                progressUpdate.accept("Transcribing native text for bilingual display...");
                File nativeSrt = tempDir.resolve("chunk-native").toFile();
                TranscriptionResult nativeResult = whisperEngine.transcribe(tempWav, nativeSrt, model,
                    language, false, quality, progressUpdate);

                if (nativeResult != null && nativeResult.srtFile() != null && nativeResult.srtFile().exists()) {
                    List<SubtitleEntry> nativeEntries = parser.parse(nativeResult.srtFile());
                    if (!nativeEntries.isEmpty()) {
                        entries = mergeBilingualEntries(entries, nativeEntries);
                        log.info("Chunk {} bilingual merge: {} translated + {} native entries",
                            chunk.getIndex(), entries.size(), nativeEntries.size());
                    }
                }
            }

            // Step 4: Multi-model translation (if configured)
            if (orcConfig != null && orcConfig.isMultiModel() && !entries.isEmpty()) {
                chunk.setStatus(ChunkStatus.TRANSCRIBING); // reuse status for "translating"
                progressUpdate.accept("Translating via " + orcConfig.getTranslationProvider().getName() + "...");

                try {
                    MultiModelOrchestrator orchestrator = new MultiModelOrchestrator(orcConfig);
                    entries = orchestrator.translateEntries(entries, language.code(), progressUpdate);
                    log.info("Chunk {} translated: {} entries via {}", chunk.getIndex(),
                            entries.size(), orcConfig.getTranslationProvider().getName());
                } catch (TranslationException e) {
                    log.warn("Translation failed for chunk {}, using original text: {}",
                            chunk.getIndex(), e.getMessage());
                    progressUpdate.accept("Translation failed, keeping original: " + e.getMessage());
                }
            }

            // Remove old cues from previous pass (e.g., Phase 1 micro-chunk) before adding upgraded cues
            dsrtFile.removeCuesForChunk(chunk.getIndex());
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
            // Clean up temp files (recursive for Windows compatibility)
            cleanupTempDirectory(tempDir);
        }
    }

    /**
     * Merges translated (English) entries with native-language entries into bilingual format.
     * Each translated entry is paired with the best-overlapping native entry.
     */
    private List<SubtitleEntry> mergeBilingualEntries(
            List<SubtitleEntry> translatedEntries,
            List<SubtitleEntry> nativeEntries) {
        List<SubtitleEntry> merged = new ArrayList<>();
        for (SubtitleEntry te : translatedEntries) {
            SubtitleEntry bestMatch = findClosestNativeEntry(te, nativeEntries);
            String text = bestMatch != null
                ? te.getText() + "\n[" + bestMatch.getText() + "]"
                : te.getText();
            merged.add(new SubtitleEntry(te.getIndex(),
                te.getStartTimeMs(), te.getEndTimeMs(), text));
        }
        return merged;
    }

    /**
     * Finds the native entry with the greatest time overlap to the target entry.
     */
    private SubtitleEntry findClosestNativeEntry(SubtitleEntry target,
                                                  List<SubtitleEntry> nativeEntries) {
        SubtitleEntry best = null;
        long bestOverlap = 0;
        for (SubtitleEntry ne : nativeEntries) {
            long overlapStart = Math.max(target.getStartTimeMs(), ne.getStartTimeMs());
            long overlapEnd = Math.min(target.getEndTimeMs(), ne.getEndTimeMs());
            long overlap = overlapEnd - overlapStart;
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = ne;
            }
        }
        return best;
    }

    /**
     * Returns whether generation has been cancelled.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Cancels all running and pending chunk tasks, including child processes.
     */
    public void cancel() {
        cancelled = true;
        // Kill all active FFmpeg and whisper.cpp child processes
        audioExtractor.destroyAllProcesses();
        whisperEngine.destroyAllProcesses();
        if (executorService != null) {
            executorService.shutdownNow();
        }
        log.info("Chunked generation cancelled (child processes destroyed)");
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

    /**
     * Recursively deletes a temp directory (Windows-compatible).
     */
    private static void cleanupTempDirectory(Path dir) {
        try {
            if (dir != null && Files.exists(dir)) {
                Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            // Best-effort cleanup
                        }
                    });
            }
        } catch (IOException e) {
            log.debug("Could not clean up temp directory: {}", dir, e);
        }
    }

    private static String getBaseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
