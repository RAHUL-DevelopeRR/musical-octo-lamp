package com.luminaplayer.ai;

/**
 * Quality presets for Whisper transcription.
 * Controls beam search, sampling, entropy filtering, and temperature parameters.
 */
public enum WhisperQuality {

    FAST(1, 2, 2.8, 0.0, "Fast - greedy decoding, lower accuracy"),
    BALANCED(5, 5, 2.4, 0.0, "Balanced - beam search for good accuracy"),
    BEST(8, 8, 2.0, 0.0, "Best - large beam, highest accuracy, slowest");

    private final int beamSize;
    private final int bestOf;
    private final double entropyThreshold;
    private final double temperature;
    private final String description;

    WhisperQuality(int beamSize, int bestOf, double entropyThreshold,
                   double temperature, String description) {
        this.beamSize = beamSize;
        this.bestOf = bestOf;
        this.entropyThreshold = entropyThreshold;
        this.temperature = temperature;
        this.description = description;
    }

    public int beamSize() { return beamSize; }
    public int bestOf() { return bestOf; }
    public double entropyThreshold() { return entropyThreshold; }
    public double temperature() { return temperature; }
    public String description() { return description; }

    @Override
    public String toString() {
        return name().charAt(0) + name().substring(1).toLowerCase() + " - " + description;
    }
}
