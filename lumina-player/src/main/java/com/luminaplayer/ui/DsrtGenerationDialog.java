package com.luminaplayer.ui;

import com.luminaplayer.ai.*;
import com.luminaplayer.ai.orchestration.*;
import com.luminaplayer.subtitle.ChunkStatus;
import com.luminaplayer.subtitle.DsrtFile;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dialog for configuring and running parallel chunked subtitle generation
 * using the .dsrt (Dynamic SRT) format. Processes the first 30-second chunk
 * immediately, then generates subsequent chunks in parallel via multithreading.
 */
public class DsrtGenerationDialog extends VBox {

    private static final long DEFAULT_CHUNK_DURATION_MS = 60_000;

    private final Stage owner;
    private final Runnable onClose;
    private final ChunkedSubtitleGenerator generator;
    private final File mediaFile;
    private final long totalDurationMs;
    private final long currentTimeMs;
    private final Consumer<DsrtFile> onFirstChunkReady;
    private final Consumer<DsrtFile> onComplete;
    private final Consumer<Boolean> onGenerationStateChanged;

    private ComboBox<WhisperModel> modelSelector;
    private ComboBox<WhisperLanguage> languageSelector;
    private ComboBox<WhisperQuality> qualitySelector;
    private CheckBox translateCheckBox;
    private Spinner<Integer> chunkDurationSpinner;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label toolStatusLabel;
    private Label chunkSummaryLabel;
    private Label modelDesc;
    private Button generateBtn;
    private Button cancelBtn;
    private Button downloadBtn;
    private FlowPane chunkGrid;
    private List<Region> chunkIndicators;
    private ChunkedTranscriptionTask currentTask;
    private ModelDownloadTask currentDownloadTask;

    // Orchestration controls
    private ComboBox<String> orchestrationModeSelector;
    private ComboBox<String> providerSelector;
    private TextField providerUrlField;
    private TextField ollamaModelField;
    private CheckBox keepOriginalCheckBox;
    private Label orchestrationStatusLabel;

    public DsrtGenerationDialog(Stage owner, File mediaFile, long totalDurationMs,
                                 long currentTimeMs,
                                 Consumer<DsrtFile> onFirstChunkReady,
                                 Consumer<DsrtFile> onComplete,
                                 Consumer<Boolean> onGenerationStateChanged,
                                 Runnable onClose) {
        this.owner = owner;
        this.onClose = onClose;
        this.generator = new ChunkedSubtitleGenerator();
        this.mediaFile = mediaFile;
        this.totalDurationMs = totalDurationMs;
        this.currentTimeMs = currentTimeMs;
        this.onFirstChunkReady = onFirstChunkReady;
        this.onComplete = onComplete;
        this.onGenerationStateChanged = onGenerationStateChanged;

        setStyle("-fx-background-color: #1e1e2e;");
        setPrefWidth(340);
        setMaxWidth(400);

        buildContent();
        checkToolAvailability();
    }

    /** Cancel any running tasks and clean up. */
    public void dispose() {
        if (currentDownloadTask != null && currentDownloadTask.isRunning()) currentDownloadTask.cancel();
        if (currentTask != null && currentTask.isRunning()) currentTask.cancel();
    }

    /**
     * Finds the best available model (largest downloaded model).
     */
    private WhisperModel getBestAvailableModel() {
        WhisperEngine engine = generator.getWhisperEngine();
        WhisperModel[] models = WhisperModel.values();
        // Search from largest to smallest
        for (int i = models.length - 1; i >= 0; i--) {
            if (engine.isModelAvailable(models[i])) {
                return models[i];
            }
        }
        return WhisperModel.BASE; // fallback
    }

    /**
     * Finds the best available model at or above a minimum level.
     */
    private WhisperModel getBestAvailableModelAtLeast(WhisperModel minimum) {
        WhisperEngine engine = generator.getWhisperEngine();
        // First try to find a model >= minimum
        WhisperModel[] models = WhisperModel.values();
        for (int i = models.length - 1; i >= minimum.ordinal(); i--) {
            if (engine.isModelAvailable(models[i])) {
                return models[i];
            }
        }
        // Fall back to best available
        return getBestAvailableModel();
    }

