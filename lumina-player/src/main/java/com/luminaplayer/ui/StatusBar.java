package com.luminaplayer.ui;

import com.luminaplayer.player.PlayerController;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Bottom status bar showing current file info and playback status.
 */
public class StatusBar extends HBox {

    private final Label fileInfoLabel;
    private final Label statusLabel;

    public StatusBar(PlayerController controller) {
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER);
        setPadding(new Insets(2, 8, 2, 8));
        setSpacing(8);

        fileInfoLabel = new Label("No file loaded");
        fileInfoLabel.getStyleClass().add("status-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-text");

        getChildren().addAll(fileInfoLabel, spacer, statusLabel);

        // Bind to controller
        controller.currentMediaProperty().addListener((obs, oldInfo, newInfo) -> {
            if (newInfo != null) {
                fileInfoLabel.setText(newInfo.getDisplayTitle());
            } else {
                fileInfoLabel.setText("No file loaded");
            }
        });

        controller.statusTextProperty().addListener((obs, oldText, newText) ->
            statusLabel.setText(newText));
    }
}
