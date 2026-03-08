package com.luminaplayer.ai.training;

import com.luminaplayer.subtitle.DsrtCue;
import com.luminaplayer.subtitle.DsrtFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collects bilingual parallel training data from completed DSRT files.
 * Automatically detects the language pair from the DSRT metadata and cue content,
 * then appends deduplicated pairs to language-specific TSV corpus files.
 *
 * Corpus files are stored under ~/.luminaplayer/training-data/{src}-{tgt}.tsv
 * (e.g., ja-en.tsv, hi-en.tsv, zh-en.tsv).
 */
public final class TrainingDataCollector {

    private static final Logger log = LoggerFactory.getLogger(TrainingDataCollector.class);

    /** Detects non-Latin scripts to identify the native language text in brackets.
     *  Uses Unicode script properties (\p{IsScriptName}) which are valid in Java 17. */
    private static final Pattern NON_LATIN_PATTERN = Pattern.compile(
        "[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}" +
        "\\p{IsHangul}\\p{IsThai}\\p{IsArabic}\\p{IsDevanagari}" +
        "\\p{IsTamil}\\p{IsTelugu}\\p{IsBengali}\\p{IsKannada}" +
        "\\p{IsMalayalam}\\p{IsGujarati}\\p{IsGurmukhi}" +
        "\\p{IsCyrillic}\\p{IsGreek}\\p{IsHebrew}\\p{IsGeorgian}" +
        "\\p{IsMyanmar}\\p{IsSinhala}\\p{IsLao}\\p{IsKhmer}]");

    private TrainingDataCollector() {
    }

    /**
     * Collects bilingual training pairs from a DSRT file for any language pair.
     * Uses the DSRT's language metadata and translate flag to determine the pair.
     *
     * @param dsrtFile the completed DSRT file with bilingual cues
     * @return the number of new pairs added
     */
    public static synchronized int collectTrainingPairs(DsrtFile dsrtFile) {
        if (dsrtFile == null) {
            return 0;
        }

        String sourceLanguage = dsrtFile.getLanguageCode();
        if (sourceLanguage == null || sourceLanguage.isBlank()
                || "auto".equalsIgnoreCase(sourceLanguage)
                || "en".equalsIgnoreCase(sourceLanguage)) {
            // Can't determine source language, or it's English (no bilingual pair)
            return 0;
        }

        // Target is always English for now (Whisper translate outputs English)
        String targetLanguage = "en";

        try {
            Path dataDir = Path.of(System.getProperty("user.home"), ".luminaplayer", "training-data");
            Files.createDirectories(dataDir);

            String corpusName = sourceLanguage + "-" + targetLanguage + ".tsv";
            Path corpusFile = dataDir.resolve(corpusName);
            Set<String> existing = loadExistingPairs(corpusFile);

            StringBuilder toAppend = new StringBuilder();
            int added = 0;

            List<DsrtCue> cues = dsrtFile.getAllCues();
            for (DsrtCue cue : cues) {
                if (cue == null || cue.text() == null) {
                    continue;
                }
                String text = cue.text().trim();

                // Parse bilingual format: "translated text\n[native text]"
                int marker = text.indexOf("\n[");
                if (marker <= 0 || !text.endsWith("]")) {
                    continue;
                }

                String translatedText = text.substring(0, marker).trim();
                String nativeText = text.substring(marker + 2, text.length() - 1).trim();
                if (translatedText.isBlank() || nativeText.isBlank()) {
                    continue;
                }

                // Determine which is native and which is translated.
                // The native text (in brackets) should contain non-Latin characters
                // for non-Latin languages; the translated text should be English.
                String source;
                String target;
                if (NON_LATIN_PATTERN.matcher(nativeText).find()) {
                    source = nativeText;
                    target = translatedText;
                } else if (NON_LATIN_PATTERN.matcher(translatedText).find()) {
                    // Unusual: native text is in Latin but translated is non-Latin
                    source = translatedText;
                    target = nativeText;
                } else {
                    // Both Latin-script: use position (bracketed = native)
                    source = nativeText;
                    target = translatedText;
                }

                String key = source + "\t" + target;
                if (existing.add(key)) {
                    toAppend.append(key).append("\n");
                    added++;
                }
            }

            if (added > 0) {
                Files.writeString(corpusFile, toAppend.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.info("Training corpus updated: +{} {} pairs (total {}): {}",
                    added, sourceLanguage + "-" + targetLanguage, existing.size(), corpusFile);
            }

            return added;
        } catch (IOException e) {
            log.warn("Failed to collect training pairs", e);
            return 0;
        }
    }

    /**
     * Legacy method: collects JA-EN pairs specifically.
     * Delegates to the generic collectTrainingPairs when the DSRT has language metadata,
     * otherwise falls back to JA-EN assumption.
     */
    public static synchronized int collectJaEnPairs(DsrtFile dsrtFile) {
        return collectTrainingPairs(dsrtFile);
    }

    /**
     * Returns the corpus path for a specific language pair.
     */
    public static Path getCorpusPath(String sourceLanguage, String targetLanguage) {
        return Path.of(System.getProperty("user.home"), ".luminaplayer", "training-data",
            sourceLanguage + "-" + targetLanguage + ".tsv");
    }

    /**
     * Returns the default JA-EN corpus path (backwards-compatible).
     */
    public static Path getCorpusPath() {
        return getCorpusPath("ja", "en");
    }

    private static Set<String> loadExistingPairs(Path corpusFile) throws IOException {
        Set<String> existing = new HashSet<>();
        if (!Files.exists(corpusFile)) {
            return existing;
        }

        for (String line : Files.readAllLines(corpusFile, StandardCharsets.UTF_8)) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.isBlank()) {
                existing.add(trimmed);
            }
        }
        return existing;
    }
}
