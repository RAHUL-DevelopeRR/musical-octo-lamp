package com.luminaplayer.app;

import com.luminaplayer.engine.VlcEngine;
import com.luminaplayer.player.PlayerController;
import com.luminaplayer.playlist.Playlist;
import com.luminaplayer.playlist.PlaylistController;
import com.luminaplayer.ui.MainWindow;
import com.luminaplayer.util.ResourceCleaner;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LuminaPlayer application entry point.
 * Initializes the VLC engine, builds the UI, and manages the application lifecycle.
 */
public class LuminaPlayerApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(LuminaPlayerApp.class);

    private VlcEngine engine;
    private PlayerController playerController;
    private PlaylistController playlistController;

    public static void main(String[] args) {
        // MUST configure native library paths before any vlcj class is loaded
        NativeLibrarySetup.configure();
        launch(args);
    }

    @Override
    public void init() {
        log.info("Initializing {}...", AppConfig.APP_NAME);

        // Create engine
        engine = new VlcEngine();
        ResourceCleaner.instance().register(engine);

        // Create playlist
        Playlist playlist = new Playlist();
        playlistController = new PlaylistController(playlist);

        // Create player controller
        playerController = new PlayerController(engine);
        playerController.setPlaylistController(playlistController);
    }

    @Override
    public void start(Stage primaryStage) {
        log.info("Starting {} UI...", AppConfig.APP_NAME);

        // Set uncaught exception handler for FX thread so errors are visible
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            log.error("Uncaught exception on FX thread", e);
            e.printStackTrace();
        });

        primaryStage.setTitle(AppConfig.APP_NAME);
        primaryStage.setMinWidth(AppConfig.MIN_WINDOW_WIDTH);
        primaryStage.setMinHeight(AppConfig.MIN_WINDOW_HEIGHT);

        // Try to load app icon
        try {
            Image icon = new Image(getClass().getResourceAsStream("/com/luminaplayer/icons/app-icon.png"));
            primaryStage.getIcons().add(icon);
        } catch (Exception e) {
            log.debug("App icon not found, using default");
        }

        // Build the main window
        MainWindow mainWindow = new MainWindow(primaryStage, playerController, playlistController);
        Scene scene = mainWindow.buildScene();

        // Attach the vlcj video surface to the ImageView
        mainWindow.attachVideoSurface();

        primaryStage.setScene(scene);

        // Cleanup on close
        primaryStage.setOnCloseRequest(event -> {
            log.info("Window close requested, shutting down...");
            playerController.stop();
            Platform.exit();
        });

        primaryStage.show();
        mainWindow.showWhatsNewOnStartup();
        log.info("{} is ready.", AppConfig.APP_NAME);
    }

    @Override
    public void stop() {
        log.info("Stopping {}...", AppConfig.APP_NAME);
        ResourceCleaner.instance().releaseAll();
        log.info("{} stopped.", AppConfig.APP_NAME);
    }
}
