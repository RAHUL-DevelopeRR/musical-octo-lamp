package com.luminaplayer.ui;

import com.luminaplayer.ai.*;
import com.luminaplayer.ai.orchestration.*;
import com.luminaplayer.subtitle.ChunkStatus;
import com.luminaplayer.subtitle.DsrtFile;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * LuminaPlayer — AI Subtitle Generation Panel (redesigned)
 *
 * UI/UX design principles applied:
 *  • Progressive disclosure: basic controls visible, advanced collapsed
 *  • Live stage feedback: 5-phase progress labels per chunk
 *  • Sound-events toggle with accessible tooltip explanation
 *  • Compact glassmorphism card layout — consistent 8px grid spacing
 *  • Colour semantics: cyan=active, green=done, amber=warn, red=error
 */
public class DsrtGenerationDialog extends VBox {

    // ── Styles ──────────────────────────────────────────────────────────────
    private static final String S_BG          = "-fx-background-color: #13131f;";
    private static final String S_CARD        = "-fx-background-color: rgba(255,255,255,0.05); "
                                               + "-fx-background-radius: 10; "
                                               + "-fx-border-color: rgba(255,255,255,0.08); "
                                               + "-fx-border-radius: 10; "
                                               + "-fx-border-width: 1;";
    private static final String S_HEADER_BG   = "-fx-background-color: #0d0d1a;";
    private static final String S_LABEL       = "-fx-text-fill: #c8c8d8; -fx-font-size: 12px;";
    private static final String S_LABEL_DIM   = "-fx-text-fill: #606070; -fx-font-size: 11px;";
    private static final String S_LABEL_CYAN  = "-fx-text-fill: #00d4ff; -fx-font-size: 11px;";
    private static final String S_LABEL_GREEN = "-fx-text-fill: #69db7c; -fx-font-size: 12px;";
    private static final String S_LABEL_AMBER = "-fx-text-fill: #ffd43b; -fx-font-size: 12px;";
    private static final String S_LABEL_RED   = "-fx-text-fill: #ff6b6b; -fx-font-size: 12px;";
    private static final String S_SECTION_HDR = "-fx-text-fill: #7c7c9a; -fx-font-size: 10px; "
                                               + "-fx-font-weight: bold;";
    private static final String S_BTN_PRIMARY = "-fx-background-color: #00d4ff; "
                                               + "-fx-text-fill: #0d0d1a; "
                                               + "-fx-font-weight: bold; "
                                               + "-fx-background-radius: 6; "
                                               + "-fx-cursor: hand; "
                                               + "-fx-min-width: 100;";
    private static final String S_BTN_GHOST   = "-fx-background-color: rgba(255,255,255,0.07); "
                                               + "-fx-text-fill: #9090a8; "
                                               + "-fx-background-radius: 6; "
                                               + "-fx-cursor: hand; "
                                               + "-fx-min-width: 80;";
    private static final String S_BTN_WARN    = "-fx-background-color: #ffd43b; "
                                               + "-fx-text-fill: #1a1a00; "
                                               + "-fx-font-size: 11px; "
                                               + "-fx-background-radius: 5; "
                                               + "-fx-cursor: hand;";
    private static final String S_COMBO       = "-fx-background-color: rgba(255,255,255,0.06); "
                                               + "-fx-text-fill: #c8c8d8; "
                                               + "-fx-background-radius: 6; "
                                               + "-fx-border-color: rgba(255,255,255,0.1); "
                                               + "-fx-border-radius: 6;";
    private static final String S_SPINNER     = S_COMBO;
    private static final String S_PROGRESS    = "-fx-accent: #00d4ff; "
                                               + "-fx-background-color: rgba(255,255,255,0.06); "
                                               + "-fx-background-radius: 4;";

    private static final long DEFAULT_CHUNK_DURATION_MS = 60_000;

    // ── State ────────────────────────────────────────────────────────────────
    private final Stage owner;
    private final Runnable onClose;
    private final ChunkedSubtitleGenerator generator;
    private final File mediaFile;
    private final long totalDurationMs;
    private final long currentTimeMs;
    private final Consumer<DsrtFile> onFirstChunkReady;
    private final Consumer<DsrtFile> onComplete;
    private final Consumer<Boolean>  onGenerationStateChanged;

    // ── UI Controls ──────────────────────────────────────────────────────────
    private ComboBox<WhisperModel>    modelSelector;
    private ComboBox<WhisperLanguage> languageSelector;
    private ComboBox<WhisperQuality>  qualitySelector;
    private CheckBox  translateCheckBox;
    private CheckBox  soundEventsCheckBox;
    private CheckBox  useVadCheckBox;
    private CheckBox  cacheCheckBox;
    private Spinner<Integer> chunkDurationSpinner;
    private Spinner<Integer> maxCpuSpinner;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label stageLabel;
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

    // Orchestration
    private ComboBox<String> orchestrationModeSelector;
    private ComboBox<String> providerSelector;
    private TextField providerUrlField;
    private TextField ollamaModelField;
    private CheckBox  keepOriginalCheckBox;
    private Label     orchestrationStatusLabel;

