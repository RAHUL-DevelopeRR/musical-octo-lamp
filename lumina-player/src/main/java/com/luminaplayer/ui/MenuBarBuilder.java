package com.luminaplayer.ui;

import com.luminaplayer.app.AppConfig;
import com.luminaplayer.player.PlayerController;
import com.luminaplayer.playlist.PlaylistController;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import uk.co.caprica.vlcj.player.base.TrackDescription;

import java.util.List;
import java.util.Optional;

/**
 * Builds the application menu bar with all menus and actions.
 */
public class MenuBarBuilder {

    private final PlayerController playerController;
    private final PlaylistController playlistController;
    private final Runnable onOpenFile;
    private final Runnable onOpenMultiple;
    private final Runnable onFullscreen;
    private final Runnable onLoadSubtitle;
    private final Runnable onSnapshot;
    private final Runnable onShowAbout;
    private final Runnable onOpenNetworkStream;
    private final Runnable onGenerateSubtitles;
    private final Runnable onToggleSubtitleOverlay;
    private Menu whatsNewMenu;

    public MenuBarBuilder(PlayerController playerController,
                          PlaylistController playlistController,
                          Runnable onOpenFile,
                          Runnable onOpenMultiple,
                          Runnable onFullscreen,
                          Runnable onLoadSubtitle,
                          Runnable onSnapshot,
                          Runnable onShowAbout,
                          Runnable onOpenNetworkStream,
                          Runnable onGenerateSubtitles,
                          Runnable onToggleSubtitleOverlay) {
        this.playerController = playerController;
        this.playlistController = playlistController;
        this.onOpenFile = onOpenFile;
        this.onOpenMultiple = onOpenMultiple;
        this.onFullscreen = onFullscreen;
        this.onLoadSubtitle = onLoadSubtitle;
        this.onSnapshot = onSnapshot;
        this.onShowAbout = onShowAbout;
        this.onOpenNetworkStream = onOpenNetworkStream;
        this.onGenerateSubtitles = onGenerateSubtitles;
        this.onToggleSubtitleOverlay = onToggleSubtitleOverlay;
    }

    public MenuBar build() {
        MenuBar menuBar = new MenuBar();
        whatsNewMenu = buildWhatsNewMenu();
        menuBar.getMenus().addAll(
            buildFileMenu(),
            buildPlaybackMenu(),
            buildAudioMenu(),
            buildSubtitleMenu(),
            buildVideoMenu(),
            buildToolsMenu(),
            whatsNewMenu,
            buildHelpMenu()
        );
        return menuBar;
    }

    public Menu getWhatsNewMenu() {
        return whatsNewMenu;
    }

