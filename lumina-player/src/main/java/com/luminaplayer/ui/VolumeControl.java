package com.luminaplayer.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;

/**
 * Volume slider with mute toggle button.
 */
public class VolumeControl extends HBox {

    private final Button muteButton;
    private final Slider volumeSlider;
    private final Label volumeLabel;

    private Runnable onMuteToggle;
    private java.util.function.IntConsumer onVolumeChange;
    private boolean updatingFromExternal = false;

    public VolumeControl() {
        getStyleClass().add("volume-control");
        setAlignment(Pos.CENTER);
        setSpacing(4);

        muteButton = new Button("\uD83D\uDD0A"); // speaker emoji as placeholder
        muteButton.getStyleClass().add("control-button");
        muteButton.setFocusTraversable(false);
        muteButton.setOnAction(e -> {
            if (onMuteToggle != null) onMuteToggle.run();
        });

        volumeSlider = new Slider(0, 100, 80);
        volumeSlider.setPrefWidth(100);
        volumeSlider.getStyleClass().add("volume-slider");

        volumeLabel = new Label("80%");
        volumeLabel.getStyleClass().add("volume-label");
        volumeLabel.setMinWidth(35);

        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!updatingFromExternal && onVolumeChange != null) {
                onVolumeChange.accept(newVal.intValue());
            }
            volumeLabel.setText(newVal.intValue() + "%");
        });

        // Scroll wheel adjusts volume
        volumeSlider.setOnScroll(e -> {
            double delta = e.getDeltaY() > 0 ? 5 : -5;
            volumeSlider.setValue(volumeSlider.getValue() + delta);
        });

        getChildren().addAll(muteButton, volumeSlider, volumeLabel);
    }

    public void setVolume(int percent) {
        updatingFromExternal = true;
        try {
            volumeSlider.setValue(percent);
        } finally {
            updatingFromExternal = false;
        }
    }

    public int getVolume() {
        return (int) volumeSlider.getValue();
    }

    public void setMuted(boolean muted) {
        muteButton.setText(muted ? "\uD83D\uDD07" : "\uD83D\uDD0A");
    }

    public void setOnMuteToggle(Runnable action) {
        this.onMuteToggle = action;
    }

    public void setOnVolumeChange(java.util.function.IntConsumer action) {
        this.onVolumeChange = action;
    }

    public Slider getSlider() {
        return volumeSlider;
    }
}