    public DsrtGenerationDialog(Stage owner, File mediaFile, long totalDurationMs,
                                long currentTimeMs,
                                Consumer<DsrtFile> onFirstChunkReady,
                                Consumer<DsrtFile> onComplete,
                                Consumer<Boolean>  onGenerationStateChanged,
                                Runnable onClose) {
        this.owner                   = owner;
        this.onClose                 = onClose;
        this.generator               = new ChunkedSubtitleGenerator();
        this.mediaFile               = mediaFile;
        this.totalDurationMs         = totalDurationMs;
        this.currentTimeMs           = currentTimeMs;
        this.onFirstChunkReady       = onFirstChunkReady;
        this.onComplete              = onComplete;
        this.onGenerationStateChanged = onGenerationStateChanged;

        setStyle(S_BG);
        setPrefWidth(360);
        setMaxWidth(420);
        buildContent();
        checkToolAvailability();
    }

    public void dispose() {
        if (currentDownloadTask != null && currentDownloadTask.isRunning()) currentDownloadTask.cancel();
        if (currentTask        != null && currentTask.isRunning())        currentTask.cancel();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Build UI
    // ═══════════════════════════════════════════════════════════════════════

    private void buildContent() {
        getChildren().addAll(
            buildHeader(),
            new Separator(),
            buildScrollBody(),
            buildButtonBar()
        );
    }

    // ── Header ───────────────────────────────────────────────────────────────
    private HBox buildHeader() {
        Label icon  = new Label("✦");
        icon.setStyle("-fx-text-fill: #00d4ff; -fx-font-size: 14px;");
        Label title = new Label("AI Subtitle Generator");
        title.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 14px; -fx-font-weight: bold;");
        HBox left = new HBox(8, icon, title);
        left.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(left, Priority.ALWAYS);

        // Animated status dot
        Circle dot = new Circle(4, Color.web("#00d4ff"));
        Label dotLabel = new Label("", dot);
        FadeTransition pulse = new FadeTransition(Duration.millis(1200), dot);
        pulse.setFromValue(1.0); pulse.setToValue(0.3);
        pulse.setAutoReverse(true); pulse.setCycleCount(FadeTransition.INDEFINITE);
        pulse.play();

        Button closeBtn = new Button("✕");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #606070; "
                        + "-fx-font-size: 13px; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> { if (onClose != null) onClose.run(); });

        HBox header = new HBox(8, left, dotLabel, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 12, 10, 14));
        header.setStyle(S_HEADER_BG);
        return header;
    }

    // ── Scrollable body ───────────────────────────────────────────────────────
    private ScrollPane buildScrollBody() {
        VBox body = new VBox(12);
        body.setPadding(new Insets(14));
        body.getChildren().addAll(
            buildFileInfoCard(),
            buildModelCard(),
            buildAccessibilityCard(),
            buildOrchestrationPane(),
            buildProgressCard(),
            buildAdvancedPane(),
            buildToolStatusPane()
        );

        ScrollPane sp = new ScrollPane(body);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setPannable(true);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(sp, Priority.ALWAYS);
        return sp;
    }

    // ── File Info Card ────────────────────────────────────────────────────────
    private VBox buildFileInfoCard() {
        Label sectionHdr = sectionHeader("MEDIA");
        String fileName  = mediaFile != null ? mediaFile.getName() : "No file";
        Label fileLabel  = new Label("⬡  " + fileName);
        fileLabel.setStyle("-fx-text-fill: #e0e0f0; -fx-font-size: 12px;");
        fileLabel.setWrapText(true);

        int totalChunks = calcTotalChunks(DEFAULT_CHUNK_DURATION_MS);
        chunkSummaryLabel = new Label(formatChunkSummary(totalChunks, (int)(DEFAULT_CHUNK_DURATION_MS / 1000)));
        chunkSummaryLabel.setStyle(S_LABEL_DIM);

        VBox card = card(sectionHdr, fileLabel, chunkSummaryLabel);
        return card;
    }