    private Menu buildFileMenu() {
        Menu menu = new Menu("File");

        MenuItem openFile = new MenuItem("Open File...");
        openFile.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN));
        openFile.setOnAction(e -> onOpenFile.run());

        MenuItem openMultiple = new MenuItem("Open Multiple Files...");
        openMultiple.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        openMultiple.setOnAction(e -> onOpenMultiple.run());

        MenuItem openStream = new MenuItem("Open Network Stream...");
        openStream.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN));
        openStream.setOnAction(e -> onOpenNetworkStream.run());

        MenuItem exit = new MenuItem("Exit");
        exit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.CONTROL_DOWN));
        exit.setOnAction(e -> javafx.application.Platform.exit());

        menu.getItems().addAll(openFile, openMultiple, openStream, new SeparatorMenuItem(), exit);
        return menu;
    }

    private Menu buildPlaybackMenu() {
        Menu menu = new Menu("Playback");

        MenuItem playPause = new MenuItem("Play/Pause                    Space");
        // Bare-key accelerator removed: handled by MainWindow.setupKeyboardShortcuts()
        // which properly guards against text input fields
        playPause.setOnAction(e -> playerController.togglePlayPause());

        MenuItem stop = new MenuItem("Stop");
        stop.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN));
        stop.setOnAction(e -> playerController.stop());

        MenuItem nextFrame = new MenuItem("Next Frame                       E");
        nextFrame.setOnAction(e -> playerController.nextFrame());

        MenuItem skipFwd = new MenuItem("Skip Forward 10s            Right");
        skipFwd.setOnAction(e -> playerController.skipForward(10000));

        MenuItem skipBwd = new MenuItem("Skip Backward 10s           Left");
        skipBwd.setOnAction(e -> playerController.skipBackward(10000));

        // Speed submenu
        Menu speedMenu = new Menu("Speed");
        for (float rate : AppConfig.PLAYBACK_RATES) {
            String label = rate == (int) rate ? String.format("%dx", (int) rate) : String.format("%.2fx", rate);
            MenuItem item = new MenuItem(label);
            item.setOnAction(e -> playerController.setRate(rate));
            speedMenu.getItems().add(item);
        }

        CheckMenuItem loop = new CheckMenuItem("Loop");
        loop.setAccelerator(new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN));
        loop.selectedProperty().bindBidirectional(playerController.loopingProperty());

        menu.getItems().addAll(playPause, stop, new SeparatorMenuItem(),
            nextFrame, skipFwd, skipBwd, new SeparatorMenuItem(),
            speedMenu, loop);
        return menu;
    }

    private Menu buildAudioMenu() {
        Menu menu = new Menu("Audio");

        MenuItem mute = new MenuItem("Toggle Mute                      M");
        mute.setOnAction(e -> playerController.toggleMute());

        MenuItem volUp = new MenuItem("Volume Up                       Up");
        volUp.setOnAction(e -> playerController.setVolume(
            Math.min(200, playerController.volumeProperty().get() + 5)));

        MenuItem volDown = new MenuItem("Volume Down                 Down");
        volDown.setOnAction(e -> playerController.setVolume(
            Math.max(0, playerController.volumeProperty().get() - 5)));

        // Audio track selection dialog
        MenuItem selectAudioTrack = new MenuItem("Select Audio Track...");
        selectAudioTrack.setOnAction(e -> showAudioTrackDialog());

        // Audio track submenu (populated dynamically)
        Menu audioTrackMenu = new Menu("Audio Track");
        menu.setOnShowing(e -> {
            audioTrackMenu.getItems().clear();
            try {
                List<? extends TrackDescription> tracks =
                    playerController.getEngine().audio().getAudioTrackDescriptions();
                for (TrackDescription td : tracks) {
                    MenuItem trackItem = new MenuItem(td.description());
                    trackItem.setOnAction(ev ->
                        playerController.getEngine().audio().setAudioTrack(td.id()));
                    audioTrackMenu.getItems().add(trackItem);
                }
            } catch (Exception ex) {
                audioTrackMenu.getItems().add(new MenuItem("(none available)"));
            }
        });

        // Audio delay adjustment
        Menu audioDelayMenu = new Menu("Audio Delay");
        MenuItem audioDelayPlus = new MenuItem("Increase +100ms");
        audioDelayPlus.setOnAction(e -> {
            long current = playerController.getEngine().audio().getAudioDelay();
            playerController.getEngine().audio().setAudioDelay(current + 100000);
        });
        MenuItem audioDelayMinus = new MenuItem("Decrease -100ms");
        audioDelayMinus.setOnAction(e -> {
            long current = playerController.getEngine().audio().getAudioDelay();
            playerController.getEngine().audio().setAudioDelay(current - 100000);
        });
        MenuItem audioDelayReset = new MenuItem("Reset to 0");
        audioDelayReset.setOnAction(e -> playerController.getEngine().audio().setAudioDelay(0));
        audioDelayMenu.getItems().addAll(audioDelayPlus, audioDelayMinus, audioDelayReset);

        menu.getItems().addAll(mute, volUp, volDown, new SeparatorMenuItem(),
            selectAudioTrack, audioTrackMenu, audioDelayMenu);
        return menu;
    }

    private Menu buildSubtitleMenu() {
        Menu menu = new Menu("Subtitles");

        MenuItem loadSrt = new MenuItem("Load Subtitle File...");
        loadSrt.setOnAction(e -> onLoadSubtitle.run());

        MenuItem generateSubs = new MenuItem("Generate Dynamic Subtitles (.dsrt)...");
        generateSubs.setAccelerator(new KeyCodeCombination(KeyCode.G, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        generateSubs.setOnAction(e -> onGenerateSubtitles.run());

        MenuItem toggleOverlay = new MenuItem("Toggle Subtitle Overlay       V");
        toggleOverlay.setOnAction(e -> onToggleSubtitleOverlay.run());

        MenuItem disableSubs = new MenuItem("Disable Subtitles");
        disableSubs.setOnAction(e -> playerController.getEngine().subtitles().disableSubtitles());

        // Subtitle track selection dialog
        MenuItem selectSubTrack = new MenuItem("Select Subtitle Track...");
        selectSubTrack.setOnAction(e -> showSubtitleTrackDialog());

        // Subtitle track submenu
        Menu subTrackMenu = new Menu("Subtitle Track");
        menu.setOnShowing(e -> {
            subTrackMenu.getItems().clear();
            try {
                List<? extends TrackDescription> tracks =
                    playerController.getEngine().subtitles().getSubtitleTrackDescriptions();
                for (TrackDescription td : tracks) {
                    MenuItem trackItem = new MenuItem(td.description());
                    trackItem.setOnAction(ev ->
                        playerController.getEngine().subtitles().setSubtitleTrack(td.id()));
                    subTrackMenu.getItems().add(trackItem);
                }
            } catch (Exception ex) {
                subTrackMenu.getItems().add(new MenuItem("(none available)"));
            }
        });

        // Delay adjustment
        Menu delayMenu = new Menu("Subtitle Delay");
        MenuItem delayPlus = new MenuItem("Increase +100ms");
        delayPlus.setOnAction(e -> {
            long current = playerController.getEngine().subtitles().getDelay();
            playerController.getEngine().subtitles().setDelay(current + 100000);
        });
        MenuItem delayMinus = new MenuItem("Decrease -100ms");
        delayMinus.setOnAction(e -> {
            long current = playerController.getEngine().subtitles().getDelay();
            playerController.getEngine().subtitles().setDelay(current - 100000);
        });
        MenuItem delayReset = new MenuItem("Reset to 0");
        delayReset.setOnAction(e -> playerController.getEngine().subtitles().setDelay(0));

        delayMenu.getItems().addAll(delayPlus, delayMinus, delayReset);

        menu.getItems().addAll(loadSrt, generateSubs, toggleOverlay, new SeparatorMenuItem(),
            disableSubs, selectSubTrack, subTrackMenu, delayMenu);
        return menu;
    }

    private Menu buildVideoMenu() {
        Menu menu = new Menu("Video");

        MenuItem fullscreen = new MenuItem("Fullscreen");
        fullscreen.setAccelerator(new KeyCodeCombination(KeyCode.F11));
        fullscreen.setOnAction(e -> onFullscreen.run());

        // Aspect ratio submenu
        Menu aspectMenu = new Menu("Aspect Ratio");
        String[] ratios = {"Default", "16:9", "4:3", "1:1", "16:10", "2.21:1", "2.35:1", "5:4"};
        for (String ratio : ratios) {
            MenuItem item = new MenuItem(ratio);
            item.setOnAction(e -> {
                String value = ratio.equals("Default") ? null : ratio;
                playerController.getEngine().video().setAspectRatio(value);
            });
            aspectMenu.getItems().add(item);
        }

        // Scale submenu
        Menu scaleMenu = new Menu("Scale");
        float[] scales = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        for (float scale : scales) {
            MenuItem item = new MenuItem(String.format("%.0f%%", scale * 100));
            item.setOnAction(e -> playerController.getEngine().video().setScale(scale));
            scaleMenu.getItems().add(item);
        }
        MenuItem fitScale = new MenuItem("Fit to Window");
        fitScale.setOnAction(e -> playerController.getEngine().video().setScale(0));
        scaleMenu.getItems().add(0, fitScale);

        MenuItem snapshot = new MenuItem("Take Snapshot");
        snapshot.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHIFT_DOWN, KeyCombination.CONTROL_DOWN));
        snapshot.setOnAction(e -> onSnapshot.run());

        menu.getItems().addAll(fullscreen, new SeparatorMenuItem(),
            aspectMenu, scaleMenu, new SeparatorMenuItem(), snapshot);
        return menu;
    }

    private Menu buildToolsMenu() {
        Menu menu = new Menu("Tools");

        MenuItem generateSubs2 = new MenuItem("Generate Dynamic Subtitles (.dsrt)...");
        generateSubs2.setOnAction(e -> onGenerateSubtitles.run());

        MenuItem mediaInfo = new MenuItem("Media Information");
        mediaInfo.setAccelerator(new KeyCodeCombination(KeyCode.I, KeyCombination.CONTROL_DOWN));
        mediaInfo.setOnAction(e -> showMediaInfoDialog());

        menu.getItems().addAll(generateSubs2, new SeparatorMenuItem(), mediaInfo);
        return menu;
    }

    private Menu buildWhatsNewMenu() {
        Menu menu = new Menu("What's New");

        MenuItem headline = new MenuItem("Architectural Innovations & Product Highlights");
        headline.setDisable(true);

        MenuItem novelty1 = new MenuItem("Novelty 1: .dsrt Progressive Subtitle Format");
        novelty1.setDisable(true);
        MenuItem novelty1Details = new MenuItem("Progressive loading, fast cue lookup, and early subtitle availability");
        novelty1Details.setDisable(true);

        MenuItem novelty2 = new MenuItem("Novelty 2: Priority-Chunk Latency Pipeline");
        novelty2.setDisable(true);
        MenuItem novelty2Details = new MenuItem("First chunk prioritized for quick time-to-first-subtitle");
        novelty2Details.setDisable(true);

        MenuItem novelty3 = new MenuItem("Novelty 3: Multi-Model Translation Verification");
        novelty3.setDisable(true);
        MenuItem novelty3Details = new MenuItem("Pluggable providers with optional second-pass verification");
        novelty3Details.setDisable(true);

        MenuItem novelty4 = new MenuItem("Offline-First AI Media Pipeline");
        novelty4.setDisable(true);
        MenuItem novelty4Details = new MenuItem("On-device transcription and translation with privacy-first flow");
        novelty4Details.setDisable(true);

        menu.getItems().addAll(
            headline,
            new SeparatorMenuItem(),
            novelty1,
            novelty1Details,
            new SeparatorMenuItem(),
            novelty2,
            novelty2Details,
            new SeparatorMenuItem(),
            novelty3,
            novelty3Details,
            new SeparatorMenuItem(),
            novelty4,
            novelty4Details
        );

        return menu;
    }

    private Menu buildHelpMenu() {
        Menu menu = new Menu("Help");

        MenuItem shortcuts = new MenuItem("Keyboard Shortcuts");
        shortcuts.setOnAction(e -> showShortcutsDialog());

        MenuItem about = new MenuItem("About " + AppConfig.APP_NAME);
        about.setOnAction(e -> onShowAbout.run());

        menu.getItems().addAll(shortcuts, new SeparatorMenuItem(), about);
        return menu;
    }

    private void showAudioTrackDialog() {
        try {
            List<? extends TrackDescription> tracks =
                playerController.getEngine().audio().getAudioTrackDescriptions();
            int current = playerController.getEngine().audio().getAudioTrack();

            TrackSelectionDialog dialog = new TrackSelectionDialog("Audio Tracks", tracks, current);
            Optional<Integer> result = dialog.showAndWait();
            result.ifPresent(trackId -> playerController.getEngine().audio().setAudioTrack(trackId));
        } catch (Exception e) {
            showErrorAlert("Audio Tracks", "No audio tracks available.");
        }
    }

    private void showSubtitleTrackDialog() {
        try {
            List<? extends TrackDescription> tracks =
                playerController.getEngine().subtitles().getSubtitleTrackDescriptions();
            int current = playerController.getEngine().subtitles().getSubtitleTrack();

            TrackSelectionDialog dialog = new TrackSelectionDialog("Subtitle Tracks", tracks, current);
            Optional<Integer> result = dialog.showAndWait();
            result.ifPresent(trackId -> {
                if (trackId == -1) {
                    playerController.getEngine().subtitles().disableSubtitles();
                } else {
                    playerController.getEngine().subtitles().setSubtitleTrack(trackId);
                }
            });
        } catch (Exception e) {
            showErrorAlert("Subtitle Tracks", "No subtitle tracks available.");
        }
    }

    private void showMediaInfoDialog() {
        var media = playerController.currentMediaProperty().get();
        if (media == null) {
            showErrorAlert("Media Information", "No media is currently loaded.");
            return;
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Media Information");
        alert.setHeaderText(media.getTitle());

        StringBuilder info = new StringBuilder();
        info.append("File: ").append(media.getFilePath()).append("\n");
        info.append("Size: ").append(formatFileSize(media.getFileSize())).append("\n");

        long duration = playerController.totalDurationProperty().get();
        if (duration > 0) {
            info.append("Duration: ").append(formatDuration(duration)).append("\n");
        }

        try {
            int audioTracks = playerController.getEngine().audio().getAudioTrackCount();
            int subTracks = playerController.getEngine().subtitles().getSubtitleTrackCount();
            info.append("Audio tracks: ").append(audioTracks).append("\n");
            info.append("Subtitle tracks: ").append(subTracks).append("\n");
        } catch (Exception e) {
            // media not fully loaded yet
        }

        alert.setContentText(info.toString());
        alert.showAndWait();
    }

    private void showShortcutsDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Keyboard Shortcuts");
        alert.setHeaderText("LuminaPlayer Keyboard Shortcuts");
        alert.setContentText(
            "SPACE          Play/Pause\n" +
            "F11 / F        Fullscreen\n" +
            "ESC            Exit fullscreen\n" +
            "M              Toggle mute\n" +
            "RIGHT          Skip forward 10s\n" +
            "LEFT           Skip backward 10s\n" +
            "UP             Volume up 5%\n" +
            "DOWN           Volume down 5%\n" +
            "N              Next track\n" +
            "P              Previous track\n" +
            "E              Next frame\n" +
            "V              Toggle subtitle overlay\n" +
            "Ctrl+O         Open file\n" +
            "Ctrl+Shift+O   Open multiple files\n" +
            "Ctrl+N         Open network stream\n" +
            "Ctrl+Shift+G   Generate subtitles (AI)\n" +
            "Ctrl+I         Media information\n" +
            "Ctrl+L         Toggle loop\n" +
            "Ctrl+Shift+S   Take snapshot\n" +
            "Ctrl+Q         Exit"
        );
        alert.getDialogPane().setPrefWidth(450);
        alert.showAndWait();
    }

    private void showErrorAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format("%d:%02d", minutes, secs);
    }
}
