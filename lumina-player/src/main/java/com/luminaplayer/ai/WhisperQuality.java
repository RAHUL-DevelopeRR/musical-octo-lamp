package com.luminaplayer.ai;

/**
 * Quality presets for Whisper transcription.
 *
 * <p>For the <b>Faster-Whisper sidecar</b> (preferred mode) these values control the
 * {@code quality} string sent in the JSON request; the sidecar maps them to
 * beam_size and a temperature fallback chain (see {@code whisper_server.py}).
 *
 * <p>For the <b>whisper.cpp CLI fallback</b> {@link #beamSize()}, {@link #bestOf()},
 * {@link #entropyThreshold()} and {@link #temperature()} are used directly as CLI flags.
 *
 * <table>
 *   <tr><th>Preset</th><th>beam_size</th><th>Temperature chain</th><th>Use-case</th></tr>
 *   <tr><td>INSTANT</td><td>1</td><td>0.0 only</td><td>Phase-1 micro-chunk — instant display</td></tr>
 *   <tr><td>FAST</td><td>1</td><td>0.0, 0.2</td><td>Background chunks on slow hardware</td></tr>
 *   <tr><td>BALANCED</td><td>3</td><td>0.0, 0.2, 0.4</td><td>Default — good speed/accuracy trade-off</td></tr>
 *   <tr><td>BEST</td><td>5</td><td>0.0 … 0.8</td><td>Maximum accuracy, slowest</td></tr>
 * </table>
 */
public enum WhisperQuality {

    // name        beamSize  bestOf  entropyThreshold  temperature  description
    INSTANT(          1,       1,        3.2,            0.0,   "Instant — greedy, no fallback"),
    FAST(             1,       1,        2.8,            0.0,   "Fast — greedy with light fallback"),
    BALANCED(         3,       1,        2.4,            0.0,   "Balanced — beam-3 + temp fallback"),
    BEST(             5,       1,        2.0,            0.0,   "Best — beam-5, full temp fallback");

    private final int    beamSize;
    private final int    bestOf;
    private final double entropyThreshold;
    private final double temperature;     // initial temperature (fallback handled by sidecar / --temperature-inc)
    private final String description;

    WhisperQuality(int beamSize, int bestOf, double entropyThreshold,
                   double temperature, String description) {
        this.beamSize          = beamSize;
        this.bestOf            = bestOf;
        this.entropyThreshold  = entropyThreshold;
        this.temperature       = temperature;
        this.description       = description;
    }

    public int    beamSize()          { return beamSize; }
    public int    bestOf()            { return bestOf; }
    public double entropyThreshold()  { return entropyThreshold; }
    public double temperature()       { return temperature; }
    public String description()       { return description; }

    @Override
    public String toString() {
        return name().charAt(0) + name().substring(1).toLowerCase() + " — " + description;
    }
}
