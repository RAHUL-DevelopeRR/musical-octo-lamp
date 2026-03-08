package com.luminaplayer.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Wraps the whisper.cpp CLI executable for offline speech-to-text transcription.
 * Expects the whisper binary (main or whisper-cli) to be available on the system.
 */
public class WhisperEngine {

    private static final Logger log = LoggerFactory.getLogger(WhisperEngine.class);

    private Path whisperBinaryPath;
    private Path modelsDirectory;
    private final CopyOnWriteArrayList<Process> activeProcesses = new CopyOnWriteArrayList<>();
    private int threadCountOverride = 0; // 0 = auto-detect

    public WhisperEngine() {
        this.whisperBinaryPath = findWhisperBinary();
        this.modelsDirectory = resolveModelsDirectory();
    }

    public void setWhisperBinaryPath(Path path) {
        this.whisperBinaryPath = path;
    }

    public void setModelsDirectory(Path dir) {
        this.modelsDirectory = dir;
    }

    /**
     * Sets the thread count override for whisper.cpp's -t flag.
     * Use 0 (default) for automatic detection (availableProcessors - 1).
     */
    public void setThreadCount(int threadCount) {
        this.threadCountOverride = threadCount;
    }

    public boolean isAvailable() {
        return whisperBinaryPath != null && Files.exists(whisperBinaryPath);
    }

    public String getBinaryPath() {
        return whisperBinaryPath != null ? whisperBinaryPath.toString() : null;
    }

    public Path getModelsDirectory() {
        return modelsDirectory;
    }

    /**
     * Checks if a specific model file exists in the models directory.
     */
    public boolean isModelAvailable(WhisperModel model) {
        if (modelsDirectory == null) return false;
        return Files.exists(modelsDirectory.resolve(model.fileName()));
    }

    /**
     * Returns the path to a model file.
     */
    public Path getModelPath(WhisperModel model) {
        if (modelsDirectory == null) return null;
        return modelsDirectory.resolve(model.fileName());
    }

    /**
     * Runs Whisper transcription on a WAV file and produces an SRT subtitle file.
     *
     * @param wavFile        input audio file (16kHz mono WAV)
     * @param outputSrt      output SRT file path (without extension - whisper adds .srt)
     * @param model          Whisper model to use
     * @param language       target language (or AUTO for detection)
     * @param translate      if true, translate to English
     * @param progressUpdate callback for progress messages
     * @return the transcription result with SRT file and detected language, or null on failure
     */
    public TranscriptionResult transcribe(File wavFile, File outputSrt, WhisperModel model,
                           WhisperLanguage language, boolean translate,
                           Consumer<String> progressUpdate)
            throws IOException, InterruptedException {
        return transcribe(wavFile, outputSrt, model, language, translate,
            WhisperQuality.BALANCED, progressUpdate);
    }

