package com.luminaplayer.ai.orchestration;

import com.luminaplayer.subtitle.SubtitleEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Multi-model orchestrator for subtitle translation.
 *
 * Pipeline:
 *   1. Whisper transcribes audio in the NATIVE language (high accuracy)
 *   2. TranslationProvider translates transcribed text to English
 *   3. (Optional) VerificationProvider verifies/improves translations
 *
 * This approach is far superior to Whisper's built-in -tr flag because:
 *   - Whisper transcription accuracy is highest in the native language
 *   - Dedicated translation models (LibreTranslate, LLMs) produce better translations
 *   - Verification pass catches errors and improves quality
 *   - Each model does what it's best at (separation of concerns)
 */
public class MultiModelOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiModelOrchestrator.class);

    private final OrchestrationConfig config;
    private volatile boolean cancelled = false;

    public MultiModelOrchestrator(OrchestrationConfig config) {
        this.config = config;
    }

    /**
     * Translates a list of subtitle entries using the configured pipeline.
     * Entries are translated in-place (the text field is updated).
     * If keepOriginalText is true, the original text is prepended in brackets.
     *
     * @param entries          subtitle entries from Whisper transcription (native language)
     * @param sourceLang       source language code (e.g. "ja", "ta")
     * @param progressCallback progress updates
     * @return the same list with translated text
     * @throws TranslationException if translation fails critically
     */
    public List<SubtitleEntry> translateEntries(List<SubtitleEntry> entries,
                                                 String sourceLang,
                                                 Consumer<String> progressCallback)
            throws TranslationException {
        if (entries == null || entries.isEmpty()) return entries;
        if (!config.isMultiModel()) return entries; // SINGLE_PASS: no translation needed

        String targetLang = config.getTargetLanguage();
        TranslationProvider provider = config.getTranslationProvider();

        progressCallback.accept("Translating " + entries.size() + " subtitles via " + provider.getName() + "...");
        log.info("Starting translation: {} entries, {} -> {}, provider={}",
                entries.size(), sourceLang, targetLang, provider.getName());

        // Step 1: Extract texts and batch-translate
        List<String> originalTexts = entries.stream()
                .map(SubtitleEntry::getText)
                .toList();

        List<String> translatedTexts = translateInBatches(originalTexts, sourceLang,
                targetLang, provider, progressCallback);

        // Step 2: Optional verification pass
        if (config.getMode() == OrchestrationConfig.Mode.MULTI_MODEL_VERIFIED &&
                config.getVerificationProvider() != null) {
            TranslationProvider verifier = config.getVerificationProvider();
            progressCallback.accept("Verifying translations with " + verifier.getName() + "...");
            translatedTexts = verifyTranslations(originalTexts, translatedTexts,
                    sourceLang, targetLang, verifier, progressCallback);
        }

        // Step 3: Build result entries with translated text
        List<SubtitleEntry> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            SubtitleEntry original = entries.get(i);
            String translated = i < translatedTexts.size() ? translatedTexts.get(i) : original.getText();

            String finalText;
            if (config.isKeepOriginalText() && !sourceLang.equals(targetLang)) {
                // Show both: translated text + original in brackets
                finalText = translated + "\n[" + original.getText() + "]";
            } else {
                finalText = translated;
            }

            result.add(new SubtitleEntry(original.getIndex(),
                    original.getStartTimeMs(), original.getEndTimeMs(), finalText));
        }

        progressCallback.accept("Translation complete: " + result.size() + " subtitles");
        log.info("Translation complete: {} entries translated", result.size());
        return result;
    }

    /**
     * Translates texts in batches, respecting the provider's max chars limit.
     */
    private List<String> translateInBatches(List<String> texts, String sourceLang,
                                             String targetLang,
                                             TranslationProvider provider,
                                             Consumer<String> progressCallback)
            throws TranslationException {

        int maxChars = provider.getMaxCharsPerRequest();
        int batchSize = config.getTranslationBatchSize();

        // Build batches respecting both count and character limits
        List<List<String>> batches = buildBatches(texts, batchSize, maxChars);
        List<String> allTranslated = new ArrayList<>();
        AtomicInteger batchNum = new AtomicInteger(0);

        if (config.isParallelTranslation() && batches.size() > 1) {
            // Parallel batch translation
            allTranslated = translateBatchesParallel(batches, sourceLang, targetLang,
                    provider, batchNum, batches.size(), progressCallback);
        } else {
            // Sequential batch translation
            for (List<String> batch : batches) {
                if (cancelled) break;
                int num = batchNum.incrementAndGet();
                progressCallback.accept(String.format("Translating batch %d/%d (%d texts)...",
                        num, batches.size(), batch.size()));

                List<String> translated = provider.translateBatch(batch, sourceLang, targetLang);
                allTranslated.addAll(translated);
            }
        }

        return allTranslated;
    }

    /**
     * Translates batches in parallel using a thread pool.
     */
    private List<String> translateBatchesParallel(List<List<String>> batches,
                                                   String sourceLang, String targetLang,
                                                   TranslationProvider provider,
                                                   AtomicInteger batchNum, int totalBatches,
                                                   Consumer<String> progressCallback)
            throws TranslationException {

        int poolSize = Math.min(3, batches.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "translate-worker");
            t.setDaemon(true);
            return t;
        });

        try {
            // Submit all batches and preserve order
            List<Future<List<String>>> futures = new ArrayList<>();
            for (List<String> batch : batches) {
                futures.add(executor.submit(() -> {
                    if (cancelled) return List.<String>of();
                    int num = batchNum.incrementAndGet();
                    progressCallback.accept(String.format("Translating batch %d/%d (%d texts)...",
                            num, totalBatches, batch.size()));
                    return provider.translateBatch(batch, sourceLang, targetLang);
                }));
            }

            // Collect results in order
            List<String> allTranslated = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                try {
                    allTranslated.addAll(future.get(120, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    throw new TranslationException("Translation batch timed out");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof TranslationException te) throw te;
                    throw new TranslationException("Batch translation failed: " + cause.getMessage(), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TranslationException("Translation interrupted");
                }
            }
            return allTranslated;

        } finally {
            executor.shutdown();
        }
    }

    /**
     * Verification pass: uses a second model to review and improve translations.
     * The verifier receives the original + translated text and can correct errors.
     */
    private List<String> verifyTranslations(List<String> originals,
                                             List<String> translations,
                                             String sourceLang, String targetLang,
                                             TranslationProvider verifier,
                                             Consumer<String> progressCallback)
            throws TranslationException {

        List<String> verified = new ArrayList<>();
        int total = originals.size();

        for (int i = 0; i < total; i++) {
            if (cancelled) {
                verified.add(translations.get(i));
                continue;
            }

            String original = originals.get(i);
            String translated = translations.get(i);

            // Build verification prompt
            String verificationText = String.format(
                    "Verify this subtitle translation. Original (%s): \"%s\" | " +
                    "Translation (%s): \"%s\" | " +
                    "If the translation is accurate, output it as-is. " +
                    "If it has errors, output the corrected translation only.",
                    sourceLang, original, targetLang, translated);

            try {
                String result = verifier.translate(verificationText, sourceLang, targetLang);
                verified.add(result != null && !result.isBlank() ? result : translated);
            } catch (TranslationException e) {
                // Verification failed - keep original translation
                log.warn("Verification failed for entry {}, keeping original translation", i, e);
                verified.add(translated);
            }

            if ((i + 1) % 5 == 0 || i == total - 1) {
                progressCallback.accept(String.format("Verified %d/%d subtitles...", i + 1, total));
            }
        }

        return verified;
    }

    /**
     * Splits texts into batches respecting both count and character limits.
     */
    private List<List<String>> buildBatches(List<String> texts, int maxBatchSize, int maxChars) {
        List<List<String>> batches = new ArrayList<>();
        List<String> currentBatch = new ArrayList<>();
        int currentChars = 0;

        for (String text : texts) {
            int textLen = text.length();

            if (!currentBatch.isEmpty() &&
                    (currentBatch.size() >= maxBatchSize || currentChars + textLen > maxChars)) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentChars = 0;
            }

            currentBatch.add(text);
            currentChars += textLen;
        }

        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }

    /**
     * Cancels any ongoing translation work.
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Returns whether the orchestrator has been cancelled.
     */
    public boolean isCancelled() {
        return cancelled;
    }
}
