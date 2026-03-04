package com.luminaplayer.util;

import com.luminaplayer.app.AppConfig;

import java.io.File;

/**
 * File extension and type checking utilities.
 */
public final class FileUtils {

    private FileUtils() {
    }

    public static String getExtension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }

    public static boolean isVideoFile(File file) {
        return matchesExtension(file, AppConfig.VIDEO_EXTENSIONS);
    }

    public static boolean isAudioFile(File file) {
        return matchesExtension(file, AppConfig.AUDIO_EXTENSIONS);
    }

    public static boolean isSubtitleFile(File file) {
        return matchesExtension(file, AppConfig.SUBTITLE_EXTENSIONS);
    }

    public static boolean isMediaFile(File file) {
        return isVideoFile(file) || isAudioFile(file);
    }

    private static boolean matchesExtension(File file, String[] extensions) {
        String ext = getExtension(file);
        for (String e : extensions) {
            if (e.equalsIgnoreCase(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build a file extension filter string for FileChooser.
     * Example: "*.mp4;*.mkv;*.avi"
     */
    public static String buildExtensionFilter(String[] extensions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < extensions.length; i++) {
            if (i > 0) sb.append(";");
            sb.append("*.").append(extensions[i]);
        }
        return sb.toString();
    }
}
