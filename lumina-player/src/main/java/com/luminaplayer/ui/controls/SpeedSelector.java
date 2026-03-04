package com.luminaplayer.ui.controls;

import com.luminaplayer.app.AppConfig;
import javafx.beans.property.FloatProperty;
import javafx.beans.property.SimpleFloatProperty;
import javafx.scene.control.ComboBox;

/**
 * ComboBox for selecting playback speed.
 */
public class SpeedSelector extends ComboBox<String> {

    private final FloatProperty selectedRate = new SimpleFloatProperty(1.0f);

    public SpeedSelector() {
        getStyleClass().add("speed-selector");

        for (float rate : AppConfig.PLAYBACK_RATES) {
            getItems().add(formatRate(rate));
        }

        setValue(formatRate(1.0f));
        setPrefWidth(80);

        setOnAction(e -> {
            String selected = getValue();
            if (selected != null) {
                float rate = parseRate(selected);
                selectedRate.set(rate);
            }
        });
    }

    public void setRate(float rate) {
        setValue(formatRate(rate));
        selectedRate.set(rate);
    }

    public FloatProperty selectedRateProperty() {
        return selectedRate;
    }

    private static String formatRate(float rate) {
        if (rate == (int) rate) {
            return String.format("%dx", (int) rate);
        }
        return String.format("%.2fx", rate);
    }

    private static float parseRate(String text) {
        try {
            return Float.parseFloat(text.replace("x", ""));
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }
}
