package com.luminaplayer.ui;

import com.luminaplayer.player.PlayerController;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

/**
 * Video display surface wrapping an ImageView for vlcj rendering.
 * Black background when no video is playing.
 * Includes a SubtitleOverlay for dynamic .dsrt subtitle display.
 */
public class VideoPane extends StackPane {

    private final ImageView videoImageView;
    private final SubtitleOverlay subtitleOverlay;
    private final LoadingOverlay loadingOverlay;

    public VideoPane(PlayerController playerController) {
        this.videoImageView = new ImageView();
        videoImageView.setPreserveRatio(true);
        videoImageView.fitWidthProperty().bind(this.widthProperty());
        videoImageView.fitHeightProperty().bind(this.heightProperty());

        this.subtitleOverlay = new SubtitleOverlay(playerController);
        this.loadingOverlay = new LoadingOverlay();

        setStyle("-fx-background-color: #000000;");
        getChildren().addAll(videoImageView, loadingOverlay, subtitleOverlay);
    }

    public ImageView getImageView() {
        return videoImageView;
    }

    public SubtitleOverlay getSubtitleOverlay() {
        return subtitleOverlay;
    }

    public void showLoadingOverlay(boolean show) {
        loadingOverlay.setVisible(show);
    }

    public void setLoadingMessage(String message) {
        loadingOverlay.setMessage(message);
    }
}
