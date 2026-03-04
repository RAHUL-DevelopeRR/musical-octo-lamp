package com.luminaplayer.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import uk.co.caprica.vlcj.player.base.TrackDescription;

import java.util.List;

/**
 * Dialog for selecting audio or subtitle tracks from the currently playing media.
 */
public class TrackSelectionDialog extends Dialog<Integer> {

    private final ListView<TrackItem> trackList;

    /**
     * Creates a track selection dialog.
     *
     * @param title        dialog title (e.g. "Audio Tracks" or "Subtitle Tracks")
     * @param tracks       list of available tracks from VLC
     * @param currentTrack currently selected track ID
     */
    public TrackSelectionDialog(String title, List<? extends TrackDescription> tracks, int currentTrack) {
        setTitle(title);
        setHeaderText("Select a track:");
        setResizable(true);

        DialogPane pane = getDialogPane();
        pane.setPrefWidth(400);
        pane.setPrefHeight(300);

        VBox content = new VBox(8);
        content.setPadding(new Insets(12));

        Label info = new Label(tracks.size() + " tracks available");
        info.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");

        trackList = new ListView<>();
        trackList.setPrefHeight(200);

        for (TrackDescription td : tracks) {
            TrackItem item = new TrackItem(td.id(), td.description());
            trackList.getItems().add(item);
            if (td.id() == currentTrack) {
                trackList.getSelectionModel().select(item);
            }
        }

        // Add "Disable" option for subtitle tracks
        if (title.toLowerCase().contains("subtitle")) {
            TrackItem disableItem = new TrackItem(-1, "Disable subtitles");
            trackList.getItems().add(0, disableItem);
            if (currentTrack == -1) {
                trackList.getSelectionModel().select(disableItem);
            }
        }

        content.getChildren().addAll(info, trackList);
        pane.setContent(content);

        ButtonType selectType = new ButtonType("Select", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(selectType, cancelType);

        Button selectBtn = (Button) pane.lookupButton(selectType);
        selectBtn.setStyle("-fx-background-color: #4fc3f7; -fx-text-fill: #000000; -fx-font-weight: bold;");

        setResultConverter(buttonType -> {
            if (buttonType == selectType) {
                TrackItem selected = trackList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    return selected.id();
                }
            }
            return null;
        });
    }

    /**
     * Internal representation of a track in the list.
     */
    private record TrackItem(int id, String description) {
        @Override
        public String toString() {
            return String.format("[%d] %s", id, description);
        }
    }
}
