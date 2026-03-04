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
 * Translation provider backed by a LibreTranslate instance (local or remote).
 * LibreTranslate is a free, open-source machine translation API.
 * 
 * Typical setup: run LibreTranslate locally via Docker:
 *   docker run -d -p 5000:5000 libretranslate/libretranslate
 *
 * Supports all major languages including Japanese, Tamil, Chinese, Korean, etc.
 */
public class LibreTranslateProvider implements TranslationProvider {

    private static final Logger log = LoggerFactory.getLogger(LibreTranslateProvider.class);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * Creates a provider with default local endpoint (http://localhost:5000).
     */
    public LibreTranslateProvider() {
        this("http://localhost:5000", null);
    }

    /**
     * Creates a provider with a custom endpoint and optional API key.
     *
     * @param baseUrl LibreTranslate server URL (e.g. "http://localhost:5000")
     * @param apiKey  optional API key for hosted instances; null for local
     */
    public LibreTranslateProvider(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "http://localhost:5000";
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getName() {
        return "LibreTranslate";
    }

    @Override
    public String getDescription() {
        return "Open-source machine translation (LibreTranslate at " + baseUrl + ")";
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/languages"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("LibreTranslate not available at {}: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    @Override
    public String getAvailabilityStatus() {
        if (isAvailable()) {
            return "Connected to LibreTranslate at " + baseUrl;
        }
        return "Cannot reach LibreTranslate at " + baseUrl +
                ". Start with: docker run -d -p 5000:5000 libretranslate/libretranslate";
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang)
            throws TranslationException {
        if (text == null || text.isBlank()) return text;

        try {
            String jsonBody = buildJsonBody(text, sourceLang, targetLang);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/translate"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new TranslationException(
                        "LibreTranslate returned HTTP " + response.statusCode() +
                                ": " + response.body());
            }

            return extractTranslatedText(response.body());

        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("LibreTranslate request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> translateBatch(List<String> texts, String sourceLang,
                                        String targetLang) throws TranslationException {
        // LibreTranslate doesn't support native batch, so we translate one-by-one
        // but we can join short texts with a separator for efficiency
        if (texts.size() <= 3) {
            List<String> results = new ArrayList<>();
            for (String text : texts) {
                results.add(translate(text, sourceLang, targetLang));
            }
            return results;
        }

        // Join with a unique separator that won't appear in subtitles
        String separator = "\n|||SPLIT|||\n";
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) joined.append(separator);
            joined.append(texts.get(i));
        }

        String translated = translate(joined.toString(), sourceLang, targetLang);

        // Split back
        String[] parts = translated.split("\\|\\|\\|SPLIT\\|\\|\\|");
        List<String> results = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (i < parts.length) {
                results.add(parts[i].trim());
            } else {
                // Fallback: translate individually if split failed
                results.add(translate(texts.get(i), sourceLang, targetLang));
            }
        }
        return results;
    }

    @Override
    public int getMaxCharsPerRequest() {
        return 5000;
    }

    /**
     * Builds the JSON request body for the /translate endpoint.
     */
    private String buildJsonBody(String text, String sourceLang, String targetLang) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"q\":").append(jsonEscape(text)).append(",");
        sb.append("\"source\":").append(jsonEscape(sourceLang)).append(",");
        sb.append("\"target\":").append(jsonEscape(targetLang)).append(",");
        sb.append("\"format\":\"text\"");
        if (apiKey != null && !apiKey.isBlank()) {
            sb.append(",\"api_key\":").append(jsonEscape(apiKey));
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Extracts the "translatedText" field from the JSON response.
     * Simple manual parsing to avoid external JSON dependency.
     */
    private String extractTranslatedText(String json) throws TranslationException {
        String key = "\"translatedText\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            throw new TranslationException("No 'translatedText' in response: " + json);
        }

        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) {
            throw new TranslationException("Malformed response: " + json);
        }

        // Find the opening quote of the value
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) {
            throw new TranslationException("Malformed response value: " + json);
        }

        // Find the closing quote, handling escaped quotes
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

        return value.toString();
    }

    /**
     * JSON-escapes a string value (wraps in quotes, escapes special chars).
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
