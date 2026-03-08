package com.luminaplayer.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Multi-feature Voice Activity Detection for 16kHz mono 16-bit PCM WAV files.
 * Uses three complementary signals to distinguish speech from silence/music:
 *
 * 1. RMS Energy: detects audio presence (filters silence)
 * 2. Zero-Crossing Rate: speech has moderate ZCR; music/noise tends to have very high or very low ZCR
 * 3. Spectral Energy Ratio: speech concentrates energy in 300Hz-3kHz;
 *    music spreads energy more evenly across the spectrum
 *
 * A chunk is classified as speech if energy is above threshold AND at least one of
 * the speech-specific features (ZCR range or spectral ratio) indicates speech.
 */
public class SimpleVAD {

    private static final Logger log = LoggerFactory.getLogger(SimpleVAD.class);

    /** WAV header size in bytes */
    private static final int WAV_HEADER_SIZE = 44;

    /** Sample rate of expected input audio */
    private static final int SAMPLE_RATE = 16000;

    /** Analysis frame size in samples (~20ms at 16kHz) */
    private static final int FRAME_SIZE = 320;

    /** Minimum zero-crossing rate for speech (speech is typically 0.02-0.15) */
    private static final double ZCR_SPEECH_MIN = 0.01;

    /** Maximum zero-crossing rate for speech (above this is likely noise/fricatives) */
    private static final double ZCR_SPEECH_MAX = 0.25;

    /** Minimum fraction of frames that should look like speech */
    private static final double SPEECH_FRAME_RATIO = 0.10;

    /** Minimum spectral energy ratio in speech band for speech detection */
    private static final double SPECTRAL_SPEECH_RATIO = 0.3;

    private SimpleVAD() {
        // Utility class
    }

    /**
     * Checks if a 16kHz mono WAV file contains speech using multi-feature analysis.
     *
     * @param wavFile           16kHz mono 16-bit PCM WAV file
     * @param rmsThreshold      RMS energy threshold (typical: 50-200)
     * @return true if the audio likely contains speech, false if silent or non-speech
     */
    public static boolean hasSpeech(File wavFile, double rmsThreshold) throws IOException {
        if (wavFile == null || !wavFile.exists() || wavFile.length() <= WAV_HEADER_SIZE) {
            return false;
        }

        long fileLength = wavFile.length();
        long dataLength = fileLength - WAV_HEADER_SIZE;
        long numSamples = dataLength / 2; // 16-bit = 2 bytes per sample

        if (numSamples <= 0) {
            return false;
        }

        // Read all PCM samples
        short[] samples = readPcmSamples(wavFile, numSamples);
        if (samples.length == 0) {
            return false;
        }

        // Feature 1: Global RMS energy (quick reject for silence)
        double rms = computeRms(samples);
        if (rms < rmsThreshold) {
            log.debug("VAD: RMS={} below threshold={}, silent", f(rms), f(rmsThreshold));
            return false;
        }

        // Feature 2: Frame-level analysis with ZCR and energy
        int totalFrames = samples.length / FRAME_SIZE;
        if (totalFrames == 0) {
            // Too short for frame analysis, fall back to RMS only
            return true;
        }

        int speechFrames = 0;
        for (int i = 0; i < totalFrames; i++) {
            int offset = i * FRAME_SIZE;
            double frameRms = computeFrameRms(samples, offset, FRAME_SIZE);
            double zcr = computeZeroCrossingRate(samples, offset, FRAME_SIZE);

            // A frame looks like speech if it has energy AND moderate ZCR
            if (frameRms >= rmsThreshold * 0.5
                    && zcr >= ZCR_SPEECH_MIN
                    && zcr <= ZCR_SPEECH_MAX) {
                speechFrames++;
            }
        }

        double speechRatio = (double) speechFrames / totalFrames;

        // Feature 3: Spectral energy ratio in speech band (300Hz-3kHz)
        // Uses a simplified DFT on a representative segment
        double spectralRatio = computeSpeechBandRatio(samples);

        boolean hasSpeech = speechRatio >= SPEECH_FRAME_RATIO
            || spectralRatio >= SPECTRAL_SPEECH_RATIO;

        log.debug("VAD: {} samples, RMS={}, speechFrameRatio={}, spectralRatio={}, hasSpeech={}",
            samples.length, f(rms), f(speechRatio), f(spectralRatio), hasSpeech);

        return hasSpeech;
    }

