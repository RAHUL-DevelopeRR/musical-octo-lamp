package com.luminaplayer.ai;

/**
 * Available Whisper model sizes for speech-to-text transcription.
 * Larger models provide better accuracy but require more resources.
 */
public enum WhisperModel {

    TINY("tiny", "ggml-tiny.bin", 75, "Fastest, lowest accuracy"),
    BASE("base", "ggml-base.bin", 142, "Fast, good for clear audio"),
    SMALL("small", "ggml-small.bin", 466, "Balanced speed and accuracy"),
    MEDIUM("medium", "ggml-medium.bin", 1500, "High accuracy, slower"),
    LARGE("large", "ggml-large-v3.bin", 2900, "Best accuracy, slowest");

    private static final String HF_BASE_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final String name;
    private final String fileName;
    private final int sizeMb;
    private final String description;

    WhisperModel(String name, String fileName, int sizeMb, String description) {
        this.name = name;
        this.fileName = fileName;
        this.sizeMb = sizeMb;
        this.description = description;
    }

    public String modelName() { return name; }
    public String fileName() { return fileName; }
    public int sizeMb() { return sizeMb; }
    public String description() { return description; }

    public String downloadUrl() {
        return HF_BASE_URL + fileName;
    }

    @Override
    public String toString() {
        return String.format("%s (%dMB) - %s", name, sizeMb, description);
    }
}
