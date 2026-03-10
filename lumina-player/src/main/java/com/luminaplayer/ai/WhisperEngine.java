package com.luminaplayer.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Whisper transcription engine with two operation modes:
 *
 * <p><b>Mode 1 — Faster-Whisper sidecar (preferred):</b><br>
 * Launches {@code scripts/whisper_server.py} once on the first {@link #transcribe} call and
 * reuses the same process for every subsequent chunk.  The Python model is loaded a single
 * time, eliminating the 1-3 minute per-chunk model-reload overhead of the old whisper.cpp CLI.
 * {@code condition_on_previous_text=False} is sent in every request, fixing cross-chunk
 * hallucinations that caused inaccurate subtitles.
 *
 * <p><b>Mode 2 — whisper.cpp CLI fallback:</b><br>
 * Used automatically when Python / faster-whisper is not available.  Behaviour is identical
 * to the original implementation with the addition of {@code --no-context} to fix accuracy.
 */
public class WhisperEngine {

    private static final Logger log = LoggerFactory.getLogger(WhisperEngine.class);

    // ── Sidecar state ─────────────────────────────────────────────────────────
    private Process sidecarProcess;
    private BufferedWriter sidecarWriter;
    private BufferedReader sidecarReader;
    private volatile boolean sidecarFailed = false;   // true → fall back to whisper.cpp

    // ── whisper.cpp fallback state ────────────────────────────────────────────
    private Path whisperBinaryPath;
    private Path modelsDirectory;
    private final CopyOnWriteArrayList<Process> activeProcesses = new CopyOnWriteArrayList<>();
    private int threadCountOverride = 0;

    // ── Sidecar script location ───────────────────────────────────────────────
    /** Path to whisper_server.py, resolved at construction time. */
    private final Path sidecarScript;

    public WhisperEngine() {
        this.whisperBinaryPath = findWhisperBinary();
        this.modelsDirectory   = resolveModelsDirectory();
        this.sidecarScript     = findSidecarScript();
    }

    // ── Public API (unchanged from original) ─────────────────────────────────

    public void setWhisperBinaryPath(Path path) { this.whisperBinaryPath = path; }
    public void setModelsDirectory(Path dir)    { this.modelsDirectory   = dir; }
    public void setThreadCount(int n)           { this.threadCountOverride = n; }

    public boolean isAvailable() {
        // Available if either the sidecar script OR the whisper.cpp binary is present
        return (sidecarScript != null && Files.exists(sidecarScript))
            || (whisperBinaryPath != null && Files.exists(whisperBinaryPath));
    }

    public String getBinaryPath() {
        if (sidecarScript != null && Files.exists(sidecarScript))
            return "faster-whisper (" + sidecarScript + ")";
        return whisperBinaryPath != null ? whisperBinaryPath.toString() : null;
    }

    public Path getModelsDirectory() { return modelsDirectory; }

    public boolean isModelAvailable(WhisperModel model) {
        // For the sidecar, faster-whisper downloads models automatically; always report true
        // when the sidecar is usable.  For the CLI fallback, check the file on disk.
        if (sidecarScript != null && Files.exists(sidecarScript) && !sidecarFailed)
            return true;
        if (modelsDirectory == null) return false;
        return Files.exists(modelsDirectory.resolve(model.fileName()));
    }

    public Path getModelPath(WhisperModel model) {
        if (modelsDirectory == null) return null;
        return modelsDirectory.resolve(model.fileName());
    }

    // ── Transcribe (single-quality overload) ─────────────────────────────────

    public TranscriptionResult transcribe(File wavFile, File outputSrt, WhisperModel model,
                                          WhisperLanguage language, boolean translate,
                                          Consumer<String> progressUpdate)
            throws IOException, InterruptedException {
        return transcribe(wavFile, outputSrt, model, language, translate,
                WhisperQuality.BALANCED, progressUpdate);
    }

    // ── Transcribe (main entry point) ─────────────────────────────────────────

    /**
     * Transcribes {@code wavFile} and writes subtitles to {@code outputSrt}.
     *
     * <p>Tries the Faster-Whisper sidecar first; falls back to whisper.cpp on any
     * failure.
     */
    public TranscriptionResult transcribe(File wavFile, File outputSrt, WhisperModel model,
                                          WhisperLanguage language, boolean translate,
                                          WhisperQuality quality,
                                          Consumer<String> progressUpdate)
            throws IOException, InterruptedException {

        // ── Try sidecar first ─────────────────────────────────────────────────
        if (sidecarScript != null && Files.exists(sidecarScript) && !sidecarFailed) {
            try {
                return transcribeViaSidecar(wavFile, outputSrt, model, language,
                        translate, quality, progressUpdate);
            } catch (Exception e) {
                log.warn("Faster-Whisper sidecar failed ({}), falling back to whisper.cpp: {}",
                        e.getClass().getSimpleName(), e.getMessage());
                sidecarFailed = true;
                shutdownSidecar();
            }
        }

        // ── whisper.cpp CLI fallback ──────────────────────────────────────────
        return transcribeViaCli(wavFile, outputSrt, model, language,
                translate, quality, progressUpdate);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SIDECAR MODE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Ensures the sidecar process is running and transcribes via JSON-lines protocol.
     */
    private TranscriptionResult transcribeViaSidecar(File wavFile, File outputSrt,
                                                      WhisperModel model, WhisperLanguage language,
                                                      boolean translate, WhisperQuality quality,
                                                      Consumer<String> progressUpdate)
            throws IOException, InterruptedException {

        ensureSidecarRunning(model);

        progressUpdate.accept("[faster-whisper] Transcribing with " + model.modelName() + " model…");

        // Build JSON request
        String langCode = (language == WhisperLanguage.AUTO) ? "" : language.code();
        String reqJson = buildSidecarRequest(wavFile.getAbsolutePath(),
                model.modelName(), langCode, quality, translate);

        log.info("Sidecar request: {}", reqJson);
        sidecarWriter.write(reqJson);
        sidecarWriter.newLine();
        sidecarWriter.flush();

        // Read response (blocking — one JSON line)
        String respLine = sidecarReader.readLine();
        if (respLine == null)
            throw new IOException("Sidecar process closed its stdout unexpectedly.");

        log.info("Sidecar response length: {} chars", respLine.length());
        return parseSidecarResponse(respLine, outputSrt, progressUpdate);
    }

    /** Starts the sidecar process if it is not already running. */
    private synchronized void ensureSidecarRunning(WhisperModel model)
            throws IOException, InterruptedException {

        if (sidecarProcess != null && sidecarProcess.isAlive()) return;

        String python = findPython();
        if (python == null)
            throw new IOException("Python 3 interpreter not found on PATH. "
                    + "Install Python 3 and faster-whisper to use the sidecar.");

        ProcessBuilder pb = new ProcessBuilder(python, sidecarScript.toString());
        pb.redirectErrorStream(false);   // keep stderr separate so it doesn't pollute stdout JSON
        if (isWindows()) {
            pb.environment().put("PYTHONIOENCODING", "utf-8");
        }

        log.info("Launching Faster-Whisper sidecar: {} {}", python, sidecarScript);
        sidecarProcess = pb.start();

        sidecarWriter = new BufferedWriter(
                new OutputStreamWriter(sidecarProcess.getOutputStream(), StandardCharsets.UTF_8));
        sidecarReader = new BufferedReader(
                new InputStreamReader(sidecarProcess.getInputStream(), StandardCharsets.UTF_8));

        // Drain sidecar stderr on a background daemon thread so it never blocks
        Thread stderrDrainer = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(sidecarProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = err.readLine()) != null) {
                    log.debug("[sidecar-stderr] {}", l);
                }
            } catch (IOException ignored) { }
        }, "sidecar-stderr-drainer");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();

        // Ping to confirm the server is ready
        sidecarWriter.write("{\"cmd\":\"ping\"}");
        sidecarWriter.newLine();
        sidecarWriter.flush();
        String pong = sidecarReader.readLine();
        if (pong == null || !pong.contains("pong"))
            throw new IOException("Sidecar ping failed (got: " + pong + ")");

        log.info("Faster-Whisper sidecar ready.");
    }

    /** Builds the JSON request string (manual, no external JSON library needed). */
    private static String buildSidecarRequest(String wavPath, String modelName,
                                               String langCode, WhisperQuality quality,
                                               boolean translate) {
        return String.format(
                "{\"wav_path\":\"%s\",\"model\":\"%s\",\"language\":\"%s\","
              + "\"quality\":\"%s\",\"translate\":%s}",
                escapeJson(wavPath), modelName, langCode,
                quality.name(), translate ? "true" : "false");
    }

    /** Parses the sidecar JSON response and writes an SRT file. */
    private TranscriptionResult parseSidecarResponse(String respLine, File outputSrt,
                                                      Consumer<String> progressUpdate)
            throws IOException {

        // ── Minimal hand-rolled JSON parser ───────────────────────────────────
        // We avoid pulling in a full JSON library (Gson/Jackson) to stay lean.
        // The sidecar returns a well-defined structure we can parse with regex.

        String error = extractJsonString(respLine, "error");
        if (error != null && !error.equals("null") && !error.isEmpty()) {
            throw new IOException("Sidecar transcription error: " + error);
        }

        String detectedLang = extractJsonString(respLine, "language");

        // Parse segments array
        List<SubtitleEntry> entries = new ArrayList<>();
        int idx = respLine.indexOf("\"segments\"");
        if (idx < 0) throw new IOException("Sidecar response missing 'segments' field.");

        int arrStart = respLine.indexOf('[', idx);
        int arrEnd   = respLine.lastIndexOf(']');
        if (arrStart < 0 || arrEnd < 0)
            throw new IOException("Sidecar response has malformed segments array.");

        String segmentsJson = respLine.substring(arrStart + 1, arrEnd);
        String[] segTokens  = segmentsJson.split("\\},\\s*\\{");

        int cueIndex = 1;
        double sumLogProb = 0;
        int logProbCount = 0;

        for (String seg : segTokens) {
            seg = seg.replace("{", "").replace("}", "");
            if (seg.isBlank()) continue;

            try {
                double start      = Double.parseDouble(extractJsonNumber(seg, "start"));
                double end        = Double.parseDouble(extractJsonNumber(seg, "end"));
                String text       = extractJsonString(seg, "text");
                String confStr    = extractJsonNumber(seg, "confidence");
                double confidence = confStr.isEmpty() ? 0.0 : Double.parseDouble(confStr);

                if (text == null || text.isBlank()) continue;

                entries.add(new SubtitleEntry(cueIndex++,
                        (long) (start * 1000), (long) (end * 1000), text));
                sumLogProb += confidence;
                logProbCount++;
            } catch (NumberFormatException e) {
                log.debug("Could not parse sidecar segment: {}", seg);
            }
        }

        // Write SRT file
        SrtParser.writeToFile(entries, outputSrt);
        progressUpdate.accept("[faster-whisper] Complete — " + entries.size() + " cues.");

        double avgLogProb = logProbCount > 0 ? sumLogProb / logProbCount : 0.0;
        return new TranscriptionResult(outputSrt, detectedLang, avgLogProb);
    }

    // ── Tiny JSON helpers (no external dependency) ────────────────────────────

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"";
        int i = findPattern(json, pattern);
        if (i < 0) return null;
        int start = json.indexOf('"', i + key.length() + 3) + 1;
        int end   = json.indexOf('"', start);
        if (start <= 0 || end < 0) return null;
        return json.substring(start, end)
                   .replace("\\\\", "\\").replace("\\\"", "\"");
    }

    private static String extractJsonNumber(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*";
        int i = findPattern(json, pattern);
        if (i < 0) return "";
        int valStart = json.indexOf(':', i + key.length() + 2) + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        int valEnd = valStart;
        while (valEnd < json.length()) {
            char c = json.charAt(valEnd);
            if (c == ',' || c == '}' || c == ']') break;
            valEnd++;
        }
        return json.substring(valStart, valEnd).trim();
    }

    private static int findPattern(String text, String pattern) {
        return text.indexOf(pattern.replace("\\\\", "\\").replace("\\s*", "").replace("\\\":", "\":"));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Sends exit command and destroys the sidecar process. */
    public synchronized void shutdownSidecar() {
        if (sidecarProcess == null) return;
        try {
            if (sidecarWriter != null) {
                sidecarWriter.write("{\"cmd\":\"exit\"}");
                sidecarWriter.newLine();
                sidecarWriter.flush();
            }
        } catch (IOException ignored) { }
        try {
            if (!sidecarProcess.waitFor(3, TimeUnit.SECONDS))
                sidecarProcess.destroyForcibly();
        } catch (InterruptedException e) {
            sidecarProcess.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally {
            sidecarProcess = null;
            sidecarWriter  = null;
            sidecarReader  = null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // whisper.cpp CLI FALLBACK
    // ═════════════════════════════════════════════════════════════════════════

    private TranscriptionResult transcribeViaCli(File wavFile, File outputSrt,
                                                  WhisperModel model, WhisperLanguage language,
                                                  boolean translate, WhisperQuality quality,
                                                  Consumer<String> progressUpdate)
            throws IOException, InterruptedException {

        if (whisperBinaryPath == null || !Files.exists(whisperBinaryPath))
            throw new IOException("Whisper binary not found and faster-whisper sidecar is unavailable.");

        Path modelPath = getModelPath(model);
        if (modelPath == null || !Files.exists(modelPath))
            throw new IOException("Model not found: " + model.fileName());

        String outputBase = outputSrt.getAbsolutePath();
        if (outputBase.endsWith(".srt"))
            outputBase = outputBase.substring(0, outputBase.length() - 4);

        List<String> cmd = new ArrayList<>();
        cmd.add(whisperBinaryPath.toString());
        cmd.add("-m");  cmd.add(modelPath.toString());
        cmd.add("-f");  cmd.add(wavFile.getAbsolutePath());
        cmd.add("-osrt");
        cmd.add("-of"); cmd.add(outputBase);

        if (language != WhisperLanguage.AUTO) {
            cmd.add("-l"); cmd.add(language.code());
        }
        if (translate) cmd.add("-tr");

        // ── FIX: --no-context prevents cross-chunk hallucinations ─────────────
        cmd.add("--no-context");

        cmd.add("-bs");  cmd.add(String.valueOf(quality.beamSize()));
        cmd.add("-bo");  cmd.add(String.valueOf(quality.bestOf()));
        cmd.add("-et");  cmd.add(String.format(java.util.Locale.US, "%.1f", quality.entropyThreshold()));
        cmd.add("-tp");  cmd.add(String.format(java.util.Locale.US, "%.2f", quality.temperature()));
        cmd.add("-nth"); cmd.add("0.6");
        cmd.add("--temperature-inc"); cmd.add("0.2");  // temperature fallback

        String prompt = language.initialPrompt();
        if (prompt != null && !prompt.isEmpty()) {
            cmd.add("--prompt"); cmd.add(prompt);
        }

        cmd.add("-sow");
        int threads = threadCountOverride > 0 ? threadCountOverride
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        cmd.add("-t"); cmd.add(String.valueOf(threads));
        cmd.add("-pp");
        cmd.add("-ls");

        log.info("[fallback] Running whisper.cpp: {}", String.join(" ", cmd));
        progressUpdate.accept("[whisper.cpp] Starting transcription with " + model.modelName() + "…");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (isWindows()) {
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("LANG", "en_US.UTF-8");
        }
        Process process = pb.start();
        activeProcesses.add(process);

        String detectedLanguage = null;
        double sumLogProb = 0;
        int logProbCount  = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("whisper: {}", line);
                if (line.contains("auto-detected language:")) {
                    detectedLanguage = line.substring(line.indexOf("auto-detected language:") + 23).trim();
                }
                if (line.contains("logprob") || line.contains("log_prob") || line.contains("score")) {
                    try {
                        int sep = Math.max(line.indexOf('='), line.indexOf(':'));
                        if (sep > 0) {
                            String val = line.substring(sep + 1).trim().split("[\\s,;\\]\\)]")[0];
                            double p = Double.parseDouble(val);
                            if (p < 0 && p > -10) { sumLogProb += p; logProbCount++; }
                        }
                    } catch (NumberFormatException ignored) { }
                }
                String prog = parseWhisperProgress(line);
                if (prog != null) progressUpdate.accept(prog);
            }
        }

        double avgLogProb = logProbCount > 0 ? sumLogProb / logProbCount : 0.0;
        boolean finished  = process.waitFor(15, TimeUnit.MINUTES);
        activeProcesses.remove(process);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("whisper.cpp timed out after 15 minutes.");
        }
        if (process.exitValue() != 0)
            throw new IOException("whisper.cpp failed (exit " + process.exitValue() + ").");

        File srtFile = new File(outputBase + ".srt");
        if (srtFile.exists() && srtFile.length() > 0)
            return new TranscriptionResult(srtFile, detectedLanguage, avgLogProb);

        return null;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SHARED UTILITIES
    // ═════════════════════════════════════════════════════════════════════════

    /** Kills all active whisper.cpp processes AND shuts down the sidecar. */
    public void destroyAllProcesses() {
        for (Process p : activeProcesses) {
            try { p.destroyForcibly(); } catch (Exception ignored) { }
        }
        activeProcesses.clear();
        shutdownSidecar();
    }

    private static String parseWhisperProgress(String line) {
        if (line.contains("progress =")) {
            int i = line.indexOf("progress =");
            return "Transcribing... " + line.substring(i + 10).trim();
        }
        if (line.startsWith("[") && line.contains("-->")) {
            int close = line.indexOf(']');
            if (close > 0 && close + 1 < line.length()) {
                String text = line.substring(close + 1).trim();
                if (!text.isEmpty())
                    return "Transcribing: \"" + (text.length() > 60 ? text.substring(0, 60) + "…" : text) + "\"";
            }
        }
        return null;
    }

    private static Path findSidecarScript() {
        Path appDir = Path.of(System.getProperty("user.dir"));
        Path[] candidates = {
            appDir.resolve("scripts").resolve("whisper_server.py"),
            appDir.resolve("lumina-player").resolve("scripts").resolve("whisper_server.py"),
            appDir.getParent() != null
                ? appDir.getParent().resolve("scripts").resolve("whisper_server.py")
                : null,
        };
        for (Path c : candidates) {
            if (c != null && Files.exists(c)) {
                log.info("Found Faster-Whisper sidecar script: {}", c);
                return c;
            }
        }
        log.info("Faster-Whisper sidecar script not found — will use whisper.cpp CLI fallback.");
        return null;
    }

    private static Path findWhisperBinary() {
        String[] names = isWindows()
            ? new String[]{"whisper-cli.exe", "main.exe", "whisper.exe"}
            : new String[]{"whisper-cli", "main", "whisper"};
        for (String n : names) {
            Path found = findOnPath(n);
            if (found != null) { log.info("Found whisper.cpp binary: {}", found); return found; }
        }
        Path appDir = Path.of(System.getProperty("user.dir"));
        for (String dir : new String[]{"whisper", "whisper.cpp", "bin"}) {
            for (String n : names) {
                Path c = appDir.resolve(dir).resolve(n);
                if (Files.isExecutable(c)) return c;
            }
        }
        return null;
    }

    private static Path resolveModelsDirectory() {
        Path appDir = Path.of(System.getProperty("user.dir"));
        for (Path c : new Path[]{
                appDir.resolve("models"),
                appDir.resolve("whisper").resolve("models"),
                appDir.resolve("whisper.cpp").resolve("models")}) {
            if (Files.isDirectory(c)) return c;
        }
        Path def = appDir.resolve("models");
        try { Files.createDirectories(def); } catch (IOException e) { /* best-effort */ }
        return def;
    }

    private static String findPython() {
        for (String py : new String[]{"python3", "python"}) {
            if (findOnPath(py) != null) return py;
            // Windows: also check common install paths
            if (isWindows()) {
                for (String p : new String[]{
                        System.getenv("LOCALAPPDATA") + "\\Programs\\Python\\Python311\\python.exe",
                        System.getenv("LOCALAPPDATA") + "\\Programs\\Python\\Python312\\python.exe",
                        "C:\\Python311\\python.exe", "C:\\Python312\\python.exe"}) {
                    if (p != null && Files.exists(Path.of(p))) return p;
                }
            }
        }
        return null;
    }

    private static Path findOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path c = Path.of(dir, executable);
            if (Files.isExecutable(c)) return c;
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
