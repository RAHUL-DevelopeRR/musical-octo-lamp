package com.luminaplayer.ui;

import com.luminaplayer.player.PlayerController;
import com.luminaplayer.subtitle.DsrtCue;
import com.luminaplayer.subtitle.DsrtFile;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Overlay for dynamically displaying .dsrt subtitle cues on top of the video.
 * Uses a Timeline polling at 100ms intervals to sync with playback time.
 * Mouse-transparent so it does not intercept clicks on the video.
 */
public class SubtitleOverlay extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(SubtitleOverlay.class);
    private static final long POLL_INTERVAL_MS = 100;

    private final Label translatedLabel;
    private final Label originalLabel;
    private final VBox subtitleBox;
    private final PlayerController playerController;
    private DsrtFile dsrtFile;
    private Timeline pollTimeline;
    private DsrtCue lastDisplayedCue;

    public SubtitleOverlay(PlayerController playerController) {
        this.playerController = playerController;

        // Configure overlay container
        setAlignment(Pos.BOTTOM_CENTER);
        setPickOnBounds(false);
        setMouseTransparent(true);
        getStyleClass().add("subtitle-overlay");

        // Translated (English) label — white, larger, on top
        translatedLabel = new Label();
        translatedLabel.getStyleClass().add("subtitle-overlay-label");
        translatedLabel.setWrapText(true);

        // Original (native language) label — colored, smaller, on bottom
        originalLabel = new Label();
        originalLabel.getStyleClass().add("subtitle-overlay-original-label");
        originalLabel.setWrapText(true);

        translatedLabel.maxWidthProperty().bind(widthProperty().multiply(0.8));
        originalLabel.maxWidthProperty().bind(widthProperty().multiply(0.8));

        getStyleClass().add("subtitle-overlay-container");

        // Unified container: black rounded rectangle with both labels inside
        subtitleBox = new VBox(4, translatedLabel, originalLabel);
        subtitleBox.setAlignment(Pos.CENTER);
        subtitleBox.getStyleClass().add("subtitle-box");
        subtitleBox.setMaxWidth(USE_PREF_SIZE);
        subtitleBox.setMaxHeight(USE_PREF_SIZE);
        subtitleBox.maxWidthProperty().bind(widthProperty().multiply(0.82));
        subtitleBox.setVisible(false);
        subtitleBox.setMouseTransparent(true);
        getChildren().add(subtitleBox);

        // Create the polling timeline (starts inactive)
        pollTimeline = new Timeline(new KeyFrame(Duration.millis(POLL_INTERVAL_MS),
            e -> updateSubtitle()));
        pollTimeline.setCycleCount(Timeline.INDEFINITE);
    }

    /**
     * Activates the overlay with a DsrtFile, starting the polling timeline.
     */
    public void activate(DsrtFile dsrtFile) {
        this.dsrtFile = dsrtFile;
        this.lastDisplayedCue = null;
        setVisible(true);
        pollTimeline.play();
        log.info("Subtitle overlay activated ({} cues available)", dsrtFile.getCueCount());
    }

    /**
     * Deactivates the overlay, stopping the polling timeline and hiding the label.
     */
    public void deactivate() {
        pollTimeline.stop();
        this.dsrtFile = null;
        this.lastDisplayedCue = null;
        translatedLabel.setText("");
        originalLabel.setText("");
        translatedLabel.setVisible(false);
        originalLabel.setVisible(false);
        subtitleBox.setVisible(false);
        log.info("Subtitle overlay deactivated");
    }

    /**
     * Disposes the overlay, stopping the polling timeline permanently.
     */
    public void dispose() {
        deactivate();
        pollTimeline.stop();
    }

    /**
     * Toggles overlay visibility without affecting the active DsrtFile.
     */
    public void toggleVisibility() {
        if (dsrtFile == null) return;
        if (isVisible()) {
            setVisible(false);
            pollTimeline.pause();
        } else {
            setVisible(true);
            pollTimeline.play();
        }
    }

    /**
     * Returns whether the overlay is currently active with a DsrtFile.
     */
    public boolean isActive() {
        return dsrtFile != null;
    }

    private void updateSubtitle() {
        if (dsrtFile == null) {
            translatedLabel.setText("");
            originalLabel.setText("");
            translatedLabel.setVisible(false);
            originalLabel.setVisible(false);
            subtitleBox.setVisible(false);
            return;
        }

        long currentMs = playerController.currentTimeProperty().get();
        DsrtCue cue = dsrtFile.getActiveCue(currentMs);

        if (cue != null) {
            if (cue != lastDisplayedCue) {
                String text = cue.text() == null ? "" : cue.text();
                SubtitleLines lines = splitSubtitleLines(text);
                translatedLabel.setText(lines.translated());
                translatedLabel.setVisible(!lines.translated().isBlank());
                originalLabel.setText(lines.nativeText());
                originalLabel.setVisible(!lines.nativeText().isBlank());
                subtitleBox.setVisible(translatedLabel.isVisible() || originalLabel.isVisible());
                lastDisplayedCue = cue;
            }
        } else {
            if (lastDisplayedCue != null) {
                translatedLabel.setText("");
                originalLabel.setText("");
                translatedLabel.setVisible(false);
                originalLabel.setVisible(false);
                subtitleBox.setVisible(false);
                lastDisplayedCue = null;
            }
        }
    }

    private SubtitleLines splitSubtitleLines(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isBlank()) {
            return new SubtitleLines("", "");
        }

        // Primary format produced by orchestrator: translated + "\n[original]"
        int bracketSeparator = text.indexOf("\n[");
        if (bracketSeparator > 0 && text.endsWith("]")) {
            String translated = text.substring(0, bracketSeparator).trim();
            String nativeText = text.substring(bracketSeparator + 2, text.length() - 1).trim();
            return new SubtitleLines(translated, nativeText);
        }

        // Fallback inline format: translated [original]
        int inlineOpen = text.lastIndexOf("[");
        if (inlineOpen > 0 && text.endsWith("]")) {
            String translated = text.substring(0, inlineOpen).trim();
            String nativeText = text.substring(inlineOpen + 1, text.length() - 1).trim();
            if (!translated.isBlank() && !nativeText.isBlank()) {
                return new SubtitleLines(translated, nativeText);
            }
        }

        // Generic two-line fallback: prefer English on top and native on bottom.
        List<String> lines = Arrays.stream(text.split("\\R"))
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();

        if (lines.size() >= 2) {
            String first = lines.get(0);
            String second = lines.get(1);
            boolean firstIsNative = isNonLatin(first);
            boolean secondIsNative = isNonLatin(second);

            if (firstIsNative && secondIsNative) {
                // Both lines are non-Latin — show combined in native label
                return new SubtitleLines("", first);
            }
            if (firstIsNative && !secondIsNative) {
                return new SubtitleLines(second, first);
            }
            if (!firstIsNative && secondIsNative) {
                return new SubtitleLines(first, second);
            }
            return new SubtitleLines(first, second);
        }

        // If single line is non-Latin, show in native label position
        if (isNonLatin(text)) {
            return new SubtitleLines("", text);
        }

        return new SubtitleLines(text, "");
    }

    /**
     * Detects non-Latin text (CJK, Japanese, Korean, Thai, Arabic, Devanagari, etc.)
     */
    private boolean isNonLatin(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.codePoints().anyMatch(cp ->
            (cp >= 0x3040 && cp <= 0x309F) || // Hiragana
            (cp >= 0x30A0 && cp <= 0x30FF) || // Katakana
            (cp >= 0x4E00 && cp <= 0x9FFF) || // CJK Unified Ideographs
            (cp >= 0xAC00 && cp <= 0xD7AF) || // Hangul (Korean)
            (cp >= 0x0E00 && cp <= 0x0E7F) || // Thai
            (cp >= 0x0600 && cp <= 0x06FF) || // Arabic
            (cp >= 0x0900 && cp <= 0x097F)    // Devanagari
        );
    }

    private record SubtitleLines(String translated, String nativeText) {
    }
}