    /**
     * Reads raw 16-bit PCM samples from a WAV file, skipping the header.
     */
    private static short[] readPcmSamples(File wavFile, long numSamples) throws IOException {
        // Cap at 5 minutes of audio to avoid excessive memory use
        int maxSamples = Math.min((int) numSamples, SAMPLE_RATE * 300);
        short[] samples = new short[maxSamples];
        int samplesRead = 0;

        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            raf.seek(WAV_HEADER_SIZE);

            byte[] buffer = new byte[8192]; // 4096 samples at a time
            int bytesRead;
            while ((bytesRead = raf.read(buffer)) > 0 && samplesRead < maxSamples) {
                int count = Math.min(bytesRead / 2, maxSamples - samplesRead);
                for (int i = 0; i < count; i++) {
                    int low = buffer[i * 2] & 0xFF;
                    int high = buffer[i * 2 + 1];
                    samples[samplesRead++] = (short) (low | (high << 8));
                }
            }
        }

        if (samplesRead < maxSamples) {
            short[] trimmed = new short[samplesRead];
            System.arraycopy(samples, 0, trimmed, 0, samplesRead);
            return trimmed;
        }
        return samples;
    }

    /** Computes global RMS energy. */
    private static double computeRms(short[] samples) {
        double sumSquared = 0;
        for (short s : samples) {
            sumSquared += (double) s * s;
        }
        return Math.sqrt(sumSquared / samples.length);
    }

    /** Computes RMS energy for a frame. */
    private static double computeFrameRms(short[] samples, int offset, int length) {
        double sumSquared = 0;
        int end = Math.min(offset + length, samples.length);
        for (int i = offset; i < end; i++) {
            sumSquared += (double) samples[i] * samples[i];
        }
        return Math.sqrt(sumSquared / (end - offset));
    }

    /** Computes zero-crossing rate for a frame (0.0 to 1.0). */
    private static double computeZeroCrossingRate(short[] samples, int offset, int length) {
        int end = Math.min(offset + length, samples.length);
        int crossings = 0;
        for (int i = offset + 1; i < end; i++) {
            if ((samples[i] >= 0 && samples[i - 1] < 0)
                    || (samples[i] < 0 && samples[i - 1] >= 0)) {
                crossings++;
            }
        }
        return (double) crossings / (end - offset - 1);
    }

    /**
     * Computes the ratio of energy in the speech frequency band (300Hz-3kHz)
     * vs total energy, using a simplified Goertzel-based approach on a
     * representative segment of audio.
     *
     * For 16kHz sample rate: bin resolution = sampleRate / N
     * We use N=512 giving ~31Hz resolution. Speech band = bins 10-96.
     */
    private static double computeSpeechBandRatio(short[] samples) {
        int N = 512; // FFT size
        if (samples.length < N) {
            return 0.5; // Too short to analyze, assume it might be speech
        }

        // Analyze up to 10 segments spread across the audio
        int segments = Math.min(10, samples.length / N);
        int stride = samples.length / segments;

        double totalSpeechEnergy = 0;
        double totalEnergy = 0;

        // Speech band: 300Hz to 3000Hz
        // At 16kHz with N=512: bin = freq * N / sampleRate
        int binLow = (int) (300.0 * N / SAMPLE_RATE);   // ~10
        int binHigh = (int) (3000.0 * N / SAMPLE_RATE);  // ~96

        for (int seg = 0; seg < segments; seg++) {
            int offset = seg * stride;
            if (offset + N > samples.length) break;

            // Compute power spectrum using Goertzel algorithm for key bins
            double segSpeechEnergy = 0;
            double segTotalEnergy = 0;

            for (int k = 1; k < N / 2; k++) {
                double power = goertzelPower(samples, offset, N, k);
                segTotalEnergy += power;
                if (k >= binLow && k <= binHigh) {
                    segSpeechEnergy += power;
                }
            }

            totalSpeechEnergy += segSpeechEnergy;
            totalEnergy += segTotalEnergy;
        }

        return totalEnergy > 0 ? totalSpeechEnergy / totalEnergy : 0;
    }

    /**
     * Goertzel algorithm: efficiently computes the power at a single DFT bin.
     * More efficient than full FFT when only a few bins are needed.
     */
    private static double goertzelPower(short[] samples, int offset, int N, int k) {
        double w = 2.0 * Math.PI * k / N;
        double coeff = 2.0 * Math.cos(w);
        double s0 = 0, s1 = 0, s2 = 0;

        for (int i = 0; i < N; i++) {
            s0 = samples[offset + i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        // Power = s1^2 + s2^2 - coeff * s1 * s2
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    private static String f(double value) {
        return String.format("%.2f", value);
    }
}
