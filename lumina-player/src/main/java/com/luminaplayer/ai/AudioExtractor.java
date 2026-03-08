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
 * Extracts audio from media files using FFmpeg CLI.
 * Converts to 16kHz mono WAV format required by Whisper.
 */
public class AudioExtractor {

    private static final Logger log = LoggerFactory.getLogger(AudioExtractor.class);

    private Path ffmpegPath;
    private final CopyOnWriteArrayList<Process> activeProcesses = new CopyOnWriteArrayList<>();

    public AudioExtractor() {
        this.ffmpegPath = findFfmpeg();
    }

    public void setFfmpegPath(Path path) {
        this.ffmpegPath = path;
    }

    public boolean isAvailable() {
        return ffmpegPath != null && Files.exists(ffmpegPath);
    }

    public String getToolPath() {
        return ffmpegPath != null ? ffmpegPath.toString() : null;
    }

    /**
     * Extracts audio from a media file to a 16kHz mono WAV file suitable for Whisper.
     *
     * @param inputFile      source media file
     * @param outputWav      destination WAV file
     * @param progressUpdate callback for progress messages
     * @return true if extraction succeeded
     */
    public boolean extractAudio(File inputFile, File outputWav, Consumer<String> progressUpdate)
            throws IOException, InterruptedException {

        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found. Install FFmpeg or set its path in settings.");
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath.toString());
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-vn");                  // no video
        command.add("-acodec");
        command.add("pcm_s16le");            // 16-bit PCM
        command.add("-ar");
        command.add("16000");                // 16kHz sample rate
        command.add("-ac");
        command.add("1");                    // mono
        command.add("-y");                   // overwrite
        command.add(outputWav.getAbsolutePath());

        log.info("Extracting audio: {}", String.join(" ", command));
        progressUpdate.accept("Extracting audio from media file...");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        activeProcesses.add(process);

