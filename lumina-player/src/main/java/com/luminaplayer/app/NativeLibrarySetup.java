package com.luminaplayer.app;

import com.sun.jna.NativeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configures native library paths for libVLC before any vlcj classes are loaded.
 * MUST be called in main() before Application.launch().
 *
 * Search order:
 *  1. Bundled native libraries in platform-specific subdirectory
 *  2. System-installed VLC (discovered automatically by vlcj/JNA)
 */
public final class NativeLibrarySetup {

    private static final Logger log = LoggerFactory.getLogger(NativeLibrarySetup.class);

    private NativeLibrarySetup() {
    }

    public static void configure() {
        Path appDir = resolveAppDirectory();
        log.info("Application directory: {}", appDir);

        String platformDir = getPlatformDirectory();
        log.info("Platform native directory: {}", platformDir);

        Path[] candidates = {
            appDir.resolve("native").resolve(platformDir),
            appDir.resolve("lumina-player").resolve("native").resolve(platformDir),
            appDir.getParent() != null ? appDir.getParent().resolve("native").resolve(platformDir) : null
        };

        for (Path candidate : candidates) {
            if (candidate != null && tryConfigurePath(candidate)) {
                return;
            }
        }

        // On macOS, also check common install locations
        if (isMac()) {
            Path[] macPaths = {
                Path.of("/Applications/VLC.app/Contents/MacOS/lib"),
            };
            for (Path p : macPaths) {
                if (tryConfigurePath(p)) return;
            }
        }

        // On Linux, check standard paths
        if (isLinux()) {
            Path[] linuxPaths = {
                Path.of("/usr/lib"),
                Path.of("/usr/lib/x86_64-linux-gnu"),
                Path.of("/usr/local/lib"),
            };
            for (Path p : linuxPaths) {
                if (Files.exists(p.resolve("libvlc.so")) || Files.exists(p.resolve("libvlc.so.5"))) {
                    String absPath = p.toAbsolutePath().toString();
                    log.info("Found VLC libraries at: {}", absPath);
                    NativeLibrary.addSearchPath("libvlc", absPath);
                    NativeLibrary.addSearchPath("libvlccore", absPath);
                    return;
                }
            }
        }

        log.info("No bundled VLC libraries found. Falling back to system VLC discovery.");
    }

    private static boolean tryConfigurePath(Path nativeDir) {
        if (nativeDir == null || !Files.isDirectory(nativeDir)) {
            return false;
        }

        String libName = isWindows() ? "libvlc.dll"
            : isMac() ? "libvlc.dylib"
            : "libvlc.so";

        Path libvlc = nativeDir.resolve(libName);
        if (!Files.exists(libvlc)) {
            // Also check without lib prefix on some platforms
            if (!isWindows()) {
                libvlc = nativeDir.resolve("libvlc.so.5");
            }
        }

        if (Files.exists(libvlc)) {
            String absPath = nativeDir.toAbsolutePath().toString();
            log.info("Found VLC libraries at: {}", absPath);

            NativeLibrary.addSearchPath("libvlc", absPath);
            NativeLibrary.addSearchPath("libvlccore", absPath);

            Path pluginsDir = nativeDir.resolve("plugins");
            if (Files.exists(pluginsDir)) {
                String pluginsPath = pluginsDir.toAbsolutePath().toString();
                System.setProperty("VLC_PLUGIN_PATH", pluginsPath);
                log.info("VLC plugin path: {}", pluginsPath);
            }

            return true;
        }

        return false;
    }

    private static String getPlatformDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        if (os.contains("win")) {
            return arch.contains("64") ? "win-x64" : "win-x86";
        } else if (os.contains("mac")) {
            return arch.contains("aarch64") ? "mac-arm64" : "mac-x64";
        } else {
            return arch.contains("64") ? "linux-x64" : "linux-x86";
        }
    }

    private static Path resolveAppDirectory() {
        // Try to resolve from the CodeSource location (works for JAR and IDE)
        try {
            Path codeSource = Path.of(
                NativeLibrarySetup.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()
            );
            if (Files.isRegularFile(codeSource)) {
                // Running from JAR
                return codeSource.getParent();
            } else {
                // Running from IDE (classes directory)
                return codeSource;
            }
        } catch (Exception e) {
            log.debug("Could not resolve from CodeSource, using user.dir", e);
        }

        return Path.of(System.getProperty("user.dir"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }
}
