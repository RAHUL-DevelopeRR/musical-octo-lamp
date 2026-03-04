package com.luminaplayer.ai;

import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JavaFX Task that downloads a Whisper model file from Hugging Face.
 * Reports download progress for UI binding.
 */
public class ModelDownloadTask extends Task<File> {

    private static final Logger log = LoggerFactory.getLogger(ModelDownloadTask.class);

    private final WhisperModel model;
    private final Path modelsDirectory;

    public ModelDownloadTask(WhisperModel model, Path modelsDirectory) {
        this.model = model;
        this.modelsDirectory = modelsDirectory;
    }

    @Override
    protected File call() throws Exception {
        Files.createDirectories(modelsDirectory);

        Path targetFile = modelsDirectory.resolve(model.fileName());
        Path tempFile = modelsDirectory.resolve(model.fileName() + ".downloading");

        updateMessage("Connecting to Hugging Face...");
        updateProgress(-1, 1);

        String url = model.downloadUrl();
        log.info("Downloading model {} from {}", model.modelName(), url);

        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestProperty("User-Agent", "LuminaPlayer/1.0");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);

        // Follow redirects (Hugging Face uses them)
        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
            responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
            responseCode == 307 || responseCode == 308) {

            String redirectUrl = connection.getHeaderField("Location");
            log.info("Redirected to: {}", redirectUrl);
            connection.disconnect();
            connection = (HttpURLConnection) URI.create(redirectUrl).toURL().openConnection();
            connection.setRequestProperty("User-Agent", "LuminaPlayer/1.0");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            responseCode = connection.getResponseCode();
        }

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Download failed: HTTP " + responseCode);
        }

        long contentLength = connection.getContentLengthLong();
        long expectedBytes = model.sizeMb() * 1024L * 1024L;
        long totalBytes = contentLength > 0 ? contentLength : expectedBytes;

        log.info("Downloading {} ({} bytes)", model.fileName(), totalBytes);

        try (InputStream in = new BufferedInputStream(connection.getInputStream(), 65536);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempFile), 65536)) {

            byte[] buffer = new byte[65536];
            long downloaded = 0;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                if (isCancelled()) {
                    log.info("Download cancelled");
                    Files.deleteIfExists(tempFile);
                    updateMessage("Download cancelled");
                    return null;
                }

                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                double progress = (double) downloaded / totalBytes;
                long downloadedMb = downloaded / (1024 * 1024);
                long totalMb = totalBytes / (1024 * 1024);

                updateProgress(downloaded, totalBytes);
                updateMessage(String.format("Downloading %s... %dMB / %dMB (%.0f%%)",
                    model.modelName(), downloadedMb, totalMb, progress * 100));
            }
        }

        // Move temp file to final location
        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        log.info("Model download complete: {}", targetFile);
        updateMessage("Download complete: " + model.fileName());
        return targetFile.toFile();
    }

    @Override
    protected void cancelled() {
        updateMessage("Download cancelled");
        // Clean up temp file
        try {
            Files.deleteIfExists(modelsDirectory.resolve(model.fileName() + ".downloading"));
        } catch (IOException e) {
            log.warn("Failed to clean up temp download file", e);
        }
    }

    @Override
    protected void failed() {
        log.error("Model download failed", getException());
        updateMessage("Download failed: " + getException().getMessage());
        try {
            Files.deleteIfExists(modelsDirectory.resolve(model.fileName() + ".downloading"));
        } catch (IOException e) {
            log.warn("Failed to clean up temp download file", e);
        }
    }
}
