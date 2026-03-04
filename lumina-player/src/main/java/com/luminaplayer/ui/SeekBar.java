package com.luminaplayer.ui;

import com.luminaplayer.util.TimeFormatter;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Popup;

/**
 * Seek slider for media position.
 * Detaches from position updates during user drag to prevent jitter.
 * Shows a time tooltip popup when hovering over the seek bar.
 */
public class SeekBar extends HBox {

    private final Slider slider;
    private boolean dragging = false;

    private Runnable onSeekAction;

    // Time tooltip popup
    private final Popup timeTooltip;
    private final Label timeTooltipLabel;
    private final LongProperty totalDuration = new SimpleLongProperty(0);

    public SeekBar() {
        getStyleClass().add("seek-bar");

        slider = new Slider(0, 1.0, 0);
        slider.getStyleClass().add("seek-slider");
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setMaxWidth(Double.MAX_VALUE);

        slider.setOnMousePressed(e -> dragging = true);
        slider.setOnMouseReleased(e -> {
            dragging = false;
            if (onSeekAction != null) {
                onSeekAction.run();
            }
        });

        // Also handle value change by click (not drag)
        slider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging && dragging) {
                dragging = false;
                if (onSeekAction != null) {
                    onSeekAction.run();
                }
            }
        });

        // --- Time tooltip popup ---
        timeTooltipLabel = new Label("0:00");
        timeTooltipLabel.setStyle(
            "-fx-background-color: #333333;" +
            "-fx-text-fill: #e0e0e0;" +
            "-fx-padding: 4 8 4 8;" +
            "-fx-font-size: 12px;" +
            "-fx-font-family: 'Consolas', 'Courier New', monospace;" +
            "-fx-background-radius: 4;" +
            "-fx-border-color: #555555;" +
            "-fx-border-radius: 4;" +
            "-fx-border-width: 1;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 6, 0, 0, 2);"
        );

        timeTooltip = new Popup();
        timeTooltip.getContent().add(timeTooltipLabel);
        timeTooltip.setAutoHide(true);

        // Show tooltip on mouse move over the slider
        slider.setOnMouseMoved(e -> {
            if (totalDuration.get() <= 0) return;

            // Calculate position relative to slider width
            Bounds sliderBounds = slider.getBoundsInLocal();
            double relativeX = e.getX() / sliderBounds.getWidth();
            relativeX = Math.max(0, Math.min(1, relativeX));

            // Calculate time at this position
            long timeAtPosition = (long) (relativeX * totalDuration.get());
            timeTooltipLabel.setText(TimeFormatter.format(timeAtPosition));

            // Position tooltip above the cursor
            Point2D screenPoint = slider.localToScreen(e.getX(), e.getY());
            if (screenPoint != null) {
                timeTooltip.show(slider,
                    screenPoint.getX() - 25,
                    screenPoint.getY() - 35);
            }
        });

        slider.setOnMouseExited(e -> timeTooltip.hide());

        getChildren().add(slider);
    }

    /**
     * Update the slider position (called from the player controller).
     * Ignored while the user is dragging.
     */
    public void updatePosition(float position) {
        if (!dragging) {
            slider.setValue(position);
        }
    }

    public double getSeekPosition() {
        return slider.getValue();
    }

    public void setOnSeek(Runnable action) {
        this.onSeekAction = action;
    }

    public Slider getSlider() {
        return slider;
    }

    public LongProperty totalDurationProperty() {
        return totalDuration;
    }
}
