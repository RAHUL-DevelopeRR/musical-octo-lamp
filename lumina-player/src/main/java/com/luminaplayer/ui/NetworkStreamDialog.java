package com.luminaplayer.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Dialog for opening a network stream by URL.
 * Supports common protocols: HTTP, RTSP, RTMP, MMS, UDP.
 */
public class NetworkStreamDialog extends Dialog<String> {

    private final TextField urlField;
    private final ComboBox<String> recentUrls;

    public NetworkStreamDialog() {
        setTitle("Open Network Stream");
        setHeaderText("Enter the URL of the network stream");
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.setPrefWidth(550);

        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        // Protocol hint
        Label hint = new Label("Supported protocols: http://, https://, rtsp://, rtmp://, mms://, udp://");
        hint.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
        hint.setWrapText(true);

        // URL input
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);

        Label urlLabel = new Label("URL:");
        urlLabel.setStyle("-fx-text-fill: #cccccc;");
        urlField = new TextField();
        urlField.setPromptText("https://example.com/stream.m3u8");
        urlField.setPrefWidth(400);
        GridPane.setHgrow(urlField, Priority.ALWAYS);

        grid.add(urlLabel, 0, 0);
        grid.add(urlField, 1, 0);

        // Recently used (simple ComboBox placeholder)
        Label recentLabel = new Label("Recent:");
        recentLabel.setStyle("-fx-text-fill: #cccccc;");
        recentUrls = new ComboBox<>();
        recentUrls.setPromptText("No recent URLs");
        recentUrls.setMaxWidth(Double.MAX_VALUE);
        recentUrls.setEditable(false);
        GridPane.setHgrow(recentUrls, Priority.ALWAYS);

        recentUrls.setOnAction(e -> {
            String selected = recentUrls.getValue();
            if (selected != null && !selected.isBlank()) {
                urlField.setText(selected);
            }
        });

        grid.add(recentLabel, 0, 1);
        grid.add(recentUrls, 1, 1);

        // Common examples
        Label exampleLabel = new Label("Examples:");
        exampleLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
        Label examples = new Label(
            "  HTTP stream:  http://server:port/stream.mp4\n" +
            "  HLS stream:   https://server/path/playlist.m3u8\n" +
            "  RTSP camera:  rtsp://user:pass@camera-ip:554/stream"
        );
        examples.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px; -fx-font-family: 'Consolas';");

        content.getChildren().addAll(hint, grid, new Separator(), exampleLabel, examples);
        pane.setContent(content);

        // Buttons
        ButtonType openType = new ButtonType("Open", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(openType, cancelType);

        Button openBtn = (Button) pane.lookupButton(openType);
        openBtn.setStyle("-fx-background-color: #4fc3f7; -fx-text-fill: #000000; -fx-font-weight: bold;");

        // Disable Open button when URL is empty
        openBtn.disableProperty().bind(urlField.textProperty().isEmpty());

        setResultConverter(buttonType -> {
            if (buttonType == openType) {
                String url = urlField.getText().trim();
                if (!url.isEmpty()) {
                    return url;
                }
            }
            return null;
        });

        // Focus URL field on show
        setOnShowing(e -> urlField.requestFocus());
    }

    public TextField getUrlField() {
        return urlField;
    }
}
