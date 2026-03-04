package com.luminaplayer.ui;

import com.luminaplayer.app.AppConfig;
import com.luminaplayer.player.MediaInfo;
import com.luminaplayer.player.PlayerController;
import com.luminaplayer.playlist.PlaylistController;
import com.luminaplayer.util.FileUtils;
import com.luminaplayer.subtitle.DsrtFile;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.MenuBar;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Builds the main application window, wiring all UI components together.
 */
public class MainWindow {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private final BorderPane root;
    private final VideoPane videoPane;
    private final ControlBar controlBar;
    private final SeekBar seekBar;
    private final StatusBar statusBar;
    private final PlaylistPanel playlistPanel;
    private MenuBar menuBar;
    private FullScreenHandler fullScreenHandler;

    private final PlayerController playerController;
    private final PlaylistController playlistController;
    private final Stage stage;

    private boolean playlistVisible = true;
    private boolean whatsNewShown;

    public MainWindow(Stage stage, PlayerController playerController, PlaylistController playlistController) {
        this.stage = stage;
        this.playerController = playerController;
        this.playlistController = playlistController;

        root = new BorderPane();
        videoPane = new VideoPane(playerController);
        controlBar = new ControlBar(playerController);
        seekBar = new SeekBar();
        statusBar = new StatusBar(playerController);
        playlistPanel = new PlaylistPanel(playlistController);
    }

