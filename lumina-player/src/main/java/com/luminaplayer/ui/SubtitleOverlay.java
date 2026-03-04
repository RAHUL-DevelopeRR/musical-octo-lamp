package com.luminaplayer.ui;

import com.luminaplayer.player.PlayerController;
import com.luminaplayer.subtitle.DsrtCue;
import com.luminaplayer.subtitle.DsrtFile;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overlay for dynamically displaying .dsrt subtitle cues on top of the video.
 * Uses a Timeline polling at 100ms intervals to sync with playback time.
 * Mouse-transparent so it does not intercept clicks on the video.
 */
public class SubtitleOverlay extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(SubtitleOverlay.class);
    private static final long POLL_INTERVAL_MS = 100;

    private final Label subtitleLabel;
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

        // Configure subtitle label
        subtitleLabel = new Label();
        subtitleLabel.getStyleClass().add("subtitle-overlay-label");
        subtitleLabel.setWrapText(true);
        subtitleLabel.setVisible(false);

        // Apply inline styles as fallback (CSS class overrides if loaded)
        subtitleLabel.setStyle(
            "-fx-text-fill: #FFFFFF;" +
            "-fx-font-size: 22px;" +
            "-fx-font-weight: bold;" +
            "-fx-font-family: 'Segoe UI', 'Arial', sans-serif;" +
            "-fx-background-color: rgba(0,0,0,0.70);" +
            "-fx-background-radius: 6;" +
            "-fx-padding: 6 16 6 16;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 4, 0, 0, 2);" +
            "-fx-text-alignment: center;"
        );

        // Bind max width programmatically (JavaFX CSS doesn't support percentages)
        subtitleLabel.maxWidthProperty().bind(widthProperty().multiply(0.8));

        // Bottom padding for the overlay container
        setStyle("-fx-padding: 0 0 40 0;");

        getChildren().add(subtitleLabel);

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
        subtitleLabel.setText("");
        subtitleLabel.setVisible(false);
        log.info("Subtitle overlay deactivated");
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
            subtitleLabel.setText("");
            subtitleLabel.setVisible(false);
            return;
        }

        long currentMs = playerController.currentTimeProperty().get();
        DsrtCue cue = dsrtFile.getActiveCue(currentMs);

        if (cue != null) {
            if (cue != lastDisplayedCue) {
                subtitleLabel.setText(cue.text());
                subtitleLabel.setVisible(true);
                lastDisplayedCue = cue;
            }
        } else {
            if (lastDisplayedCue != null) {
                subtitleLabel.setText("");
                subtitleLabel.setVisible(false);
                lastDisplayedCue = null;
            }
        }
    }
}
