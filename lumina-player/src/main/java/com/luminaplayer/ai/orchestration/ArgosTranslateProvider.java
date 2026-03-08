package com.luminaplayer.ai.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Offline translation provider using Argos Translate.
 * 
 * Argos Translate uses CTranslate2 (optimized C++ inference) with OpenNMT models —
 * the same transformer architecture family that powers Google Translate's NMT.
 * Each language pack is ~50MB and runs fully offline with no internet needed.
 *
 * Install: pip install argostranslate
 * Install language packs: argospm install translate-ja_en  (Japanese→English)
 *
 * Supports 50+ languages including: ja, zh, ko, ta, te, hi, bn, ar, ru, etc.
 * Speed: ~50-200ms per sentence on CPU (CTranslate2 is extremely fast).
 */
public class ArgosTranslateProvider implements TranslationProvider {

    private static final Logger log = LoggerFactory.getLogger(ArgosTranslateProvider.class);

    private Path pythonPath;
    private volatile Boolean available;
    private volatile String statusMessage;

    /**
     * Creates a provider that auto-detects Python on the system PATH.
     */
    public ArgosTranslateProvider() {
        this.pythonPath = findPython();
    }

    /**
     * Creates a provider with an explicit Python executable path.
     */
    public ArgosTranslateProvider(Path pythonPath) {
        this.pythonPath = pythonPath;
    }

    public void setPythonPath(Path path) {
        this.pythonPath = path;
        this.available = null; // force re-check
    }

    @Override
    public String getName() {
        return "Argos Translate (Offline)";
    }

    @Override
    public String getDescription() {
        return "Offline neural machine translation using CTranslate2 + OpenNMT models (~50MB/language)";
    }

    @Override
    public boolean isAvailable() {
        if (available == null) {
            checkAvailability();
        }
        return Boolean.TRUE.equals(available);
    }

    @Override
    public String getAvailabilityStatus() {
        if (available == null) {
            checkAvailability();
        }
        return statusMessage != null ? statusMessage : "Not checked";
    }

