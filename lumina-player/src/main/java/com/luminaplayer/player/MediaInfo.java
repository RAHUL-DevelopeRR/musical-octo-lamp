package com.luminaplayer.player;

/**
 * POJO holding parsed media metadata.
 */
public class MediaInfo {

    private String title;
    private String artist;
    private String album;
    private long durationMs;
    private String filePath;
    private long fileSize;
    private String videoCodec;
    private String audioCodec;
    private int width;
    private int height;

    public MediaInfo() {
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }

    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public String getDisplayTitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (filePath != null) {
            int sep = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            return sep >= 0 ? filePath.substring(sep + 1) : filePath;
        }
        return "Unknown";
    }
}