    private void updateModelDescription() {
        WhisperModel selected = modelSelector.getValue();
        if (selected != null) {
            boolean available = generator.getWhisperEngine().isModelAvailable(selected);
            if (!available) {
                modelDesc.setText(selected.description() + " [NOT DOWNLOADED - click Download]");
                modelDesc.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 11px;");
                downloadBtn.setVisible(true);
                downloadBtn.setText("Download " + selected.modelName() + " (" + selected.sizeMb() + "MB)");
            } else {
                modelDesc.setText(selected.description());
                modelDesc.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
                downloadBtn.setVisible(false);
            }
        }
    }

    private void buildContent() {
        // Header with title and close button
        Label titleLabel = new Label("Generate Dynamic Subtitles (.dsrt)");
        titleLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 14px; -fx-font-weight: bold;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        Button closeBtn = new Button("\u2715");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #aaaaaa; -fx-font-size: 14px; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> { if (onClose != null) onClose.run(); });
        HBox header = new HBox(titleLabel, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 8, 4, 12));
        header.setStyle("-fx-background-color: #151525;");

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // --- File Info ---
        Label fileLabel = new Label("Media file: " + (mediaFile != null ? mediaFile.getName() : "None"));
        fileLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 13px;");

        int totalChunks = totalDurationMs > 0
            ? (int) Math.ceil((double) totalDurationMs / DEFAULT_CHUNK_DURATION_MS) : 0;
        String durationStr = String.format("Duration: %.1fs | Chunks: %d x 30s",
            totalDurationMs / 1000.0, totalChunks);
        chunkSummaryLabel = new Label(durationStr);
        chunkSummaryLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px;");

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

        // Default to best available model
        WhisperModel bestAvailable = getBestAvailableModel();
        modelSelector.setValue(bestAvailable);
        modelSelector.setMaxWidth(Double.MAX_VALUE);

        modelDesc = new Label(bestAvailable.description());
        modelDesc.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");

        // Download button for missing models
        downloadBtn = new Button("Download");
        downloadBtn.setStyle("-fx-background-color: #4fc3f7; -fx-text-fill: #000000; -fx-font-size: 11px;");
        downloadBtn.setVisible(false);
        downloadBtn.setOnAction(e -> startModelDownload());

