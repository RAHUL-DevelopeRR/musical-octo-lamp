package com.luminaplayer.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WhisperLanguage enum.
 */
class WhisperLanguageTest {

    @Test
    void autoDetectCodeIsAuto() {
        assertEquals("auto", WhisperLanguage.AUTO.code());
    }

    @Test
    void englishCodeIsEn() {
        assertEquals("en", WhisperLanguage.ENGLISH.code());
    }

    @Test
    void allLanguagesHaveNonEmptyCode() {
        for (WhisperLanguage lang : WhisperLanguage.values()) {
            assertNotNull(lang.code());
            assertFalse(lang.code().isEmpty(), "Language code should not be empty: " + lang);
        }
    }

    @Test
    void allLanguagesHaveDisplayName() {
        for (WhisperLanguage lang : WhisperLanguage.values()) {
            assertNotNull(lang.displayName());
            assertFalse(lang.displayName().isEmpty(), "Display name should not be empty: " + lang);
        }
    }

    @Test
    void toStringReturnsDisplayName() {
        for (WhisperLanguage lang : WhisperLanguage.values()) {
            assertEquals(lang.displayName(), lang.toString());
        }
    }

    @Test
    void allCodesAreUnique() {
        WhisperLanguage[] languages = WhisperLanguage.values();
        for (int i = 0; i < languages.length; i++) {
            for (int j = i + 1; j < languages.length; j++) {
                assertNotEquals(languages[i].code(), languages[j].code(),
                    "Duplicate code: " + languages[i].code());
            }
        }
    }

    @Test
    void languageCodesAreShort() {
        for (WhisperLanguage lang : WhisperLanguage.values()) {
            assertTrue(lang.code().length() <= 4,
                "Language code too long: " + lang.code());
        }
    }
}
