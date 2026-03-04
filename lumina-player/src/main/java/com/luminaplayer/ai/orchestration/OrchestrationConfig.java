package com.luminaplayer.ai.orchestration;

/**
 * Configures how the multi-model orchestration pipeline operates.
 */
public class OrchestrationConfig {

    /**
     * The orchestration mode determines how subtitle generation works.
     */
    public enum Mode {
        /** Single-pass: Whisper transcribes and optionally translates (original behavior). */
        SINGLE_PASS,
        /** Multi-model: Whisper transcribes in native language, then a separate model translates. */
        MULTI_MODEL,
        /** Multi-model with verification: adds a verification pass using a second model. */
        MULTI_MODEL_VERIFIED
    }

    private Mode mode = Mode.SINGLE_PASS;
    private TranslationProvider translationProvider;
    private TranslationProvider verificationProvider;
    private boolean keepOriginalText = true;
    private String targetLanguage = "en";
    private double confidenceThreshold = 0.7;
    private boolean parallelTranslation = true;
    private int translationBatchSize = 10;

    public OrchestrationConfig() {}

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public TranslationProvider getTranslationProvider() { return translationProvider; }
    public void setTranslationProvider(TranslationProvider provider) { this.translationProvider = provider; }

    public TranslationProvider getVerificationProvider() { return verificationProvider; }
    public void setVerificationProvider(TranslationProvider provider) { this.verificationProvider = provider; }

    /** If true, the original native-language text is preserved alongside the translation. */
    public boolean isKeepOriginalText() { return keepOriginalText; }
    public void setKeepOriginalText(boolean keep) { this.keepOriginalText = keep; }

    public String getTargetLanguage() { return targetLanguage; }
    public void setTargetLanguage(String lang) { this.targetLanguage = lang; }

    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double threshold) { this.confidenceThreshold = threshold; }

    public boolean isParallelTranslation() { return parallelTranslation; }
    public void setParallelTranslation(boolean parallel) { this.parallelTranslation = parallel; }

    public int getTranslationBatchSize() { return translationBatchSize; }
    public void setTranslationBatchSize(int size) { this.translationBatchSize = size; }

    /**
     * Returns whether multi-model orchestration is active.
     */
    public boolean isMultiModel() {
        return mode != Mode.SINGLE_PASS && translationProvider != null;
    }

    @Override
    public String toString() {
        return String.format("Orchestration[mode=%s, provider=%s, target=%s]",
                mode, translationProvider != null ? translationProvider.getName() : "none",
                targetLanguage);
    }
}
