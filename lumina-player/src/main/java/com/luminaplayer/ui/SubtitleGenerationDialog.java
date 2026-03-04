package com.luminaplayer.ui;

import com.luminaplayer.ai.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.function.Consumer;

/**
 * Dialog for configuring and running AI-powered subtitle generation.
 * Provides model selection, language selection, progress tracking, and tool status.
 */
public class SubtitleGenerationDialog extends Dialog<File> {

    private final SubtitleGenerator generator;
    private final File mediaFile;
    private final Consumer<File> onSubtitleGenerated;

    private ComboBox<WhisperModel> modelSelector;
    private ComboBox<WhisperLanguage> languageSelector;
    private CheckBox translateCheckBox;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label toolStatusLabel;
    private Button generateBtn;
    private Button cancelBtn;
    private TranscriptionTask currentTask;

    public SubtitleGenerationDialog(Stage owner, File mediaFile, Consumer<File> onSubtitleGenerated) {
        this.generator = new SubtitleGenerator();
        this.mediaFile = mediaFile;
        this.onSubtitleGenerated = onSubtitleGenerated;

        setTitle("Generate Subtitles (AI)");
        initOwner(owner);
        setResizable(true);

        buildContent();
        checkToolAvailability();
    }

    private void buildContent() {
        DialogPane pane = getDialogPane();
        pane.getStyleClass().add("subtitle-gen-dialog");
        pane.setPrefWidth(500);

        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        // --- File Info ---
        Label fileLabel = new Label("Media file: " + (mediaFile != null ? mediaFile.getName() : "None"));
        fileLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 13px;");

        // --- Tool Status ---
        toolStatusLabel = new Label("Checking tools...");
        toolStatusLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px; -fx-font-family: 'Consolas';");
        toolStatusLabel.setWrapText(true);

        TitledPane toolPane = new TitledPane("Tool Status", toolStatusLabel);
        toolPane.setExpanded(false);
        toolPane.setStyle("-fx-text-fill: #cccccc;");

        // --- Model Selection ---
        Label modelLabel = new Label("Whisper Model:");
        modelLabel.setStyle("-fx-text-fill: #cccccc;");
        modelSelector = new ComboBox<>();
        modelSelector.getItems().addAll(WhisperModel.values());
        modelSelector.setValue(WhisperModel.BASE);
        modelSelector.setMaxWidth(Double.MAX_VALUE);

        // Model description
        Label modelDesc = new Label(WhisperModel.BASE.description());
        modelDesc.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
        modelSelector.setOnAction(e -> {
            WhisperModel selected = modelSelector.getValue();
            if (selected != null) {
                modelDesc.setText(selected.description());
                if (!generator.getWhisperEngine().isModelAvailable(selected)) {
                    modelDesc.setText(selected.description() + " [MODEL NOT DOWNLOADED]");
                    modelDesc.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 11px;");
                } else {
                    modelDesc.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
                }
            }
        });

        // --- Language Selection ---
        Label langLabel = new Label("Language:");
        langLabel.setStyle("-fx-text-fill: #cccccc;");
        languageSelector = new ComboBox<>();
        languageSelector.getItems().addAll(WhisperLanguage.values());
        languageSelector.setValue(WhisperLanguage.AUTO);
        languageSelector.setMaxWidth(Double.MAX_VALUE);

        // --- Translate Option ---
        translateCheckBox = new CheckBox("Translate to English");
        translateCheckBox.setStyle("-fx-text-fill: #cccccc;");

        // --- Settings grid ---
        GridPane settingsGrid = new GridPane();
        settingsGrid.setHgap(12);
        settingsGrid.setVgap(8);
        settingsGrid.add(modelLabel, 0, 0);
        settingsGrid.add(modelSelector, 1, 0);
        settingsGrid.add(new Label(), 0, 1);
        settingsGrid.add(modelDesc, 1, 1);
        settingsGrid.add(langLabel, 0, 2);
        settingsGrid.add(languageSelector, 1, 2);
        settingsGrid.add(translateCheckBox, 1, 3);
        GridPane.setHgrow(modelSelector, Priority.ALWAYS);
        GridPane.setHgrow(languageSelector, Priority.ALWAYS);

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(100);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        settingsGrid.getColumnConstraints().addAll(labelCol, fieldCol);

        // --- Progress ---
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-accent: #4fc3f7;");

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px;");
        statusLabel.setWrapText(true);

        // --- Custom Path Config (collapsible) ---
        VBox pathConfig = buildPathConfig();
        TitledPane pathPane = new TitledPane("Tool Paths (Advanced)", pathConfig);
        pathPane.setExpanded(false);
        pathPane.setStyle("-fx-text-fill: #cccccc;");

        content.getChildren().addAll(
            fileLabel,
            new Separator(),
            settingsGrid,
            new Separator(),
            progressBar,
            statusLabel,
            toolPane,
            pathPane
        );

        pane.setContent(content);

        // --- Buttons ---
        ButtonType generateType = new ButtonType("Generate", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(generateType, cancelType);

        generateBtn = (Button) pane.lookupButton(generateType);
        cancelBtn = (Button) pane.lookupButton(cancelType);

        generateBtn.setStyle("-fx-background-color: #4fc3f7; -fx-text-fill: #000000; -fx-font-weight: bold;");

        // Handle generate action
        generateBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume(); // prevent dialog from closing
            startGeneration();
        });