        HBox modelRow = new HBox(8, modelSelector, downloadBtn);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modelSelector, Priority.ALWAYS);

        modelSelector.setOnAction(e -> updateModelDescription());

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

        // --- Quality Selection ---
        Label qualityLabel = new Label("Quality:");
        qualityLabel.setStyle("-fx-text-fill: #cccccc;");
        qualitySelector = new ComboBox<>();
        qualitySelector.getItems().addAll(WhisperQuality.values());
        qualitySelector.setValue(WhisperQuality.BALANCED);
        qualitySelector.setMaxWidth(Double.MAX_VALUE);

        Label qualityDesc = new Label(WhisperQuality.BALANCED.description());
        qualityDesc.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
        qualitySelector.setOnAction(e -> {
            WhisperQuality selected = qualitySelector.getValue();
            if (selected != null) {
                qualityDesc.setText(selected.description());
            }
        });

        // --- Chunk Duration ---
        Label chunkLabel = new Label("Chunk (sec):");
        chunkLabel.setStyle("-fx-text-fill: #cccccc;");
        chunkDurationSpinner = new Spinner<>(15, 120, 60, 15);
        chunkDurationSpinner.setEditable(true);
        chunkDurationSpinner.setMaxWidth(Double.MAX_VALUE);

        // Input validation: only allow numeric input and clamp on focus lost
        chunkDurationSpinner.getEditor().setTextFormatter(new javafx.scene.control.TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.matches("\\d*")) {
                return change;
            }
            return null;
        }));
        chunkDurationSpinner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                try {
                    int val = Integer.parseInt(chunkDurationSpinner.getEditor().getText());
                    val = Math.max(15, Math.min(120, val));
                    chunkDurationSpinner.getValueFactory().setValue(val);
                } catch (NumberFormatException ex) {
                    chunkDurationSpinner.getValueFactory().setValue(60);
                }
            }
        });

        Label chunkDescLabel = new Label("Longer chunks give Whisper more context for better accuracy");
        chunkDescLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
        chunkDescLabel.setWrapText(true);

        // Update chunk summary when chunk duration changes
        chunkDurationSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal > 0) {
                int chunks = (int) Math.ceil((double) totalDurationMs / (newVal * 1000L));
                chunkSummaryLabel.setText(String.format("Duration: %.1fs | Chunks: %d x %ds",
                    totalDurationMs / 1000.0, chunks, newVal));
                rebuildChunkGrid(chunks);
            }
        });

        // Auto-upgrade model & quality for non-English languages
        Label autoUpgradeLabel = new Label("");
        autoUpgradeLabel.setStyle("-fx-text-fill: #4fc3f7; -fx-font-size: 11px;");
        autoUpgradeLabel.setWrapText(true);

        Runnable autoUpgrade = () -> {
            WhisperLanguage lang = languageSelector.getValue();
            boolean isNonEnglish = lang != null && lang != WhisperLanguage.AUTO && lang != WhisperLanguage.ENGLISH;
            boolean isTranslate = translateCheckBox.isSelected();

            if (isNonEnglish || isTranslate) {
                // Auto-upgrade to best available model >= SMALL
                WhisperModel currentModel = modelSelector.getValue();
                WhisperModel recommended = getBestAvailableModelAtLeast(WhisperModel.SMALL);
                if (currentModel != null && currentModel.ordinal() < recommended.ordinal()) {
                    modelSelector.setValue(recommended);
                    updateModelDescription();
                }
                // If nothing >= SMALL is available, show SMALL as selected so
                // the download button appears
                if (recommended.ordinal() < WhisperModel.SMALL.ordinal()) {
                    modelSelector.setValue(WhisperModel.SMALL);
                    updateModelDescription();
                }
                // Upgrade quality to BALANCED (fast enough while accurate)
                if (qualitySelector.getValue() == WhisperQuality.FAST
                        || qualitySelector.getValue() == WhisperQuality.INSTANT) {
                    qualitySelector.setValue(WhisperQuality.BALANCED);
                    qualityDesc.setText(WhisperQuality.BALANCED.description());
                }
                String modelName = modelSelector.getValue().modelName().toUpperCase();
                autoUpgradeLabel.setText("Auto-upgraded to " + modelName +
                    " model, BALANCED quality for non-English accuracy");
            } else {
                autoUpgradeLabel.setText("");
            }
        };

        languageSelector.setOnAction(e -> autoUpgrade.run());
        translateCheckBox.setOnAction(e -> autoUpgrade.run());

        // --- Settings grid ---
        GridPane settingsGrid = new GridPane();
        settingsGrid.setHgap(12);
        settingsGrid.setVgap(8);
        settingsGrid.add(modelLabel, 0, 0);
        settingsGrid.add(modelRow, 1, 0);
        settingsGrid.add(new Label(), 0, 1);
        settingsGrid.add(modelDesc, 1, 1);
        settingsGrid.add(langLabel, 0, 2);
        settingsGrid.add(languageSelector, 1, 2);
        settingsGrid.add(translateCheckBox, 1, 3);
        settingsGrid.add(qualityLabel, 0, 4);
        settingsGrid.add(qualitySelector, 1, 4);
        settingsGrid.add(new Label(), 0, 5);
        settingsGrid.add(qualityDesc, 1, 5);
        settingsGrid.add(chunkLabel, 0, 6);
        settingsGrid.add(chunkDurationSpinner, 1, 6);
        settingsGrid.add(new Label(), 0, 7);
        settingsGrid.add(chunkDescLabel, 1, 7);
        settingsGrid.add(autoUpgradeLabel, 0, 8, 2, 1);
        GridPane.setHgrow(modelRow, Priority.ALWAYS);
        GridPane.setHgrow(languageSelector, Priority.ALWAYS);
        GridPane.setHgrow(qualitySelector, Priority.ALWAYS);
        GridPane.setHgrow(chunkDurationSpinner, Priority.ALWAYS);

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(100);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        settingsGrid.getColumnConstraints().addAll(labelCol, fieldCol);

        // --- Chunk Progress Grid ---
        Label chunkGridLabel = new Label("Chunk Progress:");
        chunkGridLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12px;");

        // --- Orchestration Settings (collapsible) ---
        VBox orchestrationContent = buildOrchestrationPanel();
        TitledPane orchestrationPane = new TitledPane("Multi-Model Translation (AI Orchestration)", orchestrationContent);
        orchestrationPane.setExpanded(false);
        orchestrationPane.setStyle("-fx-text-fill: #cccccc;");

        chunkGrid = new FlowPane(4, 4);
        chunkGrid.setPrefWrapLength(460);
        chunkIndicators = new ArrayList<>();

        for (int i = 0; i < totalChunks; i++) {
            Region indicator = new Region();
            indicator.setMinSize(16, 16);
            indicator.setMaxSize(16, 16);
            indicator.setStyle("-fx-background-color: #555555; -fx-background-radius: 3;");
            Tooltip.install(indicator, new Tooltip("Chunk " + (i + 1) + ": Pending"));
            chunkIndicators.add(indicator);
            chunkGrid.getChildren().add(indicator);
        }

        VBox chunkProgressBox = new VBox(4, chunkGridLabel, chunkGrid);
        chunkProgressBox.setVisible(totalChunks > 0);

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
            chunkSummaryLabel,
            new Separator(),
            settingsGrid,
            new Separator(),
            orchestrationPane,
            new Separator(),
            chunkProgressBox,
            progressBar,
            statusLabel,
            toolPane,
            pathPane
        );

        ScrollPane contentScroll = new ScrollPane(content);
        contentScroll.setFitToWidth(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentScroll.setPannable(true);
        contentScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(contentScroll, Priority.ALWAYS);

        // --- Buttons ---
        generateBtn = new Button("Generate");
        generateBtn.setStyle("-fx-background-color: #4fc3f7; -fx-text-fill: #000000; -fx-font-weight: bold; -fx-min-width: 90;");
        generateBtn.setOnAction(e -> startGeneration());

        cancelBtn = new Button("Cancel");
        cancelBtn.setStyle("-fx-min-width: 70;");
        cancelBtn.setOnAction(e -> {
            if (currentDownloadTask != null && currentDownloadTask.isRunning()) {
                currentDownloadTask.cancel();
                return;
            }
            if (currentTask != null && currentTask.isRunning()) {
                currentTask.cancel();
                return;
            }
            if (onClose != null) onClose.run();
        });

        HBox buttonBar = new HBox(10, generateBtn, cancelBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(8, 12, 8, 12));
        buttonBar.setStyle("-fx-background-color: #151525;");

        getChildren().addAll(header, new Separator(), contentScroll, buttonBar);
    }

    private void startModelDownload() {
        WhisperModel model = modelSelector.getValue();
        if (model == null || generator.getWhisperEngine().isModelAvailable(model)) {
            return;
        }

        java.nio.file.Path modelsDir = generator.getWhisperEngine().getModelsDirectory();
        if (modelsDir == null) {
            statusLabel.setText("Models directory not set");
            statusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 12px;");
            return;
        }

        // Disable controls during download
        generateBtn.setDisable(true);
        downloadBtn.setDisable(true);
        modelSelector.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        cancelBtn.setText("Cancel");

        currentDownloadTask = new ModelDownloadTask(model, modelsDir);

        progressBar.progressProperty().bind(currentDownloadTask.progressProperty());
        statusLabel.textProperty().bind(currentDownloadTask.messageProperty());
        statusLabel.setStyle("-fx-text-fill: #4fc3f7; -fx-font-size: 12px;");

        currentDownloadTask.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            progressBar.setVisible(false);
            generateBtn.setDisable(false);
            downloadBtn.setDisable(false);
            modelSelector.setDisable(false);
            cancelBtn.setText("Close");

            statusLabel.setText("Model " + model.modelName() + " downloaded successfully!");
            statusLabel.setStyle("-fx-text-fill: #81c784; -fx-font-size: 12px;");

            // Refresh model status
            updateModelDescription();
            currentDownloadTask = null;
        });

        currentDownloadTask.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            progressBar.setVisible(false);
            generateBtn.setDisable(false);
            downloadBtn.setDisable(false);
            modelSelector.setDisable(false);
            cancelBtn.setText("Close");

            String msg = currentDownloadTask.getException() != null
                ? currentDownloadTask.getException().getMessage() : "Unknown error";
            statusLabel.setText("Download failed: " + msg);
            statusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 12px;");
            currentDownloadTask = null;
        });

        currentDownloadTask.setOnCancelled(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            progressBar.setVisible(false);
            generateBtn.setDisable(false);
            downloadBtn.setDisable(false);
            modelSelector.setDisable(false);
            cancelBtn.setText("Close");

            statusLabel.setText("Download cancelled");
            statusLabel.setStyle("-fx-text-fill: #ffb74d; -fx-font-size: 12px;");
            currentDownloadTask = null;
        });

        Thread dlThread = new Thread(currentDownloadTask, "model-download");
        dlThread.setDaemon(true);
        dlThread.start();
    }

    private VBox buildOrchestrationPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10));

        Label infoLabel = new Label(
                "Multi-model orchestration improves non-English subtitle quality by using " +
                "Whisper for native-language transcription, then a dedicated translation " +
                "model for accurate English translation.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");

        // Mode selector
        Label modeLabel = new Label("Mode:");
        modeLabel.setStyle("-fx-text-fill: #cccccc;");
        orchestrationModeSelector = new ComboBox<>();
        orchestrationModeSelector.getItems().addAll(
                "Single Pass (Whisper only)",
                "Multi-Model (Whisper + Translation)",
                "Multi-Model + Verification"
        );
        orchestrationModeSelector.setValue("Single Pass (Whisper only)");
        orchestrationModeSelector.setMaxWidth(Double.MAX_VALUE);

        // Provider selector
        Label provLabel = new Label("Provider:");
        provLabel.setStyle("-fx-text-fill: #cccccc;");
        providerSelector = new ComboBox<>();
        providerSelector.getItems().addAll("Argos Translate (Offline)", "LibreTranslate", "Ollama (LLM)");
        providerSelector.setValue("Argos Translate (Offline)");
        providerSelector.setMaxWidth(Double.MAX_VALUE);

        // Provider URL (hidden for Argos Translate since it's offline/local)
        Label urlLabel = new Label("Server URL:");
        urlLabel.setStyle("-fx-text-fill: #cccccc;");
        providerUrlField = new TextField("");
        providerUrlField.setPromptText("http://localhost:5000");
        // Hide URL by default since Argos (default) doesn't need it
        urlLabel.setVisible(false);
        urlLabel.setManaged(false);
        providerUrlField.setVisible(false);
        providerUrlField.setManaged(false);

        // Ollama model name (shown when Ollama is selected)
        Label ollamaModelLabel = new Label("LLM Model:");
        ollamaModelLabel.setStyle("-fx-text-fill: #cccccc;");
        ollamaModelField = new TextField("llama3");
        ollamaModelField.setPromptText("llama3, qwen2, mistral...");
        ollamaModelLabel.setVisible(false);
        ollamaModelField.setVisible(false);
        ollamaModelLabel.setManaged(false);
        ollamaModelField.setManaged(false);

        // Keep original text checkbox
        keepOriginalCheckBox = new CheckBox("Show original text alongside translation");
        keepOriginalCheckBox.setStyle("-fx-text-fill: #cccccc;");
        keepOriginalCheckBox.setSelected(true);

        Label targetLangLabel = new Label("Target language: English");
        targetLangLabel.setStyle("-fx-text-fill: #4fc3f7; -fx-font-size: 11px;");

        // Status label
        orchestrationStatusLabel = new Label("");
        orchestrationStatusLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
        orchestrationStatusLabel.setWrapText(true);

        // Check availability button
        Button checkBtn = new Button("Check Availability");
        checkBtn.setStyle("-fx-background-color: #333333; -fx-text-fill: #cccccc; -fx-font-size: 11px;");
        checkBtn.setOnAction(e -> checkOrchestrationAvailability());

        // Provider changes update URL defaults and visibility
        providerSelector.setOnAction(e -> {
            String provider = providerSelector.getValue();
            boolean isOllama = "Ollama (LLM)".equals(provider);
            boolean isArgos = provider != null && provider.startsWith("Argos");
            ollamaModelLabel.setVisible(isOllama);
            ollamaModelField.setVisible(isOllama);
            ollamaModelLabel.setManaged(isOllama);
            ollamaModelField.setManaged(isOllama);
            // Argos doesn't need a URL, hide URL field
            urlLabel.setVisible(!isArgos);
            providerUrlField.setVisible(!isArgos);
            urlLabel.setManaged(!isArgos);
            providerUrlField.setManaged(!isArgos);
            if (isArgos) {
                providerUrlField.setText("");
            } else {
                providerUrlField.setText(isOllama ? "http://localhost:11434" : "http://localhost:5000");
            }
            orchestrationStatusLabel.setText("");
        });

        // Mode changes enable/disable controls
        VBox providerControls = new VBox(6);
        orchestrationModeSelector.setOnAction(e -> {
            boolean isMultiModel = !orchestrationModeSelector.getValue().startsWith("Single");
            providerControls.setVisible(isMultiModel);
            providerControls.setManaged(isMultiModel);
            translateCheckBox.setSelected(isMultiModel);
            translateCheckBox.setDisable(isMultiModel);
        });

        // Grid for provider settings
        GridPane provGrid = new GridPane();
        provGrid.setHgap(10);
        provGrid.setVgap(6);
        provGrid.add(provLabel, 0, 0);
        provGrid.add(providerSelector, 1, 0);
        provGrid.add(urlLabel, 0, 1);
        provGrid.add(providerUrlField, 1, 1);
        provGrid.add(ollamaModelLabel, 0, 2);
        provGrid.add(ollamaModelField, 1, 2);
        GridPane.setHgrow(providerSelector, Priority.ALWAYS);
        GridPane.setHgrow(providerUrlField, Priority.ALWAYS);
        GridPane.setHgrow(ollamaModelField, Priority.ALWAYS);
        ColumnConstraints lc = new ColumnConstraints();
        lc.setMinWidth(80);
        ColumnConstraints fc = new ColumnConstraints();
        fc.setHgrow(Priority.ALWAYS);
        provGrid.getColumnConstraints().addAll(lc, fc);

        providerControls.getChildren().addAll(provGrid, targetLangLabel, keepOriginalCheckBox,
                new HBox(8, checkBtn, orchestrationStatusLabel));
        providerControls.setVisible(false);
        providerControls.setManaged(false);

        box.getChildren().addAll(infoLabel, modeLabel, orchestrationModeSelector, providerControls);
        return box;
    }

    private void checkOrchestrationAvailability() {
        TranslationProvider provider = buildTranslationProvider();
        if (provider == null) {
            orchestrationStatusLabel.setText("No provider configured");
            orchestrationStatusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 11px;");
            return;
        }

        orchestrationStatusLabel.setText("Checking...");
        orchestrationStatusLabel.setStyle("-fx-text-fill: #4fc3f7; -fx-font-size: 11px;");

        // Check availability in background thread
        Thread checkThread = new Thread(() -> {
            String status = provider.getAvailabilityStatus();
            boolean available = provider.isAvailable();
            javafx.application.Platform.runLater(() -> {
                orchestrationStatusLabel.setText(status);
                orchestrationStatusLabel.setStyle(available
                        ? "-fx-text-fill: #81c784; -fx-font-size: 11px;"
                        : "-fx-text-fill: #ff6b6b; -fx-font-size: 11px;");
            });
        }, "orchestration-check");
        checkThread.setDaemon(true);
        checkThread.start();
    }

    private TranslationProvider buildTranslationProvider() {
        String provider = providerSelector.getValue();
        String url = providerUrlField.getText().trim();

        if (provider != null && provider.startsWith("Argos")) {
            return new ArgosTranslateProvider();
        } else if ("Ollama (LLM)".equals(provider)) {
            String model = ollamaModelField.getText().trim();
            if (model.isEmpty()) model = "llama3";
            return new OllamaTranslationProvider(
                    url.isEmpty() ? "http://localhost:11434" : url, model);
        } else {
            return new LibreTranslateProvider(
                    url.isEmpty() ? "http://localhost:5000" : url, null);
        }
    }

    private OrchestrationConfig buildOrchestrationConfig() {
        String mode = orchestrationModeSelector.getValue();
        if (mode == null || mode.startsWith("Single")) {
            return null; // No orchestration
        }

        OrchestrationConfig config = new OrchestrationConfig();
        TranslationProvider provider = buildTranslationProvider();

        if (mode.contains("Verification")) {
            config.setMode(OrchestrationConfig.Mode.MULTI_MODEL_VERIFIED);
            config.setVerificationProvider(provider); // Same provider for verification
        } else {
            config.setMode(OrchestrationConfig.Mode.MULTI_MODEL);
        }

        config.setTranslationProvider(provider);
        WhisperLanguage selectedLanguage = languageSelector.getValue();
        boolean forceKeepOriginal = selectedLanguage == WhisperLanguage.JAPANESE;
        config.setKeepOriginalText(forceKeepOriginal || keepOriginalCheckBox.isSelected());
        config.setTargetLanguage("en");
        return config;
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
            File file = fc.showOpenDialog(owner);
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
            File file = fc.showOpenDialog(owner);
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

        updateModelDescription();
    }

    private void startGeneration() {
        if (mediaFile == null || totalDurationMs <= 0) {
            statusLabel.setText("No media file loaded or unknown duration");
            return;
        }

        WhisperModel model = modelSelector.getValue();

        // If model is not downloaded, auto-download it first, then generate
        if (!generator.getWhisperEngine().isModelAvailable(model)) {
            java.nio.file.Path modelsDir = generator.getWhisperEngine().getModelsDirectory();
            if (modelsDir == null) {
                statusLabel.setText("Models directory not set");
                statusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 12px;");
                return;
            }

            // Disable all controls
            generateBtn.setDisable(true);
            downloadBtn.setDisable(true);
            modelSelector.setDisable(true);
            languageSelector.setDisable(true);
            translateCheckBox.setDisable(true);
            qualitySelector.setDisable(true);
            chunkDurationSpinner.setDisable(true);
            progressBar.setVisible(true);
            progressBar.setProgress(-1);
            cancelBtn.setText("Cancel");

            currentDownloadTask = new ModelDownloadTask(model, modelsDir);
            progressBar.progressProperty().bind(currentDownloadTask.progressProperty());
            statusLabel.textProperty().bind(currentDownloadTask.messageProperty());
            statusLabel.setStyle("-fx-text-fill: #4fc3f7; -fx-font-size: 12px;");

            // After download succeeds, automatically start generation
            currentDownloadTask.setOnSucceeded(e -> {
                progressBar.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progressBar.setVisible(false);
                updateModelDescription();
                currentDownloadTask = null;
                // Now proceed with generation
                doStartGeneration();
            });

            currentDownloadTask.setOnFailed(e -> {
                progressBar.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progressBar.setVisible(false);
                resetControls();
                String msg = currentDownloadTask.getException() != null
                    ? currentDownloadTask.getException().getMessage() : "Unknown error";
                statusLabel.setText("Model download failed: " + msg);
                statusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 12px;");
                currentDownloadTask = null;
            });

            currentDownloadTask.setOnCancelled(e -> {
                progressBar.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progressBar.setVisible(false);
                resetControls();
                statusLabel.setText("Download cancelled");
                statusLabel.setStyle("-fx-text-fill: #ffb74d; -fx-font-size: 12px;");
                currentDownloadTask = null;
            });

            Thread dlThread = new Thread(currentDownloadTask, "model-download");
            dlThread.setDaemon(true);
            dlThread.start();
            return;
        }

        doStartGeneration();
    }

    private void doStartGeneration() {
        WhisperModel model = modelSelector.getValue();
        WhisperLanguage lang = languageSelector.getValue();
        boolean translate = translateCheckBox.isSelected();
        WhisperQuality quality = qualitySelector.getValue();
        long chunkDurationMs = chunkDurationSpinner.getValue() * 1000L;

        boolean usingArgosMultiModel = orchestrationModeSelector != null
            && orchestrationModeSelector.getValue() != null
            && !orchestrationModeSelector.getValue().startsWith("Single")
            && providerSelector != null
            && providerSelector.getValue() != null
            && providerSelector.getValue().startsWith("Argos");

        if (usingArgosMultiModel && lang == WhisperLanguage.AUTO) {
            statusLabel.setText("Argos Translate needs a source language. Set Language to the spoken language (not Auto-detect).");
            statusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 12px;");
            return;
        }

        // Configure orchestration if enabled
        OrchestrationConfig orchestrationConfig = buildOrchestrationConfig();
        if (orchestrationConfig != null && orchestrationConfig.isMultiModel()) {
            // When using multi-model orchestration, Whisper should transcribe
            // in the native language (NOT translate), for best accuracy
            translate = false;
            generator.setOrchestrationConfig(orchestrationConfig);
        } else {
            generator.setOrchestrationConfig(null);
        }

        // Disable controls during generation
        generateBtn.setDisable(true);
        modelSelector.setDisable(true);
        languageSelector.setDisable(true);
        translateCheckBox.setDisable(true);
        qualitySelector.setDisable(true);
        chunkDurationSpinner.setDisable(true);
        downloadBtn.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        if (onGenerationStateChanged != null) {
            onGenerationStateChanged.accept(true);
        }

        cancelBtn.setText("Cancel");

        // Reset chunk indicators
        for (Region indicator : chunkIndicators) {
            indicator.setStyle("-fx-background-color: #555555; -fx-background-radius: 3;");
        }

        currentTask = new ChunkedTranscriptionTask(
            generator, mediaFile, totalDurationMs, chunkDurationMs,
            model, lang, translate, quality, currentTimeMs,
            // On first chunk ready
            dsrtFile -> {
                if (onFirstChunkReady != null) {
                    onFirstChunkReady.accept(dsrtFile);
                }
            },
            // On chunk progress (keeps updating indicators even after task completes)
            this::onChunkProgress,
            // On all background chunks complete
            () -> {
                resetControls();
                DsrtFile result = currentTask.getValue();
                if (result != null) {
                    statusLabel.setText(String.format("All chunks complete: %d cues generated",
                        result.getCueCount()));
                    statusLabel.setStyle("-fx-text-fill: #81c784; -fx-font-size: 12px;");
                    if (onComplete != null) {
                        onComplete.accept(result);
                    }
                }
            }
        );

        // Bind progress
        progressBar.progressProperty().bind(currentTask.progressProperty());
        statusLabel.textProperty().bind(currentTask.messageProperty());
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px;");

        currentTask.setOnSucceeded(e -> {
            // Task returned after first chunk -- background threads still running
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            statusLabel.setText("Generating remaining chunks in background...");
            statusLabel.setStyle("-fx-text-fill: #4fc3f7; -fx-font-size: 12px;");
            // Keep chunk indicators updating via onChunkProgress
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
            statusLabel.setText("Cancelled - completed chunks are saved");
            statusLabel.setStyle("-fx-text-fill: #ffb74d; -fx-font-size: 12px;");
        });

        Thread thread = new Thread(currentTask, "dsrt-generation");
        thread.setDaemon(true);
        thread.start();
    }

    private void onChunkProgress(ChunkProgressEvent event) {
        // Update chunk indicator color
        if (event.chunkIndex() >= 0 && event.chunkIndex() < chunkIndicators.size()) {
            Region indicator = chunkIndicators.get(event.chunkIndex());
            String color = switch (event.status()) {
                case PENDING -> "#555555";
                case EXTRACTING -> "#ffb74d";
                case TRANSCRIBING -> "#4fc3f7";
                case COMPLETED -> "#81c784";
                case FAILED -> "#ff6b6b";
            };
            indicator.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 3;");
            Tooltip.install(indicator, new Tooltip(
                "Chunk " + (event.chunkIndex() + 1) + ": " + event.status().displayName()));
        }
    }

    private void resetControls() {
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        generateBtn.setDisable(false);
        modelSelector.setDisable(false);
        languageSelector.setDisable(false);
        translateCheckBox.setDisable(false);
        qualitySelector.setDisable(false);
        chunkDurationSpinner.setDisable(false);
        downloadBtn.setDisable(false);
        cancelBtn.setText("Close");
        if (onGenerationStateChanged != null) {
            onGenerationStateChanged.accept(false);
        }
    }

    private void rebuildChunkGrid(int totalChunks) {
        chunkGrid.getChildren().clear();
        chunkIndicators.clear();
        for (int i = 0; i < totalChunks; i++) {
            Region indicator = new Region();
            indicator.setMinSize(16, 16);
            indicator.setMaxSize(16, 16);
            indicator.setStyle("-fx-background-color: #555555; -fx-background-radius: 3;");
            Tooltip.install(indicator, new Tooltip("Chunk " + (i + 1) + ": Pending"));
            chunkIndicators.add(indicator);
            chunkGrid.getChildren().add(indicator);
        }
    }
}
