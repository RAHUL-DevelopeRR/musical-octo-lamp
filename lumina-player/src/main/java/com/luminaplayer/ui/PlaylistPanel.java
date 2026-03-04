package com.luminaplayer.ui;

import com.luminaplayer.playlist.PlaylistController;
import com.luminaplayer.playlist.PlaylistItem;
import com.luminaplayer.util.FileUtils;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sidebar panel displaying the playlist with controls.
 */
public class PlaylistPanel extends VBox {

    private final ListView<PlaylistItem> listView;
    private final PlaylistController playlistController;
    private Consumer<PlaylistItem> onItemSelected;

    public PlaylistPanel(PlaylistController playlistController) {
        this.playlistController = playlistController;
        getStyleClass().add("playlist-panel");
        setPrefWidth(280);
        setMinWidth(200);
        setPadding(new Insets(4));
        setSpacing(4);

        Label header = new Label("Playlist");
        header.getStyleClass().add("playlist-header");

        listView = new ListView<>(playlistController.getPlaylist().getItems());
        listView.getStyleClass().add("playlist-list");
        VBox.setVgrow(listView, Priority.ALWAYS);

        // Custom cell factory
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PlaylistItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item.getDisplayName());
                    if (item.isPlaying()) {
                        setStyle("-fx-font-weight: bold; -fx-text-fill: #4fc3f7;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        // Double click to play
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                int idx = listView.getSelectionModel().getSelectedIndex();
                if (idx >= 0) {
                    PlaylistItem item = playlistController.playAt(idx);
                    if (item != null && onItemSelected != null) {
                        onItemSelected.accept(item);
                    }
                }
            }
        });

        // Drag-and-drop files from OS
        listView.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        listView.setOnDragDropped(event -> {
            List<File> files = event.getDragboard().getFiles();
            if (files != null) {
                for (File f : files) {
                    if (FileUtils.isMediaFile(f)) {
                        playlistController.getPlaylist().add(f);
                    }
                }
                event.setDropCompleted(true);
            }
            event.consume();
        });

        // Control buttons
        Button addBtn = new Button("+");
        addBtn.setTooltip(new Tooltip("Add files"));
        addBtn.getStyleClass().add("playlist-button");

        Button removeBtn = new Button("-");
        removeBtn.setTooltip(new Tooltip("Remove selected"));
        removeBtn.getStyleClass().add("playlist-button");
        removeBtn.setOnAction(e -> {
            int idx = listView.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                playlistController.getPlaylist().remove(idx);
            }
        });

        Button clearBtn = new Button("Clear");
        clearBtn.setTooltip(new Tooltip("Clear playlist"));
        clearBtn.getStyleClass().add("playlist-button");
        clearBtn.setOnAction(e -> playlistController.getPlaylist().clear());

        HBox buttonBar = new HBox(4, addBtn, removeBtn, clearBtn);
        buttonBar.setPadding(new Insets(2, 0, 0, 0));

        getChildren().addAll(header, listView, buttonBar);
    }

    public void setOnItemSelected(Consumer<PlaylistItem> onItemSelected) {
        this.onItemSelected = onItemSelected;
    }

    public Button getAddButton() {
        // Return the first button in the button bar
        HBox buttonBar = (HBox) getChildren().get(2);
        return (Button) buttonBar.getChildren().get(0);
    }

    public ListView<PlaylistItem> getListView() {
        return listView;
    }
}