        // Handle cancel - also cancel running task
        cancelBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (currentTask != null && currentTask.isRunning()) {
                currentTask.cancel();
                e.consume(); // first click cancels the task
                return;
            }
            // second click or no task running: close
        });

        setResultConverter(buttonType -> null); // result comes from callback
    }

    private VBox buildPathConfig() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(8));

        // FFmpeg path
        HBox ffmpegRow = new HBox(8);
        ffmpegRow.setAlignment(Pos.CENTER_LEFT);
        Label ffLabel = new Label("FFmpeg:");
        ffLabel.setStyle("-fx-text-fill: #cccccc; -fx-min-width: 70;");
        TextField ffmpegField = new TextField(
            generator.getAudioExtractor().getToolPath() != null
                ? generator.getAudioExtractor().getToolPath() : "");
        ffmpegField.setPromptText("Path to ffmpeg executable");
        HBox.setHgrow(ffmpegField, Priority.ALWAYS);
        Button ffBrowse = new Button("...");
        ffBrowse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select FFmpeg Executable");
            File file = fc.showOpenDialog(getOwner());
            if (file != null) {
                ffmpegField.setText(file.getAbsolutePath());
                generator.getAudioExtractor().setFfmpegPath(file.toPath());
                checkToolAvailability();
            }
        });
        ffmpegRow.getChildren().addAll(ffLabel, ffmpegField, ffBrowse);

        // Whisper path
        HBox whisperRow = new HBox(8);
        whisperRow.setAlignment(Pos.CENTER_LEFT);
        Label whLabel = new Label("Whisper:");
        whLabel.setStyle("-fx-text-fill: #cccccc; -fx-min-width: 70;");
        TextField whisperField = new TextField(
            generator.getWhisperEngine().getBinaryPath() != null
                ? generator.getWhisperEngine().getBinaryPath() : "");
        whisperField.setPromptText("Path to whisper executable");
        HBox.setHgrow(whisperField, Priority.ALWAYS);
        Button whBrowse = new Button("...");
        whBrowse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Whisper Executable");
            File file = fc.showOpenDialog(getOwner());
            if (file != null) {
                whisperField.setText(file.getAbsolutePath());
                generator.getWhisperEngine().setWhisperBinaryPath(file.toPath());
                checkToolAvailability();
            }
        });
        whisperRow.getChildren().addAll(whLabel, whisperField, whBrowse);

        // Models path
        HBox modelsRow = new HBox(8);
        modelsRow.setAlignment(Pos.CENTER_LEFT);
        Label mdLabel = new Label("Models:");
        mdLabel.setStyle("-fx-text-fill: #cccccc; -fx-min-width: 70;");
        TextField modelsField = new TextField(
            generator.getWhisperEngine().getModelsDirectory() != null
                ? generator.getWhisperEngine().getModelsDirectory().toString() : "");
        modelsField.setPromptText("Path to models directory");
        HBox.setHgrow(modelsField, Priority.ALWAYS);
        modelsRow.getChildren().addAll(mdLabel, modelsField);

        // Apply button
        Button applyPaths = new Button("Apply Paths");
        applyPaths.setStyle("-fx-background-color: #333333; -fx-text-fill: #cccccc;");
        applyPaths.setOnAction(e -> {
            if (!ffmpegField.getText().isBlank()) {
                generator.getAudioExtractor().setFfmpegPath(java.nio.file.Path.of(ffmpegField.getText()));
            }
            if (!whisperField.getText().isBlank()) {
                generator.getWhisperEngine().setWhisperBinaryPath(java.nio.file.Path.of(whisperField.getText()));
            }
            if (!modelsField.getText().isBlank()) {
                generator.getWhisperEngine().setModelsDirectory(java.nio.file.Path.of(modelsField.getText()));
            }
            checkToolAvailability();
        });

        box.getChildren().addAll(ffmpegRow, whisperRow, modelsRow, applyPaths);
        return box;
    }

    private void checkToolAvailability() {
        SubtitleGenerator.AvailabilityStatus status = generator.checkAvailability();
        toolStatusLabel.setText(status.getSummary());

        if (status.isReady()) {
            toolStatusLabel.setStyle("-fx-text-fill: #81c784; -fx-font-size: 11px; -fx-font-family: 'Consolas';");
            generateBtn.setDisable(false);
        } else {
            toolStatusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 11px; -fx-font-family: 'Consolas';");
            generateBtn.setDisable(true);

            String help = status.getSummary() + "\n\nRequired tools:\n" +
                "- FFmpeg: https://ffmpeg.org/download.html\n" +
                "- Whisper.cpp: https://github.com/ggerganov/whisper.cpp/releases";
            toolStatusLabel.setText(help);
        }

        // Check model availability
        WhisperModel selectedModel = modelSelector.getValue();
        if (selectedModel != null && !generator.getWhisperEngine().isModelAvailable(selectedModel)) {
            statusLabel.setText("Model '" + selectedModel.fileName() + "' not found in models directory.");
            statusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 12px;");
        }
    }

    private void startGeneration() {
        if (mediaFile == null) {
            statusLabel.setText("No media file loaded");
            return;
        }

        WhisperModel model = modelSelector.getValue();
        WhisperLanguage lang = languageSelector.getValue();
        boolean translate = translateCheckBox.isSelected();

        // Disable controls during generation
        generateBtn.setDisable(true);
        modelSelector.setDisable(true);
        languageSelector.setDisable(true);
        translateCheckBox.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1); // indeterminate

        cancelBtn.setText("Cancel");

        currentTask = new TranscriptionTask(generator, mediaFile, model, lang, translate);

        // Bind progress
        progressBar.progressProperty().bind(currentTask.progressProperty());
        statusLabel.textProperty().bind(currentTask.messageProperty());
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px;");

        currentTask.setOnSucceeded(e -> {
            File result = currentTask.getValue();
            resetControls();
            if (result != null) {
                statusLabel.setStyle("-fx-text-fill: #81c784; -fx-font-size: 12px;");
                onSubtitleGenerated.accept(result);
            } else {
                statusLabel.setText("Transcription produced no output");
                statusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 12px;");
            }
        });

        currentTask.setOnFailed(e -> {
            resetControls();
            String msg = currentTask.getException() != null
                ? currentTask.getException().getMessage()
                : "Unknown error";
            statusLabel.setText("Failed: " + msg);
            statusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 12px;");
        });

        currentTask.setOnCancelled(e -> {
            resetControls();
            statusLabel.setText("Cancelled");
            statusLabel.setStyle("-fx-text-fill: #ffb74d; -fx-font-size: 12px;");
        });

        Thread thread = new Thread(currentTask, "subtitle-generation");
        thread.setDaemon(true);
        thread.start();
    }

    private void resetControls() {
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        generateBtn.setDisable(false);
        modelSelector.setDisable(false);
        languageSelector.setDisable(false);
        translateCheckBox.setDisable(false);
        cancelBtn.setText("Close");
    }
}