    public Scene buildScene() {
        // --- Menu Bar ---
        MenuBarBuilder menuBuilder = new MenuBarBuilder(
            playerController, playlistController,
            this::showOpenFileDialog,
            this::showOpenMultipleDialog,
            () -> fullScreenHandler.toggle(),
            this::showLoadSubtitleDialog,
            this::takeSnapshot,
            this::showAboutDialog,
            this::showWhatsNewDialog,
            this::showNetworkStreamDialog,
            this::showGenerateSubtitlesDialog,
            () -> videoPane.getSubtitleOverlay().toggleVisibility()
        );
        menuBar = menuBuilder.build();

        // --- Bottom controls ---
        VBox controlContainer = new VBox();
        controlContainer.getChildren().addAll(seekBar, controlBar, statusBar);
        controlContainer.getStyleClass().add("control-container");

        // --- Layout ---
        root.setTop(menuBar);
        root.setCenter(videoPane);
        root.setBottom(controlContainer);
        root.setRight(playlistPanel);

        // --- Fullscreen handler ---
        fullScreenHandler = new FullScreenHandler(stage, controlContainer, menuBar);

        // --- Wire seek bar ---
        playerController.positionProperty().addListener((obs, oldVal, newVal) ->
            seekBar.updatePosition(newVal.floatValue()));
        seekBar.setOnSeek(() ->
            playerController.seek((float) seekBar.getSeekPosition()));
        seekBar.totalDurationProperty().bind(playerController.totalDurationProperty());

        // --- Wire playlist panel ---
        playlistPanel.setOnItemSelected(item ->
            playerController.openFile(item.getFile()));

        // Playlist toggle
        controlBar.getPlaylistBtn().setOnAction(e -> togglePlaylist());

        // Fullscreen button
        controlBar.getFullscreenBtn().setOnAction(e -> fullScreenHandler.toggle());

        // Shuffle button
        controlBar.getShuffleBtn().setOnAction(e -> playlistController.toggleShuffle());
        playlistController.shuffleEnabledProperty().addListener((obs, oldVal, newVal) ->
            controlBar.getShuffleBtn().setStyle(newVal ? "-fx-text-fill: #4fc3f7;" : ""));

        // Playlist add button
        playlistPanel.getAddButton().setOnAction(e -> showOpenMultipleDialog());

        // --- Drag and drop on video pane ---
        videoPane.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        videoPane.setOnDragDropped(event -> {
            List<File> files = event.getDragboard().getFiles();
            if (files != null && !files.isEmpty()) {
                List<File> mediaFiles = files.stream()
                    .filter(FileUtils::isMediaFile)
                    .toList();
                if (!mediaFiles.isEmpty()) {
                    playerController.openFiles(mediaFiles);
                }
                event.setDropCompleted(true);
            }
            event.consume();
        });

        // Double click video for fullscreen
        videoPane.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                fullScreenHandler.toggle();
            }
        });

        // --- Scene ---
        Scene scene = new Scene(root, AppConfig.DEFAULT_WINDOW_WIDTH, AppConfig.DEFAULT_WINDOW_HEIGHT);

        // Load CSS
        String css = getClass().getResource("/com/luminaplayer/css/main.css") != null
            ? getClass().getResource("/com/luminaplayer/css/main.css").toExternalForm()
            : null;
        if (css != null) {
            scene.getStylesheets().add(css);
        }

        // --- Keyboard shortcuts ---
        setupKeyboardShortcuts(scene);

        return scene;
    }

    public void showWhatsNewOnStartup() {
        if (whatsNewShown) {
            return;
        }
        whatsNewShown = true;
        Platform.runLater(this::showWhatsNewDialog);
    }

    private void setupKeyboardShortcuts(Scene scene) {
        scene.setOnKeyPressed(e -> {
            // Don't capture if a text input has focus
            if (e.getTarget() instanceof javafx.scene.control.TextInputControl) {
                return;
            }

            switch (e.getCode()) {
                case SPACE -> {
                    playerController.togglePlayPause();
                    e.consume();
                }
                case F11 -> {
                    fullScreenHandler.toggle();
                    e.consume();
                }
                case ESCAPE -> {
                    if (fullScreenHandler.isFullScreen()) {
                        fullScreenHandler.toggle();
                        e.consume();
                    }
                }
                case F -> {
                    if (!e.isControlDown()) {
                        fullScreenHandler.toggle();
                        e.consume();
                    }
                }
                case M -> {
                    if (!e.isControlDown()) {
                        playerController.toggleMute();
                        e.consume();
                    }
                }
                case RIGHT -> {
                    playerController.skipForward(10000);
                    e.consume();
                }
                case LEFT -> {
                    playerController.skipBackward(10000);
                    e.consume();
                }
                case UP -> {
                    playerController.setVolume(
                        Math.min(200, playerController.volumeProperty().get() + 5));
                    e.consume();
                }
                case DOWN -> {
                    playerController.setVolume(
                        Math.max(0, playerController.volumeProperty().get() - 5));
                    e.consume();
                }
                case N -> {
                    playerController.playNext();
                    e.consume();
                }
                case P -> {
                    playerController.playPrevious();
                    e.consume();
                }
                case E -> {
                    playerController.nextFrame();
                    e.consume();
                }
                case V -> {
                    if (!e.isControlDown()) {
                        videoPane.getSubtitleOverlay().toggleVisibility();
                        e.consume();
                    }
                }
                default -> { }
            }
        });
    }

    public void attachVideoSurface() {
        playerController.attachVideoSurface(videoPane.getImageView());
    }

    private void togglePlaylist() {
        playlistVisible = !playlistVisible;
        if (playlistVisible) {
            root.setRight(playlistPanel);
        } else {
            root.setRight(null);
        }
    }

    private void showOpenFileDialog() {
        FileChooser chooser = createMediaFileChooser("Open Media File");
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            playlistController.getPlaylist().add(file);
            int idx = playlistController.getPlaylist().size() - 1;
            playlistController.playAt(idx);
            playerController.openFile(file);
        }
    }

    private void showOpenMultipleDialog() {
        FileChooser chooser = createMediaFileChooser("Open Media Files");
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            playerController.openFiles(files);
        }
    }

    private void showLoadSubtitleDialog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Subtitle File");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Subtitle Files",
                "*.srt", "*.ass", "*.ssa", "*.sub", "*.vtt", "*.dsrt"));
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Dynamic Subtitles (.dsrt)", "*.dsrt"));
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            if (file.getName().endsWith(".dsrt")) {
                // Load .dsrt file into overlay
                try {
                    DsrtFile dsrt = DsrtFile.loadFrom(file);
                    playerController.getEngine().subtitles().disableSubtitles();
                    videoPane.getSubtitleOverlay().activate(dsrt);
                    log.info("Loaded .dsrt subtitle: {} ({} cues)", file.getName(), dsrt.getCueCount());
                } catch (IOException ex) {
                    log.error("Failed to load .dsrt file", ex);
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Load Error");
                    alert.setContentText("Failed to load .dsrt file: " + ex.getMessage());
                    alert.initOwner(stage);
                    alert.showAndWait();
                }
            } else {
                // Load static subtitle via VLC
                videoPane.getSubtitleOverlay().deactivate();
                playerController.getEngine().subtitles().loadExternalSubtitle(file);
            }
        }
    }

    private void showNetworkStreamDialog() {
        NetworkStreamDialog dialog = new NetworkStreamDialog();
        dialog.initOwner(stage);
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(url -> {
            log.info("Opening network stream: {}", url);
            playerController.openUrl(url);
        });
    }

    private void showGenerateSubtitlesDialog() {
        log.info("showGenerateSubtitlesDialog() called");
        MediaInfo media = playerController.currentMediaProperty().get();
        File mediaFile = null;

        if (media != null && media.getFilePath() != null) {
            File f = new File(media.getFilePath());
            if (f.exists()) {
                mediaFile = f;
            }
        }

        if (mediaFile == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Generate Subtitles");
            alert.setHeaderText(null);
            alert.setContentText("Please open a media file first before generating subtitles.");
            alert.initOwner(stage);
            alert.showAndWait();
            return;
        }

        long totalDuration = playerController.totalDurationProperty().get();
        if (totalDuration <= 0) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Generate Subtitles");
            alert.setHeaderText(null);
            alert.setContentText("Cannot determine media duration. Please wait for playback to start.");
            alert.initOwner(stage);
            alert.showAndWait();
            return;
        }

        // Disable VLC native subtitles when using .dsrt overlay
        try {
            playerController.getEngine().subtitles().disableSubtitles();
        } catch (Exception e) {
            log.warn("Could not disable native subtitles: {}", e.getMessage());
        }

        long currentTime = playerController.currentTimeProperty().get();

        try {
            log.info("Creating DsrtGenerationDialog: mediaFile={}, duration={}ms, currentTime={}ms",
                mediaFile.getName(), totalDuration, currentTime);

            DsrtGenerationDialog dialog = new DsrtGenerationDialog(
                stage, mediaFile, totalDuration, currentTime,
                dsrtFile -> {
                    // First chunk ready - activate subtitle overlay
                    log.info("First chunk ready, activating subtitle overlay ({} cues)",
                        dsrtFile.getCueCount());
                    videoPane.getSubtitleOverlay().activate(dsrtFile);
                },
                dsrtFile -> {
                    // All complete
                    log.info("DSRT generation complete: {} cues across {} chunks",
                        dsrtFile.getCueCount(), dsrtFile.getCompletedChunkCount());
                }
            );

            // Ensure dialog inherits the application stylesheet
            dialog.getDialogPane().getStylesheets().addAll(
                stage.getScene().getStylesheets());

            log.info("Showing DsrtGenerationDialog...");
            dialog.show();
            log.info("DsrtGenerationDialog.show() returned");
        } catch (Exception ex) {
            log.error("Failed to show subtitle generation dialog", ex);
            Alert errorAlert = new Alert(Alert.AlertType.ERROR);
            errorAlert.setTitle("Subtitle Generation Error");
            errorAlert.setHeaderText("Could not open subtitle generation dialog");
            errorAlert.setContentText(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            errorAlert.initOwner(stage);

            // Add stack trace to expandable content
            java.io.StringWriter sw = new java.io.StringWriter();
            ex.printStackTrace(new java.io.PrintWriter(sw));
            javafx.scene.control.TextArea textArea = new javafx.scene.control.TextArea(sw.toString());
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.setMaxHeight(Double.MAX_VALUE);
            errorAlert.getDialogPane().setExpandableContent(textArea);
            errorAlert.showAndWait();
        }
    }

    private void takeSnapshot() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Snapshot");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        chooser.setInitialFileName("snapshot.png");
        File file = chooser.showSaveDialog(stage);
        if (file != null) {
            playerController.getEngine().video().takeSnapshot(file);
        }
    }

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About " + AppConfig.APP_NAME);
        alert.setHeaderText(AppConfig.APP_NAME + " v" + AppConfig.APP_VERSION);
        alert.setContentText(
            "An offline desktop media player powered by libVLC.\n\n" +
            "Features:\n" +
            "  - Full media playback with VLC codec support\n" +
            "  - AI-powered offline subtitle generation (Whisper)\n" +
            "  - Dynamic .dsrt progressive subtitle format\n" +
            "  - Network streaming support\n" +
            "  - Playlist management with shuffle & repeat\n\n" +
            "Check Help > What's New for latest innovations.\n\n" +
            "libVLC is licensed under LGPL. This application uses\n" +
            "vlcj for Java integration.\n\n" +
            "This software is not affiliated with VideoLAN or VLC."
        );
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private void showWhatsNewDialog() {
        WhatsNewDialog dialog = new WhatsNewDialog(stage, stage.getScene().getStylesheets());
        dialog.showAndWait();
    }

    private FileChooser createMediaFileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);

        // All media filter
        StringBuilder allMedia = new StringBuilder();
        for (String ext : AppConfig.VIDEO_EXTENSIONS) {
            if (!allMedia.isEmpty()) allMedia.append(";");
            allMedia.append("*.").append(ext);
        }
        for (String ext : AppConfig.AUDIO_EXTENSIONS) {
            allMedia.append(";*.").append(ext);
        }
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("All Media Files", allMedia.toString().split(";")));

        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Video Files",
                java.util.Arrays.stream(AppConfig.VIDEO_EXTENSIONS)
                    .map(e -> "*." + e).toArray(String[]::new)));

        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Audio Files",
                java.util.Arrays.stream(AppConfig.AUDIO_EXTENSIONS)
                    .map(e -> "*." + e).toArray(String[]::new)));

        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("All Files", "*.*"));

        return chooser;
    }

    public VideoPane getVideoPane() { return videoPane; }
}
