package com.luminaplayer.ui;

import javafx.animation.PauseTransition;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles fullscreen toggle with auto-hiding controls.
 */
public class FullScreenHandler {

    private static final Logger log = LoggerFactory.getLogger(FullScreenHandler.class);
    private static final double HIDE_DELAY_SECONDS = 3.0;

    private final Stage stage;
    private final VBox controlContainer;
    private final javafx.scene.control.MenuBar menuBar;

    private boolean fullScreen = false;
    private final PauseTransition hideTimer;
    private EventHandler<MouseEvent> mouseFilter;

    public FullScreenHandler(Stage stage, VBox controlContainer, javafx.scene.control.MenuBar menuBar) {
        this.stage = stage;
        this.controlContainer = controlContainer;
        this.menuBar = menuBar;

        hideTimer = new PauseTransition(Duration.seconds(HIDE_DELAY_SECONDS));
        hideTimer.setOnFinished(e -> {
            if (fullScreen) {
                controlContainer.setVisible(false);
                controlContainer.setManaged(false);
                if (menuBar != null) {
                    menuBar.setVisible(false);
                    menuBar.setManaged(false);
                }
            }
        });

        // Create the mouse filter once (prevents leak on repeated toggles)
        mouseFilter = e -> {
            if (fullScreen) {
                controlContainer.setVisible(true);
                controlContainer.setManaged(true);
                if (menuBar != null) {
                    menuBar.setVisible(true);
                    menuBar.setManaged(true);
                }
                hideTimer.playFromStart();
            }
        };
    }

    public void toggle() {
        fullScreen = !fullScreen;
        stage.setFullScreen(fullScreen);

        Scene scene = stage.getScene();
        if (fullScreen) {
            hideTimer.playFromStart();
            if (scene != null) {
                scene.addEventFilter(MouseEvent.MOUSE_MOVED, mouseFilter);
            }
        } else {
            hideTimer.stop();
            if (scene != null) {
                scene.removeEventFilter(MouseEvent.MOUSE_MOVED, mouseFilter);
            }
            controlContainer.setVisible(true);
            controlContainer.setManaged(true);
            if (menuBar != null) {
                menuBar.setVisible(true);
                menuBar.setManaged(true);
            }
        }

        log.debug("Fullscreen: {}", fullScreen);
    }

    public boolean isFullScreen() {
        return fullScreen;
    }
}
