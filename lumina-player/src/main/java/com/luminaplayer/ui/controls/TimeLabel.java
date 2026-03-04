package com.luminaplayer.ui.controls;

import com.luminaplayer.util.TimeFormatter;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.scene.control.Label;

/**
 * Label displaying formatted time as "current / total".
 */
public class TimeLabel extends Label {

    private final LongProperty currentTime = new SimpleLongProperty(0);
    private final LongProperty totalDuration = new SimpleLongProperty(0);

    public TimeLabel() {
        getStyleClass().add("time-label");
        setText("00:00 / 00:00");

        currentTime.addListener((obs, oldVal, newVal) -> updateText());
        totalDuration.addListener((obs, oldVal, newVal) -> updateText());
    }

    private void updateText() {
        String current = TimeFormatter.format(currentTime.get());
        String total = TimeFormatter.format(totalDuration.get());
        setText(current + " / " + total);
    }

    public LongProperty currentTimeProperty() { return currentTime; }
    public LongProperty totalDurationProperty() { return totalDuration; }
}
