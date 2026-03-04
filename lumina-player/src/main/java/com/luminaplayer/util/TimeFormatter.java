package com.luminaplayer.util;

/**
 * Utility for formatting millisecond durations into human-readable time strings.
 */
public final class TimeFormatter {

    private TimeFormatter() {
    }

    /**
     * Format milliseconds as MM:SS or HH:MM:SS if >= 1 hour.
     */
    public static String format(long millis) {
        if (millis < 0) {
            millis = 0;
        }

        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * Format milliseconds as HH:MM:SS.mmm (always includes hours).
     */
    public static String formatFull(long millis) {
        if (millis < 0) {
            millis = 0;
        }

        long totalSeconds = millis / 1000;
        long ms = millis % 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, ms);
    }
}