        // Read output for progress
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("ffmpeg: {}", line);
                if (line.contains("time=")) {
                    progressUpdate.accept("Extracting audio... " + extractTimeProgress(line));
                }
            }
        }

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        activeProcesses.remove(process);
        if (!finished) {
            process.destroyForcibly();
            log.error("FFmpeg timed out after 10 minutes");
            return false;
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("FFmpeg exited with code: {}", exitCode);
            return false;
        }

        log.info("Audio extraction complete: {} ({} bytes)", outputWav.getName(), outputWav.length());
        return outputWav.exists() && outputWav.length() > 0;
    }

    /**
     * Extracts a specific time range of audio from a media file to a 16kHz mono WAV file.
     * Used for chunked subtitle generation.
     *
     * @param inputFile      source media file
     * @param outputWav      destination WAV file
     * @param startMs        start time in milliseconds
     * @param durationMs     duration to extract in milliseconds
     * @param progressUpdate callback for progress messages
     * @return true if extraction succeeded
     */
    public boolean extractAudioChunk(File inputFile, File outputWav, long startMs,
                                     long durationMs, Consumer<String> progressUpdate)
            throws IOException, InterruptedException {

        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found. Install FFmpeg or set its path in settings.");
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath.toString());
        // -ss BEFORE -i = fast keyframe seeking (critical for speed!)
        command.add("-ss");
        command.add(String.format(java.util.Locale.US, "%.3f", startMs / 1000.0));
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-t");
        command.add(String.format(java.util.Locale.US, "%.3f", durationMs / 1000.0));
        command.add("-vn");                  // no video
        command.add("-acodec");
        command.add("pcm_s16le");            // 16-bit PCM
        command.add("-ar");
        command.add("16000");                // 16kHz sample rate
        command.add("-ac");
        command.add("1");                    // mono
        command.add("-y");                   // overwrite
        command.add(outputWav.getAbsolutePath());

        log.info("Extracting audio chunk: {}", String.join(" ", command));
        progressUpdate.accept(String.format("Extracting audio chunk (%.1fs - %.1fs)...",
            startMs / 1000.0, (startMs + durationMs) / 1000.0));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        activeProcesses.add(process);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("ffmpeg: {}", line);
                if (line.contains("time=")) {
                    progressUpdate.accept("Extracting audio chunk... " + extractTimeProgress(line));
                }
            }
        }

        boolean finished = process.waitFor(2, TimeUnit.MINUTES);
        activeProcesses.remove(process);
        if (!finished) {
            process.destroyForcibly();
            log.error("FFmpeg chunk extraction timed out after 2 minutes");
            return false;
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("FFmpeg chunk extraction exited with code: {}", exitCode);
            return false;
        }

        log.info("Audio chunk extraction complete: {} ({} bytes)", outputWav.getName(), outputWav.length());
        return outputWav.exists() && outputWav.length() > 0;
    }

    /**
     * Slices a chunk from an already-extracted WAV file. Much faster than extracting
     * from the original media file since no video demuxing is needed.
     *
     * @param sourceWav      pre-extracted full audio WAV file
     * @param outputWav      destination WAV file for this chunk
     * @param startMs        start time in milliseconds
     * @param durationMs     duration to extract in milliseconds
     * @param progressUpdate callback for progress messages
     * @return true if slicing succeeded
     */
    public boolean sliceWavChunk(File sourceWav, File outputWav, long startMs,
                                 long durationMs, Consumer<String> progressUpdate)
            throws IOException, InterruptedException {

        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found.");
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath.toString());
        command.add("-ss");
        command.add(String.format(java.util.Locale.US, "%.3f", startMs / 1000.0));
        command.add("-i");
        command.add(sourceWav.getAbsolutePath());
        command.add("-t");
        command.add(String.format(java.util.Locale.US, "%.3f", durationMs / 1000.0));
        command.add("-acodec");
        command.add("copy");                 // no re-encoding needed for WAV→WAV
        command.add("-y");
        command.add(outputWav.getAbsolutePath());

        log.debug("Slicing WAV chunk: {}", String.join(" ", command));
        progressUpdate.accept(String.format("Slicing audio chunk (%.1fs - %.1fs)...",
            startMs / 1000.0, (startMs + durationMs) / 1000.0));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        activeProcesses.add(process);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("ffmpeg-slice: {}", line);
            }
        }

        boolean finished = process.waitFor(2, TimeUnit.MINUTES);
        activeProcesses.remove(process);
        if (!finished) {
            process.destroyForcibly();
            log.error("FFmpeg WAV slice timed out after 2 minutes");
            return false;
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("FFmpeg WAV slice exited with code: {}", exitCode);
            return false;
        }

        return outputWav.exists() && outputWav.length() > 0;
    }

    private String extractTimeProgress(String line) {
        int idx = line.indexOf("time=");
        if (idx >= 0) {
            int end = line.indexOf(" ", idx + 5);
            if (end > idx) {
                return line.substring(idx + 5, end).trim();
            }
        }
        return "";
    }

    private static Path findFfmpeg() {
        // Check common locations
        String[] names = isWindows() ? new String[]{"ffmpeg.exe"} : new String[]{"ffmpeg"};

        for (String name : names) {
            // Check PATH
            Path found = findOnPath(name);
            if (found != null) return found;
        }

        // Check common Windows install locations
        if (isWindows()) {
            String[] commonPaths = {
                System.getenv("LOCALAPPDATA") + "\\FFmpeg\\bin\\ffmpeg.exe",
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\FFmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files (x86)\\FFmpeg\\bin\\ffmpeg.exe"
            };
            for (String path : commonPaths) {
                if (path != null && Files.exists(Path.of(path))) {
                    return Path.of(path);
                }
            }
        }

        log.warn("FFmpeg not found on system");
        return null;
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

    /**
     * Forcibly destroys all active FFmpeg processes.
     * Used for cancellation.
     */
    public void destroyAllProcesses() {
        for (Process p : activeProcesses) {
            try {
                p.destroyForcibly();
            } catch (Exception e) {
                log.debug("Error destroying ffmpeg process", e);
            }
        }
        activeProcesses.clear();
    }
}