    private void checkAvailability() {
        if (pythonPath == null) {
            available = false;
            statusMessage = "Python not found. Install Python 3.8+ and run: pip install argostranslate";
            return;
        }

        try {
            // Check if argostranslate is importable
            ProcessBuilder pb = new ProcessBuilder(
                pythonPath.toString(), "-c",
                "import argostranslate.translate; " +
                "langs = argostranslate.translate.get_installed_languages(); " +
                "names = [l.name for l in langs]; " +
                "print('OK:' + ','.join(names))"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + b);
            }

            int exit = proc.waitFor();
            if (exit == 0 && output.startsWith("OK:")) {
                String langs = output.substring(3);
                available = true;
                statusMessage = "Argos Translate ready. Installed languages: " +
                    (langs.isEmpty() ? "(none — install with: argospm install translate)" : langs);
                log.info("Argos Translate available: {}", langs);
            } else {
                available = false;
                statusMessage = "Argos Translate not installed. Run: pip install argostranslate";
                log.info("Argos Translate not available: {}", output);
            }
        } catch (Exception e) {
            available = false;
            statusMessage = "Error checking Argos Translate: " + e.getMessage();
            log.warn("Argos Translate check failed", e);
        }
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang)
            throws TranslationException {
        if (text == null || text.isBlank()) return text;

        try {
            String src = mapLangCode(sourceLang);
            String tgt = mapLangCode(targetLang);

            if (!isLanguagePairAvailable(src, tgt)) {
                throw new TranslationException("Argos language pack missing for " + src + " -> " + tgt +
                    ". Install with: argospm install translate-" + src + "_" + tgt);
            }

            // Use Python one-liner for single translation with explicit language pair resolution
            String script = String.format(
                "import argostranslate.translate; " +
                "langs=argostranslate.translate.get_installed_languages(); " +
                "src=next((l for l in langs if l.code=='%s'), None); " +
                "tgt=next((l for l in langs if l.code=='%s'), None); " +
                "tr=src.get_translation(tgt) if src and tgt else None; " +
                "print(tr.translate('%s') if tr else '')",
                src, tgt, escapePython(text));

            ProcessBuilder pb = new ProcessBuilder(pythonPath.toString(), "-c", script);
            pb.redirectErrorStream(true);
            if (isWindows()) {
                pb.environment().put("PYTHONIOENCODING", "utf-8");
            }
            Process proc = pb.start();

            String result;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                result = reader.lines().reduce("", (a, b) -> a + "\n" + b).trim();
            }

            int exit = proc.waitFor();
            if (exit != 0 || result.isEmpty()) {
                throw new TranslationException("Argos Translate failed (exit " + exit + "): " + result);
            }

            return result;
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Argos Translate error: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> translateBatch(List<String> texts, String sourceLang,
                                        String targetLang) throws TranslationException {
        if (texts == null || texts.isEmpty()) return texts;

        String src = mapLangCode(sourceLang);
        String tgt = mapLangCode(targetLang);
        if (!isLanguagePairAvailable(src, tgt)) {
            throw new TranslationException("Argos language pack missing for " + src + " -> " + tgt +
                ". Install with: argospm install translate-" + src + "_" + tgt);
        }

        // For batch translation, write a Python script to translate all at once
        // This avoids the overhead of starting a new Python process per sentence
        try {
            Path tempScript = Files.createTempFile("argos-batch-", ".py");
            Path tempInput = Files.createTempFile("argos-input-", ".txt");
            Path tempOutput = Files.createTempFile("argos-output-", ".txt");

            try {
                // Write input texts (one per line, escaped)
                StringBuilder inputBuilder = new StringBuilder();
                for (String text : texts) {
                    inputBuilder.append(text.replace("\n", " ").replace("\r", "")).append("\n");
                }
                Files.writeString(tempInput, inputBuilder.toString(), StandardCharsets.UTF_8);

                // Write batch translation script
                String script = String.format("""
                    import argostranslate.translate
                    import sys
                    
                    source_lang = '%s'
                    target_lang = '%s'

                    langs = argostranslate.translate.get_installed_languages()
                    src = next((l for l in langs if l.code == source_lang), None)
                    tgt = next((l for l in langs if l.code == target_lang), None)
                    tr = src.get_translation(tgt) if src and tgt else None
                    if tr is None:
                        raise RuntimeError(f'Missing Argos language pack: {source_lang} -> {target_lang}')
                    
                    with open(r'%s', 'r', encoding='utf-8') as f:
                        lines = f.read().strip().split('\\n')
                    
                    results = []
                    for line in lines:
                        line = line.strip()
                        if line:
                            try:
                                translated = tr.translate(line)
                                results.append(translated)
                            except Exception as e:
                                results.append(line)  # Keep original on error
                        else:
                            results.append('')
                    
                    with open(r'%s', 'w', encoding='utf-8') as f:
                        f.write('\\n'.join(results))
                    
                    print('OK:' + str(len(results)))
                    """,
                    src,
                    tgt,
                    tempInput.toString().replace("\\", "\\\\"),
                    tempOutput.toString().replace("\\", "\\\\")
                );
                Files.writeString(tempScript, script, StandardCharsets.UTF_8);

                // Run batch translation
                ProcessBuilder pb = new ProcessBuilder(pythonPath.toString(), tempScript.toString());
                pb.redirectErrorStream(true);
                if (isWindows()) {
                    pb.environment().put("PYTHONIOENCODING", "utf-8");
                }
                Process proc = pb.start();

                String procOutput;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    procOutput = reader.lines().reduce("", (a, b) -> a + b).trim();
                }

                boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    throw new TranslationException("Argos Translate batch timed out (120s)");
                }

                int exit = proc.exitValue();
                if (exit != 0) {
                    throw new TranslationException("Argos Translate batch failed (exit " + exit + "): " + procOutput);
                }

                // Read translated output
                List<String> results = Files.readAllLines(tempOutput, StandardCharsets.UTF_8);

                // Pad or trim to match input size
                List<String> finalResults = new ArrayList<>(texts.size());
                for (int i = 0; i < texts.size(); i++) {
                    finalResults.add(i < results.size() ? results.get(i) : texts.get(i));
                }

                log.info("Argos batch translation complete: {} texts, {} → {}",
                    texts.size(), sourceLang, targetLang);
                return finalResults;

            } finally {
                Files.deleteIfExists(tempScript);
                Files.deleteIfExists(tempInput);
                Files.deleteIfExists(tempOutput);
            }

        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Argos Translate batch error: " + e.getMessage(), e);
        }
    }

    @Override
    public int getMaxCharsPerRequest() {
        return 10000; // Argos handles large batches well since it's local
    }

    /**
     * Returns true when the specific source->target Argos package is installed.
     */
    public boolean isLanguagePairAvailable(String sourceLang, String targetLang) {
        if (pythonPath == null) return false;

        String src = mapLangCode(sourceLang);
        String tgt = mapLangCode(targetLang);

        try {
            String script = String.format(
                "import argostranslate.translate; " +
                "langs=argostranslate.translate.get_installed_languages(); " +
                "s=next((l for l in langs if l.code=='%s'), None); " +
                "t=next((l for l in langs if l.code=='%s'), None); " +
                "ok=(s is not None and t is not None and s.get_translation(t) is not None); " +
                "print('OK' if ok else 'NO')",
                src, tgt
            );

            ProcessBuilder pb = new ProcessBuilder(pythonPath.toString(), "-c", script);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + b).trim();
            }

            int exit = proc.waitFor();
            return exit == 0 && "OK".equals(output);
        } catch (Exception e) {
            log.debug("Argos language pair check failed for {} -> {}", src, tgt, e);
            return false;
        }
    }

    /**
     * Installs a language pack for Argos Translate.
     * Example: installLanguagePack("ja", "en") installs Japanese→English
     */
    public boolean installLanguagePack(String sourceLang, String targetLang,
                                        java.util.function.Consumer<String> progress) {
        if (pythonPath == null) return false;

        String src = mapLangCode(sourceLang);
        String tgt = mapLangCode(targetLang);

        try {
            progress.accept("Installing language pack: " + src + " → " + tgt + "...");

            String script = String.format("""
                import argostranslate.package
                import argostranslate.translate
                
                argostranslate.package.update_package_index()
                available = argostranslate.package.get_available_packages()
                
                pkg = next((p for p in available 
                           if p.from_code == '%s' and p.to_code == '%s'), None)
                
                if pkg:
                    download_path = pkg.download()
                    argostranslate.package.install_from_path(download_path)
                    print('OK:Installed ' + pkg.from_name + ' → ' + pkg.to_name)
                else:
                    print('ERROR:No package found for %s → %s')
                """, src, tgt, src, tgt);

            ProcessBuilder pb = new ProcessBuilder(pythonPath.toString(), "-c", script);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + b).trim();
            }

            proc.waitFor();

            if (output.startsWith("OK:")) {
                progress.accept(output.substring(3));
                available = null; // force re-check
                return true;
            } else {
                progress.accept("Failed: " + output);
                return false;
            }
        } catch (Exception e) {
            progress.accept("Install error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Maps Whisper/ISO language codes to Argos Translate codes.
     */
    private String mapLangCode(String code) {
        if (code == null) return "en";
        return switch (code.toLowerCase()) {
            case "auto" -> "auto";
            case "zh" -> "zh";     // Chinese
            case "ja" -> "ja";     // Japanese
            case "ko" -> "ko";     // Korean
            case "ta" -> "ta";     // Tamil (if available, falls back)
            case "te" -> "te";     // Telugu
            case "hi" -> "hi";     // Hindi
            case "bn" -> "bn";     // Bengali
            case "pa" -> "pa";     // Punjabi
            case "ur" -> "ur";     // Urdu
            case "ar" -> "ar";     // Arabic
            case "ru" -> "ru";     // Russian
            case "uk" -> "uk";     // Ukrainian
            case "el" -> "el";     // Greek
            case "he" -> "he";     // Hebrew
            case "fa" -> "fa";     // Persian
            case "th" -> "th";     // Thai
            case "vi" -> "vi";     // Vietnamese
            case "id" -> "id";     // Indonesian
            case "ms" -> "ms";     // Malay
            case "tl" -> "tl";     // Filipino/Tagalog
            default -> code;       // Passthrough (es, fr, de, it, pt, etc.)
        };
    }

    private static String escapePython(String text) {
        return text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private static Path findPython() {
        // 1) If running inside an activated venv, always prefer that interpreter
        String virtualEnv = System.getenv("VIRTUAL_ENV");
        if (virtualEnv != null && !virtualEnv.isBlank()) {
            Path venvPath = isWindows()
                ? Path.of(virtualEnv, "Scripts", "python.exe")
                : Path.of(virtualEnv, "bin", "python");
            if (Files.isExecutable(venvPath)) {
                return venvPath;
            }
        }

        // 2) Prefer workspace-local virtualenvs (./.venv and ../.venv)
        String userDir = System.getProperty("user.dir", "");
        if (!userDir.isBlank()) {
            Path cwd = Path.of(userDir);
            List<Path> venvCandidates = isWindows()
                ? List.of(
                    cwd.resolve(".venv").resolve("Scripts").resolve("python.exe"),
                    cwd.getParent() != null
                        ? cwd.getParent().resolve(".venv").resolve("Scripts").resolve("python.exe")
                        : null
                )
                : List.of(
                    cwd.resolve(".venv").resolve("bin").resolve("python"),
                    cwd.getParent() != null
                        ? cwd.getParent().resolve(".venv").resolve("bin").resolve("python")
                        : null
                );

            for (Path candidate : venvCandidates) {
                if (candidate != null && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }

        // 3) Fallback: probe PATH
        String[] candidates = isWindows()
            ? new String[]{"python.exe", "python3.exe", "py.exe"}
            : new String[]{"python3", "python"};

        for (String name : candidates) {
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null) continue;
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