    /**
     * Runs Whisper transcription with configurable quality level.
     *
     * @param wavFile        input audio file (16kHz mono WAV)
     * @param outputSrt      output SRT file path (without extension - whisper adds .srt)
     * @param model          Whisper model to use
     * @param language       target language (or AUTO for detection)
     * @param translate      if true, translate to English
     * @param quality        quality preset controlling beam search and sampling
     * @param progressUpdate callback for progress messages
     * @return the transcription result with SRT file and detected language, or null on failure
     */
    public TranscriptionResult transcribe(File wavFile, File outputSrt, WhisperModel model,
                           WhisperLanguage language, boolean translate,
                           WhisperQuality quality,
                           Consumer<String> progressUpdate)
            throws IOException, InterruptedException {

        if (whisperBinaryPath == null || !Files.exists(whisperBinaryPath)) {
            throw new IOException("Whisper binary not found. Download whisper.cpp and set its path.");
        }

        Path modelPath = getModelPath(model);
        if (modelPath == null || !Files.exists(modelPath)) {
            throw new IOException("Model not found: " + model.fileName() +
                ". Download it to: " + modelsDirectory);
        }

        // Build the output base name (whisper appends .srt automatically)
        String outputBase = outputSrt.getAbsolutePath();
        if (outputBase.endsWith(".srt")) {
            outputBase = outputBase.substring(0, outputBase.length() - 4);
        }

        List<String> command = new ArrayList<>();
        command.add(whisperBinaryPath.toString());
        command.add("-m");
        command.add(modelPath.toString());
        command.add("-f");
        command.add(wavFile.getAbsolutePath());
        command.add("-osrt");               // output SRT format
        command.add("-of");
        command.add(outputBase);            // output file base name

        if (language != WhisperLanguage.AUTO) {
            command.add("-l");
            command.add(language.code());
        }

        if (translate) {
            command.add("-tr");             // translate to English
        }

        // Beam search for better accuracy (greedy is fast but less accurate)
        command.add("-bs");
        command.add(String.valueOf(quality.beamSize()));

        // Best-of sampling (used when beam size <= 1)
        command.add("-bo");
        command.add(String.valueOf(quality.bestOf()));

        // Entropy threshold - filter hallucinated/low-confidence segments
        command.add("-et");
        command.add(String.format(java.util.Locale.US, "%.1f", quality.entropyThreshold()));

        // Temperature for sampling (0.0 = most deterministic)
        command.add("-tp");
        command.add(String.format(java.util.Locale.US, "%.2f", quality.temperature()));

        // No-speech probability threshold
        command.add("-nth");
        command.add("0.6");

        // Initial prompt in the target language to prime the decoder
        String prompt = language.initialPrompt();
        if (prompt != null && !prompt.isEmpty()) {
            command.add("--prompt");
            command.add(prompt);
        }

        // Split on word boundaries for better subtitle segments
        command.add("-sow");

        // Threading - use override or auto-detect
        int threads = threadCountOverride > 0 ? threadCountOverride
            : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        command.add("-t");
        command.add(String.valueOf(threads));

        command.add("-pp");                // print progress
        command.add("-ls");                // log best decoder scores for confidence scoring

        log.info("Running Whisper: {}", String.join(" ", command));
        progressUpdate.accept("Starting transcription with " + model.modelName() + " model...");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        // Ensure UTF-8 encoding for non-Latin prompts (Japanese, Chinese, etc.) on Windows
        if (isWindows()) {
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("LANG", "en_US.UTF-8");
        }
        Process process = pb.start();
        activeProcesses.add(process);

        String detectedLanguage = null;
        double sumLogProb = 0;
        int logProbCount = 0;

        // Read progress output with UTF-8 encoding
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("whisper: {}", line);
                // Capture auto-detected language
                if (line.contains("auto-detected language:")) {
                    int idx = line.indexOf("auto-detected language:");
                    detectedLanguage = line.substring(idx + 23).trim();
                    log.info("Auto-detected language: {}", detectedLanguage);
                }
                // Capture log probability for confidence scoring
                // whisper.cpp with -ls outputs token scores. Also look for
                // "log_prob_avg" or "logprob" patterns in output.
                if (line.contains("logprob") || line.contains("log_prob")
                        || line.contains("score")) {
                    try {
                        // Try to parse any floating point number after '=' or ':'
                        int sepIdx = Math.max(line.indexOf('='), line.indexOf(':'));
                        if (sepIdx > 0 && sepIdx < line.length() - 1) {
                            String val = line.substring(sepIdx + 1).trim().split("[\\s,;\\]\\)]")[0];
                            double parsed = Double.parseDouble(val);
                            if (parsed < 0 && parsed > -10) { // Valid log-prob range
                                sumLogProb += parsed;
                                logProbCount++;
                            }
                        }
                    } catch (NumberFormatException ignored) {
                        // Non-fatal: skip unparseable score lines
                    }
                }
                String progress = parseWhisperProgress(line);
                if (progress != null) {
                    progressUpdate.accept(progress);
                }
            }
        }

        double avgLogProb = logProbCount > 0 ? sumLogProb / logProbCount : 0.0;

        boolean finished = process.waitFor(15, TimeUnit.MINUTES);
        activeProcesses.remove(process);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Whisper transcription timed out after 15 minutes");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("Whisper exited with code: {}", exitCode);
            throw new IOException("Whisper transcription failed (exit code " + exitCode + ")");
        }

        File srtFile = new File(outputBase + ".srt");
        if (srtFile.exists() && srtFile.length() > 0) {
            log.info("Transcription complete: {} (avgLogProb={})", srtFile.getAbsolutePath(),
                String.format("%.4f", avgLogProb));
            return new TranscriptionResult(srtFile, detectedLanguage, avgLogProb);
        }

        log.error("SRT file not generated: {}", srtFile.getAbsolutePath());
        return null;
    }

    /**
     * Forcibly destroys all active whisper.cpp processes.
     * Used for cancellation.
     */
    public void destroyAllProcesses() {
        for (Process p : activeProcesses) {
            try {
                p.destroyForcibly();
            } catch (Exception e) {
                log.debug("Error destroying whisper process", e);
            }
        }
        activeProcesses.clear();
    }

    private String parseWhisperProgress(String line) {
        // whisper.cpp prints progress like: "whisper_print_progress_callback: progress = 42%"
        if (line.contains("progress =")) {
            int idx = line.indexOf("progress =");
            String pct = line.substring(idx + 10).trim();
            return "Transcribing... " + pct;
        }
        // Also catch timestamp lines like "[00:00:00.000 --> 00:00:05.000] text"
        if (line.startsWith("[") && line.contains("-->")) {
            int closeIdx = line.indexOf("]");
            if (closeIdx > 0 && closeIdx + 1 < line.length()) {
                String text = line.substring(closeIdx + 1).trim();
                if (!text.isEmpty()) {
                    return "Transcribing: \"" +
                        (text.length() > 60 ? text.substring(0, 60) + "..." : text) + "\"";
                }
            }
        }
        return null;
    }

    private static Path findWhisperBinary() {
        String[] names = isWindows()
            ? new String[]{"whisper-cli.exe", "main.exe", "whisper.exe"}
            : new String[]{"whisper-cli", "main", "whisper"};

        for (String name : names) {
            Path found = findOnPath(name);
            if (found != null) {
                log.info("Found Whisper binary: {}", found);
                return found;
            }
        }

        // Check common locations
        Path appDir = Path.of(System.getProperty("user.dir"));
        String[] subDirs = {"whisper", "whisper.cpp", "bin"};
        for (String dir : subDirs) {
            for (String name : names) {
                Path candidate = appDir.resolve(dir).resolve(name);
                if (Files.isExecutable(candidate)) {
                    log.info("Found Whisper binary: {}", candidate);
                    return candidate;
                }
            }
        }

        log.info("Whisper binary not found on system");
        return null;
    }

    private static Path resolveModelsDirectory() {
        // Check standard locations for whisper models
        Path appDir = Path.of(System.getProperty("user.dir"));
        Path[] candidates = {
            appDir.resolve("models"),
            appDir.resolve("whisper").resolve("models"),
            appDir.resolve("whisper.cpp").resolve("models"),
        };

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        // Default: create models dir next to app
        Path defaultDir = appDir.resolve("models");
        try {
            Files.createDirectories(defaultDir);
        } catch (IOException e) {
            log.warn("Could not create models directory: {}", defaultDir, e);
        }
        return defaultDir;
    }

    private static Path findOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path candidate = Path.of(dir, executable);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