    // ── Model & Quality Card ──────────────────────────────────────────────────
    private VBox buildModelCard() {
        Label sectionHdr = sectionHeader("TRANSCRIPTION");

        // Model row
        modelSelector = styledCombo(WhisperModel.values());
        modelSelector.setValue(getBestAvailableModel());
        downloadBtn = new Button("Download");
        downloadBtn.setStyle(S_BTN_WARN);
        downloadBtn.setVisible(false);
        downloadBtn.setOnAction(e -> startModelDownload());
        HBox modelRow = labeledRow("Model", new HBox(8, modelSelector, downloadBtn));
        HBox.setHgrow(modelSelector, Priority.ALWAYS);

        modelDesc = new Label();
        modelDesc.setStyle(S_LABEL_DIM);
        modelDesc.setWrapText(true);
        modelSelector.setOnAction(e -> updateModelDescription());

        // Language row
        languageSelector = styledCombo(WhisperLanguage.values());
        languageSelector.setValue(WhisperLanguage.AUTO);
        HBox langRow = labeledRow("Language", languageSelector);

        // Translate
        translateCheckBox = styledCheck("Translate to English");

        // Quality row
        qualitySelector = styledCombo(WhisperQuality.values());
        qualitySelector.setValue(WhisperQuality.BALANCED);
        HBox qualityRow = labeledRow("Quality", qualitySelector);

        Label qualityDesc = new Label(WhisperQuality.BALANCED.description());
        qualityDesc.setStyle(S_LABEL_DIM);
        qualityDesc.setWrapText(true);
        qualitySelector.setOnAction(e -> {
            WhisperQuality q = qualitySelector.getValue();
            if (q != null) qualityDesc.setText(q.description());
        });

        // Chunk duration row
        chunkDurationSpinner = new Spinner<>(15, 120, 60, 15);
        chunkDurationSpinner.setEditable(true);
        chunkDurationSpinner.setMaxWidth(Double.MAX_VALUE);
        chunkDurationSpinner.setStyle(S_SPINNER);
        chunkDurationSpinner.getEditor().setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().matches("\\d*") ? change : null));
        chunkDurationSpinner.focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                try {
                    int v = Math.max(15, Math.min(120,
                        Integer.parseInt(chunkDurationSpinner.getEditor().getText())));
                    chunkDurationSpinner.getValueFactory().setValue(v);
                } catch (NumberFormatException ex) {
                    chunkDurationSpinner.getValueFactory().setValue(60);
                }
            }
        });
        chunkDurationSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null && n > 0) {
                int chunks = calcTotalChunks(n * 1000L);
                chunkSummaryLabel.setText(formatChunkSummary(chunks, n));
                rebuildChunkGrid(chunks);
            }
        });
        HBox chunkRow = labeledRow("Chunk (s)", chunkDurationSpinner);

        // Auto-upgrade logic
        Label autoUpgradeLabel = new Label("");
        autoUpgradeLabel.setStyle(S_LABEL_CYAN);
        autoUpgradeLabel.setWrapText(true);
        Runnable autoUpgrade = buildAutoUpgradeLogic(autoUpgradeLabel, qualityDesc);
        languageSelector.setOnAction(e -> autoUpgrade.run());
        translateCheckBox.setOnAction(e -> autoUpgrade.run());

        updateModelDescription();

        return card(sectionHdr, modelRow, modelDesc, langRow,
                    translateCheckBox, qualityRow, qualityDesc, chunkRow, autoUpgradeLabel);
    }

    // ── Accessibility Card (new!) ─────────────────────────────────────────────
    private VBox buildAccessibilityCard() {
        Label sectionHdr = sectionHeader("ACCESSIBILITY & PERFORMANCE");

        // Sound events toggle
        soundEventsCheckBox = styledCheck("Include sound event captions");
        soundEventsCheckBox.setSelected(false);
        Label sedInfo = new Label(
            "Adds non-speech captions like (Applause), (Door slams), (Music playing)\n"
            + "— like Netflix accessibility subtitles. Requires: pip install tensorflow tensorflow-hub soundfile");
        sedInfo.setStyle(S_LABEL_DIM);
        sedInfo.setWrapText(true);

        // VAD toggle
        useVadCheckBox = styledCheck("Skip silence (VAD)");
        useVadCheckBox.setSelected(true);
        Label vadInfo = new Label(
            "Silero VAD filters out silent regions before transcription — ~40% faster.\n"
            + "Requires: pip install torch torchaudio");
        vadInfo.setStyle(S_LABEL_DIM);
        vadInfo.setWrapText(true);

        // Cache toggle
        cacheCheckBox = styledCheck("Cache results (skip re-processing)");
        cacheCheckBox.setSelected(true);
        Label cacheInfo = new Label(
            "Identical chunks (same video + timecode + settings) are returned instantly from cache.");
        cacheInfo.setStyle(S_LABEL_DIM);
        cacheInfo.setWrapText(true);

        // CPU throttle
        maxCpuSpinner = new Spinner<>(10, 100, 80, 5);
        maxCpuSpinner.setEditable(true);
        maxCpuSpinner.setMaxWidth(Double.MAX_VALUE);
        maxCpuSpinner.setStyle(S_SPINNER);
        HBox cpuRow = labeledRow("Max CPU %", maxCpuSpinner);
        Label cpuInfo = new Label(
            "Throttle AI processing to avoid overheating — 80% is a safe default.");
        cpuInfo.setStyle(S_LABEL_DIM);
        cpuInfo.setWrapText(true);

        return card(sectionHdr,
            soundEventsCheckBox, sedInfo,
            new Separator(),
            useVadCheckBox, vadInfo,
            new Separator(),
            cacheCheckBox, cacheInfo,
            new Separator(),
            cpuRow, cpuInfo);
    }

    // ── Progress Card ─────────────────────────────────────────────────────────
    private VBox buildProgressCard() {
        Label sectionHdr = sectionHeader("PROGRESS");

        int totalChunks = calcTotalChunks(DEFAULT_CHUNK_DURATION_MS);
        chunkGrid = new FlowPane(4, 4);
        chunkGrid.setPrefWrapLength(380);
        chunkIndicators = new ArrayList<>();
        buildChunkIndicators(totalChunks);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setStyle(S_PROGRESS);
        progressBar.setMinHeight(6);

        stageLabel  = new Label("");
        stageLabel.setStyle(S_LABEL_CYAN);
        stageLabel.setWrapText(true);

        statusLabel = new Label("");
        statusLabel.setStyle(S_LABEL);
        statusLabel.setWrapText(true);

        // Legend
        HBox legend = new HBox(12,
            legendDot("#555566", "Pending"),
            legendDot("#ffd43b", "Extracting"),
            legendDot("#00d4ff", "Transcribing"),
            legendDot("#69db7c", "Done"),
            legendDot("#ff6b6b", "Failed"));
        legend.setAlignment(Pos.CENTER_LEFT);

        return card(sectionHdr, legend, chunkGrid, progressBar, stageLabel, statusLabel);
    }

    // ── Orchestration (collapsible) ───────────────────────────────────────────
    private TitledPane buildOrchestrationPane() {
        TitledPane pane = new TitledPane(
            "Multi-Model Translation  (AI Orchestration)",
            buildOrchestrationContent());
        pane.setExpanded(false);
        pane.setStyle("-fx-text-fill: #9090a8; -fx-font-size: 12px;");
        return pane;
    }

    private VBox buildOrchestrationContent() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10));

        Label info = new Label(
            "Transcribes in the native language first, then translates using a dedicated model "
            + "for higher accuracy than Whisper's built-in translation.");
        info.setWrapText(true);
        info.setStyle(S_LABEL_DIM);

        orchestrationModeSelector = new ComboBox<>();
        orchestrationModeSelector.getItems().addAll(
            "Single Pass (Whisper only)",
            "Multi-Model (Whisper + Translation)",
            "Multi-Model + Verification");
        orchestrationModeSelector.setValue("Single Pass (Whisper only)");
        orchestrationModeSelector.setMaxWidth(Double.MAX_VALUE);
        orchestrationModeSelector.setStyle(S_COMBO);
        HBox modeRow = labeledRow("Mode", orchestrationModeSelector);

        providerSelector = new ComboBox<>();
        providerSelector.getItems().addAll(
            "Argos Translate (Offline)", "LibreTranslate", "Ollama (LLM)");
        providerSelector.setValue("Argos Translate (Offline)");
        providerSelector.setMaxWidth(Double.MAX_VALUE);
        providerSelector.setStyle(S_COMBO);

        providerUrlField  = new TextField();
        providerUrlField.setPromptText("http://localhost:5000");
        providerUrlField.setStyle(S_COMBO);

        ollamaModelField = new TextField("llama3");
        ollamaModelField.setPromptText("llama3, qwen2, mistral…");
        ollamaModelField.setStyle(S_COMBO);

        keepOriginalCheckBox = styledCheck("Show original text alongside translation");
        keepOriginalCheckBox.setSelected(true);

        orchestrationStatusLabel = new Label("");
        orchestrationStatusLabel.setStyle(S_LABEL_DIM);
        orchestrationStatusLabel.setWrapText(true);

        Button checkBtn = new Button("Check Availability");
        checkBtn.setStyle(S_BTN_GHOST);
        checkBtn.setOnAction(e -> checkOrchestrationAvailability());

        VBox providerControls = new VBox(6);

        // Hide provider fields for Argos (offline)
        providerSelector.setOnAction(e -> {
            String p = providerSelector.getValue();
            boolean isOllama = "Ollama (LLM)".equals(p);
            boolean isArgos  = p != null && p.startsWith("Argos");
            setManagedVisible(providerUrlField,  !isArgos);
            setManagedVisible(ollamaModelField,  isOllama);
            providerUrlField.setText(isArgos ? "" : (isOllama ? "http://localhost:11434" : "http://localhost:5000"));
            orchestrationStatusLabel.setText("");
        });

        orchestrationModeSelector.setOnAction(e -> {
            boolean multi = !orchestrationModeSelector.getValue().startsWith("Single");
            setManagedVisible(providerControls, multi);
            translateCheckBox.setSelected(multi);
            translateCheckBox.setDisable(multi);
        });

        GridPane provGrid = new GridPane();
        provGrid.setHgap(8); provGrid.setVgap(6);
        provGrid.add(label("Provider"), 0, 0); provGrid.add(providerSelector,  1, 0);
        provGrid.add(label("URL"),      0, 1); provGrid.add(providerUrlField,  1, 1);
        provGrid.add(label("LLM"),      0, 2); provGrid.add(ollamaModelField,  1, 2);
        GridPane.setHgrow(providerSelector, Priority.ALWAYS);
        GridPane.setHgrow(providerUrlField, Priority.ALWAYS);
        GridPane.setHgrow(ollamaModelField, Priority.ALWAYS);
        ColumnConstraints lc = new ColumnConstraints(); lc.setMinWidth(70);
        ColumnConstraints fc = new ColumnConstraints(); fc.setHgrow(Priority.ALWAYS);
        provGrid.getColumnConstraints().addAll(lc, fc);
        setManagedVisible(providerUrlField, false);
        setManagedVisible(ollamaModelField, false);

        providerControls.getChildren().addAll(
            provGrid,
            new Label("Target: English") {{ setStyle(S_LABEL_CYAN); }},
            keepOriginalCheckBox,
            new HBox(8, checkBtn, orchestrationStatusLabel));
        setManagedVisible(providerControls, false);

        box.getChildren().addAll(info, modeRow, providerControls);
        return box;
    }

    // ── Advanced panel (collapsible) ──────────────────────────────────────────
    private TitledPane buildAdvancedPane() {
        TitledPane pane = new TitledPane("Tool Paths  (Advanced)", buildPathConfig());
        pane.setExpanded(false);
        pane.setStyle("-fx-text-fill: #9090a8; -fx-font-size: 12px;");
        return pane;
    }

    // ── Tool status (collapsible) ─────────────────────────────────────────────
    private TitledPane buildToolStatusPane() {
        toolStatusLabel = new Label("Checking tools…");
        toolStatusLabel.setStyle(S_LABEL_DIM);
        toolStatusLabel.setWrapText(true);
        TitledPane pane = new TitledPane("Tool Status", toolStatusLabel);
        pane.setExpanded(false);
        pane.setStyle("-fx-text-fill: #9090a8; -fx-font-size: 12px;");
        return pane;
    }

    // ── Button bar ────────────────────────────────────────────────────────────
    private HBox buildButtonBar() {
        generateBtn = new Button("✦  Generate");
        generateBtn.setStyle(S_BTN_PRIMARY);
        generateBtn.setOnAction(e -> startGeneration());

        cancelBtn = new Button("Close");
        cancelBtn.setStyle(S_BTN_GHOST);
        cancelBtn.setOnAction(e -> {
            if (currentDownloadTask != null && currentDownloadTask.isRunning()) {
                currentDownloadTask.cancel(); return;
            }
            if (currentTask != null && currentTask.isRunning()) {
                currentTask.cancel(); return;
            }
            if (onClose != null) onClose.run();
        });

        HBox bar = new HBox(10, generateBtn, cancelBtn);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.setStyle(S_HEADER_BG);
        return bar;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Chunk grid helpers
    // ═══════════════════════════════════════════════════════════════════════

    private void buildChunkIndicators(int totalChunks) {
        chunkGrid.getChildren().clear();
        chunkIndicators.clear();
        for (int i = 0; i < totalChunks; i++) {
            Region r = new Region();
            r.setMinSize(14, 14); r.setMaxSize(14, 14);
            r.setStyle("-fx-background-color: #555566; -fx-background-radius: 4;");
            Tooltip.install(r, new Tooltip("Chunk " + (i + 1) + ": Pending"));
            chunkIndicators.add(r);
            chunkGrid.getChildren().add(r);
        }
    }

    private void rebuildChunkGrid(int totalChunks) {
        buildChunkIndicators(totalChunks);
    }

    private void onChunkProgress(ChunkProgressEvent event) {
        if (event.chunkIndex() < 0 || event.chunkIndex() >= chunkIndicators.size()) return;
        Region r = chunkIndicators.get(event.chunkIndex());
        String color = switch (event.status()) {
            case PENDING      -> "#555566";
            case EXTRACTING   -> "#ffd43b";
            case TRANSCRIBING -> "#00d4ff";
            case COMPLETED    -> "#69db7c";
            case FAILED       -> "#ff6b6b";
        };
        r.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 4;");
        String stageName = switch (event.status()) {
            case PENDING      -> "Pending";
            case EXTRACTING   -> "Extracting audio…";
            case TRANSCRIBING -> "Transcribing speech…";
            case COMPLETED    -> "Done";
            case FAILED       -> "Failed";
        };
        Tooltip.install(r, new Tooltip("Chunk " + (event.chunkIndex() + 1) + " — " + stageName));

        // Update stage label for the active chunk
        if (event.status() == ChunkStatus.EXTRACTING || event.status() == ChunkStatus.TRANSCRIBING) {
            stageLabel.setText("Chunk " + (event.chunkIndex() + 1) + " → " + stageName);
        } else if (event.status() == ChunkStatus.COMPLETED) {
            long done = chunkIndicators.stream()
                .filter(ind -> ind.getStyle().contains("69db7c")).count() + 1;
            stageLabel.setText(done + " / " + chunkIndicators.size() + " chunks done");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Generation logic
    // ═══════════════════════════════════════════════════════════════════════

    private void startGeneration() {
        if (mediaFile == null || totalDurationMs <= 0) {
            setStatus("No media file loaded.", "red"); return;
        }
        WhisperModel model = modelSelector.getValue();
        if (!generator.getWhisperEngine().isModelAvailable(model)) {
            java.nio.file.Path dir = generator.getWhisperEngine().getModelsDirectory();
            if (dir == null) { setStatus("Models directory not set.", "red"); return; }
            lockControls(true);
            runModelDownload(model, dir, this::doStartGeneration);
            return;
        }
        doStartGeneration();
    }

    private void doStartGeneration() {
        WhisperModel    model          = modelSelector.getValue();
        WhisperLanguage lang           = languageSelector.getValue();
        boolean         translate      = translateCheckBox.isSelected();
        WhisperQuality  quality        = qualitySelector.getValue();
        long            chunkMs        = chunkDurationSpinner.getValue() * 1000L;

        // Argos needs explicit source language
        boolean usingArgosMulti = orchestrationModeSelector != null
            && !orchestrationModeSelector.getValue().startsWith("Single")
            && providerSelector.getValue() != null
            && providerSelector.getValue().startsWith("Argos");
        if (usingArgosMulti && lang == WhisperLanguage.AUTO) {
            setStatus("Set a specific language when using Argos Translate.", "red"); return;
        }

        OrchestrationConfig oc = buildOrchestrationConfig();
        if (oc != null && oc.isMultiModel()) {
            translate = false;
            generator.setOrchestrationConfig(oc);
        } else {
            generator.setOrchestrationConfig(null);
        }

        lockControls(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        stageLabel.setText("Starting…");
        if (onGenerationStateChanged != null) onGenerationStateChanged.accept(true);
        cancelBtn.setText("Cancel");
        for (Region r : chunkIndicators)
            r.setStyle("-fx-background-color: #555566; -fx-background-radius: 4;");

        currentTask = new ChunkedTranscriptionTask(
            generator, mediaFile, totalDurationMs, chunkMs,
            model, lang, translate, quality, currentTimeMs,
            dsrt -> { if (onFirstChunkReady != null) onFirstChunkReady.accept(dsrt); },
            this::onChunkProgress,
            () -> {
                resetControls();
                DsrtFile result = currentTask.getValue();
                if (result != null) {
                    stageLabel.setText("\u2713  Complete");
                    stageLabel.setStyle(S_LABEL_GREEN);
                    setStatus(result.getCueCount() + " cues generated", "green");
                    if (onComplete != null) onComplete.accept(result);
                }
            });

        progressBar.progressProperty().bind(currentTask.progressProperty());
        statusLabel.textProperty().bind(currentTask.messageProperty());
        statusLabel.setStyle(S_LABEL);

        currentTask.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            stageLabel.setText("Background processing…");
            stageLabel.setStyle(S_LABEL_CYAN);
            setStatus("First chunk ready — generating remaining chunks in background", "cyan");
        });
        currentTask.setOnFailed(e -> {
            resetControls();
            String msg = currentTask.getException() != null
                ? currentTask.getException().getMessage() : "Unknown error";
            stageLabel.setText("\u2717  Failed");
            stageLabel.setStyle("-fx-text-fill: #ff6b6b;");
            setStatus("Error: " + msg, "red");
        });
        currentTask.setOnCancelled(e -> {
            resetControls();
            stageLabel.setText("Cancelled");
            stageLabel.setStyle(S_LABEL_AMBER);
            setStatus("Cancelled — completed chunks are saved", "amber");
        });

        Thread t = new Thread(currentTask, "dsrt-generation");
        t.setDaemon(true); t.start();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Model download
    // ═══════════════════════════════════════════════════════════════════════

    private void runModelDownload(WhisperModel model, java.nio.file.Path dir, Runnable onSuccess) {
        currentDownloadTask = new ModelDownloadTask(model, dir);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        progressBar.progressProperty().bind(currentDownloadTask.progressProperty());
        statusLabel.textProperty().bind(currentDownloadTask.messageProperty());
        statusLabel.setStyle(S_LABEL_CYAN);
        stageLabel.setText("Downloading " + model.modelName() + "…");
        cancelBtn.setText("Cancel");

        currentDownloadTask.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            progressBar.setVisible(false);
            updateModelDescription();
            currentDownloadTask = null;
            onSuccess.run();
        });
        currentDownloadTask.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            progressBar.setVisible(false);
            resetControls();
            setStatus("Download failed: " + (currentDownloadTask.getException() != null
                ? currentDownloadTask.getException().getMessage() : "?"), "red");
            currentDownloadTask = null;
        });
        currentDownloadTask.setOnCancelled(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            progressBar.setVisible(false);
            resetControls();
            setStatus("Download cancelled", "amber");
            currentDownloadTask = null;
        });

        Thread t = new Thread(currentDownloadTask, "model-download");
        t.setDaemon(true); t.start();
    }

    private void startModelDownload() {
        WhisperModel model = modelSelector.getValue();
        if (model == null || generator.getWhisperEngine().isModelAvailable(model)) return;
        java.nio.file.Path dir = generator.getWhisperEngine().getModelsDirectory();
        if (dir == null) { setStatus("Models directory not set.", "red"); return; }
        lockControls(true);
        runModelDownload(model, dir, () -> {
            lockControls(false); updateModelDescription();
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tool availability
    // ═══════════════════════════════════════════════════════════════════════

    private void checkToolAvailability() {
        SubtitleGenerator.AvailabilityStatus status = generator.checkAvailability();
        toolStatusLabel.setText(status.getSummary());
        if (status.isReady()) {
            toolStatusLabel.setStyle(S_LABEL_GREEN);
            generateBtn.setDisable(false);
        } else {
            toolStatusLabel.setStyle(S_LABEL_RED);
            generateBtn.setDisable(true);
            toolStatusLabel.setText(status.getSummary()
                + "\n\nRequired:\n"
                + "• FFmpeg  → https://ffmpeg.org/download.html\n"
                + "• Whisper → https://github.com/ggerganov/whisper.cpp/releases");
        }
        updateModelDescription();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Orchestration helpers
    // ═══════════════════════════════════════════════════════════════════════

    private void checkOrchestrationAvailability() {
        TranslationProvider p = buildTranslationProvider();
        if (p == null) { orchestrationStatusLabel.setText("No provider configured."); return; }
        orchestrationStatusLabel.setStyle(S_LABEL_CYAN);
        orchestrationStatusLabel.setText("Checking…");
        Thread t = new Thread(() -> {
            boolean ok = p.isAvailable();
            String  s  = p.getAvailabilityStatus();
            javafx.application.Platform.runLater(() -> {
                orchestrationStatusLabel.setStyle(ok ? S_LABEL_GREEN : S_LABEL_RED);
                orchestrationStatusLabel.setText(s);
            });
        }, "orch-check");
        t.setDaemon(true); t.start();
    }

    private TranslationProvider buildTranslationProvider() {
        String p   = providerSelector.getValue();
        String url = providerUrlField.getText().trim();
        if (p == null) return null;
        if (p.startsWith("Argos"))        return new ArgosTranslateProvider();
        if ("Ollama (LLM)".equals(p))     return new OllamaTranslationProvider(
            url.isEmpty() ? "http://localhost:11434" : url,
            ollamaModelField.getText().isBlank() ? "llama3" : ollamaModelField.getText().trim());
        return new LibreTranslateProvider(url.isEmpty() ? "http://localhost:5000" : url, null);
    }

    private OrchestrationConfig buildOrchestrationConfig() {
        String mode = orchestrationModeSelector.getValue();
        if (mode == null || mode.startsWith("Single")) return null;
        OrchestrationConfig cfg = new OrchestrationConfig();
        TranslationProvider prov = buildTranslationProvider();
        cfg.setMode(mode.contains("Verification")
            ? OrchestrationConfig.Mode.MULTI_MODEL_VERIFIED
            : OrchestrationConfig.Mode.MULTI_MODEL);
        if (mode.contains("Verification")) cfg.setVerificationProvider(prov);
        cfg.setTranslationProvider(prov);
        boolean forceKeep = languageSelector.getValue() == WhisperLanguage.JAPANESE;
        cfg.setKeepOriginalText(forceKeep || keepOriginalCheckBox.isSelected());
        cfg.setTargetLanguage("en");
        return cfg;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Path config panel
    // ═══════════════════════════════════════════════════════════════════════

    private VBox buildPathConfig() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));

        TextField ffField = new TextField(
            generator.getAudioExtractor().getToolPath() != null
                ? generator.getAudioExtractor().getToolPath() : "");
        ffField.setPromptText("Path to ffmpeg");
        ffField.setStyle(S_COMBO);

        TextField whField = new TextField(
            generator.getWhisperEngine().getBinaryPath() != null
                ? generator.getWhisperEngine().getBinaryPath() : "");
        whField.setPromptText("Path to whisper binary");
        whField.setStyle(S_COMBO);

        TextField mdField = new TextField(
            generator.getWhisperEngine().getModelsDirectory() != null
                ? generator.getWhisperEngine().getModelsDirectory().toString() : "");
        mdField.setPromptText("Path to models directory");
        mdField.setStyle(S_COMBO);

        Button apply = new Button("Apply");
        apply.setStyle(S_BTN_GHOST);
        apply.setOnAction(e -> {
            if (!ffField.getText().isBlank())
                generator.getAudioExtractor().setFfmpegPath(java.nio.file.Path.of(ffField.getText()));
            if (!whField.getText().isBlank())
                generator.getWhisperEngine().setWhisperBinaryPath(java.nio.file.Path.of(whField.getText()));
            if (!mdField.getText().isBlank())
                generator.getWhisperEngine().setModelsDirectory(java.nio.file.Path.of(mdField.getText()));
            checkToolAvailability();
        });

        box.getChildren().addAll(
            labeledRow("FFmpeg",  ffField),
            labeledRow("Whisper", whField),
            labeledRow("Models",  mdField),
            apply);
        return box;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Auto-upgrade logic
    // ═══════════════════════════════════════════════════════════════════════

    private Runnable buildAutoUpgradeLogic(Label upgradeLabel, Label qualityDesc) {
        return () -> {
            WhisperLanguage lang = languageSelector.getValue();
            boolean nonEn  = lang != null && lang != WhisperLanguage.AUTO && lang != WhisperLanguage.ENGLISH;
            boolean transl = translateCheckBox.isSelected();
            if (nonEn || transl) {
                WhisperModel cur = modelSelector.getValue();
                WhisperModel rec = getBestAvailableModelAtLeast(WhisperModel.SMALL);
                if (cur != null && cur.ordinal() < rec.ordinal()) {
                    modelSelector.setValue(rec);
                    updateModelDescription();
                }
                if (qualitySelector.getValue() == WhisperQuality.FAST
                        || qualitySelector.getValue() == WhisperQuality.INSTANT) {
                    qualitySelector.setValue(WhisperQuality.BALANCED);
                    qualityDesc.setText(WhisperQuality.BALANCED.description());
                }
                upgradeLabel.setText("⬆  Auto-upgraded to "
                    + modelSelector.getValue().modelName().toUpperCase()
                    + " + BALANCED for non-English accuracy");
            } else {
                upgradeLabel.setText("");
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Model helpers
    // ═══════════════════════════════════════════════════════════════════════

    private WhisperModel getBestAvailableModel() {
        WhisperEngine e = generator.getWhisperEngine();
        WhisperModel[] all = WhisperModel.values();
        for (int i = all.length - 1; i >= 0; i--)
            if (e.isModelAvailable(all[i])) return all[i];
        return WhisperModel.BASE;
    }

    private WhisperModel getBestAvailableModelAtLeast(WhisperModel minimum) {
        WhisperEngine e = generator.getWhisperEngine();
        WhisperModel[] all = WhisperModel.values();
        for (int i = all.length - 1; i >= minimum.ordinal(); i--)
            if (e.isModelAvailable(all[i])) return all[i];
        return getBestAvailableModel();
    }

    private void updateModelDescription() {
        WhisperModel m = modelSelector.getValue();
        if (m == null) return;
        boolean avail = generator.getWhisperEngine().isModelAvailable(m);
        if (!avail) {
            modelDesc.setText(m.description() + "  [not downloaded]");
            modelDesc.setStyle(S_LABEL_RED);
            downloadBtn.setVisible(true);
            downloadBtn.setText("Download " + m.modelName() + " (" + m.sizeMb() + " MB)");
        } else {
            modelDesc.setText(m.description());
            modelDesc.setStyle(S_LABEL_DIM);
            downloadBtn.setVisible(false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Control locking
    // ═══════════════════════════════════════════════════════════════════════

    private void lockControls(boolean lock) {
        generateBtn.setDisable(lock);
        modelSelector.setDisable(lock);
        languageSelector.setDisable(lock);
        translateCheckBox.setDisable(lock);
        qualitySelector.setDisable(lock);
        chunkDurationSpinner.setDisable(lock);
        soundEventsCheckBox.setDisable(lock);
        useVadCheckBox.setDisable(lock);
        cacheCheckBox.setDisable(lock);
        maxCpuSpinner.setDisable(lock);
        downloadBtn.setDisable(lock);
    }

    private void resetControls() {
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        progressBar.setVisible(false);
        lockControls(false);
        cancelBtn.setText("Close");
        if (onGenerationStateChanged != null) onGenerationStateChanged.accept(false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI factory helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static VBox card(javafx.scene.Node... children) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: rgba(255,255,255,0.04); "
                   + "-fx-background-radius: 10; "
                   + "-fx-border-color: rgba(255,255,255,0.07); "
                   + "-fx-border-radius: 10; "
                   + "-fx-border-width: 1;");
        box.getChildren().addAll(children);
        return box;
    }

    private static Label sectionHeader(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #505068; -fx-font-size: 10px; -fx-font-weight: bold;");
        return l;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #9090a8; -fx-font-size: 12px;");
        return l;
    }

    private static HBox labeledRow(String labelText, javafx.scene.Node control) {
        Label lbl = label(labelText);
        lbl.setMinWidth(90);
        HBox row = new HBox(10, lbl, control);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(control, Priority.ALWAYS);
        return row;
    }

    private static <T> ComboBox<T> styledCombo(T[] items) {
        ComboBox<T> cb = new ComboBox<>();
        cb.getItems().addAll(items);
        cb.setMaxWidth(Double.MAX_VALUE);
        cb.setStyle("-fx-background-color: rgba(255,255,255,0.06); "
                  + "-fx-text-fill: #c8c8d8; "
                  + "-fx-background-radius: 6; "
                  + "-fx-border-color: rgba(255,255,255,0.1); "
                  + "-fx-border-radius: 6;");
        return cb;
    }

    private static CheckBox styledCheck(String text) {
        CheckBox cb = new CheckBox(text);
        cb.setStyle("-fx-text-fill: #c8c8d8; -fx-font-size: 12px;");
        return cb;
    }

    private static HBox legendDot(String color, String text) {
        Region dot = new Region();
        dot.setMinSize(10, 10); dot.setMaxSize(10, 10);
        dot.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 3;");
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: #606070; -fx-font-size: 10px;");
        HBox box = new HBox(4, dot, lbl);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void setStatus(String msg, String level) {
        statusLabel.setText(msg);
        statusLabel.setStyle(switch (level) {
            case "green" -> S_LABEL_GREEN;
            case "red"   -> S_LABEL_RED;
            case "amber" -> S_LABEL_AMBER;
            case "cyan"  -> S_LABEL_CYAN;
            default      -> S_LABEL;
        });
    }

    private static void setManagedVisible(javafx.scene.Node n, boolean v) {
        n.setVisible(v); n.setManaged(v);
    }

    private int calcTotalChunks(long chunkDurationMs) {
        return totalDurationMs > 0
            ? (int) Math.ceil((double) totalDurationMs / chunkDurationMs) : 0;
    }

    private String formatChunkSummary(int chunks, int chunkSec) {
        return String.format("%.1fs total  •  %d chunks × %ds",
            totalDurationMs / 1000.0, chunks, chunkSec);
    }
}
