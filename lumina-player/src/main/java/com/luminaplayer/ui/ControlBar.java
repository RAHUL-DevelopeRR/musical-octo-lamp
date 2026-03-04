package com.luminaplayer.ui;

import com.luminaplayer.player.PlaybackState;
import com.luminaplayer.player.PlayerController;
import com.luminaplayer.ui.controls.SpeedSelector;
import com.luminaplayer.ui.controls.TimeLabel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Transport controls bar: play/pause, stop, prev/next, frame step, speed, volume, time, fullscreen.
 */
public class ControlBar extends HBox {

    private final Button playPauseBtn;
    private final Button stopBtn;
    private final Button prevBtn;
    private final Button nextBtn;
    private final Button frameStepBtn;
    private final Button loopBtn;
    private final Button shuffleBtn;
    private final Button fullscreenBtn;
    private final Button playlistBtn;
    private final SpeedSelector speedSelector;
    private final VolumeControl volumeControl;
    private final TimeLabel timeLabel;

    private final PlayerController controller;

    public ControlBar(PlayerController controller) {
        this.controller = controller;
        getStyleClass().add("control-bar");
        setAlignment(Pos.CENTER);
        setSpacing(6);
        setPadding(new Insets(6, 16, 8, 16));

        // -- Transport buttons --
        prevBtn = createButton("\u23EE", "Previous (P)");
        playPauseBtn = createButton("\u25B6", "Play/Pause (Space)");
        playPauseBtn.getStyleClass().add("play-pause-button");
        stopBtn = createButton("\u23F9", "Stop");
        nextBtn = createButton("\u23ED", "Next (N)");
        frameStepBtn = createButton("\u23E9", "Next Frame (E)");
        loopBtn = createButton("\uD83D\uDD01", "Loop (Ctrl+L)");
        shuffleBtn = createButton("\uD83D\uDD00", "Shuffle");

        // Speed
        speedSelector = new SpeedSelector();

        // Volume
        volumeControl = new VolumeControl();

        // Time
        timeLabel = new TimeLabel();

        // Spacer
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Right-side buttons
        fullscreenBtn = createButton("\u26F6", "Fullscreen (F11)");
        playlistBtn = createButton("\u2630", "Playlist");

        getChildren().addAll(
            prevBtn, playPauseBtn, stopBtn, nextBtn,
            new Separator(),
            frameStepBtn, loopBtn, shuffleBtn,
            new Separator(),
            speedSelector,
            new Separator(),
            timeLabel,
            spacer,
            volumeControl,
            new Separator(),
            fullscreenBtn, playlistBtn
        );

        wireActions();
        wireBindings();
    }

    private void wireActions() {
        playPauseBtn.setOnAction(e -> controller.togglePlayPause());
        stopBtn.setOnAction(e -> controller.stop());
        prevBtn.setOnAction(e -> controller.playPrevious());
        nextBtn.setOnAction(e -> controller.playNext());
        frameStepBtn.setOnAction(e -> controller.nextFrame());
        loopBtn.setOnAction(e -> controller.toggleLoop());

        speedSelector.selectedRateProperty().addListener((obs, oldVal, newVal) ->
            controller.setRate(newVal.floatValue()));

        volumeControl.setOnVolumeChange(controller::setVolume);
        volumeControl.setOnMuteToggle(controller::toggleMute);
    }

    private void wireBindings() {
        // Update play/pause icon based on state
        controller.playbackStateProperty().addListener((obs, oldState, newState) -> {
            if (newState == PlaybackState.PLAYING) {
                playPauseBtn.setText("\u23F8"); // pause
            } else {
                playPauseBtn.setText("\u25B6"); // play
            }
        });

        // Time display
        timeLabel.currentTimeProperty().bind(controller.currentTimeProperty());
        timeLabel.totalDurationProperty().bind(controller.totalDurationProperty());

        // Volume sync
        controller.volumeProperty().addListener((obs, oldVal, newVal) ->
            volumeControl.setVolume(newVal.intValue()));
        controller.mutedProperty().addListener((obs, oldVal, newVal) ->
            volumeControl.setMuted(newVal));

        // Loop indicator
        controller.loopingProperty().addListener((obs, oldVal, newVal) ->
            loopBtn.setStyle(newVal ? "-fx-text-fill: #4fc3f7;" : ""));

        // Speed sync
        controller.playbackRateProperty().addListener((obs, oldVal, newVal) ->
            speedSelector.setRate(newVal.floatValue()));
    }

    public Button getFullscreenBtn() { return fullscreenBtn; }
    public Button getPlaylistBtn() { return playlistBtn; }
    public Button getShuffleBtn() { return shuffleBtn; }

    private Button createButton(String text, String tooltip) {
        Button btn = new Button(text);
        btn.getStyleClass().add("control-button");
        btn.setTooltip(new Tooltip(tooltip));
        btn.setFocusTraversable(false);
        return btn;
    }
}
