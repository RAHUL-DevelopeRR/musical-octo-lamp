package com.luminaplayer.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WhisperEngine configuration and path resolution.
 */
class WhisperEngineTest {

    @Test
    void defaultEngineCreatedWithoutError() {
        WhisperEngine engine = new WhisperEngine();
        // Should not throw - may or may not find a binary
        assertNotNull(engine);
    }

    @Test
    void setCustomBinaryPath(@TempDir Path tempDir) throws Exception {
        WhisperEngine engine = new WhisperEngine();

        Path fakeBinary = tempDir.resolve("whisper-cli.exe");
        Files.writeString(fakeBinary, "fake");

        engine.setWhisperBinaryPath(fakeBinary);
        assertEquals(fakeBinary.toString(), engine.getBinaryPath());
    }

    @Test
    void setModelsDirectory(@TempDir Path tempDir) {
        WhisperEngine engine = new WhisperEngine();
        engine.setModelsDirectory(tempDir);
        assertEquals(tempDir, engine.getModelsDirectory());
    }

    @Test
    void isModelAvailableReturnsFalseForMissing(@TempDir Path tempDir) {
        WhisperEngine engine = new WhisperEngine();
        engine.setModelsDirectory(tempDir);

        assertFalse(engine.isModelAvailable(WhisperModel.BASE));
    }

    @Test
    void isModelAvailableReturnsTrueWhenPresent(@TempDir Path tempDir) throws Exception {
        WhisperEngine engine = new WhisperEngine();
        engine.setModelsDirectory(tempDir);

        // Create a fake model file
        Files.writeString(tempDir.resolve(WhisperModel.BASE.fileName()), "fake model");

        assertTrue(engine.isModelAvailable(WhisperModel.BASE));
    }

    @Test
    void getModelPathReturnsCorrectPath(@TempDir Path tempDir) {
        WhisperEngine engine = new WhisperEngine();
        engine.setModelsDirectory(tempDir);

        Path expected = tempDir.resolve(WhisperModel.TINY.fileName());
        assertEquals(expected, engine.getModelPath(WhisperModel.TINY));
    }
}
