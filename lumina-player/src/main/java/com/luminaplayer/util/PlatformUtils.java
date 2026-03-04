package com.luminaplayer.util;

/**
 * Platform detection utilities.
 */
public final class PlatformUtils {

    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    private PlatformUtils() {
    }

    public static boolean isWindows() {
        return OS.contains("win");
    }

    public static boolean isMac() {
        return OS.contains("mac");
    }

    public static boolean isLinux() {
        return OS.contains("nux") || OS.contains("nix");
    }
}
