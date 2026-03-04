package com.luminaplayer.ai.orchestration;

import java.util.List;

/**
 * Interface for translation providers in the multi-model orchestration pipeline.
 * Each provider translates text from a source language to a target language
 * using its own backend (LibreTranslate, Ollama LLM, external API, etc.).
 */
public interface TranslationProvider {

    /**
     * Returns the display name of this provider (e.g., "LibreTranslate", "Ollama").
     */
    String getName();

    /**
     * Returns a short description of this provider's capabilities.
     */
    String getDescription();

    /**
     * Checks if this provider is available and ready to use.
     * Should verify connectivity, model availability, etc.
     */
    boolean isAvailable();

    /**
     * Returns a human-readable status message about availability.
     */
    String getAvailabilityStatus();

    /**
     * Translates a single text string from source to target language.
     *
     * @param text           the text to translate
     * @param sourceLang     source language code (e.g., "ja", "ta", "zh")
     * @param targetLang     target language code (e.g., "en")
     * @return the translated text
     * @throws TranslationException if translation fails
     */
    String translate(String text, String sourceLang, String targetLang)
            throws TranslationException;

    /**
     * Batch-translates multiple text strings. Default implementation
     * translates one-by-one, but providers can override for efficiency.
     *
     * @param texts          list of texts to translate
     * @param sourceLang     source language code
     * @param targetLang     target language code
     * @return list of translated texts (same order as input)
     * @throws TranslationException if translation fails
     */
    default List<String> translateBatch(List<String> texts, String sourceLang,
                                         String targetLang) throws TranslationException {
        return texts.stream()
                .map(text -> {
                    try {
                        return translate(text, sourceLang, targetLang);
                    } catch (TranslationException e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
    }

    /**
     * Returns the maximum characters per request this provider handles well.
     * Used to batch subtitle entries for efficiency.
     */
    default int getMaxCharsPerRequest() {
        return 5000;
    }
}
