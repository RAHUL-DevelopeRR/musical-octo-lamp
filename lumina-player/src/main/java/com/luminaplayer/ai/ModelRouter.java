package com.luminaplayer.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Routes detected languages to the optimal Whisper model size.
 * Instead of using a single large model for all languages, this selects
 * the smallest model that provides good accuracy for each language.
 *
 * Routing rules are based on empirical observations:
 * - English/European languages work well with smaller models
 * - CJK and complex scripts benefit from larger models
 * - South Asian languages need at least SMALL for script accuracy
 */
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private static final Map<WhisperLanguage, WhisperModel> LANGUAGE_MODEL_MAP = new EnumMap<>(WhisperLanguage.class);

    static {
        // English and close European languages: SMALL is sufficient
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.ENGLISH, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.SPANISH, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.FRENCH, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.GERMAN, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.ITALIAN, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.PORTUGUESE, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.DUTCH, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.SWEDISH, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.NORWEGIAN, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.DANISH, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.FINNISH, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.CATALAN, WhisperModel.SMALL);

        // Slavic/Eastern European: SMALL is usually fine
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.RUSSIAN, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.POLISH, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.CZECH, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.UKRAINIAN, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.ROMANIAN, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.HUNGARIAN, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.GREEK, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.TURKISH, WhisperModel.SMALL);

        // CJK: MEDIUM or LARGE for accurate character recognition
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.JAPANESE, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.CHINESE, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.KOREAN, WhisperModel.MEDIUM);

        // South Asian: MEDIUM for accurate script handling
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.HINDI, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.TAMIL, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.TELUGU, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.BENGALI, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.KANNADA, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.MALAYALAM, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.MARATHI, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.GUJARATI, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.PUNJABI, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.URDU, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.NEPALI, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.SINHALA, WhisperModel.MEDIUM);

        // Arabic/Persian/Hebrew: MEDIUM for RTL scripts
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.ARABIC, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.PERSIAN, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.HEBREW, WhisperModel.MEDIUM);

        // Southeast Asian
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.THAI, WhisperModel.MEDIUM);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.VIETNAMESE, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.INDONESIAN, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.MALAY, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.FILIPINO, WhisperModel.SMALL);
        LANGUAGE_MODEL_MAP.put(WhisperLanguage.BURMESE, WhisperModel.MEDIUM);
    }

    private ModelRouter() {
        // Utility class
    }

    /**
     * Returns the recommended model for a language, constrained by what's available
     * and never going below the user's selected model.
     *
     * @param language        detected or selected language
     * @param userModel       the model the user explicitly selected (minimum)
     * @param whisperEngine   engine to check model availability
     * @return the best model to use - at least as good as userModel
     */
    public static WhisperModel route(WhisperLanguage language, WhisperModel userModel,
                                     WhisperEngine whisperEngine) {
        if (language == null || language == WhisperLanguage.AUTO) {
            return userModel; // Can't route without knowing the language
        }

        WhisperModel recommended = LANGUAGE_MODEL_MAP.getOrDefault(language, WhisperModel.SMALL);

        // Never downgrade below user's selection
        WhisperModel target = recommended.ordinal() >= userModel.ordinal()
            ? recommended : userModel;

        // Find closest available model at or above the target
        WhisperModel[] models = WhisperModel.values();
        for (int i = target.ordinal(); i < models.length; i++) {
            if (whisperEngine.isModelAvailable(models[i])) {
                if (models[i] != userModel) {
                    log.info("Model routing: {} -> {} (recommended: {}, user: {})",
                        language.displayName(), models[i].modelName(),
                        recommended.modelName(), userModel.modelName());
                }
                return models[i];
            }
        }

        // Fall back to user's model if nothing better is available
        return userModel;
    }

    /**
     * Resolves a detected language code string (from Whisper auto-detect output)
     * to a WhisperLanguage enum value.
     *
     * @param detectedCode the language code string (e.g., "en", "ja", "japanese")
     * @return the matching WhisperLanguage, or null if not found
     */
    public static WhisperLanguage resolveLanguage(String detectedCode) {
        if (detectedCode == null || detectedCode.isBlank()) {
            return null;
        }
        String code = detectedCode.trim().toLowerCase();
        for (WhisperLanguage lang : WhisperLanguage.values()) {
            if (lang == WhisperLanguage.AUTO) continue;
            if (lang.code().equalsIgnoreCase(code)
                    || lang.displayName().equalsIgnoreCase(code)) {
                return lang;
            }
        }
        return null;
    }
}
