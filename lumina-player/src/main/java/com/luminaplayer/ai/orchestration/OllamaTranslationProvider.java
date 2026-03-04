package com.luminaplayer.ai.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Translation provider backed by a local Ollama LLM instance.
 * Uses large language models (e.g., llama3, mistral, qwen2) for
 * high-quality contextual translation, especially effective for
 * nuanced languages like Japanese, Tamil, and Chinese.
 *
 * Setup: install Ollama (https://ollama.ai) and pull a model:
 *   ollama pull llama3
 *   ollama pull qwen2
 */
public class OllamaTranslationProvider implements TranslationProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaTranslationProvider.class);

    private final String baseUrl;
    private final String modelName;
    private final HttpClient httpClient;

    /**
     * Creates a provider with default settings (localhost:11434, llama3).
     */
    public OllamaTranslationProvider() {
        this("http://localhost:11434", "llama3");
    }

    /**
     * Creates a provider with custom endpoint and model.
     *
     * @param baseUrl   Ollama server URL (e.g. "http://localhost:11434")
     * @param modelName model to use (e.g. "llama3", "mistral", "qwen2")
     */
    public OllamaTranslationProvider(String baseUrl, String modelName) {
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "http://localhost:11434";
        this.modelName = modelName != null ? modelName : "llama3";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getName() {
        return "Ollama (" + modelName + ")";
    }

    @Override
    public String getDescription() {
        return "Local LLM translation via Ollama using " + modelName +
                " — best for nuanced, context-aware translations";
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;

            // Check if the specified model is available
            return response.body().contains("\"" + modelName + "\"") ||
                   response.body().contains("\"" + modelName + ":");
        } catch (Exception e) {
            log.debug("Ollama not available at {}: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    @Override
    public String getAvailabilityStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Ollama server not responding at " + baseUrl;
            }

            boolean modelFound = response.body().contains("\"" + modelName + "\"") ||
                                  response.body().contains("\"" + modelName + ":");
            if (modelFound) {
                return "Connected to Ollama at " + baseUrl + " with model " + modelName;
            } else {
                return "Ollama running but model '" + modelName +
                        "' not found. Pull it with: ollama pull " + modelName;
            }
        } catch (Exception e) {
            return "Cannot reach Ollama at " + baseUrl +
                    ". Install from https://ollama.ai and run: ollama serve";
        }
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang)
            throws TranslationException {
        if (text == null || text.isBlank()) return text;

        try {
            String langName = getLanguageName(sourceLang);
            String targetName = getLanguageName(targetLang);

            String systemPrompt = String.format(
                    "You are an expert subtitle translator. Translate the following %s subtitle text to %s. " +
                    "Rules: 1) Output ONLY the translated text, no explanations. " +
                    "2) Preserve the original meaning and tone. " +
                    "3) Keep translations concise for subtitle display. " +
                    "4) Preserve any line breaks in the input. " +
                    "5) If the text contains names, transliterate them appropriately.",
                    langName, targetName
            );

            String jsonBody = buildGenerateBody(systemPrompt, text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new TranslationException(
                        "Ollama returned HTTP " + response.statusCode() + ": " + response.body());
            }

            return extractGenerateResponse(response.body());

        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Ollama translation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> translateBatch(List<String> texts, String sourceLang,
                                        String targetLang) throws TranslationException {
        // For LLMs, batch by joining with numbered lines for better context
        if (texts.size() <= 2) {
            List<String> results = new ArrayList<>();
            for (String text : texts) {
                results.add(translate(text, sourceLang, targetLang));
            }
            return results;
        }

        String langName = getLanguageName(sourceLang);
        String targetName = getLanguageName(targetLang);

        StringBuilder prompt = new StringBuilder();
        prompt.append("Translate each numbered subtitle line from ").append(langName)
              .append(" to ").append(targetName).append(". ")
              .append("Output ONLY the translations, one per line, prefixed with the same number. ")
              .append("Keep translations concise for subtitle display.\n\n");

        for (int i = 0; i < texts.size(); i++) {
            prompt.append(i + 1).append(". ").append(texts.get(i)).append("\n");
        }

        try {
            String systemPrompt = "You are an expert subtitle translator. " +
                    "Translate each numbered line and output ONLY numbered translated lines.";

            String jsonBody = buildGenerateBody(systemPrompt, prompt.toString());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new TranslationException("Ollama batch failed: HTTP " + response.statusCode());
            }

            String fullResponse = extractGenerateResponse(response.body());
            return parseBatchResponse(fullResponse, texts.size(), texts, sourceLang, targetLang);

        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Ollama batch translation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int getMaxCharsPerRequest() {
        return 3000; // LLMs have lower effective context for quality
    }

    /**
     * Builds the JSON body for Ollama /api/generate endpoint.
     */
    private String buildGenerateBody(String systemPrompt, String userPrompt) {
        return "{" +
                "\"model\":" + jsonEscape(modelName) + "," +
                "\"system\":" + jsonEscape(systemPrompt) + "," +
                "\"prompt\":" + jsonEscape(userPrompt) + "," +
                "\"stream\":false," +
                "\"options\":{\"temperature\":0.3,\"top_p\":0.9}" +
                "}";
    }

    /**
     * Extracts the "response" field from Ollama's /api/generate JSON output.
     */
    private String extractGenerateResponse(String json) throws TranslationException {
        // Ollama returns {"model":"...","response":"...","done":true,...}
        String key = "\"response\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            throw new TranslationException("No 'response' field in Ollama output: " +
                    json.substring(0, Math.min(200, json.length())));
        }

        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) {
            throw new TranslationException("Malformed Ollama response");
        }

        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) {
            throw new TranslationException("Malformed response value");
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case '/' -> value.append('/');
                    default -> { value.append('\\'); value.append(c); }
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }

        return value.toString().trim();
    }

    /**
     * Parses numbered batch response back into individual translations.
     * Falls back to individual translation if parsing fails.
     */
    private List<String> parseBatchResponse(String response, int expectedSize,
                                             List<String> originals,
                                             String sourceLang, String targetLang)
            throws TranslationException {
        String[] lines = response.split("\n");
        List<String> results = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Remove number prefix like "1. " or "1) "
            String cleaned = trimmed.replaceFirst("^\\d+[.):]\\s*", "");
            if (!cleaned.isEmpty()) {
                results.add(cleaned);
            }
        }

        // If we got the right count, return
        if (results.size() == expectedSize) {
            return results;
        }

        // If parsing failed, fall back to individual translations
        log.warn("Batch parse returned {} results instead of {}, falling back to individual",
                results.size(), expectedSize);
        List<String> fallback = new ArrayList<>();
        for (String text : originals) {
            fallback.add(translate(text, sourceLang, targetLang));
        }
        return fallback;
    }

    /**
     * Converts language code to human-readable name for the LLM prompt.
     */
    private static String getLanguageName(String code) {
        return switch (code.toLowerCase()) {
            case "en" -> "English";
            case "ja" -> "Japanese";
            case "zh" -> "Chinese";
            case "ko" -> "Korean";
            case "ta" -> "Tamil";
            case "te" -> "Telugu";
            case "hi" -> "Hindi";
            case "bn" -> "Bengali";
            case "kn" -> "Kannada";
            case "ml" -> "Malayalam";
            case "mr" -> "Marathi";
            case "gu" -> "Gujarati";
            case "pa" -> "Punjabi";
            case "ur" -> "Urdu";
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "de" -> "German";
            case "it" -> "Italian";
            case "pt" -> "Portuguese";
            case "ru" -> "Russian";
            case "ar" -> "Arabic";
            case "nl" -> "Dutch";
            case "pl" -> "Polish";
            case "sv" -> "Swedish";
            case "tr" -> "Turkish";
            case "cs" -> "Czech";
            case "el" -> "Greek";
            case "ro" -> "Romanian";
            case "hu" -> "Hungarian";
            case "uk" -> "Ukrainian";
            case "id" -> "Indonesian";
            case "th" -> "Thai";
            case "vi" -> "Vietnamese";
            case "fa" -> "Persian";
            case "he" -> "Hebrew";
            case "fi" -> "Finnish";
            case "da" -> "Danish";
            case "no" -> "Norwegian";
            case "ms" -> "Malay";
            case "tl" -> "Filipino";
            case "ca" -> "Catalan";
            case "my" -> "Burmese";
            case "ne" -> "Nepali";
            case "si" -> "Sinhala";
            case "auto" -> "the source language";
            default -> code;
        };
    }

    /**
     * JSON-escapes a string value.
     */
    private static String jsonEscape(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
