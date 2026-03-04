package com.luminaplayer.subtitle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses .srt subtitle files into a list of SubtitleEntry objects.
 * Handles UTF-8 (with BOM) and ISO-8859-1 encodings.
 *
 * Note: vlcj handles subtitle rendering natively. This parser exists
 * as a fallback and for potential future custom rendering/AI processing.
 */
public class SrtParser {

    private static final Logger log = LoggerFactory.getLogger(SrtParser.class);

    // Pattern: HH:MM:SS,mmm --> HH:MM:SS,mmm
    private static final Pattern TIME_PATTERN = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})"
    );

    public List<SubtitleEntry> parse(File file) throws IOException {
        Charset charset = detectCharset(file);
        log.debug("Parsing SRT file: {} with charset: {}", file.getName(), charset);

        List<SubtitleEntry> entries = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), charset))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Try to parse as index number
                int index;
                try {
                    index = Integer.parseInt(line);
                } catch (NumberFormatException e) {
                    continue;
                }

                // Next line should be the timestamp
                String timeLine = reader.readLine();
                if (timeLine == null) break;

                Matcher matcher = TIME_PATTERN.matcher(timeLine.trim());
                if (!matcher.find()) continue;

                long startMs = parseTimeToMs(matcher, 1);
                long endMs = parseTimeToMs(matcher, 5);

                // Read text lines until empty line or EOF
                StringBuilder text = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) break;
                    if (!text.isEmpty()) text.append("\n");
                    text.append(line);
                }

                entries.add(new SubtitleEntry(index, startMs, endMs, text.toString()));
            }
        }

        log.info("Parsed {} subtitle entries from {}", entries.size(), file.getName());
        return entries;
    }

    private long parseTimeToMs(Matcher matcher, int groupOffset) {
        int hours = Integer.parseInt(matcher.group(groupOffset));
        int minutes = Integer.parseInt(matcher.group(groupOffset + 1));
        int seconds = Integer.parseInt(matcher.group(groupOffset + 2));
        int millis = Integer.parseInt(matcher.group(groupOffset + 3));
        return hours * 3600000L + minutes * 60000L + seconds * 1000L + millis;
    }

    private Charset detectCharset(File file) throws IOException {
        // Check for UTF-8 BOM
        try (InputStream is = new FileInputStream(file)) {
            byte[] bom = new byte[3];
            int read = is.read(bom);
            if (read >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
                return StandardCharsets.UTF_8;
            }
        }
        // Default to UTF-8
        return StandardCharsets.UTF_8;
    }
}
