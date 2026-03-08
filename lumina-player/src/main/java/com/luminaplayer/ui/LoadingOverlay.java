package com.luminaplayer.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class LoadingOverlay extends StackPane {

    private final Label messageLabel;

    public LoadingOverlay() {
        getStyleClass().add("loading-overlay");
        setMouseTransparent(true);
        setVisible(false);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        spinner.getStyleClass().add("loading-overlay-spinner");
        spinner.setPrefSize(70, 70);

        messageLabel = new Label("Generating subtitles...");
        messageLabel.getStyleClass().add("loading-overlay-label");

        VBox content = new VBox(12, spinner, messageLabel);
        content.setAlignment(Pos.CENTER);
        content.getStyleClass().add("loading-overlay-content");

        setAlignment(Pos.CENTER);
        getChildren().add(content);
    }

    public void setMessage(String message) {
        messageLabel.setText(message == null || message.isBlank()
            ? "Generating subtitles..."
            : message);
    }
}