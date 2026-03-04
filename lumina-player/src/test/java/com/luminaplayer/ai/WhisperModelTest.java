package com.luminaplayer.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WhisperModel enum.
 */
class WhisperModelTest {

    @Test
    void allModelsHaveUniqueFileNames() {
        WhisperModel[] models = WhisperModel.values();
        for (int i = 0; i < models.length; i++) {
            for (int j = i + 1; j < models.length; j++) {
                assertNotEquals(models[i].fileName(), models[j].fileName(),
                    "Duplicate file name: " + models[i].fileName());
            }
        }
    }

    @Test
    void allModelsHavePositiveSize() {
        for (WhisperModel model : WhisperModel.values()) {
            assertTrue(model.sizeMb() > 0, "Model " + model.modelName() + " has non-positive size");
        }
    }

    @Test
    void tinyModelIsSmallest() {
        int tinySize = WhisperModel.TINY.sizeMb();
        for (WhisperModel model : WhisperModel.values()) {
            if (model != WhisperModel.TINY) {
                assertTrue(model.sizeMb() > tinySize,
                    model.modelName() + " should be larger than tiny");
            }
        }
    }

    @Test
    void modelFileNamesEndWithBin() {
        for (WhisperModel model : WhisperModel.values()) {
            assertTrue(model.fileName().endsWith(".bin"),
                "Model " + model.modelName() + " filename should end with .bin");
        }
    }

    @Test
    void modelToStringIncludesName() {
        for (WhisperModel model : WhisperModel.values()) {
            String str = model.toString();
            assertTrue(str.contains(model.modelName()),
                "toString should include model name");
        }
    }
}
